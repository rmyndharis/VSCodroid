#!/usr/bin/env python3
"""Check that an ELF binary can actually load on Android.

    verify-android-elf.py <file> [--lib-dir DIR]...
    verify-android-elf.py --dir DIR [--lib-dir DIR]...
    verify-android-elf.py --tree DIR
    verify-android-elf.py --self-test

Four things of one file, named directly or reached by `--dir`, each of which
fails the same quiet way at runtime (the file is present, the build is green,
and the process dies or the addon refuses to load with a message nobody sees):

  * aarch64, so it matches the only ABI this app ships;
  * every DT_NEEDED library is one Bionic provides or one we bundle, since a
    glibc or musl dependency has no loader here;
  * every LOAD segment aligned to at least 16 KB, which Android 16 requires and
    NDK 27 does not do by default;
  * PT_INTERP, when there is one, names Android's loader. An aarch64 executable
    naming any other program interpreter came out of a glibc toolchain and
    nothing on the device can start it, and the second question stopped
    catching that on its own the day the glibc shim gave every glibc soname a
    file to resolve to.

`--tree` asks two of those of a whole directory tree, alignment and PT_INTERP,
because they are the ones a tree this app merely packages can be held to. What
tree mode is for is written at [alignment_sweep]; the short version is that
the first two above cannot be asked of such a tree without failing it for
files that are correct.

`--self-test` hands verify() a synthetic executable naming a glibc loader and
two it must accept, since no file this repository places can make the refusal
fire and a refusal that has stopped firing looks exactly like a clean tree.

Pure Python on purpose: the NDK's readelf is not available everywhere this runs,
and having one implementation means the addon build and the runtime download
cannot drift apart in what they consider acceptable.
"""

import argparse
import os
import pathlib
import stat
import struct
import sys

ELF_MAGIC = b"\x7fELF"
EM_AARCH64 = 0xB7
# A relocatable object, which is the one ELF the loader never maps: it has no
# LOAD segment to align, and the alignment sweep would read that absence as an
# alignment of zero. Python's build ships one, usr/lib/python3.x/config-*/
# python.o, and it is the only one in the packaged tree (measured).
ET_REL = 1
PT_LOAD, PT_DYNAMIC, PT_INTERP = 1, 2, 3
DT_NULL, DT_NEEDED, DT_STRTAB, DT_STRSZ = 0, 1, 5, 10
MIN_ALIGN = 16384

# The only program interpreter a binary built for Android names. Any other value
# in an aarch64 executable says it came out of a foreign toolchain, which is what
# the sweep below reads it as; a binary naming a glibc loader is unstartable here
# whichever way something tries, since the path is absent from the device and the
# loader indirection that does start a payload under filesDir supplies this one.
ANDROID_INTERP = "/system/bin/linker64"

# The aarch64 executables in the packaged tree that name a glibc loader, kept by
# path because they ship and cannot run.
#
# Both arrive inside @microsoft/mxc-sdk, which the server tree references from
# out/server-main.js and out/vs/platform/agentHost/node/agentHostMain.js, and
# both are built for glibc: PT_INTERP is /lib/ld-linux-aarch64.so.1, a path
# Android does not have. 4.53 MiB that no code path here can start. Nothing in
# this repository builds that tree -- it is fetched whole from a server-<version>
# release -- so pruning them belongs to whoever owns the fetch, and until then
# naming them here is what stops a THIRD one arriving unnoticed on the next VS
# Code bump. Measured 2026-08-23: 53 aarch64 executables in the packaged trees
# name /system/bin/linker64 and exactly these two do not.
FOREIGN_INTERP_ALLOWED = {
    "vscode-reh/node_modules/@microsoft/mxc-sdk/bin/arm64/lxc-exec",
    "vscode-reh/node_modules/@microsoft/mxc-sdk/bin/arm64/linux-test-proxy",
}

# Provided by the system on every supported device, so they never need bundling.
BIONIC = {
    "libc.so", "libm.so", "libdl.so", "liblog.so", "libandroid.so",
    "libz.so", "libstdc++.so", "libnetd_client.so",
}


class NotAnElf(Exception):
    pass


def read_elf(path: pathlib.Path):
    # Regular files only, checked before opening. read_bytes() on a FIFO blocks
    # forever waiting for a writer, and on a character device reads without end --
    # measured: a named pipe called x.so hung the sweep with no output and no
    # timeout. --dir globs whatever is in the directory, so this is reachable in a
    # way the single-file callers never were: they pass a path they just created.
    st = path.stat()
    if not stat.S_ISREG(st.st_mode):
        raise NotAnElf(f"{path.name} is not a regular file")
    data = path.read_bytes()
    if data[:4] != ELF_MAGIC:
        raise NotAnElf(f"{path.name} is not an ELF file")
    if data[4] != 2:
        raise NotAnElf(f"{path.name} is not 64-bit")

    e_type = struct.unpack_from("<H", data, 16)[0]
    machine = struct.unpack_from("<H", data, 18)[0]
    phoff, = struct.unpack_from("<Q", data, 32)
    phentsize, phnum = struct.unpack_from("<HH", data, 54)

    loads, dynamic, interp = [], None, None
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
        elif p_type == PT_INTERP:
            # A NUL-terminated path. Shared objects have no such segment, so
            # None here means "not an executable" rather than "unknown".
            interp = data[p_offset:p_offset + p_filesz].split(b"\0")[0].decode(
                "ascii", "replace")

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

    return machine, e_type, needed, [a for *_, a in loads], interp


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
        # exists() is inside the try, not above it. pathlib swallows only
        # ENOENT/ENOTDIR/EBADF/ELOOP and re-raises the rest, so an unsearchable
        # PARENT directory made exists() itself throw EACCES past the guard below
        # -- a traceback from a function whose whole purpose is to report the
        # problem instead of one.
        try:
            if not d.exists():
                print(f"  note   --lib-dir {d} does not exist; nothing resolves from it")
                continue
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
        machine, _e_type, needed, aligns, interp = read_elf(path)
    # ValueError covers the two that read_elf can raise out of a corrupt dynamic
    # string table: bytes.index() when a DT_NEEDED offset has no NUL after it, and
    # .decode() on a non-UTF-8 name (UnicodeDecodeError is a ValueError). Those
    # raise sites are older than this branch, but --dir is not: in a sweep an
    # escape does not just fail one file, it abandons every binary after it and
    # prints no count -- which is the exact failure the per-file catch exists to
    # prevent. Measured on a binary with DT_STRSZ patched to 0.
    except (NotAnElf, IndexError, struct.error, OSError, ValueError) as e:
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

    # The question --tree asks, and the two modes here did not. The DT_NEEDED
    # test above used to catch a glibc build on its own, because libc.so.6 was
    # a name nothing provided; build-glibc-shim.sh now writes libc.so.6,
    # libdl.so.2, libpthread.so.0 and ld-linux-aarch64.so.1 into usr/lib, and
    # every download script and the jniLibs sweep pass that directory as a
    # --lib-dir. An aarch64 executable out of a glibc toolchain therefore
    # resolves every library it names and fails at exec on the device instead,
    # with ENOENT for a loader path Android does not have, indistinguishable
    # from the tool never having been installed. A shared object has no
    # PT_INTERP and is not the subject. Nothing on disk moves: measured over
    # jniLibs, assets/usr, both toolchain packs and rg, 306 aarch64 ELF files
    # and not one naming a loader other than Android's.
    check(interp is None or interp == ANDROID_INTERP,
          f"program interpreter is {interp}" if interp
          else "no program interpreter: a shared object, or static",
          "a glibc build, which nothing on this device can start")

    return not failed


def synthetic_elf(interp) -> bytes:
    """The smallest aarch64 ELF verify() will read: one 16 KB-aligned LOAD, no
    dynamic section, and a PT_INTERP naming `interp`, or none when it is None."""
    ehsize, phentsize = 64, 56
    phnum = 2 if interp else 1
    data_off = ehsize + phentsize * phnum
    path = interp.encode() + b"\0" if interp else b""
    size = data_off + len(path)
    phdrs = struct.pack("<IIQQQQQQ", PT_LOAD, 5, 0, 0, 0, size, size, MIN_ALIGN)
    if interp:
        phdrs += struct.pack("<IIQQQQQQ", PT_INTERP, 4, data_off, data_off, data_off,
                             len(path), len(path), 1)
    ehdr = ELF_MAGIC + bytes([2, 1, 1, 0]) + b"\0" * 8 + struct.pack(
        "<HHIQQQIHHHHHH", 2, EM_AARCH64, 1, 0, ehsize, 0, 0,
        ehsize, phentsize, phnum, 0, 0, 0)
    return ehdr + phdrs + path


def self_test() -> int:
    """Hand verify() the executable it exists to refuse, and two it must accept.

    Every real binary this repository places passes, so the refusal has no
    file in the tree to fire on, and a rule that has stopped firing prints
    exactly what a clean tree prints.
    """
    import tempfile

    cases = (("glibc", "/lib/ld-linux-aarch64.so.1", False),
             ("android", ANDROID_INTERP, True),
             ("shared", None, True))
    with tempfile.TemporaryDirectory() as tmp:
        for name, interp, expected in cases:
            path = pathlib.Path(tmp) / f"lib{name}.so"
            path.write_bytes(synthetic_elf(interp))
            print(f"  {path.name}")
            if verify(path, set()) != expected:
                print(f"  FAIL   self-test: PT_INTERP {interp} was "
                      f"{'accepted' if expected is False else 'refused'}")
                return 1
    print("  ok     self-test: a foreign program interpreter is refused")
    return 0


def alignment_sweep(root: pathlib.Path) -> int:
    """Ask two questions of every ELF under [root]: can it be mapped, and can it
    be started.

    The population this exists for is the packaged asset tree, and it was
    covered by nothing. Every download script checks the file it just placed,
    and the Gradle sweep covers `jniLibs/` as a directory, but the ~150 aarch64
    binaries under `assets/` are examined only at the moment they are fetched
    and never again. A cache restore, a re-run of `package-assets.sh`, or a
    server tree fetched whole from a release all reach packaging with nothing
    having asked, and a misaligned addon is a `dlopen` failure on an Android 16
    device with no sign of it at build time.

    Segment alignment and PT_INTERP, and the question it drops is dropped for
    one reason: a tree we package is not a tree we built. It legitimately
    carries payloads for platforms this app never runs (upstream ships x86_64
    copies of ripgrep beside the arm64 ones) and dependencies nothing here ever
    loads (both copies of `kerberos.node` name `libstdc++.so.6`, measured, and
    no code path opens them). Asking DT_NEEDED of that tree would fail a build
    that is correct, and it is asked separately by
    `gen-glibc-forwarders.py --scan`, which knows about the shim.

    The two that are asked are different in kind: each is a property of the file
    alone, whoever built it, and a wrong answer is only ever a defect. Alignment
    decides whether the file can be mapped at all on Android 16. PT_INTERP
    decides whether an aarch64 executable was built for this platform: anything
    naming a loader other than Android's came out of a glibc toolchain, so
    nothing in the packaged tree can start it. A shared object has no PT_INTERP
    and is not the subject.

    A tree with no aarch64 ELF in it fails rather than passes, for the reason
    `--dir` refuses an empty directory: "nothing was checked" must not read like
    "everything passed".

    The same reason fails a FOREIGN_INTERP_ALLOWED entry that won for no file.
    That allowlist names paths in a tree fetched whole from a release, so a VS
    Code bump can move either one silently, and the entry then waves through
    whatever later arrives at that path. An entry earns its place by matching a
    real file or it goes.
    """
    checked = 0
    # By path rather than a counter: a file can fail both questions below, and a
    # counter bumped by each branch reported more rejections than there were
    # files, on the one line a CI reader scans.
    rejected = set()
    skipped = []
    # Every path that actually turned up naming a foreign loader, so an entry in
    # FOREIGN_INTERP_ALLOWED that stopped matching can be reported below.
    foreign_seen = set()
    # os.walk rather than rglob: it does not follow directory symlinks, and the
    # trees this runs over are node_modules, where a symlink pointing at an
    # ancestor is ordinary rather than exotic.
    for parent, _dirs, names in os.walk(root):
        for name in sorted(names):
            path = pathlib.Path(parent) / name
            try:
                if path.is_symlink() or not path.is_file():
                    continue
                # Four bytes rather than read_elf's whole file. Most of a tree
                # this size is JavaScript, and reading all of it to find out
                # would cost more than the check.
                with path.open("rb") as handle:
                    if handle.read(4) != ELF_MAGIC:
                        continue
            except OSError as e:
                print(f"  FAIL   {path}: {e}")
                rejected.add(path)
                continue
            try:
                machine, e_type, _needed, aligns, interp = read_elf(path)
            except (NotAnElf, IndexError, struct.error, OSError, ValueError) as e:
                # It claimed to be an ELF in its first four bytes and then could
                # not be read as one, which is a broken file rather than a file
                # this mode has no opinion about.
                print(f"  FAIL   {path}: {e}")
                rejected.add(path)
                continue
            if machine != EM_AARCH64 or e_type == ET_REL:
                skipped.append((path, "another ABI" if machine != EM_AARCH64
                                else "relocatable, never mapped"))
                continue
            checked += 1
            worst = min(aligns, default=0)
            if worst < MIN_ALIGN:
                print(f"  FAIL   {path}")
                print(f"         LOAD segments aligned to {worst:#x}; "
                      f"Android 16 needs {MIN_ALIGN:#x}")
                rejected.add(path)
            # The second question this mode can answer without the false alarms
            # the docstring above explains. An aarch64 executable naming a
            # non-Android loader is a glibc build that arrived in a tree meant
            # for this device: dead weight nothing here can start. Not for the
            # ENOENT an execve of it would return -- this tree is extracted to
            # filesDir, where SELinux refuses execve before any interpreter is
            # consulted, and the loader indirection that does start a payload
            # there mmaps it under /system/bin/linker64, which cannot satisfy a
            # glibc binary either.
            if interp is not None and interp != ANDROID_INTERP:
                rel = path.relative_to(root).as_posix()
                foreign_seen.add(rel)
                if rel not in FOREIGN_INTERP_ALLOWED:
                    print(f"  FAIL   {path}")
                    print(f"         PT_INTERP is {interp}: a glibc build, which "
                          f"nothing on this device can start")
                    rejected.add(path)

    if not checked:
        print(f"  FAIL   no aarch64 ELF under {root}; nothing was examined")
        return 1
    # An allowlist entry has to earn its place by winning for a real file. The
    # two here are paths inside a tree this repository does not build and fetches
    # whole, so a VS Code bump can move or drop either one without a word; the
    # entry then sits there waving through whatever later lands at that exact
    # path, which is the one thing the sweep is looking for. Reported after the
    # walk rather than at the entry, because only the whole walk knows what
    # matched. Kept out of `rejected`: that set is files in the tree, and this is
    # a line in this file.
    stale = sorted(FOREIGN_INTERP_ALLOWED - foreign_seen)
    for rel in stale:
        print(f"  FAIL   {rel}")
        print(f"         allowed as a known glibc build and no longer in the "
              f"tree; drop it from FOREIGN_INTERP_ALLOWED")
    # The skipped files by name, not by count. A count says four files were not
    # examined and nothing about what they are, so 11.9 MiB of x86-64 ripgrep and
    # tgrep sat in an arm64 APK behind a reassuring number for as long as this
    # mode has existed. Naming them makes a new foreign-ABI payload visible on
    # the build that first carries it, which is the VS Code bump that fetched it.
    for path, why in skipped:
        print(f"  skip   {path} ({why})")
    stale_note = ""
    if stale:
        plural = "y" if len(stale) == 1 else "ies"
        stale_note = f", {len(stale)} allowlist entr{plural} matching nothing"
    print(f"  {'FAIL  ' if rejected or stale else 'ok    '} {checked} aarch64 binaries under {root}, "
          f"{len(rejected)} rejected, {len(skipped)} skipped as another ABI or not loadable"
          f"{stale_note}")
    return 1 if rejected or stale else 0


def main() -> int:
    ap = argparse.ArgumentParser()
    # Optional so --dir can stand in for it. The nine existing callers each pass
    # exactly one path and are unaffected.
    ap.add_argument("file", type=pathlib.Path, nargs="?")
    # --dir is what the packaging step uses to ask about the directory that
    # actually ships, and it exists because per-file checking left a gap: every
    # download script checks the binary it just installed, so a binary restored
    # from a build cache, or left by an earlier fetch, used to go unexamined from
    # then on. This checker is hashed into three CI cache keys (build.yml's
    # `downloads-` and `assets-`, release.yml's `downloads-`), the same three
    # that carry verify-server-tree.py. So tightening it changes those keys,
    # the caches miss, and every binary is fetched and examined again under
    # the stricter rule. Re-measure with:
    #     grep -c 'verify-android-elf.py' .github/workflows/*.yml
    ap.add_argument("--dir", type=pathlib.Path,
                    help="check every *.so in this directory instead of one file")
    # --tree is the same idea one level out: --dir asks about a directory, this
    # asks about a tree, and it asks less of it. See alignment_sweep for which
    # question it keeps and why the other two cannot be asked there.
    ap.add_argument("--tree", type=pathlib.Path,
                    help="check LOAD alignment on every aarch64 ELF under this tree")
    ap.add_argument("--lib-dir", type=pathlib.Path, action="append", default=[],
                    help="directory whose libraries ship with the app")
    ap.add_argument("--self-test", action="store_true",
                    help="check that a glibc-built executable is refused")
    args = ap.parse_args()

    if args.self_test:
        return self_test()

    if args.tree is not None:
        return alignment_sweep(args.tree)

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
        ap.error("give a file, --dir for a directory, --tree for a whole tree, "
                 "or --self-test")

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
