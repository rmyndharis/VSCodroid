#!/usr/bin/env bash
set -euo pipefail

# Download pre-compiled Go from Termux APT repo.
# Places files in the toolchain_go asset pack module for Play Asset Delivery.
#
# Go is self-contained (CGO_ENABLED=0) — no shared lib dependencies needed.
#
# Compatible with bash 3.2+ (macOS default).

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"
PACK_ASSETS="$ROOT_DIR/android/toolchain_go/src/main/assets"
WORK_DIR="$ROOT_DIR/toolchains/termux-packages"

TERMUX_REPO="${TERMUX_MIRROR:-https://packages.termux.dev/apt/termux-main}"
PACKAGES_URL="$TERMUX_REPO/dists/stable/main/binary-aarch64/Packages"

REQUIRED_PACKAGES=(
    golang
)

echo "=== Downloading Go Toolchain ==="
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

# Also grab the version
GO_VERSION=$(awk '
    /^Package: / { current = $2 }
    /^Version: / && current == "golang" { print $2; exit }
' Packages)
echo "  Go version: $GO_VERSION"

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
echo "Placing Go toolchain in asset pack..."
rm -rf "$PACK_ASSETS/usr"
mkdir -p "$PACK_ASSETS/usr/lib"

GO_SRC="extracted/golang/data/data/com.termux/files/usr/lib/go"
if [ ! -d "$GO_SRC" ]; then
    echo "  ERROR: Go directory not found at $GO_SRC"
    find "extracted/golang" -maxdepth 4 -type d 2>/dev/null | head -10
    exit 1
fi

cp -r "$GO_SRC" "$PACK_ASSETS/usr/lib/go"

# --- Step 5: Strip unnecessary files ---
echo "Stripping unnecessary files..."
BEFORE_SIZE=$(du -sk "$PACK_ASSETS/usr/lib/go" | cut -f1)

rm -rf "$PACK_ASSETS/usr/lib/go/test" 2>/dev/null || true
rm -rf "$PACK_ASSETS/usr/lib/go/doc" 2>/dev/null || true
rm -rf "$PACK_ASSETS/usr/lib/go/api" 2>/dev/null || true
# Keep pkg/ (compiled stdlib), src/ (needed for go build), bin/ (go, gofmt)
find "$PACK_ASSETS/usr/lib/go" -name "*.md" -delete 2>/dev/null || true
find "$PACK_ASSETS/usr/lib/go" -name "PATENTS" -delete 2>/dev/null || true
find "$PACK_ASSETS/usr/lib/go" -name "AUTHORS" -delete 2>/dev/null || true
find "$PACK_ASSETS/usr/lib/go" -name "CONTRIBUTORS" -delete 2>/dev/null || true

AFTER_SIZE=$(du -sk "$PACK_ASSETS/usr/lib/go" | cut -f1)
echo "  Go: ${BEFORE_SIZE}K -> ${AFTER_SIZE}K (saved $((BEFORE_SIZE - AFTER_SIZE))K)"

# --- Step 6: Write manifest.json ---
echo ""
echo "Writing manifest.json..."
cat > "$PACK_ASSETS/toolchain_go.json" << EOF
{
    "name": "go",
    "displayName": "Go",
    "version": "$GO_VERSION",
    "binaries": [
        "usr/lib/go/bin/go",
        "usr/lib/go/bin/gofmt"
    ],
    "symlinks": {
        "go": "usr/lib/go/bin/go",
        "gofmt": "usr/lib/go/bin/gofmt"
    },
    "env": {
        "GOROOT": "\$FILESDIR/usr/lib/go",
        "GOPATH": "\$HOME/go",
        "CGO_ENABLED": "0"
    },
    "pathDirs": ["usr/lib/go/bin"],
    "installRoot": "usr/lib/go",
    "libs": []
}
EOF
echo "  toolchain_go.json written"

# --- Step 7: Size summary ---
echo ""
echo "=== Go Toolchain Size Summary ==="
echo "  Asset pack: $(du -sh "$PACK_ASSETS" | cut -f1) total"
echo "  go binary: $(du -sh "$PACK_ASSETS/usr/lib/go/bin/go" 2>/dev/null | cut -f1)"
echo "  gofmt binary: $(du -sh "$PACK_ASSETS/usr/lib/go/bin/gofmt" 2>/dev/null | cut -f1)"
echo "  stdlib (src/): $(du -sh "$PACK_ASSETS/usr/lib/go/src" 2>/dev/null | cut -f1)"
echo "  pkg/: $(du -sh "$PACK_ASSETS/usr/lib/go/pkg" 2>/dev/null | cut -f1)"

echo ""
echo "=== Go download complete ==="
echo "Next: cd android && ./gradlew bundleDebug"
