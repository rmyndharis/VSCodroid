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
 *
 * The token is not the only credential on this stream, which is why
 * [redactSecrets] runs beside it. What the bootstrap prints is not only its own
 * output: `assets/server.js` forks the editor server with `stdio: 'inherit'`,
 * and the editor server echoes the extension host's stdout and stderr into its
 * console (`ExtensionHostConnection` logs every chunk as `<pid>` and
 * `<pid><stderr>`), so anything an extension writes to either stream ends up
 * here. An extension that fails a request and dumps it prints whatever
 * authenticated that request, and a bug report is handed to a stranger verbatim.
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
     * Synchronized because this file has several writers, and one of them
     * rewrites it.
     *
     * The drain thread reading the server's stdout was the only caller until the
     * start summary began mirroring itself here, and that summary is written by
     * whichever thread started the server, after [startOutputReader] has already
     * put the drain on its own thread. A rotation is a read of the whole file
     * followed by a write of the whole file, so an append landing inside one is
     * written to a length that is about to stop existing, and the line is lost or
     * the file is left holding half of each. One line of contention against a
     * drain that is idle most of the time is not a cost worth the risk.
     *
     * The lock is [FILE_LOCK] and not this instance, because the instance is not
     * what is being protected. See there.
     */
    fun append(line: String) = synchronized(FILE_LOCK) {
        try {
            // One stat answers both questions, because length() is 0 for a file
            // that does not exist. The logs directory is created by the server
            // rather than by anything on this side, so the first line can arrive
            // before there is a directory to write it into.
            val size = file.length()
            if (size == 0L) file.parentFile?.mkdirs() else if (size > MAX_BYTES) rotate()
            file.appendText(redactSecrets(redactToken(line)) + "\n")
        } catch (_: IOException) {
        }
    }

    /**
     * The newest [n] lines, read while no rotation can be part-way through.
     *
     * Exists because the reader has to take the same lock as the writer. A
     * rotation is a truncate followed by a write of the tail, so a read that
     * lands between the two comes back short or empty, and an empty server
     * section in a report is indistinguishable from a server that had nothing
     * to say, which is the ambiguity this class was added to remove.
     *
     * Returns an empty list for a file that does not exist or cannot be read.
     * The caller is expected to say so rather than to omit the section, for the
     * same reason.
     */
    fun tail(n: Int): List<String> = synchronized(FILE_LOCK) {
        try {
            if (!file.isFile) emptyList() else file.readLines().takeLast(n)
        } catch (_: IOException) {
            emptyList()
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
         * Process-wide, because the file is and the instance is not.
         *
         * `ProcessManager` builds one [ServerLog] over `files/logs/server.log`
         * and `NodeService.onCreate` builds one `ProcessManager`, but a service
         * that has been stopped and started again is a second instance in the
         * same process, and a drain thread outliving the stop that was meant to
         * end it puts two writers on that one path. An instance monitor guards
         * neither of them against the other: whichever crosses [MAX_BYTES] first
         * rewrites the whole file while the other is appending to a length that
         * is about to stop existing. The reader in `CrashReporter` takes this
         * same lock through [tail] for the same reason.
         *
         * This is what the rest of this codebase already does once a second
         * instance became reachable: `SafSyncEngine.uploadJournalLock` and
         * `ToolchainManager.stateLock` are both companion-scoped for it.
         */
        val FILE_LOCK = Any()

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

/**
 * [line] with the credential shapes that are not the connection token taken out.
 *
 * `redactToken` covers exactly one secret, the server's own connection token, and
 * says so. That was the whole exposure while this file held only the bootstrap's
 * own chatter. It is not, now that the file mirrors everything the editor server
 * prints: the extension host's stdout and stderr are echoed into that console, so
 * an extension that authenticates, and that dumps a failing request or a stack
 * trace when it goes wrong, writes its own credential onto this stream. The file
 * is then quoted verbatim into a bug report the user is invited to paste
 * somewhere public.
 *
 * Shapes rather than values, because nothing here knows what any extension's
 * secret looks like. The name is kept and the value is replaced, so a reader can
 * still see which header or which key was involved, which is most of what the
 * line was worth diagnostically.
 *
 * The containment is worth stating as plainly as `redactToken`'s is. This catches
 * a credential that travels next to a name that suggests it, or that carries a
 * vendor prefix. A secret printed bare, with no name and no prefix, is
 * indistinguishable from any other word and passes through. Widening it further
 * would start eating the diagnostics the file exists for; narrowing what is
 * mirrored in the first place is the other direction, and it would throw away the
 * extension-host output that is often the only account of what went wrong.
 */
internal fun redactSecrets(line: String): String {
    var out = line
    for ((pattern, replacement) in SECRET_PATTERNS) {
        out = out.replace(pattern, replacement)
    }
    return out
}

/**
 * The patterns [redactSecrets] applies, each with what it leaves behind.
 *
 * Order matters in one place: the header rule runs before the bare-scheme rule so
 * that `Authorization: Bearer x` is consumed whole. Reversed, the scheme rule
 * would take `Bearer x` and leave the header rule matching nothing, which is
 * harmless here but stops being harmless for any scheme that is not recognised.
 *
 * The header value runs to the end of the line or to the next quote, comma or
 * brace, rather than to the next space: an authorization value has a space in it
 * by construction (`Bearer `, `Basic `), and stopping at the space leaves the
 * secret itself in the file.
 */
private val SECRET_PATTERNS: List<Pair<Regex, String>> = listOf(
    Regex("""(?i)(\bauthorization\b\s*["']?\s*[:=]\s*)["']?[^"',}]+""") to "\$1<redacted>",
    Regex("""(?i)(\b(?:bearer|basic)\s+)[A-Za-z0-9._~+/=-]{8,}""") to "\$1<redacted>",
    Regex(
        """(?i)(\b(?:api[_-]?key|apikey|access[_-]?token|refresh[_-]?token|auth[_-]?token""" +
            """|client[_-]?secret|password|passwd|secret|token)\b\s*["']?\s*[:=]\s*)""" +
            """["']?[^\s"',}&]+"""
    ) to "\$1<redacted>",
    // Vendor-prefixed keys, which are the ones that travel with no name beside
    // them at all: an npm or GitHub token pasted into a URL, or a key echoed by a
    // failing request body.
    Regex("""\b(?:sk|pk|rk)-[A-Za-z0-9_-]{16,}""") to "<redacted>",
    Regex("""\bgh[pousr]_[A-Za-z0-9]{16,}""") to "<redacted>",
)
