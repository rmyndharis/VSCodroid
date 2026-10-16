package com.vscodroid.service

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import com.vscodroid.R
import com.vscodroid.util.Environment
import com.vscodroid.util.Logger
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.spyk
import io.mockk.unmockkAll
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
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
        setServiceField("lastReadyAt", NEVER_READY_AT)
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
            assertNull(service.lastStartupNotice(), "the replacement starts without an old warning")
            finishCommit.countDown()
            withTimeout(5_000) { handled.join() }

            assertEquals(3, kills.get(), "the old death must still be recorded")
            assertNull(service.lastStartupNotice(), "the old heap charge replaced the new run's notice")
            assertTrue(notices.isEmpty(), "the old heap charge notified the replacement run")
        } finally {
            finishCommit.countDown()
            withTimeout(5_000) { handled.join() }
            manager.stopServer()
            unmockkAll()
        }
    }
}
