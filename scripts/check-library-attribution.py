#!/usr/bin/env python3
"""Refuse a build that ships a library nobody has attributed.

    check-library-attribution.py

Every shared library under `assets/usr/lib` and every executable under
`jniLibs/arm64-v8a` is redistributed inside the APK, and all of them arrive from
Termux or Alpine rather than from this repository. Permissive licences require
their notice to travel with the binary; GPL and LGPL additionally require an
offer of the corresponding source. Neither obligation is discharged by code, so
nothing in the build could previously notice when one went unmet.

One did. `libdb-18.1.so` -- Berkeley DB, **AGPL-3.0-only**, the strongest
copyleft in common use -- shipped in every release with no attribution anywhere
and no source offer, because it arrived as a transitive dependency of krb5 and
was added to a package list without anyone asking what its licence was. It
turned out nothing linked it at all, so it was dropped rather than documented;
but `libgdbm`, `liblzma` and `libzstd` are used, are copyleft, and were missing
from the source offer for the same reason.

Three checks, in the order a new library trips them:

  1. Every shipped file maps to a known component. A library nobody has
     classified fails here, which is the moment to look up its licence.
  2. Every component is named in docs/LEGAL_NOTICES.md.
  3. Every component whose licence is GPL/LGPL/AGPL is additionally named in
     that file's source-availability section. Attribution alone does not
     discharge a copyleft obligation.

The map below is the licence record. Its source is Termux's own
`TERMUX_PKG_LICENSE`, which is what these packages are actually built from.
"""

import json
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
USR_LIB = ROOT / "android/app/src/main/assets/usr/lib"
JNILIBS = ROOT / "android/app/src/main/jniLibs/arm64-v8a"
NOTICES = ROOT / "docs/LEGAL_NOTICES.md"
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

# soname (or executable) -> (component name as written in LEGAL_NOTICES, licence)
#
# "VSCodroid" as the component means the file is built from this repository's
# own source and is covered by the root LICENSE; it needs no third-party
# attribution. The glibc-shim stubs are the only such case: they carry glibc's
# DT_NEEDED names but contain none of glibc's code.
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
    "libbz2.so": ("bzip2", "BSD-4-Clause"),
    "libbz2.so.1.0": ("bzip2", "BSD-4-Clause"),
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


def toolchain_libs():
    """Every library the shipped toolchain manifests declare, by file name.

    Read from the manifests rather than from disk: the packs are built by CI and
    are not present in a working tree, so a disk scan would find nothing and
    report success. The manifests are committed, so this answers the same on any
    machine.
    """
    out = []
    for manifest in sorted(TOOLCHAINS.glob("toolchain_*/src/main/assets/*.json")):
        try:
            data = json.loads(manifest.read_text(encoding="utf-8"))
        except (OSError, ValueError) as exc:
            raise SystemExit(f"FAIL cannot read {manifest}: {exc}")
        out.extend(data.get("libs", []))
    return sorted(set(out))


def shipped():
    """Every redistributed binary, by file name."""
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


def main():
    names = shipped()
    if not names:
        # Neither tree is committed, so an empty run means the assets were never
        # downloaded. Saying so beats reporting that nothing is unattributed.
        print("skip -- no built assets present (run the download scripts first)")
        return 0

    notices = NOTICES.read_text(encoding="utf-8") if NOTICES.is_file() else ""
    if not notices:
        print(f"FAIL {NOTICES} is missing", file=sys.stderr)
        return 1

    # The source-availability section, isolated so a component merely mentioned
    # elsewhere in the file cannot satisfy the copyleft check.
    m = re.search(r"^## GPL Source Code Availability$(.*?)^## ", notices,
                  re.MULTILINE | re.DOTALL)
    offer = m.group(1) if m else ""
    if not offer:
        print("FAIL no '## GPL Source Code Availability' section in LEGAL_NOTICES.md",
              file=sys.stderr)
        return 1

    unknown, unattributed, unoffered, unlicensed = [], [], [], []
    # Toolchain payloads are checked alongside the base tree. They ship to fewer
    # devices, not to none, and the obligations do not scale with audience.
    for_check = [(n, LIBRARIES.get(n)) for n in names]
    tc = toolchain_libs()
    for_check += [(n, TOOLCHAIN_LIBRARIES.get(n)) for n in tc]

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
        if component not in notices:
            unattributed.append(f"{name} ({component})")
        if any(c in licence.upper() for c in COPYLEFT) and component not in offer:
            unoffered.append(f"{name} ({component}, {licence})")

    for label, items, hint in (
        ("recorded with no licence at all", unlicensed,
         "fill in the licence Termux's build.sh declares; an empty field silently "
         "answers the copyleft question with 'no'"),
        ("not classified", unknown,
         "add it to LIBRARIES with the licence Termux's build.sh declares"),
        ("not attributed in LEGAL_NOTICES.md", unattributed,
         "add a section naming the project and its licence"),
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
    print(f"ok -- {len(names)} shipped binaries + {len(tc)} toolchain libraries, "
          f"{len(covered)} components, all attributed")
    # The names, not just the count. A count alone cannot answer the question that
    # actually matters when two trees disagree -- *which* file is missing -- and
    # some of these are found by `dlopen` at run time rather than through
    # DT_NEEDED, so the ELF gate cannot see their absence either. That is the shape
    # of the bug that left five Python modules dead on shipped builds. Printing the
    # list costs a line of log and makes any two builds directly comparable.
    print("   " + " ".join(sorted(names)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
