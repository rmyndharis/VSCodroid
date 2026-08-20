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

# Files that describe what the product CONTAINS, whether a user or a maintainer
# reads them. The split is what a file is for, not who opens it.
#
# Gated: everything that states the shipped set in the present tense. A design
# document that names a retired toolchain sends the next person to build against
# something that is not there, and the six that did all cited
# `ToolchainRegistry.kt` as their source while contradicting it.
#
# Not gated, and this is a boundary rather than an oversight: files whose job is
# to record what happened on a date. `CHANGELOG.md` and the version history in
# `docs/10-RELEASE_PLAN.md` are where a withdrawal is described. `MILESTONES.md`
# holds completed checklist items and device measurements taken while the
# toolchain still shipped, and rewriting those would falsify a record rather than
# correct a claim. `docs/12-IMPLEMENTATION_PLAN.md` is a mix: alongside those
# records it carries unchecked `- [ ]` items and acceptance tables, so it is out
# for a weaker reason, that nobody has separated the two.
# `docs/DEVICE_TEST_CHECKLIST.md` is out because its Go rows test that an already
# installed Go is REMOVED on update, which is live behaviour in
# `ToolchainManager.removeRetiredToolchainsSync`.
#
# `docs/12-IMPLEMENTATION_PLAN.md` also writes "Go/no-go" for a decision gate,
# which this check would read as an offer. None of the other excluded files do.
OFFER_FILES = [
    ROOT / "README.md",
    ROOT / "NOTICE.md",
    ROOT / "docs/USER_GUIDE.md",
    ROOT / "docs/PRIVACY_POLICY.md",
    ROOT / "docs/LEGAL_NOTICES.md",
    ROOT / "docs/01-PRD.md",
    ROOT / "docs/02-SRS.md",
    ROOT / "docs/03-ARCHITECTURE.md",
    ROOT / "docs/04-TECHNICAL_SPEC.md",
    ROOT / "docs/05-API_SPEC.md",
    ROOT / "docs/06-SECURITY.md",
    ROOT / "docs/07-TESTING_STRATEGY.md",
    ROOT / "docs/08-RISK_MATRIX.md",
    ROOT / "docs/11-GLOSSARY.md",
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

# Ordinary English that the capitalised name collides with. These are cut OUT of
# a line before it is searched, rather than excusing the whole line: the
# sentences that state how toolchains are delivered are exactly the ones that say
# "Google Play", so skipping any line containing one hid the highest-value claims
# in the set. Measured: a line reading "on-demand toolchain packs (Go, Ruby, Java)
# are delivered via Google Play Asset Delivery" passed the check.
#
# Case-insensitive because the excuses are written lowercase while the name is
# matched capitalised, so "Go Back" and "Go To Definition" matched the name and
# not the excuse. Both are ordinary VS Code menu wording in the documents this
# now reads.
INNOCENT = re.compile(
    r"go (?:to|back|ahead|live)\b|on-the-go|google|\bgo gopher\b|let's go\b",
    re.IGNORECASE,
)


RETIRED_OPEN = re.compile(r"RETIRED_TOOLCHAINS\s*(?::[^=]+)?=\s*(?:setOf|mapOf)\(")


def retired_body(text):
    """What is inside `RETIRED_TOOLCHAINS = mapOf(...)`, or None if it will not parse.

    Balances the parentheses rather than reading to the first `)`. The regex this
    replaces used `[^)]*`, which stops at any close paren, including one inside a
    value expression or a trailing comment: `mapOf("go" to (179L * 1_000_000L),
    "ruby" to ...)` and a `// measured (unpacked)` comment both truncated the body
    to the first entry, and the second identifier was dropped with nothing said.
    That is the one outcome this whole function exists to prevent, since a short
    list means the check looks for less than it claims to.

    Parens inside double-quoted strings and after `//` do not count. A body that
    never balances returns None, so the caller fails rather than searching an
    empty string.
    """
    match = RETIRED_OPEN.search(text)
    if not match:
        return None
    depth, in_string, escaped, out = 1, False, False, []
    i = match.end()
    while i < len(text):
        char = text[i]
        if in_string:
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == '"':
                in_string = False
        elif char == '"':
            in_string = True
        elif text.startswith("//", i):
            end_of_line = text.find("\n", i)
            if end_of_line < 0:
                return None
            out.append(" ")
            i = end_of_line
            continue
        elif char == "(":
            depth += 1
        elif char == ")":
            depth -= 1
            if depth == 0:
                return "".join(out)
        out.append(char)
        i += 1
    return None


def retired_ids():
    """The identifiers `ToolchainManager` says are withdrawn.

    Reads the declaration rather than a copy, and treats a declaration it cannot
    parse as a failure rather than as an empty answer. Those two outcomes look
    identical downstream -- no identifiers, so nothing to look for, so every file
    passes -- and only one of them means the tree is clean.
    """
    text = MANAGER.read_text(encoding="utf-8")
    body = retired_body(text)
    if body is None:
        sys.exit("FAIL could not read RETIRED_TOOLCHAINS from ToolchainManager.kt")
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
            # The excused wording is removed and the rest of the line is still
            # read, so "Go to Settings, then install Go" is caught on its second
            # half. The whole line is reported, not the scrubbed copy, because
            # that is what a person has to go and edit.
            scrubbed = INNOCENT.sub(" ", line)
            for ident, pattern in patterns:
                if pattern.search(scrubbed):
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
