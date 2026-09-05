#!/usr/bin/env python3
"""Lets js-debug's helper processes start on a device with no /usr.

    patch-js-debug-env.py <server-tree>
    patch-js-debug-env.py --check <server-tree>

js-debug launches a program by asking the terminal to run it, then starts two
helpers of its own that carry the debugger connection: a watchdog child, and a
synchronous probe that blocks until the debugger attaches. Both are spawned with
an `env` object that REPLACES the environment rather than extending it, and it
holds three variables:

    NODE_INSPECTOR_INFO, NODE_SKIP_PLATFORM_CHECK, ELECTRON_RUN_AS_NODE

On every platform js-debug was written for that is enough, because the child is
Node and Node is on the default path with its libraries where the loader looks.
Neither is true here. This app's Node is `libnode.so` in `nativeLibraryDir`,
reached through a symlink on a PATH the app builds, and it is dynamically linked
against libraries in `usr/lib` that only LD_LIBRARY_PATH names. Measured:

    CANNOT LINK EXECUTABLE "...libnode.so": library "libz.so.1" not found

and, before that even gets a chance, the watchdog is spawned as the bare name
`node`, which an empty PATH cannot resolve. js-debug asks `/usr/bin/which` for a
full path first and falls back to the bare name when that is missing, which on
Android it always is.

Nothing reports either failure. The spawn is `stdio: "ignore"` and its result is
unref'd, so the helper dies unseen while the program blocks in `Atomics.wait`
for a debugger that will never attach. What a user sees is a debug session that
starts, shows the toolbar and the status bar, and never runs their program.

So two keys are added to each of those objects, taken from the parent:

    PATH, LD_LIBRARY_PATH

Not a patch under `patches/`: that directory is applied to the microsoft/vscode
source with `git apply`, and js-debug is not in it. It arrives as a built
extension the VS Code build downloads, so the edit belongs where the tree is
assembled, in the same family as `patch-default-shell.py`.

The edit is anchored on `ELECTRON_RUN_AS_NODE:"1"` inside an `env:{...}` that
does not spread `process.env`, which is exactly the shape that needs it. One
further mark in the file is a port probe that spreads the parent environment
already and needs nothing, named here by that shape rather than by its position:
the count below is read by someone comparing occurrences after upstream has
moved them, which is the one moment an ordinal is wrong. Two sites are expected.
Any other count is a build failure rather than a warning, because a miss here is
invisible in every other gate and shows up only as a debug session that hangs.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

BOOTLOADER = "extensions/ms-vscode.js-debug/src/bootloader.js"

# The literal that marks a spawn env, and the two keys added after it.
MARK = 'ELECTRON_RUN_AS_NODE:"1"'
ADDED = ",PATH:process.env.PATH,LD_LIBRARY_PATH:process.env.LD_LIBRARY_PATH"

# What a patched file must contain, and what the packaged tree is checked for.
FINGERPRINT = "LD_LIBRARY_PATH:process.env.LD_LIBRARY_PATH"

EXPECTED_SITES = 2


def sites(source: str) -> list[int]:
    """Where the two stripped-environment spawns end, in file order.

    A site is an `ELECTRON_RUN_AS_NODE:"1"` whose enclosing `env:{` does not
    spread the parent environment. `rfind` is enough to find that opening
    because these are single-line minified objects and `env:{` appears once per
    spawn; a nested object between them would need a real parser, and there is
    none in the shape this file has had across every version checked.
    """
    found = []
    for match in re.finditer(re.escape(MARK), source):
        opening = source.rfind("env:{", 0, match.start())
        if opening < 0:
            continue
        if "...process.env" in source[opening:match.start()]:
            continue
        found.append(match.end())
    return found


def patch(text: str) -> tuple[str, int]:
    """The file with both sites extended, and how many were changed."""
    out, last, changed = [], 0, 0
    for end in sites(text):
        out.append(text[last:end])
        out.append(ADDED)
        last = end
        changed += 1
    out.append(text[last:])
    return "".join(out), changed


def main() -> int:
    args = sys.argv[1:]
    check_only = False
    if args and args[0] == "--check":
        check_only = True
        args = args[1:]
    if len(args) != 1:
        print(__doc__.strip().splitlines()[0], file=sys.stderr)
        print("usage: patch-js-debug-env.py [--check] <server-tree>", file=sys.stderr)
        return 2

    target = Path(args[0]) / BOOTLOADER
    if not target.is_file():
        print(f"FAIL   no js-debug bootloader at {target}", file=sys.stderr)
        print("       the debug adapter is missing from this tree, so nothing", file=sys.stderr)
        print("       could debug on the device even if this succeeded", file=sys.stderr)
        return 1

    text = target.read_text(encoding="utf-8", errors="surrogateescape")
    already = text.count(FINGERPRINT)

    if check_only:
        if already == EXPECTED_SITES:
            print(f"  ok      js-debug helpers keep PATH and LD_LIBRARY_PATH ({already} sites)")
            return 0
        print(f"FAIL   js-debug bootloader carries {already} of {EXPECTED_SITES} "
              "environment fixes", file=sys.stderr)
        print("       a debug session will start, show its toolbar and never run "
              "the program", file=sys.stderr)
        return 1

    if already:
        # Idempotent on purpose: the work volume is reused between local runs,
        # and a second pass over an already-patched tree must not add the keys
        # twice or fail the build.
        if already != EXPECTED_SITES:
            print(f"FAIL   js-debug bootloader is half patched: {already} of "
                  f"{EXPECTED_SITES} sites", file=sys.stderr)
            return 1
        print(f"  already patched: {already} js-debug spawn sites keep the environment")
        return 0

    patched, changed = patch(text)
    if changed != EXPECTED_SITES:
        print(f"FAIL   expected {EXPECTED_SITES} stripped-environment spawns in "
              f"{BOOTLOADER}, found {changed}", file=sys.stderr)
        print("       js-debug has changed shape. Read how it spawns its watchdog "
              "and its", file=sys.stderr)
        print("       wait-for-debugger probe before touching this, and do not "
              "relax the count:", file=sys.stderr)
        print("       a missed site is a debug session that hangs with nothing "
              "on screen.", file=sys.stderr)
        return 1

    target.write_text(patched, encoding="utf-8", errors="surrogateescape")
    print(f"  patched {changed} js-debug spawn sites to keep PATH and LD_LIBRARY_PATH")
    return 0


if __name__ == "__main__":
    sys.exit(main())
