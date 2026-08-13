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

# Also grab the version
GO_VERSION=$(awk '
    /^Package: / { current = $2 }
    /^Version: / && current == "golang" { print $2; exit }
' Packages)
echo "  Go version: $GO_VERSION"

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

# The binaries list is what ToolchainManager chmods on install, and nothing else
# gives these files an execute bit: asset archives carry no permissions and the
# install copies them with Kotlin's copyTo, which does not preserve one. A
# binary missing from this list therefore arrives unrunnable.
#
# It used to name two, `go` and `gofmt`, and `go` does not compile anything by
# itself -- it forks compile, link, asm and the rest out of pkg/tool. Those
# arrived without the bit, so `go build` failed with a permission error on both
# delivery channels while the build stayed green. Someone had hand-corrected the
# generated manifest on a working copy, but this file is gitignored, so the
# correction could not travel and the next run overwrote it.
#
# Derived now, from exactly the set the verification step below sweeps: every ELF
# under bin/ and pkg/tool/. The two agree by construction rather than by anyone
# remembering to update both. The tool directory is found by traversal because
# its name carries the platform, and the set is not fixed either -- Go 1.25.6
# ships seven tools and 1.26.5 ships eight, which is precisely why a written list
# was stale before it was written.
GO_DIR="$PACK_ASSETS/usr/lib/go"
for required in "$GO_DIR/bin" "$GO_DIR/pkg/tool"; do
    if [ ! -d "$required" ]; then
        echo "  ERROR: $required is missing from the pack -- the layout moved" >&2
        exit 1
    fi
done

is_elf() {
    [ "$(dd if="$1" bs=4 count=1 2>/dev/null | od -An -tx1 | tr -d ' \n')" = "7f454c46" ]
}

GO_BINARIES='['
FIRST_BIN=true
while IFS= read -r obj; do
    is_elf "$obj" || continue
    [ "$FIRST_BIN" = true ] && FIRST_BIN=false || GO_BINARIES+=','
    GO_BINARIES+="\"usr/lib/go/${obj#$GO_DIR/}\""
done < <(find "$GO_DIR/bin" "$GO_DIR/pkg/tool" -type f | sort)
GO_BINARIES+=']'
echo "  binaries: $(echo "$GO_BINARIES" | tr ',' '\n' | wc -l | tr -d ' ') entries derived from the pack"

# The symlinks stay bin/-only on purpose. They put a command on PATH, and only
# `go` and `gofmt` are meant to be typed; the pkg/tool binaries are forked by
# `go` through GOROOT and would only clutter usr/bin with names no one calls.
cat > "$PACK_ASSETS/toolchain_go.json" << EOF
{
    "name": "go",
    "displayName": "Go",
    "version": "$GO_VERSION",
    "binaries": $GO_BINARIES,
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

# --- Step 7: Verify the binaries the device will actually run ---
echo ""
echo "=== Verifying Go binaries ==="
# The pack reaches devices through Play Asset Delivery and the release ZIPs, and
# nothing checked that anything in it could load. A wrong-architecture,
# misaligned or dependency-missing binary produces a green build, a ZIP that
# uploads, and a `go build` that dies on someone's phone with a linker message
# nobody sees. The digest check cannot catch it: upstream's hash covers whatever
# is in the file, correct or not.
#
# Gated: bin/ and pkg/tool/ -- what the device executes. Not the whole pack, and
# that is deliberate. Step 5 above keeps src/ on purpose ("needed for go build"),
# and a Go source tree legitimately carries foreign-architecture .syso link-time
# objects: measured in this pack, ten of them across x86-64, ppc64le, s390x,
# loong64 and riscv64, under src/runtime/race/ and src/crypto/internal/boring/.
# They are inputs a cross-compiling linker may consume, never code Android loads.
#
# An exemption list over those paths was the obvious alternative and is a trap.
# Two measurements a few hours apart disagreed on both the count and the set --
# the second found a riscv64 object the first did not name. A list that rots
# between two readings of the same pack will rot in CI, and each widening erodes
# what the gate means. Naming what runs does not rot: a wrong-arch bin/go still
# fails, and a new .syso in src/ never becomes the gate's business.
verify_failures=0
verify_checked=0

verify_object() {
    local out
    verify_checked=$((verify_checked + 1))
    # The toolchain installs into filesDir/usr alongside the base APK's own
    # libraries, so both directories are where a dependency may legitimately
    # live. Without them the gate would reject binaries that work.
    if ! out=$(python3 "$SCRIPT_DIR/verify-android-elf.py" "$1" \
                   --lib-dir "$PACK_ASSETS/usr/lib" \
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
done < <(find "$GO_DIR/bin" "$GO_DIR/pkg/tool" -type f)

if [ "$verify_failures" -gt 0 ]; then
    echo "" >&2
    echo "  ERROR: $verify_failures of $verify_checked Go binaries would fail to" >&2
    echo "         load on a device. Shipping them produces a working build and a" >&2
    echo "         broken toolchain." >&2
    exit 1
fi
echo "  $verify_checked binaries verified: architecture, dependencies, 16 KB alignment"

# --- Step 8: Size summary ---
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
