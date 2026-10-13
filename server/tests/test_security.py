"""Regression tests for the findings of the October 2026 security review (see ARCHITECTURE.md, threat model)."""
import asyncio
import re
import struct
import time

import cv2
import httpx
import numpy as np
import pytest
from fastapi.testclient import TestClient
from starlette.websockets import WebSocketDisconnect

from conftest import ADMIN_PW, FakeDetector, csrf_of, login
from rwr_server import security
from rwr_server import settings as server_settings
from rwr_server.app import MAX_BODY_BYTES, create_app
from rwr_server.clips import encode_mp4
from rwr_server.config import Config
from rwr_server.detector import decode_frame, frame_size_ok, jpeg_size
from rwr_server.events import EventStore
from test_api import auth, paired


def _bomb_jpeg(width=30000, height=30000) -> bytes:
    ok, j = cv2.imencode(".jpg", np.zeros((16, 16, 3), np.uint8))
    j = bytearray(j.tobytes())
    i = j.index(b"\xff\xc0")
    j[i + 5:i + 9] = struct.pack(">HH", height, width)
    return bytes(j)


def _app(monkeypatch, **env):
    for k, v in env.items():
        monkeypatch.setenv(k, v)
    app = create_app(Config(), detector=FakeDetector())
    app.state.db.execute("INSERT INTO admin(id, username, password_hash, must_change, password_changed_at) "
                         "VALUES(1, 'admin', ?, 0, 0)", (security.hash_password(ADMIN_PW),))
    return app


# --- 1. request bodies are limited while they stream in (pre-auth memory exhaustion) ---

def test_oversized_bodies_are_refused_before_buffering(client):
    assert client.post("/api/v1/pair", content=b"x" * (MAX_BODY_BYTES + 1),
                       headers={"Content-Type": "application/json"}).status_code == 413

    def chunks():  # chunked: no Content-Length to check up front
        for _ in range(64):
            yield b"a" * 65536
    r = client.post("/api/v1/pair", content=chunks(), headers={"Content-Type": "application/json"})
    assert r.status_code == 413
    r = client.post("/login", content=chunks(), headers={"Content-Type": "application/x-www-form-urlencoded"})
    assert r.status_code == 413


def test_media_upload_may_be_larger_than_the_default_limit(admin, app, client):
    _, token = paired(admin, app, client)
    big = b"\xff\xd8\xff\xe0" + b"\0" * (2 * MAX_BODY_BYTES)
    r = client.post("/api/v1/media", headers=auth(token), files={"file": ("x.jpg", big, "image/jpeg")},
                    data={"kind": "photo"})
    assert r.status_code == 200, r.text


# --- 2. parallel login attempts can't get past the lockout ---

def test_parallel_login_attempts_respect_the_lockout(app):
    async def run():
        transport = httpx.ASGITransport(app=app, client=("198.51.100.7", 1234))
        async with httpx.AsyncClient(transport=transport, base_url="https://testserver") as c:
            page = await c.get("/login")
            token = csrf_of(page.text)

            async def guess(i):
                r = await c.post("/login", data={"username": "admin", "password": f"wrong guess {i}", "csrf": token},
                                 headers={"Origin": "https://testserver"})
                return r.status_code
            return await asyncio.gather(*(guess(i) for i in range(15)))
    codes = asyncio.run(run())
    assert codes.count(401) == security.IP_MAX_FAILURES   # checked; the rest were refused without a check
    assert codes.count(429) == 15 - security.IP_MAX_FAILURES


def test_ipv6_clients_are_throttled_per_64():
    assert security.throttle_key("2001:db8:1:2::1") == security.throttle_key("2001:db8:1:2:ffff::9")
    assert security.throttle_key("2001:db8:1:2::1") != security.throttle_key("2001:db8:1:3::1")
    assert security.throttle_key("::ffff:192.0.2.1") == security.throttle_key("192.0.2.1") == "ip:192.0.2.1"


def test_totp_failures_also_count_globally(app):
    db = app.state.db
    from rwr_server.web import _auth_failure, _locked
    for i in range(security.TOTP_GLOBAL_MAX_FAILURES):
        _auth_failure(db, f"203.0.113.{i}", "totp_failed", totp=True)   # a different IP every time
    assert _locked(db, "198.51.100.99", totp=True)


def test_current_password_guesses_on_the_account_page_are_throttled(admin):
    for _ in range(security.IP_MAX_FAILURES):
        page = admin.get("/account")
        r = admin.post("/account/password", data={"csrf": csrf_of(page.text), "current": "not it at all",
                                                  "new": "a fresh long passphrase", "confirm": "a fresh long passphrase"})
        assert r.status_code == 401
    page = admin.get("/account")
    r = admin.post("/account/password", data={"csrf": csrf_of(page.text), "current": ADMIN_PW,
                                              "new": "a fresh long passphrase", "confirm": "a fresh long passphrase"})
    assert r.status_code == 429


# --- 3. the Immich key and the clips can't be redirected without the key ---

def test_changing_immich_destination_needs_the_api_key(admin, app, client):
    _, token = paired(admin, app, client)
    r = client.put("/api/v1/config", headers=auth(token), json={
        "immich_url": "https://immich.example.lan", "immich_api_key": "secret-key-123", "immich_enabled": True})
    assert r.status_code == 200
    for change in ({"immich_url": "https://attacker.example"},
                   {"immich_url": "http://attacker.example", "immich_allow_http": True},
                   {"immich_ca_pem": "-----BEGIN CERTIFICATE-----\nMIIB\n-----END CERTIFICATE-----"}):
        r = client.put("/api/v1/config", headers=auth(token), json=change)
        assert r.status_code == 400 and "API key again" in r.json()["error"], change
    assert server_settings.load(app.state.db)["immich_url"] == "https://immich.example.lan"
    # Same rule in the web UI.
    page = admin.get("/settings")
    r = admin.post("/settings", data={"csrf": csrf_of(page.text), "immich_enabled": "on",
                                      "immich_url": "https://attacker.example", "immich_album": "RWR",
                                      "retention_days": "7", "min_face_px": "16", "detector_score": "0.6"})
    assert r.status_code == 400
    # With the key re-entered, the admin (or a trusted phone) can move Immich.
    r = client.put("/api/v1/config", headers=auth(token), json={
        "immich_url": "https://new-immich.example.lan", "immich_api_key": "secret-key-123"})
    assert r.status_code == 200
    # Unrelated settings don't need the key.
    assert client.put("/api/v1/config", headers=auth(token), json={"retention_days": 3}).status_code == 200


# --- 4. decompression bombs ---

def test_jpeg_size_header_parsing():
    ok, j = cv2.imencode(".jpg", np.zeros((48, 64, 3), np.uint8))
    assert jpeg_size(j.tobytes()) == (64, 48)
    assert jpeg_size(b"not a jpeg") is None
    assert jpeg_size(b"\xff\xd8\xff") is None
    assert jpeg_size(_bomb_jpeg()) == (30000, 30000)


def test_frame_claiming_huge_dimensions_is_not_decoded():
    bomb = _bomb_jpeg()
    assert len(bomb) < 1000 and not frame_size_ok(bomb)
    assert decode_frame(bomb, 0, False) is None
    ok, j = cv2.imencode(".jpg", np.zeros((720, 1280, 3), np.uint8))
    assert decode_frame(j.tobytes(), 0, False).shape == (720, 1280, 3)


def test_stream_closes_on_bomb_frame(admin, app, client):
    _, token = paired(admin, app, client)
    with client.websocket_connect("/api/v1/stream", headers=auth(token)) as ws:
        ws.receive_text()
        ws.send_bytes(struct.pack(">BBBBIq", 1, 0, 1, 0, 1, int(time.time() * 1000)) + _bomb_jpeg())
        with pytest.raises(WebSocketDisconnect) as e:
            ws.receive_text()
        assert e.value.code == 1003


# --- 5. clips are encoded frame by frame ---

def test_encode_mp4_pulls_frames_lazily():
    made = []

    def frame(i):
        made.append(i)
        return np.full((120, 160, 3), i * 20, np.uint8)
    data = encode_mp4(frame, [i * 100 for i in range(8)])
    assert data[4:8] == b"ftyp"
    assert made == sorted(made) and len(set(made)) == 8   # each frame produced once, in order


# --- 6. proxy mode: http is redirected to https ---

def test_proxy_mode_redirects_plain_http(monkeypatch, cfg):
    app = _app(monkeypatch, RWR_TLS="off", RWR_PUBLIC_URL="https://rwr.example.com",
               RWR_TRUSTED_PROXIES="10.9.8.7/32")
    with TestClient(app, base_url="http://rwr.example.com", client=("10.9.8.7", 5555)) as c:
        r = c.get("/login?next=1", headers={"X-Forwarded-Proto": "http"}, follow_redirects=False)
        assert r.status_code == 308 and r.headers["location"] == "https://rwr.example.com/login?next=1"
        assert c.get("/login", headers={"X-Forwarded-Proto": "https"}).status_code == 200
    # A client that isn't the proxy can't trigger it (and is firewalled off in a real deployment anyway).
    with TestClient(app, base_url="http://rwr.example.com", client=("192.0.2.50", 5555)) as c:
        assert c.get("/login", headers={"X-Forwarded-Proto": "http"}).status_code == 200


# --- 7. optional admin network allow-list ---

def test_admin_ui_can_be_limited_to_networks(monkeypatch, cfg):
    app = _app(monkeypatch, RWR_ADMIN_NETWORKS="192.168.1.0/24,fd00::/8")
    with TestClient(app, base_url="https://testserver", client=("203.0.113.9", 5555)) as c:
        assert c.get("/login").status_code == 404
        assert c.post("/login", data={"username": "admin", "password": ADMIN_PW}).status_code == 404
        assert c.get("/api/v1/device").status_code == 401        # the phone API is unaffected
        assert c.get("/healthz").status_code == 200
    with TestClient(app, base_url="https://testserver", client=("192.168.1.20", 5555)) as c:
        assert login(c).status_code == 303


# --- 8. uploads: rate limit and storage cap ---

def test_upload_rate_limit_per_device(admin, app, client, monkeypatch):
    import rwr_server.api as api
    monkeypatch.setattr(api, "MAX_UPLOADS_PER_HOUR", 3)
    _, token = paired(admin, app, client)
    codes = [client.post("/api/v1/media", headers=auth(token),
                         files={"file": ("x.jpg", b"\xff\xd8\xff\xe0fake", "image/jpeg")},
                         data={"kind": "photo"}).status_code for _ in range(5)]
    assert codes[:2] == [200, 200] and 429 in codes


def test_event_storage_is_capped(app, tmp_path):
    store = EventStore(app.state.db, tmp_path / "ev", max_bytes=2500)
    ids = [store.record(device_id=None, at=time.time() + i, kind="photo", data=b"\xff\xd8" + b"x" * 1000,
                        onlookers=1, mode=None, source="upload", want_immich=False) for i in range(5)]
    kept = [r["id"] for r in app.state.db.all("SELECT id FROM events WHERE file IS NOT NULL ORDER BY id")]
    assert kept == ids[-2:]
    assert sum(p.stat().st_size for p in (tmp_path / "ev").iterdir()) <= 2500
