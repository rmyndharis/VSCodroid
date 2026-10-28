package com.vscodroid.setup

import android.content.Context
import android.content.pm.InstallSourceInfo
import android.content.pm.PackageManager
import com.google.android.play.core.assetpacks.AssetPackManager
import com.google.android.play.core.assetpacks.AssetPackManagerFactory
import com.google.android.play.core.assetpacks.AssetPackState
import com.google.android.play.core.assetpacks.AssetPackStateUpdateListener
import com.google.android.play.core.assetpacks.model.AssetPackStatus
import com.vscodroid.util.Logger
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread

/**
 * Play delivers a state update on the main thread while holding the monitor of its listener
 * registry, and registering or removing a listener takes that same monitor. A manager that took
 * its own monitor first and then asked Play for the registry, while Play held the registry and
 * asked the manager's monitor for a report, deadlocked the main thread for good.
 *
 * Here the registry is a lock the test holds as Play does, and the two threads are made to meet
 * at the point where each holds one and wants the other.
 */
class ToolchainListenerLockOrderTest {

    @TempDir
    lateinit var filesDir: File

    private lateinit var context: Context
    private lateinit var packManager: AssetPackManager
    private val registry = ReentrantLock()
    private val unregistered = AtomicInteger()
    private val delivered = mutableListOf<Pair<String, Int>>()

    @BeforeEach
    fun setUp() {
        mockkObject(Logger)
        every { Logger.d(any(), any()) } just Runs
        every { Logger.i(any(), any()) } just Runs
        every { Logger.w(any(), any(), any()) } just Runs
        every { Logger.e(any(), any(), any()) } just Runs

        packManager = mockk(relaxed = true)
        mockkStatic(AssetPackManagerFactory::class)
        every { AssetPackManagerFactory.getInstance(any()) } returns packManager
        every { packManager.unregisterListener(any()) } answers {
            registry.lock()
            try { unregistered.incrementAndGet() } finally { registry.unlock() }
        }

        context = mockk(relaxed = true)
        every { context.filesDir } returns filesDir
        every { context.cacheDir } returns File(filesDir, "cache")
        every { context.packageName } returns "com.vscodroid"
        File(filesDir, "home/.vscodroid").mkdirs()
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    private fun report(m: ToolchainManager, pack: String, status: Int) {
        ToolchainManager::class.java.getDeclaredMethod(
            "report", String::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            java.lang.Long::class.java,
        ).apply { isAccessible = true }.invoke(m, pack, status, 100, null)
    }

    private fun update(pack: String): AssetPackState = mockk {
        every { name() } returns pack
        every { status() } returns AssetPackStatus.DOWNLOADING
        every { totalBytesToDownload() } returns 100L
        every { bytesDownloaded() } returns 10L
        every { errorCode() } returns 0
    }

    @Test
    fun `a screen that lets go of its listener while Play delivers another pack's update does not deadlock`() {
        val captured = slot<AssetPackStateUpdateListener>()
        every { packManager.registerListener(capture(captured)) } just Runs
        val manager = ToolchainManager(context)
        manager.registerListener()

        val ioInCallback = CountDownLatch(1)
        val playHoldsRegistry = CountDownLatch(1)
        manager.onStateChange = { pack, status, _, _, _ ->
            if (status == AssetPackStatus.COMPLETED) {
                // The completion callback of a screen that is gone: it lets go of its listener,
                // once Play has the registry and is about to report another pack.
                ioInCallback.countDown()
                playHoldsRegistry.await(5, TimeUnit.SECONDS)
                manager.unregisterListener()
            } else {
                synchronized(delivered) { delivered.add(pack to status) }
            }
        }

        val io = thread(isDaemon = true, name = "io") { report(manager, "toolchain_ruby", AssetPackStatus.COMPLETED) }
        val play = thread(isDaemon = true, name = "play-main") {
            registry.lock()
            try {
                playHoldsRegistry.countDown()
                ioInCallback.await(5, TimeUnit.SECONDS)
                // Give the other thread the moment it needs to ask for the registry.
                Thread.sleep(200)
                captured.captured.onStateUpdate(update("toolchain_java"))
            } finally {
                registry.unlock()
            }
        }

        io.join(5_000)
        play.join(5_000)
        val stuck = io.isAlive || play.isAlive
        if (stuck) { io.interrupt(); play.interrupt() }

        assertTrue(!stuck, "the report and the listener removal waited on each other")
        assertEquals(1, unregistered.get(), "the listener was not removed")
        assertEquals(listOf("toolchain_java" to AssetPackStatus.DOWNLOADING), synchronized(delivered) { delivered.toList() })
    }

    private fun installedByPlay(): ToolchainManager {
        val source = mockk<InstallSourceInfo>()
        every { source.installingPackageName } returns "com.android.vending"
        val packages = mockk<PackageManager>(relaxed = true)
        every { packages.getInstallSourceInfo(any()) } returns source
        every { context.packageManager } returns packages
        return ToolchainManager(context)
    }

    @Test
    fun `an idle release lets go of the listener`() {
        val manager = ToolchainManager(context)
        manager.registerListener()

        manager.unregisterListenerIfIdle()

        assertEquals(1, unregistered.get())
    }

    @Test
    fun `an idle release keeps the listener while a Play request is outstanding`() {
        val manager = installedByPlay()

        manager.install("toolchain_ruby")
        manager.unregisterListenerIfIdle()

        assertEquals(0, unregistered.get(), "the listener of a request still downloading was released")
        report(manager, "toolchain_ruby", AssetPackStatus.COMPLETED)
        manager.unregisterListenerIfIdle()
        assertEquals(1, unregistered.get(), "the listener stayed after the request ended")
    }
}
