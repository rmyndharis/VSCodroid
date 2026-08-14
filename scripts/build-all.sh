#!/usr/bin/env bash
# Prepare every asset the APK needs, then build it.
#
# The order below is build.yml's, and it is not arbitrary:
#   * the server tree has to be in assets/ before build-native-addons.sh, which
#     writes its .node files into that tree;
#   * download-node.sh runs after download-termux-tools.sh, which places the
#     libraries libnode.so links against;
#   * build-native-addons.sh runs after download-node.sh so its version pairing
#     has a runtime to pair against;
#   * build-glibc-shim.sh runs last, because download-termux-tools.sh wipes
#     assets/usr/lib and the stubs live there.
#
# scripts/check-build-steps.py fails CI when this list and the workflow's stop
# agreeing.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"
ASSETS="$ROOT_DIR/android/app/src/main/assets"

echo "========================================="
echo "  VSCodroid Full Build"
echo "========================================="

step() { echo; echo "[$1] $2"; }

step 1/10 "Checking prerequisites..."
"$SCRIPT_DIR/setup.sh"

step 2/10 "Fetching the Code - OSS server tree..."
"$SCRIPT_DIR/fetch-vscode-oss.sh"

# fetch-vscode-oss.sh leaves the tree in server/; the APK reads it from assets/.
# Without this copy the build succeeds and ships no server at all.
step 3/10 "Installing the server tree into assets..."
"$SCRIPT_DIR/package-assets.sh"

step 4/10 "Downloading Termux tools..."
"$SCRIPT_DIR/download-termux-tools.sh"

step 5/10 "Downloading npm..."
"$SCRIPT_DIR/download-npm.sh"

step 6/10 "Downloading Python..."
"$SCRIPT_DIR/download-python.sh"

step 7/10 "Downloading extensions..."
"$SCRIPT_DIR/download-extensions.sh"

step 8/10 "Downloading the musl loader..."
"$SCRIPT_DIR/download-musl-loader.sh"

step 9/10 "Building native addons and the compatibility shim..."
"$SCRIPT_DIR/download-node.sh"
"$SCRIPT_DIR/build-native-addons.sh"
"$SCRIPT_DIR/build-glibc-shim.sh" \
    --scan "$ASSETS/vscode-reh" \
    --scan "$ASSETS/extensions"

step 10/10 "Building the APK..."
cd "$ROOT_DIR/android"
if [ ! -f gradlew ]; then
    echo "  ERROR: Gradle wrapper not found. Run: cd android && gradle wrapper" >&2
    exit 1
fi
./gradlew assembleDebug

APK_PATH="$ROOT_DIR/android/app/build/outputs/apk/debug/app-debug.apk"
echo
if [ -f "$APK_PATH" ]; then
    echo "  APK: $(du -sh "$APK_PATH" | cut -f1) at $APK_PATH"
else
    # assembleDebug succeeding without producing an APK would mean the output
    # path moved; saying "build complete" here would be the lie this script
    # used to tell.
    echo "  ERROR: assembleDebug reported success but $APK_PATH is missing." >&2
    exit 1
fi

echo
echo "========================================="
echo "  Build complete"
echo "  Deploy: ./scripts/deploy.sh"
echo "  Optional toolchains: ./scripts/download-{go,ruby,java}.sh"
echo "========================================="
