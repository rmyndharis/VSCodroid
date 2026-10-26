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
    functions = "\n".join(shell_function(text, name) for name in ("pass", "fail", "skip", "vlog"))
    cases = {}
    for label in ("install-fail", "build-fail", "missing-apk", "install-nonzero-success", "success", "skip-install"):
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
ADB="$PWD/adb"
PASS=0 FAIL=0 SKIP=0 FAILURES=""
RED="" GREEN="" YELLOW="" BOLD="" RESET=""
VERBOSE=false
SKIP_BUILD=true
SKIP_INSTALL=false
[ "$SCENARIO" = build-fail ] && SKIP_BUILD=false
if [ "$SCENARIO" = skip-install ]; then SKIP_BUILD=false; SKIP_INSTALL=true; fi
'''
        script = preamble + functions + "\n" + phase
        script += '\nprintf completed > "$PWD/continued"\n[ "$FAIL" -eq 0 ]\n'
        write(case / "setup.sh", script)
        observed = run(["/bin/bash", str(case / "setup.sh")], case,
                       {"SCENARIO": label, "CALL_LOG": str(case / "calls.txt")})
        calls = (case / "calls.txt").read_text().splitlines()
        observed.update({"calls": calls, "continued": (case / "continued").exists(),
                         "clears": sum(line.startswith("shell pm clear ") for line in calls),
                         "installs": sum(line.startswith("install ") for line in calls)})
        cases[label] = observed
    controls = (cases["success"]["status"] == 0 and cases["success"]["clears"] == 1
                and cases["success"]["installs"] == 1 and cases["skip-install"]["status"] == 0
                and cases["skip-install"]["clears"] == 0 and cases["skip-install"]["installs"] == 0)
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
                    'Not compiled here; the manifest records where each one came from.</p>\n'
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

    def test_verify_rejects_a_tree_containing_only_unsupported_addons(self):
        self.assert_contract(forwarders(SOURCE / "scripts/gen-glibc-forwarders.py", self.root / "addons"))

    def test_failed_build_or_install_never_clears_app_data(self):
        self.assert_contract(device_setup(SOURCE / "scripts/device-test.sh", self.root / "setup"))

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

    def test_wheelhouse_accepts_relative_and_external_output_paths(self):
        self.assert_contract(wheelhouse(SOURCE / "scripts/build-wheelhouse.py", self.root / "wheels"))


if __name__ == "__main__":
    unittest.main()
