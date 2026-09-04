package com.vscodroid.webview

import android.net.Uri
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Which localhost requests are the page asking for a translated interface
 * bundle, and which are the editor server's own traffic.
 *
 * `shouldInterceptRequest` asks [VSCodroidWebViewClient.nlsBundleRequested] about
 * every request to our own origin before it does anything else with it, and every
 * asset, route and WebSocket upgrade the workbench makes goes there. So the two
 * directions cost different things: a request this fails to recognise leaves the
 * interface in English silently, and a request it recognises wrongly takes a
 * route away from the server that answers it and hands the page a 404 in its
 * place.
 *
 * The shape is `/_nls/<commit>/<version>/<locale>/nls.messages.js`, built by
 * `out/server-main.js` from the `nlsCoreBaseUrl` that `assets/server.js` sets.
 * Only the ends are read: the two middle segments are the build's own commit and
 * version, which this app has no reason to check because they come from the
 * `product.json` it just served the page from. `DisplayLanguageTest` holds the
 * first segment against the address the server advertises.
 *
 * `Uri` is a mock with its segments supplied directly, which is how the rest of
 * this package tests code taking one (`CdnUrlInjectionTest`, `ExternalUrlHandoffTest`):
 * `Uri.parse` under the unit-test `android.jar` is a stub that throws, and the
 * splitting is Android's, not ours. What is under test is the decision made on
 * the segments, so the segments are the input.
 */
class WebViewClientNlsPathTest {

    private fun requested(vararg segments: String): String? {
        val uri = mockk<Uri>()
        every { uri.pathSegments } returns segments.toList()
        return VSCodroidWebViewClient.nlsBundleRequested(uri)
    }

    @Test
    fun `a bundle request answers with the locale in it`() {
        assertEquals(
            "ja",
            requested("_nls", "cd4ee3b1", "1.133.0", "ja", "nls.messages.js"),
            "the URL the page is given by server-main.js is not recognised as one, so " +
                "nothing serves it and every non-English device gets an English interface",
        )
        assertEquals(
            "zh-hans",
            requested("_nls", "cd4ee3b1", "1.133.0", "zh-hans", "nls.messages.js"),
            "a locale with a hyphen in it is still one segment",
        )
    }

    @Test
    fun `a path too short to hold a locale is not a bundle request`() {
        // Nothing builds this, which is the point: the locale is read by counting
        // back from the end, so a path with no room for it must be refused rather
        // than have the commit or the prefix read as a language name.
        assertNull(requested("_nls", "nls.messages.js"))
    }

    @Test
    fun `another route that ends the same way is left alone`() {
        // The editor server serves its own English bundle from a static path
        // ending in the same file name. Answering that from the APK would replace
        // the fallback the page has already loaded, in a build where the two are
        // not the same array.
        assertNull(requested("stable-cd4ee3b1", "static", "out", "nls.messages.js"))
    }

    @Test
    fun `the prefix alone is not enough`() {
        // The other end of the same test. A path under the prefix that asks for
        // anything else is the server's business: taking it here answers a
        // request for something the APK does not contain with a 404 that nothing
        // retries.
        assertNull(requested("_nls", "cd4ee3b1", "1.133.0", "ja", "workbench.js"))
    }
}
