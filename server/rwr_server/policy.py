"""Alert gating; same rules as AlertGate in the app's OnlookerPolicy.kt."""
from __future__ import annotations

import collections


class AlertGate:
    """An onlooker must appear in [required] of the last [window] analysed frames, and alerts are
    spaced at least the cooldown apart."""

    def __init__(self, window: int = 3, required: int = 2):
        self.window = window
        self.required = required
        self._recent: collections.deque[bool] = collections.deque(maxlen=window)
        self._last_alert: int | None = None

    def on_frame(self, onlooker_seen: bool, now_ms: int, cooldown_ms: int) -> bool:
        self._recent.append(onlooker_seen)
        if sum(self._recent) < self.required:
            return False
        if self._last_alert is not None and now_ms - self._last_alert < cooldown_ms:
            return False
        self._last_alert = now_ms
        self._recent.clear()
        return True

    def reset(self) -> None:
        self._recent.clear()
