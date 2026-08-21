#!/usr/bin/env bash
set -euo pipefail

# Builds the one binary that makes a downloaded toolchain command runnable from
# anything that is not bash.
#
#   ANDROID_NDK_HOME=... ./scripts/build-exec-trampoline.sh
#
# Output: jniLibs/arm64-v8a/libexec-trampoline.so, an executable rather than a
# library. The `.so` name is the packaging trick the rest of this tree uses: the
# package manager extracts jniLibs into nativeLibraryDir with the execute bit,
# and that is the only directory this app may execve from. `usr/libexec/tcbin`
# then holds one symlink per toolchain command pointing here, so `ruby` and
# `java` are real files on PATH for the first time.
#
# Why the whole of scripts/exec-trampoline.c cannot be replaced by symlinks: the
# payload has to be handed to /system/bin/linker64 as its first ARGUMENT, and a
# symlink cannot insert an argument. Nothing else on the device does that
# insertion, and the app cannot write into nativeLibraryDir to place a per
# toolchain wrapper there, so one dispatching binary plus a table is the only
# shape available.
#
# No dependency beyond Bionic's libc, deliberately. This runs in front of every
# toolchain command, so anything it needed to load would be loaded again on every
# invocation, and any library it named would be one more thing that can be
# missing at the moment a user's build starts.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"
OUT_DIR="${OUT_DIR:-$ROOT_DIR/android/app/src/main/jniLibs/arm64-v8a}"
OUT="$OUT_DIR/libexec-trampoline.so"

TARGET=aarch64-linux-android
API=33

echo "=== toolchain execution trampoline ==="

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

# -pie because Android refuses to execute a non-PIE binary, and because
# /system/bin/linker64 is what loads this one too.
"$CC" -pie -fPIE -O2 -Wall -Wextra -Werror \
    -o "$OUT" \
    "$SCRIPT_DIR/exec-trampoline.c" \
    "${PAGE_SIZE_FLAGS[@]}" \
    "${BUILD_ID_FLAG[@]}"
echo "  $(wc -c < "$OUT" | tr -d ' ') bytes"

echo ""
echo "=== Verify ==="
# The same gate every other bundled binary passes, run here on the file just
# written rather than only by the Gradle sweep at packaging time. A
# wrong-architecture or 4 KB-aligned trampoline would install perfectly and make
# every toolchain command fail on an Android 16 device, which is
# indistinguishable from the toolchain not being installed.
python3 "$SCRIPT_DIR/verify-android-elf.py" "$OUT" --lib-dir "$OUT_DIR"
