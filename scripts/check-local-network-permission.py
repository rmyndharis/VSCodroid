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

ROOT = pathlib.Path(__file__).resolve().parent.parent
GRADLE = ROOT / "android/app/build.gradle.kts"
MANIFEST = ROOT / "android/app/src/main/AndroidManifest.xml"

PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
ENFORCED_FROM_SDK = 37


def fail(message: str) -> int:
    print(f"  FAIL   {message}", file=sys.stderr)
    return 1


def main() -> int:
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
    declared = PERMISSION in MANIFEST.read_text()

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
