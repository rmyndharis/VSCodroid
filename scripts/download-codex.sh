#!/usr/bin/env bash
set -euo pipefail

# Download the Codex engine from the Termux APT repo.
# Places files in the toolchain_codex asset pack module for Play Asset Delivery.
#
# What the pack is: OpenAI's Codex command line agent (Apache-2.0) as Termux
# builds it for Bionic, and the helper process its code mode needs. What it is
# not: the extension. The Codex extension in the Open VSX gallery ships a static
# musl build, which cannot start on Android; this is an engine that can.
#
# Nothing the base APK already ships is repeated here: the engine needs libssl,
# libcrypto, libc++_shared and Bionic, and the first three are in assets/usr/lib.
# The index lists git, libc++ and openssl as dependencies; resolution here
# expands none, so the list below is the closure minus the base.
#
# The layout is the engine's own requirement. It looks for codex-code-mode-host
# in the directory of its own executable, so the two files sit together. They
# are in a lib/ directory of the install root because ToolchainManager does not
# turn anything under <root>/lib/ into a command, a shell function or a table
# row, and neither file is one. The command `codex` is a launcher the app writes
# (ToolchainManager.addCodexRows): the engine has to start through the loader
# with two libraries preloaded and its real path in the environment, and a pack
# cannot carry a script that knows where filesDir is.
#
# Compatible with bash 3.2+ (macOS default).

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"
PACK_ASSETS="$ROOT_DIR/android/toolchain_codex/src/main/assets"
BASE_USR="${BASE_USR:-$ROOT_DIR/android/app/src/main/assets/usr}"
WORK_DIR="$ROOT_DIR/toolchains/termux-packages"

# The index fetch, its signature check, package resolution and the digest check
# on each .deb, shared with every other script that takes packages from Termux.
# It also picks the mirror; TERMUX_MIRROR still overrides it.
. "$SCRIPT_DIR/lib/termux-packages.sh"

REQUIRED_PACKAGES=(
    # The engine and codex-code-mode-host.
    codex
    # Not a component that ships: it carries usr/share/LICENSES, the shared
    # licence texts a package's copyright file may point at. See
    # termux_copy_notices in scripts/lib/termux-packages.sh.
    termux-licenses
)

# What termux_copy_notices is asked about: the shipped components.
NOTICE_PACKAGES=(codex termux-licenses)

ENGINE_FILES=(codex codex-code-mode-host)
ENGINE_ROOT="usr/libexec/codex"
ENGINE_DIR="$PACK_ASSETS/$ENGINE_ROOT/lib"

echo "=== Downloading Codex Toolchain ==="
echo ""

mkdir -p "$WORK_DIR"
cd "$WORK_DIR"

termux_fetch_index
termux_resolve_packages resolved-codex.tsv "${REQUIRED_PACKAGES[@]}"
CODEX_VERSION="$(termux_pkg_version codex)"
echo "  Codex version: $CODEX_VERSION"

termux_download_packages "${REQUIRED_PACKAGES[@]}"
termux_extract_packages "${REQUIRED_PACKAGES[@]}"

PKG_USR="extracted/codex/data/data/com.termux/files/usr"

# --- Step 1: refuse a package that ships more than this pack accounts for ---
#
# The engine looks beside itself for what it needs. A release that starts
# shipping another helper (the Linux sandbox, a resources directory) would run
# here without it and fail at the first use, in a way the download would never
# show. So a file the pack does not name stops the build, to be read.
echo ""
echo "Checking what the package ships..."
unexpected="$(cd "$PKG_USR" && find . \( -type f -o -type l \) | sed 's|^\./||' | sort \
    | grep -v -E '^(bin/(codex|codex-code-mode-host)|share/doc/codex/.*)$' || true)"
if [ -n "$unexpected" ]; then
    echo "  ERROR: the codex package ships files this pack does not place:" >&2
    echo "$unexpected" | sed 's/^/     /' >&2
    echo "         Read what each one is for, then place it or say why it is dropped." >&2
    exit 1
fi
for f in "${ENGINE_FILES[@]}"; do
    if [ ! -f "$PKG_USR/bin/$f" ]; then
        echo "  ERROR: $f is not in the codex package at $PKG_USR/bin" >&2
        exit 1
    fi
done
echo "  only the engine, its helper and its notices"

# --- Step 2: place the engine ---
echo ""
echo "Placing the Codex engine in the asset pack..."
rm -rf "$PACK_ASSETS/usr"
mkdir -p "$ENGINE_DIR"
for f in "${ENGINE_FILES[@]}"; do
    cp -L "$PKG_USR/bin/$f" "$ENGINE_DIR/$f"
    chmod 755 "$ENGINE_DIR/$f"
    echo "  $ENGINE_ROOT/lib/$f ($(du -sh "$ENGINE_DIR/$f" | cut -f1))"
done

# --- Step 3: the upstream notices, beside what they describe ---
termux_copy_notices "$PACK_ASSETS/usr" "${NOTICE_PACKAGES[@]}"
if [ ! -f "$PACK_ASSETS/usr/share/doc/codex/LICENSE" ]; then
    echo "  ERROR: the Apache-2.0 licence text is not in the pack (share/doc/codex/LICENSE)" >&2
    exit 1
fi

# --- Step 4: Write toolchain_codex.json ---
echo ""
echo "Writing toolchain_codex.json..."

# Written by a small Python program, and every list is read off the tree, for
# the reason download-cpp.sh gives: a file the manifest does not account for is
# a file an uninstall would leave on every device.
#
#   installRoot  the engine's own directory, removed whole at uninstall
#   binaries     the two ELF files, made executable at install. Both are under
#                <installRoot>/lib/, so neither becomes a PATH command
#   ownedPaths   the notices, which sit in usr/share/doc beside other packages'
python3 -I - "$PACK_ASSETS" "$CODEX_VERSION" "$ENGINE_ROOT" <<'PYEOF'
import json, os, sys

pack, version, root = sys.argv[1], sys.argv[2], sys.argv[3]
usr = os.path.join(pack, "usr")

binaries = sorted(
    os.path.relpath(os.path.join(d, n), pack).replace(os.sep, "/")
    for d, _dirs, names in os.walk(os.path.join(pack, root))
    for n in names
)
owned = ["usr/share/doc/codex"]

problems = []
for dirpath, _dirs, names in os.walk(usr):
    for n in names:
        rel = os.path.relpath(os.path.join(dirpath, n), pack).replace(os.sep, "/")
        if rel in binaries or any(rel == o or rel.startswith(o + "/") for o in owned):
            continue
        problems.append("nothing in the manifest would remove " + rel)
if problems:
    for p in problems[:20]:
        print("  ERROR: " + p, file=sys.stderr)
    sys.exit(1)

manifest = {
    "name": "codex",
    "displayName": "Codex",
    "version": version,
    "installRoot": root,
    "binaries": binaries,
    "env": {},
    "ownedPaths": owned,
}
with open(os.path.join(pack, "toolchain_codex.json"), "w") as f:
    json.dump(manifest, f, indent=4)
    f.write("\n")
print("  toolchain_codex.json written: %d binaries, %d owned path(s)" % (len(binaries), len(owned)))
PYEOF

# --- Step 5: Verify every binary the pack ships ---
echo ""
echo "=== Verifying Codex binaries ==="
verify_failures=0
for f in "${ENGINE_FILES[@]}"; do
    if ! out=$(python3 "$SCRIPT_DIR/verify-android-elf.py" "$ENGINE_DIR/$f" \
                   --lib-dir "$BASE_USR/lib" 2>&1); then
        echo "  FAILED  $ENGINE_ROOT/lib/$f" >&2
        echo "$out" | grep -v '^  ok' | sed 's/^/     /' >&2 || true
        verify_failures=$((verify_failures + 1))
    fi
done
if [ "$verify_failures" -gt 0 ]; then
    echo "" >&2
    echo "  ERROR: $verify_failures of ${#ENGINE_FILES[@]} Codex binaries would fail to load on a" >&2
    echo "         device. Shipping them produces a working build and a broken toolchain." >&2
    exit 1
fi
echo "  ${#ENGINE_FILES[@]} binaries verified: architecture, dependencies, 16 KB alignment"

# The base module and a pack may not carry the same asset path.
echo ""
echo "=== Verifying nothing here is also in the base APK ==="
overlap=0
if [ -d "$BASE_USR" ]; then
    while IFS= read -r rel; do
        if [ -e "$BASE_USR/${rel#usr/}" ]; then
            echo "  ERROR: $rel is also shipped by the base APK" >&2
            overlap=$((overlap + 1))
        fi
    done < <(cd "$PACK_ASSETS" && find usr -type f)
    if [ "$overlap" -gt 0 ]; then
        exit 1
    fi
    echo "  none"
else
    echo "  skipped: $BASE_USR is absent, so the base tree could not be compared"
fi

echo ""
echo "=== Verifying no file in the pack names a shell it cannot reach ==="
python3 "$SCRIPT_DIR/patch-default-shell.py" --check "$PACK_ASSETS"

# Neither delivery path can carry a symbolic link.
echo ""
echo "=== Verifying the pack carries no symbolic link ==="
link_count=$(find "$PACK_ASSETS" -type l | wc -l | tr -d ' ')
if [ "$link_count" -ne 0 ]; then
    echo "  ERROR: $link_count symbolic link(s) in the pack:" >&2
    find "$PACK_ASSETS" -type l | sed "s|^$PACK_ASSETS/|     |" >&2
    exit 1
fi
echo "  none"

# --- Step 6: Size summary ---
echo ""
echo "=== Codex Toolchain Size Summary ==="
echo "  Asset pack: $(du -sh "$PACK_ASSETS" | cut -f1) total"
echo "  files: $(find "$PACK_ASSETS/usr" -type f | wc -l | tr -d ' ')"
echo "  du -sk usr/: $(du -sk "$PACK_ASSETS/usr" | cut -f1) KiB"

echo ""
echo "=== Codex download complete ==="
echo "Next: ./scripts/package-toolchains.sh codex"
