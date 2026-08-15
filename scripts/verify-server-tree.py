#!/usr/bin/env python3
"""Check that a vscode-reh server tree is the one this app can actually run.

Used from both ends of the pivot: build-vscode-oss.sh runs it on what gulp
produced, and fetch-vscode-oss.sh runs it on what was downloaded. Same checks
either side, because the failures it catches are the ones that survive every
other gate and only show up on a device.

    verify-server-tree.py <tree>
"""

import json
import pathlib
import struct
import sys

# e_machine values from the ELF spec. The tree also carries Windows PE addons for
# extensions that never load here; those are skipped rather than flagged.
AARCH64 = 0xB7
MACHINES = {0x3E: "x86-64", AARCH64: "aarch64", 0x28: "arm", 0xF3: "riscv"}

# The paths VSCodroid loads by name. server.js:57 forks the first, rewrites the
# second on every start, and the Search service execs into the third.
REQUIRED = [
    "out/server-main.js",
    "product.json",
    # Moved in 1.133: @vscode/ripgrep became @vscode/ripgrep-universal, which
    # ships one binary per platform instead of one per install.
    "node_modules/@vscode/ripgrep-universal/bin/linux-arm64/rg",
    # Code - OSS is MIT and this tree is redistributed inside every APK, so the
    # copyright notice has to travel with it. product.json names it too.
    "LICENSE.txt",
    # What patch 0010 exists to keep: upstream's .moduleignore strips the SDK
    # entry the extension's own exports map points at, and on device that
    # surfaces as chat submit dying in ChatSessionsService. The android-arm64
    # aliases built at runtime resolve into this file, so a tree without it
    # ships a Copilot that renders but cannot send.
    "extensions/copilot/node_modules/@github/copilot/sdk/index.js",
]

failed = False


def check(ok, label, detail=""):
    global failed
    print(f"  {'ok     ' if ok else 'FAIL   '} {label}{'' if ok else '  ' + detail}")
    if not ok:
        failed = True


def main(tree):
    for rel in REQUIRED:
        check((tree / rel).exists(), rel)

    # Present only in Microsoft's build. Its presence means this is not the tree
    # we think it is, whatever the filename said.
    check(not (tree / "node_modules/vsda").exists(), "no vsda",
          "this is not an OSS tree")

    # gulp's node-linux-arm64 task ships a GNU/Linux Node whose interpreter does
    # not exist on Android. Nothing references it; the runtime uses
    # nativeLibraryDir/libnode.so. 92 MiB of dead weight in every APK.
    check(not (tree / "node").exists(), "no bundled GNU/Linux node",
          "prune it before packaging")

    # Every native module is built for the build host, and only node-pty and
    # @parcel/watcher are overlaid for Bionic afterwards. ripgrep is the one that
    # bites: its postinstall downloads a binary for whatever os.platform() and
    # arch() report, so an x86-64 build host yields a tree that installs cleanly,
    # passes every other check, and then fails at exec with Search silently
    # returning no results.
    wrong, checked = [], 0
    for path in sorted(tree.rglob("*")):
        if not path.is_file() or path.is_symlink():
            continue
        if path.suffix != ".node" and path.name != "rg":
            continue
        # Vendored packages ship one directory per platform and pick at runtime.
        # The copies for other platforms are inert here -- never loaded, never
        # executed -- so flagging them would only report upstream's packaging.
        # The check still catches what it is for: a binary on the path this
        # device actually loads, built for the wrong architecture.
        if any(part in str(path) for part in
               ("/linux-x64/", "/darwin-", "/win32-", "-x64-", "/x64/")):
            continue
        # A file the build cannot read, or cannot read far enough, is a failed
        # check rather than a crash. Without this the walk raises straight out of
        # main(), the remaining binaries go unexamined, and the script exits
        # non-zero having printed no FAIL line at all -- which makes every caller
        # that says "the line above names what failed" into a false statement.
        #
        # The exception set matches verify-android-elf.py's, deliberately. Both
        # scripts read the same header off the same files and can meet the same
        # states, and they disagreed on one: a file that opens fine but is shorter
        # than the 20 bytes the e_machine field needs. Measured, on a truncated
        # .node -- 19 bytes raised struct.error out of the unpack below while the
        # sibling reported "FAIL index out of range" and carried on. A truncated
        # download or an interrupted copy lands exactly there.
        try:
            head = path.open("rb").read(20)
            if head[:4] != b"\x7fELF":
                continue
            machine = struct.unpack_from("<H", head, 18)[0]
        except (OSError, struct.error, IndexError) as e:
            check(False, f"{path.relative_to(tree)} could not be read", str(e))
            continue
        checked += 1
        if machine != AARCH64:
            wrong.append((path.relative_to(tree), MACHINES.get(machine, hex(machine))))

    for rel, arch in wrong:
        check(False, f"{rel} is {arch}, not aarch64", "build on an arm64 host")
    if not wrong:
        check(True, f"{checked} native binaries are aarch64")

    product_path = tree / "product.json"
    if product_path.exists():
        # Same reason as the read above: unparseable is a verdict this script
        # should state, not an exception it should die of.
        try:
            product = json.loads(product_path.read_text())
        except (OSError, ValueError) as e:
            check(False, "product.json is readable JSON", str(e))
            product = None
    else:
        product = None
    if product is not None:
        check(product.get("nameLong") == "VSCodroid", "product.json is branded",
              f"nameLong = {product.get('nameLong')!r}")
        # workbench.js hardcodes *.vscode-cdn.net and the WebView cannot reach it;
        # the template being absent is what makes the Kotlin-side interception the
        # only path.
        check("webviewContentExternalBaseUrlTemplate" not in product,
              "no vscode-cdn.net template")
        # Asserted as an absence, not a presence. The gallery is deliberately not
        # set at build time: builtInExtensions.ts:96 downloads the bundled
        # js-debug extensions from whatever gallery product.json names, and Open
        # VSX repackages them, so their sha256 no longer matches the one
        # product.json records and the build fails on a checksum mismatch.
        # Left unset, the download goes to each extension's own GitHub release
        # where the hashes do match, and assets/server.js writes the Open VSX
        # gallery into product.json on every start instead.
        gallery = product.get("extensionsGallery", {}).get("serviceUrl", "")
        check("marketplace.visualstudio.com" not in gallery,
              "no Microsoft marketplace URL", f"serviceUrl = {gallery!r}")

    # The Mobile CSS block is appended to the packaged workbench.css at server
    # build time, and on 2026-08-15 its content changed — the Accounts/Manage
    # hide was removed — while the idempotency marker stayed the same. The
    # server tarball is rebuilt in place without the version moving, so a
    # workflow dispatched from a ref that predates the change would publish a
    # tree that passes every other check here and silently hides the section
    # again. Two assertions, one per failure direction: the marker missing
    # means the append never ran; the hide selector present means the block is
    # the old one. This runs on both sides — the build before publishing, the
    # fetch before packaging — so either end refuses the stale tree.
    wb_css = tree / "out/vs/code/browser/workbench/workbench.css"
    if wb_css.exists():
        try:
            css = wb_css.read_text(errors="replace")
        except OSError as e:
            check(False, "workbench.css is readable", str(e))
            css = None
    else:
        css = None
        check(False, "out/vs/code/browser/workbench/workbench.css exists",
              "the packaged web client is missing")
    if css is not None:
        check("VSCodroid: Mobile-friendly" in css,
              "workbench.css carries the mobile menu overrides",
              "the Mobile CSS append did not run")
        check('aria-label="Manage"' not in css,
              "workbench.css does not hide Accounts/Manage",
              "built from a ref that predates the 2026-08-15 un-hide")

    return 1 if failed else 0


if __name__ == "__main__":
    if len(sys.argv) != 2:
        print("usage: verify-server-tree.py <tree>", file=sys.stderr)
        sys.exit(2)
    root = pathlib.Path(sys.argv[1])
    if not root.is_dir():
        print(f"  FAIL    {root} is not a directory", file=sys.stderr)
        sys.exit(1)
    sys.exit(main(root))
