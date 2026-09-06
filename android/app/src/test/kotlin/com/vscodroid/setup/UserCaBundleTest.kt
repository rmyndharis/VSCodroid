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
 * Pins what [FirstRunSetup.setupGitCaBundle] puts in the terminal's certificate
 * bundle, and, more sharply, when it agrees to look again.
 *
 * The bundle is the device's own trust store, not a copy of the certificate
 * directory underneath it, and those are two different answers. A root the
 * device owner turned off in Settings > Trusted credentials leaves the
 * platform's merged store, so every other app on the phone stops trusting it,
 * and its file stays in the certificate directory exactly where it was. A bundle
 * concatenated from that directory therefore went on trusting a root the owner
 * had distrusted. It used to matter only for git; SSL_CERT_FILE and
 * REQUESTS_CA_BUNDLE now put the same file in front of python, pip and curl.
 *
 * So where the store answers, it is the whole bundle and the certificate
 * directory is not read at all, which is what `a root the store stops answering
 * with leaves the bundle` measures. Where the store answers with nothing (a
 * provider that threw, a device that has none) the directory is concatenated as
 * before, because a bundle that honours a removal is worth less than a bundle
 * that exists: this runs inside SplashActivity's per-launch repair block, and
 * what it writes is the file every HTTPS client in the terminal reads.
 *
 * Freshness is two conditions and neither is redundant. The certificate
 * directory's mtime is the cheap one, and it is blind to both halves of what
 * this measures: installing a CA through Settings and turning a root off both
 * write into a store of their own and leave that mtime where it was. The
 * fingerprint over the whole store is the condition that notices, and without it
 * the right bundle is built on some early launch and then never again. That
 * failure is silent in both directions: the user sees the terminal ignore a
 * certificate they installed, or go on trusting one they removed, and nothing
 * anywhere says why.
 *
 * Certificates are supplied through the [FirstRunSetup.deviceTrustedCertificates]
 * seam. `AndroidCAStore` does not exist on a JVM, so there is no version of
 * these tests that reaches the real provider; what the seam's own body does is
 * measured on device instead, through the certificate count this method logs.
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

    /**
     * The same root as [systemPem], in the form the store answers with.
     *
     * The encoder base64s a certificate's bytes, so one whose encoding is the
     * ASCII of "systemroot" comes out carrying the body [systemPem] carries.
     * That is what lets a case have the store answer with a root whose file is
     * sitting in the certificate directory at the same time, which is the
     * arrangement the whole change turns on.
     */
    private val systemRoot = certificateOf("systemroot".toByteArray())

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

    /** [FirstRunSetup] wired to the fixture directory, with [certs] as the store. */
    private fun setupWith(certs: () -> List<Certificate>): FirstRunSetup =
        FirstRunSetup(context).apply {
            systemCaCertificateDirs = listOf(systemCaDir.path)
            deviceTrustedCertificates = certs
        }

    /**
     * The store answers with the root every device has, plus [certs].
     *
     * The merged store holds both halves, so a case that supplied only its own
     * certificates would be measuring a device that trusts nothing else, which
     * is not a device.
     */
    private fun buildWith(vararg certs: Certificate) =
        setupWith { listOf(systemRoot) + certs }.setupGitCaBundle()

    /**
     * Makes the cheap freshness test say "fresh".
     *
     * This is the state every launch after the first is in, and the state
     * neither installing a CA nor turning a root off disturbs, so it is the
     * state the store cases have to start from to be measuring anything.
     */
    private fun makeMtimeLookFresh() {
        assertTrue(bundle.isFile, "no bundle to age; the harness is wrong")
        assertTrue(systemCaDir.setLastModified(1_000_000_000_000L), "could not age the store")
        assertTrue(bundle.setLastModified(2_000_000_000_000L), "could not age the bundle")
    }

    private fun bundleText() = bundle.readText()

    /** How many times [body] occurs in the bundle. */
    private fun countIn(body: String) = bundleText().split(body).size - 1

    // -- the merged store is the bundle --

    @Test
    fun `every certificate the store answers with reaches the bundle, exactly once`() {
        // Exactly once rather than merely present, and that is the whole point of
        // counting: the certificate directory holds the same root under
        // a1b2c3d4.0, so a bundle that concatenated the directory as well as the
        // store would contain every body asserted here and still be wrong.
        buildWith(certificateOf(byteArrayOf(1, 2, 3, 4)), certificateOf(byteArrayOf(5, 6)))

        assertEquals(
            1,
            countIn("c3lzdGVtcm9vdA=="),
            "the system root the store answered with is missing from the bundle, or in it " +
                "twice because the certificate directory was copied in as well",
        )
        assertEquals(1, countIn("AQIDBA=="), "a certificate the store answered with is not in the bundle")
        assertEquals(1, countIn("BQY="), "a certificate the store answered with is not in the bundle")
    }

    /**
     * The case the change exists for.
     *
     * Turning a root off in Settings > Trusted credentials drops it from the
     * platform's store and leaves its file in the certificate directory, so a
     * bundle built by copying that directory keeps trusting a root its owner
     * distrusted and every other app on the phone has already dropped. The mtime
     * is made to look fresh first because that is the honest arrangement: a
     * removal does not move it, so the fingerprint over the whole store is the
     * only thing that can notice.
     */
    @Test
    fun `a root the store stops answering with leaves the bundle`() {
        val stillTrusted = certificateOf(byteArrayOf(4, 4, 4))
        buildWith(stillTrusted)
        makeMtimeLookFresh()
        assertTrue(
            File(systemCaDir, "a1b2c3d4.0").isFile,
            "the removed root's file is gone from the certificate directory; the harness is wrong",
        )

        setupWith { listOf(stillTrusted) }.setupGitCaBundle()

        assertFalse(
            "c3lzdGVtcm9vdA==" in bundleText(),
            "a root the owner turned off is still trusted by everything this bundle feeds, " +
                "which is git, python, pip and curl. Its file is still in the certificate " +
                "directory and only the store knows it was distrusted, so a bundle built from " +
                "that directory cannot honour the removal",
        )
        assertTrue("BAQE" in bundleText(), "the rebuild dropped the roots that are still trusted")
    }

    @Test
    fun `an unchanged store is not rebuilt`() {
        // The control for every "was rebuilt" assertion below. Without it they
        // would all also hold for a method that rebuilt unconditionally, which
        // would rewrite the whole bundle on the main thread of every launch.
        buildWith()
        makeMtimeLookFresh()
        bundle.writeText("sentinel")

        buildWith()

        assertEquals("sentinel", bundleText(), "the bundle was rebuilt though nothing had changed")
    }

    @Test
    fun `a certificate directory newer than the bundle forces a rebuild`() {
        // The other half of that control: the mtime test still has to work. It is
        // the only signal a system update moving the roots underneath us gives.
        buildWith()
        bundle.writeText("sentinel")
        assertTrue(bundle.setLastModified(1_000_000_000_000L), "could not age the bundle")
        assertTrue(systemCaDir.setLastModified(2_000_000_000_000L), "could not age the store")

        buildWith()

        assertTrue(systemPem in bundleText(), "a store newer than the bundle did not force a rebuild")
    }

    /**
     * The other direction of the same blindness.
     *
     * A CA installed through Settings does not touch the mtime of the system
     * certificate directory, so the check that guarded this method before sees a
     * bundle newer than the store and returns. Anything reading only that clause
     * builds the right bundle on some earlier launch and then ignores every
     * certificate the owner installs afterwards, for the life of the install,
     * with nothing on screen or in the log to say so.
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
        assertTrue(systemPem in bundleText(), "the rebuild lost the rest of the store")
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
        assertTrue(systemPem in bundleText(), "the rest of the store was dropped over one broken certificate")
    }

    /**
     * The closed-failure argument the whole feature rests on, as a property.
     *
     * This runs from SplashActivity's per-launch repair block, ahead of the
     * symlink and settings repairs, so an exception escaping here costs work
     * that matters more than this does. And the bundle is now what git, python,
     * pip and curl all read, so a device whose provider is missing or refuses to
     * load has to end up with the bundle as it was before any of this existed:
     * the certificate directory, concatenated, which cannot see a root the owner
     * removed but does keep HTTPS working at all. That is the trade, and it is
     * the right way round.
     *
     * Both ways of answering with nothing, because they arrive by different
     * doors: a provider that throws is caught inside the seam's caller, and an
     * empty list is a store that loaded and held nothing usable. Only the first
     * of the two was measured before.
     */
    @Test
    fun `a store that answers with nothing falls back to the certificate directory`() {
        // Two files, because the fallback has to carry the whole directory: this
        // is the one path on which what the platform ships is copied through
        // rather than encoded here, and a bundle holding some roots and not the
        // rest fails against whichever hosts fall on the wrong side of the cut.
        val secondPem = "-----BEGIN CERTIFICATE-----\nc2Vjb25k\n-----END CERTIFICATE-----\n"
        File(systemCaDir, "e5f6a7b8.0").writeText(secondPem)

        fun assertFallsBack(how: String, store: () -> List<Certificate>) {
            // Cleared first, or a later pass is answered by the freshness check
            // rather than by the fallback: a store that answers with nothing
            // fingerprints to the same hash whichever way it emptied, so a
            // surviving bundle would satisfy the assertion without the method
            // having built anything.
            bundle.parentFile?.deleteRecursively()
            assertFalse(bundle.exists(), "could not clear the bundle between passes; the harness is wrong")

            setupWith(store).setupGitCaBundle()

            assertTrue(bundle.isFile, "$how left no bundle at all")
            assertEquals(
                systemPem + secondPem,
                bundleText(),
                "$how left the terminal without the system roots, so HTTPS fails everywhere " +
                    "the bundle is read: git, python, pip and curl",
            )
        }

        assertFallsBack("a provider that cannot be loaded") {
            throw java.security.KeyStoreException("no such provider")
        }
        assertFallsBack("a device with no such store") { emptyList() }
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
     * Every byte of the normal bundle is now encoded by this app rather than
     * copied through from a file, so the line wrapping, the header and footer
     * and the trailing newline are all this app's to get right. The thing that
     * decides whether it did is a certificate parser rather than a regular
     * expression of ours: a real self-signed certificate goes in and has to come
     * back out of the bundle.
     *
     * The store answers with that certificate alone, so nothing here depends on
     * where the sort puts it.
     */
    @Test
    fun `what the bundle carries parses back as the certificate that went in`() {
        val real = realCertificate()

        setupWith { listOf(real) }.setupGitCaBundle()

        val parsed = CertificateFactory.getInstance("X.509")
            .generateCertificate(bundleText().byteInputStream()) as X509Certificate
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
     * a line-oriented C parser inside a libcurl this suite cannot reach, and the
     * fallback path copies the platform's own root files through byte for byte
     * at 64 columns. The bundle this app encodes having a different shape from
     * the bundle it falls back to is a difference with no upside and one that
     * costs an argument to avoid, so it is pinned rather than left to a reader
     * to rediscover.
     */
    @Test
    fun `the bundle is wrapped like the root files it stands in for`() {
        setupWith { listOf(realCertificate()) }.setupGitCaBundle()

        val body = bundleText().lines().filter { it.isNotBlank() && !it.startsWith("-----") }
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
     * differ in nothing a shortcut would compare, and what lets a fixture
     * certificate carry the same body as a file in the certificate directory.
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
