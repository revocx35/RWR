"""Owner tracking and alert gating; same rules as OwnerTracker/AlertGate in the app's OnlookerPolicy.kt."""
from __future__ import annotations

import collections

Box = tuple[int, int, int, int]  # left, top, right, bottom

# A face overlapping last frame's owner by at least this much is still the owner.
OWNER_MIN_IOU = 0.3
# Forget the owner after this long without seeing them.
OWNER_KEEP_MS = 2000
# The owner looks at their screen, so faces turned further sideways aren't picked as the owner (a half face
# at the frame edge can be bigger than the owner's). Pitch is not checked: phones are often held low.
OWNER_MAX_YAW = 35.0


def iou(a: Box, b: Box) -> float:
    iw = min(a[2], b[2]) - max(a[0], b[0])
    ih = min(a[3], b[3]) - max(a[1], b[1])
    if iw <= 0 or ih <= 0:
        return 0.0
    inter = iw * ih
    union = (a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - inter
    return inter / union if union > 0 else 0.0


def near(a: Box, b: Box) -> bool:
    """Same person in consecutive frames: centres at most one face size apart."""
    dx = (a[0] + a[2]) / 2 - (b[0] + b[2]) / 2
    dy = (a[1] + a[3]) / 2 - (b[1] + b[3]) / 2
    size = max(a[2] - a[0], a[3] - a[1], b[2] - b[0], b[3] - b[1])
    return dx * dx + dy * dy <= size * size


# Faces narrower than this fraction of the frame width are never onlookers: at about 7% the person is ~2 m away,
# too far to read a phone screen, and nearly all false detections (ceiling fixtures, bags, hands, distant crowds)
# are that small. Same value as OnlookerPolicy.MIN_FACE_FRACTION in the app.
MIN_FACE_FRACTION = 0.07
# Alert gate: an onlooker must be seen at about the same place over at least GATE_MIN_SPAN_MS, in at least
# GATE_MIN_RATIO of the analysed frames in that span. Time-based, so it means the same at 3 fps (phone) and at
# 15 fps (server); counting frames made it 0.15 s on the server.
GATE_MIN_SPAN_MS = 500
GATE_MIN_RATIO = 0.6
GATE_HISTORY_MS = 3000

# Face Landmarker runs on an enlarged crop around each detection; if it reports a face covering less than this
# of the detection, it found a different (neighbouring) face in the crop and the result is not used.
LANDMARKS_MIN_IOU = 0.3


def landmarks_match(box: Box, xs, ys) -> bool:
    """Whether landmarks (frame pixel coordinates) belong to the face detected at [box]."""
    return iou(box, (int(min(xs)), int(min(ys)), int(max(xs)), int(max(ys)))) >= LANDMARKS_MIN_IOU


class OwnerTracker:
    """The owner is the largest face turned toward the camera when watching starts, then whichever face keeps
    overlapping them. A momentary bigger false detection (a finger or hand close to the lens) can't take the
    owner's place, which would turn the owner into an "onlooker"."""

    def __init__(self, keep_ms: int = OWNER_KEEP_MS):
        self.keep_ms = keep_ms
        self._last: Box | None = None
        self._last_at = 0

    def pick(self, boxes: list[Box], yaws: list[float], now_ms: int) -> int | None:
        """Index of the owner in [boxes] (head yaw of each in [yaws]), or None if there are no faces."""
        if not boxes:
            return None
        owner = None
        if self._last is not None and now_ms - self._last_at <= self.keep_ms:
            best = max(range(len(boxes)), key=lambda i: iou(boxes[i], self._last))
            if iou(boxes[best], self._last) >= OWNER_MIN_IOU:
                owner = best
        if owner is None:
            facing = [i for i in range(len(boxes)) if abs(yaws[i]) <= OWNER_MAX_YAW] or range(len(boxes))
            owner = max(facing, key=lambda i: _area(boxes[i]))
        self._last, self._last_at = boxes[owner], now_ms
        return owner

    def reset(self) -> None:
        self._last = None


def _area(b: Box) -> int:
    return max(0, b[2] - b[0]) * max(0, b[3] - b[1])


class AlertGate:
    """Turns per-frame onlooker boxes into alerts (see GATE_MIN_SPAN_MS); alerts are spaced at least the cooldown
    apart. False detections flicker and jump around; a person who reads your screen stays put."""

    def __init__(self, min_span_ms: int = GATE_MIN_SPAN_MS, min_ratio: float = GATE_MIN_RATIO,
                 history_ms: int = GATE_HISTORY_MS):
        self.min_span_ms = min_span_ms
        self.min_ratio = min_ratio
        self.history_ms = history_ms
        self._recent: collections.deque[tuple[int, list[Box]]] = collections.deque()
        self._last_alert: int | None = None

    def on_frame(self, onlookers: list[Box], now_ms: int, cooldown_ms: int) -> bool:
        self._recent.append((now_ms, list(onlookers)))
        while self._recent and self._recent[0][0] < now_ms - self.history_ms:
            self._recent.popleft()
        if not onlookers or not any(self._confirmed(a, now_ms) for a in onlookers):
            return False
        if self._last_alert is not None and now_ms - self._last_alert < cooldown_ms:
            return False
        self._last_alert = now_ms
        self._recent.clear()
        return True

    def _confirmed(self, box: Box, now_ms: int) -> bool:
        hits = total = 0
        for ts, boxes in reversed(self._recent):   # newest first
            total += 1
            if any(near(box, b) for b in boxes):
                hits += 1
                if now_ms - ts >= self.min_span_ms and hits >= 2 and hits / total >= self.min_ratio:
                    return True
        return False

    def reset(self) -> None:
        self._recent.clear()
