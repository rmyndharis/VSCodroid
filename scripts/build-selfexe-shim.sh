#!/usr/bin/env bash
set -euo pipefail

# Builds the library that gives the Codex engine a truthful /proc/self/exe and
# a path through /data/user/0 and through a device folder's name.
#
#   ANDROID_NDK_HOME=... ./scripts/build-selfexe-shim.sh
#
# Output: assets/usr/lib/libselfexe.so, beside libtermux-exec.so. Only the
# Codex launcher preloads it (ToolchainManager.addCodexRows); see
# scripts/selfexe-shim.c for what it answers and when.
#
# Preloaded into a program, so the export set is the whole interface: the two
# readlink and the two openat entry points, and nothing else. A build that
# exported a helper would put it in front of the same name in every program the
# engine starts.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"
OUT_DIR="${OUT_DIR:-$ROOT_DIR/android/app/src/main/assets/usr/lib}"
OUT="$OUT_DIR/libselfexe.so"

TARGET=aarch64-linux-android
# The app's minSdk. Bionic aborts an exec whose LD_PRELOAD names a library with a
# symbol it lacks, for everything the launcher starts, so a call newer than the
# oldest supported device has to fail the build here and not the engine there.
API=29

echo "=== selfexe LD_PRELOAD library ==="

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

BIN="$NDK/toolchains/llvm/prebuilt/$HOST_TAG/bin"
CC="$BIN/${TARGET}${API}-clang"
READELF="$BIN/llvm-readelf"
if [ ! -x "$CC" ]; then
    echo "  ERROR: no compiler at $CC" >&2
    exit 1
fi
echo "  ndk    : $NDK"

mkdir -p "$OUT_DIR"

# Android 16 requires 16 KB-aligned segments; NDK 27 still defaults to 4 KB.
PAGE_SIZE_FLAGS=(-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384)

# A GNU build-id, for the same reason build-exec-trampoline.sh passes one.
BUILD_ID_FLAG=(-Wl,--build-id=sha1)

"$CC" -shared -fPIC -fvisibility=hidden -O2 -Wall -Wextra -Werror \
    -Wl,--exclude-libs=ALL \
    -o "$OUT" \
    "$SCRIPT_DIR/selfexe-shim.c" \
    "${PAGE_SIZE_FLAGS[@]}" \
    "${BUILD_ID_FLAG[@]}"
echo "  $(basename "$OUT"): $(wc -c < "$OUT" | tr -d ' ') bytes"

echo ""
echo "=== Verify ==="
python3 "$SCRIPT_DIR/verify-android-elf.py" "$OUT" --lib-dir "$OUT_DIR"

EXPECTED_EXPORTS="openat openat64 readlink readlinkat"
EXPORTS="$("$READELF" --dyn-syms "$OUT" \
    | awk '($5=="GLOBAL"||$5=="WEAK") && $7!="UND" {print $8}' | sort | tr '\n' ' ' | sed 's/ $//')"
if [ "$EXPORTS" != "$EXPECTED_EXPORTS" ]; then
    echo "  ERROR: the library exports more or less than the readlink and openat entry points" >&2
    echo "    expected: $EXPECTED_EXPORTS" >&2
    echo "    got     : $EXPORTS" >&2
    exit 1
fi
echo "  exports: $EXPORTS"
