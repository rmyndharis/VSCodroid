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

Four rules, all mechanical:

  * every section's stated count equals the rows actually present in it;
  * the total equals the sum, and equals the rows in the file;
  * every row `docs/10-RELEASE_PLAN.md` cites is a row here, and it cites one;
  * every row here belongs to a family this script lists.

The second is not implied by the first. A total maintained separately can be
right about a set of section counts that are themselves wrong, and was.

The third exists because row IDs outlive their rows: scripts/device-test.sh
went on citing TC-5 for weeks after it was removed with the Go toolchain. The
release plan sends a person through named rows with the minified build before
every tag, so a cited row that is gone is a step nobody can follow, and a plan
citing none has lost the step. Its refusals never fire on a clean tree, so
every run first hands them the input they exist to refuse.

The fourth exists for the third. A citation is an ID of one of the
checklist's own families, because the plan's prose can also name IDs of other
families in the same shape, such as PSF-2.0, EPL-2.0, ADR-001 and API-36. The
families are listed here rather than read from the rows, so one whose section
has gone is still recognised, and a row of a family the list lacks is refused,
because every citation of it would otherwise go unread.

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
# The checklist's row ID families, one per section. Only IDs of these are read
# as citations. Listed rather than read from the rows: a family read from them
# vanished with its section, so with no TC row left TC-2 was not an ID at all,
# and the plan could send a person to it with this check green. A family stays
# listed after its section goes for that reason. Listing them is also what
# keeps IDs of other families out: matching every ID of the shape would read
# a licence such as PSF-2.0 as a checklist row that was "no longer left".
FAMILIES = {"AV", "BG", "DL", "DM", "ED", "EX", "KB", "PF", "SC", "SF", "ST", "TC", "TT"}
# An ID wherever the plan writes one. The tail of a longer ID such as FR-DEV-06
# is not read as one.
CITED = re.compile(r"(?<![A-Z]-)\b([A-Z]{2,3})-\d+\b")


def citation_problems(ids, plan_text):
    """The row IDs `plan_text` cites, and what is wrong with them given `ids`."""
    # IDs are read whole: a cited SF-16 must never pass as the row SF-1.
    cited = {m.group(0) for m in CITED.finditer(plan_text)
             if m.group(1) in FAMILIES}
    if not cited:
        return cited, [
            "  the release plan cites no checklist row, so its pre-tag device "
            "step is gone"
        ]
    prefixes = {i.split("-")[0] for i in ids}
    return cited, [
        f"  {PLAN.name} cites {rid}, which is not a row here"
        + ("" if rid.split("-")[0] in prefixes
           else f", and no {rid.split('-')[0]} row is left at all")
        for rid in sorted(cited - ids)
    ]


def unknown_families(ids):
    """The families `ids` use that FAMILIES does not list, sorted."""
    return sorted({i.split("-")[0] for i in ids} - FAMILIES)


def main():
    # `if`, not assert: python -O strips asserts, and a refusal must never
    # pass by not running.
    if not any("SF-16" in p for p in citation_problems({"SF-1"}, "run SF-16")[1]):
        sys.exit("FAIL self-check: a cited SF-16 was read as the row SF-1")
    if not citation_problems({"SF-1"}, "no rows")[1]:
        sys.exit("FAIL self-check: a plan citing no row was accepted")
    if citation_problems({"SF-1", "SF-16"}, "SF-1 and SF-16")[1]:
        sys.exit("FAIL self-check: a plan citing only existing rows was refused")
    if not any("TC-2" in p for p in citation_problems({"SF-1"}, "SF-1 and TC-2")[1]):
        sys.exit("FAIL self-check: a cited TC-2 passed once no TC row was left")
    if citation_problems({"SF-1"}, "SF-1, SHA-256 and UTF-8")[1]:
        sys.exit("FAIL self-check: SHA-256 or UTF-8 was read as a row ID")
    if not any("SF-3" in p for p in citation_problems({"SF-1"}, "SF-1-SF-3")[1]):
        sys.exit("FAIL self-check: the far end of a range SF-1-SF-3 was not read")
    if citation_problems({"SF-1"}, "SF-1, FR-DEV-06, GPL-2 and BSD-3")[1]:
        sys.exit("FAIL self-check: FR-DEV-06, GPL-2 or BSD-3 was read as a row ID")
    if citation_problems({"SF-1"}, "SF-1 under PSF-2.0, EPL-2.0, ADR-001 and API-36")[1]:
        sys.exit("FAIL self-check: PSF-2.0, EPL-2.0, ADR-001 or API-36 was read as a row ID")
    if unknown_families({"SF-1", "NW-1", "NW-2"}) != ["NW"]:
        sys.exit("FAIL self-check: a row of a family FAMILIES does not list was not named")
    if unknown_families({"SF-1", "TC-2"}):
        sys.exit("FAIL self-check: rows of listed families were refused")

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
    unknown = unknown_families(ids)
    # The plan is not judged against an incomplete list: its citations of the
    # missing family are invisible, and a plan citing only those would be
    # told it cites nothing.
    cited, cite_problems = (set(), []) if unknown else citation_problems(
        ids, PLAN.read_text(encoding="utf-8"))

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
    if unknown:
        print("FAIL the checklist has rows of a family this check does not list: "
              + ", ".join(unknown))
        print(
            "  -> add it to FAMILIES in scripts/check-checklist-totals.py: until "
            "then the release plan's citations of it are not read, so one naming "
            "a row that is gone would pass"
        )
    if problems or cite_problems or unknown:
        return 1

    print(
        f"ok      the checklist accounts for all {rows_in_file} rows across "
        f"{len(stated)} sections, and the {len(cited)} rows the release plan cites"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
