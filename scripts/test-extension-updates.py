#!/usr/bin/env python3
"""Exercise extension version checks against real, temporary Git histories."""

import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

CHECK = Path(__file__).with_name("check-extension-updates.py")


class ExtensionUpdatesTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.git("init", "-q")
        self.git("config", "user.name", "Fixture")
        self.git("config", "user.email", "fixture@example.invalid")
        self.directory = self.root / "android/app/src/main/assets/extensions/vscodroid.example-1.0.0"
        self.directory.mkdir(parents=True)
        self.manifest = self.directory / "package.json"
        self.manifest.write_text('{"name":"example","version":"1.0.0","browser":"extension.js","enabled":false}')
        (self.directory / "extension.js").write_text("module.exports = {};\n")
        (self.directory / "package.nls.json").write_text('{"title":"Original","enabled":false}')
        self.git("add", "--", str(self.directory.relative_to(self.root)))
        self.git("commit", "-qm", "Add extension")
        self.check = self.root / "scripts/check-extension-updates.py"
        self.check.parent.mkdir()
        shutil.copyfile(CHECK, self.check)

    def git(self, *args):
        return subprocess.run(["git", "-C", str(self.root), *args], capture_output=True, check=True)

    def check_status(self):
        return subprocess.run([sys.executable, str(self.check)], capture_output=True, text=True)

    def test_semantic_manifest_edit_requires_new_directory(self):
        pkg = json.loads(self.manifest.read_text())
        pkg["displayName"] = "Changed"
        self.manifest.write_text(json.dumps(pkg))
        self.assertNotEqual(0, self.check_status().returncode)
        self.directory.rename(self.directory.with_name("vscodroid.example-1.0.1"))
        self.assertEqual(0, self.check_status().returncode)

    def test_formatting_does_not_require_version_bump(self):
        self.manifest.write_text(json.dumps(json.loads(self.manifest.read_text()), indent=4))
        self.assertEqual(0, self.check_status().returncode)

    def test_browser_entry_and_translations_require_new_directory(self):
        for name in ["extension.js", "package.nls.json"]:
            file = self.directory / name
            previous = file.read_text()
            file.write_text('{"title":"Changed"}' if name.endswith("json") else "module.exports = { changed: true };\n")
            self.assertNotEqual(0, self.check_status().returncode, name)
            file.write_text(previous)

    def test_commit_does_not_hide_unchanged_directory_edit(self):
        self.manifest.write_text('{"name":"example","version":"1.0.0","displayName":"Changed"}')
        self.git("add", "--", str(self.manifest.relative_to(self.root)))
        self.git("commit", "-qm", "Change title")
        self.assertNotEqual(0, self.check_status().returncode)

    def test_removing_translation_requires_new_directory(self):
        (self.directory / "package.nls.json").unlink()
        self.assertNotEqual(0, self.check_status().returncode)

    def test_json_boolean_to_number_requires_new_directory(self):
        for file in [self.manifest, self.directory / "package.nls.json"]:
            with self.subTest(file=file.name):
                original = file.read_text()
                file.write_text(original.replace("false", "0"))
                self.assertNotEqual(0, self.check_status().returncode)
                file.write_text(original)

    def test_readding_directory_keeps_its_first_content_baseline(self):
        files = {file.name: file.read_text() for file in self.directory.iterdir()}
        relative = str(self.directory.relative_to(self.root))
        self.git("rm", "-qr", "--", relative)
        self.git("commit", "-qm", "Remove extension")
        self.directory.mkdir(parents=True)
        for name, text in files.items():
            (self.directory / name).write_text(text)
        self.manifest.write_text(self.manifest.read_text().replace('"enabled":false', '"enabled":true'))
        self.git("add", "--", relative)
        self.git("commit", "-qm", "Restore extension")
        self.assertNotEqual(0, self.check_status().returncode)

    def test_shallow_clone_is_refused_instead_of_passed(self):
        # In a depth-1 clone the tip is the oldest commit git can see, so it reads as the
        # one that added the extension and an edit made since compares equal to itself.
        self.manifest.write_text('{"name":"example","version":"1.0.0","displayName":"Changed"}')
        self.git("add", "--", str(self.manifest.relative_to(self.root)))
        self.git("commit", "-qm", "Change title")
        self.assertNotEqual(0, self.check_status().returncode)
        clones = tempfile.TemporaryDirectory()
        self.addCleanup(clones.cleanup)
        clone = Path(clones.name) / "shallow"
        subprocess.run(
            ["git", "clone", "-q", "--depth", "1", self.root.as_uri(), str(clone)],
            capture_output=True, check=True,
        )
        (clone / "scripts").mkdir()
        shutil.copyfile(CHECK, clone / "scripts/check-extension-updates.py")
        result = subprocess.run(
            [sys.executable, str(clone / "scripts/check-extension-updates.py")],
            capture_output=True, text=True,
        )
        self.assertNotEqual(0, result.returncode)
        self.assertIn("shallow", result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
