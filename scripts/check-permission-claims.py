#!/usr/bin/env python3
"""Hold the published privacy policy to the permissions the app really holds.

    check-permission-claims.py

`docs/PRIVACY_POLICY.md` is the document a Play reviewer and a suspicious user
read, and it is the one place in this repository that makes a closed statement
about permissions: these, and no others. Nothing measured that statement. It
said "four Android permissions and no others" while the installed app declared
six, and had said so for at least one release, because the two extras arrive
after the file anybody edits: AGP's manifest merger folds in the permissions of
every library, and the Play listing shows the merged set rather than ours.

So the subject here is not our manifest. It is the manifest that ships.

Two halves, and the script says which of them ran, because the interesting one
is not always available:

  * The source manifest, always. `android/app/src/main/AndroidManifest.xml` is
    committed, so this half answers on a bare checkout and is what makes the
    check runnable from lint.yml and from release.yml's pre-build gates.
  * The merged manifest, when a build has produced one. That is the half that
    notices a dependency adding a permission nobody asked for, which is the
    drift the source half cannot see by construction. It is wired to the release
    manifest task in `android/app/build.gradle.kts`, so a real build always runs
    it; a checkout with no build output runs the first half alone and says so.

Names are compared on their last dot-separated segment. That is not laziness
about `android.permission.` prefixes: the app-defined receiver permission is
named after the applicationId, so it is `com.vscodroid.DYNAMIC_RECEIVER_...` in
a release build and `com.vscodroid.debug.DYNAMIC_RECEIVER_...` in a debug one,
and a comparison on the full name would answer differently for the same
document depending on which variant last built. The cost is that two permissions
sharing a final segment would be conflated; nothing in this app or its
dependencies has that shape, and a collision would show up as a name the
document is asked for and does not carry.
"""

import pathlib
import re
import sys
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parent.parent
SOURCE_MANIFEST = ROOT / "android/app/src/main/AndroidManifest.xml"
POLICY = ROOT / "docs/PRIVACY_POLICY.md"
MERGED_GLOB = "android/app/build/intermediates/merged_manifest/*/*/AndroidManifest.xml"

ANDROID_NS = "http://schemas.android.com/apk/res/android"
TOOLS_NS = "http://schemas.android.com/tools"
NAME = f"{{{ANDROID_NS}}}name"
NODE = f"{{{TOOLS_NS}}}node"

# Same two element names and the same removing-node set as
# check-local-network-permission.py, for the same reasons: <permission> defines
# one this app hands out rather than asks for, a component merely named after a
# permission is not a declaration, and `tools:node="remove"` deletes one.
PERMISSION_ELEMENTS = ("uses-permission", "uses-permission-sdk-23")
REMOVING_NODES = ("remove", "removeAll")

# What the merger is expected to add on top of our own four. Written down rather
# than derived, so that a library adding a permission is a change somebody has to
# make here and explain in the policy, instead of one that lands quietly. The
# merged half below checks this list against a real merged manifest whenever one
# exists, so it cannot rot into a fiction of its own.
MERGED_IN = {
    # com.google.android.play:asset-delivery, which backs toolchain downloads.
    "FOREGROUND_SERVICE_DATA_SYNC",
    # AndroidX, for its own runtime-registered receivers. Signature level, and
    # named after the applicationId, which is why names are compared on their
    # last segment.
    "DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
}

# A permission name as the policy writes it: in backticks, and recognised by its
# last segment being SHOUTING_CASE. That excludes the other backticked things in
# the document (`localhost`, `product.json`, `com.android.vending`) without
# needing a list of them, and it means a permission the policy mentions only in
# prose is not counted, which is deliberate: the document has to name it as a
# permission for a reader to be able to check it against the Play listing.
BACKTICKED = re.compile(r"`([A-Za-z0-9_.]+)`")
SHOUTING = re.compile(r"^[A-Z][A-Z0-9_]{2,}$")


def leaf(name: str) -> str:
    return name.rsplit(".", 1)[-1]


def fail(message: str) -> int:
    print(f"  FAIL   {message}", file=sys.stderr)
    return 1


def declared_in(manifest_xml: str) -> set:
    """The permissions a manifest asks the platform for, by last segment."""
    root = ET.fromstring(manifest_xml)
    # The name is read once into a local rather than fetched again inside the
    # comprehension: `get` returns an optional, and asking twice leaves the
    # value that reaches `leaf` typed as though it could still be absent.
    names = set()
    for element in root.iter():
        if element.tag not in PERMISSION_ELEMENTS:
            continue
        if element.get(NODE) in REMOVING_NODES:
            continue
        name = element.get(NAME)
        if name:
            names.add(leaf(name))
    return names


def named_in_policy(text: str) -> set:
    return {
        leaf(m.group(1))
        for m in BACKTICKED.finditer(text)
        if SHOUTING.match(leaf(m.group(1)))
    }


# Controls the readers are run against on every invocation rather than in a test
# somebody remembers. A reader that has quietly stopped recognising a
# declaration, or a policy scan that has stopped recognising a name, both print
# the same reassuring line as a tree that is genuinely in agreement.
_CONTROLS = (
    (
        "a real declaration",
        f'<manifest xmlns:android="{ANDROID_NS}">'
        '<uses-permission android:name="android.permission.INTERNET" /></manifest>',
        {"INTERNET"},
    ),
    (
        "a declaration the merger is told to remove",
        f'<manifest xmlns:android="{ANDROID_NS}" xmlns:tools="{TOOLS_NS}">'
        '<uses-permission android:name="android.permission.INTERNET" '
        'tools:node="remove" /></manifest>',
        set(),
    ),
    (
        "a component merely named after a permission",
        f'<manifest xmlns:android="{ANDROID_NS}"><application>'
        '<activity android:name="android.permission.INTERNET" />'
        "</application></manifest>",
        set(),
    ),
)

_POLICY_CONTROLS = (
    ("a backticked permission", "holds `INTERNET` and nothing else", {"INTERNET"}),
    ("an applicationId-scoped one", "`com.vscodroid.DYNAMIC_RECEIVER_X`",
     {"DYNAMIC_RECEIVER_X"}),
    ("a backticked lowercase token", "installed from `com.android.vending`", set()),
    ("a permission named only in prose", "requires the INTERNET permission", set()),
)


def merged_manifests():
    """Every merged manifest a build has left behind, newest first."""
    found = sorted(ROOT.glob(MERGED_GLOB), key=lambda p: p.stat().st_mtime, reverse=True)
    return found


def main() -> int:
    for label, xml, expected in _CONTROLS:
        if declared_in(xml) != expected:
            return fail(
                f"the manifest reader answers {declared_in(xml)} for {label}, so its "
                "verdict on the real manifest cannot be trusted"
            )
    for label, text, expected in _POLICY_CONTROLS:
        if named_in_policy(text) != expected:
            return fail(
                f"the policy reader answers {named_in_policy(text)} for {label}, so "
                "its verdict on the real document cannot be trusted"
            )

    for path in (SOURCE_MANIFEST, POLICY):
        if not path.is_file():
            return fail(f"{path.relative_to(ROOT)} is missing; this check cannot run")

    try:
        own = declared_in(SOURCE_MANIFEST.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, ET.ParseError) as exc:
        # Fail closed. A manifest that cannot be parsed is a check that cannot
        # answer, and answering "nothing declared" would print the reassuring
        # line for a file nothing read.
        return fail(
            f"{SOURCE_MANIFEST.relative_to(ROOT)} could not be read as XML ({exc})"
        )
    if not own:
        return fail(
            f"{SOURCE_MANIFEST.relative_to(ROOT)} declares no permissions at all, "
            "which this app cannot run without; the reader or the file is wrong"
        )

    stated = named_in_policy(POLICY.read_text(encoding="utf-8"))
    expected = own | MERGED_IN

    unstated = sorted(expected - stated)
    if unstated:
        return fail(
            f"{POLICY.relative_to(ROOT)} does not name {len(unstated)} permission(s) "
            "the installed app holds:\n         "
            + ", ".join(unstated)
            + "\n         The Play listing shows the merged set, so a reader "
            "comparing the two\n         finds a capability the policy says "
            "nothing about."
        )

    invented = sorted(stated - expected)
    if invented:
        return fail(
            f"{POLICY.relative_to(ROOT)} names {len(invented)} permission(s) the app "
            "does not hold:\n         "
            + ", ".join(invented)
            + "\n         Either the manifest dropped one, or the document is "
            "describing an app\n         that no longer exists."
        )

    print(f"  ok     the privacy policy names all {len(expected)} permissions the "
          f"installed app holds")
    print(f"         ours: {', '.join(sorted(own))}")
    print(f"         merged in: {', '.join(sorted(MERGED_IN))}")

    merged_paths = merged_manifests()
    if not merged_paths:
        # Not a pass for the half that did not run. The source manifest cannot
        # see a library's permissions at all, so saying nothing here would let a
        # reader take the line above for a verdict on the installed app.
        print("  note   no merged manifest on disk, so the two above were not "
              "checked against a build.")
        print("         Build once (any variant) and re-run to cover the "
              "libraries as well.")
        return 0

    path = merged_paths[0]
    try:
        merged = declared_in(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, ET.ParseError) as exc:
        return fail(f"{path.relative_to(ROOT)} could not be read as XML ({exc})")

    added = merged - own
    if added != MERGED_IN:
        return fail(
            f"the manifest merger no longer adds what this check records.\n"
            f"         recorded: {', '.join(sorted(MERGED_IN)) or '(none)'}\n"
            f"         measured: {', '.join(sorted(added)) or '(none)'}\n"
            f"         read from {path.relative_to(ROOT)}\n"
            "         A dependency changed what the installed app asks for. "
            "Update MERGED_IN\n         here and say so in "
            "docs/PRIVACY_POLICY.md and docs/06-SECURITY.md, which are\n"
            "         what a user and a Play reviewer read."
        )

    print(f"  ok     {path.relative_to(ROOT)} adds exactly the "
          f"{len(added)} recorded above")
    return 0


if __name__ == "__main__":
    sys.exit(main())
