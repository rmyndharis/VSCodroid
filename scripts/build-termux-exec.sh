#!/usr/bin/env bash
set -euo pipefail

# Builds the exec interceptor the editor's terminals preload.
#
#   ANDROID_NDK_HOME=... ./scripts/build-termux-exec.sh
#
# Output: assets/usr/lib/libtermux-exec.so, and the upstream licence texts
# under assets/usr/share/doc/termux-exec/.
#
# What it is for. SELinux refuses execve() of anything under the app's own
# storage (no execute_no_trans on app_data_file for targetSdk >= 29), which is
# why every binary this app ships is a lib*.so in nativeLibraryDir. The same
# policy does let that storage be mmap'ed and executed, so a program there can
# be run by handing it to /system/bin/linker64 as an argument. termux-exec is
# the library that does the handing over: preloaded into a process, it
# interposes the exec family and turns execve("<filesDir>/a.out") into
# execve("/system/bin/linker64", ["<filesDir>/a.out", ...]), and resolves a
# `#!` line the same way. It is the mechanism the Termux build on Google Play
# uses, at $PREFIX/lib/libtermux-exec.so. With it a `./hello` the user just
# compiled, a `#!/bin/sh` git hook and a venv console script run from the
# terminal; without it each of those is "Permission denied" however it is
# chmod'ed.
#
# Only the terminals get it. The Kotlin side hands the path to the workbench
# through `terminal.integrated.env.linux`, which reaches terminals, shell tasks
# and process tasks; anything the extension host spawns without a pty (the git
# extension's own git, a language server) keeps today's behaviour. That
# boundary is deliberate, and it is why the app's own process environment does
# not carry LD_PRELOAD.
#
# The value the terminal exports has to be this exact file. Bionic treats every
# LD_PRELOAD entry like a DT_NEEDED and aborts the exec when one cannot be
# found, `CANNOT LINK EXECUTABLE "bash": library "..." not found`, for bash,
# node, python3 and /system/bin/sh alike, with no warning mode. Measured on API
# 33 and 36 emulators, 2026-09-22/23: a dangling path killed every new terminal
# until the file was back. So the library is a real file under filesDir,
# extracted with the rest of usr/, never a symlink into nativeLibraryDir (which
# moves on every reinstall) and never a bare name (which dies the moment
# LD_LIBRARY_PATH is cleared).
#
# Built from source rather than taken from Termux's .deb. Upstream's decisions
# assume Termux's layout, where the prefix holds every binary, and five of them
# are wrong for this app; scripts/termux-exec.patch names each. The .deb is
# also linked with an rpath into Termux's own data directory.
#
# Compile and link lines follow the upstream Makefile targets
# build-libtermux-core_nos_c_tre (termux-core), build-libtermux-exec_nos_c_tre
# and build-libtermux-exec-direct-ld-preload (termux-exec): the sources go into
# two static archives, and only the eight exec*() entry points are exported
# (-fvisibility=hidden, --exclude-libs=ALL). Differences from Termux's build:
# API 33 instead of 24, no RUNPATH, 16 KB pages, and a GNU build-id. The
# compile-time defaults (/data/data/com.termux/...) are upstream's; at runtime
# every path is read from TERMUX_APP__DATA_DIR, TERMUX_APP__LEGACY_DATA_DIR and
# TERMUX__PREFIX, which Environment.kt exports, so the debug and release
# package names need no separate builds.
#
# Two upstream trees, each pinned by the sha256 of its GitHub tarball:
# termux-exec-package at its 2.5.0 tag, and termux-core-package at the commit
# the library was measured against, since that repository tags nothing and
# termux-exec links it in statically. A tarball whose digest moved is refused.
#
# After download-termux-tools.sh, which wipes assets/usr/lib. build-all.sh and
# both workflows run this directly after the glibc shim for that reason.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"
OUT_DIR="${OUT_DIR:-$ROOT_DIR/android/app/src/main/assets/usr/lib}"
# Beside the Termux notices, at usr/share/doc relative to the library, so a run
# into another OUT_DIR mirrors the whole layout rather than half of it.
DOC_DIR="$(dirname "$OUT_DIR")/share/doc/termux-exec"
WORK_DIR="${WORK_DIR:-$ROOT_DIR/toolchains/build/termux-exec}"
PATCH="$SCRIPT_DIR/termux-exec.patch"
OUT="$OUT_DIR/libtermux-exec.so"

TE_VERSION=2.5.0
TE_URL="https://github.com/termux/termux-exec-package/archive/refs/tags/v$TE_VERSION.tar.gz"
TE_SHA256=5c5eeb1565ad4379ce227ee3017f9fe88611c03ca91f00b8a3fadcf6f7396f51
TE_DIR="termux-exec-package-$TE_VERSION"
TC_COMMIT=63bf9286ad86603f9a58de73e4c740926c88f5e3
TC_URL="https://github.com/termux/termux-core-package/archive/$TC_COMMIT.tar.gz"
TC_SHA256=92a87ca75d51e0566abc198e7306276ab8293fddb3d778c526f7bbed77dc759e
TC_DIR="termux-core-package-$TC_COMMIT"

TARGET=aarch64-linux-android
API=33

echo "=== termux-exec LD_PRELOAD library ==="

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
AR="$BIN/llvm-ar"
READELF="$BIN/llvm-readelf"
if [ ! -x "$CC" ]; then
    echo "  ERROR: no compiler at $CC" >&2
    exit 1
fi
echo "  ndk    : $NDK"

if [ ! -f "$PATCH" ]; then
    echo "  ERROR: $PATCH is missing; an unpatched termux-exec is not this product" >&2
    exit 1
fi

mkdir -p "$WORK_DIR" "$OUT_DIR"

echo ""
echo "--- sources ---"
# Downloaded once, and checked against the pin on every run, cached file
# included: nothing here can tell a file this script wrote from one anything
# else left in the directory, the same rule the Termux .deb cache follows.
fetch() {
    local url="$1" file="$2" expected="$3" actual
    if [ -f "$file" ]; then
        echo "  $(basename "$file") (cached)"
    else
        curl -L --fail --show-error -o "$file" "$url"
        echo "  $(basename "$file") ($(du -sh "$file" | cut -f1))"
    fi
    actual=$( (sha256sum "$file" 2>/dev/null || shasum -a 256 "$file") | cut -d' ' -f1)
    if [ "$actual" != "$expected" ]; then
        echo "  ERROR: $(basename "$file") does not match the pinned digest" >&2
        echo "    pinned: $expected" >&2
        echo "    file  : $actual" >&2
        rm -f "$file"
        exit 1
    fi
}
fetch "$TE_URL" "$WORK_DIR/$TE_DIR.tar.gz" "$TE_SHA256"
fetch "$TC_URL" "$WORK_DIR/$TC_DIR.tar.gz" "$TC_SHA256"

# Unpacked fresh every run, so the patch always meets a pristine tree and a
# rerun cannot land on an already applied hunk.
SRC="$WORK_DIR/src"
rm -rf "$SRC"
mkdir -p "$SRC"
tar xzf "$WORK_DIR/$TE_DIR.tar.gz" -C "$SRC"
tar xzf "$WORK_DIR/$TC_DIR.tar.gz" -C "$SRC"
TE_SRC="$SRC/$TE_DIR"
TC_SRC="$SRC/$TC_DIR"
for dir in "$TE_SRC" "$TC_SRC"; do
    if [ ! -f "$dir/LICENSE" ] || [ ! -d "$dir/lib" ]; then
        echo "  ERROR: $dir is not the tree its tarball was expected to hold" >&2
        exit 1
    fi
done

echo ""
echo "--- patch ---"
# -F0: no fuzz. The upstream tree is pinned, so a hunk that needs fuzz to fit
# means the pin or the patch changed, and that is worth a stopped build rather
# than a hunk applied a few lines from where it was written.
patch -p1 -F0 -d "$TE_SRC" < "$PATCH"

echo ""
echo "--- compile ---"
# Upstream CFLAGS_FORCE, plus -fPIC for objects that end up in a shared object.
CFLAGS=(-Wall -Wextra -Werror -Wshadow -O2 -D_FORTIFY_SOURCE=2 -fstack-protector-strong -fPIC)
# Upstream LDFLAGS_DEFAULT without the Termux rpath. Then 16 KB pages, which
# Android 16 requires and NDK 27 does not default to, and a GNU build-id, which
# Play's native crash support needs to pair a symbol upload with the binary.
LDFLAGS=(-Wl,--enable-new-dtags -Wl,--as-needed -Wl,-z,relro,-z,now
    -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384 -Wl,--build-id=sha1)

TERMUX_APP__DATA_DIR=/data/data/com.termux
TERMUX__ROOTFS=$TERMUX_APP__DATA_DIR/files
TERMUX__PREFIX=$TERMUX__ROOTFS/usr
COMMON_MACROS=(
    -DTERMUX__NAME='"Termux"'
    -DTERMUX__LNAME='"termux"'
    -DTERMUX__REPOS_HOST_ORG_NAME='"termux"'
    -DTERMUX_APP__DATA_DIR="\"$TERMUX_APP__DATA_DIR\""
    -DTERMUX__ROOTFS="\"$TERMUX__ROOTFS\""
    -DTERMUX__PREFIX="\"$TERMUX__PREFIX\""
    -DTERMUX__PREFIX__BIN_DIR="\"$TERMUX__PREFIX/bin\""
    -DTERMUX_ENV__S_TERMUX='"TERMUX__"'
    -DTERMUX_ENV__S_TERMUX_APP='"TERMUX_APP__"'
    -DTERMUX_ENV__S_TERMUX_ROOTFS='"TERMUX_ROOTFS__"'
)
CORE_MACROS=("${COMMON_MACROS[@]}"
    -DTERMUX_CORE_PKG__VERSION='"0.4.0"'
    -DTERMUX__PREFIX__TMP_DIR="\"$TERMUX__PREFIX/tmp\""
    -DTERMUX_ENV__S_TERMUX_CORE__TESTS='"TERMUX_CORE__TESTS__"'
)
EXEC_MACROS=("${COMMON_MACROS[@]}"
    -DTERMUX_EXEC_PKG__VERSION="\"$TE_VERSION\""
    -DTERMUX_ENV__S_TERMUX_EXEC='"TERMUX_EXEC__"'
    -DTERMUX_ENV__S_TERMUX_EXEC__TESTS='"TERMUX_EXEC__TESTS__"'
    -DLIBTERMUX_EXEC__NOS__C__EXECVE_CALL__CHECK_ARGV0_BUFFER_OVERFLOW=0
)

# LIBTERMUX_CORE__NOS__C__SOURCE_FILES and LIBTERMUX_EXEC__NOS__C__SOURCE_FILES
# from the two Makefiles, at the pinned trees.
CORE_SOURCES=(
    android/shell/command/environment/AndroidShellEnvironment.c
    TermuxCoreLibraryConfig.c
    data/AssertUtils.c
    data/DataUtils.c
    logger/FileLoggerImpl.c
    logger/Logger.c
    logger/StandardLoggerImpl.c
    security/SecurityUtils.c
    termux/file/TermuxFile.c
    termux/shell/command/environment/TermuxShellEnvironment.c
    termux/shell/command/environment/termux_core/TermuxCoreShellEnvironment.c
    unix/file/UnixFileUtils.c
    unix/os/process/UnixForkUtils.c
    unix/os/process/UnixSafeStrerror.c
    unix/os/process/UnixSignalUtils.c
    unix/os/selinux/UnixSeLinuxUtils.c
    unix/shell/command/environment/UnixShellEnvironment.c
)
EXEC_SOURCES=(
    TermuxExecLibraryConfig.c
    termux/api/termux_exec/service/ld_preload/TermuxExecLDPreload.c
    termux/api/termux_exec/service/ld_preload/direct/exec/ExecIntercept.c
    termux/api/termux_exec/service/ld_preload/direct/exec/ExecVariantsIntercept.c
    termux/os/process/termux_exec/TermuxExecProcess.c
    termux/shell/command/environment/termux_exec/TermuxExecShellEnvironment.c
)
# The "direct" variant: what Termux's own package installs as
# libtermux-exec-ld-preload.so, and what every measurement here preloaded.
ENTRY=app/termux-exec-direct-ld-preload/src/termux/api/termux_exec/service/ld_preload/direct/TermuxExecDirectLDPreloadEntryPoint.c

CORE_INC="$TC_SRC/lib/termux-core_nos_c/tre/include"
EXEC_INC="$TE_SRC/lib/termux-exec_nos_c/tre/include"
OBJ="$WORK_DIR/obj"
rm -rf "$OBJ"
mkdir -p "$OBJ/core" "$OBJ/exec"

for src in "${CORE_SOURCES[@]}"; do
    "$CC" -c "${CFLAGS[@]}" -I "$CORE_INC" "${CORE_MACROS[@]}" -fvisibility=default \
        -o "$OBJ/core/${src//\//_}.o" "$TC_SRC/lib/termux-core_nos_c/tre/src/$src"
done
"$AR" rcs "$OBJ/libtermux-core_nos_c_tre.a" "$OBJ"/core/*.o

for src in "${EXEC_SOURCES[@]}"; do
    "$CC" -c "${CFLAGS[@]}" -I "$CORE_INC" -I "$EXEC_INC" "${EXEC_MACROS[@]}" -fvisibility=default \
        -o "$OBJ/exec/${src//\//_}.o" "$TE_SRC/lib/termux-exec_nos_c/tre/src/$src"
done
"$AR" rcs "$OBJ/libtermux-exec_nos_c_tre.a" "$OBJ"/exec/*.o

"$CC" "${CFLAGS[@]}" -I "$CORE_INC" -I "$EXEC_INC" "${EXEC_MACROS[@]}" \
    -shared -fvisibility=hidden -Wl,--exclude-libs=ALL "${LDFLAGS[@]}" \
    -o "$OUT" "$TE_SRC/$ENTRY" \
    -L "$OBJ" -l:libtermux-exec_nos_c_tre.a -l:libtermux-core_nos_c_tre.a
echo "  $(basename "$OUT"): $(wc -c < "$OUT" | tr -d ' ') bytes"

echo ""
echo "--- notices ---"
# Apache-2.0 section 4 asks that a redistributed copy carry the licence text and
# the upstream copyright notices, and that a modified file say so; MIT asks for
# its notice. The two DEP-5 LICENSE files say which text covers which files,
# and the texts they point at travel with them under names the attribution
# gate recognises. The modification notice is the first line of each patched
# source, put there by the patch itself.
rm -rf "$DOC_DIR"
mkdir -p "$DOC_DIR"
for src in "$TE_SRC" "$TC_SRC"; do
    pkg="$(basename "$src")"
    pkg="${pkg%-*}"
    cp "$src/LICENSE" "$DOC_DIR/LICENSE.$pkg"
    for text in "$src"/licenses/*.md; do
        cp "$text" "$DOC_DIR/LICENSE.$pkg.${text##*__}"
    done
done
for f in "$DOC_DIR"/*; do
    echo "  $(basename "$f") ($(wc -c < "$f" | tr -d ' ') bytes)"
done

echo ""
echo "=== Verify ==="
python3 "$SCRIPT_DIR/verify-android-elf.py" "$OUT" --lib-dir "$OUT_DIR"

# The export set is the whole interface, and this is preloaded into every
# process a terminal starts. A build that lost -fvisibility=hidden or
# --exclude-libs=ALL would export termux-core's own helpers into all of them,
# where a same-named symbol in any program would resolve here first.
EXPECTED_EXPORTS="execl execle execlp execv execve execvp execvpe fexecve"
EXPORTS="$("$READELF" --dyn-syms "$OUT" \
    | awk '($5=="GLOBAL"||$5=="WEAK") && $7!="UND" {print $8}' | sort | tr '\n' ' ' | sed 's/ $//')"
if [ "$EXPORTS" != "$EXPECTED_EXPORTS" ]; then
    echo "  ERROR: the library exports more or less than the exec family" >&2
    echo "    expected: $EXPECTED_EXPORTS" >&2
    echo "    got     : $EXPORTS" >&2
    exit 1
fi
echo "  exports: $EXPORTS"
