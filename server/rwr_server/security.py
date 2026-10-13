"""Authentication primitives: password hashing, tokens, sessions, CSRF, brute-force throttling."""
from __future__ import annotations

import hashlib
import hmac
import ipaddress
import secrets
import time
from dataclasses import dataclass

from argon2 import PasswordHasher
from argon2.exceptions import InvalidHashError, VerificationError, VerifyMismatchError

from .db import DB

# argon2id, RFC 9106 "second recommended" profile (64 MiB, t=3, p=4).
_hasher = PasswordHasher(time_cost=3, memory_cost=64 * 1024, parallelism=4)
_DUMMY_HASH = _hasher.hash(secrets.token_urlsafe(16))

SESSION_IDLE_S = 30 * 60
SESSION_ABSOLUTE_S = 12 * 3600
TOTP_STAGE_S = 5 * 60            # time allowed between password and 2FA code
MIN_PASSWORD_LEN = 12
COOKIE_NAME = "__Host-rwr_session"   # __Host-: Secure, Path=/, no Domain, so subdomains can't set it

# Login throttling: per client IP, and a global cap against distributed guessing.
IP_MAX_FAILURES, IP_WINDOW_S, IP_LOCK_S = 5, 15 * 60, 15 * 60
GLOBAL_MAX_FAILURES, GLOBAL_WINDOW_S, GLOBAL_LOCK_S = 20, 15 * 60, 5 * 60
# Second factor: also capped globally, so a botnet that has the password can't spread code guesses over IPs.
TOTP_GLOBAL_MAX_FAILURES = 10
# Pairing / device-token endpoints.
PAIR_MAX_FAILURES, PAIR_WINDOW_S, PAIR_LOCK_S = 10, 10 * 60, 10 * 60

_COMMON = {"password", "123456789012", "qwertyuiopas", "rwr-password", "changemechangeme", "adminadminadmin"}


def hash_password(pw: str) -> str:
    return _hasher.hash(pw)


def verify_password(stored_hash: str | None, pw: str) -> bool:
    """Constant work whether or not the account exists (verifies against a dummy hash)."""
    try:
        return _hasher.verify(stored_hash or _DUMMY_HASH, pw) and stored_hash is not None
    except (VerifyMismatchError, VerificationError, InvalidHashError):
        return False


def needs_rehash(stored_hash: str) -> bool:
    return _hasher.check_needs_rehash(stored_hash)


def password_problem(pw: str, username: str = "") -> str | None:
    if len(pw) < MIN_PASSWORD_LEN:
        return f"Use at least {MIN_PASSWORD_LEN} characters."
    if len(pw) > 256:
        return "Use at most 256 characters."
    if pw.lower() in _COMMON or (username and username.lower() in pw.lower()):
        return "That password is too easy to guess."
    if len(set(pw)) < 5:
        return "Use more different characters."
    return None


def throttle_key(ip: str) -> str:
    """Per-client throttle key. IPv6 clients usually control a whole /64, so it counts as one client."""
    try:
        addr = ipaddress.ip_address(ip)
    except ValueError:
        return f"ip:{ip}"
    if addr.version == 6:
        if addr.ipv4_mapped:
            return f"ip:{addr.ipv4_mapped}"
        return "ip:" + str(ipaddress.ip_network(f"{addr}/64", strict=False))
    return f"ip:{addr}"


def new_token(nbytes: int = 32) -> str:
    return secrets.token_urlsafe(nbytes)


def token_hash(token: str) -> str:
    """Tokens are high-entropy random values, so a fast hash is enough (no need for argon2)."""
    return hashlib.sha256(token.encode()).hexdigest()


def same(a: str, b: str) -> bool:
    return hmac.compare_digest(a.encode(), b.encode())


# --- throttling ---

def locked_for(db: DB, key: str) -> float:
    row = db.one("SELECT locked_until FROM throttle WHERE key = ?", (key,))
    return max(0.0, row["locked_until"] - time.time()) if row else 0.0


def record_failure(db: DB, key: str, max_failures: int, window_s: float, lock_s: float) -> None:
    now = time.time()
    with db.transaction():
        row = db.one("SELECT failures, window_start FROM throttle WHERE key = ?", (key,))
        if row is None or now - row["window_start"] > window_s:
            failures, start = 1, now
        else:
            failures, start = row["failures"] + 1, row["window_start"]
        locked = now + lock_s if failures >= max_failures else 0
        if locked:
            failures, start = 0, now
        db.execute(
            "INSERT INTO throttle(key, failures, window_start, locked_until) VALUES(?, ?, ?, ?) "
            "ON CONFLICT(key) DO UPDATE SET failures = excluded.failures, window_start = excluded.window_start, "
            "locked_until = MAX(throttle.locked_until, excluded.locked_until)",
            (key, failures, start, locked),
        )


def clear_failures(db: DB, key: str) -> None:
    db.execute("DELETE FROM throttle WHERE key = ?", (key,))


# --- sessions ---

@dataclass
class Session:
    id_hash: str
    stage: str
    csrf: str
    created: float
    last_seen: float


def create_session(db: DB, stage: str, ip: str | None, user_agent: str | None) -> tuple[str, Session]:
    raw = new_token(32)
    now = time.time()
    s = Session(token_hash(raw), stage, new_token(32), now, now)
    db.execute(
        "INSERT INTO sessions(id_hash, stage, csrf, created, last_seen, ip, user_agent) VALUES(?, ?, ?, ?, ?, ?, ?)",
        (s.id_hash, stage, s.csrf, now, now, ip, (user_agent or "")[:200]),
    )
    return raw, s


def load_session(db: DB, raw: str | None) -> Session | None:
    if not raw or len(raw) > 100:
        return None
    h = token_hash(raw)
    row = db.one("SELECT * FROM sessions WHERE id_hash = ?", (h,))
    if row is None:
        return None
    now = time.time()
    max_age = TOTP_STAGE_S if row["stage"] == "totp" else SESSION_ABSOLUTE_S
    if now - row["last_seen"] > SESSION_IDLE_S or now - row["created"] > max_age:
        db.execute("DELETE FROM sessions WHERE id_hash = ?", (h,))
        return None
    db.execute("UPDATE sessions SET last_seen = ? WHERE id_hash = ?", (now, h))
    return Session(h, row["stage"], row["csrf"], row["created"], now)


def session_alive(db: DB, id_hash: str) -> bool:
    """Whether the session still exists and hasn't expired, without counting as activity (long-running
    responses like the live view must end when the session ends, not keep it alive)."""
    row = db.one("SELECT stage, created, last_seen FROM sessions WHERE id_hash = ?", (id_hash,))
    if row is None or row["stage"] != "full":
        return False
    now = time.time()
    return now - row["last_seen"] <= SESSION_IDLE_S and now - row["created"] <= SESSION_ABSOLUTE_S


def delete_session(db: DB, id_hash: str) -> None:
    db.execute("DELETE FROM sessions WHERE id_hash = ?", (id_hash,))


def delete_all_sessions(db: DB, except_hash: str | None = None) -> None:
    db.execute("DELETE FROM sessions WHERE id_hash != ?", (except_hash or "",))


def purge_expired(db: DB) -> None:
    now = time.time()
    db.execute(
        "DELETE FROM sessions WHERE last_seen < ? OR created < ?",
        (now - SESSION_IDLE_S, now - SESSION_ABSOLUTE_S),
    )
    db.execute("DELETE FROM pairing_codes WHERE expires < ? OR used = 1", (now - 3600,))
    db.execute("DELETE FROM throttle WHERE locked_until < ? AND window_start < ?", (now, now - 86400))
