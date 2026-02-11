#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"
BUILD_DIR="$SCRIPT_DIR/build/make"
OUTPUT_DIR="$ROOT_DIR/android/app/src/main/jniLibs/arm64-v8a"
MAKE_VERSION="4.4.1"

echo "=== Building GNU Make $MAKE_VERSION for ARM64 Android ==="

if [ -z "${ANDROID_NDK_HOME:-}" ]; then
    echo "ERROR: ANDROID_NDK_HOME not set"
    exit 1
fi

NDK_HOST_TAG="$(uname -s | tr '[:upper:]' '[:lower:]')-$(uname -m)"
NDK_TOOLCHAIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$NDK_HOST_TAG"

mkdir -p "$BUILD_DIR"
cd "$BUILD_DIR"

if [ ! -f "make-${MAKE_VERSION}.tar.gz" ]; then
    wget -q "https://ftp.gnu.org/gnu/make/make-${MAKE_VERSION}.tar.gz"
fi
if [ ! -d "make-${MAKE_VERSION}" ]; then
    tar xf "make-${MAKE_VERSION}.tar.gz"
fi
cd "make-${MAKE_VERSION}"

export CC="$NDK_TOOLCHAIN/bin/aarch64-linux-android28-clang"
export LDFLAGS="-Wl,-z,max-page-size=16384"

./configure --host=aarch64-linux-android \
    --prefix=/data/data/com.vscodroid/files/usr \
    --disable-nls

make -j"$(nproc)"

"$NDK_TOOLCHAIN/bin/llvm-strip" --strip-unneeded make
mkdir -p "$OUTPUT_DIR"
cp make "$OUTPUT_DIR/libmake.so"

echo "=== Make build complete ==="
echo "Output: $OUTPUT_DIR/libmake.so"
