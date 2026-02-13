#!/usr/bin/env bash
set -euo pipefail

# Download pre-compiled bash + git (with dependencies) from Termux APT repo.
# Places executables in jniLibs/arm64-v8a/ (.so trick) and shared libraries
# in assets/usr/lib/ for first-run extraction.
#
# Compatible with bash 3.2+ (macOS default).

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"
ASSETS_DIR="$ROOT_DIR/android/app/src/main/assets"
JNILIBS_DIR="$ROOT_DIR/android/app/src/main/jniLibs/arm64-v8a"
WORK_DIR="$ROOT_DIR/toolchains/termux-packages"

TERMUX_REPO="https://packages.termux.dev/apt/termux-main"
PACKAGES_URL="$TERMUX_REPO/dists/stable/main/binary-aarch64/Packages"

# Packages to download
REQUIRED_PACKAGES=(
    bash
    readline
    ncurses
    libiconv
    libandroid-support
    git
    pcre2
    libcurl
    openssl
    zlib
    libnghttp2
    libnghttp3
    libngtcp2
    libssh2
    libexpat
    tmux
    libevent
    libandroid-glob
    make
    openssh
    libedit
    ldns
    krb5
    libdb
    libresolv-wrapper
)

# Soname mapping: returns space-separated soname(s) for a package.
# Uses the actual embedded SONAME from each library (some versioned, some not).
get_sonames() {
    case "$1" in
        readline)          echo "libreadline.so.8" ;;
        ncurses)           echo "libncursesw.so.6" ;;
        libiconv)          echo "libiconv.so" ;;
        libandroid-support) echo "libandroid-support.so" ;;
        libcurl)           echo "libcurl.so" ;;
        openssl)           echo "libssl.so.3 libcrypto.so.3" ;;
        pcre2)             echo "libpcre2-8.so" ;;
        libexpat)          echo "libexpat.so.1" ;;
        libnghttp2)        echo "libnghttp2.so" ;;
        libnghttp3)        echo "libnghttp3.so" ;;
        libngtcp2)         echo "libngtcp2.so libngtcp2_crypto_ossl.so" ;;
        libssh2)           echo "libssh2.so" ;;
        zlib)              echo "libz.so.1" ;;
        libevent)          echo "libevent-2.1.so libevent_core-2.1.so" ;;
        libandroid-glob)   echo "libandroid-glob.so" ;;
        libedit)           echo "libedit.so" ;;
        ldns)              echo "libldns.so" ;;
        krb5)              echo "libgssapi_krb5.so.2 libkrb5.so.3 libk5crypto.so.3 libkrb5support.so.0 libcom_err.so.3" ;;
        libdb)             echo "libdb-18.1.so" ;;
        libresolv-wrapper) echo "libresolv_wrapper.so" ;;
        *)                 echo "" ;;
    esac
}

# Packages that have shared libraries to extract (not bash/git — those go to jniLibs)
LIB_PACKAGES=(
    readline ncurses libiconv libandroid-support
    libcurl openssl pcre2 libexpat
    libnghttp2 libnghttp3 libngtcp2 libssh2 zlib
    libevent libandroid-glob libedit ldns
    krb5 libdb libresolv-wrapper
)

echo "=== Downloading Termux Tools (bash + git + tmux + make + ssh) ==="
echo ""

mkdir -p "$WORK_DIR"
cd "$WORK_DIR"

# --- Step 1: Download and parse Packages index ---
echo "Downloading Packages index..."
if [ ! -f Packages ] || [ -n "$(find Packages -mmin +60 2>/dev/null)" ]; then
    curl -L --fail --show-error -o Packages "$PACKAGES_URL"
    echo "  Downloaded: $(du -sh Packages | cut -f1)"
else
    echo "  Using cached Packages index (less than 1 hour old)"
fi

# Parse index to find Filename for each required package.
# Store results in a temp file (one line per pkg: "pkgname filename")
echo ""
echo "Resolving package URLs..."
PKG_MAP_FILE="$(mktemp)"
trap "rm -f '$PKG_MAP_FILE'" EXIT

for pkg in "${REQUIRED_PACKAGES[@]}"; do
    filename=$(awk -v pkg="$pkg" '
        /^Package: / { current = $2 }
        /^Filename: / && current == pkg { print $2; exit }
    ' Packages)

    if [ -z "$filename" ]; then
        echo "  ERROR: Package '$pkg' not found in index"
        exit 1
    fi
    echo "$pkg $filename" >> "$PKG_MAP_FILE"
    echo "  $pkg -> $(basename "$filename")"
done

# Helper to look up filename for a package
get_pkg_filename() {
    awk -v pkg="$1" '$1 == pkg { print $2; exit }' "$PKG_MAP_FILE"
}

# --- Step 2: Download .deb files ---
echo ""
echo "Downloading .deb packages..."
mkdir -p debs
for pkg in "${REQUIRED_PACKAGES[@]}"; do
    filename="$(get_pkg_filename "$pkg")"
    debname="$(basename "$filename")"
    if [ -f "debs/$debname" ]; then
        echo "  $debname (cached)"
    else
        curl -L --fail --show-error -o "debs/$debname" "$TERMUX_REPO/$filename"
        echo "  $debname ($(du -sh "debs/$debname" | cut -f1))"
    fi
done

# --- Step 3: Extract all .deb packages ---
echo ""
echo "Extracting packages..."
rm -rf extracted
mkdir -p extracted
for pkg in "${REQUIRED_PACKAGES[@]}"; do
    filename="$(get_pkg_filename "$pkg")"
    debname="$(basename "$filename")"
    pkg_extract="extracted/$pkg"
    mkdir -p "$pkg_extract"
    (
        cd "$pkg_extract"
        # .deb files are ar archives. Use bsdtar which handles them on macOS.
        # Extract the data.tar.* member, then extract its contents.
        bsdtar xf "../../debs/$debname" data.tar.xz data.tar.gz data.tar.zst 2>/dev/null || true
        if [ -f data.tar.xz ]; then
            tar xf data.tar.xz
        elif [ -f data.tar.gz ]; then
            tar xf data.tar.gz
        elif [ -f data.tar.zst ]; then
            zstd -d data.tar.zst -o data.tar && tar xf data.tar
        else
            echo "  ERROR: Could not extract data archive from $debname"
            ls -la
            exit 1
        fi
    )
    echo "  $pkg extracted"
done

# --- Step 4: Place main executables in jniLibs ---
echo ""
echo "Placing executables in jniLibs..."
mkdir -p "$JNILIBS_DIR"

# bash
BASH_BIN="extracted/bash/data/data/com.termux/files/usr/bin/bash"
if [ -f "$BASH_BIN" ]; then
    cp "$BASH_BIN" "$JNILIBS_DIR/libbash.so"
    echo "  libbash.so ($(du -sh "$JNILIBS_DIR/libbash.so" | cut -f1))"
else
    echo "  ERROR: bash binary not found at $BASH_BIN"
    echo "  Looking for bash..."
    find "extracted/bash" -name "bash" -type f 2>/dev/null || true
    exit 1
fi

# git
GIT_BIN="extracted/git/data/data/com.termux/files/usr/bin/git"
if [ -f "$GIT_BIN" ]; then
    cp "$GIT_BIN" "$JNILIBS_DIR/libgit.so"
    echo "  libgit.so ($(du -sh "$JNILIBS_DIR/libgit.so" | cut -f1))"
else
    echo "  ERROR: git binary not found at $GIT_BIN"
    echo "  Looking for git..."
    find "extracted/git" -name "git" -type f 2>/dev/null || true
    exit 1
fi

# tmux
TMUX_BIN="extracted/tmux/data/data/com.termux/files/usr/bin/tmux"
if [ -f "$TMUX_BIN" ]; then
    cp "$TMUX_BIN" "$JNILIBS_DIR/libtmux.so"
    echo "  libtmux.so ($(du -sh "$JNILIBS_DIR/libtmux.so" | cut -f1))"
else
    echo "  ERROR: tmux binary not found at $TMUX_BIN"
    find "extracted/tmux" -name "tmux" -type f 2>/dev/null || true
    exit 1
fi

# make
MAKE_BIN="extracted/make/data/data/com.termux/files/usr/bin/make"
if [ -f "$MAKE_BIN" ]; then
    cp "$MAKE_BIN" "$JNILIBS_DIR/libmake.so"
    echo "  libmake.so ($(du -sh "$JNILIBS_DIR/libmake.so" | cut -f1))"
else
    echo "  ERROR: make binary not found at $MAKE_BIN"
    find "extracted/make" -name "make" -type f 2>/dev/null || true
    exit 1
fi

# ssh (OpenSSH client)
SSH_BIN="extracted/openssh/data/data/com.termux/files/usr/bin/ssh"
if [ -f "$SSH_BIN" ]; then
    cp "$SSH_BIN" "$JNILIBS_DIR/libssh.so"
    echo "  libssh.so ($(du -sh "$JNILIBS_DIR/libssh.so" | cut -f1))"
else
    echo "  ERROR: ssh binary not found at $SSH_BIN"
    find "extracted/openssh" -name "ssh" -type f 2>/dev/null || true
    exit 1
fi

# ssh-keygen
SSH_KEYGEN_BIN="extracted/openssh/data/data/com.termux/files/usr/bin/ssh-keygen"
if [ -f "$SSH_KEYGEN_BIN" ]; then
    cp "$SSH_KEYGEN_BIN" "$JNILIBS_DIR/libssh-keygen.so"
    echo "  libssh-keygen.so ($(du -sh "$JNILIBS_DIR/libssh-keygen.so" | cut -f1))"
else
    echo "  ERROR: ssh-keygen binary not found at $SSH_KEYGEN_BIN"
    find "extracted/openssh" -name "ssh-keygen" -type f 2>/dev/null || true
    exit 1
fi

# --- Step 5: Place shared libraries in assets/usr/lib/ ---
echo ""
echo "Placing shared libraries in assets/usr/lib/..."
# Clean previous libs (avoids stale versioned files from earlier runs)
rm -f "$ASSETS_DIR/usr/lib"/*.so* 2>/dev/null || true
mkdir -p "$ASSETS_DIR/usr/lib"

for pkg in "${LIB_PACKAGES[@]}"; do
    sonames="$(get_sonames "$pkg")"
    [ -z "$sonames" ] && continue
    pkg_lib_dir="extracted/$pkg/data/data/com.termux/files/usr/lib"
    for soname in $sonames; do
        # Find the actual file — try exact match, then unversioned name
        src=""
        if [ -f "$pkg_lib_dir/$soname" ] || [ -L "$pkg_lib_dir/$soname" ]; then
            src="$pkg_lib_dir/$soname"
        else
            # Soname is versioned (e.g. libreadline.so.8) but file may be unversioned
            # Strip version: libreadline.so.8 -> libreadline.so
            base_soname="$(echo "$soname" | sed 's/\.so\..*/\.so/')"
            if [ "$base_soname" != "$soname" ] && ( [ -f "$pkg_lib_dir/$base_soname" ] || [ -L "$pkg_lib_dir/$base_soname" ] ); then
                src="$pkg_lib_dir/$base_soname"
            fi
        fi

        if [ -n "$src" ] && ( [ -f "$src" ] || [ -L "$src" ] ); then
            # Copy and rename to the soname the binaries expect
            cp -L "$src" "$ASSETS_DIR/usr/lib/$soname"
            echo "  $soname ($(du -sh "$ASSETS_DIR/usr/lib/$soname" | cut -f1))"
        else
            echo "  WARNING: $soname not found in $pkg (looked in $pkg_lib_dir)"
            [ -d "$pkg_lib_dir" ] && ls "$pkg_lib_dir"/*.so* 2>/dev/null | head -5 || true
        fi
    done
done

# --- Step 6: Place git-core helpers ---
echo ""
echo "Setting up git-core..."
GIT_CORE_SRC="extracted/git/data/data/com.termux/files/usr/libexec/git-core"
GIT_CORE_DST="$ASSETS_DIR/usr/lib/git-core"
rm -rf "$GIT_CORE_DST"
mkdir -p "$GIT_CORE_DST"

if [ ! -d "$GIT_CORE_SRC" ]; then
    echo "  WARNING: git-core not at $GIT_CORE_SRC, searching..."
    GIT_CORE_SRC="$(find extracted/git -type d -name "git-core" | head -1)"
    if [ -z "$GIT_CORE_SRC" ]; then
        echo "  ERROR: git-core directory not found"
        exit 1
    fi
    echo "  Found at: $GIT_CORE_SRC"
fi

# Identify which git-core files are hardlinks/symlinks to the main git binary.
# These will be created as symlinks to libgit.so at runtime (saves ~4MB per copy).
# Compare by file size since inode comparison fails across extraction boundaries.
GIT_BIN_SIZE=$(wc -c < "$GIT_BIN" | tr -d ' ')
SYMLINK_MANIFEST="$GIT_CORE_DST/gitcore-symlinks"
> "$SYMLINK_MANIFEST"
COPIED=0
SYMLINK_COUNT=0

for file in "$GIT_CORE_SRC"/*; do
    [ -e "$file" ] || continue
    name="$(basename "$file")"

    if [ -L "$file" ]; then
        # Symlink — check if it points to git
        target="$(readlink "$file")"
        if [ "$target" = "git" ] || [ "$target" = "../../bin/git" ]; then
            echo "$name" >> "$SYMLINK_MANIFEST"
            SYMLINK_COUNT=$((SYMLINK_COUNT + 1))
            continue
        fi
    fi

    if [ -f "$file" ] && [ ! -L "$file" ]; then
        file_size=$(wc -c < "$file" | tr -d ' ')
        if [ "$file_size" = "$GIT_BIN_SIZE" ]; then
            # Same size as git binary — almost certainly a hardlink copy
            echo "$name" >> "$SYMLINK_MANIFEST"
            SYMLINK_COUNT=$((SYMLINK_COUNT + 1))
            continue
        fi
    fi

    # Script or standalone binary — copy
    if [ -f "$file" ] || [ -L "$file" ]; then
        # Resolve symlinks for copy
        real_file="$file"
        [ -L "$file" ] && real_file="$(cd "$(dirname "$file")" && readlink -f "$(basename "$file")" 2>/dev/null || echo "$file")"

        if [ -f "$real_file" ] && head -c 2 "$real_file" 2>/dev/null | grep -q '#!'; then
            # Detect script type by content
            if head -20 "$real_file" | grep -q '^use \|^require '; then
                # Perl script — Perl is not bundled, remove entirely
                echo "  Skipping Perl script: $name"
                COPIED=$((COPIED - 1))  # offset the +1 below
            elif head -1 "$real_file" | grep -q 'python'; then
                # Python script with Termux shebang — niche tool, remove
                echo "  Skipping Python script: $name"
                COPIED=$((COPIED - 1))
            elif [ "$name" = "git-instaweb" ]; then
                # Needs httpd + perl, neither bundled — remove
                echo "  Skipping $name (needs httpd + perl)"
                COPIED=$((COPIED - 1))
            else
                # Shell script — fix shebangs and embedded Termux paths
                sed \
                    -e 's|#!/data/data/com.termux/files/usr/bin/sh|#!/system/bin/sh|g' \
                    -e 's|#!/data/data/com.termux/files/usr/bin/bash|#!/system/bin/sh|g' \
                    -e 's|/data/data/com.termux/files/usr/bin/sh|/system/bin/sh|g' \
                    -e 's|/data/data/com.termux/files/usr/share/locale|$PREFIX/share/locale|g' \
                    "$real_file" > "$GIT_CORE_DST/$name"
            fi
        else
            # Binary — copy as-is
            cp -L "$file" "$GIT_CORE_DST/$name" 2>/dev/null || cp "$real_file" "$GIT_CORE_DST/$name"
        fi
        COPIED=$((COPIED + 1))
    fi
done

echo "  Copied $COPIED standalone files"
echo "  Created gitcore-symlinks manifest ($SYMLINK_COUNT entries -> symlinked to libgit.so at runtime)"

# Also copy git templates if they exist
GIT_TEMPLATES_SRC="extracted/git/data/data/com.termux/files/usr/share/git-core"
if [ -d "$GIT_TEMPLATES_SRC" ]; then
    mkdir -p "$ASSETS_DIR/usr/share/git-core"
    cp -r "$GIT_TEMPLATES_SRC/templates" "$ASSETS_DIR/usr/share/git-core/" 2>/dev/null || true
    echo "  Copied git templates"
fi

# --- Step 7: Place terminfo data ---
echo ""
echo "Setting up terminfo..."
TERMINFO_SRC="extracted/ncurses/data/data/com.termux/files/usr/share/terminfo"
TERMINFO_DST="$ASSETS_DIR/usr/share/terminfo"
mkdir -p "$TERMINFO_DST/x" "$TERMINFO_DST/d"

if [ -d "$TERMINFO_SRC" ]; then
    for entry in x/xterm x/xterm-256color d/dumb; do
        if [ -f "$TERMINFO_SRC/$entry" ]; then
            cp "$TERMINFO_SRC/$entry" "$TERMINFO_DST/$entry"
            echo "  $entry"
        fi
    done
else
    echo "  WARNING: terminfo not found in ncurses package"
fi

# --- Step 8: Size summary ---
echo ""
echo "=== Size Summary ==="

echo "jniLibs executables:"
for so in "$JNILIBS_DIR"/libbash.so "$JNILIBS_DIR"/libgit.so "$JNILIBS_DIR"/libtmux.so "$JNILIBS_DIR"/libmake.so "$JNILIBS_DIR"/libssh.so "$JNILIBS_DIR"/libssh-keygen.so; do
    [ -f "$so" ] && echo "  $(basename "$so"): $(du -sh "$so" | cut -f1)"
done

echo ""
echo "Shared libraries (assets/usr/lib/):"
total_lib=0
for f in "$ASSETS_DIR/usr/lib"/*.so*; do
    [ -f "$f" ] || continue
    size=$(du -sk "$f" | cut -f1)
    total_lib=$((total_lib + size))
    echo "  $(basename "$f"): $(du -sh "$f" | cut -f1)"
done
echo "  Total: $((total_lib / 1024))M"

echo ""
echo "git-core (assets/usr/lib/git-core/):"
if [ -d "$GIT_CORE_DST" ]; then
    echo "  $(du -sh "$GIT_CORE_DST" | cut -f1) total, $(ls -1 "$GIT_CORE_DST" | wc -l | tr -d ' ') files"
fi

echo ""
echo "terminfo (assets/usr/share/terminfo/):"
if [ -d "$TERMINFO_DST" ]; then
    echo "  $(find "$TERMINFO_DST" -type f | wc -l | tr -d ' ') entries"
fi

echo ""
echo "=== Download complete ==="
echo "Next: cd android && ./gradlew assembleDebug"
