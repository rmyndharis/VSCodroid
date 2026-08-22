package com.vscodroid.setup

import com.vscodroid.downloadRefreshFor
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * What one reading of the process-wide download snapshot asks the Toolchains
 * screen to do.
 *
 * The screen has no subscription to lean on: progress reaches the manager that
 * began the transfer and nothing else, so a download started by the first-run
 * queue or by the bridge is visible here only through that snapshot. It was read
 * once, at `onStart`, and the thing it describes keeps moving, so the bar sat at
 * whichever percentage it happened to catch and the card went on offering Cancel
 * after the install had finished. A user who stays on the screen never sees it
 * corrected; leaving and coming back is what fixed it, which is why a rotation
 * looked like the cure.
 *
 * The decision lives at file scope beside the Activity so it can be run here at
 * all, the same reason `isTerminalPackStatus` does.
 */
class ToolchainDownloadRefreshTest {

    private val java = "toolchain_java"

    @Test
    fun `an unchanged snapshot asks for nothing`() {
        // The normal case, once a second, for as long as the screen is in front.
        // Pushing anyway costs a full rebind of every card, and a rebind is what
        // takes accessibility focus off a button someone is on.
        assertFalse(downloadRefreshFor(mapOf(java to 40), mapOf(java to 40)).push)
    }

    @Test
    fun `a moved percentage is pushed and reads no file`() {
        // The two questions are separate on purpose. Any change at all is worth a
        // repaint; only a pack leaving the map is worth going back to disk, and
        // folding them together would mean a file read a second for an answer
        // that cannot have changed while the download is still running.
        val refresh = downloadRefreshFor(mapOf(java to 40), mapOf(java to 55))

        assertTrue(refresh.push)
        assertFalse(refresh.rereadInstalled)
    }

    @Test
    fun `a download that has ended re-reads the install record`() {
        // Its outcome becomes readable exactly when it leaves the map, and this is
        // the moment the screen would otherwise draw Install for something that
        // has just finished installing.
        val refresh = downloadRefreshFor(mapOf(java to 90), emptyMap())

        assertTrue(refresh.push)
        assertTrue(refresh.rereadInstalled)
    }

    @Test
    fun `a download that has just started does not re-read it`() {
        val refresh = downloadRefreshFor(emptyMap(), mapOf(java to 0))

        assertTrue(refresh.push)
        assertFalse(refresh.rereadInstalled)
    }
}

/**
 * That the poll is both started and stopped.
 *
 * Half of it is the defect above: without the post, one reading of the snapshot
 * is all the screen ever gets. The other half is a leak: a Runnable that reposts
 * itself on the RecyclerView keeps the Activity referenced from the view's
 * message queue for as long as the process lives, and nothing else here would
 * notice.
 *
 * Source reading, because both are Activity lifecycle callbacks and this project
 * has no Robolectric. It proves the calls are written in the callbacks that own
 * them, not that the handler ever runs.
 */
class ToolchainPollWiringTest {

    private val source = File("src/main/kotlin/com/vscodroid/ToolchainActivity.kt")

    /** Brace-matched from the signature, stepping over braces inside comments. */
    private fun bodyOf(signature: String): String {
        assertTrue(
            source.isFile,
            "${source.path} is not at ${source.absolutePath}; this test would " +
                "otherwise pass by reading nothing",
        )
        val text = source.readText()
        val start = text.indexOf(signature)
        assertTrue(start >= 0, "no function matching `$signature` in ${source.name}")
        var i = text.indexOf('{', start)
        assertTrue(i >= 0, "no body follows `$signature` in ${source.name}")
        val open = i
        var depth = 0
        while (i < text.length) {
            when {
                text.startsWith("//", i) -> while (i < text.length && text[i] != '\n') i++
                text.startsWith("/*", i) -> {
                    i += 2
                    while (i < text.length && !text.startsWith("*/", i)) i++
                    i += 2
                }
                text[i] == '{' -> { depth++; i++ }
                text[i] == '}' -> {
                    depth--
                    if (depth == 0) return text.substring(open, i + 1)
                    i++
                }
                else -> i++
            }
        }
        error("unbalanced braces after `$signature` in ${source.name}")
    }

    /**
     * Comments dropped, so the paragraph explaining the poll cannot stand in for
     * the poll. Bounded too: an extraction that ran away would answer every
     * question below for the wrong reason.
     */
    private fun code(signature: String): String {
        val body = bodyOf(signature)
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), " ")
            .replace(Regex("""//[^\n]*"""), " ")
        assertTrue(
            body.length in 20..2_000,
            "extracted ${body.length} characters of $signature, which means the extraction " +
                "is wrong rather than the code",
        )
        return body
    }

    @Test
    fun `the poll is posted when the screen starts and taken back when it stops`() {
        // Line-anchored on both, because a commented-out call is still a call to a
        // substring search and commenting one out is how a developer disables it.
        assertTrue(
            Regex("""(?m)^\s*grid\.postDelayed\(pollDownloads""")
                .containsMatchIn(code("override fun onStart(")),
            "onStart does not start the poll, so the screen is back to one reading of a " +
                "snapshot that keeps moving under it",
        )
        assertTrue(
            Regex("""(?m)^\s*grid\.removeCallbacks\(pollDownloads\)""")
                .containsMatchIn(code("override fun onStop(")),
            "onStop does not take the poll back, so a Runnable that reposts itself holds " +
                "this Activity through the view's message queue for the life of the process",
        )
    }

    @Test
    fun `the screen still seeds itself at start`() {
        // Not covered by the post above: relying on the first tick alone leaves
        // the grid a second behind whatever is already downloading, which is the
        // whole of a short pack's transfer.
        assertTrue(
            Regex("""(?m)^\s*refreshDownloads\(\)""")
                .containsMatchIn(code("override fun onStart(")),
            "onStart waits for the first tick before drawing anything the process is " +
                "already downloading",
        )
    }
}
