#!/usr/bin/env python3
"""Fail when an APK or AAB has an asset path that is both a file and a directory.

    check-asset-table.py <app.apk | app.aab>
    check-asset-table.py --self-test

First-run setup tells a file from a folder by calling AssetManager.open() first and
AssetManager.list() only when open() fails or the entry opens empty. That holds only
while no asset path is both: an entry that opens with bytes is then a file, never a
folder with children. The Gradle asset merge writes one directory tree, which cannot
hold both, so this reads the finished archive and turns the assumption into a check
that can go red.
"""
import io
import sys
import zipfile


def conflicts(archive):
    files, dirs = set(), set()
    for info in archive.infolist():
        name = info.filename
        if name.startswith("base/"):  # an AAB keeps its base module under base/
            name = name[len("base/"):]
        if not name.startswith("assets/"):
            continue
        name = name[len("assets/"):]
        if info.is_dir():
            dirs.add(name.rstrip("/"))
            continue
        files.add(name)
        parts = name.split("/")
        dirs.update("/".join(parts[:i]) for i in range(1, len(parts)))
    return sorted(files & dirs), len(files)


def check(path_or_file, quiet=False):
    with zipfile.ZipFile(path_or_file) as archive:
        both, count = conflicts(archive)
    if not quiet:
        for name in both:
            print(f"asset path is both a file and a directory: {name}")
        print(f"{count} asset files, {len(both)} paths that are both a file and a directory")
    # An archive with no asset at all has nothing to conflict, so it would pass having read nothing:
    # the shape a build gets when the assets move to another module or another path.
    if not count:
        if not quiet:
            print("no asset files were found, so nothing was checked")
        return 1
    return 1 if both else 0


def self_test():
    def archive(*names):
        buffer = io.BytesIO()
        with zipfile.ZipFile(buffer, "w") as z:
            for name in names:
                z.writestr(name, b"" if name.endswith("/") else b"x")
        buffer.seek(0)
        return buffer

    assert check(archive("assets/a/b", "assets/empty", "classes.dex"), quiet=True) == 0
    assert check(archive("assets/a", "assets/a/b"), quiet=True) == 1
    assert check(archive("assets/a", "assets/a/"), quiet=True) == 1
    assert check(archive("base/assets/a", "base/assets/a/b/c"), quiet=True) == 1
    assert check(archive("classes.dex"), quiet=True) == 1, "an archive with no assets passed unread"
    assert check(archive("base/dex/classes.dex", "toolchain_java/assets/a"), quiet=True) == 1, \
        "an AAB whose assets are in another module passed unread"
    assert check(archive("assets/"), quiet=True) == 1, "a directory entry alone counts as an asset file"
    print("self-test passed")
    return 0


if __name__ == "__main__":
    if sys.argv[1:] == ["--self-test"]:
        sys.exit(self_test())
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    sys.exit(check(sys.argv[1]))
