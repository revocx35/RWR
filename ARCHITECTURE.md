# Architecture

RWR is a single-module Android app (`app/`, package `io.github.revocx35.rwr`, Kotlin, minSdk 29,
targetSdk 37). No network, no database; settings live in SharedPreferences.

```
 Quick Settings tile ──► StartWatchActivity (invisible) ─┐
 MainActivity "Start" ───────────────────────────────────┼─► WatcherService (FGS, type=camera)
                                                         │      │  CameraX ImageAnalysis (front cam)
                                                         │      ▼  ~3 frames/s, RGBA
                                                         │   upright + mirrored Bitmap
                                                         │      ▼
                                                         │   FaceFinder (ML Kit, bundled model)
                                                         │      ▼  List<FaceBox>
                                                         │   OnlookerPolicy ─► AlertGate
                                                         │      ▼  (alert)
                                                         │   annotate ─► SnapshotStore (MediaStore → Pictures/RWR)
                                                         │      ▼
                                                         └─► AlertPresenter: overlay card + heads-up notification
```

## Components

| File | Role |
|---|---|
| `detection/WatcherService.kt` | `LifecycleService` running as a camera-type foreground service. Binds CameraX `ImageAnalysis` to the front camera, throttles to one frame per 330 ms, runs detection, raises alerts. Unbinds the camera on `SCREEN_OFF` and rebinds on `SCREEN_ON`. Exposes `running: StateFlow<Boolean>` for the UI and tile. |
| `detection/FaceFinder.kt` | Wraps ML Kit face detection (fast mode, min face size 5%). Synchronous via `Tasks.await`, called on the analysis thread. |
| `detection/OnlookerPolicy.kt` | Pure Kotlin, unit-tested. `OnlookerPolicy` picks onlookers: drop the largest face (the owner) if enabled, keep faces whose head yaw and pitch are within the sensitivity angle. `AlertGate` requires an onlooker in 2 of the last 3 analysed frames and enforces the cooldown. |
| `alert/SnapshotStore.kt` | Writes JPEGs via MediaStore with `RELATIVE_PATH=Pictures/RWR` and `IS_PENDING` (no storage permission needed on API 29+). |
| `alert/AlertPresenter.kt` | Always posts a high-importance notification with a downscaled BigPicture; additionally shows a `TYPE_APPLICATION_OVERLAY` card (centered, auto-dismiss after 20 s) when overlay permission is granted and the setting is on. |
| `tile/WatchTileService.kt` | Toggleable QS tile. Stop: `stopService`. Start: launches `StartWatchActivity` via `startActivityAndCollapse(PendingIntent)` (API 34+) or the Intent overload. Opens `MainActivity` instead when the camera permission is missing. |
| `tile/StartWatchActivity.kt` | Translucent, no-history trampoline that starts the service and finishes immediately. |
| `ui/MainActivity.kt` | Compose screen: status + start/stop, "add tile" (`StatusBarManager.requestAddTileService`, API 33+), permission rows, settings. |
| `detection/CameraTuning.kt` | Pure Kotlin, unit-tested. `chooseFpsRange` maps the user's frame-rate choice onto one of the camera's supported auto-exposure FPS ranges. |
| `Prefs.kt` | SharedPreferences wrapper: `ignoreOwner`, `overlayPopup`, `maxHeadAngle`, `cooldownSec`, plus the battery settings `cameraFps`, `lowResolution`, `efficientConversion`. Changing a key in `CAMERA_KEYS` makes the running service rebind the camera immediately (preference listener). |
| `src/debug/.../DebugInjectReceiver.kt` | Debug-only exported receiver that feeds an image through the real pipeline (see AGENTS.md). Not in release builds. |

## Key decisions and platform constraints

- **Front camera, not back.** It faces the same way as the screen, so anyone it sees facing it is looking toward the screen.
- **"Looking" = head pose.** ML Kit gives head Euler angles; gaze tracking is not available on-device at this quality. Yaw/pitch ≤ 25° by default.
- **Owner heuristic.** The largest face is assumed to be the user. Turn it off to alert on any face looking at the screen (e.g. phone left on a desk).
- **Camera FGS must start from the foreground.** Android 14+ refuses to start a camera foreground service from the background. Both entry points start it while an app activity is in front; the tile goes through the invisible trampoline. Logs show Android 17 also temp-allowlists tile clicks, but the trampoline keeps it working across versions.
- **Not sticky, no boot start.** A system restart or `BOOT_COMPLETED` would start from the background, which is forbidden for camera FGS. The user restarts it from the tile.
- **Battery** (user-controllable in the "Battery" section, defaults are the frugal options):
  - *Camera frame rate* (5/10/15/30, default 5): sets `CONTROL_AE_TARGET_FPS_RANGE` through `Camera2Interop`, so the sensor itself produces fewer frames instead of the app dropping them. Phones support only certain ranges, so the slowest supported range that reaches the target is used (often 15 is the minimum).
  - *Resolution* (640×480 default, or 1280×720): the analysis size. High gives sharper snapshots.
  - *Convert only analysed frames* (default on): frames arrive as YUV and `ImageProxy.toBitmap()` runs only on the ~3/s frames that are analysed. Off = CameraX converts every frame to RGBA (v1.0.0 behaviour).
  - Fixed: analysis is throttled to one frame per 330 ms with `STRATEGY_KEEP_ONLY_LATEST`, and the camera is fully released while the screen is off.
  - Measured on the API 37 emulator (`scripts/measure_cpu.sh`): v1.0.0 ~21% of one core, v1.0.1 defaults ~17%. On the emulator most of the remaining CPU is ML Kit detection. Its camera is simulated, so savings in the sensor/ISP from lower frame rates don't show there. Real-device power hasn't been measured yet.
- **Snapshot orientation.** Frames are rotated upright and mirrored horizontally, so they look like a selfie preview (someone behind your left shoulder appears on the left). Detection runs on that same bitmap, so face boxes map directly onto the saved image.
- **Overlay limits.** System Settings (and some sensitive screens) hide third-party overlays (`HIDE_OVERLAY_WINDOWS`). The notification is the fallback. The overlay is centered because heads-up notifications draw above app overlays at the top of the screen.
- **R8.** ML Kit creates its component registrars by reflection; `proguard-rules.pro` keeps their no-arg constructors, otherwise release builds crash in `WatcherService.onCreate`.
- **Privacy.** No INTERNET permission, `allowBackup=false`, and snapshots only go to the local gallery.
