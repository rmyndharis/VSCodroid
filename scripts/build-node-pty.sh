#!/usr/bin/env bash
set -euo pipefail

# Cross-compile node-pty v1.1.0-beta22 for Android ARM64 using NDK.
# Produces pty.node (native addon) for use by VS Code's terminal on Android.
#
# Prerequisites:
#   - Android NDK r27+ (via ANDROID_NDK_HOME or ~/Library/Android/sdk/ndk/*)
#   - node-gyp (npm install -g node-gyp)
#   - Node.js + npm
#
# Output:
#   android/app/src/main/assets/vscode-reh/node_modules/node-pty/build/Release/pty.node

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"
WORK_DIR="$ROOT_DIR/.build/node-pty"
OUTPUT_DIR="$ROOT_DIR/android/app/src/main/assets/vscode-reh/node_modules/node-pty/build/Release"

NODE_VERSION="20.18.1"
NODE_PTY_VERSION="1.1.0-beta22"
TARGET="aarch64-linux-android"
API=33

echo "=== Building node-pty ${NODE_PTY_VERSION} for Android ARM64 ==="
echo ""

# --- Step 1: Find Android NDK ---
echo "Finding Android NDK..."
if [ -n "${ANDROID_NDK_HOME:-}" ]; then
    NDK_DIR="$ANDROID_NDK_HOME"
elif [ -n "${ANDROID_HOME:-}" ] && [ -d "$ANDROID_HOME/ndk" ]; then
    NDK_DIR="$(ls -d "$ANDROID_HOME/ndk/"* 2>/dev/null | sort -V | tail -1)"
elif [ -d "$HOME/Library/Android/sdk/ndk" ]; then
    NDK_DIR="$(ls -d "$HOME/Library/Android/sdk/ndk/"* 2>/dev/null | sort -V | tail -1)"
else
    echo "ERROR: Cannot find Android NDK."
    echo "Set ANDROID_NDK_HOME or install NDK via Android Studio SDK Manager."
    exit 1
fi

if [ -z "${NDK_DIR:-}" ] || [ ! -d "$NDK_DIR" ]; then
    echo "ERROR: NDK directory not found or empty."
    exit 1
fi

echo "  NDK: $NDK_DIR"

# Detect host toolchain directory
# NDK r25+ uses llvm/bin toolchain
HOST_TAG="$(uname -s | tr '[:upper:]' '[:lower:]')-$(uname -m)"
TOOLCHAIN_DIR="$NDK_DIR/toolchains/llvm/prebuilt/$HOST_TAG"

if [ ! -d "$TOOLCHAIN_DIR" ]; then
    # Try darwin-x86_64 on macOS (Rosetta or older NDK layout)
    TOOLCHAIN_DIR="$NDK_DIR/toolchains/llvm/prebuilt/darwin-x86_64"
fi

if [ ! -d "$TOOLCHAIN_DIR" ]; then
    echo "ERROR: Cannot find NDK toolchain directory."
    echo "Looked for: $NDK_DIR/toolchains/llvm/prebuilt/$HOST_TAG"
    ls "$NDK_DIR/toolchains/llvm/prebuilt/" 2>/dev/null || echo "  (prebuilt dir not found)"
    exit 1
fi

echo "  Toolchain: $TOOLCHAIN_DIR"

# Verify compiler exists
NDK_CC="${TOOLCHAIN_DIR}/bin/${TARGET}${API}-clang"
NDK_CXX="${TOOLCHAIN_DIR}/bin/${TARGET}${API}-clang++"
NDK_AR="${TOOLCHAIN_DIR}/bin/llvm-ar"

if [ ! -f "$NDK_CC" ]; then
    echo "ERROR: Cannot find ${TARGET}${API}-clang"
    echo "  Expected at: $NDK_CC"
    exit 1
fi

echo "  CC:  $NDK_CC"
echo "  CXX: $NDK_CXX"
echo "  AR:  $NDK_AR"

# --- Step 2: Clean and create work directory ---
echo ""
echo "Preparing work directory..."
rm -rf "$WORK_DIR"
mkdir -p "$WORK_DIR"
cd "$WORK_DIR"

# --- Step 3: Download Node.js headers ---
echo ""
echo "Downloading Node.js ${NODE_VERSION} headers..."
NODE_HEADERS_URL="https://nodejs.org/dist/v${NODE_VERSION}/node-v${NODE_VERSION}-headers.tar.gz"
NODE_DIR="$WORK_DIR/node-v${NODE_VERSION}"

curl -L --fail --show-error -o node-headers.tar.gz "$NODE_HEADERS_URL"
tar xzf node-headers.tar.gz
rm node-headers.tar.gz
echo "  Extracted: $NODE_DIR"

# node-gyp expects a "Release" directory with node.lib (Windows) but on Unix
# it just needs the include directory. Verify the include dir exists.
if [ ! -d "$NODE_DIR/include/node" ]; then
    echo "ERROR: Node.js headers missing: $NODE_DIR/include/node"
    ls -la "$NODE_DIR/" 2>/dev/null
    exit 1
fi
echo "  Headers: $NODE_DIR/include/node/"

# --- Step 4: Download node-pty source ---
echo ""
echo "Downloading node-pty@${NODE_PTY_VERSION}..."
NPM_PACK_DIR="$WORK_DIR/node-pty-src"
mkdir -p "$NPM_PACK_DIR"
cd "$NPM_PACK_DIR"

npm pack "node-pty@${NODE_PTY_VERSION}" --quiet
tar xzf "node-pty-${NODE_PTY_VERSION}.tgz"
mv package/* .
rm -rf package "node-pty-${NODE_PTY_VERSION}.tgz"

echo "  Source: $NPM_PACK_DIR"

# --- Step 5: Install node-addon-api ---
echo ""
echo "Installing node-addon-api..."
npm install --ignore-scripts --quiet
echo "  Installed: $(ls node_modules/node-addon-api/napi.h >/dev/null 2>&1 && echo 'OK' || echo 'FAILED')"

# --- Step 6: Patch binding.gyp ---
# Remove -lutil from libraries. Android Bionic has forkpty() built into libc.
# The existing binding.gyp removes -lutil for mac/solaris but not for Android/Linux.
echo ""
echo "Patching binding.gyp (remove -lutil for Android)..."
python3 - binding.gyp <<'PYEOF'
import sys

path = sys.argv[1]
with open(path, 'r') as f:
    content = f.read()

# The unix target has: 'libraries': ['-lutil']
# We replace it with an empty libraries array.
# This is safe because Android Bionic includes forkpty() in libc directly.
old = "'libraries': [\n            '-lutil'\n          ]"
new = "'libraries': []"
count = content.count(old)
if count > 0:
    content = content.replace(old, new)
    with open(path, 'w') as f:
        f.write(content)
    print(f"  Removed -lutil ({count} occurrence(s))")
else:
    # Try alternate formatting (single line)
    old2 = "'-lutil'"
    if old2 in content:
        content = content.replace(old2, '')
        with open(path, 'w') as f:
            f.write(content)
        print(f"  Removed -lutil (inline format)")
    else:
        print(f"  WARNING: -lutil not found in binding.gyp (may already be removed)")
PYEOF

# --- Step 7: Cross-compile with NDK clang ---
# node-gyp's gyp generator adds macOS-specific -arch flags that NDK clang doesn't support.
# Instead, compile directly with NDK clang — node-pty has a single source file (pty.cc)
# and uses N-API (node-addon-api), so the build is straightforward.
echo ""
echo "Cross-compiling node-pty for ${TARGET} (API ${API})..."

SYSROOT="${TOOLCHAIN_DIR}/sysroot"
NAPI_INCLUDE="$NPM_PACK_DIR/node_modules/node-addon-api"
NODE_INCLUDE="$NODE_DIR/include/node"

mkdir -p build/Release

set +e
"$NDK_CXX" \
    -shared -fPIC -std=c++17 -O2 \
    -DNODE_ADDON_API_DISABLE_DEPRECATED \
    -DNAPI_VERSION=9 \
    -DNODE_GYP_MODULE_NAME=pty \
    -I"$NODE_INCLUDE" \
    -I"$NAPI_INCLUDE" \
    -o build/Release/pty.node \
    src/unix/pty.cc \
    2>&1 | while IFS= read -r line; do echo "  $line"; done
BUILD_EXIT=${PIPESTATUS[0]}
set -e

if [ "$BUILD_EXIT" -ne 0 ]; then
    echo ""
    echo "ERROR: Compilation failed (exit code $BUILD_EXIT)"
    echo "Check:"
    echo "  - NDK sysroot: $SYSROOT/usr/include/"
    echo "  - Node headers: $NODE_INCLUDE/"
    echo "  - node-addon-api: $NAPI_INCLUDE/"
    exit 1
fi

# --- Step 8: Verify output ---
echo ""
echo "Verifying output..."
PTY_NODE="$NPM_PACK_DIR/build/Release/pty.node"

if [ ! -f "$PTY_NODE" ]; then
    echo "ERROR: pty.node not found at $PTY_NODE"
    echo "Build directory contents:"
    find "$NPM_PACK_DIR/build" -type f 2>/dev/null || echo "  (build directory not found)"
    exit 1
fi

# Verify it's an ARM64 ELF
FILE_INFO="$(file "$PTY_NODE")"
echo "  $FILE_INFO"

if echo "$FILE_INFO" | grep -q "aarch64\|ARM aarch64"; then
    echo "  Architecture: ARM64 (OK)"
else
    echo "  ERROR: Expected ARM64 ELF, got: $FILE_INFO"
    exit 1
fi

if echo "$FILE_INFO" | grep -q "ELF"; then
    echo "  Format: ELF shared object (OK)"
else
    echo "  ERROR: Expected ELF format, got: $FILE_INFO"
    exit 1
fi

# Strip debug symbols
"${TOOLCHAIN_DIR}/bin/llvm-strip" "$PTY_NODE"

SIZE="$(du -sh "$PTY_NODE" | cut -f1)"
echo "  Size: $SIZE (stripped)"

# --- Step 9: Copy to output location ---
echo ""
echo "Copying to VS Code Server assets..."
mkdir -p "$OUTPUT_DIR"
cp "$PTY_NODE" "$OUTPUT_DIR/pty.node"
echo "  Output: $OUTPUT_DIR/pty.node ($SIZE)"

echo ""
echo "=== node-pty ${NODE_PTY_VERSION} built successfully ==="
echo ""
echo "Next steps:"
echo "  1. Run: ./scripts/download-vscode-server.sh  (patches index.js to use native pty)"
echo "  2. Run: cd android && ./gradlew assembleDebug"
