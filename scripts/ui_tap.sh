#!/bin/bash
# ui_tap.sh "Exact text"  -> scrolls down until a node with that text is visible, then taps its centre.
export PATH=$PATH:/opt/android-sdk/platform-tools
for i in $(seq 1 8); do
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  b=$(adb shell cat /sdcard/ui.xml | python3 -c "
import re,sys
x=sys.stdin.read()
for m in re.finditer(r'<node [^>]*>', x):
    n=m.group(0)
    if 'text=\"$1\"' in n:
        a=list(map(int,re.search(r'bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"',n).groups()))
        print((a[0]+a[2])//2,(a[1]+a[3])//2); break")
  if [ -n "$b" ]; then adb shell input tap $b; echo "tapped $1 at $b"; exit 0; fi
  adb shell input swipe 540 1800 540 900 300; sleep 1
done
echo "not found: $1"; exit 1
