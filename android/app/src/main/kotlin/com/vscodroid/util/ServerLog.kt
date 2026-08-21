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
     * Rewrites the file with its last [KEEP_LINES] lines.
     *
     * Truncating to empty would be shorter and wrong: a report taken shortly
     * after a rotation would carry almost nothing, which is the failure this
     * class exists to remove. Keeping more than the 200 lines the reader takes
     * leaves a margin, so a rotation just before a report still fills it.
     */
    private fun rotate() {
        val keep = file.readLines().takeLast(KEEP_LINES)
        file.writeText(keep.joinToString("\n", postfix = "\n"))
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
    }
}
