import re
import sys
import time
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from rwr_server import security  # noqa: E402
from rwr_server.app import create_app  # noqa: E402
from rwr_server.config import Config  # noqa: E402
from rwr_server.detector import FaceResult  # noqa: E402

ADMIN_PW = "correct horse battery staple"


class FakeDetector:
    """Every frame contains the owner (large; picked by the stream's OwnerTracker) and one onlooker looking at
    the camera."""

    def __init__(self):
        self.calls = 0

    def analyze(self, img, s):
        self.calls += 1
        return [
            FaceResult(box=(0, 0, 100, 120), score=0.9, yaw=0, pitch=0),
            FaceResult(box=(150, 20, 190, 70), score=0.9, yaw=5, pitch=0, looking=True),
        ]


@pytest.fixture
def cfg(tmp_path, monkeypatch):
    monkeypatch.setenv("RWR_DATA_DIR", str(tmp_path))
    return Config()


@pytest.fixture
def app(cfg):
    app = create_app(cfg, detector=FakeDetector())
    app.state.db.execute(
        "INSERT INTO admin(id, username, password_hash, must_change, password_changed_at) VALUES(1, 'admin', ?, 0, ?)",
        (security.hash_password(ADMIN_PW), time.time()),
    )
    return app


@pytest.fixture
def client(app):
    with TestClient(app, base_url="https://testserver") as c:
        yield c


def csrf_of(html: str) -> str:
    m = re.search(r'name="csrf" value="([^"]+)"', html)
    assert m, "no csrf field in page"
    return m.group(1)


def login(client, username="admin", password=ADMIN_PW):
    page = client.get("/login")
    return client.post("/login", data={"username": username, "password": password, "csrf": csrf_of(page.text)},
                       follow_redirects=False)


@pytest.fixture
def admin(client):
    r = login(client)
    assert r.status_code == 303, r.text
    return client
