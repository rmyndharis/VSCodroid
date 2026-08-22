#!/usr/bin/env bash
set -euo pipefail

# Launches VSCodroid on a connected device and clears whatever stands in the way
# until the workbench is actually up.
#
#   ./scripts/device-launch.sh              # launch and settle
#   ./scripts/device-launch.sh install      # install the debug APK first
#
# A first launch is interrupted by things that look nothing like each other in a
# log but block everything the same way: Android 13+ asks for POST_NOTIFICATIONS,
# and first-run setup shows a toolchain picker. Both take focus, so the app is
# backgrounded and its process is gone; through `ps` and `logcat` that reads
# exactly like a crash, which is how it gets misdiagnosed.
#
# So the dialogs are dismissed by looking at what is on screen rather than by
# guessing: uiautomator dumps the view hierarchy, buttons are matched by their
# text, and the tap goes to the centre of the reported bounds. No fixed
# coordinates, nothing that breaks when the layout moves.

ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
PKG="${PKG:-com.vscodroid.debug}"
APK="${APK:-android/app/build/outputs/apk/debug/app-debug.apk}"

# Buttons to press when they appear, in the order they are looked for.
DISMISS=("Allow" "Skip")

tap_by_text() {
    local want=$1 dump
    dump=$("$ADB" shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 && "$ADB" shell cat /sdcard/ui.xml 2>/dev/null | tr -d '\r') || return 1
    python3 - "$want" <<PY
import re, subprocess, sys
xml = """$dump"""
want = sys.argv[1]
m = re.search(r'text="%s"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"' % re.escape(want), xml)
if not m:
    sys.exit(1)
x1, y1, x2, y2 = map(int, m.groups())
print(f"  tapped {want} at {(x1+x2)//2},{(y1+y2)//2}")
subprocess.run(["$ADB", "shell", "input", "tap", str((x1+x2)//2), str((y1+y2)//2)], check=True)
PY
}

if [ "${1:-}" = "install" ]; then
    echo "=== Installing ==="
    "$ADB" install -r "$APK" | tail -1 | sed 's/^/  /'
fi

echo "=== Launching ==="
"$ADB" shell am force-stop "$PKG" || true
"$ADB" shell am start -n "$PKG/com.vscodroid.SplashActivity" >/dev/null

# Poll rather than sleep for a fixed time: a first run extracts ~100 MB and takes
# far longer than an upgrade, and a fixed wait is either too short or wasteful.
for attempt in $(seq 1 40); do
    sleep 5

    for button in "${DISMISS[@]}"; do
        tap_by_text "$button" 2>/dev/null || true
    done

    nodes=$("$ADB" shell ps -A 2>/dev/null | tr -d '\r' | grep -c libnode || true)
    if [ "${nodes:-0}" -ge 2 ]; then
        echo "  server up after ~$((attempt * 5))s ($nodes node processes)"
        exit 0
    fi
done

echo "  did not come up; capturing the screen so the reason is visible" >&2
"$ADB" shell screencap -p /sdcard/launch-failed.png >/dev/null 2>&1
"$ADB" pull /sdcard/launch-failed.png . >/dev/null 2>&1
exit 1
