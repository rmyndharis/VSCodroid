package com.vscodroid.bridge

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import com.vscodroid.storage.SafStorageManager
import com.vscodroid.util.Logger
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File

/**
 * That [AndroidBridge.openExternalUrl] answers whether it opened anything.
 *
 * Which URLs may be opened is a separate question, pinned separately and being
 * settled elsewhere; nothing pinned what the caller is TOLD about the outcome.
 * The method returned Unit, so a refusal and a launch were the same event from
 * outside: the relay in `MainActivity.injectBridgeRelay` posted
 * `ok: true` either way, and the bundled extension's "Open in Browser" resolved
 * its promise with no browser and no message. That is the same shape this
 * project keeps hitting -- the predicate is covered, the wiring is not -- and
 * the wiring here is the report itself.
 *
 * ## Why most cases here assert false, and what makes that mean anything
 *
 * A launch cannot complete on this classpath unless it is faked: the android.jar
 * stubs are not configured with `returnDefaultValues`, so `Uri.parse` and the
 * `Intent` methods throw rather than answer, and the catch turns that into
 * false. So `false` alone cannot tell "refused at the guard" from "tried and
 * could not".
 *
 * Each test therefore carries a discriminator rather than resting on the return:
 *
 *  - `Uri.parse` is stubbed to throw, so *whether it was called at all* separates
 *    a guard refusal from a failed launch
 *  - the success case fakes the Intent work far enough to reach `startActivity`,
 *    and asserts true -- the half every assertFalse is blind to
 *  - the rollback case spies on `AuthTabWindow` and asserts the two calls in
 *    order, because the final reading is identical whether the window was armed
 *    and rolled back or never armed at all
 *
 * That last one took three attempts. A control asserting the clock stub answered,
 * and then a `verify` on the clock, were both satisfied without the production
 * code doing anything -- the second because it counted this test's own call.
 * Deleting the arming line left the class green both times.
 */
class OpenExternalUrlRefusalTest {

    /**
     * The real one, not a mock: its token is the one the bridge will accept, so
     * nothing about the outcome is stubbed into existence. Deliberately says
     * nothing about which URLs it permits -- that policy is not this class's
     * subject, and every case here is chosen to hold whatever it is.
     */
    private val security = SecurityManager()

    private lateinit var context: Context
    private lateinit var bridge: AndroidBridge

    private companion object {
        /** Named so a failure says the launch was attempted, not that something broke. */
        const val UNREACHABLE = "a browser launch is not reachable from a JVM test"

        /**
         * What the stubbed resources answer, one recognisable sentinel each.
         *
         * The refusals are string resources now, so a `Context` is what turns an id
         * into a sentence and the test has to say what its own Context returns.
         * Distinct values, because two refusals answering with the same text would
         * let a method that always returns the wrong one satisfy both cases.
         */
        const val STALE = "stale-session-resource"
        const val NO_HANDLER = "no-handler-resource"
        const val FAILED_OTHER = "launch-failed-resource"

        const val LOCAL = "http://localhost:3000"

        /**
         * An https sign-in address, which is the branch that goes through Custom
         * Tabs. It carries a request id because that, and not the fact that a
         * browser opened, is what arms the callback window: the workbench mints
         * `vscode-reqid` for the callback it is waiting on, and the address the
         * provider is given has to carry it back.
         */
        const val REMOTE =
            "https://github.com/login/oauth/authorize?client_id=abc" +
                "&state=http%253A%252F%252F127.0.0.1%253A13337%252Fcallback%253Fvscode-reqid%253D707"

        /** The id [REMOTE] carries, matched against what the window recorded. */
        const val REMOTE_REQUEST_ID = "707"

        /** A plain-http sign-in, the LAN shape that leaves through ACTION_VIEW. */
        const val REMOTE_HTTP =
            "http://192.168.1.50/signin?redirect_uri=" +
                "http%3A%2F%2F127.0.0.1%3A13337%2Fcallback%3Fvscode-reqid%3D808"

        const val REMOTE_HTTP_REQUEST_ID = "808"

        /** The secret half of [TOKEN_BEARING], kept apart so the check names it. */
        const val TOKEN_SECRET = "3f9a1c77-not-a-real-token"

        /**
         * The editor's own address, which is what the workbench hands over when a
         * user asks to open the current page in a browser, and which carries the
         * connection token as a query parameter.
         */
        const val TOKEN_BEARING = "http://127.0.0.1:41003/?folder=/home&tkn=" + TOKEN_SECRET
    }

    /**
     * [AuthTabWindow] is an object, this suite runs in one JVM, and the cases here
     * write to it through the real bridge. Handing the ids back means whatever
     * runs next reads what it would have read had this class not existed; mockk's
     * own cleanup covers mocks, not state a test wrote through a real API.
     */
    @AfterEach
    fun handBackArmedRequests() {
        AuthTabWindow.disarm(listOf(REMOTE_REQUEST_ID, REMOTE_HTTP_REQUEST_ID))
    }

    @BeforeEach
    fun setUp() {
        mockkObject(Logger)
        every { Logger.w(any(), any()) } just Runs
        every { Logger.e(any(), any(), any()) } just Runs

        mockkStatic(Uri::class)
        every { Uri.parse(any()) } throws IllegalStateException(UNREACHABLE)
        // Arming left the https branch, so the plain-http launch below reads the
        // clock too; the one test that needs a particular reading re-stubs it.
        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } returns 1L

        context = mockk(relaxed = true)
        // Stubbed rather than left to the relaxed mock, and not as bookkeeping. A
        // relaxed Context answers getString with the EMPTY STRING, which is this
        // method's answer for "it opened". Unstubbed, every refusal below would come
        // back as a success and each case would pass by agreeing with itself.
        every { context.getString(OPEN_URL_STALE_SESSION) } returns STALE
        every { context.getString(OPEN_URL_NO_HANDLER) } returns NO_HANDLER
        every { context.getString(OPEN_URL_FAILED, *anyVararg()) } returns FAILED_OTHER
        bridge = AndroidBridge(
            context = context,
            security = security,
            clipboard = mockk(relaxed = true),
            onBackPressed = mockk(relaxed = true),
            onMinimize = mockk(relaxed = true),
            onOpenFolderPicker = mockk(relaxed = true),
            onOpenRecentFolder = mockk(relaxed = true),
            onShowAbout = mockk(relaxed = true),
            safManager = mockk<SafStorageManager>(relaxed = true),
        )
    }

    @Test
    fun `a URL with a good token reaches the launch, rather than being turned away`() {
        // The counterpart to the token case at the bottom, and the reason that
        // one means anything. Both fail, because no browser can be launched from
        // a JVM test, but they no longer fail identically. While both answered
        // `false` the verify was the only thing separating "tried and could not"
        // from "turned away at the door"; the reason now says which happened, so
        // the two assertions corroborate each other instead of one carrying both.
        assertNotEquals(
            STALE,
            bridge.openExternalUrl(LOCAL, security.getSessionToken()),
            "a valid token was treated as a stale one, so the call never reached the launch",
        )
        verify(exactly = 1) {
            Uri.parse(LOCAL)
        }
    }

    /**
     * The other half of the contract, and the half every assertion above is
     * blind to: they all expect false, so a method rewritten to refuse
     * everything satisfies them. The relay's success path -- and therefore
     * every legitimate open -- depends on this `true` alone.
     *
     * Reaching it costs two more stubs. `Uri.parse` has to answer instead of
     * throwing, and the `Intent` has to be intercepted -- not because its
     * constructor throws, which under AGP's mockable android.jar it does not,
     * but because `addFlags` returns the stub's default and the chain built on
     * it does not survive. Nothing about the decision is stubbed: the real
     * SecurityManager and its real token are used, and what is faked is only the
     * platform's launch machinery, which does not exist in a JVM.
     */
    @Test
    fun `an allowed address that launches reports success`() {
        val uri = mockk<Uri>(relaxed = true)
        every { uri.host } returns "localhost"
        every { uri.scheme } returns "http"
        every { Uri.parse(LOCAL) } returns uri

        mockkConstructor(Intent::class)
        every { anyConstructed<Intent>().addFlags(any()) } returns mockk(relaxed = true)

        assertEquals(
            "", bridge.openExternalUrl(LOCAL, security.getSessionToken()),
            "a URL that was handed to an activity must come back with no reason; the relay " +
                "posts ok:false on anything else, so a reason here puts an error in front of " +
                "every successful open",
        )
        verify(exactly = 1) { context.startActivity(any()) }
    }

    /**
     * A launch that threw must not leave the sign-in callback window armed.
     *
     * `AuthTabWindow.arm()` is called before `launchUrl` on purpose -- a
     * browser that answers instantly could otherwise return before the window
     * it needs is open -- so a launch that then fails had already widened it.
     * For ten minutes after that, a `vscodroid://callback` naming that request
     * is accepted from anything on the device, through an exported BROWSABLE
     * filter, for a sign-in nobody started. Nothing could tell before, because
     * the method could not report that the launch had failed.
     *
     * The launch itself is not stubbed: the https branch reaches
     * `CustomTabsIntent`, whose Intent work throws from android.jar's stub,
     * and that throw is exactly the failure being modelled.
     *
     * `SystemClock.elapsedRealtime()` must be, though, and leaving it out is
     * how this test first passed against the bug. It is an android.jar stub
     * that throws, and it is evaluated as the argument to the arming call -- so
     * the window was never armed, the record still held whatever this test had
     * just written, and removing the rollback changed nothing. A stubbed clock
     * is what makes the arming real and the assertion able to fail.
     */
    @Test
    fun `a launch that fails puts the auth callback window back`() {
        val armedAt = 999_999L

        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } returns armedAt

        val remote = mockk<Uri>(relaxed = true)
        every { remote.host } returns "github.com"
        every { remote.scheme } returns "https"
        every { Uri.parse(REMOTE) } returns remote

        // Watched as well as read, because the two see different mutations. The
        // final state cannot tell "armed then rolled back" from "never armed",
        // and two earlier attempts at a control here were satisfied without the
        // production code doing anything -- the first because the real clock
        // throws before the arming it was meant to observe, the second because
        // `verify { SystemClock.elapsedRealtime() }` counted this test's OWN call
        // to it. Deleting the arming line outright left this test green both
        // times; only a neighbouring source-order test noticed.
        //
        // A spy on the object records the calls themselves, so neither substitute
        // is needed and neither failure mode returns. Real behaviour still runs.
        mockkObject(AuthTabWindow)

        assertNotEquals(
            "", bridge.openExternalUrl(REMOTE, security.getSessionToken()),
            "no Custom Tab can be launched from a JVM test, so this must report a reason",
        )

        verifyOrder {
            AuthTabWindow.arm(listOf(REMOTE_REQUEST_ID), armedAt)
            AuthTabWindow.disarm(listOf(REMOTE_REQUEST_ID))
        }
        assertNull(
            AuthTabWindow.armedAt(REMOTE_REQUEST_ID),
            "a launch that threw left the callback window armed. For the next ten minutes any " +
                "app on the device can post a vscodroid://callback naming that request and this " +
                "app will accept it, with no sign-in in flight to justify it.",
        )
    }

    @Test
    fun `an address with no request in it arms nothing`() {
        // The narrowing, from the outside. Opening a documentation link used to
        // widen the window an unsolicited callback is accepted in by ten minutes,
        // exactly as starting a sign-in did, because the window recorded that a
        // browser had opened rather than what it had been sent to do.
        val uri = mockk<Uri>(relaxed = true)
        every { uri.host } returns "code.visualstudio.com"
        every { uri.scheme } returns "https"
        every { Uri.parse(any()) } returns uri

        mockkObject(AuthTabWindow)

        bridge.openExternalUrl(
            "https://code.visualstudio.com/docs", security.getSessionToken()
        )

        verify(exactly = 1) { AuthTabWindow.arm(emptyList(), any()) }
    }

    /*
     * NOT TESTED HERE, and the limit is worth stating rather than leaving as an
     * absence: that a launch which SUCCEEDS leaves the window armed.
     *
     * The https branch reaches CustomTabsIntent, whose Builder calls Intent
     * methods the mockable android.jar refuses -- addFlags, then putExtra, then
     * more behind those. Faking enough of androidx to make the launch succeed
     * would be testing the fakes. What the two tests around this DO pin is the
     * decomposition: `verifyOrder` above shows the arming happens before the
     * launch and is rolled back only when the launch throws, and the source-order
     * test below shows the arming still precedes the launch at all. The success
     * case is covered on a device or not at all.
     */

    /**
     * That the window is still armed *before* the launch, which no behavioural
     * test here can see.
     *
     * Moving `opened()` below `launchUrl` is the obvious way to stop a failed
     * launch arming anything, and it looks like it works: the test above keeps
     * passing, because a launch that throws then never arms at all. What it
     * silently gives up is the reason the ordering exists -- `launchUrl` hands
     * off to another process, and a browser that answers instantly can return
     * before the window it needs is open, refusing a sign-in that really did
     * happen. It also strands the rollback: `armed` would stay empty and the
     * `disarm` call become dead code.
     *
     * The property is about ordering against another process, so there is
     * nothing in a JVM to observe. Asserted on the source instead, the way
     * `ExtensionCallbackTest` asserts that the no-workbench branch precedes the
     * timing gate in the same file.
     */
    @Test
    fun `the auth window is armed before the launch, not after`() {
        val source = File("src/main/kotlin/com/vscodroid/bridge/AndroidBridge.kt")
        check(source.isFile) { "AndroidBridge.kt not found at ${source.absolutePath}" }

        // Comment lines are dropped before looking, the way ExtensionCallbackTest
        // does it, and for a reason measured here: prose in this file names both
        // calls, and reading the file raw found a KDoc line above the real
        // arming. The order then compared two comments and was true whatever the
        // code did -- this test passed against the very mutation it exists to
        // catch until the filter was added.
        val lines = source.readLines().filterNot {
            val t = it.trimStart()
            t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
        }

        val arm = lines.indexOfFirst { it.contains("AuthTabWindow.arm(") }
        val launch = lines.indexOfFirst { it.contains("launchUrl(") }
        assertTrue(arm >= 0, "the auth window is no longer armed anywhere in AndroidBridge.kt")
        assertTrue(launch >= 0, "no browser launch found in AndroidBridge.kt")

        assertTrue(
            arm < launch,
            "AuthTabWindow.arm() must precede launchUrl(): the launch hands off to another " +
                "process, and a browser that returns instantly would otherwise arrive before " +
                "the window it is judged against exists. A failed launch is handled by the " +
                "rollback in the catch, not by arming later; arming later also makes that " +
                "rollback unreachable.",
        )
    }

    @Test
    fun `an http address opened through the system browser arms the callback window`() {
        // The Custom Tabs branch is not the only way out: plain http, the LAN
        // dev-server shape this method exists to serve, goes through ACTION_VIEW.
        // Both return by the same `vscodroid://callback`, and a window armed on
        // one branch only refuses the other's return, a sign-in against a
        // self-hosted IdP over http then hangs with nothing said, because the
        // callback is judged against a window that was never opened.
        val uri = mockk<Uri>(relaxed = true)
        every { uri.host } returns "192.168.1.50"
        every { uri.scheme } returns "http"
        every { Uri.parse(any()) } returns uri

        mockkConstructor(Intent::class)
        every { anyConstructed<Intent>().addFlags(any()) } returns mockk(relaxed = true)

        assertEquals(
            "", bridge.openExternalUrl(REMOTE_HTTP, security.getSessionToken()),
            "the system browser branch must report the launch it made"
        )
        verify(exactly = 1) { context.startActivity(any()) }
        assertNotNull(
            AuthTabWindow.armedAt(REMOTE_HTTP_REQUEST_ID),
            "every browser hand-off has to record the request it carries, or the callback " +
                "it returns with is refused"
        )
    }

    /**
     * The URL here is supplied by the page, and `Logger.e` is not gated on a
     * debuggable build, so this line ships. logcat is readable by anything holding
     * `READ_LOGS`, and the workbench that supplies the URL is also what holds the
     * connection token, so a URL it hands over can carry it. That is the same
     * reasoning `logToNative` states for redacting there.
     *
     * The message keeps the scheme and the host and nothing else, which is the
     * rule `TlsFailure` states for the same class of value: what the page hands
     * over is the page's to choose, and the query is where a sign-in puts its
     * credential.
     *
     * The throwable is asserted absent for the same reason. `ActivityNotFoundException`
     * quotes the whole Intent it could not match, so passing it to `Logger.e` would
     * print the address inside the trace right under a message that had just left
     * it out, and the containment would only look like one.
     */
    @Test
    fun `the failure log carries neither the token nor the throwable that repeats it`() {
        val logged = mutableListOf<String>()
        every { Logger.e(any(), capture(logged), any()) } just Runs

        val uri = mockk<Uri>(relaxed = true)
        every { uri.host } returns "127.0.0.1"
        every { uri.scheme } returns "http"
        every { Uri.parse(TOKEN_BEARING) } returns uri
        mockkConstructor(Intent::class)
        every { anyConstructed<Intent>().addFlags(any()) } returns mockk(relaxed = true)
        every { context.startActivity(any()) } throws
            ActivityNotFoundException("No Activity found to handle Intent { dat=" + TOKEN_BEARING + " }")

        bridge.openExternalUrl(TOKEN_BEARING, security.getSessionToken())

        assertTrue(logged.isNotEmpty(), "the failure was not logged at all, so nothing was checked")
        val line = logged.last()
        assertFalse(
            line.contains(TOKEN_SECRET),
            "the connection token was written to logcat verbatim: " + line,
        )
        assertTrue(
            line.contains("127.0.0.1:41003"),
            "the address is not named in the line at all, so this case would pass on a " +
                "line that dropped it rather than one that reduced it to its host: " + line,
        )
        assertFalse(
            line.contains("folder=") || line.contains("tkn="),
            "the line repeats the query the page chose. `redactToken` only takes out this " +
                "app's own parameter, and an address handed to a browser is routinely a " +
                "sign-in carrying an OAuth code or an API key in that same query: " + line,
        )
        // Asserted at the argument rather than by capturing it, because a captured
        // null and an argument that was never recorded look the same in a list.
        verify(exactly = 1) { Logger.e(any(), any(), null) }
    }

    /**
     * The distinction a boolean could not carry, and the whole point of answering
     * with a reason.
     *
     * Both halves below fail at the same line. While the answer was `false` the
     * relay reported both as "no app on this device handles that link", which is
     * the right advice for the first and unfollowable for the second: Android
     * refused that launch itself, and installing an app does not change its mind.
     *
     * `SecurityException` stands in for that family because it is easy to throw
     * here. The case met on a device is `FileUriExposedException`, which Android
     * raises for a `file://` URL handed to another app, and which the extension's
     * "Open in Browser" box will produce for anyone who types a path.
     */
    @Test
    fun `the reason separates a missing app from a launch Android refused`() {
        val uri = mockk<Uri>(relaxed = true)
        every { uri.host } returns "localhost"
        every { uri.scheme } returns "http"
        every { Uri.parse(LOCAL) } returns uri
        mockkConstructor(Intent::class)
        every { anyConstructed<Intent>().addFlags(any()) } returns mockk(relaxed = true)

        every { context.startActivity(any()) } throws ActivityNotFoundException("nothing handles it")

        assertEquals(
            NO_HANDLER,
            bridge.openExternalUrl(LOCAL, security.getSessionToken()),
            "nothing claimed the intent, which is the one cause the user can act on",
        )

        every { context.startActivity(any()) } throws SecurityException("refused by policy")
        val refused = bridge.openExternalUrl(LOCAL, security.getSessionToken())

        assertNotEquals(
            NO_HANDLER, refused,
            "a launch Android refused on its own terms was reported as a missing app, which " +
                "sends the user to install something that cannot help",
        )
        assertEquals(
            FAILED_OTHER, refused,
            "the reason has to come from the catch-all resource, got: " + refused,
        )
        // And carrying the class name, which is the whole of what that resource has
        // to say. Asserting on the returned text cannot show this: the stub above
        // answers the same sentence whatever it is handed.
        verify { context.getString(OPEN_URL_FAILED, "SecurityException") }
    }

    @Test
    fun `a rejected token is refused before the URL is even looked at`() {
        assertEquals(
            STALE,
            bridge.openExternalUrl(LOCAL, "not the session token"),
            "a URL that would otherwise open must still be refused with a bad token, and " +
                "the reason has to say so: blaming a missing app sends the user to install " +
                "something that cannot help",
        )
        verify(exactly = 0) {
            Uri.parse(any())
        }
    }
}
