package com.vscodroid.webview

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The two suffix tests that decide a proxied asset's cache header and its
 * fallback MIME type, asked the way `proxyToLocalhost` asks them.
 *
 * That arm is handed the rewritten URL, not a path, and every such URL has ended
 * in `tkn=<hex>` since the editor server began requiring a connection token
 * (`rewriteCdnUrl` closes with `withToken`). A plain `endsWith(".js")` over that
 * string is false for every asset there is, so both fallbacks had quietly stopped
 * being answerable: no caller changed and no branch was deleted, the question
 * simply became one the string could not answer.
 *
 * Nothing user-visible rested on it, and that is stated rather than glossed: the
 * `/{quality}-{commit}/static/...` route this arm proxies answers with a
 * `Cache-Control` and a `Content-Type` of its own, so both fallbacks are expected
 * to stay quiet. What is worth holding is that they CAN fire, because the day the
 * server stops sending one of those headers is not the day anyone will think to
 * re-read a suffix test.
 *
 * NEGATIVE CONTROL: drop `.substringBefore('?')` from
 * `VSCodroidWebViewClient.assetPathOf` and every case naming a token below goes
 * red, while the bare-path case stays green.
 */
class ProxiedAssetSuffixTest {

    private val token = "0123456789abcdef0123456789abcdef"
    private val staticRoot = "http://127.0.0.1:41293/stable-cd4ee3b1/static"

    @Test
    fun `a proxied script is still a static asset with the token appended`() {
        assertTrue(
            VSCodroidWebViewClient.isStaticAsset("$staticRoot/out/vs/workbench/workbench.js?tkn=$token"),
            "the workbench's own scripts are versioned by commit hash and are the whole " +
                "reason the immutable cache header exists",
        )
        assertTrue(
            VSCodroidWebViewClient.isStaticAsset("$staticRoot/out/vs/workbench/workbench.css?tkn=$token"),
        )
    }

    @Test
    fun `a proxied script keeps its MIME type with the token appended`() {
        assertEquals(
            "application/javascript",
            VSCodroidWebViewClient.guessMimeType("$staticRoot/out/vs/workbench/workbench.js?tkn=$token"),
            "handed to the WebView as application/octet-stream, a script does not run",
        )
    }

    @Test
    fun `dropping the query does not make everything an asset`() {
        // The other half. Without it, an assetPathOf that answered with a
        // constant `.js` would satisfy both cases above.
        assertFalse(
            VSCodroidWebViewClient.isStaticAsset("$staticRoot/out/vs/code/browser/index.html?tkn=$token"),
            "a document is not versioned by commit hash and must not be cached forever",
        )
        assertEquals(
            "text/html",
            VSCodroidWebViewClient.guessMimeType("$staticRoot/out/vs/code/browser/index.html?tkn=$token"),
        )
    }

    @Test
    fun `a bare path answers exactly as it did before`() {
        // The second caller, `interceptResourceRequest`, passes `uri.path`, which
        // has no query on it. Stripping one must leave that answer alone, or a
        // fix aimed at the proxy would change what every extension webview
        // resource is served as. That arm asks for the MIME type only: it stopped
        // asking whether a file is a static asset when it stopped declaring one
        // immutable, since the paths it resolves are the user's own and carry no
        // version. The suffix answer is pinned here all the same, because the
        // question is the same one either caller asks.
        assertTrue(VSCodroidWebViewClient.isStaticAsset("/data/user/0/extensions/md/media/m.css"))
        assertFalse(VSCodroidWebViewClient.isStaticAsset("/data/user/0/projects/app/notes.md"))
        assertEquals(
            "image/svg+xml",
            VSCodroidWebViewClient.guessMimeType("/data/user/0/extensions/md/media/icon.svg"),
        )
        assertEquals(
            "application/octet-stream",
            VSCodroidWebViewClient.guessMimeType("/data/user/0/projects/app/notes.md"),
        )
    }
}
