#!/usr/bin/env bash
# Usage: scripts/measure_cpu.sh LABEL — CPU time used by RWR (% of one core) over 2x30 s, from /proc.
# A proxy only: it cannot see camera sensor/ISP power, which on real phones is a large share.
PID=$(adb shell pidof io.github.revocx35.rwr)
t() { adb shell cat /proc/$PID/stat | awk '{print $14+$15}'; }
for i in 1 2; do a=$(t); sleep 30; b=$(t); echo "$1 run$i: $(awk "BEGIN{printf \"%.1f\", ($b-$a)/30}")% of one core"; done
