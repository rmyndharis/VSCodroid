package com.vscodroid.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * [ServerLog] and the one line that connects it to the server's output.
 *
 * The connection matters as much as the class. A bug report that carries no
 * server output looks exactly like one whose server had nothing to say, so if
 * the call ever goes missing there is no symptom to notice and no other test
 * that would redden.
 */
class ServerLogTest {

    @TempDir
    lateinit var tempDir: File

    private fun logFile() = File(tempDir, "logs/server.log")

    @Test
    fun `each line lands in the file as its own line`() {
        val file = logFile()
        val log = ServerLog(file)

        log.append("listening on 41003")
        log.append("Extension host started")

        assertEquals(
            listOf("listening on 41003", "Extension host started"),
            file.readLines(),
            "CrashReporter reads this file by lines and takes the last 200 of them",
        )
    }

    /**
     * The directory is created by the server, from its own `--logsPath` argument,
     * so on a cold start the first line of output can be read before the server
     * has got that far.
     */
    @Test
    fun `the first line creates the directory it needs`() {
        val file = File(tempDir, "a/b/c/server.log")
        assertFalse(file.parentFile.exists(), "the directory must not exist yet")

        ServerLog(file).append("first")

        assertEquals(listOf("first"), file.readLines())
    }

    /**
     * The token is a live credential and a bug report is something a user hands
     * to a stranger. Redacting only in the reader would still leave it written
     * down on the device, where nothing ever removes it.
     */
    @Test
    fun `the connection token never reaches the file`() {
        val file = logFile()

        ServerLog(file).append("GET /?folder=/home&tkn=8f3c1d2e-secret HTTP/1.1")

        val written = file.readText()
        assertFalse(
            "8f3c1d2e-secret" in written,
            "the token was written to disk verbatim: $written",
        )
        assertTrue("tkn=<redacted>" in written, "expected the redacted form, got: $written")
    }

    /**
     * This runs on the thread that drains the process's stdout. An exception
     * escaping ends that loop, after which the pipe buffer fills and the server
     * blocks on its next write, which is a far worse outcome than a thin report.
     */
    @Test
    fun `an unwritable destination does not take the reader thread down`() {
        // A regular file where the directory should be, so mkdirs cannot succeed
        // and the append that follows has nowhere to go.
        val blocked = File(tempDir, "blocked")
        blocked.writeText("not a directory")
        val file = File(blocked, "server.log")

        ServerLog(file).append("this cannot be written")

        assertFalse(file.exists(), "nothing should have been created under a regular file")
    }

    /**
     * Rotation keeps the tail rather than truncating to empty, because a report
     * taken shortly after a rotation would otherwise carry almost nothing, which
     * is the failure the class exists to remove.
     *
     * 300 KB is written to exceed the class's own cap. If that cap is ever raised
     * past this figure the case reddens by finding no rotation, which is the
     * direction that says so out loud.
     */
    @Test
    fun `rotation drops the oldest lines and keeps enough for a full report`() {
        val file = logFile()
        file.parentFile.mkdirs()
        val filler = "x".repeat(600)
        file.writeText((0 until 500).joinToString("\n", postfix = "\n") { "line-$it $filler" })
        val sizeBefore = file.length()
        assertTrue(sizeBefore > 256L * 1024, "the fixture must exceed the cap, was $sizeBefore")

        ServerLog(file).append("after rotation")

        val lines = file.readLines()
        assertTrue(file.length() < sizeBefore, "the file did not shrink, so nothing rotated")
        assertEquals("after rotation", lines.last(), "the appended line is missing")
        assertFalse(
            lines.any { it.startsWith("line-0 ") },
            "the oldest line survived, so the tail is not what was kept",
        )
        assertTrue(
            lines.last { it.startsWith("line-") }.startsWith("line-499 "),
            "the newest line before the append was dropped, so the wrong end was kept",
        )
        assertTrue(
            lines.size > 200,
            "CrashReporter takes the last 200 lines and a rotation left only " +
                "${lines.size}, so a report taken now would be short",
        )
    }

    /**
     * A rotation has to leave the file under the cap, or it has not rotated.
     *
     * Bounding what is kept by lines alone says nothing about their size, so a
     * stream of fat lines, which is what one stack trace or one JSON blob is,
     * leaves the file over the threshold the instant it was rotated. The next
     * line then rotates again, and so does every line after it, each one a full
     * read plus a full write on the thread draining the server's stdout.
     *
     * The observable for that is the file growing between appends. A file that
     * rotates on every line cannot grow: it is cut back to the same tail each
     * time and hovers at its rotated size, or drifts down as the oldest fat line
     * falls off the end.
     */
    @Test
    fun `fat lines leave the file under the cap and stop rotating`() {
        val file = logFile()
        file.parentFile.mkdirs()
        // Wide enough that the 400 lines the line bound keeps are themselves
        // over the 256 KiB cap, which is the whole of the defect.
        val filler = "x".repeat(700)
        file.writeText((0 until 500).joinToString("\n", postfix = "\n") { "line-$it $filler" })

        val log = ServerLog(file)
        val sizes = (0 until 5).map {
            log.append("tick-$it")
            file.length()
        }

        assertTrue(
            sizes.last() <= 256L * 1024,
            "the file is still over the cap after rotating, so the next line " +
                "rotates too, and so does every line after it: $sizes",
        )
        assertEquals(
            sizes.sorted(),
            sizes,
            "the file did not grow from one line to the next, so each of them " +
                "rewrote the whole file instead of appending to it: $sizes",
        )
        assertTrue(
            sizes.last() > sizes.first(),
            "five lines left the file no larger, so rotation is running on " +
                "every one of them: $sizes",
        )
        assertEquals(
            "tick-4",
            file.readLines().last(),
            "the newest line is not the last one in the file",
        )
    }

    /**
     * One line can be wider than the whole byte budget, and it is kept anyway.
     *
     * It is the newest thing the server said and therefore the most useful line
     * in the file, so a size bound is the wrong reason to lose it. The cost is
     * one further rotation rather than a permanent one: once it is no longer the
     * newest line the budget drops it, which is what the second half asserts.
     */
    @Test
    fun `a line wider than the budget survives its own rotation`() {
        val file = logFile()
        file.parentFile.mkdirs()
        file.writeText("giant " + "y".repeat(300 * 1024) + "\n")

        val log = ServerLog(file)
        log.append("right after the giant line")

        assertTrue(
            file.readLines().any { it.startsWith("giant ") },
            "the newest output was dropped to satisfy a size bound",
        )

        log.append("one line later")

        assertEquals(
            listOf("right after the giant line", "one line later"),
            file.readLines(),
            "the oversized line is still being carried, so the file never " +
                "returns under the cap",
        )
    }


    /**
     * The guard is over the file, so it cannot be over one instance of this class.
     *
     * Two are reachable in one process. `ProcessManager` builds one and
     * `NodeService.onCreate` builds one `ProcessManager`, but a service that has
     * been stopped and started again is a second instance in the same process, and
     * a drain thread that outlives the stop meant to end it leaves the first
     * writing too. An instance monitor puts them on separate locks over one path,
     * and a rotation is a read of the whole file followed by a write of the whole
     * file: an append landing inside one is written to a length that is about to
     * stop existing.
     *
     * Held from the test thread rather than raced, because racing a rotation only
     * measures how fast the machine is. What is asserted is the property itself:
     * while the file's lock is held, an append through a DIFFERENT instance does
     * not proceed.
     */
    @Test
    fun `an append through a second instance waits for the lock on the file`() {
        val file = logFile()
        val lock = ServerLog::class.java.getDeclaredField("FILE_LOCK")
            .apply { isAccessible = true }
            .get(null)

        val started = CountDownLatch(1)
        val written = CountDownLatch(1)
        val worker = synchronized(lock) {
            val t = thread(name = "second-server-log") {
                started.countDown()
                ServerLog(file).append("from the second instance")
                written.countDown()
            }
            assertTrue(started.await(5, TimeUnit.SECONDS), "the second writer never ran")
            assertFalse(
                written.await(300, TimeUnit.MILLISECONDS),
                "a second ServerLog wrote the file while its lock was held, so the two " +
                    "instances are on separate monitors and a rotation by either can " +
                    "swallow the other's line",
            )
            t
        }

        assertTrue(
            written.await(5, TimeUnit.SECONDS),
            "the second writer never woke after the lock was released",
        )
        worker.join()
        assertEquals(
            listOf("from the second instance"), file.readLines(),
            "the line was lost rather than merely delayed",
        )
    }

    /**
     * The reader takes the same lock, for the same reason the writers do.
     *
     * `CrashReporter` used to read the file directly. A rotation truncates it and
     * then writes the tail back, so a report taken between the two carries a short
     * or empty server section, which is indistinguishable from a server that had
     * nothing to say.
     */
    @Test
    fun `a tail read waits for the lock on the file`() {
        val file = logFile()
        file.parentFile.mkdirs()
        file.writeText("one\ntwo\n")
        val lock = ServerLog::class.java.getDeclaredField("FILE_LOCK")
            .apply { isAccessible = true }
            .get(null)

        val started = CountDownLatch(1)
        val read = CountDownLatch(1)
        val worker = synchronized(lock) {
            val t = thread(name = "server-log-reader") {
                started.countDown()
                ServerLog(file).tail(200)
                read.countDown()
            }
            assertTrue(started.await(5, TimeUnit.SECONDS), "the reader never ran")
            assertFalse(
                read.await(300, TimeUnit.MILLISECONDS),
                "the reader does not take the lock a rotation holds, so a report can " +
                    "still be taken from a file that is part-way through being rewritten",
            )
            t
        }

        assertTrue(read.await(5, TimeUnit.SECONDS), "the reader never woke")
        worker.join()
    }

    @Test
    fun `the tail is the newest lines and nothing more`() {
        val file = logFile()
        file.parentFile.mkdirs()
        file.writeText((1..10).joinToString("\n", postfix = "\n") { "line-$it" })

        assertEquals(listOf("line-8", "line-9", "line-10"), ServerLog(file).tail(3))
        assertEquals(10, ServerLog(file).tail(200).size, "a tail wider than the file is the file")
    }

    /**
     * A file that was never written answers with nothing rather than throwing, so
     * the reader can say "no server output recorded" instead of dropping its
     * section and leaving the reader of the report to guess which happened.
     */
    @Test
    fun `a missing file tails to nothing rather than throwing`() {
        assertEquals(emptyList<String>(), ServerLog(File(tempDir, "nowhere/server.log")).tail(200))
    }

    /**
     * The stream this file mirrors is not only ours.
     *
     * `assets/server.js` forks the editor server with `stdio: 'inherit'`, and the
     * editor server echoes the extension host's stdout and stderr into its own
     * console. So an extension that authenticates, and that dumps a failing
     * request when it goes wrong, writes its credential onto the stream that ends
     * up here and then in a bug report the user pastes somewhere public.
     */
    @Test
    fun `an extension's credentials do not reach the file`() {
        val file = logFile()
        val log = ServerLog(file)

        log.append("<4242><stderr> request failed: {\"authorization\":\"Bearer abcd1234efgh5678\"}")
        log.append("<4242> GET /v1/models api_key=super-secret-value")
        log.append("<4242> using key sk-abcdefghijklmnopqrstuvwxyz")
        log.append("<4242> token ghp_abcdefghijklmnopqrstuvwxyz0123")

        val written = file.readText()
        for (secret in listOf(
            "abcd1234efgh5678",
            "super-secret-value",
            "sk-abcdefghijklmnopqrstuvwxyz",
            "ghp_abcdefghijklmnopqrstuvwxyz0123",
        )) {
            assertFalse(secret in written, "\"$secret\" was written to disk verbatim:\n$written")
        }
        assertTrue(
            written.contains("<redacted>"),
            "nothing was replaced, so the shapes are not being matched at all:\n$written",
        )
    }

    /**
     * The name beside the credential is almost never the bare keyword.
     *
     * A Node server prints environment variables, an extension dumps its own
     * configuration, and a dotenv parse error quotes the line it choked on, so
     * what reaches this file is `NPM_TOKEN=`, `DB_PASSWORD:` or
     * `AWS_SECRET_ACCESS_KEY=`. The rule was anchored with `\b`, which
     * java.util.regex defines over `[A-Za-z0-9_]`: an underscore is a word
     * character, so the boundary never held next to one and every name in this
     * case was written to disk verbatim and copied into a bug report.
     *
     * NEGATIVE CONTROL: put the `\b` back around the keyword alternation in the
     * third entry of `SECRET_PATTERNS` (`\b(?:api[_-]?key|...|token)\b` in place
     * of the two lookarounds). Every line below is then written unchanged and this
     * case reddens on the first of them.
     */
    @Test
    fun `a credential named like an environment variable is redacted too`() {
        val file = logFile()
        val log = ServerLog(file)
        val secrets = mapOf(
            "<4242><stderr> NPM_TOKEN=npm_AAAABBBBCCCCDDDDEEEE" to "npm_AAAABBBBCCCCDDDDEEEE",
            "<4242><stderr> DB_PASSWORD: hunter2hunter2" to "hunter2hunter2",
            "<4242><stderr>   \"DB_PASSWORD\": \"correct-horse\"" to "correct-horse",
            "<4242><stderr> MY_API_KEY=AKIAIOSFODNN7EXAMPLE" to "AKIAIOSFODNN7EXAMPLE",
            "<4242><stderr> AWS_SECRET_ACCESS_KEY=wJalrXUtnFEMI/K7MDENG/bPxRfi" to
                "wJalrXUtnFEMI/K7MDENG/bPxRfi",
            "<4242><stderr> VSCODE_AGENT_TOKEN=1234567890abcdef" to "1234567890abcdef",
            "<4242><stderr> export user_token=abc123def456" to "abc123def456",
        )
        secrets.keys.forEach { log.append(it) }

        val written = file.readText()
        for ((line, secret) in secrets) {
            assertFalse(
                secret in written,
                "\"$secret\" survived from \"$line\", so a bug report carries it:\n$written",
            )
        }
        assertEquals(
            secrets.size,
            written.lines().count { "<redacted>" in it },
            "one line was left with nothing replaced:\n$written",
        )
    }

    /**
     * The other side of the same boundary: the keyword has to be a segment of the
     * name, not any occurrence of those letters.
     *
     * Widening the rule until it fires wherever `token` or `secret` appears would
     * eat the diagnostics this file exists for, and an exception class or a
     * source file is exactly where those letters turn up innocently.
     *
     * NEGATIVE CONTROL: one mutation per boundary, because two different things
     * hold these lines. Drop `(?<![A-Za-z0-9])` from the third entry of
     * `SECRET_PATTERNS` and the last line reddens, `notasecret=` and `mytoken=`
     * both read as names. Make the separator optional in the same entry, `[:=]?`
     * for `[:=]`, and the middle two redden instead: nothing else stops a keyword
     * with letters welded onto its right, because it can then reach a value with
     * no separator in between. The first line takes both mutations together,
     * which is what makes it the one worth keeping when the rule is next widened.
     */
    @Test
    fun `a keyword welded into a longer word is not a credential name`() {
        val file = logFile()
        val log = ServerLog(file)
        val lines = listOf(
            "<4242><stderr> InvalidTokenError: unexpected end of input",
            "<4242><stderr> SecretStorageService: initialised",
            "<4242> at Tokenizer.scan (/data/user/0/x/tokenizer.js:12:3)",
            "<4242> notasecret=1 mytoken=diagnostic",
        )
        lines.forEach { log.append(it) }

        assertEquals(lines, file.readLines(), "diagnostics were eaten by the redaction")
    }

    /**
     * The control for the case above. A redaction wide enough to swallow the
     * output makes the file useless for the one thing it exists for, and a
     * `<redacted>` in place of every line would satisfy that test perfectly.
     */
    @Test
    fun `ordinary server output survives redaction untouched`() {
        val file = logFile()
        val log = ServerLog(file)
        val lines = listOf(
            "Extension host agent listening on 41003",
            "[error] Error: listen EADDRINUSE: address already in use 127.0.0.1:41003",
            "<4242> Extension host with pid 4242 started",
            "Authorization failed for a request that carried no header",
            "FATAL ERROR: JavaScript heap out of memory",
        )
        lines.forEach { log.append(it) }

        assertEquals(lines, file.readLines(), "diagnostics were eaten by the redaction")
    }

    /**
     * The table this rule set is meant to satisfy, in one case: what has to go,
     * what has to survive, and what the multi-word and segmented shapes do.
     *
     * Kept as one table because the rules are one rule set. A case per shape
     * hides the thing that goes wrong here, which is that widening the pattern
     * for a name in the first column quietly moves a line out of the second.
     *
     * NEGATIVE CONTROL: three mutations of the third entry of `SECRET_PATTERNS`,
     * two for the first column and one for the second. Drop `pgpassword|` and the
     * `PGPASSWORD` row survives; put the value back to `["']?[^\s"',}&]+` with no
     * quoted alternative and `DB_PASSWORD="correct horse battery staple"` keeps
     * everything after its first word. Drop `(?<![A-Za-z0-9])` and the second
     * column's `notasecret=1 mytoken=diagnostic` is redacted instead. Measured:
     * each of the three reddens this case on its own.
     *
     * Four more, one per later widening. Put the quote back to a bare `["']?`
     * in the first and third entries and the three `\"`-quoted rows survive: a
     * body serialised into an error message arrives with a backslash before
     * every quote, and the `\s*` then meets that backslash where the separator
     * has to be. Drop `|cookie|set-cookie` from the first entry and the three
     * cookie rows survive this function (the `vscode-tkn` one is still taken
     * by `redactToken` on the append path, which is why it is here as a row
     * against this function alone). Drop `private[_-]?key|` from the third and the
     * `private_key:` row survives (the `"privateKey":` row does not, because
     * its value is a one-line key and the `-----BEGIN` entry takes that on its
     * own). Drop the `-----BEGIN` entry and the bare one-line key, the one with
     * no name beside it, survives. The second column holds the
     * shapes each of those must not eat: a count of cookies, a header named
     * with no value, a count of keys, a diagnostic that only names the marker,
     * and a certificate, which is what a failing TLS handshake prints.
     */
    @Test
    fun `the redaction table holds in both directions`() {
        val mustGo = mapOf(
            "<4242><stderr> request failed: {\"authorization\":\"Bearer abcd1234efgh5678\"}" to
                "abcd1234efgh5678",
            "<4242> GET /v1/models api_key=super-secret-value" to "super-secret-value",
            "<4242><stderr> NPM_TOKEN=npm_AAAABBBBCCCCDDDDEEEE" to "npm_AAAABBBBCCCCDDDDEEEE",
            "<4242><stderr> AWS_SECRET_ACCESS_KEY=wJalrXUtnFEMI/K7MDENG" to "wJalrXUtnFEMI",
            "<4242><stderr> x_api_key_v2: abcdef0123456789" to "abcdef0123456789",
            "<4242><stderr> PGPASSWORD=s3cr3tpassword psql -h db" to "s3cr3tpassword",
            "<4242><stderr> DB_PASSWORD=\"correct horse battery staple\"" to "battery staple",
            "<4242><stderr> export MY_SECRET='hunter 2 three'" to "2 three",
            "<4242> using key sk-abcdefghijklmnopqrstuvwxyz" to "sk-abcdefghijklmnopqrstuvwxyz",
            // A body that was JSON.stringify'd and then quoted into an error
            // message: every quote carries a backslash.
            "<4242><stderr> body: \"{\\\"api_key\\\":\\\"AKIAIOSFODNN7EXAMPLE\\\"}\"" to
                "AKIAIOSFODNN7EXAMPLE",
            "<4242><stderr> {\\\"authorization\\\":\\\"AKIAIOSFODNN7EXAMPLE\\\"}" to
                "AKIAIOSFODNN7EXAMPLE",
            "<4242><stderr> {\\\"password\\\":\\\"hunter2hunter2\\\"}" to "hunter2hunter2",
            "<4242><stderr> Cookie: session=AKIAIOSFODNN7EXAMPLE; theme=dark" to
                "AKIAIOSFODNN7EXAMPLE",
            "<4242><stderr> set-cookie: sid=AKIAIOSFODNN7EXAMPLE; Path=/; HttpOnly" to
                "AKIAIOSFODNN7EXAMPLE",
            "<4242><stderr> cookie: vscode-tkn=8f3c1d2e-secret" to "8f3c1d2e-secret",
            "<4242><stderr> private_key: AKIAIOSFODNN7EXAMPLE" to "AKIAIOSFODNN7EXAMPLE",
            "<4242><stderr> \"privateKey\": \"-----BEGIN PRIVATE KEY-----\\n" +
                "MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQC7\\n" +
                "-----END PRIVATE KEY-----\\n\"" to "MIIEvQIBADANBg",
            "<4242><stderr> Error: bad key \"-----BEGIN RSA PRIVATE KEY-----\\n" +
                "MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQC7\\n" +
                "-----END RSA PRIVATE KEY-----\"" to "MIIEvQIBADANBg",
        )
        val mustSurvive = listOf(
            "Extension host agent listening on 41003",
            "[error] Error: listen EADDRINUSE: address already in use 127.0.0.1:41003",
            "Authorization failed for a request that carried no header",
            "FATAL ERROR: JavaScript heap out of memory",
            "<4242><stderr> InvalidTokenError: unexpected end of input",
            "<4242><stderr> SecretStorageService: initialised",
            "<4242> at Tokenizer.scan (/data/user/0/x/tokenizer.js:12:3)",
            "<4242> notasecret=1 mytoken=diagnostic",
            "<4242> cookies: 3 stored for 127.0.0.1",
            "<4242> Set-Cookie header missing from the response",
            "<4242> privateKeys: 2 loaded from ~/.ssh",
            "<4242><stderr> expected a -----BEGIN PRIVATE KEY----- header",
            "<4242><stderr> peer certificate \"-----BEGIN CERTIFICATE-----\\n" +
                "MIIDdzCCAl+gAwIBAgIEAgAAuTANBgkqhkiG9w0BAQUFADBaMQswCQYDVQQGEwJJ\\n" +
                "-----END CERTIFICATE-----\"",
        )

        for ((line, secret) in mustGo) {
            val redacted = redactSecrets(line)
            assertFalse(
                secret in redacted,
                "\"$secret\" survived from \"$line\", so a bug report carries it: $redacted",
            )
            assertTrue("<redacted>" in redacted, "nothing was replaced in \"$line\"")
        }
        for (line in mustSurvive) {
            assertEquals(line, redactSecrets(line), "a diagnostic was eaten by the redaction")
        }
    }

    /**
     * What accepting a name's remaining segments costs, pinned to the character.
     *
     * The note above `SECRET_PATTERNS` bounds the cost, and a bound nobody
     * measures drifts: it said "a hyphenated ordinary word ending in a colon"
     * loses "the one word after it", while the rule also fires on `_` segments
     * and after a flag's `=`, and the value class does not stop at a `:`, so the
     * punctuation goes with the value. Asserting the exact output is what makes
     * the next widening of that rule visible instead of silent.
     *
     * NEGATIVE CONTROL: drop `(?:[_-][A-Za-z0-9_-]*)?` from the third entry of
     * `SECRET_PATTERNS`. All three lines are then returned unchanged, which
     * reddens here and reddens `x_api_key_v2` in the table above, and those two
     * together are the trade being made.
     */
    @Test
    fun `a segmented ordinary name costs exactly the run after its separator`() {
        val file = logFile()
        val log = ServerLog(file)
        val lines = listOf(
            "<4242><stderr> secret-storage: initialised at 12:00",
            "<4242><stderr> password_hash_algorithm: bcrypt",
            "<4242><stderr> fatal: --secret-file=/etc/k.pem: no such file",
        )
        lines.forEach { log.append(it) }

        assertEquals(
            listOf(
                "<4242><stderr> secret-storage: <redacted> at 12:00",
                "<4242><stderr> password_hash_algorithm: <redacted>",
                "<4242><stderr> fatal: --secret-file=<redacted> no such file",
            ),
            file.readLines(),
            "the cost of the segment rule is not what the note beside it says",
        )
    }

    /**
     * A crash log is a whole file, and every pattern here is written against one
     * line.
     *
     * `CrashReporter` now runs this over the text of a crash log rather than over
     * one line of server output, and the header rule's value class excludes
     * quotes, commas and braces but not newlines, while the `\s*` and `\s+` in
     * the header and scheme rules match a newline like any other space. Given a
     * whole file, one `authorization:` in a throwable's message took everything
     * under it as far as the next quote, comma or brace, and a Java stack trace
     * has none of those, so the report kept five lines of eleven while still
     * announcing a complete crash log.
     *
     * NEGATIVE CONTROL: make `redactSecrets` one pass over the whole text again
     * (`redactOneLine(text)` with no split). The trace below collapses to the
     * `IOException` line and this case reddens on the first frame.
     */
    @Test
    fun `redaction of a whole crash log keeps the trace under the match`() {
        val crash = listOf(
            "Crash at 2026-08-22 10:02:11 +0700",
            "Thread: main (id=2)",
            "",
            "java.io.IOException: Authorization: Bearer rejected by the proxy",
            "\tat com.vscodroid.service.ProcessManager.startServer(ProcessManager.kt:119)",
            "Caused by: java.net.SocketException: Socket is closed",
            "\tat java.net.Socket.getOutputStream(Socket.java:1030)",
        ).joinToString("\n", postfix = "\n")

        val redacted = redactSecrets(crash)

        assertEquals(
            crash.count { it == '\n' },
            redacted.count { it == '\n' },
            "the redaction swallowed lines of the trace:\n$redacted",
        )
        assertFalse(
            "Bearer rejected" in redacted,
            "the header value still has to go:\n$redacted",
        )
        for (kept in listOf(
            "ProcessManager.startServer(ProcessManager.kt:119)",
            "Caused by: java.net.SocketException: Socket is closed",
            "java.net.Socket.getOutputStream(Socket.java:1030)",
        )) {
            assertTrue(kept in redacted, "\"$kept\" was eaten:\n$redacted")
        }
    }

    /**
     * The other cross-line shape, which excluding a newline from one value class
     * would not have closed: the scheme rule's `\s+` and the named rule's `\s*`
     * match a newline, so both could reach into the line below and replace its
     * first word.
     *
     * NEGATIVE CONTROL: the same mutation as the case above. Both lines then lose
     * their first frame to a `<redacted>` and this reddens.
     */
    @Test
    fun `a keyword at the end of a line does not reach the frame below it`() {
        val text = "java.lang.IllegalStateException: basic\n" +
            "\tat com.vscodroid.util.Thing.doIt(Thing.kt:1)\n" +
            "java.lang.IllegalStateException: password:\n" +
            "\tat com.vscodroid.MainActivity.onCreate(MainActivity.kt:1)\n"

        assertEquals(text, redactSecrets(text), "a rule reached across a newline")
    }

    /**
     * A pathological name cannot take the process with it.
     *
     * java.util.regex compiles a quantified group into a `Loop` that recurses
     * once per repetition, so the segment suffix written as `(?:[_-][A-Za-z0-9]+)*`
     * raised StackOverflowError on a long enough name. That is an `Error`: it
     * passes through the `IOException` catch in `append` and the `Exception` catch
     * in `ProcessManager.startOutputReader`, reaches the default handler and kills
     * the process the editor runs in, with the log line never written.
     *
     * Run on a thread with a 1 MiB stack, which is the order of the drain thread's
     * own, because the default stack of whatever JVM runs the suite is not the
     * property under test.
     *
     * NEGATIVE CONTROL: put `(?:[_-][A-Za-z0-9]+)*` back in place of
     * `(?:[_-][A-Za-z0-9_-]*)?` in the third entry of `SECRET_PATTERNS`. Measured:
     * this case then fails with StackOverflowError, at 2000 segments as well as at
     * the 20000 below, and no other case in this file changes.
     */
    @Test
    fun `a name made of thousands of segments does not overflow the stack`() {
        val line = "<4242><stderr> token" + "_a".repeat(20_000) + "=hunter2"
        var thrown: Throwable? = null
        var out: String? = null

        val worker = Thread(null, {
            try {
                out = redactSecrets(line)
            } catch (t: Throwable) {
                thrown = t
            }
        }, "redaction-1m-stack", 1L shl 20)
        worker.start()
        worker.join()

        assertEquals(null, thrown, "the redaction threw on a long name, killing the process")
        assertFalse("hunter2" in out!!, "the long name is still a credential name: $out")
    }

    /**
     * The wiring, read from the source because the only behavioural route to it
     * needs a live server process.
     *
     * Deliberately not routed through `onServerOutput`. That seam is public and
     * documented as one, so a consumer assigning it would replace this writer
     * rather than join it, and the symptom would be an empty report again.
     */
    @Test
    fun `the output reader writes every line it reads`() {
        val body = outputReaderBody()

        // Anchored to the start of a line so that a commented-out call, which is
        // how a developer disables something while debugging, cannot satisfy it.
        assertTrue(
            Regex("""(?m)^\s*serverLog\.append\(line\)""").containsMatchIn(body),
            "startOutputReader does not write to the server log, so every bug " +
                "report carries no server output and looks like a quiet server",
        )
    }

    /**
     * The control for [outputReaderBody]. A body that ran past its own closing
     * brace would satisfy the case above by finding the call somewhere else in
     * the file, so its scope has to be asserted rather than assumed.
     *
     * `startAdoptionWatch` is the declaration after `startOutputReader`, which
     * makes its name appearing inside the extracted body exactly the failure
     * being guarded against.
     */
    @Test
    fun `the extracted body stops at the end of its own function`() {
        val body = outputReaderBody()

        assertFalse(
            "startAdoptionWatch" in body,
            "the extraction ran past startOutputReader, so the case above is " +
                "really a file-wide search: ${body.length} chars",
        )
    }

    /**
     * `startOutputReader` runs to the first line that is exactly a closing brace
     * at member indentation. Every brace nested inside it is indented further, so
     * this identifies the real end without counting braces, and the control above
     * is what turns a formatting change that broke the assumption into a failure
     * rather than a silent widening.
     */
    private fun outputReaderBody(): String {
        val source = File("src/main/kotlin/com/vscodroid/service/ProcessManager.kt")
        assertTrue(
            source.isFile,
            "${source.absolutePath} is missing; this test would otherwise pass by " +
                "reading nothing",
        )
        val text = source.readText()
        val start = text.indexOf("private fun startOutputReader()")
        assertTrue(start >= 0, "ProcessManager has no startOutputReader")
        val end = text.indexOf("\n    }\n", start)
        assertTrue(end > start, "startOutputReader has no closing brace at member indentation")
        return text.substring(start, end)
    }
}
