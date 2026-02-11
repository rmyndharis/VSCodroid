#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"
BUILD_DIR="$SCRIPT_DIR/build/bash"
OUTPUT_DIR="$ROOT_DIR/android/app/src/main/jniLibs/arm64-v8a"
BASH_VERSION_NUM="5.2"

echo "=== Building Bash $BASH_VERSION_NUM for ARM64 Android ==="

if [ -z "${ANDROID_NDK_HOME:-}" ]; then
    echo "ERROR: ANDROID_NDK_HOME not set"
    exit 1
fi

NDK_HOST_TAG="$(uname -s | tr '[:upper:]' '[:lower:]')-$(uname -m)"
NDK_TOOLCHAIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$NDK_HOST_TAG"

mkdir -p "$BUILD_DIR"
cd "$BUILD_DIR"

if [ ! -f "bash-${BASH_VERSION_NUM}.tar.gz" ]; then
    wget -q "https://ftp.gnu.org/gnu/bash/bash-${BASH_VERSION_NUM}.tar.gz"
fi
if [ ! -d "bash-${BASH_VERSION_NUM}" ]; then
    tar xf "bash-${BASH_VERSION_NUM}.tar.gz"
fi
cd "bash-${BASH_VERSION_NUM}"

export CC="$NDK_TOOLCHAIN/bin/aarch64-linux-android28-clang"
export LDFLAGS="-Wl,-z,max-page-size=16384"

./configure \
    --host=aarch64-linux-android \
    --prefix=/data/data/com.vscodroid/files/usr \
    --without-bash-malloc \
    --disable-nls \
    bash_cv_getenv_redef=no \
    bash_cv_getcwd_malloc=yes

make -j"$(nproc)"

"$NDK_TOOLCHAIN/bin/llvm-strip" --strip-unneeded bash
mkdir -p "$OUTPUT_DIR"
cp bash "$OUTPUT_DIR/libbash.so"

echo "=== Bash build complete ==="
echo "Output: $OUTPUT_DIR/libbash.so ($(du -sh "$OUTPUT_DIR/libbash.so" | cut -f1))"
