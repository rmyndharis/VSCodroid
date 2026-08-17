#!/usr/bin/env python3
"""Check the process monitor can recognise the language servers that ship.

    check-langserver-patterns.py <extensions-dir>

process-monitor.js labels a process as a language server by matching the
basename of each command-line argument against a list of patterns, and that list
is what decides which processes the idle-kill can reclaim. A pattern that
matches nothing is invisible twice over: the server keeps running, it keeps
counting against Android's phantom-process budget, and the feature built to
manage exactly that never sees it.

That already happened. The list carried 'css-languageserver',
'html-languageserver' and 'json-languageserver' while the processes VS Code
actually launches are cssServerMain.js, htmlServerMain.js and jsonServerMain.js,
so the three servers most likely to be running were the three the monitor could
not see. Nothing reported it, because a pattern that matches nothing looks
exactly like a language server that is not running.

This compares the list against the servers in the tree being packaged. It cannot
cover servers that arrive with extensions a user installs later -- those are the
rest of the list, and they are unverifiable here by construction. What it does
cover is everything this repository chooses to ship, which is where the drift
was.

The comparison has to be the monitor's comparison, and for a while it was not.
This matched each pattern against the whole path, as written; the monitor
lower-cased the command line first and matched against that, so a pattern with a
capital in it could not match there while matching perfectly here. Every run of
this script said the three bundled servers were covered, and none of them was.
Both sides now fold case and both look at the basename, which is the part that
names the program rather than the directory somebody put it in. Both also split
the list the same way: a pattern that is a single word has to be the whole
basename, give or take the extension a Node entry point carries, while a pattern
carrying a separator is still found anywhere inside it.
"""

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
MONITOR = ROOT / "android/app/src/main/assets/process-monitor.js"

# VS Code's built-in language servers are all named <lang>ServerMain.js.
SERVER_ENTRY = "*ServerMain.js"


def patterns():
    """The pattern list, or empty when it cannot be found.

    Empty rather than raising: the caller turns that into a message naming what
    it looked for. A traceback exits non-zero too, but it tells whoever hits it
    that this script broke rather than that the list moved, which sends them to
    the wrong file.

    Each line is cut at its first `//` before any quoted string is taken from
    it. The list is heavily commented, and those comments quote the names of
    patterns that were tried and removed for matching nothing. Reading them as
    entries turned this script into the thing it exists to prevent: a server
    could be reported covered by a 'pattern' that lives only in prose, while the
    monitor classified the same process 'unknown'. No pattern contains `//`, so
    the cut can only ever remove commentary.
    """
    src = MONITOR.read_text()
    start = src.find("const LANG_SERVER_PATTERNS")
    if start < 0:
        return []
    end = src.find("];", start)
    if end < 0:
        return []
    code = "\n".join(line.split("//", 1)[0] for line in src[start:end].splitlines())
    found = re.findall(r"'([^']*)'", code)
    # A pattern is compared against one basename, so whitespace in one means the
    # cut above stopped working and prose is being read as code again. An empty
    # one is worse than wrong: the monitor's substring arm accepts '' inside
    # every name, so one would classify every process a language server. Refuse
    # the whole list rather than the offending entry, because a partial list
    # silently narrows what this script checks, which is the failure it is here
    # to catch.
    if any(not p or re.search(r"\s", p) for p in found):
        return []
    return found


def matches(name, pattern):
    """classify()'s comparison, against one already-lower-cased basename.

    The character class is spelled out rather than written \\w: Python's is
    unicode-aware and JavaScript's is not, and a helper that accepts a pattern
    the monitor would reject is this script's whole failure mode.
    """
    needle = pattern.lower()
    if not re.fullmatch(r"[A-Za-z0-9_]+", needle):
        return needle in name
    return any(name == needle + ext for ext in ("", ".js", ".mjs", ".cjs"))


def main(extensions):
    pats = patterns()
    # An empty list would make every assertion below vacuously true, and this
    # reads the list out of a JavaScript file, so the parse failing quietly is a
    # real possibility rather than a theoretical one.
    if not pats:
        print(f"  FAIL   no patterns parsed from {MONITOR.name}; the list moved or changed shape")
        return 1

    servers = sorted(extensions.rglob(SERVER_ENTRY))
    if not servers:
        print(f"  FAIL   no {SERVER_ENTRY} under {extensions}; "
              "either nothing ships them or the naming convention changed")
        return 1

    failed = False
    for server in servers:
        # classify() splits the command line on spaces and tests the basename of
        # each argument, case-folded. The argument that carries a server is its
        # path, so its basename is what has to match here.
        name = server.name.lower()
        hit = next((p for p in pats if matches(name, p)), None)
        if hit:
            print(f"  ok      {server.name} matched by {hit!r}")
        else:
            print(f"  FAIL    {server.name} is matched by no pattern")
            print(f"            {server.relative_to(extensions)}")
            failed = True

    if failed:
        print(f"         Add a pattern to LANG_SERVER_PATTERNS in {MONITOR.name}.")
        print("         Until then the monitor cannot see it, and the idle-kill "
              "cannot reclaim it.")
    return 1 if failed else 0


if __name__ == "__main__":
    if len(sys.argv) != 2:
        print("usage: check-langserver-patterns.py <extensions-dir>", file=sys.stderr)
        sys.exit(2)
    path = pathlib.Path(sys.argv[1])
    if not path.is_dir():
        print(f"  FAIL    {path} is not a directory", file=sys.stderr)
        sys.exit(1)
    sys.exit(main(path))
