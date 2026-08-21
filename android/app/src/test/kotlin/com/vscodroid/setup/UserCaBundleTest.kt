package com.vscodroid.setup

import android.content.Context
import com.vscodroid.util.Logger
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.security.PublicKey
import java.security.cert.Certificate
import java.security.cert.CertificateEncodingException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64

/**
 * Pins what [FirstRunSetup.setupGitCaBundle] puts in git's certificate bundle,
 * and, more sharply, when it agrees to look again.
 *
 * The bundle used to be system roots only, rebuilt whenever the system store's
 * directory was newer than the file. Folding in the CAs the device owner
 * installed for themselves breaks that test, because installing a CA through
 * Settings writes into a store of its own and leaves the system directory's
 * mtime exactly where it was. So the launch after an install is precisely a
 * launch on which the cheap check says "fresh" and the answer is wrong, and a
 * naive implementation builds the right bundle once and then never again. That
 * failure is silent in both directions -- the user sees the app ignore a
 * certificate they installed, and nothing anywhere says why -- which is what
 * `installing a CA rebuilds a bundle the mtime check calls fresh` exists to
 * catch.
 *
 * The whole method had no unit test before this, so the system half is pinned
 * here too: without it the new cases would be measuring one unmeasured thing
 * against another.
 *
 * Certificates are supplied through the [FirstRunSetup.userTrustedCertificates]
 * seam. `AndroidCAStore` does not exist on a JVM, so there is no version of
 * these tests that reaches the real provider; what the seam's own body does is
 * measured on device instead, through the user-CA count this method logs.
 */
class UserCaBundleTest {

    @TempDir
    lateinit var filesDir: File

    @TempDir
    lateinit var systemCaDir: File

    private lateinit var context: Context
    private lateinit var bundle: File

    /** A file of the shape Android's trust store holds: PEM, one root per file. */
    private val systemPem = "-----BEGIN CERTIFICATE-----\nc3lzdGVtcm9vdA==\n-----END CERTIFICATE-----\n"

    @BeforeEach
    fun setUp() {
        mockkObject(Logger)
        every { Logger.d(any(), any()) } just Runs
        every { Logger.i(any(), any()) } just Runs
        every { Logger.w(any(), any(), any()) } just Runs
        every { Logger.e(any(), any(), any()) } just Runs

        context = mockk(relaxed = true)
        every { context.filesDir } returns filesDir

        File(systemCaDir, "a1b2c3d4.0").writeText(systemPem)
        bundle = File(filesDir, "usr/etc/tls/cert.pem")
    }

    @AfterEach
    fun tearDown() = unmockkObject(Logger)

    /** [FirstRunSetup] wired to the fixture store, with [certs] as the user half. */
    private fun setupWith(certs: () -> List<Certificate>): FirstRunSetup =
        FirstRunSetup(context).apply {
            systemCaCertificateDirs = listOf(systemCaDir.path)
            userTrustedCertificates = certs
        }

    private fun buildWith(vararg certs: Certificate) =
        setupWith { certs.toList() }.setupGitCaBundle()

    /**
     * Makes the cheap freshness test say "fresh".
     *
     * This is the state every launch after the first is in, and the state an
     * install through Settings does not disturb, so it is the state the user-CA
     * cases have to start from to be measuring anything.
     */
    private fun makeMtimeLookFresh() {
        assertTrue(bundle.isFile, "no bundle to age; the harness is wrong")
        assertTrue(systemCaDir.setLastModified(1_000_000_000_000L), "could not age the store")
        assertTrue(bundle.setLastModified(2_000_000_000_000L), "could not age the bundle")
    }

    private fun bundleText() = bundle.readText()

    // -- the system half, which had no test at all before this --

    @Test
    fun `the bundle carries every file in the system store`() {
        File(systemCaDir, "e5f6a7b8.0").writeText("-----BEGIN CERTIFICATE-----\nc2Vjb25k\n-----END CERTIFICATE-----\n")

        buildWith()

        assertTrue(systemPem in bundleText(), "the first system root is missing from the bundle")
        assertTrue("c2Vjb25k" in bundleText(), "the second system root is missing from the bundle")
    }

    @Test
    fun `an unchanged store is not rebuilt`() {
        // The control for every "was rebuilt" assertion below. Without it they
        // would all also hold for a method that rebuilt unconditionally, which
        // would put a 143-file concatenation on the main thread of every launch.
        buildWith()
        makeMtimeLookFresh()
        bundle.writeText("sentinel")

        buildWith()

        assertEquals("sentinel", bundleText(), "the bundle was rebuilt though nothing had changed")
    }

    @Test
    fun `a store that grew a certificate is rebuilt`() {
        // The other half of that control: the mtime test still has to work.
        buildWith()
        bundle.writeText("sentinel")
        assertTrue(bundle.setLastModified(1_000_000_000_000L), "could not age the bundle")
        assertTrue(systemCaDir.setLastModified(2_000_000_000_000L), "could not age the store")

        buildWith()

        assertTrue(systemPem in bundleText(), "a store newer than the bundle did not force a rebuild")
    }

    // -- the user half --

    @Test
    fun `a user CA reaches the bundle, after the system roots`() {
        buildWith(certificateOf(byteArrayOf(1, 2, 3, 4)))

        val text = bundleText()
        assertTrue(systemPem in text, "the system roots were dropped when a user CA was folded in")
        assertTrue("AQIDBA==" in text, "the user CA is not in the bundle git reads")
        assertTrue(
            text.indexOf(systemPem) < text.indexOf("AQIDBA=="),
            "the user CA was written before the system roots; the bundle is a concatenation " +
                "and appending is what keeps the system half byte-identical to the store",
        )
    }

    /**
     * The case the whole change turns on.
     *
     * A CA installed through Settings does not touch the mtime of the system
     * certificate directory, so the check that guarded this method before sees
     * a bundle newer than the store and returns. Anything that reads only that
     * clause builds the right bundle on some earlier launch and then ignores
     * every certificate the owner installs afterwards, for the life of the
     * install, with nothing on screen or in the log to say so.
     */
    @Test
    fun `installing a CA rebuilds a bundle the mtime check calls fresh`() {
        buildWith()
        makeMtimeLookFresh()

        buildWith(certificateOf(byteArrayOf(9, 9, 9)))

        assertTrue(
            "CQkJ" in bundleText(),
            "a newly installed CA never reached the bundle: the freshness test read the " +
                "system store's mtime, which an install through Settings does not move",
        )
    }

    @Test
    fun `removing a CA rebuilds the bundle without it`() {
        buildWith(certificateOf(byteArrayOf(9, 9, 9)))
        makeMtimeLookFresh()

        buildWith()

        assertFalse(
            "CQkJ" in bundleText(),
            "a CA the owner removed is still trusted by git; the bundle is rewritten rather " +
                "than appended to, and the fingerprint has to notice a shrinking store too",
        )
        assertTrue(systemPem in bundleText(), "the rebuild lost the system roots")
    }

    /**
     * Why the fingerprint covers the certificates' bytes and not a cheaper
     * summary of them.
     *
     * A Conscrypt alias is a hash of the issuer's subject, so a CA re-issued
     * under the same name arrives under the alias the old one had, and the count
     * does not move either. Both of those are what a shortcut would compare.
     */
    @Test
    fun `a CA re-issued under the same name is noticed`() {
        buildWith(certificateOf(byteArrayOf(1, 1, 1)))
        makeMtimeLookFresh()

        buildWith(certificateOf(byteArrayOf(2, 2, 2)))

        assertTrue("AgIC" in bundleText(), "the re-issued certificate never reached the bundle")
        assertFalse("AQEB" in bundleText(), "the superseded certificate is still trusted")
    }

    /**
     * The store is enumerated fresh on every launch, and `KeyStore.aliases`
     * promises no order. An order-sensitive fingerprint would therefore differ
     * from the recorded one on launches where nothing had changed, and rebuild
     * the whole bundle on the main thread each time.
     */
    @Test
    fun `the order the store enumerates in does not force a rebuild`() {
        val first = certificateOf(byteArrayOf(1, 1, 1))
        val second = certificateOf(byteArrayOf(2, 2, 2))
        setupWith { listOf(first, second) }.setupGitCaBundle()
        makeMtimeLookFresh()
        bundle.writeText("sentinel")

        setupWith { listOf(second, first) }.setupGitCaBundle()

        assertEquals(
            "sentinel",
            bundleText(),
            "the same two certificates in the other order counted as a change",
        )
    }

    // -- degrading rather than failing, inside a per-launch repair --

    @Test
    fun `one certificate that cannot be encoded does not cost the others`() {
        buildWith(certificateOf(byteArrayOf(7, 7, 7)), certificateOf(null))

        assertTrue("BwcH" in bundleText(), "a readable certificate was dropped along with a broken one")
        assertTrue(systemPem in bundleText(), "the system roots were dropped over one broken certificate")
    }

    /**
     * The closed-failure argument the whole feature rests on, as a property.
     *
     * This runs from SplashActivity's per-launch repair block, ahead of the
     * symlink and settings repairs, so an exception escaping here costs work
     * that matters more than this does. On a device where the provider is
     * missing or refuses to load, the answer has to be the bundle as it was
     * before any of this existed.
     */
    @Test
    fun `a trust store that cannot be read leaves the system-only bundle`() {
        setupWith { throw java.security.KeyStoreException("no such provider") }.setupGitCaBundle()

        assertTrue(bundle.isFile, "the bundle was not written at all")
        assertEquals(systemPem, bundleText(), "the bundle is not the system-only one")
    }

    /**
     * Ordering, as a property rather than a comment.
     *
     * The marker vouches for a bundle, so it can only be written once that
     * bundle is under its final name. Recorded first, a crash between the two
     * writes leaves a marker describing a file that was never built, and the
     * freshness check then returns early on every later launch: the bundle is
     * wrong permanently and the app is certain it is right. Recorded last, the
     * same crash leaves the marker absent, which is a mismatch, which is a
     * rebuild.
     *
     * Arranged as in [BashrcAtomicityTest], by occupying the temporary path the
     * atomic write derives from its destination: the bundle write fails, and
     * what the next launch does about it is the property.
     */
    @Test
    fun `a fingerprint is never recorded for a bundle that was not written`() {
        buildWith()
        val blocker = blockTheBundleWrite()

        buildWith(certificateOf(byteArrayOf(5, 5, 5)))
        assertEquals(
            systemPem,
            bundleText(),
            "the blocked write modified the bundle anyway; the harness is wrong",
        )

        blocker.deleteRecursively()
        makeMtimeLookFresh()

        buildWith(certificateOf(byteArrayOf(5, 5, 5)))

        assertTrue(
            "BQUF" in bundleText(),
            "the failed write left behind a fingerprint that vouches for it, so the freshness " +
                "check returns early on every later launch and the CA is never picked up",
        )
    }

    /** Non-empty, so the cleanup `delete()` cannot quietly reclaim it. */
    private fun blockTheBundleWrite(): File {
        bundle.parentFile?.mkdirs()
        return File(bundle.parentFile, "${bundle.name}.tmp~").also {
            assertTrue(it.mkdirs(), "could not stage the blocked temp path")
            File(it, "occupied").writeText("x")
        }
    }

    // -- the armour itself --

    /**
     * The user half is the only part of the bundle this app encodes; the system
     * files are copied through byte for byte. So the line wrapping, the header
     * and footer and the trailing newline are this app's to get right, and the
     * thing that decides whether it did is a certificate parser rather than a
     * regular expression of ours. A real self-signed certificate goes in and has
     * to come back out of the bundle.
     */
    @Test
    fun `what the bundle carries parses back as the certificate that went in`() {
        val real = realCertificate()

        buildWith(real)

        val pem = bundleText().substringAfter(systemPem)
        val parsed = CertificateFactory.getInstance("X.509")
            .generateCertificate(pem.byteInputStream()) as X509Certificate
        assertEquals(
            (real as X509Certificate).subjectX500Principal,
            parsed.subjectX500Principal,
            "the PEM in the bundle did not parse back as the certificate it encodes",
        )
    }

    /**
     * The width, which the round-trip above cannot see.
     *
     * Java's certificate factory accepts an unbroken run of base64 quite
     * happily, so a parser is the wrong instrument for this one: measured, an
     * unwrapped encoder passes that test. What reads the bundle in production is
     * a line-oriented C parser inside a libcurl this suite cannot reach, and
     * every other entry in the file is a system root copied through byte for
     * byte at 64 columns. The user half being the one stretch of the file with a
     * different shape is a difference with no upside and one that costs an
     * argument to avoid, so it is pinned rather than left to a reader to
     * rediscover.
     */
    @Test
    fun `the user half is wrapped like every other entry in the bundle`() {
        buildWith(realCertificate())

        val body = bundleText().substringAfter(systemPem)
            .lines().filter { it.isNotBlank() && !it.startsWith("-----") }
        assertTrue(body.size > 1, "the certificate was emitted as one unbroken run of base64")
        assertTrue(
            body.all { it.length <= 64 },
            "a line ran past 64 columns: ${body.map { it.length }}",
        )
    }

    /**
     * A certificate whose encoding is exactly [der], or one whose encoding
     * cannot be read when [der] is null.
     *
     * Only `getEncoded` is reached: [FirstRunSetup] turns each entry straight
     * into PEM and never asks a certificate anything else. Supplying the bytes
     * directly is what lets a re-issue be expressed as two certificates that
     * differ in nothing a shortcut would compare.
     */
    private fun certificateOf(der: ByteArray?): Certificate = object : Certificate("X.509") {
        override fun getEncoded(): ByteArray =
            der ?: throw CertificateEncodingException("this certificate cannot be encoded")

        override fun verify(key: PublicKey) = Unit

        override fun verify(key: PublicKey, sigProvider: String?) = Unit

        override fun getPublicKey(): PublicKey = throw UnsupportedOperationException()

        override fun toString(): String = "certificateOf(${der?.size ?: "unreadable"})"
    }

    /** A self-signed CA, `O=VSCodroid Test, CN=Test CA`, valid to 2036. */
    private fun realCertificate(): Certificate =
        CertificateFactory.getInstance("X.509").generateCertificate(
            Base64.getDecoder().decode(TEST_CA_DER).inputStream(),
        )
}

private const val TEST_CA_DER =
    "MIIDNzCCAh+gAwIBAgIUJnoYEVwNJOi5TaXU9yqNn6CeM4QwDQYJKoZIhvcNAQELBQAwKzEXMBUG" +
        "A1UECgwOVlNDb2Ryb2lkIFRlc3QxEDAOBgNVBAMMB1Rlc3QgQ0EwHhcNMjYwODIxMTQ0NzUyWhcN" +
        "MzYwODE4MTQ0NzUyWjArMRcwFQYDVQQKDA5WU0NvZHJvaWQgVGVzdDEQMA4GA1UEAwwHVGVzdCBD" +
        "QTCCASIwDQYJKoZIhvcNAQEBBQADggEPADCCAQoCggEBALG+F8Gb3EA8uZQfDfZYmblmQYknv7c9" +
        "TSzqvZPRCK1IwI3Y8oaQvOkGC5irKgcUa6qLlZ++p+Cr9mnJQrb+aj9pKFXeA1/6yniFSAnFYMxp" +
        "P4Pxq+Az8YQYwUJgeQSQ3MWmGesXks4BrhjMfrRWnfmpzwmbEwRYWtqXWe2FD68Hy3eMjX2nVeOD" +
        "sDVbZKlZTl2KeyYCqsHVXoww56+decfqR259+Fmf+3tvhol/AWLg0DpYZUO/MGL+AICrahHZNftN" +
        "dLtN0f/7ohxGEtQyYTVt052N05rOizk1ZJlDj9SysE28nMoFKQlmcTyCvpMMzt6i46IPYtpwxGJA" +
        "f6ws7ZcCAwEAAaNTMFEwHQYDVR0OBBYEFFQqDUUIUn4Pgl88bqnF9YlJWFtbMB8GA1UdIwQYMBaA" +
        "FFQqDUUIUn4Pgl88bqnF9YlJWFtbMA8GA1UdEwEB/wQFMAMBAf8wDQYJKoZIhvcNAQELBQADggEB" +
        "ABZAbAvoeHBzeeViFam0nVWXFcNpnS3ljpTwb8nVIxIGgDVu+qNm58AbpqibPkPuYnN/oOZa2g9p" +
        "e+FtFGfIkcor0vxuA7farq6HPGWRUyfLL68cMIubrsY/dyFxQo1E2yIERUxRseGa5IuC9ZQnWOwb" +
        "Ig82DLAOa0RPbFsphRSwmgXE/vjgMu3GwazyK3z+VgV++OrVPaFqL1jweBpqrELHPIzWD1JRPjl3" +
        "DF2ZOlRbVrBkyTv2UML2DkUp+boMdWGkR2Gau5PCzPZO9wdO0uYI3ovDMIP52MW692zvN8z/NgjO" +
        "VGw9WFdevvYVYcxcdPDYRRP//2jkaPpyba8OXLc="
