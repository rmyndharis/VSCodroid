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
 * This method had no test at all, which is the finding that stands on its own
 * whatever anyone decides about the behaviour. It is the app's second exit for a
 * URL, and the two exits do not agree.
 *
 * The other one is `SecurityManager.isAllowedUrl`, reached through the bridge's
 * `openUrl`. It permits `https:`, `mailto:`, and `http:` only for localhost —
 * everything else is refused and logged, and `UrlAllowlistWiringTest` pins
 * `intent://scan/#Intent;scheme=zxing;end` among what must never reach a browser.
 * This path applies no such rule: anything that is not the workbench origin and
 * not a CDN host is handed to `ACTION_VIEW`.
 *
 * These cases pin what that actually is, so the asymmetry is a decision someone
 * makes rather than something nobody has looked at. They assert CURRENT
 * behaviour; if the policy changes, they invert in the same commit.
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
     * path allows.
     *
     * Worth knowing before anyone aligns the two: a dev server on the LAN is an
     * ordinary thing for this app's users to open, so applying `isAllowedUrl`
     * here verbatim would refuse a workflow rather than an attack.
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
