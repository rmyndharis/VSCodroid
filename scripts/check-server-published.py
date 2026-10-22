#!/usr/bin/env python3
"""A release may ship the published server only if this commit would have built it.

    check-server-published.py

release.yml does not build the Code - OSS server. It downloads the `server-<version>`
release that build-vscode-oss.yml published, and what it checks on the way in compares only
the pin, `patches/` and `branding/product.json` with that tree. A change to
build-vscode-oss.sh or to patch-js-debug-env.py alone passes every one of those checks, and
the tag ships the tree the old script built. A change to a patch is refused, but only at the
tag, after it was pushed.

This asks first: does any file that decides the server tree differ between this commit and the
commit the `server-<version>` tag points at? Each publish moves that tag to the commit it
built (build-vscode-oss.yml, "Point the tag at the commit that was built"), so the tag names
the source of the asset that will be downloaded. release.yml runs this before anything is
built, on a tag and on a dispatched run alike.

The files are the ones codeoss-build.yml hashes into the key of its tarball cache, read from
that file rather than listed here, so the cache and this check cannot disagree about what
decides the tree. An edit to codeoss-build.yml itself is an input change, comments included,
which is the cache's own rule.

It compares the contents of the two commits, not their ancestry: a publish can be dispatched
from a branch that was squash-merged since, so the tag may sit on a commit main never held.
Uncommitted edits are not seen. A clone that fetched the tag before a rebuild holds the old
commit, because `git fetch --tags` refuses to move it, and needs
`git fetch --force origin 'refs/tags/server-*:refs/tags/server-*'`.
"""

import pathlib
import re
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
WORKFLOW = ".github/workflows/codeoss-build.yml"

# What the cache key must still name for this check to mean anything. A parse that came back
# short would wave through every change to the files it missed.
MUST_NAME = ("VSCODE_VERSION", "VSCODE_COMMIT", "patches/**", "branding/**",
             "scripts/build-vscode-oss.sh", WORKFLOW)

REPUBLISH = ('Dispatch the "Build Code - OSS server" workflow (build-vscode-oss.yml) from '
             "this ref, wait for it to finish, and run this again.")


def server_inputs(workflow_text):
    """The patterns codeoss-build.yml hashes into the key of its tarball cache."""
    calls = re.findall(r"hashFiles\(([^)]*)\)", workflow_text)
    if len(calls) != 1:
        raise ValueError(f"{WORKFLOW} has {len(calls)} hashFiles() calls and this reads exactly "
                         "one, the tarball cache key; read the file again before trusting this check")
    inputs = re.findall(r"'([^']+)'", calls[0])
    lost = [name for name in MUST_NAME if name not in inputs]
    if lost:
        raise ValueError(f"the cache key in {WORKFLOW} no longer names {', '.join(lost)}")
    return inputs


def git(*args):
    return subprocess.run(["git", "-C", str(ROOT), *args], capture_output=True, text=True)


def fail(first, *rest):
    print(f"::error::{first}")
    for line in rest:
        print(f"  {line}")
    return 1


def main() -> int:
    version = (ROOT / "VSCODE_VERSION").read_text().strip()
    if not re.fullmatch(r"[0-9]+(\.[0-9]+)*", version):
        return fail(f"VSCODE_VERSION holds {version!r}, which is not a version number")
    try:
        inputs = server_inputs((ROOT / WORKFLOW).read_text())
    except ValueError as exc:
        return fail(str(exc))
    # `glob` magic so that ** reaches into subdirectories the way hashFiles does.
    specs = [f":(glob){pattern}" for pattern in inputs]
    tag = f"server-{version}"

    found = git("rev-parse", "--verify", "--quiet", f"refs/tags/{tag}^{{commit}}")
    if found.returncode:
        return fail(
            f"This clone has no {tag} tag, so nothing says which sources the server was built from.",
            f"Either it was never published. {REPUBLISH}",
            "Or the checkout did not fetch tags: actions/checkout needs fetch-depth: 0, and a",
            "local clone needs git fetch --force origin 'refs/tags/server-*:refs/tags/server-*'.")
    built = found.stdout.strip()

    tracked = git("ls-files", "--", *specs).stdout.split()
    if not tracked:
        return fail("None of the server build inputs exist in this checkout, so this compared nothing.")
    diff = git("diff", "--name-only", "--no-renames", built, "HEAD", "--", *specs)
    if diff.returncode:
        return fail(f"git could not compare {built[:8]} with HEAD: {diff.stderr.strip()}")
    changed = diff.stdout.split()
    if changed:
        return fail(
            f"{tag} was built from {built[:8]}; {len(changed)} file(s) that decide the "
            "server tree differ in this commit:",
            *(f"  {path}" for path in changed),
            "release.yml would ship the published tree, which was built without these changes.",
            REPUBLISH,
            "A local clone that fetched the tag before it moved holds the old commit and needs",
            "git fetch --force origin 'refs/tags/server-*:refs/tags/server-*' first.")

    print(f"  ok     {tag} was built from {built[:8]}; none of the {len(inputs)} patterns "
          f"that decide the server tree ({len(tracked)} files) differ at HEAD")
    return 0


if __name__ == "__main__":
    sys.exit(main())
