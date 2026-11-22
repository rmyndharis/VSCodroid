#!/usr/bin/env python3
"""Refuse a welcome screen that states things which go stale on their own.

    check-welcome-claims.py

The Get Started walkthrough is committed source. The runtimes it described are
not: Node, Python and Git are resolved from the Termux index when the app is
built, so a version written into the manifest is correct only until the next
rebuild. It stayed wrong for two releases -- the screen said Node.js 20 while
24.18.0 shipped, Python 3.12 while 3.14 shipped, and the illustration beside it
printed a mock `node --version` giving v20.18.1.

Nothing reported it, because a hardcoded string cannot disagree with itself.

Three rules, all mechanical:

  * No version number beside a bundled tool's name. The screen's job is to say
    the tool is there and ready; a user who needs the number runs `node -v`,
    which the very next step of the walkthrough tells them to do.

  * No "coming soon", in any language the screen ships in. Java 25 and Ruby
    were installable while the screen said the opposite, which is worse than a
    stale number, because it sends a user away from a toolchain already sitting
    on their device. Rust was promised there too, and has no module, pack or
    registry entry.

  * A step's picture draws every toolchain its text offers by name. The tools
    step's text gained C/C++ when that pack shipped, and the illustration beside
    it went on drawing Java 25 and Ruby alone.

This checks the manifest and the illustrations together. The pictures carried
the same claims as the text and were missed when the text was first questioned.
"""

import json
import pathlib
import re
import sys
from xml.etree import ElementTree

ROOT = pathlib.Path(__file__).resolve().parent.parent
EXTENSIONS = ROOT / "android/app/src/main/assets/extensions"
# What the app installs on demand, by the name a user reads.
REGISTRY = ROOT / "android/app/src/main/kotlin/com/vscodroid/setup/ToolchainRegistry.kt"
SVG = "{http://www.w3.org/2000/svg}"

# "Coming soon" in every language the walkthrough ships in, each read from its own
# bundle. The English phrase alone let a translation promise the same thing in its
# own words. A bundle whose language has no entry here fails the check rather than
# being read for English only.
COMING_SOON = {
    "": [r"\bcoming soon\b"],
    "cs": [r"\bjiž brzy\b", r"\bpřipravujeme\b"],
    "de": [r"\bdemnächst\b", r"\bin Kürze\b", r"\bbald verfügbar\b"],
    "es": [r"\bpróximamente\b"],
    "fr": [r"\bbientôt\b", r"\bprochainement\b"],
    "it": [r"\bprossimamente\b", r"\bin arrivo\b"],
    "ja": [r"近日", r"今後対応"],
    "ko": [r"출시 예정", r"지원 예정", r"곧 제공"],
    "pl": [r"\bwkrótce\b"],
    "pt-br": [r"\bem breve\b"],
    "ru": [r"\bскоро\b", r"\bв ближайшее время\b"],
    "tr": [r"\byakında\b"],
    "zh-cn": [r"即将推出", r"即将支持", r"敬请期待"],
    "zh-tw": [r"即將推出", r"即將支援", r"敬請期待"],
}

# Tier-1 tools, all resolved from the Termux index at build time. Naming a
# version for any of them is the defect; the toolchains are excluded on purpose,
# because Java 25 is a pinned choice rather than whatever the index happens to
# carry, and saying which Java is useful.
BUNDLED = r"(?:Node\.js|Node|npm|Python|Git|make)"

FORBIDDEN = [
    (re.compile(rf"\b{BUNDLED}\s+v?\d+(?:\.\d+)*", re.I),
     "names a bundled tool with a version; it comes from the Termux index at "
     "build time and will not follow"),
    # Both forms, because they are not the same miss. `v20.18.1` was caught by
    # the leading v; `git version 2.53.0` on the line below it was not -- "git"
    # is followed by a word, and the number carries no prefix.
    (re.compile(r"\bv?\d+\.\d+\.\d+\b"),
     "prints a version string that goes stale on the next rebuild"),
    (re.compile("|".join(p for patterns in COMING_SOON.values() for p in patterns), re.I),
     "promises something as unavailable; say what a user can install today instead"),
]


# The prose is checked the other way round. The rules above name shapes known to
# be bad, and a denylist has a tail: `git version 2.53.0` slipped the first
# version of the tool name rule, because "git" is followed by a word and the
# number carries no prefix. It was caught by running the tool against the text
# that shipped, which only works for shapes that already exist somewhere.
#
# In the walkthrough's own prose a digit is almost never legitimate -- claims
# live there, code samples do not -- so anything numeric has to be declared. A
# denylist fails silently on a shape nobody anticipated; this fails loudly on
# legitimate new text, and that failure is one line a reviewer can judge at a
# glance. The illustrations keep the denylist: their text is mock terminal
# output, where digits are the point.
PROSE_ALLOWED = (
    "Java 25",   # a pinned toolchain, not a version resolved at build time
    "python3",   # the command's name
)

# The illustrations need the same escape hatch, and not having one was an
# asymmetry rather than a decision. Prose can declare a number and carry on;
# a picture could not, so a rule refusing it left no sanctioned way through --
# only editing the rule, which is how a gate stops being trusted.
#
# It is not hypothetical. `http://127.0.0.1:3000` in a mock terminal is refused
# today, matching `127.0.0` as a version string, and that address is what this
# app's own dev-server preview shows. `make 4 targets` is refused too.
#
# Empty on purpose: the illustrations that ship carry only code samples no rule
# matches. An entry here is a claim that a number in a picture is deliberate,
# and it should be as reviewable as one in prose.
MEDIA_ALLOWED = ()

# `%walkthrough.tools.description%` in the manifest: the whole value, never a
# fragment of one, which is what the editor's own substitution walker requires.
PLACEHOLDER = re.compile(r"%([^%]+)%")

# The English base, and the per-key fallback for every translation. Its absence
# is the one failure that puts a literal `%key%` on the screen.
BASE_BUNDLE = "package.nls.json"


def welcome_dir():
    """The bundled welcome extension, whatever version it is at.

    Ordered by parsed version rather than by name, because lexically 1.10.0
    sorts below 1.9.0 and this would then check the older directory and pass.
    Only one ever ships, so this cannot bite today -- but the failure it would
    produce is a green gate reading the wrong file, which is the shape this
    script exists to prevent.
    """
    def version(path):
        tail = path.name.rsplit("-", 1)[-1]
        try:
            return tuple(int(part) for part in tail.split("."))
        except ValueError:
            return ()

    found = sorted(EXTENSIONS.glob("vscodroid.vscodroid-welcome-*"), key=version)
    return found[-1] if found else None


def texts(directory, rows):
    """Every user-visible string, from the manifest and the illustrations.

    The pictures are read as plain text rather than parsed: the claims sat in
    <text> elements, and any XML shape that renders words is worth checking.
    """
    for where, text in rows:
        yield where, text

    for svg in sorted(directory.glob("media/*.svg")):
        # Comments explain why a claim was removed and would match the rules
        # they document, which would make this script fail on its own reasoning.
        body = re.sub(r"<!--.*?-->", "", svg.read_text(), flags=re.S)
        for allowed in MEDIA_ALLOWED:
            body = body.replace(allowed, "")
        yield svg.name, body


def bundles(directory):
    """`{file name: parsed contents}` for every `package.nls*.json` beside the manifest.

    The manifest's user-facing strings are `%key%` placeholders that the editor's
    extension scanner resolves out of these files, one per shipped language plus
    an English base. So the prose this script exists to police no longer lives in
    the file this script used to read, and reading only the English base would
    leave thirteen copies of every claim unexamined.
    """
    return {
        path.name: json.loads(path.read_text(encoding="utf-8"))
        for path in sorted(directory.glob("package.nls*.json"))
    }


def prose(directory):
    """`(rows, unresolved)`: every title and description line, and what could not be read.

    The walkthrough carries a title and a description of its own, and they are
    the first two lines a user reads -- they head the page the four steps sit
    under. This read only the steps, so those two fields were the one place on
    the screen where a version number or a "coming soon" was checked by nothing.

    A field holding `%key%` yields one row per bundle that carries the key,
    labelled with the bundle's file name so a failure names the file to edit. A
    field holding `%key%` that the English base does not carry yields no rows at
    all, which is why it is reported separately: it is exactly the shape that
    would turn this whole script into a scan of placeholders that passes.
    """
    manifest = directory / "package.json"
    rows, unresolved = [], []
    if not manifest.is_file():
        return rows, unresolved
    data = json.loads(manifest.read_text())
    found = bundles(directory)
    base = found.get(BASE_BUNDLE)

    def resolved(where, value):
        key = PLACEHOLDER.fullmatch(value)
        if not key:
            return [(where, value)]
        key = key.group(1)
        if base is None or key not in base:
            unresolved.append(f"{where}: %{key}%")
        return [
            (f"{where} in {name}", bundle[key])
            for name, bundle in found.items()
            if key in bundle
        ]

    def lines(where, node):
        for field in ("title", "description"):
            for labelled, text in resolved(where, node.get(field, "")):
                for line in text.split("\n"):
                    if line.strip():
                        rows.append((labelled, line.strip()))

    for walkthrough in data.get("contributes", {}).get("walkthroughs", []):
        lines(f"{manifest.name} walkthrough '{walkthrough.get('id', '?')}'", walkthrough)
        for step in walkthrough.get("steps", []):
            lines(f"{manifest.name} step '{step.get('id', '?')}'", step)

    return rows, unresolved


def undrawn_toolchains(directory):
    """`(gaps, offered)`: the toolchains a step's English text names in bold that
    its picture does not draw, and how many were offered in all.

    The names are the registry's display names, so a toolchain added there is
    looked for here without an edit. A picture is read for its `<text>` elements,
    which is where a box's label is drawn.
    """
    names = re.findall(r'displayName\s*=\s*"([^"]+)"', REGISTRY.read_text(encoding="utf-8"))
    if not names:
        sys.exit(f"FAIL no displayName could be read from {REGISTRY.relative_to(ROOT)}; "
                 "the pictures would be compared with nothing")
    data = json.loads((directory / "package.json").read_text())
    base = bundles(directory).get(BASE_BUNDLE, {})
    gaps, offered = [], 0
    for walkthrough in data.get("contributes", {}).get("walkthroughs", []):
        for step in walkthrough.get("steps", []):
            picture = (step.get("media") or {}).get("svg")
            text = step.get("description", "")
            key = PLACEHOLDER.fullmatch(text)
            text = base.get(key.group(1), "") if key else text
            bold = set(re.findall(r"\*\*([^*]+)\*\*", text))
            named = [name for name in names if name in bold]
            if not named or not picture:
                continue
            offered += len(named)
            drawn = {
                "".join(node.itertext()).strip()
                for node in ElementTree.parse(directory / picture).iter(f"{SVG}text")
            }
            missing = [name for name in named if name not in drawn]
            if missing:
                gaps.append((picture, step.get("id", "?"), missing))
    return gaps, offered


def main():
    directory = welcome_dir()
    if directory is None:
        print(f"  FAIL    no vscodroid-welcome-* under {EXTENSIONS}")
        return 1

    failed = False

    # The illustrations are XML, and an unrenderable one fails silently: the
    # walkthrough step still opens, with a blank space where the picture was.
    # Worth checking here because this script asks people to edit these files,
    # and the obvious way to explain a removal is a comment -- which cannot
    # contain two hyphens in a row, exactly what a comment about `--version`
    # wants to write. Nothing else in this repository would catch that.
    for svg in sorted(directory.glob("media/*.svg")):
        try:
            ElementTree.parse(svg)
        except ElementTree.ParseError as e:
            print(f"  FAIL    {svg.name}: not well-formed XML ({e})")
            print("            the picture will not render; the step opens with a blank space")
            failed = True

    rows, unresolved = prose(directory)

    languages = {name[len("package.nls"):-len(".json")].lstrip(".") for name in bundles(directory)}
    unread = sorted(languages - COMING_SOON.keys())
    if unread:
        print(f"  FAIL    no \"coming soon\" wording for {', '.join(unread)} in COMING_SOON")
        print("            a translation in that language would be read for the English phrase only")
        failed = True

    gaps, offered = undrawn_toolchains(directory)
    if not offered:
        print("  FAIL    no step's text names a toolchain from ToolchainRegistry.kt in bold")
        print("            so no picture was compared with its text; the names or the markup moved")
        failed = True
    for picture, step, missing in gaps:
        print(f"  FAIL    {picture}: step '{step}' offers {', '.join(missing)} and the picture "
              f"does not draw {'it' if len(missing) == 1 else 'them'}")
        print("            a user reads the picture beside the text; draw a box for each toolchain the text names")
        failed = True

    # A run that examined nothing must not read as a run that examined
    # everything. With the prose behind `%key%` and no English bundle to resolve
    # it, every rule below sees a placeholder: no digit, no forbidden phrase, and
    # an ok line over text nobody read.
    if unresolved:
        print(f"  FAIL    {len(unresolved)} walkthrough field(s) could not be resolved:")
        for entry in unresolved:
            print(f"            {entry}")
        print(f"            the walkthrough prose moved into a bundle this check cannot "
              f"find; {BASE_BUNDLE} is missing or has no such key, and without it this "
              f"check would pass without reading anything")
        failed = True

    for where, line in rows:
        stripped = line
        for allowed in PROSE_ALLOWED:
            stripped = stripped.replace(allowed, "")
        if any(c.isdigit() for c in stripped):
            print(f"  FAIL    {where}: {line[:90]!r}")
            print("            a number in walkthrough prose has to be declared in "
                  "PROSE_ALLOWED; if it names a bundled tool's version it should not be "
                  "there at all")
            failed = True

    for where, text in texts(directory, rows):
        for pattern, why in FORBIDDEN:
            for hit in pattern.findall(text):
                print(f"  FAIL    {where}: {hit.strip()!r}")
                print(f"            {why}")
                failed = True

    if failed:
        print(f"         Checked {directory.name}.")
        print("         Say the tool is ready without naming its version, say what a "
              "user can install today rather than what is coming, and draw what the "
              "text offers.")
        return 1

    print(f"  ok      {directory.name} states nothing that goes stale on its own")
    return 0


if __name__ == "__main__":
    sys.exit(main())
