#!/usr/bin/env bash
set -euo pipefail

# Download pre-compiled Python 3 + pip from Termux APT repo.
# Places interpreter in jniLibs/arm64-v8a/ (.so trick), stdlib + pip in
# assets/usr/lib/python3.12/, and new shared libs in assets/usr/lib/.
#
# Dependencies already provided by download-termux-tools.sh are NOT re-downloaded:
#   libandroid-support, libexpat, ncurses, openssl, readline, zlib
#
# Compatible with bash 3.2+ (macOS default).

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"
ASSETS_DIR="$ROOT_DIR/android/app/src/main/assets"
JNILIBS_DIR="$ROOT_DIR/android/app/src/main/jniLibs/arm64-v8a"
WORK_DIR="$ROOT_DIR/toolchains/termux-packages"

TERMUX_REPO="${TERMUX_MIRROR:-https://packages.termux.dev/apt/termux-main}"
PACKAGES_URL="$TERMUX_REPO/dists/stable/main/binary-aarch64/Packages"

PYTHON_MAJOR_MINOR="3.12"

# Packages to download (only Python-specific, not shared deps already in termux-tools)
REQUIRED_PACKAGES=(
    python
    python-pip
    libffi
    libbz2
    liblzma
    libsqlite
    libcrypt
    libandroid-posix-semaphore
    gdbm
)

# Soname mapping for new shared libraries
get_sonames() {
    case "$1" in
        libffi)                      echo "libffi.so" ;;
        libbz2)                      echo "libbz2.so" ;;
        liblzma)                     echo "liblzma.so" ;;
        libsqlite)                   echo "libsqlite3.so" ;;
        libcrypt)                    echo "libcrypt.so" ;;
        libandroid-posix-semaphore)  echo "libandroid-posix-semaphore.so" ;;
        gdbm)                        echo "libgdbm.so" ;;
        *)                           echo "" ;;
    esac
}

# Packages that have shared libraries to extract
LIB_PACKAGES=(
    libffi libbz2 liblzma libsqlite libcrypt
    libandroid-posix-semaphore gdbm
)

echo "=== Downloading Python ${PYTHON_MAJOR_MINOR} + pip ==="
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

# Parse index to find Filename for each required package.
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

# --- Step 3: Extract all .deb packages ---
echo ""
echo "Extracting packages..."
for pkg in "${REQUIRED_PACKAGES[@]}"; do
    filename="$(get_pkg_filename "$pkg")"
    debname="$(basename "$filename")"
    pkg_extract="extracted/$pkg"
    # Re-extract python packages fresh each time
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
            ls -la
            exit 1
        fi
    )
    echo "  $pkg extracted"
done

# --- Step 4: Place Python interpreter in jniLibs ---
echo ""
echo "Placing Python interpreter in jniLibs..."
mkdir -p "$JNILIBS_DIR"

PYTHON_BIN="extracted/python/data/data/com.termux/files/usr/bin/python${PYTHON_MAJOR_MINOR}"
if [ -f "$PYTHON_BIN" ]; then
    cp "$PYTHON_BIN" "$JNILIBS_DIR/libpython.so"
    echo "  libpython.so ($(du -sh "$JNILIBS_DIR/libpython.so" | cut -f1))"
else
    echo "  ERROR: Python binary not found at $PYTHON_BIN"
    echo "  Looking for python..."
    find "extracted/python" -name "python*" -type f 2>/dev/null || true
    exit 1
fi

# --- Step 5: Place stdlib in assets ---
echo ""
echo "Placing Python stdlib..."
STDLIB_SRC="extracted/python/data/data/com.termux/files/usr/lib/python${PYTHON_MAJOR_MINOR}"
STDLIB_DST="$ASSETS_DIR/usr/lib/python${PYTHON_MAJOR_MINOR}"

if [ -d "$STDLIB_SRC" ]; then
    rm -rf "$STDLIB_DST"
    cp -r "$STDLIB_SRC" "$STDLIB_DST"

    # Strip test directories and bytecode to save space
    echo "  Stripping test directories and bytecode..."
    BEFORE_SIZE=$(du -sk "$STDLIB_DST" | cut -f1)
    find "$STDLIB_DST" -type d \( -name "test" -o -name "tests" -o -name "__pycache__" \) -exec rm -rf {} + 2>/dev/null || true
    find "$STDLIB_DST" -name "*.pyc" -delete 2>/dev/null || true
    # Also strip idle and turtle (GUI libs useless on Android)
    rm -rf "$STDLIB_DST/idlelib" "$STDLIB_DST/turtle.py" "$STDLIB_DST/turtledemo" 2>/dev/null || true
    AFTER_SIZE=$(du -sk "$STDLIB_DST" | cut -f1)

    echo "  Stdlib: ${BEFORE_SIZE}K -> ${AFTER_SIZE}K (saved $((BEFORE_SIZE - AFTER_SIZE))K)"
else
    echo "  ERROR: Python stdlib not found at $STDLIB_SRC"
    exit 1
fi

# --- Step 6: Place pip ---
echo ""
echo "Placing pip..."
PIP_SRC="extracted/python-pip/data/data/com.termux/files/usr/lib/python${PYTHON_MAJOR_MINOR}/site-packages"
PIP_DST="$STDLIB_DST/site-packages"

if [ -d "$PIP_SRC" ]; then
    mkdir -p "$PIP_DST"
    # Copy pip package
    cp -r "$PIP_SRC/pip" "$PIP_DST/" 2>/dev/null || true
    cp -r "$PIP_SRC"/pip-*.dist-info "$PIP_DST/" 2>/dev/null || true
    # Strip __pycache__ from pip too
    find "$PIP_DST" -type d -name "__pycache__" -exec rm -rf {} + 2>/dev/null || true
    echo "  pip installed to site-packages ($(du -sh "$PIP_DST/pip" 2>/dev/null | cut -f1))"
else
    echo "  WARNING: pip site-packages not found at $PIP_SRC"
    find "extracted/python-pip" -type d -name "pip" 2>/dev/null | head -3 || true
fi

# --- Step 7: Place shared libraries in assets/usr/lib/ ---
echo ""
echo "Placing shared libraries in assets/usr/lib/..."
mkdir -p "$ASSETS_DIR/usr/lib"

for pkg in "${LIB_PACKAGES[@]}"; do
    sonames="$(get_sonames "$pkg")"
    [ -z "$sonames" ] && continue
    pkg_lib_dir="extracted/$pkg/data/data/com.termux/files/usr/lib"
    for soname in $sonames; do
        src=""
        if [ -f "$pkg_lib_dir/$soname" ] || [ -L "$pkg_lib_dir/$soname" ]; then
            src="$pkg_lib_dir/$soname"
        else
            # Try unversioned name
            base_soname="$(echo "$soname" | sed 's/\.so\..*/\.so/')"
            if [ "$base_soname" != "$soname" ] && ( [ -f "$pkg_lib_dir/$base_soname" ] || [ -L "$pkg_lib_dir/$base_soname" ] ); then
                src="$pkg_lib_dir/$base_soname"
            fi
        fi

        if [ -n "$src" ] && ( [ -f "$src" ] || [ -L "$src" ] ); then
            cp -L "$src" "$ASSETS_DIR/usr/lib/$soname"
            echo "  $soname ($(du -sh "$ASSETS_DIR/usr/lib/$soname" | cut -f1))"
        else
            echo "  WARNING: $soname not found in $pkg (looked in $pkg_lib_dir)"
            [ -d "$pkg_lib_dir" ] && ls "$pkg_lib_dir"/*.so* 2>/dev/null | head -5 || true
        fi
    done
done

# Also need libpython shared library
# Binary links against versioned SONAME (libpython3.12.so.1.0), not unversioned
LIBPYTHON_SRC="extracted/python/data/data/com.termux/files/usr/lib"
LIBPYTHON_VERSIONED="libpython${PYTHON_MAJOR_MINOR}.so.1.0"
LIBPYTHON_UNVERSIONED="libpython${PYTHON_MAJOR_MINOR}.so"
if [ -f "$LIBPYTHON_SRC/$LIBPYTHON_VERSIONED" ] || [ -L "$LIBPYTHON_SRC/$LIBPYTHON_VERSIONED" ]; then
    cp -L "$LIBPYTHON_SRC/$LIBPYTHON_VERSIONED" "$ASSETS_DIR/usr/lib/$LIBPYTHON_VERSIONED"
    echo "  $LIBPYTHON_VERSIONED ($(du -sh "$ASSETS_DIR/usr/lib/$LIBPYTHON_VERSIONED" | cut -f1))"
elif [ -f "$LIBPYTHON_SRC/$LIBPYTHON_UNVERSIONED" ] || [ -L "$LIBPYTHON_SRC/$LIBPYTHON_UNVERSIONED" ]; then
    cp -L "$LIBPYTHON_SRC/$LIBPYTHON_UNVERSIONED" "$ASSETS_DIR/usr/lib/$LIBPYTHON_UNVERSIONED"
    echo "  $LIBPYTHON_UNVERSIONED ($(du -sh "$ASSETS_DIR/usr/lib/$LIBPYTHON_UNVERSIONED" | cut -f1))"
else
    echo "  NOTE: libpython shared lib not found (Python may be statically linked)"
fi

# --- Step 8: Size summary ---
echo ""
echo "=== Python Size Summary ==="

echo "Interpreter:"
echo "  libpython.so: $(du -sh "$JNILIBS_DIR/libpython.so" | cut -f1)"

echo ""
echo "Stdlib (assets/usr/lib/python${PYTHON_MAJOR_MINOR}/):"
echo "  $(du -sh "$STDLIB_DST" | cut -f1) total"

echo ""
echo "New shared libraries:"
for pkg in "${LIB_PACKAGES[@]}"; do
    sonames="$(get_sonames "$pkg")"
    for soname in $sonames; do
        [ -f "$ASSETS_DIR/usr/lib/$soname" ] && echo "  $soname: $(du -sh "$ASSETS_DIR/usr/lib/$soname" | cut -f1)"
    done
done
[ -f "$ASSETS_DIR/usr/lib/$LIBPYTHON_VERSIONED" ] && echo "  $LIBPYTHON_VERSIONED: $(du -sh "$ASSETS_DIR/usr/lib/$LIBPYTHON_VERSIONED" | cut -f1)"
[ -f "$ASSETS_DIR/usr/lib/$LIBPYTHON_UNVERSIONED" ] && echo "  $LIBPYTHON_UNVERSIONED: $(du -sh "$ASSETS_DIR/usr/lib/$LIBPYTHON_UNVERSIONED" | cut -f1)"

echo ""
echo "=== Python download complete ==="
echo "Next: cd android && ./gradlew assembleDebug"
