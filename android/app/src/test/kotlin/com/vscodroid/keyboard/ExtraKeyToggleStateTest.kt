package com.vscodroid.keyboard

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Where a modifier's on/off state is kept, and that there is exactly one of it.
 *
 * A modifier has two consumers that must never disagree: [KeyPageAdapter]
 * repaints a (re)bound button from the stored state, and [ExtraKeyRow] reads the
 * same question to decide whether the next key goes out as Ctrl+key. When those
 * were two values, only one of them was written on the way *on* -- so a page
 * rebuilt while Ctrl was held would have drawn an idle key over a live modifier,
 * and the next tap would have gone out modified with nothing on screen saying so.
 *
 * [KeyPageAdapter] is an ordinary object with no `View` in its constructor, so
 * the store can be exercised directly here. What cannot: [ExtraKeyRow] is a
 * `View` whose initialiser reaches resources and display metrics, and
 * `onBindViewHolder` builds `ExtraKeyButton`s, so neither the row's key handling
 * nor the repaint can be driven from the JVM at all. Those halves are held by
 * structure instead of by assertion, and `the key row keeps no modifier state of
 * its own` below is what checks the structure is still there.
 */
class ExtraKeyToggleStateTest {

    private fun adapter() = KeyPageAdapter(
        pages = KeyPages.defaults,
        onKeyAction = { _, _, _ -> },
        onArrowKey = { },
        onDragEnd = { },
        onLongPress = { _, _ -> },
    )

    /**
     * The defect, as the store sees it.
     *
     * Switching a modifier on has to be recorded, not just drawn. Drop the write
     * in `setToggleState` -- which is the state the row was in, because the only
     * callers passed `false` -- and the "on" case below goes red.
     */
    @Test
    fun `the store keeps a modifier that was switched on, not only one released`() {
        val adapter = adapter()

        adapter.setToggleState("Ctrl", true)
        assertTrue(
            adapter.isToggleActive("Ctrl"),
            "a held modifier must survive in the store; this is what a rebuilt page is " +
                "painted from, and a page painted from a stale 'off' shows an idle key " +
                "while the next keystroke still goes out modified",
        )

        adapter.setToggleState("Ctrl", false)
        assertFalse(
            adapter.isToggleActive("Ctrl"),
            "releasing must be recorded too; a store that only ever answers true would " +
                "satisfy the assertion above",
        )
    }

    /** A modifier nobody has touched is off, which is what a first bind asks. */
    @Test
    fun `a modifier that was never set is off`() {
        assertFalse(adapter().isToggleActive("Ctrl"))
        assertFalse(adapter().isToggleActive("nonsense"))
    }

    /**
     * The structure that makes the two consumers unable to disagree: the row
     * holds no copy.
     *
     * `ExtraKeyRow` used to carry `ctrlActive`, `altActive` and `shiftActive` as
     * fields, and every change had to be written twice -- once here for what gets
     * injected, once into the adapter for what gets drawn. One of the two writes
     * was missing on the path that switches a modifier on. Reading and writing
     * through the adapter instead leaves nothing to forget, and putting a
     * `Boolean` field back on this class turns this red.
     *
     * Reflection rather than source text, so it answers about what was compiled.
     * It reads fields, not behaviour: it cannot see a copy smuggled into a
     * non-boolean field, and it cannot tell that the accessors reach the adapter
     * rather than something else -- the control below covers only that the class
     * still has an adapter to reach.
     *
     * If this fails for a boolean that is genuinely not modifier state, that is
     * the moment to decide whether it can drift the same way, and to name it here
     * if it cannot.
     */
    @Test
    fun `the key row keeps no modifier state of its own`() {
        val row = Class.forName(
            "com.vscodroid.keyboard.ExtraKeyRow", false,
            ExtraKeyToggleStateTest::class.java.classLoader,
        )

        // Control: a renamed or deleted class would make the assertion below pass
        // by looking at nothing, and a row with no adapter has no store to read.
        assertTrue(
            row.declaredFields.any { it.type == KeyPageAdapter::class.java },
            "ExtraKeyRow no longer holds a ${KeyPageAdapter::class.java.simpleName}, so " +
                "the field check below is not looking at a row that reads the shared store",
        )

        val ownState = row.declaredFields
            .filter { it.type == java.lang.Boolean.TYPE }
            .map { it.name }

        assertEquals(
            emptyList<String>(), ownState,
            "ExtraKeyRow is storing $ownState itself. A modifier kept here has to be " +
                "written in step with the adapter's toggle map, which is what a rebuilt " +
                "page is painted from, and a change that updates one and not the other " +
                "shows the user a modifier that differs from the one being sent",
        )
    }

    /**
     * The premise the three accessors rest on.
     *
     * `ExtraKeyRow` names Ctrl, Alt and Shift one at a time, and `KeyInjector`
     * carries exactly those three to the page. A fourth toggle on the row would
     * be handled as an ordinary key: injected, then left out of the reset, so its
     * button would flip itself on at the first tap and stay lit with nothing
     * behind it. If this fails, the new toggle needs a home in both places before
     * it goes on the row.
     */
    @Test
    fun `the toggles on the row are the three the injector can carry`() {
        val toggles = KeyPages.defaults
            .flatMap { it.items }
            .filterIsInstance<KeyItem.Button>()
            .filter { it.isToggle }
            .map { it.value }

        assertEquals(listOf("Ctrl", "Alt", "Shift"), toggles)
    }
}
