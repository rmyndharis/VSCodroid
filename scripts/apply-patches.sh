#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"
VSCODE_DIR="$ROOT_DIR/server/lib/vscode"
CS_PATCHES="$ROOT_DIR/patches/code-server"
VS_PATCHES="$ROOT_DIR/patches/vscodroid"

echo "=== Applying Patches ==="

if [ ! -d "$VSCODE_DIR" ]; then
    echo "ERROR: VS Code source not found at $VSCODE_DIR"
    echo "Run: git submodule update --init --recursive"
    exit 1
fi

cd "$VSCODE_DIR"

# Reset any previous patches
echo "Resetting VS Code source..."
git checkout -- . 2>/dev/null || true

# Apply code-server patches first
echo ""
echo "Applying code-server patches..."
if [ -d "$CS_PATCHES" ] && ls "$CS_PATCHES"/*.diff 1>/dev/null 2>&1; then
    for patch in "$CS_PATCHES"/*.diff; do
        patchname=$(basename "$patch")
        echo -n "  Applying $patchname... "
        if git apply --check "$patch" 2>/dev/null; then
            git apply "$patch"
            echo "✓"
        else
            echo "FAILED (conflict or already applied)"
            echo "  Attempting 3-way merge..."
            if git apply --3way "$patch" 2>/dev/null; then
                echo "  ✓ Applied with 3-way merge"
            else
                echo "  ✗ FAILED — manual resolution needed"
                exit 1
            fi
        fi
    done
else
    echo "  No code-server patches found (expected at first setup)"
fi

# Apply VSCodroid patches
echo ""
echo "Applying VSCodroid patches..."
if [ -d "$VS_PATCHES" ] && ls "$VS_PATCHES"/*.diff 1>/dev/null 2>&1; then
    for patch in "$VS_PATCHES"/*.diff; do
        patchname=$(basename "$patch")
        echo -n "  Applying $patchname... "
        if git apply --check "$patch" 2>/dev/null; then
            git apply "$patch"
            echo "✓"
        else
            echo "FAILED"
            exit 1
        fi
    done
else
    echo "  No VSCodroid patches found yet (will be created in M2+)"
fi

echo ""
echo "=== All patches applied successfully ==="
