"""SQLite storage. One connection guarded by a lock: the server's write rate is tiny (logins, events)."""
from __future__ import annotations

import json
import sqlite3
import threading
import time
from pathlib import Path
from typing import Any

SCHEMA = """
CREATE TABLE IF NOT EXISTS admin (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    username TEXT NOT NULL,
    password_hash TEXT NOT NULL,
    must_change INTEGER NOT NULL DEFAULT 1,
    totp_secret TEXT,
    totp_enabled INTEGER NOT NULL DEFAULT 0,
    totp_last_step INTEGER NOT NULL DEFAULT 0,
    password_changed_at REAL NOT NULL
);
CREATE TABLE IF NOT EXISTS sessions (
    id_hash TEXT PRIMARY KEY,
    stage TEXT NOT NULL,              -- 'totp' (password ok, waiting for code) or 'full'
    csrf TEXT NOT NULL,
    created REAL NOT NULL,
    last_seen REAL NOT NULL,
    ip TEXT,
    user_agent TEXT
);
CREATE TABLE IF NOT EXISTS throttle (
    key TEXT PRIMARY KEY,             -- 'ip:<addr>' or 'user' or 'pair:<addr>'
    failures INTEGER NOT NULL,
    window_start REAL NOT NULL,
    locked_until REAL NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS devices (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL,
    token_hash TEXT NOT NULL UNIQUE,
    can_configure INTEGER NOT NULL DEFAULT 0,
    created REAL NOT NULL,
    last_seen REAL,
    last_ip TEXT,
    revoked INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS pairing_codes (
    code_hash TEXT PRIMARY KEY,
    created REAL NOT NULL,
    expires REAL NOT NULL,
    can_configure INTEGER NOT NULL,
    used INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS settings (
    key TEXT PRIMARY KEY,
    value TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS events (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    device_id INTEGER,
    at REAL NOT NULL,
    kind TEXT NOT NULL,               -- 'photo' or 'video'
    file TEXT,                        -- relative to events dir; NULL when not kept
    onlookers INTEGER NOT NULL DEFAULT 0,
    mode TEXT,
    source TEXT NOT NULL,             -- 'server' (server detection) or 'upload' (phone detection)
    immich_status TEXT,               -- NULL (not requested), 'pending', 'ok', 'error'
    immich_detail TEXT
);
CREATE TABLE IF NOT EXISTS audit (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    at REAL NOT NULL,
    ip TEXT,
    action TEXT NOT NULL,
    detail TEXT
);
"""


class DB:
    def __init__(self, path: Path):
        path.parent.mkdir(parents=True, exist_ok=True)
        self._conn = sqlite3.connect(path, check_same_thread=False, isolation_level=None)
        self._conn.row_factory = sqlite3.Row
        self._lock = threading.RLock()
        with self._lock:
            self._conn.execute("PRAGMA journal_mode=WAL")
            self._conn.execute("PRAGMA foreign_keys=ON")
            self._conn.executescript(SCHEMA)
        try:
            path.chmod(0o600)
        except OSError:
            pass

    def execute(self, sql: str, params: tuple | dict = ()) -> sqlite3.Cursor:
        with self._lock:
            return self._conn.execute(sql, params)

    def one(self, sql: str, params: tuple | dict = ()) -> sqlite3.Row | None:
        with self._lock:
            return self._conn.execute(sql, params).fetchone()

    def all(self, sql: str, params: tuple | dict = ()) -> list[sqlite3.Row]:
        with self._lock:
            return self._conn.execute(sql, params).fetchall()

    def transaction(self):
        return _Tx(self)

    # --- settings (JSON values) ---
    def get_setting(self, key: str, default: Any = None) -> Any:
        row = self.one("SELECT value FROM settings WHERE key = ?", (key,))
        return json.loads(row["value"]) if row else default

    def set_setting(self, key: str, value: Any) -> None:
        self.execute(
            "INSERT INTO settings(key, value) VALUES(?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value",
            (key, json.dumps(value)),
        )

    def audit(self, ip: str | None, action: str, detail: str = "") -> None:
        self.execute("INSERT INTO audit(at, ip, action, detail) VALUES(?, ?, ?, ?)", (time.time(), ip, action, detail))
        # Keep the log bounded.
        self.execute("DELETE FROM audit WHERE id <= (SELECT MAX(id) FROM audit) - 2000")


class _Tx:
    def __init__(self, db: DB):
        self.db = db

    def __enter__(self):
        self.db._lock.acquire()
        self.db._conn.execute("BEGIN IMMEDIATE")
        return self.db

    def __exit__(self, exc_type, exc, tb):
        try:
            self.db._conn.execute("ROLLBACK" if exc_type else "COMMIT")
        finally:
            self.db._lock.release()
        return False
