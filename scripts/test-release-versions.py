#!/usr/bin/env python3
"""Run the release workflow's actual version extraction against Gradle fixtures."""

import os
from pathlib import Path
import re
import subprocess
import tempfile
import unittest

SOURCE = (Path(__file__).resolve().parent.parent / ".github/workflows/release.yml").read_text()
LINES = [line.strip() for line in SOURCE.splitlines()
         if line.strip().startswith(("version_name=$(", "version_code=$("))]


class ReleaseVersionsTest(unittest.TestCase):
    def parse(self, text):
        self.assertEqual(2, len(LINES))
        with tempfile.NamedTemporaryFile(mode="w") as file:
            file.write(text)
            file.flush()
            result = subprocess.run(
                ["bash", "-c", "\n".join(LINES) + '\nprintf "%s\\n%s\\n" "$version_name" "$version_code"'],
                env={**os.environ, "gradle_file": file.name}, capture_output=True, text=True, check=True,
            )
        return result.stdout.splitlines()

    def test_literals_ignore_comment_quotes_and_digits(self):
        self.assertEqual(["1.5.0", "16"], self.parse(
            '// versionCode = 999\n  versionName = "1.5.0" // "example"\n  versionCode = 16 // v1.5.0\n',
        ))

    def test_numeric_separators_are_refused_instead_of_truncated(self):
        self.assertEqual(["1.5.0", ""], self.parse('versionName = "1.5.0"\nversionCode = 16_001\n'))

    def test_expressions_are_refused_instead_of_reading_a_prefix(self):
        self.assertEqual(["", ""], self.parse('versionName = "1.5.0" + "-test"\nversionCode = 16 + 1\n'))

    def test_previous_release_uses_the_same_code_parser(self):
        parsers = re.findall(r"sed -n -E '([^']*versionCode[^']*)'", SOURCE)
        self.assertEqual(2, len(parsers))
        self.assertEqual(parsers[0], parsers[1])

    def gate(self, previous_code):
        start = SOURCE.index("          version_name=$(")
        end = SOURCE.index("          # The published release notes", start)
        program = "set -euo pipefail\ngradle_file=android/app/build.gradle.kts\n" + "\n".join(
            line[10:] for line in SOURCE[start:end].splitlines()
        )
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            def git(*args):
                subprocess.run(["git", "-C", directory, *args], capture_output=True, check=True)
            git("init", "-q")
            git("config", "user.name", "Fixture")
            git("config", "user.email", "fixture@example.invalid")
            file = root / "android/app/build.gradle.kts"
            file.parent.mkdir(parents=True)
            for version, code in [("1.5.0", previous_code), ("1.5.1", "17")]:
                file.write_text(f'versionName = "{version}"\nversionCode = {code}\n')
                git("add", "--", "android/app/build.gradle.kts")
                git("commit", "-qm", "Set release version")
                git("tag", f"v{version}")
            result = subprocess.run(
                ["bash", "-c", program], cwd=root, env={**os.environ, "TAG": "v1.5.1"},
                capture_output=True, text=True,
            )
        return result

    def test_unreadable_previous_code_refuses_the_release(self):
        allowed = self.gate("16")
        self.assertEqual(0, allowed.returncode, allowed.stdout + allowed.stderr)
        refused = self.gate("16_000")
        self.assertNotEqual(0, refused.returncode, refused.stdout + refused.stderr)


if __name__ == "__main__":
    unittest.main()
