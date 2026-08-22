package com.vscodroid.webview

import com.vscodroid.FORCE_RELOAD_THRESHOLD_MS
import com.vscodroid.HEALTH_CHECK_THRESHOLD_MS
import com.vscodroid.ResumeAction
import com.vscodroid.resumeAction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * That coming back from the save picker does not throw the save away.
 *
 * Saving a download opens the create-document picker, which is another app, so
 * the browse *is* the absence this Activity measures. Past five minutes the
 * resume rule reloads the WebView, which fires `onPageFinished`, which tells the
 * coordinator the page owing the bytes is gone; the picker's answer then arrives
 * naming a download nothing is holding any more, and the document it created is
 * deleted. The user chose a folder, typed a name, and received nothing.
 *
 * Reachable rather than theoretical, because of the ordering: Android delivers an
 * activity result after `onStart` and before `onResume`, and the resume decision
 * is taken from `onStart`, so the answer is always still in flight at exactly the
 * moment the decision is made.
 *
 * The rule already covers the other picker, `<input type=file>`, and gets this
 * one for the same reason. What it did not have was a way to know: the predicate
 * asked the chrome client, and the create-document picker is launched from a
 * separate registration whose outstanding state lives in the Activity. So the
 * behaviour cases below are joined by two source-shape cases, because a decision
 * function that is exactly right and never told is the shape this defect had.
 */
class SavePickerResumeTest {

    private val source = File("src/main/kotlin/com/vscodroid/MainActivity.kt")

    /** The file's lines with comment lines dropped, so prose cannot answer for code. */
    private fun code(): List<String> {
        assertTrue(source.isFile) { "MainActivity.kt not found at ${source.absolutePath}" }
        return source.readLines()
            .filterNot { val t = it.trimStart(); t.startsWith("//") || t.startsWith("*") }
    }

    @Test
    fun `a save picker still waiting stops the reload`() {
        assertEquals(
            ResumeAction.NOTHING,
            resumeAction(
                FORCE_RELOAD_THRESHOLD_MS + 1,
                signInPending = false,
                fileChooserPending = false,
                savePickerPending = true,
            ),
            "the reload discards the page the picker's answer has to come back into, so " +
                "the file the user just named can never be read",
        )
    }

    @Test
    fun `a save picker stops the probe as well as the reload`() {
        assertEquals(
            ResumeAction.NOTHING,
            resumeAction(
                HEALTH_CHECK_THRESHOLD_MS + 1,
                signInPending = false,
                fileChooserPending = false,
                savePickerPending = true,
            ),
            "the probe reloads the page from JS whenever IndexedDB is unusable, which " +
                "discards the pending save exactly as the forced reload does",
        )
        assertEquals(
            ResumeAction.NOTHING,
            resumeAction(
                FORCE_RELOAD_THRESHOLD_MS + 1,
                signInPending = true,
                fileChooserPending = false,
                savePickerPending = true,
            ),
            "a sign-in in flight downgrades the reload to the probe, and the probe is " +
                "still too strong for a picker that is about to answer",
        )
    }

    /**
     * The control, and the reason the two cases above are not satisfied by a
     * function that answers NOTHING to everything.
     */
    @Test
    fun `with no picker out a long absence still reloads`() {
        assertEquals(
            ResumeAction.RELOAD,
            resumeAction(
                FORCE_RELOAD_THRESHOLD_MS + 1,
                signInPending = false,
                fileChooserPending = false,
                savePickerPending = false,
            ),
        )
    }

    @Test
    fun `the resume decision is told about the save picker`() {
        // The parameter defaults to false so that the eleven call sites in the
        // existing suite keep compiling, and that default is exactly what would
        // hide a dropped argument here: every case above would stay green while
        // the reload went back to discarding the file the user just named.
        val decisions = code()
            .filter { it.contains("resumeAction(") }
            .filterNot { it.contains("fun resumeAction") }

        assertEquals(1, decisions.size, "expected one resume decision, found: $decisions")
        assertTrue(
            decisions.single().contains("savePickerIsPending()"),
            "the resume decision must be told whether the download's create-document " +
                "picker is still out; found: " + decisions.single(),
        )
    }

    @Test
    fun `the reader answers from the picker's own record`() {
        val declaration = "private fun savePickerIsPending("
        val text = source.readText()
        val start = text.indexOf(declaration)
        assertTrue(start >= 0) {
            "savePickerIsPending is gone from MainActivity.kt, so the case above is " +
                "checking that an argument names something that no longer exists. If it " +
                "moved or was renamed, point both cases at the new site."
        }
        val end = text.indexOf("\n\n", start).takeIf { it > start } ?: text.length
        val reader = text.substring(start, end)

        assertTrue(reader.contains("pickerRequestId")) {
            "the reader has to answer from the field the picker's own result clears, or " +
                "it can drift from the answer it is waiting for: a constant, or the " +
                "coordinator's own state, is set and taken back somewhere else. Found:\n" +
                reader
        }
    }
}
