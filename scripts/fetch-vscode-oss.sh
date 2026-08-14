#!/usr/bin/env bash
set -euo pipefail

# Fetches the Code - OSS server tree that build-vscode-oss.yml published, and
# unpacks it where the build expects it.
#
#   ./scripts/fetch-vscode-oss.sh
#
# This replaced downloading a pre-built server from Microsoft's update CDN. That
# artifact is covered by the Microsoft pre-release licence, not MIT, so it could
# not be modified and redistributed inside an APK. The tree fetched here is built
# from the MIT source by .github/workflows/build-vscode-oss.yml, with this
# repository's patches and branding applied before the build.
#
# The expensive part — cloning VS Code and running gulp — happens once per VS
# Code version in that workflow, not on every app build. What is left here is a
# download and a check.
#
# Sources, in the order tried:
#   VSCODE_OSS_URL        a direct URL, for a private mirror or a local test
#   gh release download   the server-<version> release in this repository
#
# VSCODE_OSS_SHA256 checks the tarball against a digest you name, whatever source
# it came from. It exists for the VSCODE_OSS_URL path, which has no release to be
# compared against and otherwise reports its digest as UNCHECKED.
#
# Everything unpacked is verified before it is usable, because the failures that
# matter here are quiet: an x86-64 ripgrep installs fine and then returns no
# search results, and a tree from before a branding change ships Microsoft's
# product.json.

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

VSCODE_VERSION="${VSCODE_VERSION:-$(cat "$ROOT_DIR/VSCODE_VERSION")}"
ARCH="${ARCH:-arm64}"
REPO="${REPO:-rmyndharis/VSCodroid}"

TARBALL_NAME="vscode-reh-web-linux-$ARCH-$VSCODE_VERSION.tar.gz"
TARBALL="$ROOT_DIR/server/$TARBALL_NAME"
DEST="$ROOT_DIR/server/vscode-reh"

echo "=== Fetch Code - OSS server ==="
echo "  version : $VSCODE_VERSION"
echo "  tarball : $TARBALL_NAME"

mkdir -p "$ROOT_DIR/server"

# Kept under server/ so CI's existing server/*.tar.gz cache path covers it, and a
# rerun on a warm cache skips the download entirely.
#
# A cached file only counts as the artifact while it still matches the digest
# the release carries NOW. The server tarball is rebuilt in place when a patch
# changes, without the version moving, and CI's restore-keys will happily hand
# back the pre-rebuild bytes — which is exactly how a stale 190M tarball met the
# verify gate that its own commit had introduced, and lost. The VSCODE_OSS_URL
# path has no release to compare against; it is checked further down, against a
# digest the caller names.
if [ -f "$TARBALL" ] && [ -z "${VSCODE_OSS_URL:-}" ]; then
    # `|| true` collapsed three outcomes into one empty string: gh not
    # installed, gh unable to answer, and the release genuinely carrying no such
    # asset. All three then skipped the comparison in silence and the next line
    # printed "cached", which is how an unchecked tarball became a verified one.
    # Status and value are read separately now.
    gh_err="$(mktemp)"
    if expected="$(gh release view "server-$VSCODE_VERSION" --repo "$REPO" \
            --json assets \
            -q ".assets[] | select(.name==\"$TARBALL_NAME\") | .digest" 2>"$gh_err")"; then
        gh_ok=1
    else
        gh_ok=0
    fi
    gh_message="$(cat "$gh_err")"
    rm -f "$gh_err"

    if [ "$gh_ok" -eq 0 ]; then
        cat >&2 <<EOF

  Cannot check the cached server tarball against the release:

      $TARBALL

  gh could not report what server-$VSCODE_VERSION carries:

$gh_message

  That is not proof the cache is wrong. gh may be absent, unauthenticated or
  rate-limited, or the release may not be published yet. It is proof that
  nothing here can tell -- and an unchecked cache is how a tarball from before
  a rebuild reaches an APK. The two gates further down read the shape of the
  tree and the patch fingerprints, not the rest of the bytes.

  Pick one:

      gh auth login
          then run this again

      rm -f "$TARBALL"
          drop the cache and refetch it from the release

      VSCODE_OSS_SHA256=<digest> VSCODE_OSS_URL=file://$TARBALL $0
          deliberately offline, checked against a digest you name
EOF
        exit 1
    fi

    if [ -z "$expected" ] || [ "$expected" = "null" ]; then
        echo "  unpublished: server-$VSCODE_VERSION carries no $TARBALL_NAME," >&2
        echo "               so this cached file matches nothing published — discarding" >&2
        rm -f "$TARBALL"
    else
        actual="sha256:$( (sha256sum "$TARBALL" 2>/dev/null || shasum -a 256 "$TARBALL") | cut -d' ' -f1)"
        if [ "$actual" != "$expected" ]; then
            echo "  stale   : cached tarball digest $actual"
            echo "            release now carries  $expected — refetching"
            rm -f "$TARBALL"
        fi
    fi
fi

if [ -f "$TARBALL" ]; then
    echo "  cached  : $(du -h "$TARBALL" | cut -f1)"
elif [ -n "${VSCODE_OSS_URL:-}" ]; then
    echo "  source  : VSCODE_OSS_URL"
    curl -L --fail --show-error -o "$TARBALL" "$VSCODE_OSS_URL"
else
    echo "  source  : $REPO release server-$VSCODE_VERSION"
    # Download to a temporary name so an interrupted transfer cannot be picked up
    # as a cache hit on the next run.
    tmp="$(mktemp -d)"
    trap 'rm -rf "$tmp"' EXIT
    if ! gh release download "server-$VSCODE_VERSION" \
            --repo "$REPO" --pattern "$TARBALL_NAME" --dir "$tmp"; then
        cat >&2 <<EOF

  No server-$VSCODE_VERSION release with $TARBALL_NAME.

  Build and publish it first: run the "Build Code - OSS server" workflow, or
  set VSCODE_OSS_URL to a tarball you already have. The version comes from the
  VSCODE_VERSION file, so bumping that means running the workflow again.
EOF
        exit 1
    fi
    mv "$tmp/$TARBALL_NAME" "$TARBALL"
fi

echo "  size    : $(du -h "$TARBALL" | cut -f1)"

# A digest the caller names is checked whatever the tarball came from. The
# VSCODE_OSS_URL path is the reason it exists -- that path has no release to
# compare against, so it took whatever it was given, including a cached file
# left by an earlier and different URL. But honouring the variable only on that
# path would mean setting it anywhere else did nothing and said nothing, which
# is the same skipped check this script was just fixed for.
#
# A leading "sha256:" is accepted because that is the form `gh release view`
# prints, and the message above tells people to copy one from there.
if [ -n "${VSCODE_OSS_SHA256:-}" ]; then
    actual="$( (sha256sum "$TARBALL" 2>/dev/null || shasum -a 256 "$TARBALL") | cut -d' ' -f1)"
    if [ "$actual" != "${VSCODE_OSS_SHA256#sha256:}" ]; then
        cat >&2 <<EOF

  The tarball does not match VSCODE_OSS_SHA256.

      file     $TARBALL
      got      $actual
      expected ${VSCODE_OSS_SHA256#sha256:}
EOF
        exit 1
    fi
    echo "  digest  : matches VSCODE_OSS_SHA256"
elif [ -n "${VSCODE_OSS_URL:-}" ]; then
    echo "  digest  : UNCHECKED — VSCODE_OSS_URL has no release to compare against;"
    echo "            set VSCODE_OSS_SHA256 to check this tarball"
fi

echo
echo "=== Extract ==="
rm -rf "$DEST"
mkdir -p "$DEST"
tar -xzf "$TARBALL" -C "$DEST"
echo "  into    : $DEST"
du -sh "$DEST" | awk '{print "  size    : "$1}'

echo
echo "=== Verify ==="
# The same script the build ran on its own output. Running it again here is not
# redundant: the tarball may predate a branding or patch change, and this is the
# last point before the tree is copied into the APK.
python3 "$ROOT_DIR/scripts/verify-server-tree.py" "$DEST"

echo
echo "=== Patches ==="
# The same check build-vscode-oss.sh runs on its own output, here against the
# tree that will actually ship.
#
# This is the gate nothing else on this side can stand in for. A server tarball
# built before a patch landed has the same filename, the same version, and a
# digest that verifies -- a digest proves the bytes are intact, not that they are
# the right bytes. verify-server-tree.py does not help either: it checks the
# shape of the tree, and a tree missing a patch has exactly the right shape.
# Without this, a patch could sit in patches/ while every build quietly shipped a
# server that predates it, and the first symptom would be on a user's device.
if ! python3 "$ROOT_DIR/scripts/check-patch-fingerprints.py" "$DEST"; then
    cat >&2 <<EOF

  This server tree is missing at least one patch this checkout applies.

  It is almost certainly older than patches/. The fix is to rebuild and publish
  the server -- run the "Build Code - OSS server" workflow -- and then remove

      $TARBALL

  before running this again. A cached tarball is only refetched when the digest
  on the release changes, so a rebuild that has not been published will not
  reach you no matter how many times you retry.
EOF
    exit 1
fi

echo
echo "=== ripgrep ==="
# rg cannot run from where the rest of the tree lives. SELinux denies
# execute_no_trans on app_data_file for targetSdk >= 29, so anything under
# filesDir is unexecutable no matter how it is chmodded; binaries have to be
# packaged as lib*.so in jniLibs, which Android extracts into nativeLibraryDir
# with execute permission. FirstRunSetup.kt:270-288 then symlinks
# node_modules/@vscode/ripgrep-universal/bin/linux-arm64/rg at it, which is the
# path VS Code's search service looks for. That path moved in 1.133 -- the
# package used to be @vscode/ripgrep with a single bin/rg.
#
# libripgrep.so is gitignored, so this is its only source in a clean checkout —
# without it Search returns nothing and nothing else fails.
JNILIBS_DIR="$ROOT_DIR/android/app/src/main/jniLibs/arm64-v8a"
mkdir -p "$JNILIBS_DIR"
cp "$DEST/node_modules/@vscode/ripgrep-universal/bin/linux-arm64/rg" "$JNILIBS_DIR/libripgrep.so"
chmod +x "$JNILIBS_DIR/libripgrep.so"
echo "  rg -> libripgrep.so ($(du -h "$JNILIBS_DIR/libripgrep.so" | cut -f1))"
