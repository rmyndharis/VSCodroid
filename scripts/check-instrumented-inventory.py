#!/usr/bin/env python3
"""Hold androidTest/README.md to the suite it describes.

    check-instrumented-inventory.py

Nothing runs the instrumented suite. No runner this project has measured can
(that README says why, with the error strings), so what CI does is compile it,
and the only account of what the suite covers is prose in that file. A document
that is the sole record of coverage, kept by hand, next to a directory nobody
executes, is the exact shape that rots without anyone noticing.

It did. The stated total read 22, then 37, against a suite that is neither, and
three of its classes had no row in the table at all: two keyboard suites and the
one covering the execution trampoline, which is the single claim no JVM test can
settle. A reader working out whether to spend a minute on a device was being told
the wrong size and the wrong contents.

Two rules:

  * the stated total matches the sources, counted the way the file itself says
    it counts: `grep -cE '^\\s*@Test'` over the directory;
  * every class carrying a `@Test` has a row in the "what is here" table, and
    every row names a class that exists.

The second is the one that actually decays. A count is one number and someone
eventually re-runs the grep; a table gains rows only when whoever adds a suite
remembers the table exists, and loses them only when someone notices a deleted
class still listed. Neither happened.

A file with no `@Test` in it is not a test class and is not expected in the
table: `util/ServerReadyHelper.kt` is shared setup.
"""

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
SUITE = ROOT / "android/app/src/androidTest/kotlin"
README = ROOT / "android/app/src/androidTest/README.md"

TEST = re.compile(r"^\s*@Test", re.M)
# "**At HEAD there are 50 tests across eleven classes.**"
STATED = re.compile(r"there are (\S+) tests across (\S+) classes", re.I)
# A table row naming a class: `| `ServerHealthTest` | ... |`. The runner tables
# higher up the file name runner images in the same cell shape, so the row is
# recognised by its subject existing as a class rather than by position: an
# entry that names no test class is reported, not silently skipped, which is
# what would happen if this filtered on a heading.
ROW = re.compile(r"^\|\s*`(\w+)`\s*\|", re.M)

# Prose here writes small numbers as words and large ones as digits, and both
# spellings appear in the sentence this parses. Refusing one of them would push
# the file into an idiom it does not use anywhere else.
WORDS = {w: i for i, w in enumerate(
    "zero one two three four five six seven eight nine ten eleven twelve "
    "thirteen fourteen fifteen sixteen seventeen eighteen nineteen twenty".split())}


def number(token):
    """`"50"` or `"eleven"` as an int, or None for anything else."""
    token = token.strip(".*_ ").lower()
    return int(token) if token.isdigit() else WORDS.get(token)


def main() -> int:
    if not SUITE.is_dir() or not README.is_file():
        print(f"::error::{SUITE} or {README} is missing; this check would "
              f"otherwise compare nothing")
        return 1

    counts = {}
    for path in sorted(SUITE.rglob("*.kt")):
        found = len(TEST.findall(path.read_text(encoding="utf-8")))
        if found:
            counts[path.stem] = found

    # The left-hand side of both rules. An empty one would make each of them
    # vacuously true, and "all classes documented" prints the same whether the
    # table is complete or the glob stopped matching.
    if not counts:
        print(f"::error::no @Test found under {SUITE}; either the suite is gone "
              f"or the pattern this file and the README both count with has "
              f"stopped matching")
        return 1

    text = README.read_text(encoding="utf-8")
    failed = False

    stated = STATED.search(text)
    if not stated:
        print(f"::error::{README.name} no longer states a total in the form "
              f"'there are N tests across M classes'; the sentence this check "
              f"reads was reworded, so nothing holds the figure to the suite")
        return 1

    said_tests, said_classes = number(stated.group(1)), number(stated.group(2))
    real_tests, real_classes = sum(counts.values()), len(counts)
    if (said_tests, said_classes) != (real_tests, real_classes):
        print(f"::error file={README.relative_to(ROOT)}::states "
              f"{stated.group(1)} tests across {stated.group(2)} classes; the "
              f"sources hold {real_tests} across {real_classes}")
        failed = True
    else:
        print(f"  ok     the stated total is the suite's: {real_tests} tests "
              f"across {real_classes} classes")

    listed = set(ROW.findall(text))
    undocumented = sorted(set(counts) - listed)
    if undocumented:
        print(f"::error file={README.relative_to(ROOT)}::{len(undocumented)} "
              f"test class(es) have no row in the table, so the only account of "
              f"what this suite covers omits them:")
        for name in undocumented:
            print(f"::error::  {name} ({counts[name]} tests)")
        failed = True

    # Only rows whose subject looks like a test class of this suite: the file
    # legitimately names other classes in prose rows, and the runner tables use
    # the same cell shape. A row for a class that has been deleted is the
    # failure worth catching here.
    ghosts = sorted(n for n in listed - set(counts)
                    if n.endswith("Test") or n.endswith("InstrumentedTest"))
    if ghosts:
        print(f"::error file={README.relative_to(ROOT)}::{len(ghosts)} row(s) "
              f"name a class this suite no longer has: {', '.join(ghosts)}")
        failed = True

    if not undocumented and not ghosts:
        print(f"  ok     all {real_classes} test classes have a row, and no row "
              f"names one that is gone")

    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
