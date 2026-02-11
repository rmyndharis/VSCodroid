#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"
VSCODE_DIR="$ROOT_DIR/server/lib/vscode"

echo "=== Building VS Code ==="

if [ ! -d "$VSCODE_DIR" ]; then
    echo "ERROR: VS Code source not found at $VSCODE_DIR"
    exit 1
fi

cd "$VSCODE_DIR"

# Apply patches first
echo "Step 1: Applying patches..."
"$SCRIPT_DIR/apply-patches.sh"

# Install dependencies
echo ""
echo "Step 2: Installing dependencies..."
yarn --frozen-lockfile

# Build web client
echo ""
echo "Step 3: Building vscode-web (web client)..."
yarn gulp vscode-web-min
echo "  ✓ vscode-web built"

# Build server (remote extension host)
echo ""
echo "Step 4: Building vscode-reh (server)..."
yarn gulp vscode-reh-min
echo "  ✓ vscode-reh built"

# Verify outputs
echo ""
echo "Verifying build outputs..."
WEB_OUT="$VSCODE_DIR/../vscode-web"
REH_OUT="$VSCODE_DIR/../vscode-reh"

if [ -d "$WEB_OUT" ]; then
    echo "  ✓ vscode-web: $(du -sh "$WEB_OUT" | cut -f1)"
else
    echo "  ✗ vscode-web output not found"
    exit 1
fi

if [ -d "$REH_OUT" ]; then
    echo "  ✓ vscode-reh: $(du -sh "$REH_OUT" | cut -f1)"
else
    echo "  ✗ vscode-reh output not found"
    exit 1
fi

echo ""
echo "=== VS Code build complete ==="
echo "Run: ./scripts/package-assets.sh to copy to Android project"
