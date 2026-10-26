#!/usr/bin/env python3
"""Inline the pinned language model's CPU backend for browser workers.

    patch-language-detection.py [--check] <package-directory>...

Version 1.0.23 loads its CPU backend with a CommonJS require of 979.js, even
when imported through AMD in a browser worker. Keep that chunk and its license
inside the original loader expression so inference needs no require shim.
The hashes identify the packaged files after upstream removes source map URLs.
Unknown package bytes stop the build before anything is rewritten.
"""

import argparse
import hashlib
import json
from pathlib import Path
import sys

INDEX_SHA256 = "47295200bade2503c770a48e88ad80d0c9d2ac99474ce08653b5b28de0a2df14"
CHUNK_SHA256 = "d2e489d4d13d8760e4ca4b136ab1410a7ed7819d79079e1ba981cca7753e336d"
LOADER = b'require("./"+r.u(e))'


def patch_package(package: Path, check_only: bool = False) -> None:
    metadata = json.loads((package / "package.json").read_text(encoding="utf-8"))
    if (metadata.get("name"), metadata.get("version")) != ("@vscode/vscode-languagedetection", "1.0.23"):
        raise ValueError("expected @vscode/vscode-languagedetection 1.0.23")
    chunk = (package / "dist/lib/979.js").read_bytes()
    if hashlib.sha256(chunk).hexdigest() != CHUNK_SHA256:
        raise ValueError("unsupported dist/lib/979.js; check the CPU backend before updating its hash")
    target = package / "dist/lib/index.js"
    source = target.read_bytes()
    inline = b"(()=>{const exports={};\n" + chunk + b"\nreturn exports;})()"
    already_patched = source.count(inline) == 1
    original = source.replace(inline, LOADER, 1) if already_patched else source
    if hashlib.sha256(original).hexdigest() != INDEX_SHA256 or original.count(LOADER) != 1:
        raise ValueError("unsupported dist/lib/index.js; check its chunk loader before updating its hash")
    if check_only and not already_patched:
        raise ValueError("language model CPU backend is not inlined")
    if not already_patched:
        target.write_bytes(source.replace(LOADER, inline, 1))
    print(f"  ok      language model CPU backend is inlined: {package}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--check", action="store_true")
    parser.add_argument("packages", type=Path, nargs="+")
    args = parser.parse_args()
    try:
        for package in args.packages:
            patch_package(package, args.check)
    except (OSError, ValueError) as error:
        print(f"FAIL   {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
