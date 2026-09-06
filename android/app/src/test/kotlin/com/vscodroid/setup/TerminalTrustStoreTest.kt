package com.vscodroid.setup

import android.content.Context
import android.content.pm.ApplicationInfo
import com.google.android.play.core.assetpacks.AssetPackManagerFactory
import com.vscodroid.util.Environment
import com.vscodroid.util.Logger
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * That everything in the terminal which speaks TLS is given a trust store it can
 * actually read, and not only git.
 *
 * A directory trust store is not scanned, it is looked up: OpenSSL hashes the
 * issuer name and opens `<hash>.0`. Android names its files with the OpenSSL
 * 0.9.8 hash and everything since 1.0 uses a different one, so pointing
 * `SSL_CERT_DIR` at the system store yields a directory that can be listed and
 * never read. Measured against a certificate out of
 * `/system/etc/security/cacerts` on an API 37 emulator: the file is `01419da9.0`,
 * `openssl x509 -subject_hash_old` answers `01419da9`, and `-hash` answers
 * `8d89cda1`, which is the name OpenSSL 3 goes looking for and which is not
 * there.
 *
 * What that cost, measured on device before the file variable existed: the
 * bundled Python loaded zero certificates and every HTTPS request failed with
 * CERTIFICATE_VERIFY_FAILED, so `pip install` could not reach PyPI at all, on a
 * command the user guide documents and the Get Started walkthrough advertises as
 * ready. git was unaffected the whole time, because it had been handed the file
 * form separately, which is exactly why nothing noticed.
 */
class TerminalTrustStoreTest {

    @TempDir
    lateinit var filesDir: File

    private lateinit var context: Context

    @BeforeEach
    fun setUp() {
        mockkObject(Logger)
        every { Logger.d(any(), any()) } just Runs
        every { Logger.i(any(), any()) } just Runs
        every { Logger.w(any(), any()) } just Runs
        every { Logger.w(any(), any(), any()) } just Runs
        every { Logger.e(any(), any(), any()) } just Runs

        mockkStatic(AssetPackManagerFactory::class)
        every { AssetPackManagerFactory.getInstance(any()) } returns mockk(relaxed = true)

        context = mockk(relaxed = true)
        every { context.filesDir } returns filesDir
        every { context.cacheDir } returns File(filesDir, "cache")
        every { context.applicationInfo } returns ApplicationInfo().apply {
            nativeLibraryDir = "/data/app/~~hash==/com.vscodroid-hash==/lib/arm64"
        }
        every { context.getExternalFilesDir(null) } returns File(filesDir, "external")

        File(filesDir, "home/.vscodroid").mkdirs()
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    private fun env(): Map<String, String> = Environment.buildProcessEnvironment(context, 1234)

    @Test
    fun `the bundle reaches everything that speaks TLS, not only git`() {
        val environment = env()
        val bundle = "${filesDir.absolutePath}/usr/etc/tls/cert.pem"

        assertEquals(
            bundle, environment["GIT_SSL_CAINFO"],
            "git no longer names the bundle the app builds; this case reads both halves",
        )
        assertEquals(
            bundle, environment["SSL_CERT_FILE"],
            "nothing but git is given a readable trust store. The directory store beside " +
                "this is named with the pre-1.0 OpenSSL hash, so OpenSSL 3 looks up a name " +
                "that is not there and finds no issuer: pip install fails at TLS against " +
                "PyPI, on a command the guide documents as working",
        )
    }

    /**
     * pip does not read SSL_CERT_FILE. It verifies against the certifi bundle
     * vendored inside itself, so it kept working throughout and the row above
     * does nothing for it. This is the name it does read, and without it a CA
     * the device owner installed reaches git and never reaches a private index.
     */
    @Test
    fun `pip is given the bundle under the name it reads`() {
        File(filesDir, "usr/etc/tls").mkdirs()
        File(filesDir, "usr/etc/tls/cert.pem").writeText("-----BEGIN CERTIFICATE-----\n")

        assertEquals(
            "${filesDir.absolutePath}/usr/etc/tls/cert.pem", env()["REQUESTS_CA_BUNDLE"],
            "pip and a user's own requests code are back on the vendored certifi, so a CA " +
                "the device owner installed does not reach a private index",
        )
    }

    /**
     * And only when the file is there.
     *
     * requests treats a bundle it cannot open as fatal rather than falling back,
     * so naming one `setupGitCaBundle` never wrote would stop pip outright: it
     * is the one Python HTTPS client that works without any of this, and taking
     * it away to improve trust would be the worse trade.
     */
    @Test
    fun `no bundle on disk means the variable is not set at all`() {
        assertTrue(
            !File(filesDir, "usr/etc/tls/cert.pem").exists(),
            "this case has to run with no bundle written, or it proves nothing",
        )

        assertTrue(
            env()["REQUESTS_CA_BUNDLE"] == null,
            "pip is pointed at a bundle that does not exist, which it treats as fatal: " +
                "every install stops with \"Could not find a suitable TLS CA certificate " +
                "bundle\" instead of falling back to the one vendored inside it",
        )
    }

    /**
     * The directory stays. It costs nothing, and a client that does resolve the
     * old hashes keeps working; dropping it would be a change nobody measured.
     */
    @Test
    fun `the system store is still offered alongside the bundle`() {
        val environment = env()

        assertTrue(
            environment["SSL_CERT_DIR"]?.startsWith("/") == true,
            "SSL_CERT_DIR no longer names an absolute system path: ${environment["SSL_CERT_DIR"]}",
        )
        assertTrue(
            environment["SSL_CERT_DIR"] != environment["SSL_CERT_FILE"],
            "the directory and the file variables now name the same thing, so one of them " +
                "is being used for something it is not",
        )
    }
}
