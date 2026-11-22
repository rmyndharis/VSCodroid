#!/usr/bin/env python3
"""Compile the real table parser with a host loader and inspect its argument vector."""

import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

SOURCE = Path(__file__).with_name("exec-trampoline.c")


class ExecTrampolineTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory()
        cls.addClassCleanup(cls.temp.cleanup)
        cls.root = Path(cls.temp.name)
        source = SOURCE.read_text()
        declaration = 'static const char *kLoader = "/system/bin/linker64";'
        assert source.count(declaration) == 1
        source = source.replace(declaration, f"static const char *kLoader = {json.dumps(sys.executable)};")
        c_file = cls.root / "trampoline.c"
        c_file.write_text(source)
        cls.command = cls.root / "java"
        subprocess.run(["cc", "-Wall", "-Wextra", "-Werror", str(c_file), "-o", str(cls.command)], check=True)
        cls.target = cls.root / "dump.py"
        cls.target.write_text("import json,sys; print(json.dumps(sys.argv[1:]))\n")
        cls.environment = cls.root / "environment.py"
        cls.environment.write_text("import json,os; print(json.dumps({k: os.environ.get(k) "
                                   "for k in ('TRAMPOLINE_FIXTURE', 'TRAMPOLINE_LATE')}))\n")

    def invoke(self, row_args, caller_args=()):
        table = self.root / "table.tsv"
        table.write_text("\tTRAMPOLINE_FIXTURE\tkept\n" + "\t".join(["java", str(self.target), *row_args]) + "\n")
        return subprocess.run(
            [str(self.command), *caller_args],
            env={**os.environ, "VSCODROID_EXEC_TABLE": str(table)},
            capture_output=True, text=True,
        )

    def test_options_precede_caller_arguments_without_splitting(self):
        result = self.invoke(["-XX:UseSVE=0", "-Duser.home=/home/a b", "-Djava.io.tmpdir=/tmp/app"], ["file name.java", "x"])
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(
            ["-XX:UseSVE=0", "-Duser.home=/home/a b", "-Djava.io.tmpdir=/tmp/app", "file name.java", "x"],
            json.loads(result.stdout),
        )

    def test_target_only_and_legacy_script_rows_still_work(self):
        for row in [[], ["script path.sh"]]:
            result = self.invoke(row, ["caller"])
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual(row + ["caller"], json.loads(result.stdout))

    def test_eight_arguments_are_kept_including_an_empty_one(self):
        args = ["", "2", "3", "4", "5", "6", "7", "8"]
        result = self.invoke(args)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(args, json.loads(result.stdout))

    def run_table(self, table, caller_env=None, caller_args=()):
        """The trampoline as `java` with exactly the environment given: the
        table is wherever caller_env says, or nowhere."""
        env = {"PATH": os.environ.get("PATH", "/usr/bin:/bin"), **(caller_env or {})}
        if isinstance(table, str):
            table = table.encode()
        path = self.root / "table.tsv"
        path.write_bytes(table)
        env.setdefault("VSCODROID_EXEC_TABLE", str(path))
        return subprocess.run([str(self.command), *caller_args], env=env, capture_output=True)

    def test_an_environment_row_supplies_what_the_caller_lacks_wherever_it_sits(self):
        result = self.run_table(f"\tTRAMPOLINE_FIXTURE\tkept\twith a tab\njava\t{self.environment}\n"
                                "\tTRAMPOLINE_LATE\tafter the command\n")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual({"TRAMPOLINE_FIXTURE": "kept\twith a tab", "TRAMPOLINE_LATE": "after the command"},
                         json.loads(result.stdout))

    def test_an_environment_row_never_replaces_the_callers_value(self):
        result = self.run_table(f"\tTRAMPOLINE_FIXTURE\tkept\njava\t{self.environment}\n",
                                {"TRAMPOLINE_FIXTURE": "the caller's"})
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("the caller's", json.loads(result.stdout)["TRAMPOLINE_FIXTURE"])

    def test_the_table_is_found_from_prefix_when_its_variable_is_gone(self):
        home = self.root / "prefix-case/home/.vscodroid"
        home.mkdir(parents=True)
        (self.root / "prefix-case/usr").mkdir()
        (home / "toolchain-exec.tsv").write_text(f"java\t{self.target}\n")
        result = self.run_table("", {"VSCODROID_EXEC_TABLE": "", "PREFIX": str(self.root / "prefix-case/usr")},
                                ["from prefix"])
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(["from prefix"], json.loads(result.stdout))
        result = self.run_table("", {"VSCODROID_EXEC_TABLE": ""})
        self.assertEqual(127, result.returncode)
        self.assertIn(b"no toolchain table in the environment", result.stderr)

    def test_a_relative_target_is_refused(self):
        result = self.run_table("java\tdump.py\n")
        self.assertEqual(127, result.returncode)
        self.assertIn(b"not an absolute path", result.stderr)
        self.assertEqual(b"", result.stdout)

    def test_a_record_carrying_a_nul_is_skipped_wherever_it_ends(self):
        # The path before the NUL is absolute and real, so reading the record up
        # to the NUL would run it: the record has to be refused as damaged, also
        # when it is the last thing in the file and ends without a newline.
        for label, table in (("followed", f"java\t{self.target}\0junk\njava\t{self.environment}\n"),
                             ("last", f"java\t{self.target}\0junk")):
            with self.subTest(label=label):
                result = self.run_table(table)
                self.assertIn(b"skipping a damaged toolchain entry", result.stderr)
                if label == "followed":
                    self.assertEqual(0, result.returncode, result.stderr)
                    self.assertIn(b"TRAMPOLINE_FIXTURE", result.stdout)
                else:
                    self.assertEqual(127, result.returncode)
                    self.assertEqual(b"", result.stdout)

    def test_a_long_record_is_read_whole(self):
        # Longer than any buffer sized from PATH_MAX. Dropped, a row the app
        # wrote went missing with no word; cut, its tail would be read as a
        # record of its own.
        argument = "z" * 20000
        result = self.run_table(f"java\t{self.target}\t{argument}\n")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual([argument], json.loads(result.stdout))

    def test_a_damaged_long_record_leaves_no_tail_behind(self):
        # A damaged long record ends where its newline is: nothing after its
        # first NUL, however far along, is read as a command of its own.
        for limit in (1024, 4096):
            with self.subTest(path_max=limit):
                # Sized so that a buffer of 2 * PATH_MAX + 64 ends exactly
                # before "java", which is where a cut record would resume.
                padding = "x" * (2 * limit + 64 - 2)
                result = self.run_table(f"\0{padding}java\t{self.target}\n")
                self.assertEqual(127, result.returncode, result.stdout)
                self.assertEqual(b"", result.stdout)

    def test_ninth_argument_refuses_the_whole_row(self):
        result = self.invoke([str(n) for n in range(9)])
        self.assertEqual(127, result.returncode)
        self.assertIn("too many arguments", result.stderr)
        self.assertEqual("", result.stdout)


if __name__ == "__main__":
    unittest.main()
