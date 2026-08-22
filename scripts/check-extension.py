#!/usr/bin/env python3
"""Check one extracted extension against the VS Code version this build ships.

    check-extension.py <extension-dir> <VSCODE_VERSION-file>

Both things it looks at fail the same quiet way. An extension whose
engines.vscode is newer than the server is registered and then simply never
activates: no error, no log line, just a missing feature. And a glibc native
payload loads fine on a desktop and throws on first use under Bionic, long after
the build that shipped it went green.
"""

import json
import pathlib
import re
import sys

ELF_AARCH64 = 0xB7


def main(ext: pathlib.Path, version_file: pathlib.Path) -> int:
    target = tuple(int(x) for x in version_file.read_text().split()[0].split("."))

    manifest = json.loads((ext / "package.json").read_text())
    req = (manifest.get("engines") or {}).get("vscode", "")
    # Open VSX only ever states a floor here, as ^X.Y.Z or >=X.Y.Z.
    m = re.search(r"(\d+)\.(\d+)\.(\d+)", req)
    if m and target < tuple(int(g) for g in m.groups()):
        print(f"  FAIL   needs VS Code {req}, this build ships "
              f"{'.'.join(map(str, target))}; it would never activate")
        return 1
    print(f"  ok     engines.vscode {req or '(unset)'}")

    # Only aarch64 ELF payloads are worth reporting: x86-64 and Windows binaries
    # are dead weight the extension never reaches on this device, while an
    # aarch64 one will be loaded and has to be glibc-free to work.
    natives, damaged = [], []
    for f in sorted(ext.rglob("*")):
        # stat() and open() were both bare, and either can raise on a file this
        # walk merely passes over. An unreadable one ended the run in a
        # PermissionError with the remaining extensions unexamined -- and this
        # gate runs over every bundled extension in turn.
        try:
            if not f.is_file() or f.is_symlink():
                continue
            if f.suffix != ".node" and not f.stat().st_mode & 0o111:
                continue
            with f.open("rb") as fh:
                head = fh.read(20)
        except OSError as exc:
            damaged.append((f.relative_to(ext), str(exc)))
            continue
        if head[:4] != b"\x7fELF":
            continue
        # Something claiming the ELF magic and stopping before e_machine is a
        # truncated or half-written file, not a format to skip quietly.
        # head[18] indexed it directly, so four bytes of magic raised IndexError
        # straight out of main() with no FAIL line naming the file. The sibling
        # verify-android-elf.py reports "FAIL <name>: index out of range" for the
        # same four bytes and carries on; two readers of one header should not
        # disagree about what a damaged one looks like.
        if len(head) < 20:
            damaged.append((f.relative_to(ext),
                            f"{len(head)} bytes, truncated before e_machine"))
            continue
        if head[18] == ELF_AARCH64:
            natives.append(f.relative_to(ext))
    for rel in natives:
        print(f"  note   carries an aarch64 native: {rel}")
    for rel, why in damaged:
        print(f"  FAIL   {rel} could not be read as a binary: {why}")

    return 1 if damaged else 0


if __name__ == "__main__":
    if len(sys.argv) != 3:
        print("usage: check-extension.py <extension-dir> <VSCODE_VERSION-file>",
              file=sys.stderr)
        sys.exit(2)
    sys.exit(main(pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2])))
