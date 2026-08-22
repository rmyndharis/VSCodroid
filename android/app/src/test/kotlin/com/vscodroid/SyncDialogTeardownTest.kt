package com.vscodroid

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * That a folder sync failing while the screen goes away does not take the process
 * with it.
 *
 * The progress dialog belongs to a window, and dismissing one whose window has
 * already been torn down throws. Inside a catch handler that throw is not caught
 * by anything: it leaves the handler, leaves the coroutine, and reaches the
 * process's uncaught handler, so the app dies while the user is swiping it away
 * and the crash notice greets them on the next launch. The two ways in are
 * ordinary: a grant revoked mid-sync raises `SecurityException`, and a provider
 * on a network share or a phone on MTP raises an `IOException` into the
 * catch-all, either of them able to land in the moment the activity is finishing.
 *
 * The cancellation branch beside them has carried this guard since it was
 * written. These are its two siblings, which did not.
 *
 * Source reading, and the weaker layer, for the reason [SyncDialogTeardownTest]
 * shares with the other cases over this method: the handler is inside an Activity
 * method holding an `AlertDialog` and a `lifecycleScope`, and a plain JVM test
 * can build neither.
 *
 * NEGATIVE CONTROL, measured rather than assumed: dropping the
 * `if (!isFinishing && !isDestroyed)` from either failure handler reddens `every
 * failure handler guards the dialog it dismisses` and nothing else.
 */
class SyncDialogTeardownTest {

    private val file = File("src/main/kotlin/com/vscodroid/MainActivity.kt")

    private val source by lazy {
        check(file.isFile) {
            "MainActivity.kt not found at ${file.absolutePath}; this test would " +
                "otherwise pass by looking at nothing"
        }
        file.readText()
    }

    /** The body of a `private fun name(` declaration, to its closing brace. */
    private fun body(name: String): String {
        val start = source.indexOf("private fun $name(")
        assertTrue(start >= 0) {
            "$name is gone from MainActivity.kt, so this test is measuring nothing. " +
                "If it moved or was renamed, point this at the new site rather than " +
                "deleting it."
        }
        val open = source.indexOf('{', start)
        var depth = 0
        var i = open
        while (i < source.length) {
            if (source[i] == '{') depth += 1
            if (source[i] == '}') {
                depth -= 1
                if (depth == 0) return source.substring(open, i + 1)
            }
            i += 1
        }
        throw AssertionError("Could not find the end of $name in MainActivity.kt")
    }

    /**
     * Comment lines dropped, so prose about the guard cannot stand in for one and
     * a dismiss that has been commented out cannot be read as a live one.
     */
    private fun code(text: String): List<String> =
        text.lines().filterNot {
            val t = it.trimStart()
            t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
        }

    @Test
    fun `every failure handler guards the dialog it dismisses`() {
        val opened = code(body("openSafFolder"))

        val firstCatch = opened.indexOfFirst { it.contains("} catch (") }
        assertTrue(firstCatch >= 0) {
            "openSafFolder no longer handles anything, so this case is measuring nothing"
        }
        val handlers = opened.count { it.contains("} catch (") }
        assertEquals(3, handlers) {
            "expected the cancellation, permission and catch-all handlers. If one was " +
                "added or removed, check the new one dismisses the dialog only while " +
                "there is still a window under it."
        }

        val dismissals = opened.drop(firstCatch).filter { it.contains("dialog.dismiss()") }
        assertEquals(3, dismissals.size) {
            "expected one dismissal in each handler; found " +
                dismissals.joinToString("\n") { "  ${it.trim()}" }
        }

        val unguarded = dismissals.filterNot {
            it.contains("if (!isFinishing && !isDestroyed)")
        }
        assertEquals(emptyList<String>(), unguarded.map { it.trim() }) {
            "a failure handler dismisses the sync dialog without asking whether its " +
                "window is still there. A sync that fails for a real reason while the " +
                "activity is being torn down throws out of the handler, out of the " +
                "coroutine and into the uncaught handler, which kills the app."
        }
    }

    @Test
    fun `the dialog is still dismissed when the sync succeeds`() {
        // Control. Guarding every dismissal in the method, or deleting them, would
        // satisfy the case above by removing its subject: a finished sync has to
        // take its progress dialog off the screen, and there the activity is alive
        // by construction, because a cancelled scope cannot reach that line.
        val opened = code(body("openSafFolder"))
        val firstCatch = opened.indexOfFirst { it.contains("} catch (") }

        val success = opened.take(firstCatch).filter { it.contains("dialog.dismiss()") }
        assertEquals(1, success.size) {
            "the successful sync no longer dismisses its own progress dialog, so a modal " +
                "with no way out is left over the editor. Found: $success"
        }
    }
}
