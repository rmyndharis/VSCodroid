package com.vscodroid.webview

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import com.vscodroid.util.Logger
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * What `shouldOverrideUrlLoading` hands to another app, and what it keeps.
 *
 * Handing any external URL to the system browser is DELIBERATE and these cases
 * exist to say so. This is a development environment: a link in the editor can
 * legitimately point at a LAN dev server, a private registry, a staging host on
 * a scheme nobody anticipated. Refusing what an allow-list has not heard of would
 * break the product rather than protect it.
 *
 * The method had no test at all until now, which is why the decision had never
 * been written down anywhere and read as an oversight.
 *
 * It differs from `SecurityManager.isAllowedUrl`, reached through the bridge's
 * `openUrl`, which permits `https:`, `mailto:` and `http:` to localhost only —
 * and `UrlAllowlistWiringTest` pins `intent://scan/#Intent;scheme=zxing;end`
 * among what must never reach a browser from there. The two are not inconsistent,
 * they answer different questions:
 *
 *  - here the USER followed a link, and opening it is what a browser does;
 *  - there the PAGE called a native method, which is an app capability handed to
 *    JavaScript rather than an action anyone took, so it is allow-listed.
 *
 * Change the rule here and the cases below invert; that is the point of them
 * being written down rather than assumed.
 *
 * A word on how far the asymmetry goes, because the obvious reading overstates
 * it. Handing `intent://…` here is NOT the intent-redirection hazard it looks
 * like: `Intent(ACTION_VIEW, uri)` sets an action and a data URI and does not
 * decode the component and extras that `Intent.parseUri` would. So what this
 * grants is what any browser grants — the ability to launch whatever app has
 * registered for a scheme — rather than the ability to aim an Intent inside this
 * app. Reasoned from the two APIs, not measured. What is measured is below.
 *
 * The first case is the control and it is what makes the rest a measurement: the
 * workbench's own origin is NOT handed over, so this is not a fixture in which
 * `startActivity` is simply always called. The real `isLocalhost` runs; nothing
 * about the decision is stubbed.
 *
 * `Intent`'s constructor is mocked only because the stub `android.jar` does not
 * answer `addFlags`.
 */
class ExternalUrlHandoffTest {

    private val ALLOWED_PORT = 13337

    private lateinit var context: Context
    private lateinit var view: WebView
    private lateinit var client: VSCodroidWebViewClient

    private fun request(scheme: String, host: String, port: Int): WebResourceRequest {
        val uri = mockk<Uri>(relaxed = true)
        every { uri.scheme } returns scheme
        every { uri.host } returns host
        every { uri.port } returns port
        val req = mockk<WebResourceRequest>(relaxed = true)
        every { req.url } returns uri
        return req
    }

    @BeforeEach
    fun setUp() {
        mockkObject(Logger)
        every { Logger.i(any(), any()) } just Runs
        every { Logger.e(any(), any(), any()) } just Runs
        every { Logger.d(any(), any()) } just Runs

        mockkConstructor(Intent::class)
        every { anyConstructed<Intent>().addFlags(any()) } returns mockk(relaxed = true)

        context = mockk(relaxed = true)
        view = mockk(relaxed = true)
        every { view.context } returns context

        client = VSCodroidWebViewClient(
            allowedPort = ALLOWED_PORT,
            resourceRoots = emptyList(),
            sensitiveLocations = emptyList(),
            openFolder = { null },
            connectionToken = { null },
            onCrash = {},
            onPageLoaded = {},
        )
    }

    /** The control: an internal URL must NOT be handed to an activity. */
    @Test
    fun `the workbench origin is not handed to an activity`() {
        val handled = client.shouldOverrideUrlLoading(view, request("http", "127.0.0.1", ALLOWED_PORT))
        assertFalse(handled, "the WebView must navigate to its own origin itself")
        verify(exactly = 0) { context.startActivity(any()) }
    }

    /**
     * A plain-http LAN address, which the bridge's allow-list refuses and this
     * path allows on purpose.
     *
     * This is the case that decides the policy rather than illustrating it. A dev
     * server on the LAN is the ordinary thing a user of this app opens, so
     * applying `isAllowedUrl` here would refuse the workflow the product exists
     * for. Anyone tempted to align the two should fail this test first and then
     * ask whether they meant to.
     */
    @Test
    fun `a LAN address the bridge refuses is handed to an activity here`() {
        val handled = client.shouldOverrideUrlLoading(view, request("http", "192.168.1.50", 5173))
        verify(exactly = 1) { context.startActivity(any()) }
        assertTrue(handled, "returning true is what stops the WebView navigating to it")
    }

    /** Any external host at all — the load-bearing case. */
    @Test
    fun `an arbitrary external host is handed to an activity with no allow-list`() {
        val handled = client.shouldOverrideUrlLoading(view, request("https", "evil.example.com", -1))
        verify(exactly = 1) { context.startActivity(any()) }
        assertTrue(handled)
    }

    /** A scheme the bridge refuses outright, and the one the pinned rule names. */
    @Test
    fun `a non-http scheme is also handed to an activity`() {
        val handled = client.shouldOverrideUrlLoading(view, request("intent", "scan", -1))
        verify(exactly = 1) { context.startActivity(any()) }
        assertTrue(handled)
    }
}
