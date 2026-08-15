#!/usr/bin/env bash
set -euo pipefail

# Verifies a downloaded Termux Packages index against the repository's signed
# InRelease, so that everything the download scripts derive from that index --
# filenames and SHA256 digests for binaries that end up as libnode.so,
# libbash.so, libgit.so and libpython*.so on user devices -- rests on a
# signature rather than on the good behaviour of one host.
#
#   scripts/verify-termux-index.sh <packages_url> <local_packages_file>
#
# The gap this closes: every download script reads a package's SHA256 out of
# the index, then checks the .deb against it. Index and .deb come from the same
# host, so a host serving a backdoored node plus a matching digest passed every
# check this repository had. Measured 2026-08-15: grep for gpg/InRelease across
# scripts/ returned nothing.
#
# The chain, all four links measured against the live repository before this
# was written:
#
#   pinned key CC72CF8B...E57CF20C   scripts/termux-repo-key.asc
#     -> signs dists/<suite>/InRelease
#          -> which carries SHA256 of main/binary-aarch64/Packages
#               -> which carries SHA256 of every .deb   (already checked by the
#                                                        download scripts)
#
# Why the third-party mirror can stay. TERMUX_MIRROR defaults to a mirror
# because packages.termux.dev is frequently down, and that was a real tradeoff
# while trust came from the host. It stops being one here: the signature is
# upstream's, byte-identical on both hosts (14016 bytes on each, measured), and
# a mirror cannot forge it. Choosing a mirror becomes an availability decision
# with no security content.

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

PACKAGES_URL="${1:?usage: verify-termux-index.sh <packages_url> <local_packages_file>}"
PACKAGES_FILE="${2:?usage: verify-termux-index.sh <packages_url> <local_packages_file>}"

KEY_FILE="$ROOT_DIR/scripts/termux-repo-key.asc"

# The pin. A public key is self-verifying against its fingerprint, so what
# matters is not where the bytes came from but that this value was reviewed.
# Cross-checked against two parties that are not the mirror serving the
# payload, and against the signature on the live InRelease -- all three agreed
# (2026-08-15):
#
#   keyserver.ubuntu.com                          CC72CF8B...E57CF20C
#   github.com/termux/termux-packages             CC72CF8B...E57CF20C
#   the signature on dists/stable/InRelease       CC72CF8B...E57CF20C
#
# The key carries no expiry date, so this does not become a scheduled build
# failure. If Termux ever rotates, this fails loudly with the new fingerprint
# printed, which is the intended way to learn about it.
PINNED_FPR="CC72CF8BA7DBFA0182877D045A897D96E57CF20C"

# InRelease has no Valid-Until (measured -- it carries Origin, Label, Suite,
# Codename, Date, Architectures, Components, Description and nothing else), so
# a signature alone does not stop a host from serving last year's index for
# ever and pinning every build to packages with known holes. Date is present,
# and the live index was under a day old, so a month is far outside the normal
# publishing cadence while still leaving room for an upstream pause.
MAX_INDEX_AGE_DAYS="${TERMUX_INDEX_MAX_AGE_DAYS:-30}"

if ! command -v gpg >/dev/null 2>&1; then
    cat >&2 <<EOF

  gpg is required to verify the Termux package index and was not found.

      macOS   brew install gnupg
      Debian  apt-get install -y gnupg

  The index names the digest of every binary that ends up in the APK, so it is
  not checked on trust.
EOF
    exit 1
fi

[ -f "$KEY_FILE" ] || { echo "  ERROR: pinned key missing: $KEY_FILE" >&2; exit 1; }
[ -f "$PACKAGES_FILE" ] || { echo "  ERROR: no index to verify: $PACKAGES_FILE" >&2; exit 1; }

# Split at the LAST /dists/ so the suite and the entry name are derived from the
# caller's URL rather than hardcoded. A hardcoded main/binary-aarch64/Packages
# would keep verifying that entry after a script moved to another architecture,
# and would report success for an index it had not checked.
BASE_URL="${PACKAGES_URL%/dists/*}"
REST="${PACKAGES_URL##*/dists/}"
SUITE="${REST%%/*}"
ENTRY="${REST#*/}"
INRELEASE_URL="$BASE_URL/dists/$SUITE/InRelease"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

# A keyring of our own. Importing into the caller's would both pollute it and
# make the check depend on whatever else it already trusts.
export GNUPGHOME="$TMP/gnupg"
mkdir -p "$GNUPGHOME"
chmod 700 "$GNUPGHOME"

curl -sL --fail --show-error -o "$TMP/InRelease" "$INRELEASE_URL"
gpg --batch --quiet --import "$KEY_FILE"

# --decrypt, not --verify: it writes out the signed payload and nothing else, so
# the digest is read from bytes the signature covers. Parsing the file as
# downloaded would also read anything appended after the signature block.
if ! gpg --batch --status-fd 3 --decrypt "$TMP/InRelease" \
        3>"$TMP/status" 2>/dev/null > "$TMP/payload"; then
    echo "  ERROR: the InRelease signature at $INRELEASE_URL did not verify" >&2
    sed 's/^/    /' "$TMP/status" >&2
    exit 1
fi

# The exit code above is not enough on its own. gpg is happy with any key in the
# keyring, so a check that stopped there would keep passing if a second key were
# ever imported here. VALIDSIG names the key that actually signed.
SIGNER_FPR="$(awk '/VALIDSIG/{print $3; exit}' "$TMP/status")"
if [ "$SIGNER_FPR" != "$PINNED_FPR" ]; then
    cat >&2 <<EOF

  The Termux index was signed by a key this repository does not pin.

      signed by $SIGNER_FPR
      pinned    $PINNED_FPR

  Either the repository rotated its signing key, or this index is not
  Termux's. Confirm the new fingerprint against a source that is not the
  mirror serving the packages -- keyserver.ubuntu.com and
  github.com/termux/termux-packages both publish it -- before changing
  PINNED_FPR in this script.
EOF
    exit 1
fi

INDEX_DATE="$(awk -F': ' '/^Date: /{print $2; exit}' "$TMP/payload")"
if [ -n "$INDEX_DATE" ]; then
    # python3 rather than date(1): parsing RFC 2822 needs -d on GNU and -j -f on
    # BSD, and this script runs on both. python3 is already required by the
    # verify-*.py gates either side of this one.
    AGE_DAYS="$(python3 -c '
import email.utils, sys, time
t = email.utils.parsedate_to_datetime(sys.argv[1]).timestamp()
print(int((time.time() - t) // 86400))
' "$INDEX_DATE" 2>/dev/null || echo "")"
    if [ -n "$AGE_DAYS" ] && [ "$AGE_DAYS" -gt "$MAX_INDEX_AGE_DAYS" ]; then
        cat >&2 <<EOF

  The Termux index is signed but stale -- $AGE_DAYS days old, limit $MAX_INDEX_AGE_DAYS.

      $INRELEASE_URL
      dated $INDEX_DATE

  A valid signature does not stop a host replaying an old index, which would
  hold every build to whatever was current then. Try another mirror with
  TERMUX_MIRROR, or raise TERMUX_INDEX_MAX_AGE_DAYS if upstream really has
  paused.
EOF
        exit 1
    fi
fi

# Read only inside the SHA256 block. Every path in this file appears four times
# -- MD5Sum, SHA1, SHA256, SHA512 -- so a scan that matched on the path alone
# would take whichever block it reached first and compare a SHA256 against an
# MD5. Any unindented line starts a new block.
EXPECTED="$(awk -v e="$ENTRY" '
    /^SHA256:/ { inblock = 1; next }
    /^[A-Za-z]/ { inblock = 0 }
    inblock && $3 == e { print $1; exit }
' "$TMP/payload")"

if [ -z "$EXPECTED" ]; then
    echo "  ERROR: signed InRelease carries no SHA256 for $ENTRY" >&2
    echo "         $INRELEASE_URL" >&2
    exit 1
fi

ACTUAL="$( (sha256sum "$PACKAGES_FILE" 2>/dev/null || shasum -a 256 "$PACKAGES_FILE") | cut -d' ' -f1)"
if [ "$ACTUAL" != "$EXPECTED" ]; then
    cat >&2 <<EOF

  The package index does not match the signed InRelease.

      file     $PACKAGES_FILE
      entry    $ENTRY
      got      $ACTUAL
      expected $EXPECTED

  Every digest the download scripts trust comes out of this file. Delete it and
  run again; if it keeps failing, the mirror is serving an index that upstream
  did not sign.
EOF
    rm -f "$PACKAGES_FILE"
    exit 1
fi

echo "  index   : signed by $PINNED_FPR, $ENTRY matches"
