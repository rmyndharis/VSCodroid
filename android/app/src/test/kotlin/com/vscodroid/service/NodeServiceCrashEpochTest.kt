package com.vscodroid.service

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import com.vscodroid.R
import com.vscodroid.util.Environment
import com.vscodroid.util.Logger
import io.mockk.Runs
import androidx.core.app.ServiceCompat
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.spyk
import io.mockk.unmockkAll
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
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
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

class NodeServiceCrashEpochTest {

    @TempDir
    lateinit var files: File

    @Test
    fun `a heap charge suspended across stop and relaunch cannot replace the new startup notice`() = runBlocking {
        mockEditorGateway()
        mockkObject(Logger)
        every { Logger.i(any(), any()) } just Runs
        every { Logger.d(any(), any()) } just Runs
        every { Logger.w(any(), any()) } just Runs
        every { Logger.w(any(), any(), any()) } just Runs
        every { Logger.e(any(), any()) } just Runs
        every { Logger.e(any(), any(), any()) } just Runs
        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } returns 1_000L

        val fixture = File(files, "server-fixture.sh").apply {
            writeText("#!/bin/sh\nexec /bin/sleep 20\n")
            check(setExecutable(true))
        }
        mockkObject(Environment)
        every { Environment.getNodePath(any()) } returns fixture.path
        every { Environment.getServerScript(any()) } returns "server.js"
        every { Environment.buildProcessEnvironment(any(), any()) } returns emptyMap()
        every { Environment.getExtensionsDir(any()) } returns "extensions"
        every { Environment.getUserDataDir(any()) } returns "data"
        every { Environment.getLogsDir(any()) } returns File(files, "logs").path
        val context = mockk<Context>(relaxed = true)
        every { context.filesDir } returns files
        every { context.cacheDir } returns files
        val manager = ProcessManager(context)
        ProcessManager::class.java.getDeclaredField("heapOverrideActive").apply {
            isAccessible = true
            setBoolean(manager, true)
        }
        ProcessManager::class.java.getDeclaredField("heapOverrideAskedMb").apply {
            isAccessible = true
            set(manager, 640)
        }

        // Bypass Android constructors while retaining the service's real crash handler.
        val service = spyk(mockk<NodeService>(relaxed = true))
        fun setServiceField(name: String, value: Any) {
            NodeService::class.java.getDeclaredField(name).apply {
                isAccessible = true
                set(service, value)
            }
        }
        setServiceField("tag", "NodeService")
        setServiceField("processManager", manager)
        setServiceField("isServiceRunning", true)
        setServiceField("lastReadyAt", 500L)
        val notices = mutableListOf<String>()
        service.onServerError = { notices += it }
        every { service.getString(R.string.heap_override_suspended) } returns "Heap override suspended"

        val committing = CountDownLatch(1)
        val finishCommit = CountDownLatch(1)
        val kills = AtomicInteger(2)
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        every { editor.putInt(PREF_HEAP_KILLS, any()) } answers {
            kills.set(secondArg())
            editor
        }
        every { editor.commit() } answers {
            committing.countDown()
            check(finishCommit.await(5, TimeUnit.SECONDS))
            true
        }
        val prefs = mockk<SharedPreferences>()
        every { prefs.getInt(PREF_HEAP_KILLS, 0) } answers { kills.get() }
        every { prefs.getInt(PREF_HEAP_VALUE_SEEN, 0) } returns 640
        every { prefs.edit() } returns editor
        every { service.getSharedPreferences(HEAP_PREFS_NAME, Context.MODE_PRIVATE) } returns prefs
        val crash = NodeService::class.java.getDeclaredMethod(
            "handleServerCrash", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Continuation::class.java,
        ).apply { isAccessible = true }
        val epoch = manager.serverEpoch
        val handled = launch(start = CoroutineStart.UNDISPATCHED) {
            suspendCoroutineUninterceptedOrReturn<Unit> { continuation ->
                crash.invoke(service, 137, epoch, continuation)
            }
        }
        try {
            assertTrue(committing.await(5, TimeUnit.SECONDS), "the heap charge never reached preference I/O")
            setServiceField("isServiceRunning", false)
            manager.stopServer()
            setServiceField("isServiceRunning", true)
            assertTrue(manager.startServer(), "the replacement server must start")
            setServiceField("lastReadyAt", 1_500L)
            assertNull(service.lastStartupNotice(), "the replacement starts without an old warning")
            finishCommit.countDown()
            withTimeout(5_000) { handled.join() }

            assertEquals(3, kills.get(), "the old death must still be recorded")
            assertNull(service.lastStartupNotice(), "the old heap charge replaced the new run's notice")
            assertTrue(notices.isEmpty(), "the old heap charge notified the replacement run")
            val readyAfter = NodeService::class.java.getDeclaredField("lastReadyAt")
                .apply { isAccessible = true }.getLong(service)
            assertEquals(
                1_500L,
                readyAfter,
                "consuming lastReadyAt after chargeHeapOverride overwrote the replacement server's readiness",
            )
        } finally {
            finishCommit.countDown()
            withTimeout(5_000) { handled.join() }
            val watchdog = ProcessManager::class.java.getDeclaredField("watchdogThread")
                .apply { isAccessible = true }.get(manager) as Thread?
            manager.stopServer()
            watchdog?.join(5_000)
            assertFalse(watchdog?.isAlive == true, "the replacement watchdog outlived the test")
            unmockkAll()
        }
    }

    private data class CrashHarness(
        val service: NodeService,
        val manager: ProcessManager,
        val gaveUp: MutableList<Boolean>,
    ) {
        fun getRestartCount(): Int =
            NodeService::class.java.getDeclaredField("restartCount")
                .apply { isAccessible = true }.getInt(service)

        fun setRestartCount(value: Int) {
            NodeService::class.java.getDeclaredField("restartCount")
                .apply { isAccessible = true }.setInt(service, value)
        }

        fun getLastReadyAt(): Long =
            NodeService::class.java.getDeclaredField("lastReadyAt")
                .apply { isAccessible = true }.getLong(service)

        fun setLastReadyAt(value: Long) {
            NodeService::class.java.getDeclaredField("lastReadyAt")
                .apply { isAccessible = true }.setLong(service, value)
        }

        fun crash(exitCode: Int = 1) {
            val method = NodeService::class.java.getDeclaredMethod(
                "handleServerCrash",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Continuation::class.java,
            ).apply { isAccessible = true }
            val epoch = manager.serverEpoch
            runBlocking {
                val job = launch(start = CoroutineStart.UNDISPATCHED) {
                    suspendCoroutineUninterceptedOrReturn<Unit> { cont ->
                        method.invoke(service, exitCode, epoch, cont)
                    }
                }
                // handleServerCrash runs synchronously through retryOrGiveUp's restartCount++
                // up to the backoff delay (or through enterTerminalState on give-up).
                job.cancel()
                withTimeout(5_000) { job.join() }
            }
        }
    }

    private fun buildCrashHarness(now: () -> Long): CrashHarness {
        mockEditorGateway()
        mockkObject(Logger)
        every { Logger.i(any(), any()) } just Runs
        every { Logger.d(any(), any()) } just Runs
        every { Logger.w(any(), any()) } just Runs
        every { Logger.w(any(), any(), any()) } just Runs
        every { Logger.e(any(), any()) } just Runs
        every { Logger.e(any(), any(), any()) } just Runs
        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } answers { now() }
        mockkStatic(ServiceCompat::class)
        every { ServiceCompat.startForeground(any(), any(), any(), any()) } just Runs
        every { ServiceCompat.stopForeground(any(), any<Int>()) } just Runs

        val context = mockk<Context>(relaxed = true)
        every { context.filesDir } returns files
        every { context.cacheDir } returns files
        val manager = ProcessManager(context)
        val service = spyk(mockk<NodeService>(relaxed = true), recordPrivateCalls = true)
        every { service.getString(any()) } returns "text"
        every { service invokeNoArgs "stopServingRecoverably" } answers {
            NodeService::class.java.getDeclaredField("isServiceRunning")
                .apply { isAccessible = true }.setBoolean(service, false)
            NodeService::class.java.getDeclaredMethod("endFailureEpisode")
                .apply { isAccessible = true }.invoke(service)
            Unit
        }
        every { service invokeNoArgs "refreshNotification" } returns Unit
        fun setField(name: String, value: Any) {
            NodeService::class.java.getDeclaredField(name).apply {
                isAccessible = true
                set(service, value)
            }
        }
        setField("tag", "NodeService")
        setField("processManager", manager)
        setField("isServiceRunning", true)
        setField("lastReadyAt", NEVER_READY_AT)
        val gaveUp = mutableListOf<Boolean>()
        service.onServerGaveUp = { gaveUp += it }
        return CrashHarness(service, manager, gaveUp)
    }

    @Test
    fun `a readiness timestamp from an earlier server does not pardon a later crash loop`() {
        var clock = 10_000L
        val h = buildCrashHarness { clock }
        try {
            // First server becomes ready at t = 10s, then dies 10s later (under READY_STABLE_MS).
            h.setLastReadyAt(10_000L)
            clock = 20_000L
            h.crash()
            assertEquals(1, h.getRestartCount())
            assertEquals(NEVER_READY_AT, h.getLastReadyAt(), "the dead server's readiness must be consumed")

            // Replacement servers never become ready and keep crashing past t = 10s + READY_STABLE_MS.
            for ((step, t) in listOf(22_000L, 26_000L, 34_000L, 50_000L).withIndex()) {
                clock = t
                h.crash()
                assertEquals(
                    step + 2,
                    h.getRestartCount(),
                    "crash at t=${t}ms must not inherit the first server's readiness timestamp",
                )
                assertTrue(h.gaveUp.isEmpty(), "must not give up before MAX_RESTARTS")
            }

            // Sixth crash at t = 82s (72s after the first server's readiness) must enter terminal state
            // instead of refilling the budget from the first server's stale lastReadyAt.
            clock = 82_000L
            h.crash()
            assertEquals(listOf(true), h.gaveUp, "sixth crash must reach enterTerminalState instead of resetting")
        } finally {
            unmockkAll()
        }
    }

    @Test
    fun `a server that ran past the stability window pardons its own death once and not its successors`() {
        var clock = 10_000L
        val h = buildCrashHarness { clock }
        try {
            h.setRestartCount(3)
            h.setLastReadyAt(10_000L)

            // Dies after READY_STABLE_MS: its own death resets the failure episode before spending an attempt.
            clock = 10_000L + READY_STABLE_MS + 5_000L
            h.crash()
            assertEquals(1, h.getRestartCount(), "a stable server's death resets the episode and records restart #1")
            assertEquals(NEVER_READY_AT, h.getLastReadyAt())

            // Next replacement dies immediately without ever becoming ready: must NOT reset again.
            clock += 5_000L
            h.crash()
            assertEquals(2, h.getRestartCount(), "an unready successor must not get a second pardon")
        } finally {
            unmockkAll()
        }
    }

    @Test
    fun `a restarted server that announces ready and runs past the stability window earns its own recovery`() {
        var clock = 10_000L
        val h = buildCrashHarness { clock }
        try {
            h.setLastReadyAt(10_000L)
            clock = 20_000L
            h.crash()
            assertEquals(1, h.getRestartCount())
            assertEquals(NEVER_READY_AT, h.getLastReadyAt())

            // Replacement announces ready at t = 25s and runs past READY_STABLE_MS.
            clock = 25_000L
            val announce = NodeService::class.java.getDeclaredMethod("announceReady").apply { isAccessible = true }
            announce.invoke(h.service)
            assertEquals(25_000L, h.getLastReadyAt())

            clock = 25_000L + READY_STABLE_MS + 1_000L
            h.crash()
            assertEquals(1, h.getRestartCount(), "the replacement earned its own stability recovery")
            assertEquals(NEVER_READY_AT, h.getLastReadyAt())
        } finally {
            unmockkAll()
        }
    }
}
