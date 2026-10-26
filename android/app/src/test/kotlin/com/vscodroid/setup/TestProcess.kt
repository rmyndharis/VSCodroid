package com.vscodroid.setup

import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/** Collects merged output without waiting for EOF from an inherited pipe. */
internal fun finishTestProcess(
    process: Process,
    timeoutMillis: Long = 30_000,
    maxOutputBytes: Int = 1024 * 1024,
): Pair<Int, String> {
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
    val workDeadline = deadline - TimeUnit.MILLISECONDS.toNanos(minOf(1_000, timeoutMillis / 4))
    val output = ByteArrayOutputStream()
    var interrupted = false
    try {
        require(timeoutMillis >= 4 && maxOutputBytes > 0)
        process.outputStream.close()
        val input = process.inputStream
        val buffer = ByteArray(8192)
        fun checkTime() {
            if (System.nanoTime() >= workDeadline) {
                throw AssertionError("Command timed out after ${timeoutMillis}ms. Output: ${output.toString("UTF-8")}")
            }
        }
        fun drainAvailable() {
            var available = input.available()
            while (available > 0) {
                checkTime()
                val count = input.read(buffer, 0, minOf(available, buffer.size))
                if (count < 0) break
                if (output.size().toLong() + count > maxOutputBytes) {
                    throw AssertionError("Command output exceeded $maxOutputBytes bytes")
                }
                output.write(buffer, 0, count)
                available = input.available()
            }
        }
        while (true) {
            checkTime()
            drainAvailable()
            val remaining = workDeadline - System.nanoTime()
            if (process.waitFor(minOf(remaining.coerceAtLeast(0), TimeUnit.MILLISECONDS.toNanos(10)), TimeUnit.NANOSECONDS)) {
                drainAvailable()
                return process.exitValue() to output.toString("UTF-8")
            }
        }
    } catch (e: InterruptedException) {
        interrupted = true
        throw e
    } finally {
        interrupted = Thread.interrupted() || interrupted
        try {
            if (process.isAlive) process.destroyForcibly()
            while (true) {
                try {
                    if (process.waitFor((deadline - System.nanoTime()).coerceAtLeast(0), TimeUnit.NANOSECONDS)) break
                    throw AssertionError("Command could not be reaped within ${timeoutMillis}ms")
                } catch (e: InterruptedException) {
                    interrupted = true
                    if (System.nanoTime() >= deadline) throw AssertionError("Interrupted while reaping command", e)
                }
            }
        } finally {
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
            runCatching { process.outputStream.close() }
            if (interrupted) Thread.currentThread().interrupt()
        }
    }
}
