#!/usr/bin/env python3
"""Offline regression checks for packaging and device maintenance commands."""

import base64
import contextlib
import hashlib
import importlib.util
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
# Asked of the interpreter and flags the cases run the checks with: `-I` hides the user site, so a PyYAML
# installed there is found by this process and not by the check.
PYYAML = subprocess.run([sys.executable, "-I", "-c", "import yaml"], capture_output=True).returncode == 0


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

    def test_plain_punctuation_refuses_a_text_file_it_cannot_decode(self):
        checker = SOURCE / "scripts/check-plain-punctuation.py"
        repo = self.root / "undecodable"
        repo.mkdir()
        # Windows-1252: the dash is the single byte 0x97, which is not UTF-8.
        (repo / "notes.md").write_bytes(b"caf\xe9 a \x97 b\n")
        (repo / "picture.png").write_bytes(b"\x89PNG\r\n\x1a\n\xff\xfe\x00")
        write(repo / "ok.md", "plain words\n")
        env = {"HOME": str(self.root), "GIT_CONFIG_NOSYSTEM": "1"}
        self.assertEqual(0, run(["git", "init", "-q"], repo, env)["status"])
        self.assertEqual(0, run(["git", "add", "--", "notes.md", "picture.png", "ok.md"], repo, env)["status"])
        refused = run([sys.executable, "-I", str(checker), "notes.md"], repo, env)
        self.assertEqual(1, refused["status"], refused)
        self.assertIn("notes.md:", refused["stdout"])
        for name in ("picture.png", "ok.md"):
            with self.subTest(name=name):
                result = run([sys.executable, "-I", str(checker), name], repo, env)
                self.assertEqual(0, result["status"], result)

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

    def test_lint_policy_keeps_every_play_sdk_index_id_informational(self):
        """Each id lint's Play SDK Index reports a pinned version under is informational, and held so."""
        fixture = self.root / "sdk-index"
        (fixture / "scripts").mkdir(parents=True)
        (fixture / "android/app").mkdir(parents=True)
        checker = fixture / "scripts/check-lint-baseline.py"
        shutil.copy2(SOURCE / "scripts/check-lint-baseline.py", checker)
        shutil.copy2(SOURCE / "android/app/lint-baseline.xml", fixture / "android/app")
        original = (SOURCE / "android/app/build.gradle.kts").read_text()
        gradle = fixture / "android/app/build.gradle.kts"
        # The four ids GradleDetector in lint 32.3.1 defines for a Play SDK Index label, and the
        # one it raises for a version the same index marks outdated.
        for issue in ("OutdatedLibrary", "PlaySdkIndexDeprecated", "PlaySdkIndexGenericIssues",
                      "PlaySdkIndexNonCompliant", "PlaySdkIndexVulnerability"):
            entry = f'            "{issue}",\n'
            with self.subTest(issue=issue):
                self.assertEqual(1, original.count(entry), f"{issue} is not listed as informational")
                gradle.write_text(original.replace(entry, "", 1))
                result = run([sys.executable, "-I", "-B", str(checker)], fixture)
                self.assertEqual(1, result["status"], result)
                self.assertIn(issue, result["stdout"])

    def test_release_refuses_a_lint_report_play_console_would_block(self):
        """Listing the SDK Index ids as informational hides a blocking label too, so the report is read for it."""
        reports = self.root / "reports"
        reports.mkdir()
        checker = [sys.executable, "-I", "-B", str(SOURCE / "scripts/check-lint-baseline.py"),
                   "--play-console-labels", str(reports)]

        def report(name, *issues):
            body = "".join(f'  <issue id="{i}" severity="{s}" message="{m}" category="Compliance"/>\n'
                           for i, s, m in issues)
            # The shape lint 9.3.1 writes; an id listed as informational is reported as a Hint.
            write(reports / name, f'<?xml version="1.0" encoding="UTF-8"?>\n<issues format="6" by="lint 9.3.1">\n{body}</issues>\n')

        nothing = run(checker, self.root)
        self.assertEqual(1, nothing["status"], nothing)
        self.assertIn("no lint XML report", nothing["stdout"])
        report("lint-results-debug.xml",
               ("GradleDependency", "Hint", "A newer version of androidx.core is available"),
               ("PlaySdkIndexNonCompliant", "Hint",
                "com.google.android.play:asset-delivery version 2.2.2 has policy issues that will block "
                "publishing of your app to Play Console in the future"))
        clean = run(checker, self.root)
        self.assertEqual(0, clean["status"], clean)
        report("lint-results-release-fatal.xml",
               ("PlaySdkIndexGenericIssues", "Hint",
                "[Prevents app release in Google Play Console] com.example:lib version 1.0 has been "
                "reported as problematic by its author and will block publishing of your app to Play Console"))
        blocked = run(checker, self.root)
        self.assertEqual(1, blocked["status"], blocked)
        self.assertIn("PlaySdkIndexGenericIssues", blocked["stdout"])
        self.assertIn("lint-results-release-fatal.xml", blocked["stdout"])
        write(reports / "lint-results-release-fatal.xml", "<issues>")
        unreadable = run(checker, self.root)
        self.assertEqual(1, unreadable["status"], unreadable)
        release = (SOURCE / ".github/workflows/release.yml").read_text()
        step = ("      - name: Refuse a dependency Play Console would block\n"
                "        run: python3 scripts/check-lint-baseline.py --play-console-labels android/app/build/reports\n")
        self.assertEqual(1, release.count(step), "release.yml does not read the lint report for a blocking label")
        self.assertLess(release.index("        run: ./gradlew lint\n"), release.index(step))
        self.assertLess(release.index(step), release.index("      - name: Decode keystore\n"))

    def test_a_mistyped_self_test_flag_is_refused_rather_than_running_the_main_check(self):
        """`--selftest` ran the main check and exited 0, so a CI line with a typo skipped the self-test in green."""
        for script in ("check-markdown-tables.py", "check-workflow-steps.py"):
            if script == "check-workflow-steps.py" and not PYYAML:
                self.assertFalse(os.environ.get("CI"), "CI images carry PyYAML")
                continue
            for args in (["--selftest"], ["--self-test", "extra"], ["-self-test"]):
                with self.subTest(script=script, args=args):
                    result = run([sys.executable, "-I", "-B", str(SOURCE / "scripts" / script), *args], self.root)
                    self.assertEqual(2, result["status"], result)
                    self.assertIn("usage", result["stderr"])

    def test_translatable_strings_refuses_a_scan_that_read_no_literal(self):
        """Zero literals over the whole tree is a tokeniser that stopped reading, not a clean tree."""
        fixture = self.root / "translatable"
        checker = fixture / "scripts/check-translatable-strings.py"
        write(checker, (SOURCE / "scripts/check-translatable-strings.py").read_text())
        source = fixture / "android/app/src/main/kotlin/com/example/Example.kt"
        write(source, "package com.example\n\nclass Example {\n    fun size(list: List<Int>) = list.size\n}\n")
        empty = run([sys.executable, "-I", "-B", str(checker)], fixture)
        self.assertEqual(2, empty["status"], empty)
        self.assertIn("0 string literals", empty["stderr"])
        write(source, 'package com.example\n\nclass Example {\n    val tag = "Example"\n}\n')
        clean = run([sys.executable, "-I", "-B", str(checker)], fixture)
        self.assertEqual(0, clean["status"], clean)
        self.assertIn("1 string literals", clean["stdout"])

    def test_pack_overlap_refuses_a_run_that_compared_no_pack(self):
        """With no pack tree on disk the comparison is empty, and an empty comparison is not a pass."""
        fixture = self.root / "pack-overlap"
        checker = fixture / "scripts/check-pack-overlap.py"
        write(checker, (SOURCE / "scripts/check-pack-overlap.py").read_text())
        write(fixture / "android/app/src/main/assets/usr/lib/libffi.so", "base")
        (fixture / "android/toolchain_ruby").mkdir(parents=True)
        none = run([sys.executable, "-I", "-B", str(checker)], fixture)
        self.assertEqual(1, none["status"], none)
        self.assertIn("no toolchain pack", none["stdout"])
        write(fixture / "android/toolchain_ruby/src/main/assets/usr/lib/libruby.so", "ruby")
        one = run([sys.executable, "-I", "-B", str(checker)], fixture)
        self.assertEqual(0, one["status"], one)
        self.assertIn("toolchain_ruby", one["stdout"])
        write(fixture / "android/toolchain_ruby/src/main/assets/usr/lib/libffi.so", "ruby")
        shared = run([sys.executable, "-I", "-B", str(checker)], fixture)
        self.assertEqual(1, shared["status"], shared)
        self.assertIn("toolchain_ruby: assets/usr/lib/libffi.so", shared["stdout"])

    def test_extension_check_names_every_aarch64_payload_it_ships(self):
        """A shared object is loaded whatever its mode, so a non-executable one is still named."""
        ext = self.root / "ext"
        write(ext / "package.json", json.dumps({"name": "fixture", "engines": {"vscode": "^1.90.0"}}))
        version = self.root / "VSCODE_VERSION"
        write(version, "1.139.1\n")
        aarch64 = elf_import("libc.so.6", "GLIBC_2.17", "malloc")
        x86 = aarch64[:18] + struct.pack("<H", 62) + aarch64[20:]
        (ext / "bin").mkdir()
        for name, body, mode in (("native/addon.node", aarch64, 0o644), ("lib/libhelper.so", aarch64, 0o644),
                                 ("lib/libversioned.so.1", aarch64, 0o644), ("bin/tool", aarch64, 0o755),
                                 ("lib/libother.so", x86, 0o644), ("lib/notes.txt", b"text", 0o644)):
            path = ext / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(body)
            path.chmod(mode)
        result = run([sys.executable, "-I", "-B", str(SOURCE / "scripts/check-extension.py"), str(ext), str(version)],
                     self.root)
        self.assertEqual(0, result["status"], result)
        named = sorted(line.split(": ", 1)[1] for line in result["stdout"].splitlines()
                       if "carries an aarch64 native" in line)
        self.assertEqual(["bin/tool", "lib/libhelper.so", "lib/libversioned.so.1", "native/addon.node"], named, result)

    def test_document_dates_hold_a_pending_change_to_the_day_it_reaches_main(self):
        """A change not yet on main is stamped when it lands there, never earlier than today."""
        repo = self.root / "dates"
        write(repo / "scripts/check-document-dates.py", (SOURCE / "scripts/check-document-dates.py").read_text())
        legal = "_This document was last updated on {}._\n"
        policy = "**Last Updated: {}**\n"
        env = {"GIT_CONFIG_GLOBAL": os.devnull, "GIT_CONFIG_NOSYSTEM": "1",
               "GIT_AUTHOR_NAME": "Fixture", "GIT_AUTHOR_EMAIL": "fixture@example.invalid",
               "GIT_COMMITTER_NAME": "Fixture", "GIT_COMMITTER_EMAIL": "fixture@example.invalid",
               "GIT_AUTHOR_DATE": "2026-01-05T12:00:00+07:00", "GIT_COMMITTER_DATE": "2026-01-05T12:00:00+07:00"}

        def git(*args):
            result = run(["git", *args], repo, env)
            self.assertEqual(0, result["status"], result)

        def check():
            return run([sys.executable, "-I", "-B", str(repo / "scripts/check-document-dates.py")], repo, env)

        write(repo / "docs/LEGAL_NOTICES.md", legal.format("January 5, 2026"))
        write(repo / "docs/PRIVACY_POLICY.md", policy.format("January 5, 2026"))
        git("init", "-q")
        git("add", "--", ".")
        git("commit", "-q", "-m", "Date both documents")
        git("update-ref", "refs/remotes/origin/main", "HEAD")
        on_main = check()
        self.assertEqual(0, on_main["status"], on_main)

        # The same day on a branch: the squash that carries it is stamped the day it merges.
        git("checkout", "-q", "-b", "edit")
        write(repo / "docs/PRIVACY_POLICY.md", policy.format("January 5, 2026") + "A new paragraph.\n")
        git("commit", "-q", "-am", "Edit the policy")
        branch = check()
        self.assertEqual(1, branch["status"], branch)
        self.assertIn("docs/PRIVACY_POLICY.md", branch["stdout"])
        self.assertNotIn("docs/LEGAL_NOTICES.md", branch["stdout"])
        write(repo / "docs/PRIVACY_POLICY.md", policy.format("December 31, 2099") + "A new paragraph.\n")
        git("commit", "-q", "-am", "Date the policy for its release")
        ahead = check()
        self.assertEqual(0, ahead["status"], ahead)

        # An edit not committed yet has no commit at all; the last one that touched the file is older.
        git("checkout", "-q", "-")
        write(repo / "docs/LEGAL_NOTICES.md", legal.format("January 5, 2026") + "A new row.\n")
        dirty = check()
        self.assertEqual(1, dirty["status"], dirty)
        self.assertIn("docs/LEGAL_NOTICES.md", dirty["stdout"])

    def test_bundle_size_reads_plays_megabytes_the_strict_way(self):
        """Play states its caps in MB; read as MiB they let through a base up to 23 MiB over 500 million bytes."""
        spec = importlib.util.spec_from_file_location("fixture_bundle_size", SOURCE / "scripts/check-bundle-size.py")
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        aab = self.root / "app.aab"
        aab.write_bytes(b"")
        mib = 1024 * 1024
        cases = {
            "base just under 500 million bytes": ({"base": (476 * mib, 900 * mib)}, 0),
            "base between 500 million bytes and 500 MiB": ({"base": (490 * mib, 900 * mib)}, 1),
            "pack between 1.5 billion bytes and 1536 MiB": ({"base": (200 * mib, 600 * mib),
                                                              "toolchain_codex": (1450 * mib, 1500 * mib)}, 1),
        }
        for label, (sizes, status) in cases.items():
            with self.subTest(case=label):
                module.module_sizes = lambda _aab, sizes=sizes: sizes
                with contextlib.redirect_stdout(io.StringIO()) as out:
                    self.assertEqual(status, module.main(["check-bundle-size.py", str(aab)]), out.getvalue())

    def test_build_step_check_refuses_a_copied_notice_missing_from_the_assets_key(self):
        """A notice a download script copies into the cached tree has to move the cache key when it changes."""
        fixture, check = self.step_checks_fixture("build-steps-notices")
        healthy = check("check-build-steps.py")
        self.assertEqual(0, healthy.returncode, healthy.stdout + healthy.stderr)
        self.assertIn("licence notices the build job copies into the cached tree are in", healthy.stdout)
        build = fixture / ".github/workflows/build.yml"
        text = build.read_text()
        entry = "'licenses/**', "
        self.assertEqual(1, text.count(entry), "the assets cache key does not hash licenses/")
        build.write_text(text.replace(entry, "", 1))
        unkeyed = check("check-build-steps.py")
        self.assertNotEqual(0, unkeyed.returncode, unkeyed.stdout + unkeyed.stderr)
        self.assertIn("licenses/LICENSE.ICU", unkeyed.stdout)
        self.assertIn("licenses/COPYRIGHT.musl", unkeyed.stdout)

    def test_workflow_step_check_refuses_an_unbounded_package_index_refresh(self):
        """An apt-get update with no bound on it can hold a stalled mirror for the whole job's budget."""
        self.assertTrue(PYYAML or not os.environ.get("CI"), "CI images carry PyYAML")
        if not PYYAML:
            self.skipTest("check-workflow-steps.py needs PyYAML")
        fixture, check = self.step_checks_fixture("apt-bounded")
        healthy = check("check-workflow-steps.py")
        self.assertEqual(0, healthy.returncode, healthy.stdout + healthy.stderr)
        for name in ("codeoss-build.yml", "build.yml", "release.yml"):
            workflow = fixture / ".github/workflows" / name
            text = workflow.read_text()
            bounded = "            if sudo timeout 120 apt-get update; then\n"
            budget = "\n        timeout-minutes: "
            self.assertEqual(1, text.count(bounded), name)
            for label, edited in (("no timeout on the command", text.replace(bounded, "            if sudo apt-get update; then\n")),
                                  ("no timeout on the step", self.drop_step_budget(text, bounded, budget))):
                workflow.write_text(edited)
                with self.subTest(workflow=name, case=label):
                    result = check("check-workflow-steps.py")
                    self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
                    self.assertIn(f"{name}: job", result.stdout)
                    self.assertIn("apt-get update", result.stdout)
            workflow.write_text(text)

    @staticmethod
    def drop_step_budget(text, inside, budget):
        """`text` without the `timeout-minutes` of the step holding `inside`."""
        at = text.rindex(budget, 0, text.index(inside))
        end = text.index("\n", at + 1)
        return text[:at] + text[end:]

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
        (fixture / "docs").mkdir()
        shutil.copy2(SOURCE / "docs/USER_GUIDE.md", fixture / "docs")
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
        (fixture / "docs").mkdir()
        shutil.copy2(SOURCE / "docs/USER_GUIDE.md", fixture / "docs")

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
        (fixture / "docs").mkdir()
        shutil.copy2(SOURCE / "docs/USER_GUIDE.md", fixture / "docs")

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

    def test_build_step_check_refuses_a_guide_naming_another_npm_major(self):
        fixture = self.root / "build-steps-npm"
        shutil.copytree(SOURCE / "scripts", fixture / "scripts")
        shutil.copytree(SOURCE / ".github/workflows", fixture / ".github/workflows")
        (fixture / "android/app").mkdir(parents=True)
        shutil.copy2(SOURCE / "android/app/build.gradle.kts", fixture / "android/app")
        shutil.copy2(SOURCE / "CONTRIBUTING.md", fixture)
        (fixture / "docs").mkdir()
        shutil.copy2(SOURCE / "docs/USER_GUIDE.md", fixture / "docs")

        def check():
            return subprocess.run([sys.executable, str(fixture / "scripts/check-build-steps.py")],
                                  cwd=fixture, capture_output=True, text=True, timeout=30)

        healthy = check()
        self.assertEqual(0, healthy.returncode, healthy.stdout + healthy.stderr)
        guide = fixture / "docs/USER_GUIDE.md"
        text = guide.read_text()
        line = "npm -v            # npm 11.x"
        self.assertEqual(1, text.count(line))
        guide.write_text(text.replace(line, "npm -v            # npm 10.x"))
        stale = check()
        self.assertNotEqual(0, stale.returncode, stale.stdout + stale.stderr)
        self.assertIn("npm 10.x", stale.stdout)
        guide.write_text(text.replace(line, "npm -v            # npm"))
        unread = check()
        self.assertNotEqual(0, unread.returncode, unread.stdout + unread.stderr)
        self.assertIn("the pattern stopped matching", unread.stdout)

    def step_checks_fixture(self, name):
        """A copy of what check-build-steps.py and check-workflow-steps.py read, and a runner for both."""
        fixture = self.root / name
        shutil.copytree(SOURCE / "scripts", fixture / "scripts")
        shutil.copytree(SOURCE / ".github/workflows", fixture / ".github/workflows")
        (fixture / "android/app").mkdir(parents=True)
        shutil.copy2(SOURCE / "android/app/build.gradle.kts", fixture / "android/app")
        shutil.copy2(SOURCE / "CONTRIBUTING.md", fixture)
        (fixture / "docs").mkdir()
        shutil.copy2(SOURCE / "docs/USER_GUIDE.md", fixture / "docs")

        def check(script):
            return subprocess.run([sys.executable, "-I", str(fixture / "scripts" / script)], cwd=fixture,
                                  capture_output=True, text=True, timeout=60)
        return fixture, check

    def test_step_checks_refuse_a_self_check_step_that_cannot_run(self):
        """A step switched off in place is as unrun as a deleted one, to both step checkers."""
        fixture, check = self.step_checks_fixture("switched-off")
        lint = fixture / ".github/workflows/lint.yml"
        text = lint.read_text()
        step = "      - name: Check the bundled JavaScript runtime\n"
        job = "  lint:\n    name: Android Lint\n"
        self.assertEqual(1, text.count(step))
        self.assertEqual(1, text.count(job))
        for script in ("check-build-steps.py", "check-workflow-steps.py"):
            if script == "check-workflow-steps.py" and not PYYAML:
                continue
            healthy = check(script)
            self.assertEqual(0, healthy.returncode, healthy.stdout + healthy.stderr)
        switched_off = {
            "a step under if: ${{ false }}": text.replace(step, step + "        if: ${{ false }}\n"),
            "a step that may fail": text.replace(step, step + "        continue-on-error: true\n"),
            "a step under an event lint never sees":
                text.replace(step, step + "        if: github.event_name == 'schedule'\n"),
            "a step whose bullet carries the condition":
                text.replace(step, "      - if: ${{ false }}\n        name: Check the bundled JavaScript runtime\n"),
            "the job under if: ${{ false }}": text.replace(job, job + "    if: ${{ false }}\n"),
            "the job that may fail": text.replace(job, job + "    continue-on-error: true\n"),
        }
        for label, edited in switched_off.items():
            lint.write_text(edited)
            with self.subTest(case=label, script="check-build-steps.py"):
                result = check("check-build-steps.py")
                self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
                self.assertIn("test-dns-proxy.js", result.stdout)
            if PYYAML:
                with self.subTest(case=label, script="check-workflow-steps.py"):
                    result = check("check-workflow-steps.py")
                    self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
                    self.assertIn("job lint" if label.startswith("the job") else
                                  "Check the bundled JavaScript runtime", result.stdout)
        # A list written at another indentation is refused rather than read without its conditions.
        steps = "    steps:\n      - name: Checkout\n"
        self.assertEqual(1, text.count(steps))
        lint.write_text(text.replace(steps, "    steps:\n    - name: Checkout\n"))
        moved = check("check-build-steps.py")
        self.assertNotEqual(0, moved.returncode, moved.stdout + moved.stderr)
        self.assertIn("lint.yml: a steps: list this reader cannot place", moved.stdout)
        # A condition the workflow lists for a step that still runs, and a step that may not fail.
        for label, edited in {
            "always()": text.replace(step, step + "        if: always()\n"),
            "continue-on-error: false": text.replace(step, step + "        continue-on-error: false\n"),
        }.items():
            lint.write_text(edited)
            with self.subTest(case=label):
                result = check("check-build-steps.py")
                self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertTrue(PYYAML or not os.environ.get("CI"),
                        "CI images carry PyYAML; without it half of this case did not run")

    # Skipped on a machine without PyYAML, never in CI: a skip there reads as a pass in a log nobody opens,
    # and CI images carry it, so its absence there is a changed image and fails.
    @unittest.skipUnless(PYYAML or os.environ.get("CI"), "check-workflow-steps.py needs PyYAML, which CI images carry")
    def test_workflow_step_check_holds_the_release_document_gates(self):
        """The release steps that hold the documents to the build are required steps like any other gate."""
        self.assertTrue(PYYAML, "CI set and no PyYAML for this interpreter with -I: the check cannot run here")
        fixture = self.root / "workflow-steps"
        (fixture / "scripts").mkdir(parents=True)
        shutil.copy2(SOURCE / "scripts/check-workflow-steps.py", fixture / "scripts")
        shutil.copytree(SOURCE / ".github/workflows", fixture / ".github/workflows")
        release = fixture / ".github/workflows/release.yml"
        text = release.read_text()

        def check(edited):
            release.write_text(edited)
            return subprocess.run([sys.executable, "-I", str(fixture / "scripts/check-workflow-steps.py")],
                                  cwd=fixture, capture_output=True, text=True, timeout=30)

        healthy = check(text)
        self.assertEqual(0, healthy.returncode, healthy.stdout + healthy.stderr)
        sizes = ("      - name: Check the documented core sizes against the APK\n"
                 "        run: python3 scripts/check-size-claims.py --apk "
                 "android/app/build/outputs/apk/release/app-release.apk\n")
        self.assertEqual(1, text.count(sizes))
        deleted = check(text.replace(sizes, ""))
        self.assertNotEqual(0, deleted.returncode, deleted.stdout + deleted.stderr)
        self.assertIn("Check the documented core sizes against the APK", deleted.stdout)
        tables = ("      - name: Check every Markdown table is drawn as written\n"
                  "        run: |\n"
                  "          python3 scripts/check-markdown-tables.py --self-test\n"
                  "          python3 scripts/check-markdown-tables.py\n")
        self.assertEqual(1, text.count(tables))
        dropped = check(text.replace(tables, ""))
        self.assertNotEqual(0, dropped.returncode, dropped.stdout + dropped.stderr)
        self.assertIn("Check every Markdown table is drawn as written", dropped.stdout)
        guide = "      - name: Hold the user guide's tool versions to what this build resolved\n"
        self.assertEqual(1, text.count(guide))
        for key in ("continue-on-error: true", "if: ${{ false }}"):
            skipped = check(text.replace(guide, f"{guide}        {key}\n"))
            self.assertNotEqual(0, skipped.returncode, (key, skipped.stdout + skipped.stderr))
            self.assertIn("Hold the user guide's tool versions", skipped.stdout, key)

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

    def test_bridge_spec_gate_reads_an_expression_body_and_refuses_a_commented_method(self):
        source = SOURCE / "scripts/check-bridge-api-spec.py"
        gate = types.ModuleType("fixture_bridge_spec")
        gate.__file__ = str(source)
        exec(compile(source.read_text(encoding="utf-8"), str(source), "exec"), gate.__dict__)
        bridge = self.root / "AndroidBridge.kt"
        gate.BRIDGE = bridge
        write(bridge, "class B {\n    @JavascriptInterface\n    fun ping(token: String): Boolean = impl(token)\n\n"
                      "    @JavascriptInterface\n    fun inferred(token: String) = impl(token)\n\n"
                      "    @JavascriptInterface\n    fun other(token: String): String {\n        return \"\"\n    }\n}\n")
        found, expected = gate.signatures(bridge)
        self.assertEqual(3, expected)
        self.assertEqual(("token: String", "Boolean"), found["ping"])
        self.assertEqual(("token: String", "String"), found["other"])
        # No declared type: it cannot equal any document's, so the gate reports a difference, never a match.
        self.assertEqual(("token: String", "<inferred>"), found["inferred"])
        write(bridge, "class B {\n    /*\n    @JavascriptInterface\n    fun gone(token: String): Boolean {\n    }\n    */\n}\n")
        with self.assertRaises(SystemExit) as refused:
            gate.signatures(bridge)
        self.assertIn("inside a block comment", str(refused.exception))
        # Prose that names the annotation in a block comment is no method.
        write(bridge, "/**\n * one `@JavascriptInterface` method per call\n */\nclass B\n")
        self.assertEqual(({}, 0), gate.signatures(bridge))

    def test_wheelhouse_names_reach_the_documents_people_read(self):
        source = SOURCE / "scripts/check-wheelhouse-abi.py"
        gate = types.ModuleType("fixture_wheelhouse_abi")
        gate.__file__ = str(source)
        exec(compile(source.read_text(encoding="utf-8"), str(source), "exec"), gate.__dict__)
        repo = self.root / "abi-repo"
        gate.REPO = repo
        manifest = {"packages": [
            {"name": "numpy", "version": "2.5.0"}, {"name": "pydantic-core", "version": "2.41.5"},
            {"name": "cryptography", "version": "50.0.2"}, {"name": "cffi", "version": "2.1.1"},
        ]}
        guide = ("- `numpy` 2.5.0\n- `pydantic-core` 2.41.5\n"
                 "- `cryptography` 50.0.2, with `cffi` 2.1.1, which it needs to load\n")
        notice = ("| Software | License |\n|---|---|\n| numpy | BSD 3-Clause |\n| pydantic-core | MIT |\n"
                  "| cryptography | Apache 2.0 |\n| cffi | MIT-0 |\n")
        legal = "| numpy 2.5.0 | x |\n| pydantic_core 2.41.5 | x |\n| cryptography 50.0.2 | x |\n| cffi 2.1.1 | x |\n"

        def problems(guide=guide, notice=notice, legal=legal):
            write(repo / "docs/USER_GUIDE.md", guide)
            write(repo / "NOTICE.md", notice)
            write(repo / "docs/LEGAL_NOTICES.md", legal)
            return gate.document_problems(manifest)

        self.assertEqual([], problems())
        # A version that ends a sentence is still that version.
        self.assertEqual([], problems(guide=guide.replace("2.1.1, which it needs to load", "2.1.1.")))
        # Named only in prose: a reader cannot tell which version pip is offered.
        self.assertEqual(["docs/USER_GUIDE.md does not name cryptography 50.0.2"],
                         problems(guide=guide.replace("`cryptography` 50.0.2", "the cryptography module")))
        # A version the manifest no longer pins, or one that only starts like it.
        for stale in ("cffi 2.1.0", "cffi 2.1.10", "cffi 2.1.1.1", "cffi 2.1.1.post1", "cffi 2.1.1rc1"):
            self.assertEqual(["docs/LEGAL_NOTICES.md does not name cffi 2.1.1"],
                             problems(legal=legal.replace("cffi 2.1.1", stale)), stale)
        # Another package whose name ends in this one's.
        self.assertEqual(["docs/LEGAL_NOTICES.md does not name cffi 2.1.1"],
                         problems(legal=legal.replace("| cffi 2.1.1", "| pycffi 2.1.1")))
        # The licence table is what credits a wheel; a mention elsewhere in NOTICE.md is not a row.
        self.assertEqual(["NOTICE.md has no table row for cffi"],
                         problems(notice=notice.replace("| cffi | MIT-0 |\n", "") + "\ncffi is compiled here.\n"))
        (repo / "docs/USER_GUIDE.md").unlink()
        write(repo / "NOTICE.md", notice.replace("| cffi | MIT-0 |\n", ""))
        self.assertEqual(
            ["docs/USER_GUIDE.md is missing", "NOTICE.md has no table row for cffi"], gate.document_problems(manifest),
        )

    def test_guide_tool_versions_follow_the_build_manifest(self):
        source = SOURCE / "scripts/check-guide-versions.py"
        gate = types.ModuleType("fixture_guide_versions")
        gate.__file__ = str(source)
        exec(compile(source.read_text(encoding="utf-8"), str(source), "exec"), gate.__dict__)
        guide = (
            "### Bundled Tools\n\nAll tools are available:\n\n```\n"
            "node -v           # Node.js 24.x\n"
            "npm -v            # npm 11.x\n"
            "python3 --version # Python 3.14.x\n"
            "python3 -m pip --version   # pip, bundled inside Python's site-packages\n"
            "git --version     # Git 2.x\n"
            "bash --version    # Bash 5.3.x\n"
            "tmux -V           # tmux 3.x\n"
            "make --version    # GNU Make 4.4.1\n"
            "ssh -V            # OpenSSH 10.x client\n"
            "rg --version      # ripgrep (powers VS Code search)\n"
            "```\n"
        )
        shipped = {"nodejs-lts": "24.22.3", "python": "3.14.10", "git": "2.56.1", "bash": "5.3.20",
                   "tmux": "3.8", "make": "4.4.1-1", "openssh": "10.6p2", "openssl": "1:3.6.5"}

        def manifest(**changes):
            rows = {**shipped, **changes}
            return "app-version\t1.10.1\n" + "".join(
                f"termux\t{package}\t{version}\tdigest\n" for package, version in rows.items() if version)

        self.assertEqual([], gate.problems(guide, manifest()))
        # The version the program reports: an epoch and a Debian revision are not part of it.
        self.assertEqual([], gate.problems(guide, manifest(make="1:4.4.1-3")))
        for package, version, command, stated in (
            ("make", "4.4.2-1", "make --version", "4.4.1"),
            ("nodejs-lts", "25.0.1", "node -v", "24.x"),
            ("python", "3.15.0", "python3 --version", "3.14.x"),
            ("bash", "5.30.1", "bash --version", "5.3.x"),
            ("openssh", "11.0p1", "ssh -V", "10.x"),
        ):
            self.assertEqual(
                [f"the guide says `{command}` gives {stated}; this build ships {package} {gate.upstream(version)}"],
                gate.problems(guide, manifest(**{package: version})), package)
        self.assertEqual(["the manifest records no tmux, so `tmux -V` cannot be compared"],
                         gate.problems(guide, manifest(tmux=None)))
        self.assertEqual(["`ruby -v` gives a version and has no Termux package in PACKAGES to compare it with"],
                         gate.problems(guide.replace("rg --version", "ruby -v           # Ruby 3.4.x\nrg --version"),
                                       manifest()))
        self.assertEqual(
            ["the Bundled Tools list has no versioned `make --version` line; the pattern stopped matching"],
            gate.problems(guide.replace("# GNU Make 4.4.1", "# GNU Make"), manifest()))
        with self.assertRaises(SystemExit):
            gate.problems(guide.replace("### Bundled Tools", "### Tools"), manifest())
        # The guide this repository ships still reads as the release step expects, so a rewording
        # fails here, on the pull request, rather than on the tag.
        listed = gate.listed_versions((SOURCE / "docs/USER_GUIDE.md").read_text(encoding="utf-8"))
        self.assertLessEqual(set(gate.PACKAGES), set(listed))
        self.assertLessEqual(set(listed), set(gate.PACKAGES) | gate.CHECKED_ELSEWHERE)

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

    def test_toolchain_zip_is_held_to_the_sizes_the_registry_records(self):
        """The card quotes both figures, and a figure below what ships costs the person choosing."""
        # Two entries, the second with figures of its own: a read that runs on past the first entry's
        # end answers with the second pack's figure when the first has none, and passes by accident.
        registry = (
            "ToolchainInfo(\n"
            "    packName = \"toolchain_ruby\",\n"
            "    // estimatedSize = 999_999_999, a figure in a comment is not the figure\n"
            "    estimatedSize = %s,\n"
            "    downloadSize = %s,\n"
            ")\n"
            "ToolchainInfo(\n"
            "    packName = \"toolchain_java\",\n"
            "    estimatedSize = 192_000_000,\n"
            "    downloadSize = 70_000_000,\n"
            ")\n"
        )

        def package(estimated, download, stale_zip=False):
            with tempfile.TemporaryDirectory() as raw:
                root = Path(raw)
                script = root / "scripts" / "package-toolchains.sh"
                script.parent.mkdir()
                shutil.copy(SOURCE / "scripts" / "package-toolchains.sh", script)
                write(root / "android/app/src/main/kotlin/com/vscodroid/setup/ToolchainRegistry.kt",
                      registry % (estimated, download))
                assets = root / "android" / "toolchain_ruby" / "src" / "main" / "assets"
                write(assets / "toolchain_ruby.json", '{"version": "1"}')
                (assets / "usr" / "lib").mkdir(parents=True)
                (assets / "usr" / "lib" / "blob").write_bytes(os.urandom(300_000))
                if stale_zip:
                    write(root / "toolchain-zips" / "toolchain_ruby.zip", "from an earlier run")
                result = run(["bash", str(script), "ruby"], root)
                result["zip"] = (root / "toolchain-zips" / "toolchain_ruby.zip").exists()
                return result

        within = package("1_000_000", "1_000_000")
        self.assertEqual(within["status"], 0, within)
        self.assertTrue(within["zip"])
        self.assertIn("Download:", within["stdout"])
        self.assertIn("recorded 1000000)", within["stdout"])

        small_zip = package("1_000_000", "1_000")
        self.assertEqual(small_zip["status"], 1, small_zip)
        self.assertIn("downloadSize", small_zip["stderr"])
        self.assertFalse(small_zip["zip"], "a ZIP the gate refused was left to be attached to a release")

        small_tree = package("1_000", "1_000_000", stale_zip=True)
        self.assertEqual(small_tree["status"], 1, small_tree)
        self.assertIn("estimatedSize", small_tree["stderr"])
        self.assertFalse(small_tree["zip"], "a ZIP from an earlier run survived a refused tree")

        # A value that is not a plain number is no figure: it must not be read from the next entry.
        for unreadable in ("oops", "RUBY_ZIP_BYTES", "11_000_000 + 1"):
            result = package("1_000_000", unreadable)
            self.assertEqual(result["status"], 1, (unreadable, result))
            self.assertIn("records no downloadSize", result["stderr"], (unreadable, result))
            self.assertNotIn("70000000", result["stdout"], "the next pack's figure was read for this one")

        # A Long literal is a plain number.
        suffixed = package("1_000_000", "1_000_000L")
        self.assertEqual(suffixed["status"], 0, suffixed)

    def test_size_claims_follow_the_registry_and_the_apk(self):
        """A figure a document quotes by hand has to be the one the code gives."""
        def registry(codex_download="153_000_000", ruby_download="11_300_000", extra=""):
            entries = {
                "ruby": ("41_000_000", ruby_download), "java": ("192_000_000", "70_000_000"),
                "cpp": ("280_000_000", "81_000_000"), "codex": ("395_000_000", codex_download),
            }
            return "".join(
                f'ToolchainInfo(\n    packName = "toolchain_{pack}",\n    estimatedSize = {est},\n'
                f"    downloadSize = {dl},\n)\n"
                for pack, (est, dl) in entries.items()
            ) + extra

        readme = (
            "| Play Store download (core)             | ~1 MB              |\n"
            "| + Each toolchain (on-demand)           | 11-153 MB per toolchain |\n"
            "| Extracted to internal storage (core)   | ~1 MB              |\n"
            "| Extracted, plus Ruby and Java          | ~230 MB              |\n"
            "| Extracted, plus Ruby, Java and C/C++   | ~510 MB            |\n"
            "| + Codex, on top of any of these        | +395 MB              |\n"
            "| Storage      | ~6 MB free to install            |\n"
            "| Free space required to install         | ~6 MB              |\n"
        )
        guide = (
            "The core download is roughly 1 MB (the APK on GitHub Releases is 1 MB), and you need about 6 MB free "
            "for the extraction that follows.\n"
            "About 1 MB of files are unpacked, one by one. Setup wants room: the ~6 MB above is what it insists on "
            "having free before it will start. An update (roughly 2 MB for a complete tree rather than 6 MB).\n"
            "| Language | Download Size | Installed Size | Includes |\n"
            "| Ruby | 11 MB | 41 MB | ruby |\n"
            "| Java 25 (OpenJDK) | 70 MB | 192 MB | java |\n"
            "| C/C++ (clang) | 81 MB | 280 MB | cc |\n"
            "| Codex | 153 MB | 395 MB | codex |\n"
            "it runs, which for this one is about 840 MB. Reinstalling\n"
            "once unpacked, on top of the\n  toolchain's 395 MB, and Codex keeps\n"
            "Core installation extracts approximately 1 MB to internal storage. With Ruby and Java installed, "
            "expect around 230 MB, and about 510 MB with C/C++ as well. The Codex toolchain adds about 395 MB. "
            "These are the sizes of the files; setup counts their allocation and adds a small working margin, "
            "which is why it needs about 6 MB free before it starts.\n"
        )
        srs = (
            "| NFR-RES-05 | Runtime storage | About 1 MiB (1 MB), the asset tree that is measured; about 219 MiB "
            "(230 MB) with Ruby and Java installed, and about 486 MiB (510 MB) with C/C++ as well, by the estimates |\n"
            "| Storage | About 1 MB extracted for core and about 6 MB free for a fresh installation |\n"
        )
        site = "<dt>First launch</dt>\n<dd>Around 6 MB free</dd>\n"
        manager = "internal const val SPACE_BUFFER = 50_000_000L\n"
        # A block of a million bytes makes the allocation setup counts big enough to see in whole MB: two files
        # of 400,000 and 600,000 bytes and the directory one of them sits in take 4 MB, and with the margin and
        # the 1 MB of files a fresh install needs 6 MB, over the 1.4 MB minimum. An update needs room to rewrite
        # the largest file, 600,000 bytes, plus that minimum: 2 MB.
        setup = ("private const val EXTRACTION_SLACK_BYTES = 1_400_000L\n"
                 "private const val EXTRACTION_WORKING_MARGIN_BYTES = 1L * 1_000_000L\n"
                 "private const val EXTRACTION_BLOCK_BYTES = 1_000_000L\n")

        def check(*, readme=readme, guide=guide, srs=srs, site=site, registry_text=None, apk=None, setup=setup):
            with tempfile.TemporaryDirectory() as raw:
                root = Path(raw)
                script = root / "scripts" / "check-size-claims.py"
                script.parent.mkdir()
                shutil.copy(SOURCE / "scripts" / "check-size-claims.py", script)
                kotlin = root / "android/app/src/main/kotlin/com/vscodroid/setup"
                write(kotlin / "ToolchainRegistry.kt", registry_text if registry_text is not None else registry())
                write(kotlin / "ToolchainManager.kt", manager)
                write(kotlin / "FirstRunSetup.kt", setup)
                write(root / "README.md", readme)
                write(root / "docs/USER_GUIDE.md", guide)
                write(root / "docs/02-SRS.md", srs)
                write(root / "docs/site/index.html", site)
                command = [sys.executable, "-I", str(script)]
                if apk:
                    path = root / "app-release.apk"
                    with zipfile.ZipFile(path, "w") as archive:
                        archive.writestr("assets/core.bin", b"x" * (apk["assets"] - 600_000))
                        archive.writestr("assets/usr/big.bin", b"x" * 600_000)
                        archive.writestr("assets/nls/messages.bin", b"x" * 1_000)
                        archive.writestr("classes.dex", b"")
                    command += ["--apk", str(path)]
                return run(command, root)

        self.assertEqual(check()["status"], 0, check())
        self.assertEqual(check(apk={"assets": 1_000_000})["status"], 0, check(apk={"assets": 1_000_000}))

        def refused(label, expected, **kwargs):
            result = check(**kwargs)
            self.assertEqual(result["status"], 1, (label, result))
            self.assertIn(expected, result["stdout"] + result["stderr"], (label, result))

        refused("codex on top", "README.md", readme=readme.replace("+395 MB", "+375 MB"))
        refused("codex row", "Codex row of the toolchain table", guide=guide.replace("| Codex | 153 MB", "| Codex | 134 MB"))
        refused("codex free space", "Codex install free space", guide=guide.replace("about 840 MB", "about 770 MB"))
        refused("srs total", "docs/02-SRS.md", srs=srs.replace("(230 MB)", "(200 MB)"))
        refused("total with C/C++", "total with C/C++", readme=readme.replace("~510 MB", "~480 MB"))
        refused("a sentence that went away", "expected exactly one", guide=guide.replace("The Codex toolchain adds about 395 MB.", ""))
        refused("registry moved", "Codex row of the toolchain table", registry_text=registry(codex_download="134_000_000"))
        refused("range follows the registry", "toolchain download range", registry_text=registry(ruby_download="9_000_000"))
        refused("a figure that is not a number", "no plain number", registry_text=registry(codex_download="CODEX_ZIP_BYTES"))
        refused(
            "a pack with no label", "no label",
            registry_text=registry(extra='ToolchainInfo(\n    packName = "toolchain_lua",\n    estimatedSize = 1,\n    downloadSize = 1,\n)\n'),
        )
        refused("core extraction against the APK", "the APK holds", apk={"assets": 2_000_000})
        refused("free space quoted twice, differently", "free space to install",
                guide=guide.replace("you need about 6 MB free", "you need about 8 MB free"))
        refused("free space in the requirements", "docs/02-SRS.md", srs=srs.replace("about 6 MB free", "about 5 MB free"))
        refused("the storage sentence went away", "expected exactly one",
                guide=guide.replace("which is why it needs about 6 MB free before it starts", "so it needs room"))
        refused("the update sentence went away", "expected exactly one",
                guide=guide.replace("roughly 2 MB for a complete tree", "less for a complete tree"))
        # Documents that agree with each other and not with what setup asks of this APK.
        refused("free space against the APK", "setup asks a fresh install for 6.0 MB", apk={"assets": 1_000_000},
                readme=readme.replace("6 MB", "5 MB"), guide=guide.replace("6 MB", "5 MB"), srs=srs.replace("6 MB", "5 MB"),
                site=site.replace("6 MB", "5 MB"))
        refused("update against the APK", "setup asks an update for 2.0 MB", apk={"assets": 1_000_000},
                guide=guide.replace("roughly 2 MB", "roughly 3 MB"))
        refused("a setup figure that is not a number", "EXTRACTION_SLACK_BYTES",
                setup=setup.replace("1_400_000L", "SLACK"))
        refused("free space on the site", "docs/site/index.html", site=site.replace("6 MB", "5 MB"))
        refused("the site's sentence went away", "expected exactly one", site=site.replace("Around 6 MB free", "Plenty"))
        refused("the release APK's size went away", "expected exactly one",
                guide=guide.replace(" (the APK on GitHub Releases is 1 MB)", ""))
        refused("the release APK's size against the APK", "size of the APK on GitHub Releases reads 2 MB", apk={"assets": 1_000_000},
                guide=guide.replace("GitHub Releases is 1 MB", "GitHub Releases is 2 MB"))

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
