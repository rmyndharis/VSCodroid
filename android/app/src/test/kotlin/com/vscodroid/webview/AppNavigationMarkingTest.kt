package com.vscodroid.webview

import com.vscodroid.SourceScan
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * That every navigation this app performs is marked as its own.
 *
 * `onJsBeforeUnload` answers the confirm only while [MainActivity.navigationIsOurs]
 * is true, and it is true only for a window after `markAppNavigation()`. The
 * behaviour of that split is covered by BeforeUnloadTest, which builds the client
 * directly; what nothing there can see is the wiring, because the mark lives in
 * the Activity and the reader lives in the chrome client.
 *
 * A dropped mark fails in the quiet direction, which is why it needs a test: the
 * navigation still happens, and the only symptom is a browser's "Changes you made
 * may not be saved" appearing over a folder the user just chose, on the fraction
 * of attempts where a backup is still pending. That is the noise this override was
 * written to remove, returning by the door nobody watches.
 *
 * Source-level, and the limit is worth stating: it holds the four navigations
 * that exist today, and it cannot notice a fifth being added unmarked. What
 * catches that is the review of a diff adding a `loadUrl`, plus the failure mode
 * being the conservative one.
 */
class AppNavigationMarkingTest {

    private fun mainActivity(): String =
        SourceScan.withoutComments(SourceScan.read("src/main/kotlin/com/vscodroid/MainActivity.kt"))

    /**
     * Read out of each function's own body rather than off the whole file: a
     * count of `markAppNavigation()` across the file is satisfied by three calls
     * sitting together in one of them, which is the shape a careless merge
     * produces and the shape that leaves two navigations unmarked.
     */
    @Test
    fun `every navigation this app starts marks itself first`() {
        val source = mainActivity()

        for (function in listOf(
            "private fun handleResumeFromBackground(",
            "private fun loadVSCode(",
            "private fun navigateToFolder(",
            // The fourth, and the one the first version of this list missed. It
            // replaces a live workbench with the page carrying the only control
            // that can restart a dead server, so an unload veto blocking it
            // leaves the app with no lever at all.
            "private fun showErrorPage(",
        )) {
            val body = SourceScan.body(source, function)
            assertTrue(
                body.contains("markAppNavigation()"),
                "`$function` navigates the WebView without calling markAppNavigation first, " +
                    "so onJsBeforeUnload treats a navigation this app decided on as one the " +
                    "page started and leaves the browser's leave-page modal over it",
            )
        }
    }

    /** The reader is wired to the client, or the mark above answers nobody. */
    @Test
    fun `the chrome client is given the reader`() {
        val source = mainActivity()

        assertTrue(
            SourceScan.body(source, "private fun initBridge(")
                .contains("navigationIsOurs = ::navigationIsOurs"),
            "initBridge no longer constructs the chrome client with navigationIsOurs, so nothing " +
                "can tell an app navigation from a page one and the split collapses to " +
                "whichever answer the constructor now defaults to",
        )
    }
}
