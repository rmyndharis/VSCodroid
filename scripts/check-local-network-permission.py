#!/usr/bin/env python3
"""Keep serving your app to other devices working across the targetSdk bump.

    check-local-network-permission.py

Running a dev server and handing the address to someone on the same Wi-Fi is
what this project exists for. Android is closing that path behind a permission,
and the closing is scheduled rather than hypothetical:

  * Android 16 -- opt-in. Local network access is open by default, which is why
    a server bound to 0.0.0.0 is reachable from another device today.
  * Android 17, and any app targeting SDK 37 or later -- blocked by default.
    ACCESS_LOCAL_NETWORK must be declared and granted at runtime. The platform
    documentation lists "Accepting an incoming TCP connection" as requiring it,
    so this covers a server running here being reached from elsewhere, not only
    this app reaching out.

Nothing announces that at build time. Raising targetSdk to 37 would produce a
green build, a working editor, and dev servers that no other device can reach --
with no error the user can act on, because their friend's browser simply times
out. The failure would arrive one release after the change that caused it.

So this asserts the pair: at targetSdk 37 or above, the manifest declares the
permission. It is deliberately not a demand to declare it now. The permission
would appear in the Play listing today for a capability the platform does not
yet withhold, and asking for something before it is needed is its own cost.

What this does not check, because it cannot: whether the runtime request is
actually made and handled when refused. A declaration is necessary and not
sufficient. Whoever raises targetSdk has to do that part, and this check exists
to make sure they find out from the build rather than from a review.
"""

import pathlib
import re
import sys
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parent.parent
GRADLE = ROOT / "android/app/build.gradle.kts"
MANIFEST = ROOT / "android/app/src/main/AndroidManifest.xml"

PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
ENFORCED_FROM_SDK = 37

# The SOURCE manifest, not the merged one. AGP merges this file with every
# library's manifest, so a permission a dependency adds is decided after this
# runs and is outside what this can see. That gap only ever fails a build whose
# merged manifest would have been fine, and declaring the permission here is the
# fix either way. The gap in the other direction is closed below.
ANDROID_NS = "http://schemas.android.com/apk/res/android"
TOOLS_NS = "http://schemas.android.com/tools"
NAME = f"{{{ANDROID_NS}}}name"
NODE = f"{{{TOOLS_NS}}}node"

# The two elements that request a permission the platform can grant. Deliberately
# NOT "any element with a name attribute": this file is mostly <activity
# android:name=...>, <service android:name=...> and <provider android:name=...>,
# so accepting any element would answer yes to a component that merely happened
# to be named after the permission. That is a weaker version of the same defect
# as the substring test this replaces, and harder to see, because it would look
# like a parse. <permission> is absent for the same reason: it defines a
# permission this app can hand out, which is the opposite operation.
PERMISSION_ELEMENTS = ("uses-permission", "uses-permission-sdk-23")

# The manifest-merger operations that delete a declaration written here. Without
# them a `tools:node="remove"` would read as a declaration and this would pass a
# build whose merged manifest has no permission at all, which is precisely the
# shape of failure this gate exists to catch. No other operation removes.
REMOVING_NODES = ("remove", "removeAll")

# Controls the reader is run against on every invocation, not only in a test
# somebody remembers to run. A reader that has quietly stopped recognising a
# declaration prints the same reassuring line as a tree that genuinely has none,
# and this gate is dormant at targetSdk 36, so nothing else would notice for as
# long as the bump takes.
_CONTROLS = (
    (
        "a real declaration",
        f'<manifest xmlns:android="{ANDROID_NS}">'
        f'<uses-permission android:name="{PERMISSION}" /></manifest>',
        True,
    ),
    (
        "a uses-permission-sdk-23 declaration",
        f'<manifest xmlns:android="{ANDROID_NS}">'
        f'<uses-permission-sdk-23 android:name="{PERMISSION}" /></manifest>',
        True,
    ),
    (
        "the literal in a comment and in a component name only",
        f'<manifest xmlns:android="{ANDROID_NS}"><!-- {PERMISSION} -->'
        f'<application><activity android:name="{PERMISSION}" />'
        f"</application></manifest>",
        False,
    ),
    (
        "a declaration the merger is told to remove",
        f'<manifest xmlns:android="{ANDROID_NS}" xmlns:tools="{TOOLS_NS}">'
        f'<uses-permission android:name="{PERMISSION}" tools:node="remove" />'
        f"</manifest>",
        False,
    ),
)


def fail(message: str) -> int:
    print(f"  FAIL   {message}", file=sys.stderr)
    return 1


def declares_permission(manifest_xml: str) -> bool:
    """Whether the manifest asks the platform for PERMISSION.

    Raises ElementTree.ParseError for input that does not parse. The caller
    fails on that rather than reading the absence as "not declared".
    """
    root = ET.fromstring(manifest_xml)
    return any(
        element.tag in PERMISSION_ELEMENTS
        and element.get(NAME) == PERMISSION
        and element.get(NODE) not in REMOVING_NODES
        for element in root.iter()
    )


def main() -> int:
    for label, xml, expected in _CONTROLS:
        if declares_permission(xml) is not expected:
            return fail(
                f"the manifest reader answers {not expected} for {label}, so its "
                "verdict on the real manifest cannot be trusted"
            )

    if not GRADLE.is_file():
        return fail(f"{GRADLE.relative_to(ROOT)} is missing; this check cannot run")
    if not MANIFEST.is_file():
        return fail(f"{MANIFEST.relative_to(ROOT)} is missing; this check cannot run")

    gradle = GRADLE.read_text()
    match = re.search(r"^\s*targetSdk\s*=\s*(\d+)", gradle, re.MULTILINE)
    if not match:
        # Not "assume it is fine": a targetSdk that cannot be read is a check
        # that cannot answer, and answering anyway is how a gate starts lying.
        return fail(
            f"no targetSdk found in {GRADLE.relative_to(ROOT)}; "
            "the check cannot tell whether the permission is required"
        )

    target = int(match.group(1))
    try:
        declared = declares_permission(MANIFEST.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, ET.ParseError) as exc:
        # Fail closed. A manifest that cannot be parsed is a check that cannot
        # answer, and answering "not declared" would print the reassuring line
        # for a file nothing read.
        return fail(
            f"{MANIFEST.relative_to(ROOT)} could not be read as XML ({exc}); "
            "the check cannot tell whether the permission is declared"
        )

    if target < ENFORCED_FROM_SDK:
        state = "declared" if declared else "not declared"
        print(f"  ok     targetSdk {target}: local network access is implicit "
              f"({PERMISSION} {state})")
        return 0

    if not declared:
        return fail(
            f"targetSdk {target} withholds local network access, and "
            f"{PERMISSION}\n"
            "         is not declared in the manifest. Dev servers running on "
            "this device would\n"
            "         stop being reachable from other devices, with nothing to "
            "tell the user why.\n"
            "         Declare the permission, request it at runtime where the "
            "user asks to serve\n"
            "         on the network, and keep working when it is refused."
        )

    print(f"  ok     targetSdk {target}: {PERMISSION} is declared")
    print("         (that it is also requested at runtime is not something "
          "this check can see)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
