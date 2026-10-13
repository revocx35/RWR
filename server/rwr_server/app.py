"""FastAPI application factory."""
from __future__ import annotations

import asyncio
import contextlib
import datetime
import logging
import tempfile
from pathlib import Path

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse, PlainTextResponse, RedirectResponse
from fastapi.staticfiles import StaticFiles
from fastapi.templating import Jinja2Templates

from . import security, tls
from .config import Config
from .db import DB
from .events import EventStore

log = logging.getLogger("rwr")
HERE = Path(__file__).parent

# Strict CSP: no inline scripts or styles, nothing from other origins, no framing.
CSP = (
    "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' data:; media-src 'self'; "
    "connect-src 'self'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'"
)
SECURITY_HEADERS = {
    "Content-Security-Policy": CSP,
    "Strict-Transport-Security": "max-age=31536000",
    "X-Content-Type-Options": "nosniff",
    "X-Frame-Options": "DENY",
    # same-origin, not no-referrer: with no-referrer browsers send "Origin: null" on form POSTs, which
    # would defeat the Origin check. Nothing is sent to other sites either way.
    "Referrer-Policy": "same-origin",
    "Permissions-Policy": "camera=(), microphone=(), geolocation=(), interest-cohort=()",
    "Cross-Origin-Opener-Policy": "same-origin",
    "Cross-Origin-Resource-Policy": "same-origin",
}


# Request body limits, enforced while the body streams in (Content-Length can be absent with chunked encoding).
# Without them one anonymous request to /api/v1/pair could make the server buffer gigabytes.
MAX_BODY_BYTES = 256 * 1024
MAX_UPLOAD_BODY_BYTES = 81 * 1024 * 1024     # /api/v1/media: 80 MB clip + multipart overhead


class _BodyTooLarge(Exception):
    pass


class BodyLimit:
    """Pure ASGI middleware: answers 413 as soon as a request body exceeds its limit."""

    def __init__(self, app):
        self.app = app

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http":
            return await self.app(scope, receive, send)
        limit = MAX_UPLOAD_BODY_BYTES if scope["path"] == "/api/v1/media" else MAX_BODY_BYTES
        declared = dict(scope["headers"]).get(b"content-length")
        if declared is not None:
            try:
                too_big = int(declared) > limit
            except ValueError:
                return await _reply(send, 400, b'{"error":"bad content-length"}')
            if too_big:
                return await _reply(send, 413, b'{"error":"request too large"}')
        received = 0
        started = False

        async def limited_receive():
            nonlocal received
            msg = await receive()
            if msg["type"] == "http.request":
                received += len(msg.get("body", b""))
                if received > limit:
                    raise _BodyTooLarge
            return msg

        async def tracking_send(msg):
            nonlocal started
            if msg["type"] == "http.response.start":
                started = True
            await send(msg)

        too_large = False
        try:
            await self.app(scope, limited_receive, tracking_send)
        except* _BodyTooLarge:  # may arrive wrapped in an ExceptionGroup by Starlette's task groups
            too_large = True
        if too_large and not started:
            await _reply(send, 413, b'{"error":"request too large"}')


async def _reply(send, status: int, body: bytes) -> None:
    await send({"type": "http.response.start", "status": status,
                "headers": [(b"content-type", b"application/json"), (b"content-length", str(len(body)).encode()),
                            (b"connection", b"close")]})
    await send({"type": "http.response.body", "body": body})


def create_app(cfg: Config, detector=None) -> FastAPI:
    """[detector] is injectable so tests can run without the ML models."""
    db = DB(cfg.db_path)
    events = EventStore(db, cfg.events_dir, cfg.events_max_mb * 1024 * 1024)
    if cfg.tls:
        cert = tls.load_cert(tls.ensure_certificate(cfg)[0])

    if detector is None:
        from .detector import Detector
        from . import settings as server_settings
        detector = Detector(
            str(cfg.models_dir / "face_detection_yunet_2023mar.onnx"),
            str(cfg.models_dir / "face_landmarker.task"),
            server_settings.load(db)["detector_score"],
        )
    from .stream import StreamHub
    tmp_dir = tempfile.gettempdir()
    hub = StreamHub(detector, events, db, cfg.detector_threads, cfg.max_devices_streaming, tmp_dir)

    @contextlib.asynccontextmanager
    async def lifespan(_app: FastAPI):
        task = asyncio.create_task(_housekeeping(db, events))
        yield
        task.cancel()

    app = FastAPI(lifespan=lifespan, docs_url=None, redoc_url=None, openapi_url=None)
    app.state.cfg = cfg
    app.state.db = db
    app.state.events = events
    app.state.hub = hub
    # One password/2FA check at a time. Without it, parallel requests all pass the lockout check before any
    # failure is recorded (a burst of 60 guesses got through where 5 are allowed), and each argon2 check takes
    # 64 MiB of memory.
    app.state.auth_lock = asyncio.Lock()
    # Behind a reverse proxy the phone sees the proxy's certificate, so there is nothing of ours to pin:
    # the app then validates the proxy's certificate against the phone's trusted CAs.
    app.state.pin = tls.spki_pin(cert) if cfg.tls else None
    app.state.fingerprint = tls.fingerprint(cert) if cfg.tls else None
    app.state.templates = Jinja2Templates(directory=str(HERE / "templates"))
    app.state.templates.env.filters["dt"] = lambda ts: datetime.datetime.fromtimestamp(ts).strftime("%Y-%m-%d %H:%M:%S")

    @app.middleware("http")
    async def headers(request: Request, call_next):
        response = _gatekeeper(request)
        if response is None:
            response = await call_next(request)
        for k, v in SECURITY_HEADERS.items():
            response.headers.setdefault(k, v)
        if not request.url.path.startswith("/static/"):
            response.headers.setdefault("Cache-Control", "no-store")
        return response

    app.add_middleware(BodyLimit)

    @app.exception_handler(413)
    async def too_big(_request, _exc):
        return JSONResponse({"error": "request too large"}, status_code=413)

    @app.get("/healthz", include_in_schema=False)
    async def healthz():
        return PlainTextResponse("ok")

    from . import api, web
    app.include_router(api.router)
    app.include_router(web.router)
    app.mount("/static", StaticFiles(directory=str(HERE / "static")), name="static")
    return app


def _gatekeeper(request: Request):
    """Proxy mode: plain-http requests are sent to https before a password can be typed into an http page.
    Optional RWR_ADMIN_NETWORKS: the admin UI answers only to those networks. Returns a response to stop."""
    cfg = request.app.state.cfg
    path = request.url.path
    peer = request.client.host if request.client else ""
    from_proxy = cfg.is_trusted_proxy(peer)
    if not cfg.tls and from_proxy and request.headers.get("x-forwarded-proto", "").lower() == "http":
        base = cfg.public_url if cfg.public_url.startswith("https://") else f"https://{request.headers.get('host', '')}"
        query = f"?{request.url.query}" if request.url.query else ""
        return RedirectResponse(base + path + query, status_code=308)
    if cfg.admin_networks and not (path.startswith("/api/v1/") or path == "/healthz"):
        from .web import client_ip
        if not cfg.is_admin_client(client_ip(request)):
            return PlainTextResponse("Not found", status_code=404)
    return None


async def _housekeeping(db: DB, events: EventStore) -> None:
    while True:
        try:
            security.purge_expired(db)
            removed = await asyncio.to_thread(events.purge)
            if removed:
                log.info("retention: removed %d old events", removed)
        except Exception:
            log.exception("housekeeping failed")
        await asyncio.sleep(3600)
