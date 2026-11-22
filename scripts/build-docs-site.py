#!/usr/bin/env python3
"""Render the user-facing documents into the published site.

    build-docs-site.py

Three pages, each generated from Markdown that is already maintained in the
repository, rather than transcribed into HTML beside it. The privacy policy used to
exist twice, as `docs/PRIVACY_POLICY.md` and a hand-written
`docs/site/privacy-policy.html`, with nothing holding the two together; that is
the same shape as every other defect where one fact was kept in two places and
only one of them was updated.

What is deliberately NOT published: everything else under `docs/`. The product
requirements, the architecture and technical specifications, the risk matrix,
the release plan and the implementation plans are working documents for people
changing the app. Publishing them put a reader looking for how to open a folder
in front of a milestone table, and it is why this script names its inputs one at
a time rather than walking a directory.

The site's own stylesheet is shared with the hand-written landing page, so a
change to one is a change to all four.
"""

import html
import pathlib
import re
import sys

try:
    import markdown
except ImportError:
    sys.exit(
        "FAIL python-markdown is not installed. This is a build dependency of "
        "the docs site: pip install 'markdown==3.8'"
    )

ROOT = pathlib.Path(__file__).resolve().parent.parent
SITE = ROOT / "docs/site"

# Each entry: source, output, page title, and the short label used in the nav.
PAGES = [
    {
        "source": ROOT / "docs/USER_GUIDE.md",
        "output": SITE / "guide.html",
        "title": "User guide",
        "description": (
            "How to use VSCodroid: the editor, the terminal, extensions, Git and "
            "SSH, on-demand toolchains, and what Android does not allow."
        ),
        "nav": "guide",
    },
    {
        "source": ROOT / "docs/PRIVACY_POLICY.md",
        "output": SITE / "privacy-policy.html",
        "title": "Privacy policy",
        "description": "What VSCodroid stores, what it sends, and to whom.",
        "nav": "privacy",
    },
    {
        "source": ROOT / "CHANGELOG.md",
        "output": SITE / "changelog.html",
        "title": "Release notes",
        "description": "What changed in each release of VSCodroid.",
        # Not in the header: it has room for three links on a phone.
        "nav": "changelog",
    },
]

NAV = [
    ("guide", "guide.html", "User guide"),
    ("privacy", "privacy-policy.html", "Privacy"),
    (None, "https://github.com/rmyndharis/VSCodroid", "GitHub"),
]

# Filled in by assets/site.js from what the deploy read; hidden until then.
STARS = ' <span class="stars" hidden><span data-live="stars"></span><span class="sr"> stars</span></span>'

# The guide opens with its own table of contents, written for a reader scrolling
# one long Markdown file. The page builds a sidebar from the same headings, so
# publishing both would be the same list twice, disagreeing the first time one
# of them is edited.
INLINE_TOC = re.compile(
    r"^## Table of Contents\s*\n.*?(?=^## |\Z)", re.M | re.S
)

# The changelog opens with what has been merged and not released. A reader of
# the site can install none of it, so the page starts at the newest release.
# Up to the next section or the end of the file: a changelog whose only section
# is the unreleased one would otherwise publish it whole.
UNRELEASED = re.compile(r"^## \[Unreleased\]\s*\n.*?(?=^## |\Z)", re.M | re.S)

# Wide content scrolls inside its own box rather than making the page scroll.
TABLE = re.compile(r"(<table>.*?</table>)", re.S)


def slugify(text: str) -> str:
    text = re.sub(r"<[^>]+>", "", text)
    text = html.unescape(text).strip().lower()
    text = re.sub(r"[^\w\s-]", "", text)
    return re.sub(r"[\s_]+", "-", text).strip("-")


def render(source: pathlib.Path) -> "tuple[str, list[tuple[str, str]]]":
    if not source.is_file():
        sys.exit(f"FAIL {source} is missing, so there is nothing to publish")

    text = source.read_text(encoding="utf-8")
    text = INLINE_TOC.sub("", text)
    text = UNRELEASED.sub("", text)

    # The first heading becomes the page title, which the shell renders itself.
    text = re.sub(r"\A#\s+.*?\n", "", text, count=1)

    md = markdown.Markdown(extensions=["tables", "fenced_code", "sane_lists"])
    body = md.convert(text)

    # The privacy policy carries two dates on two source lines, which Markdown
    # joins into one. Keep them on two lines.
    body = re.sub(
        r"(Effective Date:[^<]*</strong>)\n(<strong>Last Updated)", r"\1<br>\n\2", body
    )

    # Anchors, added here rather than by the toc extension so the ids match the
    # sidebar exactly and nothing depends on that extension's slug rules.
    entries: "list[tuple[str, str]]" = []
    seen: "dict[str, int]" = {}

    def anchor(match: "re.Match[str]") -> str:
        level, inner = match.group(1), match.group(2)
        slug = slugify(inner)
        # The changelog has a "Fixed" under every release, and an id has to be
        # the only one on its page.
        seen[slug] = seen.get(slug, 0) + 1
        if seen[slug] > 1:
            slug = f"{slug}-{seen[slug]}"
        if level == "2":
            entries.append((slug, re.sub(r"<[^>]+>", "", inner)))
        return f'<h{level} id="{slug}">{inner}</h{level}>'

    body = re.sub(r"<h([23])>(.*?)</h\1>", anchor, body, flags=re.S)
    body = TABLE.sub(r'<div class="table-scroll">\1</div>', body)
    return body, entries


def shell(page: dict, body: str, entries: "list[tuple[str, str]]") -> str:
    nav = "\n".join(
        f'      <a href="{href}"'
        + (' aria-current="page"' if key and key == page["nav"] else "")
        + f">{label}{STARS if key is None else ''}</a>"
        for key, href, label in NAV
    )
    # The label is a heading's HTML with the tags taken off, so its text is
    # already escaped: escaping it again printed "&amp;amp;" for an ampersand.
    toc = "\n".join(
        f'          <li><a href="#{slug}">{html.escape(html.unescape(label))}</a></li>'
        for slug, label in entries
    )
    aside = (
        f"""      <nav class="toc" aria-label="On this page">
        <p>On this page</p>
        <ul>
{toc}
        </ul>
      </nav>
"""
        if entries
        else ""
    )
    return f"""<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<title>{html.escape(page["title"])} | VSCodroid</title>
<meta name="description" content="{html.escape(page["description"])}">
<meta name="color-scheme" content="light dark">
<meta name="theme-color" content="#0e1116">
<link rel="icon" href="assets/vscodroid.png" type="image/png">
<link rel="preload" href="assets/fonts/plus-jakarta-sans.woff2" as="font" type="font/woff2" crossorigin>
<link rel="stylesheet" href="assets/style.css">
<script src="assets/site.js" defer></script>
</head>
<body>

<a class="skip" href="#main">Skip to content</a>

<header class="site-header">
  <div class="wrap">
    <a class="brand" href="./">
      <img src="assets/vscodroid.png" alt="" width="128" height="128">
      VSCodroid
    </a>
    <nav class="site-nav" aria-label="Main">
{nav}
    </nav>
  </div>
</header>

<main id="main" class="doc">
  <div class="wrap doc-layout">
{aside}      <article class="content">
        <h1>{html.escape(page["title"])}</h1>
{body}
      </article>
  </div>
</main>

<footer class="site-footer">
  <div class="wrap">
    <div class="footer-grid">
      <div>
        <a class="brand" href="./">
          <img src="assets/vscodroid.png" alt="" width="128" height="128">
          VSCodroid
        </a>
      </div>
      <nav class="footer-links" aria-label="Footer">
        <a href="guide.html">User guide</a>
        <a href="privacy-policy.html">Privacy policy</a>
        <a href="https://github.com/rmyndharis/VSCodroid">Source{STARS}</a>
        <a href="changelog.html">Release notes</a>
        <a href="https://github.com/rmyndharis/VSCodroid/releases">Releases</a>
        <a href="https://github.com/rmyndharis/VSCodroid/issues">Report an issue</a>
      </nav>
    </div>
    <p class="disclaimer">
      VSCodroid is not affiliated with or endorsed by Microsoft Corporation.
      "Visual Studio Code" and "VS Code" are trademarks of Microsoft. Built from
      the MIT-licensed Code&nbsp;-&nbsp;OSS source code, and using the Open VSX
      extension registry rather than the Microsoft Marketplace.
    </p>
  </div>
</footer>

</body>
</html>
"""


def main() -> int:
    if not SITE.is_dir():
        sys.exit(f"FAIL {SITE} is missing")

    for page in PAGES:
        body, entries = render(page["source"])
        page["output"].write_text(shell(page, body, entries), encoding="utf-8")
        rel = page["output"].relative_to(ROOT)
        print(f"  ok     {rel}  ({len(entries)} sections, {len(body):,} bytes)")

    return 0


if __name__ == "__main__":
    sys.exit(main())
