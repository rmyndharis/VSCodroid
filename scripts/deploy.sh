#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"
APK_PATH="$ROOT_DIR/android/app/build/outputs/apk/debug/app-debug.apk"

echo "=== VSCodroid Deploy ==="

# Check ADB
if ! command -v adb &> /dev/null; then
    echo "ERROR: adb not found. Install Android SDK Platform-Tools."
    exit 1
fi

# Check device
DEVICE_COUNT=$(adb devices | grep -c "device$" || true)
if [ "$DEVICE_COUNT" -eq 0 ]; then
    echo "ERROR: No Android device connected."
    echo "  Connect a device with USB debugging enabled."
    exit 1
fi

echo "Connected devices:"
adb devices -l | grep "device " | sed 's/^/  /'

# adb chooses the target from ANDROID_SERIAL and refuses to guess between two,
# so with an emulator up beside a phone the install below fails with adb's
# "more than one device/emulator", after the build. Refused here instead,
# before anything is spent, and with the variable named, since adb honours it
# without this script doing anything further.
if [ "$DEVICE_COUNT" -gt 1 ] && [ -z "${ANDROID_SERIAL:-}" ]; then
    echo "ERROR: $DEVICE_COUNT devices connected and ANDROID_SERIAL is not set."
    echo "  export ANDROID_SERIAL=<serial from the list above> to choose one."
    exit 1
fi

# Always built. Gradle is incremental, so an unchanged tree costs seconds,
# while building only when the APK was absent installed whatever build was on
# disk after every source edit, and the edit then looked as though it had no
# effect. SKIP_BUILD=1 installs the APK as it stands, for one fetched from CI.
if [ -z "${SKIP_BUILD:-}" ]; then
    echo ""
    echo "Building..."
    ( cd "$ROOT_DIR/android" && ./gradlew assembleDebug )
fi
if [ ! -f "$APK_PATH" ]; then
    echo "ERROR: no APK at $APK_PATH"
    exit 1
fi

# Install
echo ""
echo "Installing APK..."
adb install -r "$APK_PATH"
echo "  ✓ Installed"

# Launch
echo ""
echo "Launching VSCodroid..."
# The debug build type sets applicationIdSuffix = ".debug", so the package this
# script just installed is com.vscodroid.debug. Launching com.vscodroid resolved
# nothing ("Activity class {com.vscodroid/com.vscodroid.SplashActivity} does not
# exist") unless a release build happened to be installed beside it, in which
# case it started that one instead and the install above was wasted.
#
# SplashActivity and not MainActivity: MainActivity bypasses first-run setup
# entirely, so a fresh install launched that way opens against an asset tree
# that was never extracted.
PKG="${PKG:-com.vscodroid.debug}"
adb shell am start -n "$PKG/com.vscodroid.SplashActivity"
echo "  ✓ Launched $PKG"

echo ""
echo "=== Deploy complete ==="
# Every tag is VSCodroid.<class>, so the bare tag matches nothing as a filterspec,
# and logcat filterspecs take no wildcard.
echo "View logs: adb logcat | grep VSCodroid"
