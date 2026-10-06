#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"
HELPER="$SCRIPT_DIR/lib/android-target.sh"
TMP="$(mktemp -d "${TMPDIR:-/tmp}/vscodroid-target.XXXXXX")"
trap 'rm -rf "$TMP"' EXIT

expect() {
    local actual="$1" expected="$2" label="$3"
    if [ "$actual" != "$expected" ]; then
        echo "FAIL $label: expected '$expected', got '$actual'" >&2
        exit 1
    fi
}

arm="$(VSCODROID_ABI=arm64-v8a bash -c '. "$1"; printf "%s|%s|%s|%s|%s|%s" "$ANDROID_ABI" "$TERMUX_ARCH" "$NODE_ARCH" "$NDK_TARGET" "$ELF_MACHINE" "$GLIBC_LOADER"' _ "$HELPER")"
expect "$arm" 'arm64-v8a|aarch64|arm64|aarch64-linux-android|183|ld-linux-aarch64.so.1' 'ARM mapping'
x64="$(VSCODROID_ABI=x86_64 bash -c '. "$1"; printf "%s|%s|%s|%s|%s|%s" "$ANDROID_ABI" "$TERMUX_ARCH" "$NODE_ARCH" "$NDK_TARGET" "$ELF_MACHINE" "$GLIBC_LOADER"' _ "$HELPER")"
expect "$x64" 'x86_64|x86_64|x64|x86_64-linux-android|62|ld-linux-x86-64.so.2' 'x86_64 mapping'

if VSCODROID_ABI=mips bash -c '. "$1"' _ "$HELPER" >/dev/null 2>&1; then
    echo 'FAIL invalid ABI was accepted' >&2
    exit 1
fi

mkdir -p "$TMP/legacy-assets/usr"
touch "$TMP/legacy-assets/usr/old-arm-payload"
VSCODROID_ABI=arm64-v8a bash -c '. "$1"; android_target_require_staging "$2"' _ "$HELPER" "$TMP/legacy-assets"
expect "$(cat "$TMP/legacy-assets/.vscodroid-abi")" 'arm64-v8a' 'legacy ARM staging stamp'

mkdir -p "$TMP/unmarked-assets/usr"
touch "$TMP/unmarked-assets/usr/arm-payload"
if VSCODROID_ABI=x86_64 bash -c '. "$1"; android_target_require_staging "$2"' _ "$HELPER" "$TMP/unmarked-assets" >/dev/null 2>&1; then
    echo 'FAIL x86_64 accepted an unmarked binary staging tree' >&2
    exit 1
fi
if [ -f "$TMP/unmarked-assets/.vscodroid-abi" ]; then
    echo 'FAIL rejected x86_64 staging was stamped' >&2
    exit 1
fi

mkdir -p "$TMP/mismatch"
printf 'arm64-v8a\n' > "$TMP/mismatch/.vscodroid-abi"
if VSCODROID_ABI=x86_64 bash -c '. "$1"; android_target_require_staging "$2"' _ "$HELPER" "$TMP/mismatch" >/dev/null 2>&1; then
    echo 'FAIL mismatched ABI marker was accepted' >&2
    exit 1
fi

mkdir -p "$TMP/clean-assets"
VSCODROID_ABI=x86_64 bash -c '. "$1"; android_target_require_staging "$2"' _ "$HELPER" "$TMP/clean-assets"
expect "$(cat "$TMP/clean-assets/.vscodroid-abi")" 'x86_64' 'clean x86_64 staging stamp'
echo 'PASS Android target mappings and staging guards'
