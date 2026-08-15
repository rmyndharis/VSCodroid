package com.vscodroid.bridge

import android.content.Context
import android.net.Uri
import com.vscodroid.storage.SafStorageManager
import com.vscodroid.util.Logger
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

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

    private companion object {
        /** Named so a failure says the launch was attempted, not that something broke. */
        const val UNREACHABLE = "a browser launch is not reachable from a JVM test"

        /** A dev server on the LAN: exactly what "Serve on Network" hands a user. */
        const val LAN = "http://192.168.1.5:3000"
        const val LOCAL = "http://localhost:3000"
    }

    @BeforeEach
    fun setUp() {
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
