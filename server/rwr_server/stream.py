"""Phone -> server frame stream over an authenticated WebSocket (wss).

Protocol (see ARCHITECTURE.md):
  phone -> server  text   {"type":"hello"|"settings", "settings":{...}}
  phone -> server  binary 16-byte header + JPEG
                          u8 version(=1), u8 rotation quarter turns (clockwise), u8 flags (bit0 mirror),
                          u8 reserved, u32 seq, i64 capture time (ms since epoch), all big-endian
  server -> phone  text   {"type":"ready"}, {"type":"alert", ...}, {"type":"stats", ...}, {"type":"error", ...}
"""
from __future__ import annotations

import asyncio
import concurrent.futures
import json
import logging
import struct
import time
from dataclasses import dataclass, field

import cv2
from starlette.websockets import WebSocket, WebSocketDisconnect, WebSocketState

from . import settings as server_settings
from .clips import Frame, FrameBuffer, encode_mp4
from .detector import DetectSettings, Detector, FaceResult, annotate, decode_frame
from .events import EventStore
from .policy import AlertGate, OwnerTracker

log = logging.getLogger("rwr.stream")

HEADER = struct.Struct(">BBBBIq")
MAX_FRAME_BYTES = 4 * 1024 * 1024
MAX_TEXT_BYTES = 16 * 1024
MAX_FPS = 40                       # frames above this rate are dropped
MAX_CLIP_SIDE_S = 30


@dataclass
class ClientSettings:
    mode: str = "head"
    ignore_owner: bool = True
    max_head_angle: float = 25.0
    max_gaze_angle: float = 12.0
    cooldown_sec: int = 15
    media: str = "photo"           # "photo" or "video"
    pre_sec: int = 10
    post_sec: int = 10
    save_immich: bool = False

    @staticmethod
    def parse(d: dict) -> "ClientSettings":
        """Validates and clamps what the phone sent; anything malformed falls back to defaults."""
        c = ClientSettings()

        def num(key, lo, hi, cast):
            v = d.get(key)
            if isinstance(v, (int, float)) and not isinstance(v, bool):
                setattr(c, key, cast(min(hi, max(lo, v))))

        if d.get("mode") in ("head", "eye"):
            c.mode = d["mode"]
        if d.get("media") in ("photo", "video"):
            c.media = d["media"]
        for key in ("ignore_owner", "save_immich"):
            if isinstance(d.get(key), bool):
                setattr(c, key, d[key])
        num("max_head_angle", 5, 60, float)
        num("max_gaze_angle", 3, 45, float)
        num("cooldown_sec", 3, 600, int)
        num("pre_sec", 0, MAX_CLIP_SIDE_S, int)
        num("post_sec", 0, MAX_CLIP_SIDE_S, int)
        return c


@dataclass
class _Clip:
    alert_ts: int
    end_ts: int
    frames: list[Frame]
    onlookers: int


@dataclass
class DeviceStream:
    device_id: int
    device_name: str
    ws: WebSocket
    settings: ClientSettings = field(default_factory=ClientSettings)
    buffer: FrameBuffer = field(default_factory=lambda: FrameBuffer(12))
    gate: AlertGate = field(default_factory=AlertGate)
    owner: OwnerTracker = field(default_factory=OwnerTracker)
    busy: bool = False
    pending: Frame | None = None
    clip: _Clip | None = None
    boxes_by_seq: dict[int, list[FaceResult]] = field(default_factory=dict)
    received: int = 0
    analysed: int = 0
    last_frame_at: float = 0.0
    started: float = field(default_factory=time.time)
    closed: bool = False


class StreamHub:
    def __init__(self, detector: Detector, events: EventStore, db, threads: int, max_streams: int, tmp_dir: str):
        self.detector = detector
        self.events = events
        self.db = db
        self.pool = concurrent.futures.ThreadPoolExecutor(max_workers=max(1, threads), thread_name_prefix="detect")
        self.max_streams = max_streams
        self.tmp_dir = tmp_dir
        self.active: dict[int, DeviceStream] = {}

    def status(self) -> list[dict]:
        now = time.time()
        return [{
            "device_id": s.device_id, "name": s.device_name, "mode": s.settings.mode,
            "fps_in": round(s.received / max(1.0, now - s.started), 1),
            "fps_analysed": round(s.analysed / max(1.0, now - s.started), 1),
        } for s in self.active.values()]

    async def disconnect_device(self, device_id: int) -> None:
        s = self.active.get(device_id)
        if s and s.ws.application_state == WebSocketState.CONNECTED:
            await s.ws.close(code=4403, reason="device revoked")

    async def serve(self, ws: WebSocket, device_id: int, device_name: str) -> None:
        if device_id not in self.active and len(self.active) >= self.max_streams:
            await ws.close(code=4429, reason="too many streams")
            return
        old = self.active.get(device_id)
        if old:
            old.closed = True
            try:
                await old.ws.close(code=4409, reason="replaced by a new connection")
            except RuntimeError:
                pass
        s = DeviceStream(device_id, device_name, ws)
        self.active[device_id] = s
        await ws.send_text(json.dumps({"type": "ready", "max_fps": MAX_FPS}))
        stats_task = asyncio.create_task(self._stats_loop(s))
        try:
            while not s.closed:
                msg = await ws.receive()
                if msg["type"] == "websocket.disconnect":
                    break
                if msg.get("bytes") is not None:
                    await self._on_frame(s, msg["bytes"])
                elif msg.get("text") is not None:
                    await self._on_text(s, msg["text"])
        except WebSocketDisconnect:
            pass
        finally:
            s.closed = True
            stats_task.cancel()
            if self.active.get(device_id) is s:
                del self.active[device_id]
            if s.clip:
                self._finish_clip(s)

    async def _on_text(self, s: DeviceStream, text: str) -> None:
        if len(text) > MAX_TEXT_BYTES:
            await s.ws.close(code=1009, reason="message too big")
            return
        try:
            msg = json.loads(text)
        except ValueError:
            return
        if not isinstance(msg, dict):
            return
        if msg.get("type") in ("hello", "settings") and isinstance(msg.get("settings"), dict):
            s.settings = ClientSettings.parse(msg["settings"])
            s.buffer.seconds = s.settings.pre_sec + 2
            log.info("device %s settings: %s", s.device_id, s.settings)

    async def _on_frame(self, s: DeviceStream, data: bytes) -> None:
        if len(data) > MAX_FRAME_BYTES or len(data) <= HEADER.size:
            await s.ws.close(code=1009, reason="bad frame size")
            return
        version, rot, flags, _, seq, ts = HEADER.unpack_from(data)
        if version != 1:
            await s.ws.close(code=1003, reason="unsupported frame version")
            return
        now = time.time()
        if now - s.last_frame_at < 1.0 / MAX_FPS:
            return  # too fast: drop
        s.last_frame_at = now
        s.received += 1
        frame = Frame(seq, ts, bytes(data[HEADER.size:]), rot & 3, bool(flags & 1))
        s.buffer.add(frame)
        if s.clip:
            s.clip.frames.append(frame)
            if ts >= s.clip.end_ts:
                self._finish_clip(s)
        if s.busy:
            s.pending = frame  # analyse the newest frame next; older pending ones are skipped
        else:
            s.busy = True
            asyncio.create_task(self._analyse_loop(s, frame))

    async def _analyse_loop(self, s: DeviceStream, frame: Frame) -> None:
        loop = asyncio.get_running_loop()
        try:
            while frame is not None and not s.closed:
                cs = s.settings
                srv = server_settings.load(self.db)
                det = DetectSettings(cs.mode, cs.max_head_angle, cs.max_gaze_angle)
                result = await loop.run_in_executor(self.pool, self._analyse, s, frame, det, srv["min_face_px"])
                s.analysed += 1
                if result is not None:
                    await self._after_analysis(s, frame, *result)
                frame, s.pending = s.pending, None
        except Exception:  # never let one bad frame kill the stream
            log.exception("analysis failed for device %s", s.device_id)
        finally:
            s.busy = False

    def _analyse(self, s: DeviceStream, frame: Frame, det: DetectSettings, min_face_px: int):
        """Runs in the detector pool; frames of one stream are analysed one at a time (see _analyse_loop)."""
        img = decode_frame(frame.jpeg, frame.rotation, frame.mirror)
        if img is None:
            return None
        faces = self.detector.analyze(img, det)
        for f in faces:
            f.owner = False
        if s.settings.ignore_owner:
            i = s.owner.pick([f.box for f in faces], [f.yaw for f in faces], frame.ts_ms)
            if i is not None:
                faces[i].owner = True
        onlookers = [f for f in faces if f.looking and not f.owner and f.box[2] - f.box[0] >= min_face_px]
        return img, faces, onlookers

    async def _after_analysis(self, s: DeviceStream, frame: Frame, img, faces, onlookers) -> None:
        if onlookers:
            s.boxes_by_seq[frame.seq] = onlookers
            if len(s.boxes_by_seq) > 600:
                for k in sorted(s.boxes_by_seq)[:300]:
                    del s.boxes_by_seq[k]
        if s.clip is not None:
            return  # still recording the previous alert's clip
        if not s.gate.on_frame([f.box for f in onlookers], int(time.time() * 1000), s.settings.cooldown_sec * 1000):
            return
        h, w = img.shape[:2]
        log.info("device %s: onlooker alert (%d of %d faces, %s mode)", s.device_id, len(onlookers), len(faces),
                 s.settings.mode)
        await s.ws.send_text(json.dumps({
            "type": "alert", "seq": frame.seq, "ts": frame.ts_ms, "width": w, "height": h,
            "faces": [f.to_json() for f in onlookers],
        }))
        srv = server_settings.load(self.db)
        if not (s.settings.save_immich or srv["keep_events"]):
            return
        if s.settings.media == "video":
            pre = s.buffer.since(frame.ts_ms - s.settings.pre_sec * 1000)
            s.clip = _Clip(frame.ts_ms, frame.ts_ms + s.settings.post_sec * 1000, list(pre), len(onlookers))
            if s.settings.post_sec == 0:
                self._finish_clip(s)
        else:
            photo = annotate(img, onlookers)
            ok, jpg = cv2.imencode(".jpg", photo, [cv2.IMWRITE_JPEG_QUALITY, 92])
            if ok:
                self.events.record(device_id=s.device_id, at=frame.ts_ms / 1000, kind="photo", data=jpg.tobytes(),
                                   onlookers=len(onlookers), mode=s.settings.mode, source="server",
                                   want_immich=s.settings.save_immich)

    def _finish_clip(self, s: DeviceStream) -> None:
        clip, s.clip = s.clip, None
        if not clip or not clip.frames:
            return
        boxes = dict(s.boxes_by_seq)
        cs = s.settings
        self.pool.submit(self._encode_and_record, s.device_id, clip, boxes, cs)

    def _encode_and_record(self, device_id: int, clip: _Clip, boxes: dict[int, list[FaceResult]], cs) -> None:
        try:
            imgs, stamps, last_boxes, last_box_ts = [], [], [], -10**12
            for f in sorted(clip.frames, key=lambda f: f.ts_ms):
                img = decode_frame(f.jpeg, f.rotation, f.mirror)
                if img is None:
                    continue
                if f.seq in boxes:
                    last_boxes, last_box_ts = boxes[f.seq], f.ts_ms
                if last_boxes and f.ts_ms - last_box_ts < 700:  # keep boxes visible between analysed frames
                    img = annotate(img, last_boxes)
                imgs.append(img)
                stamps.append(f.ts_ms)
            mp4 = encode_mp4(imgs, stamps, self.tmp_dir)
            self.events.record(device_id=device_id, at=clip.alert_ts / 1000, kind="video", data=mp4,
                               onlookers=clip.onlookers, mode=cs.mode, source="server", want_immich=cs.save_immich)
            log.info("device %s: clip of %d frames recorded", device_id, len(imgs))
        except Exception:
            log.exception("clip encoding failed for device %s", device_id)

    async def _stats_loop(self, s: DeviceStream) -> None:
        while True:
            await asyncio.sleep(10)
            elapsed = max(1.0, time.time() - s.started)
            try:
                await s.ws.send_text(json.dumps({
                    "type": "stats", "fps_in": round(s.received / elapsed, 1),
                    "fps_analysed": round(s.analysed / elapsed, 1),
                }))
            except Exception:
                return
