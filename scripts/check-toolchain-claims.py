#!/usr/bin/env python3
"""Refuse a document that offers a toolchain the app no longer installs.

    check-toolchain-claims.py

Withdrawing a toolchain is a one-line change to `ToolchainRegistry.available`,
and the picker follows it immediately because the picker is built from that
list. Nothing else is. Seven places name the toolchains in prose instead, and
when Go was withdrawn all seven kept offering it: the README's feature list and
its "pick your languages" step, the user guide's size table and its worked
example, the privacy policy's description of what may be downloaded, both
attribution documents, and worst of the set, the Get Started walkthrough and
its illustration, which are shown to every new user inside the app.

The picker was right and everything a user reads was wrong, so the app promised
a language it then would not offer.

`check-welcome-claims.py` reads two of those files and did not catch it, which
is the useful part of the story rather than an aside: that check asks whether
the screen states a version number or says "coming soon". Both questions are
sound and neither is this one. A file being covered by some gate is not the
same as the claim in it being covered.

One rule. In a file whose job is to tell a user what they can install, a
retired toolchain's name may not appear at all. There is no attempt to judge
whether a given sentence offers or merely mentions it: the files listed here
carry no history, so any mention reads as an offer. The CHANGELOG is therefore
not among them. It is where a withdrawal is supposed to be described.

The names to look for are not derived from the retired identifier. `"go"` as a
substring is in "going" and "Google", and a check that searched for it would
report the whole English language. They are written out per identifier below,
and an identifier with no entry is a hard failure rather than a silent pass, so
retiring a second toolchain cannot quietly check nothing.
"""

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent

MANAGER = ROOT / "android/app/src/main/kotlin/com/vscodroid/setup/ToolchainManager.kt"
WELCOME = ROOT / "android/app/src/main/assets/extensions/vscodroid.vscodroid-welcome-1.2.2"

# Files that answer "what can I install", and are read by a user rather than by
# a maintainer. Design documents under docs/ are deliberately absent: they
# record what was planned, and correcting them backwards would destroy the
# record. The CHANGELOG is absent for the same reason, more sharply.
OFFER_FILES = [
    ROOT / "README.md",
    ROOT / "NOTICE.md",
    ROOT / "docs/USER_GUIDE.md",
    ROOT / "docs/PRIVACY_POLICY.md",
    ROOT / "docs/LEGAL_NOTICES.md",
    WELCOME / "package.json",
    WELCOME / "media/tools.svg",
]

# How each retired identifier is written where a human reads it. The command
# forms are here because a worked example teaches the toolchain as firmly as a
# table row does, and the user guide's was six lines of `go mod init`.
RETIRED_SPELLINGS = {
    "go": [
        r"\bGo\b",
        r"\bgolang\b",
        r"\bgofmt\b",
        r"\bgo (?:build|run|version|env|mod|get|install|test|vet)\b",
    ],
}

# Ordinary English that the capitalised name collides with. Kept narrow on
# purpose: a line excused here is a line this check cannot see.
INNOCENT = re.compile(
    r"Go (?:to|back|ahead|live)\b|on-the-go|Google|\bGo Gopher\b|Let's Go\b"
)


def retired_ids():
    """The identifiers `ToolchainManager` says are withdrawn.

    Reads the declaration rather than a copy, and treats a declaration it cannot
    parse as a failure rather than as an empty answer. Those two outcomes look
    identical downstream -- no identifiers, so nothing to look for, so every file
    passes -- and only one of them means the tree is clean.
    """
    text = MANAGER.read_text(encoding="utf-8")
    match = re.search(
        r"RETIRED_TOOLCHAINS\s*(?::[^=]+)?=\s*(setOf|mapOf)\(([^)]*)\)", text
    )
    if not match:
        sys.exit("FAIL could not read RETIRED_TOOLCHAINS from ToolchainManager.kt")
    body = match.group(2)
    # mapOf entries are `"name" to <size>`; setOf entries are bare strings.
    ids = re.findall(r'"([^"]+)"\s+to\b', body) or re.findall(r'"([^"]+)"', body)
    if body.strip() and not ids:
        sys.exit(
            "FAIL RETIRED_TOOLCHAINS was found but no identifier could be read "
            f"from it: {body.strip()[:80]!r}. Passing here would check nothing."
        )
    return ids


def main():
    ids = retired_ids()
    if not ids:
        print("ok      nothing is retired, so nothing can be offered in error")
        return 0

    missing = [i for i in ids if i not in RETIRED_SPELLINGS]
    if missing:
        sys.exit(
            f"FAIL {', '.join(missing)} is retired but has no entry in "
            "RETIRED_SPELLINGS, so this check would pass without looking"
        )

    patterns = [
        (i, re.compile(p)) for i in ids for p in RETIRED_SPELLINGS[i]
    ]

    findings = []
    for path in OFFER_FILES:
        if not path.exists():
            sys.exit(f"FAIL {path.relative_to(ROOT)} is listed here but absent")
        for n, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            if INNOCENT.search(line):
                continue
            for ident, pattern in patterns:
                if pattern.search(line):
                    findings.append(
                        (path.relative_to(ROOT), n, ident, line.strip()[:96])
                    )
                    break

    if findings:
        print(f"FAIL {len(findings)} offer(s) of a withdrawn toolchain:")
        for path, n, ident, line in findings:
            print(f"  {path}:{n}  names '{ident}': {line}")
        print(
            "  -> the picker is built from ToolchainRegistry.available and no "
            "longer offers it; a user reading this is promised a language the "
            "app will not install"
        )
        return 1

    print(
        f"ok      {len(OFFER_FILES)} user-facing files offer none of "
        f"{', '.join(ids)}"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
