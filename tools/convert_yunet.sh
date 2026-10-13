#!/bin/sh
# Rebuilds app/src/main/assets/face_detection_yunet_2023mar_640.tflite from OpenCV Zoo's YuNet ONNX model
# (MIT license; the same file and hash as server/models.sha256) and checks that it finds the same faces as
# OpenCV's FaceDetectorYN. Runs in a throwaway Docker container; pinned versions give a byte-identical file.
#   tools/convert_yunet.sh            # convert, verify, compare with the committed file
set -eu
cd "$(dirname "$0")/.."
docker run --rm -v "$PWD:/repo:ro" -v "$PWD/tools:/tools:ro" python:3.12-slim-trixie sh -euc '
  apt-get update -qq >/dev/null && apt-get install -y -qq --no-install-recommends curl libgl1 libglib2.0-0 libxcb1 >/dev/null
  pip install -q --root-user-action=ignore onnx==1.20.1 onnx2tf==2.6.9 onnxsim==0.6.5 onnxruntime==1.26.0 \
    onnx_graphsurgeon==0.6.1 sng4onnx==2.0.1 psutil==5.9.5 flatbuffers==25.12.19 numpy==2.2.6 protobuf==7.35.1 \
    ai-edge-litert==2.1.2 opencv-python-headless==5.0.0.93 2>&1 | grep -v "notice" || true
  mkdir -p /work && cd /work
  url=https://github.com/opencv/opencv_zoo/raw/47534e27c9851bb1128ccc0102f1145e27f23f98/models/face_detection_yunet/face_detection_yunet_2023mar.onnx
  curl -sSfL -o yunet.onnx "$url"
  echo "8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4  yunet.onnx" | sha256sum -c -
  # Square input: a non-square shape trips up the converter (a reshape mixes up H and W).
  onnxsim yunet.onnx yunet_640.onnx --overwrite-input-shape 1,3,640,640 >/dev/null
  onnx2tf -i yunet_640.onnx -o out >/dev/null 2>&1
  # Test photo from OpenCV'"'"'s samples (downloaded, not committed: the repo keeps no photos of people).
  curl -sSfL -o test.jpg https://raw.githubusercontent.com/opencv/opencv/4.12.0/samples/data/messi5.jpg
  echo "1d570e49654e84c7a943918537bd9e5e1ef82920152e147c834006e235be97c9  test.jpg" | sha256sum -c -
  python /tools/verify_yunet.py yunet.onnx out/yunet_640_float32.tflite test.jpg
  sha256sum out/yunet_640_float32.tflite /repo/app/src/main/assets/face_detection_yunet_2023mar_640.tflite
'
