#!/usr/bin/env python3
"""Points the C/C++ pack's sysroot headers at the directories this app really has.

    patch-sysroot-paths.py <include-directory>

Termux builds its NDK sysroot for its own prefix, and two of its headers carry
that prefix into every program compiled against them, as inline code rather than
as a macro a program could override:

  * stdio.h replaces tmpfile() with a function that creates its file under
    /data/data/com.termux/files/usr/tmp. That directory is inside another
    application, so the call returned NULL for every program, with TMPDIR set
    and writable. Measured on an API 33 emulator.
  * pwd.h rewrites each struct passwd that getpwuid() and getpwnam() return,
    setting pw_dir to /data/data/com.termux/files/home and pw_shell to a bash
    under the same prefix. A program that asks for the user's home (git-like
    tools, editors, shells) got a directory it cannot create.

tmpfile() now reads TMPDIR, falling back to /data/local/tmp as AOSP's own does,
and pwd.h takes the home from HOME and the shell from SHELL, which this app
exports to every terminal and task (Environment.buildProcessEnvironment), with
/ and /system/bin/sh when they are not set.

Not touched, on purpose: P_tmpdir and _PATH_TMP in stdio.h and paths.h, and the
utmp paths. They are macros a program may paste into a string literal, so a
replacement that reads the environment would not compile there, and a fixed one
has no directory to name: /tmp does not exist here and /data/local/tmp is not
writable by an app. A program that needs a temporary directory has to ask for
TMPDIR, as it does on every other system.

Exactly one occurrence of each block is required and anything else stops the
build, the way patch-default-shell.py refuses: an edit that quietly stops
matching is how a header ships unpatched. Idempotent, so a header that already
carries the replacement is accepted.
"""

import sys
from pathlib import Path

TMPFILE_OLD = (
    '\tint __termux_pid = getpid();\n'
    '\tchar* __termux_path;\n'
    '\tint __termux_i;\n'
    '\tfor (__termux_i = 0; __termux_i < 100; __termux_i++) {\n'
    '\t\tunsigned int __termux_random = arc4random();\n'
    '\t\tif (asprintf(&__termux_path, "/data/data/com.termux/files/usr/tmp/tmpfile.%d-%u", '
    '__termux_pid, __termux_random) == -1) return NULL;\n'
)
TMPFILE_NEW = (
    '\tint __termux_pid = getpid();\n'
    '\tchar* __termux_path;\n'
    '\tint __termux_i;\n'
    '\tconst char* __termux_dir = getenv("TMPDIR");\n'
    '\tif (__termux_dir == NULL || *__termux_dir == \'\\0\') __termux_dir = "/data/local/tmp";\n'
    '\tfor (__termux_i = 0; __termux_i < 100; __termux_i++) {\n'
    '\t\tunsigned int __termux_random = arc4random();\n'
    '\t\tif (asprintf(&__termux_path, "%s/tmpfile.%d-%u", __termux_dir, '
    '__termux_pid, __termux_random) == -1) return NULL;\n'
)
GETENV_OLD = 'uint32_t arc4random(void);\nstatic __inline__ FILE* _Nullable tmpfile() {\n'
GETENV_NEW = (
    'uint32_t arc4random(void);\n'
    'extern char* getenv(const char*);\n'
    'static __inline__ FILE* _Nullable tmpfile() {\n'
)

PWD_OLD = (
    'static void android_setup_pwd(struct passwd* pw) {\n'
    '       char const* result = "/data/data/com.termux/files/usr/bin/login";\n'
    '       if (result == NULL || access(result, /*X_OK*/1) == -1) {\n'
    '               pw->pw_shell = "/data/data/com.termux/files/usr/bin/bash";\n'
    '       } else {\n'
    '               pw->pw_shell = (char*) result;\n'
    '       }\n'
    '       pw->pw_dir = "/data/data/com.termux/files/home";\n'
)
PWD_NEW = (
    'extern char* getenv(const char*);\n'
    '\n'
    'static void android_setup_pwd(struct passwd* pw) {\n'
    '       char const* shell = getenv("SHELL");\n'
    '       pw->pw_shell = (shell != NULL && *shell != \'\\0\') ? (char*) shell : "/system/bin/sh";\n'
    '       char const* home = getenv("HOME");\n'
    '       pw->pw_dir = (home != NULL && *home != \'\\0\') ? (char*) home : "/";\n'
)


def patch(path: Path, edits: list[tuple[str, str]], gone: list[str]) -> str:
    text = path.read_text()
    if all(new in text for _, new in edits) and not any(g in text for g in gone):
        return "already patched"
    for old, _ in edits:
        found = text.count(old)
        if found != 1:
            sys.exit(
                f"{path}: expected exactly one occurrence of the block being replaced, found {found}. "
                "Termux changed the header; the replacement has to be rewritten against it."
            )
    for old, new in edits:
        text = text.replace(old, new)
    leftover = [g for g in gone if g in text]
    if leftover:
        sys.exit(f"{path}: still names {leftover} after the rewrite")
    path.write_text(text)
    return "patched"


def main() -> None:
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    include = Path(sys.argv[1])
    for header in ("stdio.h", "pwd.h"):
        if not (include / header).is_file():
            sys.exit(f"{include / header} is missing")
    print("  stdio.h tmpfile():", patch(
        include / "stdio.h",
        [(TMPFILE_OLD, TMPFILE_NEW), (GETENV_OLD, GETENV_NEW)],
        ["com.termux/files/usr/tmp/tmpfile"],
    ))
    print("  pwd.h pw_dir and pw_shell:", patch(
        include / "pwd.h",
        [(PWD_OLD, PWD_NEW)],
        ["com.termux/files/home", "com.termux/files/usr/bin/login", "com.termux/files/usr/bin/bash"],
    ))


if __name__ == "__main__":
    main()
