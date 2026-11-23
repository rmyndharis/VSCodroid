#!/usr/bin/env python3
"""Exercise extension version checks against real, temporary Git histories."""

import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

CHECK = Path(__file__).with_name("check-extension-updates.py")
# git reads none of the machine's configuration, and no git variable a hook may have
# exported: a developer's commit.gpgsign or core.hooksPath otherwise fails or rewrites
# every fixture commit here, and the checker runs git too.
ENV = {**{k: v for k, v in os.environ.items() if not k.startswith("GIT_")},
       "GIT_CONFIG_GLOBAL": os.devnull, "GIT_CONFIG_NOSYSTEM": "1"}


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
        # Released, so every case below edits a directory a device may already hold.
        self.git("tag", "v1.0.0")
        self.check = self.root / "scripts/check-extension-updates.py"
        self.check.parent.mkdir()
        shutil.copyfile(CHECK, self.check)

    def git(self, *args):
        return subprocess.run(["git", "-C", str(self.root), *args], capture_output=True, check=True, env=ENV)

    def check_status(self):
        return subprocess.run([sys.executable, str(self.check)], capture_output=True, text=True, env=ENV)

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

    def test_adding_translation_requires_new_directory(self):
        # A language file the directory did not ship with is served stale like an edited one:
        # the server's extension scan is cached until the directory name changes.
        (self.directory / "package.nls.ja.json").write_text('{"title":"Japanese"}')
        result = self.check_status()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("package.nls.ja.json added without a directory version bump", result.stdout)
        self.directory.rename(self.directory.with_name("vscodroid.example-1.0.1"))
        self.assertEqual(0, self.check_status().returncode)

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

    def test_unreleased_directory_takes_further_commits(self):
        # A bumped directory no release carries yet is on no device a user holds, so a
        # second commit to it, such as a fix-up in the same pull request, needs no
        # second bump.
        bumped = self.directory.with_name("vscodroid.example-1.0.1")
        self.directory.rename(bumped)
        manifest = bumped / "package.json"
        manifest.write_text(manifest.read_text().replace('"1.0.0"', '"1.0.1"'))
        self.git("add", "--", str(self.directory.relative_to(self.root)), str(bumped.relative_to(self.root)))
        self.git("commit", "-qm", "Bump extension")
        (bumped / "package.nls.json").write_text('{"title":"Fixed","enabled":false}')
        self.git("add", "--", str(bumped.relative_to(self.root)))
        self.git("commit", "-qm", "Fix the title")
        result = self.check_status()
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        # Once a release carries it, the same edit needs a new directory again.
        self.git("tag", "v1.0.1")
        (bumped / "package.nls.json").write_text('{"title":"Again","enabled":false}')
        self.assertNotEqual(0, self.check_status().returncode)

    def test_released_content_is_the_baseline(self):
        # Changed in place after it was added but before any release carried it, then
        # released: what devices hold is the released content, so that is what an edit
        # is measured against, not the commit that first added the directory.
        directory = self.directory.with_name("vscodroid.later-1.0.0")
        directory.mkdir()
        nls = directory / "package.nls.json"
        (directory / "package.json").write_text('{"name":"later","version":"1.0.0"}')
        nls.write_text('{"title":"Draft"}')
        self.git("add", "--", str(directory.relative_to(self.root)))
        self.git("commit", "-qm", "Add another extension")
        nls.write_text('{"title":"Shipped"}')
        self.git("add", "--", str(directory.relative_to(self.root)))
        self.git("commit", "-qm", "Reword it")
        self.git("tag", "v1.1.0")
        result = self.check_status()
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        nls.write_text('{"title":"Changed after release"}')
        self.assertNotEqual(0, self.check_status().returncode)

    def test_no_release_tag_is_refused_instead_of_passed(self):
        # With no release tag in sight every directory reads as unreleased, so an
        # edit to one that shipped would pass. A clone made without tags is refused.
        self.git("tag", "-d", "v1.0.0")
        self.manifest.write_text('{"name":"example","version":"1.0.0","displayName":"Changed"}')
        result = self.check_status()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("release tag", result.stdout + result.stderr)

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
            capture_output=True, check=True, env=ENV,
        )
        (clone / "scripts").mkdir()
        shutil.copyfile(CHECK, clone / "scripts/check-extension-updates.py")
        result = subprocess.run(
            [sys.executable, str(clone / "scripts/check-extension-updates.py")],
            capture_output=True, text=True, env=ENV,
        )
        self.assertNotEqual(0, result.returncode)
        self.assertIn("shallow", result.stdout + result.stderr)

    def test_main_entry_requires_new_directory(self):
        node_ext = self.directory.with_name("vscodroid.node-ext-1.0.0")
        node_ext.mkdir()
        (node_ext / "package.json").write_text('{"name":"node-ext","version":"1.0.0","main":"./extension.js"}')
        entry = node_ext / "extension.js"
        entry.write_text("module.exports = { ok: true };\n")
        self.git("add", "--", str(node_ext.relative_to(self.root)))
        self.git("commit", "-qm", "Add node extension")
        self.git("tag", "v1.2.0")
        self.assertEqual(0, self.check_status().returncode)

        entry.write_text("module.exports = { ok: false };\n")
        result = self.check_status()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("extension.js changed without a directory version bump", result.stdout)

        node_ext.rename(node_ext.with_name("vscodroid.node-ext-1.0.1"))
        self.assertEqual(0, self.check_status().returncode)

    def test_l10n_bundle_changes_require_new_directory(self):
        # Adding l10n/bundle.l10n.<lang>.json to a released extension requires a directory bump.
        l10n_dir = self.directory / "l10n"
        l10n_dir.mkdir()
        added = l10n_dir / "bundle.l10n.de.json"
        added.write_text('{"Hello":"Hallo"}')
        result = self.check_status()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("l10n/bundle.l10n.de.json added without a directory version bump", result.stdout)
        added.unlink()
        l10n_dir.rmdir()
        self.assertEqual(0, self.check_status().returncode)

        # A released extension carrying l10n bundles must bump on semantic edits or removals,
        # while formatting-only edits remain allowed.
        localized = self.directory.with_name("vscodroid.localized-1.0.0")
        (localized / "l10n").mkdir(parents=True)
        (localized / "package.json").write_text(
            '{"name":"localized","version":"1.0.0","main":"./extension.js","l10n":"./l10n"}'
        )
        (localized / "extension.js").write_text("module.exports = {};\n")
        bundle = localized / "l10n/bundle.l10n.de.json"
        bundle.write_text('{"Hello":"Hallo"}')
        self.git("add", "--", str(localized.relative_to(self.root)))
        self.git("commit", "-qm", "Add localized extension")
        self.git("tag", "v1.3.0")
        self.assertEqual(0, self.check_status().returncode)

        # Formatting-only edit passes.
        bundle.write_text('{\n  "Hello": "Hallo"\n}\n')
        self.assertEqual(0, self.check_status().returncode)

        # Semantic edit fails.
        bundle.write_text('{"Hello":"Guten Tag"}')
        changed = self.check_status()
        self.assertNotEqual(0, changed.returncode)
        self.assertIn("l10n/bundle.l10n.de.json changed without a directory version bump", changed.stdout)

        # Removal fails.
        bundle.unlink()
        removed = self.check_status()
        self.assertNotEqual(0, removed.returncode)
        self.assertIn("l10n/bundle.l10n.de.json removed without a directory version bump", removed.stdout)

        # Bumping the directory version resolves it.
        localized.rename(localized.with_name("vscodroid.localized-1.0.1"))
        self.assertEqual(0, self.check_status().returncode)


if __name__ == "__main__":
    unittest.main()
