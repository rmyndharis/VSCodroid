#!/usr/bin/env python3
"""Require a new directory version for changed bundled manifests or browser entries.

A directory is compared with its content in the first release tag that carries it,
which is what devices hold. One that no release tag carries yet has reached no
user's device, so commits that keep changing it before its first release, such as
the fix-ups of one pull request, need no further bump.
"""

import json
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parent.parent
EXTENSIONS = "android/app/src/main/assets/extensions"


def git(*args):
    return subprocess.check_output(["git", "-C", str(ROOT), *args], text=True).strip()


def refuse_shallow():
    if git("rev-parse", "--is-shallow-repository") == "true":
        sys.exit(
            "FAIL this is a shallow clone, where the tip reads as the commit that added every "
            "extension and an edit compares equal to itself. Run this where checkout sets "
            "fetch-depth: 0."
        )


def first_releases():
    """`{directory name: the oldest release tag whose tree carries it}`."""
    tags = git("tag", "--list", "v*", "--sort=version:refname").splitlines()
    if not tags:
        # Without tags every directory reads as unreleased and every edit passes.
        sys.exit(
            "FAIL no release tag (v*) is visible, so no directory can be told apart from one "
            "that never shipped and every edit would pass. Fetch the tags first."
        )
    first = {}
    for tag in tags:
        listed = subprocess.run(
            ["git", "-C", str(ROOT), "ls-tree", "--name-only", f"{tag}:{EXTENSIONS}"],
            capture_output=True, text=True,
        )
        for name in listed.stdout.splitlines() if listed.returncode == 0 else []:
            first.setdefault(name, tag)
    return first


def problems():
    failures = []
    released = first_releases()
    for directory in sorted((ROOT / EXTENSIONS).glob("vscodroid.*")):
        baseline = released.get(directory.name)
        if baseline is None:
            continue
        manifest = directory / "package.json"
        relative = manifest.relative_to(ROOT).as_posix()
        original = json.loads(git("show", f"{baseline}:{relative}"))
        current = json.loads(manifest.read_text())
        if json.dumps(current, sort_keys=True) != json.dumps(original, sort_keys=True):
            failures.append(f"{directory.name}: package.json changed without a directory version bump")
        original_files = git("ls-tree", "-r", "--name-only", f"{baseline}:{directory.relative_to(ROOT).as_posix()}")
        files = set(directory.glob("package.nls*.json"))
        files.update(directory / name for name in original_files.splitlines() if name.startswith("package.nls") and name.endswith(".json"))
        if original.get("browser"):
            files.add(directory / original["browser"])
        for file in sorted(files):
            if not file.is_file():
                failures.append(f"{directory.name}: {file.name} removed without a directory version bump")
                continue
            relative = file.relative_to(ROOT).as_posix()
            existed = subprocess.run(
                ["git", "-C", str(ROOT), "cat-file", "-e", f"{baseline}:{relative}"],
                capture_output=True,
            ).returncode == 0
            if not existed:
                failures.append(f"{directory.name}: {file.name} added without a directory version bump")
                continue
            before = git("show", f"{baseline}:{relative}")
            now = file.read_text().strip()
            same = (
                json.dumps(json.loads(before), sort_keys=True) == json.dumps(json.loads(now), sort_keys=True)
                if file.suffix == ".json" else before == now
            )
            if not same:
                failures.append(f"{directory.name}: {file.name} changed without a directory version bump")
    return failures


if __name__ == "__main__":
    refuse_shallow()
    failures = problems()
    for failure in failures:
        print(failure)
    if not failures:
        print("ok: bundled extension manifests and browser entries retain their directory version")
    sys.exit(bool(failures))
