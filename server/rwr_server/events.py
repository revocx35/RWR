"""Alert events: optional storage on the server (for the web UI) and Immich delivery."""
from __future__ import annotations

import concurrent.futures
import datetime as dt
import logging
import os
import time
from pathlib import Path

from . import immich, settings
from .db import DB

log = logging.getLogger("rwr.events")


def media_name(at: float, kind: str) -> str:
    return "RWR_" + dt.datetime.fromtimestamp(at).strftime("%Y%m%d_%H%M%S") + (".mp4" if kind == "video" else ".jpg")


class EventStore:
    def __init__(self, db: DB, events_dir: Path):
        self.db = db
        self.dir = events_dir
        self.dir.mkdir(parents=True, exist_ok=True)
        # Uploads run off the event loop; one at a time per server keeps Immich load polite.
        self._uploader = concurrent.futures.ThreadPoolExecutor(max_workers=2, thread_name_prefix="immich")

    def record(self, *, device_id: int | None, at: float, kind: str, data: bytes, onlookers: int,
               mode: str | None, source: str, want_immich: bool) -> int:
        s = settings.load(self.db)
        immich_on = want_immich and s["immich_enabled"]
        cur = self.db.execute(
            "INSERT INTO events(device_id, at, kind, onlookers, mode, source, immich_status) VALUES(?, ?, ?, ?, ?, ?, ?)",
            (device_id, at, kind, onlookers, mode, source, "pending" if immich_on else None),
        )
        event_id = cur.lastrowid
        if s["keep_events"]:
            rel = f"{event_id}_{media_name(at, kind)}"
            path = self.dir / rel
            fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(fd, "wb") as f:
                f.write(data)
            self.db.execute("UPDATE events SET file = ? WHERE id = ?", (rel, event_id))
        if immich_on:
            self._uploader.submit(self._upload, event_id, data, at, kind, s)
        elif want_immich:
            self.db.execute("UPDATE events SET immich_status = 'error', immich_detail = ? WHERE id = ?",
                            ("Immich is not enabled on the server", event_id))
        return event_id

    def _upload(self, event_id: int, data: bytes, at: float, kind: str, s: dict) -> None:
        mime = "video/mp4" if kind == "video" else "image/jpeg"
        last = ""
        for attempt in range(3):
            try:
                asset = immich.upload(s, data, media_name(at, kind), mime, at, f"rwr-{event_id}-{int(at)}")
                self.db.execute("UPDATE events SET immich_status = 'ok', immich_detail = ? WHERE id = ?",
                                (asset, event_id))
                log.info("event %s uploaded to Immich", event_id)
                return
            except immich.ImmichError as e:
                last = str(e)
                log.warning("event %s Immich upload failed (attempt %d): %s", event_id, attempt + 1, last)
                time.sleep(2 * (attempt + 1))
        self.db.execute("UPDATE events SET immich_status = 'error', immich_detail = ? WHERE id = ?",
                        (last[:300], event_id))

    def path_of(self, event_id: int) -> tuple[Path, str] | None:
        row = self.db.one("SELECT file, kind FROM events WHERE id = ?", (event_id,))
        if not row or not row["file"]:
            return None
        p = (self.dir / row["file"]).resolve()
        if p.parent != self.dir.resolve() or not p.exists():
            return None
        return p, row["kind"]

    def delete(self, event_id: int) -> None:
        found = self.path_of(event_id)
        if found:
            found[0].unlink(missing_ok=True)
        self.db.execute("DELETE FROM events WHERE id = ?", (event_id,))

    def purge(self) -> int:
        days = settings.load(self.db)["retention_days"]
        cutoff = time.time() - days * 86400
        old = self.db.all("SELECT id FROM events WHERE at < ?", (cutoff,))
        for row in old:
            self.delete(row["id"])
        return len(old)
