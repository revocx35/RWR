"""Docker HEALTHCHECK: GET /healthz on loopback (TLS unless RWR_TLS=off)."""
import ssl
import sys
import urllib.request

from .config import Config

cfg = Config()
if not cfg.tls:
    url, ctx = f"http://127.0.0.1:{cfg.port}/healthz", None
elif cfg.tls_cert_file:
    # Bring-your-own certificate: chain and names are for the public hostname, not 127.0.0.1.
    # Nothing secret is sent on this loopback ping, so skip verification here only.
    url, ctx = f"https://127.0.0.1:{cfg.port}/healthz", ssl._create_unverified_context()
else:
    url, ctx = f"https://127.0.0.1:{cfg.port}/healthz", ssl.create_default_context(cafile=str(cfg.tls_dir / "cert.pem"))
try:
    with urllib.request.urlopen(url, context=ctx, timeout=4) as r:
        sys.exit(0 if r.read() == b"ok" else 1)
except Exception:
    sys.exit(1)
