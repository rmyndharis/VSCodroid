#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"

echo "=== VSCodroid Development Setup ==="

# Check prerequisites
check_command() {
    if ! command -v "$1" &> /dev/null; then
        echo "ERROR: $1 is required but not installed."
        exit 1
    fi
    echo "  ✓ $1 ($(command -v "$1"))"
}

echo ""
echo "Checking prerequisites..."
# No yarn: nothing in this repository uses it. The server tree is fetched
# prebuilt, and building it from source (build-vscode-oss.sh) runs npm inside a
# container.
check_command node
check_command git
check_command python3

# Check Node.js version
NODE_VERSION=$(node --version | sed 's/v//')
NODE_MAJOR=$(echo "$NODE_VERSION" | cut -d. -f1)
if [ "$NODE_MAJOR" -lt 20 ]; then
    echo "ERROR: Node.js 20+ required, found v$NODE_VERSION"
    exit 1
fi
echo "  ✓ Node.js v$NODE_VERSION"

# Check for Android SDK (optional at setup time)
if [ -n "${ANDROID_HOME:-}" ]; then
    echo "  ✓ ANDROID_HOME=$ANDROID_HOME"
else
    echo "  ⚠ ANDROID_HOME not set (needed for building APK)"
fi

# Fatal, not a warning. build-all.sh runs this first and then spends roughly
# twenty minutes downloading before build-native-addons.sh and
# build-glibc-shim.sh both exit on a missing NDK -- a prerequisite that was
# knowable in the first second. REQUIRE_NDK=0 keeps the old behaviour for
# someone checking an environment they do not intend to build in.
if [ -n "${ANDROID_NDK_HOME:-}${ANDROID_NDK_ROOT:-}" ]; then
    echo "  ✓ ANDROID_NDK_HOME=${ANDROID_NDK_HOME:-$ANDROID_NDK_ROOT}"
elif [ "${REQUIRE_NDK:-1}" = "0" ]; then
    echo "  ⚠ ANDROID_NDK_HOME not set (needed to build the native addons)"
else
    echo "  ERROR: ANDROID_NDK_HOME is not set." >&2
    echo "         build-native-addons.sh and build-glibc-shim.sh both need it," >&2
    echo "         and they run after the downloads. Set it now rather than" >&2
    echo "         twenty minutes from now, or re-run with REQUIRE_NDK=0 to skip" >&2
    echo "         this check." >&2
    exit 1
fi

cd "$ROOT_DIR"

# Create required directories
echo ""
echo "Creating directory structure..."
mkdir -p "$ROOT_DIR/android/app/src/main/jniLibs/arm64-v8a"
mkdir -p "$ROOT_DIR/android/app/src/main/assets/vscode-reh"
mkdir -p "$ROOT_DIR/android/app/src/main/assets/extensions"
echo "  ✓ Directories created"

echo ""
echo "=== Setup complete ==="
echo ""
# Deliberately not a build recipe. This script is step one of build-all.sh, and
# a second copy of the sequence here is the drift that left the documented steps
# missing two scripts. build-all.sh runs it; CONTRIBUTING.md explains it.
echo "Prerequisites are in place. To prepare assets and build:"
echo "  ./scripts/build-all.sh"
