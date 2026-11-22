#!/usr/bin/env python3
"""The lint baseline may not grow, and may not name one machine.

    check-lint-baseline.py
    check-lint-baseline.py --play-console-labels <reports-dir>

`android/app/lint-baseline.xml` holds the warnings lint is told to hide, so
every entry in it is a warning no report will ever show again. How many entries
it is allowed to hold is stated in one place, the comment above `baseline =` in
`android/app/build.gradle.kts`. Nothing held the file to it.

`./gradlew updateLintBaseline` is the documented way to edit the file and it
does not edit it, it rewrites it. Measured in a clean checkout on 2026-08-18,
against the committed 17-entry file:

    entries before                                    17
    entries after `./gradlew updateLintBaseline`      91
    of those, locations rooted at `$HOME`             45
    issue ids present after but not before            10

The 10 include `HardcodedText`, `InsecureBaseConfiguration` and `SwitchIntDef`,
which nobody chose to hide; they were reported warnings until the regeneration
swallowed them. So the cost of an accidental run is not a noisy diff, it is
lint going quiet about things it was telling us.

Nothing could notice. `LintBaselineFixed`, the issue lint raises for an entry
matching nothing, is Information severity: the same measurement left 45 entries
unmatched and `./gradlew lint` still exited 0 with `abortOnError = true`.

Five assertions, all against the committed files, none needing a lint run:

  * the entry count equals the number the comment in build.gradle.kts states.
    One number, read from the file that states it rather than restated here, so
    the comment cannot drift either. Growing the baseline on purpose therefore
    means editing that number, which is a line a reviewer sees;
  * every `<location file=...>` is relative to the module. An absolute path, or
    one lint rooted at a variable such as `$HOME`, matches only in the checkout
    that produced it and is dead weight in every other, including the runner's;
  * the file parses, holds entries, and holds locations. A checker that reads
    nothing reports nothing, and CI reads the exit status, not the log;
  * the lint block still sets `abortOnError = true` and
    `warningsAsErrors = true`. Without either, a new warning lands under a green
    lint, and a baseline entry hides nothing that could have failed;
  * and it keeps the Play SDK Index warnings informational. Google's index
    raises those on its own schedule, so as errors they would fail every build
    on the day a label arrives, with nothing in any commit to blame.

Both of the first two are needed, and each covers what the other misses. Only
the count sees a regeneration performed outside the home directory, where lint
writes `../gradle/libs.versions.toml` instead, a relative path every location
rule would accept. Only the location rule sees an edit that holds the count
steady while putting a machine-rooted path back.

Neither of them reads what is being hidden, and that is the limit worth stating
rather than leaving to be discovered. An edit that keeps the count and keeps
every path relative to the module passes both: repoint an entry at another file
here, or swap its issue id, and the run is green. Only the diff catches that.
What the count rule buys is that such a diff cannot be a quiet one, since hiding
one more warning also means editing the number in build.gradle.kts.

The committed XML, deliberately, and not the count lint prints in its report
(`N warnings were filtered out because they are listed in the baseline file`).
That number is a property of the machine, not of the commit. Measured on the
same 91-entry file: 91 filtered in the checkout that generated it, and 46 in a
checkout at any other path, with the other 45 reappearing as warnings. So a
report-based check comparing the two numbers reads green at the one moment that
matters, on the developer's machine right after the accidental regeneration,
and red afterwards on CI for a reason that says nothing about growth, since a
merely stale entry produces the identical mismatch. It would also need lint to
have run first, which is fifteen minutes into the job; this needs nothing.
"""

import pathlib
import re
import sys
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parent.parent
BASELINE = ROOT / "android/app/lint-baseline.xml"
GRADLE = ROOT / "android/app/build.gradle.kts"

# The sentence in build.gradle.kts's lint block that states the count. Matched
# after the Kotlin comments are stripped and joined, because the number and
# the filename sit on either side of a line break.
STATED_COUNT = re.compile(r"\b(\d+) issues recorded in lint-baseline\.xml\b")

# `<issues` is the root element and must not be counted; `\b` refuses it,
# because `e` and `s` are both word characters and there is no boundary between
# them.
AN_ENTRY = re.compile(r"<issue\b")

# What makes lint fail a build at all, errors and warnings alike.
LINT_FLAGS = ("abortOnError = true", "warningsAsErrors = true")

# The other half of warningsAsErrors: warnings no commit raises. Lint downloads
# Google's Play SDK Index and reports a non-blocking label on a pinned version
# as a warning under these ids (lint 32.3.1, GradleDetector), so with the flag
# on, the index turning a label on would fail every build that day. All five:
# PlaySdkIndexGenericIssues, a label the library's own author sets, was the one
# missing, and it would have failed every build the day such a label arrived.
SDK_INDEX_WARNINGS = (
    "OutdatedLibrary",
    "PlaySdkIndexDeprecated",
    "PlaySdkIndexGenericIssues",
    "PlaySdkIndexNonCompliant",
    "PlaySdkIndexVulnerability",
)

# What listing those ids costs. Lint has no override that tells a non-blocking
# label from a blocking one, so the blocking label is informational too, and an
# informational issue fails nothing: a dependency Play Console refuses would
# pass `./gradlew lint`, the tag would publish the GitHub release, and the store
# upload would be the first refusal. Lint still writes the issue into its
# report, opening with this label (GooglePlaySdkIndex in lint-checks 32.3.1), so
# the release reads the report for it after lint has run.
BLOCKING_LABEL = "Prevents app release in Google Play Console"
INFORMATIONAL = re.compile(r"informational\s*\+=\s*listOf\(([^)]*)\)")

HOW_TO_EDIT = (
    "Do not regenerate it: `./gradlew updateLintBaseline` rewrites the whole "
    "file, restoring machine-specific locations and hiding issue types nobody "
    "chose to hide. Run `./gradlew lint` and delete by hand the entries it "
    "names under LintBaselineFixed, then correct the count stated in "
    "android/app/build.gradle.kts."
)

# Keep quoted text intact so a glob or URL cannot start a comment.
COMMENTS = re.compile(
    r'""".*?"""|"(?:\\.|[^"\\\r\n])*"|'
    r"(?P<comment>/\*.*?\*/|//[^\n]*)", re.DOTALL)


def comment_prose(text: str) -> str:
    """Kotlin comment prose, joined into one line."""
    said = []
    for match in COMMENTS.finditer(text):
        comment = match.group("comment")
        if comment:
            said.append(comment[2:-2] if comment.startswith("/*") else comment[2:])
    return re.sub(r"\s+", " ", " ".join(said)).strip()


def is_module_relative(path: str) -> bool:
    """Whether a baseline location means the same thing in every checkout.

    Absolute paths and lint's variable-rooted form (`$HOME/...`, expanded when
    lint reads the baseline back) both name one machine. `..` is fine: it
    resolves against the module and travels with it.
    """
    if path.startswith(("/", "~", "$")):
        return False
    return re.match(r"^[A-Za-z]:[\\/]", path) is None


def refuse_blocking_labels(reports: pathlib.Path) -> int:
    """Fail on any issue in the lint XML reports under [reports] carrying [BLOCKING_LABEL].

    No report at all fails too: this runs after `./gradlew lint`, so a missing
    report is a moved or renamed output, and reading nothing would pass.
    """
    found = sorted(reports.glob("lint-results-*.xml"))
    if not found:
        print(f"  FAIL   no lint XML report (lint-results-*.xml) under {reports}; "
              f"run ./gradlew lint first, or point this at where it writes them")
        return 1
    blocked = []
    for report in found:
        try:
            issues = ET.parse(report).getroot().iter("issue")
            blocked += [(report.name, issue.get("id"), issue.get("message") or "")
                        for issue in issues if BLOCKING_LABEL in (issue.get("message") or "")]
        except (ET.ParseError, OSError) as e:
            print(f"  FAIL   {report.name} could not be read as a lint report  {e}")
            return 1
    if blocked:
        for name, issue_id, message in blocked:
            print(f"  FAIL   {name}: {issue_id}  {message}")
        print("         Play Console refuses an upload carrying this dependency. "
              "Move it to a version the SDK Index does not block before tagging.")
        return 1
    print(f"  ok     no dependency in {len(found)} lint report(s) carries the label "
          f"Play Console refuses an upload for")
    return 0


def main() -> int:
    for path in (BASELINE, GRADLE):
        if not path.is_file():
            print(f"  FAIL   {path.relative_to(ROOT)} is missing; "
                  f"the module layout changed")
            return 1

    baseline_text = BASELINE.read_text()
    try:
        root = ET.fromstring(baseline_text)
    except ET.ParseError as e:
        print(f"  FAIL   android/app/lint-baseline.xml does not parse  {e}")
        return 1

    # Counted twice, through the parsed tree and through the raw bytes, and the
    # two have to agree. They answer the same question at different layers, so a
    # future baseline format that nests entries somewhere ElementTree does not
    # look would be caught rather than counted as zero.
    entries = root.findall("issue")
    textual = len(AN_ENTRY.findall(baseline_text))
    if len(entries) != textual:
        print(f"  FAIL   android/app/lint-baseline.xml counts "
              f"{len(entries)} entries as parsed XML and {textual} as text; "
              f"the file's shape is not what this checker assumes")
        return 1
    if not entries:
        print("  FAIL   android/app/lint-baseline.xml holds no entries; "
              "an empty baseline reads exactly like a clean one")
        return 1

    failed = False
    gradle_text = GRADLE.read_text()

    stated = STATED_COUNT.findall(comment_prose(gradle_text))
    if len(stated) != 1:
        print(f"  FAIL   build.gradle.kts states the baseline entry count "
              f"{len(stated)} times, expected once")
        print("         The lint block's comment must carry the phrase "
              "'<N> issues recorded in lint-baseline.xml'. It is the only "
              "record of how big this file is allowed to be.")
        failed = True
    elif int(stated[0]) != len(entries):
        print(f"  FAIL   android/app/lint-baseline.xml holds {len(entries)} "
              f"entries; build.gradle.kts says {stated[0]}")
        print(f"         {HOW_TO_EDIT}")
        failed = True
    else:
        print(f"  ok     the baseline holds the {len(entries)} entries "
              f"build.gradle.kts states")

    code_text = COMMENTS.sub(
        lambda match: "" if match.group("comment") else match.group(), gradle_text)
    code = {line.strip() for line in code_text.splitlines()}
    unset = [flag for flag in LINT_FLAGS if flag not in code]
    if unset:
        print(f"  FAIL   build.gradle.kts no longer sets {' or '.join(unset)}")
        print("         Without it a new warning lands under a green lint, and "
              "a baseline entry hides nothing that could have failed.")
        failed = True
    else:
        print("  ok     lint fails the build on errors and warnings alike")

    informational = set(re.findall(r'"([^"]+)"', " ".join(
        m.group(1) for m in INFORMATIONAL.finditer(code_text))))
    raised = [i for i in SDK_INDEX_WARNINGS if i not in informational]
    if raised:
        print(f"  FAIL   build.gradle.kts no longer lists {', '.join(raised)} "
              f"as informational")
        print("         Google's SDK Index raises those, not a commit, and "
              "warningsAsErrors would turn every build red the day it does.")
        failed = True
    else:
        print("  ok     the SDK Index warnings no commit raises stay "
              "informational")

    located = [(issue.get("id"), loc.get("file"))
               for issue in entries
               for loc in issue.findall("location")]
    if not located:
        print("  FAIL   no <location> under any entry; the pattern this "
              "checker reads locations by stopped matching")
        return 1

    pinned = [(issue_id, f) for issue_id, f in located
              if f is None or not is_module_relative(f)]
    if pinned:
        print(f"  FAIL   {len(pinned)} of {len(located)} baseline locations "
              f"name one machine rather than this module")
        for issue_id, f in sorted(set(pinned), key=lambda p: (p[0] or "", p[1] or "")):
            print(f"           {issue_id}  {f or '(no file attribute)'}")
        print("         Such an entry matches only in the checkout that wrote "
              "it and hides nothing anywhere else, while still counting as a "
              "suppression here.")
        print(f"         {HOW_TO_EDIT}")
        failed = True
    else:
        print(f"  ok     all {len(located)} baseline locations are relative "
              f"to the module")

    return 1 if failed else 0


if __name__ == "__main__":
    if sys.argv[1:2] == ["--play-console-labels"] and len(sys.argv) == 3:
        sys.exit(refuse_blocking_labels(pathlib.Path(sys.argv[2])))
    if sys.argv[1:]:
        print("usage: check-lint-baseline.py [--play-console-labels <reports-dir>]", file=sys.stderr)
        sys.exit(2)
    sys.exit(main())
