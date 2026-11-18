package com.vscodroid.storage

import com.vscodroid.util.Logger
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Moves an entry of a directory to another name in it, in one step, only while that name is free.
 *
 * A rename replaces whatever stands at the new name, and a look at the name before it leaves a
 * moment in which an entry made there is lost. The call that refuses a taken name instead,
 * renameat2 with RENAME_NOREPLACE, is not among the ones a Kotlin program has, so it is made by
 * a program of the app's own, `libmove-noreplace.so` (`scripts/move-noreplace.c`).
 */
internal object ExclusiveMove {
    private const val TAG = "ExclusiveMove"
    private const val WAIT_MILLIS = 10_000L

    // How often the helper is asked whether it has ended. It ends within a few milliseconds of
    // its start, and the platform's own timed wait looks once and then sleeps a tenth of a
    // second: eight moves took 0.74 s on an API 29 and an API 33 emulator.
    private const val POLL_MILLIS = 2L

    /** The helper's exit status for a taken name; `scripts/move-noreplace.c` says why not 1. */
    internal const val TAKEN_STATUS = 73

    // How java.nio prints the identity of a file on Linux: the device in hexadecimal, the inode
    // in decimal. Read from the text because the type that holds the two numbers is not public.
    private val FILE_KEY = Regex("""\(dev=([0-9a-f]+),ino=(\d+)\)""")

    enum class Outcome {
        /** The entry now stands at the new name. */
        MOVED,

        /** The new name is taken, and nothing moved. */
        TAKEN,

        /**
         * The helper did not make the move, and nothing moved: it is not in this build, it could
         * not be started, or it ended by itself saying the kernel or the filesystem refused.
         */
        UNAVAILABLE,

        /** The helper was started and did not end by itself, so the entry may have moved. */
        UNKNOWN,
    }

    /**
     * Moves [from] to [to] with [helper] and says how it ended.
     *
     * [key] is the file key of the directory the caller holds open, and [directory] a path to
     * it. The helper opens the path and moves only inside a directory with that identity, so a
     * path swapped under the caller cannot send the move elsewhere. [from] and [to] are names.
     * A helper still running after [waitMillis] is ended.
     */
    fun move(
        helper: File, directory: File, key: Any, from: String, to: String, waitMillis: Long = WAIT_MILLIS,
    ): Outcome {
        val identity = FILE_KEY.matchEntire(key.toString()) ?: return Outcome.UNAVAILABLE
        val device = java.lang.Long.toUnsignedString(java.lang.Long.parseUnsignedLong(identity.groupValues[1], 16))
        val process = try {
            ProcessBuilder(helper.path, directory.path, device, identity.groupValues[2], from, to)
                .redirectErrorStream(true)
                .start()
        } catch (_: IOException) {
            return Outcome.UNAVAILABLE
        }
        var interrupted = false
        // The move is made or refused within a moment of the start. A caller interrupted
        // meanwhile still has to learn which, or it would look for the entry where it was.
        fun ended(): Boolean {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(waitMillis)
            while (true) {
                try {
                    process.exitValue()
                    return true
                } catch (_: IllegalThreadStateException) {
                    // Still running.
                }
                if (System.nanoTime() >= deadline) return false
                try {
                    Thread.sleep(POLL_MILLIS)
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
        }
        try {
            runCatching { process.outputStream.close() }
            if (!ended()) {
                // Ended from here, so that it cannot make the move after the caller has gone on.
                process.destroyForcibly()
                if (!ended()) {
                    Logger.w(TAG, "The move helper did not end")
                    return Outcome.UNKNOWN
                }
            }
            return when (val status = process.exitValue()) {
                0 -> Outcome.MOVED
                TAKEN_STATUS -> Outcome.TAKEN
                else -> {
                    val said = runCatching { process.inputStream.bufferedReader().readText().trim() }.getOrDefault("")
                    Logger.w(TAG, "The move helper ended with $status: $said")
                    // Past 128 a signal ended it, and nothing says how far it had got.
                    if (status > 128) Outcome.UNKNOWN else Outcome.UNAVAILABLE
                }
            }
        } finally {
            process.destroy()
            if (interrupted) Thread.currentThread().interrupt()
        }
    }
}
