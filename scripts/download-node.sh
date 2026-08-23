#!/usr/bin/env bash
set -euo pipefail

# Fetches the Node.js runtime and installs it as jniLibs/arm64-v8a/libnode.so.
#
#   ./scripts/download-node.sh
#
# This replaced a Node cross-compiled for Android from the Termux build recipes
# and published as a GitHub release asset. That binary ran, and passed every test
# we had, but it segfaulted on code paths nothing here exercised: the Claude Code
# CLI's `auth status`, `mcp list`, `plugin list` and `doctor` all died with
# SIGSEGV and no output, while `--version` and `--help` returned normally.
# Measured against the same CLI file: Node 20.18.1 for glibc arm64 ran them,
# Termux's Node ran them on the same device, ours did not. The fault was in our
# build, not in Android and not in the CLI.
#
# Termux's package is a maintained Bionic build with the same 16 KB page
# alignment Android 16 requires. Taking it removes a cross-compile we do not have
# the means to debug, and its version (24.18.0) is exactly what VS Code 1.133
# asks for in remote/.npmrc, which is what makes the version bump possible at all.
#
# The libraries it links against are placed by download-termux-tools.sh, not
# here: that script wipes assets/usr/lib before repopulating it, so anything
# written there afterwards would depend on running order to survive.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"
JNILIBS_DIR="$ROOT_DIR/android/app/src/main/jniLibs/arm64-v8a"
WORK_DIR="$ROOT_DIR/toolchains/termux-packages"

TERMUX_REPO="${TERMUX_MIRROR:-https://mirror.mwt.me/termux/main}"
PACKAGES_URL="$TERMUX_REPO/dists/stable/main/binary-aarch64/Packages"

# nodejs-lts, not nodejs: the LTS package tracks the line VS Code targets, while
# the plain one runs ahead of it. Check what a VS Code version wants with
#   curl -s https://raw.githubusercontent.com/microsoft/vscode/<tag>/remote/.npmrc
PACKAGE="${NODE_PACKAGE:-nodejs-lts}"

echo "=== Node runtime ==="

mkdir -p "$WORK_DIR" "$JNILIBS_DIR"
cd "$WORK_DIR"

# Emptied at the start, the way the five other resolved-*.tsv writers do it, so a
# run that fails leaves no record naming a runtime that is not in jniLibs. The
# write itself is at the very end; both halves are needed, and truncation alone
# would not have been enough -- the record used to be written before the digest of
# what it named had been checked, so a failure there replaced a correct record
# with a fresh and wrong one rather than merely leaving a stale one.
: > "$WORK_DIR/resolved-node.tsv"

# Same reasoning, one directory over. The marker is written last, and the span
# before it now contains a step that fails on purpose, so a run that stops there
# would leave a new binary beside a marker naming the previous runtime.
# build-native-addons.sh trusts the marker and only reads the version out of the
# binary when it is absent, so a stale one pairs the wrong headers silently and
# the addons are rejected at load on device. Removed rather than emptied: that
# script tests for the file, not for its contents.
rm -f "$JNILIBS_DIR/.libnode-version"

# Anything but 0 takes this branch, and verify-termux-index.sh below is the one
# place that decides which values are meant: testing for 1 here would send a
# misspelt opt-in down the download path instead, where with no network it fails
# as a connection error and the typo is never mentioned.
if [ "${TERMUX_OFFLINE:-0}" != "0" ]; then
    # Offline, the hourly refresh would replace a usable cached index with a
    # download that cannot happen, and the check below would never be reached.
    if [ ! -f Packages ]; then
        echo "  ERROR: TERMUX_OFFLINE is set and there is no cached index at" >&2
        echo "         $WORK_DIR/Packages. One run with a network creates it." >&2
        exit 1
    fi
elif [ ! -f Packages ] || [ -n "$(find Packages -mmin +60 2>/dev/null)" ]; then
    curl -sL --fail --show-error -o Packages "$PACKAGES_URL"
fi

# Checked on every run, not only after a download. A cached index is exactly as
# unchecked as a fresh one, and every digest read below comes out of this file.
#
# Named in full rather than as the bare Packages this directory is already cd'd
# into: the check reports which file it read and where the InRelease beside it
# came from, and "./InRelease" in a build log names no directory at all.
bash "$SCRIPT_DIR/verify-termux-index.sh" "$PACKAGES_URL" "$WORK_DIR/Packages"

# One pass, all three fields from the same stanza: the live index carries
# duplicate Package stanzas for some packages, and independent scans could
# pair one stanza's Filename with another's SHA256.
pkg_line=$(awk -v pkg="$PACKAGE" '
    /^Package: / { if (fn != "" && current == pkg) exit; current = $2; fn = ""; ver = ""; sh = "" }
    /^Filename: / && current == pkg { fn = $2 }
    /^Version: / && current == pkg { ver = $2 }
    /^SHA256: / && current == pkg { sh = $2 }
    END { if (fn != "") print fn, (ver == "" ? "-" : ver), (sh == "" ? "-" : sh) }
' Packages)
filename=$(echo "$pkg_line" | cut -d' ' -f1)
version=$(echo "$pkg_line" | cut -d' ' -f2)
sha256=$(echo "$pkg_line" | cut -d' ' -f3)
[ "$sha256" = "-" ] && sha256=""

if [ -z "$filename" ]; then
    echo "  ERROR: $PACKAGE is not in the Termux index" >&2
    exit 1
fi

echo "  package : $PACKAGE $version"

# This file becomes libnode.so, the entire server runtime, executed on every
# user's device. The digest below comes from an index that verify-termux-index.sh
# has already checked against Termux's signature, so the mirror it arrives over
# cannot choose both the payload and the digest it is measured by. This used to
# say the opposite, and correctly: until the index was anchored, the digest and
# the payload travelled the same channel and this was integrity against
# corruption rather than a root of trust. A file that does not match, cached or
# fresh, must not be used.
deb="debs/$(basename "$filename")"
mkdir -p debs
if [ ! -f "$deb" ]; then
    curl -sL --fail --show-error -o "$deb" "$TERMUX_REPO/$filename"
fi
if [ -n "$sha256" ]; then
    actual=$( (sha256sum "$deb" 2>/dev/null || shasum -a 256 "$deb") | cut -d' ' -f1)
    if [ "$actual" != "$sha256" ]; then
        echo "  ERROR: $deb does not match the index" >&2
        echo "    index : $sha256" >&2
        echo "    file  : $actual" >&2
        rm -f "$deb"
        exit 1
    fi
    echo "  sha256  : verified"
else
    # Fail closed: index and payload come from the same mirror, so a missing
    # SHA256 line would otherwise switch verification off silently.
    echo "  ERROR: index carries no SHA256 for $PACKAGE" >&2
    exit 1
fi
echo "  deb     : $(du -h "$deb" | cut -f1)"

extract_dir="extracted/$PACKAGE"
rm -rf "$extract_dir"
mkdir -p "$extract_dir"
bsdtar -xOf "$deb" 'data.tar*' | bsdtar -xf - -C "$extract_dir"

src="$extract_dir/data/data/com.termux/files/usr/bin/node"
if [ ! -f "$src" ]; then
    echo "  ERROR: no bin/node inside $deb" >&2
    exit 1
fi

cp "$src" "$JNILIBS_DIR/libnode.so"
chmod +x "$JNILIBS_DIR/libnode.so"

# Termux's build defaults child_process to a shell inside Termux's own data
# directory, which this app can neither read nor create, so every exec() and
# every shell-based spawn from the server or an extension dies with ENOENT.
# Before the ELF gate below, so what that gate examines is what ships. The
# rewrite is length-preserving and fails closed: if Termux changes the default
# and the string stops matching, the build stops here rather than shipping a
# runtime whose shell nobody has established.
python3 "$SCRIPT_DIR/patch-default-shell.py" "$JNILIBS_DIR/libnode.so"

# Node's notice, beside the rest of them. MIT requires the copyright and
# permission notice to travel with a binary redistribution, and this file is
# also where the notices for everything Node statically bundles live: its own
# 157 KB copyright covers V8, OpenSSL, zlib, ICU, c-ares and the rest of the
# deps tree, none of which has a file of its own anywhere.
#
# Written into the directory download-termux-tools.sh clears and fills, which is
# why this script has to run after it -- the same ordering its libraries already
# depend on. Not routed through termux_copy_notices: this script does its own
# index handling and does not source scripts/lib/termux-packages.sh.
node_notice="$extract_dir/data/data/com.termux/files/usr/share/doc/$PACKAGE/copyright"
if [ ! -f "$node_notice" ]; then
    echo "  ERROR: no share/doc/$PACKAGE/copyright inside $deb; Node would ship" >&2
    echo "         with no notice, and so would everything it bundles." >&2
    exit 1
fi
node_doc="$ROOT_DIR/android/app/src/main/assets/usr/share/doc/$PACKAGE"
mkdir -p "$node_doc"
cp "$node_notice" "$node_doc/copyright"
echo "  notice  : usr/share/doc/$PACKAGE/copyright ($(du -h "$node_doc/copyright" | cut -f1))"

# The Termux version string ("24.18.0-1") with the package revision dropped.
# build-native-addons.sh reads this marker to pair its headers against the
# runtime actually installed - the binary itself is gitignored, so the marker
# is what survives beside it for other scripts to check.
echo "${version%%-*}" > "$JNILIBS_DIR/.libnode-version"
echo "  libnode : $(du -h "$JNILIBS_DIR/libnode.so" | cut -f1) (v${version%%-*})"

echo ""
echo "=== Verify ==="
# Needs the libraries download-termux-tools.sh places, so run that first;
# the check below is what says so out loud when it has not been.
python3 "$SCRIPT_DIR/verify-android-elf.py" "$JNILIBS_DIR/libnode.so" \
    --lib-dir "$ROOT_DIR/android/app/src/main/assets/usr/lib" \
    --lib-dir "$JNILIBS_DIR"

# The ABI number is what native addons are compiled against, and a mismatch is
# not a warning: the addon simply refuses to load, taking terminals with it.
# Printed here so a version bump makes the number that has to reach
# build-native-addons.sh visible rather than implied.
echo ""
echo "  Node $version carries NODE_MODULE_VERSION for its major line."
echo "  build-native-addons.sh must be built against the matching headers."

# Same record the other download scripts keep, so the build manifest is not
# silently missing the one component that matters most -- and written LAST, after
# the digest check, the extraction, the install and the ELF gate, so it can only
# describe a runtime that is actually in jniLibs and passed every check. Nothing
# in this script reads it; write-build-manifest.py picks it up through the
# resolved-*.tsv glob, which is why a record that is merely present, rather than
# correct, is worse than none.
printf '%s %s %s\n' "$PACKAGE" "$filename" "${sha256:--}" > "$WORK_DIR/resolved-node.tsv"
