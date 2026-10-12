#!/usr/bin/env bash
# Start watching through the Quick Settings tile on a connected emulator/device (debug build installed).
# The first click after an install can race the tile binding, so retry until the service is foreground.
set -euo pipefail
PKG=${PKG:-io.github.revocx35.rwr}
TILE=$PKG/.tile.WatchTileService
adb shell cmd statusbar add-tile "$TILE" >/dev/null 2>&1 || true
for _ in 1 2 3 4 5; do
  if adb shell dumpsys activity services "$PKG" | grep -q "detection.WatcherService"; then
    adb shell cmd statusbar collapse; echo "watching"; exit 0
  fi
  # SystemUI binds the tile only while the QS panel is visible, as when a user taps it.
  adb shell cmd statusbar expand-settings; sleep 2
  adb shell cmd statusbar click-tile "$TILE"; sleep 4
done
echo "service did not start" >&2; exit 1
