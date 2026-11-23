package com.vscodroid.setup

import java.math.BigInteger
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECFieldFp
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Verifies detached ECDSA P-256 (`SHA256withECDSA`) signatures over the raw wire bytes of
 * `toolchains.sha256` for sideloaded toolchain installs.
 *
 * Uses only platform JCA primitives available on Android API 29-37 and OpenJDK, matching
 * `openssl dgst -sha256 -sign` over a `prime256v1` (`secp256r1`) keypair.
 *
 * Key provisioning workflow:
 *   1. Generate an offline ECDSA P-256 keypair with `scripts/sign-toolchain-manifest.sh --generate-key`.
 *   2. Store the private key PEM in GitHub Actions secret `TOOLCHAIN_SIGNING_KEY_PEM`.
 *   3. Paste the printed SPKI Base64 string into [TOOLCHAIN_MANIFEST_PUBLIC_KEY_SPKI_BASE64].
 *
 * Once [TOOLCHAIN_MANIFEST_PUBLIC_KEY_SPKI_BASE64] is populated, [requireSignedManifest] defaults
 * to true and sideloaded installs fail closed with [ToolchainFailure.DIGEST] on any missing,
 * truncated, oversized, or invalid `toolchains.sha256.sig`. Until the maintainer provisions a
 * production key, unprovisioned local/unsigned builds have a null [manifestPublicKeySpki] and
 * [requireSignedManifest] equal to false, falling back to HTTPS-pinned SHA-256 verification.
 */
internal object ToolchainManifestVerifier {

    /** Upper bound on a DER-encoded ECDSA P-256 signature (typically 70-72 bytes). */
    internal const val MAX_SIGNATURE_BYTES = 512

    /** Upper bound on raw `toolchains.sha256` manifest bytes read into memory. */
    internal const val MAX_MANIFEST_BYTES = 64 * 1024

    /**
     * Base64-encoded SubjectPublicKeyInfo (DER) of the release ECDSA P-256 signing public key.
     *
     * Paired with the `TOOLCHAIN_SIGNING_KEY_PEM` repository secret used by `release.yml`.
     */
    internal const val TOOLCHAIN_MANIFEST_PUBLIC_KEY_SPKI_BASE64: String = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE54dlfdBYykq9VleWPBftxqUhznXN2ZsdDelAYHSbPcuDJUGifUvjHoFsQHwMDL/jZ5w7KC73t/TvBW37O7yRrQ=="

    // Full NIST P-256 (FIPS 186-4 / SEC 2 secp256r1) domain parameters.
    private val P256_PRIME =
        BigInteger("ffffffff00000001000000000000000000000000ffffffffffffffffffffffff", 16)
    private val P256_A =
        BigInteger("ffffffff00000001000000000000000000000000fffffffffffffffffffffffc", 16)
    private val P256_B =
        BigInteger("5ac635d8aa3a93e7b3ebbd55769886bc651d06b0cc53b0f63bce3c3e27d2604b", 16)
    private val P256_GX =
        BigInteger("6b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296", 16)
    private val P256_GY =
        BigInteger("4fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5", 16)
    private val P256_ORDER =
        BigInteger("ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551", 16)

    /** True when the build embeds a non-blank signing public key constant. */
    internal val hasEmbeddedPublicKey: Boolean
        get() = TOOLCHAIN_MANIFEST_PUBLIC_KEY_SPKI_BASE64.isNotBlank()

    /**
     * Active DER-encoded SubjectPublicKeyInfo public key used to verify `toolchains.sha256.sig`.
     *
     * Mutable internally so unit tests can inject ephemeral P-256 test keys without modifying
     * production constants.
     */
    @Volatile
    internal var manifestPublicKeySpki: ByteArray? =
        decodePublicKeySpki(TOOLCHAIN_MANIFEST_PUBLIC_KEY_SPKI_BASE64)

    /**
     * When true, sideloaded toolchain installs refuse any manifest that lacks a valid P-256
     * signature, failing closed even if [manifestPublicKeySpki] is null (for example if an
     * embedded key constant was malformed).
     */
    @Volatile
    internal var requireSignedManifest: Boolean = hasEmbeddedPublicKey

    /**
     * Decodes a Base64-encoded DER SubjectPublicKeyInfo string, ignoring ASCII whitespace.
     * Returns null when [base64] is blank or not valid Base64.
     */
    internal fun decodePublicKeySpki(base64: String): ByteArray? {
        val stripped = base64.filterNot { it.isWhitespace() }
        if (stripped.isEmpty()) return null
        return try {
            Base64.getDecoder().decode(stripped).takeIf { it.isNotEmpty() }
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /**
     * Verifies [signatureBytes] (canonical DER `SHA256withECDSA`) over exact wire [manifestBytes]
     * using [publicKeySpki] (DER SubjectPublicKeyInfo for NIST P-256 / `secp256r1`).
     *
     * Fails closed (returns false) on empty, oversized, or trailing-byte-padded signatures,
     * non-P-256 keys, malformed DER structures, or any cryptographic exception.
     */
    internal fun verify(
        manifestBytes: ByteArray,
        signatureBytes: ByteArray,
        publicKeySpki: ByteArray,
    ): Boolean {
        if (signatureBytes.isEmpty() || signatureBytes.size > MAX_SIGNATURE_BYTES) return false
        if (publicKeySpki.isEmpty()) return false
        if (!isExactDerEcdsaSignature(signatureBytes)) return false
        return try {
            val keyFactory = KeyFactory.getInstance("EC")
            val publicKey = keyFactory.generatePublic(X509EncodedKeySpec(publicKeySpki))
            if (!isP256Key(publicKey)) return false
            val verifier = Signature.getInstance("SHA256withECDSA")
            verifier.initVerify(publicKey)
            verifier.update(manifestBytes)
            verifier.verify(signatureBytes)
        } catch (_: GeneralSecurityException) {
            false
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    /**
     * Validates that [sig] is a single DER `SEQUENCE { INTEGER r, INTEGER s }` spanning the
     * entire byte array with canonical lengths and no trailing bytes.
     */
    private fun isExactDerEcdsaSignature(sig: ByteArray): Boolean {
        if (sig.size < 8 || sig[0] != 0x30.toByte()) return false
        val seqLenByte = sig[1].toInt() and 0xFF
        val seqLen: Int
        var pos: Int
        if (seqLenByte < 0x80) {
            seqLen = seqLenByte
            pos = 2
        } else if (seqLenByte == 0x81) {
            if (sig.size < 4) return false
            seqLen = sig[2].toInt() and 0xFF
            if (seqLen < 0x80) return false
            pos = 3
        } else {
            return false
        }
        if (pos + seqLen != sig.size) return false
        repeat(2) {
            if (pos + 2 > sig.size || sig[pos] != 0x02.toByte()) return false
            val intLen = sig[pos + 1].toInt() and 0xFF
            if (intLen == 0 || intLen >= 0x80 || pos + 2 + intLen > sig.size) return false
            val first = sig[pos + 2].toInt() and 0xFF
            if ((first and 0x80) != 0) return false
            if (intLen > 1 && first == 0x00 && (sig[pos + 3].toInt() and 0x80) == 0) return false
            pos += 2 + intLen
        }
        return pos == sig.size
    }

    private fun isP256Key(key: PublicKey): Boolean {
        val ecKey = key as? ECPublicKey ?: return false
        val params = ecKey.params ?: return false
        val curve = params.curve ?: return false
        val field = curve.field as? ECFieldFp ?: return false
        val generator = params.generator ?: return false
        return field.p == P256_PRIME &&
            curve.a == P256_A &&
            curve.b == P256_B &&
            generator.affineX == P256_GX &&
            generator.affineY == P256_GY &&
            params.order == P256_ORDER &&
            params.cofactor == 1
    }
}
