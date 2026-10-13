import math

import numpy as np

from rwr_server import gaze
from rwr_server.clips import clip_fps, encode_mp4, resample
from rwr_server.policy import AlertGate, OwnerTracker
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


ONLOOKER = (300, 40, 360, 110)


def test_alert_gate():
    g = AlertGate()
    assert not g.on_frame([ONLOOKER], 0, 1000)
    assert g.on_frame([ONLOOKER], 100, 1000)
    assert not g.on_frame([ONLOOKER], 200, 1000)
    assert not g.on_frame([ONLOOKER], 300, 1000)   # cooldown
    assert g.on_frame([ONLOOKER], 1500, 1000)


def test_alert_gate_follows_a_moving_onlooker_but_ignores_flicker():
    g = AlertGate()
    assert not g.on_frame([ONLOOKER], 0, 0)
    assert g.on_frame([(320, 50, 380, 120)], 100, 0)          # moved a bit: same person
    # False detections in different places every frame (car ceiling sticker, seat, ...) never alert.
    flicker = [[(77, 13, 139, 88)], [], [(589, 878, 658, 960)], [(3, 2, 102, 116)], [], [(539, 901, 592, 960)]]
    g = AlertGate()
    assert not any(g.on_frame(b, i * 100, 0) for i, b in enumerate(flicker))


def test_owner_tracker_keeps_the_owner_when_a_bigger_false_face_appears():
    t = OwnerTracker()
    owner = (200, 300, 450, 650)
    assert t.pick([owner], [0], 0) == 0
    # A blurry finger at the frame edge detected as a huge "face": the owner stays the owner.
    finger = (0, 0, 400, 500)
    assert t.pick([finger, (210, 310, 455, 660)], [0, 5], 100) == 1
    # The owner moving around is followed.
    assert t.pick([(240, 330, 490, 680)], [10], 200) == 0
    # After the owner is gone for a while, the largest face becomes the owner again.
    assert t.pick([(10, 10, 60, 70), (600, 600, 700, 720)], [0, 0], 5000) == 1


def test_landmarks_of_a_neighbouring_face_are_rejected():
    from rwr_server.policy import landmarks_match
    # The crop around a half face at the frame edge also contained the owner; the landmarker found the owner.
    edge, owner_mesh = (21, 227, 266, 544), ((230, 440), (300, 520))
    assert not landmarks_match(edge, owner_mesh[0], owner_mesh[1])
    assert landmarks_match((214, 292, 447, 524), owner_mesh[0], owner_mesh[1])


def test_owner_is_not_a_bigger_face_turned_away():
    # A half face cut off by the frame edge, turned 56 degrees, is bigger than the owner facing the screen.
    edge, me = (21, 227, 266, 544), (214, 292, 447, 524)
    assert OwnerTracker().pick([me, edge], [3.5, 56.0], 0) == 0
    # Nobody facing the camera: the largest face is still picked.
    assert OwnerTracker().pick([me, edge], [40.0, 56.0], 0) == 1


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
