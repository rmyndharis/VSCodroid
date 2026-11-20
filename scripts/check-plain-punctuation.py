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

Two more places decode a spelling into the character before anyone reads it.
A page does: HTML and Markdown (which the docs site and GitHub render as
HTML) turn a named or numeric character reference into the character, and a
browser takes a number without its semicolon and remaps the windows-1252
numbers onto both dashes. SVG is read the same way, since a browser draws it
and decodes its references. Each reference is put through the standard
library's HTML decoder rather than matched against a list, so those count the
same as the obvious name. A Markdown code span is not told apart: that needs a
Markdown parser, and a miss costs the character on a published page. And a
string literal does: shipped Kotlin, Java, JavaScript and JSON (the strings of
the bundled extensions among them) decode a backslash-u escape, in JavaScript
the braced form too, into text a person is shown. Code under a `src/main`
directory, the JSON under `branding/` that the server build copies into the
tree it ships, and every line a patch adds to the editor's source are read for
that form; tests and scripts spell it on purpose, as a fixture, and show it to
nobody. A backslash that is itself escaped, as in `\\\\u` followed by the
digits, leaves a backslash and plain text, so only an odd run of backslashes
before the `u` counts.

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
* Inside a `.patch`, the context and removed lines from the first
  `diff --git` or `--- a/` line onward. Those are upstream source, and a
  context line has to match the file it is applied to byte for byte. The
  description above that point is ours, and so is every added `+` line below
  it: both are checked. An added line is read as the file it lands in, named
  by the `+++ b/` line, so a character reference added to a page is seen as
  one; and a hunk is followed by the counts in its `@@` line, so an added
  line that starts `++ ` is not taken for a file header. Below the first
  diff line the gate reads file headers and hunks and nothing else, so a
  line there that is neither is refused, and so is a file header it cannot
  take a path from: a description that happened to start a line with
  `--- a/` used to end the description for this gate, and what followed it
  was read by nobody.
* Anything git does not track, which is what keeps build output and
  downloaded toolchains out of the result.

The number of files examined is printed on success. A gate that says only
"ok" cannot be told apart from one whose file list came back empty, and this
repository has shipped that mistake before.
"""

import html
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

# The escape a shipped string literal decodes: the four-digit form Kotlin,
# Java, JavaScript and JSON share, and the braced form JavaScript adds.
#
# ODD_BACKSLASH starts a match only where the backslash before the `u` is not
# itself escaped: an even run of backslashes ahead of it decodes to backslashes
# and leaves the `u` and its digits as plain text.
ODD_BACKSLASH = r"(?<!\\)(?:\\\\)*\\u"
CODE_ESCAPED = {
    char: re.compile(
        rf"{ODD_BACKSLASH}(?:{ord(char):04x}|\{{0*{ord(char):x}\}})",
        re.IGNORECASE,
    )
    for char in BANNED
}

# A candidate character reference, the same shape the standard library's HTML
# decoder looks for. What it decodes to is the decoder's answer, not ours.
REFERENCE = re.compile(r"&(?:#[0-9]+;?|#[xX][0-9a-fA-F]+;?|[^\t\n\f <&#;]{1,32};?)")

SKIP_PREFIXES = ("licenses/",)

# Files named for text: one of these that does not decode is an offence, where any other undecodable
# file is a binary that carries no prose.
TEXT_SUFFIXES = frozenset((
    ".md", ".txt", ".html", ".css", ".js", ".json", ".xml", ".yml", ".yaml", ".py", ".sh", ".kt",
    ".kts", ".java", ".c", ".h", ".ts", ".patch", ".svg", ".gradle", ".properties", ".toml", ".tsv",
))

# How many offences to print before summarising. A first sweep of a stale
# tree produces hundreds, and a wall of them buries the count that says how
# much work is left.
MAX_SHOWN = 40


def tracked(paths):
    """Every tracked file, or every tracked file under the given paths."""
    cmd = ["git", "ls-files", "-z", "--"] + list(paths)
    out = subprocess.run(cmd, capture_output=True, check=True).stdout
    return [p for p in out.decode("utf-8", "surrogateescape").split("\0") if p]


# `@@ -a,b +c,d @@`: how many lines of the old and of the new file the hunk holds; a count that is left
# out means one.
HUNK = re.compile(r"^@@ -\d+(?:,(\d+))? \+\d+(?:,(\d+))? @@")


# The lines git writes between `diff --git` and the first hunk of a file, besides the two that name it,
# and the `diff -ruN a b` line GNU diff writes there.
EXTENDED_HEADER = (
    "diff ", "index ", "new file mode ", "deleted file mode ", "old mode ", "new mode ",
    "similarity index ", "dissimilarity index ", "rename from ", "rename to ", "copy from ", "copy to ",
    "Binary files ",
)
UNPLACED = "a line below the diff that is no file header and belongs to no hunk; nothing reads it"
UNREAD_HEADER = "a file header this gate cannot take a path from"
STRUCTURAL = (UNPLACED, UNREAD_HEADER)


def checkable_lines(path, text):
    """The (number, line, landing) triples this gate is allowed to judge, and the (number, why, line)
    triples of a patch it could not place. `landing` is None for a line judged as the file it is in. For
    a line a patch adds it is the path of the file that line lands in: the editor's build compiles it,
    or a page shows it, as that file and not as the `.patch`."""
    lines = enumerate(text.split("\n"), start=1)
    if not path.endswith(".patch"):
        return [(number, line, None) for number, line in lines], []
    judged, unplaced, in_diff, in_binary, landing = [], [], False, False, None
    old_left = new_left = 0
    for number, line in lines:
        if old_left or new_left:
            # Inside a hunk every line belongs to it and only its first character says which side: a
            # removed line `-- x` and an added line `++ y` read `--- x` and `+++ y` without being headers.
            if line.startswith("+"):
                judged.append((number, line, landing))
                new_left -= 1
            elif line.startswith("-"):
                old_left -= 1
            elif not line.startswith("\\"):  # "\ No newline at end of file" belongs to neither side
                old_left -= 1
                new_left -= 1
            continue
        hunk = HUNK.match(line)
        # A hunk belongs to a file. With no file named yet the line is text that looks like one, and
        # taking its counts would pass over the lines that follow it unread.
        if hunk and landing is not None:
            old_left = 1 if hunk.group(1) is None else int(hunk.group(1))
            new_left = 1 if hunk.group(2) is None else int(hunk.group(2))
            continue
        if line == "-- ":
            # The line `git format-patch` ends a patch with. What follows is text again.
            in_diff = in_binary = False
        elif line.startswith("diff --git ") or line.startswith("--- a/"):
            in_diff = True
        # The description above the diff is ours and is judged as it is.
        if not in_diff:
            judged.append((number, line, None))
            continue
        # Below it only file headers, hunks and the blocks of a binary file are read, so anything else
        # is said, not passed over. What is read as a header is text of the patch file all the same and
        # is judged as that, or a line that merely begins like one would carry anything past the gate.
        if line.startswith("diff "):
            in_binary = False
        elif line == "GIT binary patch":
            in_binary = True
        if line.startswith("+++ b/"):
            landing = line[len("+++ b/"):].split("\t")[0].rstrip()
        elif line == "+++ /dev/null":
            landing = path
        elif in_binary:
            pass
        elif line.startswith("+++ ") or (line.startswith("--- ") and not line.startswith(("--- a/", "--- /dev/null"))):
            unplaced.append((number, UNREAD_HEADER, line))
        elif line.startswith("+"):
            # A hunk whose counts ran out early: what it adds is still ours.
            judged.append((number, line, landing or path))
            continue
        elif line and not line.startswith(EXTENDED_HEADER + ("--- ", "\\ ")):
            unplaced.append((number, UNPLACED, line))
        judged.append((number, line, None))
    return judged, unplaced


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


def rendered_as_html(path):
    """Whether a reader sees this file as a page that decodes references."""
    return path.endswith((".html", ".htm", ".md", ".svg"))


def decodes_code_escapes(path):
    """Whether this is shipped code or JSON whose string literals decode a
    backslash-u escape: under a `src/main` directory, in any module, or under
    `branding/`, which the server build copies into the tree it ships."""
    if not path.endswith((".kt", ".java", ".js", ".mjs", ".cjs", ".ts", ".json")):
        return False
    parts = pathlib.PurePosixPath(path).parts
    if parts[:1] == ("branding",):
        return True
    return any(parts[i:i + 2] == ("src", "main") for i in range(len(parts) - 1))


def line_offences(path, number, line, added=False):
    """The offences on one line this gate is allowed to judge. `added` marks a
    line a patch adds, which the editor's build compiles like shipped code."""
    found = []
    for column, char in enumerate(line, start=1):
        if char in BANNED:
            found.append((number, column, BANNED[char], line.strip()))
    # Only where something expands them. Elsewhere an escape is six characters
    # a reader sees as six characters, and this gate is about what reaches a
    # user rather than about how a file spells things.
    escaped = []
    if compiled_by_aapt2(path):
        escaped.append((ESCAPED, "escaped; the resource compiler expands it"))
    if added or decodes_code_escapes(path):
        escaped.append((CODE_ESCAPED, "escaped; the string literal decodes it"))
    for table, why in escaped:
        for char, pattern in table.items():
            for hit in pattern.finditer(line):
                found.append((number, hit.start() + 1, f"{BANNED[char]}, {why}", line.strip()))
    if rendered_as_html(path):
        for hit in REFERENCE.finditer(line):
            decoded = html.unescape(hit.group(0))
            for char in BANNED:
                if char in decoded:
                    found.append((
                        number, hit.start() + 1,
                        f"{BANNED[char]}, as a character reference; the page decodes it",
                        line.strip(),
                    ))
    return found


def offences(path):
    try:
        text = pathlib.Path(path).read_text(encoding="utf-8")
    except UnicodeDecodeError:
        # A binary file carries no prose. A file named for text that is not UTF-8 does, in an
        # encoding this gate cannot read, and passing it would count it as examined: a dash
        # written there reaches a reader and nothing here sees it.
        if pathlib.Path(path).suffix.lower() in TEXT_SUFFIXES:
            return [(1, 1, "not UTF-8, so this gate cannot read it", "")]
        return []
    except OSError:
        # A file git tracks but the checkout does not hold is a different problem
        # that a different gate should report.
        return []
    judged, unplaced = checkable_lines(path, text)
    found = [(number, 1, why, line.strip()) for number, why, line in unplaced]
    for number, line, landing in judged:
        found.extend(line_offences(path if landing is None else landing, number, line, landing is not None))
    # The passes walk the same line, so a file holding one of each would
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

    # And for the forms read since: a string literal's two escapes, and a
    # page's references by name, by number, by number without its semicolon
    # and by the windows-1252 number a browser remaps. Each must be seen once.
    names = {0x2014: "mdash", 0x2013: "ndash", 0x2015: "horbar"}
    remapped = {0x2014: 151, 0x2013: 150}
    for code in (0x2014, 0x2013, 0x2015):
        for path, forms in (
            ("src/main/x.js", (rf"\u{code:04X}", rf"\u{{0{code:x}}}")),
            ("x.html", (f"&{names[code]};", f"&#{code};", f"&#X{code:x};", f"&#{code} ")
             + ((f"&#{remapped[code]};",) if code in remapped else ())),
        ):
            for form in forms:
                if len(line_offences(path, 1, form)) != 1:
                    print(f"  FAIL   {path} cannot see U+{code:04X} written as {form.strip()}")
                    return 2

    paths = argv[1:] or ["."]
    files = [f for f in tracked(paths) if not f.startswith(SKIP_PREFIXES)]
    if not files:
        print("  FAIL   no tracked files to examine; the path argument matches nothing")
        return 1

    total = unread = 0
    for path in sorted(files):
        for number, column, name, line in offences(path):
            total += 1
            unread += name in STRUCTURAL
            if total <= MAX_SHOWN:
                print(f"  FAIL   {path}:{number}:{column}  {name}")
                print(f"         {line[:120]}")

    if total:
        if total > MAX_SHOWN:
            print(f"  ... and {total - MAX_SHOWN} more")
        print()
        if total > unread:
            print(f"  {total - unread} banned dash characters in {len(files)} tracked files.")
            print("  Use a comma, a colon, a semicolon, a full stop, or parentheses.")
            print("  The ASCII hyphen is not the subject here and must not be changed.")
            print("  Reproducing text that already exists is the one exception: such")
            print("  text belongs under licenses/, or below the diff line of a patch.")
        if unread:
            print(f"  {unread} lines of a patch that this gate cannot place. A description ends at the")
            print("  first line that starts `diff --git ` or `--- a/`; below it every line is a file")
            print("  header or part of a hunk, and the `+++ b/` line names the file a hunk lands in.")
        return 1

    print(f"  ok     no banned dash characters in {len(files)} tracked files")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
