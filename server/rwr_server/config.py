"""Deployment settings from environment variables (see server/.env.example). Runtime settings that the
admin can change (Immich, retention, ...) live in the database instead, see settings.py."""
from __future__ import annotations

import ipaddress
import os
from dataclasses import dataclass, field
from pathlib import Path


def _list(name: str) -> list[str]:
    return [v.strip() for v in os.environ.get(name, "").split(",") if v.strip()]


@dataclass(frozen=True)
class Config:
    data_dir: Path = field(default_factory=lambda: Path(os.environ.get("RWR_DATA_DIR", "/data")))
    models_dir: Path = field(default_factory=lambda: Path(os.environ.get("RWR_MODELS_DIR", "/app/models")))
    port: int = field(default_factory=lambda: int(os.environ.get("RWR_PORT", "8443")))
    # Names/IPs the phone and browser use to reach the server; added to the self-signed certificate.
    tls_hostnames: list[str] = field(default_factory=lambda: _list("RWR_TLS_HOSTNAMES"))
    # Bring your own certificate (e.g. from a reverse proxy / ACME). Default: self-signed in data_dir/tls.
    tls_cert_file: str = field(default_factory=lambda: os.environ.get("RWR_TLS_CERT_FILE", ""))
    tls_key_file: str = field(default_factory=lambda: os.environ.get("RWR_TLS_KEY_FILE", ""))
    # URL put in pairing QR codes. Empty = the URL the admin's browser used.
    public_url: str = field(default_factory=lambda: os.environ.get("RWR_PUBLIC_URL", "").rstrip("/"))
    # "auto": pairing codes carry the server certificate's key pin (needed for self-signed certs).
    # "none": the app validates the certificate against the phone's trusted CAs instead.
    pairing_pin: str = field(default_factory=lambda: os.environ.get("RWR_PAIRING_PIN", "auto"))
    # Reverse proxies whose X-Forwarded-For is trusted for the client IP (rate limiting, audit log).
    trusted_proxies: list[str] = field(default_factory=lambda: _list("RWR_TRUSTED_PROXIES"))
    # "off": serve plain HTTP for a TLS-terminating reverse proxy (e.g. Nginx Proxy Manager). Then set
    # RWR_PUBLIC_URL to the proxy's https:// address and RWR_TRUSTED_PROXIES to the proxy's IP.
    tls: bool = field(default_factory=lambda: os.environ.get("RWR_TLS", "on").lower() not in ("off", "0", "false", "no"))
    detector_threads: int = field(default_factory=lambda: int(os.environ.get("RWR_DETECTOR_THREADS", "2")))
    max_devices_streaming: int = field(default_factory=lambda: int(os.environ.get("RWR_MAX_STREAMS", "8")))

    @property
    def db_path(self) -> Path:
        return self.data_dir / "rwr.db"

    @property
    def tls_dir(self) -> Path:
        return self.data_dir / "tls"

    @property
    def events_dir(self) -> Path:
        return self.data_dir / "events"

    def is_trusted_proxy(self, host: str | None) -> bool:
        if not host or not self.trusted_proxies:
            return False
        try:
            ip = ipaddress.ip_address(host)
        except ValueError:
            return False
        return any(ip in ipaddress.ip_network(n, strict=False) for n in self.trusted_proxies)
