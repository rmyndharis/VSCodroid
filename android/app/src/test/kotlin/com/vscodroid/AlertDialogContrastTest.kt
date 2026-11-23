package com.vscodroid

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.pow

/**
 * Verifies AlertDialog action button text colors clear WCAG 2.1 AA (4.5:1) on both
 * light and dark Material3 dialog surfaces while leaving filled primary buttons intact.
 */
class AlertDialogContrastTest {

    private fun parseXml(path: String): Element {
        val file = File(path)
        assertTrue(file.isFile, "Missing resource file: ${file.path}")
        return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement
    }

    private fun colorsIn(path: String): Map<String, String> {
        val nodes = parseXml(path).getElementsByTagName("color")
        return (0 until nodes.length).associate { i ->
            val el = nodes.item(i) as Element
            el.getAttribute("name") to el.textContent.trim()
        }
    }

    private fun styleItems(path: String, styleName: String): Map<String, String> {
        val styles = parseXml(path).getElementsByTagName("style")
        for (i in 0 until styles.length) {
            val style = styles.item(i) as Element
            if (style.getAttribute("name") == styleName) {
                val items = style.getElementsByTagName("item")
                return (0 until items.length).associate { j ->
                    val item = items.item(j) as Element
                    item.getAttribute("name") to item.textContent.trim()
                }
            }
        }
        return emptyMap()
    }

    private fun contrastRatio(fgHex: String, bgHex: String): Double {
        fun channel(hex: String, offset: Int): Double {
            val s = hex.removePrefix("#").substring(offset, offset + 2).toInt(16) / 255.0
            return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        }
        fun luminance(hex: String): Double =
            0.2126 * channel(hex, 0) + 0.7152 * channel(hex, 2) + 0.0722 * channel(hex, 4)
        val l1 = luminance(fgHex)
        val l2 = luminance(bgHex)
        val lighter = maxOf(l1, l2)
        val darker = minOf(l1, l2)
        return (lighter + 0.05) / (darker + 0.05)
    }

    @Test
    fun `alert dialog action buttons clear WCAG AA contrast in light and dark modes without altering filled primary buttons`() {
        val lightColors = colorsIn("src/main/res/values/colors.xml")
        val darkColors = colorsIn("src/main/res/values-night/colors.xml")

        val lightDialogText = lightColors["colorDialogButtonText"]
        val darkDialogText = darkColors["colorDialogButtonText"]
        assertNotNull(lightDialogText, "values/colors.xml must declare colorDialogButtonText")
        assertNotNull(darkDialogText, "values-night/colors.xml must declare colorDialogButtonText")

        // Material3 light dialog surface (#ECE6F0) and white (#FFFFFF)
        assertTrue(contrastRatio(lightDialogText!!, "#ECE6F0") >= 4.5) {
            "light colorDialogButtonText ($lightDialogText) on #ECE6F0 below 4.5:1: ${contrastRatio(lightDialogText, "#ECE6F0")}"
        }
        assertTrue(contrastRatio(lightDialogText, "#FFFFFF") >= 4.5) {
            "light colorDialogButtonText ($lightDialogText) on #FFFFFF below 4.5:1"
        }

        // Material3 dark dialog surface (#2B2930) and AppCompat dark surface (#424242)
        assertTrue(contrastRatio(darkDialogText!!, "#2B2930") >= 4.5) {
            "dark colorDialogButtonText ($darkDialogText) on #2B2930 below 4.5:1: ${contrastRatio(darkDialogText, "#2B2930")}"
        }
        assertTrue(contrastRatio(darkDialogText, "#424242") >= 4.5) {
            "dark colorDialogButtonText ($darkDialogText) on #424242 below 4.5:1: ${contrastRatio(darkDialogText, "#424242")}"
        }

        // Filled button primary surface stays unchanged at >= 4.5:1 for white text
        val primary = lightColors["colorPrimary"]
        assertNotNull(primary)
        assertTrue(contrastRatio("#FFFFFF", primary!!) >= 4.5) {
            "filled button white-on-primary contrast degraded: $primary"
        }

        // Theme wires both AppCompat and Material3 AlertDialog overlays to the AA button style
        val appTheme = styleItems("src/main/res/values/themes.xml", "Theme.VSCodroid")
        assertEquals("@style/ThemeOverlay.VSCodroid.AlertDialog", appTheme["alertDialogTheme"])
        assertEquals("@style/ThemeOverlay.VSCodroid.MaterialAlertDialog", appTheme["materialAlertDialogTheme"])

        for (overlayName in listOf("ThemeOverlay.VSCodroid.AlertDialog", "ThemeOverlay.VSCodroid.MaterialAlertDialog")) {
            val overlay = styleItems("src/main/res/values/themes.xml", overlayName)
            assertEquals("@color/colorDialogButtonText", overlay["colorPrimary"])
            assertEquals("@style/Widget.VSCodroid.Button.Dialog", overlay["buttonBarPositiveButtonStyle"])
            assertEquals("@style/Widget.VSCodroid.Button.Dialog", overlay["buttonBarNegativeButtonStyle"])
            assertEquals("@style/Widget.VSCodroid.Button.Dialog", overlay["buttonBarNeutralButtonStyle"])
        }

        val buttonStyle = styleItems("src/main/res/values/themes.xml", "Widget.VSCodroid.Button.Dialog")
        assertEquals("@color/colorDialogButtonText", buttonStyle["android:textColor"])
    }
}
