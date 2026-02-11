#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"
BUILD_DIR="$SCRIPT_DIR/build/git"
OUTPUT_DIR="$ROOT_DIR/android/app/src/main/jniLibs/arm64-v8a"
ASSETS_DIR="$ROOT_DIR/android/app/src/main/assets/usr/lib/git-core"
GIT_VERSION="2.47.1"

echo "=== Building Git $GIT_VERSION for ARM64 Android ==="

if [ -z "${ANDROID_NDK_HOME:-}" ]; then
    echo "ERROR: ANDROID_NDK_HOME not set"
    exit 1
fi

NDK_HOST_TAG="$(uname -s | tr '[:upper:]' '[:lower:]')-$(uname -m)"
NDK_TOOLCHAIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$NDK_HOST_TAG"

mkdir -p "$BUILD_DIR"
cd "$BUILD_DIR"

# Download
if [ ! -f "git-${GIT_VERSION}.tar.xz" ]; then
    wget -q "https://mirrors.edge.kernel.org/pub/software/scm/git/git-${GIT_VERSION}.tar.xz"
fi
if [ ! -d "git-${GIT_VERSION}" ]; then
    tar xf "git-${GIT_VERSION}.tar.xz"
fi
cd "git-${GIT_VERSION}"

# Configure
export CC="$NDK_TOOLCHAIN/bin/aarch64-linux-android28-clang"
export LDFLAGS="-Wl,-z,max-page-size=16384"

./configure \
    --host=aarch64-linux-android \
    --prefix=/data/data/com.vscodroid/files/usr \
    --without-tcltk \
    --without-python \
    ac_cv_fread_reads_directories=no \
    ac_cv_snprintf_returns_bogus=no

# Build
make -j"$(nproc)" \
    NO_GETTEXT=YesPlease \
    NO_TCLTK=YesPlease \
    NO_PERL=YesPlease \
    NO_EXPAT=YesPlease \
    NO_CURL=YesPlease

# Install to staging
STAGING="$BUILD_DIR/staging"
make install DESTDIR="$STAGING" NO_GETTEXT=YesPlease NO_TCLTK=YesPlease

# Copy main binary
mkdir -p "$OUTPUT_DIR"
"$NDK_TOOLCHAIN/bin/llvm-strip" --strip-unneeded git
cp git "$OUTPUT_DIR/libgit.so"

# Copy git-core helpers
mkdir -p "$ASSETS_DIR"
cp -r "$STAGING/data/data/com.vscodroid/files/usr/lib/git-core/"* "$ASSETS_DIR/" 2>/dev/null || true

echo "=== Git build complete ==="
echo "Output: $OUTPUT_DIR/libgit.so"
