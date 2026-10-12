# RWR: onlooker alert for Android

RWR watches through the **front camera** for people looking at your screen from behind (shoulder surfers).
When it sees one, it:

- pops up a card **over whatever app you are using** with a snapshot of the onlooker (boxed in red; in eye mode
  with "laser eyes" showing where they look),
- posts a high-priority notification,
- saves a **photo or a video clip** (for example 10 s before to 10 s after) to your **gallery** (Pictures/RWR), to
  **Immich**, or to both.

It runs in the background and can be toggled from the **Quick Settings** panel ("Onlooker alert" tile).

## Detection modes

- **Head tracking**: a face turned toward the screen counts as looking. Lightest on battery.
- **Eye tracking**: follows the irises (MediaPipe Face Landmarker, the same kind of model behind "laser eyes"
  filters) and combines them with head pose. It alerts only when the eyes point at the screen, even if the
  head is turned away. It ignores people facing the camera but looking elsewhere.

Detection runs **on the phone** by default (offline, bundled models). Or pair the app with your own
**RWR server** and the phone just streams frames to it.

## RWR server (optional, Docker)

The server does the heavy work: every frame, full resolution, a stronger face detector (YuNet) that also
finds small, distant faces, and iris tracking on every face. Your phone spends no CPU on detection. It also
uploads snapshots and clips to **Immich**.

```sh
git clone https://github.com/revocx35/RWR && cd RWR/server
cp .env.example .env            # set RWR_TLS_HOSTNAMES to the server's LAN IP / hostname
docker compose pull && docker compose up -d     # prebuilt image (or: docker compose up -d --build)
docker compose logs rwr | grep -A4 "first start"     # one-time admin password
```

1. Open `https://<server>:8443`. The certificate is self-signed: compare the SHA-256 fingerprint in the log
   with the browser's before continuing. Sign in and change the password. Turning on two-factor
   authentication (Account page) is recommended.
2. **Devices → Create pairing code**, then in the app: **Server → Pair with server** and scan the QR code.
3. In the app, choose **Detection runs: On server**. Optionally set up Immich in **Settings** (web UI, or
   **Server settings** in the app) and choose **Save to: Immich** or **Both**.

Security:
- **Connection:** TLS everywhere. The app pins the server's key from the QR code, so nobody on your network
  can intercept the stream.
- **Login:** argon2id passwords, login rate limiting and lockout, optional TOTP 2FA, secure session cookies,
  CSRF protection and a strict CSP.
- **Phones:** each phone has a revocable token, stored only as a hash on the server and encrypted by the
  Android Keystore on the phone.
- **Immich:** the API key is write-only and TLS is verified.
- **Container:** non-root, read-only filesystem, no capabilities.

Details are in [ARCHITECTURE.md](ARCHITECTURE.md#threat-model-server-mode).

**Behind a reverse proxy** (Nginx Proxy Manager etc.): set `RWR_TLS=off`, `RWR_PUBLIC_URL=https://<your domain>`,
`RWR_TRUSTED_PROXIES=<proxy IP>` and `RWR_PAIRING_PIN=none` in `.env`. Point the proxy at `http://<server>:8443` with
WebSocket support and a valid (e.g. Let's Encrypt) certificate, and make sure only the proxy can reach port 8443.

Locked out? `docker compose exec rwr python -m rwr_server.admin reset-password`

## Install & use the app

1. Grab the APK from [Releases](https://github.com/revocx35/RWR/releases). `arm64-v8a` fits almost every
   current phone; `universal` works everywhere. Install it and open **RWR**.
2. Grant **Camera**, **Notifications**, and **Display over other apps** (needed for the pop-up).
3. Tap **Start watching**, or add the Quick Settings toggle.

Settings:
- **Detection:** head or eye tracking, angles, ignore your own face (the largest face), minimum time
  between alerts.
- **Snapshots:** pop-up on/off; photo or video clip with adjustable seconds before and after (0–30 each);
  save to Gallery / Immich / Both. Immich needs a paired server.
- **Server:** pair or unpair; run detection on the phone or on the server; fall back to the phone while the
  server is unreachable.
- **Battery:** camera frame rate, resolution, efficient frame conversion. The camera is always off while
  the screen is off.

Notes:
- Without a paired server nothing leaves the phone. With one, frames go only to your server, over TLS.
- Android shows the green camera-in-use dot while watching. The pop-up can't appear over the system
  Settings app; the notification still does.
- Requires Android 10+ and a front camera.

## Build

App: JDK 17+ and the Android SDK (compileSdk 37). The first build downloads the eye-tracking model
(checksum-verified).

```sh
echo "sdk.dir=/path/to/android-sdk" > local.properties
./gradlew testDebugUnitTest assembleDebug
./gradlew assembleRelease      # per-ABI + universal APKs, signed if keystore.properties exists
```

Server tests: `cd server && docker build -t rwr-server:dev . && docker run --rm -u 0 -v $PWD:/src -w /src rwr-server:dev sh -c "pip install pytest==9.1.1 && python -m pytest tests"`

See [ARCHITECTURE.md](ARCHITECTURE.md) for how it works and [AGENTS.md](AGENTS.md) for contributor and agent notes.

## License

RWR is free software, licensed under the [GNU General Public License v3.0](LICENSE). Bundled models:
MediaPipe Face Landmarker (Apache-2.0) and OpenCV YuNet (MIT).
