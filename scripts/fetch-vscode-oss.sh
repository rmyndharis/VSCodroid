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
# The expensive part (cloning VS Code and running gulp) happens once per VS
# Code version in that workflow, not on every app build. What is left here is a
# download and a check.
#
# Sources, in the order tried:
#   VSCODE_OSS_URL        a direct URL, for a private mirror or a local test
#   server/<name>.tar.gz  an existing cache, checked against the release digest
#   gh release download   the server-<version> release, checked against the same
#
# VSCODE_OSS_SHA256 checks the tarball against a digest you name, whatever source
# it came from. It exists for the VSCODE_OSS_URL path, which has no release to be
# compared against and otherwise reports its digest as UNCHECKED.
#
# Everything unpacked is verified before it is usable, because the failures that
# matter here are quiet: an x86-64 ripgrep installs fine and then returns no
# search results, a tree from before a branding change ships Microsoft's
# product.json, and a tree built from a commit the pin no longer names is
# indistinguishable from the right one until someone tries to reproduce a bug.

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

VSCODE_VERSION="${VSCODE_VERSION:-$(cat "$ROOT_DIR/VSCODE_VERSION")}"
# Both halves of the pin, because both are compared below. VSCODE_COMMIT was
# enforced only inside build-vscode-oss.sh, a workflow run by hand once per
# version bump, so on the side that actually produces an APK it was a file
# nothing read.
VSCODE_COMMIT="${VSCODE_COMMIT:-$(cat "$ROOT_DIR/VSCODE_COMMIT")}"
ARCH="${ARCH:-arm64}"
REPO="${REPO:-rmyndharis/VSCodroid}"

TARBALL_NAME="vscode-reh-web-linux-$ARCH-$VSCODE_VERSION.tar.gz"
TARBALL="$ROOT_DIR/server/$TARBALL_NAME"
DEST="$ROOT_DIR/server/vscode-reh"

echo "=== Fetch Code - OSS server ==="
echo "  version : $VSCODE_VERSION"
echo "  tarball : $TARBALL_NAME"

mkdir -p "$ROOT_DIR/server"

# What the release says this tarball should hash to, into `expected`.
#
# `|| true` collapsed three outcomes into one empty string: gh not installed, gh
# unable to answer, and the release genuinely carrying no such asset. All three
# then skipped the comparison in silence and the next line printed "cached",
# which is how an unchecked tarball became a verified one. Status and value are
# read separately for that reason, and both callers below decide on `gh_ok`
# rather than on emptiness.
#
# One lookup, two callers. The cache path asks whether the file on disk is still
# what the release carries; the download path asks whether the bytes that just
# arrived are what it carries at all. Those are different questions with the same
# answer key, and only the first was ever asked.
release_digest() {
    local gh_err
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
}

tarball_sha256() {
    (sha256sum "$TARBALL" 2>/dev/null || shasum -a 256 "$TARBALL") | cut -d' ' -f1
}

# Kept under server/ so CI's existing server/*.tar.gz cache path covers it, and a
# rerun on a warm cache skips the download entirely.
#
# A cached file only counts as the artifact while it still matches the digest
# the release carries NOW. The server tarball is rebuilt in place when a patch
# changes, without the version moving, and CI's restore-keys will happily hand
# back the pre-rebuild bytes, which is exactly how a stale 190M tarball met the
# verify gate that its own commit had introduced, and lost. The VSCODE_OSS_URL
# path has no release to compare against; it is checked further down, against a
# digest the caller names.
if [ -f "$TARBALL" ] && [ -z "${VSCODE_OSS_URL:-}" ]; then
    release_digest

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
          deliberately offline. This pins the file to bytes you have already
          decided to trust; it does not check them against the release, which
          is the thing that cannot be reached right now.
EOF
        exit 1
    fi

    if [ -z "$expected" ] || [ "$expected" = "null" ]; then
        # Deliberately not deleting the file. The download below cannot succeed
        # either -- the release carries no such asset -- so removing the cache
        # buys nothing and can destroy the only copy someone has, which is a
        # real state: a release whose 200 MB upload timed out exists with no
        # assets on it.
        cat >&2 <<EOF

  The server-$VSCODE_VERSION release carries no $TARBALL_NAME, so there is
  nothing to check this against:

      $TARBALL

  The release exists but its asset does not. Publish it -- run the "Build
  Code - OSS server" workflow -- or, if you trust this file, name its digest:

      VSCODE_OSS_SHA256=<digest> VSCODE_OSS_URL=file://$TARBALL $0
EOF
        exit 1
    else
        actual="sha256:$(tarball_sha256)"
        if [ "$actual" != "$expected" ]; then
            echo "  stale   : cached tarball digest $actual"
            echo "            release now carries  $expected, refetching"
            rm -f "$TARBALL"
        fi
    fi
fi

# A named URL wins over the cache. It used to lose -- the cached-file test came
# first, so a caller who named a URL silently got the cache instead. That is
# exactly the situation the build prints the URL remedy for: the
# checkPatchFingerprints failure in app/build.gradle.kts tells you to run
# VSCODE_OSS_URL=file:///path/to/the.tar.gz, and it only ever fires when a stale
# server/*.tar.gz is present -- which is the one condition under which the
# instruction did nothing at all.
#
# Downloaded through a temp file because the remedy this script itself prints is
# VSCODE_OSS_URL=file://$TARBALL, and curl reading and writing one path truncates
# it to zero bytes before it has read a thing.
if [ -n "${VSCODE_OSS_URL:-}" ]; then
    echo "  source  : VSCODE_OSS_URL"
    tmp="$(mktemp -d)"
    trap 'rm -rf "$tmp"' EXIT
    curl -L --fail --show-error -o "$tmp/$TARBALL_NAME" "$VSCODE_OSS_URL"
    mv "$tmp/$TARBALL_NAME" "$TARBALL"
elif [ -f "$TARBALL" ]; then
    echo "  cached  : $(du -h "$TARBALL" | cut -f1)"
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

    # The same comparison the cache path runs, on the path that had none. A
    # cached tarball was held to the release digest while a freshly downloaded
    # one was taken on trust, which is backwards: the cache was at least checked
    # once when it was written, and these bytes have been checked by nothing.
    # gh handing over the asset says the transfer ended, not that it ended
    # intact.
    release_digest
    if [ "$gh_ok" -eq 0 ]; then
        cat >&2 <<EOF

  Downloaded $TARBALL_NAME but cannot check it against the release:

      $TARBALL

  gh could not report what server-$VSCODE_VERSION carries:

$gh_message

  gh served the asset and then failed to answer, so this is a fault of the run,
  not of the bytes. Run it again. If it keeps happening, pin the file to a
  digest you have already decided to trust -- both halves are needed, because
  the digest is only read on the URL path:

      VSCODE_OSS_SHA256=<digest> VSCODE_OSS_URL=file://$TARBALL $0
EOF
        exit 1
    fi

    if [ -z "$expected" ] || [ "$expected" = "null" ]; then
        cat >&2 <<EOF

  The server-$VSCODE_VERSION release names no digest for the asset it just
  served, so there is nothing to check these bytes against:

      $TARBALL

  Every release in this repository carries one, so an asset without one is
  worth understanding before trusting it. Republish it -- run the "Build
  Code - OSS server" workflow -- or, if you trust this file, name its digest:

      VSCODE_OSS_SHA256=<digest> VSCODE_OSS_URL=file://$TARBALL $0
EOF
        exit 1
    fi

    actual="sha256:$(tarball_sha256)"
    if [ "$actual" != "$expected" ]; then
        # Left on disk rather than deleted. The cache path deletes on a mismatch
        # so the next run refetches, but here the refetch is what just happened,
        # and deleting would drop the only evidence of what arrived -- then fall
        # through to `du -h` on a path that no longer exists. The next run's
        # cache check reaches the same verdict and does the deleting.
        cat >&2 <<EOF

  The downloaded tarball is not what server-$VSCODE_VERSION records.

      file     $TARBALL
      got      $actual
      expected $expected

  A transfer that ended is not a transfer that arrived intact. Run this again;
  the file is left in place for inspection and the next run refetches it.
EOF
        exit 1
    fi
    echo "  digest  : matches server-$VSCODE_VERSION"
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
    # Lower-cased before the prefix is stripped: ${VAR#sha256:} matches only a
    # literal lowercase label, and a value copied as SHA256:... would otherwise
    # survive the strip and be reported as a content mismatch it is not.
    want="$(printf '%s' "$VSCODE_OSS_SHA256" | tr 'A-Z' 'a-z')"
    want="${want#sha256:}"
    actual="$(tarball_sha256)"
    if [ "$actual" != "$want" ]; then
        cat >&2 <<EOF

  The tarball does not match VSCODE_OSS_SHA256.

      file     $TARBALL
      got      $actual
      expected $want
EOF
        exit 1
    fi
    echo "  digest  : matches VSCODE_OSS_SHA256"
elif [ -n "${VSCODE_OSS_URL:-}" ]; then
    echo "  digest  : UNCHECKED, VSCODE_OSS_URL has no release to compare against;"
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
echo "=== Pin ==="
# The tree answers this itself: the build stamps the source commit and version
# it was made from into product.json, and both pin files sit beside this script.
#
# Nothing on this side compared them. The version half was guarded by accident,
# through the tarball's filename, and not even that under VSCODE_OSS_URL,
# which names any file it likes. The commit half was guarded nowhere: it is
# checked in build-vscode-oss.sh, which runs on workflow_dispatch, and appears
# in build.yml and release.yml only as part of a cache key.
#
# What that let through: move VSCODE_COMMIT to a fix commit without re-running
# the "Build Code - OSS server" workflow. The cache key changes, so the fetch
# runs again, and downloads the same server-<version> release, built from the
# old source. Every gate downstream stays green, correctly: the tree is intact,
# correctly shaped and carries every patch fingerprint. It is simply not the
# source the pin names. write-build-manifest.py then copies the pin file into
# build-manifest.txt and release.yml attaches it, so the one record that ties a
# shipped APK back to its source names a commit that did not build it.
#
# Reported by this script rather than by verify-server-tree.py, which runs in
# three places including inside the build container, where only /scripts,
# /patches and /branding are mounted and the pin files are not readable at all.
if ! python3 - "$DEST/product.json" "$VSCODE_COMMIT" "$VSCODE_VERSION" <<'PIN'; then
import json
import sys

path, want_commit, want_version = sys.argv[1:4]
try:
    product = json.loads(open(path, encoding="utf-8").read())
except (OSError, ValueError) as exc:
    print(f"  FAIL    product.json could not be read  {exc}")
    sys.exit(1)

bad = False
for label, got, want in (("commit", product.get("commit"), want_commit),
                         ("version", product.get("version"), want_version)):
    if got == want:
        print(f"  ok      {label}  {got}")
    else:
        print(f"  FAIL    {label}  tree says {got!r}, pin says {want!r}")
        bad = True
sys.exit(1 if bad else 0)
PIN
    cat >&2 <<EOF

  This server tree was not built from the commit this checkout pins.

  The tarball is named after VSCODE_VERSION alone, so a release that was built
  before the pin moved has the right name, the right digest and the right patch
  fingerprints. Only product.json records which source it came from.

  Either the pin moved and the server was not rebuilt, run the "Build Code -
  OSS server" workflow and then remove

      $TARBALL

  so the new release is fetched instead of this cache, or VSCODE_COMMIT and
  VSCODE_VERSION disagree with each other, in which case fix the files.
EOF
    exit 1
fi

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
# libripgrep.so is gitignored, so this is its only source in a clean checkout:
# without it Search returns nothing and nothing else fails.
JNILIBS_DIR="$ROOT_DIR/android/app/src/main/jniLibs/arm64-v8a"
mkdir -p "$JNILIBS_DIR"
RG_SRC="$DEST/node_modules/@vscode/ripgrep-universal/bin/linux-arm64/rg"

# The same gate every other binary in jniLibs already gets, and this was the one
# without it. verify-server-tree.py above reads rg's e_machine and stops there:
# it does not read LOAD alignment, which Android 16 refuses below 16 KB, and it
# does not read DT_NEEDED, so an rg linked against something nothing bundles
# would copy in cleanly and then fail at exec -- with Search returning no
# results, which is the same thing an empty workspace looks like.
#
# This file is also the only bundled ELF that no step here produces: every other
# one comes from a download script that fetched and checked it, while this is a
# plain cp out of a tarball built in another workflow on another machine. It is
# checked twice for that reason -- here, and again over the whole of jniLibs by
# the verifyBundledBinaries Gradle task at packaging time.
#
# Checked at the source, BEFORE the copy, so a rejected rg never becomes
# libripgrep.so at all. The sibling scripts all install first and verify after,
# which leaves the rejected binary sitting in the packaging directory when they
# fail; CI is unharmed because the job dies and actions/cache declares
# post-if: success(), but a developer whose fetch failed here and who then runs
# ./gradlew assembleDebug directly would package the very binary this refused --
# and no later gate would notice, because verify-server-tree.py reads e_machine
# and that is not what is wrong with it.
python3 "$ROOT_DIR/scripts/verify-android-elf.py" "$RG_SRC"

cp "$RG_SRC" "$JNILIBS_DIR/libripgrep.so"
chmod +x "$JNILIBS_DIR/libripgrep.so"
echo "  rg -> libripgrep.so ($(du -h "$JNILIBS_DIR/libripgrep.so" | cut -f1))"
