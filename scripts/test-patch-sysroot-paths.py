#!/usr/bin/env python3
"""Exercise patch-sysroot-paths.py against header text shaped like Termux's.

    python3 scripts/test-patch-sysroot-paths.py

The fixtures are built from the script's own search blocks, so they hold the
bytes Termux ships. The cases are the ones that decide whether a header leaves
the build patched: the rewrite itself, a second run over its own output, and the
refusals when the header is not what the script was written against.
"""

import importlib.util
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parent.parent
SCRIPT = ROOT / "scripts/patch-sysroot-paths.py"
spec = importlib.util.spec_from_file_location("patch_sysroot_paths", SCRIPT)
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)

STDIO = (
    "int open(const char*, int, ...);\n"
    + mod.GETENV_OLD
    + mod.TMPFILE_OLD
    + "\t\tint __termux_fd = open(__termux_path, O_RDWR);\n}\n"
)
PWD = "#include <sys/types.h>\n" + mod.PWD_OLD + "       pw->pw_passwd = \"*\";\n}\n"


def run(include: Path) -> subprocess.CompletedProcess:
    return subprocess.run(
        [sys.executable, "-I", str(SCRIPT), str(include)], capture_output=True, text=True,
    )


class PatchSysrootPaths(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.inc = Path(self.tmp.name)
        (self.inc / "stdio.h").write_text(STDIO)
        (self.inc / "pwd.h").write_text(PWD)

    def tearDown(self):
        self.tmp.cleanup()

    def test_tmpfile_reads_tmpdir_and_pwd_reads_home_and_shell(self):
        result = run(self.inc)

        self.assertEqual(result.returncode, 0, result.stderr)
        stdio = (self.inc / "stdio.h").read_text()
        pwd = (self.inc / "pwd.h").read_text()
        self.assertIn('getenv("TMPDIR")', stdio)
        self.assertIn('"%s/tmpfile.%d-%u"', stdio)
        self.assertNotIn("com.termux", stdio)
        self.assertIn('getenv("HOME")', pwd)
        self.assertIn('getenv("SHELL")', pwd)
        self.assertNotIn("com.termux", pwd)
        # What follows the replaced block is untouched.
        self.assertIn('pw->pw_passwd = "*";', pwd)
        self.assertIn("open(__termux_path, O_RDWR)", stdio)

    def test_a_second_run_changes_nothing(self):
        run(self.inc)
        before = ((self.inc / "stdio.h").read_text(), (self.inc / "pwd.h").read_text())

        result = run(self.inc)

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("already patched", result.stdout)
        self.assertEqual(before, ((self.inc / "stdio.h").read_text(), (self.inc / "pwd.h").read_text()))

    def test_a_header_that_changed_stops_the_build(self):
        (self.inc / "pwd.h").write_text(PWD.replace("com.termux/files/home", "com.termux/files/h0me"))

        result = run(self.inc)

        self.assertNotEqual(result.returncode, 0, "an unmatched header was accepted")
        self.assertIn("exactly one occurrence", result.stderr)

    def test_a_block_that_appears_twice_stops_the_build(self):
        (self.inc / "stdio.h").write_text(STDIO + STDIO)

        result = run(self.inc)

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("found 2", result.stderr)

    def test_a_missing_header_stops_the_build(self):
        (self.inc / "pwd.h").unlink()

        result = run(self.inc)

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("pwd.h is missing", result.stderr)


if __name__ == "__main__":
    unittest.main()
