#!/usr/bin/env bash
# VSCodroid Device Test Suite
# Automated tests run against a connected Android device via adb.
# macOS bash 3.2 compatible (no associative arrays, no `timeout` command).
#
# Usage:
#   bash scripts/device-test.sh [OPTIONS]
#
# Options:
#   --skip-build     Use existing APK (don't run Gradle)
#   --skip-install   Test already-running app (skip install + clear)
#   --device SERIAL  Target specific device (passed as adb -s SERIAL)
#   --verbose        Show full adb output
#   --timeout N      Server-ready timeout in seconds (default: 120)
#   --self-check     Resolve every expectation this suite derives, then exit.
#                    Needs no device, so CI can run it.
#   --instrumented   Check the preconditions, then run the instrumented suite
#                    (./gradlew connectedDebugAndroidTest) and exit. Needs a
#                    booted arm64 emulator or device.
#
# WHEN TO RUN THIS
#
# It needs a device or emulator, and nothing runs it automatically. That is a
# measured conclusion rather than an omission: GitHub's arm64 runners expose no
# /dev/kvm, and nine of the eleven bundled executables ask for
# /system/bin/linker64, so executing them anywhere needs an Android system image
# -- 2.1 GB, inside a partitioned disk image. The issue tracker carries the
# evidence and the options.
#
# So it is on a person, and the moments that matter are:
#
#   * before tagging a release -- this suite AND --instrumented, that one first,
#     since it is the only one that starts the app rather than reading what was
#     packed into it;
#   * after changing scripts/download-*.sh or scripts/build-*.sh, which decide
#     what gets bundled;
#   * after a Node, Python or VS Code version bump;
#   * after touching MainActivity, SplashActivity, NodeService, ProcessManager or
#     FirstRunSetup -- --instrumented, which is the only thing that covers them.
#
# This suite once demanded Node v20.x for two releases after the runtime moved
# to 24.18.0. Nothing caught it, because nothing ran it. The versions it checks
# are now read from the tree instead of written here, and --self-check verifies
# those readings still resolve -- but neither of those is a substitute for
# running it.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"
APK_PATH="$ROOT_DIR/android/app/build/outputs/apk/debug/app-debug.apk"
PKG="com.vscodroid.debug"

# ── Derived expectations ───────────────────────────────────────────
# Every version this suite asserts is read from the tree rather than written
# here. Defined once, so --self-check validates the same readings the tests use;
# a second copy for the self-check would be exactly the drift this prevents.

derive_node_expected() {
    # The version the native addons are compiled against. The runtime has to
    # equal it -- an addon built for one Node and loaded by another is the
    # defect check_pair exists for -- so that is where "which Node" lives.
    sed -n 's/^NODE_VERSION="${NODE_VERSION:-\([0-9.]*\)}".*/\1/p' \
        "$ROOT_DIR/scripts/build-native-addons.sh" | head -1
}

derive_npm_expected() {
    # The packaged tree is the better source -- it is what actually shipped --
    # but a checkout that only installed the APK does not have it. The constant
    # the download script uses is the fallback, so this stays derived rather
    # than skipped.
    local pkg="$ROOT_DIR/android/app/src/main/assets/usr/lib/node_modules/npm/package.json"
    local v
    v=$(python3 -c "import json,sys;print(json.load(open(sys.argv[1]))['version'])" "$pkg" 2>/dev/null)
    [ -n "$v" ] || v=$(sed -n 's/^NPM_VERSION="\([0-9.]*\)".*/\1/p' \
        "$ROOT_DIR/scripts/download-npm.sh" | head -1)
    printf '%s' "$v"
}

derive_py_expected() {
    # Resolved from the Termux index at build time, so it moves without warning
    # and there is no number in this repository to compare against -- only the
    # library that actually shipped.
    ls "$ROOT_DIR"/android/app/src/main/assets/usr/lib/libpython3.*.so 2>/dev/null \
        | head -1 | sed 's/.*libpython\(3\.[0-9]*\)\.so/\1/'
}

# Auto-detect adb if not in PATH
if ! command -v adb &>/dev/null; then
    for candidate in \
        "$HOME/Library/Android/sdk/platform-tools/adb" \
        "${ANDROID_HOME:-__none__}/platform-tools/adb" \
        "${ANDROID_SDK_ROOT:-__none__}/platform-tools/adb"; do
        if [ -x "$candidate" ]; then
            export PATH="$(dirname "$candidate"):$PATH"
            break
        fi
    done
fi

# Defaults
SKIP_BUILD=false
SKIP_INSTALL=false
DEVICE=""
VERBOSE=false
TIMEOUT=120
SELF_CHECK=false
INSTRUMENTED=false

# Parse args
while [ $# -gt 0 ]; do
    case "$1" in
        --skip-build)  SKIP_BUILD=true ;;
        --skip-install) SKIP_INSTALL=true ;;
        --device)      DEVICE="$2"; shift ;;
        --verbose)     VERBOSE=true ;;
        --timeout)     TIMEOUT="$2"; shift ;;
        --self-check)  SELF_CHECK=true ;;
        --instrumented) INSTRUMENTED=true ;;
        -h|--help)
            sed -n '2,/^$/s/^# //p' "$0"
            exit 0 ;;
        *) echo "Unknown option: $1"; exit 1 ;;
    esac
    shift
done

# adb wrapper — respects --device flag
ADB="adb"
if [ -n "$DEVICE" ]; then
    ADB="adb -s $DEVICE"
fi

# ── Colors ─────────────────────────────────────────────────────────
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[0;33m'
CYAN='\033[0;36m'
BOLD='\033[1m'
RESET='\033[0m'

# ── Counters ───────────────────────────────────────────────────────
PASS=0
FAIL=0
SKIP=0
FAILURES=""

pass() {
    PASS=$((PASS + 1))
    printf "  ${GREEN}PASS${RESET}  %s\n" "$1"
}

fail() {
    FAIL=$((FAIL + 1))
    FAILURES="${FAILURES}\n  - $1: $2"
    printf "  ${RED}FAIL${RESET}  %s — %s\n" "$1" "$2"
}

skip() {
    SKIP=$((SKIP + 1))
    printf "  ${YELLOW}SKIP${RESET}  %s — %s\n" "$1" "$2"
}

# A first launch is interrupted by the Android 13+ notification request and by the
# first-run toolchain picker. Both take focus, so the app is backgrounded and its
# process disappears — through logcat and ps that is indistinguishable from a
# crash, and it is the reason this suite used to time out on a healthy device.
# Matched by button text out of the view hierarchy rather than by coordinates, so
# it survives a layout change.
dismiss_blocking_dialogs() {
    $ADB shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 || return 0
    DIALOG_XML=$($ADB shell cat /sdcard/ui.xml 2>/dev/null | tr -d '\r') || return 0
    for BUTTON in Allow Skip; do
        BOUNDS=$(echo "$DIALOG_XML" \
            | grep -o "text=\"$BUTTON\"[^>]*bounds=\"\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]\"" \
            | grep -o 'bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"' | head -1)
        [ -z "$BOUNDS" ] && continue
        COORDS=$(echo "$BOUNDS" | tr -cd '0-9,][' | tr '][' ' ' | tr ',' ' ')
        set -- $COORDS
        [ $# -lt 4 ] && continue
        $ADB shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 )) >/dev/null 2>&1
        vlog "Dismissed \"$BUTTON\" dialog"
    done
}

vlog() {
    if $VERBOSE; then
        echo "       $*"
    fi
}

# ── Timeout helper (macOS has no `timeout`) ────────────────────────
# Usage: run_with_timeout SECONDS COMMAND [ARGS...]
# Returns the command's exit code, or 124 on timeout.
run_with_timeout() {
    local secs="$1"; shift
    "$@" &
    local cmd_pid=$!
    (sleep "$secs" && kill "$cmd_pid" 2>/dev/null) &
    local watcher_pid=$!
    wait "$cmd_pid" 2>/dev/null
    local rc=$?
    kill "$watcher_pid" 2>/dev/null
    wait "$watcher_pid" 2>/dev/null
    return $rc
}

# ── Captured state ─────────────────────────────────────────────────
SERVER_PORT=""
NATIVE_LIB_DIR=""
DATA_DIR=""  # resolved after device check via run-as pwd

# ── Header ─────────────────────────────────────────────────────────
printf "\n${BOLD}${CYAN}=== VSCodroid Device Test Suite ===${RESET}\n\n"

# ═══════════════════════════════════════════════════════════════════
# --self-check: can this suite still read what it asserts?
# ═══════════════════════════════════════════════════════════════════
# Nothing runs the suite automatically, so its own rot goes unseen until someone
# opens it -- which is how it came to demand Node v20.x two releases after the
# runtime moved. Reading the versions from the tree fixed that class and
# introduced a smaller one: the readings themselves can stop resolving when a
# source file is renamed or reformatted, and a derivation that yields nothing
# looks the same as one that has not run.
#
# This resolves each of them and fails when one comes back empty. It needs no
# device, so CI can run it, and it is the only part of this suite that can be
# automated at all.
if $SELF_CHECK; then
    printf "${BOLD}Self-check: expectations this suite derives${RESET}\n"

    NODE_V=$(derive_node_expected)
    if [ -n "$NODE_V" ]; then
        pass "node expectation ($NODE_V, from scripts/build-native-addons.sh)"
    else
        fail "node expectation" "NODE_VERSION unreadable in scripts/build-native-addons.sh"
    fi

    NPM_V=$(derive_npm_expected)
    if [ -n "$NPM_V" ]; then
        pass "npm expectation ($NPM_V)"
    else
        fail "npm expectation" \
            "neither the packaged tree nor NPM_VERSION in scripts/download-npm.sh is readable"
    fi

    # The only one with no source in the repository: the bundled Python version
    # comes from the Termux index at download time. Where the assets exist it is
    # checked; where they do not it is reported as unresolved rather than passed
    # over, because a skip nobody sees is what this suite exists to remove.
    PY_V=$(derive_py_expected)
    if [ -n "$PY_V" ]; then
        pass "python expectation ($PY_V, from the bundled libpython)"
    else
        skip "python expectation" \
            "no assets tree in this checkout; resolvable only where the build ran"
    fi

    printf "\n  ${GREEN}%d passed${RESET}, ${RED}%d failed${RESET}, ${YELLOW}%d skipped${RESET}\n\n" \
        "$PASS" "$FAIL" "$SKIP"
    [ "$FAIL" -eq 0 ] || printf "${RED}Failures:${RESET}%b\n\n" "$FAILURES"
    exit $([ "$FAIL" -eq 0 ] && echo 0 || echo 1)
fi

# ── Instrumented suite ─────────────────────────────────────────────
# The androidTest suite cannot run in CI, and that is measured rather than
# assumed -- see androidTest/README.md. CI compiles it, which is the most it can
# do; running it needs a person with an emulator, and this is the one command.
#
# The checks below are here because both failures they catch present as a
# timeout rather than as an error, which is the worst way for a test run to go
# wrong: an x86_64 emulator accepts the install and then has no arm64 library to
# load, and an incomplete asset tree yields an APK that builds, installs, opens,
# and dies with "error=2, No such file or directory" from a path nothing else
# points at. Gradle's own checkPatchFingerprints covers a different question --
# whether the server tree is CORRECT -- and skips entirely when the tree is
# absent, which is exactly the case this covers.
if $INSTRUMENTED; then
    printf "\n${BOLD}Preconditions for the instrumented suite${RESET}\n"

    ATTACHED=$(adb devices 2>/dev/null | awk 'NR>1 && $2=="device" {print $1}')
    if [ -z "$ATTACHED" ]; then
        fail "a device is attached" "no device in 'adb devices'; start an emulator first"
    elif [ -n "$DEVICE" ] && ! printf '%s\n' "$ATTACHED" | grep -qx "$DEVICE"; then
        fail "a device is attached" "--device $DEVICE is not among: $(printf '%s' "$ATTACHED" | tr '\n' ' ')"
    elif [ -z "$DEVICE" ] && [ "$(printf '%s\n' "$ATTACHED" | wc -l | tr -d ' ')" -gt 1 ]; then
        # Gradle would choose for itself and not say which, so a green run would
        # not name the API level it was green on -- and this machine keeps
        # emulators at three of them.
        fail "one target, or one named" \
            "attached: $(printf '%s' "$ATTACHED" | tr '\n' ' ') -- name one with --device SERIAL"
    else
        pass "a device is attached${DEVICE:+ ($DEVICE)}"

        if [ "$($ADB shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; then
            pass "it has finished booting"
        else
            fail "it has finished booting" "sys.boot_completed is not 1; an emulator mid-boot answers adb and nothing else"
        fi

        ABI=$($ADB shell getprop ro.product.cpu.abi 2>/dev/null | tr -d '\r')
        case "$ABI" in
            arm64*) pass "its ABI is $ABI" ;;
            "")     fail "its ABI is arm64-v8a" "could not read ro.product.cpu.abi" ;;
            *)      fail "its ABI is arm64-v8a" "this device reports $ABI; the app ships arm64-v8a only" ;;
        esac
    fi

    # The gitignored build output. A fresh worktree has none of it, nothing
    # fails, and the screen simply stays white -- documented in CONTRIBUTING.md
    # under the on-device suite.
    ASSETS="$ROOT_DIR/android/app/src/main/assets"
    LIBNODE="$ROOT_DIR/android/app/src/main/jniLibs/arm64-v8a/libnode.so"
    if [ ! -f "$LIBNODE" ]; then
        fail "the bundled runtime is present" "$LIBNODE is missing; run scripts/build-all.sh"
    elif [ "$(wc -c < "$LIBNODE" | tr -d ' ')" -lt 1000 ]; then
        fail "the bundled runtime is present" "$LIBNODE is the CI stub, not a runtime"
    else
        pass "the bundled runtime is present"
    fi
    if [ -f "$ASSETS/vscode-reh/out/server-main.js" ]; then
        pass "the server tree is present"
    else
        fail "the server tree is present" \
            "no assets/vscode-reh/out/server-main.js; run fetch-vscode-oss.sh then package-assets.sh"
    fi
    if [ -d "$ASSETS/usr" ]; then
        pass "the bundled tools are present"
    else
        fail "the bundled tools are present" "no assets/usr; run scripts/download-termux-tools.sh"
    fi

    if [ "$FAIL" -ne 0 ]; then
        printf "\n  ${RED}%d precondition(s) failed${RESET} — not starting the suite%b\n\n" \
            "$FAIL" "$FAILURES"
        exit 1
    fi
    # How --device reaches Gradle. Verified against AGP 8.9.1 rather than
    # assumed: DeviceProviderInstrumentTestTask calls System.getenv on this
    # name, and ConnectedDeviceProvider keeps it as androidSerialsEnv and
    # filters the attached devices by it -- its "Connected device with serial
    # '%s' not found!" is what an unattached serial produces.
    [ -n "$DEVICE" ] && export ANDROID_SERIAL="$DEVICE"

    printf "\n  ${GREEN}%d passed${RESET} — handing off to Gradle%s\n\n" \
        "$PASS" "${DEVICE:+ (ANDROID_SERIAL=$DEVICE)}"
    cd "$ROOT_DIR/android" || exit 1
    # exec so the suite's own exit status is this script's, with no wrapper
    # between a failing test and whoever is reading.
    exec ./gradlew connectedDebugAndroidTest
fi

# ═══════════════════════════════════════════════════════════════════
# TEST 1: device_connected
# ═══════════════════════════════════════════════════════════════════
printf "${BOLD}Phase 1: Setup${RESET}\n"

DEVICE_COUNT=$($ADB devices 2>/dev/null | grep -c "device$" || true)
if [ "$DEVICE_COUNT" -eq 0 ]; then
    fail "device_connected" "No Android device found"
    printf "\n${RED}Cannot continue without a device.${RESET}\n"
    exit 1
else
    pass "device_connected"
    vlog "$($ADB devices -l | grep 'device ' | head -1)"
fi

# ═══════════════════════════════════════════════════════════════════
# TEST 2: build_apk
# ═══════════════════════════════════════════════════════════════════
if $SKIP_BUILD; then
    if [ -f "$APK_PATH" ]; then
        skip "build_apk" "--skip-build (APK exists)"
    else
        fail "build_apk" "--skip-build but APK not found at $APK_PATH"
    fi
elif $SKIP_INSTALL; then
    skip "build_apk" "--skip-install"
else
    printf "  ...  Building APK (this may take a minute)\n"
    BUILD_OUT=$(cd "$ROOT_DIR/android" && ./gradlew assembleDebug 2>&1)
    if [ $? -eq 0 ] && [ -f "$APK_PATH" ]; then
        pass "build_apk"
    else
        fail "build_apk" "Gradle build failed"
        vlog "$BUILD_OUT"
    fi
fi

# ═══════════════════════════════════════════════════════════════════
# TEST 3: install_apk
# ═══════════════════════════════════════════════════════════════════
if $SKIP_INSTALL; then
    skip "install_apk" "--skip-install"
else
    INSTALL_OUT=$($ADB install -r "$APK_PATH" 2>&1)
    if echo "$INSTALL_OUT" | grep -q "Success"; then
        pass "install_apk"
    else
        fail "install_apk" "adb install failed"
        vlog "$INSTALL_OUT"
    fi
fi

# ═══════════════════════════════════════════════════════════════════
# TEST 4: clear_data
# ═══════════════════════════════════════════════════════════════════
if $SKIP_INSTALL; then
    skip "clear_data" "--skip-install"
else
    CLEAR_OUT=$($ADB shell pm clear "$PKG" 2>&1)
    if echo "$CLEAR_OUT" | grep -q "Success"; then
        pass "clear_data"
    else
        fail "clear_data" "pm clear failed"
        vlog "$CLEAR_OUT"
    fi
fi

# ═══════════════════════════════════════════════════════════════════
# TEST 5: launch_app
# ═══════════════════════════════════════════════════════════════════
printf "\n${BOLD}Phase 2: Launch & First Run${RESET}\n"

# Stop first. `am start` against a running instance just delivers the intent to
# it and produces no new log lines, so every wait below would sit until timeout
# reading an empty buffer — which looks exactly like the app failing to start.
$ADB shell am force-stop "$PKG" 2>/dev/null
sleep 2

# Clear logcat before launch so we only see fresh output
$ADB logcat -c 2>/dev/null

LAUNCH_OUT=$($ADB shell am start -n "$PKG/com.vscodroid.SplashActivity" 2>&1)
if echo "$LAUNCH_OUT" | grep -q "Error\|Exception"; then
    fail "launch_app" "am start failed"
    vlog "$LAUNCH_OUT"
else
    pass "launch_app"
fi

# ═══════════════════════════════════════════════════════════════════
# TEST 6: first_run_setup
# ═══════════════════════════════════════════════════════════════════
# Wait for first-run setup to complete.
# On first run: VSCodroid.FirstRunSetup logs "First-run setup completed"
# On subsequent runs: VSCodroid.SplashActivity logs "Not first run, launching main"
SETUP_TIMEOUT=$TIMEOUT
ELAPSED=0
SETUP_OK=false

while [ $ELAPSED -lt $SETUP_TIMEOUT ]; do
    LOGCAT=$($ADB logcat -d -s VSCodroid.SplashActivity:I VSCodroid.FirstRunSetup:I 2>/dev/null)
    if echo "$LOGCAT" | grep -q "setup completed\|launching main"; then
        SETUP_OK=true
        break
    fi
    dismiss_blocking_dialogs
    sleep 2
    ELAPSED=$((ELAPSED + 2))
    # Show progress every 10s
    if [ $((ELAPSED % 10)) -eq 0 ]; then
        vlog "Waiting for first-run setup... ${ELAPSED}s/${SETUP_TIMEOUT}s"
    fi
done

if $SETUP_OK; then
    pass "first_run_setup"
else
    fail "first_run_setup" "Did not complete within ${SETUP_TIMEOUT}s"
fi

# ═══════════════════════════════════════════════════════════════════
# TEST 7: server_ready
# ═══════════════════════════════════════════════════════════════════
# Wait for "Server is ready on port" or "Server ready after" in logcat
READY_TIMEOUT=60
ELAPSED=0
READY_OK=false

while [ $ELAPSED -lt $READY_TIMEOUT ]; do
    LOGCAT=$($ADB logcat -d -s VSCodroid.NodeService:I VSCodroid.ProcessManager:I 2>/dev/null)
    # Extract port from "Server is ready on port NNNN"
    PORT_LINE=$(echo "$LOGCAT" | grep -o "Server is ready on port [0-9]*" | tail -1)
    if [ -n "$PORT_LINE" ]; then
        SERVER_PORT=$(echo "$PORT_LINE" | grep -o '[0-9]*$')
        READY_OK=true
        break
    fi
    # Also check ProcessManager's "Server ready after Nms"
    if echo "$LOGCAT" | grep -q "Server ready after"; then
        # Try to get port from earlier log
        PORT_LINE2=$(echo "$LOGCAT" | grep -o "Starting server on port [0-9]*" | tail -1)
        if [ -n "$PORT_LINE2" ]; then
            SERVER_PORT=$(echo "$PORT_LINE2" | grep -o '[0-9]*$')
        else
            SERVER_PORT="13337"  # default
        fi
        READY_OK=true
        break
    fi
    sleep 2
    ELAPSED=$((ELAPSED + 2))
done

if $READY_OK && [ -n "$SERVER_PORT" ]; then
    pass "server_ready (port=$SERVER_PORT)"
else
    fail "server_ready" "Server did not become ready within ${READY_TIMEOUT}s"
fi

# ═══════════════════════════════════════════════════════════════════
# TEST 8: health_check
# ═══════════════════════════════════════════════════════════════════
if [ -n "$SERVER_PORT" ]; then
    # Forward device port to localhost
    $ADB forward tcp:$SERVER_PORT tcp:$SERVER_PORT 2>/dev/null
    # /version is answered before the connection-token check, so it probes
    # liveness without a token. "/" would answer 403 to this unauthenticated
    # curl, and the old 200-499 range counted that as a pass -- which means it
    # would have reported a healthy server while that server refused every
    # request it received. The range was too wide to distinguish "running" from
    # "reachable and saying no", and the token only made that visible.
    HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" "http://127.0.0.1:$SERVER_PORT/version" 2>/dev/null || true)
    if [ "$HTTP_CODE" = "200" ]; then
        pass "health_check (HTTP $HTTP_CODE)"
    else
        fail "health_check" "Expected 200, got $HTTP_CODE"
    fi
    # Remove port forward
    $ADB forward --remove tcp:$SERVER_PORT 2>/dev/null
else
    skip "health_check" "No server port"
fi

# ═══════════════════════════════════════════════════════════════════
# TESTS 9-14: symlink checks
# ═══════════════════════════════════════════════════════════════════
printf "\n${BOLD}Phase 3: Tool Symlinks${RESET}\n"

# Resolve the app data directory (run-as cwd)
DATA_DIR=$($ADB shell run-as "$PKG" pwd 2>/dev/null | tr -d '\r\n')
vlog "dataDir=$DATA_DIR"

# Discover nativeLibraryDir by reading a symlink target
NODE_LINK=$($ADB shell run-as "$PKG" ls -la files/usr/bin/node 2>/dev/null)
NATIVE_LIB_DIR=$(echo "$NODE_LINK" | grep -o '/data/app/[^ ]*lib/arm64' | head -1)
vlog "nativeLibraryDir=$NATIVE_LIB_DIR"

check_symlink() {
    local name="$1"
    local target_name="$2"
    local link_out
    link_out=$($ADB shell run-as "$PKG" ls -la "files/usr/bin/$name" 2>/dev/null)
    if echo "$link_out" | grep -q "$target_name"; then
        pass "symlink_$name"
    else
        fail "symlink_$name" "Symlink missing or wrong target"
        vlog "$link_out"
    fi
}

check_symlink "bash"    "libbash.so"
check_symlink "node"    "libnode.so"
check_symlink "git"     "libgit.so"
check_symlink "python3" "libpython.so"
check_symlink "rg"      "libripgrep.so"
check_symlink "ssh"     "libssh.so"

# ═══════════════════════════════════════════════════════════════════
# TESTS 15-19: Tool version checks
# ═══════════════════════════════════════════════════════════════════
printf "\n${BOLD}Phase 4: Tool Versions${RESET}\n"

# Helper: run a tool inside the app sandbox with proper env
run_tool() {
    local tool_path="$1"; shift
    $ADB shell run-as "$PKG" env \
        "HOME=$DATA_DIR/files/home" \
        "PATH=$DATA_DIR/files/usr/bin:$NATIVE_LIB_DIR" \
        "LD_LIBRARY_PATH=$DATA_DIR/files/usr/lib:$NATIVE_LIB_DIR" \
        "PYTHONHOME=$DATA_DIR/files/usr" \
        "GIT_EXEC_PATH=$DATA_DIR/files/usr/libexec/git-core" \
        "$tool_path" "$@" 2>&1
}

# run_tool_code TOOL CODE — for a `-c` argument, which contains spaces.
#
# `adb shell` joins its arguments with a space and hands the result to the
# device's shell, so quoting applied on this side is consumed here and never
# reaches the device. `run_tool python3 -c "import bz2"` arrives as
# `python3 -c import bz2`, which is a syntax error with bz2 as argv[1], and
# `bash -c "echo ok"` runs echo with no arguments at all. Measured against a
# device rather than assumed: `adb shell sh -c echo hello` prints an empty line.
#
# So the code is quoted for the remote shell instead. That breaks if the code
# itself contains a single quote, which is a real limit rather than a
# theoretical one, so it is refused rather than silently mis-parsed.
run_tool_code() {
    local tool_path="$1" code="$2"
    case "$code" in
        *"'"*)
            echo "run_tool_code: code must not contain a single quote: $code" >&2
            return 2 ;;
    esac
    run_tool "$tool_path" -c "'$code'"
}

# Test 15: node
NODE_OUT=$(run_tool "files/usr/bin/node" --version)
# Derived, not pinned: this asserted v20.x while the runtime moved to 24.18.0,
# so it would have failed every build had anything run it. See
# derive_node_expected for where the number comes from.
NODE_EXPECTED=$(derive_node_expected)
if [ -z "$NODE_EXPECTED" ]; then
    fail "tool_node" "could not read NODE_VERSION from build-native-addons.sh"
elif echo "$NODE_OUT" | grep -q "^v${NODE_EXPECTED}$"; then
    pass "tool_node ($NODE_OUT)"
else
    fail "tool_node" "Expected v$NODE_EXPECTED (what the addons are built against), got: $NODE_OUT"
fi

# Test 16: npm (via node + npm-cli.js)
NPM_OUT=$($ADB shell run-as "$PKG" env \
    "HOME=$DATA_DIR/files/home" \
    "PATH=$DATA_DIR/files/usr/bin:$NATIVE_LIB_DIR" \
    "LD_LIBRARY_PATH=$DATA_DIR/files/usr/lib:$NATIVE_LIB_DIR" \
    files/usr/bin/node files/usr/lib/node_modules/npm/bin/npm-cli.js --version 2>&1)
# Was pinned to "10.x", which happened to be right; being right today is exactly
# what the node assertion above was until it wasn't. See derive_npm_expected.
NPM_EXPECTED=$(derive_npm_expected)
if [ -z "$NPM_EXPECTED" ]; then
    fail "tool_npm" "could not determine the expected npm version from tree or script"
elif [ "$NPM_OUT" = "$NPM_EXPECTED" ]; then
    pass "tool_npm ($NPM_OUT)"
else
    fail "tool_npm" "Expected $NPM_EXPECTED (the version in the tree), got: $NPM_OUT"
fi

# Test 17: python3
PYTHON_OUT=$(run_tool "files/usr/bin/python3" --version)
# See derive_py_expected: there is no number in this repository to compare
# against, only the library that actually shipped.
PY_EXPECTED=$(derive_py_expected)
if [ -z "$PY_EXPECTED" ]; then
    skip "tool_python" "no bundled libpython to compare against"
elif echo "$PYTHON_OUT" | grep -q "Python ${PY_EXPECTED}"; then
    pass "tool_python ($PYTHON_OUT)"
else
    fail "tool_python" "Expected Python ${PY_EXPECTED}.x, got: $PYTHON_OUT"
fi

# Test 18: git
GIT_OUT=$(run_tool "files/usr/bin/git" --version)
if echo "$GIT_OUT" | grep -q "git version 2\."; then
    pass "tool_git ($GIT_OUT)"
else
    fail "tool_git" "Expected git 2.x, got: $GIT_OUT"
fi

# Test 19: rg (ripgrep)
RG_OUT=$(run_tool "files/usr/bin/rg" --version)
if echo "$RG_OUT" | grep -q "ripgrep"; then
    pass "tool_rg ($(echo "$RG_OUT" | head -1))"
else
    fail "tool_rg" "Expected ripgrep, got: $RG_OUT"
fi

# Test 19b: bash actually starts
BASH_OUT=$(run_tool_code "files/usr/bin/bash" "echo bash-ok")
if [ "$BASH_OUT" = "bash-ok" ]; then
    pass "tool_bash"
else
    fail "tool_bash" "Expected bash-ok, got: $BASH_OUT"
fi

# Test 19c: the Python modules that need a shared library behind them.
#
# `python3 --version` above proves the interpreter starts and nothing more. Each
# of these is a separate .so that has to have been bundled and found, and every
# one of them was broken on a shipped build at some point while every gate
# stayed green: bz2, lzma and dbm.gnu had no library bundled at all, and two
# more were bundled under a name nothing looks for. An import is the only thing
# that distinguishes "present" from "loads".
#
# Imported one per line so the failure names the module. A single combined
# import would report only the first one to break.
PY_MODULE_FAILURES=""
for module in bz2 lzma sqlite3 ssl ctypes curses.panel dbm.gnu zlib readline pip; do
    MOD_OUT=$(run_tool_code "files/usr/bin/python3" "import $module")
    MOD_RC=$?
    if [ "$MOD_RC" -ne 0 ] || [ -n "$MOD_OUT" ]; then
        PY_MODULE_FAILURES="$PY_MODULE_FAILURES $module"
        vlog "import $module: $MOD_OUT"
    fi
done
if [ -z "$PY_MODULE_FAILURES" ]; then
    pass "python_modules (10 imported)"
else
    fail "python_modules" "failed to import:$PY_MODULE_FAILURES"
fi

# Test 19d: git's HTTPS remote helper can be executed at all.
#
# SELinux denies execute_no_trans on app_data_file, so a helper that is a real
# file under filesDir cannot be exec'd by the app no matter what its mode bits
# say -- that is what broke `git clone https://` until the binary moved to
# nativeLibraryDir and filesDir kept only a symlink to it. Invoked with no
# arguments it exits complaining about usage; what matters is that it got far
# enough to complain. 126 and "Permission denied" are the regression.
#
# Note this runs through `run-as`, which is a different SELinux domain than the
# app (runas_app against untrusted_app) and is more permissive about exec. So a
# pass here is weaker than it looks: it catches the helper going missing or
# losing its mode bits, not a domain refusal. Only the app's own terminal can
# answer that, which is why the symlink shape is asserted separately above.
HELPER_OUT=$(run_tool "files/usr/libexec/git-core/git-remote-https" 2>&1)
HELPER_RC=$?
if [ "$HELPER_RC" -eq 126 ] || echo "$HELPER_OUT" | grep -qi "permission denied"; then
    fail "git_remote_helper" "cannot execute git-remote-https (rc=$HELPER_RC): $HELPER_OUT"
else
    pass "git_remote_helper (executed, rc=$HELPER_RC)"
fi

# ═══════════════════════════════════════════════════════════════════
# TESTS 20-23: Runtime checks
# ═══════════════════════════════════════════════════════════════════
printf "\n${BOLD}Phase 5: Runtime${RESET}\n"

# Test 20: process_count
PROC_LIST=$($ADB shell ps -A 2>/dev/null | grep "$PKG" || true)
PROC_COUNT=$(echo "$PROC_LIST" | grep -c "$PKG" || true)
if [ "$PROC_COUNT" -lt 10 ]; then
    pass "process_count ($PROC_COUNT processes)"
else
    fail "process_count" "Expected <10, got $PROC_COUNT"
fi
vlog "$(echo "$PROC_LIST" | head -5)"

# Test 21: extensions_manifest
EXT_JSON=$($ADB shell run-as "$PKG" cat "files/home/.vscodroid/extensions/extensions.json" 2>/dev/null || true)
if echo "$EXT_JSON" | grep -q '"identifier"'; then
    # grep -c counts matching *lines*, and VS Code rewrites this file as a single
    # line, so it reported 1 no matter how many extensions were installed — which
    # reads as a catastrophic loss when comparing two runs. Count occurrences.
    EXT_COUNT=$(echo "$EXT_JSON" | grep -o '"identifier"' | wc -l | tr -d ' ')
    pass "extensions_manifest ($EXT_COUNT extensions)"
else
    fail "extensions_manifest" "No extensions found in manifest"
fi

# Test 22: settings_json
# FirstRunSetup writes settings to data/Machine/ (Environment.getMachineSettingsPath)
# because the workbench never reads a server-side User/ file, and
# migrateSettingsToMachinePath() deletes any legacy User/ copy on every launch —
# so probing User/ fails on exactly the healthy devices.
SETTINGS_CHECK=$($ADB shell "run-as $PKG sh -c 'test -f files/home/.vscodroid/data/Machine/settings.json && echo EXISTS'" 2>/dev/null)
if echo "$SETTINGS_CHECK" | grep -q "EXISTS"; then
    pass "settings_json"
else
    fail "settings_json" "settings.json not found"
fi

# Test 23: file_creation (uses home dir, not projects/ which may be an external symlink)
TEST_FILE="files/home/.vscodroid-test-$$"
FILE_CHECK=$($ADB shell "run-as $PKG sh -c 'touch $TEST_FILE && test -f $TEST_FILE && echo EXISTS'" 2>/dev/null)
if echo "$FILE_CHECK" | grep -q "EXISTS"; then
    pass "file_creation"
    $ADB shell "run-as $PKG rm $TEST_FILE" 2>/dev/null
else
    fail "file_creation" "Could not create/verify file"
fi

# ═══════════════════════════════════════════════════════════════════
# Summary
# ═══════════════════════════════════════════════════════════════════
TOTAL=$((PASS + FAIL + SKIP))

printf "\n${BOLD}${CYAN}═══════════════════════════════════${RESET}\n"
printf "${BOLD}Results: ${GREEN}$PASS passed${RESET}, "
if [ $FAIL -gt 0 ]; then
    printf "${RED}$FAIL failed${RESET}, "
else
    printf "0 failed, "
fi
printf "${YELLOW}$SKIP skipped${RESET} / $TOTAL total\n"

if [ $FAIL -gt 0 ]; then
    printf "\n${RED}Failures:${RESET}"
    printf "$FAILURES\n"
    printf "\n"
    exit 1
else
    printf "\n${GREEN}All tests passed!${RESET}\n\n"
    exit 0
fi
