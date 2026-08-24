package com.vscodroid.webview

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * What a file served off the filesystem says about who may use it.
 *
 * The origin gate decides who may READ one, and `WebviewOriginTrustTest` pins
 * that. This pins the other half. A request that sends no `Origin` is served on
 * purpose, because `<img>`, `<link>` and `<script src>` never send one and every
 * extension webview is made of those, and the response to it used to carry no
 * policy at all. Opaque is not inert: a `.js` under the open workspace arrives
 * as `application/javascript`, so a remote page in the bundled Simple Browser
 * could name it in `<script src>` and run it in its own realm, and
 * `<img onerror>` against a guessed path told the same page which files exist.
 * `Cross-Origin-Resource-Policy` is the one check a no-cors response is put
 * through, so it is the only thing between such a page and those two.
 *
 * Read as a value, for the reason `ByteRangeTest` gives: a `WebResourceResponse`
 * cannot be constructed under the stub `android.jar`, so the header map is
 * observable nowhere else. The call site is read from the source for the same
 * reason, in the convention `WorkspaceAssetCachingTest` uses: a helper whose
 * answer the served arm no longer composes is a policy nothing sends.
 */
class ResourceHeadersTest {

    private val corp = "Cross-Origin-Resource-Policy"
    private val nosniff = "X-Content-Type-Options"
    private val allowOrigin = "Access-Control-Allow-Origin"
    private val webview = "https://0f7c2b1a-uuid.vscode-cdn.net"

    /**
     * `same-site`, and not `same-origin`, because a webview document at
     * `https://<uuid>.vscode-cdn.net` is another origin on the same site as the
     * resource authority, and its stylesheet and images are exactly the no-cors
     * loads this header is checked against. `same-origin` would leave every
     * extension webview unstyled; the remote page in the simple browser is on
     * another site either way.
     */
    @Test
    fun `a request with no origin is answered same-site`() {
        val headers = VSCodroidWebViewClient.resourceHeaders(null)

        assertEquals("same-site", headers[corp], "no-cors loads from a remote page are not refused")
    }

    /**
     * And not `nosniff`. The policy alone refuses the remote page; the header
     * would add nothing against it and would cost every file whose extension
     * the type table does not know, which goes out as `application/octet-stream`:
     * opaque-response blocking refuses a nosniff response of that type for an
     * `<img>` or `<audio>` it cannot sniff, where without the header it sniffs
     * and renders. The bundled media preview loads `.bmp`, `.avif` and `.oga`
     * exactly that way.
     */
    @Test
    fun `the served type stays advisory`() {
        assertFalse(
            VSCodroidWebViewClient.resourceHeaders(null).containsKey(nosniff),
            "nosniff refuses every image and audio file the type table does not name",
        )
        assertFalse(VSCodroidWebViewClient.resourceHeaders(webview).containsKey(nosniff))
    }

    /**
     * The control: a request that carries no `Origin` is still answered without
     * an echo, because there is nothing to echo and `*` was the hole the echo
     * replaced.
     */
    @Test
    fun `no asker, no echo`() {
        val headers = VSCodroidWebViewClient.resourceHeaders(null)

        assertFalse(headers.containsKey(allowOrigin), "an absent origin was answered with a header")
        assertFalse(headers.containsKey("Vary"), "Vary: Origin without an origin to vary on")
    }

    /**
     * The echo survives, and the policy rides with it. A webview's module
     * scripts and fonts are CORS-mode requests and pass or fail on this echo
     * alone, so a policy that replaced it would take the markdown preview's
     * math and icons with it.
     */
    @Test
    fun `an asker is echoed and still carries the policy`() {
        val headers = VSCodroidWebViewClient.resourceHeaders(webview)

        assertEquals(webview, headers[allowOrigin], "the asker is no longer echoed")
        assertEquals("Origin", headers["Vary"], "two askers would share one cached answer")
        assertEquals("same-site", headers[corp], "the policy is missing when an origin is present")
    }

    /**
     * The helper is what the served arm sends. A build that kept the helper and
     * put the old inline map back at the call site would pass every case above
     * over responses that carry no policy.
     */
    @Test
    fun `the served arm composes its headers from the helper`() {
        val source = File("src/main/kotlin/com/vscodroid/webview/VSCodroidWebViewClient.kt").readText()
        val start = source.indexOf("fun interceptResourceRequest(")
        assertTrue(start >= 0) { "interceptResourceRequest is gone; point this at the new site" }
        val open = source.indexOf('{', start)
        var depth = 0
        var end = -1
        for (i in open until source.length) {
            if (source[i] == '{') depth += 1
            if (source[i] == '}') {
                depth -= 1
                if (depth == 0) { end = i; break }
            }
        }
        assertTrue(end > open) { "could not find the end of interceptResourceRequest" }
        val body = source.substring(open, end)
            .lines()
            .filterNot { it.trimStart().startsWith("//") }
            .joinToString("\n")

        assertTrue(body.contains("resourceHeaders(")) {
            "interceptResourceRequest no longer builds its headers from resourceHeaders, so " +
                "the policy the cases above pin reaches no response"
        }
        assertFalse(body.contains("\"Access-Control-Allow-Origin\" to")) {
            "interceptResourceRequest composes an Access-Control-Allow-Origin header inline " +
                "beside the helper; there is one source of these headers"
        }
    }
}

/**
 * Who may read a file served off the filesystem when the request carries no
 * `Origin`, which is every `<img>`, `<link>` and classic `<script src>`.
 *
 * `Cross-Origin-Resource-Policy` was supposed to be the answer and is not:
 * measured on an API 37 emulator (Chrome 149 WebView), a page served from
 * `http://10.0.2.2:8877` and opened in the bundled Simple Browser loaded a
 * workspace `.js` and `.png` while the response carried `same-site`. The same
 * WebView refuses an ordinary network response carrying `same-origin`, so the
 * check simply is not applied to a response synthesised by
 * `shouldInterceptRequest`. The `Referer` is what discriminates, measured at
 * the gate itself: every legitimate no-cors load carried
 * `https://<uuid>.vscode-cdn.net/`, the attack carried `http://10.0.2.2:8877/`,
 * and with `<meta name="referrer" content="no-referrer">` the attack carried
 * none at all and still read the file.
 */
class RefererGateTest {

    private val port = 13337
    private val webview = "https://18m4k0prj7umm96am9tapr0qko2nieuq2bsup0c1g7s34jl8bqrh.vscode-cdn.net/"

    @Test
    fun `a webview document is ours`() {
        assertTrue(VSCodroidWebViewClient.isOurReferer(webview, port))
        assertTrue(
            VSCodroidWebViewClient.isOurReferer("$webview" + "some/path.html?q=1", port),
            "the path and query are not part of the origin this judges",
        )
    }

    @Test
    fun `the workbench is ours`() {
        assertTrue(VSCodroidWebViewClient.isOurReferer("http://127.0.0.1:$port/", port))
        assertTrue(VSCodroidWebViewClient.isOurReferer("http://localhost:$port/?folder=%2Fx", port))
        assertFalse(
            VSCodroidWebViewClient.isOurReferer("http://127.0.0.1:${port + 1}/", port),
            "another port on loopback is another server",
        )
    }

    /**
     * The resource authority is accepted here and refused by `isOurOrigin`. There
     * the value is an `Origin` on a request such a document made; here it is the
     * address of a stylesheet already served from it, whose `url(...)` loads
     * carry it as their referrer.
     */
    @Test
    fun `the resource authority is a referer we answer`() {
        assertTrue(
            VSCodroidWebViewClient.isOurReferer(
                "https://file+.vscode-resource.vscode-cdn.net/data/user/0/x/style.css", port,
            ),
        )
    }

    @Test
    fun `a foreign page is refused`() {
        // The measured attack.
        assertFalse(VSCodroidWebViewClient.isOurReferer("http://10.0.2.2:8877/", port))
        assertFalse(VSCodroidWebViewClient.isOurReferer("https://example.com/x.html", port))
    }

    /**
     * The case the whole rule turns on. A page suppresses its referrer with one
     * attribute, and measured on device the request then reaches the gate with
     * none and read the workspace anyway, so absent has to be refused.
     */
    @Test
    fun `no referer at all is refused`() {
        assertFalse(
            VSCodroidWebViewClient.isOurReferer(null, port),
            "a page suppresses its referrer with one meta tag; falling open here " +
                "makes the gate an attribute away from useless",
        )
    }

    /**
     * Kills a substring test. `endsWith(".vscode-cdn.net")` over the whole
     * referer, rather than over its origin, accepts any host at all as long as
     * the attacker puts the string in the path or the query.
     */
    @Test
    fun `the string cannot be smuggled in the path or the query`() {
        assertFalse(
            VSCodroidWebViewClient.isOurReferer("http://10.0.2.2:8877/x.vscode-cdn.net", port),
            "the origin is what is judged, not the whole URL",
        )
        assertFalse(
            VSCodroidWebViewClient.isOurReferer("http://10.0.2.2:8877/?a=.vscode-cdn.net", port),
        )
        assertFalse(
            VSCodroidWebViewClient.isOurReferer("http://evil.example/#.vscode-cdn.net", port),
        )
    }

    /**
     * And a lookalike host. `endsWith` over the origin is what makes
     * `notvscode-cdn.net` fail, since the dot is part of the suffix.
     */
    @Test
    fun `a lookalike host is refused`() {
        assertFalse(VSCodroidWebViewClient.isOurReferer("https://evilvscode-cdn.net/", port))
        assertFalse(VSCodroidWebViewClient.isOurReferer("https://vscode-cdn.net.evil.com/", port))
        assertFalse(
            VSCodroidWebViewClient.isOurReferer("http://x.vscode-cdn.net/", port),
            "http is not the scheme a webview document is served on",
        )
    }

    @Test
    fun `something that is not an absolute URL is refused`() {
        assertFalse(VSCodroidWebViewClient.isOurReferer("", port))
        assertFalse(VSCodroidWebViewClient.isOurReferer("about:blank", port))
        assertFalse(VSCodroidWebViewClient.isOurReferer("/just/a/path", port))
        assertFalse(VSCodroidWebViewClient.isOurReferer("data:text/html,x", port))
    }
}
