#!/usr/bin/env python3
"""Build the editor's translated string bundles from microsoft/vscode-loc.

The workbench reads its interface strings out of one flat array,
`globalThis._VSCODE_NLS_MESSAGES`, indexed by number. `out/nls.messages.json`
in the packaged server tree is that array in English, and `out/nls.keys.json`
is the same order expressed as (module, [key, ...]) pairs. vscode-loc publishes
its translations keyed by exactly those module and key names, so a bundle for a
language is the English array with every message that has a translation
replaced, position for position.

Nothing here rebuilds the server. It reads the tree `package-assets.sh` has
already produced and writes one JSON array per language into
`android/app/src/main/assets/nls/`, which `VSCodroidWebViewClient` serves the
page from. Nothing else reads them: the server process cannot be translated from
this side, and `assets/server.js` records why.

The pinned source commit lives in `VSCODE_LOC_COMMIT`, for the same reason
`VSCODE_COMMIT` exists: vscode-loc is updated most days, and a build that
silently picks up a different day's strings is not reproducible.

Run after `scripts/package-assets.sh`:

    python3 scripts/build-nls-bundles.py

Without a network it fails, and the app falls back to English, which is what it
did before this script existed.
"""

from __future__ import annotations

import json
import re
import sys
import urllib.error
import urllib.request
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
ASSETS = REPO_ROOT / "android/app/src/main/assets"
SERVER_OUT = ASSETS / "vscode-reh/out"
DEST = ASSETS / "nls"
COMMIT_FILE = REPO_ROOT / "VSCODE_LOC_COMMIT"

# Every language vscode-loc publishes except `qps-ploc`, which is a debugging
# pseudo-language that renders each string as accented nonsense and would cost
# the same 1.5 MiB as a real one.
#
# This is the whole list, not a selection: it already covers eight of the
# languages people have reviewed the app in, and the ones it misses (Arabic,
# Vietnamese, Persian, Indonesian, Thai, Greek) are missing because nobody has
# translated the editor into them, not because they were left out here.
#
# Named in lower case, which is the form the server asks for: it lowercases the
# locale it reads from the request before building the bundle's URL. vscode-loc
# spells one of its directories differently, hence [PACKS].
LOCALES = [
    "cs", "de", "es", "fr", "it", "ja", "ko",
    "pl", "pt-br", "ru", "tr", "zh-hans", "zh-hant",
]

PACKS = {"pt-br": "pt-BR"}

SOURCE = "https://raw.githubusercontent.com/microsoft/vscode-loc/{commit}/i18n/vscode-language-pack-{pack}/translations/main.i18n.json"

# Below this share of messages translated, something structural has changed --
# vscode-loc renaming a module path, or a VSCODE_VERSION bump moving the keys
# far enough that the packs no longer line up -- and the right outcome is a
# failed build rather than a bundle that is 90 percent English with a language
# name on it. Measured at 98 percent for every language at the pinned commit, so
# the floor is set well below that rather than just under it: the gap is the
# strings a Code - OSS build carries that upstream's own packs do not cover, and
# it widens on its own between a VSCODE_VERSION bump and vscode-loc catching up.
MIN_COVERAGE = 0.45

# What the branding patches renamed in English, kept renamed in translation.
# `patches/0011` rewrites a handful of `localize()` calls so the interface says
# VSCodroid where Code - OSS says Visual Studio Code; a translated bundle would
# put the trademark back, in the exact strings that were changed to remove it.
# Only those positions are touched: the other 140-odd mentions in the English
# array are upstream's own and already ship as they are.
BRANDED = "VSCodroid"

# Matched with the spaces loose and the last word optional, because neither is
# reliable. vscode-loc writes the product name with U+00A0 between the words in
# several languages, and some translations drop "Code" entirely: Polish renders
# the English "Welcome to VSCodroid" as "Program Visual Studio, Zapraszamy!".
# A pattern anchored on the full name left the mark standing in exactly the
# strings the branding patch took it out of. Longest alternative first, so the
# full name is not left half replaced by the short one.
TRADEMARK = re.compile(
    r"Visual[\s\u00a0]+Studio([\s\u00a0]+Code)?"
    r"|VS[\s\u00a0]*Code"
    r"|VSCode"
)


def fetch(url: str) -> bytes:
    with urllib.request.urlopen(url, timeout=120) as response:
        return response.read()


def load_source_order() -> tuple[list[str], list[tuple[str, list[str]]]]:
    messages_file = SERVER_OUT / "nls.messages.json"
    keys_file = SERVER_OUT / "nls.keys.json"
    for path in (messages_file, keys_file):
        if not path.is_file():
            sys.exit(
                f"{path} not found. This reads the packaged server tree, so run\n"
                "  bash scripts/fetch-vscode-oss.sh && bash scripts/package-assets.sh\n"
                "first."
            )
    messages = json.loads(messages_file.read_text(encoding="utf-8"))
    keys = json.loads(keys_file.read_text(encoding="utf-8"))
    flat = sum(len(module_keys) for _, module_keys in keys)
    if flat != len(messages):
        sys.exit(
            f"nls.keys.json describes {flat} messages, nls.messages.json holds "
            f"{len(messages)}. The two no longer share an order, so an index "
            "built from one cannot address the other; nothing was written."
        )
    return messages, keys


def build(commit: str, locale: str, messages: list[str], keys: list[tuple[str, list[str]]]) -> tuple[list[str], float]:
    """Return the translated array and the share of messages that were translated."""
    raw = fetch(SOURCE.format(commit=commit, pack=PACKS.get(locale, locale)))
    contents = json.loads(raw.decode("utf-8"))["contents"]

    out: list[str] = []
    translated = 0
    index = 0
    for module, module_keys in keys:
        module_translations = contents.get(module, {})
        for key in module_keys:
            english = messages[index]
            value = module_translations.get(key)
            if value is None:
                out.append(english)
            else:
                if BRANDED in english:
                    value = TRADEMARK.sub(BRANDED, value)
                out.append(value)
                translated += 1
            index += 1
    return out, translated / len(messages)


if __name__ == "__main__":
    commit = COMMIT_FILE.read_text(encoding="utf-8").strip()
    if not commit:
        sys.exit(f"{COMMIT_FILE} is empty; it must pin a microsoft/vscode-loc commit")

    messages, keys = load_source_order()
    print(f"vscode-loc @ {commit[:12]}, {len(messages)} messages per bundle")

    # Every bundle is fetched and checked before any of them is written, which is
    # the whole reason thirteen of them are held in memory rather than written as
    # they arrive.
    #
    # A bundle only means anything against the English array it was built from:
    # the workbench reads `_VSCODE_NLS_MESSAGES` by position, so a directory
    # holding some bundles built for one VS Code version and some for another
    # renders every string after the first inserted or deleted message under the
    # wrong control, in the languages left over. Nothing downstream can tell.
    # This script is the only thing that ever holds the array and a translation
    # side by side; Gradle sums the directory's size, and the client resolves a
    # bundle by locale alone.
    #
    # Writing inside the loop put that mixture one dropped connection away, and
    # made it durable: CI restores this directory from a cache, a working tree
    # keeps whatever was last written, and either way the half-old set outlives
    # the run that failed and is packaged by the next build. Holding the
    # serialised bundles instead costs about 20 MiB.
    built: list[tuple[str, bytes]] = []
    for locale in LOCALES:
        try:
            bundle, coverage = build(commit, locale, messages, keys)
        except urllib.error.URLError as error:
            sys.exit(f"{locale}: could not fetch translations ({error}). Nothing written.")
        if coverage < MIN_COVERAGE:
            sys.exit(
                f"{locale}: only {coverage:.0%} of messages were translated, below the "
                f"{MIN_COVERAGE:.0%} floor. The key layout has moved; check that "
                "VSCODE_LOC_COMMIT matches VSCODE_VERSION before raising the floor."
            )
        leaked = [
            index for index, english in enumerate(messages)
            if BRANDED in english and TRADEMARK.search(bundle[index])
        ]
        if leaked:
            sample = leaked[0]
            sys.exit(
                f"{locale}: {len(leaked)} of the branded strings still carry Microsoft's "
                f"product name after substitution, for example index {sample}: "
                f"{bundle[sample]!r}. These are the strings patch 0011 rebrands, so shipping "
                "them puts the trademark back into the interface. Widen TRADEMARK to cover "
                "the form this language uses."
            )

        payload = json.dumps(bundle, ensure_ascii=False).encode("utf-8")
        built.append((locale, payload))
        print(f"  {locale:<8} {coverage:.0%} translated, {len(payload) / 1024 / 1024:.1f} MiB")

    # A language dropped from LOCALES has to leave the directory as well as the
    # list. CI restores this directory from a cache keyed on this script, and a
    # partial-key restore can hand a build the previous run's tree, so a bundle
    # nobody generates any more would go on shipping and go on being offered by
    # LocaleCoverageTest's counterpart in res/.
    DEST.mkdir(parents=True, exist_ok=True)
    expected = {f"{locale}.json" for locale in LOCALES}
    for stale in sorted(path for path in DEST.glob("*.json") if path.name not in expected):
        stale.unlink()
        print(f"  removed {stale.name}, no longer in LOCALES")

    for locale, payload in built:
        (DEST / f"{locale}.json").write_bytes(payload)

    print(f"{len(built)} bundles in {DEST.relative_to(REPO_ROOT)}")
