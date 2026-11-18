#!/usr/bin/env python3
"""Compile move-noreplace.c with the host compiler and run it against real directories.

The program makes one Linux system call, so the cases run on Linux only; elsewhere
they are skipped and the run says so.
"""

import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

SOURCE = Path(__file__).with_name("move-noreplace.c")


@unittest.skipUnless(sys.platform.startswith("linux"), "renameat2 is a Linux system call")
class MoveNoReplaceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory()
        cls.addClassCleanup(cls.temp.cleanup)
        cls.program = Path(cls.temp.name) / "move-noreplace"
        subprocess.run(["cc", "-Wall", "-Wextra", "-Werror", str(SOURCE), "-o", str(cls.program)], check=True)

    def setUp(self):
        self.directory = Path(tempfile.mkdtemp(dir=self.temp.name))

    def move(self, source, target, directory=None, identity=None):
        directory = directory or self.directory
        held = os.stat(identity or directory)
        return subprocess.run(
            [str(self.program), str(directory), str(held.st_dev), str(held.st_ino), source, target],
            capture_output=True, text=True,
        )

    def test_a_taken_name_is_refused_and_nothing_is_replaced(self):
        (self.directory / "copy").write_text("the copy")
        (self.directory / "taken").write_text("made meanwhile")
        result = self.move("copy", "taken")
        self.assertEqual(73, result.returncode, result.stderr)
        self.assertEqual("the copy", (self.directory / "copy").read_text())
        self.assertEqual("made meanwhile", (self.directory / "taken").read_text())

    def test_a_dangling_link_takes_its_name_too(self):
        (self.directory / "copy").write_text("the copy")
        (self.directory / "link").symlink_to("nowhere")
        self.assertEqual(73, self.move("copy", "link").returncode)
        self.assertTrue((self.directory / "link").is_symlink())

    def test_a_free_name_takes_the_entry(self):
        (self.directory / "copy").write_text("the copy")
        result = self.move("copy", "free name.txt")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertFalse((self.directory / "copy").exists())
        self.assertEqual("the copy", (self.directory / "free name.txt").read_text())

    def test_a_directory_is_moved_and_refused_the_same_way(self):
        (self.directory / "folder").mkdir()
        (self.directory / "folder" / "inside").write_text("kept")
        (self.directory / "other").mkdir()
        self.assertEqual(73, self.move("folder", "other").returncode)
        self.assertEqual(0, self.move("folder", "moved").returncode)
        self.assertEqual("kept", (self.directory / "moved" / "inside").read_text())

    def test_a_missing_entry_is_not_reported_as_a_taken_name(self):
        (self.directory / "taken").write_text("made meanwhile")
        self.assertEqual(2, self.move("gone", "taken").returncode)
        self.assertEqual(2, self.move("gone", "free").returncode)
        self.assertEqual("made meanwhile", (self.directory / "taken").read_text())

    def test_a_path_swapped_for_another_directory_moves_nothing(self):
        (self.directory / "copy").write_text("the copy")
        other = self.directory / "elsewhere"
        other.mkdir()
        (other / "copy").write_text("another file of that name")
        # The caller holds self.directory; the path it passes leads elsewhere by now.
        result = self.move("copy", "free", directory=other, identity=self.directory)
        self.assertEqual(2, result.returncode)
        self.assertEqual("another file of that name", (other / "copy").read_text())
        self.assertFalse((other / "free").exists())
        self.assertFalse((self.directory / "free").exists())

    def test_a_link_to_the_held_directory_is_the_held_directory(self):
        (self.directory / "copy").write_text("the copy")
        link = Path(tempfile.mkdtemp(dir=self.temp.name)) / "by-name"
        link.symlink_to(self.directory)
        self.assertEqual(0, self.move("copy", "free", directory=link, identity=self.directory).returncode)
        self.assertEqual("the copy", (self.directory / "free").read_text())

    def test_only_single_names_are_moved(self):
        (self.directory / "copy").write_text("the copy")
        (self.directory / "sub").mkdir()
        for source, target in [("copy", "sub/free"), ("copy", "../free"), ("./copy", "free"), ("copy", ".."), ("copy", ""), ("", "free")]:
            with self.subTest(source=source, target=target):
                self.assertEqual(2, self.move(source, target).returncode)
        self.assertEqual("the copy", (self.directory / "copy").read_text())

    def test_the_identity_has_to_be_two_numbers(self):
        (self.directory / "copy").write_text("the copy")
        held = os.stat(self.directory)
        for device, inode in [("x", str(held.st_ino)), (str(held.st_dev), "-1"), (str(held.st_dev), ""), (str(held.st_dev), str(held.st_ino) + "x")]:
            with self.subTest(device=device, inode=inode):
                result = subprocess.run([str(self.program), str(self.directory), device, inode, "copy", "free"], capture_output=True)
                self.assertEqual(2, result.returncode)
        self.assertEqual(2, subprocess.run([str(self.program), str(self.directory)], capture_output=True).returncode)
        self.assertEqual("the copy", (self.directory / "copy").read_text())


if __name__ == "__main__":
    unittest.main()
