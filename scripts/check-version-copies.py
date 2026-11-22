#!/usr/bin/env python3
"""Refuse a document that states the app's current version other than build.gradle.kts does.

    check-version-copies.py

android/app/build.gradle.kts declares versionCode and versionName, and three
places repeat them by hand: the `versionCode` and `versionName` block in
docs/10-RELEASE_PLAN.md, the same block in the `defaultConfig` of
docs/04-TECHNICAL_SPEC.md, and the placeholder of the `VSCodroid Version` field
in .github/ISSUE_TEMPLATE/bug_report.yml. A release bump has to change all
four, and nothing compared them, so a copy could go on stating a version that
was no longer the current one.

Only the lines that state the current version are read, each found by a pattern
that has to match exactly once, so a reworded line cannot switch its check off:
it fails and says which file to look at. Every other mention of a version, in
those files and in the CHANGELOG, is history and is left alone.
"""

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
GRADLE = "android/app/build.gradle.kts"
CODE = re.compile(r"^[ \t]*versionCode = ([0-9]+)[ \t]*(?://.*)?$", re.M)
NAME = re.compile(r'^[ \t]*versionName = "([^"]*)"[ \t]*(?://.*)?$', re.M)
# The placeholder inside the input whose id is `version`, before the next item of the form.
PLACEHOLDER = re.compile(r"^[ \t]*id: version[ \t]*\n(?:(?![ \t]*- ).*\n)*?[ \t]*placeholder: *(\S+)[ \t]*$", re.M)
COPIES = (
    ("docs/10-RELEASE_PLAN.md", (("versionCode", CODE), ("versionName", NAME))),
    ("docs/04-TECHNICAL_SPEC.md", (("versionCode", CODE), ("versionName", NAME))),
    (".github/ISSUE_TEMPLATE/bug_report.yml", (("versionName", PLACEHOLDER),)),
)


def the_one(path, label, pattern):
    """The single value `pattern` finds in `path`, or a problem naming why there is not exactly one."""
    found = pattern.findall((ROOT / path).read_text(encoding="utf-8"))
    if len(found) != 1:
        return None, f"{path}: {len(found)} lines state the {label}, wanted exactly one; the pattern stopped matching"
    return found[0], None


def main():
    problems = []
    current = {}
    for label, pattern in (("versionCode", CODE), ("versionName", NAME)):
        current[label], problem = the_one(GRADLE, label, pattern)
        if problem:
            problems.append(problem)
    if not problems:
        for path, wanted in COPIES:
            for label, pattern in wanted:
                value, problem = the_one(path, label, pattern)
                if problem:
                    problems.append(problem)
                elif value != current[label]:
                    problems.append(f"{path}: states {label} {value}, {GRADLE} says {current[label]}")
    if problems:
        print(f"FAIL {len(problems)} copy(ies) of the app's version disagree with {GRADLE}:")
        for problem in problems:
            print(f"  {problem}")
        print("  -> a version bump changes build.gradle.kts and every copy above together")
        return 1
    print(f"ok      versionCode {current['versionCode']} and versionName {current['versionName']} "
          f"are stated the same in {len(COPIES)} copies")
    return 0


if __name__ == "__main__":
    sys.exit(main())
