package com.vscodroid.webview

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * That a file served from the filesystem is not declared cacheable forever.
 *
 * The arm that serves one, `interceptResourceRequest`, resolves it against roots
 * that include the projects tree, the SAF mirrors and whatever folder the user
 * has open. Those paths carry no version: they are the user's own files at fixed
 * absolute paths, and the user rewrites them in place from the terminal or with
 * `git pull`. `public, max-age=31536000, immutable` forbids revalidation, so an
 * edited image went on rendering from the old bytes in the markdown preview for
 * the life of the WebView renderer, with nothing in the app able to force a
 * refetch: that extension emits query-less resource URLs, unlike `media-preview`,
 * which appends a `version=` of its own. Reopening the preview, reloading and
 * switching folders all leave it stale.
 *
 * The commit hash that made the header safe belongs to the OTHER arm's URL,
 * `/{quality}-{commit}/static/...`, where it stays: `proxyToLocalhost` is the
 * positive control below, which is what makes a search finding nothing here a
 * measurement rather than a broken search.
 *
 * Read from the source, for the reason `SafFolderLogCallSiteTest` gives: there is
 * no seam. `WebResourceResponse` cannot be constructed under the stub
 * `android.jar` and its constructor is mocked in every test that reaches this
 * code, so the header map handed to it can be observed nowhere. Comments are
 * stripped first, because the rule is explained at length beside the very line it
 * governs and a search over the raw text would be satisfied by the explanation.
 *
 * NEGATIVE CONTROL: put `if (isStaticAsset(path)) put("Cache-Control",
 * CACHE_IMMUTABLE)` back into the response `interceptResourceRequest` builds and
 * `a served file is not declared immutable` goes red, while the proxy control
 * stays green.
 */
class WorkspaceAssetCachingTest {

    private val source =
        File("src/main/kotlin/com/vscodroid/webview/VSCodroidWebViewClient.kt").readText()

    /** The body of a `private fun name(` declaration, to its closing brace. */
    private fun body(name: String): String {
        val start = source.indexOf("private fun $name(")
        assertTrue(start >= 0) {
            "$name is gone from VSCodroidWebViewClient.kt, so this test is measuring " +
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
        throw AssertionError("Could not find the end of $name in VSCodroidWebViewClient.kt")
    }

    /**
     * Comments removed, so prose about the rule cannot satisfy a search for the
     * rule. Both forms, because both disable code, and a block counts as a comment
     * only where it opens a line.
     */
    private fun withoutComments(text: String): String {
        var inBlock = false
        return text.lines().joinToString("\n") { raw ->
            var line = raw
            if (inBlock) {
                val close = line.indexOf("*/")
                if (close < 0) return@joinToString ""
                inBlock = false
                line = line.substring(close + 2)
            }
            while (line.trimStart().startsWith("/*")) {
                val open = line.indexOf("/*")
                val close = line.indexOf("*/", open + 2)
                if (close < 0) {
                    inBlock = true
                    return@joinToString line.substring(0, open)
                }
                line = line.substring(0, open) + line.substring(close + 2)
            }
            val marker = line.indexOf("//")
            if (marker >= 0) line.substring(0, marker) else line
        }
    }

    private val served by lazy { withoutComments(body("interceptResourceRequest")) }
    private val proxied by lazy { withoutComments(body("proxyToLocalhost")) }

    @Test
    fun `the body being checked was actually found`() {
        assertTrue(served.contains("FileInputStream") && served.contains("resourceOutcome")) {
            "interceptResourceRequest no longer looks like the function this was written " +
                "against, so the search below is running over the wrong text and would " +
                "pass over anything. Point it at wherever a resource is served from the " +
                "filesystem now."
        }
    }

    /**
     * The positive control. The header is still set where a commit hash makes it
     * true, so a search that finds nothing in the arm above is a fact about that
     * arm and not about the search.
     */
    @Test
    fun `the proxied static route still caches`() {
        assertTrue(proxied.contains("CACHE_IMMUTABLE")) {
            "nothing sets the immutable cache header any more, so the case below cannot " +
                "tell a fix from a rename. If the constant moved, follow it."
        }
    }

    @Test
    fun `a served file is not declared immutable`() {
        val offenders = served.lines()
            .filter { it.contains("CACHE_IMMUTABLE") || it.contains("Cache-Control") }

        assertTrue(offenders.isEmpty()) {
            "a file resolved from the filesystem is served with a cache header, and the " +
                "roots it resolves against include the open workspace, the projects tree " +
                "and the SAF mirrors, which the user edits in place. Found:\n" +
                offenders.joinToString("\n") { "  ${it.trim()}" }
        }
    }
}
