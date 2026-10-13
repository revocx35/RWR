"""Admin web UI: login (+ optional TOTP), dashboard, devices/pairing, settings, events, account."""
from __future__ import annotations

import asyncio
import secrets
import time
import urllib.parse

import pyotp
import segno
from fastapi import APIRouter, Request
from fastapi.responses import FileResponse, RedirectResponse, Response, StreamingResponse

from . import immich, security
from . import settings as server_settings
from .security import COOKIE_NAME

router = APIRouter()
PRE_COOKIE = "__Host-rwr_pre"       # login-form CSRF (double submit) before a session exists
PAIRING_TTL_S = 10 * 60
FLASH = {
    "saved": "Settings saved.",
    "password": "Password changed. Other sessions were signed out.",
    "totp_on": "Two-factor authentication is on.",
    "totp_off": "Two-factor authentication is off.",
    "revoked": "Device removed. It can no longer connect.",
    "deleted": "Event deleted.",
    "perm": "Device permission updated.",
}


class Redirect(Exception):
    def __init__(self, url: str):
        self.url = url


def _db(request: Request):
    return request.app.state.db


def client_ip(request: Request) -> str:
    peer = request.client.host if request.client else ""
    xff = request.headers.get("x-forwarded-for")
    if xff and request.app.state.cfg.is_trusted_proxy(peer):
        return xff.split(",")[-1].strip()[:64]
    return peer


def _auth_failure(db, ip: str, action: str, detail: str = "", totp: bool = False) -> None:
    """Counts a failed password or 2FA check against the client and against the global budget."""
    security.record_failure(db, security.throttle_key(ip), security.IP_MAX_FAILURES, security.IP_WINDOW_S,
                            security.IP_LOCK_S)
    if totp:
        security.record_failure(db, "totp", security.TOTP_GLOBAL_MAX_FAILURES, security.GLOBAL_WINDOW_S,
                                security.GLOBAL_LOCK_S)
    else:
        security.record_failure(db, "user", security.GLOBAL_MAX_FAILURES, security.GLOBAL_WINDOW_S,
                                security.GLOBAL_LOCK_S)
    db.audit(ip, action, detail)


def _locked(db, ip: str, totp: bool = False) -> float:
    return max(security.locked_for(db, security.throttle_key(ip)), security.locked_for(db, "totp" if totp else "user"))


def _render(request: Request, name: str, ctx: dict | None = None, status: int = 200) -> Response:
    ctx = dict(ctx or {})
    ctx.setdefault("flash", FLASH.get(request.query_params.get("msg", "")))
    return request.app.state.templates.TemplateResponse(request, name, ctx, status_code=status)


def _set_session_cookie(resp: Response, raw: str) -> None:
    resp.set_cookie(COOKIE_NAME, raw, secure=True, httponly=True, samesite="strict", path="/")


def _admin_row(request: Request):
    return _db(request).one("SELECT * FROM admin WHERE id = 1")


def _session(request: Request, stage: str = "full") -> security.Session | None:
    s = security.load_session(_db(request), request.cookies.get(COOKIE_NAME))
    return s if s and s.stage == stage else None


def _same_origin(request: Request) -> bool:
    origin = request.headers.get("origin")
    if origin is None or origin == "null":
        # Browsers send Origin on POST; fall back to Referer when a privacy setting strips it.
        ref = request.headers.get("referer")
        if ref is None:
            return origin is None
        origin = ref
    return urllib.parse.urlsplit(origin).netloc == request.headers.get("host")


async def _admin_form(request: Request, allow_must_change: bool = False):
    """For POSTs: valid full session + CSRF token + same origin. Returns (session, form)."""
    s = _session(request)
    if s is None:
        raise Redirect("/login")
    form = await request.form()
    if not _same_origin(request) or not security.same(str(form.get("csrf", "")), s.csrf):
        _db(request).audit(client_ip(request), "csrf_rejected", request.url.path)
        raise Redirect("/?msg=")
    if not allow_must_change and _admin_row(request)["must_change"]:
        raise Redirect("/account")
    return s, form


def _admin_page(request: Request, allow_must_change: bool = False) -> security.Session:
    s = _session(request)
    if s is None:
        raise Redirect("/login")
    if not allow_must_change and _admin_row(request)["must_change"]:
        raise Redirect("/account")
    return s


def _wrap(handler):
    """Turns Redirect exceptions into 303 responses (keeps the handlers linear)."""
    import functools

    @functools.wraps(handler)
    async def inner(request: Request, *a, **kw):
        try:
            return await handler(request, *a, **kw)
        except Redirect as r:
            return RedirectResponse(r.url, status_code=303)
    return inner


# ---------------------------------------------------------------- login

@router.get("/login")
@_wrap
async def login_page(request: Request):
    if _session(request):
        raise Redirect("/")
    return _login_form(request)


def _login_form(request: Request, error: str | None = None, status: int = 200) -> Response:
    # Reuse a still-valid token so two open login tabs don't invalidate each other's form.
    existing = request.cookies.get(PRE_COOKIE, "")
    pre = existing if 20 <= len(existing) <= 64 and existing.replace("-", "").replace("_", "").isalnum() \
        else security.new_token(24)
    resp = _render(request, "login.html", {"csrf": pre, "error": error, "public": True}, status)
    resp.set_cookie(PRE_COOKIE, pre, secure=True, httponly=True, samesite="strict", path="/", max_age=900)
    return resp


@router.post("/login")
@_wrap
async def login(request: Request):
    db = _db(request)
    ip = client_ip(request)
    form = await request.form()
    pre = request.cookies.get(PRE_COOKIE, "")
    if not pre or not security.same(str(form.get("csrf", "")), pre) or not _same_origin(request):
        return _login_form(request, "Your session expired. Please try again.", 400)
    username = str(form.get("username", ""))[:100]
    password = str(form.get("password", ""))[:256]
    ip_key = security.throttle_key(ip)
    async with request.app.state.auth_lock:  # lockout check, password check and failure count happen as one step
        wait = max(security.locked_for(db, ip_key), security.locked_for(db, "user"))
        if wait:
            db.audit(ip, "login_locked")
            return _login_form(request, f"Too many attempts. Try again in {int(wait // 60) + 1} min.", 429)
        admin = _admin_row(request)
        # Always verify (against a dummy hash if needed) so timing doesn't reveal the username.
        pw_ok = await asyncio.to_thread(security.verify_password, admin["password_hash"] if admin else None, password)
        ok = bool(admin and pw_ok and security.same(username, admin["username"]))
        if not ok:
            _auth_failure(db, ip, "login_failed", username[:40])
    if not ok:
        await asyncio.sleep(0.5 + secrets.randbelow(500) / 1000)
        return _login_form(request, "Wrong username or password.", 401)

    if security.needs_rehash(admin["password_hash"]):
        db.execute("UPDATE admin SET password_hash = ? WHERE id = 1", (security.hash_password(password),))
    stage = "totp" if admin["totp_enabled"] else "full"
    raw, _ = security.create_session(db, stage, ip, request.headers.get("user-agent"))
    if stage == "full":
        security.clear_failures(db, ip_key)
        db.audit(ip, "login_ok")
    resp = RedirectResponse("/login/totp" if stage == "totp" else "/", status_code=303)
    _set_session_cookie(resp, raw)
    resp.delete_cookie(PRE_COOKIE, path="/", secure=True, httponly=True, samesite="strict")
    return resp


@router.get("/login/totp")
@_wrap
async def totp_page(request: Request):
    s = _session(request, "totp")
    if s is None:
        raise Redirect("/login")
    return _render(request, "totp.html", {"csrf": s.csrf, "public": True})


@router.post("/login/totp")
@_wrap
async def totp_submit(request: Request):
    db = _db(request)
    ip = client_ip(request)
    s = _session(request, "totp")
    if s is None:
        raise Redirect("/login")
    form = await request.form()
    if not _same_origin(request) or not security.same(str(form.get("csrf", "")), s.csrf):
        raise Redirect("/login")
    async with request.app.state.auth_lock:
        if _locked(db, ip, totp=True):
            security.delete_session(db, s.id_hash)
            raise Redirect("/login")
        admin = _admin_row(request)
        step = _verify_totp(admin["totp_secret"], str(form.get("code", "")), admin["totp_last_step"])
        if step is None:
            _auth_failure(db, ip, "totp_failed", totp=True)
        else:
            db.execute("UPDATE admin SET totp_last_step = ? WHERE id = 1", (step,))
    if step is None:
        await asyncio.sleep(0.5)
        return _render(request, "totp.html", {"csrf": s.csrf, "error": "Wrong code.", "public": True}, 401)
    security.delete_session(db, s.id_hash)  # rotate: the half-authenticated session id is never upgraded
    raw, _ = security.create_session(db, "full", ip, request.headers.get("user-agent"))
    security.clear_failures(db, security.throttle_key(ip))
    db.audit(ip, "login_ok", "with 2FA")
    resp = RedirectResponse("/", status_code=303)
    _set_session_cookie(resp, raw)
    return resp


def _verify_totp(secret: str | None, code: str, last_step: int) -> int | None:
    """Returns the matched time step, refusing codes at or before the last used one (replay)."""
    code = code.strip().replace(" ", "")
    if not secret or not code.isdigit() or len(code) != 6:
        return None
    totp = pyotp.TOTP(secret)
    now_step = int(time.time()) // 30
    for step in (now_step - 1, now_step, now_step + 1):
        if step > last_step and security.same(totp.at(step * 30), code):
            return step
    return None


@router.post("/logout")
@_wrap
async def logout(request: Request):
    s = _session(request)
    if s is not None:
        form = await request.form()
        if _same_origin(request) and security.same(str(form.get("csrf", "")), s.csrf):
            security.delete_session(_db(request), s.id_hash)
            _db(request).audit(client_ip(request), "logout")
    resp = RedirectResponse("/login", status_code=303)
    resp.delete_cookie(COOKIE_NAME, path="/", secure=True, httponly=True, samesite="strict")
    return resp


# ---------------------------------------------------------------- dashboard

@router.get("/")
@_wrap
async def dashboard(request: Request):
    s = _admin_page(request)
    db = _db(request)
    return _render(request, "dashboard.html", {
        "csrf": s.csrf,
        "streams": request.app.state.hub.status(),
        "devices": db.one("SELECT COUNT(*) AS n FROM devices WHERE revoked = 0")["n"],
        "events": db.all("SELECT * FROM events ORDER BY at DESC LIMIT 8"),
        "audit": db.all("SELECT * FROM audit ORDER BY id DESC LIMIT 15"),
        "fingerprint": request.app.state.fingerprint,
        "settings": server_settings.public_view(server_settings.load(db)),
    })


# ---------------------------------------------------------------- devices & pairing

@router.get("/devices")
@_wrap
async def devices(request: Request):
    s = _admin_page(request)
    rows = _db(request).all("SELECT * FROM devices WHERE revoked = 0 ORDER BY created DESC")
    online = {d["device_id"] for d in request.app.state.hub.status()}
    return _render(request, "devices.html", {"csrf": s.csrf, "devices": rows, "online": online})


@router.post("/devices/pair")
@_wrap
async def pair_new(request: Request):
    s, form = await _admin_form(request)
    db = _db(request)
    code = security.new_token(16)
    can_configure = form.get("can_configure") == "on"
    now = time.time()
    db.execute("INSERT INTO pairing_codes(code_hash, created, expires, can_configure) VALUES(?, ?, ?, ?)",
               (security.token_hash(code), now, now + PAIRING_TTL_S, int(can_configure)))
    db.audit(client_ip(request), "pairing_code_created", "can_configure" if can_configure else "")
    cfg = request.app.state.cfg
    base = cfg.public_url or str(request.base_url).rstrip("/")
    query = {"u": base, "c": code}
    if cfg.pairing_pin == "auto" and request.app.state.pin:
        query["p"] = request.app.state.pin
    link = "rwr://pair?" + urllib.parse.urlencode(query)
    qr = segno.make(link, error="m").svg_data_uri(scale=6, border=2)
    return _render(request, "pair.html", {"csrf": s.csrf, "insecure_base": not base.startswith("https://"), "qr": qr, "link": link, "minutes": PAIRING_TTL_S // 60, "base": base})


@router.post("/devices/{device_id}/revoke")
@_wrap
async def revoke(request: Request, device_id: int):
    await _admin_form(request)
    _db(request).execute("UPDATE devices SET revoked = 1 WHERE id = ?", (device_id,))
    _db(request).audit(client_ip(request), "device_revoked", str(device_id))
    await request.app.state.hub.disconnect_device(device_id)
    raise Redirect("/devices?msg=revoked")


@router.post("/devices/{device_id}/permission")
@_wrap
async def permission(request: Request, device_id: int):
    _, form = await _admin_form(request)
    allow = form.get("can_configure") == "1"
    _db(request).execute("UPDATE devices SET can_configure = ? WHERE id = ?", (int(allow), device_id))
    _db(request).audit(client_ip(request), "device_permission", f"{device_id} can_configure={allow}")
    raise Redirect("/devices?msg=perm")


# ---------------------------------------------------------------- settings

@router.get("/settings")
@_wrap
async def settings_page(request: Request):
    s = _admin_page(request)
    cur = server_settings.public_view(server_settings.load(_db(request)))
    return _render(request, "settings.html", {"csrf": s.csrf, "s": cur})


@router.post("/settings")
@_wrap
async def settings_save(request: Request):
    s, form = await _admin_form(request)
    db = _db(request)
    changes = {
        "immich_enabled": form.get("immich_enabled") == "on",
        "immich_url": str(form.get("immich_url", "")),
        "immich_album": str(form.get("immich_album", "")),
        "immich_allow_http": form.get("immich_allow_http") == "on",
        "immich_ca_pem": str(form.get("immich_ca_pem", "")),
        "keep_events": form.get("keep_events") == "on",
        "immich_api_key": str(form.get("immich_api_key", "")),
        "web_live_view": form.get("web_live_view") == "on",
    }
    try:
        changes["retention_days"] = int(form.get("retention_days", 7))
        changes["min_face_px"] = int(form.get("min_face_px", 16))
        changes["detector_score"] = float(form.get("detector_score", 0.6))
        if form.get("remove_api_key") == "on":
            stored = server_settings.load(db)
            stored["immich_api_key"] = ""
            stored["immich_enabled"] = False
            db.set_setting("server", stored)
            changes["immich_enabled"] = False
        server_settings.apply_update(db, changes)
    except (ValueError, server_settings.SettingsError) as e:
        cur = server_settings.public_view(server_settings.load(db))
        return _render(request, "settings.html", {"csrf": s.csrf, "s": cur, "error": str(e)}, 400)
    db.audit(client_ip(request), "settings_changed", "web")
    raise Redirect("/settings?msg=saved")


@router.post("/settings/immich-test")
@_wrap
async def immich_test(request: Request):
    s, _ = await _admin_form(request)
    cur = server_settings.load(_db(request))
    try:
        result, ok = await asyncio.to_thread(immich.test_connection, cur), True
    except immich.ImmichError as e:
        result, ok = str(e), False
    return _render(request, "settings.html", {
        "csrf": s.csrf, "s": server_settings.public_view(cur), "test_result": result, "test_ok": ok,
    })


# ---------------------------------------------------------------- events

@router.get("/events")
@_wrap
async def events_page(request: Request):
    s = _admin_page(request)
    try:
        page = max(0, int(request.query_params.get("page", "0")))
    except ValueError:
        page = 0
    rows = _db(request).all(
        "SELECT e.*, d.name AS device FROM events e LEFT JOIN devices d ON d.id = e.device_id "
        "ORDER BY e.at DESC LIMIT 25 OFFSET ?", (page * 25,))
    return _render(request, "events.html", {"csrf": s.csrf, "events": rows, "page": page})


@router.get("/events/{event_id}/media")
@_wrap
async def event_media(request: Request, event_id: int):
    _admin_page(request)
    found = request.app.state.events.path_of(event_id)
    if not found:
        return Response(status_code=404)
    path, kind = found
    return FileResponse(path, media_type="video/mp4" if kind == "video" else "image/jpeg",
                        headers={"Cache-Control": "private, no-store"})


@router.post("/events/{event_id}/delete")
@_wrap
async def event_delete(request: Request, event_id: int):
    await _admin_form(request)
    request.app.state.events.delete(event_id)
    raise Redirect("/events?msg=deleted")


# ---------------------------------------------------------------- live view

LIVE_MAX_S = 10 * 60        # a live view ends after this; reload the page to continue
LIVE_MAX_VIEWERS = 2        # across all devices
LIVE_FPS = 5


@router.get("/live")
@_wrap
async def live_list(request: Request):
    s = _admin_page(request)
    enabled = server_settings.load(_db(request))["web_live_view"]
    return _render(request, "live.html", {"csrf": s.csrf, "enabled": enabled,
                                          "streams": request.app.state.hub.status() if enabled else []})


@router.get("/live/{device_id}")
@_wrap
async def live_page(request: Request, device_id: int):
    s = _admin_page(request)
    db = _db(request)
    if not server_settings.load(db)["web_live_view"]:
        raise Redirect("/live")
    dev = db.one("SELECT id, name FROM devices WHERE id = ? AND revoked = 0", (device_id,))
    if dev is None:
        return Response(status_code=404)
    return _render(request, "live.html", {"csrf": s.csrf, "enabled": True, "device": dev,
                                          "minutes": LIVE_MAX_S // 60})


@router.get("/live/{device_id}/stream")
@_wrap
async def live_stream(request: Request, device_id: int):
    """MJPEG stream of the server's analysed frames with every face annotated. No JavaScript needed: the page
    shows it in an <img>. Ends when the session ends, the setting is switched off, or after LIVE_MAX_S."""
    s = _admin_page(request)
    db = _db(request)
    hub = request.app.state.hub
    if not server_settings.load(db)["web_live_view"]:
        return Response("Live view is switched off in Settings.", status_code=403, media_type="text/plain")
    if db.one("SELECT 1 FROM devices WHERE id = ? AND revoked = 0", (device_id,)) is None:
        return Response(status_code=404)
    if hub.total_viewers() >= LIVE_MAX_VIEWERS:
        return Response("Too many live views open.", status_code=503, media_type="text/plain")
    ip = client_ip(request)
    hub.add_viewer(device_id)
    db.audit(ip, "live_view_started", f"device {device_id}")

    async def frames():
        from .detector import annotate_live, placeholder
        started = last_check = time.monotonic()
        last_n, last_sent, waiting_sent = -1, 0.0, 0.0
        try:
            while time.monotonic() - started < LIVE_MAX_S:
                now = time.monotonic()
                if now - last_check >= 1.0:
                    last_check = now
                    if not security.session_alive(db, s.id_hash) or not server_settings.load(db)["web_live_view"]:
                        break
                st = hub.active.get(device_id)
                live = st.live if st else None
                jpg = None
                if live and st.live_n != last_n and now - last_sent >= 1 / LIVE_FPS:
                    last_n, last_sent = st.live_n, now
                    header = f"LIVE  server - {live['mode']} mode - {live['ms']} ms - {len(live['faces'])} faces"
                    jpg = await asyncio.to_thread(_live_jpeg, annotate_live, live["img"], live["faces"], header)
                elif not live and now - waiting_sent >= 2.0:
                    waiting_sent = now
                    msg = "Waiting for the phone (detection must run on the server)" if st is None \
                        else "Waiting for frames..."
                    jpg = await asyncio.to_thread(_live_jpeg, lambda img, *_: img, placeholder(msg), [], "")
                if jpg:
                    yield (b"--frame\r\nContent-Type: image/jpeg\r\nContent-Length: " + str(len(jpg)).encode()
                           + b"\r\n\r\n" + jpg + b"\r\n")
                await asyncio.sleep(0.05)
        finally:
            hub.remove_viewer(device_id)
            db.audit(ip, "live_view_ended", f"device {device_id}")

    return StreamingResponse(frames(), media_type="multipart/x-mixed-replace; boundary=frame", headers={
        "Cache-Control": "no-store", "X-Accel-Buffering": "no",  # nginx (Proxy Manager) must not buffer it
    })


def _live_jpeg(draw, img, faces, header) -> bytes:
    import cv2
    ok, jpg = cv2.imencode(".jpg", draw(img, faces, header), [cv2.IMWRITE_JPEG_QUALITY, 80])
    return jpg.tobytes() if ok else b""


# ---------------------------------------------------------------- account

@router.get("/account")
@_wrap
async def account(request: Request):
    s = _admin_page(request, allow_must_change=True)
    admin = _admin_row(request)
    return _render(request, "account.html", {
        "csrf": s.csrf, "must_change": bool(admin["must_change"]), "totp_enabled": bool(admin["totp_enabled"]),
        "username": admin["username"], "min_len": security.MIN_PASSWORD_LEN,
    })


@router.post("/account/password")
@_wrap
async def change_password(request: Request):
    s, form = await _admin_form(request, allow_must_change=True)
    db = _db(request)
    admin = _admin_row(request)
    current, new, confirm = (str(form.get(k, ""))[:256] for k in ("current", "new", "confirm"))

    def fail(msg: str, status: int = 400):
        return _render(request, "account.html", {
            "csrf": s.csrf, "must_change": bool(admin["must_change"]), "totp_enabled": bool(admin["totp_enabled"]),
            "username": admin["username"], "min_len": security.MIN_PASSWORD_LEN, "error": msg,
        }, status)

    ip = client_ip(request)
    async with request.app.state.auth_lock:
        if _locked(db, ip):
            return fail("Too many attempts. Try again later.", 429)
        ok = await asyncio.to_thread(security.verify_password, admin["password_hash"], current)
        if not ok:
            _auth_failure(db, ip, "password_change_failed")
    if not ok:
        return fail("Current password is wrong.", 401)
    if new != confirm:
        return fail("The new passwords don't match.")
    if security.same(new, current):
        return fail("Choose a password different from the current one.")
    problem = security.password_problem(new, admin["username"])
    if problem:
        return fail(problem)
    db.execute("UPDATE admin SET password_hash = ?, must_change = 0, password_changed_at = ? WHERE id = 1",
               (await asyncio.to_thread(security.hash_password, new), time.time()))
    # Sign out everywhere, then give this browser a fresh session.
    security.delete_all_sessions(db)
    raw, _ = security.create_session(db, "full", client_ip(request), request.headers.get("user-agent"))
    db.audit(client_ip(request), "password_changed")
    resp = RedirectResponse("/account?msg=password", status_code=303)
    _set_session_cookie(resp, raw)
    return resp


@router.post("/account/totp/start")
@_wrap
async def totp_start(request: Request):
    s, _ = await _admin_form(request)
    secret = pyotp.random_base32()
    _db(request).set_setting("totp_pending", {"secret": secret, "at": time.time()})
    uri = pyotp.TOTP(secret).provisioning_uri(name=_admin_row(request)["username"], issuer_name="RWR server")
    qr = segno.make(uri, error="m").svg_data_uri(scale=5, border=2)
    return _render(request, "totp_setup.html", {"csrf": s.csrf, "qr": qr, "secret": secret})


@router.post("/account/totp/confirm")
@_wrap
async def totp_confirm(request: Request):
    s, form = await _admin_form(request)
    db = _db(request)
    pending = db.get_setting("totp_pending")
    if not pending or time.time() - pending["at"] > 900:
        raise Redirect("/account")
    step = _verify_totp(pending["secret"], str(form.get("code", "")), 0)
    if step is None:
        uri = pyotp.TOTP(pending["secret"]).provisioning_uri(name=_admin_row(request)["username"],
                                                             issuer_name="RWR server")
        qr = segno.make(uri, error="m").svg_data_uri(scale=5, border=2)
        return _render(request, "totp_setup.html", {
            "csrf": s.csrf, "qr": qr, "secret": pending["secret"], "error": "Wrong code, try again.",
        }, 400)
    db.execute("UPDATE admin SET totp_secret = ?, totp_enabled = 1, totp_last_step = ? WHERE id = 1",
               (pending["secret"], step))
    db.execute("DELETE FROM settings WHERE key = 'totp_pending'")
    db.audit(client_ip(request), "totp_enabled")
    raise Redirect("/account?msg=totp_on")


@router.post("/account/totp/disable")
@_wrap
async def totp_disable(request: Request):
    s, form = await _admin_form(request)
    db = _db(request)
    admin = _admin_row(request)
    ip = client_ip(request)
    async with request.app.state.auth_lock:
        if _locked(db, ip) or _locked(db, ip, totp=True):
            ok_pw, step = False, None
        else:
            ok_pw = await asyncio.to_thread(security.verify_password, admin["password_hash"], str(form.get("current", "")))
            step = _verify_totp(admin["totp_secret"], str(form.get("code", "")), admin["totp_last_step"])
            if not ok_pw or step is None:
                _auth_failure(db, ip, "totp_disable_failed", totp=ok_pw)
    if not ok_pw or step is None:
        return _render(request, "account.html", {
            "csrf": s.csrf, "must_change": False, "totp_enabled": True, "username": admin["username"],
            "min_len": security.MIN_PASSWORD_LEN, "error": "Password or code is wrong.",
        }, 401)
    db.execute("UPDATE admin SET totp_secret = NULL, totp_enabled = 0 WHERE id = 1")
    db.audit(client_ip(request), "totp_disabled")
    raise Redirect("/account?msg=totp_off")
