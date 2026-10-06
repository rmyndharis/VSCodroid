#!/usr/bin/env python3
"""Give packaged ELF files the 16 KB LOAD alignment Android 16 requires.

    align-android-pages.py [--abi arm64-v8a|x86_64] <tree>

Upstream linux-x64 prebuilds often record 4 KB alignment. A file whose
segments already sit on a 16 KB modulus only needs p_align raised. A data
segment laid out one 4 KB page away from its virtual address is moved, and
everything after it moves with it, so the file offsets stay congruent with
the addresses the loader was given. Virtual addresses do not change.

Relocatable objects are skipped. They are never mapped. An ELF for another
machine is skipped. A tree that is already aligned is left byte for byte.
"""

from __future__ import annotations

import argparse
import os
import struct
import sys
from pathlib import Path

PAGE = 0x4000
PT_LOAD = 1
ET_REL = 1
ELF_MAGIC = b"\x7fELF"
ABI_MACHINE = {"arm64-v8a": 0xB7, "x86_64": 0x3E}


class AlignError(Exception):
    pass


def _u16(data: bytes, off: int) -> int:
    return struct.unpack_from("<H", data, off)[0]


def _u64(data: bytes, off: int) -> int:
    return struct.unpack_from("<Q", data, off)[0]


def _coverage(pieces: list[tuple[int, int, int]], size: int) -> None:
    end = 0
    for start, stop, _new in pieces:
        if start != end or stop < start:
            raise AlignError(f"layout does not cover the file ({start:#x} after {end:#x})")
        end = stop
    if end != size:
        raise AlignError(f"layout ended at {end:#x}, file is {size:#x}")


def _map(pieces: list[tuple[int, int, int]], size: int, old: int) -> int:
    if old == size:
        start, stop, new = pieces[-1]
        return new + (size - start)
    for start, stop, new in pieces:
        if start <= old < stop:
            return new + (old - start)
    raise AlignError(f"file offset {old:#x} is not in the rebuilt layout")


def align_image(data: bytes, machine: int) -> bytes | None:
    """Return a 16 KB-aligned image, or None when no change is required."""
    if len(data) < 64 or data[:4] != ELF_MAGIC or data[4] != 2 or data[5] != 1:
        return None
    if _u16(data, 18) != machine or _u16(data, 16) == ET_REL:
        return None

    e_phoff = _u64(data, 32)
    e_shoff = _u64(data, 40)
    e_phentsize = _u16(data, 54)
    e_phnum = _u16(data, 56)
    e_shentsize = _u16(data, 58)
    e_shnum = _u16(data, 60)
    if e_phentsize < 56 or e_phoff + e_phnum * e_phentsize > len(data):
        raise AlignError("program headers do not fit")
    if e_shnum and (e_shentsize < 64 or e_shoff + e_shnum * e_shentsize > len(data)):
        raise AlignError("section headers do not fit")

    ordered: list[tuple[int, int, int, int]] = []
    for i in range(e_phnum):
        off = e_phoff + i * e_phentsize
        p_type = struct.unpack_from("<I", data, off)[0]
        p_offset, p_vaddr, _paddr, p_filesz, _memsz, p_align = struct.unpack_from(
            "<QQQQQQ", data, off + 8)
        if p_type == PT_LOAD and p_filesz:
            ordered.append((p_offset, p_vaddr, p_filesz, p_align))
    ordered.sort()
    for (off, _va, filesz, _align), (nxt, *_rest) in zip(ordered, ordered[1:]):
        if off + filesz > nxt:
            raise AlignError("overlapping LOAD segments")

    if all(align >= PAGE and off % PAGE == va % PAGE for off, va, _sz, align in ordered):
        return None

    pieces: list[tuple[int, int, int]] = []
    cursor_old = 0
    cursor_new = 0
    for off, va, filesz, _align in ordered:
        if off < cursor_old:
            raise AlignError("LOAD segments are not in file order")
        if off > cursor_old:
            pieces.append((cursor_old, off, cursor_new))
            cursor_new += off - cursor_old
            cursor_old = off
        cursor_new += (va - cursor_new) % PAGE
        pieces.append((off, off + filesz, cursor_new))
        cursor_new += filesz
        cursor_old = off + filesz
    if cursor_old < len(data):
        pieces.append((cursor_old, len(data), cursor_new))
    _coverage(pieces, len(data))

    out = bytearray()
    for start, stop, new in pieces:
        if len(out) > new:
            raise AlignError("rebuilt ranges overlap")
        out.extend(b"\x00" * (new - len(out)))
        out.extend(data[start:stop])

    def mapped(old: int) -> int:
        return _map(pieces, len(data), old)

    struct.pack_into("<Q", out, 32, mapped(e_phoff))
    struct.pack_into("<Q", out, 40, mapped(e_shoff))
    new_phoff = mapped(e_phoff)
    for i in range(e_phnum):
        src = e_phoff + i * e_phentsize
        dst = new_phoff + i * e_phentsize
        p_type = struct.unpack_from("<I", data, src)[0]
        p_offset, _va, _pa, p_filesz, _ms, p_align = struct.unpack_from("<QQQQQQ", data, src + 8)
        struct.pack_into("<Q", out, dst + 8, mapped(p_offset))
        if p_type == PT_LOAD:
            struct.pack_into("<Q", out, dst + 48, max(p_align, PAGE))
    new_shoff = mapped(e_shoff)
    for i in range(e_shnum):
        src = e_shoff + i * e_shentsize
        dst = new_shoff + i * e_shentsize
        sh_offset = _u64(data, src + 24)
        struct.pack_into("<Q", out, dst + 24, mapped(sh_offset))
    return bytes(out)


def align_tree(root: Path, abi: str) -> int:
    machine = ABI_MACHINE[abi]
    changed = 0
    for dirpath, dirnames, names in os.walk(root):
        dirnames[:] = [name for name in dirnames if not Path(dirpath, name).is_symlink()]
        for name in names:
            path = Path(dirpath, name)
            if path.is_symlink() or not path.is_file():
                continue
            try:
                data = path.read_bytes()
            except OSError as exc:
                print(f"FAIL   cannot read {path}: {exc}", file=sys.stderr)
                return -1
            try:
                updated = align_image(data, machine)
            except AlignError as exc:
                print(f"FAIL   {path}: {exc}", file=sys.stderr)
                return -1
            if updated is None:
                continue
            path.write_bytes(updated)
            changed += 1
            print(f"  aligned {path.relative_to(root)}")
    print(f"  {changed} {abi} ELF file(s) realigned to 16 KB")
    return changed


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--abi", choices=sorted(ABI_MACHINE),
                        default=os.environ.get("VSCODROID_ABI", "arm64-v8a"))
    parser.add_argument("tree", type=Path)
    args = parser.parse_args()
    if not args.tree.is_dir():
        print(f"FAIL   not a directory: {args.tree}", file=sys.stderr)
        return 1
    if align_tree(args.tree, args.abi) < 0:
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
