package com.vscodroid.service

import android.app.Notification
import android.content.Intent
import android.os.SystemClock
import androidx.core.app.ServiceCompat
import com.vscodroid.util.Logger
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.spyk
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

/**
 * Stop Server from the notification, run on a service whose framework half is mocked.
 *
 * Ending the server takes up to seconds (the bootstrap's own exit, an editor it left behind, a
 * temporary origin's listener), and it ran on the main thread, which froze the screen for that long.
 * It runs on a thread of its own now, with the foreground card kept until it has ended, and the
 * rest of the stop runs back on the main thread.
 */
class NodeServiceStopTest {
    private lateinit var service: NodeService
    private lateinit var processes: ProcessManager
    private val events = Collections.synchronizedList(mutableListOf<String>())
    private val posted = LinkedBlockingQueue<Runnable>()
    private val release = CountDownLatch(1)
    private val stopping = CountDownLatch(1)

    @BeforeEach
    fun setUp() {
        mockkObject(Logger)
        every { Logger.i(any(), any()) } just Runs
        every { Logger.w(any(), any(), any()) } just Runs
        every { Logger.e(any(), any(), any()) } just Runs
        mockkStatic(ServiceCompat::class)
        every { ServiceCompat.stopForeground(any(), any()) } answers { events += "stopForeground ${secondArg<Int>()}" }
        service = mockk(relaxed = true)
        processes = mockk(relaxed = true)
        every { processes.stopServer() } answers {
            events += "stopServer on ${Thread.currentThread().name}"
            stopping.countDown()
            release.await(10, TimeUnit.SECONDS)
            Unit
        }
        every { processes.releaseAwayOrigin() } answers { events += "releaseAwayOrigin on ${Thread.currentThread().name}" }
        NodeService::class.java.getDeclaredField("processManager").apply { isAccessible = true }.set(service, processes)
        every { service.mainExecutor } returns Executor { posted += it }
        every { service["shutdown"]() } answers { callOriginal() }
        every { service["stopEnded"]() } answers { callOriginal() }
        every { service.onStartCommand(any(), any(), any()) } answers { callOriginal() }
        every { service["promoteToForeground"](any<Boolean>()) } returns true
        every { service["removeNotification"]() } answers { events += "removeNotification" }
        every { service["launchServer"]() } answers { events += "launchServer" }
        every { service.stopSelf() } answers { events += "stopSelf" }
        every { service.onServerStopped } returns { events += "told" }
    }

    @AfterEach
    fun tearDown() {
        release.countDown()
        unmockkAll()
    }

    private fun stop() = service.onStartCommand(command(NodeService.ACTION_STOP), 0, 1)

    private fun command(action: String?) = mockk<Intent> { every { this@mockk.action } returns action }

    /** The main thread's turn once the stop thread has handed its end over. */
    private fun runEnd() {
        val end = posted.poll(10, TimeUnit.SECONDS)
        assertTrue(end != null, "the stop never handed its end to the main thread")
        end!!.run()
    }

    @Test
    fun `stop from the notification returns while the server is still being stopped`() {
        val returned = CountDownLatch(1)
        Thread { stop(); returned.countDown() }.start()

        assertTrue(returned.await(2, TimeUnit.SECONDS), "the stop held the calling thread, the main one, until the server was down")
        assertTrue(stopping.await(5, TimeUnit.SECONDS), "the server was never stopped")
        assertEquals(listOf("stopServer on server-stop"), events.toList(), "the card went, or the editor was told, before the server was down")

        release.countDown()
        runEnd()

        assertEquals(
            listOf(
                "stopServer on server-stop",
                "releaseAwayOrigin on server-stop",
                "stopForeground ${ServiceCompat.STOP_FOREGROUND_REMOVE}",
                "removeNotification",
                "stopSelf",
                "told",
            ),
            events.toList(),
            "a temporary origin is given up after the server, and the card goes and the editor is told only then",
        )
    }

    @Test
    fun `a start that arrives while the stop runs is launched when it ends, and keeps the card`() {
        stop()
        service.onStartCommand(command(null), 0, 2)
        assertTrue("launchServer" !in events, "a start launched while the old server was still running is refused as one already running")

        release.countDown()
        runEnd()

        assertEquals(listOf("stopServer on server-stop", "releaseAwayOrigin on server-stop", "launchServer"), events.toList())
    }

    @Test
    fun `a second stop while the first runs adds nothing`() {
        stop()
        stop()
        release.countDown()
        runEnd()

        verify(exactly = 1) { processes.stopServer() }
        assertEquals(1, events.count { it == "stopSelf" })
        assertTrue(posted.isEmpty(), "two ends were handed to the main thread")
    }

    @Test
    fun `a stop that throws still stops the service`() {
        every { processes.stopServer() } throws IllegalStateException("the kill was refused")
        stop()
        runEnd()

        assertTrue(events.containsAll(listOf("stopSelf", "told")), "a failed stop left the service and its card up: ${events.toList()}")
    }

    /**
     * A temporary origin, bound because home was busy, is given up by the stop so that the next start
     * tries home. A stop that threw skipped that, and the process went on holding the temporary port
     * for the next start to inherit.
     */
    @Test
    fun `a stop that throws still gives up a temporary origin`() {
        every { processes.stopServer() } throws IllegalStateException("the kill was refused")
        stop()
        runEnd()

        assertTrue("releaseAwayOrigin on server-stop" in events, "a failed stop kept the temporary origin: ${events.toList()}")
    }

    /**
     * An error from the stop, not an exception, left the stop thread uncaught, and on Android that ends
     * the process: the person who pressed Stop got a crash instead, with the stop's last steps undone.
     */
    @Test
    fun `an error from the stop stays on its thread, and the stop still ends`() {
        val escaped = LinkedBlockingQueue<Throwable>()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, failure -> escaped += failure }
        try {
            every { processes.stopServer() } throws StackOverflowError()
            stop()
            runEnd()
            // The end is handed over before the thread leaves, so let it leave before asking what escaped.
            Thread.getAllStackTraces().keys.filter { it.name == "server-stop" }.forEach { it.join(5_000) }
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }

        assertTrue(escaped.isEmpty(), "the error left the stop thread: ${escaped.toList()}")
        assertTrue(
            events.containsAll(listOf("releaseAwayOrigin on server-stop", "stopSelf", "told")),
            "the stop did not end: ${events.toList()}",
        )
    }

    /**
     * The notice the last run left, a terminal one or a slow start, is about a server the person has
     * just stopped. Only a launch cleared it, and a start that arrives while the stop runs waits for
     * the stop to end before it launches, so a screen that bound meanwhile showed the stopped run's
     * failure instead of waiting for the new server.
     */
    @Test
    fun `a stop takes the last run's startup notice with it`() {
        every { service.lastStartupNotice() } answers { callOriginal() }
        NodeService::class.java.getDeclaredField("startupNotice").apply { isAccessible = true }
            .set(service, StartupNotice("the restart budget is spent", terminal = true))

        stop()
        service.onStartCommand(command(null), 0, 2)

        assertNull(service.lastStartupNotice(), "a screen that binds while the stop runs is shown the stopped run's notice")
    }

    /**
     * Setup's card is up for the minutes the unpack takes, and a Stop on it ran the whole stop: the
     * service left the foreground and stopped itself with setup still writing, the hold it takes against
     * the low-memory killer gone. No server runs behind that card, so it offers no Stop.
     */
    @Test
    fun `setup's card offers no Stop, and the server's card does`() {
        val offered = mutableListOf<Boolean>()
        every { service["promoteToForeground"](any<Boolean>()) } answers { callOriginal() }
        every { ServiceCompat.startForeground(any(), any(), any(), any()) } just Runs
        every { service["createNotification"](any<String>(), any<String>(), any<Boolean>(), any<Boolean>()) } answers {
            offered += arg<Boolean>(3)
            mockk<Notification>()
        }

        service.onStartCommand(command(NodeService.ACTION_HOLD), 0, 1)
        service.onStartCommand(command(null), 0, 2)

        assertEquals(listOf(false, true), offered, "setup's card and the server's card, in that order")
    }

    /**
     * The service with its real crash handling, stopped by the notification and started again while
     * the stop thread still runs, which leaves the start to [NodeService.stopEnded]. Only the framework
     * half is stubbed, as in [setUp].
     */
    private fun restartedDuringStop(): NodeService {
        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } returns 1_000L
        every { processes.serverEpoch } returns EPOCH
        every { processes.heapOverrideValueInEffect(any()) } returns null
        val running = spyk(mockk<NodeService>(relaxed = true))
        for ((name, value) in listOf("tag" to "NodeService", "processManager" to processes)) {
            NodeService::class.java.getDeclaredField(name).apply { isAccessible = true }.set(running, value)
        }
        every { running.mainExecutor } returns Executor { posted += it }
        every { running["promoteToForeground"](any<Boolean>()) } returns true
        every { running["removeNotification"]() } answers { events += "removeNotification" }
        every { running["launchServer"]() } answers { events += "launchServer" }
        every { running.stopSelf() } answers { events += "stopSelf" }
        running.onStartCommand(command(NodeService.ACTION_STOP), 0, 1)
        running.onStartCommand(command(null), 0, 2)
        return running
    }

    private fun restartCount(service: NodeService) =
        NodeService::class.java.getDeclaredField("restartCount").apply { isAccessible = true }.getInt(service)

    /**
     * Calls a private suspending member of [service] and reports whether it ran to its end without
     * suspending. A retry suspends in its backoff, so a call that stays suspended scheduled one.
     */
    private fun completes(service: NodeService, name: String, vararg args: Int): Boolean = runBlocking {
        val types = args.map { Int::class.javaPrimitiveType } + Continuation::class.java
        val member = NodeService::class.java.getDeclaredMethod(name, *types.toTypedArray()).apply { isAccessible = true }
        val call = launch(start = CoroutineStart.UNDISPATCHED) {
            suspendCoroutineUninterceptedOrReturn<Unit> { member.invoke(service, *args.toTypedArray(), it) }
        }
        val done = call.isCompleted
        call.cancel()
        done
    }

    /**
     * A crash report already on its way to the service when Stop was pressed lands after a start that
     * arrived while the stop thread runs. The start is the stop's to launch when it ends, so a retry
     * here would be a second chain beside that launch, spending the new run's budget on the old
     * server's exit.
     */
    @Test
    fun `a crash report that lands while the stop runs schedules no restart`() {
        val running = restartedDuringStop()

        assertTrue(completes(running, "handleServerCrash", 137, EPOCH), "a retry is waiting out its backoff beside the stop's launch")
        assertEquals(0, restartCount(running), "the old server's exit spent a restart of the new run")
        // The report is turned away at its door: it does not reach the heap ceiling's count of kills
        // either, which would charge the old server's exit to the person's chosen ceiling.
        verify(exactly = 0) { processes.heapOverrideValueInEffect(any()) }

        release.countDown()
        runEnd()
        assertEquals(listOf("launchServer"), events.filter { it == "launchServer" }, "the start was not launched once, by the stop's end")
    }

    /** The same decision on the start path's way in, which shares it. */
    @Test
    fun `an unreported start while the stop runs schedules no restart`() {
        val running = restartedDuringStop()

        assertTrue(completes(running, "retryOrGiveUp"), "a retry is waiting out its backoff beside the stop's launch")
        assertEquals(0, restartCount(running), "a restart was spent while the stop runs")
    }

    @Test
    fun `setup's hold given back during a stop leaves the card to the stop`() {
        stop()
        service.onStartCommand(command(NodeService.ACTION_RELEASE_HOLD), 0, 2)
        assertTrue(stopping.await(5, TimeUnit.SECONDS), "the server was never stopped")
        assertEquals(listOf("stopServer on server-stop"), events.toList(), "the foreground was left with the server still being stopped")

        release.countDown()
        runEnd()
        assertEquals(1, events.count { it == "stopSelf" })
    }

    private companion object {
        const val EPOCH = 3
    }
}
