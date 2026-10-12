"""Uploads snapshots/clips to Immich (https://immich.app) with an API key.

Outbound security: TLS certificates are verified (system CAs, plus an optional admin-provided CA for
self-signed Immich), plain HTTP only when the admin explicitly allows it, redirects are not followed
(an API key must not be replayed to another host), and the key never appears in logs or errors.

API key permissions needed: asset.upload, plus album.read, album.create and albumAsset.create when an
album is set.
"""
from __future__ import annotations

import datetime as dt
import logging
import ssl
from typing import Any

import certifi
import httpx

log = logging.getLogger("rwr.immich")
TIMEOUT = httpx.Timeout(30.0, connect=10.0)


class ImmichError(Exception):
    pass


def _ssl_context(ca_pem: str) -> ssl.SSLContext:
    ctx = ssl.create_default_context(cafile=certifi.where())
    if ca_pem.strip():
        ctx.load_verify_locations(cadata=ca_pem)
    return ctx


def _client(s: dict[str, Any]) -> httpx.Client:
    url = s["immich_url"].rstrip("/")
    if url.startswith("http://") and not s["immich_allow_http"]:
        raise ImmichError("plain HTTP is not allowed")
    return httpx.Client(
        base_url=url,
        headers={"x-api-key": s["immich_api_key"], "Accept": "application/json"},
        verify=_ssl_context(s["immich_ca_pem"]),
        timeout=TIMEOUT,
        follow_redirects=False,
    )


def _check(resp: httpx.Response, what: str) -> Any:
    if resp.is_redirect:
        raise ImmichError(f"{what}: server redirected to {resp.headers.get('location', '?')}; use that URL")
    if resp.status_code in (401, 403):
        raise ImmichError(f"{what}: Immich refused the API key (HTTP {resp.status_code}); check key and permissions")
    if resp.status_code >= 400:
        raise ImmichError(f"{what}: HTTP {resp.status_code}")
    try:
        return resp.json()
    except ValueError:
        raise ImmichError(f"{what}: unexpected non-JSON reply (is this the Immich URL?)") from None


def _safe(e: Exception) -> str:
    """httpx errors can echo the request; keep only the class and a short message."""
    msg = str(e).splitlines()[0][:200] if str(e) else ""
    return f"{type(e).__name__}: {msg}"


def test_connection(s: dict[str, Any]) -> str:
    """Returns a human-readable success message or raises ImmichError."""
    if not s["immich_url"] or not s["immich_api_key"]:
        raise ImmichError("set the Immich URL and API key first")
    try:
        with _client(s) as c:
            pong = _check(c.get("/api/server/ping"), "ping")
            if pong.get("res") != "pong":
                raise ImmichError("ping: not an Immich server")
            r = c.get("/api/users/me")
            if r.status_code == 403:
                return "Connected. The key works but can't read the user (fine for uploads if it has asset.upload)."
            me = _check(r, "authenticate")
            return f"Connected to Immich as {me.get('name') or me.get('email') or 'user'}."
    except ImmichError:
        raise
    except (httpx.HTTPError, ssl.SSLError, ValueError) as e:
        raise ImmichError(_safe(e)) from None


def upload(s: dict[str, Any], data: bytes, filename: str, mime: str, taken_at: float, device_asset_id: str) -> str:
    """Uploads one file, adds it to the configured album, returns the Immich asset id."""
    when = dt.datetime.fromtimestamp(taken_at, dt.timezone.utc).isoformat()
    try:
        with _client(s) as c:
            body = _check(c.post(
                "/api/assets",
                files={"assetData": (filename, data, mime)},
                data={
                    "deviceAssetId": device_asset_id,
                    "deviceId": "rwr-server",
                    "fileCreatedAt": when,
                    "fileModifiedAt": when,
                    "filename": filename,
                },
            ), "upload")
            asset_id = body.get("id")
            if not asset_id:
                raise ImmichError("upload: no asset id in reply")
            album = s["immich_album"].strip()
            if album:
                _add_to_album(c, album, asset_id)
            return asset_id
    except ImmichError:
        raise
    except (httpx.HTTPError, ssl.SSLError, ValueError) as e:
        raise ImmichError(_safe(e)) from None


def _add_to_album(c: httpx.Client, name: str, asset_id: str) -> None:
    albums = _check(c.get("/api/albums"), "list albums")
    match = next((a for a in albums if a.get("albumName") == name), None)
    if match is None:
        _check(c.post("/api/albums", json={"albumName": name, "assetIds": [asset_id]}), "create album")
    else:
        _check(c.put(f"/api/albums/{match['id']}/assets", json={"ids": [asset_id]}), "add to album")
