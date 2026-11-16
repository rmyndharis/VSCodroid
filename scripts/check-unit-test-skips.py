#!/usr/bin/env python3
"""Require Gradle JUnit reports with no unexpected skipped testcases."""

import argparse
from pathlib import Path
import sys
import xml.etree.ElementTree as ET


PACK_SKIPS = {
    ("com.vscodroid.setup.ToolchainCppPackTest", name) for name in (
        "the manifest the script writes asks for what the manager honours()",
        "installing it makes every command name resolve to a program and starts nothing by hand()",
        "uninstalling it leaves no file of its own behind()",
    )
} | {("com.vscodroid.setup.ToolchainRegistryTest$CatalogTest",
      "no recorded size is under the tree its pack ships()")}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("results", type=Path, help="Gradle test-results directory")
    parser.add_argument("--allow-pack-skips", action="store_true",
                        help="allow the four pack-dependent cases in pull-request builds")
    args = parser.parse_args()
    reports = sorted(args.results.glob("TEST-*.xml"))
    if not reports:
        print(f"  FAIL   no JUnit XML reports in {args.results}")
        return 1

    allowed = PACK_SKIPS if args.allow_pack_skips else set()
    failed = False
    total = skipped = 0
    for report in reports:
        try:
            suite = ET.parse(report).getroot()
            cases = suite.findall("testcase")
            actual_skips = sum(case.find("skipped") is not None for case in cases)
            if (suite.tag != "testsuite" or int(suite.get("tests", "-1")) != len(cases)
                    or int(suite.get("skipped", "-1")) != actual_skips):
                raise ValueError("expected a Gradle testsuite with matching tests/skipped counts")
            if any(not case.get("classname") or not case.get("name") for case in cases):
                raise ValueError("a testcase has no classname or name")
        except (OSError, ET.ParseError, ValueError) as error:
            print(f"  FAIL   {report}: {error}")
            failed = True
            continue
        total += len(cases)
        skipped += actual_skips
        for case in cases:
            if case.find("skipped") is None:
                continue
            identity = (case.get("classname"), case.get("name"))
            if identity in allowed:
                print(f"  ok     expected pack skip: {identity[0]}.{identity[1]}")
            else:
                print(f"  FAIL   unexpected skip: {identity[0]}.{identity[1]}")
                failed = True
    if not total:
        print("  FAIL   no testcases were reported")
        failed = True
    print(f"  {'FAIL' if failed else 'ok  '}   {total} testcases, {skipped} skipped, "
          f"{len(reports)} reports checked")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
