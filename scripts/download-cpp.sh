#!/usr/bin/env bash
set -euo pipefail

# Download clang, lld and a minimal sysroot from the Termux APT repo.
# Places files in the toolchain_cpp asset pack module for Play Asset Delivery.
#
# What the pack is: the clang driver, lld, libLLVM and libclang-cpp, the NDK
# sysroot (headers, crt objects, libunwind), the clang resource headers with the
# builtins archive, libc++'s headers, a handful of llvm-* tools, and Python's
# headers so pip can build a C extension. What it is not: clangd and the other
# clang tools, libclang, cmake, ninja, the sanitizer runtimes, OpenMP.
#
# Nothing the base APK already ships is repeated here. libc++_shared.so, libz,
# libzstd, libffi, libiconv and liblzma are in assets/usr/lib, an asset entry
# both the base module and a pack carry is a conflict bundletool refuses, and a
# toolchain installs into filesDir/usr beside the base copy. Resolution here
# expands no dependencies, so the list below is the closure minus the base.
#
# The layout is Termux's own, deliberately: clang derives its sysroot, its
# resource directory and the lld next to it from where it sits (bin/../.. and
# bin/../lib/clang), so bin, include and lib stay under one prefix. Do not move
# anything into a directory of its own.
#
# Compatible with bash 3.2+ (macOS default).

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"
PACK_ASSETS="$ROOT_DIR/android/toolchain_cpp/src/main/assets"
BASE_USR="$ROOT_DIR/android/app/src/main/assets/usr"
WORK_DIR="$ROOT_DIR/toolchains/termux-packages"

# The index fetch, its signature check, package resolution and the digest check
# on each .deb, shared with every other script that takes packages from Termux.
# It also picks the mirror; TERMUX_MIRROR still overrides it.
. "$SCRIPT_DIR/lib/termux-packages.sh"

REQUIRED_PACKAGES=(
    # The driver, libclang-cpp.so and the resource headers.
    clang
    # libLLVM.so, and LICENSE.TXT, the one notice the LLVM subpackages share.
    libllvm
    lld
    llvm
    # Only its libclang_rt.builtins archive is kept; the rest is sanitizers.
    libcompiler-rt
    ndk-sysroot
    libxml2
    # Headers only. The version is the one download-python.sh resolves from the
    # same index, so they match the interpreter in the base APK.
    python
    # Not a component that ships: it carries usr/share/LICENSES, the shared
    # licence texts ndk-sysroot's copyright symlink points at. See
    # termux_copy_notices in scripts/lib/termux-packages.sh.
    termux-licenses
)

# What termux_copy_notices is asked about: the shipped components, and not
# python. Its notice is already in the base APK at usr/share/doc/python, and a
# second copy in this pack is an overlap bundletool refuses.
NOTICE_PACKAGES=(clang libllvm lld llvm libcompiler-rt ndk-sysroot libxml2 termux-licenses)

# Soname mapping for the shared libraries this pack adds. The name each one is
# placed under is the name its consumer records in DT_NEEDED: libLLVM.so asks for
# libxml2.so.16, so a copy of the unversioned link would be a file nothing opens.
# check-termux-licenses.py reads this function too.
get_sonames() {
    case "$1" in
        libllvm)      echo "libLLVM.so" ;;
        clang)        echo "libclang-cpp.so" ;;
        libxml2)      echo "libxml2.so.16" ;;
        ndk-sysroot)  echo "libpthread.so librt.so libutil.so" ;;
        *)            echo "" ;;
    esac
}

LIB_PACKAGES=(libllvm clang libxml2 ndk-sysroot)

# The llvm tools worth carrying, by the names a build system asks for. Each is a
# real file or a link inside the package, and cp -L makes it a file either way.
LLVM_TOOLS=(llvm-ar llvm-ranlib llvm-nm llvm-objdump llvm-objcopy llvm-strip llvm-readelf llvm-size)

is_elf() {
    [ "$(dd if="$1" bs=4 count=1 2>/dev/null | od -An -tx1 | tr -d ' \n')" = "7f454c46" ]
}

# e_type 1, ET_REL: an object file, which the loader never maps. The crt files
# are the five of them here, and the 16 KB question belongs to what the linker
# makes out of them (it passes max-page-size itself), not to them.
is_relocatable() {
    [ "$(dd if="$1" bs=1 skip=16 count=2 2>/dev/null | od -An -tx1 | tr -d ' \n')" = "0100" ]
}

echo "=== Downloading C/C++ (clang) Toolchain ==="
echo ""

mkdir -p "$WORK_DIR"
cd "$WORK_DIR"

# --- Step 1: the package index, its signature, and what it resolves to ---
termux_fetch_index
termux_resolve_packages resolved-cpp.tsv "${REQUIRED_PACKAGES[@]}"
CLANG_VERSION="$(termux_pkg_version clang)"
LLVM_MAJOR="$(echo "$CLANG_VERSION" | grep -oE '^[0-9]+')"
PYTHON_MAJOR_MINOR="$(termux_pkg_version python | grep -oE '^[0-9]+\.[0-9]+')"
echo "  clang version: $CLANG_VERSION (resource directory lib/clang/$LLVM_MAJOR)"
echo "  python headers: $PYTHON_MAJOR_MINOR"

# The headers are only useful against the interpreter they were built for. When
# the base tree is here, hold the two to each other: a pack carrying 3.14
# headers beside a 3.15 interpreter installs cleanly and fails on first pip use.
BASE_LIBPYTHON="$(ls "$BASE_USR"/lib/libpython3.*.so 2>/dev/null | head -1 || true)"
if [ -n "$BASE_LIBPYTHON" ]; then
    case "$(basename "$BASE_LIBPYTHON")" in
        "libpython${PYTHON_MAJOR_MINOR}.so") ;;
        *)
            echo "  ERROR: the base APK ships $(basename "$BASE_LIBPYTHON") but the index" >&2
            echo "         resolves python $PYTHON_MAJOR_MINOR. Re-run download-python.sh first." >&2
            exit 1
            ;;
    esac
fi

# --- Step 2: Download .deb files, each checked against the signed index ---
termux_download_packages "${REQUIRED_PACKAGES[@]}"

# --- Step 3: Extract ---
termux_extract_packages "${REQUIRED_PACKAGES[@]}"

# --- Step 4: Place files in asset pack module ---
echo ""
echo "Placing C/C++ toolchain in asset pack..."
rm -rf "$PACK_ASSETS/usr"
mkdir -p "$PACK_ASSETS/usr/bin" "$PACK_ASSETS/usr/lib" "$PACK_ASSETS/usr/include"

pkg_usr() {
    echo "extracted/$1/data/data/com.termux/files/usr"
}

# Each of these is a hard failure. A missing piece does not stop the pack
# installing: it stops the first compile, on a device, with a message about a
# header or a program rather than about a pack.
require() {
    if [ ! -e "$1" ]; then
        echo "  ERROR: $1 is not in the package; Termux moved something this" >&2
        echo "         script places by name." >&2
        exit 1
    fi
}

CLANG_USR="$(pkg_usr clang)"
LLVM_USR="$(pkg_usr llvm)"
LLD_USR="$(pkg_usr lld)"
RT_USR="$(pkg_usr libcompiler-rt)"
NDK_USR="$(pkg_usr ndk-sysroot)"
PY_USR="$(pkg_usr python)"

# The driver is clang-<major>; cc, c++, gcc, g++, clang and clang++ are links to
# it in the package and are made at install time from the manifest instead.
require "$CLANG_USR/bin/clang-$LLVM_MAJOR"
cp -L "$CLANG_USR/bin/clang-$LLVM_MAJOR" "$PACK_ASSETS/usr/bin/clang-$LLVM_MAJOR"
echo "  bin/clang-$LLVM_MAJOR"

# lld is one binary that behaves as ld.lld, ld and the other flavours by the
# name it is started under; the names are manifest links as well.
require "$LLD_USR/bin/lld"
cp -L "$LLD_USR/bin/lld" "$PACK_ASSETS/usr/bin/lld"
echo "  bin/lld"

for tool in "${LLVM_TOOLS[@]}"; do
    require "$LLVM_USR/bin/$tool"
    cp -L "$LLVM_USR/bin/$tool" "$PACK_ASSETS/usr/bin/$tool"
    echo "  bin/$tool"
done

# Shared libraries, by soname.
echo ""
echo "Placing shared libraries..."
for pkg in "${LIB_PACKAGES[@]}"; do
    pkg_lib_dir="$(pkg_usr "$pkg")/lib"
    for soname in $(get_sonames "$pkg"); do
        if [ -f "$pkg_lib_dir/$soname" ] || [ -L "$pkg_lib_dir/$soname" ]; then
            cp -L "$pkg_lib_dir/$soname" "$PACK_ASSETS/usr/lib/$soname"
            echo "  $soname ($(du -sh "$PACK_ASSETS/usr/lib/$soname" | cut -f1))"
        else
            # Fatal for the reason download-ruby.sh gives: the ELF gate below
            # checks what is present, and this is about what is absent.
            echo "  ERROR: $soname not found in $pkg (looked in $pkg_lib_dir)" >&2
            ls "$pkg_lib_dir"/*.so* 2>/dev/null | head -5 >&2 || true
            exit 1
        fi
    done
done

# The rest of the sysroot's lib directory: crtbegin/crtend objects, libunwind.a
# and the small static archives the Android driver links by default. Anything it
# gains that has .so in its name reaches the attribution gate as an unlisted
# library, which is the prompt to look at it.
for src in "$NDK_USR/lib"/*; do
    [ -f "$src" ] || continue
    name="$(basename "$src")"
    [ -e "$PACK_ASSETS/usr/lib/$name" ] && continue
    cp -L "$src" "$PACK_ASSETS/usr/lib/$name"
    echo "  lib/$name"
done

# Headers: bionic's, the kernel's, and libc++'s under include/c++/v1, which is
# where the driver looks for them.
echo ""
echo "Placing headers..."
require "$NDK_USR/include/c++/v1"
cp -RL "$NDK_USR/include/." "$PACK_ASSETS/usr/include/"
echo "  include/ ($(find "$PACK_ASSETS/usr/include" -type f | wc -l | tr -d ' ') files)"

require "$PY_USR/include/python$PYTHON_MAJOR_MINOR/Python.h"
cp -RL "$PY_USR/include/python$PYTHON_MAJOR_MINOR" "$PACK_ASSETS/usr/include/python$PYTHON_MAJOR_MINOR"
echo "  include/python$PYTHON_MAJOR_MINOR"

# Clang's own headers (stddef.h, stdarg.h, the intrinsics) and the one runtime
# archive a plain link needs. The sanitizers, profile and fuzzer runtimes are
# what makes libcompiler-rt 34 MB, and nothing here selects them.
RESOURCE="usr/lib/clang/$LLVM_MAJOR"
require "$CLANG_USR/lib/clang/$LLVM_MAJOR/include/stddef.h"
mkdir -p "$PACK_ASSETS/$RESOURCE/lib/linux"
cp -RL "$CLANG_USR/lib/clang/$LLVM_MAJOR/include" "$PACK_ASSETS/$RESOURCE/include"
require "$RT_USR/lib/clang/$LLVM_MAJOR/lib/linux/libclang_rt.builtins-aarch64-android.a"
cp -L "$RT_USR/lib/clang/$LLVM_MAJOR/lib/linux/libclang_rt.builtins-aarch64-android.a" \
    "$PACK_ASSETS/$RESOURCE/lib/linux/libclang_rt.builtins-aarch64-android.a"
echo "  $RESOURCE (headers and the aarch64 builtins archive)"

# --- Step 4b: point the one compiled-in shell at /system/bin/sh ---
#
# Termux's paths.h defines _PATH_BSHELL as /data/data/com.termux/files/usr/bin/sh,
# a directory inside another application. A program compiled here that names the
# macro (shells, pagers, anything that runs a command line itself) would carry it
# and fail with ENOENT at run time, three layers from the cause. No binary in the
# pack names the path, which the sweep further down confirms for every file. Fails
# closed on a header that no longer holds exactly one such definition.
echo ""
echo "Pointing paths.h at /system/bin/sh..."
python3 "$SCRIPT_DIR/patch-default-shell.py" "$PACK_ASSETS/usr/include/paths.h"

# --- Step 5: the upstream notices, beside what they describe ---
termux_copy_notices "$PACK_ASSETS/usr" "${NOTICE_PACKAGES[@]}"

# --- Step 6: Write toolchain_cpp.json ---
echo ""
echo "Writing toolchain_cpp.json..."

# Written by a small Python program instead of by concatenating strings, since
# the list of owned paths is a hundred and fifty entries.
#
# Every list is read off the tree rather than kept here, and the last check in
# the program is what makes that safe: a file the manifest does not account for
# is a file an uninstall would leave on every device, so the build stops.
#
#   binaries    every file in usr/bin (chmod +x at install, deleted at uninstall)
#   libs        the loose .so files in usr/lib, which the attribution gate reads
#   ownedPaths  what the pack puts into directories it shares with the base
#               install: usr/include/*, usr/lib/clang, the crt objects and
#               archives, usr/share/doc/*. Entry by entry rather than whole
#               directories, so a header a user installed beside them survives.
#   symlinks    the command names, made in usr/bin at install time because a
#               pack cannot carry a link
#
# preloadOnly: no bash function and no trampoline row is made for these
# binaries. Both of those start a program as `/system/bin/linker64 <file>`
# directly, and the loader then reports itself as the program's own path, which
# clang and libLLVM use to find the resource directory and the sysroot. They
# start through the preloaded exec instead, which names the file in
# TERMUX_EXEC__PROC_SELF_EXE first.
python3 -I - "$PACK_ASSETS" "$CLANG_VERSION" "$LLVM_MAJOR" <<'PYEOF'
import json, os, sys

pack, version, major = sys.argv[1], sys.argv[2], sys.argv[3]
usr = os.path.join(pack, "usr")


def files(sub):
    root = os.path.join(usr, sub)
    return sorted(
        f for f in os.listdir(root) if os.path.isfile(os.path.join(root, f))
    )


binaries = ["usr/bin/" + f for f in files("bin")]
libs = [f for f in files("lib") if ".so" in f]
owned = ["usr/lib/clang"]
owned += ["usr/lib/" + f for f in files("lib") if f not in libs]
for sub in ("usr/include", "usr/share/doc"):
    if os.path.isdir(os.path.join(pack, sub)):
        owned += [sub + "/" + e for e in sorted(os.listdir(os.path.join(pack, sub)))]

driver = "usr/bin/clang-" + major
symlinks = {}
for name in ("clang", "clang++", "cc", "c++", "gcc", "g++",
             "aarch64-linux-android-clang", "aarch64-linux-android-clang++"):
    symlinks[name] = driver
for name in ("ld", "ld.lld"):
    symlinks[name] = "usr/bin/lld"
for name, target in (("ar", "llvm-ar"), ("ranlib", "llvm-ranlib"), ("nm", "llvm-nm"),
                     ("strip", "llvm-strip"), ("objdump", "llvm-objdump"),
                     ("objcopy", "llvm-objcopy"), ("readelf", "llvm-readelf"),
                     ("size", "llvm-size")):
    symlinks[name] = "usr/bin/" + target

problems = []
for name, target in sorted(symlinks.items()):
    if target not in binaries:
        problems.append("link %s points at %s, which the pack does not ship" % (name, target))
    if name in files("bin"):
        problems.append("link %s would shadow the file of the same name in usr/bin" % name)

# Everything the pack ships has to be named by one of the lists above.
covered = set(binaries) | {"usr/lib/" + f for f in libs}
claims = tuple(owned)
for dirpath, _dirs, names in os.walk(usr):
    for n in names:
        rel = os.path.relpath(os.path.join(dirpath, n), pack).replace(os.sep, "/")
        if rel in covered:
            continue
        if any(rel == c or rel.startswith(c + "/") for c in claims):
            continue
        problems.append("nothing in the manifest would remove " + rel)
if problems:
    for p in problems[:20]:
        print("  ERROR: " + p, file=sys.stderr)
    sys.exit(1)

manifest = {
    "name": "cpp",
    "displayName": "C/C++",
    "version": version,
    "preloadOnly": True,
    "binaries": binaries,
    "symlinks": symlinks,
    # Empty on purpose, and not decoration: the env file and the server
    # environment read pathDirs only for a toolchain that has an env object.
    "env": {},
    "pathDirs": ["usr/bin"],
    "libs": libs,
    "ownedPaths": owned,
}
with open(os.path.join(pack, "toolchain_cpp.json"), "w") as f:
    json.dump(manifest, f, indent=4)
    f.write("\n")
print("  toolchain_cpp.json written: %d binaries, %d libs, %d owned paths, %d command names"
      % (len(binaries), len(libs), len(owned), len(symlinks)))
PYEOF

# --- Step 7: Verify every binary the pack ships ---
echo ""
echo "=== Verifying C/C++ binaries ==="
# The whole pack is swept, for the reason download-ruby.sh gives, with the base
# APK's usr/lib offered as a place a dependency may live: libLLVM needs libffi,
# libz, libzstd and libc++_shared, which the base ships and this pack does not.
verify_failures=0
verify_checked=0

verify_object() {
    local out
    verify_checked=$((verify_checked + 1))
    if ! out=$(python3 "$SCRIPT_DIR/verify-android-elf.py" "$1" \
                   --lib-dir "$PACK_ASSETS/usr/lib" \
                   --lib-dir "$BASE_USR/lib" 2>&1); then
        echo "  FAILED  ${1#$PACK_ASSETS/}" >&2
        echo "$out" | grep -v '^  ok' | sed 's/^/     /' >&2 || true
        verify_failures=$((verify_failures + 1))
    fi
}

skipped_objects=0
while IFS= read -r obj; do
    is_elf "$obj" || continue
    if is_relocatable "$obj"; then
        skipped_objects=$((skipped_objects + 1))
        continue
    fi
    verify_object "$obj"
done < <(find "$PACK_ASSETS/usr" -type f)

if [ "$verify_checked" -eq 0 ]; then
    echo "  ERROR: no ELF object was found in the pack, so nothing was verified." >&2
    exit 1
fi
if [ "$verify_failures" -gt 0 ]; then
    echo "" >&2
    echo "  ERROR: $verify_failures of $verify_checked C/C++ binaries would fail to" >&2
    echo "         load on a device. Shipping them produces a working build and a" >&2
    echo "         broken toolchain." >&2
    exit 1
fi
echo "  $verify_checked binaries verified: architecture, dependencies, 16 KB alignment"
echo "  $skipped_objects relocatable objects (crt files) are linker input and were not loaded-checked"

# The base module and a pack may not carry the same asset path. check-pack-overlap
# answers this for the whole bundle once every tree is down; asking it here
# names this pack's file at the moment it is placed.
echo ""
echo "=== Verifying nothing here is also in the base APK ==="
overlap=0
if [ -d "$BASE_USR" ]; then
    while IFS= read -r rel; do
        if [ -e "$BASE_USR/${rel#usr/}" ]; then
            echo "  ERROR: $rel is also shipped by the base APK" >&2
            overlap=$((overlap + 1))
        fi
    done < <(cd "$PACK_ASSETS" && find usr -type f)
    if [ "$overlap" -gt 0 ]; then
        exit 1
    fi
    echo "  none"
else
    echo "  skipped: $BASE_USR is absent, so the base tree could not be compared"
fi

echo ""
echo "=== Verifying no file in the pack names a shell it cannot reach ==="
python3 "$SCRIPT_DIR/patch-default-shell.py" --check "$PACK_ASSETS"

# Neither delivery path can carry a symbolic link, and both loops above are
# driven by `find -type f`, which steps over one without a word.
echo ""
echo "=== Verifying the pack carries no symbolic link ==="
link_count=$(find "$PACK_ASSETS" -type l | wc -l | tr -d ' ')
if [ "$link_count" -ne 0 ]; then
    echo "  ERROR: $link_count symbolic link(s) in the pack:" >&2
    find "$PACK_ASSETS" -type l | sed "s|^$PACK_ASSETS/|     |" >&2
    echo "         Copy with 'cp -RL' so the link's target travels instead." >&2
    exit 1
fi
echo "  none"

# --- Step 8: Size summary ---
echo ""
echo "=== C/C++ Toolchain Size Summary ==="
echo "  Asset pack: $(du -sh "$PACK_ASSETS" | cut -f1) total"
echo "  files: $(find "$PACK_ASSETS/usr" -type f | wc -l | tr -d ' ')"
echo "  du -sk usr/: $(du -sk "$PACK_ASSETS/usr" | cut -f1) KiB"
echo ""
echo "Shared libraries:"
for f in "$PACK_ASSETS/usr/lib"/*.so*; do
    [ -f "$f" ] && echo "  $(basename "$f"): $(du -sh "$f" | cut -f1)"
done
echo "  include/: $(du -sh "$PACK_ASSETS/usr/include" | cut -f1)"
echo "  lib/clang/: $(du -sh "$PACK_ASSETS/usr/lib/clang" | cut -f1)"

echo ""
echo "=== C/C++ download complete ==="
echo "Next: cd android && ./gradlew bundleDebug"
