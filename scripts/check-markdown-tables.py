#!/usr/bin/env python3
"""Refuse a Markdown table that GitHub would not draw as it is written.

    check-markdown-tables.py
    check-markdown-tables.py --self-test

GitHub reads a table the GFM way: a header row, a separator row of dashes with
as many cells, then rows until the first blank line. A row with more cells than
the header loses the ones past the last column, and a row after a blank line is
no longer in any table: it is drawn as a paragraph of pipes. Neither is an error
anywhere, and both happened at once in docs/07-TESTING_STRATEGY.md, where one
row had been appended to the end of the row before it and a blank line after
them turned the twenty rows below into plain text.

Two rules, over every Markdown file git tracks, fenced code blocks skipped:

  * every row of a table has as many cells as its header;
  * a line that opens with a pipe belongs to a table, so one that follows a
    blank line, or has no header and separator above it, is refused.

Cells are split as GFM splits them: on every pipe a backslash does not escape.
A code span does not protect a pipe inside a table, so a pipe between backticks
splits the cell on GitHub too, and here.
"""

import pathlib
import re
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent

DELIMITER_CELL = re.compile(r"\s*:?-+:?\s*")
FENCE = re.compile(r"^ {0,3}(`{3,}|~{3,})")


def cells(line):
    """The cells of a table line, split on unescaped pipes, outer pipes dropped."""
    text = line.strip()
    parts, current, ended_on_pipe, i = [], "", False, 0
    while i < len(text):
        char = text[i]
        ended_on_pipe = False
        if char == "\\" and i + 1 < len(text):
            current += text[i:i + 2]
            i += 2
            continue
        if char == "|":
            parts.append(current)
            current = ""
            ended_on_pipe = True
        else:
            current += char
        i += 1
    parts.append(current)
    if text.startswith("|"):
        parts = parts[1:]
    if ended_on_pipe and parts:
        parts = parts[:-1]
    return parts


def has_pipe(line):
    """Whether the line holds a pipe that is not escaped."""
    return len(cells("x" + line.strip() + "x")) > 1


def is_delimiter(line):
    parts = cells(line)
    return has_pipe(line) and bool(parts) and all(DELIMITER_CELL.fullmatch(part) for part in parts)


def problems(name, text):
    """`name:line: reason` for every table line GitHub would not draw as written."""
    found = []
    lines = text.split("\n")
    fence = None
    i = 0
    while i < len(lines):
        line = lines[i]
        opened = FENCE.match(line)
        if fence:
            if opened and opened.group(1)[0] == fence[0] and len(opened.group(1)) >= len(fence):
                fence = None
            i += 1
            continue
        if opened:
            fence = opened.group(1)
            i += 1
            continue
        if has_pipe(line) and i + 1 < len(lines) and is_delimiter(lines[i + 1]):
            width = len(cells(line))
            if len(cells(lines[i + 1])) != width:
                found.append(f"{name}:{i + 2}: the separator has {len(cells(lines[i + 1]))} cells and "
                             f"the header {width}, so GitHub draws no table here")
            i += 2
            while i < len(lines) and lines[i].strip() and has_pipe(lines[i]) and not FENCE.match(lines[i]):
                count = len(cells(lines[i]))
                if count != width:
                    found.append(f"{name}:{i + 1}: {count} cells in a table of {width}; GitHub drops "
                                 "the cells past the last column and pads a short row")
                i += 1
            continue
        if line.lstrip().startswith("|"):
            found.append(f"{name}:{i + 1}: a table row with no header and separator above it, which "
                         "GitHub draws as text; a blank line ends a table")
        i += 1
    return found


def tracked_markdown():
    out = subprocess.run(["git", "-C", str(ROOT), "ls-files", "-z", "--", "*.md"],
                         capture_output=True, check=True).stdout
    return [p for p in out.decode("utf-8", "surrogateescape").split("\0") if p]


def self_test():
    table = "| A | B | C |\n|---|:-:|--:|\n| 1 | 2 | 3 |\n"
    refused = {
        "a row run into the one before it": table + "| 4 | 5 | 6 || 7 | 8 | 9 |\n",
        "a short row": table + "| 4 | 5 |\n",
        "a blank line inside a table": table + "\n| 4 | 5 | 6 |\n",
        "a row with no table above it": "Text.\n\n| 4 | 5 | 6 |\n",
        "a separator narrower than its header": "| A | B | C |\n|---|---|\n| 1 | 2 | 3 |\n",
        "a pipe inside a code span": table + "| `a | b` | 2 | 3 |\n",
    }
    admitted = {
        "a table as written": table,
        "an escaped pipe": table + "| a \\| b | 2 | 3 |\n",
        "a table without outer pipes": "A | B\n--- | ---\n1 | 2\n",
        "pipes in a fenced block": "```\n| a |\n\n| b | c |\n```\n",
        "an empty last cell": table + "| 4 | 5 | |\n",
        "two tables, each with its header": table + "\n" + table,
    }
    for label, text in refused.items():
        if not problems("fixture.md", text):
            print(f"  FAIL   self-test: {label} was admitted")
            return 1
    for label, text in admitted.items():
        found = problems("fixture.md", text)
        if found:
            print(f"  FAIL   self-test: {label} was refused: {found[0]}")
            return 1
    print(f"  ok     self-test: {len(refused)} broken tables refused, {len(admitted)} drawn ones admitted")
    return 0


def main():
    names = tracked_markdown()
    if not names:
        print(f"  FAIL   git lists no Markdown file under {ROOT}; this is a broken checkout, not a clean tree")
        return 1
    found, tables = [], 0
    for name in names:
        path = ROOT / name
        if not path.is_file():
            continue
        text = path.read_text(encoding="utf-8")
        found += problems(name, text)
        tables += sum(1 for line in text.split("\n") if is_delimiter(line))
    if found:
        for problem in found:
            print(f"  FAIL   {problem}")
        print(f"  FAILED {len(found)} table line(s) GitHub would not draw as written")
        return 1
    print(f"  ok     {tables} tables in {len(names)} tracked Markdown files are drawn as written")
    return 0


if __name__ == "__main__":
    sys.exit(self_test() if sys.argv[1:] == ["--self-test"] else main())
