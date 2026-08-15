#!/usr/bin/env python3
"""Check that an ELF binary can actually load on Android.

    verify-android-elf.py <file> [--lib-dir DIR]...

Three things, each of which fails the same quiet way at runtime — the file is
present, the build is green, and the process dies or the addon refuses to load
with a message nobody sees:

  * aarch64, so it matches the only ABI this app ships;
  * every DT_NEEDED library is one Bionic provides or one we bundle, since a
    glibc or musl dependency has no loader here;
  * every LOAD segment aligned to at least 16 KB, which Android 16 requires and
    NDK 27 does not do by default.

Pure Python on purpose: the NDK's readelf is not available everywhere this runs,
and having one implementation means the addon build and the runtime download
cannot drift apart in what they consider acceptable.
"""

import argparse
import pathlib
import struct
import sys

ELF_MAGIC = b"\x7fELF"
EM_AARCH64 = 0xB7
PT_LOAD, PT_DYNAMIC = 1, 2
DT_NULL, DT_NEEDED, DT_STRTAB, DT_STRSZ = 0, 1, 5, 10
MIN_ALIGN = 16384

# Provided by the system on every supported device, so they never need bundling.
BIONIC = {
    "libc.so", "libm.so", "libdl.so", "liblog.so", "libandroid.so",
    "libz.so", "libstdc++.so", "libnetd_client.so",
}


class NotAnElf(Exception):
    pass


def read_elf(path: pathlib.Path):
    data = path.read_bytes()
    if data[:4] != ELF_MAGIC:
        raise NotAnElf(f"{path.name} is not an ELF file")
    if data[4] != 2:
        raise NotAnElf(f"{path.name} is not 64-bit")

    machine = struct.unpack_from("<H", data, 18)[0]
    phoff, = struct.unpack_from("<Q", data, 32)
    phentsize, phnum = struct.unpack_from("<HH", data, 54)

    loads, dynamic = [], None
    for i in range(phnum):
        off = phoff + i * phentsize
        p_type, = struct.unpack_from("<I", data, off)
        p_offset, p_vaddr = struct.unpack_from("<QQ", data, off + 8)
        p_filesz, = struct.unpack_from("<Q", data, off + 32)
        p_align, = struct.unpack_from("<Q", data, off + 48)
        if p_type == PT_LOAD:
            loads.append((p_vaddr, p_offset, p_filesz, p_align))
        elif p_type == PT_DYNAMIC:
            dynamic = (p_offset, p_filesz)

    def to_offset(vaddr):
        for v, o, sz, _ in loads:
            if v <= vaddr < v + sz:
                return o + (vaddr - v)
        return None

    needed = []
    if dynamic:
        d_off, d_size = dynamic
        entries, pos = [], d_off
        while pos < d_off + d_size:
            tag, val = struct.unpack_from("<qQ", data, pos)
            pos += 16
            if tag == DT_NULL:
                break
            entries.append((tag, val))
        strtab = next((v for t, v in entries if t == DT_STRTAB), None)
        strsz = next((v for t, v in entries if t == DT_STRSZ), 0)
        base = to_offset(strtab) if strtab is not None else None
        if base is not None:
            table = data[base:base + strsz]
            for tag, val in entries:
                if tag == DT_NEEDED:
                    end = table.index(b"\0", val)
                    needed.append(table[val:end].decode())

    return machine, needed, [a for *_, a in loads]


def resolvable_names(lib_dirs: list):
    """Library names the shipped directories provide, or None if one is unusable.

    Read once for the whole run rather than per file: with --dir this was walking
    every --lib-dir again for each binary, and an unreadable one raised out of the
    loop with a traceback -- the same class the per-file catch below closes, one
    line further up.

    A directory that does not exist is reported and skipped rather than refused.
    Nine callers pass --lib-dir paths that are legitimately absent at the point
    they run, so failing here would break builds that are correct; but skipping it
    narrows what can resolve, and that is worth a line rather than silence.
    """
    names = set()
    for d in lib_dirs:
        if not d.exists():
            print(f"  note   --lib-dir {d} does not exist; nothing resolves from it")
            continue
        try:
            names |= {p.name for p in d.iterdir()}
        except OSError as e:
            print(f"  FAIL   --lib-dir {d} could not be read  {e}")
            return None
    return names


def verify(path: pathlib.Path, bundled: set) -> bool:
    """Check one binary. Prints a line per property; returns True if all hold."""
    # OSError covers the states --dir can hand this that a single-file caller never
    # could: a dangling symlink, a directory whose name ends in .so, a file the
    # build cannot read. Without it the loop dies on the first one, every later
    # binary goes unexamined, and no FAIL line is printed at all -- so a caller
    # that tells its reader "the FAIL line above names the file" would be lying.
    # Caught per file so the sweep finishes and still fails.
    try:
        machine, needed, aligns = read_elf(path)
    except (NotAnElf, IndexError, struct.error, OSError) as e:
        # Named here rather than left to the caller. NotAnElf and OSError carry the
        # path in their own text but IndexError and struct.error do not, so a
        # truncated binary reported "unpack_from requires a buffer of at least 40
        # bytes" and nothing else -- fine in a sweep that prints a header per file,
        # useless in a directory holding exactly one, which is precisely the tree
        # the packaging gate was widened to reach.
        print(f"  FAIL   {path.name}: {e}")
        return False

    failed = False

    def check(ok, label, detail=""):
        nonlocal failed
        print(f"  {'ok    ' if ok else 'FAIL  '} {label}{'' if ok else '  ' + detail}")
        failed = failed or not ok

    check(machine == EM_AARCH64, "aarch64", f"e_machine = {machine:#x}")

    missing = [lib for lib in needed if lib not in BIONIC and lib not in bundled]
    check(not missing, f"{len(needed)} linked libraries resolvable",
          "not provided by Bionic and not bundled: " + ", ".join(missing))

    worst = min(aligns, default=0)
    check(worst >= MIN_ALIGN, f"LOAD segments aligned to {worst:#x}",
          f"Android 16 needs {MIN_ALIGN:#x}")

    return not failed


def main() -> int:
    ap = argparse.ArgumentParser()
    # Optional so --dir can stand in for it. The nine existing callers each pass
    # exactly one path and are unaffected.
    ap.add_argument("file", type=pathlib.Path, nargs="?")
    # --dir is what the packaging step uses to ask about the directory that
    # actually ships, and it exists because per-file checking left a gap: every
    # download script checks the binary it just installed, so a binary restored
    # from a build cache, or left by an earlier fetch, used to go unexamined from
    # then on. This checker is also in neither CI cache key -- 0 hits in both,
    # against 1 for verify-server-tree.py -- so tightening it does not by itself
    # cause a cached binary to be looked at again.
    ap.add_argument("--dir", type=pathlib.Path,
                    help="check every *.so in this directory instead of one file")
    ap.add_argument("--lib-dir", type=pathlib.Path, action="append", default=[],
                    help="directory whose libraries ship with the app")
    args = ap.parse_args()

    if args.dir is not None:
        targets = sorted(args.dir.glob("*.so"))
        if not targets:
            # An empty directory is not a clean result. Reporting success here
            # would make "nothing was checked" indistinguishable from "everything
            # passed", which is the failure this whole check exists to avoid.
            print(f"  FAIL   no *.so in {args.dir}")
            return 1
    elif args.file is not None:
        targets = [args.file]
    else:
        ap.error("give a file, or --dir to check a whole directory")

    bundled = resolvable_names(args.lib_dir)
    if bundled is None:
        return 1

    # Keyed on "is this a sweep", not on how many files the sweep happened to
    # find. Both lines used to be suppressed at len(targets) == 1, so a directory
    # holding a single bad binary printed neither its name nor a count -- and one
    # binary in jniLibs is not a corner case, it is the state the packaging gate
    # was widened to cover.
    sweep = args.dir is not None
    ok = True
    for target in targets:
        if sweep:
            print(f"  {target.name}")
        ok = verify(target, bundled) and ok
    if sweep:
        n = len(targets)
        print(f"  {n} binar{'y' if n == 1 else 'ies'} checked")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
