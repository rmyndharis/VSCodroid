#!/usr/bin/env python3
"""Build the wheelhouse: the wheels VSCodroid mirrors, and the page pip reads.

    build-wheelhouse.py                 verify the manifest, keep the wheels, emit the page
    build-wheelhouse.py --record        record a digest for an entry that has none
    build-wheelhouse.py --check         verify only; write nothing

The wheels are left in --dist (.build/wheelhouse by default), which is what gets
uploaded to the release the page links to. A prerelease, so it can never become
`releases/latest`, which the toolchain downloads resolve:

    gh release create <release-tag> --prerelease --title ... .build/wheelhouse/*.whl

docs/10-RELEASE_PLAN.md section 8.3 is the whole order.

Why this exists. The device ships a Termux CPython whose wheel tags are
`cp314-cp314-android_24_arm64_v8a`, and PyPI publishes almost nothing for that
platform, so `pip install pandas` falls back to the sdist and dies: there is no
compiler here and SELinux refuses to execve one from filesDir. Prebuilt Android
wheels do exist, built by the Termux User Repository. This mirrors the ones we
choose into our own release, with a digest, and points pip at them.

Two properties are the whole point, and neither is decoration.

  * The digest. `#sha256=` in a find-links page is enforced by pip, measured: a
    deliberately corrupted digest is refused with THESE PACKAGES DO NOT MATCH
    THE HASHES FROM THE REQUIREMENTS FILE and exit 1.

  * The ELF gate. We re-host binaries we did not build, so the digest attests
    that a user gets the bytes we mirrored and nothing about the build. This is
    the only independent check we perform on them, and it is not theatre:
    scipy's wheel today carries `libandroid-complex-math` with LOAD segments
    aligned to 0x1000, which Android 16 rejects at 16 KB pages, so
    `scipy.special` would fail to import on some devices and not others. That
    is a build failure here rather than a bug report from a phone.

The page is HTML and must be served as `text/html`. A GitHub release asset is
served `application/octet-stream`, and pip's `_ensure_api_header` skips any page
that is not one of three HTML types, logging a warning and carrying on: the
install then succeeds from PyPI, without the mirror, and nothing looks wrong.
So the wheels are release assets and this page is published to GitHub Pages.

One kind of entry is not a wheel upstream. psutil publishes no Android wheel,
and TUR builds none for this Python, but Termux's own `python-psutil` package is
built for exactly this interpreter. An entry whose `url` is a Termux .deb is
repacked into a wheel here: the files are Termux's, byte for byte, and only the
install-time records are dropped and WHEEL and RECORD rewritten. The repack is
deterministic, so its `sha256` is pinned like any other. The .deb itself is
checked against Termux's signed index, as every other Termux package in this
repository is, and the pool keeps only a package's current revision: once
Termux rebuilds it, the entry has to be re-pinned to the new file.
"""

import argparse
import base64
import hashlib
import io
import json
import os
import pathlib
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile
import urllib.error
import urllib.parse
import urllib.request
import zipfile

REPO = pathlib.Path(__file__).resolve().parent.parent
MANIFEST = REPO / "wheelhouse.json"
VERIFY_ELF = REPO / "scripts" / "verify-android-elf.py"

# What the device links against, and where those libraries live in this repo.
DEVICE_LIB_DIRS = (
    REPO / "android/app/src/main/assets/usr/lib",
    REPO / "android/app/src/main/jniLibs/arm64-v8a",
)

# The platform half of the tag the shipped interpreter accepts; the Python half
# comes from the manifest. Anything else is not for this device, and a wheel
# whose name does not carry it would install nowhere.
ANDROID_PLATFORM = "android_24_arm64_v8a"
TERMUX_LIB = REPO / "scripts/lib/termux-packages.sh"

# A wheel that vendors shared libraries puts them in a top-level directory and
# reaches them through $ORIGIN. verify-android-elf.py cannot know that, so those
# directories are handed to it as extra --lib-dir; without this every module
# that links the vendored OpenBLAS reads as an unresolved dependency, which is
# 3 of numpy's 20 objects and 17 of scipy's 108.
VENDOR_DIR_SUFFIXES = ("-libs", ".libs")

# Redistribution is permitted by these licences and obliges the notice to
# travel. The rule here is only the negative one: refuse a wheel that carries no
# notice file at all, because then re-hosting it byte for byte would not carry
# one either. It does not prove the notices are complete. A wheel that links a
# library statically can omit that library's notice, which is how lxml, with
# GNU libiconv inside it, came to be held back; wheelhouse.json records it.
LICENCE_MARKERS = ("-licenses/", ".dist-info/licenses/", ".dist-info/LICENSE")

# Where a Termux Python package installs, inside its data.tar.
TERMUX_SITE_PACKAGES = "data/data/com.termux/files/usr/lib/python{}/site-packages/"

# Written by pip when Termux installed the package, not part of what a wheel
# carries. RECORD is regenerated.
INSTALL_ONLY = ("INSTALLER", "REQUESTED", "direct_url.json", "RECORD")


def fail(message):
    print(f"  ERROR: {message}", file=sys.stderr)
    return 1


def load_manifest():
    if not MANIFEST.is_file():
        sys.exit(f"FAIL {MANIFEST} is missing; there is nothing to mirror")
    return json.loads(MANIFEST.read_text(encoding="utf-8"))


def required_tag(python_version):
    """`cp314-cp314-android_24_arm64_v8a` for "3.14"."""
    abi = "cp" + python_version.replace(".", "")
    return f"{abi}-{abi}-{ANDROID_PLATFORM}"


def normalised(name):
    """The wheel-filename spelling of a project name (PEP 427, PEP 503)."""
    return re.sub(r"[-_.]+", "_", name).lower()


def page_path(python_version):
    return REPO / "docs/site/wheels" / python_version / "wheels.html"


def bundled_python_version():
    """The Python the APK actually carries, read from the runtime's own name.

    The version is never hardcoded anywhere in this repo: download-python.sh
    resolves it from the Termux index at build time, so it can move without
    anyone deciding. A wheel built for another minor version installs nowhere,
    and the failure would be a user's `pip install` finding no candidate, weeks
    later. See check-wheelhouse-abi.py, which is the gate; this is the reader.
    """
    lib = REPO / "android/app/src/main/assets/usr/lib"
    for path in sorted(lib.glob("libpython3.*.so")):
        return path.name[len("libpython"):-len(".so")]
    return None


def download(url, dest):
    print(f"  fetching {url}")
    try:
        with urllib.request.urlopen(url, timeout=300) as response:
            dest.write_bytes(response.read())
    except (urllib.error.URLError, OSError) as exc:
        sys.exit(f"FAIL could not fetch {url}: {exc}")


def digest_of(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def deb_data_tar(deb):
    """The data.tar member of a .deb, which is an ar archive."""
    blob = deb.read_bytes()
    if not blob.startswith(b"!<arch>\n"):
        sys.exit(f"FAIL {deb.name} is not a .deb")
    offset = 8
    while offset + 60 <= len(blob):
        header = blob[offset:offset + 60]
        member = header[:16].decode().strip().rstrip("/")
        size = int(header[48:58].decode().strip())
        body = blob[offset + 60:offset + 60 + size]
        if member.startswith("data.tar"):
            return tarfile.open(fileobj=io.BytesIO(body))
        offset += 60 + size + (size % 2)
    sys.exit(f"FAIL {deb.name} has no data.tar")


def termux_index_entry(work, deb_filename):
    """The signed Termux index's record for one .deb: {Version, SHA256, ...}.

    Fetched and verified by the same library the download scripts use, so the
    digest compared against here is anchored to Termux's signature and not to
    whichever mirror served the file.
    """
    index_dir = work / "termux-index"
    result = subprocess.run(
        ["bash", "-c", f'. "{TERMUX_LIB}" && termux_fetch_index'],
        env={**os.environ, "WORK_DIR": str(index_dir)},
        capture_output=True, text=True,
    )
    if result.returncode != 0:
        sys.exit(f"FAIL could not fetch and verify Termux's signed index:\n{result.stdout}{result.stderr}")
    package = deb_filename.split("_", 1)[0]
    current = None
    for stanza in (index_dir / "Packages").read_text(encoding="utf-8").split("\n\n"):
        fields = dict(line.split(": ", 1) for line in stanza.splitlines() if ": " in line)
        if fields.get("Package") != package:
            continue
        if fields.get("Filename", "").endswith("/" + deb_filename):
            return fields
        current = fields
    moved = f"Termux now carries {package} {current['Version']} as {current['Filename']}" if current \
        else f"Termux's index no longer lists {package} at all"
    sys.exit(
        f"FAIL {deb_filename} is not in Termux's signed index. {moved}.\n"
        "     The pool keeps only a package's current revision. Point `url` at the new\n"
        "     file, clear `source-sha256` and `sha256`, and re-run with --record."
    )


def repack_termux_deb(deb, name, version, python_version, dest):
    """A wheel holding exactly the files a Termux Python package installs.

    Stored rather than deflated, with fixed timestamps and sorted entries, so
    the same .deb gives the same bytes on any machine and the pinned digest
    holds. Deflate output depends on the zlib a machine links.
    """
    prefix = TERMUX_SITE_PACKAGES.format(python_version)
    files = {}
    with deb_data_tar(deb) as tar:
        for member in tar.getmembers():
            path = member.name.lstrip("./")
            if member.isfile() and path.startswith(prefix):
                files[path[len(prefix):]] = tar.extractfile(member).read()

    # Found rather than spelled, since a project's dist-info directory uses
    # whatever capitalisation and separators its build wrote.
    dist_infos = {rel.split("/", 1)[0] + "/" for rel in files
                  if rel.split("/", 1)[0].endswith(".dist-info")}
    wanted = [d for d in dist_infos
              if normalised(d[:-len(".dist-info/")].rsplit("-", 1)[0]) == normalised(name)
              and d[:-len(".dist-info/")].rsplit("-", 1)[-1] == version]
    if len(wanted) != 1:
        sys.exit(
            f"FAIL {deb.name} does not install {name} {version} for Python {python_version}.\n"
            f"     Expected one {normalised(name)}-{version}.dist-info under {prefix}; found {sorted(dist_infos)}."
        )
    dist_info = wanted[0]
    files = {rel: data for rel, data in files.items()
             if "/__pycache__/" not in f"/{rel}"
             and not (rel.startswith(dist_info) and rel[len(dist_info):] in INSTALL_ONLY)}

    files[f"{dist_info}WHEEL"] = (
        "Wheel-Version: 1.0\n"
        f"Generator: VSCodroid build-wheelhouse.py (repacked from {deb.name})\n"
        "Root-Is-Purelib: false\n"
        f"Tag: {required_tag(python_version)}\n"
    ).encode()

    record = []
    for rel in sorted(files):
        digest = base64.urlsafe_b64encode(hashlib.sha256(files[rel]).digest()).rstrip(b"=").decode()
        record.append(f"{rel},sha256={digest},{len(files[rel])}")
    record.append(f"{dist_info}RECORD,,")
    files[f"{dist_info}RECORD"] = ("\n".join(record) + "\n").encode()

    # dist-info last, the order wheel tools write and pip reads it in.
    order = sorted(files, key=lambda rel: (rel.startswith(dist_info), rel))
    with zipfile.ZipFile(dest, "w", zipfile.ZIP_STORED) as archive:
        for rel in order:
            info = zipfile.ZipInfo(rel, date_time=(1980, 1, 1, 0, 0, 0))
            info.create_system = 3
            info.external_attr = 0o100644 << 16
            archive.writestr(info, files[rel])


def vendor_dirs(unpacked):
    return [p for p in unpacked.iterdir()
            if p.is_dir() and p.name.endswith(VENDOR_DIR_SUFFIXES)]


def check_elves(wheel_path, unpacked):
    """Every shared object in the wheel, through this repo's own ELF gate.

    Returns the number of objects checked, or exits on the first failure.
    """
    lib_args = []
    for directory in list(DEVICE_LIB_DIRS) + vendor_dirs(unpacked):
        lib_args += ["--lib-dir", str(directory)]

    objects = sorted(unpacked.rglob("*.so"))
    for obj in objects:
        result = subprocess.run(
            [sys.executable, str(VERIFY_ELF), str(obj), *lib_args],
            capture_output=True, text=True,
        )
        if result.returncode != 0:
            print(f"  REFUSED {wheel_path.name}", file=sys.stderr)
            print(f"    {obj.relative_to(unpacked)}", file=sys.stderr)
            for line in (result.stdout + result.stderr).splitlines():
                if "FAIL" in line:
                    print(f"      {line.strip()}", file=sys.stderr)
            sys.exit(
                "FAIL that object would be loaded on a user's device and refused there.\n"
                "     Pin a different version, or drop the package until upstream rebuilds it."
            )
    return len(objects)


def check_licence(wheel_path, names):
    # Files only: repair tools leave empty `<name>-licenses/` directory entries.
    if any(marker in name for name in names if not name.endswith("/") for marker in LICENCE_MARKERS):
        return
    sys.exit(
        f"FAIL {wheel_path.name} carries no licence notice.\n"
        "     Re-hosting it would redistribute the binary without the notice its\n"
        "     licence requires. Do not repackage to add one; refuse the wheel."
    )


def check_not_yanked(name, version):
    """PyPI's opinion of this version, which our page cannot carry.

    Yankedness rides on the index page as `data-yanked`, not in the version, so
    a PEP 592 yank upstream never reaches a find-links page and pip's own yank
    warning stays silent for our copy. Asking here is the only place it can be
    asked. Returns whether PyPI answered.
    """
    url = f"https://pypi.org/pypi/{name}/{version}/json"
    try:
        with urllib.request.urlopen(url, timeout=60) as response:
            info = json.loads(response.read()).get("info", {})
    except urllib.error.HTTPError as exc:
        if exc.code == 404:
            print(f"  note   PyPI does not know {name} {version}; nothing to ask about yanks")
        else:
            print(f"  note   could not ask PyPI about {name} {version}: {exc}")
        return False
    except (urllib.error.URLError, OSError, ValueError) as exc:
        print(f"  note   could not ask PyPI about {name} {version}: {exc}")
        return False
    if info.get("yanked"):
        sys.exit(
            f"FAIL {name} {version} is yanked on PyPI: {info.get('yanked_reason') or 'no reason given'}\n"
            "     A find-links page cannot carry that, so mirroring it would serve a\n"
            "     version upstream has withdrawn, silently. Pin a different one."
        )
    return True


def page(entries, release_tag):
    """The find-links page, pointing at OUR release rather than upstream.

    The manifest's `url` is where a wheel came from, kept so the provenance
    chain is auditable rather than asserted. What pip is handed is our own
    release asset, because mirroring is the point: a page that linked upstream
    would leave users fetching from a third party we do not control, with our
    digest in front of it, which is the worst of both arrangements.

    Absolute hrefs, because pip resolves a relative one against the response's
    final URL, and a release-asset URL redirects to an expiring signed address
    on another host.
    """
    base = f"https://github.com/rmyndharis/VSCodroid/releases/download/{release_tag}"
    rows = []
    for entry in entries:
        url = f"{base}/{entry['filename']}"
        parsed = urllib.parse.urlparse(url)
        if not parsed.scheme or not parsed.netloc:
            sys.exit(f"FAIL {url} is not absolute, so pip would resolve it against the wrong host")
        rows.append(f'    <a href="{url}#sha256={entry["sha256"]}">{entry["filename"]}</a><br>')
    return (
        "<!DOCTYPE html>\n<html><head><meta charset=\"utf-8\">\n"
        "<title>VSCodroid wheelhouse</title></head>\n<body>\n"
        "<h1>VSCodroid wheelhouse</h1>\n"
        "<p>Prebuilt Android wheels mirrored for the Python VSCodroid bundles.\n"
        "Not compiled here; the manifest records where each one came from.</p>\n"
        + "\n".join(rows)
        + "\n</body></html>\n"
    )


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--record", action="store_true",
                        help="record a sha256 for any entry that has none")
    parser.add_argument("--check", action="store_true",
                        help="verify only; write nothing")
    parser.add_argument("--out", type=pathlib.Path,
                        help="where to write the find-links page (default: docs/site/wheels/<python>/wheels.html)")
    parser.add_argument("--dist", type=pathlib.Path,
                        default=REPO / ".build/wheelhouse",
                        help="where to leave the wheels for the release upload")
    args = parser.parse_args()
    if args.check and args.record:
        parser.error("--record writes wheelhouse.json, and --check writes nothing")

    manifest = load_manifest()
    declared = manifest.get("python-version")
    bundled = bundled_python_version()
    if bundled and declared != bundled:
        return fail(
            f"the manifest is for Python {declared} and the APK carries {bundled}. "
            "Every wheel here would install nowhere."
        )
    tag = required_tag(declared)
    out = args.out or page_path(declared)

    with tempfile.TemporaryDirectory(prefix="wheelhouse-") as tmp:
        work = pathlib.Path(tmp)
        return build(args, manifest, declared, tag, out, work)


def build(args, manifest, declared, tag, out, work):
    entries = []
    # Built aside and moved into --dist only once every entry has verified, so a
    # failed run leaves the previous wheels where they were.
    staged = work / "dist"
    staged.mkdir()
    dirty = False

    def pin(package, key, actual, what):
        nonlocal dirty
        recorded = package.get(key)
        if not recorded:
            if not args.record:
                sys.exit(
                    f"FAIL {package['name']} has no {key}. Re-run with --record to pin {actual}, "
                    f"after satisfying yourself the {what} is the one you meant."
                )
            package[key] = actual
            dirty = True
            print(f"  recorded {key} {actual}")
        elif recorded != actual:
            sys.exit(
                f"FAIL {package['name']} {package['version']} does not match its pinned {key}.\n"
                f"    expected {recorded}\n    got      {actual}\n"
                f"    The {what} changed under a version that is supposed to be\n"
                "    immutable. Do not update the digest until you know why."
            )

    for package in manifest["packages"]:
        name, version, url = package["name"], package["version"], package["url"]
        print(f"{name} {version}")

        if url.endswith(".deb"):
            deb = work / url.rsplit("/", 1)[-1]
            # Asked first: once Termux rebuilds the package the pool drops this
            # file, and the index names what replaced it where a 404 would not.
            indexed = termux_index_entry(work, deb.name)
            download(url, deb)
            actual = digest_of(deb)
            if indexed.get("SHA256") != actual:
                sys.exit(
                    f"FAIL {deb.name} does not match the digest Termux's signed index lists.\n"
                    f"    index {indexed.get('SHA256')}\n    got   {actual}"
                )
            pin(package, "source-sha256", actual, "Termux package")
            filename = f"{normalised(name)}-{version}-{tag}.whl"
            local = staged / filename
            repack_termux_deb(deb, name, version, declared, local)
        else:
            filename = url.rsplit("/", 1)[-1]
            if tag not in filename:
                return fail(f"{filename} does not carry {tag}, so this device cannot install it")
            local = staged / filename
            download(url, local)

        pin(package, "sha256", digest_of(local), "wheel")

        unpacked = work / f"{normalised(name)}-unpacked"
        with zipfile.ZipFile(local) as archive:
            names = archive.namelist()
            archive.extractall(unpacked)
        check_licence(local, names)
        checked = check_elves(local, unpacked)
        yank = "not yanked" if check_not_yanked(name, version) else "yank status unknown"
        print(f"  ok     {checked} shared objects, licence present, {yank}")

        entries.append({"filename": filename, "url": url, "sha256": package["sha256"]})

    if args.check:
        print(f"\n{len(entries)} wheels verified; nothing written (--check)")
        return 0

    if dirty:
        MANIFEST.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
        print(f"\nUpdated {MANIFEST.relative_to(REPO)}")

    args.dist.mkdir(parents=True, exist_ok=True)
    for stale in args.dist.glob("*.whl"):
        stale.unlink()
    for wheel in staged.glob("*.whl"):
        shutil.move(str(wheel), str(args.dist / wheel.name))

    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(page(entries, manifest["release-tag"]), encoding="utf-8")
    print(f"\nWrote {out.relative_to(REPO)} listing {len(entries)} wheels")
    print(f"The wheels are in {args.dist}; upload them to the {manifest['release-tag']} release.")
    print("The page must be served as text/html, which GitHub Pages does.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
