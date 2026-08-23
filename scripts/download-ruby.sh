#!/usr/bin/env bash
set -euo pipefail

# Download pre-compiled Ruby + dependencies from Termux APT repo.
# Places files in the toolchain_ruby asset pack module for Play Asset Delivery.
#
# Note: openssl (libssl, libcrypto) is already bundled in the core APK for git.
# We only need Ruby-specific deps: libgmp, libyaml.
#
# Compatible with bash 3.2+ (macOS default).

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"
PACK_ASSETS="$ROOT_DIR/android/toolchain_ruby/src/main/assets"
WORK_DIR="$ROOT_DIR/toolchains/termux-packages"

# The index fetch, its signature check, package resolution and the digest check
# on each .deb, shared with every other script that takes packages from Termux.
# It also picks the mirror; TERMUX_MIRROR still overrides it.
. "$SCRIPT_DIR/lib/termux-packages.sh"

REQUIRED_PACKAGES=(
    ruby
    libgmp
    libyaml
    libandroid-execinfo
    # Not a component that ships: it carries usr/share/LICENSES, the shared
    # licence texts libgmp's copyright symlink points at (LGPL-3.0). See
    # termux_copy_notices in scripts/lib/termux-packages.sh.
    termux-licenses
    # fiddle, Ruby's stdlib FFI, links libffi, which the base runtime already
    # ships for Python's _ctypes in app assets. Not downloaded here: an asset
    # entry the base module and a pack both carry is a conflict bundletool
    # refuses, and the base copy is in place before any toolchain installs.
)

# Soname mapping for shared libraries
get_sonames() {
    case "$1" in
        ruby)                  echo "libruby.so" ;;
        libgmp)                echo "libgmp.so" ;;
        libyaml)               echo "libyaml-0.so" ;;
        libandroid-execinfo)   echo "libandroid-execinfo.so" ;;
        *)                     echo "" ;;
    esac
}

LIB_PACKAGES=(ruby libgmp libyaml libandroid-execinfo)

# Tells a real binary from a script by reading the file rather than the name.
# Used twice: to decide which commands need an interpreter wrapper, and by the
# verification step to skip files the ELF checker would reject for not being one.
is_elf() {
    [ "$(dd if="$1" bs=4 count=1 2>/dev/null | od -An -tx1 | tr -d ' \n')" = "7f454c46" ]
}

echo "=== Downloading Ruby Toolchain ==="
echo ""

mkdir -p "$WORK_DIR"
cd "$WORK_DIR"

# --- Step 1: the package index, its signature, and what it resolves to ---
termux_fetch_index
termux_resolve_packages resolved-ruby.tsv "${REQUIRED_PACKAGES[@]}"
RUBY_VERSION="$(termux_pkg_version ruby)"
echo "  Ruby version: $RUBY_VERSION"

# Detect Ruby minor version for RUBYLIB path (e.g. 3.4.1-2 → 3.4.0)
# Read here rather than beside the manifest it also feeds, because the shell
# rewrite below reaches into usr/lib/ruby/$RUBY_MINOR and runs first.
RUBY_MINOR=$(echo "$RUBY_VERSION" | sed 's/^[0-9]*://; s/\([0-9]*\.[0-9]*\)\..*/\1.0/')

# --- Step 2: Download .deb files, each checked against the signed index ---
termux_download_packages "${REQUIRED_PACKAGES[@]}"

# --- Step 3: Extract ---
termux_extract_packages "${REQUIRED_PACKAGES[@]}"

# --- Step 4: Place files in asset pack module ---
echo ""
echo "Placing Ruby toolchain in asset pack..."
rm -rf "$PACK_ASSETS/usr"
mkdir -p "$PACK_ASSETS/usr/bin" "$PACK_ASSETS/usr/lib"

RUBY_USR="extracted/ruby/data/data/com.termux/files/usr"

# Every command the package ships, read from the package rather than from a list
# kept here. The list used to be fixed and had drifted: it still named `ri`,
# which upstream stopped shipping, and it missed rake, rdbg, rbs, racc,
# syntax_suggest and typeprof, which upstream added. Nothing compared the two, so
# `rake` -- the command most Ruby projects are driven by -- had never been in the
# pack and said "command not found" with no sign of why. Deriving it means the
# next addition arrives on its own, and arrives gated: the verification step
# below sweeps whatever lands here.
if [ ! -d "$RUBY_USR/bin" ]; then
    echo "  ERROR: no bin directory in the ruby package at $RUBY_USR/bin" >&2
    exit 1
fi
for src in "$RUBY_USR/bin"/*; do
    [ -f "$src" ] || [ -L "$src" ] || continue
    cp -L "$src" "$PACK_ASSETS/usr/bin/$(basename "$src")"
    echo "  bin/$(basename "$src")"
done

# Copy ruby lib directory (stdlib + gems)
#
# -RL, not a bare -r, for the reason download-java.sh spells out over its own
# copy: neither delivery path can carry a symbolic link. An Android asset pack
# cannot hold one, and ToolchainManager.extractZip writes every non-directory
# entry with a FileOutputStream, so a link entry in the release ZIP arrives on a
# device as a text file holding the target path. A bare -r also does different
# things on the two machines this runs on: BSD cp on a maintainer's macOS
# dereferences a link in the source tree, GNU cp on the release runner copies it
# as a link, so the pack built locally and the pack CI ships would differ.
#
# The package upstream ships today has no link under lib/ruby, which is why this
# has cost nothing so far. What it ships next is not this repository's choice.
if [ -d "$RUBY_USR/lib/ruby" ]; then
    cp -RL "$RUBY_USR/lib/ruby" "$PACK_ASSETS/usr/lib/ruby"
    echo "  lib/ruby/"
fi

# Copy shared libraries
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
        else
            base_soname="$(echo "$soname" | sed 's/\.so\..*/\.so/')"
            if [ "$base_soname" != "$soname" ] && ( [ -f "$pkg_lib_dir/$base_soname" ] || [ -L "$pkg_lib_dir/$base_soname" ] ); then
                src="$pkg_lib_dir/$base_soname"
            fi
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

# --- Step 5: Strip unnecessary files ---
echo ""
echo "Stripping unnecessary files..."
BEFORE_SIZE=$(du -sk "$PACK_ASSETS/usr" | cut -f1)

# Strip gem cache, doc, ri
find "$PACK_ASSETS/usr/lib/ruby" -path "*/cache/*.gem" -delete 2>/dev/null || true
find "$PACK_ASSETS/usr/lib/ruby" -type d -name "doc" -exec rm -rf {} + 2>/dev/null || true
find "$PACK_ASSETS/usr/lib/ruby" -type d -name "ri" -exec rm -rf {} + 2>/dev/null || true
# Strip test directories
find "$PACK_ASSETS/usr/lib/ruby" -type d \( -name "test" -o -name "tests" -o -name "spec" \) -exec rm -rf {} + 2>/dev/null || true
# Strip man pages if any leaked through
find "$PACK_ASSETS/usr" -type d -name "man" -exec rm -rf {} + 2>/dev/null || true

AFTER_SIZE=$(du -sk "$PACK_ASSETS/usr" | cut -f1)
echo "  Ruby: ${BEFORE_SIZE}K -> ${AFTER_SIZE}K (saved $((BEFORE_SIZE - AFTER_SIZE))K)"

# --- Step 5b: Point the compiled-in default shells at one this app can run ---
#
# Termux builds Ruby for its own prefix, so three files in the pack name
# /data/data/com.termux/files/usr/bin/sh. libruby.so and the pty extension carry
# it as a C constant; mkmf.rb writes it into the SHELL line of every Makefile it
# generates for a native gem extension. That directory belongs to another
# application and this one can neither read nor create it.
#
# mkmf.rb is the one repointing make does not cover. make takes SHELL from the
# makefile and never from the environment (measured: an assignment of a
# nonexistent shell fails the build, the same value exported does nothing), so
# the assignment mkmf emits overrides the value compiled into libmake.so and
# every recipe line runs a shell that is not there. That path is latent rather
# than live today: building a native gem also needs a C compiler, and none is
# bundled. It is fixed here so the compiler is the only thing still missing.
#
# Before the ELF gate below, so what that gate examines is what ships. The
# rewrite keeps the length of the two ELF objects and fails closed on all three:
# a package that moves its default shell stops the build rather than shipping a
# pack whose shell nobody has established.
echo ""
echo "Pointing compiled-in default shells at /system/bin/sh..."
RUBY_LIB="$PACK_ASSETS/usr/lib/ruby/$RUBY_MINOR"
python3 "$SCRIPT_DIR/patch-default-shell.py" \
    "$PACK_ASSETS/usr/lib/libruby.so" \
    "$RUBY_LIB/aarch64-linux-android/pty.so" \
    "$RUBY_LIB/mkmf.rb"

# --- Step 5c: Place the upstream notices beside what they describe ---
#
# After the strip, because that sweep removes `doc` directories under
# lib/ruby and this writes into share/doc; before the manifest, so the pack a
# release attaches always carries them. Ruby's own copyright, libyaml's and
# libandroid-execinfo's reached no device until now, and GMP is LGPL-3.0: its
# copyright resolves to the shared LGPL-3.0 text, which the licences dialog also
# carries in the base APK.
termux_copy_notices "$PACK_ASSETS/usr" "${REQUIRED_PACKAGES[@]}"

# --- Step 6: Write toolchain_ruby.json ---
echo ""
echo "Writing toolchain_ruby.json..."

# Built from what was actually copied, and split by reading each file rather than
# by naming the exception. Only the interpreter is a real binary; every other
# command is a Ruby script, and a script cannot be executed from filesDir at all
# -- SELinux refuses it -- so each gets a shell function that hands the file to
# `ruby` instead. Their shebangs point into Termux's own prefix and are never
# used, which is exactly why passing the interpreter explicitly is what makes
# them work here.
#
# Deciding by content matters beyond tidiness: if upstream ever ships a second
# real binary, it lands in binaries and stays out of the wrappers on its own,
# where a name-based rule would have handed it to `ruby` and produced a syntax
# error nobody could place.
BINARIES='['
SCRIPT_WRAPPERS='{'
FIRST_BIN=true
FIRST_SW=true
for cmd in "$PACK_ASSETS/usr/bin"/*; do
    [ -f "$cmd" ] || continue
    name="$(basename "$cmd")"
    [ "$FIRST_BIN" = true ] && FIRST_BIN=false || BINARIES+=','
    BINARIES+="\"usr/bin/$name\""
    if ! is_elf "$cmd"; then
        [ "$FIRST_SW" = true ] && FIRST_SW=false || SCRIPT_WRAPPERS+=','
        SCRIPT_WRAPPERS+="\"$name\":\"usr/bin/$name\""
    fi
done
BINARIES+=']'
SCRIPT_WRAPPERS+='}'

cat > "$PACK_ASSETS/toolchain_ruby.json" << EOF
{
    "name": "ruby",
    "displayName": "Ruby",
    "version": "$RUBY_VERSION",
    "binaries": $BINARIES,
    "env": {
        "GEM_HOME": "\$HOME/.gem/ruby",
        "GEM_PATH": "\$HOME/.gem/ruby:\$FILESDIR/usr/lib/ruby/gems",
        "RUBYLIB": "\$FILESDIR/usr/lib/ruby/$RUBY_MINOR:\$FILESDIR/usr/lib/ruby/$RUBY_MINOR/aarch64-linux-android"
    },
    "pathDirs": ["usr/bin"],
    "installRoot": "usr/lib/ruby",
    "libs": ["libruby.so", "libgmp.so", "libyaml-0.so", "libandroid-execinfo.so"],
    "libSymlinks": {
        "libruby.so.${RUBY_MINOR%.*}": "libruby.so"
    },
    "scriptWrappers": {
        "interpreter": "ruby",
        "scripts": $SCRIPT_WRAPPERS
    }
}
EOF
echo "  toolchain_ruby.json written"

# --- Step 7: Verify every binary the pack ships ---
echo ""
echo "=== Verifying Ruby binaries ==="
# The pack reaches devices through Play Asset Delivery and the release ZIPs, and
# nothing checked that anything in it could load. A wrong-architecture,
# misaligned or dependency-missing binary produces a green build and a `ruby`
# that dies on someone's phone with a linker message nobody sees; the digest
# check cannot catch it, because upstream's hash covers whatever is in the file.
#
# The whole pack is swept, unlike Go: Ruby ships only what it loads -- the
# interpreter, libruby, and around a hundred stdlib and gem extensions -- so
# every ELF object in it is a promise that has to hold.
#
# The soname link directory is what makes this gate honest rather than noisy.
# Every one of those extensions names libruby.so.3.4 in DT_NEEDED, but the pack
# ships the file as libruby.so, because Android asset archives cannot carry
# symlinks; ToolchainManager creates the versioned name at install time from the
# manifest's libSymlinks. Verified against the pack as it sits on disk, all 103
# objects would be reported as missing their runtime -- a gate failing on the
# whole toolchain while the toolchain works. So the same link the device will
# have is created here, from the same expression that writes the manifest, and
# offered to the verifier as another place a dependency may live.
#
# The link is only created once its target is known to exist, and that check is
# not decoration. The verifier resolves a dependency by looking for its name in
# a lib-dir, not by following it, so a dangling link here would answer for a
# runtime that is not in the pack: the gate would report every object satisfied
# while the one library they all need was absent, which is precisely the failure
# it exists to catch. The placement step above does fail on a library it cannot
# find, so this is the second of two doors rather than the only one -- it covers
# libruby arriving by some route that step does not police.
if [ ! -f "$PACK_ASSETS/usr/lib/libruby.so" ]; then
    echo "  ERROR: libruby.so is not in the pack -- every binary here links it," >&2
    echo "         so the toolchain cannot work and the gate cannot be trusted" >&2
    exit 1
fi
SONAME_LINKS="$WORK_DIR/ruby-soname-links"
rm -rf "$SONAME_LINKS"
mkdir -p "$SONAME_LINKS"
ln -s "$PACK_ASSETS/usr/lib/libruby.so" "$SONAME_LINKS/libruby.so.${RUBY_MINOR%.*}"

verify_failures=0
verify_checked=0

verify_object() {
    local out
    verify_checked=$((verify_checked + 1))
    # The pack's own usr/lib, the install-time soname links, and the base APK's
    # usr/lib -- toolchains install into filesDir/usr beside it, so libssl,
    # libcrypto and libz resolve there at runtime. Without that last one the gate
    # would reject binaries that work.
    if ! out=$(python3 "$SCRIPT_DIR/verify-android-elf.py" "$1" \
                   --lib-dir "$PACK_ASSETS/usr/lib" \
                   --lib-dir "$SONAME_LINKS" \
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

rm -rf "$SONAME_LINKS"

if [ "$verify_failures" -gt 0 ]; then
    echo "" >&2
    echo "  ERROR: $verify_failures of $verify_checked Ruby binaries would fail to" >&2
    echo "         load on a device. Shipping them produces a working build and a" >&2
    echo "         broken toolchain." >&2
    exit 1
fi
echo "  $verify_checked binaries verified: architecture, dependencies, 16 KB alignment"

# The same question asked of the pack as a whole, rather than of the three files
# named in step 5b. Naming files at one call site is the shape that misses the
# fourth, and here that fourth would be a stdlib extension picking up Termux's
# shell after a version bump, found on a device rather than in this log. Every
# regular file at any depth, which is the whole pack: after step 5b it holds
# none, and that is the invariant this keeps true.
echo ""
echo "=== Verifying no file in the pack names a shell it cannot reach ==="
python3 "$SCRIPT_DIR/patch-default-shell.py" --check "$PACK_ASSETS"

# Asserted where it is relied on. Neither delivery path can carry a symbolic
# link: an asset pack cannot hold one, and ToolchainManager.extractZip writes
# every non-directory entry with a FileOutputStream, so a link in the release ZIP
# lands on a device as a text file holding the target path. The sonames this pack
# needs are created at install time from the manifest for exactly that reason.
# Both verification loops above are driven by `find -type f`, which steps over a
# link without a word, so nothing else here would notice one.
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
echo "=== Ruby Toolchain Size Summary ==="
echo "  Asset pack: $(du -sh "$PACK_ASSETS" | cut -f1) total"
echo "  ruby binary: $(du -sh "$PACK_ASSETS/usr/bin/ruby" 2>/dev/null | cut -f1)"
echo "  lib/ruby/: $(du -sh "$PACK_ASSETS/usr/lib/ruby" 2>/dev/null | cut -f1)"
echo ""
echo "Shared libraries:"
for f in "$PACK_ASSETS/usr/lib"/*.so*; do
    [ -f "$f" ] && echo "  $(basename "$f"): $(du -sh "$f" | cut -f1)"
done

echo ""
echo "=== Ruby download complete ==="
echo "Next: cd android && ./gradlew bundleDebug"
