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

# Transformations this build applies to an extracted tree, as distinct from the
# verifications beside them.
#
# Called from both the skip path and the extraction path, and that is the whole
# point of it being a function. The skip path's own comment states the rule it
# was written to: skip the download and the extraction, never the verification.
# A rewrite is neither, and putting it only on the extraction path meant a cached
# tree shipped without it, which is precisely what CI did on the first run of
# this change.
#
# Every step here must be safe to run twice, because on a cached tree it is.
#
# A non-zero return from this is fatal to the whole run, and the tree it failed
# on is left where it is. Both halves are deliberate. Each caller used to answer
# a failure with `rm -rf "$DEST_DIR"; continue`, which reported success for a run
# that had just dropped an extension: the summary at the bottom listed the four
# trees that remained, check-bundled-extensions.py called the fifth a note, and
# the APK shipped with no Python support at all. Deleting is also precisely what
# disarms the gate that would have caught it, because `verifyPythonPlatform` in
# android/app/build.gradle.kts is armed by the tree's presence
# (`onlyIf { tree != null }`): an unrewritten tree fails the build, an absent one
# skips the task. Leaving it also leaves the bundle a maintainer has to read to
# update the pattern, which is what a pin bump most often needs.
apply_tree_rewrites() {
    local dir_name="$1" dest="$2"
    case "$dir_name" in
        ms-python.python-*)
            python3 "$SCRIPT_DIR/patch-python-platform.py" "$dest" || return 1
            ;;
    esac
    return 0
}

# Extensions to bundle: publisher.name@version#sha256
#
# Every one is pinned to the newest STABLE version whose engines.vscode is
# satisfied by the VSCODE_VERSION file. Stable matters as much as compatible:
# GitLens publishes pre-release builds far more often than releases, so "newest
# compatible" lands on one, and its pre-releases carry an expiry date. Picking
# one ships an extension that works in testing and then puts an "this
# pre-release has expired" error in front of every user some weeks later. Leaving one unpinned would resolve to
# whatever is newest on the day of the build, and an extension needing a newer VS
# Code than the server does not error; it simply never activates, so the feature
# is missing with nothing in the log to explain it. The check after extraction
# below turns that into a failed build instead.
#
# Re-derive the pins after bumping VSCODE_VERSION; the comments say what is
# holding one back.
#
# GitLens is deliberately absent. VS Code's own SCM view, inline blame and diff
# editor cover what this app needs, and GitLens is 22 MB plus a walkthrough and
# a welcome view on first run. Anyone who wants it can install it from Open VSX.
#
# The digest after the "#" is the sha256 of the VSIX that pin resolves to,
# recorded here rather than taken from the registry. Open VSX serves the bytes
# and the files.sha256 beside them from one host under one path prefix, so on
# its own that pair says the download arrived intact and nothing about what was
# published: whoever can replace one can replace the other, and this script
# would compare the replacement against itself and report "sha256: verified".
# Pinning makes this repository the party that says what each VSIX is, the same
# arrangement download-java.sh uses for SPAWN_SHA_*.
#
# What it does not buy: nothing here establishes that a digest was right the
# first time. Each was read from Open VSX once and its value is that it stops
# moving afterwards. Bump the version and the digest together; the published
# value is still fetched below and a disagreement fails the build.
EXTENSIONS=(
    "esbenp.prettier-vscode@12.4.0#fb730ea4306d09cdc0a3aaa9e9baae28058cc97a4fbfce8b056b377a0639a9fe"
    "ms-python.python@2026.4.0#232aeafb01f069824fdd92d3e628c1c442bbcfa1d3cc945ff97076340bb2b4a6"
    "dbaeumer.vscode-eslint@3.0.34#ca5334d46f6a39079e751ef4601bfc9f86bc3a46483e87291ec609239d161308"
    "bradlc.vscode-tailwindcss@0.16.0#3fd7ceb8b20a88d1df01c3c95f240e7b3db14fd66b8f003c1d686790608d1942"
)

OPENVSX_API="https://open-vsx.org/api"

# One invariant governs this whole file: the directory name the sweep computes for
# an entry must equal the directory name the download loop extracts it into. The
# sweep builds "<id>-<pin>" from the entry text; the loop builds
# "${EXT_ID}-${VERSION}" from the entry text and the API response. When they
# disagree the extracted tree is not in the managed set, is not tracked by git,
# and the sweep deletes it on every single run -- taking with it the verified tree
# the offline path at the bottom of the loop falls back on, which turns "Open VSX
# unreachable" from a note into a failed build.
#
# The shape half is checked here, before anything reads or deletes a directory.
# Both computations split on "@" and then on "#", so an entry missing either
# separator, carrying two of one, or leaving a field empty makes them disagree:
#     foo.bar          -> sweep "foo.bar-foo.bar"  (neither strip does anything)
#     foo.bar@#abc     -> sweep "foo.bar-"         (trailing hyphen, matches nothing)
#     foo.bar@1.0@2#ab -> sweep "foo.bar@1.0-2"    (the strips cut in different places)
# none of which any extraction ever produces.
#
# Refused rather than skipped, because the header above already requires pins for
# an independent reason: unpinned resolves to whatever is newest on the day of the
# build, and an extension whose engines.vscode outruns the server is registered,
# never activates, and logs nothing. An entry naming no usable version has no
# correct handling to fall back on, so there is nothing for a skip to do but hide it.
#
# One expression rather than a case per malformation, because the digest field
# added three more of them and the interesting property is positive: the entry
# has to be an id, a version and 64 lowercase hex digits, in that order, with one
# separator between each. A missing digest must not fall through to "verify
# against whatever the registry says" -- that is the arrangement the pin exists
# to end.
#
# The shape check cannot cover the other half -- an alias like "@latest" is a
# well-formed pin that Open VSX resolves to a different string -- so that is
# asserted on the resolved value further down, where it can be measured instead
# of guessed at.
EXT_SPEC_SHAPE='^[^@#]+\.[^@#]+@[^@#]+#[0-9a-f]{64}$'
for entry in "${EXTENSIONS[@]}"; do
    if ! [[ "$entry" =~ $EXT_SPEC_SHAPE ]]; then
        echo "  FAIL   $entry is not a usable pin." >&2
        echo "         Write it as publisher.name@version#sha256, with exactly one '@'," >&2
        echo "         exactly one '#', no empty field, and 64 lowercase hex digits." >&2
        echo "         The cleanup sweep computes this extension's directory name from" >&2
        echo "         the id and the version, so an entry it cannot split is a tree it" >&2
        echo "         deletes on every run; and without the digest there is nothing to" >&2
        echo "         hold the download to but the registry serving it." >&2
        exit 1
    fi
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
        # The same expansions the download loop uses for EXT_ID and
        # PINNED_VERSION, deliberately -- %%@* and #*@ and %%#*, not %@* and
        # ##*@ and %#*. The guard above rules out the inputs where the two sets
        # differ, so either spelling would compute the same string today; using
        # the loop's makes the agreement structural rather than something a
        # reader has to re-derive.
        #
        # The digest field must be stripped here as well, or every managed name
        # becomes "<id>-<version>#<hex>", matches no extracted directory, and the
        # sweep below deletes and re-downloads all five trees on every run.
        entry_tail="${entry#*@}"
        managed="$managed ${entry%%@*}-${entry_tail%%#*}"
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
    # publisher.name@version#sha256. The guard above refuses every shape where
    # these three splits disagree with the sweep's, so all three fields are
    # non-empty here and the branches that handled their absence are gone.
    EXT_ID="${EXT_SPEC%%@*}"
    EXT_TAIL="${EXT_SPEC#*@}"
    PINNED_VERSION="${EXT_TAIL%%#*}"
    PINNED_SHA256="${EXT_TAIL#*#}"

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

    # The other half of the invariant the guard at the top of the file can only
    # half-check, asserted where it can be measured rather than guessed at. The
    # sweep derives this extension's directory name from the PIN while the
    # extraction below uses the RESOLVED version, so the two must be the same
    # string. A pin is not required to be one: "@latest" is well-formed, passes
    # every shape test, and resolves to a number -- after which the sweep looks
    # for "<id>-latest", does not find it, and deletes "<id>-<number>" on every
    # run. Comparing the resolved value catches that and anything else Open VSX
    # may resolve differently in future, which no pattern over the entry text can.
    #
    # One residual, stated rather than hidden: the sweep runs before this point,
    # because this needs the API response and the sweep needs none. So an entry
    # that trips this has already had its tree deleted once. That is a bounded
    # cost -- the build stops here naming the cause, and a corrected pin
    # re-extracts on the next run -- against the previous behaviour, which was to
    # delete and re-download silently on every run, forever, with the build green.
    if [ "$VERSION" != "$PINNED_VERSION" ]; then
        echo "  FAIL   $EXT_ID: pinned '$PINNED_VERSION', Open VSX resolved '$VERSION'." >&2
        echo "         The cleanup sweep names this extension's directory from the pin and" >&2
        echo "         the extraction names it from the resolved version, so a difference" >&2
        echo "         makes the sweep delete the tree on every run. Pin the exact version." >&2
        exit 1
    fi
    DOWNLOAD_URL=$(python3 -c "import json; d=json.load(open('$METADATA_FILE')); print(d['files']['download'])")

    DIR_NAME="${EXT_ID}-${VERSION}"
    DEST_DIR="$ASSETS_DIR/$DIR_NAME"

    echo "  Version: $VERSION"
    echo "  Download URL: $DOWNLOAD_URL"

    # The expected digest is the pinned one, so it is on hand here whatever the
    # network is doing. That retires the whole offline question this block used
    # to carry: there is no "Open VSX unreachable, trust the stamp" case left,
    # because the answer never came from Open VSX in the first place.
    EXPECTED="$PINNED_SHA256"
    STAMP="$WORK_DIR/${DIR_NAME}.verified"

    # files.sha256 is still read, demoted from the answer to a cross-check.
    # A version is immutable on Open VSX, so a published digest that moves under
    # a fixed one is the precise event a pin exists to catch, and it fails the
    # build rather than deciding it. Unreachable is not the same as moved: an
    # empty result skips the comparison and the pin still governs, which is the
    # offline behaviour download-npm.sh has.
    #
    # `|| true` binds to the assignment, not to the pipeline inside it, so a
    # failed fetch lands as an empty PUBLISHED rather than aborting under set -e
    # with only curl's message. --show-error keeps that message: it says whether
    # this was no network or a 404.
    SHA256_URL=$(python3 -c "import json; print(json.load(open('$METADATA_FILE'))['files'].get('sha256', ''))")
    if [ -z "$SHA256_URL" ]; then
        echo "  NOTE: metadata carries no files.sha256; the pin has nothing to cross-check against"
    else
        PUBLISHED=$(curl -sL --fail --show-error "$SHA256_URL" | awk '{print $1}' || true)
        if [ -n "$PUBLISHED" ] && [ "$PUBLISHED" != "$EXPECTED" ]; then
            echo "  FAIL   $EXT_ID $PINNED_VERSION: Open VSX publishes a different digest" >&2
            echo "         pinned    : $EXPECTED" >&2
            echo "         published : $PUBLISHED" >&2
            echo "         A version does not change its bytes. Either this pin was never" >&2
            echo "         right or the release was replaced; find out which before this" >&2
            echo "         build is trusted, and do not simply copy the published value." >&2
            exit 1
        fi
    fi

    # Skip the download and the extraction, never the verification.
    if [ -d "$DEST_DIR" ] && [ -f "$DEST_DIR/package.json" ] && [ -f "$STAMP" ]; then
        RECORDED=$(cat "$STAMP")
        if [ "$RECORDED" = "$EXPECTED" ]; then
            echo "  Already extracted: $DIR_NAME (matches the pinned sha256)"
        else
            echo "  Re-extracting $DIR_NAME: not the tree the pinned sha256 names"
            rm -rf "$DEST_DIR" "$STAMP"
        fi
        if [ -d "$DEST_DIR" ]; then
            # Re-run rather than trusted: this compares engines.vscode against
            # VSCODE_VERSION, which moves without the extension moving. A cached
            # tree is precisely the case where the previous verdict has gone
            # stale, and the failure it catches is silent -- the extension is
            # registered, never activates, and logs nothing.
            python3 "$SCRIPT_DIR/check-extension.py" "$DEST_DIR" "$ROOT_DIR/VSCODE_VERSION"
            apply_tree_rewrites "$DIR_NAME" "$DEST_DIR" || {
                echo "  FAIL   could not rewrite the cached $DIR_NAME tree" >&2
                echo "         The tree is left on disk unrewritten, which is what the" >&2
                echo "         packaging gate refuses and what says where the shape moved." >&2
                exit 1
            }
            continue
        fi
    elif [ -d "$DEST_DIR" ]; then
        echo "  Re-extracting $DIR_NAME: nothing on record says this tree was verified"
        rm -rf "$DEST_DIR"
    fi

    # Download VSIX
    VSIX_FILE="$WORK_DIR/${DIR_NAME}.vsix"
    if [ ! -f "$VSIX_FILE" ]; then
        echo "  Downloading VSIX..."
        curl -sL --fail --show-error -o "$VSIX_FILE" "$DOWNLOAD_URL"
        echo "  Downloaded: $(du -sh "$VSIX_FILE" | cut -f1)"
    fi

    # These are executable payloads bundled into the APK, so they get the same
    # bar as every other download script: fail closed. EXPECTED is the pin, set
    # above the fast path, so a tree this run does not download is held to the
    # same comparison as one it does.
    ACTUAL=$( (sha256sum "$VSIX_FILE" 2>/dev/null || shasum -a 256 "$VSIX_FILE") | cut -d' ' -f1)
    if [ "$ACTUAL" != "$EXPECTED" ]; then
        echo "  FAIL   $EXT_ID: VSIX does not match the pinned sha256" >&2
        echo "         pinned : $EXPECTED" >&2
        echo "         file   : $ACTUAL" >&2
        rm -f "$VSIX_FILE"
        exit 1
    fi
    echo "  sha256: matches the pinned digest"

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
        # Nothing was copied, so unlike the rewrite failures around it this
        # one has no tree to read or to refuse: the destination is the empty
        # directory made two lines up. It goes, and the run stops rather than
        # carrying on to report the extensions it did place.
        echo "  FAIL   No extension/ directory in $VSIX_FILE" >&2
        rm -rf "$DEST_DIR"
        exit 1
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

    # The Python extension decides the platform for itself and has no android
    # branch, so it answers OSType.Unknown here and then refuses to compose any
    # environment-activation command. Rewritten rather than worked around,
    # because the only alternative lever is process.platform, and changing that
    # for the whole extension host would mislead every other extension that
    # resolves a native binary by platform name.
    #
    # Safe against the usual objection to patching minified output because this
    # input is not rebuilt: the VSIX is pinned by sha256 above, so the shape is
    # fixed until the pin moves, and both checks fail loudly when it does.
    apply_tree_rewrites "$DIR_NAME" "$DEST_DIR" || {
        echo "  FAIL   could not rewrite the $DIR_NAME tree" >&2
        echo "         The tree is left on disk unrewritten, which is what the" >&2
        echo "         packaging gate refuses and what says where the shape moved." >&2
        exit 1
    }

    # Last, so a tree that failed any step above carries no record saying it
    # passed. Kept out of $ASSETS_DIR on purpose: a file there would ship inside
    # the APK, and a dot-name there would be swept by the dotfile rename above.
    printf '%s\n' "$ACTUAL" > "$STAMP"

    echo "  Extracted: $(du -sh "$DEST_DIR" | cut -f1) -> $DIR_NAME"
done

# Every tree is on disk at this point, which is the only place the licence half
# of this can run: the extracted directories are gitignored, so on a pull request
# there is nothing to read. lint.yml runs the same script for the half that reads
# committed sources.
#
# --require-trees because "every" above is a claim this call site can make and
# the workflow ones cannot: the loop has just placed all five or exited. It is
# the sweep for any future path that ends a run with an extension unplaced,
# rather than a restatement of the exits above, which stop long before here.
echo ""
python3 "$SCRIPT_DIR/check-bundled-extensions.py" --require-trees

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
