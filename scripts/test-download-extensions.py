#!/usr/bin/env python3
"""Exercise extension extraction recovery without registry access or downloads.

    python3 scripts/test-download-extensions.py
"""

import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parent.parent


class ExtensionExtractionTest(unittest.TestCase):
    def test_interrupted_copy_cannot_reuse_an_old_verification_stamp(self):
        with tempfile.TemporaryDirectory(prefix="vscodroid-extensions-") as temporary:
            root = Path(temporary)
            scripts = root / "scripts"
            scripts.mkdir()
            downloader = scripts / "download-extensions.sh"
            shutil.copyfile(ROOT / "scripts/download-extensions.sh", downloader)
            shutil.copyfile(ROOT / "scripts/check-extension.py", scripts / "check-extension.py")
            # These downstream gates do not participate in extraction recovery.
            for helper in ("patch-python-platform.py", "check-bundled-extensions.py"):
                (scripts / helper).write_text("raise SystemExit(0)\n")
            shutil.copyfile(ROOT / "VSCODE_VERSION", root / "VSCODE_VERSION")

            pins = re.findall(
                r'^    "([^"@]+)@([^"#]+)#([0-9a-f]{64})"',
                downloader.read_text(), re.MULTILINE,
            )
            self.assertTrue(pins, "the fixture must exercise the configured extension pins")
            work = root / "toolchains/extensions"
            work.mkdir(parents=True)
            assets = root / "android/app/src/main/assets/extensions"
            assets.mkdir(parents=True)
            payload = "exports.activate = function () {};\n"
            manifest = json.dumps({"main": "dist/extension.js", "engines": {"vscode": "^1.0.0"}})
            for identifier, version, digest in pins:
                name = f"{identifier}-{version}"
                (work / f"{name}.json").write_text(json.dumps({
                    "version": version, "preRelease": False,
                    "files": {"download": "https://invalid.example/fixture.vsix"},
                }))
                (work / f"{name}.sha256").write_text(digest)
                with zipfile.ZipFile(work / f"{name}.vsix", "w") as archive:
                    archive.writestr("extension/package.json", manifest)
                    archive.writestr("extension/dist/extension.js", payload)

            identifier, version, digest = pins[0]
            name = f"{identifier}-{version}"
            destination = assets / name
            destination.mkdir()
            stamp = work / f"{name}.verified"
            stamp.write_text(digest + "\n")
            fail_once = root / "fail-copy-once"
            fail_once.touch()

            commands = root / "bin"
            commands.mkdir()

            def command(name, body):
                path = commands / name
                path.write_text("#!/usr/bin/env python3\n" + body)
                path.chmod(0o755)

            command("curl", "raise SystemExit('network access is forbidden in this test')\n")
            command("sha256sum", """import pathlib, sys
path = pathlib.Path(sys.argv[1])
print(path.with_suffix('.sha256').read_text().strip() + '  ' + str(path))
""")
            command("unzip", """import sys, zipfile
with zipfile.ZipFile(sys.argv[3]) as archive:
    archive.extractall(sys.argv[sys.argv.index('-d') + 1])
""")
            command("cp", """import os, pathlib, shutil, sys
source, destination = map(pathlib.Path, sys.argv[-2:])
fail_once = pathlib.Path(os.environ['FAIL_COPY_ONCE'])
if fail_once.exists():
    fail_once.unlink()
    shutil.copyfile(source / 'package.json', destination / 'package.json')
    raise SystemExit(1)
shutil.copytree(source, destination, dirs_exist_ok=True)
""")
            environment = dict(os.environ, PATH=f"{commands}{os.pathsep}{os.environ['PATH']}",
                               FAIL_COPY_ONCE=str(fail_once))

            def run():
                return subprocess.run(
                    ["bash", str(downloader)], cwd=root, env=environment,
                    capture_output=True, text=True, timeout=30,
                )

            interrupted = run()
            self.assertNotEqual(interrupted.returncode, 0, interrupted.stdout + interrupted.stderr)
            self.assertTrue((destination / "package.json").is_file(), "copy did not reach the manifest")
            self.assertFalse((destination / "dist/extension.js").exists(), "copy did not stop early")
            stamped_after_failure = stamp.exists()

            retry = run()
            self.assertEqual(retry.returncode, 0, retry.stdout + retry.stderr)
            activation = destination / "dist/extension.js"
            self.assertTrue(activation.is_file(), "retry trusted a partial extension tree")
            self.assertEqual(activation.read_text(), payload)
            self.assertFalse(stamped_after_failure, "failed extraction kept an old verification stamp")
            self.assertEqual(stamp.read_text().strip(), digest)


if __name__ == "__main__":
    unittest.main()
