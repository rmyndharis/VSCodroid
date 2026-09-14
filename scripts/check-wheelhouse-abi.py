#!/usr/bin/env python3
"""Fails the build when the wheelhouse is for a Python the APK no longer carries.

`wheelhouse.json` pins wheels tagged `cp314-...`, and the interpreter that has to
import them is chosen somewhere else entirely: `scripts/download-python.sh`
resolves the version from the Termux index at build time, so a Python minor bump
arrives because upstream moved, not because anyone here decided. When it does,
every wheel in the mirror becomes uninstallable at once.

Without this the failure is silent and remote. The APK ships, pip is pointed at a
page full of `cp314` wheels by a device running `cp315`, none of them match the
tag set, and the user sees `Could not find a version that satisfies the
requirement numpy`, which reads like a network problem. Weeks later.

It also holds the published page to the manifest: the page for this Python
minor, `docs/site/wheels/<python-version>/wheels.html`, must list exactly the
wheels `wheelhouse.json` pins, with their digests, tagged for that minor, on the
release it names. Otherwise a one-field edit to `python-version` satisfies the
first check while the page still offers wheels for the old interpreter.

Separate from `build-wheelhouse.py`, which performs the same comparison, because
that one is run by hand when the wheelhouse is rebuilt and downloads every wheel.
This is the half that runs on every build that packages assets.

    check-wheelhouse-abi.py           compare the manifest against the asset tree and the page
"""

import json
import pathlib
import re
import sys

REPO = pathlib.Path(__file__).resolve().parent.parent
MANIFEST = REPO / "wheelhouse.json"
LIB_DIR = REPO / "android/app/src/main/assets/usr/lib"
RELEASES = "https://github.com/rmyndharis/VSCodroid/releases/download"


def page_problems(manifest, declared):
    """What is wrong with the committed page for this manifest, if anything."""
    page = REPO / "docs/site/wheels" / declared / "wheels.html"
    if not page.is_file():
        return [f"{page.relative_to(REPO)} is missing, so pip on a {declared} device is pointed at nothing"]
    abi = "cp" + declared.replace(".", "")
    tag = f"{abi}-{abi}-android_24_arm64_v8a"
    expected = set()
    for package in manifest.get("packages", []):
        url = package["url"]
        if url.endswith(".deb"):
            name = re.sub(r"[-_.]+", "_", package["name"]).lower()
            filename = f"{name}-{package['version']}-{tag}.whl"
        else:
            filename = url.rsplit("/", 1)[-1]
        expected.add((f"{RELEASES}/{manifest.get('release-tag')}/{filename}", package.get("sha256")))
        notices = package.get("notices")
        if notices:
            name = re.sub(r"[-_.]+", "_", package["name"]).lower()
            expected.add((
                f"{RELEASES}/{manifest.get('release-tag')}/{name}-{package['version']}-THIRD-PARTY-NOTICES.txt",
                notices.get("sha256"),
            ))
    listed = set()
    problems = []
    for link in re.findall(r'href="([^"]*)"', page.read_text(encoding="utf-8")):
        href, _, fragment = link.partition("#")
        match = re.fullmatch(r"sha256=([0-9a-f]{64})", fragment)
        if match:
            listed.add((href, match.group(1)))
        else:
            problems.append(f"the page links {link} without a sha256 digest, which pip would not check")
    problems += [f"the page lists {href} ({digest[:12]}), which the manifest does not pin"
                 for href, digest in sorted(listed - expected)]
    problems += [f"the page does not list {href} ({(digest or 'no digest')[:12]})"
                 for href, digest in sorted(expected - listed)]
    problems += [f"{href} is not tagged {tag}" for href, _ in sorted(listed)
                 if href.endswith(".whl") and tag not in href]
    return problems


def main() -> int:
    if not MANIFEST.is_file():
        print(f"FAIL {MANIFEST.name} is missing, so nothing pins what pip is offered", file=sys.stderr)
        return 1

    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    declared = manifest.get("python-version")
    if not declared:
        print(f"FAIL {MANIFEST.name} does not name a python-version", file=sys.stderr)
        return 1

    problems = page_problems(manifest, declared)
    if problems:
        print(
            "FAIL the wheelhouse page does not match wheelhouse.json:\n     "
            + "\n     ".join(problems)
            + "\n     Regenerate it with scripts/build-wheelhouse.py.",
            file=sys.stderr,
        )
        return 1

    runtimes = sorted(LIB_DIR.glob("libpython3.*.so"))
    if not runtimes:
        # A build shape with no Python is legitimate, and this check has nothing
        # to compare. It is not an excuse to pass silently on a tree that simply
        # has not been built yet, so say which it was.
        print(f"  skip   no libpython in {LIB_DIR.relative_to(REPO)}; nothing to compare")
        return 0
    if len(runtimes) > 1:
        print(
            "FAIL more than one Python runtime is in the asset tree: "
            + ", ".join(p.name for p in runtimes)
            + "\n     download-python.sh removes superseded ones, so this tree was assembled\n"
            "     by something else and the version this check reads is a coin toss.",
            file=sys.stderr,
        )
        return 1

    bundled = runtimes[0].name[len("libpython"):-len(".so")]
    if bundled != declared:
        tagged = {p["name"] for p in manifest.get("packages", [])}
        print(
            f"FAIL the wheelhouse is pinned to Python {declared} and the APK carries {bundled}.\n"
            f"     Every wheel in it would install nowhere: {', '.join(sorted(tagged)) or 'none listed'}.\n"
            "     Re-pin wheelhouse.json against the new interpreter and rebuild the\n"
            "     wheelhouse release, keeping the previous version's page for devices\n"
            "     still on the older APK. docs/10-RELEASE_PLAN.md section 8.3.",
            file=sys.stderr,
        )
        return 1

    print(f"  ok     wheelhouse pinned to Python {declared}, which is what the APK carries, and its page matches")
    return 0


if __name__ == "__main__":
    sys.exit(main())
