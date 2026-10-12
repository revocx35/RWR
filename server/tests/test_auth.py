import time

import pyotp

from rwr_server import security
from conftest import ADMIN_PW, csrf_of, login


def test_security_headers(client):
    r = client.get("/login")
    h = r.headers
    assert "default-src 'none'" in h["content-security-policy"]
    assert "frame-ancestors 'none'" in h["content-security-policy"]
    assert h["x-frame-options"] == "DENY"
    assert h["x-content-type-options"] == "nosniff"
    assert h["referrer-policy"] == "same-origin"
    assert h["cache-control"] == "no-store"
    assert "strict-transport-security" in h
    assert "server" not in h


def test_pages_require_login(client):
    for path in ("/", "/devices", "/settings", "/events", "/account", "/events/1/media"):
        r = client.get(path, follow_redirects=False)
        assert r.status_code == 303 and r.headers["location"] == "/login", path


def test_login_cookie_flags(client):
    r = login(client)
    assert r.status_code == 303
    cookie = r.headers["set-cookie"]
    assert cookie.startswith("__Host-rwr_session=")
    for flag in ("Secure", "HttpOnly", "SameSite=strict", "Path=/"):
        assert flag.lower() in cookie.lower()
    assert "domain" not in cookie.lower()


def test_wrong_password_is_generic_and_wrong_user_too(client):
    a = login(client, password="nope nope nope")
    b = login(client, username="root", password=ADMIN_PW)
    assert a.status_code == b.status_code == 401
    assert "Wrong username or password." in a.text and "Wrong username or password." in b.text


def test_login_requires_form_token(client):
    client.get("/login")
    r = client.post("/login", data={"username": "admin", "password": ADMIN_PW, "csrf": "forged"},
                    follow_redirects=False)
    assert r.status_code == 400


def test_browser_login_with_origin_header(client):
    """Real browsers send Origin on POST; a same-origin one must pass, null or foreign ones must not."""
    def attempt(origin):
        token = csrf_of(client.get("/login").text)
        return client.post("/login", data={"username": "admin", "password": ADMIN_PW, "csrf": token},
                           headers={"Origin": origin}, follow_redirects=False)
    assert attempt("https://evil.example").status_code == 400
    assert attempt("null").status_code == 400
    assert attempt("https://testserver").status_code == 303


def test_two_login_tabs_both_work(client):
    first = csrf_of(client.get("/login").text)
    second = csrf_of(client.get("/login").text)  # second tab must not invalidate the first form
    assert first == second
    r = client.post("/login", data={"username": "admin", "password": ADMIN_PW, "csrf": first}, follow_redirects=False)
    assert r.status_code == 303


def test_lockout_after_failures_even_with_right_password(client):
    for _ in range(security.IP_MAX_FAILURES):
        assert login(client, password="wrong password!").status_code == 401
    r = login(client)
    assert r.status_code == 429
    assert "Too many attempts" in r.text


def test_session_rotation_and_logout(admin, app):
    old = admin.cookies.get("__Host-rwr_session")
    page = admin.get("/")
    assert page.status_code == 200
    admin.post("/logout", data={"csrf": csrf_of(page.text)})
    # The old session id must be dead server-side, not just deleted from the browser.
    admin.cookies.set("__Host-rwr_session", old)
    assert admin.get("/", follow_redirects=False).status_code == 303


def test_idle_timeout(admin, app):
    app.state.db.execute("UPDATE sessions SET last_seen = ?", (time.time() - security.SESSION_IDLE_S - 5,))
    assert admin.get("/", follow_redirects=False).headers["location"] == "/login"


def test_absolute_timeout(admin, app):
    app.state.db.execute("UPDATE sessions SET created = ?", (time.time() - security.SESSION_ABSOLUTE_S - 5,))
    assert admin.get("/", follow_redirects=False).headers["location"] == "/login"


def test_post_without_csrf_is_rejected(admin, app):
    r = admin.post("/settings", data={"retention_days": "30", "min_face_px": "16", "detector_score": "0.6",
                                      "keep_events": "on"}, follow_redirects=False)
    assert r.status_code == 303
    assert app.state.db.get_setting("server") is None


def test_cross_origin_post_is_rejected(admin, app):
    csrf = csrf_of(admin.get("/settings").text)
    r = admin.post("/settings", data={"csrf": csrf, "retention_days": "30", "min_face_px": "16",
                                      "detector_score": "0.6"},
                   headers={"Origin": "https://evil.example"}, follow_redirects=False)
    assert r.status_code == 303
    assert app.state.db.get_setting("server") is None


def test_settings_save_with_csrf(admin, app):
    csrf = csrf_of(admin.get("/settings").text)
    r = admin.post("/settings", data={"csrf": csrf, "retention_days": "30", "min_face_px": "16",
                                      "detector_score": "0.6", "keep_events": "on"},
                   headers={"Origin": "https://testserver"}, follow_redirects=False)
    assert r.headers["location"] == "/settings?msg=saved"
    assert app.state.db.get_setting("server")["retention_days"] == 30


def test_must_change_password_flow(client, app):
    app.state.db.execute("UPDATE admin SET must_change = 1")
    login(client)
    assert client.get("/", follow_redirects=False).headers["location"] == "/account"
    csrf = csrf_of(client.get("/account").text)
    weak = client.post("/account/password", data={"csrf": csrf, "current": ADMIN_PW, "new": "short", "confirm": "short"})
    assert weak.status_code == 400
    new = "a much longer passphrase 42"
    ok = client.post("/account/password", data={"csrf": csrf, "current": ADMIN_PW, "new": new, "confirm": new},
                     follow_redirects=False)
    assert ok.status_code == 303
    assert client.get("/").status_code == 200
    # Old password no longer works, new one does.
    client.cookies.clear()
    assert login(client).status_code == 401
    assert login(client, password=new).status_code == 303


def test_password_change_signs_out_other_sessions(app, admin):
    from fastapi.testclient import TestClient
    with TestClient(app, base_url="https://testserver") as other:
        assert login(other).status_code == 303
        csrf = csrf_of(admin.get("/account").text)
        new = "another strong passphrase 7"
        admin.post("/account/password", data={"csrf": csrf, "current": ADMIN_PW, "new": new, "confirm": new})
        assert other.get("/", follow_redirects=False).status_code == 303
        assert admin.get("/").status_code == 200


def test_totp_enable_login_and_replay(admin, app, client):
    csrf = csrf_of(admin.get("/account").text)
    page = admin.post("/account/totp/start", data={"csrf": csrf})
    secret = app.state.db.get_setting("totp_pending")["secret"]
    assert secret in page.text
    bad = admin.post("/account/totp/confirm", data={"csrf": csrf, "code": "000000"})
    assert bad.status_code == 400
    code = pyotp.TOTP(secret).now()
    r = admin.post("/account/totp/confirm", data={"csrf": csrf, "code": code}, follow_redirects=False)
    assert r.headers["location"] == "/account?msg=totp_on"

    admin.cookies.clear()
    r = login(admin)
    assert r.headers["location"] == "/login/totp"
    # Half-authenticated session can't reach admin pages.
    assert admin.get("/", follow_redirects=False).headers["location"] == "/login"
    page = admin.get("/login/totp")
    # The code used at enrolment can't be replayed.
    replay = admin.post("/login/totp", data={"csrf": csrf_of(page.text), "code": code})
    assert replay.status_code == 401
    # Next time step's code works (simulate by resetting the last step).
    app.state.db.execute("UPDATE admin SET totp_last_step = 0")
    ok = admin.post("/login/totp", data={"csrf": csrf_of(page.text), "code": pyotp.TOTP(secret).now()},
                    follow_redirects=False)
    assert ok.headers["location"] == "/"
    assert admin.get("/").status_code == 200


def test_audit_log_records_failures(client, app):
    login(client, password="definitely wrong")
    rows = app.state.db.all("SELECT action FROM audit")
    assert "login_failed" in [r["action"] for r in rows]


def test_password_policy():
    assert security.password_problem("short")
    assert security.password_problem("aaaaaaaaaaaaaaaa")
    assert security.password_problem("my admin password", "admin")
    assert security.password_problem("a fine long passphrase") is None
