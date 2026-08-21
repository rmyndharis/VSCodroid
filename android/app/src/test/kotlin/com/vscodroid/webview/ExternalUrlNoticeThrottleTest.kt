package com.vscodroid.webview

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.webkit.WebResourceRequest
import android.webkit.WebView
import com.vscodroid.util.Logger
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File

/**
 * How often a hand-off this app could not complete may put something on screen.
 *
 * The notice itself is covered by `ExternalUrlHandoffTest`, which pins that each
 * of the three exceptions landing in that catch produces one. What is pinned here
 * is the other side of it: the notice was unconditional, and both of the things
 * that makes it are cheap for a page to drive.
 *
 * A toast holds the screen for about three and a half seconds at `LENGTH_LONG`
 * and stacks rather than replaces, and `shouldOverrideUrlLoading` receives
 * subframe navigations as well as top-level ones. A script may navigate an iframe
 * to a scheme no app on the device answers with no user gesture at all and as
 * often as it likes, and the content that reaches this client is not this app's:
 * the bundled simple browser holds an arbitrary remote site, and previews and
 * notebook output are built from whatever the workspace holds. So a rendered page
 * could hold a sustained stream of long toasts over an editor the user is working
 * in, and before the notice existed that path only logged.
 *
 * Two rules, both needed, and each of the cases below fails on its own if the
 * other is the only one present. The gate asks whether the navigation is
 * attributable to the user or to this app's own page; the throttle asks whether
 * the message has already been said. The gate alone still lets a page the user
 * taps once per link speak once per link; the throttle alone still lets a page
 * spend its first eight silent navigations on eight distinct schemes.
 *
 * The launch is NOT gated, and one case exists to keep it that way. A link in a
 * preview still leaves for a browser exactly as it did.
 */
class ExternalUrlNoticeThrottleTest {

    private val ALLOWED_PORT = 13337

    private lateinit var context: Context
    private lateinit var view: WebView
    private lateinit var client: VSCodroidWebViewClient

    /** Every hand-off failure the client passed to its presenter, in order. */
    private val announced = mutableListOf<HandoffFailure>()

    /**
     * The clock the main-frame branch reads before arming a callback window. The
     * stub `android.jar` throws, and the catch below the launch would turn that
     * into a failure that never happened.
     */
    private val launchedAt = 1_700_222L

    private fun request(
        scheme: String,
        address: String,
        fromMainFrame: Boolean,
        withGesture: Boolean,
    ): WebResourceRequest {
        val uri = mockk<Uri>(relaxed = true)
        every { uri.scheme } returns scheme
        // No host on these addresses, so `isLocalhost` answers false without the
        // port being consulted and every case here reaches the hand-off.
        every { uri.host } returns null
        every { uri.toString() } returns address
        val req = mockk<WebResourceRequest>(relaxed = true)
        every { req.url } returns uri
        // Both stated rather than left to the relaxed mock, which answers false
        // for each and would quietly make every case a silent one.
        every { req.isForMainFrame } returns fromMainFrame
        every { req.hasGesture() } returns withGesture
        return req
    }

    @BeforeEach
    fun setUp() {
        announced.clear()
        mockkObject(Logger)
        every { Logger.i(any(), any()) } just Runs
        every { Logger.e(any(), any(), any()) } just Runs
        every { Logger.d(any(), any()) } just Runs

        mockkConstructor(Intent::class)
        every { anyConstructed<Intent>().addFlags(any()) } returns mockk(relaxed = true)

        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } returns launchedAt

        context = mockk(relaxed = true)
        view = mockk(relaxed = true)
        every { view.context } returns context
        // The case this whole file is about: nothing on the device answers the
        // scheme, so the launch throws and the catch runs.
        every { context.startActivity(any()) } throws ActivityNotFoundException("no handler")

        client = VSCodroidWebViewClient(
            allowedPort = ALLOWED_PORT,
            resourceRoots = emptyList(),
            sensitiveLocations = emptyList(),
            openFolder = { null },
            connectionToken = { null },
            onCrash = {},
            onPageLoaded = {},
            onRetryServer = {},
            onHandoffFailed = { uri, error ->
                announced += HandoffFailure(uri.scheme ?: "external", error.javaClass.simpleName)
            },
        )
    }

    @AfterEach
    fun releaseMocks() {
        unmockkAll()
    }

    /**
     * The defect, driven end to end: a source this app does not vouch for, given
     * no user gesture, may not produce a message per navigation.
     *
     * The addresses differ on purpose. A gate that let these through would be
     * caught by any of them; what the varying address adds is that a throttle
     * keyed on the URL would not have helped here either, since each navigation
     * would mint a fresh key while producing the identical sentence.
     */
    @Test
    fun `a script-driven subframe cannot drive a notice per navigation`() {
        repeat(20) { i ->
            client.shouldOverrideUrlLoading(
                view,
                request("ssh", "ssh://git@example.com/repo$i.git", false, withGesture = false),
            )
        }

        assertTrue(
            announced.isEmpty(),
            "a page in an iframe drove $announced. Each one is about three and a half " +
                "seconds of toast over a live editor, and toasts stack rather than replace",
        )
    }

    /**
     * The launch is not what is gated, and this is the case that says so.
     *
     * A link in a markdown preview or in the simple browser still leaves for a
     * browser. Withholding the launch as well would be a destination filter on
     * frame identity, which is a different decision and not this one;
     * `ExternalUrlHandoffTest` pins the same property for a sign-in address.
     */
    @Test
    fun `a subframe navigation the notice is withheld from still leaves for a browser`() {
        val handled = client.shouldOverrideUrlLoading(
            view, request("ssh", "ssh://git@example.com/repo.git", false, withGesture = false)
        )

        assertTrue(handled, "the WebView was left to navigate to a scheme it cannot load")
        verify(exactly = 1) { context.startActivity(any()) }
        assertTrue(announced.isEmpty(), "the silent case announced $announced")
    }

    /**
     * The control that keeps the two cases above from passing on a channel that is
     * simply dead.
     *
     * The main frame is the workbench, the one document here this app serves, and
     * it is where both routes this channel backs up navigate. It keeps its notice
     * whether or not the gesture bit survived the workbench's opener chain, which
     * is the case the notice was added for: a sign-in or a clone link that no app
     * answers, where the WebView does not navigate either and the tap otherwise
     * did nothing and said nothing.
     */
    @Test
    fun `a main-frame hand-off is still announced`() {
        client.shouldOverrideUrlLoading(
            view, request("ssh", "ssh://git@example.com/repo.git", true, withGesture = false)
        )

        assertEquals(listOf(HandoffFailure("ssh", "ActivityNotFoundException")), announced)
    }

    /**
     * The other control, and the one that makes the gate a question about the user
     * rather than about frames.
     *
     * A link the user actually tapped inside a preview is something they did, and
     * `hasGesture()` is the request's own record of it. Silencing that would take
     * the notice away from a real tap on a real link, which is the same silence
     * this channel exists to end.
     */
    @Test
    fun `a link the user tapped in a subframe is still announced`() {
        client.shouldOverrideUrlLoading(
            view, request("ssh", "ssh://git@example.com/repo.git", false, withGesture = true)
        )

        assertEquals(listOf(HandoffFailure("ssh", "ActivityNotFoundException")), announced)
    }

    /**
     * The gate is not the whole answer, which is why the throttle exists too.
     *
     * A page may hold one gesture per link, and every one of them is attributable.
     * Ten taps on ten different `ssh:` addresses are one fact and one sentence,
     * because the message names the scheme and never the address.
     */
    @Test
    fun `many attributable failures of one kind are one message`() {
        val said = mutableSetOf<HandoffFailure>()
        val shown = mutableListOf<HandoffFailure>()

        repeat(10) { i ->
            client.shouldOverrideUrlLoading(
                view,
                request("ssh", "ssh://git@example.com/repo-$i.git", false, withGesture = true),
            )
        }
        announced.forEach { failure ->
            handoffFailureToAnnounce(failure, said)?.let { shown += it }
        }

        assertEquals(10, announced.size, "the gate let fewer through than this case needs")
        assertEquals(
            listOf(HandoffFailure("ssh", "ActivityNotFoundException")), shown,
            "one scheme failing one way is one sentence, however many addresses carry it",
        )
    }

    /** One page failing the same way twice is one message. */
    @Test
    fun `the same failure twice is announced once`() {
        val said = mutableSetOf<HandoffFailure>()
        val failure = HandoffFailure("ssh", "ActivityNotFoundException")

        assertEquals(failure, handoffFailureToAnnounce(failure, said))
        assertNull(
            handoffFailureToAnnounce(failure, said),
            "a second link on the same scheme adds nothing the user can act on",
        )
    }

    /**
     * The half that makes the rule above a filter rather than a mute.
     *
     * Keyed on the scheme and the failure type together, which is exactly what the
     * two message forms print: a different scheme needs a different app installed,
     * and the same scheme failing a different way is quoted by type for a bug
     * report rather than as something to install.
     */
    @Test
    fun `a different scheme or a different failure type is still announced`() {
        val said = mutableSetOf<HandoffFailure>()
        handoffFailureToAnnounce(HandoffFailure("ssh", "ActivityNotFoundException"), said)

        assertEquals(
            HandoffFailure("git", "ActivityNotFoundException"),
            handoffFailureToAnnounce(HandoffFailure("git", "ActivityNotFoundException"), said),
            "a second scheme is a fact the user has not been told",
        )
        assertEquals(
            HandoffFailure("ssh", "SecurityException"),
            handoffFailureToAnnounce(HandoffFailure("ssh", "SecurityException"), said),
            "the same scheme failing a different way needs a different answer from the reader",
        )
    }

    /**
     * Neither the record nor the number of toasts can grow without bound.
     *
     * The schemes are chosen by whatever page is open, so that page picks the size
     * of the set and the number of messages alike. Clearing the set at the cap
     * answered only the first: every fresh scheme was a fact never told before and
     * got its own toast, and the clear also handed back the ones already announced.
     *
     * Driven with far more distinct schemes than the cap, because at or just past
     * it the two rules agree and the case would pass either way.
     */
    @Test
    fun `the number of messages is bounded, not only the record`() {
        val said = mutableSetOf<HandoffFailure>()
        val shown = mutableListOf<HandoffFailure>()
        val first = HandoffFailure("scheme0", "ActivityNotFoundException")

        for (i in 0 until MAX_HANDOFF_FAILURES_ANNOUNCED * 4) {
            handoffFailureToAnnounce(
                HandoffFailure("scheme$i", "ActivityNotFoundException"), said,
            )?.let { shown += it }
        }

        assertEquals(
            MAX_HANDOFF_FAILURES_ANNOUNCED, shown.size,
            "a page inventing schemes got a toast for each one, and every toast holds " +
                "the bottom of the editor for about three and a half seconds",
        )
        assertEquals(
            first, shown.first(),
            "the messages that got through are the first ones, which is what makes the " +
                "count above a cap rather than a mute that started somewhere else",
        )
        assertNull(
            handoffFailureToAnnounce(first, said),
            "a failure already announced was announced a second time, so the record was " +
                "forgotten rather than closed",
        )
        assertTrue(
            said.size <= MAX_HANDOFF_FAILURES_ANNOUNCED,
            "the record grew past the cap on schemes a page chooses: ${said.size}",
        )
    }

    /**
     * The presenter is the only owner of the record, so an unwired presenter is a
     * throttle that throttles nothing while every case above stays green.
     *
     * Read from the source rather than driven through the Activity, which needs a
     * device. ⚠️ Its ceiling, the same one `WriteBackNoticeWiringTest` records:
     * this catches the call being deleted, not the branch being made unreachable.
     * Anchored at the start of a line so that commenting the call out, which is how
     * a developer disables something, does not still satisfy it.
     */
    @Test
    fun `MainActivity puts a hand-off failure through the throttle`() {
        val activity = File("src/main/kotlin/com/vscodroid/MainActivity.kt")
        assertTrue(activity.isFile, "MainActivity.kt is not where this test expects it")

        assertTrue(
            Regex("""(?m)^[^/\n]*handoffFailureToAnnounce\(""").containsMatchIn(activity.readText()),
            "nothing in MainActivity consults the record of what has already been said, so " +
                "every hand-off failure reaches the screen as its own toast",
        )
    }
}
