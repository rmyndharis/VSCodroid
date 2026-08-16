#!/usr/bin/env python3
"""Every workflow step must be a step GitHub Actions can run.

    check-workflow-steps.py

Valid YAML is not the same question as a valid workflow, and the gap between
them is where an editing mistake lives. A step carrying only a `name` parses
without complaint, loads into a dict without complaint, and is rejected by the
runner at dispatch time with "Required property is missing: uses".

Measured 2026-08-16, and the reason this file exists: an edit to
build-vscode-oss.yml left `- name: Publish the server tarball` twice, once as a
bare name and once as the real step. `yaml.safe_load` passed on all six
workflows, the pull request build was green, and nothing could have caught it,
because that workflow is `workflow_dispatch` only and runs perhaps once per VS
Code bump. The failure would have arrived a month later, on the one job whose
output every app build depends on.

Two assertions:

  * every step has exactly one of `uses` or `run`. Neither is the bare-name
    case above. Both at once is also rejected by the runner;
  * every `uses` reference is pinned to a full 40-character commit SHA. A
    floating tag is resolved at run time by whoever controls it, and these
    workflows publish the release assets, including the digest manifest that
    non-Play toolchain installs verify against.

Deliberately not a general workflow linter. `actionlint` is that, and it is a
Go binary this repository would have to fetch, pin and verify to run one check.
These two questions are the ones that have actually gone wrong here.
"""

import pathlib
import re
import sys

import yaml

ROOT = pathlib.Path(__file__).resolve().parent.parent
WORKFLOWS = ROOT / ".github/workflows"
PINNED = re.compile(r"@[0-9a-f]{40}$")


def steps_of(doc):
    """Yield `(job_name, index, step)` for every step in the document."""
    for job_name, job in (doc.get("jobs") or {}).items():
        for i, step in enumerate(job.get("steps") or []):
            yield job_name, i, step


def main() -> int:
    failures = []
    checked_files = 0
    checked_steps = 0

    for path in sorted(WORKFLOWS.glob("*.yml")):
        checked_files += 1
        doc = yaml.safe_load(path.read_text())
        for job, i, step in steps_of(doc):
            checked_steps += 1
            where = f"{path.name}: job {job}, step {i}"
            label = step.get("name") or step.get("uses") or step.get("run", "")
            label = str(label).splitlines()[0][:60]

            has = [k for k in ("uses", "run") if k in step]
            if len(has) != 1:
                found = " and ".join(has) if has else "neither"
                failures.append(
                    f"{where} ({label!r}) has {found}; a step needs exactly one "
                    f"of uses or run"
                )
                continue

            if "uses" in step and not PINNED.search(str(step["uses"])):
                failures.append(
                    f"{where} ({label!r}) uses {step['uses']}, which is not "
                    f"pinned to a 40-character commit SHA"
                )

    for f in failures:
        print(f"::error::{f}")
    if failures:
        print(f"  FAILED  {len(failures)} problem(s) across {checked_steps} steps")
        return 1

    # The counts are printed on success too. A checker that says only "ok" is
    # indistinguishable from one whose glob stopped matching anything.
    print(f"  ok     all {checked_steps} steps in {checked_files} workflows "
          f"have exactly one of uses/run")
    print(f"  ok     every uses reference is pinned to a full commit SHA")
    return 0


if __name__ == "__main__":
    sys.exit(main())
