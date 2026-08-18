#!/usr/bin/env python3
"""Points the bundled Node's default shell at one this app is allowed to run.

    patch-node-shell.py <binary>

Termux patches lib/child_process.js so that on Android, `exec()` and
`spawn(..., {shell: true})` default to a shell under Termux's own prefix:

    else if (process.platform === 'android')
      file = '/data/data/com.termux/files/usr/bin/sh';

That path is inside another application's data directory. This app can neither
read it nor create it, so every shell-based spawn from the server or from any
extension fails with ENOENT on a path nothing here chose. What a user sees is
whatever ran the command: a language extension's interpreter probe failing over
and over, three layers from the cause.

/system/bin/sh is the replacement, for the same reason download-termux-tools.sh
already rewrites Termux shebangs to it: it is on every Android device, and it
sits on a partition SELinux lets an app execute from. This app's own prefix is
not a candidate. Its absolute path is not knowable here (it carries the Android
user id), and `sh` under it already resolves through PATH, which ends in
/system/bin (see Environment.buildProcessEnvironment).

The rewrite preserves the file's length. The binary is an ELF whose section
sizes and offsets are fixed around this string, so shortening it would move
every byte after it. The 24 bytes that come free become spaces, not NULs: this
occurrence is JavaScript source, not a C string constant, and NUL padding would
land inside the quotes and hand Node a path with NUL bytes in it, which fails as
surely as the Termux path did while looking patched.

Exactly one occurrence is required; anything else stops the build:

  * 0 means Termux changed its patch or moved the default, and the shell this
    app would end up with is unknown. An edit that quietly stops matching is the
    failure this repository has already paid for once.
  * more than 1 means the path is spelled somewhere this rewrite does not reach,
    a compiled copy in the embedded code cache being the likely one, and a
    source-only edit would read as applied while the runtime kept the old path.
    Measured on Termux's nodejs-lts 24.18.0: one occurrence, in the embedded
    source of lib/child_process.js, inside a function V8 compiles lazily from
    that source.

Idempotent, so an existing jniLibs binary can be handed to it to find out
whether it is already correct, rather than refetching 45 MB to be sure.
"""

import pathlib
import sys

TERMUX_SH = b"/data/data/com.termux/files/usr/bin/sh"
ANDROID_SH = b"/system/bin/sh"

# The whole assignment, not the bare path: it is what pins the hit to the line
# this knows how to rewrite, and it is where the padding can go without changing
# what the line means.
OLD = b"file = '" + TERMUX_SH + b"';"
NEW = b"file = '" + ANDROID_SH + b"';"
PAD = b" " * (len(OLD) - len(NEW))


def is_patched(data):
    """True when the android branch names our shell and Termux's prefix is gone.

    Deliberately not `NEW + PAD`, and deliberately not a census of ANDROID_SH
    over the whole file. Stock Node already assigns '/system/bin/sh' on Android,
    with no padding because it never held a longer path; a build without Termux's
    child_process patch is exactly what this script wants and must not be
    reported as naming a shell it does not know. A second, unrelated spelling of
    /system/bin/sh elsewhere in the binary is likewise none of this script's
    business. The assertion is the assignment, plus Termux's path being gone.
    """
    return data.count(TERMUX_SH) == 0 and NEW in data


def main(argv):
    if len(argv) != 2:
        print("usage: patch-node-shell.py <binary>", file=sys.stderr)
        return 2

    path = pathlib.Path(argv[1])
    data = path.read_bytes()
    found = data.count(TERMUX_SH)

    if found == 0:
        if is_patched(data):
            print("  shell   : /system/bin/sh (already applied)")
            return 0
        print(f"  ERROR: {path} names neither shell this script knows", file=sys.stderr)
        print(f"    expected one '{TERMUX_SH.decode()}' to rewrite,", file=sys.stderr)
        print("    or the rewritten line already in place. Termux's", file=sys.stderr)
        print("    child_process patch may have moved: read what", file=sys.stderr)
        print("    lib/child_process.js falls back to on Android and", file=sys.stderr)
        print("    update this script before shipping the runtime.", file=sys.stderr)
        return 1

    if found > 1 or data.count(OLD) != 1:
        print(f"  ERROR: {path} does not carry one Termux shell assignment", file=sys.stderr)
        print(f"    copies of the path: {found}, of the assignment: {data.count(OLD)}", file=sys.stderr)
        print("    A copy outside that line would keep the old path, so the", file=sys.stderr)
        print("    runtime cannot be shipped on this rewrite alone.", file=sys.stderr)
        return 1

    path.write_bytes(data.replace(OLD, NEW + PAD))

    # Read back rather than trusting the buffer that was written: what ships is
    # the file, and the count is the whole assertion this rests on.
    after = path.read_bytes()
    if len(after) != len(data) or not is_patched(after):
        print(f"  ERROR: {path} is not what the rewrite intended", file=sys.stderr)
        return 1

    print("  shell   : /system/bin/sh (was Termux's prefix)")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
