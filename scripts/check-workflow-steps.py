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

Seven assertions:

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
  * the steps in REQUIRED_STEPS are still there, and neither they, the jobs
    that run them, nor any job those jobs need carry an `if:` or
    `continue-on-error` that ALLOWED_CONDITIONS does not list. They only check
    something, so a workflow that has lost one, or skips it, parses, loads and
    runs green, and none of the rules above can see an absence;
  * a publish never reads the tarball cache: build-vscode-oss.yml hands the
    shared build `fresh: true`, and that build skips its cache when given it;
  * every `apt-get update` runs under `timeout <seconds>` in a step with
    `timeout-minutes`, so a stalled mirror cannot spend a job's whole budget.

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
# The one `if:` the tarball cache may carry. A test that only found `inputs.fresh`
# in it also passed `${{ inputs.fresh }}`, which skips the cache when it should be
# read and reads it on a publish, and `${{ !inputs.fresh || true }}`.
FRESH_SKIP = re.compile(r"\$\{\{\s*!\s*inputs\.fresh\s*\}\}")
# A package index refresh that a stalled mirror cannot hold. Measured twice on one
# commit in build.yml: apt fell through to the public archive and sat there with no
# output until the job's budget was gone. codeoss-build.yml had no bound at all.
BOUNDED_REFRESH = re.compile(r"\btimeout\s+\d+\s+apt-get update\b")

# Steps that only check something, on the path that publishes the server and the
# app, matched by name in any job. Delete one and the workflow still loads and
# runs green. Renaming one means renaming it here too.
#
# lint.yml is listed as well. Most of its gates run again on a tag, so one deleted
# from lint.yml alone left every other rule green: check-build-steps.py asks only
# whether something runs a checker, and release.yml still did, while every pull
# request and every push to main had stopped running it.
REQUIRED_STEPS = {
    "lint.yml": (
        "Check the documented build steps match the workflows",
        "Check every workflow step is runnable and pinned",
        "Check the gates still refuse what they exist to refuse",
        "Check the Gradle distribution is pinned to a digest",
        "Check the bridge API spec matches the registered signatures",
        "Check local network access survives the targetSdk in use",
        "Check the privacy policy matches the permissions that ship",
        "Check no user-facing text is stranded in Kotlin",
        "Check the lint baseline has not grown",
        "Check the tree writes plain punctuation",
        "Check the bundled extensions match the notices",
        "Check extension extraction recovers after an interrupted copy",
        "Check the Copilot compile stage leaves a complete SDK directory",
        "Check the sysroot header patcher rewrites tmpfile and the passwd entry",
        "Check toolchain argument parsing with the host compiler",
        "Check the self-exe preload library with the host compiler",
        "Check the move helper with the host compiler",
        "Check release version declarations are read exactly",
        "Check packaging and device maintenance commands offline",
        "Check native shim behavior",
        "Check the release refuses a server built from other inputs",
        "Check no document offers a withdrawn toolchain",
        "Check the documents quote the sizes the registry records",
        "Check the documents state the app's current version",
        "Check the device-test checklist accounts for its own rows",
        "Check every Markdown table is drawn as written",
        "Check the instrumented suite matches what documents it",
        "Check the shipped documents are not dated before their contents",
        "Check every patch is still readable by git",
        "Check the device suite can still read what it asserts",
        "Check the bundled JavaScript runtime",
        "Run lint",
    ),
    "build.yml": (
        "Download the server for unit tests",
        "Fetch the server for unit tests",
        "Copy the server for unit tests",
        "Run unit tests",
        "Check unit test skips",
        "Check every shipped library is attributed",
        "Check the wheelhouse matches the bundled Python",
        "Check the bundled extensions match the notices",
        "Verify assets",
        "Check the documented binary lists match what is packaged",
        "Check the monitor recognises the language servers that ship",
        "Check the welcome screen states nothing that goes stale",
        "Refuse an asset path that is both a file and a folder",
    ),
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
        "Check the bridge API spec matches the registered signatures",
        "Check local network access survives the targetSdk in use",
        "Check the privacy policy matches the permissions that ship",
        "Check no user-facing text is stranded in Kotlin",
        "Check the lint baseline has not grown",
        "Check the welcome screen states nothing that goes stale",
        "Check the bundled extensions match the notices",
        "Check no document offers a withdrawn toolchain",
        "Check the documents quote the sizes the registry records",
        "Check the documents state the app's current version",
        "Check the tree writes plain punctuation",
        "Check the device-test checklist accounts for its own rows",
        "Check the instrumented suite matches what documents it",
        "Check every Markdown table is drawn as written",
        "Check the Gradle distribution is pinned to a digest",
        "Check the shipped documents are not dated before their contents",
        "Check the device suite can still read what it asserts",
        "Check release version declarations are read exactly",
        "Check packaging and device maintenance commands offline",
        "Check native shim behavior",
        "Check the bundled JavaScript runtime",
        "Check the gates still refuse what they exist to refuse",
        "Check the build helpers offline",
        "Fetch Code - OSS server",
        "Check every shipped library is attributed",
        "Check the wheelhouse matches the bundled Python",
        "Verify assets",
        "Check the documented binary lists match what is packaged",
        "Check the monitor recognises the language servers that ship",
        "Run unit tests",
        "Check unit test skips",
        "Run lint",
        "Refuse a dependency Play Console would block",
        "Compile the instrumented tests",
        "Decode keystore",
        "Check the documented core sizes against the APK",
        "Check every shipped toolchain library is attributed",
        "Compare recorded licences against Termux",
        "Refuse an asset the base module and a pack both ship",
        "Hold the user guide's tool versions to what this build resolved",
        "Check the shipped permissions against the privacy policy",
        "Refuse an asset path that is both a file and a folder",
        "Sign toolchain digest manifest",
        "Require every artifact, and report its size",
        "Generate checksums",
    ),
}

# The `if:` a required step, a job that runs one, or a job such a job needs may
# carry, by workflow and then by step name or job id, exactly as the workflow
# writes it. Present is not the same as run: `if: ${{ false }}`, an event the
# workflow never sees, or `continue-on-error: true` on the step or on its job
# keeps every name above in place while the check never runs, or runs without
# failing anything. A skipped job skips every job that needs it, and a skipped
# job passes a required status check. `continue-on-error` is never allowed.
ALLOWED_CONDITIONS = {
    "release.yml": {
        "Check the tag matches the app version": "github.event_name == 'push'",
        # The publishing job, whose gate the fourth rule asks about as well.
        "github-release": "github.event_name == 'push' && startsWith(github.ref, 'refs/tags/v')",
    },
}


def split_boolean(expr: str, operator: str):
    """Split at an operator outside groups and strings, or reject malformed input."""
    parts, start, groups, quoted, i = [], 0, [], False, 0
    while i < len(expr):
        char = expr[i]
        if char == "'":
            if quoted and i + 1 < len(expr) and expr[i + 1] == "'":
                i += 2  # GitHub expressions escape a quote by doubling it.
                continue
            quoted = not quoted
        elif not quoted:
            if char in "([":
                groups.append(char)
            elif char in ")]":
                if not groups or groups.pop() != {")": "(", "]": "["}[char]:
                    return None
            elif not groups and expr.startswith(operator, i):
                parts.append(expr[start:i])
                i += len(operator)
                start = i
                continue
        i += 1
    if quoted or groups:
        return None
    parts.append(expr[start:])
    return parts


def required_event_gate(expr: str) -> bool:
    """Require a positive event equality in an unavoidable conjunction.

    `${{ }}` may wrap the whole value and nothing else. With any text around it, a trailing
    newline of a block scalar included, GitHub reads the value as a string, which is always true,
    so such a value gates nothing and is refused here whatever it says inside.
    """
    if "${{" in expr or "}}" in expr:
        if not (expr.startswith("${{") and expr.endswith("}}") and expr.count("${{") == 1 and expr.count("}}") == 1):
            return False
        expr = expr[3:-2]
    return event_gate_expression(expr.strip())


def event_gate_expression(expr: str) -> bool:
    """[required_event_gate] for an expression with no `${{ }}` around it."""
    alternatives = split_boolean(expr, "||")
    if alternatives is None or len(alternatives) != 1:
        return False
    terms = split_boolean(expr, "&&")
    if terms is None:
        return False
    if len(terms) > 1:
        return all(term.strip() for term in terms) and any(event_gate_expression(term.strip()) for term in terms)
    if expr.startswith("(") and expr.endswith(")"):
        # Only remove a pair that encloses the whole expression.
        inner = expr[1:-1]
        if split_boolean(inner, "&&") is not None:
            return event_gate_expression(inner.strip())
    return ALLOWLIST_GATE.fullmatch(expr) is not None


def steps_of(doc):
    """Yield `(job_name, index, step)` for every step in the document."""
    for job_name, job in (doc.get("jobs") or {}).items():
        for i, step in enumerate(job.get("steps") or []):
            yield job_name, i, step


def missing_steps(doc, names):
    """The names in `names` that no step of `doc` carries."""
    present = {step.get("name") for _, _, step in steps_of(doc)}
    return [name for name in names if name not in present]


def conditioned_steps(name, doc, wanted):
    """Complaints for the steps in `wanted`, and the jobs running them, that may not run.

    `name` is the workflow's file name, the key into ALLOWED_CONDITIONS. A job that calls a
    local workflow holding required steps runs them too: skipped, it skips them and every
    job that needs it. So does a job that any of these needs, directly or through another
    job: skipped, it skips them.
    """
    allowed = ALLOWED_CONDITIONS.get(name, {})
    jobs = doc.get("jobs") or {}
    holders, queue, seen = [], [], set()
    for job_name, job in jobs.items():
        steps = [s for s in job.get("steps") or [] if s.get("name") in wanted]
        uses = str(job.get("uses", ""))
        if steps or (uses.startswith("./") and uses.rsplit("/", 1)[-1] in REQUIRED_STEPS):
            queue.append((job_name, f"job {job_name}"))
        holders += [(s["name"], f"step {s['name']!r}", s) for s in steps]
    # Every job that runs a required check is queued before any job it needs, so a job
    # that is both is named for the check it runs.
    while queue:
        job_name, label = queue.pop(0)
        if job_name in seen or job_name not in jobs:
            continue
        seen.add(job_name)
        holders.append((job_name, label, jobs[job_name]))
        needs = jobs[job_name].get("needs") or []
        queue += [(need, f"job {need}, which job {job_name} needs,")
                  for need in ([needs] if isinstance(needs, str) else needs)]
    bad = []
    for key, label, holder in holders:
        if "if" in holder and str(holder["if"]) != allowed.get(key):
            bad.append(f"{name}: {label} carries `if: {holder['if']!r}`, which ALLOWED_CONDITIONS "
                       f"does not list, so a required check may never run. If the condition is "
                       f"meant, list it there exactly as written")
        if holder.get("continue-on-error", False) is not False:
            bad.append(f"{name}: {label} sets continue-on-error, so a required check could fail "
                       f"or be skipped without failing the run")
    return bad


def unbounded_refreshes(name, doc):
    """Complaints for each step refreshing the apt index with nothing to bound it.

    Both bounds, because each covers what the other cannot: `timeout` on the command
    lets a retry follow a stalled attempt, and `timeout-minutes` on the step stops a
    stall anywhere in it from spending the job's whole budget.
    """
    bad = []
    for job, i, step in steps_of(doc):
        # Quoted text is a message about the refresh, not a refresh.
        lines = [line for line in (re.sub(r"\"[^\"]*\"|'[^']*'", "", raw)
                                   for raw in str(step.get("run", "")).splitlines())
                 if "apt-get update" in line]
        if not lines:
            continue
        if "timeout-minutes" not in step or not all(BOUNDED_REFRESH.search(line) for line in lines):
            bad.append(f"{name}: job {job}, step {i} ({step.get('name')!r}) runs apt-get update "
                       f"without both `timeout <seconds>` on the command and `timeout-minutes` on "
                       f"the step, so a stalled mirror can hold it until the job's budget is gone")
    return bad


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
    if not FRESH_SKIP.fullmatch(str(cache.get("if", ""))):
        bad.append("codeoss-build.yml: the step with id `cache` does not carry exactly "
                   "`if: ${{ !inputs.fresh }}`, so a fresh build could still restore a cached tarball")
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
        if "workflow_dispatch" in gate or not required_event_gate(gate):
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
        "(github.event_name == 'push' || true)",
        "((github.event_name == 'push' || true)) && success()",
        "!(github.event_name == 'push') && success()",
        "contains('github.event_name == ''push''', 'push')",
        "fromJSON('[true]')[true && github.event_name == 'push' && true]",
        "github.event_name == 'push' && success())",
        "${{ github.event_name == 'push' }} && startsWith(github.ref, 'refs/tags/v')",
        "startsWith(github.ref, 'refs/tags/v') && ${{ github.event_name == 'push' }}",
        "${{ github.event_name == 'push' && startsWith(github.ref, 'refs/tags/v') }}\n",
        "${{ github.event_name == 'push' }} ${{ success() }}",
    )
    admitted = (
        "github.event_name == 'push' && startsWith(github.ref, 'refs/tags/v')",
        "github.event_name == 'push' && (startsWith(github.ref, 'refs/tags/v') "
        "|| startsWith(github.ref, 'refs/tags/server-'))",
        "(github.event_name == 'push') && (success() || failure())",
        "((github.event_name == 'push') && success())",
        "${{ (github.event_name == 'push') && (success() || failure()) }}",
        "github.event_name == 'push' && contains('a)b', ')')",
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

    # Still there, and kept from running or from failing the run.
    def step_named(doc, wanted):
        return next(step for _, _, step in steps_of(doc) if step.get("name") == wanted)

    for name, wanted in REQUIRED_STEPS.items():
        if conditioned_steps(name, docs[name], wanted):
            print(f"  FAIL   self-test: the conditions in {name} were refused as they stand")
            return 1

    def with_gate(holder, needs, gates):
        """`holder` comes to need `needs`; `gates` maps each added job id to its own keys."""
        def edit(doc):
            doc["jobs"].update({job: {"runs-on": "ubuntu-latest", "steps": [{"run": "true"}], **keys}
                                for job, keys in gates.items()})
            doc["jobs"][holder]["needs"] = needs
        return edit

    tag_check = "Check the tag matches the app version"
    skipped = (
        ("a step under `if: ${{ false }}`", "build.yml",
         lambda doc: step_named(doc, "Run unit tests").update({"if": "${{ false }}"})),
        ("a step under an event the workflow never sees", "build.yml",
         lambda doc: step_named(doc, "Run unit tests").update({"if": "github.event_name == 'schedule'"})),
        ("a lint step under `if: ${{ false }}`", "lint.yml",
         lambda doc: step_named(doc, "Check the bundled JavaScript runtime").update({"if": "${{ false }}"})),
        ("the lint job under `if: ${{ false }}`", "lint.yml",
         lambda doc: doc["jobs"]["lint"].update({"if": "${{ false }}"})),
        ("a step that may fail", "codeoss-build.yml",
         lambda doc: step_named(doc, "Verify the server tarball").update({"continue-on-error": True})),
        ("a job under `if: ${{ false }}`", "build.yml",
         lambda doc: doc["jobs"]["test"].update({"if": "${{ false }}"})),
        ("a call of a workflow holding one under `if: ${{ false }}`", "build.yml",
         lambda doc: doc["jobs"]["codeoss"].update({"if": "${{ false }}"})),
        ("a job that may fail", "release.yml",
         lambda doc: doc["jobs"]["build-release"].update({"continue-on-error": True})),
        ("a listed condition loosened", "release.yml",
         lambda doc: step_named(doc, tag_check).update({"if": "github.event_name == 'push' || true"})),
        ("a listed condition on another step", "release.yml",
         lambda doc: step_named(doc, "Verify assets").update({"if": "github.event_name == 'push'"})),
        ("a job a required job needs, under `if: ${{ false }}`", "build.yml",
         with_gate("test", ["codeoss", "gate"], {"gate": {"if": "${{ false }}"}})),
        ("a job needed through another, under an event the workflow never sees", "release.yml",
         with_gate("build-release", "gate",
                   {"gate": {"needs": "gate2"}, "gate2": {"if": "github.event_name == 'schedule'"}})),
        ("a needed job that may fail", "build-vscode-oss.yml",
         with_gate("publish", ["build", "gate"], {"gate": {"continue-on-error": True}})),
    )
    for label, name, edit in skipped:
        edited = copy.deepcopy(docs[name])
        edit(edited)
        if not conditioned_steps(name, edited, REQUIRED_STEPS[name]):
            print(f"  FAIL   self-test: {label} was admitted for a required check")
            return 1
    print(f"  ok     self-test: {len(skipped)} ways to keep a required step from running or "
          f"failing are refused, the listed conditions admitted")

    def cache_condition(value):
        def edit(doc):
            for _, _, step in steps_of(doc):
                if step.get("id") == "cache":
                    step.pop("if", None)
                    if value is not None:
                        step["if"] = value
        return edit

    if publish_builds_fresh(docs):
        print("  FAIL   self-test: the publish workflows were refused as they stand")
        return 1
    fresh_cases = (
        ("a call without fresh", "build-vscode-oss.yml",
         lambda doc: doc["jobs"]["build"]["with"].pop("fresh")),
        ("fresh: false", "build-vscode-oss.yml",
         lambda doc: doc["jobs"]["build"]["with"].update(fresh=False)),
        ("a cache step that ignores it", "codeoss-build.yml", cache_condition(None)),
        ("a cache step read only when fresh", "codeoss-build.yml", cache_condition("${{ inputs.fresh }}")),
        ("a cache step always read", "codeoss-build.yml", cache_condition("${{ !inputs.fresh || true }}")),
        ("a cache condition with text after it", "codeoss-build.yml",
         cache_condition("${{ !inputs.fresh }} && true")),
    )
    for label, name, edit in fresh_cases:
        edited = copy.deepcopy(docs)
        edit(edited[name])
        if not publish_builds_fresh(edited):
            print(f"  FAIL   self-test: {label} was admitted as a publish that builds fresh")
            return 1
    for spelling in ("${{!inputs.fresh}}", "${{  ! inputs.fresh  }}"):
        edited = copy.deepcopy(docs)
        cache_condition(spelling)(edited["codeoss-build.yml"])
        if publish_builds_fresh(edited):
            print(f"  FAIL   self-test: the cache condition {spelling!r} was refused")
            return 1
    print(f"  ok     self-test: a publish that could read the cache is refused {len(fresh_cases)} ways")

    refreshing = sorted(name for name, doc in docs.items() if any(
        "apt-get update" in str(step.get("run", "")) for _, _, step in steps_of(doc)))
    if not refreshing or any(unbounded_refreshes(name, docs[name]) for name in refreshing):
        print("  FAIL   self-test: the bounded apt refreshes were refused as they stand, or none was found")
        return 1
    for name in refreshing:
        for label, edit in (("its step budget", lambda step: step.pop("timeout-minutes")),
                            ("its command timeout", lambda step: step.update(
                                run=re.sub(r"timeout\s+\d+\s+", "", step["run"])))):
            edited = copy.deepcopy(docs[name])
            edit(next(step for _, _, step in steps_of(edited) if "apt-get update" in str(step.get("run", ""))))
            if not unbounded_refreshes(name, edited):
                print(f"  FAIL   self-test: an apt refresh in {name} without {label} was admitted")
                return 1
    print(f"  ok     self-test: an apt refresh without either bound is refused in {', '.join(refreshing)}")
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
        failures.extend(conditioned_steps(path.name, doc, REQUIRED_STEPS.get(path.name, ())))
        failures.extend(unbounded_refreshes(path.name, doc))
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
          f"{', '.join(REQUIRED_STEPS)}, under no condition ALLOWED_CONDITIONS does not list")
    print("  ok     build-vscode-oss.yml asks for a fresh build and codeoss-build.yml skips its cache for it")
    print("  ok     every apt-get update is bounded on the command and on its step")
    return 0


if __name__ == "__main__":
    # Anything but no argument or exactly --self-test is refused: a mistyped flag that
    # fell through to main() skipped the self-test and still exited 0.
    if sys.argv[1:] not in ([], ["--self-test"]):
        print("usage: check-workflow-steps.py [--self-test]", file=sys.stderr)
        sys.exit(2)
    sys.exit(self_test() if sys.argv[1:] else main())
