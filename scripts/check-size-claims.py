#!/usr/bin/env python3
"""Refuse a document that quotes a size the app does not support.

    check-size-claims.py
    check-size-claims.py --apk android/app/build/outputs/apk/release/app-release.apk

ToolchainRegistry.kt records, for each toolchain, how big the download is and how
much room the installed tree takes. The picker and the Toolchains screen quote
both, and package-toolchains.sh refuses a pack that is bigger than either. Three
documents repeat those numbers by hand, and repeat the totals they add up to: the
README's size table, the user guide's toolchain table and storage section, and the
requirements document. Nothing tied them to the registry or to each other, and they
came apart in every way a figure can: a download quoted below the ZIP it describes,
a free-space figure for one install that was two figures out of date, totals with
Ruby and Java and with C/C++ that predated the registry's current sizes, and a core
download quoted as larger than the APK it describes.

Every claim here is found by a pattern that has to match exactly once, so rewording a
sentence cannot silently switch its check off: the check fails and says which sentence
to look at. A toolchain the registry lists and this file has no label for is a hard
failure for the same reason.

The free space setup asks for before it unpacks is quoted in eight places: two in the
README, four in the user guide, one in the requirements document and one on the site's
front page. They are held to the README's figure, and the README's figure to the APK.

With --apk (the release workflow, after the APK is built) the core figures are also
compared with the APK: the bytes the app extracts (everything under assets/ except the
translation bundles, which setup never unpacks) against the documented core size, and
the size of the APK against the documented core download and against the size the user
guide gives for the APK on GitHub Releases, which is the file the release workflow
uploads after this check reads it. The totals above are built
from the documented core size, so that comparison is what keeps them honest as the
editor tree grows. The free-space figures are compared with what
FirstRunSetup.requiredExtractionBytes asks of that APK, worked out the way the app
works it out, with the app's own constants: a fresh install needs the files, the
block-rounded allocation of every file and directory plus the working margin (or the
minimum reserve, if larger), and an update over a complete tree needs room to rewrite
the largest file plus the minimum reserve.
"""

import argparse
import pathlib
import re
import sys
import zipfile

ROOT = pathlib.Path(__file__).resolve().parent.parent

REGISTRY = ROOT / "android/app/src/main/kotlin/com/vscodroid/setup/ToolchainRegistry.kt"
MANAGER = ROOT / "android/app/src/main/kotlin/com/vscodroid/setup/ToolchainManager.kt"
SETUP = ROOT / "android/app/src/main/kotlin/com/vscodroid/setup/FirstRunSetup.kt"
README = ROOT / "README.md"
GUIDE = ROOT / "docs/USER_GUIDE.md"
SRS = ROOT / "docs/02-SRS.md"
SITE = ROOT / "docs/site/index.html"

# How the user guide's toolchain table names each pack. A pack the registry lists that is
# missing here fails the check, so adding a toolchain cannot add one nobody compares.
LABELS = {"ruby": "Ruby", "java": "Java", "cpp": "C/C++", "codex": "Codex"}

# A total is quoted to the nearest 10 MB.
TOTAL_TOLERANCE_MB = 5
# The documented core extraction against the bytes the APK holds.
EXTRACTED_TOLERANCE = 0.01
# The documented core download against the size of the APK.
DOWNLOAD_TOLERANCE = 0.05
# The user guide's size of the APK on GitHub Releases against that APK. Held as tightly as the
# extraction, because the sentence names the file's own size rather than a rough download: an APK
# that moved by more than a percent (about 2 MB) fails the tag until the guide says so.
RELEASE_APK_TOLERANCE = 0.01
# The documented free space against what setup asks for. Kept tight: a figure below
# the demand sends someone to free space, then refuses them and asks for more.
FREE_SPACE_TOLERANCE = 0.01


def fail_now(message):
    sys.exit(f"FAIL {message}")


def number(text):
    return int(text.replace(",", ""))


def whole_mb(size_bytes):
    return int(size_bytes / 1_000_000 + 0.5)


def read_registry():
    """{pack: (estimatedSize, downloadSize)} in bytes, each read from the pack's own entry."""
    text = REGISTRY.read_text(encoding="utf-8")
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    text = re.sub(r"//[^\n]*", "", text)
    starts = list(re.finditer(r'packName\s*=\s*"toolchain_([a-z0-9_]+)"', text))
    if not starts:
        fail_now(f"no packName entry found in {REGISTRY.relative_to(ROOT)}; the pattern stopped matching")
    figures = {}
    for index, start in enumerate(starts):
        end = starts[index + 1].start() if index + 1 < len(starts) else len(text)
        entry = text[start.end():end]
        pair = []
        for field in ("estimatedSize", "downloadSize"):
            found = re.search(r"\b%s\s*=\s*([0-9][0-9_]*)L?\s*[,)]" % field, entry)
            if not found:
                fail_now(f"toolchain_{start.group(1)} records no plain number for {field}")
            pair.append(int(found.group(1).replace("_", "")))
        figures[start.group(1)] = tuple(pair)
    unlabelled = [pack for pack in figures if pack not in LABELS]
    if unlabelled:
        fail_now(
            f"{', '.join(unlabelled)} is in the registry but has no label in LABELS, so the "
            "documents' figures for it would be compared with nothing"
        )
    absent = [pack for pack in LABELS if pack not in figures]
    if absent:
        fail_now(f"{', '.join(absent)} is labelled here but not in the registry")
    return figures


def space_buffer():
    found = re.search(r"const val SPACE_BUFFER\s*=\s*([0-9_]+)L", MANAGER.read_text(encoding="utf-8"))
    if not found:
        fail_now(f"SPACE_BUFFER not found in {MANAGER.relative_to(ROOT)}")
    return int(found.group(1).replace("_", ""))


def setup_constants():
    """{name: bytes} for the extraction constants of FirstRunSetup, each a product of plain numbers."""
    text = SETUP.read_text(encoding="utf-8")
    found = {}
    for name in ("EXTRACTION_SLACK_BYTES", "EXTRACTION_WORKING_MARGIN_BYTES", "EXTRACTION_BLOCK_BYTES"):
        match = re.search(r"const val %s\s*=\s*((?:\d[\d_]*L?\s*\*\s*)*\d[\d_]*L?)\s*$" % name, text, re.M)
        if not match:
            fail_now(f"{name} in {SETUP.relative_to(ROOT)} is not a product of plain numbers")
        product = 1
        for factor in match.group(1).split("*"):
            product *= int(factor.strip().rstrip("L").replace("_", ""))
        found[name] = product
    return found


class Document:
    def __init__(self, path):
        self.path = path
        self.name = str(path.relative_to(ROOT))
        self.text = path.read_text(encoding="utf-8")
        self.problems = []

    def find(self, pattern, what):
        """The one match of PATTERN, as (groups, line), or None after recording why not."""
        found = list(re.finditer(pattern, self.text, re.M | re.S))
        if len(found) != 1:
            self.problems.append(
                f"{self.name}: expected exactly one {what}, found {len(found)}; the sentence "
                "changed, so update this check's pattern or the document"
            )
            return None
        match = found[0]
        return match.groups(), self.text.count("\n", 0, match.start()) + 1

    def read(self, pattern, what):
        """The first group of the one match of PATTERN as a number, with its line, or None."""
        hit = self.find(pattern, what)
        if hit is None:
            return None
        groups, line = hit
        return number(groups[0]), line

    def expect(self, pattern, what, wanted, tolerance=0):
        """Check the first group against WANTED."""
        hit = self.read(pattern, what)
        if hit is None:
            return
        got, line = hit
        if abs(got - wanted) > tolerance:
            self.problems.append(
                f"{self.name}:{line}: {what} reads {got} MB, the code gives {wanted} MB"
            )

    def expect_all(self, pattern, what, wanted):
        """Check every group against the matching element of WANTED."""
        hit = self.find(pattern, what)
        if hit is None:
            return
        groups, line = hit
        for got, want in zip(groups, wanted):
            if number(got) != want:
                self.problems.append(
                    f"{self.name}:{line}: {what} reads {number(got)} MB, the code gives {want} MB"
                )


def check_documents(figures):
    estimated = {pack: whole_mb(pair[0]) for pack, pair in figures.items()}
    download = {pack: whole_mb(pair[1]) for pack, pair in figures.items()}
    readme, guide, srs, site = Document(README), Document(GUIDE), Document(SRS), Document(SITE)

    # The core size is the one figure the registry cannot give: it is measured from the APK
    # (--apk), and the totals below are built on it, so the documents have to agree on it.
    hit = readme.read(r"^\|\s*Extracted to internal storage \(core\)\s*\|\s*~([\d,]+) MB", "core extraction")
    if hit is None:
        return [readme, guide, srs, site], readme, guide
    core = hit[0]
    guide.expect(r"About ([\d,]+) MB of files are unpacked", "core extraction", core)
    guide.expect(r"Core installation extracts approximately ([\d,]+) MB", "core extraction", core)
    srs.expect(r"About [\d,]+ MiB \(([\d,]+) MB\), the asset tree", "core extraction", core)

    with_java = core + estimated["ruby"] + estimated["java"]
    with_cpp = with_java + estimated["cpp"]
    readme.expect(r"^\|\s*Extracted, plus Ruby and Java\s*\|\s*~([\d,]+) MB", "total with Ruby and Java", with_java, TOTAL_TOLERANCE_MB)
    readme.expect(r"^\|\s*Extracted, plus Ruby, Java and C/C\+\+\s*\|\s*~([\d,]+) MB", "total with C/C++", with_cpp, TOTAL_TOLERANCE_MB)
    readme.expect(r"^\|\s*\+ Codex, on top of any of these\s*\|\s*\+([\d,]+) MB", "Codex on top", estimated["codex"])
    readme.expect_all(
        r"^\|\s*\+ Each toolchain \(on-demand\)\s*\|\s*([\d,]+)-([\d,]+) MB per toolchain",
        "toolchain download range",
        (min(download.values()), max(download.values())),
    )
    guide.expect(r"expect around ([\d,]+) MB, and about [\d,]+ MB with C/C\+\+ as well", "total with Ruby and Java", with_java, TOTAL_TOLERANCE_MB)
    guide.expect(r"expect around [\d,]+ MB, and about ([\d,]+) MB with C/C\+\+ as well", "total with C/C++", with_cpp, TOTAL_TOLERANCE_MB)
    guide.expect(r"The Codex toolchain adds about ([\d,]+) MB", "Codex on top", estimated["codex"])
    guide.expect(r"on top of the\s+toolchain's ([\d,]+) MB", "Codex installed size", estimated["codex"])
    srs.expect(r"about [\d,]+ MiB \(([\d,]+) MB\) with Ruby and Java installed", "total with Ruby and Java", with_java, TOTAL_TOLERANCE_MB)
    srs.expect(r"with Ruby and Java installed, and about [\d,]+ MiB \(([\d,]+) MB\) with C/C\+\+ as well", "total with C/C++", with_cpp, TOTAL_TOLERANCE_MB)

    for pack, label in LABELS.items():
        guide.expect_all(
            r"^\|\s*" + re.escape(label) + r"(?!\w)[^|]*\|\s*(\d+) MB\s*\|\s*(\d+) MB\s*\|",
            f"{label} row of the toolchain table",
            (download[pack], estimated[pack]),
        )

    est, dl = figures["codex"]
    codex_install = whole_mb(max(dl + est, 2 * est) + space_buffer())
    guide.expect(r"which for this one is about ([\d,]+) MB", "Codex install free space", codex_install, TOTAL_TOLERANCE_MB)

    # The README's free-space row is the figure --apk compares with the APK; every other place
    # that quotes it has to say the same.
    hit = readme.read(r"^\|\s*Free space required to install\s*\|\s*~([\d,]+) MB", "free space to install")
    if hit is not None:
        free = hit[0]
        readme.expect(r"^\|\s*Storage\s*\|\s*~([\d,]+) MB free to install", "free space to install", free)
        guide.expect(r"you need about ([\d,]+) MB free for the extraction", "free space to install", free)
        guide.expect(r"the ~([\d,]+) MB above is what it insists on", "free space to install", free)
        guide.expect(r"for a complete tree rather than ([\d,]+) MB", "free space to install", free)
        guide.expect(
            r"adds a small working margin,\s+which is why it needs about ([\d,]+) MB free before it starts",
            "free space to install", free,
        )
        srs.expect(r"about ([\d,]+) MB free for a fresh", "free space to install", free)
        site.expect(r"Around ([\d,]+) MB free", "free space to install", free)
    # Read here so a rewording fails on every pull request; only the APK can say what they should be.
    guide.find(r"roughly ([\d,]+) MB for a complete tree", "free space for an update")
    guide.find(r"the APK on GitHub Releases is ([\d,]+) MB", "size of the APK on GitHub Releases")
    return [readme, guide, srs, site], readme, guide


def check_apk(apk_path, readme, guide, constants):
    with zipfile.ZipFile(apk_path) as archive:
        assets = [entry for entry in archive.infolist()
                  if not entry.is_dir() and entry.filename.startswith("assets/")]
    unpacked = [entry for entry in assets if not entry.filename.startswith("assets/nls/")]
    extracted = sum(entry.file_size for entry in unpacked)
    apk_bytes = pathlib.Path(apk_path).stat().st_size
    problems = []

    # What FirstRunSetup.requiredExtractionBytes asks for. A fresh install allocates every file it
    # writes, block-rounded and with one more block of its own, and every directory: extractionSpace()
    # charges the same. The largest file is taken over the whole tree, as the Gradle build takes
    # LARGEST_ASSET_BYTES. The few assets the APK carries that setup never writes (the notices, a
    # baseline profile) are counted too, which moves the result by well under a megabyte.
    block = constants["EXTRACTION_BLOCK_BYTES"]
    slack = constants["EXTRACTION_SLACK_BYTES"]
    directories = set()
    for entry in unpacked:
        parts = entry.filename.split("/")[1:-1]
        directories.update("/".join(parts[:depth]) for depth in range(1, len(parts) + 1))
    allocation = block * len(directories) + sum(block + (-entry.file_size) % block for entry in unpacked)
    demands = (
        (r"^\|\s*Free space required to install\s*\|\s*~([\d,]+) MB", readme, "free space to install",
         "a fresh install", extracted + max(slack, allocation + constants["EXTRACTION_WORKING_MARGIN_BYTES"])),
        (r"roughly ([\d,]+) MB for a complete tree", guide, "free space for an update",
         "an update", max((entry.file_size for entry in assets), default=0) + slack),
    )
    for pattern, document, what, case, demand in demands:
        hit = document.find(pattern, what)
        if hit:
            groups, line = hit
            documented = number(groups[0])
            if abs(documented - demand / 1_000_000) > demand / 1_000_000 * FREE_SPACE_TOLERANCE:
                problems.append(
                    f"{document.name}:{line}: {what} reads {documented} MB, setup asks {case} for "
                    f"{demand / 1_000_000:.1f} MB of this APK"
                )

    hit = readme.find(r"^\|\s*Extracted to internal storage \(core\)\s*\|\s*~([\d,]+) MB", "core extraction")
    if hit:
        groups, line = hit
        documented = number(groups[0])
        measured = extracted / 1_000_000
        if abs(documented - measured) > measured * EXTRACTED_TOLERANCE:
            problems.append(
                f"{readme.name}:{line}: core extraction reads {documented} MB, the APK holds "
                f"{measured:.1f} MB under assets/ (translation bundles excluded)"
            )

    for document, pattern, what, tolerance in (
        (readme, r"^\|\s*Play Store download \(core\)\s*\|\s*~([\d,]+) MB", "core download", DOWNLOAD_TOLERANCE),
        (guide, r"The core download is roughly ([\d,]+) MB", "core download", DOWNLOAD_TOLERANCE),
        (guide, r"the APK on GitHub Releases is ([\d,]+) MB", "size of the APK on GitHub Releases",
         RELEASE_APK_TOLERANCE),
    ):
        hit = document.find(pattern, what)
        if not hit:
            continue
        groups, line = hit
        documented = number(groups[0])
        measured = apk_bytes / 1_000_000
        if abs(documented - measured) > measured * tolerance:
            problems.append(f"{document.name}:{line}: {what} reads {documented} MB, the APK is {measured:.1f} MB")
    return problems, extracted, apk_bytes


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--apk", help="also compare the core figures with this release APK")
    arguments = parser.parse_args()

    figures = read_registry()
    constants = setup_constants()
    documents, readme, guide = check_documents(figures)
    found, note = [], ""
    if arguments.apk:
        found, extracted, apk_bytes = check_apk(arguments.apk, readme, guide, constants)
        note = f"; the APK extracts {extracted / 1e6:.1f} MB and is {apk_bytes / 1e6:.1f} MB"

    problems = []
    for problem in [p for document in documents for p in document.problems] + found:
        if problem not in problems:
            problems.append(problem)
    if problems:
        print(f"FAIL {len(problems)} size claim(s) the code does not support:")
        for problem in problems:
            print(f"  {problem}")
        print("  -> measure, then change the document (or the registry); the card quotes the registry")
        return 1
    print(f"ok      the size claims in {len(documents)} documents agree with the registry{note}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
