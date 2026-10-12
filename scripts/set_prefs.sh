#!/bin/bash
# set_prefs.sh '<xml entries>'  (DEBUG builds only: uses run-as)
# Replaces the app's SharedPreferences, then starts watching through the UI and goes home.
# Example: scripts/set_prefs.sh '<string name="detection_mode">eye</string><boolean name="process_on_server" value="true" />'
set -e
tmp=$(mktemp)
printf "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n%s\n</map>\n" "$1" > "$tmp"
adb shell am force-stop io.github.revocx35.rwr
adb push "$tmp" /data/local/tmp/rwr.xml >/dev/null && rm -f "$tmp"
adb shell "cat /data/local/tmp/rwr.xml | run-as io.github.revocx35.rwr sh -c 'cat > shared_prefs/rwr.xml'"
adb shell am start -n io.github.revocx35.rwr/.ui.MainActivity >/dev/null; sleep 3
"$(dirname "$0")/ui_tap.sh" "Start watching" && sleep 2 && adb shell input keyevent KEYCODE_HOME
