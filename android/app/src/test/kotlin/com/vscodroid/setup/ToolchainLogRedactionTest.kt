package com.vscodroid.setup

import android.content.Context
import android.content.pm.InstallSourceInfo
import android.content.pm.PackageManager
import com.google.android.play.core.assetpacks.AssetPackManager
import com.google.android.play.core.assetpacks.AssetPackManagerFactory
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
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * That a toolchain name a page chose is not printed in the clear.
 *
 * `AndroidBridge` takes the name from the workbench, redacts it for its own log
 * line and hands the raw value to this class, which then printed it verbatim. The
 * asymmetry is what makes it reachable: the lines below fire precisely when the
 * string is NOT a known toolchain, which is to say exactly when it is whatever
 * the caller sent. The page on the other side of the bridge holds the server's
 * connection token in its own URL, and `Logger.e` and `Logger.w` are not gated on
 * a debuggable build, so anything that lands there ships in release logcat.
 *
 * Driven rather than read out of the source: the whole path is reachable from a
 * JVM test, because `install` refuses an unknown name before it asks Play or the
 * network anything, and an uninstall of one stops at an empty install record.
 *
 * The ceiling is the one `redactToken` already documents and is repeated here so
 * this file is not read as a promise of more: it is keyed on the `tkn=`
 * parameter, so a bare token, or one re-encoded inside another parameter, still
 * passes through. This brings the callee to the same footing as the caller.
 */
class ToolchainLogRedactionTest {

    @TempDir
    lateinit var filesDir: File

    private lateinit var context: Context
    private lateinit var packageManager: PackageManager
    private lateinit var packManager: AssetPackManager

    /** Written by the caller thread and by ioExecutor, so it has to be synchronized. */
    private val messages: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** The uninstall runs on ioExecutor, so its line arrives after the call returns. */
    private val removalLogged = CountDownLatch(1)

    private val secret = "s3cr3t-connection-token"
    private val pageSupplied = "toolchain_x?tkn=$secret"

    private fun record(message: String) {
        messages.add(message)
        if (message.contains("not found in state")) removalLogged.countDown()
    }

    @BeforeEach
    fun setUp() {
        mockkObject(Logger)
        every { Logger.e(any(), any(), any()) } answers { record(secondArg<String>()) }
        every { Logger.w(any(), any(), any()) } answers { record(secondArg<String>()) }
        every { Logger.i(any(), any()) } answers { record(secondArg<String>()) }
        // Gated on a debuggable build, so it is out of scope here and out of the
        // capture as well: including it would let a debug-only line satisfy the
        // vacuity control below.
        every { Logger.d(any(), any()) } just Runs

        // Play Core is reached through field initialisation, so it runs before any
        // method can be called on the manager.
        packManager = mockk(relaxed = true)
        mockkStatic(AssetPackManagerFactory::class)
        every { AssetPackManagerFactory.getInstance(any()) } returns packManager

        packageManager = mockk(relaxed = true)
        context = mockk(relaxed = true)
        every { context.filesDir } returns filesDir
        every { context.cacheDir } returns File(filesDir, "cache")
        every { context.packageManager } returns packageManager
        every { context.packageName } returns "com.vscodroid"

        File(filesDir, "home/.vscodroid").mkdirs()
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
        messages.clear()
    }

    private fun manager() = ToolchainManager(context)

    private fun installedBy(installer: String?) {
        val info = mockk<InstallSourceInfo>()
        every { info.installingPackageName } returns installer
        every { packageManager.getInstallSourceInfo(any()) } returns info
    }

    private fun snapshot() = synchronized(messages) { messages.toList() }

    @Test
    fun `an unknown toolchain name is not printed in the clear`() {
        manager().install(pageSupplied)

        val logged = snapshot()
        assertTrue(
            logged.none { it.contains(secret) },
            "a connection token carried in a page-supplied toolchain name reached " +
                "logcat: $logged",
        )
        // The vacuity control. Without it a refactor that stopped logging on this
        // path at all would satisfy the assertion above by logging nothing.
        assertTrue(
            logged.any { it.contains("tkn=<redacted>") },
            "neither line fired, so this case proves nothing: $logged",
        )
    }

    @Test
    fun `both log lines fire for one unknown name`() {
        // An unknown name is reported twice, by the "Unknown toolchain" line and
        // again by fail(), and they are separate sites. Counting pins that the
        // case above cannot be satisfied by the first one alone.
        manager().install(pageSupplied)

        assertEquals(
            2, snapshot().count { it.contains("tkn=<redacted>") },
            "expected the unknown-name line and the failure line, got ${snapshot()}",
        )
    }

    @Test
    fun `an unknown toolchain removal is not printed in the clear`() {
        // toolchainShortName hands a name it does not recognise straight through,
        // deliberately, so that a toolchain dropped from the registry can still be
        // removed from the devices that have it. That is what puts arbitrary text
        // on the uninstall side.
        manager().uninstall(pageSupplied)

        assertTrue(
            removalLogged.await(10, TimeUnit.SECONDS),
            "the uninstall never reached its log line, so this case proves nothing",
        )
        val logged = snapshot()
        assertTrue(
            logged.none { it.contains(secret) },
            "a connection token carried in a page-supplied toolchain name reached " +
                "logcat on the uninstall path: $logged",
        )
        assertTrue(
            logged.any { it.contains("not found in state") && it.contains("tkn=<redacted>") },
            "the uninstall line is not the redacted one: $logged",
        )
    }

    @Test
    fun `a real toolchain name is logged as it stands`() {
        // The positive control: a redaction that mangled ordinary names would pass
        // every case above while making the log useless for the names that matter.
        installedBy("com.android.vending")

        manager().install("toolchain_java")

        assertTrue(
            snapshot().any { it.contains("toolchain_java") },
            "an ordinary pack name no longer survives its own log line: ${snapshot()}",
        )
    }
}
