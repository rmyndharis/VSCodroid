package com.vscodroid.keyboard

import android.webkit.WebView
import io.mockk.mockk
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Who owns the lifetime of the one modifier query allowed to be in the air.
 *
 * The poll keeps a single query outstanding so a renderer slow to reply is not
 * sent a fresh one five times a second. Held as a plain "a query is out" flag,
 * that bound outlived the page it was bounding: the answer comes back through
 * `WebView.evaluateJavascript`, a WebView destroyed before it replies never
 * delivers one, and the row is not destroyed with it.
 * `MainActivity.recreateWebView` replaces only the WebView and hands this same
 * row a new `KeyInjector` on the same main thread turn, so nothing would ever
 * have cleared the flag and every later tick would return without asking. The
 * row would then never again learn that the page had spent a latched modifier:
 * the next row key goes out as a chord, and a whole trackpad drag does, since
 * the arrow path deliberately keeps the modifier through a drag.
 *
 * So the record is kept against the injector that was asked. That is the piece
 * this file runs: [OutstandingModifierQuery] is an ordinary object, so unlike
 * the rest of the poll it can be exercised on the JVM rather than argued from
 * the source text. The last case is the control that ties it to the row, so a
 * class the poll no longer calls cannot pass here on its own behaviour.
 *
 * [ExtraKeyModifierSyncTest] holds the other half, that the tick survives an
 * answer that never comes at all.
 */
class ExtraKeyQueryOwnershipTest {

    /** Two injectors that differ only in identity, which is all this reads. */
    private fun injector() = KeyInjector(mockk<WebView>(relaxed = true))

    /** Uniform across this package; [KeyInjectorShiftTest] gives the reason. */
    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    /**
     * The defect, as the row meets it: a renderer that died owing an answer.
     *
     * NEGATIVE CONTROL: make [OutstandingModifierQuery.claim] refuse whenever
     * anything is outstanding (`if (askedOf != null) return false`), which is
     * the plain flag this replaced, and this goes red on the second claim.
     */
    @Test
    fun `a query the dead page never answered does not silence the query about the next one`() {
        val query = OutstandingModifierQuery()
        val crashed = injector()

        assertTrue(query.claim(crashed), "the first tick must be allowed to ask")

        // The renderer dies here. Nothing answers, and MainActivity hands the
        // row a new injector without the poll ever seeing a null one.
        val replacement = injector()
        assertTrue(
            query.claim(replacement),
            "the query owed by the destroyed page still bars the new one, so the row " +
                "never asks again for the rest of the Activity and goes on painting a " +
                "modifier the fresh page is not holding",
        )
    }

    /**
     * The bound itself, which is what the record exists for.
     *
     * NEGATIVE CONTROL: make [OutstandingModifierQuery.claim] always return true
     * and this goes red on the second claim, where a renderer that has not
     * answered would be sent another query every 200 ms.
     */
    @Test
    fun `one injector is asked once until it answers`() {
        val query = OutstandingModifierQuery()
        val injector = injector()

        assertTrue(query.claim(injector))
        assertFalse(
            query.claim(injector),
            "a second query goes out while the first is unanswered, so a slow renderer is " +
                "asked five times a second for as long as a modifier stays latched",
        )
        assertTrue(query.release(injector), "the answer that arrived is the one being waited for")
        assertTrue(query.claim(injector), "with the answer in, the next tick must ask again")
    }

    /**
     * The reply that is simply lost, with the injector that owes it still the
     * row's.
     *
     * Nothing displaces the claim: no replacement injector arrives, so [claim]
     * never sees a different one, and the row still has an injector, so the poll
     * never reaches [abandon]. Without a way out, the guard above turns from
     * back-pressure into a stop: every later tick returns without asking, and the
     * row never again learns that the page has spent a latched modifier. It is
     * the plain-flag failure this class was written to remove, reached by the one
     * door the class did not close.
     *
     * NEGATIVE CONTROL: restore the old `if (askedOf === injector) return false`
     * and this goes red on the last assertion, on every tick after it.
     */
    @Test
    fun `an injector that never answers is asked again after a bounded silence`() {
        val query = OutstandingModifierQuery()
        val silent = injector()

        assertTrue(query.claim(silent), "the first tick must be allowed to ask")

        // The bound is still a bound: it is not given up on the very next tick,
        // or a slow renderer is back to being asked five times a second.
        assertFalse(query.claim(silent), "the tick right after the question must not re-ask")

        // Ticks on, with nothing ever answering. This is deliberately not a
        // spelled count: what the case is about is that the silence ENDS, and
        // pinning the number here would make a change to it a two-file edit for
        // no gain. The floor below is what keeps it from being unbounded.
        var asked = 1
        var ticks = 1
        while (ticks < 20 && asked < 2) {
            ticks += 1
            if (query.claim(silent)) asked += 1
        }

        assertTrue(
            asked >= 2,
            "twenty ticks went by and the poll never asked again, so one lost reply " +
                "silences it for the life of the page: the row goes on painting a modifier " +
                "the page has already spent, and the next key goes out as a chord",
        )
        assertTrue(
            ticks >= 3,
            "the slot was given up almost immediately, which is the bound gone rather than " +
                "closed: a renderer slow to answer is asked again while its first reply is " +
                "still in the air",
        )
    }

    /**
     * That the reprieve is spent, not carried.
     *
     * A silence already counted against one query must not be counted against
     * the next: carried forward, the bound decays over the life of a page until
     * every tick asks, which is the back-pressure gone by degrees rather than
     * visibly. Compared between two rounds rather than against a spelled number,
     * for the reason the case above gives.
     */
    @Test
    fun `a silence is not counted against the query after it`() {
        val query = OutstandingModifierQuery()
        val injector = injector()

        fun refusalsBeforeReasking(): Int {
            var refusals = 0
            while (refusals < 20 && !query.claim(injector)) refusals += 1
            return refusals
        }

        assertTrue(query.claim(injector), "the first tick must be allowed to ask")
        val first = refusalsBeforeReasking()
        assertTrue(first in 1..19, "the first round never re-asked, so there is nothing to compare")

        // This round's query is answered, and then the injector goes silent again.
        assertTrue(query.release(injector), "the answer arrived")
        assertTrue(query.claim(injector), "with the answer in, the next tick must ask again")

        assertEquals(
            first, refusalsBeforeReasking(),
            "a query that follows an answered one gets a shorter reprieve than the one " +
                "before it, so the bound wears away over the life of a page",
        )
    }

    /**
     * An answer that outlived the page it describes.
     *
     * It must not be applied, and it must not free the claim the current
     * injector's query is holding, which would give back the bound above.
     *
     * NEGATIVE CONTROL: make [OutstandingModifierQuery.release] clear
     * unconditionally (`askedOf = null; return true`) and this goes red on the
     * first assertion.
     */
    @Test
    fun `an answer from a replaced injector is refused and leaves the live claim standing`() {
        val query = OutstandingModifierQuery()
        val crashed = injector()
        val replacement = injector()

        query.claim(crashed)
        query.claim(replacement)

        assertFalse(
            query.release(crashed),
            "an answer about a page that is gone is taken as the answer to the live query, " +
                "so its flags are applied to a page that never had them",
        )
        assertFalse(
            query.claim(replacement),
            "the stale answer freed the live claim, so the bound on outstanding queries is " +
                "back to nothing",
        )
        assertTrue(query.release(replacement), "the live answer still releases its own claim")
    }

    /**
     * The branch where the row is left with no injector at all: the Activity
     * being destroyed, or a renderer crash during a cold start, where no
     * replacement is built because no port is bound yet.
     *
     * NEGATIVE CONTROL: make [OutstandingModifierQuery.abandon] a no-op and this
     * goes red; delete the `outstanding.abandon()` call from the poll's
     * no-injector branch and the last case in this file goes red.
     */
    @Test
    fun `a row left with no injector gives up the answer it can never get`() {
        val query = OutstandingModifierQuery()
        val gone = injector()

        query.claim(gone)
        query.abandon()

        assertTrue(
            query.claim(gone),
            "the query owed by an injector the row no longer has is still outstanding, so " +
                "it bars every later one",
        )
    }

    /**
     * The control: the poll actually keeps its one query here.
     *
     * Without it every case above is a class talking to itself, and a poll that
     * had gone back to a bare flag would pass this file untouched.
     *
     * Source-reading, for the reason the rest of this package gives: [ExtraKeyRow]
     * is a `View` whose initialiser reaches resources, colours and display
     * metrics before its constructor finishes, so no JVM test can build one.
     * Comment lines are dropped first, so prose naming a call is not a call.
     *
     * NEGATIVE CONTROL: comment out any one of the three calls in the poll and
     * the matching assertion below goes red.
     */
    @Test
    fun `the poll keeps its one outstanding query in this class`() {
        val lines = code()
        val start = lines.indexOfFirst { it.contains("private val modifierSyncRunnable") }
        assertTrue(start >= 0, "modifierSyncRunnable is no longer where this test looks")
        val body = lines.drop(start).takeWhile { !it.startsWith("    init {") }
        assertTrue(
            body.any { it.contains("queryModifierState") },
            "the window found does not poll the page, so its verdict is worth nothing. " +
                "It reads:\n${body.joinToString("\n")}",
        )
        assertTrue(
            body.any { it.contains("OutstandingModifierQuery()") },
            "the poll holds something else, so the behaviour proved above is not the " +
                "behaviour the row gets. It reads:\n${body.joinToString("\n")}",
        )

        val query = body.indexOfFirst { it.contains("queryModifierState") }
        assertTrue(
            body.take(query).any { it.contains("outstanding.claim(injector)") },
            "nothing claims the outstanding slot before the query goes out, so the bound " +
                "is not applied at all. It reads:\n${body.joinToString("\n")}",
        )
        assertTrue(
            body.drop(query + 1).any { it.contains("outstanding.release(injector)") },
            "the answer does not release the claim it is answering, so the poll asks once " +
                "and then ticks forever without asking again. It reads:" +
                "\n${body.joinToString("\n")}",
        )

        val noInjector = body.indexOfFirst { it.contains("if (injector == null)") }
        assertTrue(
            noInjector in 0 until query,
            "the poll no longer has a branch for a row with no injector, so this case is " +
                "reading a poll that cannot reach the state it guards. It reads:" +
                "\n${body.joinToString("\n")}",
        )
        assertTrue(
            body.subList(noInjector, query).any { it.contains("outstanding.abandon()") },
            "the branch that gives up on the page keeps waiting for its answer, which " +
                "nothing can now send. It reads:\n${body.joinToString("\n")}",
        )
    }

    /** The row's source with comment lines dropped. */
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
}
