#!/usr/bin/env bash
set -euo pipefail

# Download pre-compiled Python 3 + pip from Termux APT repo.
# Places interpreter in jniLibs/arm64-v8a/ (.so trick), stdlib + pip in
# assets/usr/lib/python3.12/, and new shared libs in assets/usr/lib/.
#
# Dependencies already provided by download-termux-tools.sh are NOT re-downloaded:
#   libandroid-support, libexpat, ncurses, openssl, readline, zlib
#
# Compatible with bash 3.2+ (macOS default).

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"
ASSETS_DIR="$ROOT_DIR/android/app/src/main/assets"
JNILIBS_DIR="$ROOT_DIR/android/app/src/main/jniLibs/arm64-v8a"
WORK_DIR="$ROOT_DIR/toolchains/termux-packages"

# The index fetch, its signature check, package resolution and the digest check
# on each .deb, shared with every other script that takes packages from Termux.
# It also picks the mirror; TERMUX_MIRROR still overrides it.
. "$SCRIPT_DIR/lib/termux-packages.sh"

# Auto-detect Python major.minor from Termux repo (e.g. "3.13" from "3.13.13-1")
# The index is fetched here, before the banner, because the version it names
# decides the paths the rest of this script reads and writes.
termux_fetch_index
PYTHON_FULL_VER="$(termux_pkg_version python)"
PYTHON_MAJOR_MINOR=$(echo "$PYTHON_FULL_VER" | grep -oE '^[0-9]+\.[0-9]+')

# Packages to download (only Python-specific, not shared deps already in termux-tools)
REQUIRED_PACKAGES=(
    python
    python-pip
    libffi
    libbz2
    liblzma
    libsqlite
    libcrypt
    libandroid-posix-semaphore
    gdbm
    # _zstd is new in Python 3.14. libpanelw lives in ncurses-ui-libs, not in
    # ncurses -- that package carries only ncursesw/tinfo/tic, which is why the
    # panel layer was the missing half while curses itself worked.
    zstd
    ncurses-ui-libs
    # Not a component that ships: it carries usr/share/LICENSES, the shared
    # licence texts gdbm, liblzma and zstd point their copyright symlink at.
    # See termux_copy_notices in scripts/lib/termux-packages.sh.
    termux-licenses
)

# Soname mapping for new shared libraries
# The name each library is placed under is the name its consumer records in
# DT_NEEDED, not the tidy unversioned one. Termux ships both, and copying the
# unversioned symlink with cp -L produced a real file under a name nothing links
# against: libbz2.so was present and _bz2 still died at dlopen looking for
# libbz2.so.1.0. Every entry here was read out of the extension module that
# needs it -- change one only against fresh evidence from the same source.
get_sonames() {
    case "$1" in
        libffi)                      echo "libffi.so" ;;
        # _bz2 and _lzma link the versioned soname; nothing in the tree links
        # the unversioned form, so placing that was pure dead weight.
        libbz2)                      echo "libbz2.so.1.0" ;;
        liblzma)                     echo "liblzma.so.5" ;;
        libsqlite)                   echo "libsqlite3.so" ;;
        libcrypt)                    echo "libcrypt.so" ;;
        libandroid-posix-semaphore)  echo "libandroid-posix-semaphore.so" ;;
        # _gdbm links both: libgdbm.so for the store and libgdbm_compat.so for
        # the ndbm interface dbm.gnu exposes. Only the first was bundled.
        gdbm)                        echo "libgdbm.so libgdbm_compat.so" ;;
        # _zstd is new in Python 3.14 and was never bundled at all.
        zstd)                        echo "libzstd.so.1" ;;
        # _curses_panel needs libpanelw; libncursesw beneath it already arrives
        # with the terminal tools, which is why only the panel layer was missing.
        ncurses-ui-libs)             echo "libpanelw.so.6" ;;
        *)                           echo "" ;;
    esac
}

# Packages that have shared libraries to extract
LIB_PACKAGES=(
    libffi libbz2 liblzma libsqlite libcrypt
    libandroid-posix-semaphore gdbm zstd ncurses-ui-libs
)

echo "=== Downloading Python ${PYTHON_MAJOR_MINOR} + pip ==="
echo ""

mkdir -p "$WORK_DIR"
cd "$WORK_DIR"

# --- Step 1: what the index resolves to (it was fetched above, for the version) ---
echo "Using Packages index: $(du -sh Packages | cut -f1) (Python ${PYTHON_MAJOR_MINOR} detected)"
termux_resolve_packages resolved-python.tsv "${REQUIRED_PACKAGES[@]}"

# --- Step 2: Download .deb files, each checked against the signed index ---
termux_download_packages "${REQUIRED_PACKAGES[@]}"

# --- Step 3: Extract all .deb packages ---
termux_extract_packages "${REQUIRED_PACKAGES[@]}"

# --- Step 4: Place Python interpreter in jniLibs ---
echo ""
echo "Placing Python interpreter in jniLibs..."
mkdir -p "$JNILIBS_DIR"

PYTHON_BIN="extracted/python/data/data/com.termux/files/usr/bin/python${PYTHON_MAJOR_MINOR}"
if [ -f "$PYTHON_BIN" ]; then
    cp "$PYTHON_BIN" "$JNILIBS_DIR/libpython.so"
    echo "  libpython.so ($(du -sh "$JNILIBS_DIR/libpython.so" | cut -f1))"
else
    echo "  ERROR: Python binary not found at $PYTHON_BIN"
    echo "  Looking for python..."
    find "extracted/python" -name "python*" -type f 2>/dev/null || true
    exit 1
fi

# --- Step 5: Place stdlib in assets ---
echo ""
echo "Placing Python stdlib..."
STDLIB_SRC="extracted/python/data/data/com.termux/files/usr/lib/python${PYTHON_MAJOR_MINOR}"
STDLIB_DST="$ASSETS_DIR/usr/lib/python${PYTHON_MAJOR_MINOR}"

if [ -d "$STDLIB_SRC" ]; then
    # Placement overwrites this version and nothing else, so a tree built after
    # a Python bump carried both -- 23 MB each, and only one of them usable by
    # the interpreter that ships beside it. The app learned to clear these on
    # device (#18); this is the same sweep on the build side, so the APK never
    # carries the dead one in the first place.
    for stale in "$ASSETS_DIR"/usr/lib/python3.*; do
        [ -d "$stale" ] || continue
        [ "$stale" = "$STDLIB_DST" ] && continue
        echo "  Removing superseded stdlib $(basename "$stale") ($(du -sh "$stale" | cut -f1))"
        rm -rf "$stale"
    done
    for stale in "$ASSETS_DIR"/usr/lib/libpython3.*.so; do
        [ -f "$stale" ] || continue
        [ "$(basename "$stale")" = "libpython${PYTHON_MAJOR_MINOR}.so" ] && continue
        echo "  Removing superseded runtime $(basename "$stale")"
        rm -f "$stale"
    done

    rm -rf "$STDLIB_DST"
    cp -r "$STDLIB_SRC" "$STDLIB_DST"

    # Strip test directories and bytecode to save space
    echo "  Stripping test directories and bytecode..."
    BEFORE_SIZE=$(du -sk "$STDLIB_DST" | cut -f1)
    find "$STDLIB_DST" -type d \( -name "test" -o -name "tests" -o -name "__pycache__" \) -exec rm -rf {} + 2>/dev/null || true
    find "$STDLIB_DST" -name "*.pyc" -delete 2>/dev/null || true
    # Also strip idle and turtle (GUI libs useless on Android)
    rm -rf "$STDLIB_DST/idlelib" "$STDLIB_DST/turtle.py" "$STDLIB_DST/turtledemo" 2>/dev/null || true
    AFTER_SIZE=$(du -sk "$STDLIB_DST" | cut -f1)

    echo "  Stdlib: ${BEFORE_SIZE}K -> ${AFTER_SIZE}K (saved $((BEFORE_SIZE - AFTER_SIZE))K)"
else
    echo "  ERROR: Python stdlib not found at $STDLIB_SRC"
    exit 1
fi

# --- Step 5b: Point the compiled-in default shell at one this app can run ---
#
# Termux builds Python for its own prefix, so subprocess.py names
# /data/data/com.termux/files/usr/bin/sh as the shell _execute_child hands to
# execve for shell=True. That directory belongs to another application and this
# one cannot reach it, so every subprocess.run(cmd, shell=True) from the bundled
# interpreter raises FileNotFoundError on a path the caller never chose, as does
# every library that reaches for a shell underneath. $SHELL does not rescue it:
# the constant becomes argv[0] directly.
#
# Fails closed, like every other call to this script. If Termux changes where
# that default comes from and the line stops matching, the build stops here
# rather than shipping an interpreter whose shell nobody has established.
#
# One named file rather than a sweep of the stdlib, which is deliberate. Three
# other files under it carry the same path in a `#!` line (config-*/makesetup,
# config-*/install-sh, ctypes/macholib/fetch_macholib), none of them an
# importable module, and SELinux refuses execve on any script under filesDir, so
# those shebangs are dead text. Sweeping the directory would fail a build that
# is correct.
echo ""
echo "Pointing the stdlib's default shell at /system/bin/sh..."
python3 "$SCRIPT_DIR/patch-default-shell.py" "$STDLIB_DST/subprocess.py"

# --- Step 6: Place pip ---
echo ""
echo "Placing pip..."
PIP_SRC="extracted/python-pip/data/data/com.termux/files/usr/lib/python${PYTHON_MAJOR_MINOR}/site-packages"
PIP_DST="$STDLIB_DST/site-packages"

# Fatal, not a warning. Python without pip is not a degraded Python for the
# people this project is aimed at -- installing a package is the first thing a
# beginner does, and the failure would arrive on their device, not here. The
# `|| true` on the copies hid the same failure one level deeper.
if [ ! -d "$PIP_SRC" ]; then
    echo "  ERROR: pip site-packages not found at $PIP_SRC" >&2
    find "extracted/python-pip" -type d -name "pip" 2>/dev/null | head -3 >&2 || true
    exit 1
fi
mkdir -p "$PIP_DST"
cp -r "$PIP_SRC/pip" "$PIP_DST/"
cp -r "$PIP_SRC"/pip-*.dist-info "$PIP_DST/"
find "$PIP_DST" -type d -name "__pycache__" -exec rm -rf {} + 2>/dev/null || true
if [ ! -f "$PIP_DST/pip/__main__.py" ]; then
    echo "  ERROR: pip copied but $PIP_DST/pip/__main__.py is missing" >&2
    exit 1
fi
echo "  pip installed to site-packages ($(du -sh "$PIP_DST/pip" | cut -f1))"

# --- Step 7: Place shared libraries in assets/usr/lib/ ---
echo ""
echo "Placing shared libraries in assets/usr/lib/..."
mkdir -p "$ASSETS_DIR/usr/lib"

for pkg in "${LIB_PACKAGES[@]}"; do
    sonames="$(get_sonames "$pkg")"
    [ -z "$sonames" ] && continue
    pkg_lib_dir="extracted/$pkg/data/data/com.termux/files/usr/lib"
    for soname in $sonames; do
        src=""
        if [ -f "$pkg_lib_dir/$soname" ] || [ -L "$pkg_lib_dir/$soname" ]; then
            src="$pkg_lib_dir/$soname"
        else
            # Try unversioned name
            base_soname="$(echo "$soname" | sed 's/\.so\..*/\.so/')"
            if [ "$base_soname" != "$soname" ] && ( [ -f "$pkg_lib_dir/$base_soname" ] || [ -L "$pkg_lib_dir/$base_soname" ] ); then
                src="$pkg_lib_dir/$base_soname"
            fi
        fi

        if [ -n "$src" ] && ( [ -f "$src" ] || [ -L "$src" ] ); then
            cp -L "$src" "$ASSETS_DIR/usr/lib/$soname"
            echo "  $soname ($(du -sh "$ASSETS_DIR/usr/lib/$soname" | cut -f1))"
        else
            # Fatal, not a warning -- the same call download-termux-tools.sh
            # makes at the same point, and for the same reason. A
            # library missing here ships an APK where an extension module dies
            # at dlopen on a user's device: no _ctypes without libffi, no
            # sqlite3, no bz2. The import error names the module, never the
            # library the build quietly skipped.
            echo "  ERROR: $soname not found in $pkg (looked in $pkg_lib_dir)" >&2
            [ -d "$pkg_lib_dir" ] && ls "$pkg_lib_dir"/*.so* 2>/dev/null | head -5 >&2 || true
            exit 1
        fi
    done
done

# Also need the libpython runtime the launcher links against. readelf -d on the
# placed libpython.so needs the unversioned name (libpython3.x.so), not the
# versioned .so.1.0 -- so the runtime must land under that unversioned name
# whichever file the package ships. Termux currently ships only the unversioned
# file; copying the versioned one to the unversioned name covers a package that
# ever flips to versioned-only. A launcher with no runtime is a broken build, so
# fail here rather than emit a note and ship an APK where python cannot start.
LIBPYTHON_SRC="extracted/python/data/data/com.termux/files/usr/lib"
LIBPYTHON_VERSIONED="libpython${PYTHON_MAJOR_MINOR}.so.1.0"
LIBPYTHON_UNVERSIONED="libpython${PYTHON_MAJOR_MINOR}.so"
LIBPYTHON_DST="$ASSETS_DIR/usr/lib/$LIBPYTHON_UNVERSIONED"
if [ -e "$LIBPYTHON_SRC/$LIBPYTHON_UNVERSIONED" ]; then
    cp -L "$LIBPYTHON_SRC/$LIBPYTHON_UNVERSIONED" "$LIBPYTHON_DST"
elif [ -e "$LIBPYTHON_SRC/$LIBPYTHON_VERSIONED" ]; then
    cp -L "$LIBPYTHON_SRC/$LIBPYTHON_VERSIONED" "$LIBPYTHON_DST"
else
    echo "  ERROR: no libpython runtime ($LIBPYTHON_UNVERSIONED or $LIBPYTHON_VERSIONED) in $LIBPYTHON_SRC" >&2
    exit 1
fi
echo "  $LIBPYTHON_UNVERSIONED ($(du -sh "$LIBPYTHON_DST" | cut -f1))"

# --- Step 7b: Place the upstream notices beside what they describe ---
#
# No wipe here. download-termux-tools.sh clears the whole share/doc directory
# and runs first everywhere (build-all.sh, build.yml, release.yml), so clearing
# it again would delete the notices for every package that is not in this
# script's list.
termux_copy_notices "$ASSETS_DIR/usr" "${REQUIRED_PACKAGES[@]}"

# --- Step 8: Size summary ---
echo ""
echo "=== Python Size Summary ==="

echo "Interpreter:"
echo "  libpython.so: $(du -sh "$JNILIBS_DIR/libpython.so" | cut -f1)"

echo ""
echo "Stdlib (assets/usr/lib/python${PYTHON_MAJOR_MINOR}/):"
echo "  $(du -sh "$STDLIB_DST" | cut -f1) total"

echo ""
echo "New shared libraries:"
for pkg in "${LIB_PACKAGES[@]}"; do
    sonames="$(get_sonames "$pkg")"
    for soname in $sonames; do
        [ -f "$ASSETS_DIR/usr/lib/$soname" ] && echo "  $soname: $(du -sh "$ASSETS_DIR/usr/lib/$soname" | cut -f1)"
    done
done
[ -f "$ASSETS_DIR/usr/lib/$LIBPYTHON_UNVERSIONED" ] && echo "  $LIBPYTHON_UNVERSIONED: $(du -sh "$ASSETS_DIR/usr/lib/$LIBPYTHON_UNVERSIONED" | cut -f1)"

echo ""
echo "=== Verify ==="
# The launcher's DT_NEEDED names its runtime and every other lib it loads; this
# fails the build if one is missing, the wrong arch, or not 16 KB-aligned -- the
# check the old silent NOTE skipped. It also proves the runtime landed under the
# name the launcher links: a mismatch shows up here as an unresolved DT_NEEDED,
# not as a broken python on someone's device.
python3 "$SCRIPT_DIR/verify-android-elf.py" "$JNILIBS_DIR/libpython.so" \
    --lib-dir "$ASSETS_DIR/usr/lib" \
    --lib-dir "$JNILIBS_DIR"

# The launcher is one object out of roughly eighty. Everything the interpreter
# loads after it starts -- the runtime, the shared libraries placed above, and
# the extension modules in lib-dynload -- was unchecked, and those are where a
# missing dependency actually lands: _ctypes needs libffi, _sqlite3 needs
# libsqlite3, _bz2 needs libbz2. An upstream rename would surface as an
# ImportError naming the module on a user's device, never the library.
#
# No separate SONAME comparison. Resolving DT_NEEDED for every placed object
# tests the same disagreement at its consequence, and catches architecture and
# page alignment besides -- a name that nothing links against cannot break a
# load, and a name that something does link against fails here as unresolved.
echo ""
echo "Verifying every ELF this script placed..."
verified=0
while IFS= read -r obj; do
    # Output is captured rather than discarded, and replayed on failure. Sending
    # it to /dev/null still failed the build -- set -e sees the exit status --
    # but printed nothing about which object or which library, which is the same
    # silence this whole change exists to remove.
    if ! out=$(python3 "$SCRIPT_DIR/verify-android-elf.py" "$obj" \
        --lib-dir "$ASSETS_DIR/usr/lib" \
        --lib-dir "$JNILIBS_DIR" 2>&1); then
        echo "  ERROR: ${obj#"$ASSETS_DIR/"}" >&2
        echo "$out" | sed 's/^/    /' >&2
        exit 1
    fi
    verified=$((verified + 1))
done < <(
    find "$ASSETS_DIR/usr/lib" -maxdepth 1 -name "libpython${PYTHON_MAJOR_MINOR}.so" -type f
    find "$STDLIB_DST" -name '*.so' -type f 2>/dev/null
    for pkg in "${LIB_PACKAGES[@]}"; do
        for soname in $(get_sonames "$pkg"); do
            [ -f "$ASSETS_DIR/usr/lib/$soname" ] && echo "$ASSETS_DIR/usr/lib/$soname"
        done
    done
)
echo "  $verified objects verified (arch, dependencies, 16 KB alignment)"

echo ""
echo "=== Python download complete ==="
echo "Next: cd android && ./gradlew assembleDebug"
