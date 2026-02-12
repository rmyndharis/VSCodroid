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

TERMUX_REPO="${TERMUX_MIRROR:-https://packages.termux.dev/apt/termux-main}"
PACKAGES_URL="$TERMUX_REPO/dists/stable/main/binary-aarch64/Packages"

REQUIRED_PACKAGES=(
    openjdk-17
    libandroid-shmem
    libandroid-spawn
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

JAVA_VERSION=$(awk '
    /^Package: / { current = $2 }
    /^Version: / && current == "openjdk-17" { print $2; exit }
' Packages)
echo "  OpenJDK version: $JAVA_VERSION"

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
cp -r "$JDK_SRC" "$PACK_ASSETS/usr/lib/jvm/java-17-openjdk"

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
            echo "  WARNING: $soname not found in $pkg"
        fi
    done
done

# --- Step 5: Strip unnecessary files ---
echo ""
echo "Stripping unnecessary files..."
JDK_DIR="$PACK_ASSETS/usr/lib/jvm/java-17-openjdk"
BEFORE_SIZE=$(du -sk "$PACK_ASSETS/usr" | cut -f1)

# Strip demo, man, legal docs (keep src.zip for IDE source navigation)
rm -rf "$JDK_DIR/demo" 2>/dev/null || true
rm -rf "$JDK_DIR/man" 2>/dev/null || true
rm -rf "$JDK_DIR/legal" 2>/dev/null || true
# Strip jmods (module files, large, not needed at runtime)
rm -rf "$JDK_DIR/jmods" 2>/dev/null || true
# Strip header files (not needed without JNI compilation)
rm -rf "$JDK_DIR/include" 2>/dev/null || true

AFTER_SIZE=$(du -sk "$PACK_ASSETS/usr" | cut -f1)
echo "  Java: ${BEFORE_SIZE}K -> ${AFTER_SIZE}K (saved $((BEFORE_SIZE - AFTER_SIZE))K)"

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

# --- Step 7: Size summary ---
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
