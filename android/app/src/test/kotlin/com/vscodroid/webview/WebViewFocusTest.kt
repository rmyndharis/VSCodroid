package com.vscodroid.webview

import android.view.MotionEvent
import android.view.View
import android.webkit.WebView
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The editor cannot be typed into unless the WebView holds Android view focus.
 *
 * Measured on two emulators (WebView 109 and 150) and reported from a real
 * device: after launch the input method serves the DecorView through a fallback
 * connection, so everything the keyboard produces is discarded, and the
 * keyboard itself never rises because the show request comes from a view that
 * is not focused. A real tap moves the caret (Blink-side focus works) but
 * never grants the Android view focus, and nothing in this app requested it.
 * Chromium's own DevTools click path and a d-pad key both granted it, and with
 * focus held, the same keyboard committed text immediately.
 *
 * So [VSCodroidWebView.configure] owns two duties beyond settings: request
 * focus once up front, and keep granting it on every touch-down thereafter,
 * without consuming the touch. `configure` is the right home because both the
 * launch path and the post-crash `recreateWebView` path go through it.
 */
class WebViewFocusTest {

    private val webView = mockk<WebView>(relaxed = true)

    @BeforeEach
    fun setUp() {
        // configure() logs through android.util.Log, an android.jar stub that
        // throws on the JVM.
        mockkStatic(android.util.Log::class)
        every { android.util.Log.i(any(), any<String>()) } returns 0
        every { android.util.Log.d(any(), any()) } returns 0
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    @Test
    fun `configure makes the WebView focusable in touch mode and requests focus`() {
        VSCodroidWebView.configure(webView)

        verify { webView.isFocusable = true }
        verify { webView.isFocusableInTouchMode = true }
        verify(exactly = 1) { webView.requestFocus() }
    }

    @Test
    fun `a touch-down on an unfocused WebView requests focus and is not consumed`() {
        val listener = slot<View.OnTouchListener>()
        VSCodroidWebView.configure(webView)
        verify { webView.setOnTouchListener(capture(listener)) }

        val down = mockk<MotionEvent> { every { actionMasked } returns MotionEvent.ACTION_DOWN }
        every { webView.isFocused } returns false

        val consumed = listener.captured.onTouch(webView, down)

        assertFalse(consumed, "consuming the touch would break scrolling and taps")
        verify(exactly = 2) { webView.requestFocus() }
    }

    @Test
    fun `a touch-down on an already focused WebView does not re-request focus`() {
        val listener = slot<View.OnTouchListener>()
        VSCodroidWebView.configure(webView)
        verify { webView.setOnTouchListener(capture(listener)) }

        val down = mockk<MotionEvent> { every { actionMasked } returns MotionEvent.ACTION_DOWN }
        every { webView.isFocused } returns true

        val consumed = listener.captured.onTouch(webView, down)

        assertFalse(consumed)
        // Once from configure itself, none from the listener.
        verify(exactly = 1) { webView.requestFocus() }
    }

    @Test
    fun `the WebView touch listener has exactly one owner`() {
        // setOnTouchListener is a single setter: a second caller anywhere would
        // replace the focus-granting listener and kill this fix with every
        // other test still green, because the replacement looks harmless to its
        // author. ExtraKeyButton installs one too, on its own Button view, which
        // is a different view and cannot displace this one.
        // A count per file, not a set of file names: a set stays unchanged when
        // a SECOND call is added inside an already-listed file, and that is the
        // likeliest edit of all, because whoever adds gesture handling will put
        // it in the file that already has a touch listener. The count is the
        // quantity actually guarded: how many listeners are installed.
        //
        // What this cannot see, stated rather than closed: Java sources, XML
        // android:onTouch, a listener installed through some other API, a
        // single-line block comment naming the method (only `//` and `*`
        // prefixed lines are dropped; KDoc bodies are `*` lines, so today the
        // only surviving mentions are the two real calls), and a string
        // literal, which counts. Closing any of these needs a parser and is
        // not worth it; a false red here reads as "look at this scan", which
        // is the safe direction.
        // Comment lines are dropped before counting, because the call site's
        // own comment names the method in prose; the guarded quantity is
        // installed listeners, not mentions.
        val owners = File("src/main/kotlin")
            .walkTopDown()
            .filter { it.extension == "kt" }
            .associate { file ->
                file.name to file.readLines()
                    .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
                    .count { Regex("\\bsetOnTouchListener\\b").containsMatchIn(it) }
            }
            .filterValues { it > 0 }
            .toSortedMap()

        assertEquals(
            sortedMapOf("ExtraKeyButton.kt" to 1, "VSCodroidWebView.kt" to 1),
            owners,
            "a setOnTouchListener call appeared or multiplied; a second call on " +
                "the WebView silently replaces the focus listener, so extend the " +
                "one in VSCodroidWebView.configure instead, then update this map",
        )
    }

    @Test
    fun `a move event does not touch focus at all`() {
        val listener = slot<View.OnTouchListener>()
        VSCodroidWebView.configure(webView)
        verify { webView.setOnTouchListener(capture(listener)) }

        val move = mockk<MotionEvent> { every { actionMasked } returns MotionEvent.ACTION_MOVE }
        every { webView.isFocused } returns false

        val consumed = listener.captured.onTouch(webView, move)

        assertFalse(consumed)
        verify(exactly = 1) { webView.requestFocus() }
    }
}
