package com.vscodroid.setup

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The thread each [ToolchainManager] does its file work on, and how long it
 * lives.
 *
 * Nothing shuts one of these down and nothing can: five call sites each build
 * their own manager where they stand, and none of them owns the object long
 * enough to know when the work is finished. `SplashActivity.onCreate` alone
 * builds two and submits on both, on every launch, into a process the foreground
 * service keeps alive. With `Executors.newSingleThreadExecutor`, whose thread is
 * a core thread and therefore never times out, every launch parked two more
 * threads that had already finished their work, on a device where the project
 * budgets both memory and process count.
 *
 * Serialisation is the property that must survive the change, and it is why the
 * pool is not simply allowed to grow: the copy into `usr/`, the record write and
 * the queued `removePack` in `cancel` all depend on running one after another.
 */
class ToolchainIoExecutorTest {

    /**
     * The configuration the production executor is built with, asserted rather
     * than waited out: the real keep-alive is half a minute and a test that slept
     * through it would be the slowest in the suite.
     *
     * The behaviour behind these values is measured in the case below, against a
     * short keep-alive.
     */
    @Test
    fun `the production executor is one thread that times out`() {
        val executor = toolchainIoExecutor()
        try {
            assertTrue(
                executor.allowsCoreThreadTimeOut(),
                "the thread is a core thread, so it is never reclaimed and every launch " +
                    "leaves another one parked for the life of the process",
            )
            assertEquals(1, executor.corePoolSize, "more than one thread would stop serialising")
            assertEquals(1, executor.maximumPoolSize, "the pool may grow, so work can overlap")
            assertEquals(
                IO_THREAD_KEEPALIVE_MS,
                executor.getKeepAliveTime(TimeUnit.MILLISECONDS),
                "the keep-alive is not the one this file documents",
            )
        } finally {
            executor.shutdownNow()
        }
    }

    /**
     * And the behaviour those values buy, driven at a keep-alive short enough to
     * watch.
     *
     * Dropping `allowCoreThreadTimeOut(true)` turns this red: the pool stays at
     * one thread for as long as the executor exists, which is as long as the
     * process does.
     */
    @Test
    fun `a thread that has nothing left to do is given back`() {
        val executor = toolchainIoExecutor(keepAliveMs = 50)
        try {
            val ran = CountDownLatch(1)
            executor.execute { ran.countDown() }
            assertTrue(ran.await(10, TimeUnit.SECONDS), "the task never ran")
            assertEquals(1, executor.poolSize, "no thread was created to run it")

            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (executor.poolSize > 0 && System.nanoTime() < deadline) Thread.sleep(10)

            assertEquals(
                0, executor.poolSize,
                "the idle thread was never reclaimed, so a manager nobody shuts down " +
                    "keeps one parked for the life of the process",
            )
        } finally {
            executor.shutdownNow()
        }
    }

    /**
     * The control, and the property the change had to preserve.
     *
     * The copy into `usr/`, the record write and the `removePack` that `cancel`
     * queues behind them all rely on running one after another on one thread. An
     * executor that reclaims an idle thread must still refuse to run two tasks at
     * once, and must still run them in the order they were submitted.
     */
    @Test
    fun `work is still serialised on one thread, in order`() {
        val executor = toolchainIoExecutor(keepAliveMs = 50)
        try {
            val order = Collections.synchronizedList(mutableListOf<Int>())
            val threads = Collections.synchronizedSet(mutableSetOf<String>())
            val done = CountDownLatch(20)
            val start = CountDownLatch(1)

            // Queued behind a task that is held, so all twenty are waiting before
            // any of them runs: otherwise each could finish before the next is
            // submitted and one thread would be the only possible outcome
            // whatever the pool is configured to do.
            executor.execute { start.await(10, TimeUnit.SECONDS) }
            for (n in 0 until 20) {
                executor.execute {
                    threads.add(Thread.currentThread().name)
                    order.add(n)
                    done.countDown()
                }
            }
            start.countDown()

            assertTrue(done.await(10, TimeUnit.SECONDS), "not every task ran: $order")
            assertEquals(
                setOf("toolchain-io"), threads.toSet(),
                "work ran on more than one thread, so two installs can overlap",
            )
            assertEquals((0 until 20).toList(), order.toList(), "work ran out of order")
        } finally {
            executor.shutdownNow()
        }
    }
}
