"""TLS certificate: bring-your-own, or a self-signed one generated on first start.

The phone app pins the certificate's public key (SPKI SHA-256), delivered inside the pairing QR code,
so a self-signed certificate is safe against man-in-the-middle attacks without any CA.
The key is never regenerated automatically: that would break the pin on every paired phone.
"""
from __future__ import annotations

import base64
import datetime as dt
import hashlib
import ipaddress
import os
from pathlib import Path

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.x509.oid import NameOID

from .config import Config


def ensure_certificate(cfg: Config) -> tuple[Path, Path]:
    if cfg.tls_cert_file and cfg.tls_key_file:
        return Path(cfg.tls_cert_file), Path(cfg.tls_key_file)
    cfg.tls_dir.mkdir(parents=True, exist_ok=True)
    cert_path, key_path = cfg.tls_dir / "cert.pem", cfg.tls_dir / "key.pem"
    if cert_path.exists() and key_path.exists():
        return cert_path, key_path

    key = ec.generate_private_key(ec.SECP256R1())
    names = ["localhost", *cfg.tls_hostnames]
    sans: list[x509.GeneralName] = []
    for n in dict.fromkeys(names):
        try:
            sans.append(x509.IPAddress(ipaddress.ip_address(n)))
        except ValueError:
            sans.append(x509.DNSName(n))
    sans.append(x509.IPAddress(ipaddress.ip_address("127.0.0.1")))
    subject = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "RWR server")])
    now = dt.datetime.now(dt.timezone.utc)
    cert = (
        x509.CertificateBuilder()
        .subject_name(subject)
        .issuer_name(subject)
        .public_key(key.public_key())
        .serial_number(x509.random_serial_number())
        .not_valid_before(now - dt.timedelta(minutes=5))
        .not_valid_after(now + dt.timedelta(days=3650))
        .add_extension(x509.SubjectAlternativeName(sans), critical=False)
        .add_extension(x509.BasicConstraints(ca=False, path_length=None), critical=True)
        .add_extension(x509.ExtendedKeyUsage([x509.oid.ExtendedKeyUsageOID.SERVER_AUTH]), critical=False)
        .sign(key, hashes.SHA256())
    )
    # Write the key with 0600 from the start (no window where it is world-readable).
    fd = os.open(key_path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "wb") as f:
        f.write(key.private_bytes(
            serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()
        ))
    cert_path.write_bytes(cert.public_bytes(serialization.Encoding.PEM))
    return cert_path, key_path


def load_cert(path: Path) -> x509.Certificate:
    return x509.load_pem_x509_certificate(path.read_bytes())


def spki_pin(cert: x509.Certificate) -> str:
    """base64url(SHA-256(SubjectPublicKeyInfo)), the value the phone pins."""
    spki = cert.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
    return base64.urlsafe_b64encode(hashlib.sha256(spki).digest()).decode().rstrip("=")


def fingerprint(cert: x509.Certificate) -> str:
    """SHA-256 certificate fingerprint as browsers show it (AA:BB:...), to verify the first visit."""
    return ":".join(f"{b:02X}" for b in cert.fingerprint(hashes.SHA256()))
