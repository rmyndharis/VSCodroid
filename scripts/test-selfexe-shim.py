#!/usr/bin/env python3
"""Compile the real shim with the host compiler and ask it the questions programs ask.

A probe program named `linker64` makes the kernel itself answer that its
executable ends in `/linker64`, which is the condition the shim acts on, so the
loader does not have to be on the machine. The same probe under another name is
the program that was executed directly.

The second answer, a path through the platform's `/data/user/0` symlink, is
tested against a directory of the test's own, which the shim is built to treat as
`/data/user`: a test cannot create the real one.

The third, the link the app makes to a device folder's copy, needs no stand-in: it
is recognised by the shape of the directory it sits in, which a test can build.
"""

import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SOURCE = Path(__file__).with_name("selfexe-shim.c")
VAR = "TERMUX_EXEC__PROC_SELF_EXE"
# The names of two device-folder copies: twelve lowercase hex digits each.
HASH = "0123456789ab"
OTHER_HASH = "ba9876543210"

PROBE = r"""
#include <fcntl.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

/* probe <path> [at] [size]: print what readlink, or readlinkat, answers. */
int main(int argc, char **argv) {
    char buf[PATH_MAX];
    int use_at = argc > 2 && strcmp(argv[2], "at") == 0;
    size_t size = argc > 3 ? (size_t)atoi(argv[3]) : sizeof(buf);
    ssize_t n = use_at ? readlinkat(AT_FDCWD, argv[1], buf, size) : readlink(argv[1], buf, size);
    if (n < 0) {
        perror("readlink");
        return 2;
    }
    fwrite(buf, 1, (size_t)n, stdout);
    return 0;
}
"""


OPENER = r"""
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdio.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

/* opener <dir> <name> <word>...: open <dir>, then <name> inside it with the named flags. */
int main(int argc, char **argv) {
    int flags = 0, wide = 0, mode = 0;
    for (int i = 3; i < argc; i++) {
        if (!strcmp(argv[i], "nofollow")) flags |= O_NOFOLLOW;
        else if (!strcmp(argv[i], "directory")) flags |= O_DIRECTORY;
        else if (!strcmp(argv[i], "path")) flags |= O_PATH;
        else if (!strcmp(argv[i], "create")) { flags |= O_CREAT | O_WRONLY | O_EXCL; mode = 0640; }
        else if (!strcmp(argv[i], "wide")) wide = 1;
    }
    int dirfd = open(argv[1], O_PATH | O_DIRECTORY);
    if (dirfd < 0) { perror("open"); return 3; }
    umask(0);
    int fd = wide ? openat64(dirfd, argv[2], flags, mode) : openat(dirfd, argv[2], flags, mode);
    if (fd < 0) { printf("errno %s\n", strerror(errno)); return 0; }
    char link[64], target[PATH_MAX];
    snprintf(link, sizeof(link), "/proc/self/fd/%d", fd);
    ssize_t n = readlink(link, target, sizeof(target) - 1);
    struct stat st;
    if (n < 0 || fstat(fd, &st) != 0) { perror("inspect"); return 4; }
    target[n] = 0;
    printf("ok %s %o\n", target, st.st_mode & 0777);
    return 0;
}
"""


@unittest.skipUnless(sys.platform.startswith("linux"), "needs /proc and LD_PRELOAD")
class SelfExeShimTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory()
        cls.addClassCleanup(cls.temp.cleanup)
        cls.root = Path(cls.temp.name).resolve()
        probe_c = cls.root / "probe.c"
        probe_c.write_text(PROBE)
        cls.loader = cls.root / "linker64"
        cls.plain = cls.root / "plain"
        subprocess.run(["cc", "-Wall", "-Wextra", "-Werror", str(probe_c), "-o", str(cls.loader)], check=True)
        shutil.copy(cls.loader, cls.plain)
        # `user` stands in for /data/user, with the platform's symlink in it.
        cls.user, cls.other, cls.data = (cls.root / name for name in ("user", "other", "data"))
        for directory in (cls.user, cls.other, cls.data):
            directory.mkdir()
        (cls.user / "0").symlink_to(cls.data)
        (cls.user / "1").symlink_to(cls.data)
        (cls.other / "0").symlink_to(cls.data)
        # `saf-mirrors` stands in for the folder the app keeps its device-folder copies
        # in: a copy at <hash>, and the name the editor opens it by, by-name/<hash>/<name>,
        # a link to ../../<hash>.
        cls.mirrors = cls.root / "saf-mirrors"
        cls.copy = cls.mirrors / HASH
        cls.copy.mkdir(parents=True)
        (cls.copy / "notes.txt").write_text("in the copy")
        (cls.mirrors / OTHER_HASH).mkdir()
        cls.named = cls.mirrors / "by-name" / HASH
        cls.named.mkdir(parents=True)
        (cls.named / "project").symlink_to(f"../../{HASH}")
        (cls.named / "real").mkdir()
        (cls.named / "plain").write_text("not a directory")
        # Links the shim must leave refused: each is something the app does not make.
        for name, target in (
            ("other-copy", f"../../{OTHER_HASH}"),  # another copy than the directory names
            ("absolute", str(cls.copy)),  # the same place, spelled out
            ("sideways", f"../{HASH}"),  # not the form the app makes
            ("trailing", f"../../{HASH}/"),
            ("longer", f"../../{HASH}0"),
            ("escape", "../../.."),
        ):
            (cls.named / name).symlink_to(target)
        # The same link in a directory that is not where the app makes them. The copy it
        # names exists in every case, so following it would succeed.
        cls.misplaced = [
            cls.lay_out(cls.root / "elsewhere" / "by-name" / HASH),  # no saf-mirrors
            cls.lay_out(cls.root / "notsaf-mirrors" / "by-name" / HASH),  # a name that only ends in it
            cls.lay_out(cls.mirrors / "elsewhere" / HASH),  # not under by-name
            cls.lay_out(cls.mirrors / "by-name" / HASH.upper()),  # not lowercase hex
            cls.lay_out(cls.mirrors / "by-name" / HASH[:-1]),  # too short
            cls.lay_out(cls.mirrors / "by-name" / (HASH + "c")),  # too long
        ]
        # The right directory and the right link, over a copy that has been replaced by a link.
        swapped = cls.root / "swapped" / "saf-mirrors"
        (swapped / "by-name" / HASH).mkdir(parents=True)
        (swapped / "by-name" / HASH / "project").symlink_to(f"../../{HASH}")
        (swapped / HASH).symlink_to(cls.data)
        cls.swapped = swapped / "by-name" / HASH
        opener_c = cls.root / "opener.c"
        opener_c.write_text(OPENER)
        cls.opener = cls.root / "opener"
        subprocess.run(["cc", "-O0", "-U_FORTIFY_SOURCE", "-Wall", "-Wextra", "-Werror", str(opener_c),
                        "-o", str(cls.opener)], check=True)
        cls.shim = cls.root / "libselfexe.so"
        subprocess.run(
            ["cc", "-shared", "-fPIC", "-fvisibility=hidden", "-Wall", "-Wextra", "-Werror",
             f'-DALIAS_PARENT="{cls.user}"', str(SOURCE), "-o", str(cls.shim)],
            check=True,
        )

    @staticmethod
    def lay_out(parent):
        """`parent` named for a copy, holding a link `project` to ../../<its name>, with that copy made."""
        parent.mkdir(parents=True)
        (parent.parent.parent / parent.name).mkdir(exist_ok=True)
        (parent / "project").symlink_to(f"../../{parent.name}")
        return parent

    def ask(self, program, *args, real=None, preload=True):
        env = {k: v for k, v in os.environ.items() if k not in (VAR, "LD_PRELOAD")}
        if preload:
            env["LD_PRELOAD"] = str(self.shim)
        if real is not None:
            env[VAR] = real
        result = subprocess.run([str(program), "/proc/self/exe", *args], env=env, capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stderr)
        return result.stdout

    def opened(self, parent, name, *words, preload=True):
        env = {k: v for k, v in os.environ.items() if k not in (VAR, "LD_PRELOAD")}
        if preload:
            env["LD_PRELOAD"] = str(self.shim)
        result = subprocess.run([str(self.opener), str(parent), name, *words], env=env, capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stderr)
        return result.stdout.strip()

    def test_without_the_library_the_kernel_names_the_loader(self):
        # The control: the probe really is told it is the loader, so what the
        # other cases change is the shim's doing.
        self.assertEqual(str(self.loader), self.ask(self.loader, real="/data/x/codex", preload=False))

    def test_a_program_the_loader_started_is_told_the_real_path(self):
        self.assertEqual("/data/x/codex", self.ask(self.loader, real="/data/x/codex"))

    def test_readlinkat_gets_the_same_answer(self):
        self.assertEqual("/data/x/codex", self.ask(self.loader, "at", real="/data/x/codex"))

    def test_a_program_executed_directly_keeps_its_own_answer(self):
        # A variable inherited from a parent that ran through the loader.
        self.assertEqual(str(self.plain), self.ask(self.plain, real="/data/x/codex"))

    def test_nothing_changes_without_the_variable(self):
        self.assertEqual(str(self.loader), self.ask(self.loader))

    def test_a_value_that_is_not_an_absolute_path_is_not_trusted(self):
        for value in ("", "codex", "../codex"):
            self.assertEqual(str(self.loader), self.ask(self.loader, real=value), value)

    def test_the_answer_is_cut_to_the_buffer_like_readlink_does(self):
        self.assertEqual("/data/", self.ask(self.loader, "readlink", "6", real="/data/x/codex"))

    def test_a_buffer_shorter_than_the_loader_path_still_gets_the_real_path(self):
        # The kernel's answer, cut to this size, would not end in /linker64.
        self.assertEqual("/da", self.ask(self.loader, "readlink", "3", real="/data/x/codex"))

    def test_an_answer_too_short_to_hold_anything_is_left_to_the_kernel(self):
        result = subprocess.run(
            [str(self.loader), "/proc/self/exe", "readlink", "0"],
            env={**os.environ, "LD_PRELOAD": str(self.shim), VAR: "/data/x/codex"},
            capture_output=True, text=True,
        )
        self.assertEqual(2, result.returncode)

    def test_other_links_are_untouched(self):
        env = {**os.environ, "LD_PRELOAD": str(self.shim), VAR: "/data/x/codex"}
        result = subprocess.run([str(self.loader), "/proc/self/cwd"], env=env, capture_output=True, text=True, cwd=self.root)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(str(self.root), result.stdout)

    # The alias: a directory opened the way a program that refuses symlinks opens
    # each directory of a path.
    WALK = ("path", "directory", "nofollow")

    def test_the_alias_is_refused_without_the_library(self):
        # The control: what Codex's patch tool met, so the cases below change it.
        self.assertEqual("errno Not a directory", self.opened(self.user, "0", *self.WALK, preload=False))

    def test_the_alias_is_followed_with_it(self):
        self.assertEqual(f"ok {self.data} {self.data.stat().st_mode & 0o777:o}", self.opened(self.user, "0", *self.WALK))

    def test_openat64_follows_it_too(self):
        self.assertEqual(f"ok {self.data} {self.data.stat().st_mode & 0o777:o}", self.opened(self.user, "0", *self.WALK, "wide"))

    def test_only_the_name_the_platform_uses_is_followed(self):
        self.assertEqual("errno Not a directory", self.opened(self.user, "1", *self.WALK))

    def test_only_under_the_directory_the_platform_uses(self):
        self.assertEqual("errno Not a directory", self.opened(self.other, "0", *self.WALK))

    def test_a_walk_that_follows_symlinks_is_untouched(self):
        for preload in (False, True):
            self.assertTrue(self.opened(self.user, "0", "path", "directory", preload=preload).startswith(f"ok {self.data} "))

    def test_creating_a_file_keeps_the_mode_it_asked_for(self):
        for name, words in (("made.txt", ("create",)), ("made64.txt", ("create", "wide"))):
            self.assertEqual(f"ok {self.data / name} 640", self.opened(self.data, name, *words), name)

    def test_without_the_variable_or_with_it_the_alias_answer_is_the_same(self):
        # The two answers are independent: neither depends on the other's setup.
        env = {**os.environ, "LD_PRELOAD": str(self.shim), VAR: "/data/x/codex"}
        result = subprocess.run([str(self.opener), str(self.user), "0", *self.WALK], env=env, capture_output=True, text=True)
        self.assertEqual(f"ok {self.data} {self.data.stat().st_mode & 0o777:o}", result.stdout.strip())

    # A device folder's name: the link the app makes to a copy, opened the way the same
    # tool opens each directory of a path.
    def test_the_link_to_a_copy_is_refused_without_the_library(self):
        # The control: what the patch tool met in a device folder.
        self.assertEqual("errno Not a directory", self.opened(self.named, "project", *self.WALK, preload=False))

    def test_the_link_to_a_copy_opens_the_copy_with_it(self):
        self.assertEqual(f"ok {self.copy} {self.copy.stat().st_mode & 0o777:o}", self.opened(self.named, "project", *self.WALK))

    def test_openat64_opens_the_copy_too(self):
        self.assertEqual(f"ok {self.copy} {self.copy.stat().st_mode & 0o777:o}", self.opened(self.named, "project", *self.WALK, "wide"))

    def test_only_the_link_the_app_makes_is_followed(self):
        for name in ("other-copy", "absolute", "sideways", "trailing", "longer", "escape"):
            self.assertEqual("errno Not a directory", self.opened(self.named, name, *self.WALK), name)

    def test_only_in_a_directory_named_for_a_copy_under_saf_mirrors_by_name(self):
        for parent in self.misplaced:
            self.assertEqual("errno Not a directory", self.opened(parent, "project", *self.WALK), str(parent))

    def test_a_copy_that_was_replaced_by_a_link_is_still_refused(self):
        # The copy is opened without following anything, so a link put in its place is not
        # a way out of the tree.
        self.assertEqual("errno Not a directory", self.opened(self.swapped, "project", *self.WALK))

    def test_entries_that_are_not_links_are_untouched(self):
        real = self.named / "real"
        self.assertEqual(f"ok {real} {real.stat().st_mode & 0o777:o}", self.opened(self.named, "real", *self.WALK))
        self.assertEqual("errno Not a directory", self.opened(self.named, "plain", *self.WALK))
        self.assertEqual("errno No such file or directory", self.opened(self.named, "missing", *self.WALK))

    def test_a_walk_that_follows_the_link_is_untouched(self):
        for preload in (False, True):
            self.assertTrue(
                self.opened(self.named, "project", "path", "directory", preload=preload).startswith(f"ok {self.copy} "),
                preload,
            )

    def test_the_named_copy_opens_with_the_variable_set_too(self):
        env = {**os.environ, "LD_PRELOAD": str(self.shim), VAR: "/data/x/codex"}
        result = subprocess.run([str(self.opener), str(self.named), "project", *self.WALK], env=env, capture_output=True, text=True)
        self.assertEqual(f"ok {self.copy} {self.copy.stat().st_mode & 0o777:o}", result.stdout.strip())


if __name__ == "__main__":
    unittest.main()
