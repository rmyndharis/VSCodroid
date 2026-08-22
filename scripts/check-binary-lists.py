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

  * every `.so` in the directory is named by both documents, which catches a
    binary that arrived without its documentation;
  * every binary the documents name is in the directory, which catches one that
    was withdrawn and left behind in prose, and which is also what stops this
    gate passing over a stub. `lint.yml` creates a four-byte `libnode.so` so
    Gradle will run, and a one-direction check would call that tree documented
    and exit 0. This gate is deliberately NOT wired into that job; the second
    direction is what makes running it there fail loudly rather than lie.

An empty directory is refused outright for the same reason: no files means no
disagreement, which is the most comfortable wrong answer a checker can give.

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


def packaged() -> set:
    """Every binary the APK carries in `jniLibs`, by file name."""
    return {p.name for p in JNILIBS.glob("*.so")}


def documented(path: pathlib.Path) -> set:
    return set(NAMED.findall(path.read_text(encoding="utf-8")))


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

        named = documented(path)
        undocumented = sorted(present - named)
        # Restricted to the names this directory has ever held rather than to
        # every lib*.so a document mentions: `assets/usr/lib` holds about fifty
        # more, and several are named in these same files for other reasons.
        withdrawn = sorted(n for n in named if n.startswith("lib") and n in KNOWN - present)

        if undocumented:
            print(f"  FAIL   {where} does not name: {', '.join(undocumented)}")
            failed = True
        if withdrawn:
            print(f"  FAIL   {where} still names binaries no longer packaged: {', '.join(withdrawn)}")
            failed = True
        if not undocumented and not withdrawn:
            print(f"  ok     {where} names all {len(present)} packaged binaries")

    return 1 if failed else 0


# What the directory has held, so the withdrawal half compares against binaries
# rather than against every shared library a document happens to mention. A name
# leaves this set only when nothing describes it any more, which is the same edit
# as removing it from the documents.
KNOWN = {
    "libbash.so",
    "libexec-trampoline.so",
    "libgit-remote-curl.so",
    "libgit.so",
    "libgo.so",
    "libldmusl.so",
    "libmake.so",
    "libnode.so",
    "libpython.so",
    "libripgrep.so",
    "libssh-keygen.so",
    "libssh.so",
    "libtmux.so",
}


if __name__ == "__main__":
    sys.exit(main())
