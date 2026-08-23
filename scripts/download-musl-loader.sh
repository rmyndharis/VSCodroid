#!/usr/bin/env bash
set -euo pipefail

# Bundles musl's dynamic loader, which is how the Claude Code CLI runs here.
#
#   ./scripts/download-musl-loader.sh
#
# The CLI ships as a per-platform native binary and the marketplace serves two
# Linux flavours of it. Only the musl one can run on Android, and only through
# this loader. Both halves of that were measured on an API 36 emulator:
#
#   * The glibc build dies before main() with SIGSYS. glibc's __tls_init_tp
#     calls set_robust_list and rseq, and Android's app seccomp filter rejects
#     both. Nothing configurable avoids it -- the rseq tunable still leaves
#     set_robust_list, which has no tunable. The musl build makes neither call.
#
#   * SELinux refuses execve() of anything under filesDir for targetSdk >= 29
#     (no execute_no_trans on app_data_file), and an extension the user installs
#     lands exactly there. It does grant map and execute, though, which is all a
#     loader needs: execve the loader from nativeLibraryDir, where execution is
#     allowed, and let it mmap the payload out of filesDir.
#
# The two fit together neatly because resolveClaudeBinary() passes the resolved
# binary path as the wrapper's first argument, which is already the loader's
# calling convention -- so claudeCode.claudeProcessWrapper points straight at
# this file and no shim script sits in between.
#
# musl is MIT, so unlike the CLI itself this is ours to redistribute.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"
JNI_DIR="$ROOT_DIR/android/app/src/main/jniLibs/arm64-v8a"
WORK_DIR="$ROOT_DIR/toolchains/musl"

# A branch Alpine still supports, and it has to be checked when it is changed:
# an unsupported one keeps serving a signed index for years, so nothing here
# fails when it stops receiving fixes. v3.20 was named here until 2026-08-17,
# and it reached end of life on 2026-04-01 -- four months of building the
# loader out of a branch upstream had stopped patching, with every signature
# check passing.
#
# v3.23 rather than the newest, deliberately. It is supported to 2027-11-01 and
# carries musl 1.2.5, the same upstream release the previous branch did (r23
# against r3, so Alpine's own fixes on top of it); v3.24 has moved to 1.2.6,
# and the loader here is the libc the Claude Code CLI runs against, which
# nothing in this repository can test off-device. Taking maintenance without
# taking a libc bump is the smaller step, and the bigger one is a decision for
# a person with a device in hand.
#
#     curl -s https://alpinelinux.org/releases.json   # branch_date, eol_date
ALPINE_BRANCH="${ALPINE_BRANCH:-v3.23}"
MIRROR="https://dl-cdn.alpinelinux.org/alpine/$ALPINE_BRANCH/main/aarch64"

# How stale a signed index may be, in days, matching the Termux side's default.
#
# APKINDEX carries no expiry, so a signature alone does not stop a host serving
# an old index for ever and holding every build to whatever musl was current
# then. Measured 2026-08-17, and it is why this is not hypothetical: the v3.20
# index still verified against the pinned key four months after that branch went
# end of life, carrying a signed mtime of 2026-07-16 (31 days old that day, and a
# day older every day since).
#
# The date is read from the APKINDEX tar member's mtime, which lives inside the
# bytes the signature covers, so it cannot be set by whoever serves the file.
ALPINE_INDEX_MAX_AGE_DAYS="${ALPINE_INDEX_MAX_AGE_DAYS:-30}"

# Validated rather than compared as it arrives. A non-numeric value would make
# the comparison below fail in a way that reads as a stale index, or -- worse,
# depending on where it landed -- silently skip it. A gate that disappears on
# bad input is worse than an absent one, because its output still says the
# check ran.
case "$ALPINE_INDEX_MAX_AGE_DAYS" in
    ''|*[!0-9]*)
        echo "  ERROR: ALPINE_INDEX_MAX_AGE_DAYS must be a whole number of days," \
             "got '$ALPINE_INDEX_MAX_AGE_DAYS'" >&2
        exit 1
        ;;
esac

echo "=== musl loader ==="
mkdir -p "$WORK_DIR" "$JNI_DIR"

# Truncated at the start of the run, as every other resolved-*.tsv writer now
# does. That was not true when this line was written -- download-node.sh wrote its
# record before the digest of what it resolved had been checked -- and it is one
# grep to confirm which state the tree is in:
#     grep -nE '^[[:space:]]*: >' scripts/download-*.sh scripts/lib/*.sh
# The second path is not padding: the five Termux scripts truncate their record
# through scripts/lib/termux-packages.sh now, so a grep over scripts/download-*.sh
# alone reports two writers where there are seven, and reads as an answer.
# Note the anchor. An earlier form of this comment prescribed
# grep -n ': > "$PKG_MAP_FILE"', which answered the wrong question in both
# directions: it missed this script and download-node.sh, which truncate through
# literal filenames rather than that variable -- and the variable itself is gone
# now, so that pattern matches nothing -- and it matched this file anyway,
# on the comment quoting the pattern. A grep that can match its own documentation
# cannot fail for the person who wrote it.
#
# Sharper reason here than in most of them: the loader is installed well before
# the record is written, so a run that copies a new loader over the old one and
# then fails -- the ELF gate, an interrupt -- would otherwise leave the PREVIOUS
# run's record on disk naming a version that is no longer the file in jniLibs.
# The manifest would then state a loader the build does not contain, which is
# worse than stating nothing. Absent reads as "-"; wrong reads as fact.
: > "$WORK_DIR/resolved-musl.tsv"

# The key Alpine signs this branch's index with, pinned in the repository the
# same way scripts/termux-repo-key.asc is. Pinned rather than fetched: a key
# taken from the same host as the index it authenticates proves nothing about
# that host, which is the whole reason the Termux chain is anchored.
#
# Alpine rotating to a different key is a decision for a person, so a signature
# naming any other key fails loudly instead of falling back.
# The tar entry name, prefix included: apk-tools names it .SIGN.<algo>.<key>,
# and the algorithm is part of what is being pinned. A .SIGN.RSA256 entry would
# be a different signature scheme and must not be verified as RSA-SHA1.
ALPINE_KEY_NAME=".SIGN.RSA.alpine-devel@lists.alpinelinux.org-616ae350.rsa.pub"
ALPINE_KEY="$SCRIPT_DIR/alpine-devel-616ae350.rsa.pub"

# Resolve the current musl version from the branch index rather than pinning a
# release, the same way the Python download resolves its version from Termux's.
echo "--- Resolving musl version from $ALPINE_BRANCH ---"
curl -sL --fail --show-error -o "$WORK_DIR/APKINDEX.tar.gz" "$MIRROR/APKINDEX.tar.gz"

# Everything below reads digests out of this index, so the index has to be
# authenticated before any of them mean anything. APKINDEX.tar.gz is two
# concatenated gzip streams: the first is a tar holding .SIGN.RSA.<key>, an
# RSA-SHA1 signature; the second is the tar holding APKINDEX, and is what the
# signature covers.
echo "--- Verifying the index signature ---"
if [ ! -f "$ALPINE_KEY" ]; then
    echo "  ERROR: pinned Alpine key missing at $ALPINE_KEY" >&2
    exit 1
fi
python3 - "$WORK_DIR/APKINDEX.tar.gz" "$WORK_DIR" "$ALPINE_KEY_NAME" <<'PY'
import io, sys, tarfile, zlib

blob, work, expected_key = sys.argv[1], sys.argv[2], sys.argv[3]
data = open(blob, "rb").read()

d = zlib.decompressobj(31)
d.decompress(data)
split = len(data) - len(d.unused_data)
if split <= 0 or split >= len(data):
    sys.exit("the index is not two gzip streams; refusing to guess at its shape")

names = tarfile.open(fileobj=io.BytesIO(data[:split])).getnames()
if names != [expected_key]:
    sys.exit(
        "the index is signed by %s, not the pinned %s -- Alpine may have rotated "
        "keys, which is a decision for a person" % (names, expected_key)
    )

sig = tarfile.open(fileobj=io.BytesIO(data[:split])).extractfile(names[0]).read()
open(work + "/index.sig", "wb").write(sig)
open(work + "/index.signed", "wb").write(data[split:])
PY
openssl dgst -sha1 -verify "$ALPINE_KEY" \
    -signature "$WORK_DIR/index.sig" "$WORK_DIR/index.signed" > /dev/null
echo "  signature : verified against the pinned Alpine key"

# After the signature, never before it: an unverified mtime is whatever the host
# chose to write, so checking it first would date the index by asking the party
# the check exists to catch. index.signed is exactly the byte range openssl just
# verified.
python3 - "$WORK_DIR/index.signed" "$ALPINE_INDEX_MAX_AGE_DAYS" <<'PY'
import io, sys, tarfile, time

signed, limit = sys.argv[1], int(sys.argv[2])
tar = tarfile.open(fileobj=io.BytesIO(open(signed, "rb").read()))
try:
    member = tar.getmember("APKINDEX")
except KeyError:
    # Fail closed, the way an absent C: field does below: every real index
    # carries this entry, so its absence means the index cannot be dated, not
    # that it may be used undated.
    sys.exit("the signed stream carries no APKINDEX entry, so its age cannot be judged")

age = int((time.time() - member.mtime) // 86400)
if age > limit:
    sys.exit(
        "the Alpine index is signed but stale -- %d days old, limit %d.\n"
        "  A valid signature does not stop a host replaying an old index, which would\n"
        "  hold every build to whatever musl was current then. Check that the branch is\n"
        "  still supported (curl -s https://alpinelinux.org/releases.json), or raise\n"
        "  ALPINE_INDEX_MAX_AGE_DAYS if upstream really has paused." % (age, limit)
    )
print("  freshness : %d days old, limit %d" % (age, limit))
PY
# Unpacked to a file rather than piped: the parser stops at the first match, and
# closing the pipe early makes tar fail the whole script under `set -o pipefail`.
tar xzf "$WORK_DIR/APKINDEX.tar.gz" -C "$WORK_DIR" APKINDEX
MUSL_VERSION=$(awk -v RS='' '/(^|\n)P:musl\n/ { for (i = 1; i <= NF; i++) if ($i ~ /^V:/) { print substr($i, 3); exit } }' "$WORK_DIR/APKINDEX")
# The strongest digest APKINDEX carries: C: is "Q1" + base64(SHA1). SHA1 is
# below today's bar but it is what Alpine publishes for v2 indexes, and a
# stale or truncated mirror file still fails it -- checking it beats trusting
# the transport. Noted as a limitation rather than silently accepted.
MUSL_C1=$(awk -v RS='' '/(^|\n)P:musl\n/ { for (i = 1; i <= NF; i++) if ($i ~ /^C:/) { print substr($i, 3); exit } }' "$WORK_DIR/APKINDEX")

if [ -z "$MUSL_VERSION" ]; then
    echo "  ERROR: no musl package in the $ALPINE_BRANCH index." >&2
    exit 1
fi
echo "  version   : $MUSL_VERSION"

# The notice this ships is a committed file for one musl release, and the loader
# it describes is resolved from a live index. Alpine can move musl inside a
# branch it still supports, and nothing else compares the two: the APK would then
# carry 1.2.5's COPYRIGHT beside a different libc, which is the one way an MIT
# notice goes wrong without any step failing. Checked before the download, so a
# bump costs seconds rather than a full fetch and install.
case "$MUSL_VERSION" in
    1.2.5*) ;;
    *)
        echo "  ERROR: $ALPINE_BRANCH now carries musl $MUSL_VERSION, and" >&2
        echo "         licenses/COPYRIGHT.musl is musl 1.2.5's COPYRIGHT." >&2
        echo "         Re-take the notice from git.musl-libc.org at the matching" >&2
        echo "         tag and update this check, or pin ALPINE_BRANCH to a" >&2
        echo "         branch still on 1.2.5. The loader is the libc the Claude" >&2
        echo "         Code CLI runs against, so a libc bump wants a device." >&2
        exit 1
        ;;
esac

APK="$WORK_DIR/musl-$MUSL_VERSION.apk"
if [ ! -f "$APK" ]; then
    curl -sL --fail --show-error -o "$APK" "$MIRROR/musl-$MUSL_VERSION.apk"
fi
if [ -z "$MUSL_C1" ]; then
    # Fail closed: a missing C: field would otherwise switch verification off,
    # and the index is now authenticated, so an absent field is an anomaly
    # rather than an older index format.
    echo "  ERROR: index carries no checksum for musl" >&2
    exit 1
fi

# Two links, and both are needed. An .apk is three concatenated gzip streams --
# signature, control, data -- and the index's C: is the SHA1 of the CONTROL
# stream alone. It does not cover the loader bytes at all, so on its own it
# authenticates the package's metadata while leaving the payload free. The
# control stream's .PKGINFO carries `datahash`, the SHA-256 of the data stream,
# which is the link that reaches the file actually extracted below.
if ! python3 - "$APK" "$MUSL_C1" <<'PY'
import base64, hashlib, io, sys, tarfile, zlib

apk, expected_c1 = sys.argv[1], sys.argv[2]
data = open(apk, "rb").read()

bounds, i = [], 0
for _ in range(3):
    d = zlib.decompressobj(31)
    d.decompress(data[i:])
    end = len(data) - len(d.unused_data)
    bounds.append((i, end))
    i = end

control = data[bounds[0][1]:bounds[1][1]]
payload = data[bounds[1][1]:bounds[2][1]]

actual_c1 = "Q1" + base64.b64encode(hashlib.sha1(control).digest()).decode()
if actual_c1 != expected_c1:
    sys.exit("control stream does not match the index\n  index: %s\n  file : %s"
             % (expected_c1, actual_c1))

pkginfo = tarfile.open(fileobj=io.BytesIO(control)).extractfile(".PKGINFO").read().decode()
datahash = next(
    (line.split("=", 1)[1].strip() for line in pkginfo.splitlines()
     if line.startswith("datahash")),
    None,
)
if not datahash:
    # Fail closed for the same reason as an absent C:. Every real package
    # carries this; its absence means the chain cannot be walked, not that it
    # may be skipped.
    sys.exit("no datahash in .PKGINFO; the payload cannot be verified")

actual = hashlib.sha256(payload).hexdigest()
if actual != datahash:
    sys.exit("payload does not match the datahash the signed metadata names\n"
             "  .PKGINFO: %s\n  file    : %s" % (datahash, actual))

print("  control   : matches the signed index (SHA1, what APKINDEX carries)")
print("  payload   : matches .PKGINFO datahash (SHA-256)")
PY
then
    echo "  ERROR: musl-$MUSL_VERSION.apk failed verification" >&2
    rm -f "$APK"
    exit 1
fi

# An .apk is a gzipped tar. In musl the loader and libc are one file, so this is
# the only artifact needed -- it satisfies its own DT_NEEDED and runs with no
# LD_LIBRARY_PATH, which was verified on device.
rm -rf "$WORK_DIR/extract"
mkdir -p "$WORK_DIR/extract"
tar xzf "$APK" -C "$WORK_DIR/extract" lib/ld-musl-aarch64.so.1 2>/dev/null

SRC="$WORK_DIR/extract/lib/ld-musl-aarch64.so.1"
if [ ! -f "$SRC" ]; then
    echo "  ERROR: lib/ld-musl-aarch64.so.1 is not in musl-$MUSL_VERSION.apk." >&2
    exit 1
fi

# Named lib*.so so the Package Manager extracts it to nativeLibraryDir with the
# execute bit; that directory is the only one an app may execve from.
cp "$SRC" "$JNI_DIR/libldmusl.so"
chmod 755 "$JNI_DIR/libldmusl.so"
echo "  installed : jniLibs/arm64-v8a/libldmusl.so ($(du -h "$JNI_DIR/libldmusl.so" | cut -f1))"

python3 "$SCRIPT_DIR/verify-android-elf.py" "$JNI_DIR/libldmusl.so"

# musl is MIT, and MIT requires the copyright and permission notice to be
# included with a binary redistribution. Alpine's musl .apk carries no notice at
# all -- measured on musl-1.2.5-r3, whose three entries are the loader, the libc
# symlink and the package metadata -- so the file comes from the repository
# instead: licenses/COPYRIGHT.musl is musl 1.2.5's own COPYRIGHT, verbatim from
# git.musl-libc.org at tag v1.2.5, which is the release ALPINE_BRANCH pins.
#
# It lands under assets/usr/share/doc beside the Termux notices, which
# download-termux-tools.sh clears and fills; this script runs after it in
# build-all.sh and in both workflows, the same ordering everything else in that
# directory already depends on.
MUSL_NOTICE="$ROOT_DIR/licenses/COPYRIGHT.musl"
if [ ! -f "$MUSL_NOTICE" ]; then
    echo "  ERROR: $MUSL_NOTICE is missing; the loader would ship with no notice." >&2
    exit 1
fi
MUSL_DOC="$ROOT_DIR/android/app/src/main/assets/usr/share/doc/musl"
mkdir -p "$MUSL_DOC"
cp "$MUSL_NOTICE" "$MUSL_DOC/COPYRIGHT"
echo "  notice    : usr/share/doc/musl/COPYRIGHT"

# What shipped, for write-build-manifest.py, the same way each Termux download
# records itself in a resolved-*.tsv. The version is resolved from a live index
# at build time rather than pinned, so with no record a release cannot afterwards
# be asked which musl loader is inside it -- and this was the only bundled
# download whose provenance the manifest could not state.
#
# Written last on purpose: after the install and after the ELF gate, so a run
# that failed either leaves no record claiming success. The file is emptied at
# the top of the script, so "no record" is the state a failure actually lands in.
#
# The digest goes through a variable rather than straight into printf's argument
# list. A command substitution that fails inside an argument does not set the
# surrounding command's status, so set -e would not fire and printf would happily
# write a two-field line; in an assignment the substitution's status is what set -e
# tests.
#
# What actually makes that status non-zero here is `set -o pipefail` on line 2, not
# the assignment on its own -- the substitution wraps a PIPELINE, and a pipeline's
# status is its rightmost command's, which is `cut`, and cut exits 0 on empty
# input. Measured on a host with neither hasher: with `set -euo pipefail` the run
# stops, rc=127; with plain `set -eu` it writes `musl<TAB>1.2.5-r3<TAB>` and carries
# on. Both lines are load-bearing, so do not "simplify" pipefail away.
MUSL_SHA="$( (sha256sum "$JNI_DIR/libldmusl.so" 2>/dev/null \
              || shasum -a 256 "$JNI_DIR/libldmusl.so") | cut -d' ' -f1)"
printf '%s\t%s\t%s\n' musl "$MUSL_VERSION" "$MUSL_SHA" \
    > "$WORK_DIR/resolved-musl.tsv"

echo ""
echo "=== musl loader ready ==="
echo "  claudeCode.claudeProcessWrapper must point at this file; FirstRunSetup writes it."
