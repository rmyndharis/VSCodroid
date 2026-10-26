package com.vscodroid.setup

import android.content.Context
import android.content.pm.InstallSourceInfo
import android.content.pm.PackageManager
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
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.lang.management.ManagementFactory
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

class ToolchainTransferAdmissionTest {
    @TempDir
    lateinit var filesDir: File

    private val pack = "toolchain_ruby"
    private lateinit var context: Context
    private val executors = mutableListOf<ThreadPoolExecutor>()
    private val gates = mutableListOf<CountDownLatch>()
    private val callers = mutableListOf<Thread>()

    @BeforeEach
    fun setUp() {
        mockkObject(Logger)
        every { Logger.d(any(), any()) } just Runs
        every { Logger.i(any(), any()) } just Runs
        every { Logger.w(any(), any(), any()) } just Runs
        every { Logger.e(any(), any(), any()) } just Runs
        mockkStatic(AssetPackManagerFactory::class)
        every { AssetPackManagerFactory.getInstance(any()) } returns mockk(relaxed = true)
        val source = mockk<InstallSourceInfo>()
        every { source.installingPackageName } returns "another.installer"
        val packages = mockk<PackageManager>(relaxed = true)
        every { packages.getInstallSourceInfo(any()) } returns source
        context = mockk(relaxed = true)
        every { context.filesDir } returns filesDir
        every { context.cacheDir } returns File(filesDir, "cache")
        every { context.packageName } returns "com.vscodroid"
        every { context.packageManager } returns packages
        assertTrue(downloads().isEmpty(), "another test left a transfer outstanding")
    }

    @AfterEach
    fun tearDown() {
        callers.forEach { it.join(TimeUnit.SECONDS.toMillis(6)) }
        executors.forEach { it.queue.clear() }
        gates.forEach { it.countDown() }
        executors.forEach { it.shutdownNow() }
        executors.forEach { assertTrue(it.awaitTermination(6, TimeUnit.SECONDS)) }
        downloads().clear()
        unmockkAll()
        assertFalse(callers.any { it.isAlive }, "an install caller did not return")
    }

    @Suppress("UNCHECKED_CAST")
    private fun downloads(): ConcurrentHashMap<String, Any> =
        ToolchainManager::class.java.getDeclaredField("httpDownloads")
            .apply { isAccessible = true }.get(null) as ConcurrentHashMap<String, Any>

    private fun operationLock(): Any = ToolchainManager::class.java
        .getDeclaredField("packOperationLock").apply { isAccessible = true }.get(null)

    private fun cancelled(token: Any): Boolean = token.javaClass.getDeclaredField("cancelled")
        .apply { isAccessible = true }.getBoolean(token)

    private fun holdQueue(manager: ToolchainManager) {
        val executor = ToolchainManager::class.java.getDeclaredField("ioExecutor")
            .apply { isAccessible = true }.get(manager) as ThreadPoolExecutor
        val entered = CountDownLatch(1)
        val gate = CountDownLatch(1)
        executors.add(executor)
        gates.add(gate)
        executor.execute {
            entered.countDown()
            try {
                gate.await()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        assertTrue(entered.await(5, TimeUnit.SECONDS), "the transfer queue was not held")
    }

    @Test
    fun `simultaneous admission shares the copy and removal lock and publishes one token`() {
        val managers = listOf(ToolchainManager(context), ToolchainManager(context))
        managers.forEach(::holdQueue)
        val entered = CountDownLatch(2)
        val errors = Collections.synchronizedList(mutableListOf<Throwable>())
        val lock = operationLock()
        val threads = ManagementFactory.getThreadMXBean()

        synchronized(lock) {
            for (manager in managers) {
                callers.add(Thread {
                    entered.countDown()
                    try {
                        manager.install("ruby")
                    } catch (error: Throwable) {
                        errors.add(error)
                    }
                }.apply { start() })
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            fun waitingForClaimLock(thread: Thread): Boolean {
                val info = threads.getThreadInfo(thread.id)
                return info?.threadState == Thread.State.BLOCKED &&
                    info.lockInfo?.identityHashCode == System.identityHashCode(lock)
            }
            while (callers.any { it.isAlive && !waitingForClaimLock(it) } &&
                System.nanoTime() < deadline
            ) Thread.yield()

            assertTrue(callers.all(::waitingForClaimLock), "admission bypassed the shared claim lock")
            assertTrue(downloads().isEmpty(), "a token was published before admission held the lock")
        }
        callers.forEach { it.join(TimeUnit.SECONDS.toMillis(6)) }
        assertFalse(callers.any { it.isAlive })
        assertTrue(errors.isEmpty(), "an install caller failed: $errors")
        assertEquals(1, downloads().size)
        assertEquals(1, executors.sumOf { it.queue.size })

        managers.first().cancel("ruby")

        assertTrue(cancelled(downloads().getValue(pack)))
    }

    @Test
    fun `removal declines while an older HTTP reinstall is queued`() {
        val installing = ToolchainManager(context)
        val delivered = File(filesDir, "delivery").apply { mkdirs() }
        File(delivered, "$pack.json").writeText("""{"name":"ruby","installRoot":"usr/opt/ruby"}""")
        File(delivered, "usr/opt/ruby").mkdirs()
        File(delivered, "usr/opt/ruby/payload").writeText("payload")
        ToolchainManager::class.java.getDeclaredMethod(
            "installFromDirectory", String::class.java, File::class.java, java.lang.Boolean.TYPE,
        ).apply { isAccessible = true }.invoke(installing, pack, delivered, false)
        assertTrue(installing.getInstalledToolchains().contains("ruby"))
        holdQueue(installing)
        installing.install("ruby")
        val token = downloads().getValue(pack)
        val removing = ToolchainManager(context)
        var status: Int? = null
        removing.onStateChange = { _, next, _, _, _ -> status = next }

        ToolchainManager::class.java.getDeclaredMethod("uninstallSync", String::class.java)
            .apply { isAccessible = true }.invoke(removing, "ruby")

        assertEquals(AssetPackStatus.UNKNOWN, status)
        assertTrue(removing.getInstalledToolchains().contains("ruby"))
        assertTrue(File(filesDir, "usr/opt/ruby/payload").isFile)
        assertFalse(cancelled(token), "declining removal must preserve the running transfer")
    }

    @Test
    fun `an existing file operation declines HTTP admission`() {
        val holding = ToolchainManager(context)
        val incoming = ToolchainManager(context)
        holdQueue(incoming)
        val claim = ToolchainManager::class.java.getDeclaredMethod(
            "claimPack", String::class.java, java.lang.Boolean.TYPE,
        ).apply { isAccessible = true }
        assertEquals(true, claim.invoke(holding, pack, false))
        try {
            var status: Int? = null
            incoming.onStateChange = { _, next, _, _, _ -> status = next }

            incoming.install("ruby")

            assertEquals(AssetPackStatus.UNKNOWN, status)
            assertTrue(downloads().isEmpty())
            assertEquals(0, executors.single().queue.size)
        } finally {
            ToolchainManager::class.java.getDeclaredMethod("releasePackClaim", String::class.java)
                .apply { isAccessible = true }.invoke(holding, pack)
        }
    }

    @Test
    fun `HTTP reports and snapshots retain the same transfer identity`() {
        val manager = ToolchainManager(context)
        holdQueue(manager)
        val state = ToolchainCardState(ToolchainCardMode.MANAGER)
        var reportedId: Long? = null
        manager.onStateChange = { name, status, percent, _, id ->
            reportedId = id
            state.updateState(name, status, percent, id)
        }

        manager.install("ruby")

        val snapshot = ToolchainManager.downloadProgress()
        assertNotNull(reportedId)
        assertEquals(snapshot.getValue(pack).transferId, reportedId)
        state.setDownloading(snapshot)
        ToolchainManager::class.java.getDeclaredMethod(
            "report", String::class.java, Integer.TYPE, Integer.TYPE, java.lang.Long::class.java,
        ).apply { isAccessible = true }
            .invoke(manager, pack, AssetPackStatus.COMPLETED, 100, reportedId)

        state.setDownloading(snapshot)

        assertEquals(ToolchainAction.REMOVE, state.card(pack).action)
    }
}
