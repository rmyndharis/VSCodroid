#!/usr/bin/env python3
"""Refuses an asset entry the base module and a toolchain pack both carry.

BundleTool rejects such a bundle outright, with one line naming the entry,
which is how a release tag can otherwise fail minutes before publishing:
the base runtime and the packs are filled by different download scripts,
and nothing compares them until the whole bundle exists. Each script knows
its own tree; this is the one place that knows both.

Run after the downloads that populate the trees. A check against an empty
pack would pass for the wrong reason, so a pack whose directory exists but
answers with nothing is refused rather than skipped, and so is a run that
found no pack at all: it printed "no overlap between base and" over an empty
list and exited 0. The Gradle task runs this only once a pack holds payload,
and release.yml runs it after downloading every pack, so neither caller meets
that case unless a download put its tree somewhere else.
"""

import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
BASE_ASSETS = REPO / "android/app/src/main/assets"
PACK_GLOB = "android/toolchain_*/src/main/assets"


def relative_paths(root: Path) -> set[str]:
    return {
        p.relative_to(root).as_posix()
        for p in root.rglob("*")
        if p.is_file()
    }


def main() -> int:
    if not BASE_ASSETS.is_dir():
        print(f"FAIL base assets not found at {BASE_ASSETS}")
        return 1
    base = relative_paths(BASE_ASSETS)
    if not base:
        print("FAIL base module answers with no assets; nothing to compare")
        return 1

    packs = [p for p in sorted(REPO.glob(PACK_GLOB)) if p.is_dir()]
    if not packs:
        print(f"FAIL no toolchain pack holds an assets directory ({PACK_GLOB}); "
              "nothing was compared with the base module")
        return 1

    overlaps = []
    for pack in packs:
        entries = relative_paths(pack)
        if not entries:
            print(f"FAIL {pack.parents[2].name} has an assets directory with nothing in it")
            return 1
        shared = sorted(base & entries)
        module = pack.parents[2].name
        for entry in shared:
            overlaps.append(f"{module}: assets/{entry}")

    if overlaps:
        for o in overlaps:
            print(f"FAIL both the base module and {o} ship this entry")
        print("Each asset belongs to one module. A pack needing a library the base")
        print("runtime already ships relies on the base copy, which is extracted")
        print("before any toolchain installs.")
        return 1

    print(f"ok -- no overlap between base and {', '.join(p.parents[2].name for p in packs)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
