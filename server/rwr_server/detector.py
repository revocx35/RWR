"""Server-side onlooker detection: YuNet finds faces (also small, distant ones), then MediaPipe Face
Landmarker runs on an upscaled crop of each face for head pose and iris gaze.

This is the "max level" path: every received frame is analysed, at full resolution, with a detector
that handles faces far smaller than the phone's on-device models.
"""
from __future__ import annotations

import math
import sys
import threading
import types
from dataclasses import dataclass, field

import cv2
import numpy as np

from . import gaze as gz
from .policy import landmarks_match

# mediapipe's package __init__ eagerly imports its audio and drawing helpers, which pull in PortAudio and
# matplotlib. The server uses neither, so they are not installed; empty stand-ins satisfy the imports.
for _name in ("sounddevice", "matplotlib", "matplotlib.pyplot"):
    sys.modules.setdefault(_name, types.ModuleType(_name))
import mediapipe as mp  # noqa: E402
from mediapipe.tasks.python import BaseOptions, vision  # noqa: E402

CROP_SIZE = 256
CROP_MARGIN = 1.6
# Below this face width (pixels in the frame) irises are a few pixels wide: use head pose instead.
# At or above it, a detection where Face Landmarker finds no face is dropped as a false detection.
MIN_EYE_FACE_PX = 40


@dataclass
class DetectSettings:
    mode: str = "head"            # "head" or "eye"
    max_head_angle: float = 25.0
    max_gaze_angle: float = 12.0


@dataclass
class FaceResult:
    box: tuple[int, int, int, int]   # left, top, right, bottom
    score: float
    yaw: float
    pitch: float
    gaze: gz.Gaze | None = None
    looking: bool = False
    owner: bool = False
    # False: Face Landmarker found no face (or another face) at this detection; never an onlooker.
    verified: bool = True
    rays: list[tuple[float, float, float, float]] = field(default_factory=list)

    @property
    def area(self) -> int:
        l, t, r, b = self.box
        return max(0, r - l) * max(0, b - t)

    def to_json(self) -> dict:
        d = {"box": list(self.box), "yaw": round(self.yaw, 1), "pitch": round(self.pitch, 1)}
        if self.gaze:
            d["gaze"] = [round(self.gaze.yaw, 1), round(self.gaze.pitch, 1)]
            d["eyes_open"] = bool(self.gaze.eyes_open)
            d["rays"] = [[round(v, 1) for v in r] for r in self.rays]
        return d


def decode_frame(jpeg: bytes, rotation_quarters: int, mirror: bool) -> np.ndarray | None:
    """Decodes a phone frame and makes it upright (clockwise rotation) and selfie-mirrored."""
    img = cv2.imdecode(np.frombuffer(jpeg, np.uint8), cv2.IMREAD_COLOR)
    if img is None:
        return None
    rot = {1: cv2.ROTATE_90_CLOCKWISE, 2: cv2.ROTATE_180, 3: cv2.ROTATE_90_COUNTERCLOCKWISE}.get(rotation_quarters % 4)
    if rot is not None:
        img = cv2.rotate(img, rot)
    if mirror:
        img = cv2.flip(img, 1)
    return img


class Detector:
    """Thread-safe: each worker thread gets its own YuNet and Face Landmarker instance."""

    def __init__(self, yunet_path: str, landmarker_path: str, score_threshold: float = 0.6):
        self.yunet_path = yunet_path
        self.landmarker_path = landmarker_path
        self.score_threshold = score_threshold
        self._local = threading.local()

    def _models(self):
        loc = self._local
        if not hasattr(loc, "yunet"):
            loc.yunet = cv2.FaceDetectorYN.create(self.yunet_path, "", (320, 320), self.score_threshold, 0.3, 50)
            loc.landmarker = vision.FaceLandmarker.create_from_options(vision.FaceLandmarkerOptions(
                base_options=BaseOptions(model_asset_path=self.landmarker_path),
                running_mode=vision.RunningMode.IMAGE,
                num_faces=1,
                min_face_detection_confidence=0.3,
                min_face_presence_confidence=0.3,
            ))
        return loc.yunet, loc.landmarker

    def analyze(self, img: np.ndarray, s: DetectSettings) -> list[FaceResult]:
        """All detections in [img] with pose, gaze and the looking decision; false detections come back with
        verified=False (for the debug view). Picking the owner is up to the caller (it needs the previous frames,
        see policy.OwnerTracker)."""
        yunet, landmarker = self._models()
        h, w = img.shape[:2]
        yunet.setInputSize((w, h))
        _, raw = yunet.detect(img)
        faces: list[tuple[np.ndarray, FaceResult]] = []
        for row in (raw if raw is not None else []):
            x, y, bw, bh = row[:4]
            box = (int(max(0, x)), int(max(0, y)), int(min(w, x + bw)), int(min(h, y + bh)))
            yaw, pitch = _pose_from_five_points(row[4:14].reshape(5, 2))
            faces.append((row, FaceResult(box=box, score=float(row[14]), yaw=yaw, pitch=pitch)))
        verified: list[FaceResult] = []  # all detections, each marked verified or not
        for _, f in faces:
            face_w = f.box[2] - f.box[0]
            pts = self._landmarks(img, f.box, landmarker)
            if pts is None and face_w >= MIN_EYE_FACE_PX:
                f.verified = False  # big enough for the landmarker, yet no face there: a false detection
                verified.append(f)
                continue
            if pts is not None:
                f.yaw, f.pitch = gz.head_pose(pts)
                f.gaze = gz.estimate(pts)
            if f.gaze is not None and face_w >= MIN_EYE_FACE_PX:
                f.rays = gz.laser_rays(f.gaze, face_w * 0.8)
            if s.mode == "eye" and f.gaze is not None and face_w >= MIN_EYE_FACE_PX:
                f.looking = gz.is_looking(f.gaze, s.max_gaze_angle)
            else:
                f.looking = abs(f.yaw) <= s.max_head_angle and abs(f.pitch) <= s.max_head_angle
            verified.append(f)
        return verified

    @staticmethod
    def _landmarks(img: np.ndarray, box, landmarker) -> np.ndarray | None:
        """Runs Face Landmarker on a square, upscaled crop around [box]; returns (478,3) frame pixels."""
        h, w = img.shape[:2]
        l, t, r, b = box
        side = int(max(r - l, b - t) * CROP_MARGIN)
        if side < 8:
            return None
        cx, cy = (l + r) // 2, (t + b) // 2
        x0, y0 = cx - side // 2, cy - side // 2
        # Pad instead of clamping so the face stays centred and the crop stays square.
        crop = np.zeros((side, side, 3), np.uint8)
        sx0, sy0, sx1, sy1 = max(0, x0), max(0, y0), min(w, x0 + side), min(h, y0 + side)
        crop[sy0 - y0:sy1 - y0, sx0 - x0:sx1 - x0] = img[sy0:sy1, sx0:sx1]
        crop = cv2.resize(crop, (CROP_SIZE, CROP_SIZE), interpolation=cv2.INTER_CUBIC)
        rgb = cv2.cvtColor(crop, cv2.COLOR_BGR2RGB)
        res = landmarker.detect(mp.Image(image_format=mp.ImageFormat.SRGB, data=rgb))
        if not res.face_landmarks:
            return None
        lm = res.face_landmarks[0]
        pts = np.array([[x0 + p.x * side, y0 + p.y * side, p.z * side] for p in lm], dtype=np.float64)
        # The crop can contain a neighbouring face; only accept landmarks of the face that was detected.
        return pts if landmarks_match(box, pts[:, 0], pts[:, 1]) else None


def _pose_from_five_points(p: np.ndarray) -> tuple[float, float]:
    """Rough head pose from YuNet's 5 points (eyes, nose, mouth corners); fallback only."""
    eye_r, eye_l, nose, mouth_r, mouth_l = p
    eye_mid = (eye_r + eye_l) / 2
    mouth_mid = (mouth_r + mouth_l) / 2
    eye_dist = float(np.linalg.norm(eye_l - eye_r)) or 1.0
    yaw = math.degrees(math.asin(max(-1.0, min(1.0, 2 * (nose[0] - eye_mid[0]) / eye_dist))))
    span = float(mouth_mid[1] - eye_mid[1]) or 1.0
    # The nose tip sits ~55% of the way from the eyes to the mouth on a level face.
    pitch = (0.55 - (nose[1] - eye_mid[1]) / span) * 150
    return yaw, pitch


def annotate(img: np.ndarray, onlookers: list[FaceResult]) -> np.ndarray:
    """Red boxes around onlookers, plus "laser eye" rays in eye mode."""
    out = img.copy()
    thick = max(2, out.shape[1] // 200)
    for f in onlookers:
        l, t, r, b = f.box
        cv2.rectangle(out, (l, t), (r, b), (0, 0, 255), thick)
        for x0, y0, x1, y1 in f.rays:
            p0, p1 = (int(x0), int(y0)), (int(x1), int(y1))
            glow = out.copy()
            cv2.line(glow, p0, p1, (0, 0, 255), thick * 4, cv2.LINE_AA)
            cv2.circle(glow, p0, thick * 3, (0, 0, 255), -1, cv2.LINE_AA)
            out = cv2.addWeighted(glow, 0.45, out, 0.55, 0)
            cv2.line(out, p0, p1, (60, 60, 255), thick, cv2.LINE_AA)
            cv2.circle(out, p0, thick + 1, (220, 220, 255), -1, cv2.LINE_AA)
    return out
