package com.vscodroid.keyboard

import android.view.KeyCharacterMap
import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * That [virtualKeyboardEvents] actually types what the key row asks it to.
 *
 * This is the production path for every character on the row, and nothing
 * automated ran it. `KeyInjector` takes the lookup through its constructor so the
 * routing can be checked on the JVM, which it has to be: `KeyCharacterMap` and
 * `KeyEvent` are android.jar stubs that throw off a device. Every JVM case
 * therefore injects a fake, correctly, and the real function was left to the one
 * manual measurement recorded in `KeyInjectorTextEntryTest`'s header.
 *
 * The failure that leaves is silent and has shipped once already: `{` and `(`
 * inserting nothing at all. Changing the device id, dropping the re-stamp or
 * asking a different map for the presses keeps every JVM case green.
 *
 * Not run by CI, which compiles the instrumented tests but has no emulator to run
 * them on. This is here to be run against a device when this area changes.
 */
@RunWith(AndroidJUnit4::class)
class TextEntryInstrumentedTest {

    /** Every character the row can type, from the row rather than from a list here. */
    private fun typedCharacters(): List<String> {
        val modifiers = setOf("Ctrl", "Alt", "Shift")
        return KeyPages.defaults.flatMap { it.items }
            .filterIsInstance<KeyItem.Button>()
            .flatMap { button -> listOf(button.value) + button.alternates.map { it.value } }
            .filterNot { it in modifiers }
            .filter { isTextEntry(it, ctrlKey = false, altKey = false, metaKey = false) }
            .distinct()
    }

    /**
     * The text a run of presses produces, read back through the same layout.
     *
     * `getUnicodeChar(metaState)` is what the input pipeline consults on the way
     * in, so this asks the question the WebView will ask. A modifier press
     * answers 0 and is skipped, which is how the Shift the layout inserts for `{`
     * drops out.
     */
    private fun textOf(events: List<KeyEvent>): String =
        events.filter { it.action == KeyEvent.ACTION_DOWN }
            .mapNotNull { event -> event.getUnicodeChar(event.metaState).takeIf { it != 0 }?.toChar() }
            .joinToString("")

    @Test
    fun everyCharacterOnTheRowResolvesToPressesThatTypeIt() {
        val characters = typedCharacters()
        assertTrue(
            "the key row came back with ${characters.size} characters; this test would prove nothing",
            characters.size > 20,
        )

        for (value in characters) {
            val events = virtualKeyboardEvents(value)
            assertNotNull(
                "the virtual keyboard layout has no press for '$value', so tapping that " +
                    "key falls back to a synthetic DOM event, which performs no default " +
                    "action and inserts nothing at all",
                events,
            )
            assertEquals(
                "the presses resolved for '$value' type something else",
                value,
                textOf(events!!),
            )
        }
    }

    @Test
    fun aShiftedCharacterIsPressedWithShiftHeld() {
        // `{` is Shift down, `[` down, `[` up, Shift up on the US layout, with
        // the meta state already set on each event. A table written in Kotlin
        // would be a second opinion about a layout that is here to be asked, and
        // this is the case that fails if anyone writes one.
        val events = virtualKeyboardEvents("{")
        assertNotNull("no press types '{' on the virtual keyboard layout", events)
        assertTrue(
            "'{' is typed without Shift held, so a bracket arrives as a square one",
            events!!.any { it.keyCode == KeyEvent.KEYCODE_LEFT_BRACKET && it.isShiftPressed },
        )
    }

    @Test
    fun everyPressCarriesTheLayoutItWasResolvedFromAndACurrentTimestamp() {
        // Both halves are re-stamped by hand and neither is visible from the JVM.
        // The events come back from getEvents stamped at time zero, and a press
        // that claims to have happened at boot is one whose repeat and long-press
        // timing cannot be read downstream. The device id has to stay the map the
        // key codes were resolved from, because that is the map getUnicodeChar
        // consults again on the way in.
        val before = android.os.SystemClock.uptimeMillis()
        val events = virtualKeyboardEvents(";")
        assertNotNull("no press types ';' on the virtual keyboard layout", events)

        for (event in events!!) {
            assertEquals(
                "the press was resolved from one layout and stamped with another",
                KeyCharacterMap.VIRTUAL_KEYBOARD,
                event.deviceId,
            )
            assertTrue(
                "the press is stamped at ${event.eventTime}, before this test began",
                event.eventTime >= before,
            )
            assertEquals(
                "the down time and the event time disagree, so the press reads as a repeat",
                event.eventTime,
                event.downTime,
            )
        }
    }
}
