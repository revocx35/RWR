"""Server settings changeable at runtime from the web UI and from paired phones (with permission)."""
from __future__ import annotations

from typing import Any
from urllib.parse import urlsplit

from .db import DB

DEFAULTS: dict[str, Any] = {
    "immich_enabled": False,
    "immich_url": "",
    "immich_api_key": "",          # write-only: never returned by any API or page
    "immich_album": "RWR",
    "immich_allow_http": False,     # plain HTTP to Immich only when the admin opts in (LAN)
    "immich_ca_pem": "",            # extra CA to trust for a self-signed Immich certificate
    "keep_events": True,            # keep snapshots/clips on the server for the web UI
    "retention_days": 7,
    "min_face_px": 16,              # ignore faces smaller than this (pixels wide)
    "detector_score": 0.6,          # YuNet confidence threshold
}
SECRET_KEYS = {"immich_api_key"}


def load(db: DB) -> dict[str, Any]:
    stored = db.get_setting("server", {}) or {}
    return {k: stored.get(k, v) for k, v in DEFAULTS.items()}


def public_view(s: dict[str, Any]) -> dict[str, Any]:
    out = {k: v for k, v in s.items() if k not in SECRET_KEYS}
    out["immich_api_key_set"] = bool(s.get("immich_api_key"))
    return out


class SettingsError(ValueError):
    pass


def apply_update(db: DB, changes: dict[str, Any]) -> dict[str, Any]:
    """Validates and stores [changes]. Unknown keys are rejected; an empty/absent API key keeps the old one."""
    current = load(db)
    new = dict(current)
    for k, v in changes.items():
        if k == "immich_api_key_set":
            continue
        if k not in DEFAULTS:
            raise SettingsError(f"unknown setting: {k}")
        default = DEFAULTS[k]
        if k in SECRET_KEYS:
            if v is None or v == "":
                continue  # keep
            if not isinstance(v, str) or len(v) > 512 or any(c.isspace() for c in v):
                raise SettingsError("API key looks invalid")
        elif isinstance(default, bool):
            if not isinstance(v, bool):
                raise SettingsError(f"{k} must be true/false")
        elif isinstance(default, int):
            if isinstance(v, bool) or not isinstance(v, (int, float)) or v != int(v):
                raise SettingsError(f"{k} must be a whole number")
            v = int(v)
        elif isinstance(default, float):
            if isinstance(v, bool) or not isinstance(v, (int, float)):
                raise SettingsError(f"{k} must be a number")
            v = float(v)
        elif isinstance(default, str):
            if not isinstance(v, str):
                raise SettingsError(f"{k} must be text")
            v = v.strip()
        new[k] = v
    _validate(new)
    db.set_setting("server", new)
    return new


def _validate(s: dict[str, Any]) -> None:
    if not 1 <= s["retention_days"] <= 365:
        raise SettingsError("retention_days must be 1-365")
    if not 8 <= s["min_face_px"] <= 400:
        raise SettingsError("min_face_px must be 8-400")
    if not 0.3 <= s["detector_score"] <= 0.99:
        raise SettingsError("detector_score must be 0.3-0.99")
    if len(s["immich_album"]) > 100:
        raise SettingsError("album name too long")
    if len(s["immich_ca_pem"]) > 20000 or (s["immich_ca_pem"] and "BEGIN CERTIFICATE" not in s["immich_ca_pem"]):
        raise SettingsError("CA must be a PEM certificate")
    url = s["immich_url"]
    if url:
        parts = urlsplit(url)
        if parts.scheme not in ("https", "http") or not parts.hostname:
            raise SettingsError("Immich URL must look like https://immich.example.lan")
        if parts.scheme == "http" and not s["immich_allow_http"]:
            raise SettingsError("Immich URL uses plain http: enable 'allow plain HTTP' if this is on your LAN")
        if parts.username or parts.password or parts.query or parts.fragment:
            raise SettingsError("Immich URL must not contain credentials, query or fragment")
        if len(url) > 300:
            raise SettingsError("Immich URL too long")
    if s["immich_enabled"] and not (url and s["immich_api_key"]):
        raise SettingsError("Immich needs a URL and an API key before it can be enabled")
