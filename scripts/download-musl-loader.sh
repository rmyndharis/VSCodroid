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

ALPINE_BRANCH="${ALPINE_BRANCH:-v3.20}"
MIRROR="https://dl-cdn.alpinelinux.org/alpine/$ALPINE_BRANCH/main/aarch64"

echo "=== musl loader ==="
mkdir -p "$WORK_DIR" "$JNI_DIR"

# Truncated at the start of the run, as five of the six other resolved-*.tsv
# writers do -- download-python.sh, download-termux-tools.sh and the three
# toolchain scripts. Check rather than take it on trust, since it is one grep:
#     grep -n ': > "$PKG_MAP_FILE"' scripts/download-*.sh
# download-node.sh is the exception, and not a harmless one: it writes its record
# before the digest of what it resolved has been checked, so a run that fails
# there leaves a record naming a version that never reached jniLibs. That is the
# failure this line exists to avoid, which is why the count matters more than the
# generalisation this comment used to make.
#
# Sharper reason here than in any of them: the loader is installed well before
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
# write a two-field line; in an assignment the substitution's status IS the
# command's, so a host with neither sha256sum nor shasum stops here instead of
# recording a version with no digest beside it.
MUSL_SHA="$( (sha256sum "$JNI_DIR/libldmusl.so" 2>/dev/null \
              || shasum -a 256 "$JNI_DIR/libldmusl.so") | cut -d' ' -f1)"
printf '%s\t%s\t%s\n' musl "$MUSL_VERSION" "$MUSL_SHA" \
    > "$WORK_DIR/resolved-musl.tsv"

echo ""
echo "=== musl loader ready ==="
echo "  claudeCode.claudeProcessWrapper must point at this file; FirstRunSetup writes it."
