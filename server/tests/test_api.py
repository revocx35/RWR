import json
import struct
import time

import cv2
import numpy as np
import pytest
from starlette.websockets import WebSocketDisconnect

from rwr_server import security
from conftest import csrf_of


def pair_code(admin, app, can_configure=True):
    csrf = csrf_of(admin.get("/devices").text)
    data = {"csrf": csrf}
    if can_configure:
        data["can_configure"] = "on"
    page = admin.post("/devices/pair", data=data)
    assert page.status_code == 200
    import html, re, urllib.parse
    link = html.unescape(re.search(r"<textarea readonly rows=\"4\">([^<]+)</textarea>", page.text).group(1))
    q = urllib.parse.parse_qs(urllib.parse.urlsplit(link).query)
    assert q["p"][0] == app.state.pin
    return q["c"][0]


def paired(admin, app, client, can_configure=True):
    code = pair_code(admin, app, can_configure)
    r = client.post("/api/v1/pair", json={"code": code, "name": "Test phone"})
    assert r.status_code == 200, r.text
    return code, r.json()["token"]


def auth(token):
    return {"Authorization": f"Bearer {token}"}


def test_pairing_code_works_once(admin, app, client):
    code, token = paired(admin, app, client)
    again = client.post("/api/v1/pair", json={"code": code, "name": "Thief"})
    assert again.status_code == 401
    assert client.get("/api/v1/device", headers=auth(token)).json()["name"] == "Test phone"
    # Only the hash is stored.
    assert app.state.db.one("SELECT token_hash FROM devices")["token_hash"] == security.token_hash(token)


def test_reverse_proxy_mode_pairs_without_pin(cfg, monkeypatch):
    """RWR_TLS=off: no own certificate, the pairing link carries the proxy's https URL and no pin."""
    import html, re, urllib.parse
    from fastapi.testclient import TestClient
    from rwr_server.app import create_app
    from conftest import FakeDetector, ADMIN_PW, login
    monkeypatch.setenv("RWR_TLS", "off")
    monkeypatch.setenv("RWR_PUBLIC_URL", "https://rwr.example.com")
    monkeypatch.setenv("RWR_TRUSTED_PROXIES", "10.9.8.7/32")
    from rwr_server.config import Config
    app = create_app(Config(), detector=FakeDetector())
    app.state.db.execute("INSERT INTO admin(id, username, password_hash, must_change, password_changed_at) "
                         "VALUES(1, 'admin', ?, 0, 0)", (security.hash_password(ADMIN_PW),))
    assert app.state.pin is None
    with TestClient(app, base_url="https://rwr.example.com", client=("10.9.8.7", 5555)) as c:
        assert login(c).status_code == 303
        page = c.post("/devices/pair", data={"csrf": csrf_of(c.get("/devices").text)})
        link = html.unescape(re.search(r'<textarea readonly rows="4">([^<]+)</textarea>', page.text).group(1))
        q = urllib.parse.parse_qs(urllib.parse.urlsplit(link).query)
        assert q["u"] == ["https://rwr.example.com"] and "p" not in q
        # Client IP comes from X-Forwarded-For when the peer is the trusted proxy (rate limiting per real client).
        c.cookies.clear()
        token = csrf_of(c.get("/login").text)
        c.post("/login", data={"username": "admin", "password": "wrong wrong wrong", "csrf": token},
               headers={"X-Forwarded-For": "192.0.2.10"})
        assert app.state.db.one("SELECT ip FROM audit WHERE action = 'login_failed'")["ip"] == "192.0.2.10"


def test_expired_pairing_code(admin, app, client):
    code = pair_code(admin, app)
    app.state.db.execute("UPDATE pairing_codes SET expires = ?", (time.time() - 1,))
    assert client.post("/api/v1/pair", json={"code": code}).status_code == 401


def test_bad_tokens_get_locked_out(client):
    for _ in range(security.PAIR_MAX_FAILURES):
        assert client.get("/api/v1/device", headers=auth("x" * 43)).status_code == 401
    r = client.get("/api/v1/device", headers=auth("y" * 43))
    assert "too many" in r.json()["error"]


def test_token_in_query_string_is_not_accepted(admin, app, client):
    _, token = paired(admin, app, client)
    assert client.get(f"/api/v1/device?token={token}").status_code == 401


def test_revoked_device_is_rejected(admin, app, client):
    _, token = paired(admin, app, client)
    dev_id = client.get("/api/v1/device", headers=auth(token)).json()["device_id"]
    csrf = csrf_of(admin.get("/devices").text)
    admin.post(f"/devices/{dev_id}/revoke", data={"csrf": csrf})
    assert client.get("/api/v1/device", headers=auth(token)).status_code == 401


def test_config_requires_permission(admin, app, client):
    _, token = paired(admin, app, client, can_configure=False)
    assert client.get("/api/v1/config", headers=auth(token)).status_code == 403
    assert client.put("/api/v1/config", headers=auth(token), json={"retention_days": 3}).status_code == 403


def test_config_roundtrip_and_api_key_is_write_only(admin, app, client):
    _, token = paired(admin, app, client)
    r = client.put("/api/v1/config", headers=auth(token), json={
        "immich_url": "https://immich.example.lan", "immich_api_key": "secret-key-123", "immich_enabled": True,
    })
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["immich_api_key_set"] is True and "immich_api_key" not in body
    assert "secret-key-123" not in client.get("/api/v1/config", headers=auth(token)).text
    assert "secret-key-123" not in admin.get("/settings").text
    # Empty key keeps the stored one.
    client.put("/api/v1/config", headers=auth(token), json={"immich_api_key": ""})
    assert app.state.db.get_setting("server")["immich_api_key"] == "secret-key-123"


def test_config_validation(admin, app, client):
    _, token = paired(admin, app, client)
    bad = [
        {"immich_url": "http://immich.lan"},                    # http without opt-in
        {"immich_url": "ftp://x"},
        {"immich_url": "https://user:pw@immich.lan"},
        {"retention_days": 0},
        {"retention_days": "7"},
        {"unknown": 1},
        {"immich_enabled": True},                              # no URL/key yet
    ]
    for change in bad:
        assert client.put("/api/v1/config", headers=auth(token), json=change).status_code == 400, change
    ok = client.put("/api/v1/config", headers=auth(token), json={"immich_url": "http://immich.lan",
                                                                  "immich_allow_http": True})
    assert ok.status_code == 200


def test_media_upload_validates_type(admin, app, client):
    _, token = paired(admin, app, client)
    bad = client.post("/api/v1/media", headers=auth(token), files={"file": ("x.jpg", b"not a jpeg", "image/jpeg")},
                      data={"kind": "photo"})
    assert bad.status_code == 400
    ok = client.post("/api/v1/media", headers=auth(token),
                     files={"file": ("x.jpg", b"\xff\xd8\xff\xe0fakejpeg", "image/jpeg")},
                     data={"kind": "photo", "taken_at": str(int(time.time() * 1000))})
    assert ok.status_code == 200
    ev = app.state.db.one("SELECT * FROM events")
    # Immich not enabled -> recorded as an error, not silently dropped.
    assert ev["source"] == "upload" and ev["immich_status"] == "error"


def test_media_requires_token(client):
    r = client.post("/api/v1/media", files={"file": ("x.jpg", b"\xff\xd8", "image/jpeg")}, data={"kind": "photo"})
    assert r.status_code == 401


def test_event_media_served_only_to_admin(admin, app, client):
    _, token = paired(admin, app, client)
    client.post("/api/v1/media", headers=auth(token),
                files={"file": ("x.jpg", b"\xff\xd8\xff\xe0fakejpeg", "image/jpeg")}, data={"kind": "photo"})
    ev = app.state.db.one("SELECT id FROM events")["id"]
    assert admin.get(f"/events/{ev}/media").content.startswith(b"\xff\xd8")
    admin.cookies.clear()
    assert admin.get(f"/events/{ev}/media", follow_redirects=False).status_code == 303


def test_stream_rejects_missing_token(client):
    with pytest.raises(WebSocketDisconnect):
        with client.websocket_connect("/api/v1/stream") as ws:
            ws.receive_text()


def _frame(seq: int) -> bytes:
    img = np.full((240, 320, 3), 128, np.uint8)
    ok, jpg = cv2.imencode(".jpg", img)
    return struct.pack(">BBBBIq", 1, 0, 1, 0, seq, int(time.time() * 1000)) + jpg.tobytes()


def test_stream_alerts_on_onlooker(admin, app, client):
    _, token = paired(admin, app, client)
    with client.websocket_connect("/api/v1/stream", headers=auth(token)) as ws:
        assert json.loads(ws.receive_text())["type"] == "ready"
        ws.send_text(json.dumps({"type": "hello", "settings": {"mode": "eye", "media": "photo", "cooldown_sec": 3}}))
        alert = None
        for seq in range(1, 8):
            ws.send_bytes(_frame(seq))
            time.sleep(0.06)
        for _ in range(3):
            msg = json.loads(ws.receive_text())
            if msg["type"] == "alert":
                alert = msg
                break
        assert alert is not None
        assert alert["faces"][0]["box"] == [150, 20, 190, 70]
    # keep_events defaults on: the annotated snapshot is stored.
    deadline = time.time() + 3
    while time.time() < deadline and not app.state.db.one("SELECT 1 FROM events"):
        time.sleep(0.05)
    ev = app.state.db.one("SELECT * FROM events")
    assert ev["kind"] == "photo" and ev["source"] == "server"


def test_stream_rejects_oversized_text(admin, app, client):
    _, token = paired(admin, app, client)
    with client.websocket_connect("/api/v1/stream", headers=auth(token)) as ws:
        ws.receive_text()
        ws.send_text("x" * 20000)
        with pytest.raises(WebSocketDisconnect):
            ws.receive_text()


def test_stream_debug_messages_only_when_asked(admin, app, client):
    _, token = paired(admin, app, client)
    with client.websocket_connect("/api/v1/stream", headers=auth(token)) as ws:
        assert json.loads(ws.receive_text())["type"] == "ready"
        ws.send_text(json.dumps({"type": "hello", "settings": {"mode": "head", "debug": True, "cooldown_sec": 600}}))
        ws.send_bytes(_frame(1))
        msg = json.loads(ws.receive_text())
        assert msg["type"] == "debug" and msg["seq"] == 1 and msg["mode"] == "head"
        assert [f["role"] for f in msg["faces"]] == ["owner", "onlooker"]
        # Turned off again: the next frame (the onlooker's second) brings the alert, and no debug message.
        ws.send_text(json.dumps({"type": "settings", "settings": {"mode": "head", "debug": False, "cooldown_sec": 600}}))
        time.sleep(0.1)
        ws.send_bytes(_frame(2))
        assert json.loads(ws.receive_text())["type"] == "alert"
