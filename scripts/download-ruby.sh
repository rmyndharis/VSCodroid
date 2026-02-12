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
)

# Soname mapping for shared libraries
get_sonames() {
    case "$1" in
        ruby)    echo "libruby.so" ;;
        libgmp)  echo "libgmp.so" ;;
        libyaml) echo "libyaml-0.so" ;;
        *)       echo "" ;;
    esac
}

LIB_PACKAGES=(ruby libgmp libyaml)

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
    filename=$(awk -v pkg="$pkg" '
        /^Package: / { current = $2 }
        /^Filename: / && current == pkg { print $2; exit }
    ' Packages)

    if [ -z "$filename" ]; then
        echo "  ERROR: Package '$pkg' not found in index"
        exit 1
    fi
    echo "$pkg $filename" >> "$PKG_MAP_FILE"
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

# Copy ruby binaries (resolve symlinks with cp -L)
for bin in ruby irb gem bundle bundler erb rdoc ri; do
    src="$RUBY_USR/bin/$bin"
    if [ -f "$src" ] || [ -L "$src" ]; then
        cp -L "$src" "$PACK_ASSETS/usr/bin/$bin"
        echo "  bin/$bin"
    fi
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

# --- Step 6: Write manifest.json ---
echo ""
echo "Writing manifest.json..."

# Collect all binaries AND symlinks for the manifest (only include actually-copied files)
BINARIES='['
SYMLINKS='{'
FIRST_BIN=true
FIRST_SYM=true
for bin in ruby irb gem bundle bundler erb rdoc ri; do
    if [ -f "$PACK_ASSETS/usr/bin/$bin" ]; then
        [ "$FIRST_BIN" = true ] && FIRST_BIN=false || BINARIES+=','
        BINARIES+="\"usr/bin/$bin\""
        [ "$FIRST_SYM" = true ] && FIRST_SYM=false || SYMLINKS+=','
        SYMLINKS+="\"$bin\":\"usr/bin/$bin\""
    fi
done
BINARIES+=']'
SYMLINKS+='}'

cat > "$PACK_ASSETS/toolchain_ruby.json" << EOF
{
    "name": "ruby",
    "displayName": "Ruby",
    "version": "$RUBY_VERSION",
    "binaries": $BINARIES,
    "symlinks": $SYMLINKS,
    "env": {
        "GEM_HOME": "\$HOME/.gem/ruby",
        "GEM_PATH": "\$HOME/.gem/ruby:\$FILESDIR/usr/lib/ruby/gems"
    },
    "pathDirs": ["usr/bin"],
    "installRoot": "usr/lib/ruby",
    "libs": ["libruby.so", "libgmp.so", "libyaml-0.so"]
}
EOF
echo "  toolchain_ruby.json written"

# --- Step 7: Size summary ---
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
