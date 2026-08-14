package com.vscodroid.setup

import android.content.Context
import android.content.pm.InstallSourceInfo
import android.content.pm.PackageManager
import android.os.StatFs
import com.google.android.play.core.assetpacks.AssetPackManager
import com.google.android.play.core.assetpacks.AssetPackManagerFactory
import com.google.android.play.core.assetpacks.model.AssetPackStatus
import com.vscodroid.util.Logger
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
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
 * The install flow, which had no test of any kind.
 *
 * This is the production delivery channel for both audiences at once, and which one
 * a user gets is decided at runtime by [ToolchainManager.shouldUseHttpFallback]: a
 * Play install fetches an asset pack, everything else downloads a ZIP from a GitHub
 * release. Route a Play user down the HTTP path and the download is pointless where
 * Play would have served it; route a sideloaded user down the Play path and
 * `fetch()` fails quietly, because Play Asset Delivery has nothing to answer with on
 * an app it did not install. Neither shows up as an error anyone reads.
 *
 * The other half is [ToolchainManager.installFromDirectory], where both routes
 * converge. Its early returns are the reason first-run setup can finish: the queue
 * advances on reported state, so a pack that returns without reporting anything
 * stalls the progress screen forever rather than being skipped.
 *
 * No socket is opened here. The HTTP branch's background task checks free space
 * before it downloads, and the constructor of `StatFs` is stubbed to report none --
 * deliberately, rather than relying on the JVM's android.jar throwing `Stub!` at
 * whatever the task happens to touch first.
 */
class ToolchainInstallTest {

    @TempDir
    lateinit var filesDir: File

    private lateinit var context: Context
    private lateinit var packageManager: PackageManager
    private lateinit var packManager: AssetPackManager

    /** Written by the caller thread and by ioExecutor, so it has to be synchronized. */
    private val events: MutableList<Triple<String, Int, Int>> =
        Collections.synchronizedList(mutableListOf())
    private val failed = CountDownLatch(1)

    @BeforeEach
    fun setUp() {
        mockkObject(Logger)
        every { Logger.d(any(), any()) } just Runs
        every { Logger.i(any(), any()) } just Runs
        every { Logger.w(any(), any(), any()) } just Runs
        every { Logger.e(any(), any(), any()) } just Runs

        // Play Core is reached through field initialisation, so it runs before any
        // method can be called on the manager.
        packManager = mockk(relaxed = true)
        mockkStatic(AssetPackManagerFactory::class)
        every { AssetPackManagerFactory.getInstance(any()) } returns packManager

        // No free space, so the HTTP branch stops at its pre-flight check and the
        // download is never attempted.
        mockkConstructor(StatFs::class)
        every { anyConstructed<StatFs>().availableBytes } returns 0L

        packageManager = mockk(relaxed = true)
        context = mockk(relaxed = true)
        every { context.filesDir } returns filesDir
        every { context.cacheDir } returns File(filesDir, "cache")
        every { context.packageManager } returns packageManager
        every { context.packageName } returns "com.vscodroid"

        File(filesDir, "home/.vscodroid").mkdirs()
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    private fun manager() = ToolchainManager(context).apply {
        onStateChange = { pack, status, pct ->
            events.add(Triple(pack, status, pct))
            if (status == AssetPackStatus.FAILED) failed.countDown()
        }
    }

    private fun installedBy(installer: String?) {
        val info = mockk<InstallSourceInfo>()
        every { info.installingPackageName } returns installer
        every { packageManager.getInstallSourceInfo(any()) } returns info
    }

    /** Private, and the only entry point both delivery routes share. */
    private fun installFromDirectory(m: ToolchainManager, pack: String, dir: File) =
        ToolchainManager::class.java
            .getDeclaredMethod("installFromDirectory", String::class.java, File::class.java)
            .apply { isAccessible = true }
            .invoke(m, pack, dir)

    private fun statuses() = synchronized(events) { events.map { it.second } }

    // ── which route a user gets ──────────────────────────────────────────

    @Test
    fun `a Play install fetches the asset pack and downloads nothing`() {
        installedBy("com.android.vending")

        manager().install("toolchain_go")

        verify(exactly = 1) { packManager.fetch(listOf("toolchain_go")) }
        // The HTTP branch reports PENDING synchronously, before it queues anything,
        // so an empty list here is what distinguishes the two routes.
        assertEquals(emptyList<Int>(), statuses(), "the HTTP branch ran on a Play install")
    }

    @Test
    fun `a sideloaded install downloads over HTTP and never asks Play`() {
        installedBy("com.example.sideloader")

        manager().install("toolchain_go")

        assertTrue(failed.await(10, TimeUnit.SECONDS), "the HTTP task never reported an outcome")
        assertEquals(
            listOf(AssetPackStatus.PENDING, AssetPackStatus.FAILED), statuses(),
            "expected PENDING then the pre-flight refusal, got $events",
        )
        // The event sequence alone does not say WHICH failure this was: downloadViaHttp
        // reports FAILED from the disk-space branch and from three catch blocks, and a
        // runner with no network would produce exactly the same two events after three
        // retries. Deleting the space check would leave the assertion above green while
        // the test opened sockets. Only this line pins the branch.
        verify { Logger.e(any(), match { it.startsWith("Not enough disk space") }, any()) }
        verify(exactly = 0) { packManager.fetch(any()) }
    }

    @Test
    fun `an unreadable install source takes the HTTP route rather than none`() {
        // The catch in shouldUseHttpFallback returns true on purpose. A device that
        // will not say who installed the app is far more likely to be a sideload
        // than a Play install, and guessing Play there leaves the user with a
        // toolchain that never arrives and no error.
        every { packageManager.getInstallSourceInfo(any()) } throws SecurityException("denied")

        manager().install("toolchain_go")

        assertTrue(failed.await(10, TimeUnit.SECONDS), "the HTTP task never reported an outcome")
        assertEquals(AssetPackStatus.PENDING, statuses().first())
        verify(exactly = 0) { packManager.fetch(any()) }
    }

    @Test
    fun `an unknown toolchain reports FAILED instead of returning silently`() {
        installedBy("com.android.vending")

        manager().install("toolchain_haskell")

        assertEquals(listOf(AssetPackStatus.FAILED), statuses())
        verify(exactly = 0) { packManager.fetch(any()) }
    }

    // ── where both routes converge ───────────────────────────────────────

    @Test
    fun `a pack with no manifest reports FAILED rather than stalling the queue`() {
        val emptyPack = File(filesDir, "pack-empty").apply { mkdirs() }

        installFromDirectory(manager(), "toolchain_go", emptyPack)

        assertEquals(listOf(AssetPackStatus.FAILED), statuses())
    }

    @Test
    fun `a manifest with no name reports FAILED rather than stalling the queue`() {
        val pack = File(filesDir, "pack-nameless").apply { mkdirs() }
        File(pack, "toolchain_go.json").writeText("""{"installRoot":"usr/opt/go"}""")

        installFromDirectory(manager(), "toolchain_go", pack)

        assertEquals(listOf(AssetPackStatus.FAILED), statuses())
    }

    @Test
    fun `a valid manifest is recorded and reported COMPLETED`() {
        // The positive control for the three refusals above: without it they would
        // all still pass if installFromDirectory reported FAILED unconditionally.
        val pack = File(filesDir, "pack-go").apply { mkdirs() }
        File(pack, "toolchain_go.json").writeText("""{"name":"go","installRoot":"usr/opt/go"}""")

        installFromDirectory(manager(), "toolchain_go", pack)

        assertEquals(listOf(AssetPackStatus.COMPLETED), statuses())
        val state = File(filesDir, "home/.vscodroid/toolchains.json").readText()
        assertTrue(state.contains("\"go\""), "the install was reported but not recorded: $state")
    }
}
