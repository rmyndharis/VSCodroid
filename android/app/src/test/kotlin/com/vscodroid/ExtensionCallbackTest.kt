package com.vscodroid

import com.vscodroid.bridge.AuthTabWindow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The gate on the one exported entry point this app has.
 *
 * `AndroidManifest.xml` gives `MainActivity` a VIEW filter for
 * `vscodroid://callback` carrying BROWSABLE, so the sender is any installed app
 * or any page the user taps a link on — and what arrives is written into the
 * workbench's `localStorage` and announced with a synthetic `StorageEvent`. The
 * filter exists precisely so an outside app (the browser finishing a sign-in)
 * can reach in, so the check cannot be about who sent it. What is left is that
 * it is shaped like the one message this relay is for.
 */
class ExtensionCallbackTest {

    @Test
    fun `the callback relay is recognised`() {
        assertTrue(isExtensionCallback("vscodroid", "callback"))
    }

    @Test
    fun `neither half alone is enough`() {
        // The mutation is `||` for `&&`, or dropping one comparison as
        // redundant. Either widens an exported, browsable entry point to
        // anything under the app's own scheme, or to any scheme at all pointed
        // at the word "callback".
        assertFalse(isExtensionCallback("vscodroid", "anything-else"))
        assertFalse(isExtensionCallback("https", "callback"))
    }

    @Test
    fun `a launcher intent carrying no URI is not a callback`() {
        // The routine case. MainActivity is singleTask, so every relaunch
        // arrives through the same two methods with null data.
        assertFalse(isExtensionCallback(null, null))
        assertFalse(isExtensionCallback("vscodroid", null))
        assertFalse(isExtensionCallback(null, "callback"))
    }

    @Test
    fun `the scheme and host are matched exactly`() {
        // Android lower-cases the scheme and host of an intent URI before it is
        // matched against a filter, so a comparison that stayed
        // case-insensitive would be granting nothing and hiding the fact that
        // the platform already normalised. Stated so the exact match is not
        // "loosened" later by someone assuming it has to be.
        assertFalse(isExtensionCallback("VSCodroid", "callback"))
        assertFalse(isExtensionCallback("vscodroid", "Callback"))
        assertFalse(isExtensionCallback("vscodroid ", "callback"))
    }
}

/**
 * That the window actually carries the value between the two halves.
 *
 * `authCallbackIsExpected` is tested on numbers and the call sites are tested on
 * source text, so nothing in either touches the object that carries the reading
 * from the browser launch to the relay. An `AuthTabWindow` that returned a
 * constant, or whose accessor recursed into itself, would leave both sets green
 * and every sign-in broken on the device.
 *
 * Plain JVM: the object holds a Long its callers supply and reads no Android API.
 */
class AuthTabWindowTest {

    @Test
    fun `the reading handed in is the reading handed back`() {
        AuthTabWindow.opened(123_456L)
        assertEquals(123_456L, AuthTabWindow.openedAt())

        AuthTabWindow.opened(987_654L)
        assertEquals(
            987_654L, AuthTabWindow.openedAt(),
            "a second launch must move the window, not be ignored",
        )

        // Back to the value a fresh process starts with. This object is a
        // process-wide singleton and these are the only tests that touch it, but
        // leaving it armed would hand the next test a state it did not set.
        AuthTabWindow.opened(0L)
        assertEquals(0L, AuthTabWindow.openedAt())
    }
}

/**
 * That the timing gate is reached, and that anything opening a browser arms it.
 *
 * `authCallbackIsExpected` can be exactly right and never called, and the two
 * halves fail in opposite directions: a relay that never asks accepts
 * everything, and a browser launch that never records leaves the gate shut on a
 * real sign-in. Neither shows up in a test of the function itself.
 *
 * Source reading, which is weaker than the rest of this suite, and here for the
 * reason `ServerReadinessCallSiteTest` gives: the decision lives inside an
 * Activity method with no seam, and the mutation is not a value the code
 * computes but whether a call is present at all.
 *
 * What it does not catch: a browser opened from a third file, or the gate
 * reached through some other spelling. It catches removing either half of what
 * is here, which is the regression that would actually be made.
 */
class AuthCallbackCallSiteTest {

    private val mainActivity = File("src/main/kotlin/com/vscodroid/MainActivity.kt")
    private val bridge = File("src/main/kotlin/com/vscodroid/bridge/AndroidBridge.kt")

    /** Comments dropped: all of these names are discussed in prose above them. */
    private fun code(file: File): List<String> =
        file.readLines().filterNot {
            val t = it.trimStart()
            t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
        }

    @Test
    fun `the relay asks whether a sign-in was in flight`() {
        check(mainActivity.isFile) {
            "MainActivity.kt not found at ${mainActivity.absolutePath} — this test " +
                "would otherwise pass by looking at nothing"
        }

        // The declaration is in this same file and its first line reads
        // `fun authCallbackIsExpected(`, so a search for the name alone is
        // satisfied by the function existing. Measured: deleting the entire
        // gate from receiveCallbackIntent left all 630 tests green.
        val calls = code(mainActivity).filterNot { it.contains("fun authCallbackIsExpected") }
            .count { it.contains("authCallbackIsExpected(") }

        assertTrue(
            calls >= 1,
            "the vscodroid://callback relay must refuse a callback no sign-in was " +
                "waiting for; found $calls call site(s), and without one the " +
                "exported entry point takes a value from anything on the device " +
                "at any moment",
        )
    }

    @Test
    fun `the restart message is decided before the timing gate, not after`() {
        // A regression that was made and caught in review rather than by any
        // test. Putting the timing gate first is the obvious ordering and it is
        // wrong: the case the restart message exists for is arriving through
        // onCreate after the process was killed with the browser in front, and a
        // fresh process has openedAt == 0, so the gate returns before the
        // message is ever reached. The user who came back from signing in gets
        // silence, and every test stays green because neither branch is a value
        // any of them can see.
        check(mainActivity.isFile) { "MainActivity.kt not found" }

        val lines = code(mainActivity)
        val restart = lines.indexOfFirst { it.contains("if (!workbenchLoaded)") }
        val gate = lines.indexOfFirst {
            it.contains("authCallbackIsExpected(") && !it.contains("fun authCallbackIsExpected")
        }

        assertTrue(restart >= 0, "the no-workbench branch is gone; the restart message with it")
        assertTrue(gate >= 0, "the timing gate is gone")
        assertTrue(
            restart < gate,
            "the no-workbench branch must come first: it explains rather than " +
                "injects, and gating it silences the one case it was written for",
        )
    }

    @Test
    fun `every browser launch arms the window it will be judged against`() {
        // The control for the test above, and the half more likely to rot: a
        // second launch site added later without recording would leave that
        // sign-in's return refused, and the symptom is a sign-in that quietly
        // never completes rather than anything that fails loudly.
        check(bridge.isFile) { "AndroidBridge.kt not found at ${bridge.absolutePath}" }

        val lines = code(bridge)
        val launches = lines.count { it.contains("launchUrl(") }
        val arms = lines.count { it.contains("AuthTabWindow.opened(") }

        assertTrue(launches >= 1, "expected at least one browser launch; found $launches")
        assertEquals(
            launches, arms,
            "each browser launch must record that it happened, or the callback it " +
                "returns with is refused",
        )
    }
}

/**
 * The second half of that gate: whether anyone here asked for the callback.
 *
 * Shape was the only thing the relay could judge, and shape is exactly what an
 * unsolicited sender can produce. The id the value is filed under is handed out
 * by the workbench as a counter from one, so it is not a secret that has to be
 * guessed either. Identity cannot be checked at all — the legitimate sender is
 * whichever browser the user has — so what is left is timing: this app either
 * opened a sign-in tab in the last few minutes or it did not.
 *
 * Every case here is arithmetic on three numbers, which is why the function
 * takes its clock readings instead of asking for them.
 */
class AuthCallbackExpectedTest {

    private val window = 10 * 60 * 1000L
    private val opened = 5_000_000L

    @Test
    fun `a return from a sign-in this app started is taken`() {
        // The positive control. Every other case here is a refusal, and a
        // function that had been mutated to refuse everything would satisfy all
        // of them while breaking every sign-in in the app.
        assertTrue(authCallbackIsExpected(opened, opened + 30_000L, window))
    }

    @Test
    fun `a callback arriving with no sign-in in flight is refused`() {
        // The mutation is dropping the `openedAt != 0` guard as implied by the
        // range check. It is not: zero is the reading before any tab has been
        // opened, and early in a boot the elapsed clock is small enough that
        // `now - 0` falls inside the window on its own. That is the whole
        // attack -- a callback nobody asked for, accepted because the numbers
        // happened to line up.
        assertFalse(authCallbackIsExpected(0L, 1_000L, window))
        assertFalse(authCallbackIsExpected(0L, window, window))
    }

    @Test
    fun `a callback long after the tab was opened is refused`() {
        // The mutation is dropping the upper bound, which turns one sign-in into
        // a door that stays open for the rest of the process's life.
        assertFalse(authCallbackIsExpected(opened, opened + window + 1, window))
        assertFalse(authCallbackIsExpected(opened, opened + 24 * 60 * 60 * 1000L, window))
    }

    @Test
    fun `a reading from before the tab was opened is refused`() {
        // The mutation is dropping the lower bound as unreachable. The caller
        // passes a monotonic clock, so a negative elapsed time means the two
        // readings came from different boots and the difference is not an
        // elapsed time at all -- and it compares as comfortably under the
        // window, so a one-sided check accepts it.
        assertFalse(authCallbackIsExpected(opened, opened - 1, window))
        assertFalse(authCallbackIsExpected(opened, 0L, window))
    }

    @Test
    fun `the window is inclusive at its edge and closed one past it`() {
        // The mutation is `until` for `..`. It costs a millisecond of a
        // ten-minute window, so no sign-in would ever fail because of it and
        // nothing would ever say the boundary had moved. Pinned so the range is
        // stated rather than inherited.
        assertTrue(authCallbackIsExpected(opened, opened + window, window))
        assertFalse(authCallbackIsExpected(opened, opened + window + 1, window))
    }
}

/**
 * What a failed folder switch leaves watched.
 *
 * The watcher is stopped before every sync so the engine cannot observe its own
 * writes, which means a failure has to decide whether to put it back — and the
 * first version of that decision was "never", justified by a mirror being
 * part-written. That justification only holds for the folder the sync was
 * writing into. Switch away from a healthy folder, fail, and "never" leaves the
 * folder still on screen unwatched: the user goes on editing it believing it is
 * saving, and nothing anywhere says otherwise.
 */
class RestoreWatcherTest {

    private val opened = "content://com.android.externalstorage.documents/tree/primary%3Awork"
    private val other = "content://com.android.externalstorage.documents/tree/primary%3Anotes"

    @Test
    fun `a failed switch to a different folder keeps the open one watched`() {
        assertTrue(shouldRestorePreviousWatcher(previousUri = opened, failedUri = other))
    }

    @Test
    fun `a failed reopen of the watched folder leaves it stopped`() {
        // Its mirror is the one the sync was part-way through writing, and a
        // watcher over a part-written mirror pushes that half onto the user's
        // own documents. This is the case the whole reorder exists for, so
        // restoring unconditionally would undo it.
        assertFalse(shouldRestorePreviousWatcher(previousUri = opened, failedUri = opened))
    }

    @Test
    fun `nothing is restored when nothing was watched`() {
        // The first folder of a session. Restoring here would ask
        // startFileWatcher for a mirror that does not exist.
        assertFalse(shouldRestorePreviousWatcher(previousUri = null, failedUri = opened))
    }
}

/**
 * What the resume health check is allowed to depend on.
 *
 * The script used to search every `.monaco-dialog-box` for the words
 * "reconnect" and "lost". That is a check on the display language wearing the
 * costume of a check on the connection: under a language pack the substrings are
 * translated, the match never fires, and a broken IPC channel is reported as a
 * healthy one. Nothing throws and nothing logs, so the users it fails are
 * exactly the ones least able to report why.
 */
class ConnectionHealthProbeTest {

    @Test
    fun `the probe reads no user-visible text`() {
        val script = connectionHealthProbe()

        assertFalse(
            script.contains("textContent"),
            "reading rendered text makes the probe depend on the display language",
        )
        assertFalse(
            script.contains("monaco-dialog"),
            "a workbench dialog can only be identified here by the words in it",
        )
    }

    @Test
    fun `the probe asks something that answers the same in every locale`() {
        // A probe that reads nothing at all would also pass the test above.
        assertTrue(
            connectionHealthProbe().contains("indexedDB.open"),
            "narrowing the probe must not empty it",
        )
    }
}
