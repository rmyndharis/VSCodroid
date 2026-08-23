#!/usr/bin/env python3
"""Generate the versioned stub libraries a glibc-built addon needs to load here.

    gen-glibc-forwarders.py <addon.node|.so> [more...] --out <dir>

A prebuilt linux-arm64 addon is machine code Android can execute -- same ISA,
same calling convention -- and the only thing refusing it is the dynamic linker.
Its imports are not plain names but versioned bindings to specific libraries:
`malloc@GLIBC_2.17` looked up in `libc.so.6`. Android has no libc.so.6, and an
empty stub with that soname does not help, because the lookup carries a version
the stub does not define.

So this writes stubs that do define them. For each library the addon names, it
emits a shared object with that soname exporting exactly the symbols the addon
asks for, at exactly the versions it asks for, each one a tail branch to Bionic's
implementation of the same name:

    __asm__("__fwd_malloc: adrp x16, ptr\\n ldr x16, [x16, :lo12:ptr]\\n br x16");
    __asm__(".symver __fwd_malloc,malloc@@GLIBC_2.17");

The jump is indirect, through a pointer filled at load time from Bionic's libc,
and that indirection is not decoration. A direct `b malloc` inside a library
that itself exports `malloc` binds to its own definition -- the trampoline
branches to itself, and the addon hangs on the first allocation instead of
failing with something legible. Going through a pointer resolved against libc by
name is what makes the target unambiguous.

Either way no function signature is needed anywhere in this file. `br` is a tail
call: arguments stay in their registers, the return goes straight to the
original caller, and the trampoline never appears on the stack. x16 is the
register the ABI reserves for exactly this kind of thunk. Three instructions
forward any signature, including varargs.

Symbols Bionic does not have at all are supplied by glibc-shim.c, which these
stubs link against -- the two halves are meant to be built together by
build-glibc-shim.sh.

What this is not: an emulator, or a glibc. It works where the two libcs agree on
the ABI, which is most of it. Where they disagree -- a struct laid out
differently, a function whose error semantics diverge -- an addon will load and
then misbehave somewhere this cannot see. Anything depending on it needs testing
on a device, not trust.
"""

import argparse
import pathlib
import re
import struct
import sys

# Bionic folded these into libc long ago, so a name that used to live in one of
# them resolves from libc now. The stubs still have to exist under the old names
# because that is what the addon's DT_NEEDED asks for.
BIONIC_LIBS = ["-lc", "-lm", "-ldl", "-llog"]

# Provided by whatever loads the addon rather than by any library: Node defines
# these itself and resolves them at dlopen. Forwarding them would bind to
# nothing.
#
# uv_ belongs with the other two for the same reason and was measured the same
# way: node links libuv in and re-exports it, so the bundled runtime's dynamic
# symbol table carries 318 uv_ names alongside 145 napi_ ones, uv_thread_create
# among them. An addon importing one of those is asking the process that loads
# it, not a library on disk.
RUNTIME_PROVIDED = re.compile(r"^(napi_|node_api_|uv_)")

# Always optional. The linker emits references to them for instrumentation that
# is not present, and a forwarder would fail to link.
WEAK_OPTIONAL = {
    "_ITM_registerTMCloneTable", "_ITM_deregisterTMCloneTable",
    "__gmon_start__", "__cxa_finalize", "__cxa_atexit",
}

# An object needing one of these cannot be helped by forwarding, and saying so
# is more useful than emitting stubs that abort later.
#
# libstdc++ is GNU's C++ standard library; Android ships LLVM's libc++. They are
# not two spellings of the same thing -- the mangled names differ
# (std::__cxx11:: against std::__1::), so there is nothing to forward to, and the
# object layouts differ too, so even name-matching entries would corrupt. Making
# these load means shipping a real libstdc++ for aarch64 Android, which is a
# different and much larger undertaking.
UNSUPPORTED_LIBS = {"libstdc++.so.6"}

# Objects that legitimately reach the "cannot help" verdict in a correct tree.
#
# Every other addon that needs libstdc++ is one this project rebuilds for Bionic
# (node-pty, @parcel/watcher, @vscode/sqlite3, all linked -static-libstdc++ by
# build-native-addons.sh) or replaces outright (@vscode/spdlog, whose .node that
# script deletes). So in a tree that has been through the overlay, kerberos is
# the only one left, and it is knowingly unfixable rather than merely unfixed.
#
# Anything else appearing here means the packaged tree still carries the upstream
# glibc build of an addon, which loads on no Android device. Named individually
# because a count would be satisfied by the wrong four.
TOLERATED_BLOCKED = {"kerberos.node"}

# The glibc loader appears in DT_NEEDED of objects built against it. Bionic's
# linker is already the one running, so a stub carrying the name is enough to
# satisfy the entry.
LOADER_SONAME = "ld-linux-aarch64.so.1"

SHT_DYNSYM, SHT_GNU_VERNEED, SHT_GNU_VERSYM = 11, 0x6FFFFFFE, 0x6FFFFFFF
SHT_GNU_VERDEF = 0x6FFFFFFD
STT_FUNC, STT_OBJECT = 2, 1
STT_NOTYPE = 0
TYPE_NAMES = {STT_NOTYPE: "STT_NOTYPE", STT_OBJECT: "STT_OBJECT", STT_FUNC: "STT_FUNC"}
STB_WEAK = 2

# Data imports cannot be a branch -- the addon reads the storage itself, so the
# stub has to own a variable of the right type and fill it with Bionic's value
# before anything runs. Only the ones that actually turn up are listed; a new one
# is reported rather than guessed at, because guessing a type here means memory
# corruption rather than a link error.
# Provided by the dynamic linker rather than by any library, so dlsym cannot find
# them by name -- but a stub linked against libdl can take their address the
# ordinary way. Everything else goes through dlsym.
LINKER_PROVIDED = {
    "dlopen", "dlsym", "dlclose", "dlerror", "dladdr", "dlvsym",
    "dl_iterate_phdr", "dl_unwind_find_exidx",
}

# Symbols Bionic has, but whose arguments it lays out differently from glibc.
# Forwarding one of these does not fail -- it writes the right bytes to the wrong
# offsets and execution continues, so the damage appears somewhere else with
# nothing to connect it back. These point at translating wrappers in
# glibc-shim.c instead of at Bionic.
#
# Every entry here was measured on both sides -- glibc 2.36 on aarch64 Debian and
# Bionic on an arm64 device -- rather than inherited from a list:
#   sigaction     Bionic puts sa_flags first and uses an 8-byte mask; glibc puts
#                 the handler first and uses 128 bytes (bits/signal_types.h)
#   addrinfo      Bionic orders ai_canonname before ai_addr; glibc is the other
#                 way round, so an unconverted caller reads a name as a socket
#                 address (netdb.h)
#   gai_strerror  not a struct at all: the two libcs number the EAI_ codes
#                 differently, glibc negative and Bionic 1..14, with no value in
#                 common. A code handed back unconverted names a different error
# The sigset_t helpers come with sigaction: they operate on the 128-byte set its
# callers pass. getaddrinfo's wrapper carries the AI_/EAI_ translation for the
# same reason gai_strerror needs one -- a differing constant is as wrong as a
# differing offset, and neither announces itself.
TRANSLATED = {
    "sigaction": "__shim_sigaction",
    "sigemptyset": "__shim_sigemptyset",
    "sigfillset": "__shim_sigfillset",
    "sigaddset": "__shim_sigaddset",
    "sigdelset": "__shim_sigdelset",
    "sigismember": "__shim_sigismember",
    "sigprocmask": "__shim_sigprocmask",
    "pthread_sigmask": "__shim_pthread_sigmask",
    "getaddrinfo": "__shim_getaddrinfo",
    "freeaddrinfo": "__shim_freeaddrinfo",
    "gai_strerror": "__shim_gai_strerror",
    "getnameinfo": "__shim_getnameinfo",
}

# The same hazard, without a wrapper written yet: these abort by name on first
# call rather than forwarding, because a loud stop beats silent corruption and
# the message says which wrapper is owed.
#
# The list is empty, and that is a finding rather than an oversight. A published
# glibc-compatibility shim for FreeBSD refuses to auto-forward seventeen structs
# (sigaction, stat, statfs, dirent, termios, sockaddr, msghdr, passwd, utsname,
# rlimit and more), and that list was the starting point here. Each was then
# printed by a program compiled twice -- glibc 2.36 on aarch64 Debian, Bionic on
# an arm64 device -- and the two outputs diffed: struct stat, dirent, statfs,
# msghdr, passwd, utsname, rlimit, sockaddr and stack_t agree on size and on
# every field offset. Bionic and glibc both follow the same Linux kernel ABI,
# where FreeBSD does not.
#
# struct termios is the exception, and an earlier version of this comment put it
# on the identical side, which was wrong. glibc's is 60 bytes with NCCS 32 and
# carries c_ispeed and c_ospeed at offsets 52 and 56; Bionic's is the bare kernel
# structure, 36 bytes with NCCS 19 and no speed fields. It still needs no wrapper,
# and that is the part the size difference hides: every field the two share sits
# at the same offset -- c_iflag 0, c_oflag 4, c_cflag 8, c_lflag 12, c_line 16,
# c_cc 17 -- so Bionic reads and writes a correct 36-byte prefix of the caller's
# larger structure and never touches the remainder. Confirmed on device through
# the shim: an addon with the 60-byte layout called tcgetattr on a real pty and
# read back the right c_cc[VMIN] and c_cc[VTIME]. Nothing in the tree imports
# tcgetattr or tcsetattr in any case.
#
# The ceiling worth knowing: after tcgetattr the tail of a glibc caller's struct
# -- c_cc[19..31], c_ispeed, c_ospeed -- holds whatever it held before the call,
# because Bionic does not write there. Round-tripping through tcsetattr is still
# safe, since Bionic reads only the same prefix. Code that compares two whole
# termios structures byte for byte would be comparing that uninitialised tail.
#
# So blocking these would have broken working code to prevent a problem that is
# not there. Only what was verified different gets a wrapper -- see TRANSLATED --
# and only what is verified different, and dangerous, belongs here.
#
# Before adding a name: read the struct in
#   $ANDROID_NDK_HOME/toolchains/llvm/prebuilt/*/sysroot/usr/include
# and compare it against glibc's for aarch64. An entry added on suspicion turns
# a working call into an abort.
NEEDS_TRANSLATION = set()

# Every source is a call into libglibc-shim.so, never a bare name. The stub
# exports these very names itself (versioned, default visibility), so a bare
# `environ` or `stdout` in its own constructor binds to its own zeroed storage
# and copies NULL over NULL -- the same self-binding hazard the resolver
# comment below describes for dlopen, measured here with readelf: the GLOB_DAT
# entries pointed into the stub's own .bss. libglibc-shim.so defines none of
# these names, so a call routed through it reaches the real values.
DATA_SYMBOLS = {
    "stdout": ("FILE *", "__shim_stdout()"),
    "stderr": ("FILE *", "__shim_stderr()"),
    "stdin": ("FILE *", "__shim_stdin()"),
    "environ": ("char **", "__shim_environ()"),
    # The stack-protector canary. Bionic keeps its own private, so this is a
    # separate value -- which is fine, because the addon only ever compares it
    # against itself. It has to be non-zero and not guessable from the binary.
    "__stack_chk_guard": ("unsigned long ", "__shim_stack_guard()"),
    "__environ": ("char **", "__shim_environ()"),
    "_environ": ("char **", "__shim_environ()"),
}


class Elf:
    """Just enough ELF64 to read what symbols an object imports, and from where."""

    def __init__(self, path: pathlib.Path):
        self.data = path.read_bytes()
        self.path = path
        if self.data[:4] != b"\x7fELF" or self.data[4] != 2:
            raise ValueError(f"{path.name} is not a 64-bit ELF file")

        e_shoff, = struct.unpack_from("<Q", self.data, 40)
        e_shentsize, e_shnum, e_shstrndx = struct.unpack_from("<HHH", self.data, 58)

        self.sections = []
        for i in range(e_shnum):
            off = e_shoff + i * e_shentsize
            name, s_type = struct.unpack_from("<II", self.data, off)
            s_offset, = struct.unpack_from("<Q", self.data, off + 24)
            s_size, s_link = struct.unpack_from("<QI", self.data, off + 32)
            s_entsize, = struct.unpack_from("<Q", self.data, off + 56)
            self.sections.append(
                dict(name=name, type=s_type, offset=s_offset, size=s_size,
                     link=s_link, entsize=s_entsize))

        shstr = self.sections[e_shstrndx]
        for s in self.sections:
            s["sname"] = self._cstr(shstr["offset"] + s["name"])

    def _cstr(self, off: int) -> str:
        end = self.data.index(b"\0", off)
        return self.data[off:end].decode("utf-8", "replace")

    def _by_type(self, want):
        return next((s for s in self.sections if s["type"] == want), None)

    def imports(self):
        """Yield (soname, version, symbol, sym_type, is_weak) for each import.

        sym_type is the raw STT_* value rather than a func/data flag. The
        difference is load-bearing: STT_NOTYPE is neither, and collapsing it
        into "not a function" made it look like a data symbol and get dropped.

        soname and version are None for imports with no version requirement --
        those bind by name alone and need no stub.

        is_weak is the binding, and it is what separates the two kinds of
        unversioned import: the loader leaves a weak one unresolved and the
        caller tests the address, where a strong one has to be exported by
        something already loaded or dlopen refuses the object outright.
        """
        dynsym = self._by_type(SHT_DYNSYM)
        if not dynsym:
            return
        strtab = self.sections[dynsym["link"]]

        versym = self._by_type(SHT_GNU_VERSYM)
        # version index -> (version name, library it must come from)
        vermap = {}
        verneed = self._by_type(SHT_GNU_VERNEED)
        if verneed:
            # Verneed carries its own string table link; usually .dynstr, but
            # reading it from the section rather than assuming keeps this honest.
            vstr = self.sections[verneed["link"]]
            pos = verneed["offset"]
            while True:
                # Elf64_Verneed: version(2) cnt(2) file(4) aux(4) next(4)
                _, vn_cnt = struct.unpack_from("<HH", self.data, pos)
                vn_file, vn_aux, vn_next = struct.unpack_from("<III", self.data, pos + 4)
                soname = self._cstr(vstr["offset"] + vn_file)
                aux = pos + vn_aux
                for _ in range(vn_cnt):
                    vna_name, = struct.unpack_from("<I", self.data, aux + 8)
                    vna_other, = struct.unpack_from("<H", self.data, aux + 6)
                    vna_next, = struct.unpack_from("<I", self.data, aux + 12)
                    vermap[vna_other] = (self._cstr(vstr["offset"] + vna_name), soname)
                    if not vna_next:
                        break
                    aux += vna_next
                if not vn_next:
                    break
                pos += vn_next

        count = dynsym["size"] // dynsym["entsize"]
        for i in range(count):
            off = dynsym["offset"] + i * dynsym["entsize"]
            st_name, st_info = struct.unpack_from("<IB", self.data, off)
            st_shndx, = struct.unpack_from("<H", self.data, off + 6)
            if st_shndx != 0:            # defined here, not imported
                continue
            name = self._cstr(strtab["offset"] + st_name)
            if not name:
                continue

            version = soname = None
            if versym:
                idx, = struct.unpack_from("<H", self.data, versym["offset"] + i * 2)
                version, soname = vermap.get(idx & 0x7FFF, (None, None))

            yield soname, version, name, st_info & 0xF, (st_info >> 4) == STB_WEAK


    def exports(self):
        """Yield (symbol, version) for every symbol this object defines.

        The mirror of imports(), and the reason it exists: knowing what the
        addons ask for says nothing about what the stubs ended up carrying.
        Versions come from SHT_GNU_VERDEF rather than VERNEED -- a definition
        names its own version, where an import names the one it requires.
        """
        dynsym = self._by_type(SHT_DYNSYM)
        if not dynsym:
            return
        strtab = self.sections[dynsym["link"]]
        versym = self._by_type(SHT_GNU_VERSYM)

        # version index -> version name, skipping the base entry, which names
        # the object itself rather than a version anything can bind to.
        vermap = {}
        verdef = self._by_type(SHT_GNU_VERDEF)
        if verdef:
            vstr = self.sections[verdef["link"]]
            pos = verdef["offset"]
            while True:
                # Elf64_Verdef: version(2) flags(2) ndx(2) cnt(2) hash(4) aux(4) next(4)
                _, vd_flags, vd_ndx, vd_cnt = struct.unpack_from("<HHHH", self.data, pos)
                vd_aux, vd_next = struct.unpack_from("<II", self.data, pos + 12)
                if not (vd_flags & 0x1) and vd_cnt:
                    vda_name, = struct.unpack_from("<I", self.data, pos + vd_aux)
                    vermap[vd_ndx] = self._cstr(vstr["offset"] + vda_name)
                if not vd_next:
                    break
                pos += vd_next

        count = dynsym["size"] // dynsym["entsize"]
        for i in range(count):
            off = dynsym["offset"] + i * dynsym["entsize"]
            st_name, = struct.unpack_from("<I", self.data, off)
            st_shndx, = struct.unpack_from("<H", self.data, off + 6)
            if st_shndx == 0:            # imported, not defined here
                continue
            name = self._cstr(strtab["offset"] + st_name)
            if not name:
                continue
            version = None
            if versym:
                idx, = struct.unpack_from("<H", self.data, versym["offset"] + i * 2)
                version = vermap.get(idx & 0x7FFF)
            yield name, version


def verify_shipped(inputs, lib_dir: pathlib.Path) -> int:
    """Check the addons against the stubs that were actually built.

    Everything before this verifies intent: the generator emits what it decided
    to emit, and the compiler builds what it was handed. This reads the finished
    libraries and asks the only question that matters on a device -- for every
    versioned symbol an addon imports, does the library it names carry that
    symbol at that version. A stub that failed to build, a soname nobody
    compiled, a symbol dropped between the two, all surface here as the same
    kind of miss.
    """
    print("")
    print("--- addon imports vs shipped stubs ---")

    needed: dict = {}
    unbound = set()
    for path in inputs:
        for soname, version, name, _, is_weak in Elf(path).imports():
            if RUNTIME_PROVIDED.match(name) or name in WEAK_OPTIONAL:
                continue
            if soname is None or version is None:
                # No version requirement means the import names no library, so
                # there is no stub to look it up in. That is not the same as
                # harmless, and skipping the lot of them was how an addon could
                # ship asking for a name nothing on the device exports.
                #
                # Weak is the one case that is harmless: the loader leaves a
                # weak import unresolved rather than refusing the object, and
                # the caller tests the address before using it. Every such
                # import in the tree today is written that way -- gettid,
                # pidfd_spawnp, posix_spawn_file_actions_addchdir, sdallocx and
                # the OPENSSL_memory_/ZSTD_trace_ hooks, all STB_WEAK.
                #
                # A strong one is the opposite: dlopen fails outright unless
                # something already loaded exports that exact name, and the
                # failure lands on the device with nothing here having said so.
                if is_weak:
                    continue
                unbound.add((str(path), name))
                continue
            needed.setdefault(soname, set()).add((name, version))

    missing = []
    for soname, wants in sorted(needed.items()):
        stub = lib_dir / soname
        if not stub.is_file():
            missing.append((soname, "the library itself was not built", ""))
            continue
        try:
            have = set(Elf(stub).exports())
        except (ValueError, IndexError, struct.error) as e:
            missing.append((soname, f"unreadable: {e}", ""))
            continue
        for name, version in sorted(wants):
            if (name, version) not in have:
                missing.append((soname, name, version))
        print(f"  {soname:<24} {len(wants):4d} imports resolved")

    if missing:
        print(f"\n  ERROR: {len(missing)} import(s) are not in the libraries that ship:",
              file=sys.stderr)
        for soname, name, version in missing:
            suffix = f"@{version}" if version else ""
            print(f"    {soname}: {name}{suffix}", file=sys.stderr)

    if unbound:
        print(f"\n  ERROR: {len(unbound)} strong import(s) name no library and no"
              f"\n  stub carries them, so dlopen refuses the addon on the device:",
              file=sys.stderr)
        for owner, name in sorted(unbound):
            print(f"    {owner}: {name}", file=sys.stderr)
        print("\n  A name the loading process defines itself belongs in"
              "\n  RUNTIME_PROVIDED. Anything else needs the library that exports"
              "\n  it, or the addon rebuilt without the reference.", file=sys.stderr)

    return 1 if (missing or unbound) else 0


def report_unforwardable(unforwardable) -> int:
    """Name what cannot be forwarded and fail, rather than noting it and going on.

    The note was the bug: the build stayed green, the stub shipped without the
    symbol, and the addon died at its first call to it -- three layers from the
    line that decided to skip it.
    """
    print(f"\n  ERROR: {len(unforwardable)} import(s) cannot be forwarded, so the addon"
          f"\n  that needs them would load without them and fail at the first call:",
          file=sys.stderr)
    for name, soname, kind in sorted(unforwardable):
        print(f"    {name}  ({kind}, from {soname})", file=sys.stderr)
    print("\n  A function is forwarded automatically. A data symbol needs an entry in"
          "\n  DATA_SYMBOLS naming its C type -- guessing the type here corrupts memory"
          "\n  rather than failing to link. An STT_NOTYPE import has to be identified"
          "\n  before it can be either.", file=sys.stderr)
    return 1


def generate(inputs, out_dir: pathlib.Path):
    # soname -> version -> {symbol: is_func}
    wanted: dict = {}
    unforwardable, skipped_runtime = set(), set()

    for path in inputs:
        for soname, version, name, sym_type, _ in Elf(path).imports():
            if RUNTIME_PROVIDED.match(name):
                skipped_runtime.add(name)
                continue
            if name in WEAK_OPTIONAL or soname is None:
                continue

            # Three outcomes, and only the first two can be forwarded. A
            # function becomes a branch; a data symbol becomes storage of the
            # type DATA_SYMBOLS records. Anything else -- an object nobody has
            # given a type, or an STT_NOTYPE import whose type the linker never
            # wrote down -- cannot be turned into either without guessing, and
            # guessing here means the addon reads a code address as a pointer.
            # It used to be noted and skipped, which shipped an addon missing
            # exactly the symbol the note named.
            if sym_type == STT_FUNC:
                is_func = True
            elif sym_type == STT_OBJECT and name in DATA_SYMBOLS:
                is_func = False
            else:
                unforwardable.add((name, soname, TYPE_NAMES.get(sym_type, str(sym_type))))
                continue
            wanted.setdefault(soname, {}).setdefault(version, {})[name] = is_func

    # Before the early exit below, not after: an addon whose only versioned
    # import is one we cannot forward leaves `wanted` empty, and reporting
    # "nothing to generate" there would turn the very case this check exists for
    # into a silent success. Found by testing the failure direction.
    if unforwardable:
        return report_unforwardable(unforwardable)

    if not wanted:
        print("  nothing to generate: no versioned imports found")
        return 0

    out_dir.mkdir(parents=True, exist_ok=True)
    for soname, versions in sorted(wanted.items()):
        stem = soname.replace(".", "_")
        c_path = out_dir / f"{stem}.c"
        map_path = out_dir / f"{stem}.map"

        lines = [
            "/* Generated by gen-glibc-forwarders.py -- do not edit.",
            f" * Stub for {soname}. Functions are a tail branch to the Bionic",
            " * function of the same name; data symbols are storage of our own,",
            " * filled from Bionic's before anything else runs. Both are exported",
            " * at the version the addon asks for.",
            " */",
            "#include <dlfcn.h>",
            "#include <link.h>",
            "#include <stdio.h>",
            "#include <stdlib.h>",
            "",
            "extern char **__shim_environ(void);",
            "extern FILE *__shim_stdin(void);",
            "extern FILE *__shim_stdout(void);",
            "extern FILE *__shim_stderr(void);",
            "",
            "#include <android/log.h>",
            "",
            "/* Bionic merged libm, libdl, libpthread and librt into libc, so one",
            " * handle answers for every name this stub stands in for. Opened with",
            " * NOLOAD because libc is always already mapped. */",
            "/* Resolution lives in libglibc-shim.so, not here: this file exports",
            " * dlopen@GLIBC_2.17 and friends, so calling dlopen by name from inside",
            " * it would bind to its own unfilled trampoline. */",
            "extern void *__shim_resolve(const char *name);",
            "extern void __shim_untranslated(const char *name);",
            "extern unsigned long __shim_stack_guard(void);",
            "",
            "/* Reached when Bionic has no symbol of that name, so the pointer stayed",
            " * null. Branching to it would be a jump to address zero -- a SIGSEGV",
            " * with no indication of which of 254 forwarders was responsible. Naming",
            " * it costs one compare per call and turns a blind crash into a fact. */",
            '__attribute__((noreturn, used, visibility("hidden")))',
            "static void __fwd_missing(const char *name) {",
            '    __android_log_print(ANDROID_LOG_FATAL, "glibc-shim",',
            '                        "no Bionic symbol for %s", name);',
            "    abort();",
            "}",
            "",
        ]
        targets, inits = [], []
        for version, symbols in sorted(versions.items()):
            suffix = version.replace(".", "_")
            for name, is_func in sorted(symbols.items()):
                fwd = f"__fwd_{suffix}_{name}"
                if is_func:
                    ptr = f"__ptr_{suffix}_{name}"
                    targets.append((ptr, name, fwd))
                    # Hidden, and that is load-bearing: a default-visibility
                    # global is preemptible, so the linker routes reads through
                    # the GOT and the adrp/lo12 pair below would compute the
                    # wrong address. Hidden binds locally and makes the direct
                    # pair correct.
                    lines.append(f'void *{ptr} __attribute__((visibility("hidden")));')
                else:
                    ctype, source = DATA_SYMBOLS[name]
                    lines.append(f"{ctype}{fwd};")
                    inits.append(f"    {fwd} = {source};")
                lines.append(f'__asm__(".symver {fwd},{name}@@{version}");')

        for ptr, name, fwd in targets:
            label = f".L_miss_{fwd}"
            nsym = f"__name_{fwd}"
            # used, because the only reference is from inline asm and the
            # compiler would otherwise drop it; hidden, so adrp/add can reach it
            # directly instead of going through the GOT.
            lines.append(f'__attribute__((used, visibility("hidden")))')
            lines.append(f'static const char {nsym}[] = "{name}";')
            lines.append(
                f'__asm__(".globl {fwd}\\n"\n'
                f'        "{fwd}:\\n"\n'
                f'        "  adrp x16, {ptr}\\n"\n'
                f'        "  ldr  x16, [x16, :lo12:{ptr}]\\n"\n'
                f'        "  cbz  x16, {label}\\n"\n'
                f'        "  br   x16\\n"\n'
                f'        "{label}:\\n"\n'
                f'        "  adrp x0, {nsym}\\n"\n'
                f'        "  add  x0, x0, :lo12:{nsym}\\n"\n'
                f'        "  b    __fwd_missing\\n");')

        lines.append("")
        lines.append("__PLACEHOLDER_EXTERNS__")
        lines.append("__attribute__((constructor)) static void __fwd_init(void) {")
        lines.append('    __android_log_print(ANDROID_LOG_INFO, "glibc-shim",')
        lines.append(f'                        "init {soname}");')
        externs = []
        for ptr, name, _ in targets:
            if name in TRANSLATED:
                externs.append(f"extern void {TRANSLATED[name]}(void);")
                lines.append(f"    {ptr} = (void *)&{TRANSLATED[name]};")
            elif name in NEEDS_TRANSLATION:
                lines.append(f"    {ptr} = 0;  /* no wrapper yet; aborts by name */")
            else:
                lines.append(f'    {ptr} = __shim_resolve("{name}");')
        lines.extend(inits)
        lines.append("}")
        body = "\n".join(lines)
        body = body.replace("__PLACEHOLDER_EXTERNS__",
                            "\n".join(sorted(set(externs))) if externs else "")
        c_path.write_text(body + "\n")

        map_lines = []
        for version, symbols in sorted(versions.items()):
            body = "; ".join(sorted(symbols))
            map_lines.append(f"{version} {{ global: {body}; }};")
        # Everything not named above stays local, so the stub exports exactly
        # what the addon asked for and nothing else.
        map_lines.append("LOCAL_ONLY { local: *; };")
        map_path.write_text("\n".join(map_lines) + "\n")

        total = sum(len(v) for v in versions.values())
        print(f"  {soname:<20} {total:4d} symbols across "
              f"{len(versions)} version(s)")

    pending = sorted(
        n for versions in wanted.values() for syms in versions.values()
        for n in syms if n in NEEDS_TRANSLATION)
    if pending:
        print(f"\n  no translating wrapper yet ({len(pending)}) -- these abort by name")
        print("  on first call rather than forwarding a struct laid out differently:")
        for name in pending:
            print(f"    {name}")

    if skipped_runtime:
        print(f"\n  {len(skipped_runtime)} runtime-provided symbols skipped"
              f" (napi_*, uv_*), as the loader defines those")

    if unforwardable:
        return report_unforwardable(unforwardable)
    return 0


def needs_glibc(path: pathlib.Path) -> bool:
    """True when this object asks for a library only glibc provides.

    Read from DT_NEEDED rather than from the file name, because a .node built
    for Bionic and one built for glibc are both just .node.
    """
    try:
        elf = Elf(path)
    except (ValueError, IndexError, struct.error):
        return False
    return any(so and so.endswith((".so.6", ".so.2", ".so.1", ".so.0"))
               for so, _, _, _, _ in elf.imports())


def scan(roots):
    """Native objects under these paths that would fail to load as-is.

    Returns (shimmable, blocked): the second list is objects that need a library
    forwarding cannot stand in for, reported so nobody reads silence as success.
    """
    shimmable, blocked = [], []
    for root in roots:
        # `*.so` as well as `*.node`, because the question is "can the loader
        # take this object", and the answer does not depend on the suffix. The
        # sweep read only `*.node`, which is what a native addon happens to be
        # called; an extension or a server-tree package shipping a plain
        # `linux-arm64/*.so` that links a glibc soname would have been generated
        # no stub and would fail at dlopen on a device with every gate green.
        # Measured 2026-08-23: the two scanned trees hold no aarch64 `*.so` that
        # is not a `.node`, so this widens what is asked without changing what is
        # currently produced.
        if root.is_dir():
            candidates = sorted(set(root.rglob("*.node")) | set(root.rglob("*.so")))
        else:
            candidates = [root]
        for path in candidates:
            if not path.is_file() or not needs_glibc(path):
                continue
            try:
                libs = {so for so, _, _, _, _ in Elf(path).imports() if so}
            except (ValueError, IndexError, struct.error):
                continue
            hard = libs & UNSUPPORTED_LIBS
            if hard:
                blocked.append((path, sorted(hard)))
            else:
                shimmable.append(path)
    return shimmable, blocked


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("inputs", nargs="*", type=pathlib.Path)
    # Not required in --verify-against mode, which reads and writes nothing.
    ap.add_argument("--out", type=pathlib.Path)
    ap.add_argument("--scan", type=pathlib.Path, action="append", default=[],
                    help="directory to search for glibc-built .node files")
    ap.add_argument("--verify-against", type=pathlib.Path,
                    help="check the addons against the stubs already built in this "
                         "directory instead of generating; run after the build")
    args = ap.parse_args()

    if not args.verify_against and args.out is None:
        print("  ERROR: --out is required unless --verify-against is given",
              file=sys.stderr)
        return 1

    inputs = list(args.inputs)
    blocked = []
    if args.scan:
        discovered, blocked = scan(args.scan)
        for path in discovered:
            print(f"  will forward for: {path.name}")
        for path, libs in blocked:
            print(f"  CANNOT help    : {path.name} needs {', '.join(libs)}")
        if not discovered and not blocked:
            print("  no glibc-built addons found; nothing needs forwarding")
        inputs += discovered

    missing = [p for p in inputs if not p.is_file()]
    if missing:
        print("  ERROR: no such file: " + ", ".join(str(p) for p in missing),
              file=sys.stderr)
        return 1
    if not inputs:
        return 0
    if args.verify_against:
        # Only when checking a tree that has been through the overlay. On the
        # generate path a blocked object is expected: this runs before
        # build-native-addons.sh has replaced anything, and refusing there would
        # fail every correct build.
        unexpected = [
            (path, libs) for path, libs in blocked
            if path.name not in TOLERATED_BLOCKED
        ]
        if unexpected:
            print(
                f"  ERROR: {len(unexpected)} addon(s) in the packaged tree are still "
                "the upstream glibc build, which loads on no Android device:",
                file=sys.stderr,
            )
            for path, libs in unexpected:
                print(f"    {path}: needs {', '.join(libs)}", file=sys.stderr)
            print(
                "  -> run scripts/build-native-addons.sh, and note that "
                "package-assets.sh copies the pristine tree back over the overlay, "
                "so it has to run before this and not after",
                file=sys.stderr,
            )
            return 1
        return verify_shipped(inputs, args.verify_against)
    return generate(inputs, args.out)


if __name__ == "__main__":
    sys.exit(main())
