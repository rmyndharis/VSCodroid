package com.vscodroid.service

import android.app.ActivityManager
import android.content.Context
import android.content.SharedPreferences
import com.vscodroid.util.Environment
import com.vscodroid.util.Logger
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.spyk
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

class HeapValueAttributionTest {
    @TempDir
    lateinit var files: File

    @Test
    fun `a late death charge does not spend a different requested value`() = exercise(768)

    @Test
    fun `a late death charge remains recorded for the unchanged requested value`() = exercise(640)

    @Test
    fun `a charge queued before IO cannot spend a replacement value`() = exercise(768, beforeIo = true)

    @Test
    fun `a charge queued before IO still belongs to an unchanged value`() = exercise(640, beforeIo = true)

    private fun exercise(replacementValue: Int, beforeIo: Boolean = false) = runBlocking {
        mockkObject(Logger)
        every { Logger.i(any(), any()) } just Runs
        every { Logger.d(any(), any()) } just Runs
        every { Logger.w(any(), any()) } just Runs
        every { Logger.w(any(), any(), any()) } just Runs
        every { Logger.e(any(), any()) } just Runs
        every { Logger.e(any(), any(), any()) } just Runs
        val fixture = File(files, "server-fixture.sh").apply {
            writeText("#!/bin/sh\nexec /bin/sleep 20\n")
            check(setExecutable(true))
        }
        val settings = File(files, "settings.json")
        fun setRequest(value: Int) {
            settings.writeText("{\n    \"vscodroid.server.heapCeilingMb\": $value\n}\n")
        }
        setRequest(640)
        mockkObject(Environment)
        every { Environment.getNodePath(any()) } returns fixture.path
        every { Environment.getServerScript(any()) } returns "server.js"
        every { Environment.buildProcessEnvironment(any(), any()) } returns emptyMap()
        every { Environment.getExtensionsDir(any()) } returns File(files, "extensions").path
        every { Environment.getUserDataDir(any()) } returns File(files, "data").path
        every { Environment.getLogsDir(any()) } returns File(files, "logs").path
        every { Environment.getMachineSettingsPath(any()) } returns settings.path
        val context = mockk<Context>(relaxed = true)
        every { context.filesDir } returns files
        every { context.cacheDir } returns files
        val memory = mockk<ActivityManager>(relaxed = true) {
            every { isLowRamDevice } returns false
            every { getMemoryInfo(any()) } answers {
                firstArg<ActivityManager.MemoryInfo>().totalMem = 4L * 1024 * 1024 * 1024
            }
        }
        every { context.getSystemService(ActivityManager::class.java) } returns memory
        val values = mutableMapOf(PREF_HEAP_VALUE_SEEN to 640, PREF_HEAP_KILLS to 2)
        val committing = CountDownLatch(1)
        val finishCommit = CountDownLatch(1)
        val pauseCharge = AtomicBoolean(false)
        val prefs = mockk<SharedPreferences>()
        every { prefs.getInt(any(), any()) } answers {
            synchronized(values) { values[firstArg<String>()] ?: secondArg<Int>() }
        }
        every { prefs.edit() } answers {
            val pending = mutableMapOf<String, Int>()
            val editor = mockk<SharedPreferences.Editor>(relaxed = true)
            every { editor.putInt(any(), any()) } answers {
                pending[firstArg()] = secondArg()
                editor
            }
            fun applyPending() {
                val change = pending.toMap()
                if (!beforeIo && change.keys == setOf(PREF_HEAP_KILLS) && pauseCharge.compareAndSet(true, false)) {
                    committing.countDown()
                    check(finishCommit.await(5, TimeUnit.SECONDS))
                }
                synchronized(values) { values.putAll(change) }
            }
            every { editor.commit() } answers { applyPending(); true }
            every { editor.apply() } answers { applyPending() }
            editor
        }
        every { context.getSharedPreferences(HEAP_PREFS_NAME, Context.MODE_PRIVATE) } returns prefs
        val manager = spyk(ProcessManager(context))
        if (beforeIo) {
            every { manager.heapOverrideValueInEffect(any()) } answers {
                val value = callOriginal()
                if (pauseCharge.compareAndSet(true, false)) {
                    committing.countDown()
                    check(finishCommit.await(5, TimeUnit.SECONDS))
                }
                value
            }
        }
        val service = spyk(mockk<NodeService>(relaxed = true))
        fun setServiceField(name: String, value: Any) {
            NodeService::class.java.getDeclaredField(name).apply { isAccessible = true; set(service, value) }
        }
        setServiceField("tag", "NodeService")
        setServiceField("processManager", manager)
        every { service.getSharedPreferences(HEAP_PREFS_NAME, Context.MODE_PRIVATE) } returns prefs
        fun stopAndJoin() {
            val watch = ProcessManager::class.java.getDeclaredField("watchdogThread").apply {
                isAccessible = true
            }.get(manager) as Thread?
            manager.stopServer()
            watch?.join(5_000)
            assertFalse(watch?.isAlive == true, "a server watchdog outlived the fixture")
        }
        val charge = NodeService::class.java.getDeclaredMethod(
            "chargeHeapOverride", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Continuation::class.java,
        ).apply { isAccessible = true }
        var replacement: Thread? = null
        var handled: kotlinx.coroutines.Job? = null
        val failure = AtomicReference<Throwable?>()
        try {
            assertTrue(manager.startServer())
            assertTrue(manager.heapOverrideInEffect(), "the ended process must actually honor the raised value")
            assertEquals(640, synchronized(values) { values[PREF_HEAP_VALUE_SEEN] })
            assertEquals(2, synchronized(values) { values[PREF_HEAP_KILLS] })
            val epoch = manager.serverEpoch
            pauseCharge.set(true)
            val runningCharge = launch(Dispatchers.Default) {
                suspendCoroutineUninterceptedOrReturn<Unit> { continuation ->
                    charge.invoke(service, 137, epoch, continuation)
                }
            }
            handled = runningCharge
            assertTrue(committing.await(5, TimeUnit.SECONDS), "the real charge did not stage its write")
            stopAndJoin()
            setRequest(replacementValue)
            val finished = CountDownLatch(1)
            val worker = thread(name = "heap-replacement-start") {
                try { check(manager.startServer()) }
                catch (error: Throwable) { failure.set(error) }
                finally { finished.countDown() }
            }
            replacement = worker
            // A serialized reset waits behind the staged charge. Without a
            // shared lock this start can complete its reset before that charge.
            val completed = finished.await(1, TimeUnit.SECONDS)
            if (beforeIo) assertTrue(completed, "the replacement did not reset while the old charge was queued")
            if (!beforeIo && !completed) {
                assertEquals(Thread.State.BLOCKED, worker.state, "the replacement neither completed nor waited for the charge")
                assertTrue(worker.stackTrace.any { it.methodName == "requestedHeapCeiling" }, "the replacement waited outside the preference reset")
            }
            finishCommit.countDown()
            worker.join(5_000)
            withTimeout(5_000) { runningCharge.join() }
            assertFalse(worker.isAlive, "the replacement start outlived the fixture")
            failure.get()?.let { throw it }
            assertEquals(replacementValue, synchronized(values) { values[PREF_HEAP_VALUE_SEEN] })
            assertEquals(if (replacementValue == 640) 3 else 0, synchronized(values) { values[PREF_HEAP_KILLS] })
            assertNull(service.lastStartupNotice(), "the old death published a warning into the replacement")
            stopAndJoin()
            assertTrue(manager.startServer())
            assertEquals(replacementValue != 640, manager.heapOverrideInEffect(), "the next start applied the wrong value's budget")
        } finally {
            finishCommit.countDown()
            replacement?.join(5_000)
            handled?.let { withTimeout(5_000) { it.join() } }
            stopAndJoin()
            unmockkAll()
        }
    }
}
