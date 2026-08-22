#!/usr/bin/env python3
"""Hold the bundled extension list and the notices to each other.

    check-bundled-extensions.py

Five extensions are downloaded from Open VSX and shipped inside the APK.
`NOTICE.md` carries a table naming them and their licences, and nothing read
that table: the list and the table are two hand-maintained copies of one fact,
and adding or dropping an extension only ever touched one of them. They agree
today, which is the moment to wire them together rather than the reason not to.

The licences were never verified at all. `check-extension.py` runs on every
extracted tree and asks two questions, whether `engines.vscode` outruns the
server and whether a glibc payload came along; neither of those is "may this be
redistributed, and on what terms".

Four rules:

  * every entry in `EXTENSIONS` has a row in the notices table and every row has
    an entry, matched on the extension id;
  * the licence each row records is one this project can redistribute;
  * where the extracted tree is on disk, its `package.json` declares that same
    licence and the tree carries the licence text itself;
  * the extracted trees on disk are all of them or none of them.

The third rule is the one that reads a build product, so it runs where build
products exist. `download-extensions.sh` calls this after extracting, and
`build.yml` calls it again once the asset cache has been restored, because that
download step is skipped on a cache hit and the restored trees are still on
disk to be read. A checkout with no trees, which is what `lint.yml` has, gets
the first two rules and a note saying that none was read, so a run that examined
nothing cannot be read as one that examined all of them.

None and all are the two states a checkout can honestly be in, and a count was
all that separated them from every state in between. `download-extensions.sh`
places all five or exits non-zero, so a partial set is not a checkout that has
not downloaded yet: it is a tree that was built and then lost, which is what
ships an APK missing an extension `NOTICE.md` still attributes. So the fourth
rule fails. Zero trees stays a note, because it is the ordinary state of a
source-only checkout, and `--require-trees` is how a caller that has just built
them says that zero is wrong there too.

Matched on the id, not on the display name. The names in the table are the ones
a reader recognises and are not what the extensions call themselves: the table
says "Prettier" against a `displayName` of "Prettier - Code formatter", and
"Python" against a publisher whose id is what distinguishes it from every other
extension of that name. So the table carries the id as its own column.

Scoped to the `EXTENSIONS` list, never to a walk of the extensions directory.
Four `vscodroid.*` extensions are committed there, and they have no `license`
field and no licence file because this repository's own `LICENSE` covers them,
exactly as the notices say. A checker that walked the directory would fail on
them immediately and be switched off within the day.
"""

import json
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
DOWNLOAD_SCRIPT = ROOT / "scripts/download-extensions.sh"
NOTICE = ROOT / "NOTICE.md"
EXTRACTED = ROOT / "android/app/src/main/assets/extensions"

# publisher.name@version#sha256, as download-extensions.sh's own shape guard
# requires. Anything looser here would let this checker read a list the download
# script refuses, and report on extensions that can never be fetched.
ENTRY = re.compile(r'"([^"@#]+)@([^"@#]+)#[0-9a-f]{64}"')
BLOCK = re.compile(r"^EXTENSIONS=\(\s*$(.*?)^\)\s*$", re.M | re.S)

NOTICE_SECTION = "## Bundled VS Code Extensions"

# Permissive licences whose only condition is that the notice travels with the
# copy, which shipping the extracted tree satisfies. Deliberately short: a
# copyleft or source-availability term reaching this list is a decision for a
# person, not a default this file quietly widens.
#
# What the licence in this table does not describe: ms-python.python declares an
# extensionPack of ms-python.vscode-pylance, ms-python.debugpy and
# ms-python.vscode-python-envs. Those are fetched by the user from Open VSX
# under their own terms and are not bundled, so they are out of scope here, but
# the row must not be read as covering what installing it leads to.
ALLOWED_LICENCES = {"MIT", "Apache-2.0", "BSD-2-Clause", "BSD-3-Clause", "ISC"}

# Case-insensitive, because the five trees do not agree: dbaeumer ships
# License.txt and the other four ship LICENSE.txt.
LICENCE_FILE = re.compile(r"^(licen[sc]e|copying|notice)", re.I)


def pinned_extensions():
    """`{id: version}` from the download script's EXTENSIONS array."""
    block = BLOCK.search(DOWNLOAD_SCRIPT.read_text(encoding="utf-8"))
    if not block:
        return None
    return {ext_id: version for ext_id, version in ENTRY.findall(block.group(1))}


def notice_rows():
    """`{id: licence}` from the notices table, or None if the section is gone."""
    lines = NOTICE.read_text(encoding="utf-8").splitlines()
    try:
        start = lines.index(NOTICE_SECTION)
    except ValueError:
        return None

    rows = {}
    for line in lines[start + 1:]:
        if line.startswith("## "):
            break
        if not line.startswith("|"):
            continue
        cells = [c.strip() for c in line.strip().strip("|").split("|")]
        # The header row and the |---|---| separator under it. Matching on the
        # separator alone would keep the header as a row named "Extension".
        if len(cells) != 3 or set(cells[1]) <= set("- :") or cells[1] == "ID":
            continue
        rows[cells[1]] = cells[2]
    return rows


def check_tree(ext_id, version, licence, failures):
    """The extracted tree, when there is one. Returns True if it was read."""
    tree = EXTRACTED / f"{ext_id}-{version}"
    manifest = tree / "package.json"
    if not manifest.is_file():
        return False

    declared = json.loads(manifest.read_text(encoding="utf-8")).get("license")
    if declared != licence:
        failures.append(
            f"{ext_id} declares license {declared!r} in package.json, while "
            f"{NOTICE.name} records {licence!r}"
        )
    if not any(p.is_file() and LICENCE_FILE.match(p.name) for p in tree.iterdir()):
        failures.append(
            f"{ext_id} ships no licence file at the root of its extracted "
            f"tree, so the notice its licence requires does not travel with "
            f"the APK"
        )
    return True


USAGE = "usage: check-bundled-extensions.py [--require-trees]"


def main(argv) -> int:
    # Refused rather than ignored: a caller that means --require-trees and
    # mistypes it would otherwise get the tolerant run it was trying to escape.
    require_trees = False
    for arg in argv:
        if arg == "--require-trees":
            require_trees = True
        else:
            print(f"::error::unknown argument {arg!r}\n{USAGE}")
            return 2

    pinned = pinned_extensions()
    if not pinned:
        print(f"::error::no EXTENSIONS array found in {DOWNLOAD_SCRIPT}; the "
              f"pattern stopped matching, and an empty list makes every "
              f"comparison below vacuously true")
        return 1

    rows = notice_rows()
    if not rows:
        print(f"::error::no rows under '{NOTICE_SECTION}' in {NOTICE}; the "
              f"section was renamed, removed, or its table changed shape")
        return 1

    failures = []

    for ext_id in sorted(set(pinned) - set(rows)):
        failures.append(
            f"{ext_id} is bundled but has no row in {NOTICE.name}; it ships "
            f"inside the APK unattributed"
        )
    for ext_id in sorted(set(rows) - set(pinned)):
        failures.append(
            f"{NOTICE.name} attributes {ext_id}, which is not in EXTENSIONS; "
            f"the notices describe an APK this build does not produce"
        )

    for ext_id, licence in sorted(rows.items()):
        if licence not in ALLOWED_LICENCES:
            failures.append(
                f"{ext_id} is recorded as {licence!r}, which is not one of "
                f"{', '.join(sorted(ALLOWED_LICENCES))}. Bundling it is a "
                f"decision to take deliberately rather than by adding a row"
            )

    # Counted rather than subtracted from the pinned total: an id with no
    # notices row is not read and is not missing a tree either, it is the
    # failure two blocks up, and taking it off the total would blame the wrong
    # rule for it.
    trees_read = 0
    unread = []
    for ext_id in sorted(set(pinned) & set(rows)):
        if check_tree(ext_id, pinned[ext_id], rows[ext_id], failures):
            trees_read += 1
        else:
            unread.append(ext_id)

    # Some but not all, or none where the caller has just built them. Held here
    # with the other failures rather than reported as a count, because the count
    # was the whole defect: a run that lost a tree printed the same "note" as a
    # checkout that never had one, and every workflow stayed green.
    if unread and (require_trees or trees_read):
        failures.append(
            f"{trees_read} of {len(pinned)} extracted trees are on disk; "
            f"{', '.join(unread)} left no package.json to read. "
            + (
                "The caller says every one of them was just placed"
                if require_trees
                else "download-extensions.sh places all of them or exits "
                "non-zero, so a partial set is a tree that was built and then "
                "lost, not a checkout that has not downloaded yet"
            )
            + f"; an APK built from this tree ships missing what "
            f"{NOTICE.name} still attributes"
        )

    for f in failures:
        print(f"::error::{f}")
    if failures:
        print(f"  FAILED  {len(failures)} problem(s)")
        return 1

    print(f"  ok     all {len(pinned)} bundled extensions have a notices row, "
          f"and every row has an extension")
    print(f"  ok     every recorded licence is redistributable")
    if trees_read == len(pinned):
        print(f"  ok     all {trees_read} extracted trees declare that licence "
              f"and carry its text")
    else:
        print(f"  note   no extracted trees on disk; the licence all "
              f"{len(pinned)} declare was not read here. That half runs "
              f"wherever the trees exist, which is build.yml and from "
              f"download-extensions.sh")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
