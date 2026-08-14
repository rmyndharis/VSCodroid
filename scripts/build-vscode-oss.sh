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
# fails from clean — which is how an unpatched CI build went unnoticed. Before
# trusting a local green, `docker volume rm vscodroid-codeoss`.
#
# Run it with a named volume rather than a bind mount — npm ci writes on the
# order of 100k files, and a Docker Desktop bind mount makes that pathologically
# slow:
#
#   docker volume create vscodroid-codeoss
#   docker volume create vscodroid-npm-cache
#   docker run --rm -v vscodroid-codeoss:/work \
#       -v vscodroid-npm-cache:/root/.npm \
#       -e VSCODE_VERSION="$(cat VSCODE_VERSION)" \
#       -v "$PWD/scripts:/scripts:ro" \
#       -v "$PWD/branding:/branding" \
#       -v "$PWD/patches:/patches:ro" \
#       vscodroid-codeoss-build:24.18.0 bash /scripts/build-vscode-oss.sh
#
# Run it on an arm64 host. Every native module in the tree is built for the build
# host, and only two of them are overlaid afterwards by build-native-addons.sh —
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
# gulpfile.reh.js: BUILD_ROOT = path.dirname(REPO_ROOT), and destinationFolderName
# carries no `min` suffix — the min and non-min variants share this directory.
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
    git clone --depth 1 --branch "$VSCODE_VERSION" \
        https://github.com/microsoft/vscode.git "$SRC"
    elapsed $(( SECONDS - t0 ))
else
    echo "  already cloned"
fi
cd "$SRC"
echo "  HEAD    : $(git rev-parse --short HEAD)"
echo "  .nvmrc  : $(cat .nvmrc)"
[ "v$(cat .nvmrc)" = "$(node --version)" ] || echo "  WARNING: node does not match .nvmrc"

step "Patches"
# The Android adaptations, as real diffs against readable source. git apply exits
# non-zero when the context has shifted, and set -e turns that into a failed
# build — which is the whole reason these are moving here from regexes against
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
    echo "  no patches at $PATCHES — building unadapted (ALLOW_UNADAPTED set)"
else
    echo "  ERROR: no patches at $PATCHES" >&2
    echo "  A tree without them is not this product: no Android platform" >&2
    echo "  detection, no worker_thread hosts, no Open VSX target platform." >&2
    echo "  Set ALLOW_UNADAPTED=1 to build one deliberately." >&2
    exit 1
fi

step "Branding"
# Skippable so the stage can be run bare when isolating a build problem, but only
# on purpose: an unbranded tree carries Microsoft's product name and icons, which
# is exactly what this build exists to avoid shipping.
BRANDING="${BRANDING:-$REPO_ROOT/branding}"
if [ -d "$BRANDING" ]; then
    # Restore what this stage overwrites before overwriting it. The overlay is
    # applied with update(), so on a reused work volume it merges into the
    # previous run's output — which means a key REMOVED from the overlay stays in
    # the file forever, and the stage silently stops matching what it declares.
    # That cost three build attempts: extensionsGallery was dropped from the
    # overlay and the build kept downloading builtin extensions from Open VSX.
    git -C "$SRC" checkout -- product.json resources/server/ \
        src/vs/workbench/browser/media/code-icon.svg \
        src/vs/workbench/browser/parts/editor/media/ 2>/dev/null || true
    python3 - "$BRANDING/product.json" "$SRC/product.json" <<'PY'
import json, sys

overlay_path, product_path = sys.argv[1], sys.argv[2]
overlay = json.load(open(overlay_path))
product = json.load(open(product_path))

removed = [k for k in overlay.get("remove", []) if product.pop(k, None) is not None]
product.update(overlay.get("set", {}))

# Two spaces and a trailing newline: this file is read by humans when a build
# behaves oddly, and gulp only cares that it parses.
with open(product_path, "w") as f:
    json.dump(product, f, indent=2)
    f.write("\n")

print(f"  product.json: {len(overlay.get('set', {}))} set, {len(removed)} removed, {len(product)} keys")
for key in ("nameLong", "applicationName", "urlProtocol"):
    print(f"    {key} = {product[key]}")
PY

    # gulpfile.reh.js:343-351 copies these four into the reh-web package as they
    # are. Upstream they are Microsoft's VS Code icon and name, which must not
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
            echo "  gulpfile.reh.js copies these four into the package as they are, so" >&2
            echo "  the alternative to ours is Microsoft's. Restore the file, or remove" >&2
            echo "  it from this list if the packaging stopped using it." >&2
            exit 1
        fi
        cp "$BRANDING/server/$asset" "$SRC/resources/server/$asset"
        echo "  resources/server/$asset"
    done

    # Two more marks live outside resources/server, inside the workbench bundle:
    # the titlebar app icon and the empty-editor watermark. One theme-agnostic
    # letterpress stands in for upstream's four themed variants.
    if [ ! -f "$BRANDING/workbench/code-icon.svg" ] || [ ! -f "$BRANDING/workbench/letterpress.svg" ]; then
        echo "  ERROR: $BRANDING/workbench is incomplete" >&2
        echo "  code-icon.svg is the titlebar mark and letterpress.svg the empty-editor" >&2
        echo "  watermark. Upstream's are the VS Code logo, which is copyrighted and" >&2
        echo "  cannot travel in this APK. Both files are required." >&2
        exit 1
    fi
    cp "$BRANDING/workbench/code-icon.svg" \
        "$SRC/src/vs/workbench/browser/media/code-icon.svg"
    echo "  src/vs/workbench/browser/media/code-icon.svg"
    for variant in dark hcDark hcLight light; do
        cp "$BRANDING/workbench/letterpress.svg" \
            "$SRC/src/vs/workbench/browser/parts/editor/media/letterpress-$variant.svg"
    done
    echo "  src/vs/workbench/browser/parts/editor/media/letterpress-{dark,hcDark,hcLight,light}.svg"
elif [ -n "${ALLOW_UNADAPTED:-}" ]; then
    echo "  no branding at $BRANDING — building unbranded (ALLOW_UNADAPTED set)"
else
    echo "  ERROR: no branding at $BRANDING" >&2
    echo "  The tree would carry Microsoft's name and icons." >&2
    echo "  Set ALLOW_UNADAPTED=1 to build one deliberately." >&2
    exit 1
fi

step "Dependencies (npm ci)"
# Around 3 GB over the wire, and a single reset anywhere in it fails the whole
# stage — which then costs another full download to retry. npm's own retry only
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
# nothing here references it — the runtime uses nativeLibraryDir/libnode.so. The
# OSS build produces it byte-for-byte the same as the proprietary one, so the
# pivot does not remove it and this stays a post-build step. Doing it here rather
# than patching the gulpfile keeps it upright across version bumps.
if [ -f "$OUT/node" ]; then
    size=$(du -h "$OUT/node" | cut -f1)
    rm -f "$OUT/node"
    echo "  removed the unusable GNU/Linux node binary ($size)"
fi

# gulp's reh-web package task leaves both of these behind, and product.json still
# names one of them in licenseFileName. Code - OSS is MIT, and MIT asks that the
# copyright notice travel with the copies — this tree is redistributed inside
# every APK, so the notice has to be in it.
for f in LICENSE.txt ThirdPartyNotices.txt; do
    if [ -f "$SRC/$f" ]; then
        cp "$SRC/$f" "$OUT/$f"
        echo "  added $f"
    fi
done

step "Mobile CSS"
# Touch overrides for the hamburger menu, and the Accounts/Manage hide that the
# activity bar sizing patch (0008) assumes. The pre-pivot build appended these to
# the packaged workbench.css; the mutation lost its home when the download
# script was deleted, so menus have been desktop-sized on device since. An
# append rather than a patch because it is additive CSS against a generated
# file - there is no source context to drift. Guarded idempotent so a reused
# work volume does not accumulate copies.
#
# Two known ceilings, both parity with the pre-pivot CSS rather than new: the
# :has() selector needs Chrome 105, which is exactly the project's minimum
# WebView - correct by target, but with no margin; and [aria-label="Manage"]
# matches the English UI only, so on a localized workbench the Manage section
# reappears instead of hiding. Structural selectors would fix the latter if it
# ever matters.
WORKBENCH_CSS="$OUT/out/vs/code/browser/workbench/workbench.css"
if [ ! -f "$WORKBENCH_CSS" ]; then
    echo "  ERROR: $WORKBENCH_CSS not in the package" >&2
    exit 1
fi
if ! grep -q "VSCodroid: Mobile-friendly" "$WORKBENCH_CSS"; then
    cat >> "$WORKBENCH_CSS" << 'CSSEOF'

/* VSCodroid: Mobile-friendly hamburger menu overrides */
.menubar-menu-items-holder { min-width: 280px !important; }
.monaco-menu .monaco-action-bar.vertical .action-item { min-height: 44px !important; }
.monaco-menu .monaco-action-bar.vertical .action-label { font-size: 14px !important; padding: 8px 24px 8px 12px !important; line-height: 28px !important; }
.monaco-menu .submenu-indicator { font-size: 16px !important; }
.monaco-menu .keybinding { font-size: 12px !important; }
.monaco-menu .monaco-action-bar.vertical .action-label.separator { margin: 4px 8px !important; }
/* VSCodroid: Hide Accounts/Manage section in Activity Bar */
.activitybar .content > div:has(.actions-container[aria-label="Manage"]) { display: none !important; }
.activitybar .content > .composite-bar { flex-grow: 1 !important; }
CSSEOF
fi
grep -q "VSCodroid: Mobile-friendly" "$WORKBENCH_CSS" || { echo "  ERROR: append did not land" >&2; exit 1; }
echo "  appended mobile menu overrides to workbench.css"

step "Verify"
fail=0

# Tree shape, branding and native architecture are checked by the same script the
# fetcher runs, so what is proven here is exactly what is proven again on the way
# into an APK. The build-specific checks — that each patch survived into the
# packaged bundles, and that the product.json key set has not drifted — stay here,
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
# key has to be looked at deliberately — silently inheriting one is how a CDN
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
    # that gets redistributed. gulpfile.reh.js copies resources/server across
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
