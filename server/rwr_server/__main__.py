"""Entry point: prepare data dir, admin account and TLS certificate, then serve HTTPS."""
from __future__ import annotations

import logging
import os
import secrets
import time

import uvicorn

from . import security, tls
from .app import create_app
from .config import Config
from .db import DB
from .stream import MAX_FRAME_BYTES

log = logging.getLogger("rwr")


def ensure_admin(db: DB) -> None:
    if db.one("SELECT 1 FROM admin WHERE id = 1"):
        return
    pw_file = os.environ.get("RWR_ADMIN_PASSWORD_FILE", "")
    if pw_file:
        with open(pw_file, encoding="utf-8") as f:
            password = f.read().strip()
        problem = security.password_problem(password, "admin")
        if problem:
            raise SystemExit(f"RWR_ADMIN_PASSWORD_FILE: {problem}")
        must_change, shown = 0, "(from RWR_ADMIN_PASSWORD_FILE)"
    else:
        password = secrets.token_urlsafe(18)
        must_change, shown = 1, password
    db.execute(
        "INSERT INTO admin(id, username, password_hash, must_change, password_changed_at) VALUES(1, 'admin', ?, ?, ?)",
        (security.hash_password(password), must_change, time.time()),
    )
    # Printed once, to the container log only. It must be changed at first login.
    print("=" * 64, flush=True)
    print(" RWR server: first start", flush=True)
    print("   username: admin", flush=True)
    print(f"   password: {shown}", flush=True)
    print(" You will be asked to change it after signing in.", flush=True)
    print("=" * 64, flush=True)


def main() -> None:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    cfg = Config()
    cfg.data_dir.mkdir(parents=True, exist_ok=True)
    os.umask(0o077)
    db = DB(cfg.db_path)
    ensure_admin(db)
    ssl_args = {}
    if cfg.tls:
        cert_path, key_path = tls.ensure_certificate(cfg)
        cert = tls.load_cert(cert_path)
        log.info("TLS certificate SHA-256 fingerprint (check it on the browser's first visit): %s", tls.fingerprint(cert))
        ssl_args = {"ssl_certfile": str(cert_path), "ssl_keyfile": str(key_path)}
        log.info("listening on https://0.0.0.0:%d", cfg.port)
    else:
        log.warning("RWR_TLS=off: serving plain HTTP on port %d. Only run this behind a TLS reverse proxy, "
                    "and keep the port reachable only from that proxy.", cfg.port)
        if not cfg.public_url.startswith("https://"):
            log.warning("RWR_PUBLIC_URL should be the proxy's https:// address, or pairing codes won't work")
        if not cfg.trusted_proxies:
            log.warning("RWR_TRUSTED_PROXIES is empty: every client will look like the proxy to the login rate limiter")
    uvicorn.run(
        create_app(cfg),
        host="0.0.0.0",
        port=cfg.port,
        **ssl_args,
        server_header=False,
        proxy_headers=False,          # client IPs from proxies are handled explicitly (RWR_TRUSTED_PROXIES)
        ws_max_size=MAX_FRAME_BYTES + 64,
        ws_ping_interval=20,
        ws_ping_timeout=20,
        limit_concurrency=200,
        timeout_keep_alive=15,
        log_level="info",
        access_log=False,             # avoid logging IPs/paths of every frame request; audit log covers auth
    )


if __name__ == "__main__":
    main()
