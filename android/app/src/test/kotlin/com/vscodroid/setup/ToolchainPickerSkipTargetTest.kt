package com.vscodroid.setup

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * What the picker's two controls have to be.
 *
 * Skip was a `wrap_content` TextView with 8dp of padding and a click listener:
 * about 35dp tall, under the 48dp touch target, and announced by TalkBack as
 * text with no control role, on the one screen that decides whether the
 * toolchain offer is ever repeated. Continue beside it was a 48dp Button, so
 * the two controls on one screen were held to different standards.
 *
 * Layout reading, which is the layer available: no plain JVM test inflates a
 * view.
 */
class ToolchainPickerSkipTargetTest {

    private val layout = File("src/main/res/layout/layout_toolchain_picker.xml")

    private fun view(id: String): Element {
        check(layout.isFile) {
            "layout_toolchain_picker.xml not found at ${layout.absolutePath}; this test " +
                "would otherwise pass by looking at nothing"
        }
        val all = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(layout).getElementsByTagName("*")
        for (i in 0 until all.length) {
            val element = all.item(i) as Element
            if (element.getAttribute("android:id") == "@+id/$id") return element
        }
        throw AssertionError("no view with id $id in the picker layout; this test is measuring nothing")
    }

    /**
     * NEGATIVE CONTROL: put the TextView back and both assertions go red.
     */
    @Test
    fun `skip is a button at the minimum touch target`() {
        val skip = view("skipButton")
        assertEquals("Button", skip.tagName) {
            "Skip is a ${skip.tagName}, which TalkBack announces without a control role"
        }
        assertEquals("48dp", skip.getAttribute("android:layout_height")) {
            "Skip is not held to the 48dp touch target Continue meets on the same screen"
        }
    }

    @Test
    fun `continue is held to the same standard`() {
        val cont = view("continueButton")
        assertEquals("Button", cont.tagName)
        assertEquals("48dp", cont.getAttribute("android:layout_height"))
    }
}
