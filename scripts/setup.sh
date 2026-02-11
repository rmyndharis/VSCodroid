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
check_command node
check_command yarn
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

if [ -n "${ANDROID_NDK_HOME:-}" ]; then
    echo "  ✓ ANDROID_NDK_HOME=$ANDROID_NDK_HOME"
else
    echo "  ⚠ ANDROID_NDK_HOME not set (needed for cross-compilation)"
fi

# Initialize submodules
echo ""
echo "Initializing git submodules..."
cd "$ROOT_DIR"
if [ -f .gitmodules ]; then
    git submodule update --init --recursive
    echo "  ✓ Submodules initialized"
else
    echo "  ⚠ No .gitmodules found (code-server submodule not yet added)"
fi

# Create required directories
echo ""
echo "Creating directory structure..."
mkdir -p "$ROOT_DIR/android/app/src/main/jniLibs/arm64-v8a"
mkdir -p "$ROOT_DIR/android/app/src/main/assets/vscode-web"
mkdir -p "$ROOT_DIR/android/app/src/main/assets/vscode-reh"
mkdir -p "$ROOT_DIR/android/app/src/main/assets/extensions"
mkdir -p "$ROOT_DIR/patches/code-server"
mkdir -p "$ROOT_DIR/patches/vscodroid"
mkdir -p "$ROOT_DIR/test/projects"
mkdir -p "$ROOT_DIR/test/extensions"
echo "  ✓ Directories created"

echo ""
echo "=== Setup complete ==="
echo ""
echo "Next steps:"
echo "  1. Cross-compile Node.js:  ./toolchains/build-node.sh"
echo "  2. Build VS Code:          ./scripts/build-vscode.sh"
echo "  3. Package assets:         ./scripts/package-assets.sh"
echo "  4. Build APK:              cd android && ./gradlew assembleDebug"
echo "  5. Deploy:                 ./scripts/deploy.sh"
