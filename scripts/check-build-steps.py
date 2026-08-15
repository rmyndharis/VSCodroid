#!/usr/bin/env python3
"""Keep what CI runs in agreement with what this repository declares.

    check-build-steps.py

Four assertions, all about scripts nobody notices are missing until an app is
built without them, or shipped without them having run:

  * every shell script a workflow runs is mentioned in CONTRIBUTING.md, so
    following the documentation produces the tree CI produces;
  * every shell script the PR build's asset-preparation job runs is also run by
    build-all.sh, so the "run them all at once" shortcut is not a shorter path
    to a different tree. Scoped to that one job on purpose: other jobs in the
    same workflow run things -- a device test harness, for one -- that
    build-all.sh has no business invoking;
  * every scripts/test-*.js runs in both lint.yml and release.yml, so a
    self-check cannot be added to the tree and then run by nothing;
  * every scripts/check-*.py is invoked by something -- a workflow, a build
    script or the Gradle build. Not "by both workflows", which is right for the
    self-checks and wrong here: two of the no-argument checkers deliberately run
    in build.yml rather than lint.yml because they read an assets tree lint does
    not build, and the rest are invoked with arguments from a script or from
    Gradle. The answerable question for this family is whether anything runs
    them at all.

The first two are about `.sh` and say so. The third is deliberately not folded
into them: those two pair three sources -- the workflow, build-all.sh and
CONTRIBUTING.md -- and the JavaScript self-checks have no build-all.sh
equivalent, so widening the shell pattern to reach them would leave two thirds of
that rule vacuous while the output went on claiming a coverage it was no longer
performing. A two-set comparison over a glob is the shape of the omission, so it
is written as one.

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

# A LIVE invocation, not a mention and not a disabled one. CONTRIBUTING.md is
# matched on the bare name above because being described there is the whole point;
# a workflow has to actually run the thing. Two ways that went wrong, both
# measured, both on the same script -- lint.yml carries a paragraph explaining why
# test-dns-proxy.js matters, so a substring test called it covered with its `node`
# line deleted; and matching raw text called it covered with the line commented
# out. executable_lines() closes the second, this pattern the first.
RUNS_SELFCHECK = re.compile(r"node\s+scripts/(test-[\w.-]+\.js)")

# Every checker in the tree has to be run by something. The rule above asks
# whether a named self-check is wired; this one asks the prior question -- whether
# anything at all would notice the file existing. Measured: a scripts/check-*.py
# could be added, or have its only invocations deleted, with nothing going red,
# because no rule in this file looked at that family.
#
# Deliberately NOT "both lint.yml and release.yml", which is what the .js rule
# requires. The four no-argument checkers split on purpose:
#     check-build-steps.py, check-local-network-permission.py   lint + release
#     check-library-attribution.py, check-welcome-claims.py     build + release
# The second pair reads the assets tree, which lint.yml does not build -- it
# writes stubs -- so demanding lint would demand attributing a tree with nothing
# in it. The others take arguments and are invoked from a script or from Gradle
# rather than from a workflow at all. So the honest requirement is that something
# runs them, not which job does.
RUNNERS_OF_CHECKERS = (".github/workflows/*.yml", "scripts/*.sh",
                       "android/app/build.gradle.kts")

# Both, not either. lint.yml runs on pull_request only and release.yml on tags,
# so a self-check in just one of them is unrun on half the paths that reach a
# user -- and a hotfix pushed straight to main and tagged never sees lint at all.
SELFCHECK_WORKFLOWS = ("lint.yml", "release.yml")


def executable_lines(path) -> str:
    """The file's text with whole-line comments dropped.

    A workflow's `run:` blocks are shell, and a line whose first non-space
    character is `#` is a comment in both YAML and shell; `//` covers the Gradle
    build. Matching raw text counted a DISABLED invocation as a live one, which is
    the likelier of the two failures this rule guards -- measured: replacing
    `node scripts/test-dns-proxy.js` with
    `# node scripts/test-dns-proxy.js  (disabled: flaky)` left the rule green,
    while deleting the line correctly turned it red. Silencing a check in place
    with a note is what happens to one that has started failing.

    A trailing `# note` after a real command is deliberately left alone: that line
    still runs.
    """
    return "\n".join(
        line for line in path.read_text().splitlines()
        if not line.lstrip().startswith(("#", "//"))
    )


def named_by(pattern, path):
    return set(pattern.findall(executable_lines(path)))


def job_body(path, job):
    """The lines of one job in a workflow, by indentation.

    Only the job that prepares assets is compared against build-all.sh. Reading
    the whole file was the first version of this and it was too broad: the same
    workflow also runs a device test harness, which build-all.sh has no business
    invoking, and the check failed on a change that was correct. Scoping to the
    job is the fix; exempting the script by name would have muted the rule
    instead of correcting it.
    """
    lines = path.read_text().splitlines()
    start = next((i for i, line in enumerate(lines)
                  if line.startswith(f"  {job}:")), None)
    if start is None:
        return None
    end = next((i for i in range(start + 1, len(lines))
                if re.match(r"^  \w[\w-]*:", lines[i])), len(lines))
    return "\n".join(lines[start:end])


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
    # The asset-preparation job specifically, not the whole workflow.
    build_job = job_body(WORKFLOWS / "build.yml", "build")
    if build_job is None:
        print("  FAIL   no 'build' job in build.yml; the job layout changed")
        return 1
    ci_pr = set(IN_WORKFLOW.findall(build_job))

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

    # A self-check that nothing runs is indistinguishable from no self-check, and
    # costs more, because its presence in the tree reads as coverage. The glob is
    # the left-hand side here, so an empty one would make the comparison below
    # vacuously true in exactly the way the ci_all guard above exists to prevent:
    # "all covered" prints identically whether every script is wired or the glob
    # matched nothing at all.
    selfchecks = {p.name for p in (ROOT / "scripts").glob("test-*.js")}
    if not selfchecks:
        print("  FAIL   no scripts/test-*.js matched; the glob stopped matching")
        return 1

    unrun = {}
    for wf_name in SELFCHECK_WORKFLOWS:
        wf = WORKFLOWS / wf_name
        if not wf.is_file():
            print(f"  FAIL   {wf_name} is missing; the workflow layout changed")
            return 1
        missing = selfchecks - set(RUNS_SELFCHECK.findall(executable_lines(wf)))
        if missing:
            unrun[wf_name] = missing

    if unrun:
        for wf_name, missing in sorted(unrun.items()):
            report(f"JavaScript self-checks {wf_name} does not run",
                   missing, wf_name,
                   "A self-check nothing runs is the same as no self-check.")
        failed = True
    else:
        print(f"  ok     all {len(selfchecks)} JavaScript self-checks run in "
              f"{' and '.join(SELFCHECK_WORKFLOWS)}")

    checkers = {p.name for p in (ROOT / "scripts").glob("check-*.py")}
    if not checkers:
        print("  FAIL   no scripts/check-*.py matched; the glob stopped matching")
        return 1

    runners = [p for pattern in RUNNERS_OF_CHECKERS
               for p in sorted(ROOT.glob(pattern)) if p.is_file()]
    if not runners:
        print("  FAIL   nothing to search for invocations; the layout changed")
        return 1

    wired = set()
    for runner in runners:
        text = executable_lines(runner)
        wired |= {name for name in checkers if name in text}

    unwired = checkers - wired
    if unwired:
        report("checkers in scripts/ that nothing invokes", unwired,
               "a workflow, a build script, or android/app/build.gradle.kts",
               "A checker nothing runs is the same as no checker, and costs more:"
               " its presence in the tree reads as coverage.")
        failed = True
    else:
        print(f"  ok     all {len(checkers)} scripts/check-*.py are invoked, "
              f"across {len(runners)} files that could run one")

    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
