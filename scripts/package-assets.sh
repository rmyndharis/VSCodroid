#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"
ASSETS_DIR="$ROOT_DIR/android/app/src/main/assets"
JNILIBS_DIR="$ROOT_DIR/android/app/src/main/jniLibs/arm64-v8a"

echo "=== Packaging Assets ==="

# VS Code web client
WEB_SRC="$ROOT_DIR/server/lib/vscode-web"
if [ -d "$WEB_SRC" ]; then
    echo "Copying vscode-web..."
    rm -rf "$ASSETS_DIR/vscode-web"
    cp -r "$WEB_SRC" "$ASSETS_DIR/vscode-web"
    echo "  ✓ vscode-web: $(du -sh "$ASSETS_DIR/vscode-web" | cut -f1)"
else
    echo "  ⚠ vscode-web not found (run build-vscode.sh first)"
fi

# VS Code server (remote extension host)
REH_SRC="$ROOT_DIR/server/lib/vscode-reh"
if [ -d "$REH_SRC" ]; then
    echo "Copying vscode-reh..."
    rm -rf "$ASSETS_DIR/vscode-reh"
    cp -r "$REH_SRC" "$ASSETS_DIR/vscode-reh"
    echo "  ✓ vscode-reh: $(du -sh "$ASSETS_DIR/vscode-reh" | cut -f1)"
else
    echo "  ⚠ vscode-reh not found (run build-vscode.sh first)"
fi

# Server bootstrap
SERVER_JS="$ROOT_DIR/android/app/src/main/assets/server.js"
if [ -f "$SERVER_JS" ]; then
    echo "  ✓ server.js present"
else
    echo "  ⚠ server.js not found"
fi

# Summary
echo ""
echo "=== Packaging Summary ==="
echo "Assets dir: $ASSETS_DIR"
[ -d "$ASSETS_DIR/vscode-web" ] && echo "  vscode-web: $(du -sh "$ASSETS_DIR/vscode-web" | cut -f1)"
[ -d "$ASSETS_DIR/vscode-reh" ] && echo "  vscode-reh: $(du -sh "$ASSETS_DIR/vscode-reh" | cut -f1)"

echo ""
echo "Bundled tools (assets/usr/):"
[ -d "$ASSETS_DIR/usr/lib" ] && echo "  usr/lib (shared libs): $(du -sh "$ASSETS_DIR/usr/lib" | cut -f1)"
[ -d "$ASSETS_DIR/usr/lib/git-core" ] && echo "  usr/lib/git-core: $(du -sh "$ASSETS_DIR/usr/lib/git-core" | cut -f1)"
[ -d "$ASSETS_DIR/usr/share" ] && echo "  usr/share (terminfo, templates): $(du -sh "$ASSETS_DIR/usr/share" | cut -f1)"

echo ""
echo "jniLibs dir: $JNILIBS_DIR"
if ls "$JNILIBS_DIR"/*.so 1>/dev/null 2>&1; then
    for so in "$JNILIBS_DIR"/*.so; do
        echo "  $(basename "$so"): $(du -sh "$so" | cut -f1)"
    done
else
    echo "  No .so binaries yet (run toolchain builds first)"
fi

echo ""
echo "=== Packaging complete ==="
echo "Run: cd android && ./gradlew assembleDebug"
