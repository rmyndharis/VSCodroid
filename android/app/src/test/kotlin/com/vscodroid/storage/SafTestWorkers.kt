package com.vscodroid.storage

import org.junit.jupiter.api.Assertions.assertFalse
import java.util.concurrent.ConcurrentLinkedQueue

/** Keeps fixture failures on the test thread and joins workers before mocks are removed. */
internal class SafTestWorkers {
    private val workers = mutableListOf<Thread>()
    private val failures = ConcurrentLinkedQueue<Throwable>()

    fun prepare(work: () -> Unit): Thread = Thread {
        try {
            work()
        } catch (failure: Throwable) {
            failures.add(failure)
        }
    }.apply {
        isDaemon = true
        workers.add(this)
    }

    fun start(work: () -> Unit): Thread = prepare(work).apply { start() }

    /** The caller releases any provider gates before finishing the workers. */
    fun finish() {
        workers.forEach { it.join(5_000) }
        workers.filter { it.isAlive }.forEach { it.interrupt() }
        workers.forEach { it.join(5_000) }
        assertFalse(workers.any { it.state == Thread.State.NEW }, "a fixture worker was never started")
        assertFalse(workers.any { it.isAlive }, "a fixture worker did not terminate")
        failures.firstOrNull()?.let { throw AssertionError("a fixture worker failed", it) }
    }
}
