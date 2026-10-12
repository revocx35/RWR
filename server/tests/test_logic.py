import math

import numpy as np

from rwr_server import gaze
from rwr_server.clips import clip_fps, encode_mp4, resample
from rwr_server.policy import AlertGate
from rwr_server.stream import ClientSettings


def synthetic_face(head_yaw_deg=0.0, iris_shift=0.0):
    """Minimal 478-point face: only the indices gaze.py reads are meaningful."""
    p = np.zeros((478, 3))
    a = math.radians(-head_yaw_deg)  # positive = face turns toward image right

    def put(i, x, y, z=0.0):
        # rotate around the vertical axis, then project (orthographic), image centre 500,500
        xr = x * math.cos(a) + z * math.sin(a)
        zr = -x * math.sin(a) + z * math.cos(a)
        p[i] = (500 + xr, 500 + y, zr)

    put(gaze.CHEEK_A, -100, 0, 60)
    put(gaze.CHEEK_B, 100, 0, 60)
    put(gaze.FOREHEAD, 0, -120, 30)
    put(gaze.CHIN, 0, 120, 30)
    for (c1, c2, top, bottom), cx, iris in zip(gaze.EYES, (-45, 45), gaze.IRIS_CENTERS):
        put(c1, cx - 15, -30)
        put(c2, cx + 15, -30)
        put(top, cx, -36)
        put(bottom, cx, -24)
        put(iris, cx + iris_shift, -30)
    return p


def test_frontal_face_looks_at_camera():
    g = gaze.estimate(synthetic_face())
    assert abs(g.yaw) < 1 and abs(g.pitch) < 1 and g.eyes_open
    assert gaze.is_looking(g, 10)


def test_eyes_looking_sideways():
    g = gaze.estimate(synthetic_face(iris_shift=6))
    assert g.eye_yaw > 15
    assert not gaze.is_looking(g, 10)


def test_head_turn_compensated_by_eyes():
    turned = gaze.estimate(synthetic_face(head_yaw_deg=25))
    assert turned.head_yaw > 15
    compensated = gaze.estimate(synthetic_face(head_yaw_deg=25, iris_shift=-5))
    assert abs(compensated.yaw) < abs(turned.yaw)


def test_closed_eyes_are_not_looking():
    p = synthetic_face()
    for _, _, top, bottom in gaze.EYES:
        p[top, 1] = p[bottom, 1] = 470
    g = gaze.estimate(p)
    assert not g.eyes_open and not gaze.is_looking(g, 45)


def test_alert_gate():
    g = AlertGate()
    assert not g.on_frame(True, 0, 1000)
    assert g.on_frame(True, 100, 1000)
    assert not g.on_frame(True, 200, 1000)
    assert not g.on_frame(True, 300, 1000)   # cooldown
    assert g.on_frame(True, 1500, 1000)


def test_client_settings_are_clamped():
    c = ClientSettings.parse({"mode": "laser", "pre_sec": 999, "post_sec": -5, "cooldown_sec": True,
                              "max_gaze_angle": 1})
    assert c.mode == "head" and c.pre_sec == 30 and c.post_sec == 0 and c.cooldown_sec == 15
    assert c.max_gaze_angle == 3


def test_resample_repeats_frames_over_gaps():
    assert resample([0, 100, 400], 10) == [0, 1, 1, 1, 2]
    assert clip_fps([0, 200, 400, 600]) == 5


def test_encode_mp4():
    frames = [np.full((120, 161, 3), i * 20, np.uint8) for i in range(10)]
    data = encode_mp4(frames, [i * 200 for i in range(10)])
    assert data[4:8] == b"ftyp" and len(data) > 500
