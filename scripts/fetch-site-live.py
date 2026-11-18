#!/usr/bin/env python3
"""Write the two facts the site shows that only GitHub knows.

    fetch-site-live.py

The landing page names the latest release and the number of stars. Typed into
the HTML, both are wrong by the next release, so the deploy asks GitHub for them
and writes `docs/site/assets/live.json`, which `assets/site.js` reads. A page
without that file keeps the text it was written with.

Asked when the site is deployed, not from the reader's browser, for two reasons.
The site makes no request to another origin, and that stays true. And GitHub
answers an address without a token 60 times an hour, counted across everyone
behind the same carrier address, which for a page read on phones is most of the
audience.

A failure here does not fail the deploy. The numbers are worth less than a
correction to the guide or the privacy policy going out, so the reason is
printed (as a warning annotation under Actions), no file is written, and the
page shows its own text. What is never written is a value this script could not
check: the browser puts these strings on the page.
"""

import http.client
import json
import os
import pathlib
import re
import sys
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parent.parent
OUTPUT = ROOT / "docs/site/assets/live.json"
API = "https://api.github.com/repos/"

# A release tag as this project writes them. Server builds are tagged
# `server-<version>` and are prereleases, which `releases/latest` never returns;
# this refuses one all the same.
VERSION = re.compile(r"v\d+\.\d+\.\d+")


def stars_text(count) -> str:
    """380 stays 380; 12345 becomes 12.3k, rounded down as GitHub shows it."""
    if type(count) is not int or count < 0:
        raise ValueError(f"the star count is {count!r}, not a number of stars")
    if count < 1000:
        return str(count)
    return f"{count // 100 / 10:.1f}".removesuffix(".0") + "k"


def live(repository: dict, release: dict) -> "dict[str, str]":
    """The file's contents from the two API answers, or ValueError."""
    if not isinstance(repository, dict) or not isinstance(release, dict):
        raise ValueError("GitHub answered with something other than an object")
    tag = release.get("tag_name")
    if not isinstance(tag, str) or not VERSION.fullmatch(tag):
        raise ValueError(f"the latest release is tagged {tag!r}, not vX.Y.Z")
    return {"version": tag, "stars": stars_text(repository.get("stargazers_count"))}


def fetch(path: str) -> dict:
    headers = {
        "Accept": "application/vnd.github+json",
        "User-Agent": "docs-site-build",
    }
    token = os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN")
    if token:
        headers["Authorization"] = f"Bearer {token}"
    request = urllib.request.Request(API + path, headers=headers)
    with urllib.request.urlopen(request, timeout=20) as response:
        return json.load(response)


def main() -> int:
    slug = os.environ.get("GITHUB_REPOSITORY", "rmyndharis/VSCodroid")
    try:
        data = live(fetch(slug), fetch(slug + "/releases/latest"))
    # HTTPException is an answer cut off partway, which is neither of the others.
    except (OSError, ValueError, http.client.HTTPException) as error:
        note = f"the site keeps its own text for the release and stars: {error}"
        print(f"::warning::{note}" if os.environ.get("GITHUB_ACTIONS") else f"  skip   {note}")
        return 0
    OUTPUT.write_text(json.dumps(data) + "\n", encoding="utf-8")
    print(f"  ok     {OUTPUT.relative_to(ROOT)}  {data}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
