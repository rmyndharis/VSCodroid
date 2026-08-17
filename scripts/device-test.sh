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
#     FirstRunSetup -- --instrumented, which is the only thing that runs them on
#     a device. The JVM suite reaches parts of all five; what it never does is
#     start the app.
#
# This suite once demanded Node v20.x for two releases after the runtime moved
# to 24.18.0. Nothing caught it, because nothing ran it. The versions it checks
# are now read from the tree instead of written here, and --self-check verifies
# those readings still resolve -- but neither of those is a substitute for
# running it.
#
# WHAT IT CANNOT ANSWER
#
# Every check below either runs through `run-as` or reads a file. `run-as` is a
# different SELinux domain (runas_app, not untrusted_app) and is allowed to
# execute files the app itself is refused, so no result here is evidence that a
# command works in the app's own terminal. Phase 7 measures the app's own exec
# path once, indirectly, by looking for a bash process the app spawned.
#
# Nothing here can drive the workbench: it lives in a WebView, and the editor,
# the terminal's contents, the extension marketplace and the SAF picker are all
# out of reach. Those are what docs/DEVICE_TEST_CHECKLIST.md is for. Where a
# check cannot run, it reports SKIP and says why. A phase that reports PASS for
# something it did not run is worse than one that admits it, and a phase that
# reports FAIL for something it could not read is worse still: it blames the
# device for the workstation, and it teaches the reader to skim past red. So
# where an instrument can simply be absent from this host, its silence is
# reported as its own absence: curl, python3, `ps`, the dumpsys sections and
# `input keycombination` each say whether they can see anything at all before
# their answer is read as a verdict about the device.

# The blank line above is where -h stops: it prints `sed -n '2,/^$/'`, so the
# first empty line in the file bounds the help text. Keep the header one
# unbroken comment block, or everything after the break silently stops being
# printed by -h.
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

derive_toolchain_release_urls() {
    # Where a non-Play install gets its toolchains. Read from the registry the
    # app itself ships rather than written here, for the same reason as every
    # other expectation above: these URLs are the only copy that matters, and a
    # second one in this file would be free to drift from it.
    sed -n 's/.*downloadUrl = "\(https:[^"]*\)".*/\1/p' \
        "$ROOT_DIR/android/app/src/main/kotlin/com/vscodroid/setup/ToolchainRegistry.kt"
}

# ── Readers of files the app writes ────────────────────────────────
# python3 is an instrument like adb or curl, and its absence is a fact about
# this host, never evidence about the device. Both readers below consult this
# flag instead of probing for themselves, so --self-check can drive the
# python-less path on a machine that does have python3.
if command -v python3 >/dev/null 2>&1; then
    HAVE_PYTHON3=true
else
    HAVE_PYTHON3=false
fi

read_toolchain_names() {
    # Reads toolchains.json on stdin and prints the installed names, space
    # separated. Non-zero exit means "python3 read this file and could not parse
    # it", which is the only condition that is evidence of damage. Without
    # python3 the names come from grep and no parse verdict is offered at all:
    # reporting the app's record as unreadable JSON because the host has no
    # interpreter blames the device for the workstation. Same fallback shape as
    # derive_npm_expected().
    if $HAVE_PYTHON3; then
        python3 -c "
import json, sys
try:
    entries = json.load(sys.stdin)
except Exception:
    sys.exit(1)
print(' '.join(e.get('name', '?') for e in entries))
"
    else
        { grep -o '"name"[[:space:]]*:[[:space:]]*"[^"]*"' || true; } \
            | sed 's/.*"\([^"]*\)"$/\1/' | tr '\n' ' ' | sed 's/ *$//'
    fi
}

read_terminal_profile_path() {
    # Reads settings.json on stdin and prints the bash profile's path. The file
    # is JSONC once the workbench has rewritten it, so a plain parse is tried
    # first and a regex is the fallback rather than the method. The regex has a
    # shell twin here, outside the python program, because inside it it could
    # not help with the one thing that silences both: no python3 on the host.
    # Both take the first "bash" object in the file, so the two readings of the
    # same bytes cannot disagree about which profile they are reporting.
    if $HAVE_PYTHON3; then
        python3 -c "
import json, re, sys
raw = sys.stdin.read()
try:
    profiles = json.loads(raw).get('terminal.integrated.profiles.linux', {})
    print(profiles.get('bash', {}).get('path', ''))
except Exception:
    m = re.search(r'\"bash\"\s*:\s*\{[^}]*?\"path\"\s*:\s*\"([^\"]+)\"', raw, re.S)
    print(m.group(1) if m else '')
"
    else
        tr -d '\n' \
            | { grep -o '"bash"[[:space:]]*:[[:space:]]*{[^}]*}' || true; } \
            | head -1 \
            | sed -n 's|.*"path"[[:space:]]*:[[:space:]]*"\([^"]*\)".*|\1|p'
    fi
}

extract_wrapper_targets() {
    # Reads toolchain-env.sh on stdin and prints the file each wrapper hands to
    # the loader, one per line, deduplicated. PREFIX is filesDir/usr
    # (Environment.kt), so $PREFIX/.. is filesDir.
    #
    # Anchored on the whole generated wrapper line rather than on the
    # "$PREFIX/../" substring alone. ToolchainManager.regenerateEnvFileLocked()
    # writes that substring into three shapes and only the first names a file:
    # a wrapper function; an `export` whose value may hold several entries
    # (RUBYLIB does); and the `export PATH=` list it appends whenever a manifest
    # declares pathDirs. Matching the substring took all three, and the two that
    # are lists come back carrying a `:` and a second unexpanded $PREFIX. That is
    # not a path, so it can never be on disk, so the phase reported the payload
    # missing on an install where every wrapper target was present.
    sed -n 's|^[^ "]*() { [^"]* "\$PREFIX/\.\./\([^"]*\)" "\$@"; }$|\1|p' | sort -u
}

derive_wrapper_line_samples() {
    # The wrapper lines ToolchainManager.regenerateEnvFileLocked() writes,
    # rendered from the generator's own Kotlin string literals with sample
    # values substituted for the interpolations. extract_wrapper_targets is
    # asserted against these rather than against a copy of the format kept
    # here, so a change to how the generator spells a wrapper turns up as a
    # failed --self-check instead of as a phase that skips forever.
    awk '
/\$PREFIX\/\.\.\// && (/lines\.add\(/ || /sb\.appendLine\(/) {
    line = $0
    sub(/^[^(]*\("/, "", line)
    sub(/"\)[ \t]*$/, "", line)
    gsub(/\\"/, "\"", line)
    gsub(/\\\$/, "@@DOLLAR@@", line)
    n = 0
    while (match(line, /\$\{[^}]*\}|\$[A-Za-z_][A-Za-z0-9_]*/)) {
        n++
        repl = (n == 1) ? "sample" : (n == 2) ? "/system/bin/loader" : "usr/lib/sample/bin/sample"
        line = substr(line, 1, RSTART - 1) repl substr(line, RSTART + RLENGTH)
    }
    gsub(/@@DOLLAR@@/, "$", line)
    print line
}
' "$ROOT_DIR/android/app/src/main/kotlin/com/vscodroid/setup/ToolchainManager.kt"
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

    # Not reachability -- that needs a network and belongs to a device run. This
    # is the reading itself: a rename or a reformat of ToolchainRegistry.kt would
    # otherwise leave the toolchain phase checking an empty list of URLs and
    # calling it clean.
    TC_URLS=$(derive_toolchain_release_urls)
    TC_URL_COUNT=$(printf '%s\n' "$TC_URLS" | grep -c 'https' || true)
    if [ "$TC_URL_COUNT" -gt 0 ]; then
        pass "toolchain release URLs ($TC_URL_COUNT, from ToolchainRegistry.kt)"
    else
        fail "toolchain release URLs" \
            "no downloadUrl readable in ToolchainRegistry.kt; the toolchain phase would check nothing"
    fi

    # The three readings the device phases make of files the app writes. Each is
    # asserted for the same reason as the URLs above: a reading that stops
    # matching does not announce itself, it turns into a permanent SKIP or into
    # a verdict about the wrong thing.
    #
    # The sample below is one wrapper line per shape the generator emits,
    # rendered from ToolchainManager.kt itself, followed by the two shapes that
    # carry the same "$PREFIX/../" substring and must be ignored: a multi-valued
    # export (ToolchainManager.kt writes RUBYLIB this way) and the PATH list it
    # appends whenever a manifest declares pathDirs. Only the first kind names a
    # file, and a reader that returns any of the others reports a healthy
    # install as a payload that is gone.
    WRAP_SAMPLE=$(derive_wrapper_line_samples)
    WRAP_RENDERED=$(printf '%s\n' "$WRAP_SAMPLE" | grep -c . || true)
    WRAP_INPUT=$(printf '%s\nexport RUBYLIB="$PREFIX/../usr/lib/ruby/3.4.0:$PREFIX/../usr/lib/ruby/3.4.0/aarch64-linux-android"\nexport PATH="$PREFIX/../usr/bin:$PATH"\n' "$WRAP_SAMPLE")
    WRAP_GOT=$(printf '%s\n' "$WRAP_INPUT" | extract_wrapper_targets | tr '\n' ' ' | sed 's/ *$//')
    if [ "$WRAP_RENDERED" -eq 0 ]; then
        fail "wrapper target reading" \
            "no wrapper line readable in ToolchainManager.kt; the toolchain payload check would have nothing to compare against"
    elif [ "$WRAP_GOT" = "usr/lib/sample/bin/sample" ]; then
        pass "wrapper target reading ($WRAP_RENDERED generated shapes, exports and PATH ignored)"
    else
        fail "wrapper target reading" \
            "reading ToolchainManager.kt's own wrapper lines yielded '$WRAP_GOT', not the single file they name"
    fi

    # Read twice on purpose: as this host will run it, and again on a host with
    # no python3. The second run is not just a flag flip. It also shadows python3
    # with a function that fails the way a missing command does, so a reader that
    # reaches for the interpreter anyway comes back empty and this goes red.
    # Without that, the flag alone would be satisfied by a reader that ignores it
    # entirely, and the fallback would be asserted without ever being run.
    TC_SAMPLE='[{"name":"go","displayName":"Go"},{"name":"ruby","displayName":"Ruby"}]'
    TC_NAMES_PY=$(printf '%s' "$TC_SAMPLE" | read_toolchain_names)
    TC_NAMES_NOPY=$(
        HAVE_PYTHON3=false
        python3() { return 127; }
        printf '%s' "$TC_SAMPLE" | read_toolchain_names
    )
    if [ "$TC_NAMES_PY" = "go ruby" ] && [ "$TC_NAMES_NOPY" = "go ruby" ]; then
        pass "toolchains.json reading (with python3, and with none)"
    else
        fail "toolchains.json reading" \
            "expected 'go ruby' from both readings, got '$TC_NAMES_PY' with python3 and '$TC_NAMES_NOPY' without"
    fi

    ST_SAMPLE='{"terminal.integrated.defaultProfile.linux": "bash",
    "terminal.integrated.profiles.linux": {
        "bash": { "path": "/data/user/0/p/files/usr/bin/bash", "args": [], "icon": "terminal-bash" }
    }}'
    ST_WANT="/data/user/0/p/files/usr/bin/bash"
    ST_PATH_PY=$(printf '%s' "$ST_SAMPLE" | read_terminal_profile_path)
    ST_PATH_NOPY=$(
        HAVE_PYTHON3=false
        python3() { return 127; }
        printf '%s' "$ST_SAMPLE" | read_terminal_profile_path
    )
    if [ "$ST_PATH_PY" = "$ST_WANT" ] && [ "$ST_PATH_NOPY" = "$ST_WANT" ]; then
        pass "settings.json profile reading (with python3, and with none)"
    else
        fail "settings.json profile reading" \
            "expected '$ST_WANT' from both readings, got '$ST_PATH_PY' with python3 and '$ST_PATH_NOPY' without"
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

    # This used to `exec`, so that the suite's exit status was this script's with
    # no wrapper between a failing test and whoever is reading. That property is
    # kept below by exiting with the captured status; what `exec` also did was
    # make it impossible to write anything afterwards, and a run that leaves no
    # trace is a run nobody can be asked to have done.
    #
    # Nothing runs this suite automatically and no runner measured here can, so
    # the only thing between a release and an unrun suite is a person
    # remembering. A record does not make anyone run it. It makes the answer to
    # "was it run, on what, and when" a file rather than a recollection.
    ./gradlew connectedDebugAndroidTest
    GRADLE_STATUS=$?

    RECORD="$ROOT_DIR/android/app/build/reports/device-run.txt"
    mkdir -p "$(dirname "$RECORD")"
    # Counts come from the XML the run just wrote, not from Gradle's log. A
    # stale report from an earlier run says "0 failures" and is
    # indistinguishable from success, which is the trap the mutation harness
    # documents for the same reason.
    RESULT_DIR="$ROOT_DIR/android/app/build/outputs/androidTest-results/connected"
    COUNTS=$(python3 - "$RESULT_DIR" <<'PY' 2>/dev/null || echo "counts unavailable"
import glob, os, re, sys
t = f = e = s = n = 0
for p in glob.glob(os.path.join(sys.argv[1], "**", "*.xml"), recursive=True):
    with open(p, encoding="utf-8", errors="replace") as fh:
        head = fh.read(800)
    m = re.search(r'tests="(\d+)".*?failures="(\d+)".*?errors="(\d+)"', head, re.S)
    if m:
        n += 1
        t += int(m.group(1)); f += int(m.group(2)); e += int(m.group(3))
    m = re.search(r'skipped="(\d+)"', head)
    if m:
        s += int(m.group(1))
# Zeros are the answer both to "nothing failed" and to "nothing ran", and only
# one of those is good news. Say which, rather than printing a clean-looking
# line for a suite that never started.
if n == 0:
    print("no result files: the suite wrote nothing, so it did not run")
elif t == 0:
    print(f"{n} result file(s) but 0 tests: the suite ran nothing")
else:
    print(f"tests={t} failures={f} errors={e} skipped={s}")
PY
)
    FINGERPRINT=$($ADB ${DEVICE:+-s "$DEVICE"} shell getprop ro.build.fingerprint 2>/dev/null | tr -d '\r')
    {
        printf 'instrumented suite\n'
        printf '  when        %s\n' "$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
        printf '  commit      %s\n' "$(git -C "$ROOT_DIR" rev-parse --short HEAD 2>/dev/null)"
        printf '  device      %s\n' "${FINGERPRINT:-unknown}"
        printf '  gradle exit %s\n' "$GRADLE_STATUS"
        printf '  %s\n' "$COUNTS"
    } > "$RECORD"

    printf '\n'
    cat "$RECORD"
    printf '\n  recorded in %s\n\n' "${RECORD#"$ROOT_DIR"/}"
    exit "$GRADLE_STATUS"
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
# The pass needs positive evidence that the helper ran, not merely the absence
# of one particular failure. Testing only for 126 meant every other way of not
# running took the pass branch: a helper that is gone answers 127 with "not
# found", and `run-as` refused on a non-debuggable build answers with its own
# error and no helper output at all. Both printed PASS while measuring nothing,
# and "the helper going missing" is one of the two regressions this test names
# as its reason for existing.
HELPER_OUT=$(run_tool "files/usr/libexec/git-core/git-remote-https" 2>&1)
HELPER_RC=$?
if [ "$HELPER_RC" -eq 126 ] || echo "$HELPER_OUT" | grep -qi "permission denied"; then
    fail "git_remote_helper" "cannot execute git-remote-https (rc=$HELPER_RC): $HELPER_OUT"
elif [ "$HELPER_RC" -eq 127 ] || echo "$HELPER_OUT" | grep -qiE "not found|no such file"; then
    fail "git_remote_helper" "git-remote-https is not there (rc=$HELPER_RC): $HELPER_OUT"
elif echo "$HELPER_OUT" | grep -qi "run-as"; then
    fail "git_remote_helper" "run-as refused, so nothing was measured: $HELPER_OUT"
elif [ -z "$HELPER_OUT" ]; then
    # Invoked with no arguments the helper complains about usage. Silence means
    # something other than the helper answered.
    fail "git_remote_helper" "no output at all (rc=$HELPER_RC); the helper did not run"
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
# TESTS 24-29: Toolchains
# ═══════════════════════════════════════════════════════════════════
printf "\n${BOLD}Phase 6: Toolchains${RESET}\n"

# What this phase answers, and what it deliberately does not:
#
#   * It can read what an install left behind -- the record, the generated
#     environment, and whether the payload those name is still on disk -- and it
#     can ask whether the screen that installs one is reachable at all.
#   * It cannot run a toolchain. `go`, `ruby` and `java` are bash *functions*
#     defined in toolchain-env.sh, which only an interactive shell has sourced,
#     and `run-as` is a different SELinux domain (runas_app, not untrusted_app)
#     that may execute files the app itself is refused. A version string
#     obtained through it would be evidence about run-as. Checklist rows TC-4
#     and TC-5 stay a person's job.
#   * It cannot drive an install. ToolchainActivity is not exported, so `am
#     start` from the shell uid is refused, and the first-run picker is shown
#     once per install.
#
# Consequence for a default run: `pm clear` empties filesDir, so nothing is
# installed and the installed-state checks report SKIP with that reason. To make
# them measure anything, install a toolchain by hand and re-run with
# --skip-install.

# Test 24: the toolchain screen has a way in.
#
# It has exactly one: the dynamic launcher shortcut publishToolchainShortcut()
# pushes from launchMain(). The activity is not exported and no bundled
# extension sends openToolchainSettings, so if the push is refused -- rate
# limiting is the documented case -- the screen becomes unreachable and only a
# log line says so. Read rather than launched, because the shortcut cannot be
# started from the shell uid either.
#
# The control is the dump having any Package section at all: without one, this
# cannot tell "no shortcut" from "cannot see shortcuts on this Android version".
SHORTCUT_DUMP=$($ADB shell dumpsys shortcut 2>/dev/null | tr -d '\r')
if ! echo "$SHORTCUT_DUMP" | grep -q "Package:"; then
    skip "toolchain_shortcut" \
        "dumpsys shortcut listed no packages here, so an empty result proves nothing"
else
    PKG_SHORTCUTS=$(echo "$SHORTCUT_DUMP" | awk -v pkg="$PKG" '
        /Package:/ { inpkg = ($2 == pkg) }
        inpkg { print }
    ')
    if echo "$PKG_SHORTCUTS" | grep -q "id=toolchains"; then
        pass "toolchain_shortcut (the launcher shortcut is published)"
    else
        fail "toolchain_shortcut" \
            "no id=toolchains shortcut for $PKG; the toolchain screen has no entry point"
    fi
fi

# Test 25: the release still carries the ZIPs a non-Play install downloads.
#
# ToolchainRegistry points every sideloaded install at releases/latest, so a
# release published without the toolchain assets breaks installation for every
# non-Play user, including ones who already have the app. Nothing on the device
# can detect that; it is a property of the release, checked from the host.
#
# A range request rather than a HEAD, so a redirect to a signed asset URL is
# followed without pulling 179 MB. No network means SKIP: this is the one check
# here that can fail for a reason that has nothing to do with the build.
TC_URLS=$(derive_toolchain_release_urls)
if [ -z "$TC_URLS" ]; then
    fail "toolchain_release_assets" \
        "no downloadUrl readable in ToolchainRegistry.kt"
elif ! command -v curl >/dev/null 2>&1; then
    skip "toolchain_release_assets" "no curl on this host"
else
    TC_URL_BAD=""
    TC_URL_UNREACHED=""
    TC_URL_OK=0
    for url in $TC_URLS; do
        CODE=$(curl -sL -o /dev/null -r 0-0 --max-time 30 -w "%{http_code}" "$url" 2>/dev/null)
        RC=$?
        if [ $RC -ne 0 ] || [ "$CODE" = "000" ]; then
            TC_URL_UNREACHED="$TC_URL_UNREACHED $(basename "$url")"
        elif [ "$CODE" = "200" ] || [ "$CODE" = "206" ]; then
            TC_URL_OK=$((TC_URL_OK + 1))
        else
            TC_URL_BAD="$TC_URL_BAD $(basename "$url")=$CODE"
        fi
    done
    if [ -n "$TC_URL_BAD" ]; then
        fail "toolchain_release_assets" \
            "the current release does not serve:$TC_URL_BAD"
    elif [ -n "$TC_URL_UNREACHED" ]; then
        skip "toolchain_release_assets" \
            "could not reach github.com for:$TC_URL_UNREACHED"
    else
        pass "toolchain_release_assets ($TC_URL_OK served by releases/latest)"
    fi
fi

# Tests 26-29: what an install left on this device.
TC_STATE=$($ADB shell run-as "$PKG" cat files/home/.vscodroid/toolchains.json 2>/dev/null | tr -d '\r')
TC_NAMES=""
TC_STATE_OK=false
if [ -n "$TC_STATE" ]; then
    TC_NAMES=$(printf '%s' "$TC_STATE" | read_toolchain_names 2>/dev/null)
    if [ $? -eq 0 ]; then
        TC_STATE_OK=true
    fi
fi

if [ -z "$TC_STATE" ]; then
    # Absence, not damage. Either nothing was ever installed or this run cleared
    # app data, and neither is a defect.
    skip "toolchain_record" \
        "no toolchains.json on this device; install one and re-run with --skip-install"
    skip "toolchain_env_file" "no toolchain installed"
    skip "toolchain_env_sourced" "no toolchain installed"
    skip "toolchain_payload" "no toolchain installed"
elif ! $TC_STATE_OK; then
    # Damage is a different thing, and the app treats it as one: a toolchains.json
    # it cannot parse makes it keep the last good toolchain-env.sh rather than
    # delete it, because the file on disk is the only working record of how to
    # run what is still installed.
    #
    # Only reachable when python3 was here and rejected the file. Without it
    # read_toolchain_names offers no parse verdict, so a host with no
    # interpreter cannot land here and blame the device for it.
    fail "toolchain_record" "toolchains.json is present but is not readable JSON"
    skip "toolchain_env_file" "the record is unreadable"
    skip "toolchain_env_sourced" "the record is unreadable"
    skip "toolchain_payload" "the record is unreadable"
elif [ -z "$TC_NAMES" ]; then
    skip "toolchain_record" \
        "toolchains.json is an empty list; install one and re-run with --skip-install"
    skip "toolchain_env_file" "no toolchain installed"
    skip "toolchain_env_sourced" "no toolchain installed"
    skip "toolchain_payload" "no toolchain installed"
else
    pass "toolchain_record ($TC_NAMES)"

    # The record without the environment is an install nothing can use: the
    # commands simply are not names any terminal knows.
    TC_ENV=$($ADB shell run-as "$PKG" cat files/home/.vscodroid/toolchain-env.sh 2>/dev/null | tr -d '\r')
    if [ -n "$TC_ENV" ]; then
        pass "toolchain_env_file (toolchain-env.sh regenerated)"
    else
        fail "toolchain_env_file" \
            "toolchains.json names $TC_NAMES but toolchain-env.sh is missing or empty"
    fi

    # And the environment nothing sources is the same install, one layer out.
    BASHRC=$($ADB shell run-as "$PKG" cat files/home/.bashrc 2>/dev/null | tr -d '\r')
    if echo "$BASHRC" | grep -q "toolchain-env.sh"; then
        pass "toolchain_env_sourced (.bashrc sources it)"
    else
        fail "toolchain_env_sourced" \
            ".bashrc does not source toolchain-env.sh; no terminal gets the toolchain environment"
    fi

    # Every wrapper hands a file under filesDir to the system loader. A wrapper
    # whose file is gone is a command that reports a loader error instead of
    # running, which is what a half-removed or partly-copied install looks like.
    # extract_wrapper_targets reads only the wrapper lines, and --self-check
    # asserts that reading against the generator's own spelling, so this can
    # neither judge an `export` value as a missing file nor skip in silence when
    # the generated format moves.
    TC_TARGETS=$(printf '%s\n' "$TC_ENV" | extract_wrapper_targets)
    TC_TARGET_TOTAL=$(printf '%s\n' "$TC_TARGETS" | grep -c . || true)
    if [ "$TC_TARGET_TOTAL" -eq 0 ]; then
        skip "toolchain_payload" "toolchain-env.sh defines no wrappers to check"
    else
        # Capped: the whole list goes onto one adb command line, and Java alone
        # contributes dozens. Checking a bounded prefix of it is enough to catch
        # a payload that is gone, and the count says how much was looked at.
        TC_CHECKED=$(printf '%s\n' "$TC_TARGETS" | head -20)
        TC_CHECK_COUNT=$(printf '%s\n' "$TC_CHECKED" | grep -c . || true)
        TC_ARGS=$(printf '%s\n' "$TC_CHECKED" | sed "s|^|$DATA_DIR/files/|" | tr '\n' ' ')
        TC_MISSING=$($ADB shell "run-as $PKG sh -c 'for f in $TC_ARGS; do [ -e \$f ] || echo \$f; done'" 2>/dev/null | tr -d '\r')
        if [ -z "$TC_MISSING" ]; then
            pass "toolchain_payload ($TC_CHECK_COUNT of $TC_TARGET_TOTAL wrapper targets present)"
        else
            fail "toolchain_payload" \
                "toolchain-env.sh names files that are not on disk: $(echo "$TC_MISSING" | tr '\n' ' ')"
        fi
    fi
fi

# ═══════════════════════════════════════════════════════════════════
# TESTS 30-33: The terminal, as the app opens it
# ═══════════════════════════════════════════════════════════════════
printf "\n${BOLD}Phase 7: Terminal Through The App${RESET}\n"

# Phase 4 runs the bundled tools through `run-as`, which answers "is the binary
# there and does it work", and cannot answer "does the app's own terminal open".
# Those are different questions with different failure modes, and the second one
# is the one a user meets. What is reachable from adb:
#
#   * the profile the workbench will use, and whether the shell it names is
#     still on disk -- a dangling path means every terminal fails to open, and
#     it dangles for a mundane reason (a reinstall moves nativeLibraryDir);
#   * whether the two files a new shell sources still parse, because a syntax
#     error in either takes out every terminal at once rather than one command;
#   * whether a terminal can actually be opened, which is attempted rather than
#     assumed, and reports SKIP when it cannot be confirmed.
#
# What stays out of reach: everything inside the terminal. TT-1 through TT-10 on
# the checklist need a person, because the output only exists inside the WebView.

TERM_PROFILE_PATH=""
SETTINGS_JSON=$($ADB shell run-as "$PKG" cat files/home/.vscodroid/data/Machine/settings.json 2>/dev/null | tr -d '\r')
if [ -n "$SETTINGS_JSON" ]; then
    TERM_PROFILE_PATH=$(printf '%s' "$SETTINGS_JSON" | read_terminal_profile_path 2>/dev/null)
fi

# Test 30: the workbench is pointed at a shell that exists.
#
# Three ways to have no path, and they are not the same fact. The file being
# absent is about the device. The file being there with no bash profile is about
# the app. An empty answer from the shell fallback on a host with no python3 is
# about the host, and the one thing it must not do is name the app's settings.
if [ -z "$SETTINGS_JSON" ]; then
    fail "terminal_profile" \
        "no Machine/settings.json on this device; the workbench has no terminal profile to open"
elif [ -z "$TERM_PROFILE_PATH" ] && ! $HAVE_PYTHON3; then
    skip "terminal_profile" \
        "no python3 on this host and the fallback read of settings.json matched nothing; an empty answer here is about this machine"
elif [ -z "$TERM_PROFILE_PATH" ]; then
    fail "terminal_profile" \
        "no terminal.integrated.profiles.linux.bash.path in settings.json; the terminal has no shell to start"
else
    SHELL_THERE=$($ADB shell "run-as $PKG sh -c 'test -e $TERM_PROFILE_PATH && echo EXISTS'" 2>/dev/null | tr -d '\r')
    if echo "$SHELL_THERE" | grep -q "EXISTS"; then
        pass "terminal_profile ($TERM_PROFILE_PATH)"
    else
        # updateSettingsNativeLibPaths() rewrites this on every launch precisely
        # because a reinstall moves nativeLibraryDir underneath it. `test -e`
        # follows the symlink, so a dangling usr/bin/bash fails here too.
        fail "terminal_profile" \
            "$TERM_PROFILE_PATH does not resolve; no terminal can open"
    fi
fi

# Test 31: shell integration can still recognise the shell.
#
# The ptyHost picks injection arguments from a table keyed by the *basename* of
# the profile's executable: `bash` matches, `libbash.so` matches nothing and the
# injection is skipped in silence. That is why the profile names the symlink and
# not the library it points at (Environment.getTerminalShellPath), and pointing
# it at the library is a change that breaks nothing visibly.
if [ -z "$TERM_PROFILE_PATH" ]; then
    skip "terminal_shell_basename" "no profile path to read"
elif [ "$(basename "$TERM_PROFILE_PATH")" = "bash" ]; then
    pass "terminal_shell_basename (bash)"
else
    fail "terminal_shell_basename" \
        "the profile names $(basename "$TERM_PROFILE_PATH"); shell integration is keyed on the basename and would be skipped in silence"
fi

# Test 32: the files every new shell sources still parse.
#
# `bash -n` parses without executing, so the answer does not depend on which
# SELinux domain asks -- unlike anything that runs a toolchain. It is worth its
# own test because the blast radius is not one command: toolchain-env.sh is
# generated from manifests regenerated at build time, and one name bash cannot
# use as a function is a parse error that takes out every terminal.
PARSE_FAILURES=""
PARSE_CHECKED=0
for rc_file in files/home/.bashrc files/home/.vscodroid/toolchain-env.sh; do
    RC_PRESENT=$($ADB shell "run-as $PKG sh -c 'test -f $rc_file && echo EXISTS'" 2>/dev/null | tr -d '\r')
    echo "$RC_PRESENT" | grep -q "EXISTS" || continue
    PARSE_CHECKED=$((PARSE_CHECKED + 1))
    PARSE_OUT=$(run_tool "files/usr/bin/bash" -n "$DATA_DIR/$rc_file")
    PARSE_RC=$?
    if [ "$PARSE_RC" -ne 0 ]; then
        PARSE_FAILURES="$PARSE_FAILURES $rc_file(rc=$PARSE_RC: $PARSE_OUT)"
    fi
done
if [ "$PARSE_CHECKED" -eq 0 ]; then
    fail "terminal_startup_files" \
        "neither .bashrc nor toolchain-env.sh is present; a terminal would open with no environment"
elif [ -z "$PARSE_FAILURES" ]; then
    pass "terminal_startup_files ($PARSE_CHECKED parsed)"
else
    fail "terminal_startup_files" "syntax error in:$PARSE_FAILURES"
fi

# Test 33: a terminal actually opens, and bash runs in the app's own domain.
#
# This is the only check in the suite that measures the exec path the app itself
# takes rather than the one run-as takes. It is also the least certain to run:
# Ctrl+backtick has to reach the workbench inside the WebView, and nothing here
# can confirm the workbench received a keystroke.
#
# So the result is PASS on positive evidence -- a bash process under this
# package -- and SKIP otherwise, never FAIL. A failure to drive the UI and a
# broken terminal look identical from out here, and reporting the first as the
# second is how a suite starts lying in the other direction.
#
# ARGS is the command line with argv[0]'s directory stripped, which is what makes
# both spellings visible: node-pty may name the usr/bin/bash symlink or the
# libbash.so it resolves to, and "bash" occurs in either.
APP_UID=$($ADB shell run-as "$PKG" id -u 2>/dev/null | tr -d '\r')
app_process_lines() {
    $ADB shell ps -A -o UID,ARGS 2>/dev/null | tr -d '\r' \
        | awk -v uid="$APP_UID" '$1 == uid'
}
bash_process_count() {
    app_process_lines | grep -c "bash" || true
}

# The control, and it has to come first: an empty answer below means "no bash"
# only if this can see the app's processes at all. The server was reported ready
# in Phase 2, so its Node process is there to be found -- if that is not visible
# either, the reading is about `ps`, not about the terminal.
APP_PROCS=$(app_process_lines)
# Set on every path Test 33 can leave by, so that Test 34 below reports the
# reason itself rather than pointing at a line that may be a pass about process
# counts. Empty is the one case Test 33 explains: it gave up before injecting.
CHORD_VERDICT=""
if [ -z "$APP_UID" ] || ! echo "$APP_PROCS" | grep -q "node"; then
    skip "terminal_opens" \
        "could not read this app's processes out of ps (uid='$APP_UID'), so an empty result would prove nothing"
else
    BASH_BEFORE=$(bash_process_count)
    if [ "$BASH_BEFORE" -gt 0 ]; then
        pass "terminal_opens ($BASH_BEFORE bash process(es) running under $PKG)"
        CHORD_VERDICT="not-needed"
    else
        FOCUS=$($ADB shell "dumpsys window 2>/dev/null | grep -E 'mCurrentFocus|mFocusedApp'" 2>/dev/null | tr -d '\r')
        if [ -z "$FOCUS" ]; then
            skip "terminal_opens" \
                "could not read which app is in front, so a keystroke would go somewhere unknown"
        elif ! echo "$FOCUS" | grep -q "$PKG"; then
            skip "terminal_opens" \
                "$PKG is not the focused app; a keystroke would land elsewhere"
        else
            # keycombination is Android 12+. An older `input` answers with usage
            # text rather than a non-zero exit, so the output is what gets read.
            COMBO_OUT=$($ADB shell input keycombination KEYCODE_CTRL_LEFT KEYCODE_GRAVE 2>&1 | tr -d '\r')
            if echo "$COMBO_OUT" | grep -qiE "error|unknown command|usage"; then
                skip "terminal_opens" "input keycombination is unavailable here: $COMBO_OUT"
            else
                sleep 6
                BASH_AFTER=$(bash_process_count)
                if [ "$BASH_AFTER" -gt "$BASH_BEFORE" ]; then
                    pass "terminal_opens (bash spawned in the app's own domain)"
                    CHORD_VERDICT="delivered"
                else
                    skip "terminal_opens" \
                        "no bash appeared after Ctrl+backtick; the workbench may not have had keyboard focus. Not evidence of a broken terminal -- open one by hand (checklist TT-1)"
                    CHORD_VERDICT="silent"
                fi
            fi
        fi
    fi
fi

# Test 34: a hardware-style chord is delivered to the workbench.
#
# The same injection Test 33 uses, read for the other thing it proves. `input
# keycombination` goes through InputDispatcher, which is the path a physical
# keyboard's keys take, so a terminal appearing after Ctrl+backtick is evidence
# that a chord crossed the whole stack -- system, activity, WebView, workbench --
# with nothing swallowing it on the way. That property, that no key event is
# intercepted, is the entirety of this app's hardware keyboard support, and this
# is the only automated reading of it on a device. It gets its own name because
# nobody looking for keyboard coverage looks under terminal_opens.
#
# It does not satisfy checklist KB-4. `input` injects a virtual device: it
# exercises dispatch and says nothing about pairing, layout mapping, or how a
# real HID device reports its modifiers.
#
# PASS or SKIP only, never FAIL, for the reason Test 33 gives above: a workbench
# that never had focus and a keystroke that was eaten look identical from here.
case "$CHORD_VERDICT" in
    delivered)
        pass "hardware_key_chord_reaches_workbench (Ctrl+backtick was acted on)"
        ;;
    silent)
        skip "hardware_key_chord_reaches_workbench" \
            "the chord was injected and nothing acted on it, which is what a lost keystroke and an unfocused workbench both look like. Type on a real keyboard instead (checklist KB-4)"
        ;;
    not-needed)
        skip "hardware_key_chord_reaches_workbench" \
            "a terminal was already open, so Test 33 had nothing to inject and no chord was sent. Re-run with no terminal open, or type on a real keyboard (checklist KB-4)"
        ;;
    *)
        skip "hardware_key_chord_reaches_workbench" \
            "the run stopped before a chord could be injected: this app's processes, the focused window or the input command itself could not be read. The terminal_opens skip above names which"
        ;;
esac

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
