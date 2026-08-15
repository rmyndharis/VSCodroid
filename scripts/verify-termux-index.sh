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
# a signature alone does not stop a host serving last year's index for ever and
# holding every build to packages with known holes. Date is present, and the
# live index was under a day old, so a month is far outside the normal
# publishing cadence while still leaving room for an upstream pause.
MAX_INDEX_AGE_DAYS="${TERMUX_INDEX_MAX_AGE_DAYS:-30}"

# Both tools are required up front rather than where they happen to be used, and
# the freshness limit is validated rather than compared as it arrives. All three
# failed the same way otherwise -- the age check would vanish and the script
# would still exit 0, reporting a verified index it had never dated. Measured:
# with python3 off PATH, and with the limit set to a non-number, exactly that
# happened. A gate that disappears on bad input is worse than an absent one,
# because its output still says the check ran.
for tool in gpg python3; do
    command -v "$tool" >/dev/null 2>&1 && continue
    cat >&2 <<EOF

  $tool is required to verify the Termux package index and was not found.

      macOS   brew install gnupg python3
      Debian  apt-get install -y gnupg python3

  The index names the digest of every binary that ends up in the APK, so it is
  not checked on trust.
EOF
    exit 1
done

# The empty case below cannot be reached while the default above uses :- , which
# substitutes for unset *and* empty. It is written anyway so that changing :- to
# :  later cannot quietly turn an empty value into a skipped comparison.
case "$MAX_INDEX_AGE_DAYS" in
    ''|*[!0-9]*)
        echo "  ERROR: TERMUX_INDEX_MAX_AGE_DAYS must be a whole number of days," \
             "got '$MAX_INDEX_AGE_DAYS'" >&2
        exit 1
        ;;
esac

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
gpg --batch --quiet --import "$KEY_FILE"

# Returns 0 when the local index matches the signed one, 1 when it does not.
# Anything a refetch cannot fix exits from inside instead of returning: a wrong
# signing key or a month-old InRelease is a verdict, not a transient.
check_index() {
    # Tested explicitly rather than left to set -e. This function is called from
    # an `if !`, which disables set -e for everything inside it, so a failed
    # download fell through to gpg and reported "the signature did not verify"
    # for a file that was never fetched -- measured against a 404, and it points
    # the reader at the signing key when the problem is the URL.
    if ! curl -sL --fail --show-error -o "$TMP/InRelease" "$INRELEASE_URL"; then
        echo "  ERROR: could not download $INRELEASE_URL" >&2
        echo "         The index cannot be checked without it. Try another" >&2
        echo "         mirror with TERMUX_MIRROR." >&2
        exit 1
    fi

    # --decrypt, not --verify: it writes out the signed payload and nothing
    # else, so the digest is read from bytes the signature covers. Parsing the
    # file as downloaded would also read anything appended after the signature.
    if ! gpg --batch --status-fd 3 --decrypt "$TMP/InRelease" \
            3>"$TMP/status" 2>/dev/null > "$TMP/payload"; then
        echo "  ERROR: the InRelease signature at $INRELEASE_URL did not verify" >&2
        sed 's/^/    /' "$TMP/status" >&2
        exit 1
    fi

    # The exit code above is not enough on its own. gpg is happy with any key in
    # the keyring, so a check that stopped there would keep passing if a second
    # key were ever imported here. VALIDSIG names the key that actually signed.
    local signer
    signer="$(awk '/VALIDSIG/{print $3; exit}' "$TMP/status")"
    if [ "$signer" != "$PINNED_FPR" ]; then
        cat >&2 <<EOF

  The Termux index was signed by a key this repository does not pin.

      signed by $signer
      pinned    $PINNED_FPR

  Either the repository rotated its signing key, or this index is not
  Termux's. Confirm the new fingerprint against a source that is not the
  mirror serving the packages -- keyserver.ubuntu.com and
  github.com/termux/termux-packages both publish it -- before changing
  PINNED_FPR in this script.
EOF
        exit 1
    fi

    local index_date age_days
    index_date="$(awk -F': ' '/^Date: /{print $2; exit}' "$TMP/payload")"
    if [ -z "$index_date" ]; then
        # Fail closed, for the reason the download scripts fail closed on a
        # package with no SHA256 in the index: every real InRelease carries a
        # Date, so its absence is an anomaly rather than permission to skip the
        # only freshness signal there is.
        echo "  ERROR: the signed InRelease carries no Date, so its age cannot be" >&2
        echo "         judged: $INRELEASE_URL" >&2
        exit 1
    fi

    # python3 rather than date(1): parsing RFC 2822 needs -d on GNU and -j -f on
    # BSD, and this runs on both. A parse that fails is fatal, not a skip -- an
    # earlier `|| echo ""` here turned an unreadable date into a silently
    # unchecked one, the same disappearing gate as a missing python3.
    if ! age_days="$(python3 -c '
import email.utils, sys, time
t = email.utils.parsedate_to_datetime(sys.argv[1]).timestamp()
print(int((time.time() - t) // 86400))
' "$index_date" 2>/dev/null)"; then
        echo "  ERROR: could not read the InRelease date '$index_date' from" >&2
        echo "         $INRELEASE_URL" >&2
        exit 1
    fi
    if [ "$age_days" -gt "$MAX_INDEX_AGE_DAYS" ]; then
        cat >&2 <<EOF

  The Termux index is signed but stale -- $age_days days old, limit $MAX_INDEX_AGE_DAYS.

      $INRELEASE_URL
      dated $index_date

  A valid signature does not stop a host replaying an old index, which would
  hold every build to whatever was current then. Try another mirror with
  TERMUX_MIRROR, or raise TERMUX_INDEX_MAX_AGE_DAYS if upstream really has
  paused.
EOF
        exit 1
    fi

    # Read only inside the SHA256 block. Every path in this file appears four
    # times -- MD5Sum, SHA1, SHA256, SHA512 -- so a scan matching on the path
    # alone would take whichever block it reached first and compare a SHA256
    # against an MD5. Any unindented line starts a new block.
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
    [ "$ACTUAL" = "$EXPECTED" ]
}

# A stale index is the ordinary case here, not an attack: callers keep one for
# sixty minutes, CI restores one under restore-keys that matches almost any
# earlier build, and upstream publishes daily -- so a cached index usually will
# not match the signature current now. Measured while building this: a real run
# refused a cached index three days behind, which as a hard failure would have
# broken every CI run that got a cache hit. So a mismatch refetches the index
# and checks again, against a freshly fetched InRelease too, in case upstream
# published between the two. Only the second failure is a failure.
if ! check_index; then
    echo "  index   : does not match the current signature - refetching"
    curl -sL --fail --show-error -o "$PACKAGES_FILE" "$PACKAGES_URL"
    if ! check_index; then
        cat >&2 <<EOF

  The package index does not match the signed InRelease, after refetching it.

      file     $PACKAGES_FILE
      entry    $ENTRY
      got      $ACTUAL
      expected $EXPECTED

  Every digest the download scripts trust comes out of this file, so it is not
  used. A freshly downloaded index failing this means the host is serving an
  index upstream did not sign -- try another with TERMUX_MIRROR.
EOF
        rm -f "$PACKAGES_FILE"
        exit 1
    fi
fi

echo "  index   : signed by $PINNED_FPR, $ENTRY matches"
