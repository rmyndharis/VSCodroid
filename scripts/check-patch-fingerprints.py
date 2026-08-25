#!/usr/bin/env python3
"""Check a packaged tree carries every patch this repository applies.

    check-patch-fingerprints.py <tree> [patches-dir]
    check-patch-fingerprints.py --write-manifest <tree> [patches-dir]

Applying a patch to the source proves nothing about the package: the file may
not be in the target's graph, or the build may inline an older copy. Each patch
therefore leaves a fingerprint that survives minification, and this searches the
packaged tree for it. The expectations live in patches/fingerprints.txt.

A fingerprint proves a patch ARRIVED, not which version of it did, which is the
one hole the table's own header has always admitted to: edit an already-matching
patch and the pattern still matches, so a server built before the edit passes
every gate while shipping the old behaviour. The manifest closes it. The build
writes the sha256 of each patch's diff into the tree it produces, and the compare
below recomputes those hashes from the patches in this checkout. A tarball built
from different patch text is then refused wherever this runs, which includes the
fetch and the Gradle packaging gate.

The prose above each diff is deliberately not hashed, and nothing else is exempt.
A patch header never reaches the source tree -- git apply reads the hunks and
ignores everything above them -- so rewriting the reasoning there changes no byte
of what gets built, and making a typo in it look like a change of behaviour would
train people to ignore the gate.

A comment INSIDE a hunk reads the same to a person and is the opposite case: it
is an added source line, so a tree built before the edit really was built from
different source, and nothing here can tell it from a line that changes what the
code does. So editing one costs a server rebuild and a republish, about thirty
minutes on an arm64 runner, before anything can package an APK again. Those are
the only two categories, and the second is the common edit in a repository whose
comments carry the reasoning.

Dropping whole-line comments from the hash would make that edit free and is the
obvious next step. It cannot be taken on its own: changing what is hashed
invalidates every manifest already published, which costs exactly the rebuild it
is meant to save. It belongs in the same change as the next server rebuild.

Two things this fixes about the check it replaces, which was a heredoc inside
build-vscode-oss.sh:

  * It walks patches/, not the table. The old loop iterated its own rows, so a
    patch added without a row produced a run of "ok" lines and passed. Two
    patches legitimately have no fingerprint, and their absence looked identical
    to an oversight -- the gate could not tell deliberate from forgotten, so
    exemptions are now written down as lines that say how the patch is proven
    instead.

  * It takes the tree as an argument, like verify-server-tree.py, which is what
    lets the same check run on both sides. build-vscode-oss.sh checks what it
    built; fetch-vscode-oss.sh can check what it downloaded, and that second
    caller is the one that matters: a server tarball predating a patch has the
    same name, the same version, and a digest that verifies, because a digest
    proves the tarball is intact rather than that it is the right tarball.
"""

import hashlib
import json
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent

# The patches directory is an argument rather than a fixed path because
# build-vscode-oss.sh takes PATCHES from the environment: a local Docker run
# mounts it at /patches. Resolving it here from this file's own location would
# check the repository's patches while the build applied the mounted ones, and
# the two would disagree without saying so. The table lives inside that
# directory, so it follows whichever one is in use.
DEFAULT_PATCHES = ROOT / "patches"

# 0001-platform-treat-android-as-linux.patch -> 0001
PATCH_ID = re.compile(r"^(\d{4})-.*\.patch$")

# Written into the packaged tree by build-vscode-oss.sh, read back from it here.
# At the tree root rather than beside the bundles: it describes the whole build.
MANIFEST_NAME = "vscodroid-patches.json"

# Where a minifier is free to add or drop whitespace. A pattern is written the way
# one esbuild version happened to emit it -- `case"android"`, `==="/callback"` --
# and the next version choosing `case "android"` or single quotes would fail rows
# that describe a tree which is still correct.
#
# What the tolerance costs, stated rather than waved past: a space in a pattern
# becomes \s*, so `.activitybar .composite-bar` also matches the compound selector
# `.activitybar.composite-bar`, which is different CSS. Measured, not supposed. That
# is accepted because no minifier merges descendant selectors -- doing so changes
# what the rule matches -- so the looser form describes a bundle that cannot occur.
# The mapping cannot be \s+ instead: a space is exactly where a minifier is most
# likely to differ, and demanding one fails the rows this exists for.
PUNCTUATION = set("=(){}[],:;<>!+-*/&|?.")
QUOTES = "\"'"


def tolerant(pattern):
    """`pattern`, as a regex that survives a change of quote style or spacing."""
    out = []
    for i, ch in enumerate(pattern):
        if ch in QUOTES:
            out.append("[\"']")
        elif ch == " ":
            # Zero-or-more, not one-or-more. A space in a pattern is where the
            # minifier is MOST likely to differ -- `case "android"` in source
            # becomes `case"android"` in the bundle -- so demanding one here fails
            # exactly the rows this tolerance exists for.
            out.append(r"\s*")
            continue
        else:
            out.append(re.escape(ch))
        nxt = pattern[i + 1] if i + 1 < len(pattern) else ""
        if ch in PUNCTUATION or ch in QUOTES or nxt in PUNCTUATION or nxt in QUOTES:
            out.append(r"\s*")
    return re.compile("".join(out))


def squashed(text):
    """Whitespace gone and quotes unified, for comparing source against a bundle."""
    return re.sub(r"\s+", "", text).replace("'", '"')


def introduced_by(pattern, patch_path):
    """Whether the patch's own added lines are where this pattern comes from.

    The table's instruction has always been to verify a pattern appears zero times
    in an UNPATCHED bundle, because one that already matched proves nothing. That
    check needs a second full build -- thirty minutes on an arm64 runner -- so it
    has only ever been advice to a human.

    This is the half that can run offline: a pattern whose text the patch does not
    introduce cannot be evidence that the patch arrived. It is necessary, not
    sufficient -- the same text could coincidentally exist elsewhere in the tree --
    so it catches the mistake actually made (picking a pattern from surrounding
    code) rather than proving the pattern unique.
    """
    added = "".join(
        line[1:]
        for line in patch_path.read_text(errors="ignore").splitlines()
        if line.startswith("+") and not line.startswith("+++")
    )
    return squashed(pattern) in squashed(added)


def diff_body(patch_path):
    """The patch's diff, with the prose header above it dropped.

    Two shapes occur in patches/: `git format-patch`-style, which opens each file
    with `diff --git`, and a bare unified diff, which opens with the `---`/`+++`
    pair. 0010 is the second kind, so keying only on `diff --git` would hash an
    empty string for it and quietly stop covering it.
    """
    lines = patch_path.read_text(errors="ignore").splitlines(keepends=True)
    for i, line in enumerate(lines):
        if line.startswith("diff --git "):
            return "".join(lines[i:])
        if line.startswith("--- ") and i + 1 < len(lines) and lines[i + 1].startswith("+++ "):
            return "".join(lines[i:])
    return None


def patch_names(patches_dir):
    """Every *.patch in the directory, or None if one of them cannot be keyed.

    The enumeration lives here, once, because it used to live twice: as a list
    in write_manifest and as an id-keyed dict in main, both filtering on
    PATCH_ID with no else-branch. build-vscode-oss.sh applies `patches/*.patch`
    unfiltered, so a file the pattern cannot key was compiled into the shipped
    tree and then dropped by both sides of the check that exists to notice it.
    Dropping it is what makes it silent: it appears in neither the manifest nor
    the row loop, so the run prints only ok lines.
    """
    # Leading dots excluded to match the applier, not to be lenient. pathlib's glob
    # matches a leading-dot name and bash's does not, so `patches/*.patch` in
    # build-vscode-oss.sh never sees one. Without this, an AppleDouble sidecar
    # (`._0001-...patch`, which extracting an archive or copying the tree over exFAT
    # leaves behind) would be refused here as "applied and tracked by nothing" when
    # the build does not apply it at all, and the refusal reaches every APK through
    # the packaging gates.
    paths = sorted(p for p in patches_dir.glob("*.patch") if not p.name.startswith("."))
    stray = [p.name for p in paths if not PATCH_ID.match(p.name)]
    if stray:
        print(f"  FAIL   {stray} in {patches_dir} is applied by "
              f"build-vscode-oss.sh and tracked by nothing; rename it "
              f"NNNN-short-description.patch or move it out of patches/")
        return None
    if not paths:
        # An empty left-hand side would make every comparison below vacuously
        # true.
        print(f"  FAIL   no patches found in {patches_dir}; the naming changed")
        return None
    return [p.name for p in paths]


def patch_hashes(patches_dir, names):
    """{patch filename: sha256 of its diff}, or None if the set is not usable.

    The distinctness check is the only thing standing between this file and a
    silent vacuity. Both sides call this one function: the build writes the
    manifest from it and the fetch recomputes with it. A diff_body that stopped
    splitting -- a third patch shape upstream, or a `--- `/`+++ ` pair quoted in
    the prose above a diff -- could return the same body for every patch, and the
    writer and the checker would then agree perfectly while covering nothing. Two
    patches with identical diff text are not a thing this repository has or would
    want, so a collision means the splitter broke rather than that a patch was
    duplicated.
    """
    out = {}
    for name in sorted(names):
        body = diff_body(patches_dir / name)
        if body is None:
            print(f"         {name} has no diff in it; git could not apply it either")
            return None
        out[name] = hashlib.sha256(body.encode()).hexdigest()
    if len(set(out.values())) != len(out):
        collided = sorted(n for n, h in out.items()
                          if list(out.values()).count(h) > 1)
        print(f"         {len(out)} patches hash to {len(set(out.values()))} distinct "
              f"values, so the diff is not being read per patch: {collided}")
        return None
    return out


failed = False


def check(ok, label, detail=""):
    global failed
    print(f"  {'ok     ' if ok else 'FAIL   '} {label}{'' if ok else '  ' + detail}")
    if not ok:
        failed = True


def read_table(TABLE):
    """{id: [(label, bundle, pattern), ...]}; bundle '-' means an explicit exemption.

    A list per id, not one row per id. A patch that lands in two separately
    bundled entry points needs a claim about each, and 0003 is one: it bridges
    process.send onto parentPort in src/bootstrap-fork.ts, which becomes its own
    out/bootstrap-fork.js, and adds workerAsChildProcess.ts, which is pulled into
    out/server-main.js. One row can only name one of them. Keying a dict by id
    also meant a second row for the same patch silently replaced the first.
    """
    rows = {}
    for lineno, raw in enumerate(TABLE.read_text().splitlines(), 1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        head, _, rest = line.partition("|")
        bundle, _, pattern = rest.partition("|")
        ident = head.split(None, 1)[0] if head.split() else ""
        if not re.fullmatch(r"\d{4}", ident) or not bundle or not pattern:
            print(f"  FAIL   {TABLE.name}:{lineno} is not "
                  f"'NNNN label|bundle|pattern': {line}")
            return None
        rows.setdefault(ident, []).append(
            (head.split(None, 1)[1] if " " in head else "", bundle, pattern))
    return rows


def check_manifest(tree, patches_dir, names):
    """Whether the tree was built from the patch text this checkout holds.

    The fingerprints answer "did this patch arrive"; this answers "which version
    of it". They are separate questions and only this one notices an edit to a
    patch that already matched its pattern.

    Asked FIRST, before the rows, because it is the question that reframes them.
    A tree older than patches/ makes every row below a statement about a build
    nobody is going to ship, and a reader who meets thirty ok lines before this
    one has already concluded the tree is fine.
    """
    manifest_path = tree / MANIFEST_NAME
    try:
        recorded = json.loads(manifest_path.read_text())
    except FileNotFoundError:
        check(False, f"the tree records which patches built it ({MANIFEST_NAME})",
              "nothing in this tree records a patch set, so it was not built from "
              "the ones in this checkout: it predates the manifest, or it was "
              "built with ALLOW_UNADAPTED. Rebuild the server with the \"Build "
              "Code - OSS server\" workflow and refetch. The rows below describe "
              "the older tree and cannot say whether this checkout's patches work.")
        return
    except (OSError, ValueError) as e:
        check(False, f"{MANIFEST_NAME} is readable JSON", str(e))
        return

    current = patch_hashes(patches_dir, names)
    if current is None:
        check(False, "every patch in patches/ hashes to a diff of its own",
              "the line above says which patch, and whether it carries no diff "
              "at all or the same one as another")
        return

    changed = sorted(n for n in set(recorded) & set(current) if recorded[n] != current[n])
    added = sorted(set(current) - set(recorded))
    gone = sorted(set(recorded) - set(current))
    check(not (changed or added or gone),
          "the tree was built from these patches",
          f"edited since the build: {changed or 'none'}; "
          f"not in the build: {added or 'none'}; "
          f"in the build but not in patches/: {gone or 'none'}")


def write_manifest(tree, patches_dir):
    """Record in the tree which patch text produced it."""
    names = patch_names(patches_dir)
    if names is None:
        return 1
    hashes = patch_hashes(patches_dir, names)
    if hashes is None:
        return 1
    (tree / MANIFEST_NAME).write_text(json.dumps(hashes, indent=2, sort_keys=True) + "\n")
    print(f"  wrote {MANIFEST_NAME}: {len(hashes)} patches")
    return 0


def main(tree, patches_dir):
    TABLE = patches_dir / "fingerprints.txt"
    if not TABLE.is_file():
        print(f"  FAIL   no fingerprint table at {TABLE}")
        return 1

    rows = read_table(TABLE)
    if rows is None:
        return 1

    names = patch_names(patches_dir)
    if names is None:
        return 1
    patches = {PATCH_ID.match(name).group(1): name for name in names}

    check_manifest(tree, patches_dir, patches.values())

    for ident, name in sorted(patches.items()):
        if ident not in rows:
            check(False, f"{name} has no line in {TABLE.name}",
                  "add its fingerprint, or a line saying how it is proven instead")
            continue
        for label, bundle, pattern in rows[ident]:
            if bundle == "-":
                # Not a pass by omission: the line states the reason, and it had to
                # be written for the patch to get here at all.
                print(f"  ok      {ident} {label} has no fingerprint -- {pattern}")
                continue
            check(introduced_by(pattern, patches_dir / name),
                  f"{ident} {label} fingerprints its own patch",
                  f"{pattern!r} is not in what {name} adds, so matching it proves "
                  f"nothing about whether the patch arrived")

            target = tree / bundle
            if not target.is_file():
                check(False, f"{ident} {label}: {bundle} is not in the tree")
            else:
                check(tolerant(pattern).search(target.read_text(errors="ignore")) is not None,
                      f"{ident} {label} reached {pathlib.Path(bundle).name}",
                      f"{bundle} does not contain {pattern!r}")

    # A row naming a patch that no longer exists is stale rather than harmless:
    # it is the only remaining place someone would look to learn the patch is
    # gone, and it would keep asserting against a tree forever.
    for ident in sorted(set(rows) - set(patches)):
        check(False, f"{TABLE.name} has a line for {ident}, which is not in patches/",
              "remove the line, or restore the patch")

    return 1 if failed else 0


def self_test():
    """Hand both entry points a directory holding a patch nobody can key.

    Both, separately: restoring the PATCH_ID filter at either one alone leaves
    the other's mutation alive, and a self-test exercising only main would
    print green while write_manifest still wrote a manifest omitting a patch
    the build had already compiled in.

    The negative control is not optional. A refusal that has started firing on
    everything reads exactly like a refusal that works, so each case is
    followed by a conforming directory the same call has to accept.
    """
    import contextlib
    import io
    import shutil
    import tempfile

    global failed
    strays = ("foo.patch", "009-foo.patch", "0009_foo.patch")
    real = sorted(DEFAULT_PATCHES.glob("[0-9][0-9][0-9][0-9]-*.patch"))
    if len(real) < 2:
        print(f"  FAIL   self-test: {DEFAULT_PATCHES} holds no conforming patches "
              f"to build a control from")
        return 1

    with tempfile.TemporaryDirectory() as tmp:
        tree = pathlib.Path(tmp) / "tree"
        tree.mkdir()
        for stray in strays + (None,):
            src = pathlib.Path(tmp) / ("stray" if stray else "clean")
            src.mkdir()
            for p in real:
                shutil.copy(p, src / p.name)
            shutil.copy(DEFAULT_PATCHES / "fingerprints.txt", src / "fingerprints.txt")
            if stray:
                # Distinct content, not a copy of a real patch. A byte-identical
                # copy shares its diff hash with the file it came from, so a
                # refusal could be the collision detector rather than the naming
                # rule, and the case would pass while measuring the wrong thing.
                (src / stray).write_text(
                    "diff --git a/self-test b/self-test\n"
                    "--- a/self-test\n+++ b/self-test\n"
                    "@@ -1 +1 @@\n-nothing\n+nothing at all\n"
                )

            for label, call in (("write_manifest", lambda: write_manifest(tree, src)),
                                ("main", lambda: main(tree, src))):
                out = io.StringIO()
                failed = False
                with contextlib.redirect_stdout(out):
                    rc = call()
                text = out.getvalue()
                if stray:
                    if rc == 0 or stray not in text:
                        print(f"  FAIL   self-test: {label} accepted {stray}, "
                              f"or never named it (rc={rc})")
                        return 1
                else:
                    # The control only has to get PAST the naming rule. main()
                    # still judges a real tree it was not given, so its rc is
                    # not the signal; the absence of a naming refusal is.
                    if "the naming changed" in text or "tracked by nothing" in text:
                        print(f"  FAIL   self-test: {label} refused a directory "
                              f"whose names all conform")
                        return 1
            shutil.rmtree(src)
    failed = False
    print("  ok     self-test: a patch outside NNNN- is refused by both entry "
          "points, and a conforming directory is not")
    return 0


if __name__ == "__main__":
    argv = sys.argv[1:]
    if argv == ["--self-test"]:
        sys.exit(self_test())
    writing = bool(argv) and argv[0] == "--write-manifest"
    if writing:
        argv = argv[1:]
    if len(argv) not in (1, 2):
        print("usage: check-patch-fingerprints.py [--write-manifest] <tree> [patches-dir]",
              file=sys.stderr)
        sys.exit(2)
    root = pathlib.Path(argv[0])
    patches = pathlib.Path(argv[1]) if len(argv) == 2 else DEFAULT_PATCHES
    for label, path in (("tree", root), ("patches directory", patches)):
        if not path.is_dir():
            print(f"  FAIL    {path} is not a directory ({label})", file=sys.stderr)
            sys.exit(1)
    sys.exit(write_manifest(root, patches) if writing else main(root, patches))
