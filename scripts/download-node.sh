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
# the means to debug, and its version — 24.18.0 — is exactly what VS Code 1.133
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

if [ ! -f Packages ] || [ -n "$(find Packages -mmin +60 2>/dev/null)" ]; then
    curl -sL --fail --show-error -o Packages "$PACKAGES_URL"
fi

# Checked on every run, not only after a download. A cached index is exactly as
# unchecked as a fresh one, and every digest read below comes out of this file.
bash "$SCRIPT_DIR/verify-termux-index.sh" "$PACKAGES_URL" Packages

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
# Same record the other download scripts keep, so the build manifest is not
# silently missing the one component that matters most.
printf '%s %s %s\n' "$PACKAGE" "$filename" "${sha256:--}" > "$WORK_DIR/resolved-node.tsv"
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
# not a warning — the addon simply refuses to load, taking terminals with it.
# Printed here so a version bump makes the number that has to reach
# build-native-addons.sh visible rather than implied.
echo ""
echo "  Node $version carries NODE_MODULE_VERSION for its major line."
echo "  build-native-addons.sh must be built against the matching headers."
