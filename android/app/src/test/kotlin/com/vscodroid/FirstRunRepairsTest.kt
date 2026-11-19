package com.vscodroid

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * What a launch hands its repairs to, by behaviour: [repairFilesOffMain] with its dispatcher injected,
 * [oneLaunchRepairAtATime] from two threads and [SetupHolds]. The wiring that hands them over
 * is read in [SplashFirstRunRepairsTest].
 */
class FirstRunRepairsTest {

    private val io = Executors.newSingleThreadExecutor { Thread(it, "repairs-io") }
    private val main = Executors.newSingleThreadExecutor { Thread(it, "launch-main") }

    /** Without the coroutine the debug agent appends to it when assertions are on. */
    private fun threadName() = Thread.currentThread().name.substringBefore(" @")

    @AfterEach
    fun stop() {
        io.shutdownNow()
        main.shutdownNow()
    }

    @Test
    fun `the file repairs run on the I O dispatcher and what follows back on the caller's thread`() {
        var filesOn = ""
        var afterOn = ""
        runBlocking(main.asCoroutineDispatcher()) {
            repairFilesOffMain(
                io.asCoroutineDispatcher(),
                files = { filesOn = threadName() },
                after = { afterOn = threadName() },
            ) { true }
        }
        assertEquals("repairs-io", filesOn, "the file repairs ran on the launching thread")
        assertEquals("launch-main", afterOn, "what follows the repairs did not come back to the launching thread")
    }

    @Test
    fun `nothing follows the repairs once the activity is finishing or destroyed`() {
        var files = 0
        var after = 0
        runBlocking(main.asCoroutineDispatcher()) {
            repairFilesOffMain(io.asCoroutineDispatcher(), files = { files++ }, after = { after++ }) { false }
        }
        assertEquals(1, files, "the repairs did not run to their end")
        assertEquals(0, after, "setup went on for an activity that was gone")
    }

    @Test
    fun `a destroyed activity's repairs run to their end and nothing follows them`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var finished = false
        var after = false
        val job = CoroutineScope(main.asCoroutineDispatcher()).launch {
            repairFilesOffMain(
                io.asCoroutineDispatcher(),
                files = {
                    entered.countDown()
                    release.await(5, TimeUnit.SECONDS)
                    finished = true
                },
                after = { after = true },
            ) { true }
        }
        assertTrue(entered.await(5, TimeUnit.SECONDS), "the repairs never started")
        job.cancel()
        release.countDown()
        runBlocking { job.join() }
        assertTrue(finished, "the repairs were cut short")
        assertFalse(after, "setup went on after the scope of a destroyed activity was cancelled")
    }

    @Test
    fun `two launches never run their repairs at once`() {
        val inside = AtomicInteger()
        val most = AtomicInteger()
        val ready = CountDownLatch(2)
        val go = CountDownLatch(1)
        val launches = Executors.newFixedThreadPool(2)
        try {
            val done = (1..2).map {
                launches.submit {
                    ready.countDown()
                    go.await()
                    oneLaunchRepairAtATime {
                        most.accumulateAndGet(inside.incrementAndGet()) { a, b -> maxOf(a, b) }
                        Thread.sleep(150)
                        inside.decrementAndGet()
                    }
                }
            }
            ready.await()
            go.countDown()
            done.forEach { it.get(5, TimeUnit.SECONDS) }
        } finally {
            launches.shutdownNow()
        }
        assertEquals(1, most.get(), "two launches ran their file repairs at the same time")
    }

    @Test
    fun `the service's hold goes only with the last holder`() {
        SetupHolds.taken()
        SetupHolds.taken()
        assertFalse(SetupHolds.givenBack(), "a waiter's release ended the hold the other launch still needs")
        assertTrue(SetupHolds.givenBack(), "the last holder's release did not end the hold")
        assertTrue(SetupHolds.givenBack(), "a release beyond the holds kept the count from zero")
        SetupHolds.taken()
        assertTrue(SetupHolds.givenBack(), "an extra release left the count below zero, so a later hold never ended")
    }
}
