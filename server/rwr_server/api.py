"""Device API used by the phone app. Authentication: `Authorization: Bearer <device token>`.

Tokens are 256-bit random values issued once at pairing; only their SHA-256 is stored. Tokens travel
in a header (never in URLs, so they don't end up in logs) over TLS that the app pins.
"""
from __future__ import annotations

import asyncio
import time

from fastapi import APIRouter, Request, WebSocket
from fastapi.responses import JSONResponse

from . import immich, security
from . import settings as server_settings
from .web import client_ip

router = APIRouter(prefix="/api/v1")
MAX_UPLOAD_BYTES = 80 * 1024 * 1024
MAX_JSON_BYTES = 64 * 1024


def _err(status: int, msg: str) -> JSONResponse:
    return JSONResponse({"error": msg}, status_code=status)


def _ip_of(conn) -> str:
    peer = conn.client.host if conn.client else ""
    xff = conn.headers.get("x-forwarded-for")
    if xff and conn.app.state.cfg.is_trusted_proxy(peer):
        return xff.split(",")[-1].strip()[:64]
    return peer


def _authenticate(conn) -> tuple[dict | None, str | None]:
    """Returns (device row, error). Failed attempts are throttled per client IP."""
    db = conn.app.state.db
    ip = _ip_of(conn)
    if security.locked_for(db, f"pair:{ip}"):
        return None, "too many failed attempts, try later"
    auth = conn.headers.get("authorization", "")
    token = auth[7:].strip() if auth.lower().startswith("bearer ") else ""
    row = db.one("SELECT * FROM devices WHERE token_hash = ? AND revoked = 0",
                 (security.token_hash(token),)) if 20 <= len(token) <= 100 else None
    if row is None:
        security.record_failure(db, f"pair:{ip}", security.PAIR_MAX_FAILURES, security.PAIR_WINDOW_S,
                                security.PAIR_LOCK_S)
        return None, "invalid or revoked device token"
    db.execute("UPDATE devices SET last_seen = ?, last_ip = ? WHERE id = ?", (time.time(), ip, row["id"]))
    return dict(row), None


async def _json_body(request: Request) -> dict | None:
    body = await request.body()
    if len(body) > MAX_JSON_BYTES:
        return None
    try:
        data = await request.json()
    except ValueError:
        return None
    return data if isinstance(data, dict) else None


@router.post("/pair")
async def pair(request: Request):
    db = request.app.state.db
    ip = client_ip(request)
    if security.locked_for(db, f"pair:{ip}"):
        return _err(429, "too many failed attempts, try later")
    data = await _json_body(request)
    code = str((data or {}).get("code", ""))
    name = str((data or {}).get("name", "Phone")).strip()[:60] or "Phone"
    now = time.time()
    with db.transaction():
        row = db.one("SELECT * FROM pairing_codes WHERE code_hash = ? AND used = 0 AND expires > ?",
                     (security.token_hash(code), now)) if 10 <= len(code) <= 100 else None
        if row is not None:
            db.execute("UPDATE pairing_codes SET used = 1 WHERE code_hash = ?", (row["code_hash"],))
    if row is None:
        security.record_failure(db, f"pair:{ip}", security.PAIR_MAX_FAILURES, security.PAIR_WINDOW_S,
                                security.PAIR_LOCK_S)
        db.audit(ip, "pairing_failed")
        return _err(401, "pairing code is invalid, used or expired")
    token = security.new_token(32)
    cur = db.execute(
        "INSERT INTO devices(name, token_hash, can_configure, created, last_seen, last_ip) VALUES(?, ?, ?, ?, ?, ?)",
        (name, security.token_hash(token), row["can_configure"], now, now, ip),
    )
    db.audit(ip, "device_paired", f"{cur.lastrowid} {name}")
    return {"device_id": cur.lastrowid, "token": token, "name": name, "can_configure": bool(row["can_configure"])}


@router.get("/device")
async def device_info(request: Request):
    dev, err = _authenticate(request)
    if err:
        return _err(401, err)
    return {"device_id": dev["id"], "name": dev["name"], "can_configure": bool(dev["can_configure"])}


@router.delete("/device")
async def unpair(request: Request):
    dev, err = _authenticate(request)
    if err:
        return _err(401, err)
    request.app.state.db.execute("UPDATE devices SET revoked = 1 WHERE id = ?", (dev["id"],))
    request.app.state.db.audit(client_ip(request), "device_unpaired", str(dev["id"]))
    return {"ok": True}


@router.get("/config")
async def get_config(request: Request):
    dev, err = _authenticate(request)
    if err:
        return _err(401, err)
    if not dev["can_configure"]:
        return _err(403, "this device may not change server settings (allow it in the web UI)")
    return server_settings.public_view(server_settings.load(request.app.state.db))


@router.put("/config")
async def put_config(request: Request):
    dev, err = _authenticate(request)
    if err:
        return _err(401, err)
    if not dev["can_configure"]:
        return _err(403, "this device may not change server settings (allow it in the web UI)")
    data = await _json_body(request)
    if data is None:
        return _err(400, "expected a JSON object")
    try:
        new = server_settings.apply_update(request.app.state.db, data)
    except server_settings.SettingsError as e:
        return _err(400, str(e))
    request.app.state.db.audit(client_ip(request), "settings_changed", f"device {dev['id']}")
    return server_settings.public_view(new)


@router.post("/config/immich-test")
async def test_immich(request: Request):
    dev, err = _authenticate(request)
    if err:
        return _err(401, err)
    if not dev["can_configure"]:
        return _err(403, "this device may not change server settings")
    try:
        msg = await asyncio.to_thread(immich.test_connection, server_settings.load(request.app.state.db))
        return {"ok": True, "message": msg}
    except immich.ImmichError as e:
        return {"ok": False, "message": str(e)}


@router.post("/media")
async def upload_media(request: Request):
    """The phone detected an onlooker itself and wants the snapshot/clip saved to Immich (and the log)."""
    dev, err = _authenticate(request)
    if err:
        return _err(401, err)
    try:
        declared = int(request.headers.get("content-length", "0"))
    except ValueError:
        declared = 0
    if declared > MAX_UPLOAD_BYTES:
        return _err(413, "file too large")
    form = await request.form(max_files=1, max_fields=10)
    upload = form.get("file")
    kind = form.get("kind")
    if upload is None or not hasattr(upload, "read") or kind not in ("photo", "video"):
        return _err(400, "expected multipart fields: file, kind=photo|video")
    data = await upload.read(MAX_UPLOAD_BYTES + 1)
    if len(data) > MAX_UPLOAD_BYTES:
        return _err(413, "file too large")
    if kind == "photo" and not data.startswith(b"\xff\xd8"):
        return _err(400, "photo must be a JPEG")
    if kind == "video" and data[4:8] != b"ftyp":
        return _err(400, "video must be an MP4")
    try:
        taken_at = float(form.get("taken_at", time.time() * 1000)) / 1000
        onlookers = int(form.get("onlookers", 1))
    except ValueError:
        return _err(400, "bad taken_at/onlookers")
    taken_at = min(max(taken_at, time.time() - 7 * 86400), time.time() + 300)
    mode = form.get("mode") if form.get("mode") in ("head", "eye") else None
    event_id = request.app.state.events.record(
        device_id=dev["id"], at=taken_at, kind=kind, data=data, onlookers=max(0, min(onlookers, 50)),
        mode=mode, source="upload", want_immich=True,
    )
    return {"event_id": event_id}


@router.websocket("/stream")
async def stream(ws: WebSocket):
    dev, err = _authenticate(ws)
    if err:
        await ws.close(code=4401, reason=err[:100])
        return
    await ws.accept()
    await ws.app.state.hub.serve(ws, dev["id"], dev["name"])
