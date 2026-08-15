package com.vscodroid

import android.net.Uri
import com.vscodroid.bridge.AuthTabWindow
import com.vscodroid.webview.redactToken
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
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
        // A regression that no test caught, which is why this one exists.
        // Putting the timing gate first is the obvious ordering and it is
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
 * That the workbench URL and the redactor agree about where the token lives.
 *
 * The connection token is the whole of the server's authentication: it is
 * required on every route but `/version`, `/delay-shutdown` and `/callback`, so
 * anything holding it can read what the server can read and open a terminal.
 * `Logger.i` is not gated on a debuggable build, so a token printed here reaches
 * a release build's logcat.
 *
 * The coupling is the thing worth pinning. `redactToken` keys on the literal
 * `tkn=`, and nothing but this test connects that to the parameter the URL is
 * built with — rename the parameter and the redactor matches nothing while every
 * log statement still reads as redacted.
 */
class WorkbenchUrlTest {

    /** Distinctive enough that a match cannot be a coincidence. */
    private val token = "tkn-8f31c07a-do-not-log-me"

    @BeforeEach
    fun setUp() {
        // The token is a UUID in production, so identity encoding is faithful.
        // Uri is a stub under the unit-test android.jar and throws otherwise.
        mockkStatic(Uri::class)
        every { Uri.encode(any()) } answers { firstArg<String>() }
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    @Test
    fun `the token the workbench needs is the token the redactor hides`() {
        val url = workbenchUrl(13337, "/data/data/com.vscodroid/files/home/projects", token)

        assertTrue(
            url.contains(token),
            "the navigation URL must carry the token or the server answers Forbidden: $url",
        )
        assertFalse(
            redactToken(url).contains(token),
            "the redactor does not recognise where the token is in this URL, so every " +
                "log statement that trusts it prints the credential: ${redactToken(url)}",
        )
    }

    @Test
    fun `a server that has not written its token yet yields a bare URL`() {
        // Control, and a real case: the token file does not exist until the
        // server has started. An empty `tkn=` would be indistinguishable from a
        // wrong one, and there would be nothing for the redactor to find either.
        for (missing in listOf(null, "")) {
            val url = workbenchUrl(13337, "/projects", missing)
            assertFalse(url.contains("tkn="), "an absent token produced a token parameter: $url")
            assertTrue(url.endsWith("/?folder=/projects"), url)
        }
    }
}

/**
 * That the only place the navigation URL reaches the log is a redacted one.
 *
 * [WorkbenchUrlTest] pins that the redactor understands the URL; this pins that
 * it is used, and that there is only one URL for it to be used on. The defect it
 * replaces was not a leak but the shape of one: the method built two strings, the
 * URL it loaded and a token-free twin it logged, and what kept the token out of
 * logcat was a person keeping them apart. Collapsing them is what a merge does,
 * and it is also the deliberate edit of anyone who wants the real navigation URL
 * in the log.
 *
 * Source reading, and the weaker layer for the usual reason: the statement is
 * inside an Activity method, and a plain JVM test can build no Activity.
 */
class NavigationTokenLoggingTest {

    private val mainActivity = File("src/main/kotlin/com/vscodroid/MainActivity.kt")

    private fun codeLines(): List<IndexedValue<String>> =
        mainActivity.readLines().withIndex().filterNot { (_, line) ->
            val t = line.trimStart()
            t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
        }

    @Test
    fun `no log statement prints the navigation URL unredacted`() {
        check(mainActivity.isFile) {
            "MainActivity.kt not found at ${mainActivity.absolutePath} — this test " +
                "would otherwise pass by looking at nothing"
        }
        val lines = codeLines()

        val offenders = lines
            .filter { (_, l) -> l.contains("Logger.") && Regex("""\$\{?url""").containsMatchIn(l) }
            .filterNot { (_, l) -> l.contains("redactToken") }
            .map { (i, l) -> "MainActivity.kt:${i + 1}: ${l.trim()}" }

        assertEquals(
            emptyList<String>(), offenders,
            "the URL the WebView loads carries the connection token, and Logger.i is " +
                "not gated on a debuggable build. Print it through redactToken().",
        )

        // Control. Without this, deleting the log statement altogether — or
        // renaming the local — satisfies the assertion above by looking at
        // nothing, which is the shape of the defect being guarded.
        assertTrue(
            lines.any { (_, l) -> l.contains("redactToken(url)") },
            "nothing redacts the navigation URL; either the log statement went or it " +
                "stopped going through the redactor",
        )
    }

    @Test
    fun `the workbench URL is assembled in exactly one place`() {
        // The affordance, not the symptom. Two expressions for the same URL is
        // what made the redaction a matter of discipline; one cannot drift from
        // itself.
        val builders = codeLines()
            .filter { (_, l) -> l.contains("http://127.0.0.1:\$port") }
            .map { (i, l) -> "MainActivity.kt:${i + 1}: ${l.trim()}" }

        assertEquals(
            1, builders.size,
            "the navigation URL must be built once, so that the string logged and the " +
                "string loaded cannot differ. Found:\n" + builders.joinToString("\n"),
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
 * That a cancelled scope is not mistaken for a folder that failed.
 *
 * [RestoreWatcherTest] pins which folder a failure leaves watched; this pins what
 * counts as a failure in the first place. The sync runs in `lifecycleScope`, so
 * destroying the Activity cancels it — a scheduled dark-mode switch, a font-size
 * or language change, or "Don't keep activities" while the user is in another
 * app, none of which the manifest's `configChanges` absorbs. Kotlin's
 * `CancellationException` is a plain `Exception`, so the catch-all sees it and
 * every statement in that handler runs.
 *
 * `restoreWatcherAfterFailure` is the one that matters: it restarts a FileObserver
 * and the `saf-writeback` thread on the `SafStorageManager` this Activity owns,
 * *after* `onDestroy` has stopped it. Only that instance can stop them again and
 * nothing holds it any more, so they run until the process does. When the user
 * next opens that folder two engines watch one mirror, and the orphaned one reads
 * the `.partial` renames of the fresh sync as edits and pushes them back onto the
 * user's own documents.
 *
 * Source reading, and the weaker layer: the handler is inside an Activity method
 * with a `lifecycleScope`, and a plain JVM test can build neither.
 */
class SyncCancellationTest {

    private val mainActivity = File("src/main/kotlin/com/vscodroid/MainActivity.kt")

    private fun codeLines(): List<String> =
        mainActivity.readLines().filterNot {
            val t = it.trimStart()
            t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
        }

    @Test
    fun `the sync handler answers cancellation before it answers failure`() {
        check(mainActivity.isFile) {
            "MainActivity.kt not found at ${mainActivity.absolutePath} — this test " +
                "would otherwise pass by looking at nothing"
        }
        val lines = codeLines()

        val sync = lines.indexOfFirst { it.contains("safManager.syncToLocal(") }
        check(sync >= 0) { "the SAF sync is gone; this test is measuring the wrong method" }

        val after = lines.drop(sync)
        val cancelled = after.indexOfFirst { it.contains("catch (e: CancellationException)") }
        val failed = after.indexOfFirst { it.contains("catch (e: Exception)") }

        assertTrue(failed >= 0, "the catch-all is gone; this test is measuring nothing")
        assertTrue(
            cancelled >= 0,
            "a cancelled lifecycleScope reaches `catch (e: Exception)` and is handled as " +
                "a failed folder, which restarts a file watcher on a destroyed Activity's " +
                "engine that nothing can stop again",
        )
        assertTrue(
            cancelled < failed,
            "the cancellation handler must come first, or the catch-all takes it",
        )
        assertTrue(
            after.drop(cancelled).take(6).any { it.contains("throw e") },
            "the cancellation must be rethrown; swallowing it lets the rest of the " +
                "coroutine run on an Activity that is gone",
        )
    }

    @Test
    fun `the failure handler still restores the watcher it is responsible for`() {
        // Control. Deleting `restoreWatcherAfterFailure` outright would satisfy
        // the test above by removing the subject: a real failed switch away from
        // a healthy folder must still put that folder's write-back back.
        val lines = codeLines()
        val restores = lines.count { it.contains("restoreWatcherAfterFailure(previouslyWatched") }

        assertEquals(
            2, restores,
            "expected both failure handlers -- permission denied and everything else -- " +
                "to decide what stays watched; found $restores",
        )
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
