package com.vscodroid.setup

import com.vscodroid.SourceScan
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What `SplashActivity` may still do once the user has left the download screen,
 * and what it may not.
 *
 * The download queue outlives this screen. Only Cancel sets `cancelled`, so
 * pressing Back on the first-run download screen finishes the activity while the
 * transfer carries on: the HTTP path reports straight through `onStateChange`
 * rather than through the Play Core listener `onDestroy` unregisters, so minutes
 * later `downloadNext()` drains the queue and calls `launchMain`. Starting
 * MainActivity from there either pops the app into the foreground long after the
 * user left it or is dropped by the background-activity-start rules with nothing
 * said.
 *
 * Guarding the whole method is the obvious fix and it costs more than it saves.
 * `publishToolchainShortcut()` sits at the end of it and is the ONLY publisher of
 * the launcher shortcut, which is the route to the Toolchains screen that does
 * not need a loaded workbench: the screen is not exported and has no launcher
 * entry, and the other way in is a Command Palette command that only exists once
 * saf-bridge has activated. An early return therefore took that route away for
 * the session, to stop a launch that was going to be refused anyway.
 *
 * Read out of the source through [SourceScan], following [LaunchRepairWiringTest],
 * which explains why an Activity is not reachable from a JVM test here and why
 * Robolectric costs more than the gap. What this proves is that the guards are
 * written and where they sit; whether the platform flags mean what they are read
 * to mean is not something any test here can say.
 */
class SplashLaunchGuardTest {

    private val source = SourceScan.read("src/main/kotlin/com/vscodroid/SplashActivity.kt")

    /**
     * The body of a declaration, bounded so an extraction that ran on cannot
     * satisfy an assertion by finding a name somewhere else.
     *
     * [SourceScan.body] has no bound of its own -- its brace match is
     * string-unaware, so overrunning is the failure mode it names -- and every
     * case here is a search for a token, which an overrun makes easier to satisfy
     * rather than harder.
     */
    private fun bodyOf(declaration: String): String {
        val body = SourceScan.body(source, declaration)
        check(body.length in 200..6_000) {
            "extracted ${body.length} characters of $declaration, which means the extraction " +
                "is wrong rather than the code"
        }
        return body
    }

    /** Comments name most of this, so the checks below read code only. */
    private fun code(body: String) = SourceScan.withoutComments(body)

    @Test
    fun `the editor is not started from a screen the user has already left`() {
        val body = code(bodyOf("private fun launchMain("))

        assertTrue(
            "isFinishing" in body && "isDestroyed" in body,
            "launchMain no longer asks whether this screen is still alive, so a download " +
                "queue draining minutes after Back starts MainActivity behind the user",
        )
        val guard = body.indexOf("isFinishing")
        val start = body.indexOf("startActivity(")
        assertTrue(start > guard, "startActivity is reached before the liveness check")
    }

    /**
     * The half that an early return took away.
     *
     * NEGATIVE CONTROL: put `return` back in the guard branch. The shortcut
     * publish then sits behind it and this reddens.
     */
    @Test
    fun `the toolchain shortcut is published whether or not the editor is started`() {
        val body = code(bodyOf("private fun launchMain("))

        assertTrue(
            "publishToolchainShortcut()" in body,
            "launchMain no longer publishes the launcher shortcut, which is the route to " +
                "the Toolchains screen that does not need a loaded workbench",
        )
        assertTrue(
            !Regex("""\breturn\b""").containsMatchIn(body),
            "launchMain returns early again, which skips publishToolchainShortcut() and " +
                "costs the user the route to the Toolchains screen that does not need a " +
                "loaded workbench, for the session",
        )
    }

    /**
     * The queue that outlives the screen must not depend on when the collector
     * runs.
     *
     * `onStateChange` is the only thing that advances it: `handleDownloadState`
     * ends in `downloadNext()`, which installs the pack behind the one that has
     * just settled. Held behind a `WeakReference`, the callback stopped firing as
     * soon as the finished Activity was collected, so pressing Back during the
     * first-run downloads installed the pack in flight and silently abandoned
     * every one queued behind it -- on the HTTP path only, which is every
     * sideload and GitHub-release install, and with the outcome decided by the
     * timing of a garbage collection.
     *
     * Source text rather than behaviour, for the reason the cases above are: the
     * callback is installed in an Activity method and this module has no
     * Robolectric.
     */
    @Test
    fun `the download callback is not gated on a reference that can be collected`() {
        val body = code(bodyOf("private fun startDownloads("))

        assertTrue(
            "manager.onStateChange" in body,
            "startDownloads no longer installs the state callback, so nothing advances the " +
                "first-run download queue at all",
        )
        assertFalse(
            "WeakReference" in body,
            "the state callback is behind a reference that can be collected, so the download " +
                "queue stops advancing whenever the collector reaches this finished screen " +
                "and every pack behind the one in flight is silently never installed",
        )
    }

    /**
     * And the other end of it: holding the screen strongly is only bounded
     * because the queue hands it back.
     *
     * `endDownloadQueue` clears the callback and unregisters the Play Core
     * listener when the queue drains and when Cancel ends it. Without the clear a
     * finished window stays reachable through the manager; without the
     * unregister, `ToolchainManager.install` has already re-registered the
     * listener that `onDestroy` handed back, so a queue draining after Back ends
     * with this screen in a process-wide registry that nothing takes it out of.
     */
    @Test
    fun `the queue hands the screen back when it ends`() {
        assertTrue(
            "endDownloadQueue()" in code(bodyOf("private fun downloadNext(")),
            "the drained queue no longer releases what the manager holds of this screen",
        )
        assertTrue(
            "endDownloadQueue()" in code(bodyOf("private fun startDownloads(")),
            "Cancel no longer releases what the manager holds of this screen",
        )
        val release = code(SourceScan.body(source, "private fun endDownloadQueue("))
        assertTrue(
            "unregisterListener()" in release && "onStateChange = null" in release,
            "endDownloadQueue gives back only half of what the manager holds, and either " +
                "half alone keeps this screen alive for the life of the process",
        )
    }

    /**
     * The one pack status that neither settles nor can be asked about twice.
     *
     * REQUIRES_USER_CONFIRMATION is Play holding a metered download until the
     * app puts its cellular-data question, and `showCellularDataConfirmation`
     * needs a live Activity to put it in. The queue outlives this screen, so a
     * pack reaching that status after Back was asking a window that is gone: the
     * call fails and is only logged, `isTerminalPackStatus` answers false, and
     * nothing calls `downloadNext()` again. The queue stopped rather than
     * skipped, so that toolchain and every one behind it were silently never
     * installed; `endDownloadQueue` is reached only where the queue ends, so this
     * destroyed screen stayed in Play Core's process-wide registry for the life
     * of the process; and the Cancel button that would have ended it is on a
     * screen the user has already left. ToolchainActivity can defer the question
     * to its next `onStart`, which is why the shared `isTerminalPackStatus` must
     * go on calling this status unsettled; only the branch here knows the screen
     * is never coming back.
     *
     * NEGATIVE CONTROL: drop the liveness test from the confirmation branch and
     * the first assertion reddens; take the extra term back out of the advance
     * and the second does.
     */
    @Test
    fun `a confirmation this screen cannot put ends the pack instead of waiting`() {
        val body = code(SourceScan.body(source, "private fun handleDownloadState("))
        assertFalse(
            "private fun launchMain" in body,
            "the body ran past handleDownloadState and swallowed what follows it, so the " +
                "assertions below are really a file-wide search",
        )

        assertTrue(
            "isFinishing" in body && "isDestroyed" in body,
            "handleDownloadState no longer asks whether this screen is still alive, so a " +
                "pack that reaches REQUIRES_USER_CONFIRMATION after the user has left waits " +
                "on a cellular-data question nobody can be asked",
        )

        val advance = body.indexOf("downloadNext()")
        assertTrue(advance > 0, "handleDownloadState no longer advances the queue at all")
        val condition = body.substring(body.lastIndexOf("if (", advance), advance)
        assertTrue(
            "isTerminalPackStatus(status)" in condition,
            "the advance no longer reads the pack status: $condition",
        )
        assertTrue(
            "||" in condition,
            "the queue advances on the pack status alone again, so a confirmation this " +
                "screen cannot put stops it for good: isTerminalPackStatus answers false for " +
                "that status and nothing else calls downloadNext(). Condition: $condition",
        )
    }

    /**
     * And the queue's other way of reaching a user who is elsewhere.
     *
     * The row carries one word and the reason is a sentence, so a failed pack
     * says why in a Toast. The queue outlives the screen, so a failure landing
     * after Back put that sentence over the editor, about a screen that is no
     * longer there. `ToolchainActivity` gates the same message on its screen
     * being started; this one has no coming back to defer to, so the message is
     * dropped rather than held.
     */
    @Test
    fun `the reason for a failure is not put in front of a user who has left`() {
        val body = code(bodyOf("private fun startDownloads("))

        val toast = body.indexOf("Toast.makeText")
        assertTrue(toast > 0, "startDownloads no longer says why a pack failed at all")
        val guard = body.indexOf("isFinishing")
        assertTrue(
            guard in 0 until toast && "isDestroyed" in body,
            "the failure Toast is not gated on this screen still being here, so a download " +
                "that fails minutes after Back explains itself over the editor",
        )
    }

    /**
     * The control for every extraction above, because a body that ran past its
     * own declaration would satisfy the token searches by finding them somewhere
     * else.
     */
    @Test
    fun `each extracted body stops at the declaration that follows it`() {
        assertFalse(
            "private fun publishToolchainShortcut" in bodyOf("private fun launchMain("),
            "bodyOf ran past launchMain and swallowed the next declaration, so its " +
                "assertions are really a file-wide search",
        )
        assertFalse(
            "private fun buildProgressRow" in bodyOf("private fun startDownloads("),
            "bodyOf ran past startDownloads and swallowed the next declaration, so its " +
                "assertions are really a file-wide search",
        )
    }
}
