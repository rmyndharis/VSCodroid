#!/usr/bin/env python3
"""Require a new directory version for changed bundled manifests or browser entries."""

import json
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parent.parent
EXTENSIONS = "android/app/src/main/assets/extensions"


def git(*args):
    return subprocess.check_output(["git", "-C", str(ROOT), *args], text=True).strip()


def problems():
    failures = []
    for directory in sorted((ROOT / EXTENSIONS).glob("vscodroid.*")):
        manifest = directory / "package.json"
        relative = manifest.relative_to(ROOT).as_posix()
        added = git("log", "--reverse", "--no-renames", "--diff-filter=A", "--format=%H", "--", relative).splitlines()
        if not added:
            continue
        original = json.loads(git("show", f"{added[0]}:{relative}"))
        current = json.loads(manifest.read_text())
        if json.dumps(current, sort_keys=True) != json.dumps(original, sort_keys=True):
            failures.append(f"{directory.name}: package.json changed without a directory version bump")
        original_files = git("ls-tree", "-r", "--name-only", f"{added[0]}:{directory.relative_to(ROOT).as_posix()}")
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
                ["git", "-C", str(ROOT), "cat-file", "-e", f"{added[0]}:{relative}"],
                capture_output=True,
            ).returncode == 0
            if not existed:
                failures.append(f"{directory.name}: {file.name} added without a directory version bump")
                continue
            before = git("show", f"{added[0]}:{relative}")
            now = file.read_text().strip()
            same = (
                json.dumps(json.loads(before), sort_keys=True) == json.dumps(json.loads(now), sort_keys=True)
                if file.suffix == ".json" else before == now
            )
            if not same:
                failures.append(f"{directory.name}: {file.name} changed without a directory version bump")
    return failures


if __name__ == "__main__":
    failures = problems()
    for failure in failures:
        print(failure)
    if not failures:
        print("ok: bundled extension manifests and browser entries retain their directory version")
    sys.exit(bool(failures))
