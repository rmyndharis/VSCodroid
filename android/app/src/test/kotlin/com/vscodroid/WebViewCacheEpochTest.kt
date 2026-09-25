package com.vscodroid

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The WebView's HTTP cache is dropped once per build, before the first page.
 *
 * The server sends its static files with a one-year `max-age` and no validator,
 * under a path that changes only with the VS Code commit, and an app update
 * re-extracts that tree in place. Measured on an API 33 emulator: after an update,
 * `workbench.css` came out of the cache while the server held the new file, so the
 * mobile CSS the server build appends to it never reached an install that had
 * run an earlier build.
 *
 * Two halves. [webViewCacheIsStale] can be driven here; where it is asked, and
 * what is done with the answer, can only be read off the source.
 *
 * NEGATIVE CONTROL: removing the `dropCacheLeftByEarlierBuild(` call from
 * `setupWebView` reddens `every WebView is set up behind the check`; changing
 * `clearCache(true)` to `clearCache(false)` reddens `the drop reaches the disk
 * cache and records the build`.
 */
class WebViewCacheEpochTest {

    private val source = SourceScan.withoutComments(
        SourceScan.read("src/main/kotlin/com/vscodroid/MainActivity.kt")
    )

    @Test
    fun `a build that has not dropped the cache yet drops it`() {
        assertTrue(webViewCacheIsStale(clearedFor = null, build = "1.4.0/15"), "first launch of an install")
        assertTrue(webViewCacheIsStale(clearedFor = "1.4.0/15", build = "1.5.0/16"), "an update")
        // Setup re-extracts on a versionCode change alone, so this has to follow it.
        assertTrue(webViewCacheIsStale(clearedFor = "1.4.0/15", build = "1.4.0/16"), "a re-release")
    }

    @Test
    fun `the same build does not drop it again`() {
        assertFalse(webViewCacheIsStale(clearedFor = "1.4.0/15", build = "1.4.0/15"))
    }

    @Test
    fun `every WebView is set up behind the check`() {
        val setup = SourceScan.body(source, "private fun setupWebView(")
        val drop = setup.indexOf("dropCacheLeftByEarlierBuild(")
        val firstLoad = setup.indexOf("loadData(")
        assertTrue(drop >= 0, "setupWebView must ask whether the cache predates this build")
        assertTrue(firstLoad > drop, "the drop has to come before the WebView loads anything")
    }

    @Test
    fun `the drop reaches the disk cache and records the build`() {
        val drop = SourceScan.body(source, "private fun dropCacheLeftByEarlierBuild(")
        assertTrue(drop.contains("webViewCacheIsStale("), "the drop must be gated, or every launch refetches")
        assertTrue(drop.contains("clearCache(true)"), "false clears only the in-memory cache")
        assertTrue(drop.contains("KEY_WEBVIEW_CACHE_BUILD"), "the build has to be recorded after the drop")
    }
}
