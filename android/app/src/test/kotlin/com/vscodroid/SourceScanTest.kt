package com.vscodroid

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The helper the source-reading cases in this suite are built on.
 *
 * Those cases all have the same shape: extract a body, drop its comments, then
 * assert that a call is present or absent in what is left. The second step is
 * where a silent failure lives. Blanking too little is caught by the case itself
 * going red; blanking too much is not, because a name that has been deleted from
 * the text reads exactly like a call that was never made, and an assertion of
 * absence passes on it.
 *
 * So the blanking is checked here, on strings written for the purpose, rather
 * than inferred from the cases that use it.
 */
class SourceScanTest {

    /**
     * A block opener inside a string literal is not a comment.
     *
     * The reachable spelling is the wildcard mime type this app passes to the
     * file chooser, and it is not a curiosity. With the regex form this replaced,
     * the two of them in `MainActivity.kt` each swallowed everything up to the
     * next real close: about fifty lines of live code between them, with
     * `multiFileChooserLauncher.launch` among the calls in the hole. Anything a
     * case wanted to say about that call was then said about nothing.
     */
    @Test
    fun `a wildcard mime type does not blank the code after it`() {
        // The doc comment below is what gives the regex form its closing pair;
        // in the real file it is whatever doc comment comes next, which is why
        // the hole ran to the end of the following method rather than to the end
        // of the line.
        val source = """
            val types = arrayOf("*/*")
            multiFileChooserLauncher.launch(types)

            /** The next thing in the file. */
            fun other() = Unit
        """.trimIndent()

        val code = SourceScan.withoutComments(source)

        assertTrue(code.contains("multiFileChooserLauncher.launch")) {
            "a string literal was read as a comment opener, so the call after it is gone " +
                "and any case asserting on it is measuring an empty string: $code"
        }
    }

    /**
     * And the blanking still happens, which is the control the case above needs:
     * a helper that returned its input unchanged would satisfy it.
     *
     * Both forms, because either one disables a call. The block is the one every
     * doc comment in these files is written in, and the line comment is how a
     * call gets switched off while something is being debugged.
     */
    @Test
    fun `prose about a call cannot stand in for the call`() {
        val source = """
            /**
             * Calls startForegroundService, which is the point of it.
             */
            fun start() {
                // startForegroundService(intent)
                bindService(intent)
            }
        """.trimIndent()

        val code = SourceScan.withoutComments(source)

        assertTrue(!code.contains("startForegroundService")) {
            "the name survives in prose and in a call somebody commented out, so a search " +
                "for it answers yes for a method that does not make it: $code"
        }
        assertTrue(code.contains("bindService(intent)")) {
            "live code beside a comment was blanked with it: $code"
        }
    }

    /**
     * Lines survive, which is what lets a failure message quote one.
     *
     * The regex form collapsed each block comment to a single space, so a file
     * carrying more comment than code came out with its lines renumbered and its
     * offsets moved. Only the ORDER of two offsets meant anything, and a message
     * that printed a line number named a line in a file nobody has.
     */
    @Test
    fun `the line count is what it was`() {
        val source = """
            /*
             * two
             */
            val a = 1
        """.trimIndent()

        assertEquals(
            source.lines().size, SourceScan.withoutComments(source).lines().size,
            "a comment took its newlines with it, so every offset below it moved",
        )
    }
}
