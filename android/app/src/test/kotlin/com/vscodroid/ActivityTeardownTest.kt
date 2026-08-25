package com.vscodroid

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * What the editor has to let go of, and what it must not make the user wait for.
 *
 * Two questions that share one reading of the same file. Everything below is in
 * `MainActivity`, which no plain JVM test can build, so these read the source
 * for the reason `ServerReadinessCallSiteTest` gives: the regression is a call
 * being in the wrong place rather than a value the code computes.
 */
class ActivityTeardownTest {

    private val file = File("src/main/kotlin/com/vscodroid/MainActivity.kt")

    private val source by lazy {
        check(file.isFile) {
            "MainActivity.kt not found at ${file.absolutePath}; this test would " +
                "otherwise pass by looking at nothing"
        }
        file.readText()
    }

    /** The body of a declaration, by brace matching from it. */
    private fun body(declaration: String): String {
        val start = source.indexOf(declaration)
        assertTrue(start >= 0) {
            "`$declaration` is gone from MainActivity.kt, so this test is measuring " +
                "nothing. If it moved or was renamed, point this at the new site rather " +
                "than deleting it."
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
        throw AssertionError("Could not find the end of `$declaration` in MainActivity.kt")
    }

    /** Comments dropped: every rule here is discussed in prose beside the line it governs. */
    private fun code(text: String): List<String> =
        text.lines().filterNot {
            val t = it.trimStart()
            t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
        }

    @Test
    fun `a download in flight is dropped when the activity goes`() {
        // The picker creates the file when the user confirms the name, so by the
        // time the activity is destroyed there is already a document in their
        // folder wearing the name of the one they wanted. The page that owed its
        // bytes dies with the WebView, so nothing else will ever end that
        // download: the stream is dropped unclosed and the document stays, part
        // written and indistinguishable from a finished save until it is opened.
        // A font-size, display-size or locale change is enough to get here; none
        // of the three is in this activity's configChanges.
        val destroy = code(body("override fun onDestroy()"))
        val dropped = destroy.indexOfFirst { it.contains("downloads.onPageGone()") }
        val destroyed = destroy.indexOfFirst { it.contains("webView?.destroy()") }

        assertTrue(dropped >= 0) {
            "onDestroy no longer tells the download coordinator the page is going, so a " +
                "download in flight leaves an empty file behind in the user's folder"
        }
        assertTrue(destroyed >= 0) { "the WebView teardown is gone; this test is measuring nothing" }
        assertTrue(dropped < destroyed) {
            "the coordinator has to be told before the page it is reading from is destroyed"
        }
    }

    @Test
    fun `teardown survives an activity that finished before it set itself up`() {
        // handOffToSetup returns out of onCreate ahead of every field, and
        // Android delivers onDestroy to an activity that finished during
        // onCreate, so this method runs over a `lateinit` that was never
        // assigned. Reading one throws, and the throw would land on the path
        // whose whole purpose is to recover from a launch that skipped setup.
        //
        // The shape is what is checkable here: no plain JVM test can drive an
        // Activity lifecycle. It cannot tell this guard from one that reads the
        // wrong field.
        val destroy = code(body("override fun onDestroy()"))

        assertTrue(destroy.any { it.contains("::safManager.isInitialized") }) {
            "onDestroy reaches for the storage manager without asking whether this " +
                "activity ever built one. An editor handed back to the splash screen " +
                "finishes inside onCreate and reaches here with the field unset."
        }
    }

    @Test
    fun `the extra key row lets go of a WebView that has been destroyed`() {
        // The only thing that rebuilds the injector is initBridge, which
        // recreateWebView reaches only through loadVSCode, and only when a port
        // is already bound. A renderer killed during a cold start leaves the port
        // at zero, so the row went on holding a KeyInjector wrapping a destroyed
        // WebView for the rest of the session.
        val recreate = code(body("private fun recreateWebView()"))
        val cleared = recreate.indexOfFirst { it.contains("extraKeyRow?.keyInjector = null") }
        val destroyed = recreate.indexOfFirst { it.contains("wv.destroy()") }

        assertTrue(cleared >= 0) {
            "recreateWebView no longer drops the key row's injector, so it keeps a " +
                "destroyed WebView alive past its own lifetime"
        }
        assertTrue(destroyed >= 0) { "the WebView teardown is gone; this test is measuring nothing" }
        assertTrue(cleared < destroyed) {
            "the injector has to be dropped before the view it wraps is destroyed"
        }
    }

    @Test
    fun `no device folder watcher is started or stopped on the main thread`() {
        // Both halves are expensive and neither looks it. SafSyncEngine.stopWatching
        // joins the write-back worker for up to two seconds and the interrupt
        // cannot shorten it, because the copy it is inside streams through a
        // ContentResolver output stream, which is not interruptible.
        // startWatching walks the whole mirror and issues one inotify
        // registration per directory, up to the engine's cap of 2048. Every call
        // site is a coroutine on Dispatchers.Main or a lifecycle callback, and
        // the withContext around the sync alone made the ones beside it read as
        // confined.
        val lines = code(source)
        val calls = lines.withIndex().filter { (_, line) ->
            line.contains(".startFileWatcher(") || line.contains(".stopFileWatcher(") ||
                line.contains(".shutdownFileWatcher(")
        }

        assertEquals(
            4, calls.size,
            "expected the two in openSafFolder, the one in restoreWatcherAfterFailure " +
                "and the one in onDestroy. If a call site was added or removed, update " +
                "this count and check the new one is off the main thread. Found: " +
                calls.map { it.value.trim() },
        )

        // The hop is looked for on the call's own line and the five above it,
        // which is what covers both shapes in use: `withContext(...) { call }` on
        // one line, and a `thread(...)` block whose body is a few lines long. It
        // cannot tell a hop that encloses the call from one that merely precedes
        // it, and a call moved out of a nearby block would still read as hopped.
        val onMainThread = calls.filter { (index, _) ->
            lines.subList(maxOf(0, index - 5), index + 1).none {
                it.contains("withContext(Dispatchers.IO)") || it.contains("thread(name =")
            }
        }

        assertEquals(
            emptyList<String>(), onMainThread.map { it.value.trim() },
            "a device folder watcher is started or stopped on the thread that draws the " +
                "screen. The stop blocks for up to two seconds behind a write-back drain " +
                "the interrupt cannot cut short, with the sync dialog frozen; the start " +
                "walks the mirror and registers up to 2048 kernel watches.",
        )
    }

    @Test
    fun `the teardown ends the device folder watcher for good rather than until the next start`() {
        // The three calls in openSafFolder and restoreWatcherAfterFailure are
        // serialised against each other by `deviceFolderOpens`; the one here is
        // outside that mutex on purpose, on a detached thread, so it can run in
        // the middle of a start that is already inside the engine on
        // Dispatchers.IO. That start is not cancellable from here and finishes
        // afterwards, leaving observers and the `saf-writeback` thread on an
        // engine the replacement Activity cannot reach: it builds its own
        // manager. Only the shutdown refuses that start; the ordinary stop is
        // simply overtaken by it.
        val destroy = code(body("override fun onDestroy()"))

        assertTrue(destroy.any { it.contains(".shutdownFileWatcher()") }) {
            "onDestroy no longer shuts the device folder watcher down. A plain stop is " +
                "overtaken by a start already running on Dispatchers.IO, which then " +
                "leaves a watcher and a write-back thread nothing can ever stop again."
        }

        // The folder switch must keep the restartable stop: the next folder's
        // watcher starts on this same engine, and a shutdown there would leave the
        // user editing a folder nothing writes back.
        val switching = code(body("private fun openSafFolder(uri: Uri, navigate: Boolean)")) +
            code(body("private suspend fun restoreWatcherAfterFailure("))

        assertEquals(
            emptyList<String>(),
            switching.filter { it.contains(".shutdownFileWatcher(") }.map { it.trim() },
            "a folder switch shut the engine down instead of stopping it, so the folder " +
                "opened next is never watched and its saves never reach the device",
        )
    }
}
