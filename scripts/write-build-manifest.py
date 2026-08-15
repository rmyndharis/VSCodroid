#!/usr/bin/env python3
"""Record what a build actually resolved, so a release can be asked afterwards.

    scripts/write-build-manifest.py [-o build-manifest.txt]
    scripts/write-build-manifest.py --compare old-manifest.txt

Every Termux component is resolved from a live index at build time. Nothing
recorded what came back, so re-running release.yml on the same tag could ship
different binaries with nothing to say so, and a bug report against a released
version could not be tied to the binaries that version shipped.

This is deliberately a record, not a lock. Termux drops superseded .deb files
from its pool, so a hard pin would break the build on every upstream bump --
which is why the cheap thing missing here was the record, not the pin.

Measured 2026-08-15, and the reason this is not a hypothetical: release.yml
cached `toolchains/termux-packages/*.deb`, while the download scripts write to
`toolchains/termux-packages/debs/`. That glob matched zero of the 72 files
present, so every release re-downloaded everything against a freshly resolved
index. The glob is fixed in the same change; this file is what makes the
resulting drift visible rather than silent.

Exit status is 0 whenever the manifest could be written or compared. Differences
are reported, never enforced.
"""

import argparse
import hashlib
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent

# Filled by resolved_entries() when two scripts disagree about a package.
_CONFLICTS: list[tuple[str, tuple[str, str], tuple[str, str]]] = []


def sha256(path: pathlib.Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def read_text(rel: str) -> str:
    p = ROOT / rel
    return p.read_text().strip() if p.is_file() else "-"


def version_from_filename(filename: str) -> str:
    """`pool/main/n/nodejs-lts/nodejs-lts_24.18.0_aarch64.deb` -> `24.18.0`.

    Debian package names may not contain an underscore -- only lowercase
    alphanumerics, `+`, `-` and `.` -- so splitting the basename on it cannot cut
    a name in half. The filename comes out of the signed index, so this reads the
    version upstream published rather than one inferred from a file on disk.
    """
    stem = filename.rsplit("/", 1)[-1]
    stem = stem[:-4] if stem.endswith(".deb") else stem
    parts = stem.split("_")
    return parts[1] if len(parts) >= 3 else "-"


def resolved_entries() -> list[tuple[str, str, str]]:
    """Read what each download script recorded, as `(package, version, sha256)`.

    Deliberately not a scan of the .deb directory. That directory accumulates
    across builds -- measured: it held two versions of zlib, 1.3.1-1 and 1.3.2 --
    so it answers "what has ever been downloaded here", not "what did this build
    resolve". Most scripts truncate their own record at the start of a run, so
    these files answer the second question.

    Every script that keeps a record now truncates it at the start of a run and
    writes it at the end, so a run that fails leaves no record rather than one
    naming something that never reached the tree. download-node.sh was the
    exception until it was fixed; a manifest read from an older tree can still
    carry a line ahead of what that tree contains. One grep settles which state a
    checkout is in, and the anchor matters -- an earlier form of this note gave
    `grep -n ': > "$PKG_MAP_FILE"'`, which misses the two scripts that truncate
    through literal filenames and matches any comment quoting the pattern:
        grep -nE '^[[:space:]]*: >' scripts/download-*.sh
    """
    work = ROOT / "toolchains" / "termux-packages"
    # Cleared per call rather than appended to: collect() may be called more than
    # once in a process, and a module-level list would report each disagreement
    # again every time.
    _CONFLICTS.clear()
    seen: dict[str, tuple[str, str]] = {}
    for rec in sorted(work.glob("resolved-*.tsv")):
        for line in rec.read_text().splitlines():
            fields = line.split()
            if len(fields) < 3:
                continue
            pkg, filename, digest = fields[0], fields[1], fields[2]
            entry = (version_from_filename(filename), digest)
            if pkg in seen and seen[pkg] != entry:
                # Two scripts resolved the same package differently, which means
                # the index moved under the build -- callers refetch it once it
                # is over an hour old, and the checker refetches it when it stops
                # matching the signature. Recorded rather than resolved: last
                # wins would put one of two true answers in the manifest and say
                # nothing about the other, and the disagreement is the more
                # useful fact for anyone asking what a release was built from.
                _CONFLICTS.append((pkg, seen[pkg], entry))
            seen[pkg] = entry
    return [(pkg, v, d) for pkg, (v, d) in sorted(seen.items())]


def collect() -> list[str]:
    lines = [
        "# VSCodroid build manifest",
        "# What this build resolved. A record, not a lock -- see",
        "# scripts/write-build-manifest.py for why.",
        "",
        f"vscode-version\t{read_text('VSCODE_VERSION')}",
        f"vscode-commit\t{read_text('VSCODE_COMMIT')}",
    ]

    tarballs = sorted((ROOT / "server").glob("*.tar.gz")) if (ROOT / "server").is_dir() else []
    for t in tarballs:
        lines.append(f"vscode-server\t{sha256(t)}\t{t.name}")
    if not tarballs:
        lines.append("vscode-server\t-")

    # Recorded on its own line rather than joining the glob below. The loader
    # comes from Alpine's signature-verified index, not Termux's, so its record
    # lives in toolchains/musl and its filename is an .apk -- version_from_filename
    # reads Debian names and would report "-" for it. Its version is resolved from
    # a live index like everything else here, which is what makes the record worth
    # having: it was the only bundled download the manifest could not name.
    musl = ROOT / "toolchains" / "musl" / "resolved-musl.tsv"
    fields = musl.read_text().split() if musl.is_file() else []
    lines.append(f"musl-loader\t{fields[1]}\t{fields[2]}" if len(fields) >= 3
                 else "musl-loader\t-")

    # The index is signature-verified by verify-termux-index.sh before anything
    # reads a digest out of it, so recording its hash pins the whole set of
    # filenames and digests this build resolved, not just the files it kept.
    index = ROOT / "toolchains" / "termux-packages" / "Packages"
    lines.append(f"termux-index\t{sha256(index) if index.is_file() else '-'}")
    lines.append("")

    entries = resolved_entries()
    lines.append(f"# termux packages: {len(entries)}")
    if not entries:
        lines.append("# (none -- no download script has run in this tree)")
    for pkg, ver, digest in entries:
        lines.append(f"termux\t{pkg}\t{ver}\t{digest}")

    for pkg, first, second in _CONFLICTS:
        lines.append(
            f"# NOTE {pkg} was resolved twice in this build: "
            f"{first[0]} then {second[0]} -- the index moved mid-build"
        )

    return lines


def parse(text: str) -> dict[str, str]:
    """Manifest -> {identity: value}, ignoring comments and blank lines.

    Keyed on identity rather than on the whole line so that a version bump reads
    as a change to one package rather than as one removal plus one addition.
    """
    out = {}
    for line in text.splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        fields = line.split("\t")
        if fields[0] == "termux" and len(fields) >= 4:
            out[f"termux:{fields[1]}"] = f"{fields[2]} {fields[3]}"
        elif fields[0] == "vscode-server" and len(fields) >= 3:
            # Keyed by tarball name: server/ can hold more than one, and keying
            # on the bare label made the second silently replace the first, so a
            # comparison would have reported on whichever sorted last.
            out[f"vscode-server:{fields[2]}"] = fields[1]
        else:
            out[fields[0]] = "\t".join(fields[1:])
    return out


def compare(old_path: pathlib.Path) -> int:
    old = parse(old_path.read_text())
    new = parse("\n".join(collect()))

    added = sorted(set(new) - set(old))
    removed = sorted(set(old) - set(new))
    changed = sorted(k for k in set(old) & set(new) if old[k] != new[k])

    print(f"  compared against : {old_path}")
    print(f"  entries          : {len(old)} recorded, {len(new)} now")
    for k in changed:
        print(f"  changed  {k}\n             was {old[k]}\n             now {new[k]}")
    for k in added:
        print(f"  added    {k}  {new[k]}")
    for k in removed:
        print(f"  removed  {k}  {old[k]}")
    if not (added or removed or changed):
        print("  identical")
    # Always 0. Termux moves under us by design; the point is to see it, and a
    # gate that failed here would fail on ordinary upstream activity and be
    # switched off within a week.
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("-o", "--output", type=pathlib.Path,
                    help="write here instead of stdout")
    ap.add_argument("--compare", type=pathlib.Path,
                    help="report differences against an earlier manifest and exit 0")
    args = ap.parse_args()

    if args.compare:
        if not args.compare.is_file():
            print(f"  no earlier manifest at {args.compare} -- nothing to compare")
            return 0
        return compare(args.compare)

    text = "\n".join(collect()) + "\n"
    if args.output:
        args.output.write_text(text)
        n = sum(1 for line in text.splitlines() if line.startswith("termux\t"))
        print(f"  manifest: {args.output} ({n} termux packages)")
    else:
        sys.stdout.write(text)
    return 0


if __name__ == "__main__":
    sys.exit(main())
