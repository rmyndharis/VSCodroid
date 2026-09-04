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

step 1/11 "Checking prerequisites..."
"$SCRIPT_DIR/setup.sh"

step 2/11 "Fetching the Code - OSS server tree..."
"$SCRIPT_DIR/fetch-vscode-oss.sh"

# fetch-vscode-oss.sh leaves the tree in server/; the APK reads it from assets/.
# Without this copy the build succeeds and ships no server at all.
step 3/11 "Installing the server tree into assets..."
"$SCRIPT_DIR/package-assets.sh"

# Reads out/nls.keys.json from the tree the step above installed, so it cannot
# run before it, and Gradle sizes assets/ at configuration time, so it cannot run
# after the build. Not a shell script, which is why check-build-steps.py does not
# see it: that gate matches on .sh, so this line is the only thing keeping a local
# build from producing an APK whose editor is English in every language.
step 4/11 "Building the translated interface bundles..."
python3 "$SCRIPT_DIR/build-nls-bundles.py"

step 5/11 "Downloading Termux tools..."
"$SCRIPT_DIR/download-termux-tools.sh"

step 6/11 "Downloading npm..."
"$SCRIPT_DIR/download-npm.sh"

step 7/11 "Downloading Python..."
"$SCRIPT_DIR/download-python.sh"

step 8/11 "Downloading extensions..."
"$SCRIPT_DIR/download-extensions.sh"

step 9/11 "Downloading the musl loader..."
"$SCRIPT_DIR/download-musl-loader.sh"

step 10/11 "Building native addons and the compatibility shim..."
"$SCRIPT_DIR/download-node.sh"
"$SCRIPT_DIR/build-native-addons.sh"
"$SCRIPT_DIR/build-glibc-shim.sh" \
    --scan "$ASSETS/vscode-reh" \
    --scan "$ASSETS/extensions"
# Depends on nothing downloaded, so its position here is a convenience. It writes
# into jniLibs rather than assets/usr/lib, which download-termux-tools.sh wipes,
# so it is not subject to the ordering constraint the shim above is.
"$SCRIPT_DIR/build-exec-trampoline.sh"

# The same, and it can sit here for the same reason: it writes only into jniLibs.
"$SCRIPT_DIR/build-claude-shim.sh"

step 11/11 "Building the APK..."
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
echo "  Optional toolchains: ./scripts/download-{ruby,java}.sh"
echo "========================================="
