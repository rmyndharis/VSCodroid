#!/usr/bin/env bash
set -euo pipefail

# Cross-compiles the native addons the VS Code server needs into Bionic ARM64
# .node files. Every build — ours or Microsoft's — ships these compiled against
# glibc, and Android's loader cannot open them, so each one has to be replaced.
# Three are recompiled here; @vscode/spdlog is replaced by a JavaScript
# implementation instead, for the reason given at its stage at the bottom.
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

# What node-v$NODE_VERSION-headers.tar.gz must hash to, for the version on the
# line above; move the two together. Recorded here rather than read from
# nodejs.org's SHASUMS256.txt, because that file and the archive it describes
# come from one host under one path prefix: together they say the bytes arrived
# intact and nothing about whose bytes they are. These headers define the ABI
# every addon here is compiled against, so a substituted archive is a
# substituted ABI.
#
# What it does not buy: the value was taken from nodejs.org once, by hand, and
# all it does is stop changing afterwards. SHASUMS256.txt is still read below,
# only to say whether a mismatch is a bad download or a pin left behind by a
# version bump.
NODE_HEADERS_SHA256="6c7d41d83c3481d2301115b8ce4a44b7d4fbfa52859b1aac14f445d460137887"

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

# A sidecar written by an earlier run outlives the code that wrote it and sits
# here naming an expected value nothing reads. Swept before the block below
# rather than inside it: the state that holds one is a work directory warm
# enough to skip that block entirely, so a sweep in there would never meet a
# sidecar. Unlike download-npm.sh's work directory this one is in no CI cache
# path, so it is a local checkout that keeps one between runs. Globbed for the
# same reason as there: the ones on disk carry version names this build no
# longer uses.
rm -f "$WORK_DIR"/node-*-headers.tar.gz.sha256

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
    # Same shape as download-npm.sh: the expected value is the digest this
    # repository pins, which is also the answer offline and on a cold cache
    # alike. That is what the sidecar beside the cache used to buy, and why it
    # is gone: two records of one expected value is one record too many.
    HEADERS_TARBALL="node-v$NODE_VERSION-headers.tar.gz"
    HEADERS_PATH="$WORK_DIR/$HEADERS_TARBALL"
    [ -f "$HEADERS_PATH" ] || curl -fsSL --show-error \
        "https://nodejs.org/dist/v$NODE_VERSION/$HEADERS_TARBALL" -o "$HEADERS_PATH"

    published=$(curl -sL "https://nodejs.org/dist/v$NODE_VERSION/SHASUMS256.txt" 2>/dev/null \
        | awk -v f="$HEADERS_TARBALL" '$2 == f { print $1; exit }' || true)
    if [ -n "$published" ] && [ "$published" != "$NODE_HEADERS_SHA256" ]; then
        echo "  WARNING : nodejs.org does not agree with the pinned digest" >&2
        echo "    pinned    : $NODE_HEADERS_SHA256" >&2
        echo "    published : $published" >&2
        echo "    If NODE_VERSION moved, update NODE_HEADERS_SHA256 to match." >&2
    fi
    actual=$( (sha256sum "$HEADERS_PATH" 2>/dev/null || shasum -a 256 "$HEADERS_PATH") | cut -d' ' -f1)
    if [ "$actual" != "$NODE_HEADERS_SHA256" ]; then
        # Left on disk, unlike before. With the expected value coming from
        # upstream a mismatch meant a damaged download; now it just as easily
        # means the pin is behind NODE_VERSION, in which case this is the right
        # archive and deleting it changes nothing but the download time.
        echo "  ERROR: $HEADERS_TARBALL does not match the pinned digest" >&2
        echo "    pinned    : $NODE_HEADERS_SHA256" >&2
        echo "    downloaded: $actual" >&2
        echo "    Remove $HEADERS_PATH to refetch it." >&2
        exit 1
    fi
    echo "  sha256  : matches the pinned digest"
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

# @vscode/spdlog, the file logger, and the one addon here that is replaced
# rather than rebuilt. Its published spdlog.node links libstdc++.so.6, which
# Bionic does not have and nothing in this repo bundles, so the dlopen fails on
# every device this ships to. The cost is invisible from the failure: the
# server's SpdLogLogger catches the import error, keeps every message in an
# in-memory buffer that only the addon-loaded path ever drains, and reports
# nothing, so no server, ptyHost, agentHost or extension-host log file is ever
# written, and the buffer grows for the life of the Node process.
#
# Replaced rather than rebuilt because spdlog's value is fast async C++ logging
# and what the server asks of it here is appending lines to a file. The JS below
# does that with no third-party C++ tree in the build, no NODE_MODULE_VERSION to
# keep paired against libnode.so, and 650 KB less ELF in the APK.
#
# Replaced outright rather than wrapped, for two reasons. On the only platform
# this tree ships to the native branch of a "try native, fall back to JS"
# wrapper can never be taken, so it is dead code that still has to be reasoned
# about; and a runtime choice leaves nothing in the artifact saying which logger
# is live, where a build-time replacement can be read off the tree. The .node is
# deleted with it so nothing keeps a loader that cannot run, which also empties
# the "CANNOT help: spdlog.node needs libstdc++.so.6" line that
# build-glibc-shim.sh --scan prints, instead of teaching readers to skip it.
echo ""
echo "@vscode/spdlog..."
SPDLOG_DIR="$OUTPUT_ROOT/node_modules/@vscode/spdlog"
if [ ! -f "$SPDLOG_DIR/package.json" ]; then
    # Fail closed for the same reason check_pair does: a tree with no
    # @vscode/spdlog is not a tree this shim belongs in. Fetch the server first.
    echo "ERROR: @vscode/spdlog not at $SPDLOG_DIR; cannot install the JS logger" >&2
    failed=1
else
    cat > "$SPDLOG_DIR/index.js" <<'JSEOF'
// VSCodroid: pure-JavaScript stand-in for the @vscode/spdlog native addon.
//
// Installed by scripts/build-native-addons.sh, which also deletes
// build/Release/spdlog.node. Do not restore the published index.js: it loads
// that addon, the addon links libstdc++.so.6, and Bionic has no libstdc++, so
// the load fails on every device. The server catches that failure and buffers
// log messages in memory forever instead, writing no log file at all.
//
// Covers the surface the packaged bundles call and no more. spdlog's pattern
// language is NOT parsed: the server asks for one fixed pattern or for none,
// and those two shapes are the whole vocabulary here, so setPattern() with any
// other pattern still logs, in the default shape. Writes are synchronous,
// which is what the server's own setFlushOn(0) asks for, and what keeps the
// last lines before a SIGKILL on disk.
'use strict';

const fs = require('fs');
const path = require('path');

// spdlog's level numbering, which is what the caller maps its own levels onto:
// 0 trace .. 5 critical, 6 off.
const LEVEL_NAMES = ['trace', 'debug', 'info', 'warning', 'error', 'critical'];

let globalLevel = 0;

function pad(value, width) {
	return String(value).padStart(width, '0');
}

// "%Y-%m-%d %H:%M:%S.%e" in local time, the one pattern the server asks for.
function stamp() {
	const d = new Date();
	return `${d.getFullYear()}-${pad(d.getMonth() + 1, 2)}-${pad(d.getDate(), 2)} ` +
		`${pad(d.getHours(), 2)}:${pad(d.getMinutes(), 2)}:${pad(d.getSeconds(), 2)}.` +
		`${pad(d.getMilliseconds(), 3)}`;
}

class Logger {
	// loggerType and name are upstream's signature. Neither is used: both
	// logger types rotate the same way here, and the pattern the server asks
	// for has no %n, so the name never appears in a line.
	constructor(loggerType, name, filepath, maxFileSize, maxFiles) {
		this._filepath = filepath;
		this._maxFileSize = maxFileSize > 0 ? maxFileSize : Infinity;
		this._maxFiles = maxFiles > 0 ? maxFiles : 1;
		this._level = globalLevel;
		this._raw = false;
		this._reported = false;
		// Deliberately not caught: a logger that cannot open its file must
		// reject, so the caller's own catch prints why. Swallowing here is
		// what turns an unwritable log directory back into silence.
		fs.mkdirSync(path.dirname(filepath), { recursive: true });
		this._fd = fs.openSync(filepath, 'a');
		this._size = fs.fstatSync(this._fd).size;
	}

	// Once, then quiet. The caller's log() has no try/catch around these level
	// methods, so throwing from here would turn a full disk into a crash in
	// whatever code happened to be logging.
	_report(err) {
		if (this._reported) return;
		this._reported = true;
		console.error(`[spdlog-shim] ${this._filepath}: ${err && err.message}`);
	}

	// spdlog's rotating_file_sink naming: base.log, base.1.log .. base.N.log.
	_rotated(index) {
		if (index === 0) return this._filepath;
		const ext = path.extname(this._filepath);
		return `${this._filepath.slice(0, this._filepath.length - ext.length)}.${index}${ext}`;
	}

	_rotate() {
		try {
			fs.closeSync(this._fd);
			this._fd = null;
			// The rename into the last slot is what drops the oldest file.
			// maxFiles === 1 has no slot to rename into, so the base file is
			// truncated instead, also what spdlog does.
			for (let i = this._maxFiles - 1; i > 0; i--) {
				if (fs.existsSync(this._rotated(i - 1))) {
					fs.renameSync(this._rotated(i - 1), this._rotated(i));
				}
			}
			this._fd = fs.openSync(this._filepath, this._maxFiles > 1 ? 'a' : 'w');
			this._size = fs.fstatSync(this._fd).size;
		} catch (err) {
			this._report(err);
		}
	}

	_write(level, message) {
		if (level < this._level || this._fd === null) return;
		const line = this._raw
			? `${message}\n`
			: `${stamp()} [${LEVEL_NAMES[level]}] ${message}\n`;
		const bytes = Buffer.byteLength(line);
		if (this._size + bytes > this._maxFileSize) this._rotate();
		if (this._fd === null) return;
		try {
			fs.writeSync(this._fd, line);
			this._size += bytes;
		} catch (err) {
			this._report(err);
		}
	}

	trace(message) { this._write(0, message); }
	debug(message) { this._write(1, message); }
	info(message) { this._write(2, message); }
	warn(message) { this._write(3, message); }
	error(message) { this._write(4, message); }
	critical(message) { this._write(5, message); }

	getLevel() { return this._level; }
	setLevel(level) { this._level = level; }

	// Nothing to do: every write above is a writeSync, so there is never
	// anything buffered for flush() to push out.
	flush() {}

	setPattern() { this._raw = false; }
	clearFormatters() { this._raw = true; }

	drop() {
		if (this._fd === null) return;
		try { fs.closeSync(this._fd); } catch (err) { this._report(err); }
		this._fd = null;
	}
}

async function createLogger(loggerType, name, filepath, maxFileSize, maxFiles) {
	return new Logger(loggerType, name, filepath, maxFileSize, maxFiles);
}

exports.version = 'vscodroid-js-shim';
exports.Logger = Logger;
exports.setLevel = function (level) { globalLevel = level; };
// Accepted and ignored: writes are synchronous, so every message is already on
// disk whatever threshold the caller names.
exports.setFlushOn = function () {};
exports.createRotatingLogger = function (name, filepath, maxFileSize, maxFiles) {
	return createLogger('rotating', name, filepath, maxFileSize, maxFiles);
};
exports.createAsyncRotatingLogger = function (name, filepath, maxFileSize, maxFiles) {
	return createLogger('rotating_async', name, filepath, maxFileSize, maxFiles);
};
JSEOF
    rm -f "$SPDLOG_DIR/build/Release/spdlog.node"
    rmdir "$SPDLOG_DIR/build/Release" "$SPDLOG_DIR/build" 2>/dev/null || true

    cat > "$WORK_DIR/spdlog-shim-check.mjs" <<'JSEOF'
// Proves the shim writes. The failure it replaces was silent, so a shim that is
// merely present and broken would be the same silence with more steps, and
// nothing downstream would catch it: verify-server-tree.py only reads e_machine
// from binaries, and the server itself treats an unusable logger as normal.
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { pathToFileURL } from "node:url";

const shim = process.argv[2];
let failed = false;
const fail = (message) => { console.error(`  FAIL @vscode/spdlog: ${message}`); failed = true; };

// Imported, not required: all five server entry points reach it as
// `await import("@vscode/spdlog")`, so this also proves the named exports
// survive the CommonJS-to-ESM interop that a require() here would not exercise.
const spdlog = await import(pathToFileURL(shim).href);

for (const name of ["version", "Logger", "setLevel", "setFlushOn",
	"createRotatingLogger", "createAsyncRotatingLogger"]) {
	if (spdlog[name] === undefined) fail(`no export named ${name}`);
}

const dir = fs.mkdtempSync(path.join(os.tmpdir(), "spdlog-shim-check-"));

// The nested directory is the real case, not a corner of it: the server hands
// createAsyncRotatingLogger a logsHome that does not exist yet.
const logFile = path.join(dir, "logs", "check.log");
spdlog.setFlushOn(0);
const logger = await spdlog.createAsyncRotatingLogger("check", logFile, 30 * 1024 * 1024, 1);
logger.setPattern("%Y-%m-%d %H:%M:%S.%e [%l] %v");
logger.setLevel(0);
logger.info("shim-writes-this");
logger.flush();
logger.drop();
if (!fs.existsSync(logFile) || !fs.readFileSync(logFile, "utf8").includes("shim-writes-this")) {
	fail(`nothing reached ${logFile}`);
}

// A rotating logger that never rotates would move the unbounded growth this
// replaces from memory onto the phone's disk, so the size limit is measured
// rather than assumed.
const rollFile = path.join(dir, "roll.log");
const roller = await spdlog.createAsyncRotatingLogger("roll", rollFile, 64, 3);
roller.clearFormatters();
roller.setLevel(0);
for (let i = 0; i < 12; i++) roller.info(`line-${i}-padded-to-force-a-roll`);
roller.drop();
if (!fs.existsSync(path.join(dir, "roll.1.log"))) fail("the 64-byte limit did not roll the file");

fs.rmSync(dir, { recursive: true, force: true });
if (failed) process.exit(1);
JSEOF
    if node "$WORK_DIR/spdlog-shim-check.mjs" "$SPDLOG_DIR/index.js"; then
        printf '  ok   %-24s %8s bytes (JavaScript, no addon)\n' "@vscode/spdlog" \
            "$(wc -c < "$SPDLOG_DIR/index.js" | tr -d ' ')"
    else
        failed=1
    fi
fi

echo ""
[ "$failed" -eq 0 ] || { echo "=== FAILED ==="; exit 1; }
echo "=== Native addons built ==="
