#!/usr/bin/env bash
set -euo pipefail

# Package on-demand toolchain asset packs into ZIP files for sideload distribution
# via GitHub Releases.
#
# Each ZIP contains the asset pack contents (toolchain_<name>.json + usr/ directory)
# exactly as they would be extracted on device by ToolchainManager.
#
# Prerequisites:
#   Run the download scripts first to populate the asset packs:
#     ./scripts/download-ruby.sh
#     ./scripts/download-java.sh
#     ./scripts/download-cpp.sh
#     ./scripts/download-codex.sh
#
# Usage:
#   ./scripts/package-toolchains.sh              # Package all toolchains
#   ./scripts/package-toolchains.sh ruby          # Package only Ruby
#   ./scripts/package-toolchains.sh ruby java     # Package Ruby and Java
#
# Output:
#   toolchain-zips/toolchain_ruby.zip  (~11 MB)
#   toolchain-zips/toolchain_java.zip  (~69 MB)
#   toolchain-zips/toolchain_cpp.zip   (~79 MB)
#   toolchain-zips/toolchain_codex.zip (~146 MB)
#
# The ZIPs are intended to be attached to GitHub Releases so that sideloaded
# APK users (not from Play Store) can download toolchains manually. The app's
# ToolchainManager extracts them into the app's files directory.
#
# Compatible with bash 3.2+ (macOS default).

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"
OUTPUT_DIR="$ROOT_DIR/toolchain-zips"

REGISTRY="$ROOT_DIR/android/app/src/main/kotlin/com/vscodroid/setup/ToolchainRegistry.kt"

# Read from ToolchainRegistry rather than written out here, because two lists of
# toolchains drift and one cannot. A toolchain added to the registry and forgotten
# in a hand-written list here is packaged into no ZIP at all, while the registry
# points every non-Play install at releases/latest/download/<pack>.zip: that
# toolchain then fails to install for every sideloaded user, on a release that
# looks complete. release.yml re-checks the same pairing after this script runs,
# which catches it at tag time; deriving the list means there is nothing to catch.
#
# [a-z0-9_] rather than [a-z]: every name today is all letters, so the narrower
# pattern would match all of them and look correct while silently skipping a
# `toolchain_java21` added tomorrow. Word splitting is safe for the same reason
# the character class is what it is.
#
# `|| true` because `pipefail` is set: grep exits 1 when it matches nothing, the
# pipeline carries that, and `set -e` then kills the script at this assignment,
# before the check below can say what went wrong. Measured: it exited 1 having
# printed nothing at all, which is the least useful way to report a broken
# pattern.
ALL_TOOLCHAINS=($(grep -oE 'packName *= *"toolchain_[a-z0-9_]+"' "$REGISTRY" \
    | sed -e 's/.*toolchain_//' -e 's/"$//' || true))

if [ ${#ALL_TOOLCHAINS[@]} -eq 0 ]; then
    echo "ERROR: no packName entries found in $REGISTRY; the pattern stopped matching." >&2
    exit 1
fi

get_assets_dir() {
    echo "$ROOT_DIR/android/toolchain_$1/src/main/assets"
}

# A figure ToolchainRegistry.kt records for a pack, digits only, or nothing when the
# pack's own entry records none as a plain number. Both size gates below read the
# registry through this, so they cannot come to read it differently.
#
# Each entry is read on its own, from its packName up to the next one, with comments
# taken out. A search across the whole file carries on into the next pack's entry when
# this one lacks the field, or names it as a constant or an expression, and then
# answers with that pack's figure: measured with a Ruby entry whose downloadSize was a
# constant, it answered with Java's. And a figure quoted in a comment is not the figure.
recorded_figure() {
    python3 -c "
import re, sys
src = open(sys.argv[1]).read()
src = re.sub(r'/\*.*?\*/', '', src, flags=re.S)
src = re.sub(r'//[^\n]*', '', src)
start = re.search(r'packName\s*=\s*\"toolchain_%s\"' % re.escape(sys.argv[2]), src)
if not start:
    print('')
    sys.exit(0)
rest = src[start.end():]
following = re.search(r'packName\s*=', rest)
entry = rest[:following.start()] if following else rest
m = re.search(r'\b%s\s*=\s*([0-9][0-9_]*)L?\s*[,)]' % re.escape(sys.argv[3]), entry)
print(m.group(1).replace('_', '') if m else '')
" "$REGISTRY" "$2" "$1"
}

# --- Parse arguments ---
REQUESTED=()
if [ $# -gt 0 ]; then
    for arg in "$@"; do
        valid=false
        for tc in "${ALL_TOOLCHAINS[@]}"; do
            if [ "$arg" = "$tc" ]; then
                valid=true
                break
            fi
        done
        if [ "$valid" = false ]; then
            echo "ERROR: Unknown toolchain '$arg'"
            echo "Available: ${ALL_TOOLCHAINS[*]}"
            exit 1
        fi
        REQUESTED+=("$arg")
    done
else
    REQUESTED=("${ALL_TOOLCHAINS[@]}")
fi

echo "=== Packaging Toolchain ZIPs ==="
echo ""

mkdir -p "$OUTPUT_DIR"

# A full run owns the names it no longer knows. The loop below removes only the
# ZIPs of the toolchains it is asked for, which leaves behind the ZIP of a
# toolchain this build no longer has: measured in a working tree, a 53 MB
# toolchain_go.zip from February sitting beside ruby and java, months after Go
# was withdrawn.
# release.yml globs toolchain-zips/*.zip into the digest manifest and into what
# it attaches to the release, so a leftover is published and vouched for as part
# of it. Nothing in CI has shipped one only because the directory is gitignored
# and runners check out clean, which is the runner's property rather than this
# script's.
#
# Withdrawn names only. A known toolchain's ZIP is the loop's business, and the
# loop removes it as its first step for that toolchain, before any gate: a run
# that SKIPs the toolchain for a missing asset tree, or refuses its tree or its
# ZIP, leaves no ZIP for it, and the summary says so. A ZIP from an earlier run
# was held to no figure this run read, so attaching it by hand would publish
# what no gate saw; the tree is rebuilt by its download script.
#
# Only on a full run: `package-toolchains.sh ruby` is a maintainer rebuilding one
# pack, and taking the other one's ZIP away would be a surprise.
if [ $# -eq 0 ]; then
    for zip_path in "$OUTPUT_DIR"/toolchain_*.zip; do
        [ -f "$zip_path" ] || continue
        zip_name="$(basename "$zip_path" .zip)"
        known=false
        for tc in "${ALL_TOOLCHAINS[@]}"; do
            if [ "$zip_name" = "toolchain_$tc" ]; then
                known=true
                break
            fi
        done
        if [ "$known" = false ]; then
            echo "Removing $zip_name.zip: the registry names no toolchain for it"
            rm -f "$zip_path"
        fi
    done
fi

PACKAGED=0
FAILED=0
TOTAL_BYTES=0

for tc in "${REQUESTED[@]}"; do
    assets_dir="$(get_assets_dir "$tc")"
    manifest="$assets_dir/toolchain_$tc.json"
    usr_dir="$assets_dir/usr"
    zip_file="$OUTPUT_DIR/toolchain_$tc.zip"

    echo "--- $tc ---"

    # A ZIP from an earlier run goes first, so that a refusal below, whichever gate it comes
    # from, leaves no ZIP behind for anyone to attach to a release by hand.
    rm -f "$zip_file"

    # Verify the asset pack has been populated by the download script
    if [ ! -f "$manifest" ]; then
        echo "  SKIP: Manifest not found at $manifest"
        echo "  Run ./scripts/download-$tc.sh first."
        echo ""
        FAILED=$((FAILED + 1))
        continue
    fi

    if [ ! -d "$usr_dir" ]; then
        echo "  SKIP: Assets not found at $usr_dir"
        echo "  Run ./scripts/download-$tc.sh first."
        echo ""
        FAILED=$((FAILED + 1))
        continue
    fi

    # Show version from manifest
    version=""
    if command -v python3 > /dev/null 2>&1; then
        version=$(python3 -c "import json,sys; print(json.load(sys.stdin).get('version',''))" < "$manifest" 2>/dev/null || true)
    fi
    if [ -n "$version" ]; then
        echo "  Version: $version"
    fi

    # Count files to be packaged
    file_count=$(find "$usr_dir" -type f 2>/dev/null | wc -l | tr -d ' ')
    echo "  Files: $file_count (+ manifest)"

    # The recorded unpacked size is a floor, and this is the only place it can be
    # compared against the tree it describes. Both install pre-flights reserve
    # against that figure and the card quotes it to the user, and understating it
    # is the direction that admits a device the gate exists to refuse, which then
    # runs out of room partway through the copy. It went wrong exactly that way
    # once: download-java.sh stopped deleting OpenJDK's legal/ and began
    # dereferencing symlinks on copy, and the constant stayed at 146,000,000 for
    # a tree of 154.8 MB.
    #
    # ToolchainRegistryTest asks the same question and cannot answer it where it
    # matters when pack trees are absent: it skips each unavailable tree. The
    # tree is on disk here, one step before it becomes a release asset.
    #
    # Each file is rounded up to a 4 KiB block, which is what makes the figure
    # comparable with the constant. The KDoc on estimatedSize says to measure with
    # `du -sk`, so the constant records blocks, and a plain sum of file lengths is
    # a different and smaller number: for Ruby, 30.0 MB of content against 35.7 MB
    # of blocks over 2,279 files, so the gate carried 5.7 MB of slack the payload
    # did not have. Blocks are also what the device actually spends, which is what
    # both install pre-flights reserve against. Measured: rounding this way
    # reproduces `du -sk` exactly for both packs that ship.
    if command -v python3 > /dev/null 2>&1; then
        tree_bytes=$(python3 -c "
import os, sys
root = sys.argv[1]
print(sum(-(-os.path.getsize(os.path.join(d, f)) // 4096) * 4096
          for d, _, fs in os.walk(root) for f in fs))
" "$usr_dir")
        recorded=$(recorded_figure estimatedSize "$tc")
        if [ -z "$recorded" ]; then
            echo "  ERROR: ToolchainRegistry.kt records no estimatedSize for toolchain_$tc" >&2
            exit 1
        fi
        echo "  Unpacked: $tree_bytes bytes in 4 KiB blocks (recorded $recorded)"
        if [ "$tree_bytes" -gt "$recorded" ]; then
            echo "  ERROR: the tree is larger than the estimatedSize ToolchainRegistry.kt" >&2
            echo "         records for it. Every install pre-flight reserves against that" >&2
            echo "         figure and the card quotes it, so a device is admitted to an" >&2
            echo "         install it cannot finish. Raise it to at least $tree_bytes." >&2
            exit 1
        fi
    else
        echo "  WARNING: python3 not found, so estimatedSize was NOT checked against the tree"
    fi

    # Create ZIP from the assets directory contents.
    # cd into the assets dir so paths inside the ZIP are relative
    # (e.g., toolchain_ruby.json, usr/lib/ruby).
    # -r: recursive, -y: store symlinks as symlinks, -q: quiet
    (cd "$assets_dir" && zip -r -y -q "$zip_file" .)

    # Report size
    zip_bytes=$(wc -c < "$zip_file" | tr -d ' ')
    zip_mb=$(echo "scale=1; $zip_bytes / 1048576" | bc)
    TOTAL_BYTES=$((TOTAL_BYTES + zip_bytes))
    PACKAGED=$((PACKAGED + 1))

    echo "  Output: toolchain_$tc.zip (${zip_mb} MB)"

    # The recorded download size is the other figure the card quotes, to a person
    # deciding whether to fetch this over mobile data, so the same direction costs
    # them: a figure below the ZIP says it is smaller than it is. Nothing compared it
    # with the ZIP, and Codex's said 134 MB for a ZIP of 145.5 MB when 1.10.0 shipped.
    # Decimal bytes, which is what the card formats. A ZIP this refuses is removed, so
    # it cannot be attached to a release by hand.
    if command -v python3 > /dev/null 2>&1; then
        recorded_download=$(recorded_figure downloadSize "$tc")
        if [ -z "$recorded_download" ]; then
            echo "  ERROR: ToolchainRegistry.kt records no downloadSize for toolchain_$tc" >&2
            rm -f "$zip_file"
            exit 1
        fi
        echo "  Download: $zip_bytes bytes (recorded $recorded_download)"
        if [ "$zip_bytes" -gt "$recorded_download" ]; then
            echo "  ERROR: the ZIP is larger than the downloadSize ToolchainRegistry.kt" >&2
            echo "         records for it. The card quotes that figure to a person deciding" >&2
            echo "         whether to download it over mobile data, so it must not be below" >&2
            echo "         what ships. Raise it to at least $zip_bytes." >&2
            rm -f "$zip_file"
            exit 1
        fi
    else
        echo "  WARNING: python3 not found, so downloadSize was NOT checked against the ZIP"
    fi
    echo ""
done

# --- Summary ---
echo "=== Summary ==="
if [ "$PACKAGED" -gt 0 ]; then
    total_mb=$(echo "scale=1; $TOTAL_BYTES / 1048576" | bc)
    echo "  Packaged: $PACKAGED toolchain(s), ${total_mb} MB total"
    echo ""
    ls -lh "$OUTPUT_DIR"/toolchain_*.zip 2>/dev/null
    echo ""
    echo "Upload these ZIPs to GitHub Releases for sideload distribution."
fi

if [ "$FAILED" -gt 0 ]; then
    echo ""
    echo "  Skipped: $FAILED toolchain(s) (missing assets, run download scripts)"
    echo "  A skipped toolchain has no ZIP in toolchain-zips/: any from an earlier run"
    echo "  is removed, because nothing in this run checked it."
fi

if [ "$PACKAGED" -eq 0 ]; then
    echo ""
    echo "No toolchains were packaged. Run the download scripts first:"
    for tc in "${ALL_TOOLCHAINS[@]}"; do
        echo "  ./scripts/download-$tc.sh"
    done
    exit 1
fi
