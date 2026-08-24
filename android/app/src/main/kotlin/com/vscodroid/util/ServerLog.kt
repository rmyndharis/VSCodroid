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
 * [text] with the credential shapes that are not the connection token taken out.
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
 * One line at a time, whatever the caller hands over. Every pattern below is
 * written against a single line and several of them cross a newline when they
 * are given one: the header rule's value class excludes quotes, commas and
 * braces but not newlines, and the `\s+` in the scheme rule and the `\s*` in the
 * header and named-key rules match a newline like any other space, so either can
 * reach past the end of a line and take the first word of the next one. That was
 * invisible while every caller held one line, and [CrashReporter] hands whole
 * crash files here, where
 * one `authorization:` in a throwable's message takes the stack trace under it
 * as far as the next quote, comma or brace, none of which a Java stack trace
 * contains. A report whose crash section is one truncated line is worse than one
 * carrying the credential, because it still reads as a complete crash log under
 * a header that counts it. Settled here rather than inside each pattern, so the
 * next pattern added cannot forget it.
 *
 * The containment is worth stating as plainly as `redactToken`'s is. This catches
 * a credential that travels next to a name that suggests it, or that carries a
 * vendor prefix. A secret printed bare, with no name and no prefix, is
 * indistinguishable from any other word and passes through. So is a name with a
 * letter welded onto the front of the keyword, because the absence of a boundary
 * there is the same thing that keeps `mytoken=` out; `PGPASSWORD` is the one name
 * of that shape common enough to be listed by hand, and another vendor's spelling
 * of it would need listing too. A private key printed as its own lines, one run
 * of base64 after another between its `-----BEGIN` and `-----END` markers, is the
 * bare case too: the markers name it, but the lines between carry no name, and
 * this runs one line at a time with nothing kept from the line before, since
 * [ServerLog.append] is handed each line as the drain reads it. Only a key
 * serialised onto one line, the shape `JSON.stringify` gives it, is caught.
 * Widening it further would start eating the diagnostics the file exists for;
 * narrowing what is mirrored in the first place is the other direction, and it
 * would throw away the extension-host output that is often the only account of
 * what went wrong.
 */
internal fun redactSecrets(text: String): String =
    if ('\n' !in text) redactOneLine(text)
    else text.split('\n').joinToString("\n") { redactOneLine(it) }

private fun redactOneLine(line: String): String {
    var out = line
    for ((pattern, replacement) in SECRET_PATTERNS) {
        out = out.replace(pattern, replacement)
    }
    return out
}

/**
 * The patterns [redactSecrets] applies, each with what it leaves behind.
 *
 * Order matters in one place: the header rule runs before the bare-scheme rule.
 * Not because `Authorization: Bearer x` needs consuming whole, which either order
 * does: the scheme rule leaves `Authorization: Bearer <redacted>`, and the header
 * value class still matches `Bearer <redacted>` and still collapses it. The reason
 * is that the scheme rule's token class accepts letters, so it can eat the word
 * the header rule anchors on where the two abut. Run first, it turns
 * `Bearer authorization:Bearer` into `Bearer <redacted>:Bearer` and leaves the
 * trailing credential standing; run second, that line ends `<redacted>:<redacted>`.
 *
 * The header value runs to the end of the line or to the next quote, comma or
 * brace, rather than to the next space: an authorization value has a space in it
 * by construction (`Bearer `, `Basic `), and stopping at the space leaves the
 * secret itself in the file. The end of the line is where it stops because
 * [redactSecrets] never hands a pattern more than one line, not because this
 * class excludes a newline. It does not.
 *
 * `cookie` and `set-cookie` share the header rule because their value has the
 * same shape: `name=value; name=value`, every pair of which is something a
 * server handed out to identify this client, so the whole run goes. An
 * extension that dumps a failing request prints its headers, and the cookie
 * line is where a session lands. The editor's own `vscode-tkn` cookie is the
 * one cookie `redactToken` already reaches, because its pattern is unanchored
 * and says so; every other cookie on this stream had no rule at all.
 *
 * The quote around a name or a value may carry a backslash, `(?:\\?["'])?` and
 * `\\?"`, because a body that was serialised and then quoted into an error
 * message arrives with `\"` around every key: `JSON.stringify(err)` where
 * `err.message` already held a JSON body, which is what a fetch wrapper does
 * with the response it failed on. Without it the optional quote matched
 * nothing, the `\s*` met a backslash where the separator had to be, and the
 * line went to disk whole.
 *
 * The named-key rule reads its keyword as one segment of a name, not as a whole
 * word. `\b` is defined over `[A-Za-z0-9_]`, so an underscore is a word character
 * and no boundary exists inside `DB_PASSWORD` or `AWS_SECRET_ACCESS_KEY`: anchored
 * that way the rule fired on `password=` and on almost nothing an environment
 * variable is ever called, which is the shape a Node server prints and the shape
 * an extension leaks. The lookbehind is over letters and digits only, so a `_` or
 * `-` before the keyword is a boundary while a letter is not, and `mytoken=` or
 * `InvalidTokenError:` is still left alone. `pgpassword` is spelled out for that
 * same reason: libpq's variable welds its prefix on with no separator, so the
 * lookbehind refuses it exactly as it refuses `mytoken`, and it is the one name
 * of that shape common enough on this stream to be worth naming.
 *
 * The rest of the name is one optional class, `(?:[_-][A-Za-z0-9_-]*)?`, and not
 * a repeated group. java.util.regex compiles a quantified group into a `Loop`
 * that recurses once per repetition, so `(?:[_-][A-Za-z0-9]+)*` raises
 * StackOverflowError on a long enough run of segments: measured here at 2000 of
 * them on a 1 MiB stack. That is an `Error`, so it passes through the
 * `IOException` catch in [ServerLog.append] and the `Exception` catch in
 * `ProcessManager.startOutputReader`, reaching the default handler, which kills
 * the process the editor runs in. A character class repeats iteratively and has
 * no such limit to find: 500000 segments on the same stack return normally.
 * Nothing guards the right-hand end of the name, because nothing has to: whatever
 * follows it has to be the `[:=]` this rule is built around, and no separator is
 * a letter, so a keyword with letters welded onto its right (`Tokenizer: 12
 * tokens`) cannot reach one.
 *
 * A quoted value is taken whole, closing quote included, and is tried before the
 * unquoted alternative. Unquoted, the value has to stop at the first space or it
 * would eat the rest of the line, and that leaves a passphrase written down from
 * its second word on, beside a `<redacted>` telling the reader it was handled. A
 * closing quote says where the value ends without guessing, so
 * `DB_PASSWORD="correct horse battery staple"` goes as far as that quote. An
 * unterminated quote matches no alternative of that kind and falls through to the
 * unquoted one, which is what it did before.
 *
 * The cost of accepting the name's remaining segments is that an ordinary
 * segmented word in front of a `:` or an `=` loses what follows it, on both
 * separators and after a flag: `secret-storage: initialised`,
 * `password_hash_algorithm: bcrypt` and `--secret-file=/etc/k.pem` each lose the
 * word after the separator. The value class excludes whitespace but not `:`, so
 * what goes is the whole space-delimited run and any punctuation inside it, which
 * is one word rather than one line, but is more than the name alone suggests.
 */
private val SECRET_PATTERNS: List<Pair<Regex, String>> = listOf(
    Regex(
        """(?i)(\b(?:authorization|cookie|set-cookie)\b\s*(?:\\?["'])?\s*[:=]\s*)""" +
            """(?:\\?["'])?[^"',}]+"""
    ) to "\$1<redacted>",
    Regex("""(?i)(\b(?:bearer|basic)\s+)[A-Za-z0-9._~+/=-]{8,}""") to "\$1<redacted>",
    Regex(
        """(?i)((?<![A-Za-z0-9])(?:api[_-]?key|apikey|access[_-]?token|refresh[_-]?token""" +
            """|auth[_-]?token|client[_-]?secret|private[_-]?key|pgpassword|password""" +
            """|passwd|secret|token)""" +
            """(?:[_-][A-Za-z0-9_-]*)?\s*(?:\\?["'])?\s*[:=]\s*)""" +
            """(?:\\?"[^"]*"|\\?'[^']*'|["']?[^\s"',}&]+)"""
    ) to "\$1<redacted>",
    // A private key serialised onto one line, which is the shape `JSON.stringify`
    // gives it: the body is base64 and `\n` escapes, hundreds of characters of it
    // for any real key, so the floor of 40 leaves a diagnostic that merely names
    // the marker (`expected a -----BEGIN PRIVATE KEY----- header`) alone. Only
    // private keys; a certificate or a public key dumped by a failing TLS
    // handshake is the diagnostic, not the secret.
    Regex("""(-----BEGIN [A-Z ]*PRIVATE KEY-----)[A-Za-z0-9+/=\s\\]{40,}""") to "\$1<redacted>",
    // Vendor-prefixed keys, which are the ones that travel with no name beside
    // them at all: an npm or GitHub token pasted into a URL, or a key echoed by a
    // failing request body.
    Regex("""\b(?:sk|pk|rk)-[A-Za-z0-9_-]{16,}""") to "<redacted>",
    Regex("""\bgh[pousr]_[A-Za-z0-9]{16,}""") to "<redacted>",
)
