#!/usr/bin/env python3
"""Teach the bundled Python extension that Android is a Linux.

    patch-python-platform.py <extension-dir>     rewrite in place
    patch-python-platform.py --check <dir>       fail if it was not rewritten

The extension carries its own platform detection and never consults VS Code's:

    function o(e=process.platform){
      return /^win/.test(e)?i.Windows
           : /^darwin/.test(e)?i.OSX
           : /^linux/.test(e)?i.Linux
           : i.Unknown}

Node here is Termux's build, which reports `android`, so the answer is
`OSType.Unknown` on every install. That is not cosmetic. Two consequences were
measured in the shipped bundle:

  * `getEnvironmentActivationShellCommands` opens with
    `if (this.platform.osType === OSType.Unknown) return;`
    so selecting a virtual environment never activates it in a terminal, which
    is the failure users report as "it cannot find my interpreter";
  * the shell table is `{Linux: bash, OSX: bash, Windows: commandPrompt,
    Unknown: other}`, so every command the extension composes is built for a
    shell it has decided it does not know, while the shell really is bash.

Answering `Linux` is not a lie of convenience. This userland is Termux's: POSIX
paths, a bash shell, `~/.config` for user settings. Every branch the extension
takes on `OSType.Linux` was read before this was written, and all seven are
right here: the settings path, the `isLinux` predicate, two shell tables, a
telemetry key, and the "install Python" offer. That last one would propose apt
or dnf, which this device has no use for; it is reachable only when no
interpreter is found, and one is always bundled.

Why patching the artefact is safe here, when patching minified output is
normally the thing this project refuses. The objection is that a pattern matched
against generated identifiers goes stale silently. That applies to output
rebuilt on every run. This input is not rebuilt: `download-extensions.sh` pins
the VSIX by sha256 and refuses any other bytes, so the identifiers are fixed
until someone deliberately bumps the pin, at which point the digest check fails
first and this one fails second. Both are loud.

The identifiers are still read out of the match rather than assumed, so a
reformat that keeps the shape is handled rather than mangled.
"""

import pathlib
import re
import sys

# The whole conditional, with the minified names captured rather than assumed.
PLATFORM_TEST = re.compile(
    r"/\^linux/\.test\((\w+)\)\?(\w+)\.Linux:\2\.Unknown"
)
PATCHED = re.compile(
    r"/\^linux\|\^android/\.test\((\w+)\)\?(\w+)\.Linux:\2\.Unknown"
)

# One file, named rather than searched for. The extension declares this as its
# `main`, and the extension host here is a Node worker thread, so this is the
# bundle that runs. Searching the tree instead would also find `dist/
# extension.browser.js`, which is never loaded and whose rewriting would be a
# claim nobody checks.
BUNDLE = "out/client/extension.js"


def bundle_of(directory: pathlib.Path) -> pathlib.Path:
    path = directory / BUNDLE
    if not path.is_file():
        sys.exit(f"FAIL {path} is not there; this is not an unpacked ms-python tree")
    return path


def patch(directory: pathlib.Path) -> int:
    path = bundle_of(directory)
    text = path.read_text(encoding="utf-8")

    already = len(PATCHED.findall(text))
    hits = len(PLATFORM_TEST.findall(text))

    if already and not hits:
        print(f"  ok     {path.name} already answers Linux for android")
        return 0
    if hits != 1:
        print(
            f"  ERROR: expected exactly one platform test in {path}, found {hits}.\n"
            "         The bundle changed shape, which means the pinned version moved.\n"
            "         Re-read the conditional and update the pattern rather than\n"
            "         loosening it; a rewrite that matches nothing would ship a build\n"
            "         where selecting a virtual environment silently does nothing.",
            file=sys.stderr,
        )
        return 1

    text, count = PLATFORM_TEST.subn(
        lambda m: f"/^linux|^android/.test({m.group(1)})?{m.group(2)}.Linux:{m.group(2)}.Unknown",
        text,
    )
    if count != 1:
        print(f"  ERROR: substituted {count} times, expected 1", file=sys.stderr)
        return 1

    path.write_text(text, encoding="utf-8")
    print(f"  ok     {path.name}: android now answers OSType.Linux")
    return 0


def check(directory: pathlib.Path) -> int:
    path = bundle_of(directory)
    text = path.read_text(encoding="utf-8")

    if PATCHED.search(text):
        print("  ok      the bundled Python extension answers Linux for android")
        return 0

    if PLATFORM_TEST.search(text):
        print(
            "FAIL the bundled Python extension still answers OSType.Unknown on this\n"
            "     platform, so selecting a virtual environment will not activate it\n"
            "     and every shell command it composes is built for a shell it has\n"
            "     decided it does not know.\n"
            "     Re-run scripts/download-extensions.sh, which applies the rewrite.",
            file=sys.stderr,
        )
        return 1

    print(
        "FAIL neither the original platform test nor the rewritten one is in\n"
        f"     {path}. The bundle changed shape, so this check is no longer\n"
        "     looking at anything and would pass a tree that has the defect.",
        file=sys.stderr,
    )
    return 1


def main() -> int:
    args = sys.argv[1:]
    if len(args) == 2 and args[0] == "--check":
        return check(pathlib.Path(args[1]))
    if len(args) == 1:
        return patch(pathlib.Path(args[0]))
    print(__doc__.strip().splitlines()[0], file=sys.stderr)
    print(
        "usage: patch-python-platform.py <extension-dir>\n"
        "       patch-python-platform.py --check <extension-dir>",
        file=sys.stderr,
    )
    return 2


if __name__ == "__main__":
    sys.exit(main())
