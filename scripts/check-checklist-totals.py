#!/usr/bin/env python3
"""Refuse a device-test checklist whose summary disagrees with its own rows.

    check-checklist-totals.py

`docs/DEVICE_TEST_CHECKLIST.md` ends in a summary table giving a count per
section and a grand total. That table is derived data kept by hand, and it had
drifted: three sections were wrong and the total was one short, in a document
whose only job is to account for whether everything was tried.

Nothing else can catch this. The rows are prose in a Markdown table, so no test
runs them and no build reads them; a wrong count is invisible until someone
works through the list and finds it does not add up, which is exactly when the
document is being trusted.

Three rules, all mechanical:

  * every section's stated count equals the rows actually present in it;
  * the total equals the sum, and equals the rows in the file;
  * every row `docs/10-RELEASE_PLAN.md` cites is a row here, and it cites one.

The second is not implied by the first. A total maintained separately can be
right about a set of section counts that are themselves wrong, and was.

The third exists because row IDs outlive their rows: scripts/device-test.sh
still cites TC-5, which was removed with the Go toolchain. The release plan
sends a person through named rows with the minified build before every tag, so
a cited row that is gone is a step nobody can follow, and a plan citing none
has lost the step. Its refusals never fire on a clean tree, so every run first
hands them the input they exist to refuse.

Rows are recognised by their identifier: a leading `| XX-N |` cell, which is the
shape every row in the document uses and no header or prose does.
"""

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
CHECKLIST = ROOT / "docs/DEVICE_TEST_CHECKLIST.md"
PLAN = ROOT / "docs/10-RELEASE_PLAN.md"

# `## 7. Background/Foreground` -> the section heading the summary names.
SECTION = re.compile(r"^##\s+\d+\.\s+(.+?)\s*$")
ROW = re.compile(r"^\|\s*([A-Z]{2,3}-\d+)\s*\|")
# `| Background/Foreground | 7 | | | |` and the bolded total line.
SUMMARY_ROW = re.compile(r"^\|\s*([A-Za-z][A-Za-z &/]*?)\s*\|\s*(\d+)\s*\|")
TOTAL_ROW = re.compile(r"^\|\s*\*\*Total\*\*\s*\|\s*\*\*(\d+)\*\*\s*\|")


def citation_problems(ids, plan_text):
    """The row IDs `plan_text` cites, and what is wrong with them given `ids`."""
    # Prefixes come from the checklist, so there is no hand list to drift and
    # UTF-8 or SHA-256 cannot match. IDs are read whole: a cited SF-16 must
    # never pass as the row SF-1.
    prefixes = "|".join(sorted({i.split("-")[0] for i in ids}))
    cited = set(re.findall(rf"\b(?:{prefixes})-\d+\b", plan_text))
    if not cited:
        return cited, [
            "  the release plan cites no checklist row, so its pre-tag device "
            "step is gone"
        ]
    return cited, [
        f"  {PLAN.name} cites {rid}, which is not a row here"
        for rid in sorted(cited - ids)
    ]


def main():
    # `if`, not assert: python -O strips asserts, and a refusal must never
    # pass by not running.
    if not any("SF-16" in p for p in citation_problems({"SF-1"}, "run SF-16")[1]):
        sys.exit("FAIL self-check: a cited SF-16 was read as the row SF-1")
    if not citation_problems({"SF-1"}, "no rows")[1]:
        sys.exit("FAIL self-check: a plan citing no row was accepted")
    if citation_problems({"SF-1", "SF-16"}, "SF-1 and SF-16")[1]:
        sys.exit("FAIL self-check: a plan citing only existing rows was refused")

    if not CHECKLIST.is_file():
        sys.exit(f"FAIL {CHECKLIST} is missing; this check would otherwise look at nothing")
    if not PLAN.is_file():
        sys.exit(f"FAIL {PLAN} is missing, so the rows it cites cannot be checked")

    lines = CHECKLIST.read_text(encoding="utf-8").splitlines()

    # Paired by position rather than by name. The summary abbreviates: section
    # "Background / Foreground" is summarised as "Background/Foreground",
    # "Performance Benchmarks" as "Performance". Matching on the text would
    # report a rename as a missing section and say nothing about the counts,
    # which are the subject. The document guarantees the order: sections are
    # numbered and the summary lists them in that order.
    sections = []  # (heading, rows)
    current = None
    in_summary = False
    stated = []  # (label, count)
    total = None

    for line in lines:
        heading = SECTION.match(line)
        if heading:
            current = heading.group(1)
            sections.append([current, 0])
            continue
        if line.strip() == "## Summary":
            in_summary = True
            current = None
            continue
        if in_summary:
            m = TOTAL_ROW.match(line)
            if m:
                total = int(m.group(1))
                continue
            m = SUMMARY_ROW.match(line)
            if m and m.group(1) != "Category":
                stated.append((m.group(1), int(m.group(2))))
            continue
        if current and ROW.match(line):
            sections[-1][1] += 1

    if not stated:
        sys.exit("FAIL no summary table was found, so nothing was compared")
    if total is None:
        sys.exit("FAIL the summary has no total row, so nothing was compared")

    problems = []
    if len(sections) != len(stated):
        problems.append(
            f"  the document has {len(sections)} sections and the summary lists "
            f"{len(stated)}, so they cannot be paired at all"
        )
    for (heading, rows), (label, said) in zip(sections, stated):
        if rows != said:
            problems.append(
                f"  '{label}' (section '{heading}'): summary says {said}, "
                f"the document has {rows}"
            )

    rows_in_file = sum(1 for line in lines if ROW.match(line))
    if total != sum(c for _, c in stated):
        problems.append(
            f"  total says {total}, the section counts add up to "
            f"{sum(c for _, c in stated)}"
        )
    if total != rows_in_file:
        problems.append(f"  total says {total}, the document has {rows_in_file} rows")

    ids = {m.group(1) for m in map(ROW.match, lines) if m}
    cited, cite_problems = citation_problems(ids, PLAN.read_text(encoding="utf-8"))

    if problems:
        print("FAIL the device-test checklist does not account for its own rows:")
        print("\n".join(problems))
        print(
            "  -> the summary is the only record of whether every case was tried, "
            "so a wrong count is a claim about coverage that nobody made"
        )
    if cite_problems:
        print("FAIL the release plan's pre-tag device step does not resolve:")
        print("\n".join(cite_problems))
        print(
            "  -> a person runs those rows on the minified build before every "
            "tag, so each has to still say what to run"
        )
    if problems or cite_problems:
        return 1

    print(
        f"ok      the checklist accounts for all {rows_in_file} rows across "
        f"{len(stated)} sections, and the {len(cited)} rows the release plan cites"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
