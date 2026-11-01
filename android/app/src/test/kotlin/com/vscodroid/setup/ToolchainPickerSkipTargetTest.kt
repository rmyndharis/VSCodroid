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

    private fun meetsMinimumTouchHeight(button: Element): Boolean =
        button.getAttribute("android:layout_height") == "48dp" ||
            (button.getAttribute("android:layout_height") == "wrap_content" &&
                button.getAttribute("android:minHeight") == "48dp")

    /**
     * NEGATIVE CONTROL: put the TextView back and both assertions go red.
     */
    @Test
    fun `skip is a button at the minimum touch target`() {
        val skip = view("skipButton")
        assertEquals("Button", skip.tagName) {
            "Skip is a ${skip.tagName}, which TalkBack announces without a control role"
        }
        org.junit.jupiter.api.Assertions.assertTrue(meetsMinimumTouchHeight(skip)) {
            "Skip is not held to the 48dp touch target Continue meets on the same screen"
        }
    }

    @Test
    fun `continue is held to the same standard`() {
        val cont = view("continueButton")
        assertEquals("Button", cont.tagName)
        org.junit.jupiter.api.Assertions.assertTrue(meetsMinimumTouchHeight(cont))
    }

    @Test
    fun `header grid and note sit inside a scroll container above the pinned action buttons`() {
        val scroll = view("pickerScroll")
        assertEquals("androidx.core.widget.NestedScrollView", scroll.tagName)
        assertEquals("@+id/continueButton", scroll.getAttribute("app:layout_constraintBottom_toTopOf"))
        assertEquals("true", scroll.getAttribute("android:fillViewport"))

        for (childId in listOf("pickerTitle", "pickerSubtitle", "toolchainGrid", "includedNote")) {
            var node = view(childId).parentNode
            var insideScroll = false
            while (node != null) {
                if ((node as? Element)?.getAttribute("android:id") == "@+id/pickerScroll") {
                    insideScroll = true
                    break
                }
                node = node.parentNode
            }
            org.junit.jupiter.api.Assertions.assertTrue(
                insideScroll,
                "$childId must sit inside pickerScroll so short windows can scroll to every item",
            )
        }

        val grid = view("toolchainGrid")
        assertEquals("androidx.recyclerview.widget.RecyclerView", grid.tagName)
        assertEquals("wrap_content", grid.getAttribute("android:layout_height"))
        assertEquals("false", grid.getAttribute("android:nestedScrollingEnabled"))

        for (buttonId in listOf("continueButton", "skipButton")) {
            var node = view(buttonId).parentNode
            while (node != null) {
                org.junit.jupiter.api.Assertions.assertNotEquals(
                    "@+id/pickerScroll",
                    (node as? Element)?.getAttribute("android:id"),
                    "$buttonId must stay pinned outside pickerScroll",
                )
                node = node.parentNode
            }
        }
        assertEquals(
            "@+id/skipButton",
            view("continueButton").getAttribute("app:layout_constraintBottom_toTopOf"),
        )
        assertEquals(
            "parent",
            view("skipButton").getAttribute("app:layout_constraintBottom_toBottomOf"),
        )

        val splashSource = File("src/main/kotlin/com/vscodroid/SplashActivity.kt").readText()
        val pickerBody = com.vscodroid.SourceScan.withoutComments(
            com.vscodroid.SourceScan.body(splashSource, "private fun showToolchainPicker("),
        )
        org.junit.jupiter.api.Assertions.assertTrue(
            pickerBody.contains("findViewById<View>(R.id.pickerRoot)") &&
                pickerBody.contains(".padForSystemBars("),
            "showToolchainPicker must still apply system-bar padding to pickerRoot",
        )
        val downloadsBody = com.vscodroid.SourceScan.withoutComments(
            com.vscodroid.SourceScan.body(splashSource, "private fun startDownloads("),
        )
        org.junit.jupiter.api.Assertions.assertTrue(
            downloadsBody.contains("findViewById<View>(R.id.progressRoot)") &&
                downloadsBody.contains(".padForSystemBars("),
            "startDownloads must still apply system-bar padding to progressRoot",
        )
    }
}
