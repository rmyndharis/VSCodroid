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
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"
APK_PATH="$ROOT_DIR/android/app/build/outputs/apk/debug/app-debug.apk"
PKG="com.vscodroid.debug"

# Defaults
SKIP_BUILD=false
SKIP_INSTALL=false
DEVICE=""
VERBOSE=false
TIMEOUT=120

# Parse args
while [ $# -gt 0 ]; do
    case "$1" in
        --skip-build)  SKIP_BUILD=true ;;
        --skip-install) SKIP_INSTALL=true ;;
        --device)      DEVICE="$2"; shift ;;
        --verbose)     VERBOSE=true ;;
        --timeout)     TIMEOUT="$2"; shift ;;
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

# ── Header ─────────────────────────────────────────────────────────
printf "\n${BOLD}${CYAN}=== VSCodroid Device Test Suite ===${RESET}\n\n"

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
# Wait for "launching main" in logcat (SplashActivity completed)
SETUP_TIMEOUT=$TIMEOUT
ELAPSED=0
SETUP_OK=false

while [ $ELAPSED -lt $SETUP_TIMEOUT ]; do
    LOGCAT=$($ADB logcat -d -s SplashActivity:I 2>/dev/null)
    if echo "$LOGCAT" | grep -q "launching main"; then
        SETUP_OK=true
        break
    fi
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
    LOGCAT=$($ADB logcat -d -s NodeService:I ProcessManager:I 2>/dev/null)
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
    HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" "http://127.0.0.1:$SERVER_PORT/" 2>/dev/null || true)
    if [ -n "$HTTP_CODE" ] && [ "$HTTP_CODE" -ge 200 ] 2>/dev/null && [ "$HTTP_CODE" -lt 500 ] 2>/dev/null; then
        pass "health_check (HTTP $HTTP_CODE)"
    else
        fail "health_check" "Expected 200-499, got $HTTP_CODE"
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
        "HOME=\$(pwd)/files/home" \
        "PATH=\$(pwd)/files/usr/bin:$NATIVE_LIB_DIR" \
        "LD_LIBRARY_PATH=\$(pwd)/files/usr/lib:$NATIVE_LIB_DIR" \
        "PYTHONHOME=\$(pwd)/files/usr" \
        "GIT_EXEC_PATH=\$(pwd)/files/usr/libexec/git-core" \
        "$tool_path" "$@" 2>&1
}

# Test 15: node
NODE_OUT=$(run_tool "files/usr/bin/node" --version)
if echo "$NODE_OUT" | grep -q "^v20\."; then
    pass "tool_node ($NODE_OUT)"
else
    fail "tool_node" "Expected v20.x, got: $NODE_OUT"
fi

# Test 16: npm (via node + npm-cli.js)
NPM_OUT=$($ADB shell run-as "$PKG" env \
    "HOME=\$(pwd)/files/home" \
    "PATH=\$(pwd)/files/usr/bin:$NATIVE_LIB_DIR" \
    "LD_LIBRARY_PATH=\$(pwd)/files/usr/lib:$NATIVE_LIB_DIR" \
    files/usr/bin/node files/usr/lib/node_modules/npm/bin/npm-cli.js --version 2>&1)
if echo "$NPM_OUT" | grep -q "^10\."; then
    pass "tool_npm ($NPM_OUT)"
else
    fail "tool_npm" "Expected 10.x, got: $NPM_OUT"
fi

# Test 17: python3
PYTHON_OUT=$(run_tool "files/usr/bin/python3" --version)
if echo "$PYTHON_OUT" | grep -q "Python 3\.12"; then
    pass "tool_python ($PYTHON_OUT)"
else
    fail "tool_python" "Expected Python 3.12.x, got: $PYTHON_OUT"
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
EXT_JSON=$($ADB shell run-as "$PKG" cat "files/.vscodroid/extensions/extensions.json" 2>/dev/null || true)
if echo "$EXT_JSON" | grep -q '"identifier"'; then
    EXT_COUNT=$(echo "$EXT_JSON" | grep -c '"identifier"' || true)
    pass "extensions_manifest ($EXT_COUNT extensions)"
else
    fail "extensions_manifest" "No extensions found in manifest"
fi

# Test 22: settings_json
SETTINGS_CHECK=$($ADB shell run-as "$PKG" test -f "files/.vscodroid/data/Machine/settings.json" 2>&1; echo "EXIT:$?")
if echo "$SETTINGS_CHECK" | grep -q "EXIT:0"; then
    pass "settings_json"
else
    fail "settings_json" "settings.json not found"
fi

# Test 23: file_creation
TEST_FILE="files/home/projects/.vscodroid-test-$$"
$ADB shell run-as "$PKG" touch "$TEST_FILE" 2>/dev/null
FILE_CHECK=$($ADB shell run-as "$PKG" test -f "$TEST_FILE" 2>&1; echo "EXIT:$?")
if echo "$FILE_CHECK" | grep -q "EXIT:0"; then
    pass "file_creation"
    $ADB shell run-as "$PKG" rm "$TEST_FILE" 2>/dev/null
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
