package com.vscodroid.setup

import android.content.Context
import com.vscodroid.R
import com.vscodroid.setupFailureMessage
import io.mockk.every
import io.mockk.mockk
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

    private fun messageContext(): Context = mockk<Context>().also { context ->
        every { context.getString(R.string.error_setup_failed) } returns "Setup failed"
        every {
            context.getString(R.string.error_setup_failed_at, "Tools", "IOException: disk full")
        } returns "Failed at Tools: IOException: disk full"
    }

    @Test
    fun `a failure without a step retains its cause without an empty label`() {
        val failure = FirstRunSetup.describeFailure(null, IOException("early dependency failed"))
        assertEquals("Setup failed\nIOException: early dependency failed",
            setupFailureMessage(messageContext(), failure))
    }

    @Test
    fun `a named failure uses its localized step and detail`() {
        val failure = FirstRunSetup.Failure("Tools", "IOException: disk full")
        assertEquals("Failed at Tools: IOException: disk full",
            setupFailureMessage(messageContext(), failure))
    }

    @Test
    fun `an absent failure uses only the generic message`() {
        assertEquals("Setup failed", setupFailureMessage(messageContext(), null))
    }

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
     * The same, for the ellipsis the app actually emits.
     *
     * The three dots above are the form the labels had while they were Kotlin
     * literals. They are resources now, written `\u2026` in `strings.xml` and
     * translated, so what reaches this function is one character rather than
     * three, and a translator can write it in their own convention. Trimming
     * only the ASCII form left every failure message ending "Setting up git..."
     * mid-sentence.
     */
    @Test
    fun `the step loses a real ellipsis too`() {
        val failure = FirstRunSetup.describeFailure("\u2026", IOException("x"))
        assertEquals("", failure.step, "a step that is nothing but an ellipsis kept it")

        val git = FirstRunSetup.describeFailure("Setting up git\u2026", IOException("x"))
        assertEquals(
            "Setting up git", git.step,
            "the step still trails the ellipsis the resources carry, so the screen reads " +
                "\"Setup failed while: Setting up git…\"",
        )
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

    @Test
    fun `several failed extraction components still name the disk error`() {
        val message = "could not unpack vscode-reh, server.js, process-monitor.js, " +
            "platform-fix.js, dns-proxy.js, xdg-open.js, usr: " +
            "server/vscode-reh/node_modules/deep/nested/file.js: " +
            "open failed: ENOSPC (No space left on device)"

        val failure = FirstRunSetup.describeFailure("Extracting tools", IOException(message))

        assertTrue(failure.detail.endsWith(": ENOSPC (No space left on device)"), failure.detail)
        assertTrue(failure.detail.length <= "IOException: ".length + FirstRunSetup.DETAIL_LIMIT)
    }

    @Test
    fun `a long unpack message with a deep path keeps the trailing errno cause`() {
        val deepMsg =
            "could not unpack bundled extension github.copilot-chat-0.43.2026033101: " +
                "home/.vscodroid/extensions/github.copilot-chat-0.43.2026033101/node_modules/" +
                "@github/copilot/node_modules/@ Sharp/some/very/deep/nested/file.js: " +
                "open failed: ENOSPC (No space left on device)"
        assertTrue(deepMsg.length > FirstRunSetup.DETAIL_LIMIT)

        val failure = FirstRunSetup.describeFailure("Configuring extensions…", IOException(deepMsg))

        assertEquals("Configuring extensions", failure.step)
        assertTrue(
            failure.detail.endsWith(": open failed: ENOSPC (No space left on device)") ||
                failure.detail.endsWith(": ENOSPC (No space left on device)"),
            "truncating from the right dropped the OS reason at the tail: ${failure.detail}",
        )
        assertTrue(
            failure.detail.contains("…"),
            "middle-elided detail must still mark the omitted path segment with an ellipsis: ${failure.detail}",
        )
        assertTrue(
            failure.detail.length <= "IOException: ".length + FirstRunSetup.DETAIL_LIMIT,
            "detail exceeded the length budget: ${failure.detail.length}",
        )

        val serverMsg =
            "could not unpack vscode-reh: server/vscode-reh/node_modules/@vscode/ripgrep/" +
                "bin/some/very/deep/nested/directory/structure/rg: " +
                "open failed: ENOSPC (No space left on device)"
        assertTrue(serverMsg.length > FirstRunSetup.DETAIL_LIMIT)
        val serverFailure = FirstRunSetup.describeFailure("Extracting VS Code Server…", IOException(serverMsg))
        assertEquals("Extracting VS Code Server", serverFailure.step)
        assertTrue(
            serverFailure.detail.endsWith(": open failed: ENOSPC (No space left on device)") ||
                serverFailure.detail.endsWith(": ENOSPC (No space left on device)"),
            "server unpack detail dropped the trailing errno cause: ${serverFailure.detail}",
        )
        assertTrue(serverFailure.detail.contains("…"))

        val singleColonMsg = "could not unpack vscode-reh: " + "x".repeat(FirstRunSetup.DETAIL_LIMIT)
        val singleColonFailure = FirstRunSetup.describeFailure("Extracting VS Code Server…", IOException(singleColonMsg))
        assertTrue(
            singleColonFailure.detail.startsWith("IOException: could not unpack vscode-reh: "),
            "a message with a single colon and an overlong tail must not slice the step name: ${singleColonFailure.detail}",
        )
    }

    /**
     * A file system exception reads "path: reason" with one separator, and when the path is
     * long the reason, which is the part that says what to do, was cut with the rest.
     */
    @Test
    fun `a long message with one separator keeps a short reason at the tail`() {
        val path = "server/vscode-reh/" + "deep/".repeat(30) + ".vscodroid-1a2b-rg.tmp~"
        val failure = FirstRunSetup.describeFailure(
            "Extracting tools...", java.nio.file.FileSystemException(path, null, "No space left on device"),
        )

        assertTrue(failure.detail.endsWith(": No space left on device"), failure.detail)
        assertTrue(failure.detail.contains("…"), "the omitted middle is not marked: ${failure.detail}")
        assertTrue(
            failure.detail.length <= "FileSystemException: ".length + FirstRunSetup.DETAIL_LIMIT,
            "detail exceeded the length budget: ${failure.detail.length}",
        )
    }

    @Test
    fun `a long message with one separator and a tail longer than a reason keeps its head`() {
        // A tail longer than a reason and shorter than the budget, so keeping it would cut the step name.
        val step = "could not unpack bundled extension github.copilot-chat-0.43"
        val message = "$step: " + "x".repeat(FirstRunSetup.REASON_LIMIT + 32)
        assertTrue(message.length > FirstRunSetup.DETAIL_LIMIT, "setup: the message fits and is not cut")
        val failure = FirstRunSetup.describeFailure("Configuring extensions…", IOException(message))

        assertTrue(
            failure.detail.startsWith("IOException: $step: "),
            "a tail that is not a reason must leave the step name whole: ${failure.detail}",
        )
    }
}
