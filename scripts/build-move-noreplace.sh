#!/usr/bin/env bash
set -euo pipefail

# Builds the program the device-folder engine runs to move a file to a free name
# without replacing what may have appeared there (scripts/move-noreplace.c says
# why no call a Kotlin program has does that).
#
#   ANDROID_NDK_HOME=... ./scripts/build-move-noreplace.sh
#
# Output: jniLibs/arm64-v8a/libmove-noreplace.so, an executable rather than a
# library, named like one for the reason build-exec-trampoline.sh gives: the
# package manager extracts jniLibs into nativeLibraryDir with the execute bit,
# and that is the only directory this app may execve from.
#
# No dependency beyond Bionic's libc.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"
# Movable through a name of this script's own, never a generic OUT_DIR a shell
# may have exported for something else.
OUT_DIR="${MOVE_NOREPLACE_OUT_DIR:-$ROOT_DIR/android/app/src/main/jniLibs/arm64-v8a}"
OUT="$OUT_DIR/libmove-noreplace.so"

TARGET=aarch64-linux-android
# The app's minSdk, so the compiler refuses a libc call the oldest supported
# device does not have.
API=29

echo "=== move without replacing ==="

case "$(uname -s)" in
    Darwin) HOST_TAG=darwin-x86_64 ;;
    Linux)  HOST_TAG=linux-x86_64 ;;
    *) echo "  ERROR: unsupported host $(uname -s)" >&2; exit 1 ;;
esac

NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
if [ -z "$NDK" ] || [ ! -d "$NDK" ]; then
    echo "  ERROR: set ANDROID_NDK_HOME to an installed NDK" >&2
    exit 1
fi

CC="$NDK/toolchains/llvm/prebuilt/$HOST_TAG/bin/${TARGET}${API}-clang"
if [ ! -x "$CC" ]; then
    echo "  ERROR: no compiler at $CC" >&2
    exit 1
fi
echo "  ndk    : $NDK"

mkdir -p "$OUT_DIR"

# Android 16 requires 16 KB-aligned segments; NDK 27 still defaults to 4 KB.
PAGE_SIZE_FLAGS=(-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384)

# A GNU build-id, for the same reason build-glibc-shim.sh passes one: Play's
# native crash support pairs an uploaded symbol file with the shipped binary by
# this note, and a binary without one is read as no symbols at all however many
# it carries.
BUILD_ID_FLAG=(-Wl,--build-id=sha1)

# -pie because Android refuses to execute a non-PIE binary.
"$CC" -pie -fPIE -O2 -Wall -Wextra -Werror \
    -o "$OUT" \
    "$SCRIPT_DIR/move-noreplace.c" \
    "${PAGE_SIZE_FLAGS[@]}" \
    "${BUILD_ID_FLAG[@]}"
echo "  $(wc -c < "$OUT" | tr -d ' ') bytes"

echo ""
echo "=== Verify ==="
# The same gate every other bundled binary passes, run here on the file just
# written rather than only by the Gradle sweep at packaging time.
python3 "$SCRIPT_DIR/verify-android-elf.py" "$OUT" --lib-dir "$OUT_DIR"
