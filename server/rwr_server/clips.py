"""Rolling frame buffer and MP4 clip encoding (H.264 via the static ffmpeg from imageio-ffmpeg)."""
from __future__ import annotations

import collections
import os
import subprocess
import tempfile
from dataclasses import dataclass

import cv2
import imageio_ffmpeg
import numpy as np

MAX_CLIP_FPS = 30


@dataclass
class Frame:
    seq: int
    ts_ms: int
    jpeg: bytes
    rotation: int      # quarter turns clockwise to make upright
    mirror: bool


class FrameBuffer:
    """Keeps the last [seconds] of frames, capped at [max_bytes] of JPEG data."""

    def __init__(self, seconds: float, max_bytes: int = 64 * 1024 * 1024):
        self.seconds = seconds
        self.max_bytes = max_bytes
        self._frames: collections.deque[Frame] = collections.deque()
        self._bytes = 0

    def add(self, f: Frame) -> None:
        self._frames.append(f)
        self._bytes += len(f.jpeg)
        horizon = f.ts_ms - self.seconds * 1000
        while self._frames and (self._frames[0].ts_ms < horizon or self._bytes > self.max_bytes):
            self._bytes -= len(self._frames.popleft().jpeg)

    def since(self, ts_ms: int) -> list[Frame]:
        return [f for f in self._frames if f.ts_ms >= ts_ms]

    def by_seq(self, seq: int) -> Frame | None:
        return next((f for f in reversed(self._frames) if f.seq == seq), None)


def resample(timestamps_ms: list[int], fps: int) -> list[int]:
    """Indices into the input for a constant-frame-rate output (repeats frames across gaps)."""
    if not timestamps_ms:
        return []
    start, end = timestamps_ms[0], timestamps_ms[-1]
    n = int((end - start) * fps / 1000) + 1
    out, j = [], 0
    for k in range(n):
        t = start + k * 1000 / fps
        while j + 1 < len(timestamps_ms) and timestamps_ms[j + 1] <= t:
            j += 1
        out.append(j)
    return out


def clip_fps(timestamps_ms: list[int]) -> int:
    if len(timestamps_ms) < 2:
        return 1
    span = (timestamps_ms[-1] - timestamps_ms[0]) / 1000
    return int(max(1, min(MAX_CLIP_FPS, round((len(timestamps_ms) - 1) / span)))) if span > 0 else 1


def encode_mp4(frames: list[np.ndarray], timestamps_ms: list[int], tmp_dir: str | None = None) -> bytes:
    """Encodes upright BGR frames to an H.264 MP4 (yuv420p, faststart) playable everywhere."""
    if not frames:
        raise ValueError("no frames")
    h, w = frames[0].shape[:2]
    w2, h2 = w - w % 2, h - h % 2
    fps = clip_fps(timestamps_ms)
    order = resample(timestamps_ms, fps)
    fd, path = tempfile.mkstemp(suffix=".mp4", dir=tmp_dir)
    os.close(fd)
    try:
        cmd = [
            imageio_ffmpeg.get_ffmpeg_exe(), "-hide_banner", "-loglevel", "error", "-y",
            "-f", "rawvideo", "-pix_fmt", "bgr24", "-s", f"{w2}x{h2}", "-r", str(fps), "-i", "-",
            "-an", "-c:v", "libx264", "-preset", "veryfast", "-crf", "23", "-pix_fmt", "yuv420p",
            "-movflags", "+faststart", path,
        ]
        proc = subprocess.Popen(cmd, stdin=subprocess.PIPE, stderr=subprocess.PIPE)
        try:
            for i in order:
                img = frames[i]
                if img.shape[1] != w2 or img.shape[0] != h2:
                    img = cv2.resize(img, (w2, h2))
                proc.stdin.write(np.ascontiguousarray(img).tobytes())
            proc.stdin.close()
        except BrokenPipeError:
            pass
        err = proc.stderr.read().decode(errors="replace")
        if proc.wait(timeout=120) != 0:
            raise RuntimeError(f"ffmpeg failed: {err[:300]}")
        with open(path, "rb") as f:
            return f.read()
    finally:
        os.unlink(path)
