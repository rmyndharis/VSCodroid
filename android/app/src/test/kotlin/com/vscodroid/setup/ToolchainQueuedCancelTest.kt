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
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * What a download cancelled while it was still waiting its turn reports.
 *
 * The HTTP path publishes a pack's cancellation token before it queues the
 * transfer, precisely so a cancel can land while the pack waits behind another
 * one on the single-thread executor. The task body then read the flag only
 * after its space pre-flight, and the pre-flight fails the pack: on a device
 * short of the reservation, the user who had just cancelled was shown a storage
 * failure, a Retry badge and a "not enough space" toast, followed by the
 * CANCELED the finally reports. One report too many, and the wrong one first.
 *
 * The queue is arranged by hand: a task parked on the manager's executor holds
 * the download behind it until the test says so, which is the window a second
 * Install tap on the toolchain screen opens. `StatFs` reports nothing free, so
 * the pre-flight is the branch that would speak if it were reached.
 */
class ToolchainQueuedCancelTest {

    @TempDir
    lateinit var filesDir: File

    private lateinit var context: Context
    private lateinit var packageManager: PackageManager
    private lateinit var packManager: AssetPackManager

    /** Written by the caller thread and by the executor, so it is synchronized. */
    private val events: MutableList<Pair<Int, ToolchainFailure?>> =
        Collections.synchronizedList(mutableListOf())
    private val settled = CountDownLatch(1)

    @BeforeEach
    fun setUp() {
        mockkObject(Logger)
        every { Logger.d(any(), any()) } just Runs
        every { Logger.i(any(), any()) } just Runs
        every { Logger.w(any(), any()) } just Runs
        every { Logger.w(any(), any(), any()) } just Runs
        every { Logger.e(any(), any(), any()) } just Runs

        // Play Core is reached through field initialisation, so it runs before
        // any method can be called on the manager.
        packManager = mockk(relaxed = true)
        every { packManager.removePack(any()) } answers { completedPackRemoval() }
        mockkStatic(AssetPackManagerFactory::class)
        every { AssetPackManagerFactory.getInstance(any()) } returns packManager

        // Nothing free: the pre-flight refuses any pack that reaches it.
        mockkConstructor(StatFs::class)
        every { anyConstructed<StatFs>().availableBytes } returns 0L

        packageManager = mockk(relaxed = true)
        val source = mockk<InstallSourceInfo>()
        // A sideload, so install() takes the HTTP path this is about.
        every { source.installingPackageName } returns "com.example.sideloader"
        every { packageManager.getInstallSourceInfo(any()) } returns source

        context = mockk(relaxed = true)
        every { context.filesDir } returns filesDir
        every { context.cacheDir } returns File(filesDir, "cache")
        every { context.packageManager } returns packageManager
        every { context.packageName } returns "com.vscodroid"

        File(filesDir, "home/.vscodroid").mkdirs()
    }

    @AfterEach
    fun tearDown() {
        // The token map is process-wide, so one a case left behind would decline
        // the next install of the pack in this JVM with a failure naming no cause.
        outstanding().clear()
        unmockkAll()
    }

    /**
     * The per-request cancellation tokens, reached by reflection for the reason
     * the neighbouring download tests give: exposing a download's bookkeeping
     * so a test can watch it makes the class worse to make the test simpler.
     */
    @Suppress("UNCHECKED_CAST")
    private fun outstanding(m: ToolchainManager = ToolchainManager(context)): MutableMap<String, Any> =
        ToolchainManager::class.java.getDeclaredField("httpDownloads")
            .apply { isAccessible = true }
            .get(m) as MutableMap<String, Any>

    private fun manager() = ToolchainManager(context).apply {
        onStateChange = { _, status, _, why, _ ->
            events.add(status to why)
            if (status == AssetPackStatus.FAILED || status == AssetPackStatus.CANCELED) {
                settled.countDown()
            }
        }
    }

    /**
     * Parks a task on [m]'s executor until the returned latch is released, so
     * whatever [m] queues next waits behind it. The executor is an instance
     * field and is reached by reflection for the same reason the tokens are.
     */
    private fun holdTheQueue(m: ToolchainManager): CountDownLatch {
        val gate = CountDownLatch(1)
        val executor = ToolchainManager::class.java.getDeclaredField("ioExecutor")
            .apply { isAccessible = true }
            .get(m) as Executor
        executor.execute { gate.await(10, TimeUnit.SECONDS) }
        return gate
    }

    private fun statuses() = synchronized(events) { events.map { it.first } }

    /**
     * Cancelled while queued, on a device with no room: PENDING, then CANCELED,
     * and nothing else. Restoring the pre-flight ahead of the check turns this
     * red with a STORAGE failure in front of the CANCELED.
     */
    @Test
    fun `a pack cancelled while queued reports CANCELED and never a storage failure`() {
        val m = manager()
        val gate = holdTheQueue(m)

        m.install("toolchain_ruby")
        m.cancel("toolchain_ruby")
        gate.countDown()

        assertTrue(settled.await(10, TimeUnit.SECONDS), "the download never reported an outcome")
        assertEquals(
            listOf(AssetPackStatus.PENDING, AssetPackStatus.CANCELED), statuses(),
            "a download the user stopped was reported as something else first: $events",
        )
    }

    /**
     * A cancelled transfer holds the pack until it stops, so an Install tapped at once is refused, and the
     * toolchain screen offered exactly that tap: it drew Install at the Cancel and answered it with "already being
     * installed". The cancel now says the transfer will report its end, which the screen waits for; once it has, an
     * Install starts a new transfer.
     */
    @Test
    fun `a cancelled transfer says it will report its end, and an Install after that end starts again`() {
        val m = manager()
        val gate = holdTheQueue(m)
        m.install("toolchain_ruby")

        assertEquals(true, m.cancel("toolchain_ruby"), "the screen is not told the transfer will report its end")
        m.install("toolchain_ruby")
        gate.countDown()

        assertTrue(settled.await(10, TimeUnit.SECONDS), "the cancelled transfer never reported its end")
        assertEquals(
            listOf(AssetPackStatus.PENDING, AssetPackStatus.UNKNOWN, AssetPackStatus.CANCELED), statuses(),
            "an Install before the end was not refused, or the end was not reported: $events",
        )
        val again = holdTheQueue(m)
        m.install("toolchain_ruby")
        assertEquals(AssetPackStatus.PENDING, statuses().last(), "an Install after the end was refused: $events")
        // Ended before the fixture goes, so the second transfer does not outlive it.
        m.cancel("toolchain_ruby")
        again.countDown()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (statuses().count { it == AssetPackStatus.CANCELED } < 2 && System.nanoTime() < deadline) Thread.sleep(10)
        assertEquals(2, statuses().count { it == AssetPackStatus.CANCELED }, "the second transfer never ended: $events")
    }

    /** Nothing running, nothing to wait for: the screen draws the cancel at once. */
    @Test
    fun `a cancel with no transfer running says no end will be reported`() {
        assertEquals(false, manager().cancel("toolchain_ruby"))
    }

    /**
     * The end is reported on every way out of a cancelled transfer, an error the task does not catch included,
     * because the screen waits for it. The cancel lands while the task is in its space check, which then throws.
     */
    @Test
    fun `a cancelled transfer reports its end when it leaves by an error`() {
        val m = manager()
        every { anyConstructed<StatFs>().availableBytes } answers {
            m.cancel("toolchain_ruby")
            throw StackOverflowError("the space check failed")
        }

        m.install("toolchain_ruby")

        assertTrue(settled.await(10, TimeUnit.SECONDS), "the cancelled transfer never reported its end")
        assertEquals(listOf(AssetPackStatus.PENDING, AssetPackStatus.CANCELED), statuses())
    }

    /**
     * The control: the same queue and the same full disk, with nothing
     * cancelled, is still refused for space. The check moved in front of the
     * pre-flight; it did not replace it.
     */
    @Test
    fun `a pack nobody cancelled is still refused for space`() {
        val m = manager()
        val gate = holdTheQueue(m)

        m.install("toolchain_ruby")
        gate.countDown()

        assertTrue(settled.await(10, TimeUnit.SECONDS), "the download never reported an outcome")
        assertEquals(listOf(AssetPackStatus.PENDING, AssetPackStatus.FAILED), statuses())
        assertEquals(
            listOf(ToolchainFailure.STORAGE),
            synchronized(events) { events.mapNotNull { it.second } },
            "the pre-flight no longer refuses a full disk",
        )
    }
}
