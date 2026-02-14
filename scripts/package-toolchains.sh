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
#     ./scripts/download-go.sh
#     ./scripts/download-ruby.sh
#     ./scripts/download-java.sh
#
# Usage:
#   ./scripts/package-toolchains.sh              # Package all toolchains
#   ./scripts/package-toolchains.sh go            # Package only Go
#   ./scripts/package-toolchains.sh ruby java     # Package Ruby and Java
#
# Output:
#   toolchain-zips/toolchain_go.zip    (~53 MB)
#   toolchain-zips/toolchain_ruby.zip  (~10 MB)
#   toolchain-zips/toolchain_java.zip  (~55 MB)
#
# The ZIPs are intended to be attached to GitHub Releases so that sideloaded
# APK users (not from Play Store) can download toolchains manually. The app's
# ToolchainManager extracts them into the app's files directory.
#
# Compatible with bash 3.2+ (macOS default).

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"
OUTPUT_DIR="$ROOT_DIR/toolchain-zips"

# Available toolchains (parallel arrays for bash 3.2 — no associative arrays)
ALL_TOOLCHAINS=(go ruby java)

get_assets_dir() {
    echo "$ROOT_DIR/android/toolchain_$1/src/main/assets"
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

PACKAGED=0
FAILED=0
TOTAL_BYTES=0

for tc in "${REQUESTED[@]}"; do
    assets_dir="$(get_assets_dir "$tc")"
    manifest="$assets_dir/toolchain_$tc.json"
    usr_dir="$assets_dir/usr"
    zip_file="$OUTPUT_DIR/toolchain_$tc.zip"

    echo "--- $tc ---"

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

    # Remove stale ZIP
    rm -f "$zip_file"

    # Create ZIP from the assets directory contents.
    # cd into the assets dir so paths inside the ZIP are relative
    # (e.g., toolchain_go.json, usr/lib/go/bin/go).
    # -r: recursive, -y: store symlinks as symlinks, -q: quiet
    (cd "$assets_dir" && zip -r -y -q "$zip_file" .)

    # Report size
    zip_bytes=$(wc -c < "$zip_file" | tr -d ' ')
    zip_mb=$(echo "scale=1; $zip_bytes / 1048576" | bc)
    TOTAL_BYTES=$((TOTAL_BYTES + zip_bytes))
    PACKAGED=$((PACKAGED + 1))

    echo "  Output: toolchain_$tc.zip (${zip_mb} MB)"
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
fi

if [ "$PACKAGED" -eq 0 ]; then
    echo ""
    echo "No toolchains were packaged. Run the download scripts first:"
    echo "  ./scripts/download-go.sh"
    echo "  ./scripts/download-ruby.sh"
    echo "  ./scripts/download-java.sh"
    exit 1
fi
