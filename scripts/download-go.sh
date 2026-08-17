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

# The index fetch, its signature check, package resolution and the digest check
# on each .deb, shared with every other script that takes packages from Termux.
# It also picks the mirror; TERMUX_MIRROR still overrides it.
. "$SCRIPT_DIR/lib/termux-packages.sh"

REQUIRED_PACKAGES=(
    golang
)

echo "=== Downloading Go Toolchain ==="
echo ""

mkdir -p "$WORK_DIR"
cd "$WORK_DIR"

# --- Step 1: the package index, its signature, and what it resolves to ---
termux_fetch_index
termux_resolve_packages resolved-go.tsv "${REQUIRED_PACKAGES[@]}"
GO_VERSION="$(termux_pkg_version golang)"
echo "  Go version: $GO_VERSION"

# --- Step 2: Download .deb files, each checked against the signed index ---
termux_download_packages "${REQUIRED_PACKAGES[@]}"

# --- Step 3: Extract ---
termux_extract_packages "${REQUIRED_PACKAGES[@]}"

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
