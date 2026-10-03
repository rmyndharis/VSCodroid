#!/usr/bin/env python3
"""Refuse a VS Code source tree where patch 0022's premise no longer holds.

    check-editcontext-sync.py <vscode-source-root>
    check-editcontext-sync.py --self-test

0022 copies the text either side of an IME update from _previousEditContextText,
the line _updateEditContext last wrote. That is the right text only while both
model listeners, the content listener and onCursorStateChanged, call
_updateEditContext synchronously. Upstream 1.140.0 defers both to prepareRender
(microsoft/vscode#310901), and 0022 still applies there. Going by the patched
source every fingerprint row would pass there too, though no 1.140.0 bundle was
built to confirm it, so nothing else in the build notices. Exits 1 naming what
moved, the file itself included, since 0022 has to be reworked then too; 2 when
the file is there but cannot be read, which says nothing about upstream. It sees
only whether the direct call leaves those two bodies: a deferral inside
_updateEditContext itself would pass.

build-vscode-oss.sh runs this on the patched tree, check-patches-apply.sh on
upstream's before any patch, so a patch that stops applying first cannot hide
it. 0022 touches neither caller, so both read the same text; a rework of 0022
that changes a caller has to change this check too.
"""

import contextlib
import io
import pathlib
import sys
import tempfile

FILE = "src/vs/editor/browser/controller/editContext/native/nativeEditContext.ts"
PATCH = "patches/0022-editcontext-buffer-and-caret-sync.patch"
CALL = "this._updateEditContext()"

# (caller, where its body starts, where it ends)
CALLERS = (
    ("the content listener", "model.onDidChangeContent(", "}));"),
    ("onCursorStateChanged", "public override onCursorStateChanged(", "\n\t}"),
)


def problems(text):
    found = []
    for caller, start, end in CALLERS:
        i = text.find(start)
        j = text.find(end, i) if i >= 0 else -1
        if j < 0:
            found.append(f"{caller} not found")
        elif CALL not in text[i:j]:
            found.append(f"{caller} no longer calls {CALL} directly")
    return found


def main(root):
    path = pathlib.Path(root) / FILE
    try:
        text = path.read_text(encoding="utf-8")
    except FileNotFoundError:
        found = ["not found"]
    except (OSError, UnicodeDecodeError) as e:
        print(f"  ERROR   {FILE}: cannot read it: {e}", file=sys.stderr)
        return 2
    else:
        found = problems(text)
    if not found:
        print(f"  ok      {FILE}: both callers update the EditContext synchronously")
        return 0
    for p in found:
        print(f"  FAILED  {FILE}: {p}", file=sys.stderr)
    print(f"  0022 copies pre-edit text from _previousEditContextText, which is current\n"
          f"  only while that update runs synchronously after every model change.\n"
          f"  Read the header of {PATCH}\n"
          f"  and rework the patch before pinning this version.",
          file=sys.stderr)
    return 1


def self_test():
    sync = ("model.onDidChangeContent((e) => {\n\t\tif (d) {\n\t\t\tthis._updateEditContext();\n"
            "\t\t}\n\t}));\n\tpublic override onCursorStateChanged(e) {\n"
            "\t\tthis._updateEditContext();\n\t\treturn true;\n\t}\n")
    deferred = sync.replace("this._updateEditContext();", "this._editContextNeedsRefresh = true;")
    content_deferred = sync.replace("\t\t\tthis._updateEditContext();", "\t\t\tthis._editContextNeedsRefresh = true;")
    cases = [
        ("synchronous callers pass", problems(sync) == []),
        ("both deferred fail twice", len(problems(deferred)) == 2),
        ("content listener deferred fails", problems(content_deferred) == [
            "the content listener no longer calls this._updateEditContext() directly"]),
        ("a renamed caller fails", problems(sync.replace("onCursorStateChanged", "x")) == [
            "onCursorStateChanged not found"]),
    ]
    with tempfile.TemporaryDirectory() as root:
        target = pathlib.Path(root) / FILE

        def run():
            with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
                return main(root)

        cases.append(("a moved file fails as drift", run() == 1))
        target.mkdir(parents=True)
        cases.append(("an unreadable file is not drift", run() == 2))
        target.rmdir()
        target.write_bytes(b"\xff" + sync.encode())
        cases.append(("an undecodable file is not drift", run() == 2))
        target.write_text(sync, encoding="utf-8")
        cases.append(("a synchronous file passes", run() == 0))
    for name, ok in cases:
        print(f"  {'ok' if ok else 'FAILED':<8}{name}")
    return 0 if all(ok for _, ok in cases) else 1


if __name__ == "__main__":
    if sys.argv[1:] == ["--self-test"]:
        sys.exit(self_test())
    if len(sys.argv) != 2:
        print(__doc__.split("\n\n")[1], file=sys.stderr)
        sys.exit(2)
    sys.exit(main(sys.argv[1]))
