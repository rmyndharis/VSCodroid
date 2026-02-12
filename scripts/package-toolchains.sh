#!/usr/bin/env bash
# Creates ZIP archives from toolchain asset pack directories for GitHub Releases.
#
# Prerequisites: run download-go.sh, download-ruby.sh, download-java.sh first
# to populate the asset pack directories.
#
# Output: toolchain-zips/toolchain_go.zip, toolchain_ruby.zip, toolchain_java.zip

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
OUTPUT_DIR="$PROJECT_ROOT/toolchain-zips"

PACKS=(toolchain_go toolchain_ruby toolchain_java)

mkdir -p "$OUTPUT_DIR"

for pack in "${PACKS[@]}"; do
    assets_dir="$PROJECT_ROOT/android/${pack}/src/main/assets"

    if [ ! -d "$assets_dir" ]; then
        echo "ERROR: $assets_dir not found — run download scripts first"
        exit 1
    fi

    if [ ! -f "$assets_dir/${pack}.json" ]; then
        echo "ERROR: ${pack}.json not found in $assets_dir"
        exit 1
    fi

    zip_path="$OUTPUT_DIR/${pack}.zip"
    echo "Packaging $pack..."

    # Create ZIP from the assets directory contents
    # (cd into the dir so paths inside the zip are relative)
    (cd "$assets_dir" && zip -r -q "$zip_path" .)

    size=$(du -sh "$zip_path" | cut -f1)
    echo "  -> $zip_path ($size)"
done

echo ""
echo "All toolchain ZIPs created in $OUTPUT_DIR/"
ls -lh "$OUTPUT_DIR/"
