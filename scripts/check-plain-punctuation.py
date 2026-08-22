#!/usr/bin/env python3
"""Refuse a tree that writes a dash this project does not use.

    check-plain-punctuation.py [path ...]

Three Unicode characters are banned in favour of a comma, a colon, a
semicolon, a full stop, or parentheses: U+2014 EM DASH, U+2013 EN DASH and
U+2015 HORIZONTAL BAR.

The ASCII hyphen is untouched and must never be "corrected". Hyphenated
words, ranges, negative numbers, `--offline`, and a SQL comment all keep it.
This gate looks for the three codepoints above and for nothing else.

Every one of the three is named here by its codepoint rather than written
out. A checker that spells its own subject is a file that fails itself, and
the local editing hook refuses to write it at all.

The second spelling, in the XML the resource compiler reads
-----------------------------------------------------------
Comparing codepoints answers "does this file hold the character", which is
the same question as "will a user see it" everywhere except one place. An
Android resource can hold any of the three in a form that is plain ASCII on
disk and the character itself by the time it is drawn: the `\\uXXXX` escape
aapt2 processes inside a string value, and an XML character reference in
either notation. One of those had been sitting in the first-run picker's
subtitle, on screen for every user who installs the app, while this gate
reported a clean tree because the bytes really were clean.

So every XML aapt2 compiles, meaning anything under a `res/` directory plus
the manifests, is read for those two forms as well. A named entity is not
among them on purpose: XML predefines five and `&mdash;` is not one, so a
resource carrying it fails the build out loud rather than reaching a screen,
which is the opposite of the failure this section exists for.

Why a gate rather than a habit
------------------------------
857 of them accumulated across 118 files before anyone counted, in prose, in
code comments and in assertion messages. Each arrived one edit at a time
from an editor that substitutes them, which is exactly the shape a
convention cannot hold on its own. The sweep that removed them is worth
nothing without something that keeps the number at zero.

What is deliberately NOT checked, and why
-----------------------------------------
* `licenses/` holds verbatim third-party licence text. It is reproduced
  rather than written, and editing it would misrepresent the licence.
* Inside a `.patch`, everything from the first `diff --git` or `--- a/` line
  onward. Those lines are upstream source plus our edits to it, and a
  context line has to match the file it is applied to byte for byte. The
  description above that point is ours, and is checked.
* Anything git does not track, which is what keeps build output and
  downloaded toolchains out of the result.

The number of files examined is printed on success. A gate that says only
"ok" cannot be told apart from one whose file list came back empty, and this
repository has shipped that mistake before.
"""

import pathlib
import re
import subprocess
import sys

BANNED = {
    chr(0x2014): "U+2014 EM DASH",
    chr(0x2013): "U+2013 EN DASH",
    chr(0x2015): "U+2015 HORIZONTAL BAR",
}

# The same three characters in the spellings an Android resource can hold
# without any of them appearing in the file as itself: the escape aapt2
# expands inside a string value, and an XML character reference in hex or in
# decimal, with the leading zeros that notation allows.
#
# Built from the codepoints rather than written out, for the reason the module
# docstring gives. Case-insensitive because the hex digits and the `u` may be
# written either way and both compile to the same character.
ESCAPED = {
    char: re.compile(
        rf"\\u{ord(char):04x}|&#x0*{ord(char):04x};|&#0*{ord(char)};",
        re.IGNORECASE,
    )
    for char in BANNED
}

SKIP_PREFIXES = ("licenses/",)

# How many offences to print before summarising. A first sweep of a stale
# tree produces hundreds, and a wall of them buries the count that says how
# much work is left.
MAX_SHOWN = 40


def tracked(paths):
    """Every tracked file, or every tracked file under the given paths."""
    cmd = ["git", "ls-files", "-z", "--"] + list(paths)
    out = subprocess.run(cmd, capture_output=True, check=True).stdout
    return [p for p in out.decode("utf-8", "surrogateescape").split("\0") if p]


def checkable_lines(path, text):
    """The (number, line) pairs this gate is allowed to judge."""
    lines = text.split("\n")
    if path.endswith(".patch"):
        for i, line in enumerate(lines):
            if line.startswith("diff --git ") or line.startswith("--- a/"):
                lines = lines[:i]
                break
    return enumerate(lines, start=1)


def compiled_by_aapt2(path):
    """Whether the resource compiler reads this file and expands its escapes.

    Everything under a `res/` directory, in any module, plus the manifests: a
    literal `android:label` is drawn in the launcher exactly as an escape in
    `strings.xml` is drawn on screen. Path shape rather than a list of files,
    so a resource directory added later is covered by existing.

    A directory component rather than a substring, so `res/values/strings.xml`
    is recognised when the path has no leading directory at all, which is what
    `git ls-files` prints when this runs from inside a module.
    """
    if not path.endswith(".xml"):
        return False
    return "res" in pathlib.PurePosixPath(path).parts[:-1] or path.endswith("AndroidManifest.xml")


def offences(path):
    try:
        text = pathlib.Path(path).read_text(encoding="utf-8")
    except (UnicodeDecodeError, OSError):
        # Not text this gate can read. A binary file carries no prose, and a
        # file git tracks but the checkout does not hold is a different
        # problem that a different gate should report.
        return []
    found = []
    # Only where a compiler expands them. Elsewhere `\\u2014` is six characters
    # a reader sees as six characters, and this gate is about what reaches a
    # user rather than about how a file spells things.
    escaped = ESCAPED if compiled_by_aapt2(path) else {}
    for number, line in checkable_lines(path, text):
        for column, char in enumerate(line, start=1):
            if char in BANNED:
                found.append((number, column, BANNED[char], line.strip()))
        for char, pattern in escaped.items():
            for hit in pattern.finditer(line):
                found.append((
                    number, hit.start() + 1,
                    f"{BANNED[char]}, escaped; the resource compiler expands it",
                    line.strip(),
                ))
    # Both passes walk the same line, so a file holding one of each would
    # otherwise report the second line before the first column of the first.
    found.sort(key=lambda offence: offence[:2])
    return found


def main(argv) -> int:
    # The detector proves itself before it is trusted. Without this, a typo in
    # the table above reports a clean tree, which is the most comfortable
    # wrong answer a checker can give.
    probe = "a" + chr(0x2014) + "b" + chr(0x2013) + "c" + chr(0x2015) + "d"
    if sum(probe.count(c) for c in BANNED) != 3:
        print("  FAIL   the character table is wrong; this gate cannot see its own subject")
        return 2

    # The same proof for the escaped spelling, and it earns its place twice
    # over: a table that cannot see a form reports exactly what a clean tree
    # reports, and this form was invisible here for as long as the gate has
    # existed. The three codepoints are written again rather than read back
    # from the table, so a wrong number in one place is a disagreement rather
    # than a shared assumption.
    for code in (0x2014, 0x2013, 0x2015):
        forms = (rf"\u{code:04x}", rf"&#x{code:04x};", rf"&#{code};")
        seen = sum(len(p.findall(form)) for form in forms for p in ESCAPED.values())
        if seen != len(forms):
            print(f"  FAIL   the escape table cannot see U+{code:04X} written as an escape")
            return 2

    paths = argv[1:] or ["."]
    files = [f for f in tracked(paths) if not f.startswith(SKIP_PREFIXES)]
    if not files:
        print("  FAIL   no tracked files to examine; the path argument matches nothing")
        return 1

    total = 0
    for path in sorted(files):
        for number, column, name, line in offences(path):
            total += 1
            if total <= MAX_SHOWN:
                print(f"  FAIL   {path}:{number}:{column}  {name}")
                print(f"         {line[:120]}")

    if total:
        if total > MAX_SHOWN:
            print(f"  ... and {total - MAX_SHOWN} more")
        print()
        print(f"  {total} banned dash characters in {len(files)} tracked files.")
        print("  Use a comma, a colon, a semicolon, a full stop, or parentheses.")
        print("  The ASCII hyphen is not the subject here and must not be changed.")
        print("  Reproducing text that already exists is the one exception: such")
        print("  text belongs under licenses/, or below the diff line of a patch.")
        return 1

    print(f"  ok     no banned dash characters in {len(files)} tracked files")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
