#!/usr/bin/env bash
# Generate, sign, verify, and self-test ECDSA P-256 signatures for toolchains.sha256.
#
# Usage:
#   scripts/sign-toolchain-manifest.sh --generate-key <private-key.pem> [--public-out <public-key.pem>] [--force]
#   scripts/sign-toolchain-manifest.sh --sign <private-key.pem> <manifest-file> [signature-out]
#   scripts/sign-toolchain-manifest.sh --verify <public-key-pem-or-spki-base64> <manifest-file> <signature-file>
#   scripts/sign-toolchain-manifest.sh --self-test
#
# Uses standard OpenSSL primitives (prime256v1 / secp256r1, SHA-256 DER ECDSA) compatible with
# Android's JCA SHA256withECDSA verifier in ToolchainManifestVerifier.kt.
set -euo pipefail

# Exact 27-byte ASN.1 DER SubjectPublicKeyInfo prefix for an uncompressed NIST P-256 public key
# (SEQUENCE 89, AlgorithmIdentifier id-ecPublicKey + prime256v1, BIT STRING 66, 0x04 uncompressed).
readonly P256_SPKI_DER_LEN=91
readonly P256_SPKI_DER_PREFIX_HEX="3059301306072a8648ce3d020106082a8648ce3d03010703420004"

CLEANUP_DIRS=()
cleanup() {
    local d
    for d in "${CLEANUP_DIRS[@]+"${CLEANUP_DIRS[@]}"}"; do
        [ -n "$d" ] && rm -rf "$d"
    done
}
trap cleanup EXIT

# Creates a temporary directory and registers it for cleanup in the caller's shell.
# Must be called directly (`make_temp_dir var_name`), never inside a command substitution.
make_temp_dir() {
    local __var="$1"
    local __dir
    __dir="$(mktemp -d)"
    CLEANUP_DIRS+=("$__dir")
    printf -v "$__var" '%s' "$__dir"
}

usage() {
    sed -n '2,/^[^#]/{ /^#/s/^# \{0,1\}//p; }' "$0"
}

die() {
    echo "error: $*" >&2
    exit 1
}

require_openssl() {
    command -v openssl >/dev/null 2>&1 || die "openssl is required but not installed"
}

validate_p256_spki_der() {
    local der_file="$1"
    local label="${2:-public key}"
    [ -f "$der_file" ] || die "$label DER file missing"

    local size prefix
    size="$(wc -c < "$der_file" | tr -d '[:space:]')"
    if [ "$size" -ne "$P256_SPKI_DER_LEN" ]; then
        die "$label is not an uncompressed NIST P-256 SPKI key (expected $P256_SPKI_DER_LEN bytes, got $size)"
    fi

    prefix="$(head -c 27 "$der_file" | od -An -tx1 | tr -d ' \t\r\n' | tr 'A-F' 'a-f')"
    if [ "$prefix" != "$P256_SPKI_DER_PREFIX_HEX" ]; then
        die "$label is not a NIST P-256 (prime256v1 / secp256r1) public key"
    fi

    openssl pkey -pubin -inform DER -in "$der_file" -noout >/dev/null 2>&1 \
        || die "$label failed OpenSSL public key validation"
}

validate_der_ecdsa_signature_framing() {
    local sig_file="$1"
    local size tag len_hex seq_len
    size="$(wc -c < "$sig_file" | tr -d '[:space:]')"
    if [ "$size" -lt 8 ] || [ "$size" -gt 512 ]; then
        die "invalid DER ECDSA signature size: $size bytes"
    fi
    tag="$(head -c 1 "$sig_file" | od -An -tx1 | tr -d ' \t\r\n' | tr 'A-F' 'a-f')"
    if [ "$tag" != "30" ]; then
        die "signature is not a DER SEQUENCE"
    fi
    len_hex="$(dd if="$sig_file" bs=1 skip=1 count=1 2>/dev/null | od -An -tx1 | tr -d ' \t\r\n')"
    seq_len=$(( 16#$len_hex ))
    if [ "$seq_len" -ge 128 ] || [ $((seq_len + 2)) -ne "$size" ]; then
        die "signature has invalid DER length or trailing bytes (declared $((seq_len + 2)), actual $size)"
    fi
}

spki_base64_from_key() {
    local key_pem="$1"
    openssl pkey -in "$key_pem" -pubout -outform DER 2>/dev/null | base64 | tr -d '\r\n'
}

cmd_generate_key() {
    local key_out=""
    local pub_out=""
    local force=false

    while [ $# -gt 0 ]; do
        case "$1" in
            --public-out)
                [ $# -ge 2 ] || die "--public-out requires a path"
                pub_out="$2"
                shift 2
                ;;
            --force)
                force=true
                shift
                ;;
            -*)
                die "unknown option for --generate-key: $1"
                ;;
            *)
                if [ -z "$key_out" ]; then
                    key_out="$1"
                    shift
                else
                    die "unexpected argument for --generate-key: $1"
                fi
                ;;
        esac
    done

    [ -n "$key_out" ] || die "--generate-key requires an output path for the private key PEM"
    if [ -e "$key_out" ] && ! $force; then
        die "refusing to overwrite existing key: $key_out (pass --force to replace)"
    fi
    if [ -n "$pub_out" ] && [ -e "$pub_out" ] && ! $force; then
        die "refusing to overwrite existing public key: $pub_out (pass --force to replace)"
    fi

    mkdir -p "$(dirname "$key_out")"
    # Unlink before creating under umask 077 so --force never writes key material into an existing 0644 inode.
    rm -f "$key_out"
    (
        umask 077
        openssl ecparam -name prime256v1 -genkey -noout -out "$key_out"
    )
    chmod 600 "$key_out"

    if [ -n "$pub_out" ]; then
        mkdir -p "$(dirname "$pub_out")"
        rm -f "$pub_out"
        openssl pkey -in "$key_out" -pubout -out "$pub_out"
    fi

    local tmp
    make_temp_dir tmp
    openssl pkey -in "$key_out" -pubout -outform DER -out "$tmp/pub.der"
    validate_p256_spki_der "$tmp/pub.der" "generated key"

    local spki_b64
    spki_b64="$(spki_base64_from_key "$key_out")"
    [ -n "$spki_b64" ] || die "failed to extract SPKI Base64 from generated key"

    echo "Generated ECDSA P-256 private key: $key_out (mode 600)"
    if [ -n "$pub_out" ]; then
        echo "Wrote public key PEM: $pub_out"
    fi
    echo "SPKI Base64 (for TOOLCHAIN_MANIFEST_PUBLIC_KEY_SPKI_BASE64):"
    echo "$spki_b64"
}

cmd_sign() {
    [ $# -ge 2 ] && [ $# -le 3 ] || die "--sign requires <private-key.pem> <manifest-file> [signature-out]"
    local key_pem="$1"
    local manifest="$2"
    local sig_out="${3:-${manifest}.sig}"

    [ -f "$key_pem" ] || die "private key not found: $key_pem"
    [ -f "$manifest" ] || die "manifest file not found: $manifest"

    local tmp
    make_temp_dir tmp

    openssl pkey -in "$key_pem" -pubout -outform DER -out "$tmp/pub.der" 2>/dev/null \
        || die "could not parse private key: $key_pem"
    validate_p256_spki_der "$tmp/pub.der" "signing key"

    mkdir -p "$(dirname "$sig_out")"
    openssl dgst -sha256 -sign "$key_pem" -out "$sig_out" "$manifest"
    validate_der_ecdsa_signature_framing "$sig_out"

    openssl pkey -pubin -inform DER -in "$tmp/pub.der" -out "$tmp/pub.pem"
    openssl dgst -sha256 -verify "$tmp/pub.pem" -signature "$sig_out" "$manifest" >/dev/null

    echo "Signed $manifest -> $sig_out (self-verified OK)"
}

cmd_verify() {
    [ $# -eq 3 ] || die "--verify requires <public-key-pem-or-spki-base64> <manifest-file> <signature-file>"
    local pub_spec="$1"
    local manifest="$2"
    local sig_file="$3"

    [ -f "$manifest" ] || die "manifest file not found: $manifest"
    [ -f "$sig_file" ] || die "signature file not found: $sig_file"

    local tmp
    make_temp_dir tmp

    if [ -f "$pub_spec" ]; then
        if grep -q "BEGIN PUBLIC KEY" "$pub_spec" 2>/dev/null; then
            openssl pkey -pubin -in "$pub_spec" -outform DER -out "$tmp/pub.der" 2>/dev/null \
                || die "could not parse PEM public key: $pub_spec"
        elif grep -q "BEGIN.*PRIVATE KEY" "$pub_spec" 2>/dev/null; then
            openssl pkey -in "$pub_spec" -pubout -outform DER -out "$tmp/pub.der" 2>/dev/null \
                || die "could not parse PEM private key: $pub_spec"
        else
            cp "$pub_spec" "$tmp/pub.der"
        fi
    else
        local stripped
        stripped="$(printf '%s' "$pub_spec" | tr -d '[:space:]')"
        [ -n "$stripped" ] || die "empty public key specification"
        case "$stripped" in
            *[!A-Za-z0-9+/=]*) die "invalid Base64 public key" ;;
        esac
        printf '%s' "$stripped" | openssl base64 -d -A > "$tmp/pub.der" 2>/dev/null \
            || die "invalid Base64 public key"
    fi

    validate_p256_spki_der "$tmp/pub.der" "verification key"
    validate_der_ecdsa_signature_framing "$sig_file"

    openssl pkey -pubin -inform DER -in "$tmp/pub.der" -out "$tmp/pub.pem"
    openssl dgst -sha256 -verify "$tmp/pub.pem" -signature "$sig_file" "$manifest"
}

cmd_self_test() {
    local tmp
    make_temp_dir tmp

    local key_a="$tmp/key-a.pem"
    local pub_a="$tmp/pub-a.pem"
    local key_b="$tmp/key-b.pem"
    local pub_b="$tmp/pub-b.pem"
    local manifest="$tmp/toolchains.sha256"
    local sig="$tmp/sub/toolchains.sha256.sig"

    cmd_generate_key "$key_a" --public-out "$pub_a" >/dev/null
    cmd_generate_key "$key_b" --public-out "$pub_b" >/dev/null

    # --force over an existing 0644 file must recreate a 0600 file.
    local forced_key="$tmp/forced-key.pem"
    printf 'stale\n' > "$forced_key"
    chmod 644 "$forced_key"
    cmd_generate_key "$forced_key" --force >/dev/null
    local forced_mode
    forced_mode="$(ls -ld "$forced_key" | awk '{print substr($1, 1, 10)}')"
    [ "$forced_mode" = "-rw-------" ] || die "self-test: --force key mode was $forced_mode, expected -rw-------"
    echo "  ok     --force unlinks existing file and writes private key with mode 0600"

    local spki_a
    spki_a="$(spki_base64_from_key "$key_a")"
    [ -n "$spki_a" ] || die "self-test: empty SPKI Base64"

    printf '9f2fcc7c90de090d6b87cd7e9718c1ea6cb21118fc2d5de9f97e5db6ac1e9c10  toolchain_ruby.zip\n' > "$manifest"

    cmd_sign "$key_a" "$manifest" "$sig" >/dev/null
    [ -s "$sig" ] || die "self-test: signature file is empty"

    cmd_verify "$pub_a" "$manifest" "$sig" >/dev/null || die "self-test: valid PEM verification failed"
    echo "  ok     valid signature verifies against public PEM"

    cmd_verify "$spki_a" "$manifest" "$sig" >/dev/null || die "self-test: valid SPKI Base64 verification failed"
    echo "  ok     valid signature verifies against SPKI Base64"

    # Tampered manifest must fail.
    local tampered_manifest="$tmp/toolchains-tampered.sha256"
    printf '0f2fcc7c90de090d6b87cd7e9718c1ea6cb21118fc2d5de9f97e5db6ac1e9c10  toolchain_ruby.zip\n' > "$tampered_manifest"
    if bash "$0" --verify "$pub_a" "$tampered_manifest" "$sig" >/dev/null 2>&1; then
        die "self-test: tampered manifest was unexpectedly accepted"
    fi
    echo "  ok     tampered manifest is rejected"

    # Tampered signature byte inside DER body must fail.
    local tampered_sig="$tmp/toolchains-tampered.sig"
    local sig_size last_hex flipped_dec
    sig_size="$(wc -c < "$sig" | tr -d '[:space:]')"
    dd if="$sig" of="$tampered_sig" bs=1 count=$((sig_size - 1)) 2>/dev/null
    last_hex="$(tail -c 1 "$sig" | od -An -tx1 | tr -d '[:space:]')"
    flipped_dec=$(( 0x$last_hex ^ 0x01 ))
    printf "\\$(printf '%03o' "$flipped_dec")" >> "$tampered_sig"
    if bash "$0" --verify "$pub_a" "$manifest" "$tampered_sig" >/dev/null 2>&1; then
        die "self-test: tampered signature was unexpectedly accepted"
    fi
    echo "  ok     tampered signature is rejected"

    # Trailing garbage byte after a valid DER signature must fail.
    local trailing_sig="$tmp/toolchains-trailing.sig"
    cp "$sig" "$trailing_sig"
    printf '\000' >> "$trailing_sig"
    if bash "$0" --verify "$pub_a" "$manifest" "$trailing_sig" >/dev/null 2>&1; then
        die "self-test: signature with trailing garbage byte was unexpectedly accepted"
    fi
    echo "  ok     trailing garbage byte after DER signature is rejected"

    # Wrong P-256 key must fail.
    if bash "$0" --verify "$pub_b" "$manifest" "$sig" >/dev/null 2>&1; then
        die "self-test: signature verified against a different key"
    fi
    echo "  ok     signature is rejected under a different P-256 key"

    # Non-P-256 EC key (secp384r1) must be rejected by both --sign and --verify.
    local key_p384="$tmp/key-p384.pem"
    local pub_p384="$tmp/pub-p384.pem"
    local sig_p384="$tmp/sig-p384.sig"
    openssl ecparam -name secp384r1 -genkey -noout -out "$key_p384" 2>/dev/null
    openssl pkey -in "$key_p384" -pubout -out "$pub_p384" 2>/dev/null
    openssl dgst -sha256 -sign "$key_p384" -out "$sig_p384" "$manifest" 2>/dev/null
    if bash "$0" --sign "$key_p384" "$manifest" "$tmp/out-p384.sig" >/dev/null 2>&1; then
        die "self-test: secp384r1 key was unexpectedly accepted by --sign"
    fi
    if bash "$0" --verify "$pub_p384" "$manifest" "$sig_p384" >/dev/null 2>&1; then
        die "self-test: secp384r1 key was unexpectedly accepted by --verify"
    fi
    echo "  ok     secp384r1 EC key is rejected by --sign and --verify"

    # RSA-2048 key must be rejected by both --sign and --verify.
    local key_rsa="$tmp/key-rsa.pem"
    local pub_rsa="$tmp/pub-rsa.pem"
    local sig_rsa="$tmp/sig-rsa.sig"
    openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$key_rsa" 2>/dev/null
    openssl pkey -in "$key_rsa" -pubout -out "$pub_rsa" 2>/dev/null
    openssl dgst -sha256 -sign "$key_rsa" -out "$sig_rsa" "$manifest" 2>/dev/null
    if bash "$0" --sign "$key_rsa" "$manifest" "$tmp/out-rsa.sig" >/dev/null 2>&1; then
        die "self-test: RSA-2048 key was unexpectedly accepted by --sign"
    fi
    if bash "$0" --verify "$pub_rsa" "$manifest" "$sig_rsa" >/dev/null 2>&1; then
        die "self-test: RSA-2048 key was unexpectedly accepted by --verify"
    fi
    echo "  ok     RSA-2048 key is rejected by --sign and --verify"

    # Invalid Base64 must fail cleanly without leaking platform base64 flag errors.
    local bad_b64_err
    bad_b64_err="$(bash "$0" --verify "%%%not-base64%%%" "$manifest" "$sig" 2>&1 || true)"
    case "$bad_b64_err" in
        *"invalid option"*) die "self-test: invalid Base64 leaked base64 option error: $bad_b64_err" ;;
    esac
    echo "  ok     invalid Base64 fails cleanly across platforms"

    # Sub-invocations (both succeeding and failing) must clean up all temp directories on EXIT.
    local probe_tmp="$tmp/leak-probe"
    mkdir -p "$probe_tmp"
    TMPDIR="$probe_tmp" bash "$0" --generate-key "$tmp/probe-key.pem" --public-out "$tmp/probe-pub.pem" >/dev/null
    TMPDIR="$probe_tmp" bash "$0" --sign "$tmp/probe-key.pem" "$manifest" "$tmp/probe.sig" >/dev/null
    TMPDIR="$probe_tmp" bash "$0" --verify "$tmp/probe-pub.pem" "$manifest" "$tmp/probe.sig" >/dev/null
    TMPDIR="$probe_tmp" bash "$0" --verify "$pub_rsa" "$manifest" "$sig_rsa" >/dev/null 2>&1 || true
    if [ -n "$(ls -A "$probe_tmp")" ]; then
        die "self-test: leaked temp directories in TMPDIR: $(ls -A "$probe_tmp")"
    fi
    echo "  ok     zero temporary directories leaked after --generate-key, --sign, and --verify"
}

main() {
    require_openssl
    [ $# -ge 1 ] || { usage; exit 1; }
    local mode="$1"
    shift
    case "$mode" in
        --generate-key) cmd_generate_key "$@" ;;
        --sign)         cmd_sign "$@" ;;
        --verify)       cmd_verify "$@" ;;
        --self-test)    cmd_self_test "$@" ;;
        -h|--help)      usage ;;
        *)              die "unknown mode: $mode (use --help)" ;;
    esac
}

main "$@"
