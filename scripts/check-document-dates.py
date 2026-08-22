#!/usr/bin/env python3
"""Refuse a shipped document whose stated date is older than its contents.

    check-document-dates.py

Two documents that ship to users carry a date they maintain by hand, and both
had drifted: `LEGAL_NOTICES.md` said August 14 with content from the 18th,
`PRIVACY_POLICY.md` said August 15 with the same. Neither has any runtime
effect, and that is the problem: nothing failed, so nothing said.

The privacy policy's date is not decoration. The document promises, in its own
body, that the date at the top is updated whenever the policy changes. A stale
one is the policy breaking a commitment it makes to the reader, in the one
document a reader consults precisely because they do not trust prose.

The oracle is git: a document may not claim a date earlier than the last commit
that changed it. Later is fine and expected, because a policy can be dated for
the release it ships in, ahead of the commit that writes it.

Needs real history, and that is what decides where it can run. `actions/checkout`
clones one commit by default; `git log -1 -- <file>` in that clone reports the tip
commit for every file it can see and nothing for the rest, so the same check there
would return confident wrong answers. A shallow clone is therefore refused outright
rather than skipped: a check that cannot run is not a check that passed, which this
repository has already paid for once.

It ran in release.yml alone for that reason, and the cost of that was measured: a
punctuation sweep rewrote both documents without moving either date, every pull
request stayed green because none of them runs this, and the first notice would
have been the release job aborting on a tag that was already public. lint.yml now
takes `fetch-depth: 0` for this step, so the sweep that restales a date reddens on
the way in.
"""

import datetime
import pathlib
import re
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent

# Each entry: the file, and the pattern whose single group holds the date.
# Both dates are written for a reader rather than a parser, so the pattern is
# anchored on the surrounding words rather than on a bare date, which would also
# match copyright years and licence text.
DATED = [
    ("docs/LEGAL_NOTICES.md",
     re.compile(r"_This document was last updated on ([A-Z][a-z]+ \d{1,2}, \d{4})\._")),
    ("docs/PRIVACY_POLICY.md",
     re.compile(r"\*\*Last Updated: ([A-Z][a-z]+ \d{1,2}, \d{4})\*\*")),
]


def git(*args):
    return subprocess.run(
        ["git", *args], cwd=ROOT, capture_output=True, text=True, check=False
    )


def refuse_shallow():
    r = git("rev-parse", "--is-shallow-repository")
    if r.returncode != 0:
        sys.exit(f"FAIL not a git checkout, so the dates cannot be checked: {r.stderr.strip()}")
    if r.stdout.strip() == "true":
        sys.exit(
            "FAIL this is a shallow clone, so `git log` cannot say when a document "
            "last changed. Run this where checkout sets fetch-depth: 0. Skipping "
            "would report a pass for a check that never ran."
        )


def last_change(rel):
    r = git("log", "-1", "--format=%ad", "--date=short", "--", rel)
    if r.returncode != 0 or not r.stdout.strip():
        sys.exit(f"FAIL git has no commit touching {rel}; the date cannot be checked")
    return datetime.date.fromisoformat(r.stdout.strip())


def main():
    refuse_shallow()
    failures = []
    for rel, pattern in DATED:
        path = ROOT / rel
        if not path.exists():
            sys.exit(f"FAIL {rel} is listed here but absent")
        match = pattern.search(path.read_text(encoding="utf-8"))
        if not match:
            sys.exit(
                f"FAIL could not read a date out of {rel}. The wording changed, so "
                "this check stopped looking at anything; fix the pattern rather "
                "than removing the date."
            )
        stated = datetime.datetime.strptime(match.group(1), "%B %d, %Y").date()
        changed = last_change(rel)
        if stated < changed:
            failures.append((rel, stated, changed))

    if failures:
        print(f"FAIL {len(failures)} document(s) dated before their own contents:")
        for rel, stated, changed in failures:
            print(f"  {rel}  says {stated}, last changed {changed}")
        print(
            "  -> a reader takes the stated date as when the text was settled, and "
            "the privacy policy promises in its own body that this date moves with "
            "the policy"
        )
        return 1

    print(f"ok      {len(DATED)} dated documents are no older than their contents")
    return 0


if __name__ == "__main__":
    sys.exit(main())
