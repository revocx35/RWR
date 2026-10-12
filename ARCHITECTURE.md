# Architecture

Two parts:

- `app/`: Android app, package `io.github.revocx35.rwr`, Kotlin, minSdk 29, targetSdk 37.
- `server/`: optional self-hosted detection server, Python/FastAPI in Docker.

The app works fully on its own. The server is opt-in, paired with a QR code. It gives stronger detection, zero detection CPU on the phone, and Immich uploads.

```
 Quick Settings tile ─► StartWatchActivity (invisible) ─┐
 MainActivity "Start" ──────────────────────────────────┼─► WatcherService (FGS, type=camera)
                                                        │     CameraX ImageAnalysis (front camera, YUV, 5-30 fps)
                                                        │        │
                       ┌────────── on the phone ────────┴────────┴──────── server mode ───────────┐
                       ▼ (~3 frames/s)                                    every frame ▼            │
              upright + mirrored Bitmap                         FrameCodec.toJpeg (YUV→NV21→JPEG) │
                       ▼                                                  │   └► FrameRing (last N s)
              FaceFinder (ML Kit faces + head pose)                       ▼                        │
                       ▼                                 StreamClient ══ wss, pinned TLS, token ══►│ server
      head mode: OnlookerPolicy        eye mode: EyeTracker (MediaPipe       ◄══ {"type":"alert"} ═│ YuNet + Face
                       │               Face Landmarker on face crops)        ▼                     │ Landmarker
                       │               → GazeEstimator (head + iris)   frame from FrameRing        │ every frame
                       ▼                                                     ▼                     │
                   AlertGate ─────────────────────► raiseAlert(upright frame, onlookers)           │
                                                       │ annotate (boxes + "laser" rays)           │
                                                       ├► AlertPresenter: pop-up + notification    │
                                                       ├► photo: SnapshotStore (Pictures/RWR)      │
                                                       └► video: ClipJob ─► ClipEncoder (H.264)    │
                                                             ├► gallery (MediaStore)               │
                                                             └► POST /api/v1/media ─► server ─► Immich
```

## App components

| File | Role |
|---|---|
| `detection/WatcherService.kt` | Camera foreground service; everything per frame happens here on `analysisExecutor`, which is single-threaded. JPEG-encodes frames only when something needs them: the server stream, or the rolling buffer for clips. Local detection is throttled to one frame per 330 ms. Server alerts are handled on the same thread. Clip encoding and uploads run on `mediaExecutor`. Camera and stream are released on `SCREEN_OFF`. Exposes `running` and `serverState` StateFlows. |
| `detection/FaceFinder.kt` | ML Kit face detection (fast mode, min face 5%, bundled model): face boxes and head Euler angles. |
| `detection/EyeTracker.kt` | MediaPipe Face Landmarker (478 points including irises), run on each candidate face: square crop, 1.6× margin, scaled to 256 px, so small faces behind you get enough pixels. Points are mapped back to frame pixels. |
| `detection/GazeEstimator.kt` | Pure Kotlin, unit-tested; mirrors `server/rwr_server/gaze.py`. Head yaw/pitch from the 3D face plane (cheeks × forehead–chin); eye yaw/pitch from the iris offset inside each eye (`asin(1.25·offset/half-width)`); gaze = head + eyes. Eyes closed if lid gap / width < 0.10. Also builds the laser rays. |
| `detection/OnlookerPolicy.kt` | Pure Kotlin, unit-tested. Head mode: drop the owner (largest face), keep faces with \|yaw\|,\|pitch\| ≤ angle. Eye mode: prefilter at 60°, then the gaze decides; it falls back to the head rule for faces narrower than 40 px or with no landmarks. `AlertGate`: 2 of the last 3 analysed frames, plus a cooldown. |
| `detection/CameraTuning.kt` | Maps the frame-rate setting onto a supported AE FPS range. |
| `media/FrameRing.kt` | Rolling buffer of `EncodedFrame` (seq, timestamp, JPEG, rotation), bounded by seconds and 48 MB. `ClipTiming` resamples to constant frame rate, the same way as the server's `clips.resample`. |
| `media/FrameCodec.kt` | YUV_420_888 → NV21 → `YuvImage.compressToJpeg`, which is native and much cheaper than going through a Bitmap. Also decodes a frame back to upright + mirrored. |
| `media/ClipEncoder.kt` | MediaCodec H.264 encoder fed through `getInputImage()` (works with planar and semi-planar layouts), muxed to MP4 with MediaMuxer. Runs only when an alert fires. |
| `server/PairingLink.kt` | Parses `rwr://pair?u=<https url>&c=<code>&p=<pin>` and rejects non-https URLs, credentials, paths, malformed codes and malformed pins. Unit-tested. |
| `server/PinnedTls.kt` | OkHttp client. With a pin, only a certificate whose SPKI SHA-256 equals the pin is accepted, and nothing else is trusted. Without a pin, normal CA and hostname checks apply. `RESTRICTED_TLS`, no redirects. |
| `server/ServerAccount.kt` | Paired server URL, pin, device id and name, permission flag. The device token is AES-GCM encrypted with a non-exportable Android Keystore key. |
| `server/ServerApi.kt` | Blocking REST: pair, device info, unpair (revokes the token server-side), get/put config, Immich test, media upload. |
| `server/StreamClient.kt` | WebSocket client: hello/settings JSON, binary frames (16-byte header + JPEG), alerts back. Drops frames when more than 512 KB is queued; reconnects with backoff up to 60 s. Stops for good when the server refuses the device (HTTP 403 or close code 4403). |
| `alert/AlertPresenter.kt` | Pop-up overlay plus notification. For video, it shows "Saving clip…" and then switches to "Open clip" via `clipReady()`. |
| `alert/SnapshotStore.kt` | MediaStore writes for JPEG and MP4 into `Pictures/RWR`; no storage permission needed. |
| `ui/MainActivity.kt` | Compose. Sections: status, permissions, Detection (head/eye, angles, cooldown), Snapshots (pop-up, photo/video, before/after seconds, save to Gallery/Immich/Both), Server (pair, on phone/on server, fallback, server settings, unpair), Battery. |
| `ui/PairActivity.kt` | QR scanner (CameraX back camera + ZXing core, no Play Services) or paste field; confirm dialog showing the URL and pin; then `ServerApi.pair`. Stops the watcher while scanning. |
| `ui/ServerSettingsActivity.kt` | Edits server settings through the device API. Only offered when the admin allowed this device; the API key field is write-only. |
| `Prefs.kt` | All user settings. `CAMERA_KEYS` changes rebind the camera; `KEY_SERVER` reconnects; `STREAM_KEYS` changes are pushed to the server live. |
| `tile/*`, `src/debug/*` | Unchanged from v1.0 (tile trampoline; debug-only frame injection). |

## Server components (`server/rwr_server`)

| File | Role |
|---|---|
| `__main__.py` | Bootstrap. On first start it creates the admin with a random password, printed once to the log, which must be changed at first login. Ensures the TLS certificate and runs uvicorn over TLS: no access log, WebSocket message cap, connection limit. |
| `tls.py` | Self-signed ECDSA P-256 certificate generated once (the key file is written 0600), or bring your own. `spki_pin()` is what pairing QR codes carry; `fingerprint()` is for checking the browser's first visit. The key is never regenerated automatically, because that would break every phone's pin. |
| `security.py` | Authentication building blocks: argon2id (64 MiB, t=3, p=4) with a dummy hash for unknown users; 256-bit tokens stored as SHA-256; session create/load with 30 min idle and 12 h absolute timeouts (5 min for the TOTP stage); throttling (per IP: 5 failures / 15 min → 15 min lock; global: 20 failures → 5 min; pairing/token failures: 10 per 10 min); password policy (≥ 12 characters, not trivial). |
| `web.py` | Admin UI, server-rendered with no JavaScript. Pages: login (+ TOTP step), dashboard, devices and pairing QR, settings, events (media served only to the admin), account (password, TOTP enrol/disable). Every POST checks the session, the CSRF token and Origin/Referer. Login uses a double-submit pre-session token. Session ids rotate on login and after the TOTP step; a password change signs out everywhere. TOTP codes can't be replayed (last step stored). |
| `api.py` | Device API, authenticated with `Authorization: Bearer`; tokens are never accepted in URLs. Endpoints: `POST /api/v1/pair` (one-time, 10 min code), `GET/DELETE /device`, `GET/PUT /config` and `POST /config/immich-test` (only with `can_configure`), `POST /media` (validates JPEG/MP4 magic bytes, ≤ 80 MB), `WS /stream`. |
| `stream.py` | One `DeviceStream` per connected phone, with a single connection per device (a new one replaces the old). Frames: header parse, ≤ 4 MB, ≤ 40 fps (excess dropped). Analysis runs in a thread pool, always on the newest frame. Per-device `AlertGate`; alerts are sent as JSON. Builds the photo or the clip (pre frames from `FrameBuffer`, post frames collected live) and hands it to `EventStore`. Client settings are clamped (`ClientSettings.parse`). |
| `detector.py` | YuNet (OpenCV) finds faces at full resolution, including small ones. Face Landmarker runs on an upscaled square crop of every non-owner face; gaze math is in `gaze.py`. Fallback pose from YuNet's 5 points. `annotate()` draws boxes and laser rays. |
| `clips.py` | `FrameBuffer`; constant-frame-rate resampling; H.264 MP4 via the static ffmpeg from `imageio-ffmpeg` (yuv420p, faststart). |
| `events.py` | Stores the event row and, if "keep events" is on, the file (0600). Immich upload runs in a 2-thread pool with 3 retries; retention purge runs hourly. |
| `immich.py` | `POST /api/assets`, then find or create the album and `PUT /api/albums/{id}/assets`. Verified TLS (certifi plus an optional admin CA); `http://` only with an explicit opt-in; no redirects followed; the API key is never logged or returned. |
| `settings.py` | Runtime settings stored as JSON in SQLite, with validation. `immich_api_key` is write-only: `public_view()` returns only `immich_api_key_set`. |
| `db.py` | SQLite (WAL), one connection behind a lock; the file is 0600. Holds the audit log (last 2000 entries). |
| `admin.py` | Recovery CLI `reset-password`: new random password, turns off TOTP, signs out all sessions, clears lockouts. |

### Stream protocol

```
phone → server  text    {"type":"hello"|"settings","settings":{mode, ignore_owner, max_head_angle,
                         max_gaze_angle, cooldown_sec, media, pre_sec, post_sec, save_immich}}
phone → server  binary  u8 version=1 | u8 rotation quarter-turns (clockwise) | u8 flags (bit0 = mirror)
                         | u8 0 | u32 seq | i64 capture time ms (big-endian) | JPEG (sensor orientation)
server → phone  text    {"type":"ready","max_fps":40}
                         {"type":"alert","seq":n,"ts":ms,"width":w,"height":h,
                          "faces":[{"box":[l,t,r,b],"yaw":..,"pitch":..,"gaze":[y,p],"rays":[[x0,y0,x1,y1],..]}]}
                         {"type":"stats","fps_in":..,"fps_analysed":..}
close codes     4401 bad token (HTTP 403 before accept), 4403 revoked, 4409 replaced, 4429 too many streams
```

Coordinates are in the upright, mirrored frame, which phone and server build identically. On an alert, the phone looks up frame `seq` in its own `FrameRing` and draws the server's boxes on it, so no image travels back. With Immich as a destination in server mode, the server builds and uploads the media itself. The phone builds media only for its gallery.

## Key decisions

- **Front camera, owner heuristic, gate, cooldown.** Unchanged from v1.0. The largest face is assumed to be the user.
- **Two detection modes.**
  - *Head*: ML Kit Euler angles. Cheapest.
  - *Eye*: iris gaze. It alerts only when the eyes point at the screen, so it also catches someone who turns their head away but keeps their eyes on your screen. It ignores someone facing the camera but looking elsewhere. On the test photos it rejected a face at 22° gaze that head mode accepted.
  - Phone and server use the same math and agree within about 1° on the same photo.
- **Eye tracking only on candidate faces.** ML Kit is still the face detector on the phone. MediaPipe runs per non-owner face, so the extra CPU is spent only while someone is in view.
- **Server detection is "max level".** Every frame at full resolution, YuNet for small and distant faces, landmarks on every face. The phone only JPEG-encodes and sends.
  - On the emulator at 5 fps that is ~6 frames/s in and all of them analysed.
  - Radio cost: about 40 KB per frame at 640×480, roughly 1.6 Mbit/s at 5 fps. Lower the camera frame rate to cut it.
- **Fallback.** With "detect on the phone while unreachable" (default on), losing the server falls back to local detection with no gap. `REJECTED` (device revoked) stops retrying.
- **Clips.** Both sides keep a rolling JPEG buffer: the phone in `FrameRing`, the server in `FrameBuffer`. Pre seconds come from the buffer, post seconds are collected live, and the result is resampled to constant frame rate and H.264-encoded.
  - While a clip is recording, new alerts are ignored.
  - On the phone, video mode JPEG-encodes every camera frame, which costs extra battery; the UI says so.
- **Camera FGS from the foreground only, not sticky.** Unchanged from v1.0.
- **R8.** `proguard-rules.pro` keeps:
  - ML Kit registrars;
  - MediaPipe classes and protobuf-lite messages, whose fields are read by reflection;
  - Flogger, which finds its caller by walking the stack.

  Without these, eye mode silently falls back to head pose in release builds only. Smoke-test the release APK after dependency bumps.
- **APK size.** MediaPipe's native code is about 14 MB per ABI. Release builds produce per-ABI APKs (arm64-v8a about 21 MB) plus a universal one, with compressed JNI libs.

## Threat model (server mode)

Assets: camera frames and clips of people behind the user, the admin account, device tokens, and the Immich API key.

| Threat | Mitigation |
|---|---|
| Network attacker (LAN/Wi-Fi) reads or alters the stream | TLS only (the app blocks cleartext app-wide). The app pins the server's public key from the QR code, so a MITM certificate, even a CA-signed one, is refused. |
| Someone guesses the admin password | argon2id; per-IP and global lockouts; generic errors with jittered delays; optional TOTP with replay protection; no default password (random, must be changed). |
| Session theft / CSRF / clickjacking | `__Host-` cookie with Secure, HttpOnly, SameSite=Strict; idle and absolute expiry; rotation on login; CSRF token plus Origin check on every POST; `frame-ancestors 'none'` and `X-Frame-Options: DENY`; strict CSP with no inline script. |
| XSS via device names or other stored text | Jinja autoescape everywhere; no JavaScript at all, and the CSP forbids inline scripts anyway. |
| Stolen or copied device token | Stored hashed server-side and encrypted with the Keystore on the phone. Revocable in the web UI, which also drops the live stream. Unpair revokes it too. Bad tokens are throttled per IP. |
| Leaked pairing QR | Single use, valid 10 minutes, 128-bit; shown only to the logged-in admin. |
| Phone abuses the server | Frame size, rate and message caps; one stream per device; a global stream limit; settings clamped; config changes only with `can_configure`; media uploads type-checked and size-capped. |
| Immich key exposure or misuse | Write-only in UI and API; never logged; sent only to the configured URL; redirects not followed; TLS verified (custom CA possible); plain HTTP only by explicit opt-in. |
| Container compromise impact | Non-root (uid 10001), read-only rootfs, `cap_drop: ALL`, `no-new-privileges`, pids and memory limits; only `/data` is writable. |
| Self-signed certificate in the browser | The first visit shows a warning; check the SHA-256 fingerprint printed in the log and on the dashboard, or bring your own certificate. `Referrer-Policy: same-origin`, because `no-referrer` makes browsers send `Origin: null` and breaks the CSRF check. |

Reverse-proxy mode (`RWR_TLS=off`): TLS is terminated by the proxy. The proxy's certificate changes on
renewal, so pairing codes carry no pin (`RWR_PAIRING_PIN=none`) and the app validates it against the phone's
trusted CAs. That needs a publicly trusted certificate, e.g. Let's Encrypt. Client IPs come from
`X-Forwarded-For`, but only when the peer is in `RWR_TRUSTED_PROXIES`. The hop between proxy and server is
plaintext, so firewall the port to the proxy only. A proxy can also forward to `https://` with the self-signed
backend certificate, which keeps that hop encrypted.

Known limits:
- No per-user accounts (single admin).
- Self-signed certificate: the browser warning is accepted once by hand. The phone is unaffected because it uses the pin.
- Frames are not end-to-end encrypted beyond TLS. The server sees them by design.
