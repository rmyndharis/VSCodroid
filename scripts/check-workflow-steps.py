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
output every release build depends on.

Six assertions:

  * at least one workflow, carrying at least one step, was actually read. A
    checker that finds nothing reports nothing, and CI reads the exit code
    rather than the log, so an empty glob is indistinguishable from a clean
    tree without this;
  * every step has exactly one of `uses` or `run`. Neither is the bare-name
    case above. Both at once is also rejected by the runner;
  * every external `uses` reference is pinned to a full 40-character commit SHA;
    local reusable workflows must exist and use the caller's revision. A
    floating tag is resolved at run time by whoever controls it, and these
    workflows publish the release assets, including the digest manifest that
    non-Play toolchain installs verify against;
  * a workflow that can be started by hand AND by something else must not hand
    the hand-started run a write-scoped token. See below;
  * the steps in REQUIRED_STEPS are still there. They only check something, so
    a workflow that has lost one parses, loads and runs green, and none of the
    rules above can see an absence;
  * a publish never reads the tarball cache: build-vscode-oss.yml hands the
    shared build `fresh: true`, and that build skips its cache when given it.

Both extensions are read. GitHub Actions runs `.yml` and `.yaml` alike, and a
checker that knows only one of them would go on printing a healthy count while
the file it could not see carried exactly the defect this exists to catch.

Deliberately not a general workflow linter. `actionlint` is that, and it is a
Go binary this repository would have to fetch, pin and verify to run one check.
These questions are the ones that have actually gone wrong here.

## Why the fourth one

`release.yml` builds and signs, and then publishes: it creates the release and
attaches the toolchain ZIPs and the `toolchains.sha256` that every non-Play
install verifies against, under `releases/latest`. That pointer is read on every
sideloaded install, including by devices already carrying a toolchain, so a
release published off an arbitrary branch does not merely appear in a list. It
moves what those devices resolve.

That workflow now also takes `workflow_dispatch`, so the signed build can be
exercised without publishing one -- and the only thing separating the two is a
job-level `if:` on one job. Deleting that line is a one-character-looking edit
whose consequence is invisible until a dispatched run has published.

So: in a workflow declaring `workflow_dispatch` alongside at least one other
trigger, a job whose token can write to this repository must be gated on
`github.event_name == '<event>'`, that gate must not name `workflow_dispatch`
at all, and no `||` may stand beside the test. An allowlist, in other words,
and `!= 'workflow_dispatch'` is refused even though it is correct as written:
a denylist admits the next trigger added to the workflow without anyone
deciding to, and this is the line whose quiet loosening the rule exists to
prevent. `!= 'schedule'` and `== 'push' || true` are refused for the same
reason; both name the event and neither holds a dispatched run back.

The subject is the token scope rather than the step, because a rule that
recognised publishing by naming `softprops/action-gh-release`, or by grepping
for `gh release`, stops matching the day the same thing is written another way,
and a rule that stops matching prints exactly what a clean tree prints. Nothing
can create a release or attach an asset to one without `contents: write`.

`contents` and not any write: `pages.yml` holds `pages: write` and deploys the
documentation site from a hand-started run on purpose, which is a different
question and not this one.

A workflow whose ONLY trigger is `workflow_dispatch` is left alone, and that is
the point of the "alongside" clause rather than an exemption:
`build-vscode-oss.yml` is manual and publishing the server tarball is what it is
for. Gating it on an event it can never see would fail every run.

Gating on `github.event_name`, not on `github.ref`: a workflow_dispatch can be
aimed at a tag ref, so a ref test alone is true for a dispatched run and would
let one publish. A ref test beside the event test is fine and release.yml
carries one; a ref test instead of it is the mistake this rule refuses.

## Why the fifth and sixth

Deleting "Verify the server tarball" from codeoss-build.yml left this checker and
check-build-steps.py both passing: every remaining step still has one of `uses`
or `run`, and no script went unnamed. What the step ran over the finished
tarball, the pin, branding, tree and patch checks, was then gone, and for a
tarball restored from the cache it was the only place they ran. The same holds
for a gate in release.yml, and for the step that moves the `server-<version>`
tag to the commit it built, which check-server-published.py reads.
REQUIRED_STEPS names them. It is a list of strings on purpose: renaming a step
means renaming it there, and the failure says so, which is cheaper than a gate
that disappears without anyone noticing.

The tarball cache is keyed on the build's inputs, not on a build having run. A
dispatch of build-vscode-oss.yml therefore restored a tarball an earlier run
built and published it under notes that name the dispatched run, with the
install and build steps skipped and every job green. The caller now asks for a
fresh build and the build skips its cache when asked. Neither half is enough
alone, and a deleted `fresh: true` line changes nothing else, so both are held
here.
"""

import copy
import pathlib
import re
import sys

# PyYAML is a dependency this repository does not install anywhere, and that is
# a deliberate trade rather than an oversight. The two questions below are
# structural (jobs -> steps -> the keys of each step), and a hand-rolled reader
# for them would have to be right about block scalars: every `run: |` body in
# these workflows contains lines that look like keys, so a line-based parser
# would read a shell comment as a step. Getting that subtly wrong is worse than
# a missing module, because the failure is a checker that quietly stops seeing
# things.
#
# What is fixed here is the report. Ubuntu runner images carry python3-yaml
# (cloud-init depends on it), so this has never failed in CI, and it failed on
# a developer's machine as a traceback with no line saying what to install --
# in the one gate that enforces SHA-pinning on every `uses:`, so the gate whose
# absence is least visible was also the one that explained itself worst.
try:
    import yaml
except ModuleNotFoundError:
    print("::error::check-workflow-steps.py needs PyYAML, which is not "
          "installed. `pip install pyyaml`, or `apt-get install python3-yaml` "
          "on a Debian-family host. Nothing in this repository installs it: CI "
          "runs on images that already carry it.", file=sys.stderr)
    sys.exit(1)

ROOT = pathlib.Path(__file__).resolve().parent.parent
WORKFLOWS = ROOT / ".github/workflows"
PINNED = re.compile(r"@[0-9a-f]{40}$")
LOCAL_WORKFLOW = re.compile(r"^\./\.github/workflows/[^/]+\.ya?ml$")
# The one shape of gate the fourth rule accepts: the event named, by equality.
ALLOWLIST_GATE = re.compile(r"github\.event_name\s*==\s*'[a-z_]+'")

# Steps that only check something, on the path that publishes the server and the
# app, matched by name in any job. Delete one and the workflow still loads and
# runs green. Renaming one means renaming it here too.
REQUIRED_STEPS = {
    "codeoss-build.yml": (
        "Verify the server tarball",
    ),
    "build-vscode-oss.yml": (
        "Point the tag at the commit that was built",
    ),
    "release.yml": (
        "Check the tag matches the app version",
        "Check the published server was built from the same inputs",
        "Check the documented build steps match the workflows",
        "Check every workflow step is runnable and pinned",
        "Check the Gradle distribution is pinned to a digest",
        "Fetch Code - OSS server",
        "Verify assets",
        "Run unit tests",
        "Run lint",
        "Compile the instrumented tests",
        "Decode keystore",
        "Check every shipped library is attributed",
        "Check every shipped toolchain library is attributed",
        "Refuse an asset the base module and a pack both ship",
        "Check the shipped permissions against the privacy policy",
        "Require every artifact, and report its size",
        "Generate checksums",
    ),
}


def top_level_or(expr: str) -> bool:
    """Whether `||` appears in `expr` outside every pair of parentheses.

    `||` binds loosest of all in GitHub's expression language, so one outside
    the parentheses turns whatever allowlist stands beside it into an
    alternative to it: `github.event_name == 'push' || true` names the event
    and admits everything. Inside parentheses it is somebody else's disjunction
    (`== 'push' && (startsWith(a) || startsWith(b))`) and the allowlist still
    holds over the whole. Innermost groups are removed until none are left,
    which is enough because the language has no other bracketing.
    """
    while True:
        inner = re.sub(r"\([^()]*\)", "", expr)
        if inner == expr:
            return "||" in expr
        expr = inner


def steps_of(doc):
    """Yield `(job_name, index, step)` for every step in the document."""
    for job_name, job in (doc.get("jobs") or {}).items():
        for i, step in enumerate(job.get("steps") or []):
            yield job_name, i, step


def missing_steps(doc, names):
    """The names in `names` that no step of `doc` carries."""
    present = {step.get("name") for _, _, step in steps_of(doc)}
    return [name for name in names if name not in present]


def without_step(doc, name):
    """A copy of `doc` with every step called `name` deleted, as an edit that drops a gate would."""
    cut = copy.deepcopy(doc)
    for job in (cut.get("jobs") or {}).values():
        if "steps" in job:
            job["steps"] = [s for s in job["steps"] if s.get("name") != name]
    return cut


def publish_builds_fresh(docs):
    """Complaints when a publish could be served from the tarball cache.

    Two halves, neither enough alone: build-vscode-oss.yml has to ask for a fresh
    build, and the shared build has to skip its cache when asked. `docs` maps
    workflow file names to parsed documents.
    """
    bad = []
    steps = steps_of(docs.get("codeoss-build.yml") or {})
    cache = next((step for _, _, step in steps if step.get("id") == "cache"), {})
    if "inputs.fresh" not in str(cache.get("if", "")):
        bad.append("codeoss-build.yml: the step with id `cache` is not skipped when "
                   "`inputs.fresh` is set, so a fresh build could still restore a cached tarball")
    jobs = (docs.get("build-vscode-oss.yml") or {}).get("jobs") or {}
    if ((jobs.get("build") or {}).get("with") or {}).get("fresh") is not True:
        bad.append("build-vscode-oss.yml: job build does not pass `fresh: true`, so a publish "
                   "could republish a cached tarball under notes that name this run")
    return bad


def valid_job_reference(ref):
    """Local workflows use the caller's revision; external workflows need a SHA."""
    if ref.startswith("./"):
        return bool(LOCAL_WORKFLOW.fullmatch(ref))
    return bool(PINNED.search(ref))


def triggers_of(doc):
    """The trigger names a workflow declares.

    `on` is the one key that cannot be read by its name here. YAML 1.1 resolves
    a bare `on` to the boolean True, which is what `yaml.safe_load` returns and
    what GitHub's own schema tolerates, so `doc.get("on")` finds nothing on
    every workflow in this tree. Both spellings are read rather than one, since
    a quoted `"on":` in a future file would come back under the string.
    """
    raw = doc.get("on", doc.get(True))
    if isinstance(raw, dict):
        return set(raw)
    if isinstance(raw, list):
        return set(raw)
    return {str(raw)} if raw else set()


def writes_contents(doc, job) -> bool:
    """Whether this job's GITHUB_TOKEN can write to the repository.

    Job-level `permissions` replaces the workflow-level block outright rather
    than merging with it, so the job's own is read first and the workflow's only
    when the job declares none.

    Neither declaring one is reported as write-capable, and that is the useful
    reading rather than the strict one: with no block anywhere the scope is
    whatever the repository's default workflow permissions happen to be, which
    is a setting outside the file and outside review. Every workflow here
    declares one, so this costs nothing today and starts acting the moment one
    stops.

    `contents` specifically. A job holding `pages: write` and nothing else can
    deploy a site and cannot touch a release, and pages.yml deploys the docs
    from a hand-started run on purpose.
    """
    perms = job.get("permissions", doc.get("permissions"))
    if perms is None:
        return True
    if isinstance(perms, str):          # `permissions: write-all` / `read-all`
        return perms != "read-all"
    return perms.get("contents") == "write"


def ungated_writers(path, doc):
    """`(complaints, write-capable jobs examined)` for one workflow.

    Jobs in `doc` that could write to the repository from a manual run.

    Only asked of a workflow that takes `workflow_dispatch` together with some
    other trigger. With that as its only trigger, writing is what the workflow
    is for: `build-vscode-oss.yml` is manual and publishing the server tarball
    is the whole of its job.

    The question is asked of the token scope rather than of the steps, and that
    is deliberate. A rule that recognised publishing by naming the action, or by
    grepping for `gh release`, goes quiet the day the mechanism is written a
    different way, and quiet is indistinguishable from clean. Nothing can create
    a release or attach an asset without `contents: write`, so the capability is
    the thing to look at.

    It does not cover a step reaching for a personal access token out of a
    secret, which needs no declared permission at all. Nothing here does that,
    and no structural check of this shape could see it.
    """
    triggers = triggers_of(doc)
    if "workflow_dispatch" not in triggers or len(triggers) < 2:
        return [], 0

    others = sorted(triggers - {"workflow_dispatch"})
    bad, writers = [], 0
    for job_name, job in (doc.get("jobs") or {}).items():
        if not writes_contents(doc, job):
            continue
        writers += 1
        gate = str(job.get("if") or "")
        # The event has to be named by equality, workflow_dispatch must not
        # appear at all, and no `||` may stand beside the test -- an allowlist
        # (`== 'push'`), never a denylist (`!= 'workflow_dispatch'`, or
        # `!= 'schedule'`, which names the event and holds nothing back). Both
        # read as correct today and they part company the moment a third
        # trigger is added above: the allowlist keeps refusing everything it
        # does not name, while the denylist admits the new one silently, which
        # is the shape of edit this rule exists for. It was a substring test
        # for `github.event_name`, which `!= 'schedule'` and `== 'push' || true`
        # both satisfy while admitting a dispatched run.
        if (not ALLOWLIST_GATE.search(gate) or "workflow_dispatch" in gate
                or top_level_or(gate)):
            bad.append(
                f"{path.name}: job {job_name} can write to this repository "
                f"(contents: write), and the workflow can be started by hand "
                f"({', '.join(others)} and workflow_dispatch). Its job-level "
                f"`if:` is {gate!r}, which does not hold a dispatched run back. "
                f"Gate it on `github.event_name == '<event>'` with no `||` "
                f"beside it, or drop the write scope; a github.ref test cannot "
                f"stand in, because a dispatch can be aimed at a tag ref"
            )
    return bad, writers


def self_test() -> int:
    """Hand the fourth rule the gates it exists to refuse, and two it must admit.

    release.yml's gate is correct, so the refusal has no workflow in the tree
    to fire on, and a rule that has stopped firing prints what a clean tree
    prints. Each refused shape here was one the rule once admitted.
    """
    for ref, wanted in (
        ("./.github/workflows/codeoss-build.yml", True),
        ("./.github/workflows/codeoss-build.yaml", True),
        ("owner/repo/.github/workflows/build.yml@" + "a" * 40, True),
        ("owner/repo/.github/workflows/build.yml@main", False),
        ("./.github/workflows/nested/build.yml", False),
        ("./.github/workflows/../build.yml", False),
    ):
        if valid_job_reference(ref) != wanted:
            print(f"  FAIL   self-test: reusable workflow reference {ref!r}")
            return 1
    print("  ok     self-test: reusable workflows require a local path or full SHA")

    def workflow(gate):
        return {"on": {"push": {"tags": ["v*"]}, "workflow_dispatch": None},
                "permissions": {"contents": "write"},
                "jobs": {"publish": {"if": gate, "steps": []}}}

    refused = (
        "",
        "startsWith(github.ref, 'refs/tags/v')",
        "github.event_name != 'workflow_dispatch'",
        "github.event_name != 'schedule' && startsWith(github.ref, 'refs/tags/v')",
        "github.event_name == 'push' || true",
        "github.event_name == 'push' || github.event_name != 'schedule'",
    )
    admitted = (
        "github.event_name == 'push' && startsWith(github.ref, 'refs/tags/v')",
        "github.event_name == 'push' && (startsWith(github.ref, 'refs/tags/v') "
        "|| startsWith(github.ref, 'refs/tags/server-'))",
    )
    for gate in refused:
        bad, _ = ungated_writers(pathlib.Path("self-test.yml"), workflow(gate))
        if not bad:
            print(f"  FAIL   self-test: {gate!r} was admitted as a dispatch gate")
            return 1
    for gate in admitted:
        bad, _ = ungated_writers(pathlib.Path("self-test.yml"), workflow(gate))
        if bad:
            print(f"  FAIL   self-test: {gate!r} was refused: {bad[0]}")
            return 1
    print(f"  ok     self-test: {len(refused)} denylist gates refused, "
          f"{len(admitted)} allowlist gates admitted")

    # The real workflows, with one step deleted at a time. Everything that is left
    # still loads, so only the table can notice, and a name that no longer matches
    # anything would be a row that never fires.
    docs = {name: yaml.safe_load((WORKFLOWS / name).read_text())
            for name in {*REQUIRED_STEPS, "codeoss-build.yml", "build-vscode-oss.yml"}}
    cut = 0
    for name, wanted in REQUIRED_STEPS.items():
        if missing_steps(docs[name], wanted):
            print(f"  FAIL   self-test: {name} lacks steps REQUIRED_STEPS lists")
            return 1
        for step in wanted:
            if missing_steps(without_step(docs[name], step), wanted) != [step]:
                print(f"  FAIL   self-test: deleting {step!r} from {name} was not noticed")
                return 1
            cut += 1
    print(f"  ok     self-test: deleting any of {cut} required steps is refused")

    def drop_cache_condition(doc):
        for _, _, step in steps_of(doc):
            if step.get("id") == "cache":
                step.pop("if", None)

    if publish_builds_fresh(docs):
        print("  FAIL   self-test: the publish workflows were refused as they stand")
        return 1
    for label, name, edit in (
        ("a call without fresh", "build-vscode-oss.yml",
         lambda doc: doc["jobs"]["build"]["with"].pop("fresh")),
        ("fresh: false", "build-vscode-oss.yml",
         lambda doc: doc["jobs"]["build"]["with"].update(fresh=False)),
        ("a cache step that ignores it", "codeoss-build.yml", drop_cache_condition),
    ):
        edited = copy.deepcopy(docs)
        edit(edited[name])
        if not publish_builds_fresh(edited):
            print(f"  FAIL   self-test: {label} was admitted as a publish that builds fresh")
            return 1
    print("  ok     self-test: a publish that could read the cache is refused three ways")
    return 0


def main() -> int:
    failures = []
    checked_files = 0
    checked_steps = 0
    gated_writers = 0
    docs = {}

    paths = sorted(p for p in WORKFLOWS.iterdir()
                   if p.suffix in (".yml", ".yaml") and p.is_file())
    for path in paths:
        checked_files += 1
        doc = docs[path.name] = yaml.safe_load(path.read_text())
        bad, writers = ungated_writers(path, doc)
        failures.extend(bad)
        gated_writers += writers - len(bad)
        for name in missing_steps(doc, REQUIRED_STEPS.get(path.name, ())):
            failures.append(
                f"{path.name}: the step {name!r} is gone. It only checks something, so "
                f"nothing else fails without it. If it was renamed, rename it in "
                f"REQUIRED_STEPS in this script; if it was removed on purpose, remove it there"
            )
        for job_name, job in (doc.get("jobs") or {}).items():
            if "uses" not in job:
                continue
            ref = str(job["uses"])
            if not valid_job_reference(ref):
                failures.append(
                    f"{path.name}: job {job_name} uses {ref}, which is neither "
                    "a same-revision local workflow nor pinned to a full SHA"
                )
            elif ref.startswith("./") and not (ROOT / ref).is_file():
                failures.append(f"{path.name}: job {job_name} uses missing workflow {ref}")
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

    # A table row for a workflow that is not there fires on nothing, so a renamed
    # file would take its rows with it in silence.
    failures.extend(f"{name} is not under {WORKFLOWS}, but REQUIRED_STEPS lists steps in it"
                    for name in REQUIRED_STEPS if name not in docs)
    failures.extend(publish_builds_fresh(docs))

    # Asserted, not merely printed. Printing the count was the first attempt at
    # this and it does not work: CI reads the exit code, so an empty
    # .github/workflows still went green with "all 0 steps in 0 workflows".
    if not checked_files or not checked_steps:
        print(f"::error::read {checked_files} workflow file(s) and "
              f"{checked_steps} step(s) under {WORKFLOWS}; this checker has "
              f"nothing to check, which is a broken checkout or a moved "
              f"directory rather than a clean tree")
        return 1

    for f in failures:
        print(f"::error::{f}")
    if failures:
        print(f"  FAILED  {len(failures)} problem(s) across {checked_steps} steps")
        return 1

    print(f"  ok     all {checked_steps} steps in {checked_files} workflows "
          f"have exactly one of uses/run")
    print("  ok     every uses reference is pinned to a full SHA or a same-revision local workflow")
    # Zero is a legitimate answer and reads as one: it says no workflow that can
    # be started by hand also holds a write-scoped job, which is the state this
    # rule wants. It is not the empty-glob case the assertion above covers.
    print(f"  ok     {gated_writers} write-scoped job(s) in a workflow that can "
          f"also be started by hand, each gated on github.event_name")
    print(f"  ok     all {sum(map(len, REQUIRED_STEPS.values()))} required steps are in "
          f"{', '.join(REQUIRED_STEPS)}")
    print("  ok     build-vscode-oss.yml asks for a fresh build and codeoss-build.yml skips its cache for it")
    return 0


if __name__ == "__main__":
    sys.exit(self_test() if sys.argv[1:] == ["--self-test"] else main())
