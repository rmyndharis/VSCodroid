#!/usr/bin/env python3
"""Keep the documented build sequence in agreement with the one CI runs.

    check-build-steps.py

Two assertions, both about scripts nobody notices are missing until an app is
built without them:

  * every script a workflow runs is mentioned in CONTRIBUTING.md, so following
    the documentation produces the tree CI produces;
  * every script the PR build runs is also run by build-all.sh, so the
    "run them all at once" shortcut is not a shorter path to a different tree.

The lists are read from the files rather than restated here. A hand-maintained
fourth copy would be one more thing to drift, which is the defect this exists to
catch: the documentation lost download-musl-loader.sh and build-glibc-shim.sh
while both ran in CI, and the resulting APK built cleanly and shipped a Claude
Code CLI that could not start.

Scripts that only prepare optional extras may be documented without being in
build-all.sh -- the direction that matters is that nothing CI needs is absent.
"""

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
WORKFLOWS = ROOT / ".github/workflows"
CONTRIBUTING = ROOT / "CONTRIBUTING.md"
BUILD_ALL = ROOT / "scripts/build-all.sh"

# `bash scripts/foo.sh` in a workflow and `"$SCRIPT_DIR/foo.sh"` in build-all.sh
# both name a script being run. CONTRIBUTING.md is matched on the bare name
# instead: some of these are described in the script table without ever being a
# command a contributor types -- build-vscode-oss.sh takes half an hour on an
# arm64 runner -- and demanding an invocation line would document a step nobody
# should follow.
IN_WORKFLOW = re.compile(r"bash\s+scripts/([\w-]+\.sh)")
IN_BUILD_ALL = re.compile(r"\$SCRIPT_DIR/([\w-]+\.sh)")


def named_by(pattern, path):
    return set(pattern.findall(path.read_text()))


def mentioned_in(path, names):
    text = path.read_text()
    return {name for name in names if name in text}


def report(label, missing, where, fix):
    print(f"  FAIL   {label}")
    for name in sorted(missing):
        print(f"           {name}")
    print(f"         Add it to {where}. {fix}")


def main() -> int:
    if not WORKFLOWS.is_dir():
        print(f"  FAIL   no workflows at {WORKFLOWS}")
        return 1

    per_workflow = {
        wf.name: named_by(IN_WORKFLOW, wf)
        for wf in sorted(WORKFLOWS.glob("*.yml"))
    }
    ci_all = set().union(*per_workflow.values()) if per_workflow else set()
    ci_pr = per_workflow.get("build.yml", set())

    # An empty left-hand side would make both assertions below vacuously true,
    # which is the failure this whole script exists to prevent -- a check that
    # cannot fail reads exactly like a check that passed.
    if not ci_all:
        print("  FAIL   no scripts found in any workflow; the pattern stopped matching")
        return 1

    documented = mentioned_in(CONTRIBUTING, ci_all)
    built = named_by(IN_BUILD_ALL, BUILD_ALL)

    failed = False

    undocumented = ci_all - documented
    if undocumented:
        report("scripts CI runs that CONTRIBUTING.md never mentions",
               undocumented, "CONTRIBUTING.md",
               "Following the documentation must produce the tree CI produces.")
        failed = True
    else:
        print(f"  ok     all {len(ci_all)} scripts CI runs are documented")

    unbuilt = ci_pr - built
    if unbuilt:
        report("scripts the PR build runs that build-all.sh does not",
               unbuilt, "scripts/build-all.sh",
               "The shortcut must not produce a different tree.")
        failed = True
    else:
        print(f"  ok     build-all.sh runs all {len(ci_pr)} scripts the PR build runs")

    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
