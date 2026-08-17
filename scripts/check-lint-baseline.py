#!/usr/bin/env python3
"""The lint baseline may not grow, and may not name one machine.

    check-lint-baseline.py

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

Three assertions, all against the committed file, none needing a lint run:

  * the entry count equals the number the comment in build.gradle.kts states.
    One number, read from the file that states it rather than restated here, so
    the comment cannot drift either. Growing the baseline on purpose therefore
    means editing that number, which is a line a reviewer sees;
  * every `<location file=...>` is relative to the module. An absolute path, or
    one lint rooted at a variable such as `$HOME`, matches only in the checkout
    that produced it and is dead weight in every other, including the runner's;
  * the file parses, holds entries, and holds locations. A checker that reads
    nothing reports nothing, and CI reads the exit status, not the log.

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
# after the Kotlin line comments are stripped and joined, because the number and
# the filename sit on either side of a line break.
STATED_COUNT = re.compile(r"\b(\d+) issues recorded in lint-baseline\.xml\b")

# `<issues` is the root element and must not be counted; `\b` refuses it,
# because `e` and `s` are both word characters and there is no boundary between
# them.
AN_ENTRY = re.compile(r"<issue\b")

HOW_TO_EDIT = (
    "Do not regenerate it: `./gradlew updateLintBaseline` rewrites the whole "
    "file, restoring machine-specific locations and hiding issue types nobody "
    "chose to hide. Run `./gradlew lint` and delete by hand the entries it "
    "names under LintBaselineFixed, then correct the count stated in "
    "android/app/build.gradle.kts."
)


def comment_prose(text: str) -> str:
    """Every Kotlin line comment in the file, joined into one line."""
    said = [line.strip()[2:] for line in text.splitlines()
            if line.strip().startswith("//")]
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

    stated = STATED_COUNT.findall(comment_prose(GRADLE.read_text()))
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
    sys.exit(main())
