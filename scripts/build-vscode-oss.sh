#!/usr/bin/env bash
set -euo pipefail

# Builds Code - OSS into a vscode-reh-web server tree.
#
# Two ways in, and they are not interchangeable. build-vscode-oss.yml runs this
# directly on an arm64 runner with nothing mounted, so every path here has to
# resolve from the script's own location. Locally it runs inside the image from
# docker/codeoss-build.Dockerfile, which supplies the Node from VS Code's .nvmrc
# and the native toolchain.
#
# ⚠️ A reused work volume is faster and lies. It carries the previous run's
# node_modules and output tree, so a build can pass on it while the same commit
# fails from clean, which is how an unpatched CI build went unnoticed. Before
# trusting a local green, `docker volume rm vscodroid-codeoss`.
#
# Run it with a named volume rather than a bind mount; npm ci writes on the
# order of 100k files, and a Docker Desktop bind mount makes that pathologically
# slow:
#
#   docker build -f docker/codeoss-build.Dockerfile \
#       -t vscodroid-codeoss-build:24.18.0 docker/
#   docker volume create vscodroid-codeoss
#   docker volume create vscodroid-npm-cache
#   docker run --rm -v vscodroid-codeoss:/work \
#       -v vscodroid-npm-cache:/root/.npm \
#       -e VSCODE_VERSION="$(cat VSCODE_VERSION)" \
#       -e VSCODE_COMMIT="$(cat VSCODE_COMMIT)" \
#       -v "$PWD/scripts:/scripts:ro" \
#       -v "$PWD/branding:/branding" \
#       -v "$PWD/patches:/patches:ro" \
#       vscodroid-codeoss-build:24.18.0 bash /scripts/build-vscode-oss.sh
#
# ⚠️ The version in that tag is the Dockerfile's ARG NODE_VERSION, written out by
# hand in both places with NOTHING checking they agree, and nothing here reads the
# Dockerfile either. A stale tag builds against a Node the image does not have, or
# silently reuses an image built for an older one. Read the value out of
# docker/codeoss-build.Dockerfile when bumping rather than copying this line.
#
# Left ungated deliberately, and it is the one loose end in this block: the check
# would have to parse a Dockerfile that CI never builds, since the workflow installs
# the same toolchain directly on an arm64 runner. Worth a gate the day a second
# person builds locally.
#
# Note also the Dockerfile's own warning: the Node this build HOST runs and the Node
# that ends up on the DEVICE are different numbers that merely coincide at 1.133.0.
#
# Run it on an arm64 host. Every native module in the tree is built for the build
# host, and only two of them are overlaid afterwards by build-native-addons.sh:
# ripgrep in particular is downloaded by its own postinstall for whatever
# os.platform()/arch() reports. The Verify stage refuses to finish an x86-64
# tree rather than let one reach a device, where it fails at exec.
#
# Patches and branding are both applied to the source before gulp runs, so their
# effects are baked in wherever the build inlines them rather than only where a
# later regex reaches. Each stage prints what it did and fails the build if it
# could not, so a broken build is attributable to a stage rather than discovered
# on a device.

# No default. The pin lives in the repo's VSCODE_VERSION file and is passed in;
# a second copy here is a second thing to forget when the version moves.
VSCODE_VERSION="${VSCODE_VERSION:?pass it in from the repo VSCODE_VERSION file}"

# The commit that VSCODE_VERSION's tag pointed at when it was pinned. Passed in
# the same way and for the same reason as the version: under Docker only
# /scripts, /patches and /branding are mounted, so the repository root is not
# readable from in here.
#
# A tag is a mutable name. Upstream can move 1.133.0 onto a different commit and
# the clone below would follow it without a word -- the release digest and
# check-patch-fingerprints.py both protect the artifact, but nothing protected
# the link from the source to it, which ran through that name.
#
# What the pin asserts is stronger than "this is where the tag pointed". All
# the patches in patches/ were applied to this commit, in order, and applied
# cleanly. So the bar for changing this file is not that the new tag resolved --
# it is that the patches still apply to what it resolved to.
VSCODE_COMMIT="${VSCODE_COMMIT:?pass it in from the repo VSCODE_COMMIT file}"
case "$VSCODE_COMMIT" in
    *[!0-9a-f]*|"")
        echo "VSCODE_COMMIT is not a lowercase hex SHA: $VSCODE_COMMIT" >&2
        exit 1 ;;
esac
# Abbreviated SHAs are ambiguous by construction and get more so as history
# grows; the full 40 is the only form that stays a single answer.
[ "${#VSCODE_COMMIT}" -eq 40 ] || {
    echo "VSCODE_COMMIT must be the full 40-character SHA, got ${#VSCODE_COMMIT}: $VSCODE_COMMIT" >&2
    exit 1
}
ARCH="${ARCH:-arm64}"
# Resolved here, before any stage runs, because the Source stage cd's into the
# checkout and BASH_SOURCE is relative when CI invokes the script as
# `bash scripts/build-vscode-oss.sh` -- computing it later resolves against the
# wrong directory. Relative to the script rather than an absolute mount point so
# it lands right in both places the build runs: /patches and /branding under
# Docker, where the script is mounted at /scripts, and the repo's own directories
# in a checkout. Hardcoding the mount paths is what let a CI run silently build a
# tree with no patches and no branding applied.
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

WORK="${WORK:-/work}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

SRC="$WORK/vscode"
# gulpfile.reh.ts: BUILD_ROOT = path.dirname(REPO_ROOT), and destinationFolderName
# carries no `min` suffix: the min and non-min variants share this directory.
OUT="$WORK/vscode-reh-web-linux-$ARCH"

step() { printf '\n=== %s ===\n' "$1"; }
elapsed() { printf '  took %dm%02ds\n' $(( $1 / 60 )) $(( $1 % 60 )); }

step "Environment"
echo "  vscode      : $VSCODE_VERSION"
echo "  target arch : linux-$ARCH"
echo "  build arch  : $(uname -m)"
echo "  node        : $(node --version)   (expected: $(cat "$SRC/.nvmrc" 2>/dev/null || echo 'checked below'))"
df -h "$WORK" | tail -1 | awk '{print "  disk free   : "$4" of "$2}'

step "Source"
if [ ! -d "$SRC/.git" ]; then
    t0=$SECONDS
    # By commit, not by tag, and that is the whole point rather than a detail.
    # Selecting on the tag would leave a mutable name deciding which bytes
    # arrive: the comparison below would catch a moved tag, but catching it is
    # all it could do -- every build would then fail, including a rebuild of the
    # release already published, and the only way out would be to accept
    # upstream's new commit. Naming the commit keeps the tree reproducible
    # whatever happens to the name, and the comparison becomes a second belt
    # rather than the only one.
    #
    # init + fetch rather than clone, because `git clone --branch` takes a ref
    # and never a raw SHA. github.com serves a SHA to fetch -- measured, along
    # with the negative: an unknown SHA is refused by upload-pack with "not our
    # ref" and exit 128, so a wrong pin fails here, before anything is
    # downloaded, rather than at the comparison after it.
    mkdir -p "$SRC"
    git -C "$SRC" init -q
    git -C "$SRC" remote add origin https://github.com/microsoft/vscode.git
    git -C "$SRC" fetch --depth 1 origin "$VSCODE_COMMIT"
    git -C "$SRC" checkout -q FETCH_HEAD
    elapsed $(( SECONDS - t0 ))
    echo "  fetched : $VSCODE_COMMIT (tag $VSCODE_VERSION is advisory)"
else
    echo "  already cloned"
fi
cd "$SRC"

# Checked on both paths above rather than only after a clone, and the reuse path
# is the one that needed it more: a work volume that already holds $SRC skips
# the clone entirely, so a checkout left behind by a different version would be
# patched, built, and published under this version's name. Nothing downstream
# can catch that -- the tarball digest and the patch fingerprints both describe
# whatever was built, not what it was supposed to be built from.
#
# Its position is load-bearing and cheap on purpose: this runs before npm ci,
# so a wrong pin costs seconds instead of the half hour a full build takes.
# Moving it later would keep the check and lose that.
#
# The redirect and the `|| true` are for a $SRC whose .git exists but is not
# readable -- an interrupted fetch, a truncated volume. The guard above skips
# the fetch on the strength of that directory existing, so rev-parse is then
# asked of a non-repository and answers "fatal: not a git repository" or "fatal:
# ambiguous argument 'HEAD'" on exit 128. set -e would abort on that, which is
# closed but hands the operator git's text when the message below already has
# the right answer for exactly that state.
#
# --verify, not a bare rev-parse. Measured: in a repository with no commits a
# bare `git rev-parse HEAD` prints the literal string "HEAD" on stdout while
# failing on stderr, which would reach the message below as an "actual" commit
# of HEAD. --verify prints nothing in that state and nothing when .git is not a
# repository at all, so the empty check covers both.
HEAD_COMMIT="$(git rev-parse --verify HEAD 2>/dev/null || true)"
if [ "$HEAD_COMMIT" != "$VSCODE_COMMIT" ]; then
    cat >&2 <<EOF

  ERROR: this source tree is not the pinned commit.

    expected : $VSCODE_COMMIT  (VSCODE_COMMIT)
    actual   : ${HEAD_COMMIT:-(this directory has a .git that git cannot read)}

  Three things produce this and they want different answers, so it refuses
  rather than guessing:

    * The work volume still holds an earlier version's checkout. Cheapest to
      rule out first:
          docker volume rm vscodroid-codeoss

    * VSCODE_VERSION was bumped and VSCODE_COMMIT was not. Resolve the tag and
      write the SHA it prints into VSCODE_COMMIT:
          git ls-remote https://github.com/microsoft/vscode.git 'refs/tags/$VSCODE_VERSION*'
      Take the ^{} line if there is one; that is the commit an annotated tag
      points at, and it is what a clone leaves at HEAD. Resolving it is half
      the job: the pin means the patches apply to that commit, so let the
      Patches stage below run before you treat the new SHA as good.

    * Upstream moved the tag onto a different commit. Nothing here can tell
      that apart from the case above, which is the point of pinning: read what
      changed between the two commits before you accept the new one.

EOF
    exit 1
fi

echo "  HEAD    : $(git rev-parse --short HEAD)  (matches VSCODE_COMMIT)"

# The pin is two files and they move independently. The comparison above proves
# the checkout is the commit that was asked for, which any commit satisfies --
# including the one the previous version pinned. So a bump that edits
# VSCODE_VERSION and forgets VSCODE_COMMIT builds the old source and publishes it
# as server-<new version>, and nothing downstream can tell: the tarball digest
# and the patch fingerprints both describe what was built, never what it was
# supposed to be built from.
#
# Read from the source rather than by resolving refs/tags/$VSCODE_VERSION.
# Resolving the tag would catch this too, and would additionally fail every time
# upstream moved a tag -- including a rebuild of a release already published,
# which is the exact failure the commit pin exists to avoid (see the Source stage
# above). package.json at the commit states which version that commit is, which
# is the question being asked, and it costs no network and no extra ref.
SRC_VERSION="$(python3 -c 'import json; print(json.load(open("package.json"))["version"])')"
if [ "$SRC_VERSION" != "$VSCODE_VERSION" ]; then
    cat >&2 <<EOF

  ERROR: the pinned commit is not this version's source.

    VSCODE_VERSION : $VSCODE_VERSION
    VSCODE_COMMIT  : $VSCODE_COMMIT
    that commit is : $SRC_VERSION  (its own package.json)

  Almost always: VSCODE_VERSION was bumped and VSCODE_COMMIT was left behind.
  Building on would publish $SRC_VERSION's source as server-$VSCODE_VERSION, and
  no later check could notice -- every one of them describes the artifact rather
  than where it came from.

  Resolve the tag and write the SHA into VSCODE_COMMIT:

      git ls-remote https://github.com/microsoft/vscode.git 'refs/tags/$VSCODE_VERSION*'

  Take the ^{} line if there is one. Resolving it is half the job: the pin
  asserts that every patch in patches/ applies to that commit, so let the
  Patches stage run before treating the new SHA as good.

EOF
    exit 1
fi
echo "  version : $SRC_VERSION  (package.json agrees with VSCODE_VERSION)"

echo "  .nvmrc  : $(cat .nvmrc)"
[ "v$(cat .nvmrc)" = "$(node --version)" ] || echo "  WARNING: node does not match .nvmrc"

step "Patches"
# The Android adaptations, as real diffs against readable source. git apply exits
# non-zero when the context has shifted, and set -e turns that into a failed
# build, which is the whole reason these are moving here from regexes against
# minified output, where a stale pattern printed SKIP and exited 0.
#
# Applied to a clean tree every time, so a rerun does not fail on already-applied
# hunks and cannot accumulate half-states. All three steps are needed, and the
# first is the one that is easy to miss: a patch that adds a file leaves it
# staged, where neither checkout nor clean will touch it, and the next run dies
# with "already exists in working directory".
PATCHES="${PATCHES:-$REPO_ROOT/patches}"
if [ -d "$PATCHES" ] && [ -n "$(ls -A "$PATCHES"/*.patch 2>/dev/null)" ]; then
    # build/ as well as src/: patches reach the build tooling too, and a tree
    # reset that misses one of them fails the next run on an applied hunk.
    git -C "$SRC" reset -q 2>/dev/null || true
    git -C "$SRC" checkout -- src/ build/ 2>/dev/null || true
    git -C "$SRC" clean -fdq src/ build/ 2>/dev/null || true
    for patch in "$PATCHES"/*.patch; do
        git -C "$SRC" apply --verbose "$patch" 2>&1 | sed 's/^/  /'
        echo "  applied $(basename "$patch")"
    done
elif [ -n "${ALLOW_UNADAPTED:-}" ]; then
    echo "  no patches at $PATCHES, building unadapted (ALLOW_UNADAPTED set)"
else
    echo "  ERROR: no patches at $PATCHES" >&2
    echo "  A tree without them is not this product: no Android platform" >&2
    echo "  detection, no worker_thread hosts, no Open VSX target platform." >&2
    echo "  Set ALLOW_UNADAPTED=1 to build one deliberately." >&2
    exit 1
fi

step "Dependencies (npm ci)"
# Around 3 GB over the wire, and a single reset anywhere in it fails the whole
# stage, which then costs another full download to retry. npm's own retry only
# covers individual requests, so the stage is retried as a whole too. Mount a
# volume at /root/.npm (see the header) and a retry resumes from cache instead of
# starting over.
t0=$SECONDS
for attempt in 1 2 3; do
    if npm ci --fetch-retries=5 --fetch-retry-mintimeout=10000 \
              --fetch-retry-maxtimeout=120000 --prefer-offline; then
        break
    fi
    if [ "$attempt" = 3 ]; then
        echo "  npm ci failed three times; the last error is above" >&2
        exit 1
    fi
    echo "  npm ci failed, retrying ($attempt/3)" >&2
done
elapsed $(( SECONDS - t0 ))
du -sh node_modules remote/node_modules 2>/dev/null | sed 's/^/  /'


# Its own stage, and its position is load-bearing: after the Dependencies stage,
# before Branding. Measured on the artifact rather than argued: with the strip
# ahead of that stage, out/vs/code/browser/workbench/callback.html shipped at
# 157034 bytes still matching data:image while this stage printed that it had
# removed one; with the strip below, 3729 bytes and no match. What undoes an
# earlier write to src/ has not been established and is deliberately not named
# here, because the file 157034 restores to is the PATCHED one rather than
# upstream's 156200, which no explanation offered so far accounts for. The
# ordering and the outcome are what is established, and Verify asserts the
# outcome. Before Branding because that stage's restore list must not name this
# file (see the note there).
step "Callback page"
# The OAuth callback page carries the VS Code logo as a base64 PNG on a single
# 150 KB line of CSS. That page is rendered by the user's own browser at the
# end of every extension sign-in, so the image is redistributed and displayed
# rather than merely present.
#
# Stripped here rather than in patches/0006, which already edits this file for
# its name and its title, because a diff that removes a line has to quote it,
# and a 150 KB line in a patch is a patch nobody can read. The assertion is on
# the outcome rather than on the count, so running this stage twice on a reused
# work volume is not an error while a page that still embeds an image is.
#
# Unconditional, unlike Patches and Branding: an ALLOW_UNADAPTED tree still must
# not redistribute the logo, so this stage has no guard to skip it.
python3 - "$SRC/src/vs/code/browser/workbench/callback.html" <<'PY'
import re, sys

path = sys.argv[1]
html = open(path, encoding="utf-8").read()
html, n = re.subn(r'^[ \t]*background-image:[ \t]*url\("data:image[^"]*"\);\n', "", html, flags=re.M)
if "data:image" in html:
    sys.exit("  ERROR: callback.html still embeds an image after the strip")
open(path, "w", encoding="utf-8").write(html)
print(f"  src/vs/code/browser/workbench/callback.html: {n} embedded image removed")
PY

# Branding runs after the Dependencies stage. Measured on two clean-volume builds
# that differed in nothing else: with this stage ahead of the install the
# packaged workbench carried upstream's code-icon.svg (559 bytes, fill #167abf)
# and upstream's letterpress artwork byte for byte, while the stage printed both
# paths as branded; with it below, both are byte-identical to branding/.
# product.json and resources/server/ survived either way, which is why the name
# looked right, only the artwork was wrong, and nobody caught it until someone
# looked at the editor.
#
# No mechanism is asserted for what undoes an earlier write to src/, because none
# offered so far survives its own evidence: the Patches stage applies 0006 to
# callback.html before this point and that patch is present in the packaged tree.
# The constraint the measurement supports is enough, and it is the only thing
# this comment claims: the overlay has to be the last thing to touch src/ before
# the bundler reads it. Verify compares all seven artwork files against branding/
# so each build proves the outcome rather than inheriting this reasoning.
step "Branding"
# Skippable so the stage can be run bare when isolating a build problem, but only
# on purpose: an unbranded tree carries Microsoft's product name and icons, which
# is exactly what this build exists to avoid shipping.
BRANDING="${BRANDING:-$REPO_ROOT/branding}"
if [ -d "$BRANDING" ]; then
    # Restore what this stage overwrites before overwriting it. The overlay is
    # applied with update(), so on a reused work volume it merges into the
    # previous run's output, which means a key REMOVED from the overlay stays in
    # the file forever, and the stage silently stops matching what it declares.
    # That cost three build attempts: extensionsGallery was dropped from the
    # overlay and the build kept downloading builtin extensions from Open VSX.
    #
    # Fatal rather than silent. `|| true` here meant that an upstream rename of
    # any of these paths made the restore a no-op, and on a reused work volume
    # the overlay would then merge into the previous run's file -- the exact
    # failure the paragraph above describes, reintroduced through the fix for it.
    #
    # The src/ entries are redundant while patches exist, since the Patches stage
    # resets src/ and build/ wholesale earlier in this script; they are what
    # covers an ALLOW_UNADAPTED build, where that stage does nothing at all.
    #
    # callback.html is deliberately NOT in this list. Two stages above own that
    # file: patch 0006 rewrites it for the intent:// relay and the Callback page
    # stage strips its embedded logo. Checking it out here would silently undo
    # both, shipping a callback page with no relay, which means every extension
    # sign-in completes in the browser and never returns to the app, and putting
    # the 150 KB image back. It needs no reset of its own: the strip asserts its
    # outcome rather than its match count, so a second run over an
    # already-stripped file is a pass rather than an error.
    git -C "$SRC" checkout -- product.json resources/server/ \
        src/vs/workbench/browser/media/code-icon.svg \
        src/vs/workbench/browser/parts/editor/media/ \
        src/vs/sessions/contrib/chat/browser/media/ || {
        echo "  ERROR: could not restore this stage's own targets (see git's message above)" >&2
        echo "  One of those paths has moved upstream. Point this line at the new one" >&2
        echo "  before building: without the restore the overlay merges into the" >&2
        echo "  previous run's file and stops matching what it declares." >&2
        exit 1
    }
    python3 - "$BRANDING/product.json" "$SRC/product.json" <<'PY'
import json, sys

overlay_path, product_path = sys.argv[1], sys.argv[2]
overlay = json.load(open(overlay_path))
product = json.load(open(product_path))

removed = [k for k in overlay.get("remove", []) if product.pop(k, None) is not None]
# An entry naming a key upstream does not have pops nothing and used to be
# invisible: only successful removals were counted, so a list could accumulate
# keys dropped years ago with no signal. Reported rather than fatal, because a
# remove entry is also insurance against upstream reintroducing a key.
inert = [k for k in overlay.get("remove", []) if k not in removed]
product.update(overlay.get("set", {}))

# Two spaces and a trailing newline: this file is read by humans when a build
# behaves oddly, and gulp only cares that it parses.
with open(product_path, "w") as f:
    json.dump(product, f, indent=2)
    f.write("\n")

print(f"  product.json: {len(overlay.get('set', {}))} set, {len(removed)} removed, {len(product)} keys")
if inert:
    print(f"    remove entries that matched nothing: {', '.join(inert)}")
for key in ("nameLong", "applicationName", "urlProtocol"):
    print(f"    {key} = {product[key]}")
PY

    # gulpfile.reh.ts's packageTask copies these four into the reh-web package as
    # they are. Upstream they are Microsoft's VS Code icon and name, which must not
    # travel with this app. The filenames are fixed by that same gulp task.
    #
    # Fatal, not a warning. "Keeping upstream" here means shipping Microsoft's
    # icon and name inside the APK, which is the exposure this whole stage
    # exists to remove -- the stage already refuses to run when the branding
    # directory is absent, and a single file renamed or deleted put the same
    # artwork in the package through a green build.
    for asset in manifest.json code-192.png code-512.png favicon.ico; do
        if [ ! -f "$BRANDING/server/$asset" ]; then
            echo "  ERROR: $BRANDING/server/$asset is missing" >&2
            echo "  gulpfile.reh.ts copies these four into the package as they are, so" >&2
            echo "  the alternative to ours is Microsoft's. Restore the file, or remove" >&2
            echo "  it from this list if the packaging stopped using it." >&2
            exit 1
        fi
        cp "$BRANDING/server/$asset" "$SRC/resources/server/$asset"
        echo "  resources/server/$asset"
    done

    # More marks live outside resources/server, inside the workbench bundle: the
    # titlebar app icon, the empty-editor watermark, and the chat sessions
    # watermark. One theme-agnostic letterpress stands in for upstream's themed
    # variants.
    if [ ! -f "$BRANDING/workbench/code-icon.svg" ] || [ ! -f "$BRANDING/workbench/letterpress.svg" ]; then
        echo "  ERROR: $BRANDING/workbench is incomplete" >&2
        echo "  code-icon.svg is the titlebar mark and letterpress.svg the empty-editor" >&2
        echo "  watermark. Upstream's are the VS Code logo, which is copyrighted and" >&2
        echo "  cannot travel in this APK. Both files are required." >&2
        exit 1
    fi
    # Each destination is checked before it is written, which the source side has
    # had since the resources/server loop and this side never did. `cp` creates a
    # file whether or not one was there, so an upstream rename left upstream's own
    # logo in the bundle, wrote a stray file nothing reads, and printed the same
    # success line as always. The Verify stage looks at resources/server only, so
    # nothing downstream noticed either.
    #
    # letterpress-sessions-* is currently referenced by nothing in the packaged
    # tree, and is replaced anyway: it is upstream's mark, it travels in the APK,
    # and a version that starts rendering it would do so silently.
    brand_over() {
        local src="$1" dest="$2"
        if [ ! -f "$SRC/$dest" ]; then
            echo "  ERROR: $dest is not in this source tree" >&2
            echo "  Upstream renamed or moved it. Copying over it would leave the VS Code" >&2
            echo "  logo in the bundle and write a stray file nothing reads. Point this" >&2
            echo "  line at the new path, or drop it if the artwork is gone." >&2
            exit 1
        fi
        cp "$BRANDING/$src" "$SRC/$dest"
        echo "  $dest"
    }
    brand_over workbench/code-icon.svg src/vs/workbench/browser/media/code-icon.svg
    for variant in dark hcDark hcLight light; do
        brand_over workbench/letterpress.svg \
            "src/vs/workbench/browser/parts/editor/media/letterpress-$variant.svg"
    done
    for variant in dark light; do
        brand_over workbench/letterpress.svg \
            "src/vs/sessions/contrib/chat/browser/media/letterpress-sessions-$variant.svg"
    done

elif [ -n "${ALLOW_UNADAPTED:-}" ]; then
    echo "  no branding at $BRANDING, building unbranded (ALLOW_UNADAPTED set)"
else
    echo "  ERROR: no branding at $BRANDING" >&2
    echo "  The tree would carry Microsoft's name and icons." >&2
    echo "  Set ALLOW_UNADAPTED=1 to build one deliberately." >&2
    exit 1
fi

step "Build (core-ci)"
# Two tasks, in the order Microsoft's own pipelines run them, and NOT the single
# `vscode-reh-web-linux-$ARCH-min` that looks like the obvious entry point.
#
# That task still exists but nothing at Microsoft calls it: every shipping
# pipeline runs `core-ci` and then the `-min-ci` packaging tail
# (product-build-linux-compile.yml:211,311, and the darwin/win32/alpine
# equivalents). `core-ci` is esbuild -- tsgo type-check, transpile, then bundle
# straight into out-vscode-reh-web-min. The legacy gulp-and-mangler chain was
# renamed `core-ci-old` (gulpfile.vscode.ts:195) and is invoked by nothing.
#
# Calling `-min` cost eight failed builds, and every failure was in that
# abandoned path: the mangler's protected-fields gate, 168 compile errors from
# tests indexing private fields the mangler had renamed, 14 more from
# dynamic-import destructuring, and a non-ASCII check inside a minify step
# core-ci never runs. Microsoft says as much in
# .github/instructions/buildNext.instructions.md: "The new build doesn't do
# TypeScript-based mangling yet."
t0=$SECONDS
npm run gulp core-ci
elapsed $(( SECONDS - t0 ))

step "Copilot extension (compile-copilot-extension-build)"
# Not in core-ci, and packaging fails without it. The reh-web package task reads
# built-in extensions out of .build/extensions (gulpfile.reh.ts:387), and the
# copilot extension is the one thing that never lands there on its own: nothing
# in core-ci compiles it. The packaging tail then calls
# prepareBuiltInCopilotRipgrepShim unconditionally, which throws "Copilot SDK
# directory not found" -- a message about the output tree, three stages away from
# the stage that should have filled it.
#
# Microsoft's pipeline never hits this because it downloads the extension as a
# VSIX from an internal feed. gulpfile.extensions.ts:288 says so directly: this
# task is "used by non-CI local builds where copilot is not downloaded as a
# VSIX", which is exactly what this build is. Their desktop series places it
# after the non-native extensions compile and before packaging
# (gulpfile.vscode.ts:745), and so does this.
t0=$SECONDS
npm run gulp compile-copilot-extension-build
elapsed $(( SECONDS - t0 ))
[ -d "$SRC/.build/extensions/copilot/node_modules/@github/copilot/sdk" ] || {
    echo "  ERROR: .build/extensions/copilot has no @github/copilot/sdk" >&2
    echo "  Packaging would fail three stages later with a message about the" >&2
    echo "  output tree instead of about this." >&2
    exit 1
}

step "Package (vscode-reh-web-linux-$ARCH-min-ci)"
# Packaging only -- native extensions, the node download, the copy into place.
# Correct precisely because core-ci has already produced out-vscode-reh-web-min.
#
# On failure, print both sides of the copy. This stage's errors name the output
# tree, so without the input beside it there is nothing to tell "packaging did
# not copy it" apart from "it was never built" -- a distinction that cost three
# runs to make once already.
t0=$SECONDS
if ! npm run gulp "vscode-reh-web-linux-$ARCH-min-ci"; then
    echo "" >&2
    echo "  --- what packaging read from (.build/extensions/copilot) ---" >&2
    ls "$SRC/.build/extensions/copilot" 2>&1 | head -20 | sed 's/^/    /' >&2
    ls "$SRC/.build/extensions/copilot/node_modules/@github" 2>&1 | sed 's/^/    @github: /' >&2
    find "$SRC/.build/extensions/copilot/node_modules/@github/copilot/sdk" -maxdepth 1 2>&1 \
        | head -8 | sed 's/^/    sdk: /' >&2
    echo "  --- what reached the output ($OUT/extensions/copilot) ---" >&2
    ls "$OUT/extensions" 2>&1 | head -20 | sed 's/^/    /' >&2
    ls "$OUT/extensions/copilot" 2>&1 | head -20 | sed 's/^/    copilot: /' >&2
    df -h "$WORK" 2>&1 | tail -1 | sed 's/^/    disk: /' >&2
    exit 1
fi
elapsed $(( SECONDS - t0 ))
step "Prune"
# The node-linux-arm64 gulp task downloads a GNU/Linux Node and packageTask ships
# it. Its interpreter (/lib/ld-linux-aarch64.so.1) does not exist on Android and
# nothing here references it; the runtime uses nativeLibraryDir/libnode.so. The
# OSS build produces it byte-for-byte the same as the proprietary one, so the
# pivot does not remove it and this stays a post-build step. Doing it here rather
# than patching the gulpfile keeps it upright across version bumps.
if [ -f "$OUT/node" ]; then
    size=$(du -h "$OUT/node" | cut -f1)
    rm -f "$OUT/node"
    echo "  removed the unusable GNU/Linux node binary ($size)"
fi

# The second copy of the workbench. packageTask ships both the browser entry
# point (out/vs/code/browser/workbench/workbench.js, which workbench.html loads)
# and the embedder bundle beside it, which is what the vscode-web npm package
# exposes and which nothing in this tree names: a recursive grep over the whole
# packaged output matches only the two files themselves. 18 MB of JavaScript and
# 1.4 MB of CSS, 4.8 MiB of them compressed in the base module.
for dead in out/vs/workbench/workbench.web.main.internal.js \
            out/vs/workbench/workbench.web.main.internal.css; do
    if [ -f "$OUT/$dead" ]; then
        size=$(du -h "$OUT/$dead" | cut -f1)
        rm -f "$OUT/$dead"
        echo "  removed the unreferenced embedder bundle $dead ($size)"
    fi
done

# Every minified bundle ends with a sourceMappingURL pointing at
# main.vscode-cdn.net and naming the upstream commit, written by the minify
# task's hardcoded URL. The .map files are filtered out of the package, so the
# URL resolves to nothing this app ships; what it is, is a Microsoft service URL
# left inside a redistributed artifact by a build whose stated job is to remove
# them.
python3 - "$OUT/out" <<'PY'
import pathlib, re, sys

root = pathlib.Path(sys.argv[1])
# Whole lines, not the tail. The comment is written last today, but the Mobile
# CSS stage appends after it, so anchoring on end-of-file would make this stage
# order-dependent for no reason.
pattern = re.compile(rb"(?m)^(?://|/\*)# sourceMappingURL=https://main\.vscode-cdn\.net/[^\n]*\n?")
stripped = 0
left = []
for path in root.rglob("*"):
    if path.suffix not in (".js", ".css") or not path.is_file() or path.is_symlink():
        continue
    data = path.read_bytes()
    if b"main.vscode-cdn.net" not in data:
        continue
    new, n = pattern.subn(b"", data)
    if n:
        path.write_bytes(new)
        stripped += n
    if b"main.vscode-cdn.net" in new:
        left.append(str(path.relative_to(root)))
# The outcome, not the count, for the same reason the callback.html strip asserts
# one: the pattern is line-anchored, and an esbuild that appends the comment to
# the last line without a newline before it would match zero times, print
# "stripped 0" and let every URL through with the build green. Measured on the
# 1.133.0 package, after the prune above takes the two embedder bundles out of
# this walk: 20 files carry the URL, 20 line-anchored hits, nothing left.
#
# Fatal rather than a warning even though the URL is inert on device. The tree is
# redistributed, this stage is what makes the claim that the bundles this build
# produces name main.vscode-cdn.net nowhere, and verify-server-tree.py asserts it
# again on the fetch side, over the whole tree apart from the Copilot extension,
# which fetches its own configuration from that host and is Microsoft's to begin
# with -- so a tree that got past here would be refused there instead, one build
# later and further from the cause.
if left:
    sys.exit(f"  ERROR: {len(left)} files still name main.vscode-cdn.net after the strip: "
             f"{', '.join(left[:5])}")
print(f"  stripped {stripped} sourceMappingURL comments pointing at main.vscode-cdn.net")
PY

# gulp's reh-web package task leaves both of these behind, and product.json still
# names one of them in licenseFileName. Code - OSS is MIT, and MIT asks that the
# copyright notice travel with the copies; this tree is redistributed inside
# every APK, so the notice has to be in it.
for f in LICENSE.txt ThirdPartyNotices.txt; do
    if [ -f "$SRC/$f" ]; then
        cp "$SRC/$f" "$OUT/$f"
        echo "  added $f"
    fi
done

step "Mobile CSS"
# Touch overrides for the hamburger menu. The pre-pivot build appended these to
# the packaged workbench.css; the mutation lost its home when the download
# script was deleted, so menus have been desktop-sized on device since. An
# append rather than a patch because it is additive CSS against a generated
# file - there is no source context to drift.
#
# Unguarded, because the guard could not fire. It tested for the marker first, on
# the theory that a reused work volume would carry the previous run's append; but
# the -min-ci task series begins with a rimraf of the packaging destination, so
# workbench.css is freshly generated on every run, warm volume or cold. The
# assertion after the append is the half that earns its keep.
#
# This block also used to hide the Accounts/Manage section of the activity bar
# (parity with the pre-pivot CSS). Removed 2026-08-15 on the owner's call: the
# gear is the only on-screen route to Settings and the Command Palette for a
# touch user, and Accounts is where auth sessions live. Patch 0008 measures the
# room the bar's siblings actually leave, so it adapts to the section being
# visible without changes.
WORKBENCH_CSS="$OUT/out/vs/code/browser/workbench/workbench.css"
if [ ! -f "$WORKBENCH_CSS" ]; then
    echo "  ERROR: $WORKBENCH_CSS not in the package" >&2
    exit 1
fi
cat >> "$WORKBENCH_CSS" << 'CSSEOF'

/* VSCodroid: Mobile-friendly hamburger menu overrides */
.menubar-menu-items-holder { min-width: 280px !important; }
.monaco-menu .monaco-action-bar.vertical .action-item { min-height: 44px !important; }
.monaco-menu .monaco-action-bar.vertical .action-label { font-size: 14px !important; padding: 8px 24px 8px 12px !important; line-height: 28px !important; }
.monaco-menu .submenu-indicator { font-size: 16px !important; }
.monaco-menu .keybinding { font-size: 12px !important; }
.monaco-menu .monaco-action-bar.vertical .action-label.separator { margin: 4px 8px !important; min-height: 0 !important; padding: 0 !important; line-height: normal !important; }
/* A separator is an .action-item whose label carries .separator, so the 44px
   touch floor two rules above lands on the divider as well and renders a 1px
   line as a blank band. Measured on an API 37 emulator, 411px portrait: each of
   the File menu's seven separators was 71px and the menu was 1427px tall in an
   810px viewport. Neutralising both halves puts them at 11px and the menu at
   1007px with every real row still 44px. :has() is Chromium 105 and this build
   floors at WebView 107; where it is missing the selector is dropped whole and
   the divider stays as it was, which is the old behaviour rather than a broken
   one. */
.monaco-menu .monaco-action-bar.vertical .action-item:has(> .action-label.separator) { min-height: 0 !important; }
CSSEOF
grep -q "VSCodroid: Mobile-friendly" "$WORKBENCH_CSS" || { echo "  ERROR: append did not land" >&2; exit 1; }
echo "  appended mobile menu overrides to workbench.css"

step "Debug adapter"
# js-debug spawns two helpers that carry the debugger connection, and both get an
# environment of three variables rather than the parent's. That is enough
# everywhere it was written for and nowhere here: this app's Node is reached
# through a PATH the app builds and links against libraries only LD_LIBRARY_PATH
# names, so both helpers die before they start, silently, and the program blocks
# for a debugger that never attaches.
#
# Here rather than in patches/, because that directory is applied to the
# microsoft/vscode source with `git apply` and js-debug is not in it: it arrives
# as a built extension the VS Code build downloads. Same family as
# patch-default-shell.py, which rewrites another downloaded artifact's idea of
# where the system is.
python3 "$SCRIPT_DIR/patch-js-debug-env.py" "$OUT"

step "Patch manifest"
# What the fingerprints cannot see. A fingerprint row proves a patch arrived, not
# which version of it did: editing an already-matching patch leaves every gate
# green while the published server keeps the old behaviour, and the only thing
# describing the new one is a CHANGELOG line. This records the sha256 of each
# patch's diff into the tree, so the same check that reads the fingerprints can
# also tell a tarball built from today's patches apart from one built from
# yesterday's -- on the fetch side and in the Gradle packaging gate, neither of
# which has any other signal.
#
# Same condition as the fingerprint check below: a deliberately unadapted build
# has no patches to record.
if [ -d "$PATCHES" ] && [ -n "$(ls -A "$PATCHES"/*.patch 2>/dev/null)" ]; then
    python3 "$SCRIPT_DIR/check-patch-fingerprints.py" --write-manifest "$OUT" "$PATCHES"
fi

step "Verify"
fail=0

# Tree shape, branding and native architecture are checked by the same script the
# fetcher runs, so what is proven here is exactly what is proven again on the way
# into an APK. The build-specific checks (that each patch survived into the
# packaged bundles, and that the product.json key set has not drifted) stay here,
# because only this side has the patches and the expectation file.
python3 "$SCRIPT_DIR/verify-server-tree.py" "$OUT" || fail=1

# Every patch has to be proven present in the packaged output, not merely
# applied to the source. The expectations and the reasoning live in
# patches/fingerprints.txt; this runs them.
#
# It takes the tree as an argument on purpose, the way verify-server-tree.py
# does, so the same check can run on the fetch side against a downloaded
# tarball. That case is the one with no other signal: a tarball predating a
# patch has the same name, the same version, and a digest that verifies,
# because a digest proves the file is intact rather than that it is the right
# file.
# Skipped for a deliberately unadapted build, the same condition that governs
# whether the patches were applied at all -- checking a tree for patches nobody
# applied would fail it for doing exactly what was asked.
if [ -d "$PATCHES" ] && [ -n "$(ls -A "$PATCHES"/*.patch 2>/dev/null)" ]; then
    python3 "$SCRIPT_DIR/check-patch-fingerprints.py" "$OUT" "$PATCHES" || fail=1
fi

if [ -d "$BRANDING" ]; then
    python3 - "$OUT/product.json" "$OUT/resources/server/manifest.json" \
             "$BRANDING/product-keys.expected" <<'PY' || fail=1
import json, sys

product = json.load(open(sys.argv[1]))
manifest = json.load(open(sys.argv[2]))
expected_path = sys.argv[3]
bad = False

def check(label, ok, detail=""):
    global bad
    print(f"  {'ok     ' if ok else 'FAIL   '} {label}{'' if ok else '  ' + detail}")
    if not ok:
        bad = True

# product.json's own branding is checked by verify-server-tree.py, which runs on
# both sides of the pivot. What is left here needs the branding directory, so it
# can only run at build time.
check("manifest.json is branded", manifest.get("name") == "VSCodroid",
      f"name = {manifest.get('name')!r}")

# Locks the key set. A VS Code bump that adds, drops or renames a product.json
# key has to be looked at deliberately; silently inheriting one is how a CDN
# URL or a telemetry endpoint slips back in.
keys = sorted(product)
try:
    with open(expected_path) as f:
        want = [line.strip() for line in f if line.strip()]
except FileNotFoundError:
    print(f"  note    no {expected_path}; writing it from this build")
    with open(expected_path, "w") as f:
        f.write("\n".join(keys) + "\n")
    want = keys

added = [k for k in keys if k not in want]
dropped = [k for k in want if k not in keys]
check("product.json key set unchanged", not added and not dropped,
      f"added={added} dropped={dropped}")

sys.exit(1 if bad else 0)
PY

    # The icons themselves, not only the name inside manifest.json. The Branding
    # stage now refuses to run without them, but that proves what went into the
    # source tree; this proves what came out of the package, which is the tree
    # that gets redistributed. gulpfile.reh.ts copies resources/server across
    # verbatim, so byte-identical is the right bar -- measured against a
    # packaged tree, where all four match branding/server exactly.
    for asset in manifest.json code-192.png code-512.png favicon.ico; do
        if cmp -s "$BRANDING/server/$asset" "$OUT/resources/server/$asset"; then
            echo "  ok      resources/server/$asset is ours"
        else
            echo "  FAIL   resources/server/$asset is not the branded file"
            fail=1
        fi
    done

    # The artwork inside the workbench bundle, held to the same bar, and this is
    # the half that was missing. resources/server was checked and the workbench
    # was not, so a build that produced upstream's code-icon.svg (559 bytes, the
    # VS Code mark) passed Verify green while the Branding stage printed the path
    # as branded. That shipped once, and the only thing that noticed was a person
    # looking at the editor.
    #
    # Byte-identical is the right bar here too: these are copied, not processed.
    # Measured on a packaged tree, all seven match branding/workbench exactly.
    for asset in \
        out/vs/workbench/browser/media/code-icon.svg \
        out/vs/workbench/browser/parts/editor/media/letterpress-dark.svg \
        out/vs/workbench/browser/parts/editor/media/letterpress-hcDark.svg \
        out/vs/workbench/browser/parts/editor/media/letterpress-hcLight.svg \
        out/vs/workbench/browser/parts/editor/media/letterpress-light.svg \
        out/vs/sessions/contrib/chat/browser/media/letterpress-sessions-dark.svg \
        out/vs/sessions/contrib/chat/browser/media/letterpress-sessions-light.svg; do
        case "$asset" in
            *code-icon.svg) ours="$BRANDING/workbench/code-icon.svg" ;;
            *)              ours="$BRANDING/workbench/letterpress.svg" ;;
        esac
        if [ ! -f "$OUT/$asset" ]; then
            echo "  FAIL   $asset is missing from the package"
            fail=1
        elif cmp -s "$ours" "$OUT/$asset"; then
            echo "  ok      $asset is ours"
        else
            echo "  FAIL   $asset is not the branded file"
            fail=1
        fi
    done
fi

echo
echo "  output  : $OUT"
du -sh "$OUT" | awk '{print "  size    : "$1}'
df -h "$WORK" | tail -1 | awk '{print "  disk free after: "$4}'

[ "$fail" -eq 0 ] || { echo; echo "VERIFY FAILED"; exit 1; }

step "Package"
# Packed here rather than in the workflow so a local build and a CI build produce
# the same file. The contents are stored without a leading directory, so the
# fetcher extracts straight into whatever name the app expects.
TARBALL="$WORK/vscode-reh-web-linux-$ARCH-$VSCODE_VERSION.tar.gz"
t0=$SECONDS
tar -C "$OUT" -czf "$TARBALL" .
elapsed $(( SECONDS - t0 ))
echo "  tarball : $TARBALL"
du -h "$TARBALL" | awk '{print "  size    : "$1}'
echo
echo "=== Code - OSS reh-web build complete ==="
