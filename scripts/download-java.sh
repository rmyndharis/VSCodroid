#!/usr/bin/env bash
set -euo pipefail

# Download pre-compiled OpenJDK 17 from Termux APT repo.
# Places files in the toolchain_java asset pack module for Play Asset Delivery.
#
# Dependencies: libandroid-shmem, libandroid-spawn (Termux-specific shims)
#
# Compatible with bash 3.2+ (macOS default).

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"
PACK_ASSETS="$ROOT_DIR/android/toolchain_java/src/main/assets"
WORK_DIR="$ROOT_DIR/toolchains/termux-packages"

# The index fetch, its signature check, package resolution and the digest check
# on each .deb, shared with every other script that takes packages from Termux.
# It also picks the mirror; TERMUX_MIRROR still overrides it.
. "$SCRIPT_DIR/lib/termux-packages.sh"

REQUIRED_PACKAGES=(
    openjdk-17
    libandroid-shmem
    libandroid-spawn
    # Not a component that ships: it carries usr/share/LICENSES, the shared
    # licence text openjdk-17's copyright symlink points at (GPL-2.0). See
    # termux_copy_notices in scripts/lib/termux-packages.sh.
    termux-licenses
)

# Soname mapping for shared libraries
get_sonames() {
    case "$1" in
        libandroid-shmem)  echo "libandroid-shmem.so" ;;
        libandroid-spawn)  echo "libandroid-spawn.so" ;;
        *)                 echo "" ;;
    esac
}

LIB_PACKAGES=(libandroid-shmem libandroid-spawn)

echo "=== Downloading Java 17 (OpenJDK) Toolchain ==="
echo ""

mkdir -p "$WORK_DIR"
cd "$WORK_DIR"

# --- Step 1: the package index, its signature, and what it resolves to ---
termux_fetch_index
termux_resolve_packages resolved-java.tsv "${REQUIRED_PACKAGES[@]}"
JAVA_VERSION="$(termux_pkg_version openjdk-17)"
echo "  OpenJDK version: $JAVA_VERSION"

# --- Step 2: Download .deb files, each checked against the signed index ---
termux_download_packages "${REQUIRED_PACKAGES[@]}"

# --- Step 3: Extract ---
termux_extract_packages "${REQUIRED_PACKAGES[@]}"

# --- Step 4: Place files in asset pack module ---
echo ""
echo "Placing Java toolchain in asset pack..."
rm -rf "$PACK_ASSETS/usr"
mkdir -p "$PACK_ASSETS/usr/lib"

JDK_SRC="extracted/openjdk-17/data/data/com.termux/files/usr/lib/jvm/java-17-openjdk"
if [ ! -d "$JDK_SRC" ]; then
    echo "  ERROR: JDK directory not found at $JDK_SRC"
    echo "  Searching for JDK..."
    find "extracted/openjdk-17" -maxdepth 6 -type d -name "java-*" 2>/dev/null | head -5
    exit 1
fi

mkdir -p "$PACK_ASSETS/usr/lib/jvm"
# -RL, not a bare -r. legal/ is the only subtree of this JDK carrying symbolic
# links: measured on openjdk-17, bin, conf, lib, man, jmods and include carry
# none between them while legal carries 208, and every one of those points at
# another module's copy of the same notice.
#
# Neither delivery path can carry a link. An Android asset pack cannot hold one,
# which is why this pack's sonames are created at install time from the
# manifest, and ToolchainManager.extractZip writes every non-directory entry
# with a FileOutputStream, so a link entry in the ZIP would arrive on a device
# as a text file whose contents are "../java.base/LICENSE". Dereferencing here
# is what keeps the tree in the AAB and the tree in the release ZIP the same
# tree.
#
# A dangling link upstream makes this fail rather than pass silently, which is
# the right direction and not a reason to go back to -r.
cp -RL "$JDK_SRC" "$PACK_ASSETS/usr/lib/jvm/java-17-openjdk"

# Copy shared library dependencies
echo ""
echo "Placing shared libraries..."
for pkg in "${LIB_PACKAGES[@]}"; do
    sonames="$(get_sonames "$pkg")"
    [ -z "$sonames" ] && continue
    pkg_lib_dir="extracted/$pkg/data/data/com.termux/files/usr/lib"
    for soname in $sonames; do
        src=""
        if [ -f "$pkg_lib_dir/$soname" ] || [ -L "$pkg_lib_dir/$soname" ]; then
            src="$pkg_lib_dir/$soname"
        fi
        if [ -n "$src" ] && ( [ -f "$src" ] || [ -L "$src" ] ); then
            cp -L "$src" "$PACK_ASSETS/usr/lib/$soname"
            echo "  $soname ($(du -sh "$PACK_ASSETS/usr/lib/$soname" | cut -f1))"
        else
            # Fatal, not a warning, for the same reason it is fatal in
            # download-termux-tools.sh: a library missing here ships a pack
            # whose binaries die at dlopen on a user's device, and the ELF
            # verification below cannot see it -- it checks what is present, and
            # this is about what is absent. The pack would install, the command
            # would be on PATH, and it would fail on first use.
            echo "  ERROR: $soname not found in $pkg (looked in $pkg_lib_dir)" >&2
            [ -d "$pkg_lib_dir" ] && ls "$pkg_lib_dir"/*.so* 2>/dev/null | head -5 || true
            exit 1
        fi
    done
done

# --- Step 4b: Rebuild libandroid-spawn with 16 KB page alignment ---
#
# Termux's libandroid-spawn 0.3 is built with 4 KB segment alignment, and
# libjava.so links it -- so on a device with 16 KB pages the JDK cannot load at
# all. Not an optional component: the core. libandroid-shmem.so from the same
# script is correctly 16 KB-aligned, so this is one upstream package rather than
# a systemic problem, and the ELF gate below is what surfaced it.
#
# The package is two source files and two commands. It is AOSP's own posix_spawn
# implementation, vendored in the termux-packages tree under BSD 2-Clause, so
# rebuilding it is a recompile rather than a fork: the resulting library exports
# the same 25 symbols as upstream's, verified by comparing both symbol tables.
#
# DELETE THIS STEP once upstream ships a 16 KB-aligned build. The check below
# will keep working either way -- what it must never become is a reason to relax
# the alignment requirement.
echo ""
echo "Rebuilding libandroid-spawn for 16 KB pages..."

# Pinned to a commit rather than a branch, and each file checked against a
# recorded digest: this source becomes a binary that spawns processes on a
# user's device, and it arrives from a host that publishes no digest of its own.
SPAWN_COMMIT="151a3ebdb6e409bcaa2b5aa74ae8e23447bf9e54"
SPAWN_RAW="https://raw.githubusercontent.com/termux/termux-packages/$SPAWN_COMMIT/packages/libandroid-spawn"
SPAWN_SHA_posix_spawn_cpp="5267405b1b14fcbe885bd815d9bd5dde94144dc90b319d6f4be17e47f43bcad3"
SPAWN_SHA_posix_spawn_h="5cc27528237cf17727709eaab624f20878b71013b2eb265e3a6e6dae1e4294a0"
SPAWN_SHA_LICENSE="511ce23b36ca7dd3858570d8ee3054ceed18e12d284f96661d030a82590a5954"

SPAWN_SRC="$WORK_DIR/libandroid-spawn-src"
rm -rf "$SPAWN_SRC"
mkdir -p "$SPAWN_SRC"
for f in posix_spawn.cpp posix_spawn.h LICENSE; do
    curl -sL --fail --show-error -o "$SPAWN_SRC/$f" "$SPAWN_RAW/$f"
    expected_var="SPAWN_SHA_$(echo "$f" | tr '.' '_')"
    eval "expected=\$$expected_var"
    actual=$( (sha256sum "$SPAWN_SRC/$f" 2>/dev/null || shasum -a 256 "$SPAWN_SRC/$f") | cut -d' ' -f1)
    if [ "$actual" != "$expected" ]; then
        echo "  ERROR: $f does not match the pinned digest" >&2
        echo "    expected: $expected" >&2
        echo "    actual  : $actual" >&2
        exit 1
    fi
done
echo "  source verified against pinned digests ($SPAWN_COMMIT)"

# Same resolution order as build-native-addons.sh, which cross-compiles here too.
if [ -n "${ANDROID_NDK_HOME:-}" ]; then
    NDK_DIR="$ANDROID_NDK_HOME"
elif [ -n "${ANDROID_HOME:-}" ] && [ -d "$ANDROID_HOME/ndk" ]; then
    NDK_DIR="$(ls -d "$ANDROID_HOME/ndk/"* 2>/dev/null | sort -V | tail -1)"
elif [ -d "$HOME/Library/Android/sdk/ndk" ]; then
    NDK_DIR="$(ls -d "$HOME/Library/Android/sdk/ndk/"* 2>/dev/null | sort -V | tail -1)"
else
    echo "  ERROR: Cannot find Android NDK. Set ANDROID_NDK_HOME." >&2
    exit 1
fi
HOST_TAG="$(uname -s | tr '[:upper:]' '[:lower:]')-$(uname -m)"
NDK_BIN="$NDK_DIR/toolchains/llvm/prebuilt/$HOST_TAG/bin"
# Google ships no darwin-arm64 host toolchain; on Apple Silicon the x86_64 one
# runs under Rosetta.
[ -d "$NDK_BIN" ] || NDK_BIN="$NDK_DIR/toolchains/llvm/prebuilt/darwin-x86_64/bin"
[ -d "$NDK_BIN" ] || { echo "  ERROR: no NDK toolchain at $NDK_BIN" >&2; exit 1; }

(
    cd "$SPAWN_SRC"
    "$NDK_BIN/aarch64-linux-android33-clang++" -O2 -fPIC -I. -c posix_spawn.cpp -o posix_spawn.o
    "$NDK_BIN/aarch64-linux-android33-clang++" -shared posix_spawn.o -o libandroid-spawn.so \
        -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384
    "$NDK_BIN/llvm-strip" --strip-unneeded libandroid-spawn.so
)
cp "$SPAWN_SRC/libandroid-spawn.so" "$PACK_ASSETS/usr/lib/libandroid-spawn.so"
echo "  libandroid-spawn.so rebuilt ($(du -sh "$PACK_ASSETS/usr/lib/libandroid-spawn.so" | cut -f1), 16 KB-aligned)"

# --- Step 5: Strip unnecessary files ---
echo ""
echo "Stripping unnecessary files..."
JDK_DIR="$PACK_ASSETS/usr/lib/jvm/java-17-openjdk"
BEFORE_SIZE=$(du -sk "$PACK_ASSETS/usr" | cut -f1)

# Strip the sample programs and the man pages. NOT legal/, which was filed here
# with two directories whose removal takes their subject matter away with them.
# Removing legal/ takes only the notices for everything that stays: lib/modules
# still carries all 70 modules, and legal/ is their attribution. Step 5b below
# refuses to build a pack without it.
rm -rf "$JDK_DIR/demo" 2>/dev/null || true
rm -rf "$JDK_DIR/man" 2>/dev/null || true
# Strip jmods (module files, large, not needed at runtime)
rm -rf "$JDK_DIR/jmods" 2>/dev/null || true
# Strip header files (not needed without JNI compilation)
rm -rf "$JDK_DIR/include" 2>/dev/null || true

# Three JDK libraries whose dependencies were never bundled and never can be
# loaded here: libjavajpeg needs libjpeg.so.8, libjsound needs libasound.so,
# liblcms needs liblcms2.so. None of the three is present in this pack or in the
# base APK, so each fails at dlopen the moment something reaches for it -- they
# were shipped broken rather than shipped useful.
#
# Removed rather than fixed by bundling the missing libraries, and the closure
# was measured before deciding: nothing else in the pack names any of the three
# in DT_NEEDED, so they are leaves and taking them out breaks no other object.
# What they serve -- ImageIO's JPEG codec, colour management, and ALSA sound on
# a platform with no ALSA -- a headless JDK on a phone does not reach. Shipping
# the dependencies instead would add weight to make three unused paths work.
rm -f "$JDK_DIR/lib/libjavajpeg.so" "$JDK_DIR/lib/libjsound.so" "$JDK_DIR/lib/liblcms.so"

AFTER_SIZE=$(du -sk "$PACK_ASSETS/usr" | cut -f1)
echo "  Java: ${BEFORE_SIZE}K -> ${AFTER_SIZE}K (saved $((BEFORE_SIZE - AFTER_SIZE))K)"

# --- Step 5b: the notices have to have survived, as real files ---
#
# Two separate failures, and the second is the one a present-file check misses.
# `[ -f ]` follows a link, so a tree copied without -L passes a check for
# presence while every notice on the device is a one-line text file naming a
# path that is not there. The counts are printed either way so that a run which
# examined nothing cannot read as a clean one.
echo ""
echo "Checking the OpenJDK notices..."
NOTICES_MISSING=0
NOTICES_LINKED=0
NOTICES_PRESENT=0
if [ ! -d "$JDK_DIR/legal" ]; then
    echo "ERROR: $JDK_DIR/legal is absent. It is the only copy of OpenJDK's"
    echo "       third-party notices that reaches a device, and the pack must not"
    echo "       ship without it."
    exit 1
fi
while IFS= read -r entry; do
    # An empty legal/ makes find print nothing, and a heredoc holding nothing
    # still feeds read one empty line, which would be counted as a missing
    # notice and report the wrong cause. Skipping it is what lets the
    # examined-nothing check below be reached at all.
    [ -n "$entry" ] || continue
    if [ -L "$entry" ]; then
        echo "  SYMLINK ${entry#"$JDK_DIR/"}"
        NOTICES_LINKED=$((NOTICES_LINKED + 1))
    elif [ -f "$entry" ]; then
        NOTICES_PRESENT=$((NOTICES_PRESENT + 1))
    else
        echo "  MISSING ${entry#"$JDK_DIR/"}"
        NOTICES_MISSING=$((NOTICES_MISSING + 1))
    fi
done <<EOF
$(find "$JDK_DIR/legal" \( -type f -o -type l \))
EOF
if [ "$NOTICES_MISSING" -ne 0 ] || [ "$NOTICES_LINKED" -ne 0 ]; then
    echo "ERROR: $NOTICES_MISSING notice(s) absent, $NOTICES_LINKED left as symlinks."
    echo "       Neither an asset pack nor the release ZIP can carry a symbolic"
    echo "       link, so a link here becomes a text file naming a missing path."
    echo "       Copy the JDK with 'cp -RL' and do not delete legal/."
    exit 1
fi
if [ "$NOTICES_PRESENT" -eq 0 ]; then
    echo "ERROR: legal/ holds no files at all, so this check examined nothing."
    exit 1
fi
echo "  $NOTICES_PRESENT notice files present, none a symlink"

# --- Step 5c: Place the upstream notices beside what they describe ---
#
# legal/ above is the JDK's own attribution and covers its seventy modules. It
# says nothing about the two Termux shims this pack also ships, libandroid-shmem
# and libandroid-spawn, whose notices reached no device; openjdk-17's own
# package-level copyright is added here too, resolved from the shared GPL-2.0
# text rather than copied as the dangling symlink Termux ships.
termux_copy_notices "$PACK_ASSETS/usr" "${REQUIRED_PACKAGES[@]}"

# --- Step 6: Write manifest.json ---
echo ""
echo "Writing manifest.json..."

# Collect all binaries in bin/
BINARIES='['
SYMLINKS='{'
FIRST_BIN=true
FIRST_SYM=true
JDK_BIN_DIR="$JDK_DIR/bin"
if [ -d "$JDK_BIN_DIR" ]; then
    for bin in "$JDK_BIN_DIR"/*; do
        [ -f "$bin" ] || [ -L "$bin" ] || continue
        name="$(basename "$bin")"
        binpath="usr/lib/jvm/java-17-openjdk/bin/$name"
        [ "$FIRST_BIN" = true ] && FIRST_BIN=false || BINARIES+=','
        BINARIES+="\"$binpath\""
        [ "$FIRST_SYM" = true ] && FIRST_SYM=false || SYMLINKS+=','
        SYMLINKS+="\"$name\":\"$binpath\""
    done
fi
# Also include lib/jspawnhelper and lib/jexec: these aren't in bin/ but need chmod +x.
# jspawnhelper is required by ProcessBuilder (fork+exec), jexec handles #! JAR execution.
for libbin in jspawnhelper jexec; do
    libbin_path="$JDK_DIR/lib/$libbin"
    if [ -f "$libbin_path" ]; then
        binpath="usr/lib/jvm/java-17-openjdk/lib/$libbin"
        [ "$FIRST_BIN" = true ] && FIRST_BIN=false || BINARIES+=','
        BINARIES+="\"$binpath\""
    fi
done
BINARIES+=']'
SYMLINKS+='}'

cat > "$PACK_ASSETS/toolchain_java.json" << EOF
{
    "name": "java",
    "displayName": "Java 17",
    "version": "$JAVA_VERSION",
    "binaries": $BINARIES,
    "symlinks": $SYMLINKS,
    "env": {
        "JAVA_HOME": "\$FILESDIR/usr/lib/jvm/java-17-openjdk"
    },
    "pathDirs": ["usr/lib/jvm/java-17-openjdk/bin"],
    "installRoot": "usr/lib/jvm/java-17-openjdk",
    "libs": ["libandroid-shmem.so", "libandroid-spawn.so"]
}
EOF
echo "  toolchain_java.json written"

# --- Step 7: Verify every binary the pack ships ---
echo ""
echo "=== Verifying Java binaries ==="
# The pack reaches devices through Play Asset Delivery and the release ZIPs, and
# nothing checked that anything in it could load. A wrong-architecture,
# misaligned or dependency-missing binary produces a green build and a `java`
# that dies on someone's phone with a linker message nobody sees; the digest
# check cannot catch it, because upstream's hash covers whatever is in the file.
#
# The whole pack is swept, unlike Go. Go ships a source tree that legitimately
# carries other architectures; the JDK ships only what it loads, so every ELF
# object in it is a promise that has to hold. This gate is what found the
# rebuild in step 4b and the three unloadable libraries pruned in step 5 -- both
# were shipping before it existed.
#
# The lib-dirs are where a dependency may legitimately live: the JDK's own lib/
# and lib/server/ (libjvm.so is in the latter and the launchers name it), the
# pack's usr/lib for the Termux shims, and the base APK's usr/lib -- toolchains
# install into filesDir/usr beside it, so libz, libiconv and libc++_shared
# resolve there at runtime. Without that last one the gate would reject binaries
# that work, which is the failure mode that makes a gate worse than none.
is_elf() {
    [ "$(dd if="$1" bs=4 count=1 2>/dev/null | od -An -tx1 | tr -d ' \n')" = "7f454c46" ]
}

verify_failures=0
verify_checked=0

verify_object() {
    local out
    verify_checked=$((verify_checked + 1))
    if ! out=$(python3 "$SCRIPT_DIR/verify-android-elf.py" "$1" \
                   --lib-dir "$PACK_ASSETS/usr/lib" \
                   --lib-dir "$JDK_DIR/lib" \
                   --lib-dir "$JDK_DIR/lib/server" \
                   --lib-dir "$ROOT_DIR/android/app/src/main/assets/usr/lib" 2>&1); then
        echo "  FAILED  ${1#$PACK_ASSETS/}" >&2
        # `|| true` because this file runs under pipefail: a grep that matched
        # nothing would return 1 and kill the shell right here, in the one branch
        # whose job is to say what went wrong.
        echo "$out" | grep -v '^  ok' | sed 's/^/     /' >&2 || true
        verify_failures=$((verify_failures + 1))
    fi
}

while IFS= read -r obj; do
    is_elf "$obj" && verify_object "$obj"
done < <(find "$PACK_ASSETS/usr" -type f)

if [ "$verify_failures" -gt 0 ]; then
    echo "" >&2
    echo "  ERROR: $verify_failures of $verify_checked Java binaries would fail to" >&2
    echo "         load on a device. Shipping them produces a working build and a" >&2
    echo "         broken toolchain." >&2
    exit 1
fi
echo "  $verify_checked binaries verified: architecture, dependencies, 16 KB alignment"

# The whole pack, not just legal/. Step 5b asks this of the notices because that
# is where the JDK's 208 links are; this asks it of every path, because the two
# verification loops above are driven by `find -type f`, which steps over a
# symlink without a word. Neither delivery path can carry one: an asset pack
# cannot hold a link, and ToolchainManager.extractZip writes every non-directory
# entry with a FileOutputStream, so a link in the release ZIP lands on a device
# as a text file holding the target path and fails at first use.
echo ""
echo "=== Verifying the pack carries no symbolic link ==="
link_count=$(find "$PACK_ASSETS" -type l | wc -l | tr -d ' ')
if [ "$link_count" -ne 0 ]; then
    echo "  ERROR: $link_count symbolic link(s) in the pack:" >&2
    find "$PACK_ASSETS" -type l | sed "s|^$PACK_ASSETS/|     |" >&2
    echo "         Copy with 'cp -RL' so the link's target travels instead." >&2
    exit 1
fi
echo "  none"

# --- Step 8: Size summary ---
echo ""
echo "=== Java 17 Toolchain Size Summary ==="
echo "  Asset pack: $(du -sh "$PACK_ASSETS" | cut -f1) total"
echo "  JDK bin/: $(du -sh "$JDK_BIN_DIR" 2>/dev/null | cut -f1)"
echo "  JDK lib/: $(du -sh "$JDK_DIR/lib" 2>/dev/null | cut -f1)"
[ -f "$JDK_DIR/src.zip" ] && echo "  src.zip: $(du -sh "$JDK_DIR/src.zip" | cut -f1)"
echo ""
echo "Shared libraries:"
for f in "$PACK_ASSETS/usr/lib"/*.so*; do
    [ -f "$f" ] && echo "  $(basename "$f"): $(du -sh "$f" | cut -f1)"
done
echo ""
echo "Key binaries:"
for bin in java javac jar jshell; do
    [ -f "$JDK_BIN_DIR/$bin" ] && echo "  $bin: $(du -sh "$JDK_BIN_DIR/$bin" | cut -f1)"
done

echo ""
echo "=== Java download complete ==="
echo "Next: cd android && ./gradlew bundleDebug"
