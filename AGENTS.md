# Notes for agents

Read [ARCHITECTURE.md](ARCHITECTURE.md) first. Keep both files updated when behavior or structure changes.

## Build & test

```sh
export ANDROID_HOME=/opt/android-sdk          # on the dev LXC; local.properties has sdk.dir
./gradlew testDebugUnitTest assembleDebug     # JVM unit tests (OnlookerPolicy/AlertGate) + debug APK
./gradlew assembleRelease                     # R8-minified; ALWAYS smoke-test it on the emulator after
                                              # dependency bumps (ML Kit + R8 broke once, see proguard-rules.pro)
```

Toolchain pins (gradle/libs.versions.toml): AGP 9.4.1 (compiles Kotlin itself, so don't apply
`kotlin-android` in app/), Gradle 9.8, Kotlin 2.4.20, CameraX 1.6.2, ML Kit face-detection 16.1.7.
`gradle.properties` is tuned for a 3-core / 16 GB host (one daemon, in-process Kotlin).

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
- Don't use the Settings app as the "other app" when checking the pop-up. It hides third-party overlays.
- After `adb install`, SystemUI only re-binds the tile while the QS panel is open, so a bare `click-tile` is a no-op.
  `scripts/emu_start.sh` handles this.
- The `npm_test`/`wal_test` AVDs belong to other projects. Use `rwr_test`.

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
- Don't add INTERNET permission or analytics. "Nothing leaves the device" is a product promise in the UI.
- License is GPL-3.0 (`LICENSE`). New dependencies must be GPLv3-compatible.
- Test photos of people are **not** committed. Generate or download them into a scratch dir.

## Before publishing to GitHub

Run a secret/leak scan over the tree and history (IPs, passwords, tokens, keys, keystores, `local.properties`,
`keystore.properties`, emails, absolute home paths). `.gitignore` already excludes the machine-local files.
