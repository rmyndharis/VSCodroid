package com.vscodroid.setup

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * That cancelling a download does not delete the directory a copy is reading.
 *
 * Play's `removePack` is a recursive delete of the delivered pack directory, and
 * the COMPLETED branch copies out of that same directory on `ioExecutor`. The
 * card keeps offering CANCEL for the whole of that copy, because
 * `handleStateUpdate` does not report COMPLETED and the last status the UI holds
 * is TRANSFERRING. A tap in those seconds took the source away from the reader.
 *
 * Read from the source, which is the weaker layer and the only one available.
 * The race needs a real Play delivery, a real multi-second copy and a tap landing
 * inside it; a JVM test can build none of the three, and a mocked `removePack`
 * that returns instantly cannot show an ordering that only matters when it does
 * not. What can be pinned is that the release is handed to the executor the copy
 * runs on rather than performed on the caller's thread, which is the whole of the
 * fix.
 */
class CancelReleaseOrderingTest {

    private val manager = File("src/main/kotlin/com/vscodroid/setup/ToolchainManager.kt")

    /**
     * `cancel` runs to the first line that is a closing brace at member indentation.
     * Every brace nested inside it is indented further, and the control below is
     * what turns a formatting change that broke that assumption into a failure
     * rather than a silent widening.
     */
    private fun cancelBody(): String {
        assertTrue(
            manager.isFile,
            "${manager.absolutePath} is missing; this test would otherwise pass by reading nothing",
        )
        val text = manager.readText()
        val start = text.indexOf("fun cancel(packName: String)")
        assertTrue(start >= 0, "ToolchainManager has no cancel(packName)")
        val end = text.indexOf("\n    }\n", start)
        assertTrue(end > start, "cancel has no closing brace at member indentation")
        return text.substring(start, end)
    }

    @Test
    fun `the pack release is queued behind whatever the io executor is running`() {
        val body = cancelBody()

        // Anchored to the start of a line so a commented-out call cannot satisfy it,
        // and matching the execute wrapper rather than the bare call, because the
        // bare call is present in both the correct and the broken form.
        assertTrue(
            Regex("""(?m)^\s*ioExecutor\.execute\s*\{\s*releasePack\(""").containsMatchIn(body),
            "cancel releases the pack on the caller's thread, so a tap during the " +
                "copy deletes the directory the copy is reading and leaves a part " +
                "written toolchain tree behind",
        )
        assertFalse(
            Regex("""(?m)^\s*releasePack\(""").containsMatchIn(body),
            "cancel still calls releasePack directly as well, so the race it was " +
                "queued to avoid is back on the caller's thread",
        )
    }

    /**
     * The control for [cancelBody], and it names the declaration that actually
     * follows `cancel` rather than the one whose body holds the tempting token.
     *
     * `installDeliveredPack` was the obvious sentinel and a useless one: it sits
     * two hundred lines further down, so an extraction that ran past `cancel` by
     * any realistic amount still stopped short of it and the control passed while
     * the body was wrong. Measured: widening the search by 900 characters left this
     * green. `showConfirmationDialog` is the next declaration, which is what an
     * over-running body swallows first.
     */
    @Test
    fun `the extracted body stops at the end of cancel`() {
        val body = cancelBody()

        assertFalse(
            "showConfirmationDialog" in body,
            "the extraction ran past cancel and swallowed the next declaration, so " +
                "the case above is really a file-wide search: ${body.length} chars",
        )
    }
}
