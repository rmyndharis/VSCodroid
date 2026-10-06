#!/usr/bin/env bash
# Shared Android target selection for download/build scripts.
# Source this file; it exports one validated target mapping and staging guard.

android_target_init() {
    local selected="${VSCODROID_ABI:-arm64-v8a}"
    case "$selected" in
        arm64-v8a)
            ANDROID_ABI=arm64-v8a
            TERMUX_ARCH=aarch64
            NODE_ARCH=arm64
            NDK_TARGET=aarch64-linux-android
            ELF_MACHINE=183
            GLIBC_LOADER=ld-linux-aarch64.so.1
            ;;
        x86_64)
            ANDROID_ABI=x86_64
            TERMUX_ARCH=x86_64
            NODE_ARCH=x64
            NDK_TARGET=x86_64-linux-android
            ELF_MACHINE=62
            GLIBC_LOADER=ld-linux-x86-64.so.2
            ;;
        *)
            echo "  ERROR: unsupported VSCODROID_ABI '$selected' (expected arm64-v8a or x86_64)" >&2
            return 1
            ;;
    esac
    export ANDROID_ABI TERMUX_ARCH NODE_ARCH NDK_TARGET ELF_MACHINE GLIBC_LOADER
}

# Verify and stamp the single-target asset staging tree. Unmarked ARM trees are
# accepted for backwards compatibility. An x86_64 build must start with a clean
# binary staging tree so it cannot silently relabel ARM payloads.
android_target_require_staging() {
    local assets_dir="${1:-}" marker
    if [ -z "$assets_dir" ]; then
        echo "  ERROR: android_target_require_staging requires an assets directory" >&2
        return 1
    fi
    marker="$assets_dir/.vscodroid-abi"
    if [ -f "$marker" ]; then
        local current
        current="$(tr -d '\r\n' < "$marker")"
        if [ "$current" != "$ANDROID_ABI" ]; then
            echo "  ERROR: staged assets target is '$current', requested '$ANDROID_ABI'." >&2
            echo "         Clean/rebuild the binary staging tree before switching ABI." >&2
            return 1
        fi
    elif [ "$ANDROID_ABI" = x86_64 ]; then
        local payload_dir
        for payload_dir in "$assets_dir/usr" "$(dirname "$assets_dir")/jniLibs"; do
            if [ -d "$payload_dir" ] && find "$payload_dir" -type f -print -quit | grep -q .; then
                echo "  ERROR: x86_64 selected, but unmarked native payloads exist under $payload_dir." >&2
                echo "         Clean the staged binaries before starting an x86_64 download." >&2
                return 1
            fi
        done
    fi
    mkdir -p "$assets_dir"
    printf '%s\n' "$ANDROID_ABI" > "$marker"
}

android_target_init
