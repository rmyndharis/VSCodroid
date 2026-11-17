package com.vscodroid.setup

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * That the three views MANAGER mode can show under a card's size line, the status badge, the
 * progress bar and the action button, each keep a row of their own, so that none lands on top of
 * another or is squeezed to nothing.
 *
 * `bindManagerMode` makes the status badge visible for INSTALLED and for FAILED, and the action
 * button visible whenever the card offers an action, and Install on a failed download is exactly
 * that pair. Side by side, in a two-column grid at the largest font and display size, the button
 * took the whole inner width and left the badge 20 px or none, and every string on those rows is
 * translated, so the width that does it is not knowable from the English ones. Each now spans
 * the card on a row above the button, and the button is held to the card's width so that its own
 * label wraps instead of running past the edge.
 *
 * PICKER mode shows the badge for a toolchain that is already installed and never shows the
 * button at all, so what it can be affected by is the button being moved, which is what the last
 * case here refuses.
 *
 * Read from the XML with a parser rather than by matching text, so an attribute
 * written on a different line or in a different order still answers.
 */
class ToolchainCardLayoutTest {

    private val res = "http://schemas.android.com/apk/res-auto"
    private val androidNs = "http://schemas.android.com/apk/res/android"

    private fun view(id: String): Element {
        val layout = File("src/main/res/layout/item_toolchain_card.xml")
        assertTrue(
            layout.isFile,
            "${layout.absolutePath} is missing; this test would otherwise pass by " +
                "reading nothing",
        )
        val doc = DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(layout)
        val all = doc.getElementsByTagName("*")
        for (i in 0 until all.length) {
            val element = all.item(i) as Element
            val value = element.getAttributeNS(androidNs, "id")
            if (value == "@+id/$id" || value == "@id/$id") return element
        }
        error("no view with android:id=$id in ${layout.name}")
    }

    @Test
    fun `manager actions provide at least a 48dp target in both dimensions`() {
        val button = view("actionButton")
        for (attribute in listOf("minHeight", "minWidth")) {
            val value = button.getAttributeNS(androidNs, attribute)
            assertTrue(value.endsWith("dp") && value.removeSuffix("dp").toFloat() >= 48f,
                "actionButton $attribute must provide at least 48dp, got $value")
        }
    }

    /**
     * At the largest font and display size a card in the two-column grid is about 100dp wide, and
     * a label such as "Installieren" no longer fits on one line. Fixed at 48dp the button cut the
     * second line off, and unconstrained in width it ran past the card's edge and showed "Inst".
     * So the width is held to the card (the button is still right-aligned, by the bias) and the
     * height follows the label with 48dp as its floor.
     */
    @Test
    fun `the action button wraps its label inside the card instead of cutting it`() {
        val button = view("actionButton")

        assertEquals(
            "wrap_content", button.getAttributeNS(androidNs, "layout_height"),
            "a fixed height cuts a label that wraps at a large font; minHeight is the 48dp floor",
        )
        assertEquals(
            "true", button.getAttributeNS(res, "layout_constrainedWidth"),
            "a wrap_content width is only held to the card when constrainedWidth is set",
        )
        assertEquals(
            "parent", button.getAttributeNS(res, "layout_constraintStart_toStartOf"),
            "the button needs a start constraint or its width has nothing to be held to",
        )
        assertEquals(
            "parent", button.getAttributeNS(res, "layout_constraintEnd_toEndOf"),
            "the button stays at the card's end",
        )
        assertEquals(
            "1", button.getAttributeNS(res, "layout_constraintHorizontal_bias"),
            "with both ends constrained the button would centre; the bias keeps it at the end as before",
        )
    }

    /** Whichever of `@+id/x` and `@id/x` the attribute was written as, as `x`. */
    private fun target(element: Element, attribute: String): String? =
        element.getAttributeNS(res, attribute)
            .takeIf { it.isNotEmpty() }
            ?.removePrefix("@+id/")
            ?.removePrefix("@id/")

    @Test
    fun `the status badge and progress bar sit on their own full-width rows above the action button`() {
        val badge = view("statusBadge")
        val progress = view("downloadProgress")
        val button = view("actionButton")

        assertEquals(
            "parent", badge.getAttributeNS(res, "layout_constraintStart_toStartOf"),
            "the status badge must start at the parent's start",
        )
        assertEquals(
            "parent", badge.getAttributeNS(res, "layout_constraintEnd_toEndOf"),
            "the status badge must span to the parent's end so the action button cannot squeeze it to zero width",
        )
        assertEquals(
            "toolchainSize", target(badge, "layout_constraintTop_toBottomOf"),
            "the status badge must sit below toolchainSize",
        )
        assertEquals(
            "statusBadge", target(progress, "layout_constraintTop_toBottomOf"),
            "the progress bar must sit below statusBadge in the vertical chain",
        )
        assertEquals(
            "downloadProgress", target(button, "layout_constraintTop_toBottomOf"),
            "the action button must sit below downloadProgress so it never shares a row with the badge or progress bar",
        )
    }

    @Test
    fun `the badge spans the full constrained width`() {
        assertEquals(
            "0dp", view("statusBadge").getAttributeNS(androidNs, "layout_width"),
            "the badge must use 0dp match-constraints width across the card",
        )
    }

    /**
     * The checkmark says nothing to a screen reader, because the card already
     * does.
     *
     * `bindPickerMode` sets `isCheckable` and `isChecked` on the
     * `MaterialCardView`, which forwards both into its accessibility node. The
     * card is the focusable node here and this ImageView is not, and a
     * non-focusable child's label is read out as part of its clickable container.
     * Left important, a selected card stated selection twice -- once as the
     * child's label and once as the node's checked state -- while an unselected
     * one stated it once, so the two channels also disagreed about how selection
     * is named. That is the spoken half of the double tick `checkedIcon = null`
     * already took off the screen.
     *
     * Asserted on the attribute rather than on the absence of a label: the label
     * is deliberately kept, so that a reader taking the flag off gets a described
     * icon rather than an unlabelled one.
     */
    @Test
    fun `the checkmark is not a second announcement of selection`() {
        assertEquals(
            "no", view("checkmark").getAttributeNS(androidNs, "importantForAccessibility"),
            "the checkmark is announced alongside the card's own checked state, so a " +
                "selected card says it is selected twice and an unselected one says it once",
        )
    }

    /**
     * The button keeps its own place at the card's end, whichever of the rows above it are on
     * screen.
     *
     * A button constrained to the badge instead of to the parent would move whenever the badge is
     * gone. PICKER mode hides the badge and so does an unbadged card in MANAGER mode, and in both
     * the button belongs at the end of its row.
     */
    @Test
    fun `the action button is still anchored to the parent`() {
        val button = view("actionButton")

        assertEquals(
            "parent", button.getAttributeNS(res, "layout_constraintEnd_toEndOf"),
            "the button no longer ends at the parent's end, so hiding the badge moves it",
        )
        assertNull(
            target(button, "layout_constraintStart_toEndOf"),
            "the button is chained to the badge, so a card with no badge draws the " +
                "button somewhere other than the end of the row",
        )
    }
}
