#!/usr/bin/env bash
set -euo pipefail

# Builds the two files that let the Claude Code CLI run on an Android that does
# not allow epoll_pwait2.
#
#   ANDROID_NDK_HOME=... ./scripts/build-claude-shim.sh
#
# Output, both into jniLibs/arm64-v8a so the package manager extracts them with
# the execute bit into nativeLibraryDir, the only directory this app may execve
# from:
#
#   libseccomp-shim.so   preloaded into the CLI; answers the refused syscall
#   libclaude-launch.so  what claudeCode.claudeProcessWrapper names; puts the
#                        shim in LD_PRELOAD and execs musl's loader
#
# The wall being worked around: an app may make only the system calls bionic
# exposes in SYSCALLS.TXT, and epoll_pwait2 (441) is there from android15 and
# absent on android13 and android14. The CLI's runtime calls it as soon as its
# event loop starts, so on those releases the process is refused and killed.
# Measured on an API 33 emulator: the refusal is SECCOMP_RET_TRAP, so it arrives
# as a catchable SIGSYS, the shim answers it with epoll_pwait, and the CLI then
# runs to completion where it used to die with signal 31.
#
# Two separate files rather than one, because they are loaded by different
# things: the launcher is an ordinary Bionic executable that Android starts, and
# the shim is a freestanding object musl's loader maps into a musl process. A
# single file cannot be both.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"
OUT_DIR="${OUT_DIR:-$ROOT_DIR/android/app/src/main/jniLibs/arm64-v8a}"
SHIM_OUT="$OUT_DIR/libseccomp-shim.so"
LAUNCH_OUT="$OUT_DIR/libclaude-launch.so"

TARGET=aarch64-linux-android
API=33

echo "=== Claude Code seccomp shim ==="

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

# Android 16 requires 16 KB-aligned segments; the NDK still defaults to 4 KB.
PAGE_SIZE_FLAGS=(-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384)
BUILD_ID_FLAG=(-Wl,--build-id=sha1)

# -nostdlib because this is loaded beside musl, not beside Bionic: linking
# Bionic's libc into it would put a second libc in a musl process. Everything it
# does is a raw `svc #0`, so it needs none.
#
# --pack-dyn-relocs=none is the one flag here that is not a preference, and it
# cost an afternoon. Targeting API 30 or newer, lld emits relative relocations in
# Android's packed form (DT_ANDROID_RELR), which bionic reads and musl 1.2.5 does
# not. The loader that maps this object is musl's, so the packed entries are
# skipped in silence: the single relocation this file has is the `.init_array`
# pointer at its constructor, and unrelocated it holds a link-time address. The
# loader then calls into whatever is at that address in a randomly based mapping,
# and the CLI dies with SIGSEGV before it has run a line -- a worse failure than
# the SIGSYS this shim exists to prevent, and one that looks nothing like it.
# Measured: the same source built for API 24 carries RELA plus RELACOUNT 1 and
# works; built for API 33 it carries ANDROID_RELR and segfaults every time.
"$CC" -shared -fPIC -O2 -Wall -Wextra -Werror \
    -nostdlib \
    -Wl,-soname,libseccomp-shim.so \
    -Wl,--pack-dyn-relocs=none \
    -o "$SHIM_OUT" \
    "$SCRIPT_DIR/seccomp-shim.c" \
    "${PAGE_SIZE_FLAGS[@]}" \
    "${BUILD_ID_FLAG[@]}"
echo "  shim   : $(wc -c < "$SHIM_OUT" | tr -d ' ') bytes"

# The shim must name no library at all. One NEEDED entry here would be a libc
# loaded into a process that already has a different one.
if "$NDK/toolchains/llvm/prebuilt/$HOST_TAG/bin/llvm-readelf" -d "$SHIM_OUT" | grep -q NEEDED; then
    echo "  ERROR: the shim depends on a library; it must be freestanding" >&2
    "$NDK/toolchains/llvm/prebuilt/$HOST_TAG/bin/llvm-readelf" -d "$SHIM_OUT" | grep NEEDED >&2
    exit 1
fi
echo "  shim   : no library dependencies"

# And no packed relative relocations, for the reason given above the link. This
# is checked rather than trusted to the flag, because the failure it prevents is
# a segfault before the first line of the CLI runs, and a future NDK is free to
# change what the flag defaults to.
if "$NDK/toolchains/llvm/prebuilt/$HOST_TAG/bin/llvm-readelf" -d "$SHIM_OUT" | grep -q ANDROID_RELR; then
    echo "  ERROR: the shim carries DT_ANDROID_RELR, which musl's loader ignores." >&2
    echo "         Its constructor would never be relocated and the CLI would" >&2
    echo "         crash on load. Keep --pack-dyn-relocs=none." >&2
    exit 1
fi
echo "  shim   : relative relocations in a form musl reads"

# -pie because Android refuses to execute a non-PIE binary.
"$CC" -pie -fPIE -O2 -Wall -Wextra -Werror \
    -o "$LAUNCH_OUT" \
    "$SCRIPT_DIR/claude-launch.c" \
    "${PAGE_SIZE_FLAGS[@]}" \
    "${BUILD_ID_FLAG[@]}"
echo "  launch : $(wc -c < "$LAUNCH_OUT" | tr -d ' ') bytes"

echo ""
echo "=== Verify ==="
python3 "$SCRIPT_DIR/verify-android-elf.py" "$LAUNCH_OUT" --lib-dir "$OUT_DIR"
python3 "$SCRIPT_DIR/verify-android-elf.py" "$SHIM_OUT" --lib-dir "$OUT_DIR"
