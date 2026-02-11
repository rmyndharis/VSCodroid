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

# Build if needed
if [ ! -f "$APK_PATH" ]; then
    echo ""
    echo "APK not found, building..."
    cd "$ROOT_DIR/android"
    ./gradlew assembleDebug
fi

# Install
echo ""
echo "Installing APK..."
adb install -r "$APK_PATH"
echo "  ✓ Installed"

# Launch
echo ""
echo "Launching VSCodroid..."
adb shell am start -n com.vscodroid/.SplashActivity
echo "  ✓ Launched"

echo ""
echo "=== Deploy complete ==="
echo "View logs: adb logcat -s VSCodroid"
