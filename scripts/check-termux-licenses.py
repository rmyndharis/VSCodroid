#!/usr/bin/env python3
"""Compare each recorded Termux licence against the one Termux declares.

    check-termux-licenses.py

check-library-attribution.py decides whether a shipped binary needs an offer of
source by reading a licence out of a table written by hand. That table says its
source is Termux's `TERMUX_PKG_LICENSE`, and nothing had ever read the field
back, so a mistyped row, a line copied from its neighbour, or a package that
relicensed would agree with itself forever. Two published documents,
docs/LEGAL_NOTICES.md and NOTICE.md, are written from the same table, and the
copyleft rule the gate runs is only as good as the string it is handed.

Where the field actually lives, measured rather than assumed. Three places that
look like they should carry it do not:

  * the signed `Packages` index has no `License` field at all: zero lines across
    the whole index against 2918 `Homepage:` lines, so the absence belongs to
    the field and not to the reader;
  * neither has a .deb's `control` member. bash 5.3.15 declares Package,
    Version, Homepage, Depends and eight more, and no licence;
  * `usr/share/doc/<pkg>/copyright` inside the .deb payload is a symlink naming
    the licence for some packages (bash's points at `../../LICENSES/GPL-3.0.txt`)
    but free text for most and missing for a good many. It is a document, not a
    field, and it cannot be compared against a string.

`packages/<pkg>/build.sh` in termux-packages carries it on one line, and it is
demonstrably the line the table was transcribed from: liblzma's reads
`"LGPL-2.1, GPL-2.0, GPL-3.0"`, byte for byte what LIBRARIES records. So that is
what this reads, over HTTPS, one small file per package, cached for a week
beside the package index.

A report, not a gate, with one exception.

Termux moves under this project by design, and a repackaging that rewords a
licence field is ordinary upstream activity. A check that failed the build on it
would be switched off within a week; write-build-manifest.py reasons the same
way about its own comparison. So a difference is printed and the run succeeds.

The exception is the one direction with a legal consequence: a component
recorded permissive where Termux declares copyleft ships with no offer of
source, and the attribution gate cannot notice, because it asks the same wrong
table. That fails. It cannot fire on a reword, a version bump, or a second
licence being added to an already copyleft field, because all of those leave the
copyleft verdict alone and land in the reported half. It cannot fire when
upstream could not be read either: no network, or a package whose build.sh is
not where it should be, ends in a printed note and exit 0. Failing closed on an
unsigned HTTPS fetch would turn an outage at raw.githubusercontent.com into a
licence problem, and that fetch is advisory here precisely because it is
unsigned; every payload this project ships is still anchored to Termux's own
signature by verify-termux-index.sh.

Two things this must not do, both of which would damage the gate it checks.

  * It must not rewrite either side into SPDX. LIBRARIES deliberately holds
    spelled-out names beside identifiers, because COPYLEFT matches
    case-insensitive substrings and "Mozilla Public License 2.0" contains no
    "MPL". Canonicalising would delete the reason those terms exist. The
    comparison folds case and punctuation only, so `BSD-3-Clause` and
    `BSD 3-Clause` are one answer while `BSD-4-Clause` and `BSD` stay two.
  * It must not read `custom` as permissive. Three packages declare it, and it
    means Termux has not said, not that it said no. Those are reported as
    unstated and never reach the failing branch.

Which packages are compared comes from committed source, not from a built tree:
each download script's `get_sonames()`, plus the table below for the binaries
that are renamed rather than copied. So this answers for the repository, and
runs on a fresh checkout that has downloaded nothing.
"""

import fnmatch
import importlib.util
import pathlib
import re
import sys
import time
import urllib.error
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parent.parent
SCRIPTS = ROOT / "scripts"
GATE = SCRIPTS / "check-library-attribution.py"
CACHE = ROOT / "toolchains/termux-packages/licenses.tsv"
CACHE_MAX_AGE_DAYS = 7
BUILD_SH = ("https://raw.githubusercontent.com/termux/termux-packages/"
            "master/packages/{pkg}/build.sh")

# The value Termux writes when the terms are in the package's own file rather
# than in a name it can spell. Not the same statement as a permissive licence,
# which is why it is routed away from the comparison instead of into it.
UNSTATED = "custom"

# Binaries a download script renames on the way into jniLibs, and the package
# each arrives from.
#
# `get_sonames()` cannot answer for these: it maps the shared libraries a package
# ships under their own soname, while these are executables copied to a `lib*.so`
# name so that the package manager extracts them with the execute bit. They are
# also the copyleft half of the tree, bash and git and make, so leaving them out
# would drop exactly the rows worth checking.
#
# Patterns rather than literals: the Python runtime carries its version in the
# file name and download-python.sh resolves that version from the index at build
# time, so a literal here would go stale on a rebuild nobody connects to this
# file.
RENAMED = {
    "bash": ("libbash.so",),
    "git": ("libgit.so", "libgit-remote-curl.so"),
    "make": ("libmake.so",),
    "openssh": ("libssh.so", "libssh-keygen.so"),
    "tmux": ("libtmux.so",),
    # download-node.sh names its package in a bare variable rather than in a
    # REQUIRED_PACKAGES array, so this row is also what puts it in the subject
    # set at all.
    "nodejs-lts": ("libnode.so",),
    "python": ("libpython.so", "libpython3.*.so"),
}

# The body of a `get_sonames()` shell function, and one arm of the case inside
# it. Read out of the download scripts rather than copied here, so the two
# cannot drift: a soname added there is compared here on the next run.
SONAME_FN = re.compile(r"^get_sonames\(\)\s*\{(.*?)^\}", re.M | re.S)
CASE_ARM = re.compile(r'^\s*([A-Za-z0-9_.+-]+)\)\s*echo\s+"([^"]*)"', re.M)

# `TERMUX_PKG_LICENSE="GPL-3.0"`, quoted or not.
LICENSE_FIELD = re.compile(r"^TERMUX_PKG_LICENSE=(.*)$", re.M)


class Unreachable(Exception):
    """Upstream could not be contacted at all, as opposed to answering."""


def attribution():
    """The attribution gate, imported for its tables rather than run.

    Imported instead of re-stated so there is one licence record. The file name
    has hyphens in it, which is why this goes through importlib rather than a
    plain import; nothing at its module level runs anything.
    """
    spec = importlib.util.spec_from_file_location("check_library_attribution", GATE)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def widen(soname):
    """The pattern that claims a soname and the unversioned name beside it.

    `liblzma.so.5` and `liblzma.so` are one library under two names and the map
    carries both, so a row naming the versioned one has to claim the other or
    half the entry goes unchecked, copyleft included.

    Only a versioned soname is widened. Doing the same to a bare `.so` would let
    `libcrypt.so`, Termux's BSD-2-Clause crypt(3), claim `libcrypt.so.1` beside
    it, which is this repository's own glibc stub under a confusingly similar
    name and a different component entirely.
    """
    base = re.match(r"^(.*\.so)\.[0-9]", soname)
    return base.group(1) + "*" if base else soname


def subjects():
    """package -> the patterns matching the files the build places for it, and
    the download scripts whose `get_sonames()` this could not read.

    The second half is what keeps a narrowed report from reading like a clean
    one. A parse that stops matching does not empty this answer, because RENAMED
    below fills it either way, so the population is counted twice by two
    different means: which scripts contain the function at all, by name, and
    which of them this regex got case arms out of. A script in the first set and
    not the second is reported rather than dropped.
    """
    rows, unparsed = {}, []
    for script in sorted(SCRIPTS.glob("download-*.sh")):
        text = script.read_text(encoding="utf-8")
        if "get_sonames()" not in text:
            continue
        body = SONAME_FN.search(text)
        arms = CASE_ARM.findall(body.group(1)) if body else []
        if not arms:
            unparsed.append(script.name)
            continue
        for pkg, sonames in arms:
            for soname in sonames.split():
                rows.setdefault(pkg, set()).add(widen(soname))
    for pkg, patterns in RENAMED.items():
        rows.setdefault(pkg, set()).update(patterns)
    return rows, unparsed


def fold(licence):
    """Case and punctuation flattened, and nothing else.

    `BSD-3-Clause` and `BSD 3-Clause` are the same answer written twice. This is
    deliberately not an SPDX mapping: `GPLv3` is not folded onto `GPL-3.0` and
    `Mozilla Public License` is not folded onto `MPL`, because the gate's
    copyleft test matches those spellings as substrings and a canonicaliser here
    would quietly argue for removing them there.
    """
    return " ".join(re.sub(r"[^0-9a-z]+", " ", licence.lower()).split())


def read_cache():
    """What upstream answered last time, as `package -> (licence, why not)`.

    An aged-out cache is dropped whole rather than per entry: the question it
    answers, what does Termux declare today, has one age.

    A refusal is cached beside an answer, and the empty licence beside its reason
    is what records one. Caching only the successes would leave one package
    fetched on every run, which is a wasted request when the network is there and
    is enough to send the whole report down the offline path when it is not.
    """
    if not CACHE.is_file():
        return {}
    if time.time() - CACHE.stat().st_mtime > CACHE_MAX_AGE_DAYS * 86400:
        return {}
    out = {}
    for line in CACHE.read_text(encoding="utf-8").splitlines():
        fields = line.split("\t")
        if len(fields) == 3:
            out[fields[0]] = (fields[1], fields[2])
    return out


def write_cache(found):
    """Keep only what this run asked about, so a dropped package leaves no line."""
    try:
        CACHE.parent.mkdir(parents=True, exist_ok=True)
        CACHE.write_text("".join(f"{pkg}\t{lic}\t{why}\n"
                                 for pkg, (lic, why) in sorted(found.items())),
                         encoding="utf-8")
    except OSError:
        pass  # a report that cannot cache is still a report


def fetch(pkg):
    """`TERMUX_PKG_LICENSE` for one package, as `(licence, why not)`.

    An HTTP answer, 404 included, is upstream speaking and is recorded against
    the package. Anything else means this machine could not get there, which is
    a fact about the run and not about the package, so it stops the fetching.

    A 404 is the ordinary answer for a subpackage: ncurses-ui-libs is built from
    ncurses and has no `build.sh` of its own. Its parent is not guessed from its
    name. The index carries no field naming it, and a wrong guess would report
    agreement with a licence belonging to another project, which is the one
    outcome this script must never produce.
    """
    url = BUILD_SH.format(pkg=pkg)
    try:
        with urllib.request.urlopen(url, timeout=30) as response:
            text = response.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as exc:
        return "", f"HTTP {exc.code} for packages/{pkg}/build.sh"
    except (urllib.error.URLError, OSError) as exc:
        raise Unreachable(exc) from exc
    field = LICENSE_FIELD.search(text)
    licence = field.group(1).strip().strip("\"'") if field else ""
    if not licence:
        return "", f"packages/{pkg}/build.sh states no TERMUX_PKG_LICENSE"
    return licence, ""


def main():
    gate = attribution()
    records = list(gate.LIBRARIES.items()) + list(gate.TOOLCHAIN_LIBRARIES.items())
    rows, unparsed = subjects()
    if unparsed:
        print(f"FAIL get_sonames() could not be read in {len(unparsed)} script(s): "
              f"{', '.join(unparsed)}", file=sys.stderr)
        print("  -> the function's shape changed; every package it names would "
              "silently drop out of this comparison", file=sys.stderr)
        return 1

    def copyleft(licence):
        return any(term in licence.upper() for term in gate.COPYLEFT)

    claimed, mapped, unmapped = set(), [], []
    for pkg, patterns in sorted(rows.items()):
        hits = [(name, entry) for name, entry in records
                if any(fnmatch.fnmatchcase(name, p) for p in patterns)]
        if hits:
            claimed.update(name for name, _ in hits)
            mapped.append((pkg, hits))
        else:
            unmapped.append(pkg)

    # Everything the network is needed for happens here, before any comparison,
    # so a run whose cache already holds every answer reports in full with the
    # network down and a run that is short of one reports nothing at all rather
    # than a partial tally that reads like a complete one.
    cache = read_cache()
    fetched = False
    try:
        for pkg, _ in mapped:
            if pkg not in cache:
                cache[pkg] = fetch(pkg)
                fetched = True
    except Unreachable as exc:
        print(f"skip -- could not reach raw.githubusercontent.com ({exc}); "
              "no licence was compared")
        return 0
    if fetched:
        # Only when something was actually fetched. Rewriting the file on a run
        # that answered entirely from it would touch its mtime, and the mtime is
        # what expires it, so a cache refreshed by its own reader would never be
        # refetched and the report would freeze at whatever it first saw.
        write_cache(cache)

    agree, differ, understated, unstated, unread = [], [], [], [], []
    for pkg, hits in mapped:
        theirs, why = cache[pkg]
        ours = sorted({licence for _, (_, licence) in hits})
        if not theirs:
            unread.append((pkg, why))
        elif theirs.lower() == UNSTATED:
            unstated.append((pkg, ours, theirs))
        elif all(fold(o) == fold(theirs) for o in ours):
            agree.append(pkg)
        elif copyleft(theirs) and not all(copyleft(o) for o in ours):
            understated.append((pkg, ours, theirs,
                                [n for n, (_, lic) in hits if not copyleft(lic)]))
        else:
            differ.append((pkg, ours, theirs))

    print(f"termux licences: {len(mapped) - len(unread)} of {len(mapped)} packages "
          f"compared against TERMUX_PKG_LICENSE, {len(agree)} agree")
    for pkg, ours, theirs in differ:
        print(f"   differs   {pkg}: recorded {', '.join(ours)}; termux says {theirs}")
    for pkg, ours, theirs in unstated:
        print(f"   unstated  {pkg}: recorded {', '.join(ours)}; "
              f"termux says \"{theirs}\", so nothing to compare")
    for pkg, why in unread:
        print(f"   unread    {pkg}: {why}")
    if unmapped:
        print(f"   no entry  {', '.join(unmapped)}: the build places files for these "
              "and no licence is recorded for any of them")
    # The other direction of the same question. A map entry no package accounts
    # for is either not a Termux library at all, which is the answer for the two
    # here today, or a row whose package was renamed out from under it. Printed
    # rather than failed, because this script cannot tell those apart.
    orphans = sorted(name for name, (component, _) in records
                     if name not in claimed and component != "VSCodroid")
    if orphans:
        print(f"   not termux {len(orphans)}: {', '.join(orphans)}")

    if understated:
        print(f"FAIL {len(understated)} recorded permissive where Termux declares "
              "copyleft:", file=sys.stderr)
        for pkg, ours, theirs, names in understated:
            print(f"  {pkg}: recorded {', '.join(ours)} for {', '.join(names)}; "
                  f"termux declares {theirs}", file=sys.stderr)
        print("  -> correct the entry in check-library-attribution.py and add the "
              "component under '## GPL Source Code Availability' in "
              "docs/LEGAL_NOTICES.md; until then the binary ships with no offer "
              "of source", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
