#!/usr/bin/env bash
set -euo pipefail

# Download pre-built extensions from Open VSX and extract them into
# android/app/src/main/assets/extensions/ for bundling in the APK.
#
# Each extension becomes a directory like PKief.material-icon-theme-5.17.0/
# which FirstRunSetup extracts on device and registers via extensions.json.
#
# Compatible with bash 3.2+ (macOS default).

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"
ASSETS_DIR="$ROOT_DIR/android/app/src/main/assets/extensions"
WORK_DIR="$ROOT_DIR/toolchains/extensions"

# Extensions to bundle: publisher.name or publisher.name@version
#
# Every one is pinned to the newest STABLE version whose engines.vscode is
# satisfied by the VSCODE_VERSION file. Stable matters as much as compatible:
# GitLens publishes pre-release builds far more often than releases, so "newest
# compatible" lands on one — and its pre-releases carry an expiry date. Picking
# one ships an extension that works in testing and then puts an "this
# pre-release has expired" error in front of every user some weeks later. Leaving one unpinned would resolve to
# whatever is newest on the day of the build, and an extension needing a newer VS
# Code than the server does not error — it simply never activates, so the feature
# is missing with nothing in the log to explain it. The check after extraction
# below turns that into a failed build instead.
#
# Re-derive the pins after bumping VSCODE_VERSION; the comments say what is
# holding one back.
#
# GitLens is deliberately absent. VS Code's own SCM view, inline blame and diff
# editor cover what this app needs, and GitLens is 22 MB plus a walkthrough and
# a welcome view on first run. Anyone who wants it can install it from Open VSX.
EXTENSIONS=(
    "PKief.material-icon-theme@5.37.0"
    "esbenp.prettier-vscode@12.4.0"
    "ms-python.python@2026.4.0"
    "dbaeumer.vscode-eslint@3.0.34"
    "bradlc.vscode-tailwindcss@0.16.0"
)

OPENVSX_API="https://open-vsx.org/api"

# Every entry must name a version, checked before anything below reads the list.
#
# The sweep further down derives a directory name with "${entry%@*}-${entry##*@}",
# and neither strip does anything to an entry containing no "@" -- foo.bar becomes
# foo.bar-foo.bar, which matches nothing that is ever extracted. The tree such an
# entry really produces, foo.bar-<resolved version>, is therefore absent from the
# managed set and untracked by git, so the sweep deletes it on every single run.
# That also destroys the tree the offline path at the bottom of the loop falls
# back on, turning "Open VSX unreachable" from a note into a failed build.
#
# Refused rather than skipped, because the header above already requires pins for
# an independent reason: unpinned resolves to whatever is newest on the day of the
# build, and an extension whose engines.vscode outruns the server is registered,
# never activates, and logs nothing. An entry with no version has no correct
# handling to fall back on, so there is nothing for a skip to do but hide it.
for entry in "${EXTENSIONS[@]}"; do
    case "$entry" in
        *@*) ;;
        *)
            echo "  FAIL   $entry names no version." >&2
            echo "         Pin it as publisher.name@version. Unpinned entries resolve to" >&2
            echo "         whatever is newest that day, and the cleanup sweep cannot compute" >&2
            echo "         their directory name, so it deletes the extracted tree every run." >&2
            exit 1 ;;
    esac
done

echo "=== Downloading bundled extensions from Open VSX ==="

mkdir -p "$ASSETS_DIR"

# Remove extensions this script no longer manages, before placing the ones it
# does. Nothing here ever deleted, and the loop below skips a directory that
# already exists -- so an extension dropped from the list above stayed in the
# tree and shipped, which is how a removed 22 MB extension kept appearing in
# APKs long after it was taken out. In CI it also came back from the assets
# cache: restore-keys falls back to any earlier tree, and without this sweep
# the run then saved that tree forward under its own key.
#
# The discriminator is git, not a name pattern: this project's own extensions
# are committed here, downloaded ones are not. Where git cannot answer, keep
# everything -- a sweep that guesses is worse than no sweep at all.
if git -C "$ROOT_DIR" rev-parse --git-dir >/dev/null 2>&1; then
    managed=""
    for entry in "${EXTENSIONS[@]}"; do
        managed="$managed ${entry%@*}-${entry##*@}"
    done
    for dir in "$ASSETS_DIR"/*/; do
        [ -d "$dir" ] || continue
        name=$(basename "$dir")
        case " $managed " in *" $name "*) continue ;; esac
        if git -C "$ROOT_DIR" ls-files --error-unmatch \
            "android/app/src/main/assets/extensions/$name" >/dev/null 2>&1; then
            continue
        fi
        echo "  Removing extension no longer bundled: $name ($(du -sh "$dir" | cut -f1))"
        rm -rf "$dir"
    done
else
    echo "  NOTE: not a git checkout; leaving existing extension directories alone"
fi
mkdir -p "$WORK_DIR"

for EXT_SPEC in "${EXTENSIONS[@]}"; do
    # publisher.name@version. The unpinned form is refused above, so both halves
    # are always present here and neither branch that handled their absence
    # survives -- an entry with no version can no longer reach this loop.
    EXT_ID="${EXT_SPEC%%@*}"
    PINNED_VERSION="${EXT_SPEC#*@}"

    PUBLISHER="${EXT_ID%%.*}"
    NAME="${EXT_ID#*.}"

    echo ""
    echo "--- $PUBLISHER/$NAME @$PINNED_VERSION ---"

    # Query Open VSX API for version metadata
    API_URL="$OPENVSX_API/$PUBLISHER/$NAME/$PINNED_VERSION"
    METADATA_FILE="$WORK_DIR/${EXT_ID}-${PINNED_VERSION}.json"

    if [ ! -f "$METADATA_FILE" ]; then
        echo "  Fetching metadata..."
        curl -sL --fail --show-error \
            -H "Accept: application/json" \
            -o "$METADATA_FILE" \
            "$API_URL"
    fi

    # A pre-release build stops working on a date rather than on a version, so
    # it passes every check here and every test on device, then expires weeks
    # later in front of the user. Open VSX records which kind this is; the pin is
    # required to name a release.
    IS_PRERELEASE=$(python3 -c "import json; print(json.load(open('$METADATA_FILE')).get('preRelease'))")
    if [ "$IS_PRERELEASE" = "True" ]; then
        echo "  FAIL   $EXT_ID $PINNED_VERSION is a pre-release; pin a release instead." >&2
        echo "         Pre-releases expire on a date and take the feature with them." >&2
        exit 1
    fi

    # Extract version and download URL
    VERSION=$(python3 -c "import json; print(json.load(open('$METADATA_FILE'))['version'])")
    DOWNLOAD_URL=$(python3 -c "import json; d=json.load(open('$METADATA_FILE')); print(d['files']['download'])")

    DIR_NAME="${EXT_ID}-${VERSION}"
    DEST_DIR="$ASSETS_DIR/$DIR_NAME"

    echo "  Version: $VERSION"
    echo "  Download URL: $DOWNLOAD_URL"

    # files.sha256 sits right next to files.download in the metadata this loop
    # already parses, and it is read here -- above the fast path -- rather than
    # after it. The skip below used to jump over the comparison, the download
    # and the engines check together, so an extracted tree was a tree nothing
    # had ever verified, while the comment on the comparison claimed it failed
    # closed "cached files included". CI's locked cache key narrowed that but
    # did not close it: restore-keys falls back to any earlier tree.
    SHA256_URL=$(python3 -c "import json; print(json.load(open('$METADATA_FILE'))['files'].get('sha256', ''))")
    if [ -z "$SHA256_URL" ]; then
        echo "  FAIL   $EXT_ID: metadata carries no files.sha256" >&2
        exit 1
    fi
    # Unreachable is not the same as absent, and download-npm.sh already draws
    # that line: a rebuild with no network still verifies what it has instead of
    # dying in the fetch. The stamp below records the digest this tree was
    # extracted against, so offline the claim narrows honestly from "still
    # matches what Open VSX publishes" to "matched it when it was extracted".
    STAMP="$WORK_DIR/${DIR_NAME}.verified"
    # `|| true` binds to the assignment, not to the pipeline inside it, so a
    # failed fetch lands as an empty EXPECTED and is decided below rather than
    # aborting under set -e with only curl's message. --show-error keeps that
    # message: it says whether this was no network or a 404, and those want
    # different responses from whoever is reading.
    EXPECTED=$(curl -sL --fail --show-error "$SHA256_URL" | awk '{print $1}' || true)

    # Skip the download and the extraction, never the verification.
    if [ -d "$DEST_DIR" ] && [ -f "$DEST_DIR/package.json" ] && [ -f "$STAMP" ]; then
        RECORDED=$(cat "$STAMP")
        if [ -z "$EXPECTED" ]; then
            echo "  Already extracted: $DIR_NAME (Open VSX unreachable; verified at extraction)"
        elif [ "$RECORDED" = "$EXPECTED" ]; then
            echo "  Already extracted: $DIR_NAME (sha256 still matches what is published)"
        else
            echo "  Re-extracting $DIR_NAME: published sha256 has moved since it was extracted"
            rm -rf "$DEST_DIR" "$STAMP"
        fi
        if [ -d "$DEST_DIR" ]; then
            # Re-run rather than trusted: this compares engines.vscode against
            # VSCODE_VERSION, which moves without the extension moving. A cached
            # tree is precisely the case where the previous verdict has gone
            # stale, and the failure it catches is silent -- the extension is
            # registered, never activates, and logs nothing.
            python3 "$SCRIPT_DIR/check-extension.py" "$DEST_DIR" "$ROOT_DIR/VSCODE_VERSION"
            continue
        fi
    elif [ -d "$DEST_DIR" ]; then
        echo "  Re-extracting $DIR_NAME: nothing on record says this tree was verified"
        rm -rf "$DEST_DIR"
    fi

    if [ -z "$EXPECTED" ]; then
        echo "  FAIL   $EXT_ID: no published digest at $SHA256_URL, and no verified tree to fall back on" >&2
        exit 1
    fi

    # Download VSIX
    VSIX_FILE="$WORK_DIR/${DIR_NAME}.vsix"
    if [ ! -f "$VSIX_FILE" ]; then
        echo "  Downloading VSIX..."
        curl -sL --fail --show-error -o "$VSIX_FILE" "$DOWNLOAD_URL"
        echo "  Downloaded: $(du -sh "$VSIX_FILE" | cut -f1)"
    fi

    # These are executable payloads bundled into the APK, so they get the same
    # bar as every other download script: fail closed. (An earlier revision
    # claimed Open VSX publishes no digests; a live fetch of files.sha256
    # disproved it.) EXPECTED was read above, before the fast path, so that a
    # tree this run does not download is held to the same comparison.
    ACTUAL=$( (sha256sum "$VSIX_FILE" 2>/dev/null || shasum -a 256 "$VSIX_FILE") | cut -d' ' -f1)
    if [ "$ACTUAL" != "$EXPECTED" ]; then
        echo "  FAIL   $EXT_ID: VSIX does not match files.sha256" >&2
        echo "         published : ${EXPECTED:-(empty)}" >&2
        echo "         file      : $ACTUAL" >&2
        rm -f "$VSIX_FILE"
        exit 1
    fi
    echo "  sha256: verified"

    # Extract extension/ contents from VSIX (it's a ZIP)
    echo "  Extracting..."
    rm -rf "$DEST_DIR"
    mkdir -p "$DEST_DIR"

    # VSIX contains extension/ directory with actual extension files
    # Use -j to strip path prefix, but we need the directory structure
    TEMP_EXTRACT="$WORK_DIR/extract-${DIR_NAME}"
    rm -rf "$TEMP_EXTRACT"
    mkdir -p "$TEMP_EXTRACT"
    unzip -q -o "$VSIX_FILE" "extension/*" -d "$TEMP_EXTRACT"

    # Move extension/ contents to destination
    if [ -d "$TEMP_EXTRACT/extension" ]; then
        cp -a "$TEMP_EXTRACT/extension/." "$DEST_DIR/"
    else
        echo "  ERROR: No extension/ directory in VSIX"
        rm -rf "$DEST_DIR"
        continue
    fi
    rm -rf "$TEMP_EXTRACT"

    # Remove dotfiles that AAPT would strip from assets
    DOTFILES_FOUND=0
    while IFS= read -r dotfile; do
        DOTFILES_FOUND=1
        BASENAME=$(basename "$dotfile")
        DIRNAME=$(dirname "$dotfile")
        NEWNAME="${BASENAME#.}"
        echo "  Renaming dotfile: $BASENAME -> _${NEWNAME}"
        mv "$dotfile" "$DIRNAME/_${NEWNAME}"
    done < <(find "$DEST_DIR" -name '.*' -not -name '.' -not -name '..')

    if [ "$DOTFILES_FOUND" -eq 0 ]; then
        echo "  No dotfiles found (OK)"
    fi

    # An extension whose engines.vscode is newer than the server fails silently:
    # it is registered, never activates, and nothing is logged.
    python3 "$SCRIPT_DIR/check-extension.py" "$DEST_DIR" "$ROOT_DIR/VSCODE_VERSION"

    # Last, so a tree that failed any step above carries no record saying it
    # passed. Kept out of $ASSETS_DIR on purpose: a file there would ship inside
    # the APK, and a dot-name there would be swept by the dotfile rename above.
    printf '%s\n' "$ACTUAL" > "$STAMP"

    echo "  Extracted: $(du -sh "$DEST_DIR" | cut -f1) -> $DIR_NAME"
done

# Summary
echo ""
echo "=== Bundled extensions ready ==="
echo "Location: $ASSETS_DIR/"
for dir in "$ASSETS_DIR"/*/; do
    if [ -d "$dir" ]; then
        NAME=$(basename "$dir")
        SIZE=$(du -sh "$dir" | cut -f1)
        echo "  $NAME ($SIZE)"
    fi
done
echo ""
echo "Total: $(du -sh "$ASSETS_DIR" | cut -f1)"
