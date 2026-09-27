package com.vscodroid.keyboard

import android.view.KeyEvent
import android.webkit.WebView
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Which presses are typed, which navigation keys are pressed, and which are
 * announced.
 *
 * Measured on a device before this split existed: tapping `{}` or `()` on the
 * key row logged a press and inserted nothing, while Tab indented and the soft
 * keyboard typed normally. A synthetic `KeyboardEvent` is untrusted, so the
 * browser runs the page's listeners and performs no default action for it. That
 * is exactly right for a command (Tab, Escape, Ctrl+P) and useless for a
 * character, which needs the browser's own text input path.
 *
 * The two edit paths are why this cannot be patched in JavaScript. On API 33 /
 * WebView 109 the workbench has one `textarea.inputarea` and nothing else; on
 * API 37 / WebView 150 `EditContext` is supported, there is no textarea in the
 * DOM at all, and two `div.native-edit-context` elements instead. A real key
 * press enters above both.
 *
 * The layout lookup arrives through the constructor so these run on the JVM:
 * `KeyCharacterMap` and `KeyEvent` are android.jar stubs that throw when called.
 */
class KeyInjectorTextEntryTest {

    private val webView = mockk<WebView>(relaxed = true)
    private val down = mockk<KeyEvent>(relaxed = true)
    private val up = mockk<KeyEvent>(relaxed = true)

    /** Every string the injector asked the layout to type. */
    private val asked = mutableListOf<String>()

    /** Every navigation press the injector built: key code, scan code, meta state. */
    private val pressed = mutableListOf<Triple<Int, Int, Int>>()

    @BeforeEach
    fun setUp() {
        // Logger.w is not gated on debugEnabled, and the fallback case below
        // reaches it. Log is an android.jar stub that throws otherwise.
        mockkStatic(android.util.Log::class)
        every { android.util.Log.w(any(), any<String>()) } returns 0
        every { android.util.Log.d(any(), any()) } returns 0
        every { webView.dispatchKeyEvent(any()) } returns true
    }

    private fun injector(events: List<KeyEvent>? = listOf(down, up)) =
        KeyInjector(
            webView,
            keyEventsFor = { text -> asked.add(text); events },
            navigationEventsFor = { keyCode, scanCode, metaState ->
                pressed.add(Triple(keyCode, scanCode, metaState))
                listOf(down, up)
            },
        )

    @Test
    fun `a bracket is typed as a key press, not announced as a DOM event`() {
        injector().injectKey("{")

        assertEquals(listOf("{"), asked, "the layout was never asked what to press for '{'")
        verify(exactly = 1) { webView.dispatchKeyEvent(down) }
        verify(exactly = 1) { webView.dispatchKeyEvent(up) }
        verify(exactly = 0) { webView.evaluateJavascript(any(), any()) }
    }

    @Test
    fun `Tab, Escape and the function keys are still announced as DOM events`() {
        // A real Tab moves focus, and the Explorer's rename and New File boxes
        // commit the typed name when they lose it. A real Escape blurs the
        // focused element once a real arrow has turned on spatial navigation.
        for (key in listOf("Tab", "Escape", "F5")) injector().injectKey(key)

        assertTrue(asked.isEmpty(), "these are commands, not characters; the layout must not be asked to type them")
        assertTrue(pressed.isEmpty(), "a command key was pressed as a real key: $pressed")
        verify(exactly = 0) { webView.dispatchKeyEvent(any()) }
        verify(exactly = 3) { webView.evaluateJavascript(any(), any()) }
    }

    @Test
    fun `a trackpad arrow is pressed as a real key with the latched modifiers as meta state`() {
        // An announced arrow moves nothing in a text box and never reaches a
        // frame; a real one does both. Shift has to ride along as meta state, or
        // a Shift-drag stops selecting.
        injector().injectKey("ArrowLeft", shiftKey = true)

        assertEquals(
            listOf(Triple(KeyEvent.KEYCODE_DPAD_LEFT, 105, KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON)),
            pressed,
        )
        verify(exactly = 1) { webView.dispatchKeyEvent(down) }
        verify(exactly = 1) { webView.dispatchKeyEvent(up) }
        verify(exactly = 0) { webView.evaluateJavascript(any(), any()) }
    }

    @Test
    fun `Ctrl on the row reaches a navigation key as meta state and nothing else does`() {
        injector().injectKey("ArrowRight", ctrlKey = true)

        val meta = pressed.single().third
        assertEquals(KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON, meta, "Ctrl+arrow lost or gained a modifier")
        verify(exactly = 0) { webView.evaluateJavascript(any(), any()) }
    }

    @Test
    fun `navigation keys carry the scan code Chromium turns into KeyboardEvent code`() {
        // Linux evdev codes, which is the column Chromium's Android key code
        // conversion reads. A zero here delivers the key with code "".
        assertEquals(
            mapOf(
                "ArrowUp" to 103, "ArrowLeft" to 105, "ArrowRight" to 106, "ArrowDown" to 108,
                "Home" to 102, "End" to 107, "PageUp" to 104, "PageDown" to 109,
            ),
            NAVIGATION_KEYS.mapValues { it.value.second },
        )
        // MOVE_HOME is 122. KEYCODE_HOME, 3, is the system Home button.
        assertEquals(122, NAVIGATION_KEYS.getValue("Home").first)
        assertEquals(123, NAVIGATION_KEYS.getValue("End").first)
    }

    @Test
    fun `the navigation keys are the trackpad's arrows and the row's Home, End, PgUp and PgDn`() {
        // Derived from the row, so Tab, Escape and the function keys, which are
        // all on it, fail the second assertion if one is ever added.
        val row = KeyPages.defaults.flatMap { it.items }
            .filterIsInstance<KeyItem.Button>()
            .flatMap { button -> listOf(button.value) + button.alternates.map { it.value } }
            .toSet()
        assertEquals(ARROW_ACTIONS.map { it.second }.toSet(), NAVIGATION_KEYS.keys - row)
        assertEquals(setOf("Home", "End", "PageUp", "PageDown"), NAVIGATION_KEYS.keys.intersect(row))
    }

    @Test
    fun `a navigation press the WebView refuses falls back to the DOM event`() {
        every { webView.dispatchKeyEvent(any()) } returns false

        injector().injectKey("ArrowDown")

        assertEquals(1, pressed.size, "the press was never tried, so this is not the refusal path")
        verify(exactly = 1) { webView.evaluateJavascript(any(), any()) }
    }

    @Test
    fun `a modifier latched on the row keeps a character on the DOM path`() {
        injector().injectKey("p", ctrlKey = true)

        assertTrue(asked.isEmpty(), "Ctrl+P is a chord, and a chord is resolved by the workbench, not typed")
        verify(exactly = 0) { webView.dispatchKeyEvent(any()) }
        verify(exactly = 1) { webView.evaluateJavascript(any(), any()) }
    }

    @Test
    fun `a latched Shift types the shifted character, not the base one`() {
        // The row has no `?` on any page, so Shift plus `/` is its only route to
        // one. Typing `/` here would be worse than the defect this class exists
        // to fix: before, a latched Shift inserted nothing; getting the
        // unshifted character back is confidently wrong output.
        injector().injectKey("/", shiftKey = true)

        assertEquals(listOf("?"), asked, "Shift plus / must ask the layout for ?, not /")
        verify(exactly = 2) { webView.dispatchKeyEvent(any()) }
        verify(exactly = 0) { webView.evaluateJavascript(any(), any()) }
    }

    @Test
    fun `a latched Shift on an already shifted character types that character`() {
        // `{` is itself the shifted form of `[`, so a latched Shift must not
        // resolve it a second time into something else.
        injector().injectKey("{", shiftKey = true)

        assertEquals(listOf("{"), asked, "the shifted form of { is { itself")
        verify(exactly = 2) { webView.dispatchKeyEvent(any()) }
        verify(exactly = 0) { webView.evaluateJavascript(any(), any()) }
    }

    @Test
    fun `a latched Shift on a key with no shifted form still announces the chord`() {
        // Shift+Tab is a real chord the workbench resolves; it is not text.
        injector().injectKey("Tab", shiftKey = true)

        assertTrue(asked.isEmpty(), "Tab is a command; the layout must not be asked to type it")
        verify(exactly = 0) { webView.dispatchKeyEvent(any()) }
        verify(exactly = 1) { webView.evaluateJavascript(any(), any()) }
    }

    @Test
    fun `a character the layout cannot press falls back to the DOM event`() {
        // This pins the fallback, not a cure. The DOM path inserts no text, so
        // such a character still does not type; what the fallback buys is that
        // the page sees the keystroke instead of the key vanishing. The warning
        // logged on this path is the only way to find out it happened.
        injector(events = null).injectKey(";")

        assertEquals(listOf(";"), asked, "the layout was asked, so this is the fallback and not a routing regression")
        verify(exactly = 0) { webView.dispatchKeyEvent(any()) }
        verify(exactly = 1) { webView.evaluateJavascript(any(), any()) }
    }

    @Test
    fun `a press the WebView refuses falls back instead of vanishing`() {
        // dispatchKeyEvent returns false when the view cannot consume the event,
        // for instance while the renderer is being rebuilt after a crash or the
        // WebView is detached during a folder switch. Reporting that as typed
        // swallows the key with nothing on screen and nothing in a release log.
        every { webView.dispatchKeyEvent(any()) } returns false

        injector().injectKey("{")

        assertEquals(listOf("{"), asked, "the layout was asked, so this is the refusal path")
        verify(exactly = 1) { webView.evaluateJavascript(any(), any()) }
    }

    @Test
    fun `an announced keystroke asks for no answer on a build that logs nothing`() {
        // A callback makes the renderer serialize the script's return value back
        // across the process boundary, and the only thing that reads it is
        // Logger.d, which is gated on a debuggable build.
        //
        // Logger.debugEnabled is false here because Logger.init is never called
        // off a device, which is also what a release APK reports.
        injector().injectKey("Tab")

        verify(exactly = 1) { webView.evaluateJavascript(any(), isNull()) }
    }

    @Test
    fun `every key on the row is routed by whether it is a character`() {
        // Driven from the row itself, so a key added to KeyPages later is
        // covered without anyone remembering this file. The three modifiers
        // never reach the injector: ExtraKeyRow.handleKeyAction consumes them.
        val modifiers = setOf("Ctrl", "Alt", "Shift")
        val values = KeyPages.defaults.flatMap { it.items }
            .filterIsInstance<KeyItem.Button>()
            .flatMap { button -> listOf(button.value) + button.alternates.map { it.value } }
            .filterNot { it in modifiers }
        check(values.size > 30) { "the key row came back nearly empty; this test would prove nothing" }

        for (value in values) {
            assertEquals(
                value.length == 1,
                isTextEntry(value, ctrlKey = false, altKey = false, metaKey = false),
                "'$value' is routed the wrong way: a single character is typed, a named key is announced",
            )
        }
    }
}
