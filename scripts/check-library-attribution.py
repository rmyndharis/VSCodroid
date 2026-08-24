#!/usr/bin/env python3
"""Refuse a build that ships a library nobody has attributed.

    check-library-attribution.py

Every shared library under `assets/usr/lib` and every executable under
`jniLibs/arm64-v8a` is redistributed inside the APK, and all of them arrive from
Termux or Alpine rather than from this repository. Permissive licences require
their notice to travel with the binary; GPL and LGPL additionally require an
offer of the corresponding source. Neither obligation is discharged by code, so
nothing in the build could previously notice when one went unmet.

Those two directories are not the whole of what ships. They are the top level of
two directories, and the scan read only that: `assets/usr/lib/git-core/` holds
twelve GPL-2.0 Git executables, `assets/usr/lib/python*/lib-dynload/` holds
seventy-five CPython extension modules, and `assets/vscode-reh/` carries the
server's native addons, the WebAssembly its editor services load, and the
binaries the built-in extensions bundle. So the tree is walked to its leaves, and
the allowlist below maps each subtree to the component that already carries its
attribution.

A file is recognised as a binary by its magic number, plus one extension.

ELF alone is not enough, and reading it as though it were is how a scan can walk
a tree to its leaves and still miss more than it finds. Most of what ships here
below the two flat directories is not ELF at all: tree-sitter's grammars and
oniguruma are WebAssembly, PSReadLine is .NET assemblies, pip vendors distlib's
Windows launchers, and several npm packages carry the Windows and macOS builds of
a helper beside the Linux one. None of those can execute on this device. That
changes nothing about the obligation: they are inside the APK, redistributed on
their own terms, and a notice has to travel with them. So WebAssembly, PE and
Mach-O are tested for alongside ELF.

The `.node` extension is kept beside the magic numbers even though every `.node`
in the tree today is ELF or PE and would be caught anyway. A file with that
suffix is a loadable native addon whatever container it turns out to be in, and
the union is deliberately generous: a false positive costs one line in
NESTED_LIBRARIES, a false negative costs an unattributed binary in a shipped APK.

One did. `libdb-18.1.so` -- Berkeley DB, **AGPL-3.0-only**, the strongest
copyleft in common use -- shipped in every release with no attribution anywhere
and no source offer, because it arrived as a transitive dependency of krb5 and
was added to a package list without anyone asking what its licence was. It
turned out nothing linked it at all, so it was dropped rather than documented;
but `libgdbm`, `liblzma` and `libzstd` are used, are copyleft, and were missing
from the source offer for the same reason.

Four checks, in the order a new library trips them:

  1. Every shipped file maps to a known component. A library nobody has
     classified fails here, which is the moment to look up its licence.
  2. Every component is named in BOTH attribution documents: docs/LEGAL_NOTICES.md
     and NOTICE.md. Only the first was read for a long time, while NOTICE.md told
     its own readers this script fails the build when a shipped binary is missing
     from its table, so the file that claimed to be guarded was the one that was
     not, and the two agreed only by coincidence.
  3. Every component whose licence is GPL/LGPL/AGPL is additionally named in
     LEGAL_NOTICES.md's source-availability section. Attribution alone does not
     discharge a copyleft obligation. Only that file, because only that file has
     the section; NOTICE.md points at it rather than repeating it.
  4. Every component from the two flat maps carries its own notice among the
     bytes that reach the device. The three checks above are about what this
     repository says; MIT, the BSD family, ISC, NCSA, the ICU licence and
     Apache-2.0 section 4 are about what the person holding the binary is given,
     and for everything except the copyleft components they were given a name
     and a URL. See NOTICE_DIRS.

The map below is the licence record. Its source is Termux's own
`TERMUX_PKG_LICENSE`, which is what these packages are actually built from.
Nothing here reads that field, so nothing here can tell a correct entry from a
mistyped one: check-termux-licenses.py reads it back per package and reports the
differences, and fails on the one that matters, a component recorded permissive
where Termux declares copyleft.

Toolchain packs are covered too, but only where they exist. Their payloads are
downloaded, not committed, so this has to run AFTER the download step to see
them, in release.yml it runs twice for that reason. The count of manifests
read is printed, because "0 toolchain libraries" from a tree that has no packs
and from a tree whose packs were never recorded print the same otherwise.
"""

import fnmatch
import json
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
ASSETS = ROOT / "android/app/src/main/assets"
USR_LIB = ROOT / "android/app/src/main/assets/usr/lib"
JNILIBS = ROOT / "android/app/src/main/jniLibs/arm64-v8a"
LEGAL_NOTICES = ROOT / "docs/LEGAL_NOTICES.md"
# The second attribution document, and the one that says out loud that this
# script guards it. Both are read for attribution; the copyleft source offer
# lives in LEGAL_NOTICES alone.
NOTICE = ROOT / "NOTICE.md"
TOOLCHAINS = ROOT / "android"

# Licences carrying a source obligation. Matched case-insensitively as a
# substring, so "LGPL-2.1, GPL-3.0" trips on either half.
#
# The spelled-out names are here because the abbreviations do not appear in them:
# "Mozilla Public License 2.0" contains no "MPL", so a component recorded that way
# was classified permissive and never asked for a source offer. Whoever adds a
# library writes this field by hand, and Termux's own metadata uses both styles,
# so the check cannot assume an SPDX identifier.
COPYLEFT = (
    "GPL", "AGPL", "LGPL", "MPL", "EPL", "CDDL",
    "MOZILLA PUBLIC", "ECLIPSE PUBLIC", "COMMON DEVELOPMENT",
    "GENERAL PUBLIC LICENSE",
)

# The object formats redistributed in this tree, by the first bytes of the file.
#
# All four appear under assets/ today. Mach-O and PE are here because npm
# packages ship every platform's build of a helper and the whole package lands in
# the APK; WebAssembly because tree-sitter, oniguruma and two of js-debug's
# panels are compiled to it rather than to native code.
#
# `MZ` is two bytes rather than four, which is as specific as a DOS header gets.
# It is the weakest test here, and it is still the right trade: over-matching
# costs one allowlist line, under-matching ships an unattributed binary.
BINARY_MAGIC = (
    b"\x7fELF",                                  # ELF
    b"\x00asm",                                  # WebAssembly
    b"MZ",                                       # PE/COFF, .NET assemblies included
    b"\xfe\xed\xfa\xce", b"\xce\xfa\xed\xfe",    # Mach-O 32-bit, either endianness
    b"\xfe\xed\xfa\xcf", b"\xcf\xfa\xed\xfe",    # Mach-O 64-bit, either endianness
    b"\xca\xfe\xba\xbe", b"\xbe\xba\xfe\xca",    # Mach-O universal
)

# soname (or executable) -> (component name as written in LEGAL_NOTICES, licence)
#
# "VSCodroid" as the component means the file is built from this repository's
# own source and is covered by the root LICENSE; it needs no third-party
# attribution. Two families qualify. The glibc-shim stubs carry glibc's
# DT_NEEDED names but contain none of glibc's code, and libexec-trampoline.so is
# compiled here from scripts/exec-trampoline.c against Bionic alone.
LIBRARIES = {
    # --- built here, MIT, no third-party obligation ---
    "libglibc-shim.so": ("VSCodroid", "MIT"),
    "libc.so.6": ("VSCodroid", "MIT"),
    "libdl.so.2": ("VSCodroid", "MIT"),
    "libm.so.6": ("VSCodroid", "MIT"),
    "libpthread.so.0": ("VSCodroid", "MIT"),
    "librt.so.1": ("VSCodroid", "MIT"),
    "libutil.so.1": ("VSCodroid", "MIT"),
    "libresolv.so.2": ("VSCodroid", "MIT"),
    "libcrypt.so.1": ("VSCodroid", "MIT"),
    "ld-linux-aarch64.so.1": ("VSCodroid", "MIT"),
    "libgcc_s.so.1": ("VSCodroid", "MIT"),
    "libexec-trampoline.so": ("VSCodroid", "MIT"),
    # --- copyleft: attribution AND a source offer ---
    "libbash.so": ("Bash", "GPL-3.0"),
    "libgit.so": ("Git", "GPL-2.0"),
    "libgit-remote-curl.so": ("Git", "GPL-2.0"),
    "libmake.so": ("GNU Make", "GPL-3.0"),
    "libreadline.so.8": ("readline", "GPL-3.0"),
    "libiconv.so": ("libiconv", "LGPL-2.1, GPL-3.0"),
    "libgdbm.so": ("gdbm", "GPL-3.0"),
    "libgdbm_compat.so": ("gdbm", "GPL-3.0"),
    "liblzma.so": ("xz / liblzma", "LGPL-2.1, GPL-2.0, GPL-3.0"),
    "liblzma.so.5": ("xz / liblzma", "LGPL-2.1, GPL-2.0, GPL-3.0"),
    "libzstd.so.1": ("Zstandard", "GPL-2.0"),
    # --- permissive: attribution only ---
    "libnode.so": ("Node.js", "MIT"),
    "libpython.so": ("Python", "PSF-2.0"),
    "libpython3.14.so": ("Python", "PSF-2.0"),
    "libripgrep.so": ("ripgrep", "MIT"),
    "libtmux.so": ("tmux", "ISC"),
    "libssh.so": ("OpenSSH", "BSD"),
    "libssh-keygen.so": ("OpenSSH", "BSD"),
    "libldmusl.so": ("musl libc", "MIT"),
    "libandroid-support.so": ("libandroid-support", "Apache-2.0, MIT"),
    "libandroid-glob.so": ("libandroid-glob", "BSD-3-Clause"),
    "libandroid-posix-semaphore.so": ("libandroid-posix-semaphore", "MIT"),
    "libncursesw.so.6": ("ncurses", "MIT"),
    "libpanelw.so.6": ("ncurses", "MIT"),
    "libcurl.so": ("libcurl", "MIT"),
    "libssl.so.3": ("OpenSSL", "Apache-2.0"),
    "libcrypto.so.3": ("OpenSSL", "Apache-2.0"),
    "libz.so.1": ("zlib", "Zlib"),
    # bzip2-1.0.6 is the SPDX identifier for bzip2's own licence, not a
    # placeholder. This said BSD-4-Clause until 2026-08-23, which is the BSD
    # variant defined by its advertising clause ("All advertising materials
    # ... must display the following acknowledgement"), and bzip2's LICENSE has
    # no such clause: its four conditions are retain-notice, do-not-misrepresent
    # -origin, mark-altered-versions and no-endorsement. Recording the stricter
    # identifier asserted an obligation in the notices that nothing here
    # discharged. Termux declares plain "BSD", so check-termux-licenses.py
    # reports this as a difference either way and correctly does not fail.
    "libbz2.so": ("bzip2", "bzip2-1.0.6"),
    "libbz2.so.1.0": ("bzip2", "bzip2-1.0.6"),
    "libexpat.so.1": ("Expat", "MIT"),
    "libffi.so": ("libffi", "MIT"),
    "libpcre2-8.so": ("PCRE2", "BSD-3-Clause"),
    "libnghttp2.so": ("nghttp2", "MIT"),
    "libnghttp3.so": ("nghttp3", "MIT"),
    "libngtcp2.so": ("ngtcp2", "MIT"),
    "libngtcp2_crypto_ossl.so": ("ngtcp2", "MIT"),
    "libssh2.so": ("libssh2", "BSD-3-Clause"),
    "libevent-2.1.so": ("libevent", "BSD-3-Clause"),
    "libevent_core-2.1.so": ("libevent", "BSD-3-Clause"),
    "libedit.so": ("libedit", "BSD-3-Clause"),
    # Termux's `libcrypt`, a standalone crypt(3), BSD-2-Clause. Not glibc's
    # libcrypt and not libxcrypt -- the LGPL one is a different project with a
    # confusingly similar soname. `libcrypt.so.1` beside it is our own stub.
    "libcrypt.so": ("libcrypt", "BSD-2-Clause"),
    "libldns.so": ("ldns", "BSD-3-Clause"),
    "libcares.so": ("c-ares", "MIT"),
    "libsqlite3.so": ("SQLite", "Public Domain"),
    "libc++_shared.so": ("libc++", "NCSA"),
    "libicuuc.so.78": ("ICU", "ICU"),
    "libicui18n.so.78": ("ICU", "ICU"),
    "libicudata.so.78": ("ICU", "ICU"),
    "libresolv_wrapper.so": ("libresolv-wrapper", "BSD-3-Clause"),
    "libgssapi_krb5.so.2": ("Kerberos 5", "MIT"),
    "libkrb5.so.3": ("Kerberos 5", "MIT"),
    "libkrb5support.so.0": ("Kerberos 5", "MIT"),
    "libk5crypto.so.3": ("Kerberos 5", "MIT"),
    "libcom_err.so.3": ("Kerberos 5", "MIT"),
}

# Libraries that ship inside an on-demand toolchain pack rather than the base APK.
#
# A separate map because they are found a different way: the base tree can be
# listed on disk, but a toolchain's payload is only present on a machine that ran
# its download script, so these are read from the manifests the app itself uses.
# Missing that distinction cost the attribution for four of them, which were
# deleted from NOTICE.md while still shipping -- one of them LGPL.
TOOLCHAIN_LIBRARIES = {
    "libgmp.so": ("GMP", "LGPL-3.0"),
    "libyaml-0.so": ("libyaml", "MIT"),
    "libruby.so": ("libruby", "BSD-2-Clause"),
    "libandroid-execinfo.so": ("libandroid-execinfo", "BSD-2-Clause"),
    "libandroid-shmem.so": ("libandroid-shmem", "BSD-3-Clause"),
    "libandroid-spawn.so": ("libandroid-spawn", "BSD-2-Clause"),
    "libffi.so": ("libffi", "MIT"),
}

# Where each component's own notice reaches the device, as a path under
# `assets/` or under a toolchain pack's `src/main/assets/`. Both are searched,
# because a component can ship in either and two of them ship in both.
#
# Naming the project and linking its homepage is not the notice. MIT, the BSD
# family, ISC, NCSA, the ICU licence and Apache-2.0 section 4 all require the
# copyright and permission text to be included with a binary redistribution, and
# for a long time none of it was: the APK carried a table of names and URLs,
# which is not a copy of anything on a device with no network. The three GNU
# texts were bundled separately for exactly that reason and the same reasoning
# was never applied to the permissive half.
#
# Almost every entry is `usr/share/doc/<termux package>`, which is where
# `termux_copy_notices` (scripts/lib/termux-packages.sh) places what upstream
# ships. Two are not, and both are measured rather than assumed:
#
#   * ripgrep arrives with the server tree rather than from Termux, and
#     @vscode/ripgrep-universal already carries its LICENSE inside the package;
#   * musl's Alpine .apk carries no notice at all (three entries: the loader,
#     the libc symlink, the metadata), so download-musl-loader.sh places
#     licenses/COPYRIGHT.musl instead.
#
# Keyed by component rather than by file, because that is the unit the two
# attribution documents and LIBRARIES are written in, and because one package's
# notice legitimately answers for several sonames: ncurses covers libpanelw,
# which Termux splits into ncurses-ui-libs with no doc directory of its own.
NOTICE_DIRS = {
    # --- copyleft ---
    "Bash": "usr/share/doc/bash",
    "Git": "usr/share/doc/git",
    "GNU Make": "usr/share/doc/make",
    "readline": "usr/share/doc/readline",
    "libiconv": "usr/share/doc/libiconv",
    "gdbm": "usr/share/doc/gdbm",
    "xz / liblzma": "usr/share/doc/liblzma",
    "Zstandard": "usr/share/doc/zstd",
    "GMP": "usr/share/doc/libgmp",
    # --- permissive ---
    "Node.js": "usr/share/doc/nodejs-lts",
    "Python": "usr/share/doc/python",
    "ripgrep": "vscode-reh/node_modules/@vscode/ripgrep-universal",
    "tmux": "usr/share/doc/tmux",
    "OpenSSH": "usr/share/doc/openssh",
    "musl libc": "usr/share/doc/musl",
    "libandroid-support": "usr/share/doc/libandroid-support",
    "libandroid-glob": "usr/share/doc/libandroid-glob",
    "libandroid-posix-semaphore": "usr/share/doc/libandroid-posix-semaphore",
    "ncurses": "usr/share/doc/ncurses",
    "libcurl": "usr/share/doc/libcurl",
    "OpenSSL": "usr/share/doc/openssl",
    "zlib": "usr/share/doc/zlib",
    "bzip2": "usr/share/doc/libbz2",
    "Expat": "usr/share/doc/libexpat",
    "libffi": "usr/share/doc/libffi",
    "PCRE2": "usr/share/doc/pcre2",
    "nghttp2": "usr/share/doc/libnghttp2",
    "nghttp3": "usr/share/doc/libnghttp3",
    "ngtcp2": "usr/share/doc/libngtcp2",
    "libssh2": "usr/share/doc/libssh2",
    "libevent": "usr/share/doc/libevent",
    "libedit": "usr/share/doc/libedit",
    "libcrypt": "usr/share/doc/libcrypt",
    "ldns": "usr/share/doc/ldns",
    "c-ares": "usr/share/doc/c-ares",
    "SQLite": "usr/share/doc/libsqlite",
    "libc++": "usr/share/doc/libc++",
    "ICU": "usr/share/doc/libicu",
    "libresolv-wrapper": "usr/share/doc/libresolv-wrapper",
    "Kerberos 5": "usr/share/doc/krb5",
    "libyaml": "usr/share/doc/libyaml",
    "libruby": "usr/share/doc/ruby",
    "libandroid-execinfo": "usr/share/doc/libandroid-execinfo",
    "libandroid-shmem": "usr/share/doc/libandroid-shmem",
    "libandroid-spawn": "usr/share/doc/libandroid-spawn",
}

# The smallest thing that can be a notice, in bytes.
#
# A presence test alone is not enough, and the case that proves it is in the
# tree: Termux's libicu ships usr/share/doc/libicu/LICENSE holding the fourteen
# bytes "404: Not Found" (measured on libicu 78.3), which passes any test for a
# file being there and discharges nothing. The floor is set between that and the
# smallest genuine notice among the components above, tmux's 731-byte copyright.
# pcre2's 97-byte `copyright` is a pointer at LICENCE.md beside it, so pcre2
# passes on that file rather than on the pointer, which is why the rule is "at
# least one file over the floor" rather than "every file".
MIN_NOTICE_BYTES = 128

# What counts as a notice file, case-folded, matched as a prefix. The same set
# termux_copy_notices filters a package's share/doc directory down to, and it has
# to be the same set here or the floor above means different things in different
# directories.
#
# It is what makes the floor mean anything for the one component whose notice
# does not come from that function: `ripgrep` points at an npm package
# directory, which holds LICENSE (1113 B) and package.json (541 B). Taking the
# largest file of any name there, deleting the LICENCE still left package.json
# clearing the floor, so the single entry the floor was most needed for was the
# one it could not see disappear.
NOTICE_NAMES = ("copyright", "copying", "license", "licence", "notice")


# Binaries that ship deeper in the asset tree than the two directories above,
# keyed by a glob over the path relative to `assets/`.
#
# By subtree rather than by file name, for two reasons the tree makes plain. The
# names repeat -- four different `rg` binaries and four `tgrep`, in four
# directories -- so a name says nothing about which component redistributes it.
# And the names churn: `lib-dynload` alone is 75 modules that come and go with
# the Python build, and js-debug's addons carry a content hash in the file name.
# What is stable is the package the file sits inside, which is also the thing an
# attribution names.
#
# `fnmatch`'s `*` spans `/`, so `<prefix>/*` covers a whole subtree at any depth.
# The longest matching pattern wins, which is what lets a vendored copy be
# attributed to its own upstream rather than to the package around it: Copilot
# ships ripgrep inside its own tree, and ripgrep's licence is not Copilot's.
# Verified rather than assumed -- the SDK copy's `rg` is byte-identical to
# `@vscode/ripgrep-universal`'s (sha256 e152ea68...), the `copilot-linux-arm64`
# copy is a different ripgrep build, and both are ripgrep.
#
# Every component here is one the documents already carry, or now carry: the
# point of the widening is to check what ships against the record, not to invent
# a second record.
NESTED_LIBRARIES = {
    # --- Termux packages, below the top level of usr/lib ---
    # No entry for usr/lib/git-core: it holds no binary any more. Its eight
    # standalone helpers (git-daemon, git-http-fetch and the rest) were
    # unexecutable on a device -- filesDir denies the app execve, and git reaches
    # them by absolute path rather than through the exec trampoline -- so
    # download-termux-tools.sh stopped copying them. What is left there is shell
    # text plus symlinks into nativeLibraryDir, and Git stays attributed by
    # libgit.so and libgit-remote-curl.so above.
    # CPython's extension modules and the `python.o` beside its build config.
    # Globbed on the version, never spelled: download-python.sh resolves it from
    # the Termux index at build time, so a literal here goes stale on a rebuild
    # nobody connects to this file.
    "usr/lib/python*/*": ("Python", "PSF-2.0"),
    # pip vendors distlib, which ships six prebuilt Windows launchers. The
    # licence is the same PSF-2.0 as CPython's, so the entry above discharges the
    # terms either way; the copyright holder is Vinay Sajip rather than the
    # PSF, and PSF-2.0 is precisely the licence that asks for the notice to
    # travel, so the file is named for what it is.
    "usr/lib/python*/site-packages/pip/_vendor/distlib/*": ("distlib", "PSF-2.0"),
    # --- the server tree's native addons ---
    "vscode-reh/node_modules/node-pty/*": ("node-pty", "MIT"),
    "vscode-reh/node_modules/@parcel/watcher/*": ("@parcel/watcher", "MIT"),
    "vscode-reh/node_modules/@vscode/sqlite3/*": ("@vscode/sqlite3", "BSD-3-Clause"),
    "vscode-reh/node_modules/@vscode/deviceid/*": ("@vscode/deviceid", "MIT"),
    "vscode-reh/node_modules/@vscode/native-watchdog/*": ("@vscode/native-watchdog", "MIT"),
    "vscode-reh/node_modules/@vscode/sandbox-runtime/*": ("@vscode/sandbox-runtime", "Apache-2.0"),
    "vscode-reh/node_modules/@microsoft/mxc-sdk/*": ("@microsoft/mxc-sdk", "MIT"),
    # The npm addon, not the Termux C libraries. Different project, different
    # licence, confusingly similar name -- hence the qualifier, which has to
    # read the same way in both documents for the attribution check to find it.
    "vscode-reh/node_modules/kerberos/*": ("kerberos (Node addon)", "Apache-2.0"),
    # --- the search binary, wherever it is bundled from ---
    "vscode-reh/node_modules/@vscode/ripgrep-universal/*": ("ripgrep", "MIT"),
    "vscode-reh/node_modules/@github/copilot-linux-arm64/ripgrep/*": ("ripgrep", "MIT"),
    "vscode-reh/extensions/copilot/node_modules/@github/copilot/sdk/ripgrep/*":
        ("ripgrep", "MIT"),
    # --- WebAssembly the editor loads at run time ---
    # Grammars, not native code, and shipped in three copies: the server's own,
    # the Copilot extension's node_modules, and the grammars bundled into that
    # extension's `dist`. Each is attributed to the package that redistributes
    # it, which is the same rule the native entries above follow.
    "vscode-reh/node_modules/@vscode/tree-sitter-wasm/*": ("@vscode/tree-sitter-wasm", "MIT"),
    "vscode-reh/extensions/copilot/node_modules/@vscode/tree-sitter-wasm/*":
        ("@vscode/tree-sitter-wasm", "MIT"),
    "vscode-reh/extensions/copilot/node_modules/web-tree-sitter/*": ("web-tree-sitter", "MIT"),
    # `onig.wasm` is Oniguruma compiled to WebAssembly. The npm wrapper is MIT
    # and the regex engine inside it is BSD-2-Clause; both are recorded, because
    # the wrapper's licence does not cover the code it carries.
    "vscode-reh/node_modules/vscode-oniguruma/*": ("vscode-oniguruma", "MIT, BSD-2-Clause"),
    # --- built-in extensions that carry binaries of their own ---
    "vscode-reh/extensions/ms-vscode.js-debug/*": ("js-debug", "MIT"),
    "vscode-reh/extensions/ms-vscode.vscode-js-profile-table/*":
        ("vscode-js-profile-visualizer", "MIT"),
    # --- .NET assemblies, carried by the terminal's shell integration ---
    # PSReadLine and its pager ship prebuilt for the PowerShell integration.
    # They cannot run here, and they are in the APK, which is the only fact the
    # licence cares about.
    "vscode-reh/out/vs/workbench/contrib/terminal/common/scripts/psreadline/*":
        ("PSReadLine", "BSD-2-Clause"),
    # --- redistributed under GitHub's own terms, not an open source licence ---
    # Its full reasoning is the "Proprietary Redistributed Components" section of
    # LEGAL_NOTICES.md; what this line adds is that the binaries are counted.
    "vscode-reh/node_modules/@github/copilot-linux-arm64/*":
        ("@github/copilot (GitHub Copilot CLI)", "GitHub Copilot CLI License (proprietary)"),
    "vscode-reh/extensions/copilot/node_modules/@github/copilot/*":
        ("@github/copilot (GitHub Copilot CLI)", "GitHub Copilot CLI License (proprietary)"),
    # --- the Copilot Chat extension's own bundle ---
    # MIT, and a different component from the proprietary CLI two lines up
    # despite sharing a directory tree. `dist/` holds the tree-sitter grammars
    # and the blackbird WebAssembly that the extension's build inlines, so this
    # entry stands for the extension as redistributor rather than for one
    # upstream project.
    "vscode-reh/extensions/copilot/dist/*": ("GitHub Copilot Chat extension", "MIT"),
    "vscode-reh/extensions/copilot/node_modules/@github/blackbird-external-ingest-utils/*":
        ("@github/blackbird-external-ingest-utils", "MIT"),
}

# Longest pattern first, so a subtree entry beats the package entry containing
# it. Sorted once rather than per file.
NESTED_ORDER = sorted(NESTED_LIBRARIES, key=len, reverse=True)

# Every entry is a whole subtree, `<prefix>/*`. Three things depend on that shape
# and none of them can tell when it is broken: longest-match ordering is only a
# correct proxy for containment when overlap is prefix-based, containment is
# tested as a string prefix, and the staleness anchor is the pattern minus its
# `/*`. An entry ending anywhere else would quietly get all three wrong, so it is
# refused here instead, where the answer is one line long.
_odd = [p for p in NESTED_LIBRARIES if not p.endswith("/*")]
if _odd:
    raise SystemExit("NESTED_LIBRARIES entries must cover a whole subtree "
                     f"and end in '/*': {', '.join(sorted(_odd))}")


def toolchain_libs():
    """Libraries the toolchain manifests declare, which manifests were read, and
    which packs were built here without one.

    Read from the manifests rather than from disk, because a `.so` inside a pack
    is one file among thousands and the manifest is the list the app itself
    installs from.

    The manifests are NOT committed: .gitignore excludes all three, and
    toolchain_ruby.json, once tracked because it was added before that line was,
    is untracked now. So what this sees is decided by which download scripts have
    run on this machine: a fresh checkout shows nothing, and a CI runner shows
    nothing at all until the download step has run. The docstring here claimed
    the opposite until the
    manifests were checked against `git ls-files`.

    That makes the empty answer ambiguous, and the third return value resolves
    it. A pack whose `usr/` payload is on disk was downloaded here and is about
    to be zipped and attached to the release; if its manifest is absent, the
    libraries are real and the record of what they are is missing, which is the
    one case that must not pass as "nothing to attribute".
    """
    libs, read, unrecorded = [], [], []
    for module in sorted(TOOLCHAINS.glob("toolchain_*")):
        assets = module / "src/main/assets"
        manifests = sorted(assets.glob("*.json"))
        if not manifests:
            if (assets / "usr").is_dir():
                unrecorded.append(module.name)
            continue
        for manifest in manifests:
            try:
                data = json.loads(manifest.read_text(encoding="utf-8"))
            except (OSError, ValueError) as exc:
                raise SystemExit(f"FAIL cannot read {manifest}: {exc}")
            libs.extend(data.get("libs", []))
            read.append(manifest.name)
    return sorted(set(libs)), read, unrecorded


def toolchain_libs_with_payload():
    """The manifest-declared libraries whose pack payload is on disk here.

    The notice check reads a FILE out of the pack, so it can only speak for a
    pack that was actually built on this machine. A manifest can be present while
    its payload is not (a download interrupted after the manifest was written,
    or a manifest kept from an earlier run), and demanding the notices then fails
    the build over files no step has yet had the chance to place.

    The attribution and source-offer checks are deliberately NOT gated this way:
    a manifest entry is a claim about what will ship, and that claim is worth
    holding to the documents whether or not the bytes are present yet.
    """
    libs = []
    for module in sorted(TOOLCHAINS.glob("toolchain_*")):
        assets = module / "src/main/assets"
        if not (assets / "usr").is_dir():
            continue
        for manifest in sorted(assets.glob("*.json")):
            try:
                data = json.loads(manifest.read_text(encoding="utf-8"))
            except (OSError, ValueError) as exc:
                raise SystemExit(f"FAIL cannot read {manifest}: {exc}")
            libs.extend(data.get("libs", []))
    return sorted(set(libs))


def unlisted_toolchain_libs():
    """Loose libraries in a built pack that its manifest does not name.

    `toolchain_libs()` above reads the manifest, which is the list the app
    installs from, and that is the right source for WHAT to attribute. It is the
    wrong source for whether the list is complete: a download script that starts
    copying one more `.so` into the pack and does not add the matching `libs`
    entry ships a library this run never sees, so no component is demanded, no
    notice is required, and nothing goes red.

    Only the TOP LEVEL of the pack's `usr/lib` is swept, which is exactly what
    `libs` means: the loose libraries the installer copies into the shared
    `usr/lib` beside the base app's, and the ones an uninstall deletes from it.
    A toolchain's own tree below that (`usr/lib/jvm/...`, `usr/lib/ruby/...`) is
    attributed as the toolchain itself in NOTICE.md's On-Demand Toolchains
    table; widening this to reach it would demand a per-file entry for every
    object in a JDK.

    Symlinks are skipped. A soname alias points at a file that is already
    listed, so reporting it would report one library twice under a second name.

    Empty on a tree whose packs were never downloaded: there is no `usr/lib` to
    read. A pack that IS on disk with no manifest at all is a different failure
    with a clearer message, which is why `unrecorded` is answered first in
    `main()` and this never sees that pack.
    """
    out = []
    for module in sorted(TOOLCHAINS.glob("toolchain_*")):
        assets = module / "src/main/assets"
        lib_dir = assets / "usr/lib"
        if not lib_dir.is_dir():
            continue
        listed = set()
        for manifest in sorted(assets.glob("*.json")):
            try:
                data = json.loads(manifest.read_text(encoding="utf-8"))
            except (OSError, ValueError) as exc:
                raise SystemExit(f"FAIL cannot read {manifest}: {exc}")
            listed.update(data.get("libs", []))
        for path in sorted(lib_dir.iterdir()):
            if path.is_symlink() or not path.is_file() or ".so" not in path.name:
                continue
            if path.name not in listed:
                out.append(f"{module.name}: usr/lib/{path.name}")
    return out


def toolchain_notices():
    """Packs on disk whose tree carries no upstream notice file at all.

    A backstop, and deliberately a coarse one. The per-file question this module
    asks of the base tree cannot be asked of a toolchain: `unlisted_toolchain_libs`
    explains why, and widening either check to reach inside a JDK would demand a
    row per object. What can be asked instead is whether the notices upstream
    shipped are still in the pack, because a download script that strips them
    redistributes the binaries with none of their attribution and nothing else
    here would notice.

    That is not hypothetical. `download-java.sh` deleted the JDK's `legal/`
    directory, filed with `demo/` and `man/` under one comment about stripping
    documentation, and `legal/` is not documentation: it is the notice set for the
    seventy modules the pack still ships.

    Coarse in both directions, stated so nobody reads more into a pass than is
    there. It cannot tell a complete notice set from a partial one, and it cannot
    see a pack that was never downloaded, since there is no tree to read.

    Where it actually fires, measured rather than assumed: `build.yml` never runs
    a toolchain download, so the pull-request run has no pack to read and this
    passes over nothing. `release.yml` runs `download-java.sh` before its second
    call to this script, so that is the run that answers. A developer who has
    built a pack locally gets the answer too, and a pack built before the download
    script stopped stripping notices reddens until it is rebuilt, which is correct
    rather than a false alarm: that tree really would ship without attribution.
    """
    out = []
    for module in sorted(TOOLCHAINS.glob("toolchain_*")):
        usr = module / "src/main/assets/usr"
        if not usr.is_dir():
            continue
        found = False
        for path in usr.rglob("*"):
            if path.is_symlink() or not path.is_file():
                continue
            name = path.name.lower()
            if name.startswith(("license", "licence", "copying", "notice")) or \
                    name.endswith((".md", ".txt")) and "licen" in name:
                found = True
                break
        if not found:
            out.append(module.name)
    return out


def notice_missing(components):
    """Components with no upstream notice among the bytes that reach a device.

    The obligation the attribution documents do not discharge. `attributed_in`
    asks whether this repository names a component; this asks whether the person
    holding the APK is given the notice its licence says has to travel with the
    binary. Those were the same question only for the copyleft components, whose
    licence texts are bundled by the `bundleNotices` task, and for nobody else.

    Answered off the tree rather than off a document, because that is what
    ships: a row in NOTICE.md is a claim about a file, and the file is what the
    licence asks for. Both roots are searched, since a component can ship in the
    base module, in a pack, or in both -- libffi is listed by TOOLCHAIN_LIBRARIES
    and its notice is the base module's copy.

    A component with no NOTICE_DIRS entry is reported here rather than skipped.
    The alternative fails open: adding a library to LIBRARIES and forgetting the
    notice would then be indistinguishable from a component that needs none.
    """
    roots = [ASSETS] + [
        module / "src/main/assets"
        for module in sorted(TOOLCHAINS.glob("toolchain_*"))
        if (module / "src/main/assets/usr").is_dir()
    ]
    missing = []
    for component in sorted(components):
        rel = NOTICE_DIRS.get(component)
        if rel is None:
            missing.append(f"{component}: no NOTICE_DIRS entry")
            continue
        best = 0
        for root in roots:
            directory = root / rel
            if not directory.is_dir():
                continue
            for path in directory.iterdir():
                if path.is_symlink() or not path.is_file():
                    continue
                if not path.name.lower().startswith(NOTICE_NAMES):
                    continue
                best = max(best, path.stat().st_size)
        if best == 0:
            missing.append(f"{component}: no notice file in {rel}")
        elif best < MIN_NOTICE_BYTES:
            missing.append(
                f"{component}: {rel} holds nothing over {MIN_NOTICE_BYTES} bytes "
                f"(largest {best})"
            )
    return missing


def shipped():
    """The redistributed binaries at the top level of the two flat directories,
    by file name.

    Everything deeper is `nested()`'s, and the two must not overlap or a file
    would be reported twice with two different identifiers. The split is drawn
    at exactly what this function accepts -- top level of `usr/lib` AND `.so` in
    the name -- so an ELF at the top of `usr/lib` whose name says nothing (there
    are none today, measured) still reaches `nested()` rather than falling
    between them.
    """
    out = []
    for root in (USR_LIB, JNILIBS):
        if not root.is_dir():
            continue
        for p in sorted(root.iterdir()):
            if p.is_symlink() or not p.is_file():
                continue
            if ".so" not in p.name:
                continue
            out.append(p.name)
    return out


def nested():
    """Every other redistributed binary in the asset tree, by path relative to
    `assets/`.

    The path, not the name, because the name is not an identifier here: `rg`,
    `tgrep` and `kerberos.node` each ship more than once from more than one
    package, and a failure has to say which copy it means.

    A file counts as a binary if it opens with one of BINARY_MAGIC or ends in
    `.node`. Testing ELF alone would walk past most of what ships here: see the
    module docstring for the formats and why an object this device cannot execute
    still has to be attributed.
    """
    out = []
    if not ASSETS.is_dir():
        return out
    for p in sorted(ASSETS.rglob("*")):
        if p.is_symlink() or not p.is_file():
            continue
        if p.parent == USR_LIB and ".so" in p.name:
            continue  # counted by shipped()
        try:
            with p.open("rb") as f:
                head = f.read(4)
        except OSError:
            continue
        if head.startswith(BINARY_MAGIC) or p.suffix == ".node":
            out.append(p.relative_to(ASSETS).as_posix())
    return out


def nested_pattern(rel):
    """The allowlist entry that claims a nested path, or None."""
    for pattern in NESTED_ORDER:
        if fnmatch.fnmatchcase(rel, pattern):
            return pattern
    return None


def subtree_present(prefix):
    """Whether a directory prefix is on disk.

    Resolved with `Path.glob` rather than tested as a literal, because
    `usr/lib/python*` has to find whichever version the Termux index resolved to
    at build time.

    Resolving it matters, and the literal-prefix version of this got it wrong:
    truncating `usr/lib/python*/*` at its first glob leaves `usr/lib`, which
    exists in any tree that ran download-termux-tools.sh. A checkout that had
    not also run download-python.sh was then told its Python entry was stale,
    when Python was simply not there. Measured on exactly that tree.
    """
    return any(p.is_dir() for p in ASSETS.glob(prefix))


def inner_patterns(pattern):
    """The entries nested strictly inside this one.

    Every pattern is `<prefix>/*`, so containment is a string prefix test on
    `<prefix>/` -- the same relation the longest-match ordering resolves.
    """
    return [p for p in NESTED_LIBRARIES if p != pattern and p.startswith(pattern[:-1])]


def outer_pattern(pattern):
    """The entry that would claim this one's files if it stopped matching them,
    or None if nothing contains it.

    The innermost such entry, because that is the one the longest-match ordering
    would hand the files to.
    """
    outer = [p for p in NESTED_LIBRARIES if p != pattern and pattern.startswith(p[:-1])]
    return max(outer, key=len) if outer else None


def stale_anchor(pattern):
    """The directory whose presence on disk makes this entry's silence worth
    reporting.

    For an entry that nothing contains, that is its own directory. A tree can
    hold `usr/` without `vscode-reh/` or the reverse, because two scripts fetch
    them and a checkout may have run only one, so an entry whose package is
    simply not here is not stale; it is not this tree's business. Nothing else in
    the map could have claimed its files either, so if the package IS here and
    moved, the files surface at the `not classified` check instead.

    For an override -- an entry nested inside a broader one -- it is the
    CONTAINING package's directory instead, and the difference is the whole
    reason this function exists. An override goes silent exactly when its files
    move out from under it, and the move takes its own directory with it. Anchor
    on that directory and the check goes quiet at the one moment it is needed:
    the files are still in the tree, still shipped, now swallowed by the broader
    entry and attributed to the wrong component, with nothing reported. The
    containing directory survives the move, so it is what the question has to be
    asked about.

    Concretely: Copilot ships ripgrep at `.../copilot-linux-arm64/ripgrep/rg`,
    and the override there records it as ripgrep/MIT rather than as the
    proprietary CLI around it. Move it to `.../copilot-linux-arm64/bin/rg` and
    the container wins it; anchored on `ripgrep/` nothing would notice, anchored
    on `copilot-linux-arm64/` the override is reported as claiming nothing.
    """
    return (outer_pattern(pattern) or pattern).rsplit("/*", 1)[0]


def stale_patterns(nest):
    """Allowlist entries that claim nothing in a tree that should have work for
    them.

    An entry that matches no file is not harmless. It is the state a rename
    leaves behind, and the rename is what makes the binaries invisible: the
    package moves, its files stop matching, they arrive at the classifier as
    unknown paths -- or, worse, are swallowed by a broader entry that still
    matches and attributed to the wrong component. The first half fails the build
    on its own, at the `not classified` check. The second half is silent, and it
    is what `stale_anchor` is built around.

    So an entry has to earn its place by WINNING for at least one file, not
    merely by being satisfiable. The difference is the whole point of the
    overrides: `@github/copilot .../ripgrep/*` is contained by the Copilot entry
    around it, and if the longest-first ordering were lost it would still
    "match" every one of those files while never being consulted.

    One exemption, for the opposite error. A container whose every file was taken
    by an override inside it wins nothing either, and it is not stale: that is
    the ordering working as designed, and failing there would refuse a tree whose
    attribution is complete and correct.
    """
    won = {nested_pattern(rel) for rel in nest}
    stale = []
    for pattern in sorted(NESTED_LIBRARIES):
        if pattern in won:
            continue
        if any(inner in won for inner in inner_patterns(pattern)):
            continue  # shadowed by its own overrides, not left behind
        if subtree_present(stale_anchor(pattern)):
            stale.append(pattern)
    return stale


def attributed_in(text):
    """The set of component names a notices document actually attributes,
    matched whole rather than as substrings.

    Two narrowings, each because the looser version was satisfied by something
    that attributes nothing. A search over the whole document passes on any
    passing mention, "Git" appears on sixteen lines in LEGAL_NOTICES.md.
    Narrowing to table rows is not enough: libiconv's "Linked by" cell says "Bash
    and every Git executable". Narrowing to the first cell is still not enough:
    the heading "### @github/copilot (GitHub Copilot CLI)" contains "Git" inside
    "GitHub", so deleting Git's own row and heading left the check green.
    """
    names = set()
    for line in text.splitlines():
        stripped = line.lstrip()
        if stripped.startswith("#"):
            names.add(stripped.lstrip("#").strip())
        elif stripped.startswith("|"):
            cells = stripped.split("|")
            if len(cells) > 1:
                cell = cells[1].strip()
                # `[GMP](https://gmplib.org)` -> `GMP`
                link = re.match(r"^\[([^\]]+)\]\(", cell)
                names.add(link.group(1) if link else cell)
    return names


def normalised(name):
    """A component name reduced to what two spellings of it have in common.

    The keys in LIBRARIES and the bold names in the source offer are written by
    hand in two different files, so they agree on the words without always
    agreeing on the case or on a trailing full stop. Folding both sides through
    here keeps a cosmetic difference from reading as a missing source offer,
    which would be a false alarm nobody could act on. It deliberately stops
    there: anything looser is a substring test again, and a substring test is
    what let one component's bullet answer for another's.
    """
    return " ".join(name.split()).strip("*_ .,:;").casefold()


def offered_in(section):
    """The set of component names the source-offer section's bullets declare.

    A set, tested for membership, rather than a search for the name inside the
    section's text, and the difference is not academic. The libiconv bullet ends
    "linked by Bash and by every Git executable", so with the substring test in
    place both Bash's and Git's own bullets could be deleted and the section
    still read as offering source for both: the two GPL tools with the widest
    reach here were exactly the two whose loss the check could not see. Only
    deleting libiconv as well, which nothing else in the section names, turned it
    red.

    A bullet is `- **Name** (LICENCE): where the source is`. The bold name is
    what a reader of the document looks for, so it is what is matched, whole. A
    component that cannot be matched by name is reported as unoffered rather than
    waved through, so a bullet rewritten out of that shape fails loudly.
    """
    return {
        normalised(m.group(1))
        for m in re.finditer(r"^\s*[-*]\s+\*\*(.+?)\*\*\s*\(", section, re.MULTILINE)
    }


def main():
    names = shipped()
    nest = nested()
    if not names and not nest:
        # Neither tree is committed, so an empty run means the assets were never
        # downloaded. Saying so beats reporting that nothing is unattributed.
        # Both halves, because a tree can hold one without the other: the server
        # tree is fetched by a different script than the Termux libraries, and a
        # checkout that has only run one of the two still ships binaries.
        print("skip -- no built assets present (run the download scripts first)")
        return 0

    notices = LEGAL_NOTICES.read_text(encoding="utf-8") if LEGAL_NOTICES.is_file() else ""
    if not notices:
        print(f"FAIL {LEGAL_NOTICES} is missing", file=sys.stderr)
        return 1

    # Both documents, because both are published as this project's attribution
    # and a component has to be in each. Keyed by name so a failure can say which
    # file is short of it rather than just that something is.
    docs = {LEGAL_NOTICES.name: attributed_in(notices)}
    notice = NOTICE.read_text(encoding="utf-8") if NOTICE.is_file() else ""
    if not notice:
        print(f"FAIL {NOTICE} is missing", file=sys.stderr)
        return 1
    docs[NOTICE.name] = attributed_in(notice)

    # The source-availability section, isolated so a component merely mentioned
    # elsewhere in the file cannot satisfy the copyleft check.
    m = re.search(r"^## GPL Source Code Availability$(.*?)^## ", notices,
                  re.MULTILINE | re.DOTALL)
    offer = m.group(1) if m else ""
    if not offer:
        print("FAIL no '## GPL Source Code Availability' section in LEGAL_NOTICES.md",
              file=sys.stderr)
        return 1
    # Parsed into the set of names its bullets declare, because isolating the
    # section only stops a mention elsewhere in the file from counting; it does
    # nothing about one bullet mentioning another bullet's component.
    offered = offered_in(offer)

    unknown, unattributed, unoffered, unlicensed = [], [], [], []
    # Toolchain payloads are checked alongside the base tree. They ship to fewer
    # devices, not to none, and the obligations do not scale with audience.
    for_check = [(n, LIBRARIES.get(n)) for n in names]
    tc, manifests, unrecorded = toolchain_libs()
    for_check += [(n, TOOLCHAIN_LIBRARIES.get(n)) for n in tc]
    # The components whose notice has to travel as a FILE, which is the two flat
    # maps above and not `nested`. Everything nested arrives inside its own npm
    # package or extension directory and carries whatever licence file its
    # publisher put there; these arrive as a bare `.so` copied out of a .deb,
    # with the notice left behind in the package unless a download script places
    # it. Kept as a set here because `for_check` mixes all three sources.
    #
    # Restricted to the base tree plus the packs whose payload is on disk. The
    # notice is a file INSIDE the pack, and a manifest can be present while its
    # payload is not, so asking a clean checkout for those files fails a build
    # over bytes no step has placed yet. See toolchain_libs_with_payload.
    built = set(toolchain_libs_with_payload())
    native = {
        entry[0]
        for n, entry in for_check
        if entry and entry[0] != "VSCodroid" and (n in names or n in built)
    }
    # Everything below the top level of those two directories, held to exactly
    # the same three checks. Identified by path, so the three lists cannot
    # collide on a shared file name.
    #
    # Classified once and kept, because the tally at the end of this function
    # needs the same answer. Looking it up a second time there had to index
    # NESTED_LIBRARIES with whatever nested_pattern returned, and that is None
    # for an unclassified path: a KeyError and a traceback in place of the
    # report. It could not fire only because the `not classified` check returns
    # first, which is a fact about the order of two blocks eighty lines apart
    # rather than about this line.
    nested_entries = [(r, NESTED_LIBRARIES.get(nested_pattern(r))) for r in nest]
    for_check += nested_entries

    stale = stale_patterns(nest)
    if stale:
        print(f"FAIL {len(stale)} allowlist entr(ies) match nothing in a tree that "
              "has the subtree:", file=sys.stderr)
        for pattern in stale:
            print(f"  {pattern}", file=sys.stderr)
        print("  -> the package moved, was renamed, or stopped shipping binaries."
              " Point the entry at where its files are now, or delete it -- but"
              " check first that the binaries did not simply move under an entry"
              " that attributes them to something else", file=sys.stderr)
        return 1

    if unrecorded:
        # The pack was downloaded on this machine and is about to be zipped, so
        # its libraries are real; without the manifest there is nothing to check
        # them against and the run would otherwise report them as absent.
        print(f"FAIL {len(unrecorded)} toolchain pack(s) built here carry no manifest:",
              file=sys.stderr)
        for module in unrecorded:
            print(f"  {module}", file=sys.stderr)
        print("  -> its download script writes the manifest beside usr/; without it"
              " nothing knows what the pack ships", file=sys.stderr)
        return 1

    # After `unrecorded`, deliberately. A pack with a `usr/` payload and no
    # manifest would come through here as every one of its libraries being
    # unlisted, which is true and useless: the fix is to write the manifest, not
    # to add forty entries to it.
    unlisted = unlisted_toolchain_libs()
    if unlisted:
        print(f"FAIL {len(unlisted)} loose toolchain librar(ies) are in a pack that "
              "its manifest does not list:", file=sys.stderr)
        for entry in unlisted:
            print(f"  {entry}", file=sys.stderr)
        print("  -> the manifest is what this run reads to know what a pack ships,"
              " so a file missing from `libs` is redistributed with nobody"
              " attributing it. Add it to the `libs` array the download script"
              " writes, and give it a row in TOOLCHAIN_LIBRARIES and in both"
              " attribution documents", file=sys.stderr)
        return 1

    # After the two above, and for the same reason they are ordered: a pack with
    # no manifest is already reported, and a pack whose payload was never
    # downloaded has no tree for either check to read.
    stripped = toolchain_notices()
    if stripped:
        print(f"FAIL {len(stripped)} toolchain pack(s) carry no upstream notice file:",
              file=sys.stderr)
        for module in stripped:
            print(f"  {module}", file=sys.stderr)
        print("  -> the pack redistributes upstream binaries with none of their"
              " attribution. The notices ship inside the tree, beside what they"
              " describe; check that the download script is not stripping them,"
              " and that it copies with -L so none of them arrives as a symlink,"
              " which neither an asset pack nor a release ZIP can carry",
              file=sys.stderr)
        return 1

    # NOTICE_DIRS is read for the components of the two FLAT maps and nothing
    # else, so a key naming anything outside them is never looked up: it reads
    # as coverage and is not. "distlib" sat here exactly that way, a component
    # of NESTED_LIBRARIES, which `notice_missing` excludes on purpose because a
    # nested package carries its own LICENSE inside itself.
    #
    # Static, so it is asked before the tree checks and answers the same on a
    # bare checkout: both maps are literals in this file.
    orphans = sorted(
        set(NOTICE_DIRS)
        - {entry[0] for entry in LIBRARIES.values()}
        - {entry[0] for entry in TOOLCHAIN_LIBRARIES.values()}
    )
    if orphans:
        print(f"FAIL {len(orphans)} NOTICE_DIRS entr(ies) name no shipped component:",
              file=sys.stderr)
        for component in orphans:
            print(f"  {component}", file=sys.stderr)
        print("  -> nothing reads them, so the directory they point at is never"
              " checked for a notice. Either the component was renamed in"
              " LIBRARIES or TOOLCHAIN_LIBRARIES, in which case rename it here"
              " too, or it is nested and answers for itself, in which case"
              " delete the entry", file=sys.stderr)
        return 1

    # After the toolchain checks and before the per-file classification, for the
    # reason those are ordered: a pack that was never downloaded contributes no
    # component here, so this asks only about trees that exist.
    no_notice = notice_missing(native)
    if no_notice:
        print(f"FAIL {len(no_notice)} shipped component(s) reach a device with no "
              "notice of their own:", file=sys.stderr)
        for entry in no_notice:
            print(f"  {entry}", file=sys.stderr)
        print("  -> the licence requires the copyright and permission text to"
              " accompany the binary, and a row in NOTICE.md is not that text."
              " termux_copy_notices in scripts/lib/termux-packages.sh places what"
              " upstream ships into usr/share/doc/<package>; re-run the download"
              " script for the component named, and add a NOTICE_DIRS entry if it"
              " is new", file=sys.stderr)
        return 1

    for name, entry in for_check:
        if entry is None:
            unknown.append(name)
            continue
        component, licence = entry
        if component == "VSCodroid":
            continue
        if not licence.strip():
            # An empty licence answers the copyleft question with "no" for free,
            # which is the one answer nobody should get without deciding it.
            unlicensed.append(f"{name} ({component})")
        short = [doc for doc, known in docs.items() if component not in known]
        if short:
            unattributed.append(f"{name} ({component}), absent from {', '.join(short)}")
        if any(c in licence.upper() for c in COPYLEFT) and normalised(component) not in offered:
            unoffered.append(f"{name} ({component}, {licence})")

    for label, items, hint in (
        ("recorded with no licence at all", unlicensed,
         "fill in the licence Termux's build.sh declares; an empty field silently "
         "answers the copyleft question with 'no'"),
        ("not classified", unknown,
         "a bare file name belongs in LIBRARIES with the licence Termux's build.sh "
         "declares; a path belongs in NESTED_LIBRARIES, keyed on the subtree of the "
         "package that redistributes it"),
        ("not attributed", unattributed,
         "add a section naming the project and its licence to each file listed"),
        ("copyleft, absent from the source offer", unoffered,
         "add it under '## GPL Source Code Availability'"),
    ):
        if items:
            print(f"FAIL {len(items)} shipped {label}:", file=sys.stderr)
            for i in items:
                print(f"  {i}", file=sys.stderr)
            print(f"  -> {hint}", file=sys.stderr)

    if unknown or unattributed or unoffered or unlicensed:
        return 1

    covered = {c for _, (c, _) in ((n, e) for n, e in for_check if e) } - {"VSCodroid"}
    # The manifest count is in the line because the toolchain figure is otherwise
    # unreadable: "0 toolchain libraries" is what a tree with no packs prints and
    # also what a run before the download step prints, and those are opposite
    # facts. Naming the documents for the same reason, the count of components
    # says nothing about which files were held to it.
    print(f"ok: {len(names)} shipped binaries + {len(nest)} nested + "
          f"{len(tc)} toolchain libraries from {len(manifests)} manifest(s), "
          f"{len(covered)} components, all attributed in {' and '.join(docs)}")
    # The names, not just the count. A count alone cannot answer the question that
    # actually matters when two trees disagree -- *which* file is missing -- and
    # some of these are found by `dlopen` at run time rather than through
    # DT_NEEDED, so the ELF gate cannot see their absence either. That is the shape
    # of the bug that left five Python modules dead on shipped builds. Printing the
    # list costs a line of log and makes any two builds directly comparable.
    print("   " + " ".join(sorted(names)))
    # The nested set by component and count rather than by path: two hundred
    # paths is eight kilobytes of log, and the question the flat list answers --
    # do two builds ship the same thing -- is answered here by any count that
    # moves. A Python module dropping out of lib-dynload takes `Python` from 76
    # to 75.
    if nested_entries:
        tally = {}
        for _, entry in nested_entries:
            # Reusing the classification made above rather than repeating it.
            # Nothing unclassified can reach here today, and the fallback is not
            # a guess about that: it keeps a reordering of the checks above
            # printing a report instead of raising.
            component = entry[0] if entry else "unclassified"
            tally[component] = tally.get(component, 0) + 1
        print("   nested: " + ", ".join(f"{c} {n}" for c, n in sorted(tally.items())))
    return 0


if __name__ == "__main__":
    sys.exit(main())
