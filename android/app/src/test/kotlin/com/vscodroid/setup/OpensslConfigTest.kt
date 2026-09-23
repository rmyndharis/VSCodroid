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
 * The OpenSSL configuration file the server and everything under it read.
 *
 * The bundled libcrypto is Termux's build and carries Termux's OPENSSLDIR,
 * `/data/data/com.termux/files/usr/etc/tls`, so with nothing said it opens
 * that directory's `openssl.cnf` on every start. On a device without Termux
 * the open fails with ENOENT, which OpenSSL ignores. On a device WITH Termux
 * the directory exists, belongs to another app, and the open fails with
 * EACCES, which Node treats as fatal: "OpenSSL configuration error" before
 * `main()`, six times, and the user sees the server-gave-up page. That is
 * issue #447, read off the reporter's own server.log.
 *
 * The answer is a file of our own, named through OPENSSL_CONF for every
 * process the server starts, written on every launch beside the CA bundle.
 * Two halves, both pinned here: the environment row, and the file it names
 * being there with the content it should have.
 */
class OpensslConfigTest {

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

        // buildProcessEnvironment reaches the toolchain manager, whose Play
        // asset-pack client cannot be constructed on the JVM.
        mockkStatic(AssetPackManagerFactory::class)
        every { AssetPackManagerFactory.getInstance(any()) } returns mockk(relaxed = true)

        context = mockk(relaxed = true)
        every { context.filesDir } returns filesDir
        every { context.cacheDir } returns File(filesDir, "cache")
        every { context.getExternalFilesDir(null) } returns File(filesDir, "external")
        every { context.applicationInfo } returns ApplicationInfo().apply {
            nativeLibraryDir = "/data/app/x/lib/arm64"
            dataDir = filesDir.parent
        }
        every { context.packageName } returns "com.vscodroid"
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    @Test
    fun `the server environment names our own OpenSSL configuration`() {
        val env = Environment.buildProcessEnvironment(context, 13337)

        assertEquals(
            File(filesDir, "usr/etc/tls/openssl.cnf").absolutePath, env["OPENSSL_CONF"],
            "OPENSSL_CONF is not exported, so libcrypto opens Termux's compiled-in " +
                "openssl.cnf, and on a device where Termux is installed that open " +
                "fails with EACCES and Node refuses to start"
        )
    }

    @Test
    fun `the configuration file is written where the environment says it is`() {
        FirstRunSetup(context).setupOpensslConfig()

        val file = File(filesDir, "usr/etc/tls/openssl.cnf")
        assertTrue(file.isFile) { "setupOpensslConfig wrote nothing at ${file.path}" }
        assertEquals(OPENSSL_CONF_CONTENT, file.readText())
        assertTrue(file.readText().contains("openssl_conf = openssl_init")) {
            "the file must be a complete OpenSSL configuration, not an empty placeholder"
        }
    }

    @Test
    fun `a file with the right content is left alone`() {
        val file = File(filesDir, "usr/etc/tls/openssl.cnf")
        file.parentFile.mkdirs()
        file.writeText(OPENSSL_CONF_CONTENT)
        val stamp = 1_000_000_000_000L
        assertTrue(file.setLastModified(stamp))

        FirstRunSetup(context).setupOpensslConfig()

        assertEquals(stamp, file.lastModified()) {
            "a launch rewrote a file that already had the right content"
        }
    }

    @Test
    fun `a truncated or foreign file is replaced`() {
        val file = File(filesDir, "usr/etc/tls/openssl.cnf")
        file.parentFile.mkdirs()
        file.writeText("openssl_conf = ")

        FirstRunSetup(context).setupOpensslConfig()

        assertEquals(OPENSSL_CONF_CONTENT, file.readText()) {
            "a half-written file passes an existence check and fails OpenSSL's parser, " +
                "which Node treats exactly like the unreadable Termux path"
        }
    }
}
