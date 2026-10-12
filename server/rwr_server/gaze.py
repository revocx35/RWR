"""Head pose and eye gaze from MediaPipe Face Landmarker points (478 landmarks, with irises).

Pure math, no MediaPipe import, so it is unit-testable. The Android app has the same math in
GazeEstimator.kt; keep the two in sync (same indices, constants and sign conventions).

Conventions (image space, y down): yaw > 0 = face/eyes turned toward image right,
pitch > 0 = turned up. A face looking straight into the camera has yaw = pitch = 0.
"""
from __future__ import annotations

import math
from dataclasses import dataclass

import numpy as np

# Face outline / axis points.
CHEEK_A, CHEEK_B = 234, 454        # image-left and image-right cheek on an upright face
FOREHEAD, CHIN = 10, 152
# Eyes: (corner, corner, upper lid, lower lid). Which iris belongs to which eye is decided by
# distance, so mirrored frames and model conventions can't swap them.
EYES = ((33, 133, 159, 145), (362, 263, 386, 374))
IRIS_CENTERS = (468, 473)

# Eye opening is ~30 mm wide, eyeball radius ~12 mm: iris offset / half-width * 15/12 = sin(angle).
EYE_GAIN = 1.25
# Lid gap / eye width below this = eye closed (open eyes are ~0.25-0.35).
CLOSED_RATIO = 0.10


@dataclass(frozen=True)
class Gaze:
    head_yaw: float
    head_pitch: float
    eye_yaw: float
    eye_pitch: float
    eyes_open: bool
    # Iris centers in pixels, for drawing the "laser" rays.
    irises: tuple[tuple[float, float], ...]

    @property
    def yaw(self) -> float:
        return self.head_yaw + self.eye_yaw

    @property
    def pitch(self) -> float:
        return self.head_pitch + self.eye_pitch


def head_pose(p: np.ndarray) -> tuple[float, float]:
    """Yaw/pitch in degrees from 3D landmarks in pixels (x, y, z with z scaled like x)."""
    right = p[CHEEK_B] - p[CHEEK_A]
    up = p[FOREHEAD] - p[CHIN]
    normal = np.cross(right, up)  # points toward the camera (negative z) for a frontal face
    n = np.linalg.norm(normal)
    if n == 0:
        return 0.0, 0.0
    f = normal / n
    yaw = math.degrees(math.atan2(f[0], -f[2]))
    pitch = math.degrees(math.atan2(-f[1], math.hypot(f[0], f[2])))
    return yaw, pitch


def _asin_deg(x: float) -> float:
    return math.degrees(math.asin(max(-1.0, min(1.0, x))))


def estimate(p: np.ndarray) -> Gaze | None:
    """p: (478, 3) landmarks in pixels. Returns None if the model gave no iris points."""
    if p.shape[0] < 478:
        return None
    head_yaw, head_pitch = head_pose(p)
    yaws, pitches, weights, opens, irises = [], [], [], [], []
    for a, b, top, bottom in EYES:
        ca, cb = p[a, :2], p[b, :2]
        if ca[0] > cb[0]:
            ca, cb = cb, ca  # ca = image-left corner
        width = float(np.linalg.norm(cb - ca))
        if width < 1e-6:
            continue
        ex = (cb - ca) / width               # along the eye, toward image right
        ey = np.array([-ex[1], ex[0]])       # perpendicular, toward image down
        center = (ca + cb) / 2
        iris = min((p[i, :2] for i in IRIS_CENTERS), key=lambda q: float(np.linalg.norm(q - center)))
        lid_mid = (p[top, :2] + p[bottom, :2]) / 2
        half = width / 2
        h = float(np.dot(iris - center, ex)) / half
        v = float(np.dot(iris - lid_mid, ey)) / half
        yaws.append(_asin_deg(EYE_GAIN * h))
        pitches.append(-_asin_deg(EYE_GAIN * v))
        weights.append(width)
        opens.append(float(np.linalg.norm(p[top, :2] - p[bottom, :2])) / width)
        irises.append((float(iris[0]), float(iris[1])))
    if not weights:
        return None
    w = np.array(weights)
    return Gaze(
        head_yaw=head_yaw,
        head_pitch=head_pitch,
        eye_yaw=float(np.dot(yaws, w) / w.sum()),
        eye_pitch=float(np.dot(pitches, w) / w.sum()),
        eyes_open=max(opens) >= CLOSED_RATIO,
        irises=tuple(irises),
    )


def is_looking(g: Gaze, max_gaze_angle: float) -> bool:
    return g.eyes_open and abs(g.yaw) <= max_gaze_angle and abs(g.pitch) <= max_gaze_angle


def laser_rays(g: Gaze, length: float) -> list[tuple[float, float, float, float]]:
    """Line segments from each iris along the gaze direction (short when looking at the camera)."""
    dx = math.sin(math.radians(g.yaw)) * length
    dy = -math.sin(math.radians(g.pitch)) * length
    return [(x, y, x + dx, y + dy) for x, y in g.irises]
