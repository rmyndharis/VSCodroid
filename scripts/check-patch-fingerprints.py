#!/usr/bin/env python3
"""Check a packaged tree carries every patch this repository applies.

    check-patch-fingerprints.py <tree> [patches-dir]

Applying a patch to the source proves nothing about the package: the file may
not be in the target's graph, or the build may inline an older copy. Each patch
therefore leaves a fingerprint that survives minification, and this searches the
packaged tree for it. The expectations live in patches/fingerprints.txt.

Two things this fixes about the check it replaces, which was a heredoc inside
build-vscode-oss.sh:

  * It walks patches/, not the table. The old loop iterated its own rows, so a
    patch added without a row produced a run of "ok" lines and passed. Two
    patches legitimately have no fingerprint, and their absence looked identical
    to an oversight -- the gate could not tell deliberate from forgotten, so
    exemptions are now written down as lines that say how the patch is proven
    instead.

  * It takes the tree as an argument, like verify-server-tree.py, which is what
    lets the same check run on both sides. build-vscode-oss.sh checks what it
    built; fetch-vscode-oss.sh can check what it downloaded, and that second
    caller is the one that matters: a server tarball predating a patch has the
    same name, the same version, and a digest that verifies, because a digest
    proves the tarball is intact rather than that it is the right tarball.
"""

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent

# The patches directory is an argument rather than a fixed path because
# build-vscode-oss.sh takes PATCHES from the environment: a local Docker run
# mounts it at /patches. Resolving it here from this file's own location would
# check the repository's patches while the build applied the mounted ones, and
# the two would disagree without saying so. The table lives inside that
# directory, so it follows whichever one is in use.
DEFAULT_PATCHES = ROOT / "patches"

# 0001-platform-treat-android-as-linux.patch -> 0001
PATCH_ID = re.compile(r"^(\d{4})-.*\.patch$")

failed = False


def check(ok, label, detail=""):
    global failed
    print(f"  {'ok     ' if ok else 'FAIL   '} {label}{'' if ok else '  ' + detail}")
    if not ok:
        failed = True


def read_table(TABLE):
    """{id: (label, bundle, pattern)}; bundle '-' means an explicit exemption."""
    rows = {}
    for lineno, raw in enumerate(TABLE.read_text().splitlines(), 1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        head, _, rest = line.partition("|")
        bundle, _, pattern = rest.partition("|")
        ident = head.split(None, 1)[0] if head.split() else ""
        if not re.fullmatch(r"\d{4}", ident) or not bundle or not pattern:
            print(f"  FAIL   {TABLE.name}:{lineno} is not "
                  f"'NNNN label|bundle|pattern': {line}")
            return None
        rows[ident] = (head.split(None, 1)[1] if " " in head else "", bundle, pattern)
    return rows


def main(tree, patches_dir):
    TABLE = patches_dir / "fingerprints.txt"
    if not TABLE.is_file():
        print(f"  FAIL   no fingerprint table at {TABLE}")
        return 1

    rows = read_table(TABLE)
    if rows is None:
        return 1

    patches = {}
    for path in sorted(patches_dir.glob("*.patch")):
        m = PATCH_ID.match(path.name)
        if m:
            patches[m.group(1)] = path.name

    # An empty left-hand side would make every comparison below vacuously true.
    if not patches:
        print(f"  FAIL   no patches found in {patches_dir}; the naming changed")
        return 1

    for ident, name in sorted(patches.items()):
        if ident not in rows:
            check(False, f"{name} has no line in {TABLE.name}",
                  "add its fingerprint, or a line saying how it is proven instead")
            continue
        label, bundle, pattern = rows[ident]
        if bundle == "-":
            # Not a pass by omission: the line states the reason, and it had to be
            # written for the patch to get here at all.
            print(f"  ok      {ident} {label} has no fingerprint -- {pattern}")
            continue
        target = tree / bundle
        if not target.is_file():
            check(False, f"{ident} {label}: {bundle} is not in the tree")
        else:
            check(pattern in target.read_text(errors="ignore"),
                  f"{ident} {label} reached {pathlib.Path(bundle).name}",
                  f"{bundle} does not contain {pattern!r}")

    # A row naming a patch that no longer exists is stale rather than harmless:
    # it is the only remaining place someone would look to learn the patch is
    # gone, and it would keep asserting against a tree forever.
    for ident in sorted(set(rows) - set(patches)):
        check(False, f"{TABLE.name} has a line for {ident}, which is not in patches/",
              "remove the line, or restore the patch")

    return 1 if failed else 0


if __name__ == "__main__":
    if len(sys.argv) not in (2, 3):
        print("usage: check-patch-fingerprints.py <tree> [patches-dir]", file=sys.stderr)
        sys.exit(2)
    root = pathlib.Path(sys.argv[1])
    patches = pathlib.Path(sys.argv[2]) if len(sys.argv) == 3 else DEFAULT_PATCHES
    for label, path in (("tree", root), ("patches directory", patches)):
        if not path.is_dir():
            print(f"  FAIL    {path} is not a directory ({label})", file=sys.stderr)
            sys.exit(1)
    sys.exit(main(root, patches))
