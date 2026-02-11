#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"
SOURCE="$ROOT_DIR/android/app/src/main/cpp/ptybridge.c"
OUTPUT_DIR="$ROOT_DIR/android/app/src/main/jniLibs/arm64-v8a"
OUTPUT="$OUTPUT_DIR/libptybridge.so"

# API level must match app's minSdk (33 = Android 13)
API_LEVEL=33

echo "=== Building PTY Bridge ==="

# Find Android NDK
if [ -n "${ANDROID_NDK_HOME:-}" ]; then
    NDK_DIR="$ANDROID_NDK_HOME"
elif [ -n "${ANDROID_HOME:-}" ] && [ -d "$ANDROID_HOME/ndk" ]; then
    # Use latest NDK version available
    NDK_DIR="$(ls -d "$ANDROID_HOME/ndk/"* 2>/dev/null | sort -V | tail -1)"
elif [ -d "$HOME/Library/Android/sdk/ndk" ]; then
    NDK_DIR="$(ls -d "$HOME/Library/Android/sdk/ndk/"* 2>/dev/null | sort -V | tail -1)"
else
    echo "ERROR: Cannot find Android NDK."
    echo "Set ANDROID_NDK_HOME or ANDROID_HOME environment variable."
    exit 1
fi

echo "  NDK: $NDK_DIR"

# Find the cross-compiler
# NDK r25+ uses llvm/bin toolchain
CC="$NDK_DIR/toolchains/llvm/prebuilt/$(uname -s | tr '[:upper:]' '[:lower:]')-$(uname -m)/bin/aarch64-linux-android${API_LEVEL}-clang"

if [ ! -f "$CC" ]; then
    # Try darwin-x86_64 on macOS (Rosetta or older NDK)
    CC="$NDK_DIR/toolchains/llvm/prebuilt/darwin-x86_64/bin/aarch64-linux-android${API_LEVEL}-clang"
fi

if [ ! -f "$CC" ]; then
    echo "ERROR: Cannot find aarch64 clang in NDK"
    echo "Looked for: aarch64-linux-android${API_LEVEL}-clang"
    echo "NDK toolchain dir:"
    ls "$NDK_DIR/toolchains/llvm/prebuilt/" 2>/dev/null || echo "  (not found)"
    exit 1
fi

echo "  CC: $CC"

# Compile
mkdir -p "$OUTPUT_DIR"
# Note: no -lutil needed — Android Bionic includes forkpty() directly
"$CC" -O2 -Wall -Wextra \
    -o "$OUTPUT" \
    "$SOURCE"

echo "  Output: $OUTPUT ($(du -sh "$OUTPUT" | cut -f1))"
echo ""
echo "=== PTY Bridge built successfully ==="
