#!/usr/bin/env bash
# Build signed AAB for Play Store upload.
# Usage: bash scripts/build-aab.sh
#
# Signing config is read from android/signing.properties (gitignored).
# Falls back to VSCODROID_* env vars for CI.

set -euo pipefail
cd "$(dirname "$0")/../android"

if [ ! -f signing.properties ]; then
    echo "ERROR: android/signing.properties not found."
    echo "Create it with:"
    echo "  storeFile=/path/to/vscodroid-release.jks"
    echo "  storePassword=..."
    echo "  keyAlias=vscodroid"
    echo "  keyPassword=..."
    exit 1
fi

echo "Building signed AAB..."
./gradlew bundleRelease

AAB="app/build/outputs/bundle/release/app-release.aab"
if [ -f "$AAB" ]; then
    SIZE=$(du -sh "$AAB" | cut -f1)
    echo ""
    echo "AAB ready: $AAB ($SIZE)"
    echo "Upload to Play Console: https://play.google.com/console"
else
    echo "ERROR: AAB not found after build"
    exit 1
fi
