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
    Not every merged manifest on disk is this app's. AGP writes one for the
    instrumentation APK too, and `debugAndroidTest` declares REORDER_TASKS and
    none of ours, so taking the newest file failed on a developer machine that
    had just built it, and told the maintainer to publish a permission the
    shipped app does not hold. Test variants are dropped, release wins over any
    other however fresh, and the verdict names the variant it read.

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
# The variant whose manifest ships, and so the one a Play listing is a view of.
# Preferred over every other on disk however fresh, because which Gradle task a
# developer ran last must not decide what this check is a verdict about.
SHIPPING_VARIANT = "release"

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

# The third reader run against controls on every invocation, for the reason the
# two above give: this one decides WHICH manifest the verdict is about, and it
# had no opinion at all. The items are their own labels, so a case reads as the
# order it asserts. Triples are (variant, mtime, item).
_ORDER_CONTROLS = (
    (
        "the instrumentation APK built last",
        (("release", 1.0, "release"), ("debugAndroidTest", 9.0, "androidTest")),
        ["release"],
    ),
    (
        "a debug build newer than the release one",
        (("release", 1.0, "release"), ("debug", 9.0, "debug")),
        ["release", "debug"],
    ),
    (
        "no release build on disk",
        (("debug", 1.0, "debug"), ("debugAndroidTest", 9.0, "androidTest")),
        ["debug"],
    ),
)

_PATH_CONTROL = (
    pathlib.PurePosixPath(
        "android/app/build/intermediates/merged_manifest/debugAndroidTest/"
        "mergeDebugAndroidTestManifest/AndroidManifest.xml"
    ),
    "debugAndroidTest",
)


def variant_of(path) -> str:
    """merged_manifest/<variant>/<task>/AndroidManifest.xml -> <variant>."""
    return path.parent.parent.name


def app_variant(variant: str) -> bool:
    """Whether that variant directory holds a manifest of the app itself.

    AGP merges one for its test components too, and theirs describes a different
    APK. The suffix is AGP's own naming for those, so this also covers
    `releaseAndroidTest` and the unit-test variants without listing them; this
    project declares no product flavours, so no variant of the app can end that
    way.
    """
    return not variant.endswith("Test")


def in_judging_order(candidates):
    """(variant, mtime, item) triples, the one worth judging first.

    Test variants are dropped, then the shipping variant is put ahead of the
    rest, which stay newest first. Preferring release even when it is the older
    file is deliberate: under Gradle this runs finalizing the task that has just
    written it, and everywhere else the printed variant lets the reader see how
    old the tree they are judging is.
    """
    app = [c for c in candidates if app_variant(c[0])]
    app.sort(key=lambda c: (c[0] != SHIPPING_VARIANT, -c[1]))
    return [c[2] for c in app]


def merged_manifests():
    """Every merged manifest of the app itself, the one that ships first."""
    return in_judging_order(
        (variant_of(p), p.stat().st_mtime, p) for p in ROOT.glob(MERGED_GLOB)
    )


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
    path_control, expected_variant = _PATH_CONTROL
    if variant_of(path_control) != expected_variant:
        return fail(
            f"a merged-manifest path reads as variant "
            f"'{variant_of(path_control)}', so the selection below is filtering "
            "and ordering on the wrong part of the path"
        )
    for label, candidates, expected in _ORDER_CONTROLS:
        if in_judging_order(candidates) != expected:
            return fail(
                f"manifest selection answers {in_judging_order(candidates)} for "
                f"{label}, so the verdict below would be about the wrong build"
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
        print("  note   no merged manifest of the app on disk, so the two above "
              "were not checked against a build.")
        print("         Build once (debug or release) and re-run to cover the "
              "libraries as well.")
        return 0

    path = merged_paths[0]
    variant = variant_of(path)
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
            f"         read from the {variant} manifest, "
            f"{path.relative_to(ROOT)}\n"
            "         A dependency changed what the installed app asks for. "
            "Update MERGED_IN\n         here and say so in "
            "docs/PRIVACY_POLICY.md and docs/06-SECURITY.md, which are\n"
            "         what a user and a Play reviewer read."
        )

    if variant != SHIPPING_VARIANT:
        print(f"  note   judged the {variant} manifest; there is no "
              f"{SHIPPING_VARIANT} build on disk, and that is the one a Play "
              "listing shows")
    print(f"  ok     the {variant} merged manifest adds exactly the "
          f"{len(added)} recorded above")
    print(f"         {path.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
