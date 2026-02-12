#!/usr/bin/env bash
set -euo pipefail

# Download pre-built extensions from Open VSX and extract them into
# android/app/src/main/assets/extensions/ for bundling in the APK.
#
# Each extension becomes a directory like PKief.material-icon-theme-5.17.0/
# which FirstRunSetup extracts on device and registers via extensions.json.
#
# Compatible with bash 3.2+ (macOS default).

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"
ASSETS_DIR="$ROOT_DIR/android/app/src/main/assets/extensions"
WORK_DIR="$ROOT_DIR/toolchains/extensions"

# Extensions to bundle: publisher.name or publisher.name@version
# Pin versions to ensure compatibility with VS Code 1.96.4
EXTENSIONS=(
    "PKief.material-icon-theme@5.31.0"
    "esbenp.prettier-vscode@11.0.3"
    "ms-python.python@2024.22.1"
    "dbaeumer.vscode-eslint@3.0.20"
    "bradlc.vscode-tailwindcss@0.14.28"
    "eamodio.gitlens@2026.2.1114"
)

OPENVSX_API="https://open-vsx.org/api"

echo "=== Downloading bundled extensions from Open VSX ==="

mkdir -p "$ASSETS_DIR"
mkdir -p "$WORK_DIR"

for EXT_SPEC in "${EXTENSIONS[@]}"; do
    # Parse publisher.name@version or publisher.name (latest)
    EXT_ID="${EXT_SPEC%%@*}"
    PINNED_VERSION="${EXT_SPEC#*@}"
    if [ "$PINNED_VERSION" = "$EXT_SPEC" ]; then
        PINNED_VERSION=""
    fi

    PUBLISHER="${EXT_ID%%.*}"
    NAME="${EXT_ID#*.}"

    echo ""
    echo "--- $PUBLISHER/$NAME${PINNED_VERSION:+ @$PINNED_VERSION} ---"

    # Query Open VSX API for version metadata
    if [ -n "$PINNED_VERSION" ]; then
        API_URL="$OPENVSX_API/$PUBLISHER/$NAME/$PINNED_VERSION"
        METADATA_FILE="$WORK_DIR/${EXT_ID}-${PINNED_VERSION}.json"
    else
        API_URL="$OPENVSX_API/$PUBLISHER/$NAME"
        METADATA_FILE="$WORK_DIR/${EXT_ID}.json"
    fi

    if [ ! -f "$METADATA_FILE" ]; then
        echo "  Fetching metadata..."
        curl -sL --fail --show-error \
            -H "Accept: application/json" \
            -o "$METADATA_FILE" \
            "$API_URL"
    fi

    # Extract version and download URL
    VERSION=$(python3 -c "import json; print(json.load(open('$METADATA_FILE'))['version'])")
    DOWNLOAD_URL=$(python3 -c "import json; d=json.load(open('$METADATA_FILE')); print(d['files']['download'])")

    DIR_NAME="${EXT_ID}-${VERSION}"
    DEST_DIR="$ASSETS_DIR/$DIR_NAME"

    echo "  Version: $VERSION"
    echo "  Download URL: $DOWNLOAD_URL"

    # Skip if already extracted
    if [ -d "$DEST_DIR" ] && [ -f "$DEST_DIR/package.json" ]; then
        echo "  Already extracted: $DIR_NAME (skipping)"
        continue
    fi

    # Download VSIX
    VSIX_FILE="$WORK_DIR/${DIR_NAME}.vsix"
    if [ ! -f "$VSIX_FILE" ]; then
        echo "  Downloading VSIX..."
        curl -sL --fail --show-error -o "$VSIX_FILE" "$DOWNLOAD_URL"
        echo "  Downloaded: $(du -sh "$VSIX_FILE" | cut -f1)"
    fi

    # Extract extension/ contents from VSIX (it's a ZIP)
    echo "  Extracting..."
    rm -rf "$DEST_DIR"
    mkdir -p "$DEST_DIR"

    # VSIX contains extension/ directory with actual extension files
    # Use -j to strip path prefix, but we need the directory structure
    TEMP_EXTRACT="$WORK_DIR/extract-${DIR_NAME}"
    rm -rf "$TEMP_EXTRACT"
    mkdir -p "$TEMP_EXTRACT"
    unzip -q -o "$VSIX_FILE" "extension/*" -d "$TEMP_EXTRACT"

    # Move extension/ contents to destination
    if [ -d "$TEMP_EXTRACT/extension" ]; then
        cp -a "$TEMP_EXTRACT/extension/." "$DEST_DIR/"
    else
        echo "  ERROR: No extension/ directory in VSIX"
        rm -rf "$DEST_DIR"
        continue
    fi
    rm -rf "$TEMP_EXTRACT"

    # Remove dotfiles that AAPT would strip from assets
    DOTFILES_FOUND=0
    while IFS= read -r dotfile; do
        DOTFILES_FOUND=1
        BASENAME=$(basename "$dotfile")
        DIRNAME=$(dirname "$dotfile")
        NEWNAME="${BASENAME#.}"
        echo "  Renaming dotfile: $BASENAME -> _${NEWNAME}"
        mv "$dotfile" "$DIRNAME/_${NEWNAME}"
    done < <(find "$DEST_DIR" -name '.*' -not -name '.' -not -name '..')

    if [ "$DOTFILES_FOUND" -eq 0 ]; then
        echo "  No dotfiles found (OK)"
    fi

    echo "  Extracted: $(du -sh "$DEST_DIR" | cut -f1) -> $DIR_NAME"
done

# Summary
echo ""
echo "=== Bundled extensions ready ==="
echo "Location: $ASSETS_DIR/"
for dir in "$ASSETS_DIR"/*/; do
    if [ -d "$dir" ]; then
        NAME=$(basename "$dir")
        SIZE=$(du -sh "$dir" | cut -f1)
        echo "  $NAME ($SIZE)"
    fi
done
echo ""
echo "Total: $(du -sh "$ASSETS_DIR" | cut -f1)"
