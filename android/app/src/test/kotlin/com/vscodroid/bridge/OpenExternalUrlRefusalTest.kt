package com.vscodroid.bridge

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
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File

/**
 * That [AndroidBridge.openExternalUrl] answers whether it opened anything.
 *
 * [UrlAllowlistWiringTest] pins the decision; nothing pinned what the caller is
 * told about it. The method returned Unit, so a refusal and a launch were the
 * same event from outside: the relay in `MainActivity.injectBridgeRelay` posted
 * `ok: true` either way, and the bundled extension's "Open in Browser" resolved
 * its promise with no browser and no message. That is the same shape this
 * project keeps hitting -- the predicate is covered, the wiring is not -- and
 * the wiring here is the report itself.
 *
 * ## Why every case below asserts false
 *
 * A completed launch cannot be reached in a JVM test. The android.jar stubs are
 * not configured with `returnDefaultValues`, so `Uri.parse` and the `Intent`
 * constructor throw rather than answer, and the catch turns that into false. So
 * `false` alone cannot tell "refused at the guard" from "tried and could not".
 *
 * `Uri.parse` is therefore stubbed to throw a named exception, which makes
 * *whether it was called at all* the discriminator. A URL refused by the
 * allow-list returns before the try and never reaches it; an allowed one gets
 * past both guards and does. That is the positive control: without it, a method
 * rewritten to `return false` unconditionally would satisfy every assertFalse
 * here and the suite would still be green.
 */
class OpenExternalUrlRefusalTest {

    /**
     * The real one, not a mock. Its allow-list is the decision under test and
     * its token is the one the bridge will accept, so nothing about the outcome
     * is stubbed into existence.
     */
    private val security = SecurityManager()

    private lateinit var context: Context
    private lateinit var bridge: AndroidBridge

    /**
     * What [AuthTabWindow] held before this class ran.
     *
     * It is an object, this suite runs in one JVM, and one test here writes to
     * it. Restoring means whatever runs next reads what it would have read had
     * this class not existed -- mockk's own cleanup covers mocks, not the state
     * a test wrote through a real API.
     */
    private var authWindowBefore = 0L

    private companion object {
        /** Named so a failure says the launch was attempted, not that something broke. */
        const val UNREACHABLE = "a browser launch is not reachable from a JVM test"

        /** A dev server on the LAN: exactly what "Serve on Network" hands a user. */
        const val LAN = "http://192.168.1.5:3000"
        const val LOCAL = "http://localhost:3000"

        /** An https URL, which is the branch that arms the auth callback window. */
        const val REMOTE = "https://github.com/login/oauth/authorize"
    }

    @AfterEach
    fun restoreAuthWindow() {
        AuthTabWindow.disarm(authWindowBefore)
    }

    @BeforeEach
    fun setUp() {
        authWindowBefore = AuthTabWindow.openedAt()
        mockkObject(Logger)
        every { Logger.w(any(), any()) } just Runs
        every { Logger.e(any(), any(), any()) } just Runs

        mockkStatic(Uri::class)
        every { Uri.parse(any()) } throws IllegalStateException(UNREACHABLE)

        context = mockk(relaxed = true)
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
    fun `a LAN dev server is refused, and the refusal is reported to the caller`() {
        assertFalse(
            bridge.openExternalUrl(LAN, security.getSessionToken()),
            "$LAN is not on the allow-list, and the caller has to be told so -- returning " +
                "Unit here is what let the relay answer ok:true for a URL nothing opened",
        )
        verify(exactly = 0) {
            Uri.parse(any())
        }
    }

    @Test
    fun `an allowed address gets past both guards`() {
        // The positive control, and the only reason the assertions above mean
        // anything. It still returns false -- the launch cannot complete here --
        // so the load-bearing assertion is the verify, not the assertFalse.
        assertTrue(
            security.isAllowedUrl(LOCAL),
            "sanity: $LOCAL must be on the allow-list, or the test below proves nothing",
        )
        assertFalse(
            bridge.openExternalUrl(LOCAL, security.getSessionToken()),
            "no browser can be launched from a JVM test, so this is false for a reason " +
                "the verify below distinguishes from a refusal",
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
     * it does not survive. Nothing about the decision is stubbed: the allow-list
     * and the token are still the real ones, and what is faked is only the
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

        assertTrue(
            bridge.openExternalUrl(LOCAL, security.getSessionToken()),
            "a URL that was handed to an activity must report true; the relay posts ok:false " +
                "on anything else, so a false here puts an error in front of every successful open",
        )
        verify(exactly = 1) { context.startActivity(any()) }
    }

    /**
     * A launch that threw must not leave the sign-in callback window armed.
     *
     * `AuthTabWindow.opened()` is called before `launchUrl` on purpose -- a
     * browser that answers instantly could otherwise return before the window
     * it needs is open -- so a launch that then fails had already widened it.
     * For ten minutes after that, `vscodroid://callback` is accepted from
     * anything on the device, through an exported BROWSABLE filter, for a
     * sign-in nobody started. Nothing could tell before, because the method
     * could not report that the launch had failed.
     *
     * The launch itself is not stubbed: the https branch reaches
     * `CustomTabsIntent`, whose Intent work throws from android.jar's stub,
     * and that throw is exactly the failure being modelled.
     *
     * `SystemClock.elapsedRealtime()` must be, though, and leaving it out is
     * how this test first passed against the bug. It is an android.jar stub
     * that throws, and it is evaluated as the argument to `opened()` -- so the
     * window was never armed, `openedAt()` was still the value this test had
     * just written, and removing the rollback changed nothing. A stubbed clock
     * is what makes the arming real and the assertion able to fail.
     */
    @Test
    fun `a launch that fails puts the auth callback window back`() {
        val before = 4_242L
        val armedAt = 999_999L
        AuthTabWindow.opened(before)

        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } returns armedAt

        val remote = mockk<Uri>(relaxed = true)
        every { remote.host } returns "github.com"
        every { remote.scheme } returns "https"
        every { Uri.parse(REMOTE) } returns remote

        // The control this test needed and did not have. Every assertion below is
        // also satisfied by a run where the window was never armed at all, which
        // is what happens the moment the clock stub stops applying -- the clock is
        // read as the argument to opened(), so a throw lands before the write and
        // leaves the value this test just set. Proving the stub answers, and that
        // the arming line reached it, is what separates "rolled back" from
        // "never happened".
        assertEquals(
            armedAt, SystemClock.elapsedRealtime(),
            "the clock stub is not in force, so nothing below can tell a rollback from a " +
                "launch that never armed the window",
        )

        assertFalse(
            bridge.openExternalUrl(REMOTE, security.getSessionToken()),
            "no Custom Tab can be launched from a JVM test, so this must report failure",
        )
        verify(atLeast = 1) { SystemClock.elapsedRealtime() }
        assertEquals(
            before, AuthTabWindow.openedAt(),
            "a launch that threw left the callback window armed. For the next ten minutes any " +
                "app on the device can post a vscodroid://callback that this app will accept, " +
                "with no sign-in in flight to justify it.",
        )
    }

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
     * happen. It also strands the rollback: `armedFrom` would stay null and the
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

        val arm = lines.indexOfFirst { it.contains("AuthTabWindow.opened(") }
        val launch = lines.indexOfFirst { it.contains("launchUrl(") }
        assertTrue(arm >= 0, "the auth window is no longer armed anywhere in AndroidBridge.kt")
        assertTrue(launch >= 0, "no browser launch found in AndroidBridge.kt")

        assertTrue(
            arm < launch,
            "AuthTabWindow.opened() must precede launchUrl(): the launch hands off to another " +
                "process, and a browser that returns instantly would otherwise arrive before " +
                "the window it is judged against exists. A failed launch is handled by the " +
                "rollback in the catch, not by arming later — arming later also makes that " +
                "rollback unreachable.",
        )
    }

    @Test
    fun `a rejected token is refused before the URL is even looked at`() {
        assertFalse(
            bridge.openExternalUrl(LOCAL, "not the session token"),
            "an allowed URL with a bad token must still be refused",
        )
        verify(exactly = 0) {
            Uri.parse(any())
        }
    }
}
