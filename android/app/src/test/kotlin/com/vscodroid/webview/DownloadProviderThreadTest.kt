package com.vscodroid.webview

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList

/**
 * That the thread a download borrows is given back.
 *
 * The provider calls nobody waits for (closing a document, deleting it, opening
 * the one the picker just made) are handed to a thread of the coordinator's own,
 * and there is one coordinator per Activity. Nothing tells that thread to stop:
 * a shutdown from `onDestroy` would refuse the hand-off that a thread still
 * inside the provider makes on its way out, and the user's part-written document
 * would stay in their folder, which is the one thing that hand-off exists to
 * remove. So the ending is the idle timeout, and these cases measure it rather
 * than trusting the constructor's argument list.
 *
 * A parked thread is not free on a device this app is trying to leave memory on,
 * and it used to last until the coordinator was garbage collected, which for a
 * living Activity that ran one download is never.
 *
 * NEGATIVE CONTROLS, measured rather than assumed:
 *  - `allowCoreThreadTimeOut(false)` in [providerWorkExecutor] reddens `the
 *    provider thread ends when the work does` and nothing else.
 *  - restoring `Executors.newSingleThreadExecutor { ... }` as the pool reddens
 *    both `the provider thread ends when the work does` and `the coordinator
 *    ships the executor these cases measure`.
 *  - dropping the queue and starting a thread per hand-off reddens `work handed
 *    over after the thread has gone still runs, and in order` and nothing else:
 *    such a thread does end, so the timeout case cannot speak for the ordering.
 */
class DownloadProviderThreadTest {

    /** Long enough that a scheduling hiccup is not read as a leak. */
    private val patienceMs = 10_000L

    private fun awaitDeath(thread: Thread) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(patienceMs)
        while (thread.isAlive && System.nanoTime() < deadline) Thread.sleep(10)
    }

    /** The thread the given executor ran its first piece of work on. */
    private fun workerOf(executor: java.util.concurrent.Executor): Thread {
        val seen = ArrayBlockingQueue<Thread>(1)
        executor.execute { seen.put(Thread.currentThread()) }
        return seen.poll(patienceMs, TimeUnit.MILLISECONDS)
            ?: throw AssertionError("the provider executor never ran anything")
    }

    @Test
    fun `the provider thread ends when the work does`() {
        val worker = workerOf(providerWorkExecutor(idleMs = 50))

        assertEquals("download-provider", worker.name) {
            "the thread is no longer the one this case is about"
        }
        assertTrue(worker.isDaemon) {
            "a non-daemon provider thread holds the process up on its way out"
        }

        awaitDeath(worker)
        assertFalse(worker.isAlive) {
            "the provider thread is still parked ${patienceMs}ms after the last download, " +
                "so every Activity that ever saved a file leaves one behind in a process " +
                "the foreground service keeps alive"
        }
    }

    @Test
    fun `work handed over after the thread has gone still runs, and in order`() {
        // The other half of the timeout. A pool that lets its thread go has to
        // make another one for the next download, and the ordering the callers
        // rely on has to survive that: a close is handed over before the discard
        // of the same document, and neither waits for the other.
        val executor = providerWorkExecutor(idleMs = 50)
        awaitDeath(workerOf(executor))

        val order = CopyOnWriteArrayList<String>()
        val done = CountDownLatch(2)
        // The first hand-off is made slow on purpose. Two tasks on two threads
        // come out in the right order most of the time by luck, and a control
        // that only sometimes reddens measures nothing; a serial executor is the
        // only one that still answers "close" first with this here.
        executor.execute { Thread.sleep(50); order.add("close"); done.countDown() }
        executor.execute { order.add("discard"); done.countDown() }

        assertTrue(done.await(patienceMs, TimeUnit.MILLISECONDS)) {
            "the executor stopped running work once its thread timed out, so a download " +
                "after an idle spell is never closed and its document never removed"
        }
        assertEquals(listOf("close", "discard"), order.toList()) {
            "the two provider calls no longer keep the order they were handed over in, " +
                "so a document can be deleted while it is still being closed"
        }
    }

    @Test
    fun `the coordinator ships the executor these cases measure`() {
        // The cases above build their own, so this is what ties them to the
        // default a real Activity gets. Read off the source because the field is
        // private and its type is the Executor interface, which is deliberate:
        // that is what lets a test hand the class an inline one.
        //
        // Comment lines are dropped first: the function these cases measure
        // explains at length which pool it is replacing and why, and a search
        // over the raw text is answered by that explanation rather than by the
        // code under it.
        val source = File("src/main/kotlin/com/vscodroid/webview/DownloadCoordinator.kt")
            .readLines()
            .filterNot {
                val t = it.trimStart()
                t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
            }
            .joinToString("\n")

        assertTrue(source.contains("private val providerWork: Executor = providerWorkExecutor()")) {
            "the coordinator's default provider executor is no longer the one measured " +
                "here, so these cases are testing a function nothing uses"
        }
        assertFalse(source.contains("newSingleThreadExecutor")) {
            "the pool is back to one whose thread parks until the coordinator is " +
                "collected, which for a living Activity that ran one download is never"
        }
    }
}
