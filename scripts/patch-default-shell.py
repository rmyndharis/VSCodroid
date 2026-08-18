#!/usr/bin/env python3
"""Points a bundled binary's default shell at one this app is allowed to run.

    patch-default-shell.py <binary>...
    patch-default-shell.py --check <directory>

Every executable in jniLibs comes from Termux, which builds for a prefix inside
its own application, and the shell it compiles in is

    /data/data/com.termux/files/usr/bin/sh

That path is inside another application's data directory. This app can neither
read it nor create it, so whatever the binary wanted a shell for fails with
ENOENT on a path nothing here chose. What a user sees is whatever ran the
command, three layers from the cause.

/system/bin/sh is the replacement, for the same reason download-termux-tools.sh
already rewrites Termux shebangs to it: it is on every Android device, and it
sits on a partition SELinux lets an app execute from. This app's own prefix is
not a candidate. Its absolute path is not knowable here (it carries the Android
user id), and `sh` under it already resolves through PATH, which ends in
/system/bin (see Environment.buildProcessEnvironment).

Measured on the shipped set: six of the eleven binaries in jniLibs name it, and
the environment rescues none of them fully:

  * make deliberately does not take SHELL from the environment, so the compiled
    value runs every recipe line;
  * git and git-remote-curl compile it as SHELL_PATH, which backs run-command
    and therefore `!` aliases, hooks, clean/smudge filters and the pager. $SHELL
    is not consulted for any of those. They are two binaries built from the same
    code, so neither is covered by fixing the other;
  * ssh compiles it as _PATH_BSHELL, which runs ProxyCommand and LocalCommand.
    Neither is configured by default here, so this one is latent;
  * tmux reads $SHELL for `default-shell` and this app exports it, so the
    constant is the fallback taken when that lookup fails checkshell() rather
    than the usual path;
  * node reaches it through Termux's patch to lib/child_process.js, so exec()
    and spawn(..., {shell: true}) default to it.

Two spellings, because the last one is JavaScript source and the rest are C
string constants, and the padding differs:

  * a C constant ends at its first NUL. The replacement carries that NUL, so the
    24 bytes that come free are already unreachable; they become NULs rather
    than anything else so what is left in the string pool is nothing, instead of
    a run of blanks sitting where every neighbour is NUL-terminated text;
  * the JavaScript occurrence is source, not data, and a NUL is not whitespace
    there. Padding it the same way leaves NUL bytes between two statements and
    the parse throws when V8 compiles that function. That one takes spaces.

Either way the rewrite preserves the file's length. The binary is an ELF whose
section sizes and offsets are fixed around this string, so shortening it would
move every byte after it. Nothing points into the middle of the string that
would be padded over, checked on all five C constants: every code reference and
every relocation addend lands on its first byte.

Exactly one occurrence is required; anything else stops the build:

  * 0 means the package changed where its shell comes from, and the shell the
    app would end up with is unknown. An edit that quietly stops matching is the
    failure this repository has already paid for once.
  * more than 1 means the path is spelled somewhere this rewrite does not reach,
    a compiled copy in Node's embedded code cache being the likely one, and a
    source-only edit would read as applied while the runtime kept the old path.

Idempotent, so an existing jniLibs binary can be handed to it to find out
whether it is already correct, rather than refetching to be sure.

--check is the sweep, and it is what the per-binary calls cannot do. Those name
five files at one call site, which is the shape where the sixth binary added
later is missed; --check walks every *.so that ships and fails if any of them
still names Termux's prefix as a shell, whether or not a download step ran in
that build. It only ever reads. Deliberately not folded into
verify-android-elf.py, whose per-file callers include the toolchain downloads:
libruby.so and Ruby's pty.so carry this same path, and those packs are not
rewritten, so the shared checker would fail builds that are correct.
"""

import argparse
import pathlib
import stat
import sys
import typing

TERMUX_SH = b"/data/data/com.termux/files/usr/bin/sh"
ANDROID_SH = b"/system/bin/sh"


class Form(typing.NamedTuple):
    """One spelling of the shell path, and how to rewrite it in place."""

    what: str
    old: bytes
    # What a rewritten binary carries. Also the already-applied test, which is
    # deliberately not `new` and deliberately not a census of ANDROID_SH over the
    # whole file: stock Node already assigns '/system/bin/sh' on Android with no
    # padding, because it never held a longer path, and a build without Termux's
    # child_process patch is exactly what this wants and must not be reported as
    # naming a shell it does not know.
    marker: bytes
    pad: bytes

    @property
    def new(self) -> bytes:
        return self.marker + self.pad * (len(self.old) - len(self.marker))


FORMS = (
    # The whole assignment, not the bare path: it is what pins the hit to the
    # line this knows how to rewrite, and it is where the padding can go without
    # changing what the line means.
    Form("JavaScript source",
         b"file = '" + TERMUX_SH + b"';",
         b"file = '" + ANDROID_SH + b"';",
         b" "),
    # The NUL is part of the match. Without it this would also fire on a longer
    # path that merely starts with the shell's, and the rewrite would truncate
    # whatever followed.
    #
    # The marker here is the whole padded run, not the bare path, which is the
    # opposite of the row above and for the opposite reason: there is no stock
    # build of these tools that already names our shell, so nothing is lost by
    # requiring the exact bytes a rewrite leaves. A bare "/system/bin/sh\0"
    # would report a binary as already patched on the strength of fifteen bytes
    # it may carry for some unrelated reason, while its actual default had moved
    # to some third path this app cannot reach either.
    Form("C string constant", TERMUX_SH + b"\0",
         ANDROID_SH + b"\0" * (len(TERMUX_SH) - len(ANDROID_SH) + 1), b"\0"),
)

for _form in FORMS:
    # Two ways a form added later gets it wrong by copying the wrong row above,
    # both of which read as patched on the file that ships. A replacement of a
    # different length moves every byte after it in the ELF. And a C constant
    # whose marker has lost the NUL the match consumed runs on into the next
    # string in the pool, naming a shell with the following message glued to it.
    if len(_form.new) != len(_form.old):
        raise SystemExit(f"patch-default-shell.py: the {_form.what} rewrite is "
                         f"{len(_form.new)} bytes against {len(_form.old)} matched")
    if _form.old.endswith(b"\0") and not _form.marker.endswith(b"\0"):
        raise SystemExit(f"patch-default-shell.py: the {_form.what} rewrite drops "
                         "the terminator its match consumed")
    # And the mirror of that one, which is the hazard the docstring spends a
    # paragraph on: a form matching source rather than data, filled with NUL.
    # Nothing downstream catches it. The rewrite reads as applied, the sweep
    # passes because Termux's path is gone, and the parse throws when the
    # engine reaches those bytes.
    if not _form.old.endswith(b"\0") and _form.pad == b"\0":
        raise SystemExit(f"patch-default-shell.py: the {_form.what} rewrite pads "
                         "source with NUL, which is not whitespace there")


def carries_termux_shell(data: bytes) -> bool:
    """The invariant --check enforces, on the bytes of one file."""
    return TERMUX_SH in data


def patch(path: pathlib.Path) -> bool:
    data = path.read_bytes()
    found = data.count(TERMUX_SH)

    if found == 0:
        applied = [f for f in FORMS if f.marker in data]
        if applied:
            print(f"  {path.name}: shell -> /system/bin/sh (already applied)")
            return True
        print(f"  ERROR: {path} names neither shell this script knows", file=sys.stderr)
        print(f"    expected one '{TERMUX_SH.decode()}' to rewrite,", file=sys.stderr)
        print("    or the rewritten path already in place. The package may", file=sys.stderr)
        print("    have moved where its default shell comes from: read what", file=sys.stderr)
        print("    it falls back to now and update this script before", file=sys.stderr)
        print("    shipping the binary.", file=sys.stderr)
        return False

    if found > 1:
        print(f"  ERROR: {path} spells the Termux shell {found} times", file=sys.stderr)
        print("    A copy outside the one this rewrites would keep the old", file=sys.stderr)
        print("    path, so the binary cannot be shipped on this rewrite alone.", file=sys.stderr)
        return False

    forms = [f for f in FORMS if data.count(f.old) == 1]
    if len(forms) != 1:
        print(f"  ERROR: {path} carries the Termux shell in no shape this knows",
              file=sys.stderr)
        print("    Recognised: " + ", ".join(f.what for f in FORMS) + ".", file=sys.stderr)
        print("    Read the bytes around the path and add its shape here", file=sys.stderr)
        print("    rather than widening the match to the bare path, which", file=sys.stderr)
        print("    would rewrite one that is a prefix of a longer one.", file=sys.stderr)
        return False

    form = forms[0]
    path.write_bytes(data.replace(form.old, form.new))

    # Read back rather than trusting the buffer that was written: what ships is
    # the file, and the count is the whole assertion this rests on.
    after = path.read_bytes()
    if len(after) != len(data) or carries_termux_shell(after) or form.marker not in after:
        print(f"  ERROR: {path} is not what the rewrite intended", file=sys.stderr)
        return False

    print(f"  {path.name}: shell -> /system/bin/sh "
          f"(was Termux's prefix, {form.what})")
    return True


def check(directory: pathlib.Path) -> bool:
    """Fail if any binary that ships still names Termux's prefix as a shell."""
    # One stream for the whole sweep, matching verify-android-elf.py: its reader
    # is a build log, and a FAIL split onto stderr arrives out of order against
    # the count that says how much was looked at. Measured in the Gradle gate:
    # the summary printed above the failure it summarised.
    targets = sorted(directory.glob("*.so"))
    if not targets:
        # An empty directory is not a clean result. Reporting success here would
        # make "nothing was checked" indistinguishable from "everything passed",
        # which is the failure this check exists to avoid.
        print(f"  FAIL   no *.so in {directory}")
        return False

    ok = True
    for target in targets:
        # Regular files only, checked before opening, and OSError caught per
        # file. read_bytes() on a FIFO blocks forever waiting for a writer, and a
        # dangling symlink named *.so raises out of the loop, abandoning every
        # binary after it. This globs whatever is in the directory rather than a
        # path it just created, so both are reachable here -- the same hazards
        # verify-android-elf.py measured, in the same directory.
        try:
            if not stat.S_ISREG(target.stat().st_mode):
                print(f"  FAIL   {target.name} is not a regular file")
                ok = False
                continue
            carries = carries_termux_shell(target.read_bytes())
        except OSError as e:
            print(f"  FAIL   {target.name}: {e}")
            ok = False
            continue
        if carries:
            print(f"  FAIL   {target.name} names {TERMUX_SH.decode()}")
            ok = False

    n = len(targets)
    print(f"  {n} binar{'y' if n == 1 else 'ies'} checked for a shell under "
          "Termux's prefix")
    return ok


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("binary", type=pathlib.Path, nargs="*",
                    help="binary to rewrite in place")
    ap.add_argument("--check", type=pathlib.Path, metavar="DIR",
                    help="rewrite nothing; fail if any *.so in DIR names "
                         "Termux's prefix as a shell")
    args = ap.parse_args()

    if args.check is not None:
        if args.binary:
            ap.error("--check reads a directory; it does not take binaries")
        return 0 if check(args.check) else 1

    if not args.binary:
        ap.error("give a binary to rewrite, or --check DIR to sweep one")

    # Every binary, not the first failure: a build that stops on libgit.so hides
    # whether the other four moved too, and that is the difference between one
    # upstream change and five.
    ok = True
    for target in args.binary:
        ok = patch(target) and ok
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
