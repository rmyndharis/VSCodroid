#!/usr/bin/env bash
# Sourced by the download scripts that take packages from the Termux APT repo.
# Not run on its own; it defines functions and exits nothing.
#
#     WORK_DIR="$ROOT_DIR/toolchains/termux-packages"
#     . "$SCRIPT_DIR/lib/termux-packages.sh"
#
#     termux_fetch_index
#     termux_resolve_packages resolved-go.tsv "${REQUIRED_PACKAGES[@]}"
#     termux_download_packages "${REQUIRED_PACKAGES[@]}"
#     termux_extract_packages "${REQUIRED_PACKAGES[@]}"
#
# Four scripts source this today: download-java.sh, download-python.sh,
# download-ruby.sh and download-termux-tools.sh. There were five, and the fifth,
# download-go.sh, is why the count is worth stating: it went away with the Go
# toolchain and the library did not notice, which is the point of the library.
# Every one of them fetched the index, called
# verify-termux-index.sh, resolved filenames and digests with the same awk, and
# checked each .deb against the digest with the same function. A correction to
# any of it had to be made five times and was worth nothing until it had been,
# which is the shape of defect that arrives as "one script was missed".
#
# What each caller still owns is what actually differs: which packages it wants,
# where the files go afterwards, and which of them are checked as ELF objects.
#
# Compatible with bash 3.2 (macOS default), like the scripts that source it: no
# associative arrays, no `local -n`, nothing newer.

# Its own location, rather than the caller's SCRIPT_DIR. A sourced file that
# reads variables the caller happens to have set breaks the moment one of them
# is renamed, and the break is a path that resolves to nothing rather than an
# error naming the cause.
TERMUX_LIB_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TERMUX_SCRIPTS_DIR="$(dirname "$TERMUX_LIB_DIR")"

# The mirror the whole family uses. packages.termux.dev was the default in some
# of these scripts and is frequently down, and the inconsistency was worse than
# either choice alone: they share one Packages index with a 60-minute freshness
# window and one work directory, so whichever ran first left an index behind
# that the others reused. Filenames and digests resolved from one host, .deb
# files fetched from another. The digest check makes that loud rather than
# dangerous, but a build failing because two mirrors sit at different sync
# points is a confusing way to spend an afternoon.
#
# Choosing a mirror is an availability decision with no security content:
# verify-termux-index.sh anchors the index to Termux's own signature, so a
# mirror cannot pick both the payload and the digest it is measured by.
TERMUX_REPO="${TERMUX_MIRROR:-https://mirror.mwt.me/termux/main}"
PACKAGES_URL="$TERMUX_REPO/dists/stable/main/binary-aarch64/Packages"

# Set by termux_resolve_packages, read by the lookups under it.
TERMUX_RECORD_FILE=""

# Fails with the name of what is missing rather than acting on an empty path.
# `mkdir -p ""` and `rm -rf "/debs"` are the two shapes this exists to keep out
# of a script that sourced the library and forgot to say where it works.
termux_require_work_dir() {
    if [ -z "${WORK_DIR:-}" ]; then
        echo "  ERROR: WORK_DIR is not set; the caller must name its work directory" \
             "before using scripts/lib/termux-packages.sh" >&2
        return 1
    fi
}

# Downloads the package index if there is no fresh copy, then checks it against
# the repository's signed InRelease.
#
# The verification runs on every call, not only after a download: a cached index
# is exactly as unchecked as a fresh one, and every digest the callers trust is
# read out of this file.
#
# TERMUX_OFFLINE=1 keeps whatever index is here and checks it against the
# InRelease cached beside it. It does not weaken the check; see the header of
# verify-termux-index.sh for what an offline run still has to satisfy.
termux_fetch_index() {
    termux_require_work_dir || return 1
    mkdir -p "$WORK_DIR"
    local index="$WORK_DIR/Packages"

    # Anything but 0 takes this branch, and verify-termux-index.sh below is the
    # one place that decides which values are meant: testing for 1 here would
    # send a misspelt opt-in down the download path instead, where with no
    # network it fails as a connection error and the typo is never mentioned.
    if [ "${TERMUX_OFFLINE:-0}" != "0" ]; then
        # The hourly refresh below is the other half of an offline rebuild: a
        # cached index an hour old would otherwise be replaced by a download
        # that cannot happen, so verify-termux-index.sh would never be reached
        # to check the copy that is already here.
        if [ ! -f "$index" ]; then
            echo "  ERROR: TERMUX_OFFLINE is set and there is no cached index at" >&2
            echo "         $index. One run with a network creates it." >&2
            return 1
        fi
        echo "Using the cached Packages index (TERMUX_OFFLINE set, nothing downloaded)"
    else
        echo "Downloading Packages index..."
        if [ ! -f "$index" ] || [ -n "$(find "$index" -mmin +60 2>/dev/null)" ]; then
            curl -L --fail --show-error -o "$index" "$PACKAGES_URL"
            echo "  Downloaded: $(du -sh "$index" | cut -f1)"
        else
            echo "  Using cached Packages index (less than 1 hour old)"
        fi
    fi

    bash "$TERMUX_SCRIPTS_DIR/verify-termux-index.sh" "$PACKAGES_URL" "$index"
}

# Resolves each package to a filename and a digest, and records what it found.
#
#     termux_resolve_packages resolved-go.tsv golang
#
# The record is kept rather than discarded: those three fields are the exact
# statement of what the live index resolved to on this run, and
# write-build-manifest.py reads them, so a release can afterwards be asked which
# versions it shipped. It is truncated per run, so a package that goes away
# upstream leaves no stale line behind, which reading the .deb directory instead
# cannot promise since that accumulates across builds.
termux_resolve_packages() {
    termux_require_work_dir || return 1
    local record="$1"
    shift

    echo ""
    echo "Resolving package URLs..."
    TERMUX_RECORD_FILE="$WORK_DIR/$record"
    : > "$TERMUX_RECORD_FILE"

    local pkg pkg_line filename sha256
    for pkg in "$@"; do
        # One pass, emitting when the stanza ends: the live index carries
        # duplicate Package stanzas (gdk-pixbuf, neovim-nightly, ...), and two
        # independent scans could pair stanza A's Filename with stanza B's
        # SHA256.
        pkg_line=$(awk -v pkg="$pkg" '
            /^Package: / { if (fn != "" && current == pkg) exit; current = $2; fn = ""; sh = "" }
            /^Filename: / && current == pkg { fn = $2 }
            /^SHA256: / && current == pkg { sh = $2 }
            END { if (fn != "") print fn, (sh == "" ? "-" : sh) }
        ' "$WORK_DIR/Packages")
        filename=${pkg_line% *}
        sha256=${pkg_line##* }

        if [ -z "$filename" ]; then
            echo "  ERROR: Package '$pkg' not found in index" >&2
            return 1
        fi
        echo "$pkg $filename ${sha256:--}" >> "$TERMUX_RECORD_FILE"
        echo "  $pkg -> $(basename "$filename")"
    done
}

termux_pkg_filename() {
    awk -v pkg="$1" '$1 == pkg { print $2; exit }' "$TERMUX_RECORD_FILE"
}

# The index's SHA256 for a package, or "-" when the index carried none.
termux_pkg_sha256() {
    awk -v pkg="$1" '$1 == pkg { print $3; exit }' "$TERMUX_RECORD_FILE"
}

# The version string the index gives a package, for a caller that names it in a
# path or prints it. Read from the index rather than from the record, which
# deliberately holds only what a digest can be checked against.
termux_pkg_version() {
    awk -v pkg="$1" '
        /^Package: / { current = $2 }
        /^Version: / && current == pkg { print $2; exit }
    ' "$WORK_DIR/Packages"
}

# These payloads execute on user devices and arrive over a third-party mirror. A
# file that does not match the index's SHA256, cached or fresh, must not be
# used. The index the digest is read from is itself checked against Termux's
# signature, so the mirror does not get to pick both.
termux_verify_deb() {
    local file="$1" expected="$2"
    if [ "$expected" = "-" ] || [ -z "$expected" ]; then
        # Fail closed: the index and the payload come from the same host, so a
        # mirror that drops the SHA256 line could otherwise switch the check off
        # silently. Every package in the real index carries one; its absence is
        # an anomaly, not a pass.
        echo "    ERROR: no SHA256 in index for $(basename "$file")" >&2
        return 1
    fi
    local actual
    actual=$( (sha256sum "$file" 2>/dev/null || shasum -a 256 "$file") | cut -d' ' -f1)
    if [ "$actual" != "$expected" ]; then
        echo "    ERROR: $(basename "$file") does not match the index" >&2
        echo "      index : $expected" >&2
        echo "      file  : $actual" >&2
        rm -f "$file"
        return 1
    fi
}

# Downloads each package into $WORK_DIR/debs and checks it against the index.
# A cached .deb is verified again rather than trusted for having been verified
# once: nothing here can tell a file this build wrote from one left by anything
# else that had write access to the directory.
termux_download_packages() {
    termux_require_work_dir || return 1
    echo ""
    echo "Downloading .deb packages..."
    mkdir -p "$WORK_DIR/debs"

    local pkg filename debname
    for pkg in "$@"; do
        filename="$(termux_pkg_filename "$pkg")"
        debname="$(basename "$filename")"
        if [ -f "$WORK_DIR/debs/$debname" ]; then
            echo "  $debname (cached)"
        else
            curl -L --fail --show-error -o "$WORK_DIR/debs/$debname" "$TERMUX_REPO/$filename"
            echo "  $debname ($(du -sh "$WORK_DIR/debs/$debname" | cut -f1))"
        fi
        termux_verify_deb "$WORK_DIR/debs/$debname" "$(termux_pkg_sha256 "$pkg")" || return 1
    done
}

# Unpacks each package into $WORK_DIR/extracted/<package>, fresh every run.
#
# A .deb is an ar archive; bsdtar reads those on macOS as well as Linux, which
# is why it is used to reach the data member, and the member's own compression
# is whatever the packager chose.
termux_extract_packages() {
    termux_require_work_dir || return 1
    echo ""
    echo "Extracting packages..."

    local pkg filename debname pkg_extract
    for pkg in "$@"; do
        filename="$(termux_pkg_filename "$pkg")"
        debname="$(basename "$filename")"
        pkg_extract="$WORK_DIR/extracted/$pkg"
        rm -rf "$pkg_extract"
        mkdir -p "$pkg_extract"
        (
            cd "$pkg_extract"
            bsdtar xf "$WORK_DIR/debs/$debname" data.tar.xz data.tar.gz data.tar.zst 2>/dev/null || true
            if [ -f data.tar.xz ]; then
                tar xf data.tar.xz
            elif [ -f data.tar.gz ]; then
                tar xf data.tar.gz
            elif [ -f data.tar.zst ]; then
                zstd -d data.tar.zst -o data.tar && tar xf data.tar
            else
                echo "  ERROR: Could not extract data archive from $debname" >&2
                ls -la
                exit 1
            fi
        ) || return 1
        echo "  $pkg extracted"
    done
}
