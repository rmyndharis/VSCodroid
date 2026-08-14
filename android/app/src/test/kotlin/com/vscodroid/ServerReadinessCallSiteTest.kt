package com.vscodroid

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Which question `MainActivity` asks before it navigates the WebView.
 *
 * `isServerRunning()` is `Process.isAlive`: true from the instant the process is
 * spawned, and true for the whole of a restart after a crash — neither of which
 * means anything is listening on the port. Navigating on it produces a
 * connection-refused page, and `onReceivedError` only logs, so nothing takes it
 * away again. `isServerReady()` reports what the health probe found.
 *
 * This reads the source, which is a weaker kind of test than the rest of this
 * suite, and it is here because it is the only kind available. The decision sits
 * inside `setupServiceCallbacks()` and `handleResumeFromBackground()`, both of
 * which need an Activity; there is no seam to extract, because the mutation is
 * not a value the code computes — it is which of two methods gets called. A
 * function handed a boolean cannot tell which question produced it.
 *
 * What it therefore does not catch: a third call site added later that asks the
 * wrong question through some other spelling, or the same mistake made in
 * another file. It catches the specific regression of putting `isServerRunning`
 * back here, which is the one that already happened.
 */
class ServerReadinessCallSiteTest {

    private val mainActivity = File("src/main/kotlin/com/vscodroid/MainActivity.kt")

    /** Source lines with comments dropped, since both methods are named in prose. */
    private fun codeLines(): List<IndexedValue<String>> =
        mainActivity.readLines().withIndex().filterNot { (_, line) ->
            val t = line.trimStart()
            t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
        }

    @Test
    fun `the activity asks whether the server is serving, not whether a process exists`() {
        check(mainActivity.isFile) {
            "MainActivity.kt not found at ${mainActivity.absolutePath} — this test " +
                "would otherwise pass by looking at nothing"
        }

        val offenders = codeLines()
            .filter { (_, line) -> line.contains("isServerRunning") }
            .map { (i, line) -> "MainActivity.kt:${i + 1}: ${line.trim()}" }

        assertEquals(
            emptyList<String>(), offenders,
            "isServerRunning() is Process.isAlive and is true long before the server " +
                "answers. Navigating on it gives the user a connection-refused page. " +
                "Use isServerReady(), which reports the health probe's own result.",
        )
    }

    @Test
    fun `and it does ask something`() {
        // A file with neither call would satisfy the test above. This is the
        // control for it, not a second assertion about the same thing.
        //
        // The number of call sites is deliberately not pinned: two is what there
        // are today, and a third would be a normal thing to add.
        check(mainActivity.isFile) { "MainActivity.kt not found" }

        val readinessCalls = codeLines().count { (_, line) -> line.contains("isServerReady()") }

        assertTrue(
            readinessCalls > 0,
            "the readiness check must be asked for somewhere, or the test above is " +
                "satisfied by deleting the question entirely",
        )
    }
}
