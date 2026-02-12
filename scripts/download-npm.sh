#!/usr/bin/env bash
set -euo pipefail

# Download npm from the official Node.js tarball.
# Extracts lib/node_modules/npm/ to assets/usr/lib/node_modules/npm/.
# Strips docs, man pages, and test files to minimize size.
#
# npm version is locked to the one bundled with Node.js 20.18.1 (npm 10.8.2).

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"
ASSETS_DIR="$ROOT_DIR/android/app/src/main/assets"
WORK_DIR="$ROOT_DIR/toolchains/build/npm"

NODE_VERSION="v20.18.1"
NPM_VERSION="10.8.2"
NODE_TARBALL="node-${NODE_VERSION}-linux-arm64.tar.xz"
NODE_URL="https://nodejs.org/dist/${NODE_VERSION}/${NODE_TARBALL}"

DEST_DIR="$ASSETS_DIR/usr/lib/node_modules/npm"

echo "=== Downloading npm ${NPM_VERSION} (from Node.js ${NODE_VERSION}) ==="
echo ""

mkdir -p "$WORK_DIR"

# --- Step 1: Download Node.js tarball ---
echo "Downloading Node.js tarball..."
if [ -f "$WORK_DIR/$NODE_TARBALL" ]; then
    echo "  Using cached $NODE_TARBALL"
else
    curl -L --fail --show-error -o "$WORK_DIR/$NODE_TARBALL" "$NODE_URL"
    echo "  Downloaded: $(du -sh "$WORK_DIR/$NODE_TARBALL" | cut -f1)"
fi

# --- Step 2: Extract only npm ---
echo ""
echo "Extracting npm from tarball..."
rm -rf "$WORK_DIR/extracted"
mkdir -p "$WORK_DIR/extracted"

# Extract only the npm directory (lib/node_modules/npm/)
tar xf "$WORK_DIR/$NODE_TARBALL" \
    -C "$WORK_DIR/extracted" \
    --strip-components=1 \
    "node-${NODE_VERSION}-linux-arm64/lib/node_modules/npm"

NPM_SRC="$WORK_DIR/extracted/lib/node_modules/npm"

if [ ! -d "$NPM_SRC" ]; then
    echo "  ERROR: npm not found in tarball"
    exit 1
fi

# Verify version
EXTRACTED_VERSION=$(node -e "console.log(require('$NPM_SRC/package.json').version)" 2>/dev/null || \
    python3 -c "import json; print(json.load(open('$NPM_SRC/package.json'))['version'])" 2>/dev/null || \
    grep '"version"' "$NPM_SRC/package.json" | head -1 | sed 's/.*"\([0-9.]*\)".*/\1/')

echo "  npm version: $EXTRACTED_VERSION"
if [ "$EXTRACTED_VERSION" != "$NPM_VERSION" ]; then
    echo "  WARNING: Expected npm $NPM_VERSION but got $EXTRACTED_VERSION"
fi

# --- Step 3: Strip unnecessary files ---
echo ""
echo "Stripping docs, tests, and unnecessary files..."
BEFORE_SIZE=$(du -sk "$NPM_SRC" | cut -f1)

# Remove docs and changelogs
rm -rf "$NPM_SRC/docs" "$NPM_SRC/doc" "$NPM_SRC/man"
rm -f "$NPM_SRC/CHANGELOG.md" "$NPM_SRC/README.md" "$NPM_SRC/LICENSE"
rm -f "$NPM_SRC/changelogs"*

# Remove Windows-specific files
find "$NPM_SRC" -name "*.cmd" -delete 2>/dev/null || true
find "$NPM_SRC" -name "*.ps1" -delete 2>/dev/null || true
find "$NPM_SRC" -name "*.bat" -delete 2>/dev/null || true

# Remove test directories
find "$NPM_SRC" -type d -name "test" -exec rm -rf {} + 2>/dev/null || true
find "$NPM_SRC" -type d -name "tests" -exec rm -rf {} + 2>/dev/null || true
find "$NPM_SRC" -type d -name "__tests__" -exec rm -rf {} + 2>/dev/null || true
find "$NPM_SRC" -type d -name "tap-snapshots" -exec rm -rf {} + 2>/dev/null || true

# Remove other unnecessary files
find "$NPM_SRC" -name "*.md" -not -name "package.json" -delete 2>/dev/null || true
find "$NPM_SRC" -name ".npmignore" -delete 2>/dev/null || true
find "$NPM_SRC" -name ".eslintrc*" -delete 2>/dev/null || true
find "$NPM_SRC" -name ".gitignore" -delete 2>/dev/null || true
find "$NPM_SRC" -name "Makefile" -delete 2>/dev/null || true
find "$NPM_SRC" -name "AUTHORS" -delete 2>/dev/null || true
find "$NPM_SRC" -name "CONTRIBUTORS" -delete 2>/dev/null || true

AFTER_SIZE=$(du -sk "$NPM_SRC" | cut -f1)
echo "  Before: $((BEFORE_SIZE / 1024))M -> After: $((AFTER_SIZE / 1024))M (saved $((( BEFORE_SIZE - AFTER_SIZE ) / 1024))M)"

# --- Step 4: Verify entry points ---
echo ""
echo "Verifying entry points..."
for entry in "bin/npm-cli.js" "bin/npx-cli.js"; do
    if [ -f "$NPM_SRC/$entry" ]; then
        echo "  OK: $entry"
    else
        echo "  ERROR: $entry not found!"
        exit 1
    fi
done

# --- Step 5: Place in assets ---
echo ""
echo "Placing npm in assets..."
rm -rf "$DEST_DIR"
mkdir -p "$(dirname "$DEST_DIR")"
cp -r "$NPM_SRC" "$DEST_DIR"

FINAL_SIZE=$(du -sk "$DEST_DIR" | cut -f1)
FILE_COUNT=$(find "$DEST_DIR" -type f | wc -l | tr -d ' ')

echo ""
echo "=== npm Download Complete ==="
echo "  Version: $EXTRACTED_VERSION"
echo "  Size: $((FINAL_SIZE / 1024))M ($FILE_COUNT files)"
echo "  Location: $DEST_DIR"
echo ""
echo "Wrapper scripts (usr/bin/npm, usr/bin/npx) are generated at runtime"
echo "by FirstRunSetup.kt since they need absolute paths to libnode.so."
