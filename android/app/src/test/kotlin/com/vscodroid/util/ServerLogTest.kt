package com.vscodroid.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

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
