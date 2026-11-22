#!/usr/bin/env python3
"""Refuse a Markdown table that GitHub would not draw as it is written.

    check-markdown-tables.py
    check-markdown-tables.py --self-test

GitHub reads a table the GFM way: a header row, a separator row of dashes with
as many cells, then rows until a blank line or a line that begins another block
(a list item, a heading, a quote, a fence, a rule, HTML, or a line indented as
code). A row with more cells than the header loses the ones past the last column,
a line of plain text right after a table is one more row of it, with or without
a pipe, and a row after a blank line is no longer in any table: it is drawn as a
paragraph of pipes. None of it is an error anywhere, and the first and last
happened at once in docs/07-TESTING_STRATEGY.md, where one row had been appended
to the end of the row before it and a blank line after them turned the twenty
rows below into plain text.

Three rules, over every Markdown file git tracks, code blocks skipped:

  * every row of a table has as many cells as its header;
  * a table ends at a blank line or at a line that begins another block, so a
    line of plain text right after it is refused;
  * a line that opens with a pipe belongs to a table, so one that follows a
    blank line, or has no header and separator above it, is refused.

Cells are split as GFM splits them: on every pipe a backslash does not escape.
A code span does not protect a pipe inside a table, so a pipe between backticks
splits the cell on GitHub too, and here.

Code blocks are a fence, which only a fence of the same character and at least
its length with nothing after it closes, and an indented block. A list item
moves both by its content column, so a fence indented four spaces under `1.` is
a fence and a table indented four spaces under `-` is a table. The lists are
followed only that far: a line that leaves a list is read as leaving it at the
first blank line or block that starts left of the item's text. A quote is read
as the text inside its markers, so a table in a quote is held to the same rules;
a line the quote takes in without a marker is not followed into it.
"""

import pathlib
import re
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent

DELIMITER_CELL = re.compile(r"\s*:?-+:?\s*")
FENCE = re.compile(r"^(`{3,}|~{3,})(.*)$")
LIST_ITEM = re.compile(r"^( *)([-+*]|\d{1,9}[.)])( +|$)")
QUOTE = re.compile(r"^ {0,3}> ?")
# What GitHub begins another block with, which ends a table instead of adding a row to it,
# as measured by rendering each after a table. Read after the list item's indent is taken off.
BLOCK_START = re.compile(r"^ {0,3}(#{1,6}(\s|$)|>|`{3}|~{3}|[-+*](\s|$)|\d{1,9}[.)](\s|$)|<[A-Za-z/!?]|[-*_]( *[-*_]){2,} *$)")


def indent(line):
    return len(line) - len(line.lstrip(" "))


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


def problems(name, text, first=1):
    """`name:line: reason` for every table line GitHub would not draw as written; line `first` is text's first."""
    found = []
    lines = text.expandtabs(4).split("\n")
    fence = None  # the opening fence's character, its length and the list indent it opened at
    item = 0  # the content column of the list item a line can belong to, 0 outside a list
    prose = False  # the line before is paragraph text, which an indented line continues instead of starting code
    after_blank = True
    i = 0
    while i < len(lines):
        line = lines[i]
        lead = indent(line)
        if fence:
            closing = FENCE.match(line.lstrip(" "))
            if closing and closing.group(1)[0] == fence[0] and len(closing.group(1)) >= fence[1] \
                    and not closing.group(2).strip() and lead < fence[2] + 4:
                fence = None
            i += 1
            continue
        if not line.strip():
            prose, after_blank = False, True
            i += 1
            continue
        if lead >= item + 4 and not prose:
            # An indented code block, at the list item's content column or outside a list.
            after_blank = False
            i += 1
            continue
        if lead < item and not LIST_ITEM.match(line) and (after_blank or BLOCK_START.match(line)):
            item = 0
        here = line[min(lead, item):]
        listed = LIST_ITEM.match(line)
        if listed:
            item = len(listed.group(0)) if listed.group(3) else len(listed.group(0)) + 1
        opened = FENCE.match(here.lstrip(" ")) if indent(here) < 4 else None
        if opened and not (opened.group(1)[0] == "`" and "`" in opened.group(2)):
            fence = (opened.group(1)[0], len(opened.group(1)), min(lead, item))
            prose, after_blank = False, False
            i += 1
            continue
        if QUOTE.match(here):
            start = i
            while i < len(lines) and QUOTE.match(lines[i][min(indent(lines[i]), item):]):
                i += 1
            inner = "\n".join(QUOTE.sub("", quoted[min(indent(quoted), item):], count=1) for quoted in lines[start:i])
            found += problems(name, inner, first + start)
            prose, after_blank = False, False
            continue
        if has_pipe(line) and i + 1 < len(lines) and is_delimiter(lines[i + 1]):
            width = len(cells(line))
            if len(cells(lines[i + 1])) != width:
                found.append(f"{name}:{first + i + 1}: the separator has {len(cells(lines[i + 1]))} cells and "
                             f"the header {width}, so GitHub draws no table here")
            i += 2
            while i < len(lines) and lines[i].strip() and indent(lines[i]) < item + 4 \
                    and not BLOCK_START.match(lines[i][min(indent(lines[i]), item):]):
                if not has_pipe(lines[i]):
                    found.append(f"{name}:{first + i}: a line of text right after a table, which GitHub draws "
                                 "as one more row of it; a blank line ends a table")
                elif len(cells(lines[i])) != width:
                    found.append(f"{name}:{first + i}: {len(cells(lines[i]))} cells in a table of {width}; GitHub "
                                 "drops the cells past the last column and pads a short row")
                i += 1
            prose, after_blank = False, False
            continue
        if line.lstrip().startswith("|"):
            found.append(f"{name}:{first + i}: a table row with no header and separator above it, which "
                         "GitHub draws as text; a blank line ends a table")
        prose, after_blank = True, False
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
        "a line of text right after a table": table + "bar\n",
        "a row past the header in a quoted table": "> | A | B |\n> |---|---|\n> | 1 | 2 | 3 |\n",
        "a blank line inside a quoted table": "> | A | B |\n> |---|---|\n> | 1 | 2 |\n>\n> | 4 | 5 |\n",
        "a row past the header in a table inside a list item": "- item\n\n    | A | B |\n    |---|---|\n    | 1 | 2 | 3 |\n",
    }
    admitted = {
        "a table as written": table,
        "an escaped pipe": table + "| a \\| b | 2 | 3 |\n",
        "a table without outer pipes": "A | B\n--- | ---\n1 | 2\n",
        "pipes in a fenced block": "```\n| a |\n\n| b | c |\n```\n",
        "an empty last cell": table + "| 4 | 5 | |\n",
        "two tables, each with its header": table + "\n" + table,
        "a fence that a line with an info string does not close": "```\nx\n```js\n| a |\n\n| b | c |\n```\n",
        "a closing fence indented four spaces, which is text": "```\n| x |\n    ```\n| y |\n```\n",
        "a fence indented four spaces in a list item": "1. Run:\n    ```bash\n    | x\n\n    | b | c |\n    ```\n",
        "a fence after a blank line in a list item": "- Run:\n\n    ```\n    | x |\n\n    | y | z |\n    ```\n",
        "an indented code block that starts with a pipe": "Text.\n\n    | a |\n",
        "an indented code block after a list and a paragraph": "- item\n\nText.\n\n    | code |\n",
        "a list item right after a table, though it holds a pipe": table + "- a | b\n",
        "a quote right after a table": table + "> a | b\n",
        "an indented line right after a table": table + "    a | b\n",
        "a heading or a rule right after a table": table + "# Heading\n\n" + table + "***\n",
        "a quoted table as written": "> | A | B |\n> |---|---|\n> | 1 | 2 |\n",
        "a table inside a list item": "- item\n\n    | A | B |\n    |---|---|\n    | 1 | 2 |\n",
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
    # Anything but no argument or exactly --self-test is refused: a mistyped flag that
    # fell through to main() skipped the self-test and still exited 0.
    if sys.argv[1:] not in ([], ["--self-test"]):
        print("usage: check-markdown-tables.py [--self-test]", file=sys.stderr)
        sys.exit(2)
    sys.exit(self_test() if sys.argv[1:] else main())
