#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"
BUILD_DIR="$SCRIPT_DIR/build/tmux"
OUTPUT_DIR="$ROOT_DIR/android/app/src/main/jniLibs/arm64-v8a"
ASSETS_DIR="$ROOT_DIR/android/app/src/main/assets/usr/share/terminfo"
TMUX_VERSION="3.5a"
LIBEVENT_VERSION="2.1.12-stable"
NCURSES_VERSION="6.5"

echo "=== Building tmux $TMUX_VERSION for ARM64 Android ==="

if [ -z "${ANDROID_NDK_HOME:-}" ]; then
    echo "ERROR: ANDROID_NDK_HOME not set"
    exit 1
fi

NDK_HOST_TAG="$(uname -s | tr '[:upper:]' '[:lower:]')-$(uname -m)"
NDK_TOOLCHAIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$NDK_HOST_TAG"
SYSROOT="$BUILD_DIR/sysroot"
mkdir -p "$BUILD_DIR" "$SYSROOT/lib" "$SYSROOT/include"

export CC="$NDK_TOOLCHAIN/bin/aarch64-linux-android28-clang"
export LDFLAGS="-Wl,-z,max-page-size=16384 -L$SYSROOT/lib"
export CFLAGS="-I$SYSROOT/include"
export PKG_CONFIG_PATH="$SYSROOT/lib/pkgconfig"

cd "$BUILD_DIR"

# Build ncurses
echo "Building ncurses..."
if [ ! -d "ncurses-${NCURSES_VERSION}" ]; then
    wget -q "https://ftp.gnu.org/gnu/ncurses/ncurses-${NCURSES_VERSION}.tar.gz"
    tar xf "ncurses-${NCURSES_VERSION}.tar.gz"
fi
cd "ncurses-${NCURSES_VERSION}"
./configure --host=aarch64-linux-android --prefix="$SYSROOT" \
    --without-debug --without-ada --without-tests --without-manpages \
    --enable-widec --with-terminfo-dirs=/data/data/com.vscodroid/files/usr/share/terminfo
make -j"$(nproc)" && make install
cd "$BUILD_DIR"

# Build libevent
echo "Building libevent..."
if [ ! -d "libevent-${LIBEVENT_VERSION}" ]; then
    wget -q "https://github.com/libevent/libevent/releases/download/release-${LIBEVENT_VERSION}/libevent-${LIBEVENT_VERSION}.tar.gz"
    tar xf "libevent-${LIBEVENT_VERSION}.tar.gz"
fi
cd "libevent-${LIBEVENT_VERSION}"
./configure --host=aarch64-linux-android --prefix="$SYSROOT" --disable-samples --disable-libevent-regress
make -j"$(nproc)" && make install
cd "$BUILD_DIR"

# Build tmux
echo "Building tmux..."
if [ ! -d "tmux-${TMUX_VERSION}" ]; then
    wget -q "https://github.com/tmux/tmux/releases/download/${TMUX_VERSION}/tmux-${TMUX_VERSION}.tar.gz"
    tar xf "tmux-${TMUX_VERSION}.tar.gz"
fi
cd "tmux-${TMUX_VERSION}"
./configure --host=aarch64-linux-android --prefix=/data/data/com.vscodroid/files/usr \
    CFLAGS="$CFLAGS" LDFLAGS="$LDFLAGS" \
    LIBEVENT_CFLAGS="-I$SYSROOT/include" LIBEVENT_LIBS="-L$SYSROOT/lib -levent" \
    LIBNCURSES_CFLAGS="-I$SYSROOT/include/ncursesw" LIBNCURSES_LIBS="-L$SYSROOT/lib -lncursesw"
make -j"$(nproc)"

"$NDK_TOOLCHAIN/bin/llvm-strip" --strip-unneeded tmux
mkdir -p "$OUTPUT_DIR"
cp tmux "$OUTPUT_DIR/libtmux.so"

# Copy terminfo database
mkdir -p "$ASSETS_DIR"
cp -r "$SYSROOT/share/terminfo/"* "$ASSETS_DIR/" 2>/dev/null || true

echo "=== tmux build complete ==="
echo "Output: $OUTPUT_DIR/libtmux.so"
