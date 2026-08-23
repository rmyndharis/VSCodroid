package com.vscodroid.webview

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Which origins the webview resource arm answers to, as a value.
 *
 * The arm serves any file under the published roots, which is the open
 * workspace, the projects tree, the SAF mirrors, the server tree and the
 * extensions directory. So this test is the whole of "who may read the user's
 * code through the editor", and it is not observable from the interceptor: both
 * outcomes end in a `WebResourceResponse`, which cannot be constructed under the
 * stub `android.jar`. `ResourceInterceptionWiringTest` pins that the interceptor
 * consults these; this pins what they say.
 */
class WebviewOriginTrustTest {

    private val port = 41234

    @Test
    fun `the workbench's own two spellings are ours`() {
        assertTrue(VSCodroidWebViewClient.isWorkbenchOrigin("http://127.0.0.1:$port", port))
        assertTrue(VSCodroidWebViewClient.isWorkbenchOrigin("http://localhost:$port", port))
    }

    /**
     * Another port on loopback is another application. Binding one needs no
     * permission on Android, so a service the user is running, or anything else
     * installed, would otherwise be the workbench as far as this is concerned.
     */
    @Test
    fun `another port on loopback is not the workbench`() {
        assertFalse(VSCodroidWebViewClient.isWorkbenchOrigin("http://127.0.0.1:5173", port))
        assertFalse(VSCodroidWebViewClient.isWorkbenchOrigin("https://127.0.0.1:$port", port))
    }

    /**
     * A webview document is ours, and it has to be: extension webviews load their
     * icon fonts and their CSS cross-origin from the resource authority, which is
     * a CORS request and carries this origin.
     */
    @Test
    fun `a webview document is ours`() {
        assertTrue(
            VSCodroidWebViewClient.isOurOrigin("https://0f7c2b1a-uuid.vscode-cdn.net", port)
        )
    }

    /**
     * The resource authority is not, and that is the gate on a real escalation.
     *
     * A navigation carries no `Origin` header, so any `.html` under a published
     * root can be loaded as a document from
     * `https://file+.vscode-resource.vscode-cdn.net/<path>` and will run: a
     * published root is routinely a checked-out repository. While this answered
     * true, that document could `fetch` every other file under every root back
     * through the same arm.
     *
     * What it does not close, since the comment on the gate says so and this
     * should not say otherwise: the same document can still frame another path
     * on its own origin, which is a navigation and so carries no origin to
     * refuse, and read it back same-origin. This shuts the asking, not the
     * reading.
     */
    @Test
    fun `a document at the resource authority is not ours`() {
        assertFalse(
            VSCodroidWebViewClient.isOurOrigin(
                "https://file+.vscode-resource.vscode-cdn.net", port
            ),
            "a document this app served was trusted to read the rest of the workspace",
        )
        assertFalse(
            VSCodroidWebViewClient.isOurOrigin(
                "https://vscode-remote+ssh.vscode-resource.vscode-cdn.net", port
            ),
        )
    }

    /** The control: an ordinary remote page is refused whatever it is called. */
    @Test
    fun `a remote page is not ours`() {
        assertFalse(VSCodroidWebViewClient.isOurOrigin("https://evil.example", port))
        assertFalse(
            VSCodroidWebViewClient.isOurOrigin("https://evil.example/vscode-cdn.net", port),
            "the suffix test must read the host and not the whole string",
        )
        assertFalse(
            VSCodroidWebViewClient.isOurOrigin("http://x.vscode-cdn.net", port),
            "a webview document is https; plain http from that host is not one",
        )
    }

    /**
     * The parameter the CDN arm reads before it proxies anything. It arrives
     * percent-encoded, because the workbench sets it through `searchParams`, and
     * this reads it from `Uri.getEncodedQuery()` for the same reason: the decoded
     * spelling has already turned any escaped `&` in a value into a separator.
     */
    @Test
    fun `a parent origin is read out of the encoded query`() {
        assertEquals(
            "http://127.0.0.1:41234",
            VSCodroidWebViewClient.queryParameter(
                "id=1&parentOrigin=http%3A%2F%2F127.0.0.1%3A41234&swVersion=4", "parentOrigin"
            ),
        )
        assertNull(
            VSCodroidWebViewClient.queryParameter("id=1&swVersion=4", "parentOrigin"),
            "absent is the ordinary case: every asset request carries no parent origin",
        )
        assertNull(VSCodroidWebViewClient.queryParameter(null, "parentOrigin"))
    }

    /**
     * A name that merely ends with the one being looked for is a different
     * parameter. Without the anchor, `notParentOrigin=` would answer for
     * `parentOrigin` and a page could hide its own value behind one.
     */
    @Test
    fun `a parameter whose name only ends in the one asked for is not read`() {
        assertNull(
            VSCodroidWebViewClient.queryParameter("xparentOrigin=https%3A%2F%2Fevil", "parentOrigin")
        )
    }

    /**
     * Two spellings a page can use to disagree with the parser on the other side.
     *
     * `pre/index.html` reads the same address with `URLSearchParams`, which
     * decodes names as well as values and stops at the first match, so this has
     * to do both or the page and this app are reading different parameters. The
     * escaped separator is the one that matters: it is a single parameter to a
     * parser that splits before it decodes, and two to one that does not.
     */
    @Test
    fun `a name and a separator hidden in an escape are read the way the page reads them`() {
        assertEquals(
            "https://evil.example",
            VSCodroidWebViewClient.queryParameter(
                "parentOri%67in=https%3A%2F%2Fevil.example", "parentOrigin"
            ),
            "a page spelled the name in escapes and the gate never saw the parameter",
        )
        assertEquals(
            "https://evil.example",
            VSCodroidWebViewClient.queryParameter(
                "id=1%26parentOrigin%3Dhttp%3A%2F%2F127.0.0.1%3A41234" +
                    "&parentOrigin=https%3A%2F%2Fevil.example",
                "parentOrigin",
            ),
            "an escaped separator manufactured a parameter that is one value to the page",
        )
    }

    /**
     * A parameter written with no value at all. `URLSearchParams.get` answers the
     * empty string for it rather than null, and the two have to agree: null here
     * is "the page named no parent", which this is not.
     */
    @Test
    fun `a valueless parameter is an empty value and not an absent one`() {
        assertEquals("", VSCodroidWebViewClient.queryParameter("id=1&parentOrigin", "parentOrigin"))
    }
}
