#!/usr/bin/env python3
"""Points a bundled OpenSSH helper's ssh program path at PATH lookup.

    patch-ssh-program.py <file>...
    patch-ssh-program.py --check <directory>

Termux compiles _PATH_SSH_PROGRAM as

    /data/data/com.termux/files/usr/bin/ssh

into OpenSSH client helpers (`scp` and `sftp`), which hand that constant to
`execvp()`. Because the path contains `/`, `execvp()` never searches `PATH`, and
Termux's application data directory does not exist inside this app (and SELinux
refuses `execve()` on any path under app_data_file anyway), so `scp` and `sftp`
fail with ENOENT before `ssh` ever starts.

Replacing that C string constant in place with `ssh` padded with NUL bytes to
keep the original 40-byte length causes `execvp("ssh", ...)` to search `PATH`
and execute `usr/bin/ssh` -> `nativeLibraryDir/libssh.so`. Every reference in
the shipped Termux `scp` and `sftp` binaries lands on byte 0 of the constant,
and no reference lands mid-string.

Exactly one occurrence is required when patching; anything else stops the
build cleanly. Idempotent so an already-patched binary succeeds on re-run.
`--check <directory>` walks every regular file under `<directory>` and fails if
any of them still names `.../usr/bin/ssh` under any app data directory.
"""

import argparse
import pathlib
import re
import stat
import sys

TERMUX_SSH = b"/data/data/com.termux/files/usr/bin/ssh"
BARE_SSH = b"ssh"

OLD = TERMUX_SSH + b"\0"
NEW = BARE_SSH + b"\0" * (len(TERMUX_SSH) - len(BARE_SSH) + 1)

if len(NEW) != len(OLD) or not NEW.endswith(b"\0"):
    raise SystemExit(
        f"patch-ssh-program.py: invalid NUL-padded replacement ({len(NEW)} vs {len(OLD)} bytes)"
    )

# End the match at "ssh" so sibling OpenSSH binaries such as ssh-keygen,
# ssh-add, ssh-agent, ssh-keyscan, sshd, or config filenames do not match.
SSH_NAME_END = rb"(?![\w.+/-])"

TERMUX_SSH_RE = re.compile(re.escape(TERMUX_SSH) + SSH_NAME_END)
APP_DATA_SSH = re.compile(
    rb"/data/(?:data|user(?:_de)?/[0-9]+)/[A-Za-z0-9._]+/files/usr/bin/ssh" + SSH_NAME_END
)


def carries_unrunnable_ssh_program(data: bytes) -> "bytes | None":
    """Return the offending ssh program path if one under app data is present."""
    hit = APP_DATA_SSH.search(data)
    return hit.group(0) if hit else None


def patch(path: pathlib.Path) -> bool:
    """Rewrite Termux's hardcoded _PATH_SSH_PROGRAM constant to NUL-padded 'ssh'."""
    try:
        data = path.read_bytes()
    except OSError as e:
        print(f"  ERROR: {path}: {e}", file=sys.stderr)
        return False

    found = len(TERMUX_SSH_RE.findall(data))
    if found == 0:
        unrunnable = carries_unrunnable_ssh_program(data)
        if unrunnable:
            print(f"  ERROR: {path} names unrunnable app-data ssh path {unrunnable.decode()}",
                  file=sys.stderr)
            return False
        if NEW in data:
            print(f"  {path.name}: ssh program -> ssh (PATH lookup, already applied)")
            return True
        print(f"  ERROR: {path} names neither Termux's ssh path nor the patched marker",
              file=sys.stderr)
        print(f"    expected one '{TERMUX_SSH.decode()}' to rewrite, or the", file=sys.stderr)
        print("    NUL-padded 'ssh' constant already in place.", file=sys.stderr)
        return False

    if found > 1:
        print(f"  ERROR: {path} spells Termux's ssh path {found} times", file=sys.stderr)
        print("    Refusing partial rewrite.", file=sys.stderr)
        return False

    if data.count(OLD) != 1:
        print(f"  ERROR: {path} carries Termux's ssh path without a single NUL-terminated match",
              file=sys.stderr)
        return False

    path.write_bytes(data.replace(OLD, NEW))

    after = path.read_bytes()
    if len(after) != len(data) or carries_unrunnable_ssh_program(after) or NEW not in after:
        print(f"  ERROR: {path} verification after rewrite failed", file=sys.stderr)
        return False

    print(f"  {path.name}: ssh program -> ssh (PATH lookup; was Termux's prefix)")
    return True


def check(directory: pathlib.Path) -> bool:
    """Fail if any regular file under directory still names an app-data ssh path."""
    targets = sorted(
        p for p in directory.rglob("*") if not p.is_dir() or p.is_symlink()
    )
    if not targets:
        print(f"  FAIL   no files under {directory}")
        return False

    ok = True
    for target in targets:
        name = target.relative_to(directory)
        try:
            if not stat.S_ISREG(target.stat().st_mode):
                print(f"  FAIL   {name} is not a regular file")
                ok = False
                continue
            carries = carries_unrunnable_ssh_program(target.read_bytes())
        except OSError as e:
            print(f"  FAIL   {name}: {e}")
            ok = False
            continue
        if carries:
            print(f"  FAIL   {name} names {carries.decode()}")
            ok = False

    n = len(targets)
    print(f"  {n} file{'' if n == 1 else 's'} checked for an app-data ssh program path")
    return ok


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("file", type=pathlib.Path, nargs="*",
                    help="file to rewrite in place")
    ap.add_argument("--check", type=pathlib.Path, metavar="DIR",
                    help="rewrite nothing; fail if any file under DIR names "
                         "an app-data ssh program path")
    args = ap.parse_args()

    if args.check is not None:
        if args.file:
            ap.error("--check reads a directory; it does not take files")
        return 0 if check(args.check) else 1

    if not args.file:
        ap.error("give a file to rewrite, or --check DIR to sweep one")

    ok = True
    for target in args.file:
        ok = patch(target) and ok
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
