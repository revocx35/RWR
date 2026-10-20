# Notes for agents

Read [ARCHITECTURE.md](ARCHITECTURE.md) first. Keep both files updated when behavior or structure changes.

## Build & test

```sh
export ANDROID_HOME=/opt/android-sdk          # on the dev LXC; local.properties has sdk.dir
./gradlew testDebugUnitTest assembleDebug     # JVM unit tests (policy, gaze, pairing link, frame ring) + debug APK
./gradlew assembleRelease                     # R8-minified, per-ABI + universal APKs. ALWAYS smoke-test on the
                                              # emulator: R8 has broken (now removed) ML Kit once and MediaPipe twice
                                              # (protobuf fields, Flogger); see proguard-rules.pro
```

The first build downloads `app/src/main/assets/face_landmarker.task` (git-ignored, SHA-256 checked in
`app/build.gradle.kts`; same file and hash as `server/models.sha256`). The YuNet face detector
(`assets/face_detection_yunet_2023mar_640.tflite`) is committed; `tools/convert_yunet.sh` rebuilds it byte-identically
from the ONNX model in Docker and checks it against OpenCV. The converter needs a square input (640×640): with a
non-square one it produces a model whose reshapes don't match.

Telemetry check for release APKs (must print zeros and no datatransport service/receiver):
`cat classes*.dex | grep -c -a "Lcom/google/android/datatransport/cct/internal/"` after unzipping, and
`aapt2 dump xmltree --file AndroidManifest.xml <apk> | grep -E "E: (service|receiver)" -A3`.

Toolchain pins (gradle/libs.versions.toml): AGP 9.4.1 (compiles Kotlin itself, so don't apply
`kotlin-android` in app/), Gradle 9.8, Kotlin 2.4.20, CameraX 1.6.2, LiteRT 1.4.2 (classic Interpreter API; 2.x is
~2× larger and adds a GPU accelerator we don't need), MediaPipe tasks-vision 0.10.35 (1.x pulls in an LLM runtime; not
needed), OkHttp 5.5.0, ZXing core 3.5.4. No Google Play Services, Firebase or ML Kit: the app is on F-Droid, which
rejects closed-source dependencies. Before adding a dependency, check its tree for `com.google.android.gms`,
`com.google.firebase`, `com.google.mlkit` and `com.google.android.datatransport` (telemetry); see the MediaPipe exclusion
in `app/build.gradle.kts`.
`gradle.properties` is tuned for a 3-core / 16 GB host (one daemon, in-process Kotlin).

## Server (`server/`)

```sh
cd server
docker build -t rwr-server:dev .
docker run --rm -u 0 -v $PWD:/src:ro -w /src -e PYTHONDONTWRITEBYTECODE=1 rwr-server:dev \
  sh -c "pip install -q pytest==9.1.1 && python -m pytest -q -p no:cacheprovider tests"
docker compose -p rwrtest up -d --build        # local instance; settings come from server/.env ONLY
```

- Python deps are pinned in `requirements.txt`. **mediapipe is installed with `--no-deps`**: its metadata
  pulls in desktop OpenCV, matplotlib and PortAudio. `detector.py` stubs the `sounddevice`/`matplotlib`
  imports that mediapipe's `__init__` does eagerly. libGLES/libEGL are required even on CPU.
- Models are downloaded at image build and checked against `server/models.sha256`.
- `tests/conftest.py` injects a `FakeDetector`, so the API/auth tests don't need the models. They still run in
  the image because `stream.py` imports `detector.py`.
- Shell variables are **not** passed to the container. The compose file reads only `server/.env`.
- Every network-facing change needs tests for the auth paths: wrong password, lockout, missing CSRF,
  foreign or `null` Origin, expired session, bad or revoked token. The project rule is "secure by default"
  (see the threat model in ARCHITECTURE.md).
- Browser gotcha: `Referrer-Policy: no-referrer` makes browsers send `Origin: null` on form POSTs, which
  breaks the Origin check. Keep `same-origin`. TestClient sends no Origin header, so add explicit
  Origin tests.
- Reverse-proxy mode: `RWR_TLS=off` (`Config.tls`). With it there is no own certificate, `app.state.pin` and
  `fingerprint` are `None`, the healthcheck uses http, and startup warns about a missing https `RWR_PUBLIC_URL` or
  `RWR_TRUSTED_PROXIES`. Tested in `test_reverse_proxy_mode_pairs_without_pin`.
- Recovery: `docker compose exec rwr python -m rwr_server.admin reset-password`.
- Security regressions live in `tests/test_security.py` (body limits, parallel login, Immich destination,
  JPEG bombs, lazy clip encoding, proxy redirect, admin networks, upload limits). `httpx.ASGITransport` +
  `asyncio.gather` is how to test concurrency; TestClient is sequential. Starlette's `BaseHTTPMiddleware` wraps
  exceptions in an `ExceptionGroup`, hence `except*` in `BodyLimit`.
- Any new code that decodes images from the network must check `frame_size_ok()` first.
- TestClient buffers whole responses, so tests of the endless live-view MJPEG stream monkeypatch
  `web.LIVE_MAX_S` to a second or two and feed phone frames from a background thread.
- Published image: `ghcr.io/revocx35/rwr-server:<version>` + `latest`. On release: bump `RWR_VERSION`'s default in
  `docker-compose.yml`, `docker build -t ghcr.io/revocx35/rwr-server:X.Y.Z -t ...:latest .`, check that `/data` is empty
  in the image, `gh auth token | docker login ghcr.io -u revocx35 --password-stdin`, push, `docker logout ghcr.io`.

## Emulator end-to-end test (KVM is available on the dev LXC)

AVD `rwr_test` (API 37 google_apis x86_64, pixel_6, `hw.camera.front=emulated`). The emulator can feed
an image file to the front camera:

```sh
emulator -avd rwr_test -no-window -no-audio -no-boot-anim -no-snapshot \
  -camera-front imagefile:/path/scene.jpg -gpu swiftshader_indirect &
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
adb shell appops set io.github.revocx35.rwr SYSTEM_ALERT_WINDOW allow
scripts/emu_start.sh          # starts watching via the real QS tile (expand panel + click-tile, retries)
adb shell input keyevent KEYCODE_HOME
adb logcat | grep WatcherService     # debug builds log every frame with faces; "onlooker alert" on alerts
adb shell content query --uri content://media/external/images/media --projection _display_name:relative_path
```

Emulator camera gotchas (learned the hard way):
- The image is read **once per emulator boot**. Restart the emulator to change it (screen off/on is not enough).
- It does not show the whole image: with a 960×1280 input, roughly the **top-left 545×727 region** reaches the
  480×640 frame. Put test faces there. `touch files/dump_frame` (via `run-as`) makes a debug build write the
  next frame to `files/frame.jpg`, so you can see what the camera delivers.
- Two faces: the owner large, plus a smaller face turned toward the camera → 1 onlooker. One face alone → no alert.
  The usual scene.jpg also has a half face at the left edge (turned 56°, bigger than the owner). Blurring the
  onlooker out of it, at source pixels (350..530, 310..540), gives an "owner only" scene that must not alert in
  either mode. The first watch after an emulator boot often gets no frames, so repeat it.
- Don't use the Settings app as the "other app" when checking the pop-up. It hides third-party overlays.
- After `adb install`, SystemUI only re-binds the tile while the QS panel is open, so a bare `click-tile` is a no-op.
  `scripts/emu_start.sh` handles this.
- The `npm_test`/`wal_test` AVDs belong to other projects. Use `rwr_test`.

CPU measurement: `scripts/measure_cpu.sh LABEL` while watching. To compare builds, install the old release APK,
measure, then `adb install -r` the new one (this also checks that the update installs over the old version) and measure
again. While the test scene is in view it alerts every 15 s and the pop-up covers the middle of the screen, so taps
on the settings can land on the pop-up. Dismiss it and tap immediately, and check the result with `grep "camera bound"`.
The emulator camera tops out at 640×480, so the High resolution setting can't be tested there.

### Server end-to-end on the emulator

- The emulator can't reach Docker-published ports through `10.0.2.2` on the dev LXC (plain host listeners
  work). Use `adb reverse tcp:8443 tcp:8443` and set `RWR_PUBLIC_URL=https://127.0.0.1:8443` in `server/.env`.
- Admin login and pairing-code creation can be scripted with curl and a cookie jar (`--cacert` with the
  container's `/data/tls/cert.pem`). Parse the `csrf` hidden field. Change the generated password first.
- Pairing via the real QR scanner: render the link as a QR into a camera test image and boot with
  `-camera-back imagefile:qr.jpg`. Keep the QR inside the visible top-left region, at about (80, 300),
  380 px. The code expires in 10 minutes, so create it right before booting.
- Typing the link with `adb shell input text` drops characters from long strings. Check the field and
  type the rest.
- The pop-up returns every cooldown and swallows taps. `scripts/set_prefs.sh` (debug builds) sets
  preferences directly and starts watching. `scripts/ui_tap.sh "Text"` scrolls and taps.
- Immich: the upstream compose (`immich-server`, `redis`, `database`; ML not needed) on
  `127.0.0.1:2283`. Create the admin via `/api/auth/admin-sign-up` and a **scoped** API key with exactly
  the documented permissions. Then `docker network connect immichtest_default rwrtest-rwr-1` and use
  `http://immich_server:2283` with "allow plain HTTP".
- Web UI in the emulator's Chrome: skip first-run, use Advanced → Proceed for the self-signed certificate,
  then drive pages with `scripts/chrome_cdp.py` (DevTools via `adb forward tcp:9222
  localabstract:chrome_devtools_remote`). This is more reliable than typing.

Debug view (`ui/DebugActivity.kt`, all builds, Main screen → Troubleshooting): the quickest way to see why a face
counts or not, on the phone and on the server. To drive it on the emulator, turn the pop-up off first
(`<boolean name="overlay_popup" value="false" />` via `scripts/set_prefs.sh`), otherwise it swallows the swipes that
`ui_tap.sh` uses to scroll. After an emulator boot a "Pixel Launcher isn't responding" dialog sometimes blocks the
screen: `scripts/ui_tap.sh "Close app"`. Server mode: the server log shows `debug=True` while the view is open.

Debug-only frame injection (bypasses the camera, same detection/alert path; frames are interleaved with
camera frames, so inject 2–3 times quickly to satisfy the 2-of-3 gate):

```sh
adb push face.jpg /data/local/tmp/ && adb shell "cat /data/local/tmp/face.jpg | run-as io.github.revocx35.rwr sh -c 'cat > files/face.jpg'"
adb shell am broadcast -n io.github.revocx35.rwr/.DebugInjectReceiver --es path face.jpg
```

## Conventions

- Detection decisions belong in `OnlookerPolicy.kt` (pure Kotlin) with unit tests. Keep Android types out of it.
- All frame processing runs on `WatcherService.analysisExecutor` (single thread). UI/overlay work goes to the main thread.
- Debug-only code goes behind `BuildConfig.DEBUG` or in `src/debug/`. Never ship an exported test hook in release.
- No analytics, ever. Network use is limited to the user-paired RWR server: TLS only, pinned key, no
  cleartext (`network_security_config.xml`). On-device detection must keep working offline.
- Gaze math exists twice, in `GazeEstimator.kt` and `server/rwr_server/gaze.py`. Change both together, and
  keep the synthetic-face tests on both sides identical. The same applies to owner tracking, landmark verification
  and the alert gate (`OnlookerPolicy.kt` and `server/rwr_server/policy.py`).
- Replaying saved clips: the alert frames have the red box and laser glow burned in, which can break landmark
  detection on that face. Judge real onlookers on clean frames (or the live view), not on clip frames.
- Performance: the server logs `device N perf: …` every 30 s (fps in/analysed, KB/s, analysis ms, frame age on
  arrival). A large frame age means the phone's uplink is the bottleneck.
- False alerts: the server keeps event clips (`/data/events`) and logs `onlooker alert (N of M faces)` plus each
  client settings change (`ignore_owner=False` explains "1 of 1"). To debug, extract frames with the image's
  ffmpeg (`imageio_ffmpeg.get_ffmpeg_exe()`; headless OpenCV can't read video) and replay them through
  `Detector.analyze` + `OwnerTracker` + `AlertGate`. Don't commit those frames: they show real people.
- License is GPL-3.0 (`LICENSE`). New dependencies must be GPLv3-compatible.
- Test photos of people are **not** committed. Generate or download them into a scratch dir.

## authentik

`server/Authentik.kt` is the same file as in wa_logger's and insta_logger's Android clients and web-ip-phone (only the
package differs): change them together. Setup and design: `docs/authentik.md`. authentik's introspection accepts the
app's token for a proxy provider only if the app's OAuth2 provider lists that proxy provider in
`jwt_federation_providers`. The only new dependency is the kotlinx-serialization JSON runtime (Apache-2.0, no plugin).

## Releasing

1. Bump `baseVersionCode` and `versionName` in `app/build.gradle.kts` (F-Droid's per-ABI codes derive from it), add
   `fastlane/metadata/android/en-US/changelogs/<10×base+1..3>.txt` (and `<base>.txt`), then commit and tag `vX.Y.Z`
   (F-Droid's update check follows tags).
2. `./gradlew clean testDebugUnitTest assembleRelease`. Signing comes from `keystore.properties`, which is git-ignored.
   On the dev LXC it's a symlink to the key material kept **outside the repo** in a `chmod 700` directory.
   Never copy the keystore or its password into the repo, docs, or release notes.
3. Smoke-test the signed `app/build/outputs/apk/release/app-x86_64-release.apk` on the emulator in both
   modes (eye mode on the phone must log `(EYE)` alerts with no "eye tracker unavailable"; server mode
   must connect).
4. `gh release create vX.Y.Z RWR-X.Y.Z-arm64-v8a.apk RWR-X.Y.Z-armeabi-v7a.apk RWR-X.Y.Z-universal.apk --notes-file ...`. Include the APK SHA-256 and the signing cert
   fingerprint (`apksigner verify --print-certs`).
   Every release must use the same key, or Android refuses to install the update over the old version.

## Before publishing to GitHub

Run a secret/leak scan over the tree and history (IPs, passwords, tokens, keys, keystores, `local.properties`,
`keystore.properties`, emails, absolute home paths). `.gitignore` already excludes the machine-local files.
