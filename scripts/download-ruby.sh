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

TERMUX_REPO="${TERMUX_MIRROR:-https://packages.termux.dev/apt/termux-main}"
PACKAGES_URL="$TERMUX_REPO/dists/stable/main/binary-aarch64/Packages"

REQUIRED_PACKAGES=(
    ruby
    libgmp
    libyaml
    libandroid-execinfo
    # fiddle, Ruby's stdlib FFI, links libffi and it was never bundled -- the
    # extension shipped and could not load. Found by the ELF gate below.
    libffi
)

# Soname mapping for shared libraries
get_sonames() {
    case "$1" in
        ruby)                  echo "libruby.so" ;;
        libgmp)                echo "libgmp.so" ;;
        libyaml)               echo "libyaml-0.so" ;;
        libandroid-execinfo)   echo "libandroid-execinfo.so" ;;
        libffi)                echo "libffi.so" ;;
        *)                     echo "" ;;
    esac
}

LIB_PACKAGES=(ruby libgmp libyaml libandroid-execinfo libffi)

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

# --- Step 1: Download and parse Packages index ---
echo "Downloading Packages index..."
if [ ! -f Packages ] || [ -n "$(find Packages -mmin +60 2>/dev/null)" ]; then
    curl -L --fail --show-error -o Packages "$PACKAGES_URL"
    echo "  Downloaded: $(du -sh Packages | cut -f1)"
else
    echo "  Using cached Packages index (less than 1 hour old)"
fi

echo ""
echo "Resolving package URLs..."
PKG_MAP_FILE="$(mktemp)"
trap "rm -f '$PKG_MAP_FILE'" EXIT

for pkg in "${REQUIRED_PACKAGES[@]}"; do
    # One pass, emitting when the stanza ends: the live index carries duplicate
    # Package stanzas (gdk-pixbuf, neovim-nightly, ...), and two independent
    # scans could pair stanza A's Filename with stanza B's SHA256.
    pkg_line=$(awk -v pkg="$pkg" '
        /^Package: / { if (fn != "" && current == pkg) exit; current = $2; fn = ""; sh = "" }
        /^Filename: / && current == pkg { fn = $2 }
        /^SHA256: / && current == pkg { sh = $2 }
        END { if (fn != "") print fn, (sh == "" ? "-" : sh) }
    ' Packages)
    filename=${pkg_line% *}
    sha256=${pkg_line##* }

    if [ -z "$filename" ]; then
        echo "  ERROR: Package '$pkg' not found in index"
        exit 1
    fi
    echo "$pkg $filename ${sha256:--}" >> "$PKG_MAP_FILE"
    echo "  $pkg -> $(basename "$filename")"
done

RUBY_VERSION=$(awk '
    /^Package: / { current = $2 }
    /^Version: / && current == "ruby" { print $2; exit }
' Packages)
echo "  Ruby version: $RUBY_VERSION"

get_pkg_filename() {
    awk -v pkg="$1" '$1 == pkg { print $2; exit }' "$PKG_MAP_FILE"
}

# Helper to look up the index's SHA256 for a package ("-" when absent)
get_pkg_sha256() {
    awk -v pkg="$1" '$1 == pkg { print $3; exit }' "$PKG_MAP_FILE"
}

# These payloads execute on user devices and arrive over a third-party
# mirror. A file that does not match the index's SHA256 -- cached or fresh --
# must not be used.
verify_pkg_sha256() {
    local file="$1" expected="$2"
    if [ "$expected" = "-" ] || [ -z "$expected" ]; then
        # Fail closed: the index and the payload come from the same host, so a
        # mirror that drops the SHA256 line could otherwise switch the check
        # off silently. Every package in the real index carries one; its
        # absence is an anomaly, not a pass.
        echo "    ERROR: no SHA256 in index for $(basename "$file")" >&2
        return 1
    fi
    local actual
    actual=$( (sha256sum "$file" 2>/dev/null || shasum -a 256 "$file") | cut -d' ' -f1)
    if [ "$actual" != "$expected" ]; then
        echo "    ERROR: $(basename "$file") does not match the index" >&2
        echo "      index : $expected" >&2
        echo "      file  : $actual" >&2
        rm -f "$file"
        return 1
    fi
}

# --- Step 2: Download .deb files ---
echo ""
echo "Downloading .deb packages..."
mkdir -p debs
for pkg in "${REQUIRED_PACKAGES[@]}"; do
    filename="$(get_pkg_filename "$pkg")"
    debname="$(basename "$filename")"
    if [ -f "debs/$debname" ]; then
        echo "  $debname (cached)"
    else
        curl -L --fail --show-error -o "debs/$debname" "$TERMUX_REPO/$filename"
        echo "  $debname ($(du -sh "debs/$debname" | cut -f1))"
    fi
    verify_pkg_sha256 "debs/$debname" "$(get_pkg_sha256 "$pkg")"
done

# --- Step 3: Extract ---
echo ""
echo "Extracting packages..."
for pkg in "${REQUIRED_PACKAGES[@]}"; do
    filename="$(get_pkg_filename "$pkg")"
    debname="$(basename "$filename")"
    pkg_extract="extracted/$pkg"
    rm -rf "$pkg_extract"
    mkdir -p "$pkg_extract"
    (
        cd "$pkg_extract"
        bsdtar xf "../../debs/$debname" data.tar.xz data.tar.gz data.tar.zst 2>/dev/null || true
        if [ -f data.tar.xz ]; then
            tar xf data.tar.xz
        elif [ -f data.tar.gz ]; then
            tar xf data.tar.gz
        elif [ -f data.tar.zst ]; then
            zstd -d data.tar.zst -o data.tar && tar xf data.tar
        else
            echo "  ERROR: Could not extract data archive from $debname"
            exit 1
        fi
    )
    echo "  $pkg extracted"
done

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
if [ -d "$RUBY_USR/lib/ruby" ]; then
    cp -r "$RUBY_USR/lib/ruby" "$PACK_ASSETS/usr/lib/ruby"
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
            echo "  WARNING: $soname not found in $pkg"
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

# Detect Ruby minor version for RUBYLIB path (e.g. 3.4.1-2 → 3.4.0)
RUBY_MINOR=$(echo "$RUBY_VERSION" | sed 's/^[0-9]*://; s/\([0-9]*\.[0-9]*\)\..*/\1.0/')

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
    "libs": ["libruby.so", "libgmp.so", "libyaml-0.so", "libandroid-execinfo.so", "libffi.so"],
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
# runtime that is not in the pack -- and the placement step above only warns
# when a library is missing rather than failing. Without this the gate could
# report all 104 objects satisfied while the one library they all need was
# absent, which is precisely the failure it exists to catch.
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
