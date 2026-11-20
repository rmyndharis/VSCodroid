#!/usr/bin/env python3
"""Compile the one wheel the wheelhouse builds itself: cffi.

    build-cffi-wheel.py [--dist DIR]    build the wheel into DIR (.build/wheelhouse-built by default)

Why this exists. cryptography imports `_cffi_backend` when it loads, so a mirrored
cryptography wheel is of no use without cffi. cffi publishes no Android wheel, the
Termux User Repository builds none for this Python, and Termux's own packages get
it by compiling it on the phone, which a device with no compiler cannot do. So this
compiles cffi's single extension module for the bundled interpreter and packs it
with cffi's Python files into a wheel. Nothing of cffi is changed.

Everything that goes in is named by `built-from` in cffi's entry of wheelhouse.json:

  * the source distribution on PyPI, by digest;
  * Termux's `python` and `libffi` packages, which give the headers and the two
    libraries the module links, each checked against Termux's signed index and
    against the digest pinned here;
  * the NDK revision, which has to be the one installed, because another compiler
    writes other bytes.

The flags are the ones cffi's own setup.py passes where libffi is a system
library, plus the 16 KB page size every binary on the device needs. The module
links `libffi.so` and `libpython`, both of which the APK carries, and goes
through this repository's ELF gate before it is packed.

The wheel is built once per cffi version and Python minor and uploaded to the
wheelhouse release. wheelhouse.json then names that asset as the entry's `url`
and pins its digest, and build-wheelhouse.py treats it like any other wheel, so
adding or moving another package needs no compiler. Termux's pool keeps only a
package's current revision: when this has to run again for a new version, the two
header packages are re-pinned to whatever Termux then carries for the same Python
minor. docs/10-RELEASE_PLAN.md section 8.3 has the order.
"""

import argparse
import base64
import hashlib
import importlib.util
import os
import pathlib
import subprocess
import sys
import tarfile
import tempfile
import tomllib
import zipfile

REPO = pathlib.Path(__file__).resolve().parent.parent
NAME = "cffi"
# What cffi's setup.py defines for a compiler that has `__thread` and
# `__sync_synchronize`, which this one does, with libffi taken from the system.
DEFINES = ("FFI_BUILDING=1", "USE__THREAD", "HAVE_SYNC_SYNCHRONIZE", "NDEBUG")
# The lowest API level the wheel's tag promises, `android_24`.
API = 24


def wheelhouse():
    """build-wheelhouse.py as a module: the manifest, the downloads and the Termux index check."""
    spec = importlib.util.spec_from_file_location("build_wheelhouse", REPO / "scripts/build-wheelhouse.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def find_ndk(revision, environ, home):
    """The NDK of exactly `revision`, or exit saying which ones were found."""
    candidates = [environ.get("ANDROID_NDK_HOME"), environ.get("ANDROID_NDK_ROOT")]
    for sdk in (environ.get("ANDROID_HOME"), str(home / "Library/Android/sdk"), str(home / "Android/Sdk")):
        if sdk:
            candidates.append(str(pathlib.Path(sdk) / "ndk" / revision))
    found = []
    for candidate in filter(None, candidates):
        properties = pathlib.Path(candidate) / "source.properties"
        if not properties.is_file():
            continue
        for line in properties.read_text(encoding="utf-8").splitlines():
            key, _, value = line.partition("=")
            if key.strip() == "Pkg.Revision":
                if value.strip() == revision:
                    return pathlib.Path(candidate)
                found.append(f"{candidate} is {value.strip()}")
    sys.exit(
        f"FAIL this wheel is built with NDK {revision}, and none was found"
        + (": " + "; ".join(found) if found else "")
        + f".\n     Install it (sdkmanager 'ndk;{revision}') or point ANDROID_NDK_HOME at it. Another\n"
        "     NDK compiles other bytes, and the digest wheelhouse.json pins would not be reproducible."
    )


def compiler(ndk):
    hosts = sorted((ndk / "toolchains/llvm/prebuilt").glob("*/bin/clang"))
    if len(hosts) != 1:
        sys.exit(f"FAIL expected one prebuilt toolchain under {ndk}, found {len(hosts)}")
    return hosts[0].parent / f"aarch64-linux-android{API}-clang"


def members(archive, prefix):
    """The regular files of a tar under `prefix`, as {path below prefix: bytes}. Links are left out."""
    files = {}
    for member in archive.getmembers():
        path = member.name.lstrip("./")
        if member.isfile() and path.startswith(prefix):
            files[path[len(prefix):]] = archive.extractfile(member).read()
    return files


def write_tree(root, files):
    for relative, data in files.items():
        if relative.startswith("/") or ".." in pathlib.PurePosixPath(relative).parts:
            sys.exit(f"FAIL {relative} would be written outside {root}")
        target = root / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)


def entry_points(project):
    """entry_points.txt as a build backend writes it from the [project] table of pyproject.toml."""
    groups = dict(project.get("entry-points", {}))
    for table, group in (("scripts", "console_scripts"), ("gui-scripts", "gui_scripts")):
        if project.get(table):
            groups[group] = project[table]
    return "\n\n".join(
        f"[{group}]\n" + "\n".join(f"{name} = {target}" for name, target in sorted(entries.items()))
        for group, entries in sorted(groups.items())
    ) + "\n"


def licence_files(project, source):
    """The files [project] names as the licence, which the wheel has to carry."""
    names = project.get("license-files", [])
    missing = [name for name in names if name not in source]
    if not names or missing:
        sys.exit(f"FAIL the source distribution names no licence file this script can take: {names}")
    return {name: source[name] for name in names}


def pack(dest, version, tag, module, package, metadata, licences, points, source):
    """A wheel of the compiled module and cffi's own files.

    Stored rather than deflated, with fixed timestamps and sorted entries, as
    build-wheelhouse.py repacks a Termux package: the same inputs give the same
    bytes on any machine, so the digest pinned in wheelhouse.json holds.
    """
    info = f"{NAME}-{version}.dist-info/"
    files = {module.name: module.read_bytes()}
    files.update({f"{NAME}/{name}": data for name, data in package.items()})
    files[f"{info}METADATA"] = metadata
    files.update({f"{info}licenses/{name}": data for name, data in licences.items()})
    files[f"{info}entry_points.txt"] = points.encode()
    files[f"{info}top_level.txt"] = f"{module.name.split('.', 1)[0]}\n{NAME}\n".encode()
    files[f"{info}WHEEL"] = (
        "Wheel-Version: 1.0\n"
        f"Generator: VSCodroid build-cffi-wheel.py (compiled from {source})\n"
        "Root-Is-Purelib: false\n"
        f"Tag: {tag}\n"
    ).encode()
    record = []
    for relative in sorted(files):
        digest = base64.urlsafe_b64encode(hashlib.sha256(files[relative]).digest()).rstrip(b"=").decode()
        record.append(f"{relative},sha256={digest},{len(files[relative])}")
    record.append(f"{info}RECORD,,")
    files[f"{info}RECORD"] = ("\n".join(record) + "\n").encode()
    with zipfile.ZipFile(dest, "w", zipfile.ZIP_STORED) as archive:
        for relative in sorted(files, key=lambda r: (r.startswith(info), r)):
            entry = zipfile.ZipInfo(relative, date_time=(1980, 1, 1, 0, 0, 0))
            entry.create_system = 3
            entry.external_attr = (0o100755 if relative == module.name else 0o100644) << 16
            archive.writestr(entry, files[relative])


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--dist", type=pathlib.Path, default=REPO / ".build/wheelhouse-built",
                        help="where to leave the wheel")
    args = parser.parse_args()

    wh = wheelhouse()
    manifest = wh.load_manifest()
    python = manifest["python-version"]
    entry = next((p for p in manifest["packages"] if p["name"] == NAME), None)
    if entry is None or "built-from" not in entry:
        sys.exit(f"FAIL wheelhouse.json has no {NAME} entry with a built-from; there is nothing to build")
    version, built = entry["version"], entry["built-from"]
    ndk = find_ndk(built["ndk"], os.environ, pathlib.Path.home())
    clang = compiler(ndk)

    with tempfile.TemporaryDirectory(prefix="cffi-wheel-") as tmp:
        work = pathlib.Path(tmp)
        sdist = work / built["sdist"].rsplit("/", 1)[-1]
        wh.download(built["sdist"], sdist)
        if wh.digest_of(sdist) != built["sdist-sha256"]:
            sys.exit(f"FAIL {sdist.name} does not match its pinned digest\n    expected {built['sdist-sha256']}\n    got      {wh.digest_of(sdist)}")
        top = f"{NAME}-{version}/"
        with tarfile.open(sdist) as archive:
            source = members(archive, top)
        if f"src/c/_{NAME}_backend.c" not in source:
            sys.exit(f"FAIL {sdist.name} is not {NAME} {version}: it has no {top}src/c/_{NAME}_backend.c")
        # The tree as the source distribution lays it out: the module includes ../cffi/_cffi_errors.h.
        write_tree(work, {k: v for k, v in source.items() if k.startswith("src/")})

        usr = "data/data/com.termux/files/usr/"
        for package in built["headers"]:
            deb = work / package["url"].rsplit("/", 1)[-1]
            indexed = wh.termux_index_entry(work, deb.name)
            wh.download(package["url"], deb)
            actual = wh.digest_of(deb)
            if not (indexed.get("SHA256") == actual == package["sha256"]):
                sys.exit(
                    f"FAIL {deb.name} is not the file pinned for this build.\n"
                    f"    pinned {package['sha256']}\n    index  {indexed.get('SHA256')}\n    got    {actual}"
                )
            with wh.deb_data_tar(deb) as archive:
                write_tree(work / "usr", {k: v for k, v in members(archive, usr).items()
                                          if k.startswith("include/") or (k.startswith("lib/lib") and k.endswith(".so"))})

        module = work / f"_{NAME}_backend.cpython-{python.replace('.', '')}-aarch64-linux-android.so"
        command = [
            str(clang), "-shared", "-fPIC", "-O2", *[f"-D{d}" for d in DEFINES],
            f"-I{work / 'usr/include' / ('python' + python)}", f"-I{work / 'usr/include'}",
            # No path of this machine in the object, so that a rebuild elsewhere gives the same bytes.
            f"-ffile-prefix-map={work}=.", "-Wno-deprecated-declarations",
            str(work / "src/c" / f"_{NAME}_backend.c"),
            f"-L{work / 'usr/lib'}", "-lffi", f"-lpython{python}",
            "-Wl,-z,max-page-size=16384", "-Wl,--build-id=none", "-o", str(module),
        ]
        print("  compiling " + " ".join(command[1:]).replace(str(work), "<work>"))
        if subprocess.run(command).returncode != 0:
            sys.exit(f"FAIL {clang.name} could not compile _{NAME}_backend")

        tag = wh.required_tag(python)
        args.dist.mkdir(parents=True, exist_ok=True)
        wheel = args.dist / f"{NAME}-{version}-{tag}.whl"
        project = tomllib.loads(source["pyproject.toml"].decode("utf-8"))["project"]
        pack(
            wheel, version, tag, module,
            {k[len("src/cffi/"):]: v for k, v in source.items()
             if k.startswith("src/cffi/") and k.endswith((".py", ".h")) and "/" not in k[len("src/cffi/"):]},
            source["PKG-INFO"], licence_files(project, source), entry_points(project), sdist.name,
        )
        unpacked = work / "unpacked"
        with zipfile.ZipFile(wheel) as archive:
            archive.extractall(unpacked)
        checked = wh.check_elves(wheel, unpacked)

    print(f"\n{wheel}\n  {wheel.stat().st_size} bytes, {checked} shared object through the ELF gate")
    print(f"  sha256 {wh.digest_of(wheel)}")
    print(f"Upload it to the {manifest['release-tag']} release, then run build-wheelhouse.py --record.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
