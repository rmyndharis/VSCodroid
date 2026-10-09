package com.vscodroid.util

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Which colours [paintWindow] turns the bar icons dark on, and the loading page and
 * the setup screen set dark text on.
 *
 * NEGATIVE CONTROL: comparing the luminance with 0.5, the usual cut, fails the
 * second case at #777777; dropping the linearisation of the channels fails it at
 * #737373, and swapping the red and blue channels fails it at blue.
 */
class LightColorTest {

    @Test
    fun `editor backgrounds of light themes are light, of dark themes are not`() {
        // Light Modern, Light+ and High Contrast Light share white; Solarized Light, Quiet Light.
        for (color in listOf(0xFFFFFFFF, 0xFFFDF6E3, 0xFFF5F5F5)) {
            assertTrue(isLightColor(color.toInt())) { "#%06x is not taken as light".format(color and 0xFFFFFF) }
        }
        // Dark Modern, Dark+ and the window, Monokai, Solarized Dark, High Contrast.
        for (color in listOf(0xFF1F1F1F, 0xFF1E1E1E, 0xFF272822, 0xFF002B36, 0xFF000000)) {
            assertFalse(isLightColor(color.toInt())) { "#%06x is taken as light".format(color and 0xFFFFFF) }
        }
    }

    @Test
    fun `the cut is where black and white text contrast equally`() {
        // #777777 has a luminance of 0.184: black on it measures 4.7:1, white 4.5:1.
        assertTrue(isLightColor(0xFF777777.toInt())) { "#777777 is not taken as light" }
        // #737373, 0.171: black 4.4:1, white 4.7:1.
        assertFalse(isLightColor(0xFF737373.toInt())) { "#737373 is taken as light" }
        // Each channel weighs what it does in the luminance: green most, blue least.
        assertTrue(isLightColor(0xFFFFFF00.toInt())) { "yellow is not taken as light" }
        assertFalse(isLightColor(0xFF0000FF.toInt())) { "blue is taken as light" }
    }
}
