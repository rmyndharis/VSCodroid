package com.vscodroid

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * That the remove confirmation is let go of when the screen is.
 *
 * `ToolchainActivity` declares no `android:configChanges`, unlike the editor and
 * the splash screen, so a rotation or a multi-window resize destroys it and
 * builds it again. A dialog left showing is attached to the window that goes with
 * it: the framework logs `android.view.WindowLeaked` and the question the user
 * was asked disappears with their answer unmade.
 *
 * Read out of the source for the reason `ActivityTeardownTest` gives: no plain
 * JVM test here can drive an Activity lifecycle, and this project's unit tests
 * have no Robolectric. What is checkable is the shape -- the dialog is held, and
 * `onDestroy` dismisses it -- and the manifest's silence about configChanges,
 * which is what makes the shape necessary.
 */
class ToolchainRemoveDialogTest {

    private val activity = File("src/main/kotlin/com/vscodroid/ToolchainActivity.kt")
    private val manifest = File("src/main/AndroidManifest.xml")

    /**
     * The source with its comments dropped. Every rule below is discussed in
     * prose beside the line it governs, and a search over raw text would be
     * satisfied by the discussion.
     */
    private val code: String by lazy {
        assertTrue(
            activity.isFile,
            "${activity.path} is not at ${activity.absolutePath}; this test would " +
                "otherwise pass by reading nothing",
        )
        activity.readText()
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), " ")
            .replace(Regex("""//[^\n]*"""), " ")
    }

    /** The body of a declaration, by brace matching from it. */
    private fun body(declaration: String): String {
        val start = code.indexOf(declaration)
        assertTrue(start >= 0, "`$declaration` is gone from ToolchainActivity.kt")
        val open = code.indexOf('{', start)
        var depth = 0
        var i = open
        while (i < code.length) {
            if (code[i] == '{') depth += 1
            if (code[i] == '}') {
                depth -= 1
                if (depth == 0) return code.substring(open, i + 1)
            }
            i += 1
        }
        throw AssertionError("Could not find the end of `$declaration` in ToolchainActivity.kt")
    }

    /**
     * The premise, and it is not decoration: if this screen ever declares
     * configChanges the rotation stops destroying it, and the case below would go
     * on asserting a shape for a reason that no longer applies. It would still be
     * right for a `finish()` from the toolbar, which is why it stays either way,
     * but the reader deserves to know which of the two they are looking at.
     */
    @Test
    fun `the toolchains screen is still one a rotation destroys`() {
        assertTrue(manifest.isFile, "${manifest.path} is missing")
        val entry = manifest.readText()
            .substringAfter(""".ToolchainActivity""")
            .substringBefore("/>")

        assertTrue(
            entry.isNotEmpty() && entry.length < 2_000,
            "the ToolchainActivity entry was not found in the manifest",
        )
        assertTrue(
            "configChanges" !in entry,
            "ToolchainActivity now declares configChanges, so a rotation no longer " +
                "recreates it. The dismissal below is still right for finish(), but the " +
                "leak it was written for is gone: $entry",
        )
    }

    /**
     * NEGATIVE CONTROL: drop the `removeDialog?.dismiss()` line from `onDestroy`,
     * or stop assigning the built dialog to the field, and this goes red on the
     * matching assertion. Measured for both.
     */
    @Test
    fun `the remove confirmation is held and dismissed when the screen goes`() {
        assertTrue(
            Regex("""(?m)^\s*removeDialog = AlertDialog\.Builder\(""").containsMatchIn(code),
            "showRemoveConfirmation drops the dialog it built, so nothing can take it " +
                "down when the Activity is destroyed and the framework logs WindowLeaked",
        )
        assertTrue(
            Regex("""(?m)^\s*removeDialog\?\.dismiss\(\)""").containsMatchIn(body("override fun onDestroy()")),
            "onDestroy does not dismiss the remove confirmation, so rotating while it is " +
                "up leaks the window and loses the user's answer",
        )
        // Cleared where the dialog itself ends, so the field cannot outlive the
        // window it names: a stale reference is dismissed a second time, and the
        // Activity is kept alive by it in the meantime.
        assertTrue(
            Regex("""setOnDismissListener \{ removeDialog = null \}""").containsMatchIn(code),
            "nothing clears removeDialog when the user answers, so the field holds a " +
                "dialog that has already gone",
        )
    }
}
