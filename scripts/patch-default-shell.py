#!/usr/bin/env python3
"""Points a bundled file's default shell at one this app is allowed to run.

    patch-default-shell.py <file>...
    patch-default-shell.py --check <directory>

Almost everything this app ships comes from Termux, which builds for a prefix
inside its own application, and the shell it names is

    /data/data/com.termux/files/usr/bin/sh

That path is inside another application's data directory. This app can neither
read it nor create it, so whatever wanted a shell fails with ENOENT on a path
nothing here chose. What a user sees is whatever ran the command, three layers
from the cause.

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

Four more files this script rewrites sit outside jniLibs, in what the same
packages ship beside their binaries. They are not every shipped file that names
the path; the ones left alone are answered for further down, where the sweep
says which directories it covers:

  * Python's stdlib names it in subprocess.py, as the argv[0] _execute_child
    hands to execve for shell=True. Every subprocess.run(..., shell=True) from
    the bundled interpreter, and every library reaching for one underneath,
    raises FileNotFoundError naming a path the caller never chose;
  * Ruby carries it twice as a C constant, in libruby.so and in the pty
    extension. In pty.so it sits in the string pool beside the literal `SHELL`,
    which reads as the fallback PTY.getpty takes when that environment lookup
    finds nothing, the same shape as tmux above. Read from the strings, not from
    the source, so treat the placement as evidence rather than as proof;
  * Ruby's mkmf.rb writes it into the `SHELL =` line of every Makefile it
    generates for a native gem extension. Repointing make does not cover that
    one: make takes SHELL from the makefile and never from the environment, so
    an explicit assignment overrides the compiled-in value libmake.so carries.
    Latent rather than live, since building a native gem also wants a C compiler
    and none is bundled, but it is the compiler that should be the thing missing.

Four spellings. Two questions decide the rewrite: whether the file's length is
fixed, and whether the padded bytes are data or source.

  * a C constant ends at its first NUL. The replacement carries that NUL, so the
    24 bytes that come free are already unreachable; they become NULs rather
    than anything else so what is left in the string pool is nothing, instead of
    a run of blanks sitting where every neighbour is NUL-terminated text;
  * the JavaScript occurrence is source, not data, and a NUL is not whitespace
    there. Padding it the same way leaves NUL bytes between two statements and
    the parse throws when V8 compiles that function. That one takes spaces.
  * the Python and Ruby occurrences are standalone text files rather than bytes
    inside an ELF, so nothing fixes their length and they are rewritten plainly,
    with an empty pad. Padding mkmf.rb would be worse than pointless: the line
    it matches is copied verbatim into every Makefile mkmf generates, and a
    make variable keeps the trailing whitespace of its value (measured on GNU
    Make 3.81, where `SHELL = /bin/sh` followed by 24 spaces expands to a value
    carrying all 24). Whether a given make still finds the shell after that is a
    question with no reason to be asked.

A form that pads preserves the file's length, because the file it matches is an
ELF whose section sizes and offsets are fixed around this string and shortening
it would move every byte after it. Nothing points into the middle of the string
that would be padded over, checked on all five C constants in jniLibs: every
code reference and every relocation addend lands on its first byte.

Exactly one occurrence is required; anything else stops the build:

  * 0 means the package changed where its shell comes from, and the shell the
    app would end up with is unknown. An edit that quietly stops matching is the
    failure this repository has already paid for once.
  * more than 1 means the path is spelled somewhere this rewrite does not reach,
    a compiled copy in Node's embedded code cache being the likely one, and a
    source-only edit would read as applied while the runtime kept the old path.

Idempotent, so an already-placed file can be handed to it to find out whether it
is already correct, rather than refetching to be sure.

--check is the sweep, and it is what the per-file calls cannot do. Those name
their files at one call site, which is the shape where the file added later is
missed; --check walks every regular file under a directory, at any depth, and
fails if any of them still names Termux's prefix as a shell, whether or not a
download step ran in that build. A symlinked directory it fails rather than
follows, for the reason an empty directory fails: what it cannot answer for it
must not report as clean. It only ever reads.

Which directories go under it is a decision, not a default. A sweep that fails a
correct build is worse than no sweep, so a directory qualifies only once every
file in it is answered for. Two are:

  * jniLibs, swept by the verifyBundledShellPaths Gradle task at packaging time,
    which is the moment a binary restored from a cache no download step re-ran
    would otherwise ship;
  * the Ruby asset pack, swept by download-ruby.sh once it has placed the pack,
    which is the only step that ever fills it, and again by the
    verifyRubyPackShellPaths Gradle task, because what a bundle packages is the
    directory in the checkout rather than the download that last wrote it.

The Python stdlib is deliberately not one of them. Three files under it name the
path in a `#!` line rather than as a default they hand to execve, none of them
an importable module: config-3.x/makesetup, config-3.x/install-sh and
ctypes/macholib/fetch_macholib. Nothing under filesDir can be execve'd at all
under Android's SELinux policy, so a shebang there is dead text and rewriting it
would change nothing, while sweeping the directory would fail a build that is
correct. subprocess.py is therefore rewritten by name, and download-python.sh's
call is what fails closed when Termux moves it.

The rest of assets/usr is not one either, and Termux's git package is the whole
of why. Twenty-five files under it name the path: twelve git-core binaries hold
it as the same NUL-terminated constant libgit.so does, and thirteen `.sample`
hook templates hold it in a `#!` line. download-termux-tools.sh rewrites the
git-core shell scripts as it copies them and takes the binaries as they are.
None of the twenty-five is reachable. The four helpers git genuinely execs --
git-remote-http, -https, -ftp and -ftps -- are replaced at setup by symlinks to
the already-rewritten libgit-remote-curl.so (FirstRunSetup.setupGitCore); the
other eight are regular files under filesDir, which SELinux will not execve at
all; and git runs no hook whose name ends in .sample.

Deliberately not folded into verify-android-elf.py either, whose per-file callers
include the toolchain downloads. Go's pack carries Termux shebangs in two plan9
syscall generators and an iOS clang wrapper, and Java's lib/modules carries the
path as a length-prefixed constant inside a jimage archive, next to
sun.print.PrintServiceLookupProvider's AIX printer enumeration. None of the four
is reachable on Android, and the archive could not be rewritten in place anyway,
so the shared checker would fail builds that are correct.
"""

import argparse
import pathlib
import re
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
    # The filler that keeps the file's length, and empty when nothing fixes it.
    # An empty pad is a claim about the file, not a shortcut: it says the match
    # lives in a standalone text file rather than in an ELF, so the bytes after
    # it may move.
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
    # Python's stdlib, in subprocess._execute_child. The parentheses are Termux's
    # own: upstream has a conditional there, `('/system/bin/sh' if
    # hasattr(sys, 'getandroidapilevel') else '/bin/sh')`, and the patch collapsed
    # it to one path. Matching the whole assignment therefore also means an
    # upstream stdlib, which already picks the right shell on Android, is
    # reported as naming no shell this knows rather than silently rewritten.
    Form("Python source",
         b"unix_shell = ('" + TERMUX_SH + b"')",
         b"unix_shell = ('" + ANDROID_SH + b"')",
         b""),
    # Ruby's mkmf.rb, inside the heredoc that becomes the generated Makefile.
    # Matched with its `SHELL =` so the hit is pinned to the assignment rather
    # than to any other mention of a shell in a 2000-line generator.
    Form("makefile assignment",
         b"SHELL = " + TERMUX_SH,
         b"SHELL = " + ANDROID_SH,
         b""),
)

for _form in FORMS:
    # Three ways a form added later gets it wrong by copying the wrong row above,
    # all of which read as patched on the file that ships. A padded replacement of
    # a different length moves every byte after it in the ELF. A form that drops
    # the padding on a match that lives inside an ELF does the same, only by
    # shortening the file rather than by miscounting it; a NUL-terminated constant
    # is the one shape here that can only come from an ELF. And a C constant whose
    # marker has lost the NUL the match consumed runs on into the next string in
    # the pool, naming a shell with the following message glued to it.
    if _form.pad and len(_form.new) != len(_form.old):
        raise SystemExit(f"patch-default-shell.py: the {_form.what} rewrite is "
                         f"{len(_form.new)} bytes against {len(_form.old)} matched")
    if not _form.pad and _form.old.endswith(b"\0"):
        raise SystemExit(f"patch-default-shell.py: the {_form.what} rewrite drops "
                         "the padding a NUL-terminated constant needs to keep an "
                         "ELF's length")
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


# Any shell under any app's private data directory, not only Termux's.
#
# The rewriter knows one spelling and pads it in place, so it can only fix the
# Termux one. The detector has no such excuse, and had the same narrowness: the
# failure that motivated this whole file was reported as
# `/data/data/com.vscodroid/files/usr/bin/sh`, the app's OWN prefix, which the
# Termux literal does not match. SELinux refuses `execve` on anything under
# `app_data_file` whoever owns it, so every such path is unrunnable and the
# check should say so rather than only recognising the one it can repair.
APP_DATA_SH = re.compile(rb"/data/data/[A-Za-z0-9._]+/files/usr/bin/sh")


def carries_unrunnable_shell(data: bytes) -> "bytes | None":
    """The invariant --check enforces, on the bytes of one file.

    Returns the offending path, so a hit on a prefix this file cannot rewrite
    can say which one it found instead of naming Termux's.
    """
    hit = APP_DATA_SH.search(data)
    return hit.group(0) if hit else None


def patch(path: pathlib.Path) -> bool:
    # Caught rather than raised, so a target that is not there is one FAIL among
    # the others instead of a traceback out of the loop in main(). The paths a
    # caller passes are built from a package's own version string -- see
    # download-ruby.sh, which derives the directory two of its three sit in --
    # so a version that maps somewhere else takes out more than one, and the log
    # has to name all of them or the fix arrives one file at a time.
    try:
        data = path.read_bytes()
    except OSError as e:
        print(f"  ERROR: {path}: {e}", file=sys.stderr)
        return False
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
        print("    shipping the file.", file=sys.stderr)
        return False

    if found > 1:
        print(f"  ERROR: {path} spells the Termux shell {found} times", file=sys.stderr)
        print("    A copy outside the one this rewrites would keep the old", file=sys.stderr)
        print("    path, so the file cannot be shipped on this rewrite alone.", file=sys.stderr)
        return False

    forms = [f for f in FORMS if data.count(f.old) == 1]
    if not forms:
        print(f"  ERROR: {path} carries the Termux shell in no shape this knows",
              file=sys.stderr)
        print("    Recognised: " + ", ".join(f.what for f in FORMS) + ".", file=sys.stderr)
        print("    Read the bytes around the path and add its shape here", file=sys.stderr)
        print("    rather than widening the match to the bare path, which", file=sys.stderr)
        print("    would rewrite one that is a prefix of a longer one.", file=sys.stderr)
        return False

    # The opposite complaint, and it needs its own message: told to add a shape,
    # a reader goes looking for the one that is missing, and here every shape
    # that could match already has. Two forms can only meet on the single
    # occurrence counted above, which takes the bytes around it satisfying both:
    # a makefile assignment embedded in a binary would answer to `SHELL = <path>`
    # and to `<path>\0` on the same hit.
    if len(forms) > 1:
        print(f"  ERROR: {path} carries the Termux shell in more than one shape",
              file=sys.stderr)
        print("    Matched: " + ", ".join(f.what for f in forms) + ".", file=sys.stderr)
        print("    One occurrence answering to two forms means one of them is", file=sys.stderr)
        print("    too loose here, so which rewrite applies is decided by the", file=sys.stderr)
        print("    order of the table. Narrow the match that should not have", file=sys.stderr)
        print("    fired rather than adding a shape.", file=sys.stderr)
        return False

    form = forms[0]
    path.write_bytes(data.replace(form.old, form.new))

    # Read back rather than trusting the buffer that was written: what ships is
    # the file, and the count is the whole assertion this rests on. The length is
    # asserted only for a padded form, because that is the only kind that claims
    # to keep it.
    after = path.read_bytes()
    kept_length = len(after) == len(data) or not form.pad
    if not kept_length or carries_unrunnable_shell(after) or form.marker not in after:
        print(f"  ERROR: {path} is not what the rewrite intended", file=sys.stderr)
        return False

    print(f"  {path.name}: shell -> /system/bin/sh "
          f"(was Termux's prefix, {form.what})")
    return True


def check(directory: pathlib.Path) -> bool:
    """Fail if any file that ships still names Termux's prefix as a shell."""
    # One stream for the whole sweep, matching verify-android-elf.py: its reader
    # is a build log, and a FAIL split onto stderr arrives out of order against
    # the count that says how much was looked at. Measured in the Gradle gate:
    # the summary printed above the failure it summarised.
    #
    # Every regular file at any depth, not the *.so at the top of the directory
    # this used to glob. jniLibs is flat and reads the same either way; the Ruby
    # pack is not. Its three hits sit at three different depths, none of them at
    # the top, and one is a Ruby source file rather than a *.so, so a glob of one
    # extension in one directory answers for none of the three.
    #
    # A symlinked directory is kept rather than dropped, and the loop below then
    # fails it as not a regular file. rglob does not descend through one, so
    # dropping it the way a real directory is dropped would leave a whole subtree
    # unread with nothing but the count to say so -- the shape this sweep exists
    # to prevent, one level up. Following it instead is not the fix: the walk
    # would leave the directory it was asked about and answer for files that do
    # not ship. Neither swept directory carries one today, so this is closing the
    # shape rather than reporting a case that has happened.
    targets = sorted(p for p in directory.rglob("*")
                     if not p.is_dir() or p.is_symlink())
    if not targets:
        # An empty directory is not a clean result. Reporting success here would
        # make "nothing was checked" indistinguishable from "everything passed",
        # which is the failure this check exists to avoid.
        print(f"  FAIL   no files under {directory}")
        return False

    ok = True
    for target in targets:
        # Regular files only, checked before opening, and OSError caught per
        # file. read_bytes() on a FIFO blocks forever waiting for a writer, and a
        # dangling symlink raises out of the loop, abandoning every file after
        # it. This walks whatever is in the directory rather than a path it just
        # created, so both are reachable here -- the same hazards
        # verify-android-elf.py measured, in the same directory. A symlinked
        # directory reaches this line too, kept by the walk above so that it is
        # reported here rather than silently skipped.
        #
        # Named by the path below the directory, not by basename: a recursive
        # sweep can report two files of the same name from different depths, and
        # the Ruby pack ships several.
        name = target.relative_to(directory)
        try:
            if not stat.S_ISREG(target.stat().st_mode):
                print(f"  FAIL   {name} is not a regular file")
                ok = False
                continue
            carries = carries_unrunnable_shell(target.read_bytes())
        except OSError as e:
            print(f"  FAIL   {name}: {e}")
            ok = False
            continue
        if carries:
            print(f"  FAIL   {name} names {carries.decode()}")
            if carries != TERMUX_SH:
                print("         and that is not a prefix this file can rewrite. "
                      "SELinux refuses execve on anything under an app's data "
                      "directory, so no such path is runnable; the binary has to "
                      "be built or fetched with a shell outside one.")
            ok = False

    n = len(targets)
    print(f"  {n} file{'' if n == 1 else 's'} checked for a shell under "
          "any app's data directory")
    return ok


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("file", type=pathlib.Path, nargs="*",
                    help="file to rewrite in place")
    ap.add_argument("--check", type=pathlib.Path, metavar="DIR",
                    help="rewrite nothing; fail if any file under DIR names "
                         "Termux's prefix as a shell")
    args = ap.parse_args()

    if args.check is not None:
        if args.file:
            ap.error("--check reads a directory; it does not take files")
        return 0 if check(args.check) else 1

    if not args.file:
        ap.error("give a file to rewrite, or --check DIR to sweep one")

    # Every file, not the first failure: a build that stops on libgit.so hides
    # whether the other four moved too, and that is the difference between one
    # upstream change and five.
    ok = True
    for target in args.file:
        ok = patch(target) and ok
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
