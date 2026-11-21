#!/usr/bin/env python3
"""Refuse a user guide whose Bundled Tools list names a version this build did not ship.

    check-guide-versions.py build-manifest.txt

The guide's Bundled Tools list gives each tool's version in a comment
(`make --version    # GNU Make 4.4.1`), written by hand. The tools are taken from
the Termux index on the day a build runs, and write-build-manifest.py records what
that build resolved without comparing it with anything: it is a record, not a lock.
So a Termux update could move a tool while the guide went on promising the old
version, and nothing would say so.

release.yml runs this right after it writes the manifest. Each versioned line is
held to its Termux package at the precision the guide states: `24.x` asks for
the major, `3.14.x` the major and minor, and `4.4.1` the whole upstream
version, with the epoch and the Debian revision set aside. A versioned line whose
command has no package here fails, and so does a package the manifest does not
record, so a tool added to the list cannot add a version nobody compares. npm is
not a Termux package; check-build-steps.py holds its major to download-npm.sh.
"""

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
GUIDE = ROOT / "docs/USER_GUIDE.md"

# The command a line of the list starts with, and the Termux package that ships it.
PACKAGES = {
    "node -v": "nodejs-lts",
    "python3 --version": "python",
    "git --version": "git",
    "bash --version": "bash",
    "tmux -V": "tmux",
    "make --version": "make",
    "ssh -V": "openssh",
}
# Versioned, and compared somewhere else.
CHECKED_ELSEWHERE = {"npm -v"}

VERSION = re.compile(r"(?<![\w.])(\d+(?:\.\d+)*)(\.x)?(?![\w.])")


def listed_versions(text):
    """{command: (version, wildcard)} for each versioned line of the Bundled Tools block."""
    section = re.search(r"^### Bundled Tools\n.*?^```\n(.*?)^```", text, re.M | re.S)
    if not section:
        sys.exit("FAIL the guide has no Bundled Tools code block; the pattern stopped matching")
    found = {}
    for line in section.group(1).splitlines():
        command, _, comment = line.partition("#")
        version = VERSION.search(comment)
        if not version:
            continue
        command = " ".join(command.split())
        if command in found:
            sys.exit(f"FAIL the Bundled Tools list names `{command}` twice")
        found[command] = (version.group(1), bool(version.group(2)))
    return found


def upstream(version):
    """`1:3.6.5` -> `3.6.5`, `4.4.1-1` -> `4.4.1`: the version the program itself reports."""
    version = version.split(":", 1)[-1]
    return version.rsplit("-", 1)[0] if "-" in version else version


def problems(guide_text, manifest_text):
    shipped = {}
    for line in manifest_text.splitlines():
        fields = line.split("\t")
        if len(fields) >= 3 and fields[0] == "termux":
            shipped[fields[1]] = upstream(fields[2])
    listed = listed_versions(guide_text)
    found = []
    for command in sorted(set(listed) - set(PACKAGES) - CHECKED_ELSEWHERE):
        found.append(f"`{command}` gives a version and has no Termux package in PACKAGES to compare it with")
    for command, package in PACKAGES.items():
        if command not in listed:
            found.append(f"the Bundled Tools list has no versioned `{command}` line; the pattern stopped matching")
            continue
        if package not in shipped:
            found.append(f"the manifest records no {package}, so `{command}` cannot be compared")
            continue
        version, wildcard = listed[command]
        parts = version.split(".")
        actual = shipped[package].split(".")
        if (actual[:len(parts)] != parts) if wildcard else (shipped[package] != version):
            stated = version + (".x" if wildcard else "")
            found.append(f"the guide says `{command}` gives {stated}; this build ships {package} {shipped[package]}")
    return found


def main():
    if len(sys.argv) != 2:
        sys.exit("usage: " + __doc__.strip().splitlines()[2].strip())
    found = problems(GUIDE.read_text(encoding="utf-8"), pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
    if found:
        print(f"FAIL {len(found)} tool version(s) in docs/USER_GUIDE.md that this build did not ship:")
        for problem in found:
            print(f"  {problem}")
        print("  -> correct the guide's Bundled Tools list, at the precision it can keep (`5.3.x` rather than `5.3.20`)")
        return 1
    print(f"ok      the {len(PACKAGES)} tool versions the user guide lists are what this build ships")
    return 0


if __name__ == "__main__":
    sys.exit(main())
