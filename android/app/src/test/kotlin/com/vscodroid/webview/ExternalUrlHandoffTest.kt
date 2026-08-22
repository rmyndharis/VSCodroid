package com.vscodroid.webview

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.webkit.WebResourceRequest
import android.webkit.WebView
import com.vscodroid.authCallbackIsExpected
import com.vscodroid.bridge.AUTH_TAB_WINDOW_MILLIS
import com.vscodroid.bridge.AuthTabWindow
import com.vscodroid.util.Logger
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
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
 * The app's other exit, `AndroidBridge.openExternalUrl`, now agrees with it. It
 * used to disagree: an allow-list there permitted `https:`, `mailto:` and `http:`
 * to localhost only, so the same click on the same URL opened or silently did
 * nothing depending on whether VS Code routed it as a navigation or through
 * `window.open`. The list is gone (see `UrlAllowlistWiringTest`, which now pins
 * its absence) and the two exits answer the same way.
 *
 * Change the rule here and the cases below invert; that is the point of them
 * being written down rather than assumed.
 *
 * A word on how far the asymmetry goes, because the obvious reading overstates
 * it. Handing `intent://…` here is NOT the intent-redirection hazard it looks
 * like: `Intent(ACTION_VIEW, uri)` sets an action and a data URI and does not
 * decode the component and extras that `Intent.parseUri` would. So what this
 * grants is what any browser grants (the ability to launch whatever app has
 * registered for a scheme) rather than the ability to aim an Intent inside this
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

    private companion object {
        /**
         * An https sign-in address carrying the request id the workbench minted
         * for the callback it is waiting on. The id travels inside `state`,
         * percent-encoded twice, which is the ordinary shape: the callback
         * address is a parameter of the authorisation address.
         */
        const val SIGN_IN =
            "https://github.com/login/oauth/authorize?client_id=abc" +
                "&state=http%253A%252F%252F127.0.0.1%253A13337%252Fcallback%253Fvscode-reqid%253D909"

        /** The id [SIGN_IN] carries. */
        const val SIGN_IN_REQUEST_ID = "909"

        /** A neighbouring id nothing here launched, and the one that must stay refused. */
        const val UNSOLICITED_REQUEST_ID = "910"

        /**
         * A link with no sign-in in it, which must open no window at all.
         *
         * It carries a number on purpose. A hand-off that armed every digit run
         * in the address rather than the ids `vscode-reqid` names would still
         * satisfy the two cases above, since the id [SIGN_IN] carries is also a
         * digit run of its own; only a control that carries an unrelated number
         * tells the two apart.
         */
        const val DOCS = "https://code.visualstudio.com/docs/1234"

        /** The number [DOCS] carries, which nothing may read as a request id. */
        const val DOCS_NUMBER = "1234"

        /**
         * The clock reading every launch below is armed with. Distinctive so that
         * [AuthTabWindow.armedReadings] can be asked whether a launch recorded
         * anything at all, whatever key it might have chosen.
         */
        const val LAUNCHED_AT = 1_700_111L
    }

    /** Set by the client's retry callback, so a case can ask whether it fired. */
    private var retried = false

    private fun request(
        scheme: String, host: String, port: Int, address: String = "",
        fromMainFrame: Boolean = true
    ): WebResourceRequest {
        val uri = mockk<Uri>(relaxed = true)
        every { uri.scheme } returns scheme
        every { uri.host } returns host
        every { uri.port } returns port
        // Read by `authRequestIdsIn`, and a relaxed mock's own `toString` names
        // the mock rather than an address, so a case about request ids has to
        // say what the address is. Empty elsewhere: no id, nothing armed.
        every { uri.toString() } returns address
        val req = mockk<WebResourceRequest>(relaxed = true)
        every { req.url } returns uri
        // Stated rather than left to the relaxed mock, which would answer false
        // and quietly make every case below a subframe case.
        every { req.isForMainFrame } returns fromMainFrame
        return req
    }

    /**
     * The decision `MainActivity.receiveCallbackIntent` makes about an arriving
     * `vscodroid://callback`, in the two steps it makes it in: a request this app
     * never launched has no reading, and a reading has to be inside the window.
     */
    private fun callbackWouldBeTaken(requestId: String): Boolean {
        val armedAt = AuthTabWindow.armedAt(requestId) ?: return false
        return authCallbackIsExpected(armedAt, LAUNCHED_AT, AUTH_TAB_WINDOW_MILLIS)
    }

    /**
     * [AuthTabWindow] is an object and this suite runs in one JVM, so what these
     * cases write through the real API outlives them. mockk's cleanup covers
     * mocks, not that.
     */
    @AfterEach
    fun handBackArmedRequests() {
        AuthTabWindow.disarm(
            listOf(SIGN_IN_REQUEST_ID, UNSOLICITED_REQUEST_ID, DOCS_NUMBER)
        )
    }

    /** Every hand-off failure the client announced, in order. */
    private val announced = mutableListOf<Pair<String?, String>>()

    @BeforeEach
    fun setUp() {
        announced.clear()
        mockkObject(Logger)
        every { Logger.i(any(), any()) } just Runs
        every { Logger.e(any(), any(), any()) } just Runs
        every { Logger.d(any(), any()) } just Runs

        mockkConstructor(Intent::class)
        every { anyConstructed<Intent>().addFlags(any()) } returns mockk(relaxed = true)

        // The hand-off records the sign-in it is carrying, and reads the clock to
        // do it. android.jar's stub throws, which the catch below would turn into
        // a launch that never happened, so every case here needs it answered.
        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } returns LAUNCHED_AT

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
                    onRetryServer = { retried = true },
            onHandoffFailed = { uri, error ->
                announced += uri.scheme to error.javaClass.simpleName
            },
        )
    }

    /**
     * The one URL reaching this method that is a control on our own page rather
     * than an address to hand away.
     *
     * The server-gave-up page's button is a navigation, and the page can be
     * shown under either of the two clients a WebView wears. Only the bootstrap
     * one used to answer it, so on every session that had reached the editor the
     * button fell through to the branch below and was handed to a system that
     * has no component for `vscodroid://retry-server`. The exception is caught
     * and logged, so the only way off that page did nothing and said nothing.
     */
    @Test
    fun `the retry navigation is answered here, not handed to another app`() {
        retried = false

        val handled = client.shouldOverrideUrlLoading(
            view, request("vscodroid", "retry-server", -1, RETRY_URL)
        )

        assertTrue(handled, "the navigation was allowed to proceed")
        assertTrue(retried, "the only way off the server-gave-up page did nothing")
        verify(exactly = 0) { context.startActivity(any()) }
    }

    /**
     * The control that keeps the case above from being a widening.
     *
     * This client sees subframe navigations, and the frames are not all ours:
     * the bundled simple browser holds an arbitrary remote site, and previews
     * are built from workspace content. Without the main-frame gate any of them
     * could blank a live editor and restart the server from a hidden iframe.
     */
    @Test
    fun `a subframe cannot restart the server`() {
        retried = false

        client.shouldOverrideUrlLoading(
            view, request("vscodroid", "retry-server", -1, RETRY_URL, fromMainFrame = false)
        )

        assertFalse(retried, "a page in an iframe restarted the server")
    }

    /** A near miss is not the control either: the whole string has to match. */
    @Test
    fun `a retry URL carrying anything extra is not answered here`() {
        retried = false

        client.shouldOverrideUrlLoading(
            view, request("vscodroid", "retry-server", -1, "$RETRY_URL?reload=1")
        )

        assertFalse(retried, "a URL that only looks like the retry control was accepted")
    }

    /** The control: an internal URL must NOT be handed to an activity. */
    @Test
    fun `the workbench origin is not handed to an activity`() {
        val handled = client.shouldOverrideUrlLoading(view, request("http", "127.0.0.1", ALLOWED_PORT))
        assertFalse(handled, "the WebView must navigate to its own origin itself")
        verify(exactly = 0) { context.startActivity(any()) }
    }

    /**
     * A plain-http LAN address.
     *
     * This is the case that decided the policy rather than illustrating it. A dev
     * server on the LAN is the ordinary thing a user of this app opens, and it is
     * the URL that was refused on the other exit while opening on this one.
     * Anyone adding a filter here should fail this test first and then ask
     * whether they meant to.
     */
    @Test
    fun `a LAN address the bridge refuses is handed to an activity here`() {
        val handled = client.shouldOverrideUrlLoading(view, request("http", "192.168.1.50", 5173))
        verify(exactly = 1) { context.startActivity(any()) }
        assertTrue(handled, "returning true is what stops the WebView navigating to it")
    }

    /** Any external host at all: the load-bearing case. */
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

    /**
     * A sign-in that leaves through this route can come back.
     *
     * The bridge is the route the workbench normally takes, and it records the
     * launch. This one is the fallback underneath it, reached when the page
     * holds no session token yet, when the bridge reports no launch, and when
     * the workbench's own opener assigns `location.href` for a scheme that is
     * not http or https. It recorded nothing, so the `vscodroid://callback` that
     * came back was judged against a window nobody had opened and refused in the
     * log, with no message anywhere: the sign-in simply never completed.
     *
     * Asserted on the acceptance decision itself rather than on the arming call,
     * because the arming is only interesting for what it lets back in.
     */
    @Test
    fun `a sign-in handed over here is a callback this app then accepts`() {
        val handled = client.shouldOverrideUrlLoading(
            view, request("https", "github.com", -1, SIGN_IN)
        )

        assertTrue(handled, "the address has to leave the WebView for a browser at all")
        verify(exactly = 1) { context.startActivity(any()) }

        assertTrue(
            callbackWouldBeTaken(SIGN_IN_REQUEST_ID),
            "the callback for the sign-in this hand-off carried must be accepted when it " +
                "returns; refusing it is a sign-in that hangs with nothing said",
        )
        assertFalse(
            callbackWouldBeTaken(UNSOLICITED_REQUEST_ID),
            "only the request the address carried may be accepted. The callback filter is " +
                "exported and BROWSABLE and the id is a small integer, so a hand-off that " +
                "opened the relay to anything else opens it to whatever is on the device.",
        )
    }

    /**
     * A hand-off that no activity took must not leave the relay open behind it.
     *
     * The record is written before the launch, because the launch hands off to
     * another process. So a launch that then threw has already opened the window
     * for the ids it named, and for the next ten minutes any app on the device
     * can post a `vscodroid://callback` naming one of them, through an exported
     * BROWSABLE filter, for a sign-in that never started.
     */
    @Test
    fun `a hand-off that no activity took leaves nothing armed`() {
        every { context.startActivity(any()) } throws IllegalStateException("no activity took it")

        client.shouldOverrideUrlLoading(view, request("https", "github.com", -1, SIGN_IN))

        assertFalse(
            callbackWouldBeTaken(SIGN_IN_REQUEST_ID),
            "a launch that failed left the callback window armed for a sign-in that never " +
                "started, and nothing on the way back can tell the difference",
        )
    }

    /**
     * A frame that is not our own page may leave for a browser, but may not
     * decide which callbacks come back.
     *
     * This callback receives subframe navigations as well as top-level ones, and
     * a frame may always navigate itself whatever its sandbox permits. The frames
     * here render content this app does not vouch for: the bundled simple browser
     * puts an arbitrary remote site in an iframe, and previews and notebook
     * output are built from whatever the workspace holds. The other route to
     * arming, `AndroidBridge.openExternalUrl`, refuses a caller without the
     * session token; there is no token to check here, so the frame is what
     * carries that weight.
     *
     * Both routes this hand-off exists to serve navigate the top-level frame, so
     * nothing real is lost: `MainActivity.injectWindowOpenOverride` patches the
     * main window's `window.open`, and the workbench opener assigns the main
     * window's `location`.
     *
     * The cost of getting this wrong is not only a forged callback. The record
     * keeps the most recent 32 launches, one address can carry many ids, and the
     * oldest goes when it overflows, so a page able to arm at will can push out
     * the sign-in the user has open in the browser right now.
     */
    @Test
    fun `a sign-in address in a subframe opens no callback window`() {
        val handled = client.shouldOverrideUrlLoading(
            view, request("https", "github.com", -1, SIGN_IN, fromMainFrame = false)
        )

        assertTrue(handled, "a subframe's external link still leaves for a browser")
        verify(exactly = 1) { context.startActivity(any()) }
        assertFalse(
            callbackWouldBeTaken(SIGN_IN_REQUEST_ID),
            "a frame this app does not vouch for chose which vscodroid://callback the app " +
                "will accept. The filter is exported and BROWSABLE, so that is a sign-in " +
                "the user never started being taken from whatever is on the device.",
        )
    }

    /**
     * The other half, and the one that keeps this from being a widening: every
     * external link goes through here, and a link to documentation must not put
     * the callback relay within reach of an id nobody asked for.
     *
     * Measured on the readings rather than on a key, so a hand-off that recorded
     * the fact of a launch under any name at all is caught.
     */
    @Test
    fun `a link carrying no sign-in opens no callback window`() {
        client.shouldOverrideUrlLoading(view, request("https", "code.visualstudio.com", -1, DOCS))

        verify(exactly = 1) { context.startActivity(any()) }
        assertFalse(
            AuthTabWindow.armedReadings().contains(LAUNCHED_AT),
            "following a documentation link recorded a launch. Every external link in the " +
                "editor arrives here, so recording them all reopens the window an unsolicited " +
                "vscodroid://callback is taken in.",
        )
    }

    /**
     * The case this channel exists for. Nothing on the device answers `ssh:`, so
     * `startActivity` throws, and `return true` below stops the WebView from
     * navigating too. Before the channel existed the tap did nothing and said
     * nothing, and the user's only reading was that the link was broken.
     */
    @Test
    fun `a scheme no app answers is announced`() {
        every { context.startActivity(any()) } throws ActivityNotFoundException("no handler")

        val handled = client.shouldOverrideUrlLoading(
            view, request("ssh", "git@example.com", -1, "ssh://git@example.com")
        )

        assertTrue(handled, "the WebView was left to navigate to a scheme it cannot load")
        assertEquals(listOf("ssh" to "ActivityNotFoundException"), announced)
    }

    /**
     * Three different exceptions land in one catch, and from outside the app they
     * are indistinguishable: `ActivityNotFoundException` for a scheme nothing
     * claims, `FileUriExposedException` for a `file://` URI, and
     * `SecurityException` for a `content://` URI with no grant. Each still has to
     * produce a notice, because a channel that covers only the easy one leaves
     * the same silence behind for the other two.
     */
    @Test
    fun `a file URI Android refuses to expose is announced`() {
        every { context.startActivity(any()) } throws IllegalStateException("FileUriExposed")

        client.shouldOverrideUrlLoading(
            view, request("file", "/sdcard/x.txt", -1, "file:///sdcard/x.txt")
        )

        assertEquals(listOf("file" to "IllegalStateException"), announced)
    }

    @Test
    fun `a content URI without a grant is announced`() {
        every { context.startActivity(any()) } throws SecurityException("no grant")

        client.shouldOverrideUrlLoading(
            view, request("content", "doc/1", -1, "content://authority/doc/1")
        )

        assertEquals(listOf("content" to "SecurityException"), announced)
    }

    /**
     * The control, and it is what stops the cases above from passing because the
     * channel fires unconditionally. A hand-off that succeeded is not a failure,
     * and a notice on every followed link would be worse than the silence it
     * replaced.
     */
    @Test
    fun `a hand-off that succeeds announces nothing`() {
        every { context.startActivity(any()) } returns Unit

        client.shouldOverrideUrlLoading(
            view, request("https", "example.com", -1, "https://example.com/docs")
        )

        assertTrue(announced.isEmpty(), "announced $announced for a link that opened")
    }

    /**
     * The retry navigation is answered on this side and never handed away, so it
     * cannot fail as a hand-off. Without this the retry case and the failure
     * cases could both be satisfied by a channel wired to the wrong branch.
     */
    @Test
    fun `the retry navigation announces nothing`() {
        retried = false

        client.shouldOverrideUrlLoading(
            view, request("vscodroid", "retry-server", -1, RETRY_URL)
        )

        assertTrue(retried, "the retry branch did not run")
        assertTrue(announced.isEmpty(), "announced $announced for our own control")
    }
}
