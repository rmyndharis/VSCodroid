#!/usr/bin/env python3
"""Check that a vscode-reh server tree is the one this app can actually run.

Used from both ends of the pivot: build-vscode-oss.sh runs it on what gulp
produced, and fetch-vscode-oss.sh runs it on what was downloaded. Same checks
either side, because the failures it catches are the ones that survive every
other gate and only show up on a device.

    verify-server-tree.py <tree>
"""

import json
import os
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
    # Presence is not enough here, the contents are read further down.
    "LICENSE.txt",
    # The other half of the same obligation, and it was required by nothing.
    # build-vscode-oss.sh copies it under `if [ -f ]`, so an upstream rename
    # skips the copy without a word, while NOTICE.md and README.md both tell the
    # reader it ships inside the server tree. Without it the APK redistributes
    # every dependency Code - OSS vendors with none of their notices.
    "ThirdPartyNotices.txt",
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


def present(path, label):
    """Path.exists(), reporting instead of raising. None means "could not tell".

    exists() re-raises anything that is not ENOENT/ENOTDIR/EBADF/ELOOP, so an
    unreadable tree root or an unsearchable parent makes the stat itself throw. Each
    of these calls used to do that, and the throw came before the walk's onerror
    could report anything -- so the script exited non-zero having printed no FAIL
    line at all, while two callers tell the reader "the FAIL line above names which
    check it did not meet". One helper rather than four try blocks, so the next
    existence check added here inherits the behaviour instead of reintroducing the
    bug.
    """
    try:
        return path.exists()
    except OSError as e:
        check(False, f"{label} could not be checked", str(e))
        return None


def main(tree):
    for rel in REQUIRED:
        found = present(tree / rel, rel)
        if found is not None:
            check(found, rel)

    # Present only in Microsoft's build. Its presence means this is not the tree
    # we think it is, whatever the filename said.
    found = present(tree / "node_modules/vsda", "node_modules/vsda")
    if found is not None:
        check(not found, "no vsda", "this is not an OSS tree")

    # gulp's node-linux-arm64 task ships a GNU/Linux Node whose interpreter does
    # not exist on Android. Nothing references it; the runtime uses
    # nativeLibraryDir/libnode.so. 92 MiB of dead weight in every APK.
    found = present(tree / "node", "node")
    if found is not None:
        check(not found, "no bundled GNU/Linux node", "prune it before packaging")

    # Every native module is built for the build host, and four are dealt with
    # for Bionic afterwards by build-native-addons.sh: node-pty, @parcel/watcher
    # and @vscode/sqlite3 are recompiled, @vscode/spdlog is replaced by a
    # JavaScript implementation and its .node deleted. ripgrep is the one that
    # bites: its postinstall downloads a binary for whatever os.platform() and
    # arch() report, so an x86-64 build host yields a tree that installs cleanly,
    # passes every other check, and then fails at exec with Search silently
    # returning no results.
    # os.walk with onerror, not rglob. pathlib's selector swallows a directory it
    # cannot list, so an unreadable subtree reported nothing at all -- unreadable
    # FILES were stated by the catch below while unreadable DIRECTORIES were
    # silent, which is the worse half: a whole subtree of binaries can vanish from
    # the count with the run still green.
    wrong, checked = [], 0
    unlistable = []
    candidates = []
    for dirpath, _, filenames in os.walk(tree, onerror=unlistable.append):
        for name in filenames:
            candidates.append(pathlib.Path(dirpath) / name)
    for err in unlistable:
        check(False, f"{err.filename} could not be listed", str(err))

    for path in sorted(candidates):
        # is_file() stats, and a directory that is readable but not searchable
        # (mode 0644) lets os.walk list a name while refusing to stat it -- scandir
        # succeeds so onerror never fires, and the stat then threw EACCES out of the
        # loop. Regular files only: this also excludes a FIFO or a device node named
        # *.node, which would otherwise be opened and block.
        try:
            if not path.is_file() or path.is_symlink():
                continue
        except OSError as e:
            check(False, f"{path.relative_to(tree)} could not be examined", str(e))
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
            head = path.open("rb").read(64)
            # Shorter than an ELF64 header is not "some other format this walk
            # should skip" -- nothing executable in this tree, PE addons included,
            # is under 64 bytes. Treating it as unrecognised is how a 3-byte rg
            # reached the end as "ok 0 native binaries are aarch64".
            if len(head) < 64:
                check(False, f"{path.relative_to(tree)} is {len(head)} bytes",
                      "truncated: too short to be an executable of any format")
                continue
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
    if not checked:
        # Zero examined is not a pass. This printed "ok 0 native binaries are
        # aarch64", which reads exactly like a clean verdict and is the same
        # nothing-was-checked-versus-everything-passed conflation the sibling
        # refuses an empty --dir sweep for. Reachable: on an assets-cache hit the
        # fetch and the addon build are both skipped while the tree is restored
        # whole, and this is the only gate that reads it.
        check(False, "no native binaries found to check",
              "a server tree carries node-pty, the file watcher and ripgrep at least")
    elif not wrong:
        check(True, f"{checked} native binaries are aarch64")

    product_path = tree / "product.json"
    # exists() inside the try as well -- it stats, and product.json sits at the tree
    # root, so an unreadable root reaches it. Unparseable, unreadable and
    # unstattable are all verdicts this script should state rather than die of.
    product = None
    try:
        if product_path.exists():
            product = json.loads(product_path.read_text())
    except (OSError, ValueError) as e:
        check(False, "product.json is readable JSON", str(e))
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
        # An absence again, and the only place it can be read. server-main.js
        # builds the translated string bundle's URL from nlsCoreBaseUrl and hands
        # the page an empty one when the key is missing, which is the whole of
        # why the interface is English only and why docs/USER_GUIDE.md says so.
        # Neither the branding overlay nor assets/server.js names the key, but
        # the overlay is merged ONTO Code - OSS's own product.json rather than
        # replacing it, so a VSCODE_VERSION bump that lands the key upstream
        # carries it into the built file with nothing in this repository having
        # changed. That path only exists in the built tree, which is why it is
        # checked here rather than beside the other two.
        nls = sorted(k for k in product if "nls" in k.lower())
        check(not nls, "no translated string bundle URL",
              f"product.json now carries {', '.join(nls)}. The interface is no longer "
              "English only; rewrite the 'The Interface Is English Only' entry in "
              "docs/USER_GUIDE.md and retire DisplayLanguageTest with it.")

    # LICENSE.txt was required to exist and then never opened, which made it the
    # one gate that could not see the thing it was added for. The pivot away from
    # Microsoft's pre-built server happened because that artifact carries
    # MICROSOFT PRE-RELEASE SOFTWARE LICENSE TERMS, 13 KB where MIT is 1.1 KB,
    # under this exact filename, so a tree built from it satisfied the check by
    # having the name. An empty file satisfied it too.
    #
    # Two assertions, one per failure direction, the same shape as the
    # workbench.css pair below: the MIT grant has to be there, and Microsoft's
    # terms must not. Matched on the operative sentence rather than on the "MIT
    # License" title line, because a title is the cheapest thing for a wrong file
    # to carry and says nothing about what follows it.
    #
    # Not covered, deliberately: whose copyright the notice names. Code - OSS's
    # own LICENSE.txt is Microsoft's copyright under MIT, which is correct and
    # has to stay, MIT is what requires it to travel with the copy.
    licence = None
    lic_path = tree / "LICENSE.txt"
    try:
        if lic_path.exists():
            licence = lic_path.read_text(errors="replace")
    except OSError as e:
        check(False, "LICENSE.txt is readable", str(e))
    if licence is not None:
        check("Permission is hereby granted, free of charge" in licence,
              "LICENSE.txt carries the MIT grant",
              f"{len(licence)} bytes and none of them are the MIT permission notice")
        check("PRE-RELEASE SOFTWARE LICENSE TERMS" not in licence.upper(),
              "LICENSE.txt is not Microsoft's pre-release licence",
              "this is the update-CDN artifact; it cannot be redistributed here")

    # Presence is checked in REQUIRED; this is the same not-enough that LICENSE.txt
    # had. A zero-byte notices file is what a truncated copy leaves behind, and it
    # discharges nothing. The contents are not otherwise inspected, upstream
    # rewrites this file every release and there is no stable sentence in it.
    tpn = tree / "ThirdPartyNotices.txt"
    try:
        size = tpn.stat().st_size if tpn.exists() else None
    except OSError as e:
        check(False, "ThirdPartyNotices.txt is readable", str(e))
        size = None
    if size is not None:
        check(size > 0, "ThirdPartyNotices.txt is not empty", "0 bytes")

    # The Mobile CSS block is appended to the packaged workbench.css at server
    # build time, and on 2026-08-15 its content changed (the Accounts/Manage
    # hide was removed) while the idempotency marker stayed the same. The
    # server tarball is rebuilt in place without the version moving, so a
    # workflow dispatched from a ref that predates the change would publish a
    # tree that passes every other check here and silently hides the section
    # again. Two assertions, one per failure direction: the marker missing
    # means the append never ran; the hide selector present means the block is
    # the old one. This runs on both sides (the build before publishing, the
    # fetch before packaging), so either end refuses the stale tree.
    wb_css = tree / "out/vs/code/browser/workbench/workbench.css"
    # exists() is inside the try for the same reason as the REQUIRED loop above: it
    # stats, and an unsearchable parent makes the stat itself raise. This was the
    # last unguarded one -- with out/ at mode 000 the earlier fixes reported their
    # failures and then this line still threw, so the run ended with a traceback
    # after two correct FAIL lines.
    css = None
    try:
        if wb_css.exists():
            css = wb_css.read_text(errors="replace")
        else:
            check(False, "out/vs/code/browser/workbench/workbench.css exists",
                  "the packaged web client is missing")
    except OSError as e:
        check(False, "workbench.css is readable", str(e))
    if css is not None:
        check("VSCodroid: Mobile-friendly" in css,
              "workbench.css carries the mobile menu overrides",
              "the Mobile CSS append did not run")
        check('aria-label="Manage"' not in css,
              "workbench.css does not hide Accounts/Manage",
              "built from a ref that predates the 2026-08-15 un-hide")

    # Everything below asserts an effect of a patch or of a build-vscode-oss.sh
    # stage, so all of it is answered by one prior question: was this tree built
    # from what this checkout holds? A tree that predates the work is not a tree
    # whose branding was lost, and the two have to read differently or the next
    # person spends an afternoon hunting a regression that never happened.
    #
    # The signal is the manifest build-vscode-oss.sh writes into every tree it
    # produces (check-patch-fingerprints.py --write-manifest). Presence only:
    # which patches, and whether they have been edited since, is the sharper
    # question check-patch-fingerprints.py answers, and it needs patches/, which
    # this script deliberately does not take -- it also runs inside the build
    # container against a tree that is not next to a checkout.
    #
    # Absent, the patch-dependent checks are skipped rather than run and reported
    # as failures. They cannot pass on such a tree, so running them adds nine FAIL
    # lines (one for callback.html's branding, eight nls phrases) that all
    # describe the same one fact, each of them phrased as a regression.
    #
    # Stated in both directions. A silent pass left the reader of a green run
    # with no line saying the provenance question had been asked at all, which is
    # the one question that reframes every check below it. The label is worded as
    # presence for the same reason the paragraph above gives: this script never
    # sees patches/, so it cannot say WHICH patches, only that the tree names a
    # set. check-patch-fingerprints.py is where that claim is computed.
    manifest = present(tree / "vscodroid-patches.json", "vscodroid-patches.json")
    if manifest:
        check(True, "the tree records which patches built it")
    elif manifest is False and os.environ.get("ALLOW_UNADAPTED"):
        # A deliberately unadapted build has no patches to record, so demanding
        # the manifest here failed the build for doing exactly what was asked --
        # the Patch manifest step and the fingerprint check next to it already
        # obey this variable, and this sibling, which is what actually decides
        # the exit code, was left behind. Downgraded rather than skipped so the
        # tree's state still appears in the output.
        #
        # Safe on the fetch side, where the variable means nothing: a tree with
        # no manifest is refused there by check-patch-fingerprints.py, which
        # fetch-vscode-oss.sh and the Gradle packaging gate both run and which
        # honours no such escape hatch.
        print("  note    no vscodroid-patches.json, and ALLOW_UNADAPTED is set: "
              "this tree was built without the patches on purpose")
    elif manifest is False:
        check(False, "the tree records which patches built it",
              "no vscodroid-patches.json at the tree root, so this tree predates "
              "them (or was built with ALLOW_UNADAPTED). The callback branding "
              "and nls checks are skipped: they describe what those patches do, "
              "and nothing here says the branding was lost. Rebuild the server "
              "with the \"Build Code - OSS server\" workflow and refetch.")

    # Two redistribution claims that hold for a tree of ANY age, so they run
    # before the gate below rather than inheriting it. Neither is a patch effect:
    # build-vscode-oss.sh's "Callback page" stage strips the embedded logo and its
    # Prune stage strips the sourceMappingURL comments, and both sit outside the
    # patches guard, so an ALLOW_UNADAPTED tree and a tarball predating the
    # manifest are exactly the trees these two have something to say about. Below
    # the gate they could only ever fire on a tree that already carried one.
    callback = tree / "out/vs/code/browser/workbench/callback.html"
    page = None
    try:
        if callback.exists():
            page = callback.read_text(errors="replace")
        else:
            check(False, "out/vs/code/browser/workbench/callback.html exists",
                  "the OAuth callback page is missing")
    except OSError as e:
        check(False, "callback.html is readable", str(e))
    if page is not None:
        # The VS Code logo travelled in this page as a 150 KB base64 PNG, and the
        # page is rendered in the user's own browser at the end of every extension
        # sign-in, so the image is displayed and redistributed rather than merely
        # present. This is the half of the strip that runs on the fetch side,
        # where nothing else would notice a tarball built before that stage.
        check("data:image" not in page, "callback.html embeds no image",
              "this is upstream's VS Code logo; it cannot be redistributed here")

    # The minify task ends every bundle with a sourceMappingURL comment naming
    # main.vscode-cdn.net. The .map files it points at are filtered out of the
    # package, so what a device gets is a Microsoft service URL sitting inside a
    # tree whose stated job is to carry none. build-vscode-oss.sh's Prune stage
    # strips them; this is the half that runs on the fetch side, where a tarball
    # built before that stage arrives with the right name and a digest that
    # verifies. The strip's own assertion cannot cover that, and this one cannot
    # cover a minifier that stops emitting the comment on its own line, so both
    # exist: the build refuses what it could not strip, and this refuses what
    # reached it unstripped either way.
    #
    # The whole tree, not the out/ subtree the strip walks. What is claimed here
    # is an absence in a redistributed artifact, and scoping the walk to the
    # subtree the strip touches made it a claim the walk could not check:
    # measured on this tree, out/ carries the URL nowhere and
    # extensions/copilot/dist/extension.js carries it once, as
    # `await t.fetch("https://main.vscode-cdn.net/extensions/copilotChat.json")`.
    # That is a live request to a Microsoft service rather than an inert
    # sourceMappingURL comment, and it was the single occurrence in the tree that
    # the narrow walk could not see.
    #
    # Copilot is exempted by name rather than by a filter that happens to miss
    # it. The extension is Microsoft's own, it ships as the chat provider, and
    # fetching its own configuration is what it does; there is no version of this
    # tree that carries Copilot and not that URL. Stating the exception keeps the
    # line true for everything this build produces, and a NEW file naming that
    # host anywhere else now fails instead of being out of scope.
    #
    # That host only, which is why the label names it. The webview machinery
    # names `vscode-cdn.net` hosts of its own and they are deliberate: measured
    # with `grep -rhoE '[a-z0-9.*{}-]*vscode-cdn\.net'` over the packaged tree
    # under android/app/src/main/assets/vscode-reh, 5 occurrences in 4 files
    # outside extensions/copilot and not one of them `main.`, being the webview
    # CSP `frame-src` entry in out/server-main.js, the `{{uuid}}.vscode-cdn.net`
    # webviewContentExternalBaseUrlTemplate default in workbench.js, and the bare
    # host that builds `vscode-resource.vscode-cdn.net` in workbench.js and in
    # both extension host bundles. Those are the addresses
    # VSCodroidWebViewClient's shouldInterceptRequest answers locally; the
    # workbench hardcodes them and product.json cannot reach them, so a tree
    # stripped of them would render no webview at all. Widening this walk to
    # every `vscode-cdn.net` would fail every build there is.
    #
    # 11,749 .js/.css files, 217 MiB, 0.6s on the packaged tree. The walk above
    # already listed them, so only the reads are new.
    copilot = tree / "extensions/copilot"
    carrying = []
    for path in candidates:
        # Regular files only, the same guard the ELF walk above applies and for
        # the same two reasons: tar restores symlinks and special files, so a
        # broken symlink named *.js would be read as a failure it is not, and a
        # FIFO named *.js would block the gate on the open. The packaged tree has
        # no symlinks today, which is why this is a guard rather than a fix.
        try:
            if not path.is_file() or path.is_symlink():
                continue
        except OSError as e:
            check(False, f"{path.relative_to(tree)} could not be examined", str(e))
            continue
        if path.suffix not in (".js", ".css") or copilot in path.parents:
            continue
        try:
            if b"main.vscode-cdn.net" in path.read_bytes():
                carrying.append(str(path.relative_to(tree)))
        except OSError as e:
            check(False, f"{path.relative_to(tree)} could not be read", str(e))
    check(not carrying,
          "no sourceMappingURL host (main.vscode-cdn.net) outside the Copilot extension",
          f"{len(carrying)} carrying main.vscode-cdn.net, "
          f"starting with {', '.join(carrying[:3])}")

    # Everything after this return depends on the patches, and a check appended
    # after it inherits that whether or not it should. A check that a tree of ANY
    # age must pass belongs ABOVE this line.
    if not manifest:
        return 1 if failed else 0

    # Product naming outside product.json, which is where the branding overlay
    # stops looking. Both of these are user-facing: the OAuth callback page is
    # rendered in the user's own browser at the end of every extension sign-in,
    # and the nls bundle carries the strings the workbench shows on its onboarding
    # screen and after installing an extension.
    #
    # Asserted as absences of the exact strings patches 0006 and 0011 rewrite, not
    # as a sweep for "VS Code": 140 other shipped strings mention Microsoft's
    # product nominatively, describing its extension API and its gallery, and
    # renaming those would make them false. See the header of patches/0011.
    if page is not None:
        check("Visual Studio Code" not in page, "callback.html is branded",
              "the sign-in page the system browser renders still names Microsoft's product")

    nls_bundle = tree / "out/nls.messages.js"
    messages = None
    try:
        if nls_bundle.exists():
            messages = nls_bundle.read_text(errors="replace")
        else:
            check(False, "out/nls.messages.js exists", "the string bundle is missing")
    except OSError as e:
        check(False, "nls.messages.js is readable", str(e))
    if messages is not None:
        for phrase in ("Welcome to VS Code",
                       "Welcome to Visual Studio Code",
                       "reload Visual Studio Code",
                       "reloading VS Code",
                       "Get Started with VS Code for the Web",
                       "Setup VS Code Web",
                       "VS Code accessible",
                       "Setup VS Code Accessibility"):
            check(phrase not in messages, f"no shipped string says {phrase!r}",
                  "patch 0011 no longer covers it, or upstream reintroduced it elsewhere")

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
