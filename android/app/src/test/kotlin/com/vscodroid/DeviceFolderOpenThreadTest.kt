package com.vscodroid

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Opening a device folder must not ask a DocumentsProvider anything on the thread
 * that draws the screen.
 *
 * `persistPermission` is two cross-process round trips of its own (it resolves the
 * display name and reads every persisted grant to prune the recent list) and
 * `getDisplayName` is a third; `folderForOpenedPath` is a fourth. All four used to
 * run before the progress dialog existed, from the picker result, from the bridge
 * and from `onPageFinished`, which are all the main thread. Against a local
 * provider that is milliseconds; against one backed by a network share or a phone
 * on MTP it is an unresponsive editor and, past five seconds, an ANR.
 *
 * Read off the source, for the reason `SafFolderLogCallSiteTest` gives: there is
 * no seam. These functions show an `AlertDialog` and launch on `lifecycleScope`,
 * neither of which a plain JVM test can build. Comments are stripped first,
 * because the rule is discussed at length beside the lines it governs and a search
 * over the raw text would be satisfied by the explanation.
 *
 * NEGATIVE CONTROL, measured rather than assumed:
 *  - restoring the shape this replaced (both provider calls above `dialog.show()`,
 *    with no hop) reddens all three of `nothing is asked of the provider before the
 *    dialog is on screen`, `the provider round trips in openSafFolder are hopped
 *    off the main thread` and `the permission is taken even if the activity goes
 *    away`, and nothing else.
 *  - deleting the `withContext(Dispatchers.IO)` around `folderForOpenedPath` in
 *    `adoptWorkbenchFolder` reddens `the page-load lookup is hopped off the main
 *    thread`.
 *  - resolving the remembered folder inline in `loadVSCode` again, with no hop,
 *    reddens `the remembered workspace is resolved off the main thread` and, since
 *    it leaves no hop for that case to sit before, `a folder already known is
 *    navigated to without waiting for a hop`; nothing else.
 *  - returning `segment` instead of `tail` from [treeUriLabel] reddens `the
 *    placeholder name is the folder, not the whole tree path`.
 *  - deleting the `deviceFolderOpens.lock()` line reddens `device folder opens
 *    run one at a time`; moving `dialog.show()` back above it reddens that case
 *    and `a stale adoption is decided under the lock and before the watcher is
 *    touched`, and moving the `adoptionIsStale` decision above it reddens the
 *    second alone.
 *
 * The opens are serialised as well as hopped, and the cases at the end read that
 * off the same body. Two syncs running through the one engine at once ended with
 * its single watcher on whichever finished last, and for an adoption, which never
 * navigates, that could be a folder the page had already left.
 */
class DeviceFolderOpenThreadTest {

    private val source = File("src/main/kotlin/com/vscodroid/MainActivity.kt").readText()

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
     * Lines with comments removed, so prose about a hop cannot stand in for one.
     * Both forms, because both disable code, and a block counts as a comment only
     * where it opens a line.
     */
    private fun code(text: String): List<String> {
        var inBlock = false
        return text.lines().map { raw ->
            var line = raw
            if (inBlock) {
                val close = line.indexOf("*/")
                if (close < 0) return@map ""
                inBlock = false
                line = line.substring(close + 2)
            }
            while (line.trimStart().startsWith("/*")) {
                val open = line.indexOf("/*")
                val close = line.indexOf("*/", open + 2)
                if (close < 0) {
                    inBlock = true
                    return@map line.substring(0, open)
                }
                line = line.substring(0, open) + line.substring(close + 2)
            }
            val marker = line.indexOf("//")
            if (marker >= 0) line.substring(0, marker) else line
        }
    }

    private val opened by lazy { code(body("openSafFolder")) }
    private val adopted by lazy { code(body("adoptWorkbenchFolder")) }

    @Test
    fun `the bodies being checked were actually found`() {
        assertTrue(opened.any { it.contains("persistPermission") }) {
            "openSafFolder no longer looks like the function these cases were written " +
                "against, so they are searching the wrong text and would pass over " +
                "anything. Point them at wherever a device folder is opened now."
        }
        assertTrue(adopted.any { it.contains("folderForOpenedPath") }) {
            "adoptWorkbenchFolder no longer looks up the folder the workbench opened, " +
                "so this case is measuring nothing"
        }
    }

    @Test
    fun `nothing is asked of the provider before the dialog is on screen`() {
        val shown = opened.indexOfFirst { it.contains("dialog.show()") }
        val asked = opened.withIndex().filter { (_, line) ->
            line.contains("persistPermission") || line.contains("getDisplayName")
        }

        assertTrue(shown >= 0) { "the sync dialog is no longer shown; this case is measuring nothing" }
        assertTrue(asked.isNotEmpty()) { "the provider calls are gone; this case is measuring nothing" }
        assertEquals(
            emptyList<String>(), asked.filter { it.index < shown }.map { it.value.trim() },
            "a DocumentsProvider is queried before the progress dialog exists, so the " +
                "editor freezes with nothing on screen saying why",
        )
    }

    @Test
    fun `the provider round trips in openSafFolder are hopped off the main thread`() {
        // The hop is looked for on the call's own line and the three above it,
        // which covers both shapes in use here: a one-line withContext, and a
        // block whose calls sit under its opening line. It cannot tell a hop that
        // encloses a call from one that merely precedes it, which is the same
        // ceiling ActivityTeardownTest states for the watcher calls.
        val calls = opened.withIndex().filter { (_, line) ->
            line.contains("safManager.persistPermission(") || line.contains("safManager.getDisplayName(")
        }

        assertEquals(
            2, calls.size,
            "expected persistPermission and getDisplayName. If a call site was added " +
                "or removed, update this count and check the new one is off the main " +
                "thread. Found: " + calls.map { it.value.trim() },
        )

        val onMainThread = calls.filter { (index, _) ->
            opened.subList(maxOf(0, index - 3), index + 1).none {
                it.contains("withContext(") && it.contains("Dispatchers.IO")
            }
        }

        assertEquals(
            emptyList<String>(), onMainThread.map { it.value.trim() },
            "a DocumentsProvider query runs on the thread that draws the screen. A " +
                "network- or MTP-backed provider answers in seconds, and this path is " +
                "reached from a page load as well as from the picker.",
        )
    }

    @Test
    fun `the permission is taken even if the activity goes away`() {
        // The grant used to be taken synchronously on the way in, so it was taken
        // whatever happened next. Moving it into the coroutine without this makes
        // it best-effort: a scope cancelled in the moment after the picker
        // returned would leave the user's folder missing from the recent list with
        // nothing to explain it.
        val hop = opened.indexOfFirst { it.contains("safManager.persistPermission(") }
        assertTrue(hop >= 0) { "persistPermission is gone; this case is measuring nothing" }
        assertTrue(
            opened.subList(maxOf(0, hop - 3), hop + 1).any { it.contains("NonCancellable") },
            "the persisted grant is inside a cancellable hop, so destroying the " +
                "activity in the moment after the picker returns silently drops it",
        )
    }

    @Test
    fun `the page-load lookup is hopped off the main thread`() {
        val lookup = adopted.indexOfFirst { it.contains("safManager.folderForOpenedPath(") }
        assertTrue(lookup >= 0) { "the lookup is gone; this case is measuring nothing" }
        assertTrue(
            adopted.subList(maxOf(0, lookup - 3), lookup + 1).any {
                it.contains("withContext(") && it.contains("Dispatchers.IO")
            },
            "onPageFinished reads every persisted grant and prunes the recent list on " +
                "the main thread, on every cold start whose remembered workspace is a " +
                "device folder",
        )
    }

    @Test
    fun `the remembered workspace is resolved off the main thread`() {
        // The fourth round trip, and the sibling the hop above skipped.
        // `rememberedWorkspaceFolder` asks `folderForOpenedPath` whether a
        // remembered mirror is still granted, which is the same read of every
        // persisted grant, and `loadVSCode` reaches it from `onServerReady` and
        // from the already-serving branch of the service binding, both of which
        // are the main thread, in the moment before the workbench is navigated to.
        val load = code(body("loadVSCode"))
        val remembered = load.indexOfFirst { it.contains("rememberedWorkspaceFolder()") }

        assertTrue(remembered >= 0) {
            "loadVSCode no longer falls back to the remembered workspace, so this case " +
                "is measuring nothing"
        }
        assertTrue(
            load.subList(maxOf(0, remembered - 3), remembered + 1).any {
                it.contains("withContext(") && it.contains("Dispatchers.IO")
            },
            "the remembered workspace is resolved on the thread that draws the screen: " +
                "one read of every persisted grant, a prune of the recent list and, on " +
                "the fallback, a directory that may have to be created, all on the cold " +
                "start path immediately before the navigation",
        )
    }

    /**
     * The fifth read, and the one that fired on every launch rather than only on
     * a launch that reopens a device folder.
     *
     * `ProcessManager.connectionToken` is `cachedToken ?: readTokenFile()`, and
     * `readTokenFile` stats and reads a file. Nothing touches it before the page
     * exists: the two other readers are suppliers `initBridge` hands to the
     * resource interceptor and to the service worker, and neither is called until
     * the workbench starts fetching. So the first read of every run was the one
     * that built the navigation URL, on the main thread, at the moment the
     * workbench URL is assembled. `MainThreadWatch`'s inventory names
     * `ProcessManager.readTokenFile` among the sites it did NOT see on a measured
     * launch, and explains their absence as needing "an interaction a cold launch
     * does not perform"; every successful launch performs this one.
     *
     * The cold start is the branch that hops, because `onServerReady` calls
     * `loadVSCode` with no folder and the WebView is still holding the `data:`
     * placeholder, so reading the token there costs nothing that was not already
     * being paid. Every other caller keeps the parameter's default, which by then
     * reads the cache rather than the disk.
     */
    @Test
    fun `the connection token is read off the main thread on the cold start path`() {
        val load = code(body("loadVSCode"))
        val hop = load.indexOfFirst { it.contains("withContext(") && it.contains("Dispatchers.IO") }
        val read = load.indexOfFirst { it.contains("getConnectionToken()") }

        assertTrue(hop >= 0) { "loadVSCode no longer hops; this case is measuring nothing" }
        assertTrue(read >= 0) {
            "loadVSCode no longer resolves the connection token, so the navigation is " +
                "back to reading it on the main thread wherever it does resolve it"
        }
        assertTrue(read > hop) {
            "the connection token is read before the hop, which is the main thread: a " +
                "stat and a read of the token file on every cold launch, in the moment " +
                "the workbench URL is built"
        }

        // And the site it came from does not read it again. A parameter with a
        // default that reads the token is what keeps every other caller working,
        // and it is also how the main-thread read comes back: leaving a read in
        // the body means the value passed in is computed and then ignored.
        val navigate = code(body("navigateToFolder"))
        assertEquals(
            emptyList<String>(),
            navigate.filter { it.contains("getConnectionToken()") }.map { it.trim() },
            "navigateToFolder resolves the token itself again, so the caller that " +
                "resolved it off the main thread bought nothing",
        )
    }

    @Test
    fun `a folder already known is navigated to without waiting for a hop`() {
        // Control for the case above, which deleting the fast path would also
        // satisfy. A folder handed in by the caller, or read off the URL the
        // WebView already holds, costs nothing to work out, and every ordinary
        // folder switch is one of those: making them wait for a thread hop would
        // buy nothing and delay the navigation the user asked for.
        val load = code(body("loadVSCode"))
        val known = load.indexOfFirst { it.contains("folderFromUrl(") }
        val hop = load.indexOfFirst { it.contains("withContext(") && it.contains("Dispatchers.IO") }

        assertTrue(known >= 0 && hop >= 0) {
            "loadVSCode no longer both reads the URL and hops; this case is measuring nothing"
        }
        assertTrue(known < hop) {
            "the URL is read after the hop rather than before it, so the navigation that " +
                "already knows its folder is queued behind a lookup it never needed"
        }
    }

    @Test
    fun `the placeholder name is the folder, not the whole tree path`() {
        // What a tree URI's last path segment actually looks like once decoded.
        assertEquals("ClientProject", treeUriLabel("primary:Documents/ClientProject"))
        assertEquals("Documents", treeUriLabel("primary:Documents"))
    }

    @Test
    fun `a segment with no folder in it is shown as it stands`() {
        // The storage root, and the empty and absent cases. Quoting an empty name
        // in the dialog says less than the segment does, and this runs for at most
        // the one provider query it stands in for.
        assertEquals("primary:", treeUriLabel("primary:"))
        assertEquals("", treeUriLabel(""))
        assertEquals("", treeUriLabel(null))
    }

    @Test
    fun `device folder opens run one at a time`() {
        val lock = opened.indexOfFirst { it.contains("deviceFolderOpens.lock()") }
        val unlock = opened.indexOfLast { it.contains("deviceFolderOpens.unlock()") }
        val shown = opened.indexOfFirst { it.contains("dialog.show()") }
        val marked = opened.indexOfFirst { it.contains("syncingFolder = uri") }
        val cleanup = opened.indexOfLast { it.contains("} finally {") }

        assertTrue(lock >= 0) {
            "openSafFolder no longer takes the open lock, so two syncs can run through " +
                "the one engine at once and its single watcher ends on whichever " +
                "finished last, which for an adoption can be a folder the page has left"
        }
        assertTrue(unlock > cleanup && cleanup > 0) {
            "the lock is not released in the finally, so a failed or cancelled open " +
                "holds it for the life of the activity and no folder can be opened again"
        }
        assertTrue(shown > lock) {
            "the progress dialog is shown before this open's turn, stacked over the one " +
                "still reporting the previous sync"
        }
        assertTrue(marked in 0 until lock) {
            "the syncing marker is set after the lock rather than before it. The two " +
                "page-finished callbacks a switch produces arrive before any turn could, " +
                "and the second has to find it set or it queues the same sync again"
        }
    }

    @Test
    fun `a stale adoption is decided under the lock and before the watcher is touched`() {
        val lock = opened.indexOfFirst { it.contains("deviceFolderOpens.lock()") }
        val decided = opened.indexOfFirst { it.contains("adoptionIsStale(") }
        val previous = opened.indexOfFirst { it.contains("previouslyWatched = watchedSafFolder") }
        val shown = opened.indexOfFirst { it.contains("dialog.show()") }
        val stopped = opened.indexOfFirst { it.contains("safManager.stopFileWatcher()") }

        assertTrue(decided > lock && lock >= 0) {
            "the adoption is judged before its turn, against a page that may still move " +
                "while it waits, or not judged at all"
        }
        assertTrue(decided < shown && decided < stopped) {
            "a stale adoption gets as far as showing its dialog or stopping the watcher " +
                "on the folder the page is actually on"
        }
        assertTrue(previous > lock) {
            "the previous watcher is read when the open is asked for rather than when " +
                "its turn comes, so a failure restores the folder that was watched " +
                "before the open ahead of it ran"
        }
    }

    /**
     * The decision itself, which is pure and so needs no reading of the source.
     *
     * Mirror names because that is what a JVM test can supply: `android.net.Uri`
     * can be neither built nor mocked here, which is the same reason
     * `shouldRestorePreviousWatcher` compares text.
     */
    @Test
    fun `an adoption whose folder is on screen and unwatched runs`() {
        assertFalse(adoptionIsStale(navigate = false, watchedMirror = null, openMirror = "a1b2c3d4e5f6", mirror = "a1b2c3d4e5f6"))
        assertFalse(adoptionIsStale(navigate = false, watchedMirror = "0f0f0f0f0f0f", openMirror = "a1b2c3d4e5f6", mirror = "a1b2c3d4e5f6"))
    }

    @Test
    fun `an adoption is skipped once the page has moved to another folder`() {
        // The open ahead navigated, or the workbench opened a third folder. Syncing
        // now would take the only watcher off the folder on screen.
        assertTrue(adoptionIsStale(navigate = false, watchedMirror = null, openMirror = "0f0f0f0f0f0f", mirror = "a1b2c3d4e5f6"))
        // A page on no mirror at all is not on this one either.
        assertTrue(adoptionIsStale(navigate = false, watchedMirror = null, openMirror = null, mirror = "a1b2c3d4e5f6"))
    }

    @Test
    fun `an adoption is skipped when the folder is already watched`() {
        // The open ahead was for the same folder: a second sync under a fresh stop
        // would only re-copy what is there.
        assertTrue(adoptionIsStale(navigate = false, watchedMirror = "a1b2c3d4e5f6", openMirror = "a1b2c3d4e5f6", mirror = "a1b2c3d4e5f6"))
    }

    @Test
    fun `a requested open is never stale`() {
        // It navigates to its folder when it finishes, so the page is on it by
        // construction, and reopening the folder already open is how a user pulls
        // fresh content down.
        assertFalse(adoptionIsStale(navigate = true, watchedMirror = "a1b2c3d4e5f6", openMirror = "0f0f0f0f0f0f", mirror = "a1b2c3d4e5f6"))
        assertFalse(adoptionIsStale(navigate = true, watchedMirror = null, openMirror = null, mirror = "a1b2c3d4e5f6"))
    }
}
