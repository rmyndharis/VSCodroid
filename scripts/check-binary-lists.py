#!/usr/bin/env python3
"""Keep the documented list of bundled binaries in step with what is packaged.

    check-binary-lists.py

Two documents enumerate `jniLibs/arm64-v8a` by name: the architecture diagrams in
`docs/03-ARCHITECTURE.md` and the source tree in `CONTRIBUTING.md`. Neither is
derived from anything, so both go stale silently the moment a binary is added or
withdrawn, and the reader they mislead is the one deciding what ships.

That is not hypothetical. `libexec-trampoline.so` was added, packaged and shipped
while three documents still described eleven binaries, and a fourth stated the
count as a literal. Nothing failed, because nothing was reading the directory.

BOTH DIRECTIONS ARE CHECKED, and the second is the one that earns its place:

  * every `.so` in the directory is listed by both documents, which catches a
    binary that arrived without its documentation;
  * every binary the documents list is in the directory, which catches one that
    was withdrawn and left behind in prose, and which is also what stops this
    gate passing over a stub. `lint.yml` creates a four-byte `libnode.so` so
    Gradle will run, and a one-direction check would call that tree documented
    and exit 0. This gate is deliberately NOT wired into that job; the second
    direction is what makes running it there fail loudly rather than lie.

WHAT A DOCUMENT LISTS, NOT WHAT IT MENTIONS, and that distinction is the whole
of the second direction. Both files also write `lib*.so` in prose about other
things: `libtool.so` and `libdep.so` in a worked example of adding a tool,
`libpython3.x.so` for a version this build detects rather than pins, and about
fifty shared libraries that live in `assets/usr/lib` and were never in this
directory. So a name only answers for the directory when the document presents
it as a list entry, which is what [enumerated] reads: a mermaid label opening
with it, a line break inside one starting it, a tree listing marking it, or a
comma continuing a run that began one of those ways.

That rule is read off the documents' own structure. The withdrawal direction
used to filter against a hand-written set of names this directory had held, and
a hand-written set is the one thing a gate cannot check: a binary added after
that set was written was forced into both documents by the first direction,
whose failure never mentions the set, so nothing would ask for the set to be
edited, and the day that binary was withdrawn the second direction would look
straight past it and print `ok`. The exact drift this file exists to refuse,
inside the file itself.

Because both directions now read the same list, a parse that stops matching is
its own failure rather than a quiet pass: a document whose enumeration this
cannot find is refused, the same way an empty directory is. No files and no
list both mean no disagreement, which is the most comfortable wrong answer a
checker can give.

Deliberately not checking the COUNT anywhere. `docs/06-SECURITY.md` used to state
one and it drifted; it now says the sweep covers every binary and prints how many,
which is a sentence that cannot go out of date. A name is different: the documents
are read to find out what is on the device, so the names have to be right, and
that is what this reads them for.
"""

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
JNILIBS = ROOT / "android/app/src/main/jniLibs/arm64-v8a"

# Both files enumerate the directory, in a mermaid node and in a tree listing.
# Named individually rather than globbed over docs/, so a document that merely
# mentions one binary in passing is not held to listing all of them.
DOCUMENTS = (
    ROOT / "docs/03-ARCHITECTURE.md",
    ROOT / "CONTRIBUTING.md",
)

# Any lib*.so written in prose. The same shape the directory holds, so a name in
# a document and a name on disk compare as one string with no normalising.
NAMED = re.compile(r"\blib[A-Za-z0-9._+-]*\.so\b")

# What opens a list entry: a mermaid label, a line break inside one, or a tree
# listing's branch. Markdown formats, not names, so nothing here has to be
# revisited when a binary comes or goes.
MARKERS = ('["', "<br/>", "── ")

# What continues one. A run of names separated by exactly this counts as long as
# the run began at a marker, which is what tells `["libgit.so,
# libgit-remote-curl.so"]` apart from `usr/lib/ (libpython3.x.so, ICU, OpenSSL,
# libc++_shared.so ...)`: the second is a sentence about a different directory
# and starts inside a parenthesis rather than at an entry.
CONTINUATION = ", "


def packaged() -> set:
    """Every binary the APK carries in `jniLibs`, by file name."""
    return {p.name for p in JNILIBS.glob("*.so")}


def enumerated(path: pathlib.Path) -> set:
    """The binaries a document lists, ignoring the ones it merely mentions."""
    text = path.read_text(encoding="utf-8")
    names = set()
    previous_end = 0
    previous_was_entry = False
    for match in NAMED.finditer(text):
        is_entry = text[: match.start()].endswith(MARKERS) or (
            previous_was_entry
            and text[previous_end:match.start()] == CONTINUATION
        )
        if is_entry:
            names.add(match.group(0))
        previous_end, previous_was_entry = match.end(), is_entry
    return names


def main() -> int:
    if not JNILIBS.is_dir():
        print(f"  FAIL   {JNILIBS.relative_to(ROOT)} does not exist; run the download scripts first")
        return 1

    present = packaged()
    if not present:
        print(f"  FAIL   {JNILIBS.relative_to(ROOT)} holds no .so at all, so this check would")
        print("         pass over anything. Run the download scripts before it.")
        return 1

    failed = False
    for path in DOCUMENTS:
        where = path.relative_to(ROOT)
        if not path.is_file():
            print(f"  FAIL   {where} is missing")
            failed = True
            continue

        listed = enumerated(path)
        if not listed:
            print(f"  FAIL   {where} lists no binary this check can read. It enumerates the")
            print("         directory in a mermaid label or a tree listing; if that shape has")
            print("         changed, this gate now agrees with anything. Teach it the new one.")
            failed = True
            continue

        undocumented = sorted(present - listed)
        withdrawn = sorted(listed - present)

        if undocumented:
            print(f"  FAIL   {where} does not list: {', '.join(undocumented)}")
            failed = True
        if withdrawn:
            print(f"  FAIL   {where} still lists binaries no longer packaged: {', '.join(withdrawn)}")
            failed = True
        if not undocumented and not withdrawn:
            print(f"  ok     {where} lists all {len(present)} packaged binaries")

    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
