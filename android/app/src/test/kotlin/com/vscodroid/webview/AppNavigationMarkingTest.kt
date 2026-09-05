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
 * Source-level, and derived rather than listed, because the list is what failed.
 * It named the navigations that existed when it was written and could not notice
 * one added later, which has now happened twice: `showErrorPage` was missing from
 * the first version, and `retryServerStart` replaced a live workbench unmarked
 * while this file was green. The scan below finds every page-replacing member of
 * the Activity instead. What is still out of reach is a member declared in a
 * shape the scan does not match, and the floor inside the case is what exposes a
 * scan that has stopped matching.
 */
class AppNavigationMarkingTest {

    private fun mainActivity(): String =
        SourceScan.withoutComments(SourceScan.read("src/main/kotlin/com/vscodroid/MainActivity.kt"))

    /**
     * Read out of each function's own body rather than off the whole file: a
     * count of `markAppNavigation()` across the file is satisfied by three calls
     * sitting together in one of them, which is the shape a careless merge
     * produces and the shape that leaves two navigations unmarked.
     *
     * The order is asserted as well as the presence, and it is not pedantry: the
     * veto is asked during the load, so a mark written afterwards changes nothing.
     *
     * `setupWebView` is skipped by name, and it is the only site with nothing to
     * veto: it loads the placeholder into a WebView holding no document at all,
     * either the one `onCreate` inflated or the replacement `recreateWebView`
     * built after destroying the crashed one.
     */
    @Test
    fun `every navigation this app starts marks itself first`() {
        val source = mainActivity()
        // The receiver dot is what separates a WebView load from this file's own
        // method of a similar name, so `.loadVSCode(` is not one of these.
        val navigation = Regex("""\.load(Url|Data)|(webView\?|wv)\.reload\(\)""")

        // Any run of modifiers, not a spelled list of them. Written as
        // `(?:private |internal |override )?` this missed `private suspend fun`,
        // which MainActivity already declares: a suspend function that navigates
        // would have been invisible to the one check written to find it.
        val sites = Regex("""\n    (?:\w+ )*fun \w+\(""")
            .findAll(source)
            .map { it.value.trim() }
            .distinct()
            .filterNot { it.endsWith("fun setupWebView(") }
            .map { it to SourceScan.body(source, it) }
            .filter { (_, body) -> navigation.containsMatchIn(body) }
            .toList()

        // The control for the case itself. A scan that matches nothing passes
        // every assertion below it, which is the one way a derived list is weaker
        // than a written one. Five is what MainActivity holds today; a change that
        // legitimately removes one updates this number and says which.
        assertTrue(sites.size >= 5) {
            "found ${sites.size} page-replacing functions in MainActivity, so this scan has " +
                "stopped matching them and would pass by looking at nothing"
        }

        sites.forEach { (declaration, body) ->
            val marked = body.indexOf("markAppNavigation()")
            val navigates = navigation.find(body)!!.range.first
            assertTrue(marked >= 0) {
                "`$declaration` navigates the WebView without calling markAppNavigation first, " +
                    "so onJsBeforeUnload treats a navigation this app decided on as one the " +
                    "page started and leaves the browser's leave-page modal over it"
            }
            assertTrue(marked < navigates) {
                "`$declaration` marks the navigation after starting it, which is too late: " +
                    "the veto is asked during the load"
            }
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
