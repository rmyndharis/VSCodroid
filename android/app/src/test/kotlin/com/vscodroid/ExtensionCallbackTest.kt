package com.vscodroid

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

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
