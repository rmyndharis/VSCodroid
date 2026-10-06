#!/usr/bin/env python3
"""Prepare extension payloads for the Android-hosted VS Code server.

    prepare-android-runtime.py [--check] <server-tree>

The built-in Copilot extension includes an optional Copilot CLI session which
loads GitHub's native CLI runtime and ships Linux native addons and helpers.
Those files cannot run on Android. Android keeps the normal Copilot chat
provider and the SDK JavaScript entry point, while the optional CLI session is
explicitly unavailable. The same guard also prevents the extension's eager
shim setup from copying Linux rg/node-pty binaries into the installed tree.

The Microsoft Authentication extension likewise bundles an optional native
broker module. Android uses its browser/device-code OAuth flows, so the broker
is disabled even when the host reports Linux. Its two native files are removed
after the extension bundle is marked no-broker.
"""

from __future__ import annotations

import argparse
import shutil
import sys
from pathlib import Path

MSAL_JS = Path("extensions/microsoft-authentication/dist/extension.js")
MSAL_NATIVE = (
    Path("extensions/microsoft-authentication/dist/libmsalruntime.so"),
    Path("extensions/microsoft-authentication/dist/msal-node-runtime.node"),
)
COPILOT_JS = Path("extensions/copilot/dist/extension.js")
COPILOT_GUARD = 'if(process.env.VSCODROID_PACKAGE)throw new Error("Copilot CLI sessions are unavailable on Android")'
COPILOT_CTOR_OLD = "this._ensureShimsPromise=this.ensureShims()"
COPILOT_CTOR_NEW = "this._ensureShimsPromise=process.env.VSCODROID_PACKAGE?Promise.resolve():this.ensureShims()"
COPILOT_PACKAGE_OLD = "async getPackage(){try{if(await this._ensureShimsPromise"
COPILOT_PACKAGE_NEW = "async getPackage(){try{if(process.env.VSCODROID_PACKAGE)throw new Error(\"Copilot CLI sessions are unavailable on Android\");if(await this._ensureShimsPromise"
COPILOT_NATIVE_DIRS = (
    Path("extensions/copilot/node_modules/@github/copilot/sdk/prebuilds/linux-x64"),
    Path("extensions/copilot/node_modules/@github/copilot/sdk/ripgrep/bin/linux-x64"),
    Path("extensions/copilot/node_modules/@github/copilot/sdk/tgrep/bin/linux-x64"),
    Path("extensions/copilot/node_modules/@github/copilot/tgrep/bin/linux-x64"),
)
MSAL_BROKER_BRANCH = 'else if(tt.workspace.getConfiguration("microsoft-authentication").get("implementation")==="msal-no-broker")'
MSAL_ANDROID_BRANCH = 'else if(process.env.VSCODROID_PACKAGE||tt.workspace.getConfiguration("microsoft-authentication").get("implementation")==="msal-no-broker")'


def rewrite(path: Path, old: str, new: str, label: str, check_only: bool) -> bool:
    if not path.is_file():
        print(f"FAIL   missing {label}: {path}", file=sys.stderr)
        return False
    text = path.read_text(encoding="utf-8", errors="surrogateescape")
    if text.count(new) == 1 and old not in text:
        print(f"  ok      {label} is prepared")
        return True
    if check_only:
        print(f"FAIL   {label} is not prepared", file=sys.stderr)
        return False
    if text.count(old) != 1 or new in text:
        print(f"FAIL   expected one unmodified {label} anchor, found {text.count(old)}", file=sys.stderr)
        return False
    path.write_text(text.replace(old, new, 1), encoding="utf-8", errors="surrogateescape")
    print(f"  patched {label}")
    return True


def prepare(tree: Path, check_only: bool) -> bool:
    ok = True
    msal = tree / MSAL_JS
    ok &= rewrite(msal, MSAL_BROKER_BRANCH, MSAL_ANDROID_BRANCH,
                  "MSAL Android no-broker branch", check_only)

    copilot = tree / COPILOT_JS
    ok &= rewrite(copilot, COPILOT_CTOR_OLD, COPILOT_CTOR_NEW,
                  "Copilot Android shim guard", check_only)
    ok &= rewrite(copilot, COPILOT_PACKAGE_OLD, COPILOT_PACKAGE_NEW,
                  "Copilot CLI Android-disabled guard", check_only)

    for rel in MSAL_NATIVE + COPILOT_NATIVE_DIRS:
        path = tree / rel
        if path.exists():
            if check_only:
                print(f"FAIL   Android-incompatible optional native payload remains: {rel}", file=sys.stderr)
                ok = False
            else:
                if path.is_dir():
                    shutil.rmtree(path)
                else:
                    path.unlink()
                print(f"  removed {rel}")
        elif check_only:
            print(f"  ok      absent {rel}")
    return bool(ok)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--check", action="store_true", help="verify prepared tree without changing it")
    ap.add_argument("tree", type=Path)
    args = ap.parse_args()
    if not args.tree.is_dir():
        print(f"FAIL   server tree does not exist: {args.tree}", file=sys.stderr)
        return 1
    return 0 if prepare(args.tree, args.check) else 1


if __name__ == "__main__":
    raise SystemExit(main())
