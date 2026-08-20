package com.vscodroid.setup

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

/**
 * What a failed first run is able to tell the user.
 *
 * Setup catches everything at one point and returns a bare `ERROR`. The exception
 * went to `Logger.e` and nowhere else, and `Logger.e` is not readable on a release
 * build without a cable, so the whole of what the user got was "Setup failed" and
 * a Retry button. Retrying is the only move that string suggests, and it is the
 * wrong one for the two failures most likely to be real: no space left, and a
 * permission the app does not have. Both repeat for ever.
 *
 * These drive the pure half, which is the half that decides what appears on the
 * screen. The wiring that carries it there is one branch in `SplashActivity`.
 */
class SetupFailureCauseTest {

    /**
     * The message is kept, not just the type. It is the half that names something
     * the user can act on; `IOException` alone says only that something threw.
     */
    @Test
    fun `the exception message survives into the description`() {
        val failure = FirstRunSetup.describeFailure(
            "Extracting server files...", IOException("No space left on device")
        )

        assertEquals("Extracting server files", failure.step)
        assertEquals("IOException: No space left on device", failure.detail)
    }

    /**
     * Every progress label ends in an ellipsis, and the screen reads
     * "Setup failed while: <step>". Left in place the sentence looks unfinished.
     */
    @Test
    fun `the step loses the trailing ellipsis of its progress label`() {
        val failure = FirstRunSetup.describeFailure("Setting up git...", IOException("x"))

        assertEquals("Setting up git", failure.step)
        assertFalse(failure.step.endsWith("."), "the step still trails a progress ellipsis")
    }

    /**
     * A failure before the first progress report has no step to name. The screen
     * falls back to the plain string, so an empty step has to be distinguishable
     * from a real one rather than rendering as "failed while: ".
     */
    @Test
    fun `a failure with no step reported yet describes an empty step`() {
        val failure = FirstRunSetup.describeFailure(null, IOException("early"))

        assertEquals("", failure.step)
        assertEquals("IOException: early", failure.detail)
    }

    /**
     * Some exceptions carry no message at all. Appending an empty one would put a
     * bare colon on the screen.
     */
    @Test
    fun `an exception with no message contributes only its type`() {
        val failure = FirstRunSetup.describeFailure("Creating directories...", IllegalStateException())

        assertEquals("IllegalStateException", failure.detail)
        assertFalse(failure.detail.endsWith(":"), "a bare colon reached the message")
    }

    /**
     * A chained exception can carry a paragraph. The splash screen is not a log
     * viewer, and the untruncated text is already in `Logger.e` at the call site.
     */
    @Test
    fun `a very long message is truncated and marked as truncated`() {
        val long = "x".repeat(FirstRunSetup.DETAIL_LIMIT * 3)
        val failure = FirstRunSetup.describeFailure("Extracting tools...", IOException(long))

        assertTrue(
            failure.detail.length < long.length,
            "a ${long.length} character message reached the screen whole",
        )
        assertTrue(failure.detail.endsWith("…"), "the truncation is not marked")
    }

    /**
     * The control. Truncation must not eat a message that fits, or every ordinary
     * failure would be reported as if something had been withheld.
     */
    @Test
    fun `a message at the limit is not truncated`() {
        val exact = "y".repeat(FirstRunSetup.DETAIL_LIMIT)
        val failure = FirstRunSetup.describeFailure("Setting up tools...", IOException(exact))

        assertEquals("IOException: $exact", failure.detail)
        assertFalse(failure.detail.endsWith("…"), "a message that fits was marked truncated")
    }
}
