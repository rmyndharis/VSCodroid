package com.vscodroid.setup

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * That the two views MANAGER mode shows on the same row cannot land on top of each
 * other.
 *
 * `bindManagerMode` makes the status badge visible for INSTALLED and for FAILED,
 * and the action button visible whenever the card offers an action, and Install on
 * a failed download is exactly that pair. Both were constrained to the bottom of
 * the size line and nothing related them horizontally: the badge started at the
 * parent's start and grew, the button ended at the parent's end, and a badge wide
 * enough simply ran under the button. Every string on that row is translated, so
 * the width that does it is not knowable from the English ones.
 *
 * PICKER mode shows the badge for a toolchain that is already installed and never
 * shows the button at all, so the two are still never on that screen together and
 * a constraint between them cannot reach it; what it can be affected by is the
 * button being moved, which is what the last case here refuses.
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

    /** Whichever of `@+id/x` and `@id/x` the attribute was written as, as `x`. */
    private fun target(element: Element, attribute: String): String? =
        element.getAttributeNS(res, attribute)
            .takeIf { it.isNotEmpty() }
            ?.removePrefix("@+id/")
            ?.removePrefix("@id/")

    @Test
    fun `the status badge ends where the action button begins`() {
        assertEquals(
            "actionButton", target(view("statusBadge"), "layout_constraintEnd_toStartOf"),
            "nothing relates the badge and the button horizontally, so a label long " +
                "enough draws one on top of the other",
        )
    }

    /**
     * The constraint above only helps a view that can give way.
     *
     * At wrap_content the badge takes the width its text asks for and the end
     * constraint is a preference ConstraintLayout is free to overrule, which is the
     * same overlap arrived at by a longer route. At 0dp the badge is what is left
     * after the button, and a label too long for that wraps instead.
     */
    @Test
    fun `the badge is the view that yields`() {
        assertEquals(
            "0dp", view("statusBadge").getAttributeNS(androidNs, "layout_width"),
            "the badge claims its full text width, so the end constraint is advisory",
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
     * The button keeps its own place, which is what makes the pair safe in the
     * combinations where only one of them is on screen.
     *
     * A chain, or a button constrained to the badge instead of to the parent, would
     * move the button whenever the badge is gone. PICKER mode hides the badge and
     * so does an unbadged card in MANAGER mode, and in both the button belongs at
     * the end of the row.
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
