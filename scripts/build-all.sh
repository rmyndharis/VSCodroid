#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"

echo "========================================="
echo "  VSCodroid Full Build"
echo "========================================="
echo ""

# Step 1: Setup
echo "[1/5] Running setup..."
"$SCRIPT_DIR/setup.sh"

# Step 2: Build VS Code
echo ""
echo "[2/5] Building VS Code..."
if [ -d "$ROOT_DIR/server/lib/vscode" ]; then
    "$SCRIPT_DIR/build-vscode.sh"
else
    echo "  ⚠ Skipping VS Code build (code-server submodule not initialized)"
fi

# Step 3: Package assets
echo ""
echo "[3/5] Packaging assets..."
"$SCRIPT_DIR/package-assets.sh"

# Step 4: Build Android APK
echo ""
echo "[4/5] Building Android APK..."
cd "$ROOT_DIR/android"
if [ -f "gradlew" ]; then
    ./gradlew assembleDebug
    echo "  ✓ APK built"
else
    echo "  ⚠ Gradle wrapper not found. Run: cd android && gradle wrapper"
fi

# Step 5: Summary
echo ""
echo "[5/5] Build Summary"
APK_PATH="$ROOT_DIR/android/app/build/outputs/apk/debug/app-debug.apk"
if [ -f "$APK_PATH" ]; then
    echo "  ✓ APK: $(du -sh "$APK_PATH" | cut -f1) at $APK_PATH"
else
    echo "  ⚠ APK not found"
fi

echo ""
echo "========================================="
echo "  Build complete!"
echo "  Deploy: ./scripts/deploy.sh"
echo "========================================="
