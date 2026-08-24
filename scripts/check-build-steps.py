#!/usr/bin/env python3
"""Keep what CI runs in agreement with what this repository declares.

    check-build-steps.py

Six assertions, all about scripts nobody notices are missing until an app is
built without them, or shipped without them having run:

  * every shell script a workflow runs is mentioned in CONTRIBUTING.md, so
    following the documentation produces the tree CI produces;
  * every shell script the PR build's asset-preparation job runs is also run by
    build-all.sh, so the "run them all at once" shortcut is not a shorter path
    to a different tree. Scoped to that one job on purpose: other jobs in the
    same workflow run things -- a device test harness, for one -- that
    build-all.sh has no business invoking;
  * every shell script that job runs is also run by release.yml's build-release
    job, so the build that gets signed and published is not a thinner version of
    the one every pull request gets. One direction: the release path runs more,
    on purpose;
  * every scripts/test-*.js runs in both lint.yml and release.yml, so a
    self-check cannot be added to the tree and then run by nothing;
  * lint.yml runs them on the Node major the APK ships, the one
    build-native-addons.sh pairs the addons against, so a self-check green on
    a runner is a self-check green on the device's runtime;
  * every scripts/check-*.py is invoked by something -- a workflow, a build
    script or the Gradle build. Not "by both workflows", which is right for the
    self-checks and wrong here: two of the no-argument checkers deliberately run
    in build.yml rather than lint.yml because they read an assets tree lint does
    not build, and the rest are invoked with arguments from a script or from
    Gradle. The answerable question for this family is whether anything runs
    them at all.

The first three are about `.sh` and say so. The JavaScript rule is deliberately
not folded into them: those three pair four sources, build.yml, release.yml,
build-all.sh and CONTRIBUTING.md, and the JavaScript self-checks have no
build-all.sh equivalent, so widening the shell pattern to reach them would leave
that rule vacuous for them while the output went on claiming a coverage it was no
longer performing. A two-set comparison over a glob is the shape of the omission,
so it is written as one.

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
# requires. Where a checker runs follows what it reads, and the family does not
# split evenly: most take no arguments and run from lint.yml and release.yml,
# but check-library-attribution.py and check-welcome-claims.py read the assets
# tree, which lint.yml does not build -- it writes stubs -- so they run from
# build.yml and release.yml instead, and the ones that take arguments are
# invoked from a download script or from Gradle rather than from a workflow at
# all. Demanding any one job would fail a checker that is correctly placed
# elsewhere, so the honest requirement is that something runs each of them.
RUNNERS_OF_CHECKERS = (".github/workflows/*.yml", "scripts/*.sh",
                       "android/app/build.gradle.kts")

# Both, not either. lint.yml runs on pull requests and on pushes to main, and
# release.yml on tags, so a self-check in only one of them is unrun on the path
# it is absent from -- and the tag path is the one whose output reaches a user.
SELFCHECK_WORKFLOWS = ("lint.yml", "release.yml")

# The Node the self-checks run under has to be the major that ships. The device
# runtime is the Termux nodejs-lts that build-native-addons.sh names in
# NODE_VERSION, and the JavaScript under test (server.js, process-monitor.js,
# dns-proxy.js and the bundled extensions) runs on nothing else, so a self-check
# green on another major says nothing about the device: lint.yml ran the nine
# on 20 while every APK carried 24. The major only, for the reason
# build-native-addons.sh compares majors: the patch level is Termux's to move.
#
# All three workflows that set up Node: lint.yml and release.yml run the nine
# self-checks, and build.yml and release.yml run the download scripts and
# build-native-addons.sh through npm, so one major across all of them.
NODE_MAJOR_SOURCE = ROOT / "scripts/build-native-addons.sh"
NODE_MAJOR_WORKFLOWS = ("lint.yml", "build.yml", "release.yml")
SHIPPED_NODE = re.compile(r'^NODE_VERSION="\$\{NODE_VERSION:-(\d+)\.', re.M)
PINNED_NODE = re.compile(r'node-version:\s*"?(\d+)')


class Unreadable(Exception):
    """A file this script must read cannot be read or decoded."""


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

    Returns None, and says why, for a file that cannot be read or decoded. The
    checker rule reads every shell script and the Gradle build, which this script
    never touched before, so it now meets files it had no exposure to: a binary-ish
    .sh, or one saved in a non-UTF-8 encoding. Returning "" instead would make such
    a file look empty and mark its invocations missing, which is a lie in the other
    direction.
    """
    try:
        text = path.read_text()
    except (OSError, UnicodeDecodeError) as e:
        raise Unreadable(f"{path} could not be read  {e}") from e
    kept = [line for line in text.splitlines()
            if not line.lstrip().startswith(("#", "//"))]
    return "\n".join(drop_disabled_steps(kept))


def drop_disabled_steps(lines: list) -> list:
    """Remove workflow step blocks whose `if:` is a literal false.

    Commenting a command out is one of the two canonical ways to silence a CI step;
    `if: false` is the other, and it leaves the `run:` line untouched, so a rule
    that only strips comments still counts a step GitHub Actions will skip. A step
    block runs from one `- ` bullet at step indentation to the next.

    Bounded on purpose, and the bound is the point: only the literal form is caught.
    `if: ${{ false }}`, or an expression that happens to be false at run time, is
    not, and cannot be without evaluating GitHub's expression language. No workflow
    here uses any form today -- measured, zero occurrences -- so this changes
    nothing in the current tree and only starts acting when someone disables a step
    that way.
    """
    out, block, in_block = [], [], False
    for line in lines + ["\x00sentinel"]:
        starts_step = line.lstrip().startswith("- ") and line.startswith("      ")
        if starts_step or line == "\x00sentinel":
            if in_block and not any(
                    re.match(r"\s*if:\s*false\s*$", b) for b in block):
                out.extend(block)
            block, in_block = [], True
        if line != "\x00sentinel":
            (block if in_block else out).append(line)
    return out


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
    # Comment-stripped, like every other reader in this file. It was the one that
    # was not, and the asymmetry was a false failure waiting: `built` came from
    # named_by() and so ignored a commented-out line in build-all.sh, while ci_pr
    # came from this raw text and still counted the matching commented-out line in
    # the workflow. Disabling a step consistently in BOTH files therefore reported
    # "scripts the PR build runs that build-all.sh does not" -- measured, on
    # download-python.sh.
    lines = executable_lines(path).splitlines()
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
    # One place to turn an unreadable input into a stated failure. Every reader in
    # this file goes through executable_lines(), so a file that cannot be decoded
    # reports which file rather than a traceback from inside a comprehension.
    try:
        return _main()
    except Unreadable as e:
        print(f"  FAIL   {e}")
        return 1


def _main() -> int:
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

    # Its counterpart on the tag path. The two prepare the same assets and are
    # maintained by hand as two lists, which is the arrangement that drifts.
    release_job = job_body(WORKFLOWS / "release.yml", "build-release")
    if release_job is None:
        print("  FAIL   no 'build-release' job in release.yml; the job layout changed")
        return 1
    ci_release = set(IN_WORKFLOW.findall(release_job))
    if not ci_release:
        print("  FAIL   no scripts found in release.yml's build-release job; "
              "the pattern stopped matching")
        return 1

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

    # The third consumer of the same list, and nothing paired it with the first
    # two. Every rule above compares a workflow against a document or against
    # build-all.sh, so a step present in one workflow and absent from the other
    # satisfied all of them: measured by deleting the download-python.sh step from
    # release.yml alone, which left every line green because build.yml still named
    # the script and CONTRIBUTING.md still documented it. What ships from that is
    # a signed APK with no Python in it, discovered by the first user who types
    # python3.
    #
    # One direction only, and the asymmetry is real rather than an omission: the
    # release job legitimately runs more, the toolchain downloads and the ZIP
    # packaging have no place on a pull request, so requiring equality would
    # need a hand-written exemption list, which is the fourth copy this file
    # exists to avoid. What escapes: a script that only ever ran on the tag path
    # being dropped from it. Nothing else names that set, so nothing can tell.
    unreleased = ci_pr - ci_release
    if unreleased:
        report("scripts the PR build runs that the release build does not",
               unreleased, "release.yml's build-release job",
               "A tag must not produce a thinner tree than a pull request.")
        failed = True
    else:
        print(f"  ok     the release build runs all {len(ci_pr)} scripts the PR "
              f"build runs, plus {len(ci_release - ci_pr)} of its own")

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

    shipped = SHIPPED_NODE.search(NODE_MAJOR_SOURCE.read_text())
    if not shipped:
        print(f"  FAIL   {NODE_MAJOR_SOURCE.name} no longer names NODE_VERSION; "
              "the pattern stopped matching")
        return 1
    for wf_name in NODE_MAJOR_WORKFLOWS:
        pinned = PINNED_NODE.findall(executable_lines(WORKFLOWS / wf_name))
        # Every pin in the file, not the first: the self-checks run in one job
        # today and a second setup-node on another major would be the drift
        # this asks about, arriving one job over.
        wrong = sorted(set(pinned) - {shipped.group(1)})
        if not pinned or wrong:
            print(f"  FAIL   {wf_name} sets up Node "
                  f"{', '.join(wrong) or 'nothing it pins'}, and the APK ships Node "
                  f"{shipped.group(1)} ({NODE_MAJOR_SOURCE.name} NODE_VERSION). "
                  f"Pin setup-node there to the major that ships.")
            failed = True
        else:
            print(f"  ok     {wf_name} sets up Node {shipped.group(1)}, the major that ships")

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
        # An invocation, not a mention -- the same distinction the self-check rule
        # above is built on, and it was missing here. A plain `name in text` counted
        # a checker as wired when its only appearance was inside a hashFiles cache
        # key: measured, a checker with zero invocations reported "all 9 invoked".
        # Requiring `python3` on the same line covers all three real forms --
        # `python3 scripts/x.py` in a workflow, `python3 "$SCRIPT_DIR/x.py"` in a
        # shell script, and `"python3", "scripts/x.py"` in the Gradle build -- and
        # excludes a bare filename in a key, a comment or a doc string.
        wired |= {name for name in checkers
                  for line in text.splitlines()
                  if name in line and "python3" in line}

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
