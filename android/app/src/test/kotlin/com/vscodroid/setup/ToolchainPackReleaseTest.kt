package com.vscodroid.setup

import android.content.Context
import android.content.pm.InstallSourceInfo
import android.content.pm.PackageManager
import com.google.android.gms.tasks.OnCompleteListener
import com.google.android.gms.tasks.Task
import com.google.android.play.core.assetpacks.AssetPackManager
import com.google.android.play.core.assetpacks.AssetPackManagerFactory
import com.google.android.play.core.assetpacks.model.AssetPackStatus
import com.vscodroid.util.Logger
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

class ToolchainPackReleaseTest {
    @TempDir
    lateinit var filesDir: File
    private lateinit var context: Context
    private lateinit var packManager: AssetPackManager
    private val proceed = CountDownLatch(1)
    private var installer: Thread? = null
    private val pack = "toolchain_ruby"
    private val removalCompletions = Collections.synchronizedList(mutableListOf<() -> Unit>())

    @BeforeEach
    fun setUp() {
        mockkObject(Logger)
        every { Logger.d(any(), any()) } just Runs
        every { Logger.i(any(), any()) } just Runs
        every { Logger.w(any(), any()) } just Runs
        every { Logger.w(any(), any(), any()) } just Runs
        every { Logger.e(any(), any(), any()) } just Runs
        packManager = mockk(relaxed = true)
        mockkStatic(AssetPackManagerFactory::class)
        every { AssetPackManagerFactory.getInstance(any()) } returns packManager
        context = mockk(relaxed = true)
        every { context.filesDir } returns filesDir
        every { context.cacheDir } returns File(filesDir, "cache")
        every { context.packageName } returns "com.vscodroid"
        every { context.packageManager } returns packageManager
        // Play's own delivery is what these cases are about, and cancel only releases
        // a pack on an install Play delivers to.
        installedBy("com.android.vending")
        File(filesDir, "home/.vscodroid").mkdirs()
    }

    private val packageManager = mockk<PackageManager>(relaxed = true)

    private fun installedBy(installer: String?) {
        val info = mockk<InstallSourceInfo>()
        every { info.installingPackageName } returns installer
        every { packageManager.getInstallSourceInfo(any()) } returns info
    }

    @AfterEach
    fun tearDown() {
        proceed.countDown()
        installer?.join(TimeUnit.SECONDS.toMillis(10))
        removalCompletions.toList().forEach { it() }
        // Process-wide, and a removal whose Task never completes (a mock that answers
        // nothing) would otherwise stay in flight for every test that follows.
        removalsInFlight().clear()
        unmockkAll()
        assertFalse(installer?.isAlive == true, "the install did not settle")
    }

    private fun pendingRemoval(): Task<Void> {
        val task = mockk<Task<Void>>(relaxed = true)
        every { task.isSuccessful } returns true
        every { task.addOnSuccessListener(any()) } returns task
        every { task.addOnFailureListener(any()) } returns task
        every { task.addOnCompleteListener(any<Executor>(), any<OnCompleteListener<Void>>()) } answers {
            val executor = firstArg<Executor>()
            val listener = secondArg<OnCompleteListener<Void>>()
            removalCompletions.add { executor.execute { listener.onComplete(task) } }
            task
        }
        return task
    }

    private fun payload(): File {
        val dir = File(filesDir, "delivered").apply { mkdirs() }
        File(dir, "$pack.json").writeText(
            """{"name":"ruby","installRoot":"usr/lib/ruby","binaries":["usr/bin/ruby"]}"""
        )
        File(dir, "usr/bin").mkdirs()
        File(dir, "usr/bin/ruby").writeText("ruby payload")
        File(dir, "usr/lib/ruby").mkdirs()
        return dir
    }

    private fun install(manager: ToolchainManager, dir: File) =
        ToolchainManager::class.java
            .getDeclaredMethod("installFromDirectory", String::class.java, File::class.java)
            .apply { isAccessible = true }
            .invoke(manager, pack, dir)

    @Suppress("UNCHECKED_CAST")
    private fun removalsInFlight() =
        ToolchainManager::class.java.getDeclaredField("packRemovalsInFlight")
            .apply { isAccessible = true }
            .get(null) as MutableSet<String>

    private fun executor(manager: ToolchainManager) =
        ToolchainManager::class.java.getDeclaredField("ioExecutor")
            .apply { isAccessible = true }.get(manager) as ExecutorService

    @Test
    fun `cancelling from another manager preserves a payload until its copy finishes`() {
        val dir = payload()
        val copying = CountDownLatch(1)
        every { Logger.i(any(), match { it.startsWith("Installing toolchain:") }) } answers {
            copying.countDown()
            assertTrue(proceed.await(10, TimeUnit.SECONDS), "the copy was never released")
        }
        val removed = CountDownLatch(1)
        every { packManager.removePack(pack) } answers {
            dir.deleteRecursively()
            removed.countDown()
            pendingRemoval()
        }
        val events = Collections.synchronizedList(mutableListOf<Int>())
        val owner = ToolchainManager(context).apply {
            onStateChange = { _, status, _, _, _ -> events.add(status) }
        }
        val canceller = ToolchainManager(context)
        installer = Thread { install(owner, dir) }.apply { start() }
        assertTrue(copying.await(10, TimeUnit.SECONDS), "the install never reached its copy")

        canceller.cancel(pack)
        executor(canceller).submit {}.get(10, TimeUnit.SECONDS)

        assertTrue(File(dir, "usr/bin/ruby").isFile, "cancel removed another manager's source")
        proceed.countDown()
        installer!!.join(TimeUnit.SECONDS.toMillis(10))
        assertFalse(installer!!.isAlive, "the install did not finish")
        assertTrue(removed.await(10, TimeUnit.SECONDS), "cancel never released the consumed pack")
        assertEquals(listOf(AssetPackStatus.COMPLETED), events.toList())
        assertEquals("ruby payload", File(filesDir, "usr/bin/ruby").readText())
    }

    @Test
    fun `a new install cannot copy a pack while its asynchronous removal is pending`() {
        val dir = payload()
        val removalStarted = CountDownLatch(1)
        every { packManager.removePack(pack) } answers {
            removalStarted.countDown()
            pendingRemoval()
        }
        val canceller = ToolchainManager(context)
        canceller.cancel(pack)
        assertTrue(removalStarted.await(10, TimeUnit.SECONDS), "the removal never started")
        executor(canceller).submit {}.get(10, TimeUnit.SECONDS)
        val events = mutableListOf<Int>()
        val owner = ToolchainManager(context).apply {
            onStateChange = { _, status, _, _, _ -> events.add(status) }
        }

        install(owner, dir)

        assertEquals(listOf(AssetPackStatus.UNKNOWN), events)
        assertFalse(File(filesDir, "usr/bin/ruby").exists(), "the pending delete raced a new copy")
        assertFalse("ruby" in owner.getInstalledToolchains())

        dir.deleteRecursively()
        removalCompletions.toList().forEach { it() }
        val redelivered = payload()
        events.clear()
        install(owner, redelivered)
        assertEquals(listOf(AssetPackStatus.COMPLETED), events)
        assertEquals("ruby payload", File(filesDir, "usr/bin/ruby").readText())
    }
    @Test
    fun `cancelling a download on a sideloaded install asks Play to remove nothing`() {
        installedBy("com.example.sideloader")
        val manager = ToolchainManager(context)

        manager.cancel(pack)
        executor(manager).submit {}.get(10, TimeUnit.SECONDS)

        verify(exactly = 0) { packManager.removePack(any()) }
    }
}
