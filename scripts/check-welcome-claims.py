#!/usr/bin/env python3
"""Refuse a welcome screen that states things which go stale on their own.

    check-welcome-claims.py

The Get Started walkthrough is committed source. The runtimes it described are
not: Node, Python and Git are resolved from the Termux index when the app is
built, so a version written into the manifest is correct only until the next
rebuild. It stayed wrong for two releases -- the screen said Node.js 20 while
24.18.0 shipped, Python 3.12 while 3.14 shipped, and the illustration beside it
printed a mock `node --version` giving v20.18.1.

Nothing reported it, because a hardcoded string cannot disagree with itself.

Two rules, both mechanical:

  * No version number beside a bundled tool's name. The screen's job is to say
    the tool is there and ready; a user who needs the number runs `node -v`,
    which the very next step of the walkthrough tells them to do.

  * No "coming soon". Go, Java 17 and Ruby are installable today, and the screen
    said the opposite -- which is worse than a stale number, because it sends a
    user away from a toolchain already sitting on their device. Rust and C/C++
    were promised there too, and neither has a module, a pack or a registry
    entry.

This checks the manifest and the illustrations together. The pictures carried
the same claims as the text and were missed when the text was first questioned.
"""

import json
import pathlib
import re
import sys
from xml.etree import ElementTree

ROOT = pathlib.Path(__file__).resolve().parent.parent
EXTENSIONS = ROOT / "android/app/src/main/assets/extensions"

# Tier-1 tools, all resolved from the Termux index at build time. Naming a
# version for any of them is the defect; the toolchains are excluded on purpose,
# because Java 17 is a pinned choice rather than whatever the index happens to
# carry, and saying which Java is useful.
BUNDLED = r"(?:Node\.js|Node|npm|Python|Git|make)"

FORBIDDEN = [
    (re.compile(rf"\b{BUNDLED}\s+v?\d+(?:\.\d+)*", re.I),
     "names a bundled tool with a version; it comes from the Termux index at "
     "build time and will not follow"),
    # Both forms, because they are not the same miss. `v20.18.1` was caught by
    # the leading v; `git version 2.53.0` on the line below it was not -- "git"
    # is followed by a word, and the number carries no prefix.
    (re.compile(r"\bv?\d+\.\d+\.\d+\b"),
     "prints a version string that goes stale on the next rebuild"),
    (re.compile(r"\bcoming soon\b", re.I),
     "promises something as unavailable; Go, Java 17 and Ruby ship today, and "
     "nothing else is planned"),
]


def welcome_dir():
    """The bundled welcome extension, whatever version it is at."""
    found = sorted(EXTENSIONS.glob("vscodroid.vscodroid-welcome-*"))
    return found[-1] if found else None


def texts(directory):
    """Every user-visible string, from the manifest and the illustrations.

    The pictures are read as plain text rather than parsed: the claims sat in
    <text> elements, and any XML shape that renders words is worth checking.
    """
    manifest = directory / "package.json"
    if manifest.is_file():
        data = json.loads(manifest.read_text())
        for walkthrough in data.get("contributes", {}).get("walkthroughs", []):
            for step in walkthrough.get("steps", []):
                yield f"{manifest.name} step '{step.get('id', '?')}'", step.get("title", "")
                yield f"{manifest.name} step '{step.get('id', '?')}'", step.get("description", "")

    for svg in sorted(directory.glob("media/*.svg")):
        # Comments explain why a claim was removed and would match the rules
        # they document, which would make this script fail on its own reasoning.
        yield svg.name, re.sub(r"<!--.*?-->", "", svg.read_text(), flags=re.S)


def main():
    directory = welcome_dir()
    if directory is None:
        print(f"  FAIL    no vscodroid-welcome-* under {EXTENSIONS}")
        return 1

    failed = False

    # The illustrations are XML, and an unrenderable one fails silently: the
    # walkthrough step still opens, with a blank space where the picture was.
    # Worth checking here because this script asks people to edit these files,
    # and the obvious way to explain a removal is a comment -- which cannot
    # contain two hyphens in a row, exactly what a comment about `--version`
    # wants to write. That was caught in review rather than by anything.
    for svg in sorted(directory.glob("media/*.svg")):
        try:
            ElementTree.parse(svg)
        except ElementTree.ParseError as e:
            print(f"  FAIL    {svg.name}: not well-formed XML ({e})")
            print("            the picture will not render; the step opens with a blank space")
            failed = True

    for where, text in texts(directory):
        for pattern, why in FORBIDDEN:
            for hit in pattern.findall(text):
                print(f"  FAIL    {where}: {hit.strip()!r}")
                print(f"            {why}")
                failed = True

    if failed:
        print(f"         Checked {directory.name}.")
        print("         Say the tool is ready without naming its version, and say "
              "what a user can install today rather than what is coming.")
        return 1

    print(f"  ok      {directory.name} states nothing that goes stale on its own")
    return 0


if __name__ == "__main__":
    sys.exit(main())
