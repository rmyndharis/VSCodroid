#!/usr/bin/env python3
"""Offline regression checks for packaging and device maintenance commands."""

import base64
import contextlib
import hashlib
import io
import json
import os
from pathlib import Path
import shutil
import struct
import subprocess
import sys
import tempfile
import types
import unittest
import xml.etree.ElementTree as ET
import zipfile


def deny_network(event, args):
    if event.startswith("socket."):
        raise RuntimeError("Unexpected network access in an offline fixture")


sys.addaudithook(deny_network)
sys.dont_write_bytecode = True
SOURCE = Path(__file__).resolve().parents[1]


def write(path, text, executable=False):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")
    if executable:
        path.chmod(0o700)


def run(command, cwd, extra=None):
    env = {"PATH": "/usr/bin:/bin", "LC_ALL": "C", "PYTHONDONTWRITEBYTECODE": "1",
           "TMPDIR": str(cwd), **(extra or {})}
    result = subprocess.run(command, cwd=cwd, env=env, capture_output=True,
                            text=True, timeout=30)
    return {"status": result.returncode, "stdout": result.stdout, "stderr": result.stderr}


def elf_import(soname, version, symbol):
    """Parser-shaped ELF64 fixture, never loaded or executed as machine code."""
    names = b"\0.shstrtab\0.dynstr\0.dynsym\0.gnu.version\0.gnu.version_r\0"
    strings = b"\0" + soname.encode() + b"\0" + version.encode() + b"\0" + symbol.encode() + b"\0"
    sym = bytes(24) + struct.pack("<IBBHQQ", strings.index(symbol.encode()), 0x12, 0, 0, 0, 0)
    versym = struct.pack("<HH", 0, 2)
    verneed = struct.pack("<HHIII", 1, 1, strings.index(soname.encode()), 16, 0)
    verneed += struct.pack("<IHHII", 0, 0, 2, strings.index(version.encode()), 0)
    pieces = [(".shstrtab", 3, names, 0, 0), (".dynstr", 3, strings, 0, 0),
              (".dynsym", 11, sym, 2, 24), (".gnu.version", 0x6FFFFFFF, versym, 3, 2),
              (".gnu.version_r", 0x6FFFFFFE, verneed, 2, 0)]
    data = bytearray(64)
    sections = [bytes(64)]
    for name, kind, body, link, entsize in pieces:
        data.extend(bytes((-len(data)) % 8))
        offset = len(data)
        data.extend(body)
        sections.append(struct.pack("<IIQQQQIIQQ", names.index(name.encode()), kind,
                                    0, 0, offset, len(body), link, 0, 8, entsize))
    data.extend(bytes((-len(data)) % 8))
    shoff = len(data)
    data.extend(b"".join(sections))
    ident = b"\x7fELF\x02\x01\x01" + bytes(9)
    data[:64] = struct.pack("<16sHHIQQQIHHHHHH", ident, 3, 183, 1, 0, 0,
                           shoff, 0, 64, 0, 0, 64, len(sections), 1)
    return bytes(data)


def forwarders(source, root):
    root.mkdir()
    stubs = root / "stubs"
    stubs.mkdir()
    blocked = elf_import("libstdc++.so.6", "GLIBCXX_3.4", "fixture_symbol")
    shimmable = elf_import("libc.so.6", "GLIBC_2.17", "malloc")
    cases = {}
    specs = {"all-blocked": {"spdlog.node": blocked}, "empty": {},
             "tolerated-only": {"kerberos.node": blocked},
             "mixed": {"spdlog.node": blocked, "forward.node": shimmable},
             "shimmable-missing-stub": {"forward.node": shimmable}}
    for label, files in specs.items():
        tree = root / label
        tree.mkdir()
        for name, body in files.items():
            (tree / name).write_bytes(body)
        cases[label] = run([sys.executable, "-I", str(source), "--scan", str(tree),
                            "--verify-against", str(stubs)], root)
    cases["generate-all-blocked"] = run(
        [sys.executable, "-I", str(source), "--scan", str(root / "all-blocked"),
         "--out", str(root / "generated")], root)
    controls = (cases["empty"]["status"] == 0
                and cases["tolerated-only"]["status"] == 0
                and cases["mixed"]["status"] != 0
                and cases["shimmable-missing-stub"]["status"] != 0
                and cases["generate-all-blocked"]["status"] == 0)
    recognized = "spdlog.node needs libstdc++.so.6" in cases["all-blocked"]["stdout"]
    return {"cases": cases, "controls_pass": controls,

            "fixed_contract_pass": controls and recognized and cases["all-blocked"]["status"] != 0}


def shell_function(text, name):
    begin = text.index("\n" + name + "() {") + 1
    end = text.index("\n}\n", begin) + 3
    return text[begin:end]


SETUP_ADB = '''#!/bin/bash
printf '%s\n' "$*" >> "$CALL_LOG"
if [ "${1:-}" = -s ]; then
    [ "${2:-}" = fixture-device ] || exit 97
    shift 2
fi
case "$*" in
    devices) printf 'List of devices attached\nfixture-device\tdevice\n' ;;
    'devices -l') printf 'fixture-device device product:fixture\n' ;;
    'install -r '*)
        if [ "$SCENARIO" = install-fail ] || [ ! -f "$3" ]; then
            printf 'Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE]\n'; exit 1
        elif [ "$SCENARIO" = install-nonzero-success ]; then
            printf 'Success\n'; exit 1
        else
            printf 'Success\n'
        fi ;;
    'shell pm clear com.vscodroid.debug') printf 'Success\n' ;;
    *) printf 'Unexpected stub call: %s\n' "$*" >&2; exit 97 ;;
esac
'''


def device_setup(source, root):
    root.mkdir()
    text = source.read_text(encoding="utf-8")
    begin = text.index("DEVICE_COUNT=$(")
    end = text.index("# TEST 5: launch_app", begin)
    phase = text[begin:end]
    selection_begin = text.index('ADB="adb"\n')
    selection_end = text.index('\nfi\n', selection_begin) + len('\nfi\n')
    selection = text[selection_begin:selection_end]
    functions = "\n".join(shell_function(text, name) for name in ("pass", "fail", "skip", "vlog"))
    cases = {}
    for label in ("install-fail", "build-fail", "missing-apk", "install-nonzero-success", "success", "selected-device", "skip-install"):
        case = root / label
        case.mkdir()
        apk = case / "app.apk"
        if label != "missing-apk":
            apk.write_bytes(b"fixture-only-not-an-apk")
        write(case / "android/gradlew", "#!/bin/bash\nexit 1\n", True)
        write(case / "adb", SETUP_ADB, True)
        preamble = '''#!/bin/bash
set -uo pipefail
ROOT_DIR="$PWD"
APK_PATH="$PWD/app.apk"
PKG=com.vscodroid.debug
DEVICE=""
[ "$SCENARIO" = selected-device ] && DEVICE=fixture-device
PASS=0 FAIL=0 SKIP=0 FAILURES=""
RED="" GREEN="" YELLOW="" BOLD="" RESET=""
VERBOSE=false
SKIP_BUILD=true
SKIP_INSTALL=false
[ "$SCENARIO" = build-fail ] && SKIP_BUILD=false
if [ "$SCENARIO" = skip-install ]; then SKIP_BUILD=false; SKIP_INSTALL=true; fi
'''
        script = preamble + selection + functions + "\n" + phase
        script += '\nprintf completed > "$PWD/continued"\n[ "$FAIL" -eq 0 ]\n'
        write(case / "setup.sh", script)
        observed = run(["/bin/bash", str(case / "setup.sh")], case,
                       {"SCENARIO": label, "CALL_LOG": str(case / "calls.txt"),
                        "PATH": str(case) + ":/usr/bin:/bin"})
        calls = (case / "calls.txt").read_text().splitlines()
        commands = [line.removeprefix("-s fixture-device ") for line in calls]
        observed.update({"calls": calls, "continued": (case / "continued").exists(),
                         "clears": sum(line.startswith("shell pm clear ") for line in commands),
                         "installs": sum(line.startswith("install ") for line in commands)})
        cases[label] = observed
    controls = (cases["success"]["status"] == 0 and cases["success"]["clears"] == 1
                and cases["success"]["installs"] == 1 and cases["skip-install"]["status"] == 0
                and cases["skip-install"]["clears"] == 0 and cases["skip-install"]["installs"] == 0)
    selected = cases["selected-device"]
    controls = (controls and selected["status"] == 0 and selected["installs"] == 1
                and selected["clears"] == 1
                and all(line.startswith("-s fixture-device ") for line in selected["calls"]))
    failing = ("install-fail", "build-fail", "missing-apk", "install-nonzero-success")

    fixed = controls and all(cases[label]["status"] != 0 and cases[label]["clears"] == 0
                             and not cases[label]["continued"] for label in failing)
    fixed = fixed and cases["build-fail"]["installs"] == 0 and cases["missing-apk"]["installs"] == 0
    return {"cases": cases, "controls_pass": controls,
             "fixed_contract_pass": fixed}


LAUNCH_ADB = '''#!/bin/bash
printf '%s\n' "$*" >> "$CALL_LOG"
case "$*" in
    'shell am force-stop com.vscodroid.debug'|'shell am start -n com.vscodroid.debug/com.vscodroid.SplashActivity') exit 0 ;;
    'shell uiautomator dump /sdcard/ui.xml') exit 0 ;;
    'shell cat /sdcard/ui.xml') printf '<hierarchy rotation="0"></hierarchy>\n' ;;
    'shell run-as com.vscodroid.debug id -u') printf '10123\n' ;;
    'shell cmd package list packages -U com.vscodroid.debug'|'shell pm list packages -U com.vscodroid.debug') printf 'package:com.vscodroid.debug uid:10123\n' ;;
    'shell ps -A'|'shell ps -A -o UID,ARGS')
        if [ "$*" = 'shell ps -A' ]; then
            printf 'USER PID PPID VSZ RSS WCHAN ADDR S NAME\n'
            case "$SCENARIO" in
                unrelated) printf 'u0_a224 200 1 1000 100 0 0 S /data/app/other/libnode.so\nu0_a224 201 200 1000 100 0 0 S /data/app/other/libnode.so\n' ;;
                mixed) printf 'u0_a123 300 1 1000 100 0 0 S /data/app/debug/libnode.so\nu0_a224 201 200 1000 100 0 0 S /data/app/other/libnode.so\n' ;;
                target) printf 'u0_a123 300 1 1000 100 0 0 S /data/app/debug/libnode.so\nu0_a123 301 300 1000 100 0 0 S /data/app/debug/libnode.so\n' ;;
            esac
        else
            printf 'UID ARGS\n'
            case "$SCENARIO" in
                unrelated) printf '10224 /data/app/other/libnode.so\n10224 /data/app/other/libnode.so\n' ;;
                mixed) printf '10123 /data/app/debug/libnode.so\n10224 /data/app/other/libnode.so\n' ;;
                target) printf '10123 /data/app/debug/libnode.so\n10123 /data/app/debug/libnode.so\n' ;;
            esac
        fi ;;
    'shell screencap -p /sdcard/launch-failed.png'|'pull /sdcard/launch-failed.png .') exit 0 ;;
    *) printf 'Unexpected stub call: %s\n' "$*" >&2; exit 97 ;;
esac
'''


def device_launch(source, root):
    root.mkdir()
    bin_dir = root / "bin"
    bin_dir.mkdir()
    (bin_dir / "python3").symlink_to(sys.executable)
    write(bin_dir / "sleep", "#!/bin/sh\nexit 0\n", True)
    write(bin_dir / "seq", '#!/bin/bash\nfor ((n=$1; n<=$2; n++)); do printf "%s\\n" "$n"; done\n', True)
    write(bin_dir / "adb", LAUNCH_ADB, True)
    cases = {}
    for label in ("unrelated", "mixed", "target", "none"):
        case = root / label
        case.mkdir()
        observed = run(["/bin/bash", str(source)], case,
                       {"PATH": str(bin_dir) + ":/usr/bin:/bin", "ADB": str(bin_dir / "adb"),
                        "PKG": "com.vscodroid.debug", "SCENARIO": label,
                        "CALL_LOG": str(case / "calls.txt")})
        observed["calls"] = (case / "calls.txt").read_text().splitlines()
        cases[label] = observed
    controls = (cases["target"]["status"] == 0 and "server up" in cases["target"]["stdout"]
                and cases["none"]["status"] != 0 and "server up" not in cases["none"]["stdout"])

    fixed = controls and all(cases[label]["status"] != 0 and "server up" not in cases[label]["stdout"]
                             for label in ("unrelated", "mixed"))
    return {"cases": cases, "controls_pass": controls,
             "fixed_contract_pass": fixed,
            "limit": "The target control establishes the current process-count condition only, not server or UI readiness."}


def wheelhouse(source, root):
    root.mkdir()
    module = types.ModuleType("fixture_wheelhouse")
    module.__file__ = str(source)
    exec(compile(source.read_text(encoding="utf-8"), str(source), "exec"), module.__dict__)
    tag = "cp314-cp314-android_24_arm64_v8a"
    filename = "fixture-1.0-" + tag + ".whl"
    wheel = root / filename
    files = {"fixture/__init__.py": "VALUE = 1\n", "fixture-1.0.dist-info/LICENSE": "Fixture license.\n",
             "fixture-1.0.dist-info/METADATA": "Metadata-Version: 2.1\nName: fixture\nVersion: 1.0\n",
             "fixture-1.0.dist-info/WHEEL": "Wheel-Version: 1.0\nRoot-Is-Purelib: false\nTag: " + tag + "\n",
             "fixture-1.0.dist-info/RECORD": ""}
    records = []
    for name, body in sorted(files.items()):
        if name.endswith("/RECORD"):
            continue
        content = body.encode()
        hash_text = base64.urlsafe_b64encode(hashlib.sha256(content).digest()).rstrip(b"=").decode()
        records.append(f"{name},sha256={hash_text},{len(content)}")
    files["fixture-1.0.dist-info/RECORD"] = "\n".join(records + ["fixture-1.0.dist-info/RECORD,,"]) + "\n"
    with zipfile.ZipFile(wheel, "w", zipfile.ZIP_STORED) as archive:
        for name, body in sorted(files.items()):
            archive.writestr(zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0)), body)
    digest = hashlib.sha256(wheel.read_bytes()).hexdigest()
    url = "https://fixture.invalid/" + filename
    def download(requested, destination):
        assert requested == url, requested
        shutil.copyfile(wheel, destination)
    module.download = download
    module.check_not_yanked = lambda name, version: False
    cases = {}
    for label in ("relative", "absolute-outside", "default-inside", "check-relative", "digest-mismatch"):
        case = root / label
        case.mkdir()
        repo = case / "repo"
        repo.mkdir()
        module.REPO = repo
        module.MANIFEST = repo / "wheelhouse.json"
        dist = case / "published-dist"
        dist.mkdir()
        stale = dist / "old.whl"
        stale.write_bytes(b"old-dist-marker")
        out = Path("wheels.html") if label in ("relative", "check-relative") else case / "custom/wheels.html"
        if label == "default-inside":
            out = module.page_path("3.14")
        args = types.SimpleNamespace(record=False, check=label == "check-relative", dist=dist)
        package = {"name": "fixture", "version": "1.0", "url": url,
                   "sha256": "0" * 64 if label == "digest-mismatch" else digest}
        manifest = {"python-version": "3.14", "release-tag": "fixture-wheels-3.14-1", "packages": [package]}
        work = case / "work"
        work.mkdir()
        stdout, stderr = io.StringIO(), io.StringIO()
        observation = {"return": None, "exception": None}
        before = Path.cwd()
        try:
            os.chdir(case)
            with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
                try:
                    observation["return"] = module.build(args, manifest, "3.14", tag, out, work)
                except (Exception, SystemExit) as exc:
                    observation["exception"] = type(exc).__name__
                    observation["message"] = str(exc)
            actual_out = out if out.is_absolute() else case / out
            observation.update({"page_written": actual_out.is_file(), "stale_preserved": stale.exists(),
                                "wheel_published": (dist / filename).is_file(),
                                "stdout": stdout.getvalue(), "stderr": stderr.getvalue()})
            if actual_out.is_file():
                page_text = actual_out.read_text()
                expected_page = (
                    '<!DOCTYPE html>\n<html><head><meta charset="utf-8">\n'
                    '<title>VSCodroid wheelhouse</title></head>\n<body>\n'
                    '<h1>VSCodroid wheelhouse</h1>\n'
                    '<p>Prebuilt Android wheels mirrored for the Python VSCodroid bundles.\n'
                    'Not compiled here, cffi excepted; the manifest records where each one came from.</p>\n'
                    f'    <a href="https://github.com/rmyndharis/VSCodroid/releases/download/fixture-wheels-3.14-1/{filename}#sha256={digest}">{filename}</a><br>'
                    '\n</body></html>\n'
                )
                observation["page_exact"] = page_text == expected_page
                observation["page_has_exact_release_link"] = (
                    f'https://github.com/rmyndharis/VSCodroid/releases/download/fixture-wheels-3.14-1/{filename}#sha256={digest}'
                    in page_text)
        finally:
            os.chdir(before)
        cases[label] = observation
    controls = (cases["default-inside"]["return"] == 0 and cases["default-inside"]["exception"] is None
                and cases["default-inside"]["page_exact"]
                and cases["check-relative"]["return"] == 0 and not cases["check-relative"]["page_written"]
                and cases["check-relative"]["stale_preserved"]
                and cases["digest-mismatch"]["exception"] == "SystemExit"
                and not cases["digest-mismatch"]["page_written"] and cases["digest-mismatch"]["stale_preserved"])
    written = all(cases[label]["page_written"] and cases[label]["wheel_published"]
                  and not cases[label]["stale_preserved"] and cases[label]["page_exact"]
                  for label in ("relative", "absolute-outside"))

    fixed = controls and written and all(cases[label]["exception"] is None and cases[label]["return"] == 0
                                        for label in ("relative", "absolute-outside"))
    return {"cases": cases, "controls_pass": controls,
             "fixed_contract_pass": fixed}



class MaintenanceScriptsTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)

    def assert_contract(self, result):
        self.assertTrue(result["controls_pass"], json.dumps(result, indent=2))
        self.assertTrue(result["fixed_contract_pass"], json.dumps(result, indent=2))

    def test_unit_test_skips_require_an_explicit_case_allowlist(self):
        checker = SOURCE / "scripts/check-unit-test-skips.py"
        self.assertTrue(checker.is_file(), "unit test skips have no policy check")
        results = self.root / "results"
        results.mkdir()
        report = results / "TEST-fixture.xml"
        packs = [
            ("com.vscodroid.setup.ToolchainCppPackTest", name) for name in (
                "the manifest the script writes asks for what the manager honours()",
                "installing it makes every command name resolve to a program and starts nothing by hand()",
                "uninstalling it leaves no file of its own behind()",
            )
        ] + [("com.vscodroid.setup.ToolchainRegistryTest$CatalogTest",
              "no recorded size is under the tree its pack ships()")]

        def check(cases, allowed=False, skipped=None):
            suite = ET.Element("testsuite", tests=str(len(cases)), skipped=str(
                sum(skip for _, _, skip in cases) if skipped is None else skipped))
            for cls, name, skip in cases:
                case = ET.SubElement(suite, "testcase", classname=cls, name=name)
                if skip:
                    ET.SubElement(case, "skipped", message="input is absent")
            ET.ElementTree(suite).write(report, encoding="utf-8")
            return run([sys.executable, "-I", str(checker), str(results)] +
                       (["--allow-pack-skips"] if allowed else []), self.root)

        for allowed in (False, True):
            with self.subTest(kind="executed", allowed=allowed):
                self.assertEqual(0, check([("Example", "runs()", False)], allowed)["status"])
            for cls, name in packs:
                with self.subTest(kind="pack", cls=cls, name=name, allowed=allowed):
                    result = check([(cls, name, True)], allowed)
                    self.assertEqual(0 if allowed else 1, result["status"], result)
                    self.assertIn(name, result["stdout"])
            for cls, name in [("Example", "UnexpectedCase()"), (packs[0][0], "new case()"),
                              ("OtherClass", packs[0][1])]:
                with self.subTest(kind="unexpected", cls=cls, name=name, allowed=allowed):
                    result = check([(cls, name, True)], allowed)
                    self.assertEqual(1, result["status"], result)
                    self.assertIn(cls + "." + name, result["stdout"])
        for cases, skipped in [([], 0), ([("Example", "hidden skip()", True)], 0),
                               ([("Example", "runs()", False)], 1)]:
            with self.subTest(kind="invalid counts", cases=cases, skipped=skipped):
                self.assertEqual(1, check(cases, skipped=skipped)["status"])
        report.write_text("<broken>")
        self.assertEqual(1, run([sys.executable, "-I", str(checker), str(results)], self.root)["status"])
        report.unlink()
        for directory in (results, self.root / "missing"):
            with self.subTest(kind="no reports", directory=str(directory)):
                self.assertEqual(1, run([sys.executable, "-I", str(checker), str(directory)],
                                        self.root)["status"])

    def test_plain_punctuation_sees_every_spelling_a_reader_is_shown(self):
        checker = SOURCE / "scripts/check-plain-punctuation.py"
        em, en, bar = chr(0x2014), chr(0x2013), chr(0x2015)

        def escape(code, braced=False):
            return "\\u" + ("{%x}" % code if braced else "%04x" % code)

        def patch(context, added, target="f.ts", removed="old line"):
            return ("Subject: plain words\n\ndiff --git a/" + target + " b/" + target + "\n--- a/" + target
                    + "\n+++ b/" + target + "\n@@ -1,2 +1,2 @@\n " + context + "\n-" + removed + "\n+" + added + "\n")

        refused = {
            "README.md": "a " + em + " b\n",
            "android/app/src/main/res/values/strings.xml": "<s>a &#x2014; b</s>\n",
            "docs/site/named.html": "<p>a &mdash; b, c &ndash; d</p>\n",
            "docs/site/horbar.html": "<p>a &horbar; b</p>\n",
            "docs/site/decimal.html": "<p>a &#8212; b</p>\n",
            "docs/site/hex.html": "<p>a &#x2015; b</p>\n",
            "docs/site/bare.html": "<p>a &#8211 b</p>\n",
            "docs/site/remapped.html": "<p>a &#151; b</p>\n",
            "docs/GUIDE.md": "a &ndash; b\n",
            "android/app/src/main/assets/extensions/x/package.nls.json":
                '{"k": "a ' + escape(0x2014) + ' b"}\n',
            "android/app/src/main/kotlin/X.kt": 'val s = "a ' + escape(0x2013) + ' b"\n',
            "android/app/src/main/assets/x.js": "const s = 'a " + escape(0x2015, True) + " b';\n",
            "patches/0001-added.patch": patch("same", "new " + bar + " line"),
            # The editor's build compiles what a patch adds, so its escapes are decoded as well.
            "patches/0003-escaped.patch": patch("same", "const s = 'a " + escape(0x2014) + " b';"),
            # An added line whose own text starts with `++ ` is not the file header it looks like.
            "patches/0004-plus.patch": patch("same", "++ counter " + em),
            # ...and neither is one that follows a removed line that starts `-- `: the pair reads like
            # the `---` and `+++` of a file header outside a hunk, and is two lines of the hunk inside it.
            "patches/0006-minus.patch": patch("same", "++ counter " + em, target="f.sql", removed="-- old comment"),
            # A page decodes the character references a patch adds to it, whatever the patch file is called.
            "patches/0007-page.patch": patch("same", "<p>a &mdash; b</p>", target="f.html"),
            # Copied into the server tree, where product.json names the app to its users.
            "branding/product.json": '{"nameLong": "a ' + escape(0x2013) + ' b"}\n',
            "branding/workbench/x.svg": "<svg><text>a &#%d; b</text></svg>\n" % 0x2014,
            # Three backslashes: an escaped backslash, then the escape itself.
            "android/app/src/main/kotlin/Odd.kt": 'val s = "\\\\' + escape(0x2014) + '"\n',
            # A line of the description that looks like a hunk header is text: no file is named yet.
            "patches/0008-hunk-in-prose.patch": patch("same", "new line").replace(
                "plain words", "plain words\n\n@@ -1,3 +1,3 @@ is how a hunk starts\nand this " + em + " follows it"),
            # A space after the name of the file is not part of the name.
            "patches/0009-space.patch": patch("same", "<p>a &mdash; b</p>", target="f.html").replace(
                "+++ b/f.html\n", "+++ b/f.html \n"),
            # A line below the diff that begins the way a header does is still text of the patch.
            "patches/0018-header-text.patch": patch("same", "new line") + "index of the changes " + em + " notes\n",
            # And so is what follows the line `git format-patch` ends a patch with.
            "patches/0019-after-signature.patch": patch("same", "new line") + "-- \n2.43.0 " + em + "\n",
        }
        # Refused because the gate cannot place a line, whatever the line holds: what it cannot place it
        # would not read.
        unplaced = {
            # A line of the description that starts as the old side of a file header does ended the
            # description, and the lines after it were read by nobody.
            "patches/0010-header-in-prose.patch": patch("same", "new line").replace(
                "plain words", "plain words\n\n--- a/ names the old side\nand this " + em + " follows it"),
            # Text after the last hunk.
            "patches/0011-trailing.patch": patch("same", "new line") + "words after the diff " + em + "\n",
            # A name git quotes, and the prefixes diff.mnemonicPrefix writes: no path to judge the line as.
            "patches/0012-quoted.patch": patch("same", "<p>a &mdash; b</p>", target="f.html").replace(
                "+++ b/f.html", '+++ "b/f page.html"'),
            "patches/0013-mnemonic.patch": patch("same", "<p>a &mdash; b</p>", target="f.html").replace(
                "--- a/f.html", "--- i/f.html").replace("+++ b/f.html", "+++ w/f.html"),
        }
        allowed = {
            "patches/0002-upstream.patch": patch("ctx " + em + " kept", "new line").replace(
                "-old line", "-old " + en + " line"),
            "android/app/src/test/kotlin/XTest.kt": 'val s = "' + escape(0x2014) + '"\n',
            "scripts/test-x.js": "const s = '" + escape(0x2013, True) + "';\n",
            "scripts/x.py": "s = '&mdash;'\n",
            "docs/site/plain.html": "<p>a - b &amp; c &MDASH; d &mdash e &#82120;</p>\n",
            # Ours, and judged for the character, but a description is not compiled.
            "patches/0005-described.patch": patch("same", "new line").replace(
                "plain words", "keep the " + escape(0x2014) + " escape out"),
            # Two backslashes: an escaped backslash and a plain `u`, which shows no dash.
            "android/app/src/main/kotlin/Even.kt": 'val s = "\\' + escape(0x2014) + '"\n',
            # A diff with no `diff --git` line, a new file, and a last line with no newline: all read.
            "patches/0014-bare.patch": "Subject: plain words\n\n--- a/f.ts\n+++ b/f.ts\n@@ -1 +1 @@\n-old\n+new\n"
                                       "\\ No newline at end of file\n",
            "patches/0015-new-file.patch": "Subject: plain words\n\ndiff --git a/f.ts b/f.ts\nnew file mode 100644\n"
                                           "index 0000000..1111111\n--- /dev/null\n+++ b/f.ts\n@@ -0,0 +1 @@\n+new\n",
            # What `git diff --binary` writes for a file, and what plain `git diff` says of one.
            "patches/0016-binary.patch": "Subject: plain words\n\ndiff --git a/i.png b/i.png\nnew file mode 100644\n"
                                         "index 0000000..1111111\nGIT binary patch\nliteral 5\nMcmZQzU|?Wi00D\n\n"
                                         "literal 0\nHcmV?d00001\n\ndiff --git a/j.png b/j.png\nindex 1111111..2222222 100644\n"
                                         "Binary files a/j.png and b/j.png differ\n",
            # The signature `git format-patch` ends a patch with.
            "patches/0017-signed.patch": patch("same", "new line") + "-- \n2.43.0\n\n",
            # A deletion, in a checkout that gave the patch CRLF line ends, which reading the file as text undoes.
            "patches/0020-crlf.patch": "Subject: plain words\r\n\r\ndiff --git a/f.ts b/f.ts\r\ndeleted file mode 100644\r\n"
                                       "index 1111111..0000000\r\n--- a/f.ts\r\n+++ /dev/null\r\n@@ -1 +0,0 @@\r\n-old\r\n",
        }
        # Not judged at all, so it is not even a path the gate accepts: only the whole-tree count shows it.
        verbatim = {"licenses/x.txt": "a " + em + " b\n"}
        repo = self.root / "punctuation"
        for name, text in {**refused, **unplaced, **allowed, **verbatim}.items():
            write(repo / name, text)
        env = {"HOME": str(self.root), "GIT_CONFIG_NOSYSTEM": "1"}
        self.assertEqual(0, run(["git", "init", "-q"], repo, env)["status"])
        self.assertEqual(0, run(["git", "add", "--", *refused, *unplaced, *allowed, *verbatim], repo, env)["status"])
        for name, expected in [(n, 1) for n in {**refused, **unplaced}] + [(n, 0) for n in allowed]:
            with self.subTest(name=name):
                result = run([sys.executable, "-I", str(checker), name], repo, env)
                self.assertEqual(expected, result["status"], result)
                if expected:
                    self.assertIn(name + ":", result["stdout"])
                self.assertEqual(name in unplaced, "cannot place" in result["stdout"], result)
        whole = run([sys.executable, "-I", str(checker)], repo, env)
        self.assertEqual(1, whole["status"], whole)
        # One for each refused file and a second in the page that names two, and one for each of the
        # two unplaced lines that hold the character: a line the gate cannot place is still judged.
        self.assertIn(f"{len(refused) + 1 + 2} banned dash characters", whole["stdout"])
        # Counted apart from the characters, and by line: a hunk under a header that names no file is
        # unplaced as well, so the number is more than one for a patch.
        self.assertRegex(whole["stdout"], r"\n  \d+ lines of a patch that this gate cannot place")
        for name in unplaced:
            self.assertIn(name + ":", whole["stdout"])

    def test_lint_policy_flags_must_be_live_code(self):
        fixture = self.root / "lint-policy"
        (fixture / "scripts").mkdir(parents=True)
        (fixture / "android/app").mkdir(parents=True)
        checker = fixture / "scripts/check-lint-baseline.py"
        shutil.copy2(SOURCE / "scripts/check-lint-baseline.py", checker)
        shutil.copy2(SOURCE / "android/app/lint-baseline.xml", fixture / "android/app")
        original = (SOURCE / "android/app/build.gradle.kts").read_text()
        gradle = fixture / "android/app/build.gradle.kts"
        self.assertEqual(1, original.count("    lint {"))
        for flag in ("abortOnError", "warningsAsErrors"):
            assignment = f"        {flag} = true"
            self.assertEqual(1, original.count(assignment))
            commented = f"        {flag} = false\n        /*\n{assignment}\n        */"
            cases = {
                "live": (assignment, 0, ""),
                "block-comment": (commented, 1, ""),
                "line-comment": (f"        {flag} = false\n        // {flag} = true", 1, ""),
                "absent": ("", 1, ""),
            }
            for style, quote in (("ordinary", '"'), ("triple-quoted", '"""')):
                glob = f"    val probeGlob = {quote}META-INF/*{quote}\n"
                url = f"    val probeUrl = {quote}file:///{quote}\n"
                cases[f"block-marker-string-{style}"] = (assignment, 0, glob)
                cases[f"line-marker-string-{style}"] = (assignment, 0, url)
                cases[f"block-comment-after-string-{style}"] = (commented, 1, url)
            for name, (replacement, status, prefix) in cases.items():
                with self.subTest(flag=flag, case=name):
                    text = original.replace(assignment, replacement, 1)
                    gradle.write_text(text.replace("    lint {", prefix + "    lint {", 1))
                    result = run([sys.executable, "-I", "-B", str(checker)], fixture)
                    self.assertEqual(status, result["status"], result)
                    if status:
                        self.assertIn(f"no longer sets {flag} = true", result["stdout"], result)

    def test_logger_recovers_after_rotation_errors_and_honors_drop(self):
        text = (SOURCE / "scripts/build-native-addons.sh").read_text()

        def emitted(target):
            marker = f'cat > "{target}" <<\'JSEOF\'\n'
            self.assertEqual(1, text.count(marker))
            return text.split(marker, 1)[1].split("\nJSEOF", 1)[0] + "\n"

        shim = self.root / "index.js"
        check = self.root / "check.mjs"
        write(shim, emitted("$SPDLOG_DIR/index.js"))
        write(check, emitted("$WORK_DIR/spdlog-shim-check.mjs"))
        node = shutil.which("node")
        self.assertIsNotNone(node, "Node is required for the packaged logger self-check")
        result = run([node, str(check), str(shim)], self.root)
        self.assertEqual(0, result["status"], result)

    def test_build_step_check_refuses_an_empty_pr_script_set(self):
        fixture = self.root / "build-steps"
        shutil.copytree(SOURCE / "scripts", fixture / "scripts")
        shutil.copytree(SOURCE / ".github/workflows", fixture / ".github/workflows")
        (fixture / "android/app").mkdir(parents=True)
        shutil.copy2(SOURCE / "android/app/build.gradle.kts", fixture / "android/app")
        shutil.copy2(SOURCE / "CONTRIBUTING.md", fixture)
        checker = fixture / "scripts/check-build-steps.py"

        def check():
            return subprocess.run([sys.executable, str(checker)], cwd=fixture,
                                  capture_output=True, text=True, timeout=30)

        healthy = check()
        self.assertEqual(0, healthy.returncode, healthy.stdout + healthy.stderr)
        release = fixture / ".github/workflows/release.yml"
        text = release.read_text()
        command = "run: bash scripts/download-python.sh"
        self.assertIn(command, text)
        release.write_text(text.replace(command, "run: echo skipped", 1))
        missing = check()
        self.assertNotEqual(0, missing.returncode, missing.stdout + missing.stderr)
        self.assertIn("download-python.sh", missing.stdout)
        build = fixture / ".github/workflows/build.yml"
        text = build.read_text()
        self.assertGreater(text.count("bash scripts/"), 0)
        build.write_text(text.replace("bash scripts/", "bash ./scripts/"))
        changed = check()
        self.assertNotEqual(0, changed.returncode, changed.stdout + changed.stderr)
        self.assertIn("no scripts found in build.yml's build job", changed.stdout)

    def test_build_step_check_refuses_a_javascript_checker_nothing_runs(self):
        fixture = self.root / "build-steps-js"
        shutil.copytree(SOURCE / "scripts", fixture / "scripts")
        shutil.copytree(SOURCE / ".github/workflows", fixture / ".github/workflows")
        (fixture / "android/app").mkdir(parents=True)
        shutil.copy2(SOURCE / "android/app/build.gradle.kts", fixture / "android/app")
        shutil.copy2(SOURCE / "CONTRIBUTING.md", fixture)

        def check():
            return subprocess.run([sys.executable, str(fixture / "scripts/check-build-steps.py")],
                                  cwd=fixture, capture_output=True, text=True, timeout=30)

        healthy = check()
        self.assertEqual(0, healthy.returncode, healthy.stdout + healthy.stderr)
        build = fixture / "scripts/build-vscode-oss.sh"
        text = build.read_text()
        command = 'node "$SCRIPT_DIR/check-editcontext-ime.js" "$SRC"'
        self.assertEqual(1, text.count(command))
        # Still named in the line, and in the server cache key: neither is an invocation.
        build.write_text(text.replace(command, '# ' + command, 1))
        unrun = check()
        self.assertNotEqual(0, unrun.returncode, unrun.stdout + unrun.stderr)
        self.assertIn("check-editcontext-ime.js", unrun.stdout)

    def test_build_step_check_refuses_a_javascript_checker_missing_from_the_assets_key(self):
        fixture = self.root / "build-steps-key"
        shutil.copytree(SOURCE / "scripts", fixture / "scripts")
        shutil.copytree(SOURCE / ".github/workflows", fixture / ".github/workflows")
        (fixture / "android/app").mkdir(parents=True)
        shutil.copy2(SOURCE / "android/app/build.gradle.kts", fixture / "android/app")
        shutil.copy2(SOURCE / "CONTRIBUTING.md", fixture)

        def check():
            return subprocess.run([sys.executable, str(fixture / "scripts/check-build-steps.py")],
                                  cwd=fixture, capture_output=True, text=True, timeout=30)

        healthy = check()
        self.assertEqual(0, healthy.returncode, healthy.stdout + healthy.stderr)
        build = fixture / ".github/workflows/build.yml"
        text = build.read_text()
        entry = "'scripts/check-editcontext-ime.js', "
        self.assertEqual(1, text.count(entry))
        build.write_text(text.replace(entry, "", 1))
        unkeyed = check()
        self.assertNotEqual(0, unkeyed.returncode, unkeyed.stdout + unkeyed.stderr)
        self.assertIn("check-editcontext-ime.js", unkeyed.stdout)

    def cffi_builder(self):
        import importlib.util
        spec = importlib.util.spec_from_file_location("fixture_cffi_wheel", SOURCE / "scripts/build-cffi-wheel.py")
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module

    def test_cffi_wheel_is_packed_the_same_way_every_time(self):
        builder = self.cffi_builder()
        tag = "cp314-cp314-android_24_arm64_v8a"
        module = self.root / "_cffi_backend.cpython-314-aarch64-linux-android.so"
        module.write_bytes(b"\x7fELF fixture")
        project = {
            "scripts": {"cffi-gen-src": "cffi._cffi_gen_src:run"},
            "entry-points": {"distutils.setup_keywords": {"cffi_modules": "cffi.setuptools_ext:cffi_modules"}},
        }
        points = builder.entry_points(project)
        self.assertEqual(
            "[console_scripts]\ncffi-gen-src = cffi._cffi_gen_src:run\n\n"
            "[distutils.setup_keywords]\ncffi_modules = cffi.setuptools_ext:cffi_modules\n",
            points,
        )
        wheels = []
        for name in ("one.whl", "two.whl"):
            wheel = self.root / name
            builder.pack(wheel, "2.1.1", tag, module, {"api.py": b"API = 1\n", "_cffi_include.h": b"/* h */\n"},
                         b"Metadata-Version: 2.4\nName: cffi\nVersion: 2.1.1\n", {"LICENSE": b"MIT-0\n"},
                         points, "cffi-2.1.1.tar.gz")
            wheels.append(wheel)
        self.assertEqual(wheels[0].read_bytes(), wheels[1].read_bytes(), "the same inputs gave other bytes")
        with zipfile.ZipFile(wheels[0]) as archive:
            names = archive.namelist()
            first_info = next(i for i, n in enumerate(names) if n.startswith("cffi-2.1.1.dist-info/"))
            self.assertTrue(all(n.startswith("cffi-2.1.1.dist-info/") for n in names[first_info:]),
                            "a package file comes after the dist-info")
            self.assertIn("cffi-2.1.1.dist-info/licenses/LICENSE", names)
            self.assertTrue(all(i.date_time == (1980, 1, 1, 0, 0, 0) for i in archive.infolist()),
                            "an entry carries a time of the build, so another second gives other bytes")
            self.assertIn("Tag: " + tag, archive.read("cffi-2.1.1.dist-info/WHEEL").decode())
            self.assertEqual(0o100755, archive.getinfo(module.name).external_attr >> 16)
            records = set(archive.read("cffi-2.1.1.dist-info/RECORD").decode().splitlines())
            self.assertIn("cffi-2.1.1.dist-info/RECORD,,", records)
            for member in (n for n in names if n != "cffi-2.1.1.dist-info/RECORD"):
                digest = base64.urlsafe_b64encode(hashlib.sha256(archive.read(member)).digest()).rstrip(b"=").decode()
                self.assertIn(f"{member},sha256={digest},{len(archive.read(member))}", records,
                              f"RECORD does not describe {member}")

    def test_cffi_wheel_refuses_unsafe_or_missing_inputs(self):
        builder = self.cffi_builder()
        with self.assertRaises(SystemExit):
            builder.write_tree(self.root / "tree", {"../escape": b"x"})
        with self.assertRaises(SystemExit):
            builder.licence_files({"license-files": ["LICENSE", "AUTHORS"]}, {"LICENSE": b"x"})
        with self.assertRaises(SystemExit):
            builder.licence_files({}, {"LICENSE": b"x"})
        sdk = self.root / "sdk"
        for revision in ("27.2.12479018", "30.0.16248370"):
            write(sdk / "ndk" / revision / "source.properties", f"Pkg.Revision = {revision}\n")
        environ = {"ANDROID_HOME": str(sdk)}
        found = builder.find_ndk("27.2.12479018", environ, self.root / "nohome")
        self.assertEqual(sdk / "ndk" / "27.2.12479018", found)
        with self.assertRaises(SystemExit) as refused:
            builder.find_ndk("28.0.1", environ, self.root / "nohome")
        self.assertIn("none was found", str(refused.exception))
        # An NDK that answers to the wrong revision is not taken for the right one.
        write(sdk / "ndk" / "29.0.0" / "source.properties", "Pkg.Revision = 29.0.1\n")
        with self.assertRaises(SystemExit) as refused:
            builder.find_ndk("29.0.0", {"ANDROID_NDK_HOME": str(sdk / "ndk" / "29.0.0")}, self.root / "nohome")
        self.assertIn("29.0.1", str(refused.exception))

    def test_wheelhouse_names_reach_the_documents_people_read(self):
        source = SOURCE / "scripts/check-wheelhouse-abi.py"
        gate = types.ModuleType("fixture_wheelhouse_abi")
        gate.__file__ = str(source)
        exec(compile(source.read_text(encoding="utf-8"), str(source), "exec"), gate.__dict__)
        repo = self.root / "abi-repo"
        gate.REPO = repo
        manifest = {"packages": [{"name": "numpy"}, {"name": "pydantic-core"}, {"name": "cffi"}]}
        write(repo / "docs/USER_GUIDE.md", "- `numpy`\n- `pydantic-core`\n- `cffi`\n")
        write(repo / "NOTICE.md", "| numpy |\n| pydantic-core |\n| cffi |\n")
        write(repo / "docs/LEGAL_NOTICES.md", "| numpy |\n| pydantic_core |\n| cffi |\n")
        self.assertEqual([], gate.document_problems(manifest))
        write(repo / "NOTICE.md", "| numpy |\n| pydantic-core |\n")
        self.assertEqual(["NOTICE.md does not name cffi"], gate.document_problems(manifest))
        (repo / "docs/USER_GUIDE.md").unlink()
        self.assertEqual(
            ["docs/USER_GUIDE.md is missing", "NOTICE.md does not name cffi"], gate.document_problems(manifest),
        )

    def test_wheelhouse_checks_versioned_shared_objects(self):
        verifier = types.ModuleType("fixture_elf_verifier")
        verifier.__file__ = str(SOURCE / "scripts/verify-android-elf.py")
        exec(compile(Path(verifier.__file__).read_text(), verifier.__file__, "exec"), verifier.__dict__)
        builder = types.ModuleType("fixture_wheelhouse")
        builder.__file__ = str(SOURCE / "scripts/build-wheelhouse.py")
        exec(compile(Path(builder.__file__).read_text(), builder.__file__, "exec"), builder.__dict__)
        wheel = self.root / "fixture.whl"
        tree = self.root / "unpacked"
        tree.mkdir()
        self.assertEqual(0, builder.check_elves(wheel, tree))

        good = bytearray(verifier.synthetic_elf(None))
        struct.pack_into("<H", good, 16, 3)
        bad = bytearray(good)
        struct.pack_into("<Q", bad, 64 + 48, 4096)
        sample = tree / "libfixture.so.1"
        sample.write_bytes(bad)
        direct = subprocess.run([sys.executable, verifier.__file__, str(sample)],
                                capture_output=True, text=True, timeout=30)
        self.assertNotEqual(0, direct.returncode, direct.stdout + direct.stderr)
        self.assertIn("Android 16 needs 0x4000", direct.stdout)
        sample.unlink()

        for name in ("libfixture.so", "libfixture.so.1", "libfixture.so.1.2"):
            with self.subTest(name=name):
                sample = tree / name
                sample.write_bytes(bad)
                output = io.StringIO()
                with contextlib.redirect_stderr(output), self.assertRaises(SystemExit):
                    builder.check_elves(wheel, tree)
                self.assertIn("Android 16 needs 0x4000", output.getvalue())
                sample.write_bytes(good)
                self.assertEqual(1, builder.check_elves(wheel, tree))
                sample.unlink()
        (tree / "fixture.so.txt").write_text("not a shared object")
        self.assertEqual(0, builder.check_elves(wheel, tree))

    def test_verify_rejects_a_tree_containing_only_unsupported_addons(self):
        self.assert_contract(forwarders(SOURCE / "scripts/gen-glibc-forwarders.py", self.root / "addons"))

    def test_failed_build_or_install_never_clears_app_data(self):
        self.assert_contract(device_setup(SOURCE / "scripts/device-test.sh", self.root / "setup"))

    def test_node_runtime_matches_header_major(self):
        text = (SOURCE / "scripts/device-test.sh").read_text()
        begin = text.index('NODE_OUT=$(run_tool "files/usr/bin/node" --version)')
        phase = text[begin:text.index("# Test 16: npm", begin)]
        preamble = '''run_tool() { printf '%s\\n' "$RUNTIME"; }
derive_node_expected() { printf '%s\\n' '24.18.0'; }
pass() { printf 'PASS %s\\n' "$1"; }
fail() { printf 'FAIL %s: %s\\n' "$1" "$2"; exit 1; }
'''
        for runtime, status in (("v24.18.0", 0), ("v24.22.3", 0),
                                ("v25.0.0", 1), ("v240.0.0", 1)):
            with self.subTest(runtime=runtime):
                result = run(["/bin/bash", "-c", preamble + phase], self.root,
                             {"RUNTIME": runtime})
                self.assertEqual(status, result["status"], result)
                self.assertIn(runtime, result["stdout"])
                if status:
                    self.assertIn("24", result["stdout"])

    def test_ca_diagnostic_skips_only_an_explicitly_empty_trust_store(self):
        text = (SOURCE / "scripts/device-test.sh").read_text()
        begin = text.index('CA_BUNDLE_REL="')
        phase = text[begin:text.index("# TESTS 15-19:", begin)]
        functions = "\n".join(shell_function(text, name) for name in ("pass", "fail", "skip"))
        recorded_empty = hashlib.sha256(b"android-ca-store-v2:").hexdigest()
        for label, expected_status, expected_skip in (
                ("nonempty", 0, False), ("recorded-empty", 0, True),
                ("unmarked-empty", 1, False), ("missing", 1, False),
                ("unreadable", 1, False), ("corrupt-recorded-empty", 1, False)):
            with self.subTest(label=label):
                case = self.root / label
                case.mkdir()
                write(case / "adb", '''#!/bin/bash
case "$*" in
    'shell run-as com.vscodroid.debug grep -c '*)
        case "$SCENARIO" in nonempty) printf '1\n' ;; missing|unreadable) exit 1 ;; *) printf '0\n'; exit 1 ;; esac ;;
    'shell run-as com.vscodroid.debug wc -c '*)
        case "$SCENARIO" in missing|unreadable) exit 1 ;; corrupt-recorded-empty) printf '4 cert.pem\n' ;; *) printf '0 cert.pem\n' ;; esac ;;
    'shell run-as com.vscodroid.debug cat '*)
        case "$SCENARIO" in recorded-empty|corrupt-recorded-empty) printf '%s\n' "$RECORDED_CA_FINGERPRINT" ;; *) exit 1 ;; esac ;;
    'shell ls '*) printf 'root-one\nroot-two\n' ;;
    *) printf 'Unexpected stub call: %s\n' "$*" >&2; exit 97 ;;
esac
''', True)
                preamble = '''#!/bin/bash
set -uo pipefail
ADB="$PWD/adb"
PKG=com.vscodroid.debug
PASS=0 FAIL=0 SKIP=0 FAILURES=""
RED="" GREEN="" YELLOW="" RESET=""
'''
                write(case / "ca.sh", preamble + functions + "\n" + phase + '\n[ "$FAIL" -eq 0 ]\n')
                result = run(["/bin/bash", str(case / "ca.sh")], case,
                             {"SCENARIO": label, "RECORDED_CA_FINGERPRINT": recorded_empty})
                self.assertEqual(expected_status, result["status"], result)
                self.assertEqual(expected_skip, "SKIP" in result["stdout"], result)
                if expected_skip:
                    self.assertNotIn("PASS", result["stdout"])
                self.assertNotIn("came from the user store", result["stdout"])

    def test_launch_ignores_node_processes_owned_by_another_app(self):
        self.assert_contract(device_launch(SOURCE / "scripts/device-launch.sh", self.root / "launch"))

    def test_launcher_treats_ui_xml_and_adb_paths_as_data(self):
        helper = shell_function((SOURCE / "scripts/device-launch.sh").read_text(), "tap_by_text")
        for label, extra_text, button, adb_name in (
                ("ordinary", "/data/users/name", "Skip", "adb"),
                ("backslash", r"C:\Users\name", "Skip", "adb"),
                ("large-ui", "x" * 200_000, "Skip", "adb"),
                ("quoted-path", "/data/users/name", "Skip", 'adb "quoted"'),
                ("absent-button", "/data/users/name", "Continue", "adb")):
            with self.subTest(label=label):
                case = self.root / label
                case.mkdir()
                xml = case / "ui.xml"
                write(xml, '<hierarchy><node text="' + extra_text +
                      '" bounds="[1,2][3,4]"/><node text="' + button +
                      '" bounds="[10,20][30,40]"/></hierarchy>')
                adb = case / adb_name
                write(adb, '''#!/bin/bash
printf '%s\n' "$*" >> "$CALL_LOG"
case "$*" in
    'shell uiautomator dump /sdcard/ui.xml') exit 0 ;;
    'shell cat /sdcard/ui.xml') cat "$UI_XML" ;;
    'shell input tap 20 30') exit 0 ;;
    *) exit 97 ;;
esac
''', True)
                script = "set -euo pipefail\n" + helper + "\ntap_by_text Skip\n"
                result = run(["/bin/bash", "-c", script], case,
                             {"ADB": str(adb), "UI_XML": str(xml), "CALL_LOG": str(case / "calls")})
                calls = (case / "calls").read_text().splitlines()
                self.assertEqual(1 if label == "absent-button" else 0, result["status"], result)
                self.assertEqual(label != "absent-button", "shell input tap 20 30" in calls, result)

    def test_wheelhouse_accepts_relative_and_external_output_paths(self):
        self.assert_contract(wheelhouse(SOURCE / "scripts/build-wheelhouse.py", self.root / "wheels"))

    def test_java_uses_the_same_explicit_ndk_precedence_as_other_builds(self):
        text = (SOURCE / "scripts/download-java.sh").read_text()
        begin = text.index("# Same resolution order as build-native-addons.sh")
        phase = text[begin:text.index('\n(\n    cd "$SPAWN_SRC"', begin)]
        ndk_home = self.root / "ndk home"
        ndk_root = self.root / "ndk root"
        sdk = self.root / "sdk"
        for ndk in (ndk_home, ndk_root, sdk / "ndk/27.0"):
            for host in ("linux-x86_64", "linux-aarch64", "darwin-x86_64", "darwin-arm64"):
                (ndk / "toolchains/llvm/prebuilt" / host / "bin").mkdir(parents=True)
        for label, overrides, expected in (
                ("home", {"ANDROID_NDK_HOME": str(ndk_home)}, ndk_home),
                ("root", {"ANDROID_NDK_ROOT": str(ndk_root)}, ndk_root),
                ("both", {"ANDROID_NDK_HOME": str(ndk_home), "ANDROID_NDK_ROOT": str(ndk_root)}, ndk_home),
                ("sdk", {}, sdk / "ndk/27.0")):
            with self.subTest(label=label):
                result = run(["/bin/bash", "-c", "set -euo pipefail\n" + phase +
                              '\nprintf "%s" "$NDK_DIR"\n'], self.root,
                             {"HOME": str(self.root / "empty-home"), "ANDROID_HOME": str(sdk), **overrides})
                self.assertEqual(0, result["status"], result)
                self.assertEqual(str(expected), result["stdout"], result)

    def test_java_pack_requires_the_documented_launchers(self):
        text = (SOURCE / "scripts/download-java.sh").read_text()
        begin = text.index("# --- Step 6: Write manifest.json ---")
        phase = text[begin:text.index("# --- Step 7: Verify", begin)]
        launchers = ("java", "javac", "jar", "jshell")
        for missing in (None, *launchers, "all", "java-directory"):
            with self.subTest(missing=missing):
                case = self.root / (missing or "complete")
                bin_dir = case / "jdk/bin"
                bin_dir.mkdir(parents=True)
                for name in launchers:
                    if missing not in (name, "all") and not (missing == "java-directory" and name == "java"):
                        write(bin_dir / name, "launcher\n")
                if missing == "java-directory":
                    (bin_dir / "java").mkdir()
                prefix = ('set -euo pipefail\nJDK_DIR="$PWD/jdk"\nPACK_ASSETS="$PWD/pack"\n'
                          'JDK_MAJOR=25\nJDK_DIRNAME=java-25-openjdk\nJAVA_VERSION=25.0.5\n'
                          'mkdir -p "$PACK_ASSETS"\n')
                result = run(["/bin/bash", "-c", prefix + phase], case)
                manifest = case / "pack/toolchain_java.json"
                if missing is not None:
                    expected = "java" if missing in ("all", "java-directory") else missing
                    self.assertNotEqual(0, result["status"], result)
                    self.assertIn(expected, result["stderr"], result)
                    self.assertFalse(manifest.exists(), result)
                else:
                    self.assertEqual(0, result["status"], result)
                    data = json.loads(manifest.read_text())
                    for name in launchers:
                        path = f"usr/lib/jvm/java-25-openjdk/bin/{name}"
                        self.assertIn(path, data["binaries"])
                        self.assertEqual(path, data["symlinks"][name])

    def test_ruby_pack_requires_the_documented_launchers(self):
        text = (SOURCE / "scripts/download-ruby.sh").read_text()
        begin = text.index('if [ ! -d "$RUBY_USR/bin" ]; then')
        phase = text[begin:text.index("# Copy ruby lib directory", begin)]
        launchers = ("ruby", "irb", "gem", "bundle", "bundler", "rake")
        for missing in (None, *launchers, "all", "ruby-directory", "bin-directory"):
            with self.subTest(missing=missing):
                case = self.root / (missing or "complete")
                case.mkdir()
                bin_dir = case / "package/usr/bin"
                if missing != "bin-directory":
                    bin_dir.mkdir(parents=True)
                    for name in launchers:
                        if missing not in (name, "all") and not (missing == "ruby-directory" and name == "ruby"):
                            write(bin_dir / name, f"{name} launcher\n")
                    if missing == "ruby-directory":
                        (bin_dir / "ruby").mkdir()
                prefix = ('set -euo pipefail\nRUBY_USR="$PWD/package/usr"\nPACK_ASSETS="$PWD/pack"\n'
                          'mkdir -p "$PACK_ASSETS/usr/bin"\n')
                result = run(["/bin/bash", "-c", prefix + phase], case)
                copied = case / "pack/usr/bin"
                if missing is not None:
                    expected = "ruby" if missing in ("all", "ruby-directory") else missing
                    self.assertNotEqual(0, result["status"], result)
                    self.assertIn("bin" if missing == "bin-directory" else expected, result["stderr"], result)
                    self.assertEqual([], list(copied.iterdir()), result)
                else:
                    self.assertEqual(0, result["status"], result)
                    self.assertEqual(set(launchers), {p.name for p in copied.iterdir()})
                    for name in launchers:
                        self.assertEqual((bin_dir / name).read_bytes(), (copied / name).read_bytes())

    def test_java_pack_gets_its_trust_store_from_ca_certificates_java(self):
        text = (SOURCE / "scripts/download-java.sh").read_text()
        begin = text.index('CACERTS_LINK="$JDK_SRC/lib/security/cacerts"')
        tail = 'Java would trust nothing" >&2\n    exit 1\nfi\n'
        phase = text[begin:text.index(tail, begin) + len(tail)]
        prefix = ("set -euo pipefail\nJDK_NAME=openjdk-25\nJDK_DIRNAME=java-25-openjdk\n"
                  'JDK_SRC="$PWD/jdk"\nPACK_ASSETS="$PWD/pack"\nmkdir -p "$PACK_ASSETS/usr/lib/jvm"\n')
        link = "/data/data/com.termux/files/usr/etc/tls/certs/java/cacerts"
        package_file = "extracted/ca-certificates-java/data/data/com.termux/files/usr/etc/tls/certs/java/cacerts"
        for label, jdk_entry, from_package, status, size, message in (
                ("dangling link, file in the package", "link", 60000, 0, 60000, ""),
                ("dangling link, nothing in the package", "link", None, 1, None, "does not carry it"),
                ("the JDK carries the file itself", 60000, None, 0, 60000, ""),
                ("a file too small to be a trust store", 10, None, 1, None, "too small"),
                ("no entry at all", None, None, 1, None, "missing or too small")):
            with self.subTest(label=label):
                case = self.root / label.replace(" ", "-").replace(",", "")
                (case / "jdk/lib/security").mkdir(parents=True)
                entry = case / "jdk/lib/security/cacerts"
                if jdk_entry == "link":
                    os.symlink(link, entry)
                elif jdk_entry is not None:
                    entry.write_bytes(b"k" * jdk_entry)
                if from_package is not None:
                    write(case / package_file, "")
                    (case / package_file).write_bytes(b"p" * from_package)
                result = run(["/bin/bash", "-c", prefix + phase], case)
                self.assertEqual(status, result["status"], result)
                packed = case / "pack/usr/lib/jvm/java-25-openjdk/lib/security/cacerts"
                if status == 0:
                    self.assertFalse(packed.is_symlink(), result)
                    self.assertEqual(size, packed.stat().st_size, result)
                else:
                    self.assertIn(message, result["stderr"], result)

    def test_site_live_data_holds_only_what_was_checked(self):
        source = SOURCE / "scripts/fetch-site-live.py"
        module = types.ModuleType("fixture_site_live")
        module.__file__ = str(source)
        exec(compile(source.read_text(encoding="utf-8"), str(source), "exec"), module.__dict__)

        self.assertEqual(
            {"version": "v1.9.0", "stars": "380"},
            module.live({"stargazers_count": 380}, {"tag_name": "v1.9.0"}),
        )
        for count, text in ((0, "0"), (999, "999"), (1000, "1k"), (12345, "12.3k")):
            self.assertEqual(text, module.stars_text(count))
        # The browser writes these strings onto the page, so anything that is
        # not a release tag or a count is refused, not passed along.
        for repository, release in (
            ({"stargazers_count": 380}, {"tag_name": "server-1.139.1"}),
            ({"stargazers_count": 380}, {"tag_name": "<b>v1.9.0</b>"}),
            ({"stargazers_count": 380}, {}),
            ({"stargazers_count": "380"}, {"tag_name": "v1.9.0"}),
            ({"stargazers_count": True}, {"tag_name": "v1.9.0"}),
            ({"stargazers_count": -1}, {"tag_name": "v1.9.0"}),
            ({}, {"tag_name": "v1.9.0"}),
            ([], {"tag_name": "v1.9.0"}),
        ):
            with self.assertRaises(ValueError, msg=(repository, release)):
                module.live(repository, release)

    def test_offline_termux_packages_never_attempt_a_download(self):
        library = SOURCE / "scripts/lib/termux-packages.sh"
        payload = b"cached package bytes"
        for label, offline, cached, expected_status, expected_network in (
                ("offline-cached", "1", payload, 0, False),
                ("offline-missing", "1", None, 1, False),
                ("offline-corrupt", "1", b"corrupt", 1, False),
                ("online-missing", "0", None, 0, True)):
            with self.subTest(label=label):
                case = self.root / label
                (case / "debs").mkdir(parents=True)
                if cached is not None:
                    (case / "debs/fixture.deb").write_bytes(cached)
                script = '''set -euo pipefail
WORK_DIR="$PWD"
. "$1"
termux_pkg_filename() { printf pool/fixture.deb; }
termux_pkg_sha256() { printf '%s' "$EXPECTED"; }
curl() {
    printf called >> "$PWD/network-attempt"
    while [ "$1" != -o ]; do shift; done
    printf 'cached package bytes' > "$2"
}
termux_download_packages fixture
'''
                result = run(["/bin/bash", "-c", script, "bash", str(library)], case,
                             {"TERMUX_OFFLINE": offline, "EXPECTED": hashlib.sha256(payload).hexdigest()})
                self.assertEqual(expected_status, result["status"], result)
                self.assertEqual(expected_network, (case / "network-attempt").exists(), result)

    def test_download_cpp_rejects_case_insensitive_volume(self):
        text = (SOURCE / "scripts/download-cpp.sh").read_text()
        begin = text.index('echo "=== Downloading C/C++ (clang) Toolchain ==="')
        end = text.index("# --- Step 1:", begin)
        guard = text[begin:end]
        prefix = ('set -euo pipefail\n'
                  'WORK_DIR="$PWD/work"\n'
                  'PACK_ASSETS="$PWD/pack"\n')
        cases = (
            ("simulated-work-insensitive", "1", "0", None, 1, ("work", "ALLOW_CASE_INSENSITIVE=1")),
            ("simulated-pack-insensitive", "0", "1", None, 1, ("pack", "ALLOW_CASE_INSENSITIVE=1")),
            ("simulated-override-insensitive", "1", "1", "1", 0,
             ("WARNING:", "Eight netfilter header pairs collapse", "local test only")),
            ("simulated-both-sensitive", "0", "0", None, 0, ()),
        )
        for label, work_ci, pack_ci, override, expected_status, expected_stderr in cases:
            with self.subTest(label=label):
                case = self.root / label
                case.mkdir(parents=True)
                shim = (
                    f'SIM_WORK_CI={work_ci}\n'
                    f'SIM_PACK_CI={pack_ci}\n'
                    'test() {\n'
                    '    case "${1:-}:${2:-}" in\n'
                    '        -e:*/work/.case-check-*-A) [ "$SIM_WORK_CI" = "1" ] && return 0 || return 1 ;;\n'
                    '        -e:*/pack/.case-check-*-A) [ "$SIM_PACK_CI" = "1" ] && return 0 || return 1 ;;\n'
                    '        *) builtin test "$@" ;;\n'
                    '    esac\n'
                    '}\n'
                )
                env = {"ALLOW_CASE_INSENSITIVE": override} if override is not None else None
                result = run(["/bin/bash", "-c", prefix + shim + guard], case, env)
                if expected_status != 0:
                    self.assertNotEqual(0, result["status"], result)
                    self.assertIn("case-insensitive", result["stderr"], result)
                else:
                    self.assertEqual(0, result["status"], result)
                for fragment in expected_stderr:
                    self.assertIn(fragment, result["stderr"], result)
                for sub in ("work", "pack"):
                    if (case / sub).exists():
                        self.assertEqual([], list((case / sub).glob(".case-check-*")), result)

        for label, override in (("real-host-filesystem", None), ("real-host-filesystem-override", "1")):
            with self.subTest(label=label):
                case = self.root / label
                (case / "probe").mkdir(parents=True)
                (case / "probe/lower").write_text("")
                host_case_insensitive = (case / "probe/LOWER").exists()
                env = {"ALLOW_CASE_INSENSITIVE": override} if override is not None else None
                result = run(["/bin/bash", "-c", prefix + guard], case, env)
                if host_case_insensitive and override != "1":
                    self.assertNotEqual(0, result["status"], result)
                    self.assertIn("case-insensitive", result["stderr"], result)
                else:
                    self.assertEqual(0, result["status"], result)
                    if host_case_insensitive:
                        self.assertIn("WARNING:", result["stderr"], result)
                        self.assertIn("local test only", result["stderr"], result)
                for sub in ("work", "pack"):
                    if (case / sub).exists():
                        self.assertEqual([], list((case / sub).glob(".case-check-*")), result)


if __name__ == "__main__":
    unittest.main()
