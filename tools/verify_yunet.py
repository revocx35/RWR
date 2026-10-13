"""Checks a converted YuNet LiteRT model against OpenCV's FaceDetectorYN on a test photo at several sizes and
positions, and on random noise: same boxes and scores (the decoding mirrors app/.../detection/YuNet.kt)."""
import math
import sys

import cv2
import numpy as np
from ai_edge_litert.interpreter import Interpreter

onnx_path, tflite_path, photo_path = sys.argv[1], sys.argv[2], sys.argv[3]
S = 640
it = Interpreter(tflite_path)   # default delegates (XNNPack), as on the phone
it.allocate_tensors()
inp, outs = it.get_input_details()[0], it.get_output_details()
ref = cv2.FaceDetectorYN.create(onnx_path, "", (S, S), 0.6, 0.3, 50)


def decode(t):
    found = []
    for st in (8, 16, 32):
        cols = S // st
        cls, obj = t[f"cls_{st}"].reshape(-1), t[f"obj_{st}"].reshape(-1)
        bb = t[f"bbox_{st}"].reshape(-1, 4)
        for i in range(cls.size):
            score = math.sqrt(min(max(cls[i], 0), 1) * min(max(obj[i], 0), 1))
            if score >= 0.6:
                r, c = divmod(i, cols)
                w, h = math.exp(bb[i, 2]) * st, math.exp(bb[i, 3]) * st
                found.append((score, (c + bb[i, 0]) * st - w / 2, (r + bb[i, 1]) * st - h / 2, w, h))
    found.sort(key=lambda f: -f[0])
    keep = []
    for f in found:
        if all(_iou(f, k) < 0.3 for k in keep):
            keep.append(f)
    return keep


def _iou(a, b):
    iw = min(a[1] + a[3], b[1] + b[3]) - max(a[1], b[1])
    ih = min(a[2] + a[4], b[2] + b[4]) - max(a[2], b[2])
    if iw <= 0 or ih <= 0:
        return 0
    return iw * ih / (a[3] * a[4] + b[3] * b[4] - iw * ih)


rng = np.random.default_rng(1)
images = [rng.integers(0, 255, (S, S, 3), dtype=np.uint8)]
photo = cv2.imread(photo_path)
for scale, (x, y) in ((1.0, (0, 0)), (0.6, (100, 200)), (0.35, (300, 50)), (0.2, (50, 400))):
    p = cv2.resize(photo, None, fx=scale, fy=scale, interpolation=cv2.INTER_AREA)[: S - y, : S - x]
    img = np.zeros((S, S, 3), np.uint8)
    img[y:y + p.shape[0], x:x + p.shape[1]] = p
    images.append(img)
compared = 0
for img in images:
    it.set_tensor(inp["index"], img.astype(np.float32)[None])
    it.invoke()
    mine = decode({o["name"].split(":")[0]: it.get_tensor(o["index"]) for o in outs})
    _, r = ref.detect(img)
    theirs = [] if r is None else [(row[14], row[0], row[1], row[2], row[3]) for row in r]
    assert len(mine) == len(theirs), (mine, theirs)
    compared += len(mine)
    for a, b in zip(mine, theirs):
        assert all(abs(x - y) < 0.5 for x, y in zip(a[1:], b[1:])) and abs(a[0] - b[0]) < 0.01, (a, b)
assert compared >= 3, f"too few faces to compare ({compared})"
print(f"OK: LiteRT model matches OpenCV on {len(images)} images ({compared} faces)")
