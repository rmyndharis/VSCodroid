package com.vscodroid.keyboard

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * That the modifier poll survives the event it exists to recover from.
 *
 * The row paints Ctrl, Alt and Shift as latched, and while the soft keyboard is
 * up nothing else clears one: the page consumes the modifier silently and this
 * poll is what notices. So the poll ending early is not a lost tick, it is a row
 * that goes on promising a modifier no page is holding, until the keyboard is
 * hidden.
 *
 * The whole body used to sit inside the answer to `queryModifierState`, re-post
 * included. That answer arrives through `WebView.evaluateJavascript`, and the
 * renderer-crash path destroys the WebView being asked before it can reply, so
 * the chain ended for good on exactly the event that leaves the row and the
 * fresh page disagreeing: `MainActivity.recreateWebView` clears the injector,
 * the replacement page installs an interceptor with every flag false, and the
 * Kotlin latch lives on in the adapter's toggle map, which outlives both.
 *
 * Source-reading, for the reason the rest of this package gives: [ExtraKeyRow]
 * is a `View` whose initialiser reaches resources, colours and display metrics
 * before its constructor finishes, so no JVM test can build one and no JVM test
 * can run a `postDelayed`. That makes the timing property argued rather than
 * executed; what is checked here is the shape that carries it. Every case opens
 * with a control, so a scan that has stopped matching cannot report a healthy
 * poll by finding nothing at all.
 */
class ExtraKeyModifierSyncTest {

    /**
     * The row's source with comment lines dropped, so prose naming a call is
     * not a call. Both comment forms, because both switch code off, and this
     * file's subject is a re-post that the class comments also discuss.
     */
    private fun code(): List<String> {
        val file = File("src/main/kotlin/com/vscodroid/keyboard/ExtraKeyRow.kt")
        assertTrue(
            file.isFile,
            "ExtraKeyRow.kt is not at ${file.absolutePath}; this test would otherwise " +
                "pass by reading nothing",
        )
        var inBlock = false
        return file.readText().lines().filterNot { line ->
            val start = line.trimStart()
            when {
                inBlock -> {
                    if (line.indexOf("*/") >= 0) inBlock = false
                    true
                }
                start.startsWith("/*") -> {
                    if (line.indexOf("*/", line.indexOf("/*") + 2) < 0) inBlock = true
                    true
                }
                else -> start.startsWith("//") || start.startsWith("*")
            }
        }
    }

    /** The runnable's body, from its declaration to the init block that follows it. */
    private fun pollBody(): List<String> {
        val lines = code()
        val start = lines.indexOfFirst { it.contains("private val modifierSyncRunnable") }
        assertTrue(start >= 0, "modifierSyncRunnable is no longer where this test looks")
        val body = lines.drop(start).takeWhile { !it.startsWith("    init {") }
        assertTrue(
            body.any { it.contains("queryModifierState") },
            "the window found does not poll the page, so its verdict is worth nothing. " +
                "It reads:\n${body.joinToString("\n")}",
        )
        return body
    }

    @Test
    fun `the poll re-posts itself outside the answer it is waiting for`() {
        val body = pollBody()
        val repost = body.indexOfFirst { it.contains("postDelayed(modifierSyncRunnable, 200)") }
        val query = body.indexOfFirst { it.contains("queryModifierState") }
        assertTrue(
            repost >= 0 && repost < query,
            "the re-post lives inside the answer again, so a WebView destroyed before it " +
                "replies ends the poll for good and the row goes on painting a modifier " +
                "the fresh page does not have. It reads:\n${body.joinToString("\n")}",
        )
    }

    /**
     * The bound the unconditional re-post gave up, put back where it costs the
     * property above nothing.
     *
     * A tick that always re-posts is what survives an answer that never comes.
     * On its own it also lets the ticks stack: a renderer slow to reply is sent
     * a fresh query every 200 ms with nothing waiting on the last one, for as
     * long as a modifier stays latched, where the old callback-anchored re-post
     * had exactly one query outstanding at a time. Skipping the question while
     * the previous answer is outstanding keeps the ticks and restores the bound.
     *
     * The claim is made against the injector the tick just read, which is what
     * keeps the bound from outliving the page it bounds;
     * [ExtraKeyQueryOwnershipTest] holds that half and runs it.
     *
     * NEGATIVE CONTROL: delete `if (!outstanding.claim(injector)) return` from
     * the poll and this goes red.
     */
    @Test
    fun `the poll asks again only once the last answer is in`() {
        val body = pollBody()
        val repost = body.indexOfFirst { it.contains("postDelayed(modifierSyncRunnable, 200)") }
        val guard = body.indexOfFirst { it.contains("outstanding.claim(") }
        val query = body.indexOfFirst { it.contains("queryModifierState") }
        assertTrue(
            repost >= 0 && guard > repost && guard < query,
            "the tick re-posts with nothing between it and the next query, so a renderer " +
                "that has not answered is sent another one five times a second for as " +
                "long as the latch holds. It reads:\n${body.joinToString("\n")}",
        )
        assertTrue(
            body[guard].contains("if (!outstanding.claim(injector)) return"),
            "the claim decides nothing, so the query goes out whatever it found and every " +
                "tick asks again. It reads:\n${body.joinToString("\n")}",
        )
    }

    /**
     * NEGATIVE CONTROL: delete `if (!outstanding.release(injector))` and its
     * body from the answer and this goes red. That mutation is the worse half of
     * the pair: the ticks would go on running and never ask anything again, so
     * the row would keep a modifier lit that the page has already spent.
     */
    @Test
    fun `an answer that arrives releases the guard it was waiting behind`() {
        val body = pollBody()
        val query = body.indexOfFirst { it.contains("queryModifierState") }
        // After the query line, not merely present: a scan for the call alone
        // could be satisfied from anywhere in the poll and would pass with
        // nothing releasing the claim where the answer actually arrives.
        val released = body.drop(query + 1).any { it.contains("outstanding.release(injector)") }
        assertTrue(
            released,
            "the outstanding query is never released where the answer arrives, so the " +
                "poll asks once and then ticks forever without asking again. It reads:" +
                "\n${body.joinToString("\n")}",
        )
    }

    @Test
    fun `a poll with no injector clears the row instead of stopping quietly`() {
        val body = pollBody()
        assertTrue(
            body.any { it.contains("val injector = keyInjector") },
            "the poll reads the injector through a safe call again, so a tick with no " +
                "page attached does nothing at all and leaves the latch lit. It reads:" +
                "\n${body.joinToString("\n")}",
        )
        val reset = body.indexOfFirst { it.contains("resetModifiersIfNeeded()") }
        val query = body.indexOfFirst { it.contains("queryModifierState") }
        assertTrue(
            reset >= 0 && reset < query,
            "nothing clears the latch on the branch where there is no injector, which is " +
                "the state a renderer crash leaves behind: the row still shows Ctrl held " +
                "for a page that never heard of it. It reads:\n${body.joinToString("\n")}",
        )
    }

    /**
     * The three latch writes above it were already guarded on this ground; the
     * push below them was not.
     *
     * `resetModifiersIfNeeded` runs after every ordinary key press, after every
     * trackpad drag and every trackpad tap, after every long-press alternate, and
     * from the insets listener on every dispatch where the IME is hidden, which
     * includes attach, rotation and each bar change. With nothing latched, every
     * one of those was an `evaluateJavascript` writing the three flags the values
     * they already held, and each one also bumped `modifierPushCount`.
     *
     * NEGATIVE CONTROL: drop the `if (latched)` and this goes red.
     */
    @Test
    fun `a reset with nothing latched tells the page nothing`() {
        val lines = code()
        val start = lines.indexOfFirst { it.contains("private fun resetModifiersIfNeeded()") }
        assertTrue(start >= 0, "resetModifiersIfNeeded is no longer where this test looks")
        val body = lines.drop(start).takeWhile { !it.contains("private fun updateModifierBadge(") }

        assertTrue(
            body.any { it.contains("if (ctrlActive) ctrlActive = false") },
            "the window found is not the reset, so its verdict below is worth nothing. " +
                "It reads:\n${body.joinToString("\n")}",
        )
        assertTrue(
            body.any { it.contains("if (latched) syncModifierState()") },
            "the push is unconditional again, so an evaluateJavascript is issued on " +
                "every plain keystroke and on every inset dispatch with nothing to say. " +
                "It reads:\n${body.joinToString("\n")}",
        )
        assertTrue(
            body.any { it.trim() == "removeCallbacks(modifierSyncRunnable)" },
            "the poll is no longer cancelled here, and this is the call that ends the " +
                "chain on the branch with no injector. It reads:\n${body.joinToString("\n")}",
        )
    }

    @Test
    fun `the poll stops by cancelling rather than by declining to continue`() {
        val body = pollBody()
        assertTrue(
            body.any { it.contains("removeCallbacks(modifierSyncRunnable)") },
            "the poll no longer cancels the tick it queued in advance, so a row with " +
                "every modifier idle keeps asking the page every 200 ms for as long as " +
                "the keyboard is up. It reads:\n${body.joinToString("\n")}",
        )
        val conditionalRepost = Regex("""if \(ctrlActive \|\| altActive \|\| shiftActive\)""")
        assertTrue(
            body.none { conditionalRepost.containsMatchIn(it) },
            "the poll continues only when it finds a modifier still latched, which is the " +
                "shape that let an answer that never arrived end the chain. It reads:" +
                "\n${body.joinToString("\n")}",
        )
    }
}
