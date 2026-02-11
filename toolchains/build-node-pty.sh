#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"
BUILD_DIR="$SCRIPT_DIR/build/node-pty"
OUTPUT_DIR="$ROOT_DIR/android/app/src/main/jniLibs/arm64-v8a"
NODE_PTY_VERSION="1.0.0"
NODE_VERSION="20.18.1"

echo "=== Building node-pty for ARM64 Android ==="

if [ -z "${ANDROID_NDK_HOME:-}" ]; then
    echo "ERROR: ANDROID_NDK_HOME not set"
    exit 1
fi

NDK_HOST_TAG="$(uname -s | tr '[:upper:]' '[:lower:]')-$(uname -m)"
NDK_TOOLCHAIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$NDK_HOST_TAG"

mkdir -p "$BUILD_DIR"
cd "$BUILD_DIR"

# Clone node-pty
if [ ! -d "node-pty" ]; then
    git clone --depth 1 --branch "$NODE_PTY_VERSION" https://github.com/microsoft/node-pty.git
fi
cd node-pty

# Install deps
npm install

# Cross-compile
export CC="$NDK_TOOLCHAIN/bin/aarch64-linux-android28-clang"
export CXX="$NDK_TOOLCHAIN/bin/aarch64-linux-android28-clang++"

NODE_SRC_DIR="$SCRIPT_DIR/build/node/node-v${NODE_VERSION}"
if [ ! -d "$NODE_SRC_DIR" ]; then
    echo "ERROR: Node.js source not found at $NODE_SRC_DIR"
    echo "Run build-node.sh first."
    exit 1
fi

npx node-gyp rebuild \
    --arch=arm64 \
    --target_platform=android \
    --nodedir="$NODE_SRC_DIR"

# Copy output
mkdir -p "$OUTPUT_DIR"
cp build/Release/pty.node "$OUTPUT_DIR/libnode_pty.so"

echo "=== node-pty build complete ==="
echo "Output: $OUTPUT_DIR/libnode_pty.so"
