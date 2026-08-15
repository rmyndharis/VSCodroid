#!/usr/bin/env bash
set -euo pipefail

# Cross-compiles the native addons the VS Code server needs into Bionic ARM64
# .node files. Every build — ours or Microsoft's — ships these compiled against
# glibc, and Android's loader cannot open them, so each one has to be replaced.
#
# Compiles by invoking NDK clang directly rather than going through node-gyp.
# node-gyp's generator injects host-specific flags (on macOS, -arch) that NDK
# clang rejects, and these addons are small enough that listing their sources
# costs less than fighting the generator.
#
#   ./scripts/build-native-addons.sh              # into assets/vscode-reh
#   OUTPUT_ROOT=/path/to/tree ./scripts/build-native-addons.sh
#
# Prerequisites: Android NDK r27+ (ANDROID_NDK_HOME, or the Android Studio SDK).

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"
WORK_DIR="${WORK_DIR:-$ROOT_DIR/.build/native-addons}"
OUTPUT_ROOT="${OUTPUT_ROOT:-$ROOT_DIR/android/app/src/main/assets/vscode-reh}"

# Must match remote/.npmrc `target` at the VS Code tag AND the bundled
# libnode.so. A mismatch changes NODE_MODULE_VERSION and every addon here is
# rejected at load with no useful message — terminals stop working and the log
# says only that a module could not be loaded. The check at the end compares the
# headers used here against the runtime actually being shipped, so the two
# cannot drift apart silently.
NODE_VERSION="${NODE_VERSION:-24.18.0}"

TARGET=aarch64-linux-android
API=33

# Android 16 requires 16 KB-aligned segments. NDK r28+ defaults to this and r27
# does not, so passing it explicitly is what makes the result independent of
# whichever NDK happens to be installed. The verify step below fails the build
# rather than trusting the default.
PAGE_SIZE_FLAGS=(-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384)

echo "=== Building native addons for Android ARM64 (Bionic) ==="

# --- Toolchain -------------------------------------------------------------

if [ -n "${ANDROID_NDK_HOME:-}" ]; then
    NDK_DIR="$ANDROID_NDK_HOME"
elif [ -n "${ANDROID_HOME:-}" ] && [ -d "$ANDROID_HOME/ndk" ]; then
    NDK_DIR="$(ls -d "$ANDROID_HOME/ndk/"* 2>/dev/null | sort -V | tail -1)"
elif [ -d "$HOME/Library/Android/sdk/ndk" ]; then
    NDK_DIR="$(ls -d "$HOME/Library/Android/sdk/ndk/"* 2>/dev/null | sort -V | tail -1)"
else
    echo "ERROR: Cannot find Android NDK. Set ANDROID_NDK_HOME." >&2
    exit 1
fi
[ -d "$NDK_DIR" ] || { echo "ERROR: NDK not at $NDK_DIR" >&2; exit 1; }

HOST_TAG="$(uname -s | tr '[:upper:]' '[:lower:]')-$(uname -m)"
TOOLCHAIN="$NDK_DIR/toolchains/llvm/prebuilt/$HOST_TAG"
# Google ships no darwin-arm64 or linux-aarch64 host toolchain; on Apple Silicon
# the x86_64 one runs under Rosetta.
[ -d "$TOOLCHAIN" ] || TOOLCHAIN="$NDK_DIR/toolchains/llvm/prebuilt/darwin-x86_64"
[ -d "$TOOLCHAIN" ] || TOOLCHAIN="$NDK_DIR/toolchains/llvm/prebuilt/linux-x86_64"
[ -d "$TOOLCHAIN" ] || { echo "ERROR: no NDK host toolchain in $NDK_DIR" >&2; exit 1; }

CXX="$TOOLCHAIN/bin/$TARGET$API-clang++"
CC="$TOOLCHAIN/bin/$TARGET$API-clang"
STRIP="$TOOLCHAIN/bin/llvm-strip"
READELF="$TOOLCHAIN/bin/llvm-readelf"
[ -x "$CXX" ] || { echo "ERROR: $CXX not found" >&2; exit 1; }

echo "  NDK    : $NDK_DIR"
echo "  target : $TARGET$API"
echo "  node   : v$NODE_VERSION headers"
echo "  output : $OUTPUT_ROOT"

# --- Node headers ----------------------------------------------------------

mkdir -p "$WORK_DIR"
NODE_INCLUDE="$WORK_DIR/node-v$NODE_VERSION/include/node"
if [ ! -d "$NODE_INCLUDE" ]; then
    echo ""
    echo "Downloading Node v$NODE_VERSION headers..."
    # Downloaded to a file rather than piped into tar, because a pipe leaves
    # nothing to hash. These headers define the ABI every addon here is compiled
    # against, so a substituted archive is a substituted ABI -- and this was the
    # last fetch in the tree still taking bytes on trust after every other one
    # was made to verify.
    #
    # Same shape as download-npm.sh: nodejs.org publishes SHASUMS256.txt per
    # release, and the digest is kept in a sidecar so an offline rebuild against
    # an already-verified archive still verifies rather than dying in the fetch.
    # With neither the network nor a sidecar, fail closed.
    HEADERS_TARBALL="node-v$NODE_VERSION-headers.tar.gz"
    HEADERS_PATH="$WORK_DIR/$HEADERS_TARBALL"
    HEADERS_SIDECAR="$HEADERS_PATH.sha256"
    [ -f "$HEADERS_PATH" ] || curl -fsSL --show-error \
        "https://nodejs.org/dist/v$NODE_VERSION/$HEADERS_TARBALL" -o "$HEADERS_PATH"

    expected=$(curl -sL "https://nodejs.org/dist/v$NODE_VERSION/SHASUMS256.txt" 2>/dev/null \
        | awk -v f="$HEADERS_TARBALL" '$2 == f { print $1; exit }' || true)
    if [ -n "$expected" ]; then
        printf '%s\n' "$expected" > "$HEADERS_SIDECAR"
    elif [ -f "$HEADERS_SIDECAR" ]; then
        expected=$(cat "$HEADERS_SIDECAR")
        echo "  sha256: using cached digest (SHASUMS256.txt unreachable)"
    fi
    if [ -z "$expected" ]; then
        echo "  ERROR: no digest for $HEADERS_TARBALL (SHASUMS256.txt unreachable, no cached sidecar)" >&2
        exit 1
    fi
    actual=$( (sha256sum "$HEADERS_PATH" 2>/dev/null || shasum -a 256 "$HEADERS_PATH") | cut -d' ' -f1)
    if [ "$actual" != "$expected" ]; then
        echo "  ERROR: $HEADERS_TARBALL does not match SHASUMS256.txt" >&2
        echo "    published : $expected" >&2
        echo "    downloaded: $actual" >&2
        rm -f "$HEADERS_PATH"
        exit 1
    fi
    echo "  sha256  : verified"
    tar xz -C "$WORK_DIR" -f "$HEADERS_PATH"
fi
[ -d "$NODE_INCLUDE" ] || { echo "ERROR: headers missing at $NODE_INCLUDE" >&2; exit 1; }

# --- Build -----------------------------------------------------------------

# The comment on NODE_VERSION above promises a check comparing these headers
# against the runtime actually shipped; this is that check. An earlier form of
# it never ran in CI at all - both workflows built addons before fetching the
# runtime, and the missing-binary branch was a warning - so this one fails
# closed and reads the version marker download-node.sh writes next to the
# binary (the binary itself is gitignored). The comparison is major-only on
# purpose: NODE_MODULE_VERSION, which is what actually breaks addon loading,
# moves with the major, and a full-triple match would fail builds over a
# harmless patch bump.
JNILIBS="$ROOT_DIR/android/app/src/main/jniLibs/arm64-v8a"
runtime_version=""
if [ -f "$JNILIBS/.libnode-version" ]; then
    runtime_version=$(cat "$JNILIBS/.libnode-version")
elif [ -f "$JNILIBS/libnode.so" ]; then
    # A libnode.so from before the marker existed: read process.version out of
    # the binary itself.
    runtime_version=$(strings -n 6 "$JNILIBS/libnode.so" 2>/dev/null \
        | grep -E '^v[0-9]+\.[0-9]+\.[0-9]+$' | head -1 | tr -d v || true)
fi
if [ -z "$runtime_version" ]; then
    echo "ERROR: no bundled Node runtime to pair against ($JNILIBS)." >&2
    echo "  Run scripts/download-node.sh first - these addons must be built" >&2
    echo "  against the headers of the runtime that will load them." >&2
    exit 1
fi
if [ "${runtime_version%%.*}" != "${NODE_VERSION%%.*}" ]; then
    echo "ERROR: headers are for v$NODE_VERSION but the bundled runtime is v$runtime_version" >&2
    echo "  A major mismatch changes NODE_MODULE_VERSION and every addon here is" >&2
    echo "  rejected at load with no useful message. Fix NODE_VERSION or re-run" >&2
    echo "  scripts/download-node.sh before building addons." >&2
    exit 1
fi
echo "  runtime: v$runtime_version (major matches v$NODE_VERSION headers)"

# check_pair <name> <native-version> <package.json> — the .node built here must
# ship beside the SAME JS the version was chosen for. Between prerelease
# versions the private binding surface moves, so a silent mismatch is a
# runtime failure on device, not a build failure here.
check_pair() {
    local name=$1 native=$2 pkg_json=$3
    if [ ! -f "$pkg_json" ]; then
        # Fail closed: an addon built with no JS beside it is exactly the
        # unpaired state this gate exists to prevent. Fetch the server tree
        # first.
        echo "ERROR: $name JS not present at $pkg_json; cannot verify pairing" >&2
        return 1
    fi
    local js
    js=$(python3 -c "import json,sys; print(json.load(open(sys.argv[1]))['version'])" "$pkg_json")
    if [ "$js" != "$native" ]; then
        echo "ERROR: $name native is built from $native but the shipped JS is $js" >&2
        echo "  Align the fetch version in this script with the tree before shipping." >&2
        return 1
    fi
    echo "  pairing: $name JS matches native ($js)"
}

# fetch <package> <version> -> echoes the unpacked source directory
fetch() {
    local pkg=$1 version=$2
    local dir="$WORK_DIR/src/${pkg//\//_}-$version"
    if [ ! -d "$dir" ]; then
        mkdir -p "$dir"
        ( cd "$dir" && npm pack "$pkg@$version" --quiet >/dev/null \
            && tar xzf ./*.tgz --strip-components=1 && rm -f ./*.tgz )
        # node-addon-api supplies napi.h; nothing else is needed to link.
        ( cd "$dir" && npm install --ignore-scripts --no-audit --no-fund --quiet >/dev/null 2>&1 || true )
    fi
    echo "$dir"
}

# compile <src-dir> <out.node> <sources...> -- <extra compiler flags...>
compile() {
    local src=$1 out=$2; shift 2
    local sources=() flags=()
    local seen_sep=0
    for arg in "$@"; do
        if [ "$arg" = "--" ]; then seen_sep=1; continue; fi
        if [ "$seen_sep" -eq 0 ]; then sources+=("$src/$arg"); else flags+=("$arg"); fi
    done

    mkdir -p "$(dirname "$out")"
    "$CXX" \
        -shared -fPIC -std=c++17 -O2 \
        -static-libstdc++ \
        "${PAGE_SIZE_FLAGS[@]}" \
        -DNAPI_VERSION=9 \
        -I"$NODE_INCLUDE" \
        -I"$src/node_modules/node-addon-api" \
        "${flags[@]}" \
        -o "$out" \
        "${sources[@]}"
    "$STRIP" "$out"
}

# verify <out.node> — a glibc dependency or a 4 KB segment must fail the build,
# not surface later as a dlopen error on a user's device.
verify() {
    local out=$1 name=$2

    # Shared with download-node.sh rather than reimplemented against the NDK's
    # readelf: an addon and the runtime that loads it have to agree about what
    # "loads on Android" means, and two copies of that judgement drift.
    python3 "$SCRIPT_DIR/verify-android-elf.py" "$out" || return 1
    printf '  ok   %-24s %8s bytes\n' "$name" "$(wc -c < "$out" | tr -d ' ')"
}

failed=0

# node-pty — the terminal. ptyHostMain imports it statically, so a failure here
# is not a degraded terminal, it is no terminal at all.
echo ""
echo "node-pty..."
PTY_VERSION=1.2.0-beta.15
PTY_SRC=$(fetch node-pty "$PTY_VERSION")
# Bionic has forkpty() in libc; binding.gyp's -lutil is for glibc only. Compiling
# directly means simply not passing it, so binding.gyp needs no patching.
compile "$PTY_SRC" "$OUTPUT_ROOT/node_modules/node-pty/build/Release/pty.node" \
    src/unix/pty.cc \
    -- -DNODE_ADDON_API_DISABLE_DEPRECATED -DNODE_GYP_MODULE_NAME=pty
verify "$OUTPUT_ROOT/node_modules/node-pty/build/Release/pty.node" node-pty || failed=1
check_pair node-pty "$PTY_VERSION" "$OUTPUT_ROOT/node_modules/node-pty/package.json" || failed=1

# @parcel/watcher — recursive file watching. watcherMain imports it statically
# too, so without it recursive watching is dead rather than degraded, and an
# extension is simply never told a file changed.
echo ""
echo "@parcel/watcher..."
WATCHER_VERSION=2.5.6
WATCHER_SRC=$(fetch @parcel/watcher "$WATCHER_VERSION")
# Sources and defines are binding.gyp's OS=="linux" branch. Android has inotify,
# so that backend is the one that matters; watchman stays compiled in and inert
# because nothing serves its socket here.
compile "$WATCHER_SRC" "$OUTPUT_ROOT/node_modules/@parcel/watcher/build/Release/watcher.node" \
    src/binding.cc src/Watcher.cc src/Backend.cc src/DirTree.cc src/Glob.cc \
    src/watchman/BSER.cc src/watchman/WatchmanBackend.cc \
    src/shared/BruteForceBackend.cc src/linux/InotifyBackend.cc src/unix/legacy.cc \
    -- -fexceptions -DNAPI_DISABLE_CPP_EXCEPTIONS \
       -DWATCHMAN -DINOTIFY -DBRUTE_FORCE -DNODE_GYP_MODULE_NAME=watcher
verify "$OUTPUT_ROOT/node_modules/@parcel/watcher/build/Release/watcher.node" @parcel/watcher || failed=1
check_pair @parcel/watcher "$WATCHER_VERSION" "$OUTPUT_ROOT/node_modules/@parcel/watcher/package.json" || failed=1

# @vscode/sqlite3 — the workbench's storage engine, and what the Copilot CLI
# session store opens through the embedder. The glibc build fails dlopen on
# libstdc++.so.6, which surfaces as Copilot's "(modelSelectionFailed)" — an
# error three layers from its cause. Bundles SQLite itself: the amalgamation
# compiles as C, the addon as C++, so this block is two-phase where the others
# are one call.
echo ""
echo "@vscode/sqlite3..."
SQLITE_VERSION=5.1.12-vscode
SQLITE_SRC=$(fetch @vscode/sqlite3 "$SQLITE_VERSION")
SQLITE_AMALGAMATION="$SQLITE_SRC/sqlite-autoconf-3390400"
if [ ! -d "$SQLITE_AMALGAMATION" ]; then
    tar xzf "$SQLITE_SRC/deps/sqlite-autoconf-3390400.tar.gz" -C "$SQLITE_SRC"
fi
# Defines are deps/sqlite3.gyp's list. NDEBUG matters: without it SQLite
# compiles its internal assert()s in and slows every query.
SQLITE_DEFINES=(
    -DNDEBUG -D_REENTRANT=1 -DSQLITE_THREADSAFE=1 -DHAVE_USLEEP=1
    -DSQLITE_ENABLE_FTS3 -DSQLITE_ENABLE_FTS4 -DSQLITE_ENABLE_FTS5
    -DSQLITE_ENABLE_RTREE -DSQLITE_ENABLE_DBSTAT_VTAB=1
    -DSQLITE_ENABLE_MATH_FUNCTIONS
)
SQLITE_OUT="$OUTPUT_ROOT/node_modules/@vscode/sqlite3/build/Release/vscode-sqlite3.node"
mkdir -p "$(dirname "$SQLITE_OUT")"
"$CC" -c -fPIC -O2 "${SQLITE_DEFINES[@]}" \
    -o "$WORK_DIR/sqlite3.o" "$SQLITE_AMALGAMATION/sqlite3.c"
# node_addon_api_except in binding.gyp means C++ exceptions are ON here, unlike
# the addons above — so -fexceptions and no NAPI_DISABLE_CPP_EXCEPTIONS.
"$CXX" \
    -shared -fPIC -std=c++17 -O2 -fexceptions \
    -static-libstdc++ \
    "${PAGE_SIZE_FLAGS[@]}" \
    -DNAPI_VERSION=8 -DNODE_API_SWALLOW_UNTHROWABLE_EXCEPTIONS \
    "${SQLITE_DEFINES[@]}" \
    -I"$NODE_INCLUDE" \
    -I"$SQLITE_SRC/node_modules/node-addon-api" \
    -I"$SQLITE_AMALGAMATION" \
    -o "$SQLITE_OUT" \
    "$SQLITE_SRC/src/backup.cc" "$SQLITE_SRC/src/database.cc" \
    "$SQLITE_SRC/src/node_sqlite3.cc" "$SQLITE_SRC/src/statement.cc" \
    "$WORK_DIR/sqlite3.o"
"$STRIP" "$SQLITE_OUT"
verify "$SQLITE_OUT" @vscode/sqlite3 || failed=1
check_pair @vscode/sqlite3 "$SQLITE_VERSION" \
    "$OUTPUT_ROOT/node_modules/@vscode/sqlite3/package.json" || failed=1

echo ""
[ "$failed" -eq 0 ] || { echo "=== FAILED ==="; exit 1; }
echo "=== Native addons built ==="
