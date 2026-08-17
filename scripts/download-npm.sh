#!/usr/bin/env bash
set -euo pipefail

# Download npm from the official Node.js tarball.
# Extracts lib/node_modules/npm/ to assets/usr/lib/node_modules/npm/.
# Strips docs, man pages, and test files to minimize size.
#
# npm comes from the Node release the app actually runs, not from a number of
# its own.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"
ASSETS_DIR="$ROOT_DIR/android/app/src/main/assets"
WORK_DIR="$ROOT_DIR/toolchains/build/npm"

# Read from the version the native addons are compiled against. The runtime has
# to equal that -- an addon built for one Node and loaded by another is the
# defect check_pair exists for -- so it is the one place this repository states
# which Node this app is.
#
# This was pinned to v20.18.1 on its own, a leftover from the hand-cross-compiled
# runtime abandoned for segfaulting inside several CLI tools. The app therefore
# shipped npm from a Node release it no longer ran, and nothing compared the two.
# Nothing was broken by it -- npm 10 supports Node >=20.5.0 -- which is exactly
# why it survived a runtime bump unnoticed.
NODE_MAJOR_MINOR_PATCH=$(sed -n 's/^NODE_VERSION="${NODE_VERSION:-\([0-9.]*\)}".*/\1/p' \
    "$SCRIPT_DIR/build-native-addons.sh" | head -1)
if [ -z "$NODE_MAJOR_MINOR_PATCH" ]; then
    echo "ERROR: could not read NODE_VERSION from build-native-addons.sh." >&2
    echo "       npm is sourced from the Node release the app runs; without that" >&2
    echo "       number there is nothing to source it from." >&2
    exit 1
fi
NODE_VERSION="v$NODE_MAJOR_MINOR_PATCH"

# What that release carries. Declared rather than derived because scripts/
# consumers read it without downloading anything -- device-test.sh falls back to
# it when a checkout has no assets tree -- and it is asserted against the tarball
# below, so it cannot quietly disagree with what ships.
NPM_VERSION="11.16.0"
NODE_TARBALL="node-${NODE_VERSION}-linux-arm64.tar.xz"
NODE_URL="https://nodejs.org/dist/${NODE_VERSION}/${NODE_TARBALL}"

# What that file must hash to, recorded here rather than read from
# SHASUMS256.txt. nodejs.org serves the payload and the checksum from one host
# under one path prefix, so together they prove only that the bytes arrived
# intact: whoever can substitute one can substitute the other, and this side
# would compare the substitution against itself and call it verified. Pinning it
# makes this repository the party that says what the tarball is, which is the
# same reason download-java.sh carries SPAWN_SHA_*.
#
# What it does not buy: nothing here establishes that this digest was ever the
# right one. It was taken from nodejs.org once, by hand, and its whole value is
# that it stops changing after that. A first fetch of a substituted tarball
# would have pinned the substitution.
#
# Bound to the NODE_VERSION authored in build-native-addons.sh, which is where
# the headers digest sits too. A version move is three edits across two files,
# and this is the one furthest from the number, so it is the one that gets
# forgotten. The published file is still fetched below, as the cross-check that
# says which of the two is stale.
NODE_TARBALL_SHA256="58c9520501f6ae2b52d5b210444e24b9d0c029a58c5011b797bc1fe7105886f6"

DEST_DIR="$ASSETS_DIR/usr/lib/node_modules/npm"

echo "=== Downloading npm ${NPM_VERSION} (from Node.js ${NODE_VERSION}) ==="
echo ""

mkdir -p "$WORK_DIR"

# --- Step 1: Download Node.js tarball ---
echo "Downloading Node.js tarball..."
if [ -f "$WORK_DIR/$NODE_TARBALL" ]; then
    echo "  Using cached $NODE_TARBALL"
else
    curl -L --fail --show-error -o "$WORK_DIR/$NODE_TARBALL" "$NODE_URL"
    echo "  Downloaded: $(du -sh "$WORK_DIR/$NODE_TARBALL" | cut -f1)"
fi

# npm ships inside the APK, so the tarball it comes from is held to the digest
# above. A pin is also the answer offline and on a cold cache alike, which is
# what the sidecar beside the cache used to buy and why it is gone: two records
# of one expected value is one record too many.
#
# SHASUMS256.txt is still read, and only to tell the two failure modes apart. A
# file that matches nothing has either been substituted in transit or is the
# right file for a NODE_VERSION the pin has not caught up with, and those want
# opposite responses from whoever is reading.
#
# The cache is restored across CI runs by restore-keys, so a sidecar written by
# an earlier run outlives the code that wrote it and sits in the work directory
# naming an expected value nothing reads. Globbed rather than named, because
# work directories in use today still hold the sidecar of a Node release this
# app no longer ships, which the current name would walk past.
rm -f "$WORK_DIR"/node-*.tar.xz.sha256

published=$(curl -sL "https://nodejs.org/dist/${NODE_VERSION}/SHASUMS256.txt" 2>/dev/null \
    | awk -v f="$NODE_TARBALL" '$2 == f { print $1; exit }' || true)
if [ -n "$published" ] && [ "$published" != "$NODE_TARBALL_SHA256" ]; then
    echo "  WARNING: nodejs.org does not agree with the digest this script pins" >&2
    echo "    pinned    : $NODE_TARBALL_SHA256" >&2
    echo "    published : $published" >&2
    echo "    If NODE_VERSION moved, update NODE_TARBALL_SHA256 to the published" >&2
    echo "    value. If it did not, a release was republished and that is worth" >&2
    echo "    understanding before this build is trusted." >&2
fi
actual=$( (sha256sum "$WORK_DIR/$NODE_TARBALL" 2>/dev/null || shasum -a 256 "$WORK_DIR/$NODE_TARBALL") | cut -d' ' -f1)
if [ "$actual" != "$NODE_TARBALL_SHA256" ]; then
    # Left on disk. When the expected value came from upstream a mismatch meant
    # a damaged download and deleting it was the fix; now it just as easily
    # means a stale pin, in which case the file is the correct one and deleting
    # it throws away a 28 MB download on every run without changing the verdict.
    echo "  ERROR: $NODE_TARBALL does not match the digest this script pins" >&2
    echo "    pinned : $NODE_TARBALL_SHA256" >&2
    echo "    file   : $actual" >&2
    echo "    Remove $WORK_DIR/$NODE_TARBALL to refetch it." >&2
    exit 1
fi
echo "  sha256: matches the pinned digest"

# --- Step 2: Extract only npm ---
echo ""
echo "Extracting npm from tarball..."
rm -rf "$WORK_DIR/extracted"
mkdir -p "$WORK_DIR/extracted"

# Extract only the npm directory (lib/node_modules/npm/)
tar xf "$WORK_DIR/$NODE_TARBALL" \
    -C "$WORK_DIR/extracted" \
    --strip-components=1 \
    "node-${NODE_VERSION}-linux-arm64/lib/node_modules/npm"

NPM_SRC="$WORK_DIR/extracted/lib/node_modules/npm"

if [ ! -d "$NPM_SRC" ]; then
    echo "  ERROR: npm not found in tarball"
    exit 1
fi

# Verify version
EXTRACTED_VERSION=$(node -e "console.log(require('$NPM_SRC/package.json').version)" 2>/dev/null || \
    python3 -c "import json; print(json.load(open('$NPM_SRC/package.json'))['version'])" 2>/dev/null || \
    grep '"version"' "$NPM_SRC/package.json" | head -1 | sed 's/.*"\([0-9.]*\)".*/\1/')

echo "  npm version: $EXTRACTED_VERSION"
if [ "$EXTRACTED_VERSION" != "$NPM_VERSION" ]; then
    # Fatal, not a warning. NPM_VERSION is read by other scripts as the answer to
    # "which npm ships", so a warning here means they answer with a number the
    # build already knew was wrong -- and the build carries on and ships the
    # other one.
    echo "  ERROR: NPM_VERSION says $NPM_VERSION but $NODE_VERSION carries $EXTRACTED_VERSION." >&2
    echo "         Update NPM_VERSION in this script to $EXTRACTED_VERSION." >&2
    exit 1
fi

# --- Step 3: Strip unnecessary files ---
echo ""
echo "Stripping docs, tests, and unnecessary files..."
BEFORE_SIZE=$(du -sk "$NPM_SRC" | cut -f1)

# Remove docs and changelogs. Not LICENSE: npm is Artistic-2.0 and that file is
# the notice the licence requires to travel with a copy. NOTICE.md attributes
# npm by document and ships in the APK, but a document about the tree is not the
# notice inside it, and this is the copy being redistributed.
rm -rf "$NPM_SRC/docs" "$NPM_SRC/doc" "$NPM_SRC/man"
rm -f "$NPM_SRC/CHANGELOG.md" "$NPM_SRC/README.md"
rm -f "$NPM_SRC/changelogs"*

# Remove Windows-specific files
find "$NPM_SRC" -name "*.cmd" -delete 2>/dev/null || true
find "$NPM_SRC" -name "*.ps1" -delete 2>/dev/null || true
find "$NPM_SRC" -name "*.bat" -delete 2>/dev/null || true

# Remove test directories
find "$NPM_SRC" -type d -name "test" -exec rm -rf {} + 2>/dev/null || true
find "$NPM_SRC" -type d -name "tests" -exec rm -rf {} + 2>/dev/null || true
find "$NPM_SRC" -type d -name "__tests__" -exec rm -rf {} + 2>/dev/null || true
find "$NPM_SRC" -type d -name "tap-snapshots" -exec rm -rf {} + 2>/dev/null || true

# Remove other unnecessary files.
#
# The *.md sweep is where most dependency licences went: npm's dependencies ship
# theirs as LICENSE.md far more often than as a bare LICENSE, so a rule aimed at
# READMEs took 28 of them with it. -iname rather than -name, because two are
# lowercase (ms/license.md, debug/node_modules/ms/license.md).
#
# AUTHORS and CONTRIBUTORS are no longer swept either. There is one such file in
# the tree, spdx-expression-parse/AUTHORS, and its LICENSE reads
# "Copyright (c) 2015 Kyle E. Mitchell & other authors listed in AUTHORS" --
# deleting it removes half of the notice the licence requires to be kept.
find "$NPM_SRC" -name "*.md" -not -name "package.json" \
    -not -iname "license*" -not -iname "licence*" \
    -not -iname "copying*" -not -iname "notice*" -delete 2>/dev/null || true
find "$NPM_SRC" -name ".npmignore" -delete 2>/dev/null || true
find "$NPM_SRC" -name ".eslintrc*" -delete 2>/dev/null || true
find "$NPM_SRC" -name ".gitignore" -delete 2>/dev/null || true
find "$NPM_SRC" -name "Makefile" -delete 2>/dev/null || true

AFTER_SIZE=$(du -sk "$NPM_SRC" | cut -f1)
echo "  Before: $((BEFORE_SIZE / 1024))M -> After: $((AFTER_SIZE / 1024))M (saved $((( BEFORE_SIZE - AFTER_SIZE ) / 1024))M)"

# What the sweep above was allowed to keep, counted rather than asserted.
#
# The licences survive by four -not clauses inside one find, and the tree they
# produce is gitignored, so it appears in no diff and nothing else in this
# repository reads it. A later tidy-up that drops a clause would put the notices
# back in the bin with every gate still green, which is how they were lost the
# first time. Counting them here makes the measurement the build's.
#
# The ceiling is 4, not 0: npm 11.16.0 has four package roots carrying no
# licence text of any kind, and a pristine extraction of the same tarball has
# the same four, so that residue is upstream's rather than this script's. An
# NPM_VERSION bump that moves the number is meant to stop here and be read
# rather than waved through.
UNLICENSED_MAX=4

# A package root is an immediate child of a node_modules directory, or of an
# @scope inside one. Nested node_modules count, since those copies are
# redistributed too.
package_roots() {
    find "$NPM_SRC" -type d -name node_modules \
        -exec find {} -mindepth 1 -maxdepth 1 -type d \; |
    while IFS= read -r dir; do
        case "${dir##*/}" in
            @*) find "$dir" -mindepth 1 -maxdepth 1 -type d ;;
            *)  printf '%s\n' "$dir" ;;
        esac
    done
}

roots=0
unlicensed=0
while IFS= read -r pkg; do
    [ -n "$pkg" ] || continue
    roots=$((roots + 1))
    # Substituted rather than piped into a reader that stops early: with
    # pipefail set, a reader closing the pipe first would report the find as
    # failed and count a package that does carry its licence.
    if [ -z "$(find "$pkg" -maxdepth 1 -type f \
            \( -iname "licen[sc]e*" -o -iname "copying*" -o -iname "notice*" \))" ]; then
        unlicensed=$((unlicensed + 1))
    fi
done <<EOF
$(package_roots)
EOF

if [ "$roots" -eq 0 ]; then
    echo "  ERROR: no package roots found under $NPM_SRC/node_modules" >&2
    echo "         The count below would be vacuously fine, so it is refused" >&2
    echo "         instead: the layout changed and this check stopped seeing" >&2
    echo "         what it counts." >&2
    exit 1
fi
if [ ! -f "$NPM_SRC/LICENSE" ]; then
    echo "  ERROR: $NPM_SRC/LICENSE was removed" >&2
    echo "         npm is Artistic-2.0 and that file is the notice the licence" >&2
    echo "         requires to travel with the copy being redistributed." >&2
    exit 1
fi
if [ "$unlicensed" -gt "$UNLICENSED_MAX" ]; then
    echo "  ERROR: $unlicensed of $roots package roots ship no licence file, at most $UNLICENSED_MAX expected" >&2
    echo "         Every one of them is redistributed inside the APK. Check the" >&2
    echo "         *.md sweep above for a clause that went missing, or, if npm" >&2
    echo "         itself changed, confirm the new residue and move the ceiling." >&2
    exit 1
fi
echo "  Licences: $roots package roots, $unlicensed without one (upstream's own residue)"

# --- Step 4: Verify entry points ---
echo ""
echo "Verifying entry points..."
for entry in "bin/npm-cli.js" "bin/npx-cli.js"; do
    if [ -f "$NPM_SRC/$entry" ]; then
        echo "  OK: $entry"
    else
        echo "  ERROR: $entry not found!"
        exit 1
    fi
done

# --- Step 5: Place in assets ---
echo ""
echo "Placing npm in assets..."
rm -rf "$DEST_DIR"
mkdir -p "$(dirname "$DEST_DIR")"
cp -r "$NPM_SRC" "$DEST_DIR"

FINAL_SIZE=$(du -sk "$DEST_DIR" | cut -f1)
FILE_COUNT=$(find "$DEST_DIR" -type f | wc -l | tr -d ' ')

echo ""
echo "=== npm Download Complete ==="
echo "  Version: $EXTRACTED_VERSION"
echo "  Size: $((FINAL_SIZE / 1024))M ($FILE_COUNT files)"
echo "  Location: $DEST_DIR"
echo ""
echo "Wrapper scripts (usr/bin/npm, usr/bin/npx) are generated at runtime"
echo "by FirstRunSetup.kt since they need absolute paths to libnode.so."
