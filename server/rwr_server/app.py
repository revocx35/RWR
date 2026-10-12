"""FastAPI application factory."""
from __future__ import annotations

import asyncio
import contextlib
import datetime
import logging
import tempfile
from pathlib import Path

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse, PlainTextResponse
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


def create_app(cfg: Config, detector=None) -> FastAPI:
    """[detector] is injectable so tests can run without the ML models."""
    db = DB(cfg.db_path)
    events = EventStore(db, cfg.events_dir)
    cert_path, _ = tls.ensure_certificate(cfg)
    cert = tls.load_cert(cert_path)

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
    app.state.pin = tls.spki_pin(cert)
    app.state.fingerprint = tls.fingerprint(cert)
    app.state.templates = Jinja2Templates(directory=str(HERE / "templates"))
    app.state.templates.env.filters["dt"] = lambda ts: datetime.datetime.fromtimestamp(ts).strftime("%Y-%m-%d %H:%M:%S")

    @app.middleware("http")
    async def headers(request: Request, call_next):
        response = await call_next(request)
        for k, v in SECURITY_HEADERS.items():
            response.headers.setdefault(k, v)
        if not request.url.path.startswith("/static/"):
            response.headers.setdefault("Cache-Control", "no-store")
        return response

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
