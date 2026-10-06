#!/usr/bin/env bash
set -euo pipefail

# Alpine's x86_64 loader is aligned to 4 KB. Rebuild the same patched musl
# release with 16 KB LOAD alignment, using a pinned Alpine source recipe.
# APKBUILD is read as data, never sourced or executed.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"
. "$SCRIPT_DIR/lib/android-target.sh"
if [ "$ANDROID_ABI" != x86_64 ]; then
    echo "ERROR: source musl rebuild is only needed for x86_64" >&2
    exit 1
fi
if [ "$#" != 4 ] || [ "$1" != --output ] || [ "$3" != --version ]; then
    echo "Usage: VSCODROID_ABI=x86_64 bash scripts/build-musl-loader.sh --output FILE --version 1.2.5-r23" >&2
    exit 1
fi
if [ "$4" != 1.2.5-r23 ]; then
    echo "ERROR: musl $4 needs a reviewed source recipe pin; current pin is 1.2.5-r23" >&2
    exit 1
fi
python3 - "$ROOT_DIR" "$2" <<'PY'
import hashlib
import os
import platform
from pathlib import Path
import re
import shutil
import subprocess
import sys
import urllib.request

root, output = map(Path, sys.argv[1:])
commit = "8aef0c37b0ad23dc4137f0e4755b97a59dc698b8"
recipe_sha256 = "e8425e24c5c3c6d8f74b4412b11c6990fda6dbf7648c79ec6e2efa082fc5adcf"
base = f"https://raw.githubusercontent.com/alpinelinux/aports/{commit}/main/musl"
cache = root / "toolchains/musl/x86_64/source"
cache.mkdir(parents=True, exist_ok=True)

def fetch(url, path, algorithm, expected):
    if not path.exists() or hashlib.new(algorithm, path.read_bytes()).hexdigest() != expected:
        temporary = path.with_suffix(path.suffix + ".download")
        with urllib.request.urlopen(url, timeout=120) as response, temporary.open("wb") as target:
            shutil.copyfileobj(response, target)
        if hashlib.new(algorithm, temporary.read_bytes()).hexdigest() != expected:
            temporary.unlink()
            raise SystemExit(f"ERROR: source digest mismatch: {path.name}")
        temporary.replace(path)
    print(f"  source verified: {path.name}", flush=True)

recipe = cache / "APKBUILD"
fetch(base + "/APKBUILD", recipe, "sha256", recipe_sha256)
text = recipe.read_text()
hash_block = re.search(r'sha512sums="\n(.*?)\n"', text, re.S)
if not hash_block:
    raise SystemExit("ERROR: pinned source recipe has no sha512sums")
hashes = dict((name, digest) for digest, name in
              re.findall(r"^([0-9a-f]{128})\s+(\S+)$", hash_block[1], re.M))
archive = cache / "musl-1.2.5.tar.gz"
fetch("https://musl.libc.org/releases/" + archive.name, archive, "sha512", hashes[archive.name])
patches = [name for name in hashes if name.endswith(".patch")]
if not patches:
    raise SystemExit("ERROR: missing Alpine maintenance patches")
for name in patches:
    if not re.fullmatch(r"[A-Za-z0-9_.+-]+\.patch", name):
        raise SystemExit("ERROR: invalid source patch filename")
    fetch(base + "/" + name, cache / name, "sha512", hashes[name])

build = root / ".build/musl/x86_64"
build.mkdir(parents=True, exist_ok=True)
source = build / "musl-1.2.5"
if source.exists():
    shutil.rmtree(source)
subprocess.run(["tar", "-xzf", str(archive), "-C", str(build)], check=True)
for name in patches:
    subprocess.run(["patch", "-p1", "--batch", "--forward", "-i", str(cache / name)],
                   cwd=source, check=True)
# Match Alpine's prepare step for this recipe.
for name in ("memcpy.s", "memmove.s"):
    (source / "src/string/x86_64" / name).unlink()
(source / "VERSION").write_text("1.2.5\n")
# musl's x86_64 ABI uses 80-bit long double. Android's NDK uses 128-bit
# long double and its compiler builtins cannot link this libc; use Linux GCC.
# This loader uses raw Linux syscalls and has no dependency on host glibc.
flags = "-fno-stack-protector -U_FORTIFY_SOURCE -D_FORTIFY_SOURCE=0"
ldflags = ("-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384 "
           "-Wl,--build-id=sha1 -Wl,-soname,libc.musl-x86_64.so.1")
configure = ["./configure", "--target=x86_64-linux-musl", "--prefix=/usr",
             "--syslibdir=/lib", "--disable-static"]
if platform.system() == "Linux" and platform.machine() == "x86_64":
    env = dict(os.environ, CC="gcc", AR="ar", RANLIB="ranlib",
               CFLAGS=flags, LDFLAGS=ldflags)
    subprocess.run(configure, cwd=source, env=env, check=True)
    subprocess.run(["make", "-j4"], cwd=source, env=env, check=True)
else:
    image = os.environ.get("MUSL_BUILD_IMAGE", "vscodroid-codeoss-build:x86_64")
    if not shutil.which("docker"):
        raise SystemExit("ERROR: musl rebuild requires Linux x86_64 GCC or Docker; "
                         "see CONTRIBUTING.md for the build image")
    subprocess.run(["docker", "run", "--rm", "--platform", "linux/amd64",
                    "--mount", f"type=bind,source={source},target=/source",
                    "--workdir", "/source", "-e", "CC=gcc", "-e", "AR=ar",
                    "-e", "RANLIB=ranlib", "-e", f"CFLAGS={flags}",
                    "-e", f"LDFLAGS={ldflags}", image, "bash", "-c",
                    './configure --target=x86_64-linux-musl --prefix=/usr '
                    '--syslibdir=/lib --disable-static && make -j4'], check=True)
loader = source / "lib/libc.so"
subprocess.run(["python3", str(root / "scripts/verify-android-elf.py"),
                "--abi", "x86_64", str(loader)], check=True)
output.parent.mkdir(parents=True, exist_ok=True)
shutil.copy2(loader, output)
output.chmod(0o755)
record = cache.parent / "resolved-musl-source.tsv"
record.write_text(f"musl-aports\t{commit}\t{recipe_sha256}\n")
print(f"  rebuilt musl 1.2.5-r23 for 16 KB pages: {output}")
PY
