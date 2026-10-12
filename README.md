# RWR — onlooker alert for Android

RWR watches through the **front camera** for people looking at your screen from behind (shoulder
surfers). When it sees one, it:

- pops up a card **over whatever app you are using** with a snapshot of the onlooker (boxed in red),
- posts a high-priority notification with the same snapshot,
- saves every snapshot to the gallery under **Pictures/RWR**.

It runs in the background as a foreground service and can be toggled from the **Quick Settings**
panel (the "Onlooker alert" tile). All processing happens on-device with the bundled ML Kit face
detector; nothing is uploaded and the app has no internet permission.

## Install & use

1. Build (see below) or grab an APK, install it, open **RWR**.
2. Grant **Camera**, **Notifications**, and **Display over other apps** (needed for the pop-up).
3. Tap **Start watching** — or add the Quick Settings toggle (button in the app on Android 13+, or
   swipe down twice → pencil → drag "Onlooker alert").

Settings: ignore your own face (largest face in view, on by default), pop-up on/off, sensitivity (how
far a head may be turned and still count as looking), and the minimum time between alerts.

Battery: camera frame rate (default 5 fps, or the lowest your phone supports), camera resolution
(low 640×480 by default, high 1280×720 for sharper snapshots), and "convert only analysed frames"
(leave on). The camera is always off while the screen is off.

Notes:
- Android shows the green camera-in-use dot while watching. The camera is released while the screen is off.
- The pop-up cannot appear over the system Settings app (Android hides third-party overlays there);
  the notification still does.
- Requires Android 10+ and a front camera.

## Build

Requires JDK 17+ and the Android SDK (compileSdk 37).

```sh
echo "sdk.dir=/path/to/android-sdk" > local.properties
./gradlew testDebugUnitTest assembleDebug       # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease                        # signed if keystore.properties exists
```

Release signing: create `keystore.properties` (git-ignored) with `storeFile`, `storePassword`,
`keyAlias`, `keyPassword`.

See [ARCHITECTURE.md](ARCHITECTURE.md) for how it works and [AGENTS.md](AGENTS.md) for contributor/agent notes.

## License

RWR is free software, licensed under the [GNU General Public License v3.0](LICENSE).
