#!/usr/bin/env python3
"""Exercise check-server-published.py against throwaway repositories.

    python3 scripts/test-check-server-published.py

The real repository is in one state at a time, so it cannot show each refusal in turn.
Each repository here holds a copy of the real codeoss-build.yml, so the cache key the script
reads is the one that ships, and the script runs from inside the repository it judges, as it
does in release.yml. git is told to read none of the machine's configuration.
"""

import importlib.util
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parent.parent
SCRIPT = ROOT / "scripts/check-server-published.py"
WORKFLOW = ".github/workflows/codeoss-build.yml"
SCRIPT_IN_REPO = "scripts/check-server-published.py"
VERSION = "1.2.3"
TAG = f"server-{VERSION}"

ENV = dict(os.environ, GIT_CONFIG_GLOBAL=os.devnull, GIT_CONFIG_NOSYSTEM="1")
IDENTITY = ["-c", "user.name=fixture", "-c", "user.email=fixture@example.invalid",
            "-c", "commit.gpgsign=false", "-c", "tag.gpgsign=false"]

spec = importlib.util.spec_from_file_location("check_server_published", SCRIPT)
checker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checker)
INPUTS = checker.server_inputs((ROOT / WORKFLOW).read_text())


def file_for(pattern):
    """A file the pattern matches, one directory down where it ends in **."""
    return pattern.replace("**", "nested/x.txt")


class Repo:
    def __init__(self, test):
        scratch = tempfile.TemporaryDirectory(prefix="vscodroid-server-published-")
        test.addCleanup(scratch.cleanup)
        self.root = Path(scratch.name) / "repo"
        self.root.mkdir()
        self.pending = []
        self.git("init", "-q")
        self.write(SCRIPT_IN_REPO, SCRIPT.read_text())
        self.write(WORKFLOW, (ROOT / WORKFLOW).read_text())
        self.write("VSCODE_VERSION", VERSION + "\n")
        self.write("VSCODE_COMMIT", "0" * 40 + "\n")
        self.write("patches/0001-a.patch", "base\n")
        self.write("README.md", "base\n")
        for pattern in INPUTS:
            if not (self.root / file_for(pattern)).exists():
                self.write(file_for(pattern), "base\n")
        self.commit("base")

    def git(self, *args, cwd=None):
        return subprocess.run(["git", *IDENTITY, *args], cwd=cwd or self.root, env=ENV,
                              capture_output=True, text=True, check=True)

    def write(self, name, text):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)
        self.pending.append(name)

    def change(self, name):
        self.write(name, (self.root / name).read_text() + "changed\n")

    def commit(self, message):
        self.git("add", "--", *self.pending)
        self.git("commit", "-q", "-m", message)
        self.pending = []

    def run(self, cwd=None):
        cwd = cwd or self.root
        return subprocess.run([sys.executable, str(cwd / SCRIPT_IN_REPO)], cwd=cwd, env=ENV,
                              capture_output=True, text=True)


class ServerPublishedTest(unittest.TestCase):
    def published(self):
        repo = Repo(self)
        repo.git("tag", TAG)
        return repo

    def assert_refused(self, result, *wanted):
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        for text in wanted:
            self.assertIn(text, result.stdout)

    def test_a_key_that_cannot_be_read_is_refused(self):
        for text in ("no cache key here",
                     "hashFiles('VSCODE_VERSION') hashFiles('VSCODE_COMMIT')",
                     "hashFiles('VSCODE_VERSION', 'VSCODE_COMMIT')"):
            with self.subTest(text=text), self.assertRaises(ValueError):
                checker.server_inputs(text)

    def test_nothing_changed_passes(self):
        result = self.published().run()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn(TAG, result.stdout)

    def test_a_change_to_an_unrelated_file_passes(self):
        repo = self.published()
        repo.change("README.md")
        repo.write("scripts/download-python.sh", "new\n")
        repo.commit("unrelated")
        result = repo.run()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_a_change_to_the_build_script_is_refused(self):
        repo = self.published()
        repo.change("scripts/build-vscode-oss.sh")
        repo.commit("build script")
        self.assert_refused(repo.run(), TAG, "scripts/build-vscode-oss.sh",
                            'Dispatch the "Build Code - OSS server" workflow')

    def test_a_change_to_a_patch_is_refused(self):
        repo = self.published()
        repo.change("patches/0001-a.patch")
        repo.commit("patch")
        self.assert_refused(repo.run(), "patches/0001-a.patch")

    def test_an_added_patch_is_refused(self):
        repo = self.published()
        repo.write("patches/0002-b.patch", "new\n")
        repo.commit("new patch")
        self.assert_refused(repo.run(), "patches/0002-b.patch")

    def test_a_removed_patch_is_refused(self):
        repo = self.published()
        repo.git("rm", "-q", "--", "patches/0001-a.patch")
        repo.git("commit", "-q", "-m", "drop the patch")
        self.assert_refused(repo.run(), "patches/0001-a.patch")

    def test_every_input_in_the_cache_key_is_compared(self):
        # The version file is the exception: appending to it makes it unreadable as a
        # version, which would be refused for that reason and not for being compared.
        for pattern in INPUTS:
            if pattern == "VSCODE_VERSION":
                continue
            with self.subTest(pattern=pattern):
                repo = self.published()
                repo.change(file_for(pattern))
                repo.commit("change")
                self.assert_refused(repo.run(), file_for(pattern))

    def test_the_inputs_are_whatever_the_cache_key_names(self):
        repo = Repo(self)
        key = (repo.root / WORKFLOW).read_text()
        longer = key.replace("hashFiles('VSCODE_VERSION',", "hashFiles('extra/input.txt', 'VSCODE_VERSION',", 1)
        self.assertNotEqual(longer, key, "the key no longer opens with VSCODE_VERSION; update this test")
        repo.write(WORKFLOW, longer)
        repo.write("extra/input.txt", "base\n")
        repo.commit("the key names one more file")
        repo.git("tag", TAG)
        repo.change("extra/input.txt")
        repo.commit("change it")
        self.assert_refused(repo.run(), "extra/input.txt")

    def test_every_script_the_build_runs_is_an_input(self):
        build = (ROOT / "scripts/build-vscode-oss.sh").read_text()
        ran = {f"scripts/{name}" for name in re.findall(r"\$SCRIPT_DIR/([\w.-]+\.py)", build)}
        self.assertTrue(ran, "build-vscode-oss.sh runs no script by that pattern; update this test")
        self.assertEqual(ran - set(INPUTS), set(), "run by the build, absent from its cache key")

    def test_language_detection_inputs_cannot_be_removed(self):
        workflow = (ROOT / WORKFLOW).read_text()
        for name in ("scripts/patch-language-detection.py", "scripts/test-language-detection.py"):
            with self.subTest(name=name):
                self.assertIn(name, INPUTS)
                without = workflow.replace(f"'{name}', ", "")
                self.assertNotEqual(without, workflow)
                with self.assertRaisesRegex(ValueError, name):
                    checker.server_inputs(without)

    def test_language_detection_inputs_invalidate_each_server_cache(self):
        keys = []
        for workflow in ("build.yml", "release.yml", "codeoss-build.yml"):
            source = (ROOT / ".github/workflows" / workflow).read_text()
            for line in source.splitlines():
                if re.match(r"\s+key: (downloads|assets|codeoss)-", line) and "hashFiles(" in line:
                    keys.append((workflow, line))
        self.assertEqual(len(keys), 4)
        for workflow, key in keys:
            for name in ("scripts/patch-language-detection.py", "scripts/test-language-detection.py"):
                with self.subTest(workflow=workflow, name=name):
                    self.assertIn(f"'{name}'", key)

    def test_a_tag_built_at_another_pin_is_refused(self):
        repo = Repo(self)
        repo.write("VSCODE_VERSION", "1.2.2\n")
        repo.commit("pinned to the version before")
        repo.git("tag", TAG)
        repo.write("VSCODE_VERSION", VERSION + "\n")
        repo.commit("bumped")
        self.assert_refused(repo.run(), TAG, "VSCODE_VERSION")

    def test_a_tag_off_the_history_of_head_is_compared_by_content(self):
        repo = Repo(self)
        repo.git("checkout", "-q", "-b", "publish")
        repo.change("README.md")
        repo.commit("on the branch that published")
        repo.git("tag", TAG)
        repo.git("checkout", "-q", "-")
        result = repo.run()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_an_annotated_tag_is_followed_to_its_commit(self):
        repo = Repo(self)
        repo.git("tag", "-a", "-m", "published", TAG)
        result = repo.run()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_a_missing_tag_is_refused_with_both_remedies(self):
        self.assert_refused(Repo(self).run(), TAG, 'Dispatch the "Build Code - OSS server" workflow',
                            "fetch-depth: 0")

    def test_a_clone_that_did_not_fetch_the_tag_is_refused(self):
        repo = self.published()
        repo.change("README.md")
        repo.commit("after the tag")
        clone = repo.root.parent / "clone"
        repo.git("clone", "-q", "--depth", "1", repo.root.as_uri(), str(clone))
        self.assert_refused(repo.run(cwd=clone), TAG, "fetch-depth: 0")

    def test_a_version_that_is_not_one_is_refused(self):
        repo = self.published()
        repo.write("VSCODE_VERSION", "1.2.3/../x\n")
        self.assert_refused(repo.run(), "not a version number")


if __name__ == "__main__":
    unittest.main()
