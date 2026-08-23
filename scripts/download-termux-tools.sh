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

# The index fetch, its signature check, package resolution and the digest check
# on each .deb, shared with every other script that takes packages from Termux.
# It also picks the mirror; TERMUX_MIRROR still overrides it.
. "$SCRIPT_DIR/lib/termux-packages.sh"

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
    # libdb is deliberately absent. It arrived as a krb5 dependency and is the
    # only AGPL-3.0 component this project has ever shipped, which is the
    # strongest copyleft in common use and carries a source obligation the rest
    # of the tree does not. Measured before removing it: across all 155 shipped
    # ELF objects, nothing lists libdb-18.1.so in DT_NEEDED, and the only
    # binary containing the string "libdb" is that library naming itself in its
    # own SONAME. krb5's client libraries do not use it -- the Berkeley DB
    # backend belongs to the KDC, which is server-side and not built here.
    # Dropping it extinguishes the obligation entirely rather than documenting
    # it, which is both cheaper and less fragile than a written source offer.
    libresolv-wrapper
    # Node's own dependencies. It is fetched by download-node.sh, but its
    # libraries belong here: step 5 wipes assets/usr/lib before repopulating it,
    # so anything placed there by a later script would survive only by running
    # after this one.
    c-ares
    libicu
    libc++
    libsqlite
    # Not a component that ships: it carries usr/share/LICENSES, the shared
    # licence texts most of the packages above point their copyright symlink
    # at. Without it termux_copy_notices has nothing to resolve those links
    # against, and the copyleft half of the tree would reach a device with a
    # dangling link where its licence should be.
    termux-licenses
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
        libresolv-wrapper) echo "libresolv_wrapper.so" ;;
        c-ares)            echo "libcares.so" ;;
        # libicudata is the 32 MB data blob the other two are useless without.
        libicu)            echo "libicui18n.so.78 libicuuc.so.78 libicudata.so.78" ;;
        libc++)            echo "libc++_shared.so" ;;
        libsqlite)         echo "libsqlite3.so" ;;
        *)                 echo "" ;;
    esac
}

# Packages that have shared libraries to extract (not bash/git; those go to jniLibs)
LIB_PACKAGES=(
    readline ncurses libiconv libandroid-support
    libcurl openssl pcre2 libexpat
    libnghttp2 libnghttp3 libngtcp2 libssh2 zlib
    libevent libandroid-glob libedit ldns
    krb5 libresolv-wrapper
    c-ares libicu libc++ libsqlite
)

echo "=== Downloading Termux Tools (bash + git + tmux + make + ssh) ==="
echo ""

mkdir -p "$WORK_DIR"
cd "$WORK_DIR"

# --- Step 1: the package index, its signature, and what it resolves to ---
termux_fetch_index
termux_resolve_packages resolved-termux-tools.tsv "${REQUIRED_PACKAGES[@]}"

# --- Step 2: Download .deb files, each checked against the signed index ---
termux_download_packages "${REQUIRED_PACKAGES[@]}"

# --- Step 3: Extract ---
rm -rf "$WORK_DIR/extracted"
termux_extract_packages "${REQUIRED_PACKAGES[@]}"

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

# git-remote-curl, the helper git execs for anything it does not speak natively.
# http, https, ftp and ftps are one identical binary under four names -- checked
# on device, all four 2010824 bytes with the same sha256 -- so one copy serves
# all of them and setupGitCore links the four names to it.
#
# It belongs here rather than in git-core with the rest, and that is the whole
# point of this entry: SELinux refuses execve on anything under filesDir for
# targetSdk >= 29, which is why bash, git and ripgrep are shipped this way too.
# Left as the copied file in git-core it installs, chmods, and then fails at the
# moment git needs it -- "cannot exec 'git-remote-https': Permission denied",
# taking every clone, fetch and push over HTTPS with it.
GIT_REMOTE_CURL_BIN="extracted/git/data/data/com.termux/files/usr/libexec/git-core/git-remote-https"
if [ -f "$GIT_REMOTE_CURL_BIN" ]; then
    cp "$GIT_REMOTE_CURL_BIN" "$JNILIBS_DIR/libgit-remote-curl.so"
    echo "  libgit-remote-curl.so ($(du -sh "$JNILIBS_DIR/libgit-remote-curl.so" | cut -f1))"
else
    echo "  ERROR: git-remote-https not found at $GIT_REMOTE_CURL_BIN"
    find "extracted/git" -name "git-remote-https" -type f 2>/dev/null || true
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

# --- Step 4b: Point the compiled-in default shell at one this app can run ---
#
# Termux builds these for its own prefix, so each of the five carries
# /data/data/com.termux/files/usr/bin/sh as a compiled-in constant. That
# directory belongs to another application and this one cannot reach it, so
# every make recipe line, every git hook, `!` alias, clean/smudge filter and
# pager, and ssh's ProxyCommand run a shell that is not there. $SHELL does not
# rescue them: make ignores it by design and git does not consult it for
# run-command at all.
#
# Before step 8's ELF gate, so what that gate examines is what ships. The
# rewrite is length-preserving and fails closed: if a package changes where its
# default shell comes from and the string stops matching, the build stops here
# rather than shipping a binary whose shell nobody has established.
#
# Not libbash.so and not libssh-keygen.so: neither carries the path, and handing
# them to the script would fail for the right reason at the wrong file.
echo ""
echo "Pointing compiled-in default shells at /system/bin/sh..."
python3 "$SCRIPT_DIR/patch-default-shell.py" \
    "$JNILIBS_DIR/libgit.so" \
    "$JNILIBS_DIR/libgit-remote-curl.so" \
    "$JNILIBS_DIR/libtmux.so" \
    "$JNILIBS_DIR/libmake.so" \
    "$JNILIBS_DIR/libssh.so"

# --- Step 5: Place shared libraries in assets/usr/lib/ ---
echo ""
echo "Placing shared libraries in assets/usr/lib/..."
# Clean previous libs (avoids stale versioned files from earlier runs).
#
# Only the ones this script places. `usr/lib` has two owners: the Termux
# libraries below, and the glibc compatibility shim that build-glibc-shim.sh
# writes there. A blanket `*.so*` swept the shim away as well, and nothing
# rebuilt it or noticed it was gone -- the sweep at the end of this script runs
# after the wipe, so it examines only what this script just wrote, and absence is
# invisible to it. What that costs is every addon that names a glibc soname
# failing at dlopen on the device.
#
# The list is read out of the shim's own script rather than copied, so the two
# cannot drift. A `*.so*` that is not the shim's is still removed, which is the
# stale-version case this sweep exists for.
SHIM_STUBS=$(
    awk '/^STUBS=\(/,/\)/' "$SCRIPT_DIR/build-glibc-shim.sh" \
        | tr ' ()' '\n\n\n' \
        | grep -E '^(lib|ld-)[A-Za-z0-9._+-]+$' \
        | sort -u
)
SHIM_STUB_COUNT=$(printf '%s\n' "$SHIM_STUBS" | grep -c . || true)
# The count is asserted rather than assumed. An extraction that silently reads
# half the array is the failure this whole block exists to prevent, wearing the
# shape of success: the names it missed would be swept away exactly as before.
# Bump this deliberately when build-glibc-shim.sh gains or loses a stub.
if [ "$SHIM_STUB_COUNT" -ne 10 ]; then
    echo "  ERROR: read $SHIM_STUB_COUNT stub names from build-glibc-shim.sh," >&2
    echo "         expected 10. Refusing to sweep usr/lib rather than delete" >&2
    echo "         part of a shim this script does not own and cannot rebuild." >&2
    exit 1
fi
SHIM_OUTPUTS=$(printf 'libglibc-shim.so\n%s\n' "$SHIM_STUBS")
for lib in "$ASSETS_DIR/usr/lib"/*.so*; do
    [ -e "$lib" ] || continue
    if printf '%s\n' "$SHIM_OUTPUTS" | grep -qxF "$(basename "$lib")"; then
        continue
    fi
    rm -f "$lib"
done
mkdir -p "$ASSETS_DIR/usr/lib"

# Reads DT_SONAME out of an ELF shared object. Used to catch the rename trap
# below: the expected-soname list in get_sonames() is a snapshot, and when the
# repo bumps a library's major (ICU 78 -> 79) the fallback used to grab the
# unversioned symlink and cp -L would RENAME the new lib to the stale expected
# name - shipping libicui18n.so.78 whose real SONAME says .79, beside fresh
# binaries whose DT_NEEDED says .79. Death at dlopen on device, and the
# missing-file gate never fired because a file was never missing.
read_soname() {
    python3 - "$1" <<'PY'
import struct, sys
data = open(sys.argv[1], "rb").read()
if data[:4] != b"\x7fELF" or data[4] != 2:
    sys.exit(0)
e_shoff, = struct.unpack_from("<Q", data, 40)
e_shentsize, e_shnum = struct.unpack_from("<HH", data, 58)
dynstr_off = dyn_off = dyn_size = None
sections = []
for i in range(e_shnum):
    off = e_shoff + i * e_shentsize
    sh_type, = struct.unpack_from("<I", data, off + 4)
    sh_offset, sh_size = struct.unpack_from("<QQ", data, off + 24)
    sections.append((sh_type, sh_offset, sh_size, off))
    if sh_type == 6:  # SHT_DYNAMIC
        dyn_off, dyn_size = sh_offset, sh_size
        sh_link, = struct.unpack_from("<I", data, off + 40)
        link_hdr = e_shoff + sh_link * e_shentsize
        dynstr_off, = struct.unpack_from("<Q", data, link_hdr + 24)
if dyn_off is None:
    sys.exit(0)
i = dyn_off
while i < dyn_off + dyn_size:
    d_tag, d_val = struct.unpack_from("<qQ", data, i)
    if d_tag == 14:  # DT_SONAME
        end = data.index(b"\x00", dynstr_off + d_val)
        print(data[dynstr_off + d_val:end].decode())
        break
    if d_tag == 0:
        break
    i += 16
PY
}

for pkg in "${LIB_PACKAGES[@]}"; do
    sonames="$(get_sonames "$pkg")"
    if [ -z "$sonames" ]; then
        echo "  $pkg: no shared libraries expected (no get_sonames row)"
        continue
    fi
    pkg_lib_dir="extracted/$pkg/data/data/com.termux/files/usr/lib"
    for soname in $sonames; do
        # Find the actual file: try exact match, then unversioned name
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
            # The file's real SONAME must agree with the name we ship it under;
            # see read_soname() above for the rename trap this closes. A library
            # with no DT_SONAME at all is left to the ELF verification.
            actual_soname="$(read_soname "$src")"
            if [ -n "$actual_soname" ] && [ "$actual_soname" != "$soname" ]; then
                echo "  ERROR: $pkg ships $(basename "$src") with SONAME $actual_soname," >&2
                echo "         but this script expects $soname. The library moved a" >&2
                echo "         major - update get_sonames() and rebuild everything" >&2
                echo "         that links against it." >&2
                exit 1
            fi
            # Copy and rename to the soname the binaries expect
            cp -L "$src" "$ASSETS_DIR/usr/lib/$soname"
            echo "  $soname ($(du -sh "$ASSETS_DIR/usr/lib/$soname" | cut -f1))"
        else
            # Fatal, not a warning: a library missing here ships an APK where
            # bash or git dies at dlopen on a user's device - the exact class
            # of quiet breakage the ELF verification exists to prevent. It
            # printed WARNING and exited 0 before, which let exactly that
            # through.
            echo "  ERROR: $soname not found in $pkg (looked in $pkg_lib_dir)" >&2
            [ -d "$pkg_lib_dir" ] && ls "$pkg_lib_dir"/*.so* 2>/dev/null | head -5 || true
            exit 1
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

# Three consumers, all of them here because git-core mixes text with binaries:
# the copy loop below tells one from the other with it, the prefix gate after the
# loop reads only the text, and the ELF verification at the foot of this script
# reads only the binaries, since verify-android-elf.py rejects a non-ELF file
# rather than skipping it. The last of those now finds nothing, because the loop
# stops copying binaries here at all; it stays as the guard for a binary coming
# back.
is_elf() {
    [ "$(dd if="$1" bs=4 count=1 2>/dev/null | od -An -tx1 | tr -d ' \n')" = "7f454c46" ]
}

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

    # The four remote helpers are not copied at all, and this is the other half
    # of the jniLibs entry above. They are one binary under four names -- all
    # four 2010824 bytes with the same sha256, measured -- and the copy that
    # runs is libgit-remote-curl.so in nativeLibraryDir, because SELinux refuses
    # execve on anything under filesDir. FirstRunSetup.setupGitCore replaces all
    # four names with symlinks to it on every launch and does not require the
    # file to be there first: its loop creates the link when lstat finds
    # nothing. So copying them shipped 7.67 MiB in the APK, unpacked it on first
    # run, and then deleted it, four times over.
    case "$name" in
        git-remote-http|git-remote-https|git-remote-ftp|git-remote-ftps) continue ;;
    esac

    if [ -L "$file" ]; then
        # Symlink: check if it points to git
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
            # Same size as git binary: almost certainly a hardlink copy
            echo "$name" >> "$SYMLINK_MANIFEST"
            SYMLINK_COUNT=$((SYMLINK_COUNT + 1))
            continue
        fi
    fi

    # Script or standalone binary: copy
    if [ -f "$file" ] || [ -L "$file" ]; then
        # Resolve symlinks for copy
        real_file="$file"
        [ -L "$file" ] && real_file="$(cd "$(dirname "$file")" && readlink -f "$(basename "$file")" 2>/dev/null || echo "$file")"

        # Anything that is not an ELF object is text this loop has to rewrite,
        # and that is a wider set than "has a shebang". git-core ships sourced
        # shell libraries with no shebang of their own -- git-sh-i18n,
        # git-sh-setup, git-mergetool--lib -- and a shebang test sent them down
        # the binary branch, so they were copied byte for byte with Termux's
        # prefix still in them: git-sh-i18n reached the device setting
        # TEXTDOMAINDIR to /data/data/com.termux/files/usr/share/locale, a path
        # this app cannot read.
        if [ -f "$real_file" ] && ! is_elf "$real_file"; then
            # Detect script type by content
            if head -20 "$real_file" | grep -q '^use \|^require '; then
                # Perl script: Perl is not bundled, remove entirely
                echo "  Skipping Perl script: $name"
                COPIED=$((COPIED - 1))  # offset the +1 below
            elif head -1 "$real_file" | grep -q 'python'; then
                # Python script with Termux shebang: niche tool, remove
                echo "  Skipping Python script: $name"
                COPIED=$((COPIED - 1))
            elif [ "$name" = "git-instaweb" ]; then
                # Needs httpd + perl, neither bundled: remove
                echo "  Skipping $name (needs httpd + perl)"
                COPIED=$((COPIED - 1))
            elif [ "$name" = "git-gui--askyesno" ]; then
                # Its second line execs Termux's `wish`, so the whole file is a
                # Tcl/Tk program. Neither Tcl nor Tk is bundled and no sed below
                # can supply one, which left it as the last file in this tree
                # still naming Termux's prefix after the rewrite.
                echo "  Skipping $name (needs Tcl/Tk wish)"
                COPIED=$((COPIED - 1))
            else
                # Shell script: fix shebangs and embedded Termux paths
                sed \
                    -e 's|#!/data/data/com.termux/files/usr/bin/sh|#!/system/bin/sh|g' \
                    -e 's|#!/data/data/com.termux/files/usr/bin/bash|#!/system/bin/sh|g' \
                    -e 's|/data/data/com.termux/files/usr/bin/sh|/system/bin/sh|g' \
                    -e 's|/data/data/com.termux/files/usr/share/locale|$PREFIX/share/locale|g' \
                    "$real_file" > "$GIT_CORE_DST/$name"
            fi
        else
            # A standalone ELF helper, and one that cannot run here. It would
            # land under filesDir, where SELinux denies the app
            # execute_no_trans, and git reaches its helpers by absolute path out
            # of GIT_EXEC_PATH rather than through PATH, so the exec trampoline
            # never sees them either. The git-* commands that DO work are the
            # manifest entries above, which become symlinks into
            # nativeLibraryDir.
            #
            # Measured in the app's own terminal on an API 33 arm64 emulator,
            # domain u:r:untrusted_app:s0: git-shell and scalar exit 126
            # "Permission denied", `git submodule` reports `fatal: cannot exec
            # 'git-submodule'`, and `git --version` succeeds because it resolves
            # through nativeLibraryDir. Eight files qualified, 15.05 MiB, shipped
            # in the APK and unpacked on every first run for commands (git
            # daemon, git http-backend, git imap-send, scalar and the rest) that
            # fail either way. Only the message changes.
            #
            # Size is what makes the rule stop here, not reachability, and the
            # branch above is not a claim that what it copies works. Everything
            # git EXECS is equally dead, shell script or ELF: the eleven with a
            # shebang (git-submodule, git-subtree, git-mergetool,
            # git-filter-branch and the rest) fail exactly as the measurement
            # above shows. What survives the same policy is what a shell SOURCES
            # rather than execs, because sourcing is a read: git-sh-setup,
            # git-sh-i18n and git-mergetool--lib. Those three are the reason the
            # text is kept, and the eleven ride along because all fourteen
            # together are 132 KiB against the 15.05 MiB dropped here. Making
            # the exec'd ones work is a different change: setupGitCore would
            # have to symlink each into nativeLibraryDir and libexec-trampoline
            # would need a row per name.
            echo "  Skipping unexecutable helper: $name"
            COPIED=$((COPIED - 1))
        fi
        COPIED=$((COPIED + 1))
    fi
done

echo "  Copied $COPIED standalone files"
echo "  Created gitcore-symlinks manifest ($SYMLINK_COUNT entries -> symlinked to libgit.so at runtime)"

# The rewrite above is a list of four sed rules, and a rule can only cover a path
# someone thought of. This asks the produced tree the question the rules exist to
# answer, so a fifth Termux path arriving with a git bump is a failed build here
# rather than a command that fails on a device. Text only: the ELF helpers carry
# no prefix (measured, all eight at 0 occurrences), and an embedded shell path in
# a binary is scripts/patch-default-shell.py's subject, not a sed's.
stale_prefix=""
for helper in "$GIT_CORE_DST"/*; do
    [ -f "$helper" ] || continue
    is_elf "$helper" && continue
    if LC_ALL=C grep -q '/data/data/com\.termux' "$helper"; then
        stale_prefix="$stale_prefix $(basename "$helper")"
    fi
done
if [ -n "$stale_prefix" ]; then
    echo "  ERROR: git-core text still names Termux's prefix:$stale_prefix" >&2
    echo "         The app cannot read /data/data/com.termux, so whatever that" >&2
    echo "         path was for fails with ENOENT on every device. Add a sed" >&2
    echo "         rule above for it, or skip the file if nothing here can" >&2
    echo "         supply what it wants." >&2
    exit 1
fi

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

# --- Step 7b: Place the upstream notices beside what they describe ---
#
# The whole directory first, for the reason step 5 wipes usr/lib: a package
# dropped from the list above would otherwise leave its notice behind and go on
# claiming a component this APK no longer ships. This script runs before every
# other Termux consumer (build-all.sh, build.yml and release.yml all order it
# first), so the wipe is safe here and nowhere else -- download-python.sh adds
# its own packages to the same directory afterwards.
rm -rf "$ASSETS_DIR/usr/share/doc"
termux_copy_notices "$ASSETS_DIR/usr" "${REQUIRED_PACKAGES[@]}"

# ICU is the one package whose notice cannot come from Termux. Measured on
# libicu 78.3: its .deb carries usr/share/doc/libicu/LICENSE holding the
# fourteen bytes "404: Not Found", and nothing else in the package is a licence
# file. Shipping those fourteen bytes would discharge nothing, so the file is
# replaced with the LICENSE from the upstream release Termux builds, taken
# verbatim from unicode-org/icu at tag release-78.3 -- the tag its own build.sh
# names through TERMUX_PKG_SRCURL for TERMUX_PKG_VERSION=78.3.
#
# Not verified: WHY Termux ships an error page there. Its build.sh reads
# TERMUX_PKG_LICENSE_FILE="../LICENSE" under a comment calling that path a hack,
# which is suggestive and is not a measurement. What matters here is that the
# file it ships is not a licence.
#
# The text is version-specific and the package version is resolved from a live
# index, so the two are compared rather than left to a comment nobody re-reads.
# A libicu bump that nobody re-paired would ship 78.3's LICENSE beside a
# different ICU, which is the one way this notice goes wrong with every step
# green. Re-take the text from unicode-org/icu at the matching release tag and
# move the version here.
#
# Matched on the upstream version only. A Termux Version field carries a package
# revision after the upstream version (measured in the live index: python
# 3.14.6-1, ruby 3.4.1-2, libicu 78.3 with none yet), and a rebuild of the same
# ICU arrives as 78.3-1. That is the same LICENSE, so an exact string test would
# fail every build over a repackaging and send the reader to release-78.3-1, a
# tag unicode-org/icu does not have.
ICU_NOTICE_VERSION="78.3"
ICU_INDEX_VERSION="$(termux_pkg_version libicu)"
case "$ICU_INDEX_VERSION" in
    "$ICU_NOTICE_VERSION"|"$ICU_NOTICE_VERSION"-*) ;;
    *)
        echo "  ERROR: the index carries libicu $ICU_INDEX_VERSION and" >&2
        echo "         licenses/LICENSE.ICU is ICU $ICU_NOTICE_VERSION's LICENSE." >&2
        echo "         Take the text from unicode-org/icu at tag" >&2
        echo "         release-${ICU_INDEX_VERSION%%-*} and update" >&2
        echo "         ICU_NOTICE_VERSION." >&2
        exit 1
        ;;
esac
ICU_NOTICE="$ROOT_DIR/licenses/LICENSE.ICU"
if [ ! -f "$ICU_NOTICE" ]; then
    echo "  ERROR: $ICU_NOTICE is missing; ICU would ship with no notice." >&2
    exit 1
fi
mkdir -p "$ASSETS_DIR/usr/share/doc/libicu"
cp "$ICU_NOTICE" "$ASSETS_DIR/usr/share/doc/libicu/LICENSE"
echo "  libicu: replaced Termux's 404 stub with the ICU $ICU_NOTICE_VERSION LICENSE"

# --- Step 8: Verify everything placed can actually load on Android ---
echo ""
echo "=== Verifying placed binaries ==="
# Every object above arrives pre-compiled from a third-party mirror and is then
# either executed on the device or dlopen'd into a process there, and until now
# nothing checked that any of it could load at all. That gap is not theoretical:
# an install shipped a libbash.so whose libandroid-support.so was not beside it,
# and the terminal died with "CANNOT LINK EXECUTABLE ... library
# libandroid-support.so not found" -- at runtime, on a device, from a build that
# had reported success. verify-android-elf.py answers exactly the three
# questions that would have caught it: the right architecture, every DT_NEEDED
# resolvable, and 16 KB segment alignment for Android 16.
#
# Both --lib-dir arguments are load-bearing. The executables here link against
# the libraries this same script places, so a check without them would reject
# every legitimate dependency and the gate would be useless in the other
# direction.
#
# This runs last because step 5 empties assets/usr/lib before repopulating it:
# verifying earlier would be verifying a directory still being built.

JNILIBS_BINARIES=(libbash.so libgit.so libgit-remote-curl.so libtmux.so libmake.so
                  libssh.so libssh-keygen.so)

verify_failures=0
verify_checked=0

verify_object() {
    local out
    verify_checked=$((verify_checked + 1))
    if ! out=$(python3 "$SCRIPT_DIR/verify-android-elf.py" "$1" \
                   --lib-dir "$ASSETS_DIR/usr/lib" --lib-dir "$JNILIBS_DIR" 2>&1); then
        echo "  FAILED  $(basename "$1")" >&2
        # `|| true` because this file runs under pipefail: if grep ever matched
        # nothing it would return 1, and the shell would exit right here --
        # killing the build with no message at all, in the one branch whose job
        # is to explain what went wrong. Measured, not assumed: a failing
        # pipeline in this position aborts before the next line runs.
        echo "$out" | grep -v '^  ok' | sed 's/^/     /' >&2 || true
        verify_failures=$((verify_failures + 1))
    fi
}

for name in "${JNILIBS_BINARIES[@]}"; do
    verify_object "$JNILIBS_DIR/$name"
done

for lib in "$ASSETS_DIR/usr/lib"/*.so*; do
    [ -f "$lib" ] && verify_object "$lib"
done

for helper in "$GIT_CORE_DST"/*; do
    [ -f "$helper" ] && is_elf "$helper" && verify_object "$helper"
done

if [ "$verify_failures" -gt 0 ]; then
    echo "" >&2
    echo "  ERROR: $verify_failures of $verify_checked objects would fail to load" >&2
    echo "         on a device. Shipping them produces a working build and a" >&2
    echo "         broken install." >&2
    exit 1
fi
echo "  $verify_checked objects verified: architecture, dependencies, 16 KB alignment"

# --- Step 9: Size summary ---
echo ""
echo "=== Size Summary ==="

echo "jniLibs executables:"
for name in "${JNILIBS_BINARIES[@]}"; do
    so="$JNILIBS_DIR/$name"
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
