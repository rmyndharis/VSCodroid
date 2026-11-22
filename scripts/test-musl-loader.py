#!/usr/bin/env python3
"""Offline checks of the download and native build scripts.

MuslLoaderDownloadTest runs download-musl-loader.sh and asks which loader it
installs. DownloadAndBuildScriptsTest covers the rest: what the Ruby pack strips,
which directories the native builds write to, what package-toolchains.sh leaves
for a skipped toolchain, which tools setup.sh asks for, and how a Termux .deb is
read.

For the loader, the script is copied into a throwaway tree whose scripts/ directory holds an RSA key
made for the run in place of Alpine's, so an index can be signed here. curl is a
stand-in on PATH that serves the index and the packages from files the test writes,
and verify-android-elf.py is a stand-in that accepts anything: the fixture loader is
not an ELF object, and the gate under test is the script's own chain from the signed
index to the bytes it installs.

A package is three gzip streams (signature, control, data) and an index two
(signature, index). Only the data stream is reached by the chain the script walks,
and only the second stream of the index is signed, so whatever sits in a first
stream is bytes anyone could have written. The cases below put a loader, or an
index entry, there.
"""

import base64
import gzip
import hashlib
import io
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import tempfile
import time
import unittest

SOURCE = Path(__file__).resolve().parents[1]
SCRIPT = SOURCE / "scripts" / "download-musl-loader.sh"
KEY_NAME = ".SIGN.RSA.alpine-devel@lists.alpinelinux.org-616ae350.rsa.pub"
VERSION = "1.2.5-r23"
GENUINE = b"\x7fELF the loader the signed index leads to"
HOSTILE = b"\x7fELF a loader nothing signed"
END = b"\0" * 1024

CURL = """#!/bin/sh
out=; url=
while [ $# -gt 0 ]; do
    case "$1" in
        -o) out=$2; shift 2 ;;
        -*) shift ;;
        *) url=$1; shift ;;
    esac
done
printf '%s\\n' "${url##*/}" >> "$FIXTURES/requests"
[ -f "$FIXTURES/${url##*/}" ] || { echo "curl: (22) ${url##*/} is not served" >&2; exit 22; }
cp "$FIXTURES/${url##*/}" "$out"
"""


def header(name, size, damaged=False):
    info = tarfile.TarInfo(name)
    info.size = size
    info.mode = 0o755
    info.mtime = int(time.time())
    block = bytearray(info.tobuf(format=tarfile.USTAR_FORMAT))
    if damaged:
        block[148:156] = b"0000000\0"
    return bytes(block)


def member(name, data):
    return header(name, len(data)) + data + b"\0" * (-len(data) % 512)


def gz(raw):
    return gzip.compress(raw, mtime=0)


def package(loader):
    """Control and data streams for a package carrying loader, and the C: an index gives them."""
    data = gz(member("lib/ld-musl-aarch64.so.1", loader) + END)
    pkginfo = "pkgname = musl\ndatahash = %s\n" % hashlib.sha256(data).hexdigest()
    # apk-tools writes the control stream without an end-of-archive marker.
    control = gz(member(".PKGINFO", pkginfo.encode()))
    return control, data, "Q1" + base64.b64encode(hashlib.sha1(control).digest()).decode()


class MuslLoaderDownloadTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name) / "repo"
        self.fixtures = Path(temporary.name) / "fixtures"
        self.fixtures.mkdir()
        scripts = self.root / "scripts"
        scripts.mkdir(parents=True)
        shutil.copy(SCRIPT, scripts / SCRIPT.name)
        (scripts / "verify-android-elf.py").write_text("import sys\nsys.exit(0)\n")
        (self.root / "licenses").mkdir()
        shutil.copy(SOURCE / "licenses" / "COPYRIGHT.musl", self.root / "licenses" / "COPYRIGHT.musl")
        self.private_key = Path(temporary.name) / "key.pem"
        subprocess.run(["openssl", "genpkey", "-algorithm", "RSA", "-pkeyopt", "rsa_keygen_bits:2048",
                        "-out", str(self.private_key)], check=True, capture_output=True)
        subprocess.run(["openssl", "pkey", "-in", str(self.private_key), "-pubout",
                        "-out", str(scripts / "alpine-devel-616ae350.rsa.pub")], check=True, capture_output=True)
        self.bin = Path(temporary.name) / "bin"
        self.bin.mkdir()
        (self.bin / "curl").write_text(CURL)
        (self.bin / "curl").chmod(0o755)
        self.jni = self.root / "android/app/src/main/jniLibs/arm64-v8a"
        self.work = self.root / "toolchains/musl"

    def sign(self, raw):
        unsigned = self.fixtures / "to-sign"
        unsigned.write_bytes(raw)
        return subprocess.run(["openssl", "dgst", "-sha1", "-sign", str(self.private_key), str(unsigned)],
                              check=True, capture_output=True).stdout

    def serve_index(self, c1, version=VERSION, unsigned_entry=None):
        """The signed index names version with c1; unsigned_entry, when given, is index text the
        first stream carries after the signature, behind a header Python's reader stops at."""
        raw = (member("DESCRIPTION", b"v3.23\n")
               + member("APKINDEX", ("C:%s\nP:musl\nV:%s\n\n" % (c1, version)).encode()) + END)
        signed = gz(raw)
        first = member(KEY_NAME, self.sign(signed))
        if unsigned_entry is not None:
            # Python's tar reader ends the listing at a damaged header, so the script's own
            # check of the signature stream sees one entry. bsdtar retries past it, finds the
            # entry below, and takes everything up to the signed stream's end marker as its
            # content: the signed APKINDEX header is then data, never a file.
            filler = unsigned_entry + b"\n" * (-len(unsigned_entry) % 512)
            first += header("damaged", 0, damaged=True)
            first += header("APKINDEX", len(filler) + len(raw) - len(END)) + filler
        (self.fixtures / "APKINDEX.tar.gz").write_bytes(gz(first) + signed)

    def serve_package(self, version, signature_stream, control, data, trailing=b""):
        (self.fixtures / ("musl-%s.apk" % version)).write_bytes(signature_stream + control + data + trailing)

    def run_script(self):
        env = dict(os.environ, PATH="%s:%s" % (self.bin, os.environ.get("PATH", "/usr/bin:/bin")),
                   FIXTURES=str(self.fixtures), LC_ALL="C")
        env.pop("ALPINE_BRANCH", None)
        env.pop("ALPINE_INDEX_MAX_AGE_DAYS", None)
        result = subprocess.run(["bash", str(self.root / "scripts" / SCRIPT.name)], env=env,
                                capture_output=True, text=True, timeout=120)
        return result

    def installed(self):
        path = self.jni / "libldmusl.so"
        return path.read_bytes() if path.exists() else None

    def signature_stream(self):
        return gz(member(".SIGN.RSA.alpine-devel@lists.alpinelinux.org-616ae350.rsa.pub", b"package signature"))

    def assert_installed_genuine(self, result):
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual(GENUINE, self.installed(), result.stdout + result.stderr)
        record = (self.work / "resolved-musl.tsv").read_text().split("\t")
        self.assertEqual(["musl", VERSION, hashlib.sha256(GENUINE).hexdigest() + "\n"], record)

    def test_the_loader_the_signed_index_leads_to_is_installed(self):
        control, data, c1 = package(GENUINE)
        self.serve_index(c1)
        self.serve_package(VERSION, self.signature_stream(), control, data)
        self.assert_installed_genuine(self.run_script())

    def test_a_loader_in_the_signature_stream_is_never_installed(self):
        # The first stream is covered by neither the index's C: nor the datahash. A tar run over
        # the whole file reads it first, and an end-of-archive marker after the entry stops the
        # run before the data stream: the loader installed was the one nothing signed.
        control, data, c1 = package(GENUINE)
        self.serve_index(c1)
        hostile_first = gz(member("lib/ld-musl-aarch64.so.1", HOSTILE) + END)
        self.serve_package(VERSION, hostile_first, control, data)
        self.assert_installed_genuine(self.run_script())

    def test_bytes_after_the_data_stream_refuse_the_package(self):
        control, data, c1 = package(GENUINE)
        self.serve_index(c1)
        trailing = gz(member("lib/ld-musl-aarch64.so.1", HOSTILE) + END)
        self.serve_package(VERSION, self.signature_stream(), control, data, trailing)
        result = self.run_script()
        self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("follow the data stream", result.stdout + result.stderr)
        self.assertIsNone(self.installed())
        self.assertFalse((self.work / ("musl-%s.apk" % VERSION)).exists(), "a refused package stayed cached")
        self.assertEqual("", (self.work / "resolved-musl.tsv").read_text())

    def test_an_index_entry_outside_the_signed_stream_is_never_read(self):
        # The unsigned entry names another release of the same musl with its own C:, and that
        # release is served, so a script that reads it walks a chain that holds end to end.
        control, data, c1 = package(GENUINE)
        hostile_control, hostile_data, hostile_c1 = package(HOSTILE)
        self.serve_index(c1, unsigned_entry=("C:%s\nP:musl\nV:1.2.5-r99\n\n" % hostile_c1).encode())
        self.serve_package(VERSION, self.signature_stream(), control, data)
        self.serve_package("1.2.5-r99", self.signature_stream(), hostile_control, hostile_data)
        result = self.run_script()
        self.assert_installed_genuine(result)
        self.assertNotIn("musl-1.2.5-r99.apk", (self.fixtures / "requests").read_text())

    def test_a_data_stream_the_signed_metadata_does_not_name_is_refused(self):
        control, _, c1 = package(GENUINE)
        _, hostile_data, _ = package(HOSTILE)
        self.serve_index(c1)
        self.serve_package(VERSION, self.signature_stream(), control, hostile_data)
        result = self.run_script()
        self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("datahash", result.stdout + result.stderr)
        self.assertIsNone(self.installed())


def write(path, text, executable=False):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")
    if executable:
        path.chmod(0o700)


def run(command, cwd, extra=None):
    env = {"PATH": "/usr/bin:/bin", "LC_ALL": "C", "PYTHONDONTWRITEBYTECODE": "1",
           "TMPDIR": str(cwd), **(extra or {})}
    result = subprocess.run(command, cwd=cwd, env=env, capture_output=True,
                            text=True, timeout=60)
    return {"status": result.returncode, "stdout": result.stdout, "stderr": result.stderr}


class DownloadAndBuildScriptsTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)

    def test_ruby_pack_strips_only_what_a_gem_root_carries_beside_its_code(self):
        """A directory named test, spec, doc or ri inside a gem's lib is code that is loaded."""
        text = (SOURCE / "scripts/download-ruby.sh").read_text()
        begin = text.index("# --- Step 5: Strip unnecessary files ---")
        phase = text[begin:text.index("# --- Step 5b:", begin)]
        gems = "pack/usr/lib/ruby/gems/4.0.0"
        kept = (
            f"{gems}/gems/test-unit-3.7.5/lib/test/unit.rb",
            f"{gems}/gems/rdoc-7.0.4/lib/rdoc/ri/driver.rb",
            f"{gems}/gems/rbs-3.10.0/lib/rbs/test/setup.rb",
            f"{gems}/gems/rbs-3.10.0/stdlib/minitest/0/minitest/spec/spec.rbs",
            f"{gems}/specifications/rake-13.3.1.gemspec",
            "pack/usr/lib/ruby/4.0.0/bundler/templates/newgem/test/minitest/test_newgem.rb.tt",
            "pack/usr/lib/ruby/4.0.0/bundler/templates/newgem/spec/newgem_spec.rb.tt",
            "pack/usr/lib/ruby/4.0.0/rdoc.rb",
        )
        removed = (
            f"{gems}/gems/test-unit-3.7.5/doc/text/news.md",
            f"{gems}/gems/minitest-6.0.0/test/minitest/test_minitest_test.rb",
            f"{gems}/gems/example-1.0/tests/case.rb",
            f"{gems}/gems/example-1.0/spec/example_spec.rb",
            f"{gems}/gems/rdoc-7.0.4/man/ri.1",
            f"{gems}/cache/rake-13.3.1.gem",
            f"{gems}/doc/rake-13.3.1/ri/cache.ri",
            "pack/usr/share/man/man1/ruby.1",
        )
        for path in kept + removed:
            write(self.root / path, "content\n")
        result = run(["/bin/bash", "-c", 'set -euo pipefail\nPACK_ASSETS="$PWD/pack"\n' + phase], self.root)
        self.assertEqual(0, result["status"], result)
        self.assertEqual([], [p for p in kept if not (self.root / p).exists()], result)
        self.assertEqual([], [p for p in removed if (self.root / p).exists()], result)

    def test_native_build_scripts_ignore_generic_directory_names_in_the_environment(self):
        """OUT_DIR and WORK_DIR are names a shell may hold for anything; a build that deletes
        under WORK_DIR/src must not take that directory from whatever the shell exported."""
        ndk = self.root / "ndk"
        for host in ("darwin-x86_64", "linux-x86_64"):
            for compiler in ("aarch64-linux-android29-clang", "aarch64-linux-android30-clang",
                             "aarch64-linux-android30-clang++"):
                write(ndk / "toolchains/llvm/prebuilt" / host / "bin" / compiler,
                      '#!/bin/sh\nwhile [ $# -gt 0 ]; do [ "$1" = -o ] && { mkdir -p "$(dirname "$2")"; '
                      'echo built > "$2"; }; shift; done\nexit 1\n', executable=True)
        write(self.root / "bin/curl", "#!/bin/sh\nexit 22\n", executable=True)
        # build-native-addons.sh asks for CMake before it makes its work directory.
        write(self.root / "bin/cmake", "#!/bin/sh\nexit 1\n", executable=True)
        # The names of the script's own, the directory it makes by default, and its default work directory when
        # that is another one. build-native-addons.sh has no output directory to move: the one it makes before
        # the fixture's curl fails is its work directory.
        cases = {
            "build-termux-exec.sh": (("TERMUX_EXEC_OUT_DIR",), "android/app/src/main/assets/usr/lib",
                                     "toolchains/build/termux-exec"),
            "build-exec-trampoline.sh": (("TRAMPOLINE_OUT_DIR",), "android/app/src/main/jniLibs/arm64-v8a", None),
            "build-claude-shim.sh": (("CLAUDE_SHIM_OUT_DIR",), "android/app/src/main/jniLibs/arm64-v8a", None),
            "build-selfexe-shim.sh": (("SELFEXE_OUT_DIR",), "android/app/src/main/assets/usr/lib", None),
            "build-move-noreplace.sh": (("MOVE_NOREPLACE_OUT_DIR",), "android/app/src/main/jniLibs/arm64-v8a", None),
            "build-glibc-shim.sh": (("GLIBC_SHIM_OUT_DIR", "GLIBC_SHIM_WORK_DIR"), "android/app/src/main/assets/usr/lib",
                                    ".build/glibc-shim"),
            "build-native-addons.sh": (("NATIVE_ADDONS_WORK_DIR",), ".build/native-addons", None),
        }
        for name, (own_names, default_out, default_work) in cases.items():
            with self.subTest(script=name):
                repo = self.root / name
                (repo / "scripts").mkdir(parents=True)
                shutil.copy(SOURCE / "scripts" / name, repo / "scripts" / name)
                write(repo / "scripts/termux-exec.patch", "")
                victim = self.root / (name + "-elsewhere")
                env = {"PATH": f"{self.root / 'bin'}:/usr/bin:/bin", "ANDROID_NDK_HOME": str(ndk),
                       "OUT_DIR": str(victim / "out"), "WORK_DIR": str(victim / "work")}
                result = run(["/bin/bash", str(repo / "scripts" / name)], repo, env)
                self.assertNotEqual(0, result["status"], result)
                self.assertFalse(victim.exists(), result)
                self.assertTrue((repo / default_out).is_dir(), result)
                if default_work:
                    self.assertTrue((repo / default_work).is_dir(), result)
                # The script's own names still move the output, for a build into another tree.
                for own_name in own_names:
                    own = self.root / (name + "-" + own_name)
                    result = run(["/bin/bash", str(repo / "scripts" / name)], repo,
                                 {**env, own_name: str(own / "lib")})
                    self.assertTrue((own / "lib").is_dir(), result)
                    self.assertFalse(victim.exists(), result)

    def test_a_skipped_toolchain_leaves_no_zip_and_the_summary_says_so(self):
        """A ZIP no gate of this run looked at is not left to be attached to a release."""
        script = self.root / "scripts/package-toolchains.sh"
        script.parent.mkdir(parents=True)
        shutil.copy(SOURCE / "scripts/package-toolchains.sh", script)
        write(self.root / "android/app/src/main/kotlin/com/vscodroid/setup/ToolchainRegistry.kt",
              'ToolchainInfo(\n    packName = "toolchain_ruby",\n    estimatedSize = 1_000_000,\n'
              '    downloadSize = 1_000_000,\n)\nToolchainInfo(\n    packName = "toolchain_java",\n'
              '    estimatedSize = 1_000_000,\n    downloadSize = 1_000_000,\n)\n')
        assets = self.root / "android/toolchain_ruby/src/main/assets"
        write(assets / "toolchain_ruby.json", '{"version": "1"}')
        write(assets / "usr/lib/blob", "ruby\n")
        stale = self.root / "toolchain-zips/toolchain_java.zip"
        write(stale, "from an earlier run")
        result = run(["bash", str(script)], self.root)
        self.assertEqual(0, result["status"], result)
        self.assertIn("Skipped: 1", result["stdout"])
        self.assertTrue((self.root / "toolchain-zips/toolchain_ruby.zip").exists(), result)
        self.assertFalse(stale.exists(), result)
        self.assertNotIn("left as it was", result["stdout"], result)
        self.assertIn("no ZIP", result["stdout"], result)

    def test_setup_refuses_a_missing_tool_the_build_runs(self):
        """Each of these is called by a step build-all.sh runs after setup; a missing one
        otherwise stops the build minutes in, at the step that needs it."""
        script = self.root / "scripts/setup.sh"
        script.parent.mkdir(parents=True)
        shutil.copy(SOURCE / "scripts/setup.sh", script)
        required = ("node", "git", "python3", "curl", "bsdtar", "gpg", "openssl", "unzip", "patch")
        for missing in (None, *required[3:]):
            with self.subTest(missing=missing):
                tools = self.root / f"tools-{missing}"
                for name in (*required, "cmake"):
                    if name != missing:
                        write(tools / name, "#!/bin/sh\necho v24.0.0\n", executable=True)
                for name in ("dirname", "sed", "cut", "mkdir"):
                    os.symlink(shutil.which(name), tools / name)
                result = run(["/bin/bash", str(script)], self.root,
                             {"PATH": str(tools), "HOME": str(self.root), "ANDROID_NDK_HOME": str(self.root)})
                if missing is None:
                    self.assertEqual(0, result["status"], result)
                else:
                    self.assertEqual(1, result["status"], result)
                    self.assertIn(f"{missing} is required", result["stdout"] + result["stderr"])

    def test_termux_extraction_names_a_missing_bsdtar_and_takes_the_member_it_lists(self):
        library = SOURCE / "scripts/lib/termux-packages.sh"
        payload = io.BytesIO()
        with tarfile.open(fileobj=payload, mode="w:gz") as data:
            info = tarfile.TarInfo("./data/data/com.termux/files/usr/bin/tool")
            info.size = 5
            data.addfile(info, io.BytesIO(b"tool\n"))
        archive = b"!<arch>\n"
        for name, body in ((b"debian-binary", b"2.0\n"), (b"data.tar.gz", payload.getvalue())):
            archive += b"%-16s%-12d%-6d%-6d%-8s%-10d`\n" % (name, 0, 0, 0, b"100644", len(body))
            archive += body + (b"\n" if len(body) % 2 else b"")
        script = '''set -euo pipefail
WORK_DIR="$PWD"
. "$1"
termux_pkg_filename() { printf pool/fixture.deb; }
termux_extract_packages fixture
'''
        tools = self.root / "tools"
        tools.mkdir()
        for name in ("dirname", "basename", "rm", "mkdir", "tar", "ls", "gzip"):
            if shutil.which(name):
                os.symlink(shutil.which(name), tools / name)
        bsdtar = shutil.which("bsdtar")
        for label, path in (("no bsdtar", str(tools)),
                            ("bsdtar", f"{tools}:{os.path.dirname(bsdtar)}" if bsdtar else None)):
            if path is None:
                continue
            with self.subTest(label=label):
                case = self.root / label.replace(" ", "-")
                (case / "debs").mkdir(parents=True)
                (case / "debs/fixture.deb").write_bytes(archive)
                result = run(["/bin/bash", "-c", script, "bash", str(library)], case, {"PATH": path})
                extracted = case / "extracted/fixture/data/data/com.termux/files/usr/bin/tool"
                if label == "no bsdtar":
                    self.assertNotEqual(0, result["status"], result)
                    self.assertIn("bsdtar", result["stderr"], result)
                    self.assertFalse(extracted.exists(), result)
                else:
                    self.assertEqual(0, result["status"], result)
                    self.assertEqual(b"tool\n", extracted.read_bytes(), result)


if __name__ == "__main__":
    unittest.main()
