package com.vscodroid.util

import com.vscodroid.webview.redactToken
import java.io.File
import java.io.IOException

/**
 * Mirrors the Node server's output into `server.log` so a bug report carries it.
 *
 * [CrashReporter] has always appended the last 200 lines of that file to every
 * report, and nothing ever wrote it, so every report shipped with no server
 * output at all. Among the lines lost that way is the one that identifies a
 * whole class of failure: a second server that loses the race for the port
 * prints `EADDRINUSE` and keeps running, and the note above
 * `ProcessManager.startServer` describes what that costs. Until now that line
 * reached `Logger.d` and stopped there, which in a release build is nowhere.
 *
 * Written straight through rather than batched. A server about to die says why
 * in its last few lines, so a buffer flushed on a cadence drops precisely the
 * part anyone reads. The cost is one append per line, the same order as the
 * `Logger.d` already on that path, over a stream that is startup chatter and
 * occasional warnings rather than a hot loop.
 *
 * Redacted on the way in, not only on the way out. The connection token is a
 * live credential and a report is something a user hands to a stranger, so it
 * should not reach the file in the first place. [CrashReporter] still redacts
 * what it reads and now finds nothing to replace; the overlap is deliberate,
 * because it is what keeps that reader correct if anything else ever writes here.
 */
internal class ServerLog(private val file: File) {

    /**
     * Appends one line, rotating first if the file has outgrown [MAX_BYTES].
     *
     * Swallows I/O failure on purpose. This runs on the output reader thread,
     * whose real job is to drain the process's stdout, and an exception escaping
     * here ends that loop: the pipe buffer then fills and the server blocks on
     * its next write. A report missing its server output is the state this class
     * improves on, and it is not worth trading a running server for.
     */
    /**
     * Synchronized because this file has two writers, and one of them rewrites it.
     *
     * The drain thread reading the server's stdout was the only caller until the
     * start summary began mirroring itself here, and that summary is written by
     * whichever thread started the server, after [startOutputReader] has already
     * put the drain on its own thread. A rotation is a read of the whole file
     * followed by a write of the whole file, so an append landing inside one is
     * written to a length that is about to stop existing, and the line is lost or
     * the file is left holding half of each. One line of contention against a
     * drain that is idle most of the time is not a cost worth the risk.
     */
    @Synchronized
    fun append(line: String) {
        try {
            // One stat answers both questions, because length() is 0 for a file
            // that does not exist. The logs directory is created by the server
            // rather than by anything on this side, so the first line can arrive
            // before there is a directory to write it into.
            val size = file.length()
            if (size == 0L) file.parentFile?.mkdirs() else if (size > MAX_BYTES) rotate()
            file.appendText(redactToken(line) + "\n")
        } catch (_: IOException) {
        }
    }

    /**
     * Rewrites the file with its newest lines, at most [KEEP_LINES] of them and
     * at most [KEEP_BYTES] worth.
     *
     * Truncating to empty would be shorter and wrong: a report taken shortly
     * after a rotation would carry almost nothing, which is the failure this
     * class exists to remove. Keeping more than the 200 lines the reader takes
     * leaves a margin, so a rotation just before a report still fills it.
     *
     * The byte bound is what makes rotation terminate. A line bound alone says
     * nothing about the size of what it keeps, so [KEEP_LINES] fat lines can
     * still exceed [MAX_BYTES], leaving the file over the threshold the moment
     * it was rotated. Every following line then rotates again, and each of those
     * is a full read plus a full write on the thread draining the server's
     * stdout, which is the thread that must not fall behind.
     *
     * The newest line is kept even when it alone is over budget. It is the most
     * useful line in the file, and dropping it to satisfy a size bound would
     * throw away exactly what a report is read for. That costs one extra
     * rotation, not a permanent one: on the next line the oversized one is no
     * longer newest, so the budget drops it then.
     */
    private fun rotate() {
        val newestFirst = file.readLines().takeLast(KEEP_LINES).asReversed()
        var bytes = 0L
        var keep = 0
        for (line in newestFirst) {
            // Counted the way it is written back, the line's UTF-8 bytes plus
            // the newline it is joined with, or the budget is short by one byte
            // per line and by however much any non-ASCII output weighs.
            bytes += line.toByteArray().size + 1
            if (keep > 0 && bytes > KEEP_BYTES) break
            keep++
        }
        file.writeText(newestFirst.take(keep).asReversed().joinToString("\n", postfix = "\n"))
    }

    private companion object {
        /**
         * The cap is on bytes rather than lines so that one pathological line
         * cannot outgrow it, and it is generous enough that rotation is rare:
         * [KEEP_LINES] typical lines are a small fraction of it, so the file
         * spends most of its life holding far more than the reader will take.
         */
        const val MAX_BYTES = 256L * 1024
        const val KEEP_LINES = 400

        /**
         * What a rotation may retain, half the cap: enough that the 200 lines
         * the reader takes still survive a rotation at well over 600 bytes a
         * line, while the other half is left as room to fill, so the appends
         * that follow a rotation are appends rather than rotations of their own.
         *
         * No budget can promise both a line count and a size, so where they
         * conflict this one wins and the retained line count falls. Lines fat
         * enough for that to bite are the case this bound exists for.
         */
        const val KEEP_BYTES = MAX_BYTES / 2
    }
}
