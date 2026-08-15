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


# The prose is checked the other way round. The rules above name shapes known to
# be bad, and a denylist has a tail: `git version 2.53.0` slipped the first
# version of the tool name rule, because "git" is followed by a word and the
# number carries no prefix. It was caught by running the tool against the text
# that shipped, which only works for shapes that already exist somewhere.
#
# In the walkthrough's own prose a digit is almost never legitimate -- claims
# live there, code samples do not -- so anything numeric has to be declared. A
# denylist fails silently on a shape nobody anticipated; this fails loudly on
# legitimate new text, and that failure is one line a reviewer can judge at a
# glance. The illustrations keep the denylist: their text is mock terminal
# output, where digits are the point.
PROSE_ALLOWED = (
    "Java 17",   # a pinned toolchain, not a version resolved at build time
    "python3",   # the command's name
)

# The illustrations need the same escape hatch, and not having one was an
# asymmetry rather than a decision. Prose can declare a number and carry on;
# a picture could not, so a rule refusing it left no sanctioned way through --
# only editing the rule, which is how a gate stops being trusted.
#
# It is not hypothetical. `http://127.0.0.1:3000` in a mock terminal is refused
# today, matching `127.0.0` as a version string, and that address is what this
# app's own dev-server preview shows. `make 4 targets` is refused too.
#
# Empty on purpose: the illustrations that ship carry only code samples no rule
# matches. An entry here is a claim that a number in a picture is deliberate,
# and it should be as reviewable as one in prose.
MEDIA_ALLOWED = ()


def welcome_dir():
    """The bundled welcome extension, whatever version it is at.

    Ordered by parsed version rather than by name, because lexically 1.10.0
    sorts below 1.9.0 and this would then check the older directory and pass.
    Only one ever ships, so this cannot bite today -- but the failure it would
    produce is a green gate reading the wrong file, which is the shape this
    script exists to prevent.
    """
    def version(path):
        tail = path.name.rsplit("-", 1)[-1]
        try:
            return tuple(int(part) for part in tail.split("."))
        except ValueError:
            return ()

    found = sorted(EXTENSIONS.glob("vscodroid.vscodroid-welcome-*"), key=version)
    return found[-1] if found else None


def texts(directory):
    """Every user-visible string, from the manifest and the illustrations.

    The pictures are read as plain text rather than parsed: the claims sat in
    <text> elements, and any XML shape that renders words is worth checking.
    """
    for where, text in prose(directory):
        yield where, text

    for svg in sorted(directory.glob("media/*.svg")):
        # Comments explain why a claim was removed and would match the rules
        # they document, which would make this script fail on its own reasoning.
        body = re.sub(r"<!--.*?-->", "", svg.read_text(), flags=re.S)
        for allowed in MEDIA_ALLOWED:
            body = body.replace(allowed, "")
        yield svg.name, body


def prose(directory):
    """Every line of title and description text, from the walkthrough and its steps.

    The walkthrough carries a title and a description of its own, and they are
    the first two lines a user reads -- they head the page the four steps sit
    under. This read only the steps, so those two fields were the one place on
    the screen where a version number or a "coming soon" was checked by nothing.
    """
    manifest = directory / "package.json"
    if not manifest.is_file():
        return
    data = json.loads(manifest.read_text())

    def lines(where, node):
        for field in ("title", "description"):
            for line in node.get(field, "").split("\n"):
                if line.strip():
                    yield where, line.strip()

    for walkthrough in data.get("contributes", {}).get("walkthroughs", []):
        yield from lines(
            f"{manifest.name} walkthrough '{walkthrough.get('id', '?')}'", walkthrough
        )
        for step in walkthrough.get("steps", []):
            yield from lines(f"{manifest.name} step '{step.get('id', '?')}'", step)


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
    # wants to write. Nothing else in this repository would catch that.
    for svg in sorted(directory.glob("media/*.svg")):
        try:
            ElementTree.parse(svg)
        except ElementTree.ParseError as e:
            print(f"  FAIL    {svg.name}: not well-formed XML ({e})")
            print("            the picture will not render; the step opens with a blank space")
            failed = True

    for where, line in prose(directory):
        stripped = line
        for allowed in PROSE_ALLOWED:
            stripped = stripped.replace(allowed, "")
        if any(c.isdigit() for c in stripped):
            print(f"  FAIL    {where}: {line[:90]!r}")
            print("            a number in walkthrough prose has to be declared in "
                  "PROSE_ALLOWED; if it names a bundled tool's version it should not be "
                  "there at all")
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
