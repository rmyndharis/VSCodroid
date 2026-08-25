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
import org.junit.jupiter.api.Assertions.assertNull
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
 * One address is refused, and it is not a destination: `vscodroid://callback`
 * is this app's own sign-in relay, and handed to `startActivity` it comes
 * straight back through the exported filter. Both exits refuse it, and the
 * cases after the pinned rule say why an allow-list is still not what that is.
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
         * A sign-in return address of the shape the editor routinely hands over,
         * with the credential in its query.
         *
         * It carries no `vscode-reqid`, so nothing about it is armed and the
         * cases using it need no rollback.
         */
        const val CREDENTIAL_BEARING =
            "https://dev.example.com:8443/callback?code=4%2F0AdCredential&state=xyz"

        /** The half of [CREDENTIAL_BEARING] that must not reach a log line. */
        const val CREDENTIAL = "4%2F0AdCredential"

        /**
         * The clock reading every launch below is armed with. Distinctive so that
         * [AuthTabWindow.armedReadings] can be asked whether a launch recorded
         * anything at all, whatever key it might have chosen.
         */
        const val LAUNCHED_AT = 1_700_111L

        /**
         * This app's own sign-in relay, shaped as a page would forge it: the
         * payload names a request, and the address carries the same id where
         * `authRequestIdsIn` reads it, so a hand-off that armed before it judged
         * would open the window for exactly the id the payload then posts to.
         */
        const val OWN_CALLBACK_REQUEST_ID = "911"
        const val OWN_CALLBACK =
            "vscodroid://callback?data=%7B%22id%22%3A%22911%22%2C%22uri%22%3A%22x%22%7D" +
                "&vscode-reqid=911"
    }

    /** Set by the client's retry callback, so a case can ask whether it fired. */
    private var retried = false

    private fun request(
        scheme: String, host: String, port: Int, address: String = "",
        fromMainFrame: Boolean = true, withGesture: Boolean = false
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
        // Same reason: a relaxed mock answers false, which is the silent case, so
        // a case about a tap has to say so.
        every { req.hasGesture() } returns withGesture
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
            listOf(SIGN_IN_REQUEST_ID, UNSOLICITED_REQUEST_ID, DOCS_NUMBER, OWN_CALLBACK_REQUEST_ID)
        )
    }

    /** Every hand-off failure the client announced, in order. */
    private val announced = mutableListOf<Pair<String?, String>>()

    /**
     * Every message the client logged, in order.
     *
     * At the shipping levels: `Logger.i` and `Logger.e` are not gated on a
     * debuggable build, so whatever they carry reaches logcat on a user's device.
     */
    private val logged = mutableListOf<String>()

    @BeforeEach
    fun setUp() {
        announced.clear()
        logged.clear()
        mockkObject(Logger)
        every { Logger.i(any(), any()) } answers { logged += secondArg<String>() }
        every { Logger.e(any(), any(), any()) } answers { logged += secondArg<String>() }
        // Recorded as well as stubbed: the refusal of this app's own callback
        // logs here, and the real `Log.w` on the stub android.jar throws.
        every { Logger.w(any(), any()) } answers { logged += secondArg<String>() }
        every { Logger.w(any(), any(), any()) } answers { logged += secondArg<String>() }
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
     * The one address this route refuses, and the case above is its control:
     * a custom scheme still leaves, so this is not a filter on destinations.
     * `vscodroid://callback` is not a destination in another app but this app's
     * own front door. Its VIEW filter is exported and BROWSABLE, so handing it
     * to `startActivity` delivers it straight back to `MainActivity`, and the
     * arming that precedes every main-frame launch would first open the window
     * that delivery is judged against. One navigation, chosen by a page, both
     * opens the relay and posts to it.
     *
     * The bridge refuses the same address, and the workbench cannot reach the
     * bridge with it at all: `injectWindowOpenOverride` routes only http and
     * https there, and the workbench's own opener assigns `location.href` for
     * every other scheme, which arrives here on the main frame.
     *
     * NEGATIVE CONTROL: remove the callback test in `shouldOverrideUrlLoading`
     * and this goes red on the launch and on the arming.
     */
    @Test
    fun `this app's own sign-in callback is refused rather than handed over`() {
        val handled = client.shouldOverrideUrlLoading(
            view, request("vscodroid", "callback", -1, OWN_CALLBACK)
        )

        assertTrue(handled, "the WebView was left to navigate to this app's own callback")
        verify(exactly = 0) { context.startActivity(any()) }
        assertNull(
            AuthTabWindow.armedAt(OWN_CALLBACK_REQUEST_ID),
            "the page chose which vscodroid://callback this app will accept, and the " +
                "launch then posted it: the relay was opened and used in one step",
        )
        assertTrue(announced.isEmpty(), "a refusal is not a failed hand-off: announced $announced")
        // The payload is the page's, so the log says that a callback was refused
        // and nothing of what it carried.
        val line = logged.lastOrNull().orEmpty()
        assertTrue(line.isNotEmpty(), "the refusal was not logged at all, so nothing was checked")
        assertFalse(
            line.contains(OWN_CALLBACK_REQUEST_ID) || line.contains("data="),
            "a forged payload reached a shipping log line: $line",
        )
    }

    /**
     * The same from a frame this app does not vouch for, with the gesture that
     * gets a non-http subframe past the rule above it. The frame test keeps a
     * subframe from arming, so what a tapped link in the simple browser could
     * still do was post a payload of the remote site's choosing for an id a
     * real sign-in had in flight. The refusal sits on both frames for that
     * reason, and this case is what measures the subframe half.
     */
    @Test
    fun `a tapped subframe link to this app's own callback launches nothing`() {
        val handled = client.shouldOverrideUrlLoading(
            view,
            request(
                "vscodroid", "callback", -1, OWN_CALLBACK,
                fromMainFrame = false, withGesture = true,
            ),
        )

        assertTrue(handled, "the WebView was left to navigate to this app's own callback")
        verify(exactly = 0) { context.startActivity(any()) }
        assertNull(AuthTabWindow.armedAt(OWN_CALLBACK_REQUEST_ID))
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
     * A frame that is not our own page may not decide which callbacks come back.
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
     *
     * This case used to assert that the address left for a browser as well, and
     * that was the wrong half to pin: an https subframe is the shape the bundled
     * simple browser and every dev-server preview take, and handing one away
     * leaves the panel blank with another app over the editor. It is rendered
     * here now, which the case below states on its own.
     *
     * Which leaves this one pinning the rendering rather than the frame test,
     * since https returns before the launch is reached. The frame test itself is
     * measured by `a tapped subframe link carrying a sign-in arms nothing`, which
     * is the one shape that gets past both earlier rules.
     */
    @Test
    fun `a sign-in address in a subframe opens no callback window`() {
        val handled = client.shouldOverrideUrlLoading(
            view, request("https", "github.com", -1, SIGN_IN, fromMainFrame = false)
        )

        assertFalse(handled, "the WebView must be left to render an https subframe itself")
        verify(exactly = 0) { context.startActivity(any()) }
        assertFalse(
            callbackWouldBeTaken(SIGN_IN_REQUEST_ID),
            "a frame this app does not vouch for chose which vscodroid://callback the app " +
                "will accept. The filter is exported and BROWSABLE, so that is a sign-in " +
                "the user never started being taken from whatever is on the device.",
        )
    }

    /**
     * The same property as the case above, on the only shape that reaches the
     * arming site at all, and this is the case that measures the frame test.
     *
     * An https subframe is rendered here and returns before the launch, so the
     * case above pins the rendering and nothing else: the frame test could be
     * deleted with it still green. What gets past the rendering rule is a scheme
     * the WebView cannot load, and past the gesture rule is a tap, so a tapped
     * `myapp:` link inside a preview is a real navigation that reaches the launch
     * and carries a request id. `authRequestIdsIn` reads the whole address, so the
     * scheme costs the id nothing.
     *
     * The two subframe cases that do reach the launch cannot stand in for this:
     * `mailto:someone@example.com` and `market://details?id=x` carry no
     * `vscode-reqid`, so arming them arms nothing whatever the frame test says.
     *
     * NEGATIVE CONTROL: remove the main-frame test around the arming call in
     * `shouldOverrideUrlLoading` and this case goes red on `callbackWouldBeTaken`.
     */
    @Test
    fun `a tapped subframe link carrying a sign-in arms nothing`() {
        val handled = client.shouldOverrideUrlLoading(
            view,
            request(
                "myapp", "auth", -1,
                "myapp://auth?state=" + SIGN_IN.substringAfter("state="),
                fromMainFrame = false, withGesture = true,
            ),
        )

        assertTrue(handled, "the WebView was left to navigate to a scheme it cannot load")
        verify(exactly = 1) { context.startActivity(any()) }
        assertFalse(
            callbackWouldBeTaken(SIGN_IN_REQUEST_ID),
            "a frame this app does not vouch for chose which vscodroid://callback the app " +
                "will accept. The filter is exported and BROWSABLE, so that is a sign-in " +
                "the user never started being taken from whatever is on the device.",
        )
    }

    /**
     * The bundled simple browser and every in-editor preview, in one case.
     *
     * Both put a site in an `<iframe>` by assigning its `src`. Handing that to
     * the system browser leaves the panel blank while another app covers the
     * editor, and the localhost test above cannot rescue it either: that admits
     * the editor's own port and no other, so a dev server on 5173 is as external
     * to it as a remote host.
     *
     * ⚠️ Whether the WebView delivers a script-initiated iframe navigation to
     * this callback at all is NOT measured anywhere in this repository. The
     * platform documents it as one that may be called for subframes, and
     * `isForMainFrame` exists for that; DEVICE_TEST_CHECKLIST ED-11 records the
     * simple browser reaching a certificate error through this app's own TLS
     * callback, which implies it does not. The rule is written to be right under
     * either reading, and these cases pin the rule.
     */
    @Test
    fun `an https subframe is rendered here rather than handed to another app`() {
        val handled = client.shouldOverrideUrlLoading(
            view, request("https", "example.com", -1, "https://example.com", fromMainFrame = false)
        )

        assertFalse(handled, "returning true is what stops the WebView rendering the frame")
        verify(exactly = 0) { context.startActivity(any()) }
    }

    /** And the dev-server preview, which is the same navigation on a different port. */
    @Test
    fun `a local dev server in a subframe is rendered here`() {
        val handled = client.shouldOverrideUrlLoading(
            view,
            request("http", "127.0.0.1", 5173, "http://127.0.0.1:5173/", fromMainFrame = false),
        )

        assertFalse(handled, "the preview of a dev server was sent to the system browser")
        verify(exactly = 0) { context.startActivity(any()) }
    }

    /**
     * A scheme the WebView cannot render, driven by a script rather than by a tap.
     *
     * The launch carries `FLAG_ACTIVITY_NEW_TASK` and asked for no user
     * activation, so a hidden frame reassigning `src` on a timer brought an
     * arbitrary installed app to the front over a live editor as often as it
     * liked. `market:` is the cheapest demonstration; the same holds for `tel:`,
     * `sms:` and any exported deep link on the device.
     */
    @Test
    fun `a subframe launches nothing without a user gesture behind it`() {
        val handled = client.shouldOverrideUrlLoading(
            view,
            request("market", "details", -1, "market://details?id=x", fromMainFrame = false),
        )

        assertTrue(handled, "the WebView was left to navigate to a scheme it cannot load")
        verify(exactly = 0) { context.startActivity(any()) }
    }

    /**
     * The other half, and what keeps the case above from being a destination
     * filter: a link the user genuinely tapped inside a preview still opens.
     */
    @Test
    fun `a subframe link the user tapped is still handed to an activity`() {
        val handled = client.shouldOverrideUrlLoading(
            view,
            request(
                "mailto", "", -1, "mailto:someone@example.com",
                fromMainFrame = false, withGesture = true,
            ),
        )

        assertTrue(handled)
        verify(exactly = 1) { context.startActivity(any()) }
    }

    /**
     * The main frame keeps its hand-off with no gesture required, and that is
     * deliberate rather than an oversight in the gate above.
     *
     * It is the workbench, the one document here this app serves, and it is where
     * both routes this channel backs up navigate: `injectWindowOpenOverride` falls
     * through to a plain navigation and the workbench's own opener assigns
     * `location.href`. Whether user activation survives those chains is not
     * measured, and requiring it would break every sign-in that takes them.
     */
    @Test
    fun `the main frame is handed over without a gesture`() {
        val handled = client.shouldOverrideUrlLoading(
            view, request("https", "github.com", -1, SIGN_IN)
        )

        assertTrue(handled)
        verify(exactly = 1) { context.startActivity(any()) }
    }

    /**
     * A navigation cannot flush the record of what this app has in flight.
     *
     * The record holds the most recent few launches, and an address may name as
     * many request ids as its author cares to type, so one navigation carrying
     * enough of them pushed out the sign-in the user had open in the browser.
     * The callback then comes back for an id nothing recorded, is dropped in the
     * log with no message, and the extension waits for ever.
     *
     * Here rather than only at the bridge, because the two exits read the ids
     * through the same function and a bound written at one call site leaves the
     * other unbounded. The main frame is our own page, but the address is not
     * necessarily ours: `injectWindowOpenOverride` falls through to a plain
     * navigation and the workbench's own opener assigns `location.href`, so
     * whatever asked to open a URL chose this string.
     *
     * NEGATIVE CONTROL: bound the ids at `AndroidBridge.openExternalUrl` instead
     * of inside `authRequestIdsIn` and this case goes red while the bridge's own
     * stays green.
     */
    @Test
    fun `a navigation naming a crowd of requests leaves the sign-in in flight armed`() {
        val crowd = (1..32).map { "9400$it" }
        try {
            client.shouldOverrideUrlLoading(view, request("https", "github.com", -1, SIGN_IN))

            client.shouldOverrideUrlLoading(
                view,
                request(
                    "https", "example.com", -1,
                    "https://example.com/?" + crowd.joinToString("&") { "vscode-reqid=$it" },
                ),
            )

            assertTrue(
                callbackWouldBeTaken(SIGN_IN_REQUEST_ID),
                "one navigation pushed the sign-in actually in flight out of the record, so " +
                    "its callback is refused and nothing tells the user why",
            )
        } finally {
            AuthTabWindow.disarm(crowd)
        }
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
     * What the log keeps of an address this app hands to another app.
     *
     * The rule is already written down one type away: `TlsFailure` says the host
     * and never the address, because the failing URL is whatever the open page
     * asked for and a dev server or a sign-in carries an OAuth code or an API key
     * in its query. The same value arrives here, on a statement at `Logger.i`
     * that is not gated on a debuggable build, and the redaction it used to carry
     * is keyed on `tkn=`, this app's own parameter, which never appears on an
     * address bound for a browser.
     *
     * The host assertion is what keeps this from passing on a line that dropped
     * the address altogether: a reader still has to be able to tell where the tap
     * went.
     */
    @Test
    fun `a followed link is logged by host, without the query it carried`() {
        client.shouldOverrideUrlLoading(
            view, request("https", "dev.example.com", 8443, CREDENTIAL_BEARING)
        )

        val line = logged.lastOrNull().orEmpty()
        assertTrue(line.isNotEmpty(), "the hand-off was not logged at all, so nothing was checked")
        assertTrue(
            line.contains("dev.example.com:8443"),
            "the line does not say where the tap went, so this case would pass on a " +
                "statement that dropped the address rather than one that reduced it: $line",
        )
        assertFalse(
            line.contains("code=") || line.contains(CREDENTIAL) || line.contains("callback"),
            "the query a page chose reached a shipping log line: $line",
        )
    }

    /** And the same address on the failing path, which logs at `Logger.e`. */
    @Test
    fun `a hand-off that failed is logged by host, without the query it carried`() {
        every { context.startActivity(any()) } throws ActivityNotFoundException("no handler")

        client.shouldOverrideUrlLoading(
            view, request("https", "dev.example.com", 8443, CREDENTIAL_BEARING)
        )

        val line = logged.lastOrNull().orEmpty()
        assertTrue(line.isNotEmpty(), "the failure was not logged at all, so nothing was checked")
        assertTrue(
            line.contains("dev.example.com:8443"),
            "the line does not say where the tap went: $line",
        )
        assertTrue(
            line.contains("ActivityNotFoundException"),
            "the exception type is the part of the trace worth keeping: $line",
        )
        assertFalse(
            line.contains("code=") || line.contains(CREDENTIAL),
            "the query a page chose reached a shipping log line: $line",
        )
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
