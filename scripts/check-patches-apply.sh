#!/usr/bin/env bash
#
# Do the Android adaptations in patches/ still apply to upstream VS Code?
#
# The pinned commit is already covered: build-vscode-oss.sh applies every patch
# and set -e fails the build when one stops fitting. What nothing covered is the
# question this answers, which is about a version nobody has built yet: when the
# next bump happens, how much of patches/ survives it.
#
# Without this, the answer arrives all at once, in the middle of a bump, months
# after the upstream change that caused it. docs/08-RISK_MATRIX.md budgets five
# to ten days a month for exactly that, and lists "CI job that attempts to apply
# patches to latest VS Code" as its own mitigation.
#
# Applied cumulatively and in order, the way the build does it, rather than
# checked one at a time against a pristine tree. That is not a stylistic choice:
# two patches touch src/vs/server/node/remoteExtensionHostAgentServer.ts, so an
# independent --check would judge the second against source the first was
# supposed to have changed.
#
# Stops at the first failure and names it. A later patch failing after an earlier
# one already did says nothing reliable, since the tree it would apply to never
# came to exist.
#
# Usage:
#   scripts/check-patches-apply.sh              # newest stable upstream tag
#   scripts/check-patches-apply.sh 1.134.0      # a specific tag
#
# Exit codes: 0 all applied, 1 a patch failed, 2 the check could not run.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"
PATCHES="$ROOT_DIR/patches"
UPSTREAM="https://github.com/microsoft/vscode.git"

fail() { echo "$*" >&2; exit 2; }

[ -d "$PATCHES" ] || fail "no patches directory at $PATCHES"
# `|| true` is load-bearing under `set -o pipefail`: with no match the glob is
# passed through literally, `ls` exits non-zero, and the whole assignment fails,
# so `set -e` aborts here with no output at all and an exit code this file
# documents as "a patch failed". Measured, before the `|| true` was added.
patch_count=$(ls -1 "$PATCHES"/*.patch 2>/dev/null | wc -l | tr -d ' ') || true
# An empty patches directory would otherwise make this report success while
# checking nothing, which is the failure mode this whole file exists to prevent
# elsewhere.
[ "${patch_count:-0}" -gt 0 ] || fail "no *.patch files in $PATCHES; nothing to check"

TAG="${1:-}"
if [ -z "$TAG" ]; then
    echo "Resolving the newest stable tag from $UPSTREAM"
    TAG=$(git ls-remote --tags --refs "$UPSTREAM" \
        | awk '{print $2}' | sed 's|refs/tags/||' \
        | grep -E '^[0-9]+\.[0-9]+\.[0-9]+$' \
        | sort -V | tail -1) || true
    [ -n "$TAG" ] || fail "could not resolve any upstream tag; refusing to report success"
fi
case "$TAG" in
    *[!0-9.]*) fail "refusing a tag that is not plain digits and dots: $TAG" ;;
esac

PINNED=$(cat "$ROOT_DIR/VSCODE_VERSION" 2>/dev/null || echo "unknown")
echo "  pinned  : $PINNED"
echo "  checking: $TAG"
echo "  patches : $patch_count"
if [ "$TAG" = "$PINNED" ]; then
    echo
    echo "Upstream has not moved past the pin. The build already asserts these apply"
    echo "to it, so there is nothing here this run can add."
    exit 0
fi

# Every step below is guarded with `fail`, which exits 2, and that is the whole
# point of guarding them. Left bare, `set -e` ends the script with the failing
# command's own status, which for git and mktemp is 1, and 1 is the code this
# file's header assigns to "a patch failed". A runner out of temp space, or with
# no network to reach github.com, would then report the one thing it had proved
# nothing about: that the Android adaptations no longer apply upstream. The
# distinction is the reason the workflow can be read at a glance, and it is worth
# nothing if only some of the ways to fail respect it.
SRC=$(mktemp -d) || fail "could not create a work directory"
trap 'rm -rf "$SRC"' EXIT

echo
echo "Fetching $TAG (shallow)"
git -C "$SRC" init -q || fail "could not init a git repository in $SRC"
git -C "$SRC" remote add origin "$UPSTREAM" || fail "could not add the upstream remote"
git -C "$SRC" fetch -q --depth 1 origin "refs/tags/$TAG" || fail "could not fetch tag $TAG"
git -C "$SRC" checkout -q FETCH_HEAD || fail "could not check out $TAG"
head=$(git -C "$SRC" rev-parse --short HEAD) || fail "could not resolve the fetched commit"
echo "  HEAD    : $head"

echo
echo "Applying"
applied=0
for patch in "$PATCHES"/*.patch; do
    name=$(basename "$patch")
    if git -C "$SRC" apply --verbose "$patch" >"$SRC/.apply.log" 2>&1; then
        applied=$((applied + 1))
        echo "  ok      $name"
    else
        echo "  FAILED  $name"
        echo
        sed 's/^/    /' "$SRC/.apply.log"
        echo
        echo "$applied of $patch_count applied before this one."
        echo
        echo "This is not a broken build. The pinned tree at $PINNED is unaffected and"
        echo "still builds. What it says is that bumping to $TAG needs $name rebased"
        echo "first, and it is cheaper to learn that now than during the bump."
        exit 1
    fi
done

echo
echo "All $applied patches apply cleanly to $TAG."
