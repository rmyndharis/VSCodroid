package com.vscodroid.keyboard

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * That the modifier poll never applies an answer older than the latch it is
 * about.
 *
 * `queryModifierState` is answered with a snapshot the renderer took when the
 * query script ran, and `setModifierState` is a separate, later script. Scripts
 * run in the order they were submitted, so a modifier latched after a query went
 * out is guaranteed to be missing from that query's answer, which reports it as
 * clear. The poll's answer only ever clears, so it read that as "the page spent
 * the modifier" and unlatched what the user had just pressed: the Shift of a
 * Ctrl+Shift+P goes dark on its own and the key that follows is delivered as
 * Ctrl+P.
 *
 * The half that outlives the wrong shortcut is worse. This callback is the one
 * place a modifier is cleared without pushing the new value out, so after it
 * clears a flag the page has just been told is held, nothing corrects either
 * side: the row goes dark, `window.__vscodroid.ctrl` stays true, and the next
 * ordinary character typed on the soft keyboard is cancelled by the interceptor
 * and dispatched as a chord instead, so `a` selects the whole document and the
 * character after it replaces the selection.
 *
 * Source-reading, for the reason the rest of this package gives: [ExtraKeyRow]
 * is a `View` whose initialiser reaches resources, colours and display metrics
 * before its constructor finishes, so no JVM test can build one and no JVM test
 * can run a `postDelayed` or answer an `evaluateJavascript`. Every case opens
 * with a control, so a scan that has stopped matching cannot report a healthy
 * poll by finding nothing at all.
 */
class ExtraKeyModifierStalenessTest {

    /**
     * The row's source with comment lines dropped, so prose naming a call is
     * not a call. Both comment forms, because both switch code off, and the
     * guard this file is about is discussed at length in the comments beside it.
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

    /** What the answer is judged against, as the poll names it. */
    private fun snapshotLocal(body: List<String>): String {
        val named = body.firstNotNullOfOrNull {
            Regex("""modifierPushCount\s*!=\s*(\w+)""").find(it)?.groupValues?.get(1)
        }
        assertTrue(
            named != null,
            "nothing in the poll compares the push count it recorded with the one now, " +
                "so an answer that predates a latch is applied to it. It reads:" +
                "\n${body.joinToString("\n")}",
        )
        return named!!
    }

    /**
     * NEGATIVE CONTROL: delete the `if (modifierPushCount != pushedWhenAsked)`
     * branch from the answer. `snapshotLocal` then finds no comparison and this
     * goes red there.
     */
    @Test
    fun `an answer older than the latch it describes is dropped before anything is cleared`() {
        val body = pollBody()
        val local = snapshotLocal(body)
        val query = body.indexOfFirst { it.contains("queryModifierState") }
        val guard = body.indexOfFirst { it.contains("modifierPushCount != $local") }
        val cleared = body.indexOfFirst { it.contains("ctrlActive = false") }
        assertTrue(
            cleared > query,
            "the answer no longer clears a modifier at all, so this case is reading a " +
                "poll that cannot commit the mistake it guards. It reads:" +
                "\n${body.joinToString("\n")}",
        )
        assertTrue(
            guard > query && guard < cleared,
            "the staleness test does not stand between the answer and the first thing " +
                "it clears, so an answer that predates the user's latch unlatches it. " +
                "It reads:\n${body.joinToString("\n")}",
        )
        assertTrue(
            body.subList(guard, cleared).any { it.contains("return@queryModifierState") },
            "the staleness test decides nothing: the answer is applied whatever it " +
                "found. It reads:\n${body.joinToString("\n")}",
        )
    }

    /**
     * The count is meaningful only against the moment the query was submitted,
     * which is the moment the renderer's answer describes.
     *
     * NEGATIVE CONTROL: move `val pushedWhenAsked = modifierPushCount` inside the
     * `queryModifierState` callback. Read there it is the count as of the answer
     * arriving, so it can never differ from the count it is compared with, the
     * guard above is dead code, and this goes red.
     */
    @Test
    fun `the count an answer is judged against is read before the query goes out`() {
        val body = pollBody()
        val local = snapshotLocal(body)
        val read = body.indexOfFirst { it.contains("val $local = modifierPushCount") }
        val query = body.indexOfFirst { it.contains("queryModifierState") }
        assertTrue(
            read >= 0 && read < query,
            "the poll records the push count from inside the answer rather than before " +
                "asking, so it compares a value with itself and every stale answer is " +
                "applied. It reads:\n${body.joinToString("\n")}",
        )
    }

    /**
     * And the count moves where a push happens, nowhere else.
     *
     * Both directions matter. A count that does not move on a push makes the
     * guard above answer "current" for every answer, which is the defect; a count
     * moved anywhere else makes it answer "stale" for every answer, so no
     * modifier the page has spent is ever cleared and the row keeps a Ctrl lit
     * that no page is holding, which is the defect the poll itself exists for.
     *
     * NEGATIVE CONTROL: delete `modifierPushCount += 1` from `syncModifierState`
     * and this goes red on the first assertion; add a second one to the poll's
     * answer and it goes red on the second.
     */
    @Test
    fun `the count moves only where the page is pushed a new modifier state`() {
        val lines = code()
        val start = lines.indexOfFirst { it.contains("private fun syncModifierState()") }
        assertTrue(start >= 0, "syncModifierState is no longer where this test looks")
        val body = lines.drop(start).takeWhile { it != "    }" }
        assertTrue(
            body.any { it.contains("setModifierState") },
            "the window found does not push anything at the page, so its verdict is " +
                "worth nothing. It reads:\n${body.joinToString("\n")}",
        )
        assertTrue(
            body.any { it.contains("modifierPushCount +=") },
            "the one site that pushes a modifier state at the page does not record that " +
                "it did, so an answer that predates the push is indistinguishable from " +
                "one that saw it. It reads:\n${body.joinToString("\n")}",
        )
        assertEquals(
            1, lines.count { it.contains("modifierPushCount +=") },
            "the push count moves somewhere other than the push. Every outstanding " +
                "answer is then judged stale and thrown away, so nothing ever notices " +
                "the page spending a modifier and the row keeps it lit",
        )
    }
}
