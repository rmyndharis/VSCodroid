package com.vscodroid

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * That patch 0034 takes the bottom panel out of the layout in a short window without
 * touching what the user chose, and leaves a panel they are typing into alone.
 *
 * A panel cannot shrink under its 77px minimum, so on a phone in landscape with the soft
 * keyboard up it left the editor its tab strip and nothing else. The patch lives in
 * TypeScript on top of 0033's `shortWindow`, and nothing but this reads it, so the rules
 * that keep it from doing damage are pinned here by reading the source it adds:
 * [ShortWindowPatchTest] does the same for 0033, and no JVM test can lay the workbench out.
 */
class ShortWindowYieldPatchTest {

    /** The repository root is two levels above `android/app`, this suite's working directory. */
    private val patch: String = File("../../patches/0034-short-window-yields-the-panel.patch").also {
        check(it.isFile) {
            "${it.absolutePath} not found; if the patch was renumbered, point this case at the " +
                "new name rather than letting it pass by looking at nothing"
        }
    }.readText()

    private val addedLines: List<String> =
        patch.lines().filter { it.startsWith("+") && !it.startsWith("+++") }.map { it.drop(1) }

    private val decision: String = addedLines.firstOrNull { it.contains("const yielded =") }
        ?: error("patch 0034 no longer decides `yielded` in one expression")

    @Test
    fun `only a short window yields the panel, and only the 0033 flag makes a window short`() {
        assertTrue(
            decision.trim().startsWith("const yielded = this.shortWindow &&"),
            "yielding must be gated by shortWindow, which is only ever true on Android",
        )
        assertFalse(
            addedLines.any { it.contains("SHORT_WINDOW_HEIGHT") || it.contains("innerHeight") },
            "a second height test would let the two parts disagree about when the window is short",
        )
    }

    @Test
    fun `a panel the keyboard is typing into stays`() {
        assertTrue(
            decision.contains("!this.hasFocus(Parts.PANEL_PART)"),
            "hiding a terminal that holds the focus takes the keyboard down with it",
        )
    }

    @Test
    fun `only a panel below the editor with the editor showing yields`() {
        assertTrue(decision.contains("isHorizontal(this.getPanelPosition())"), "a side panel costs width, not lines")
        assertTrue(
            decision.contains("&& open &&"),
            "a closed panel has nothing to yield; `open` is read from the layout state, because " +
                "isVisible answers false for a panel that is already yielded and would end the yield " +
                "at the next decision",
        )
        assertTrue(
            addedLines.any { it.contains("const open = !this.stateModel.getRuntimeValue(LayoutStateKeys.PANEL_HIDDEN);") },
            "the decision's `open` must be the state, not isVisible",
        )
        assertTrue(
            decision.contains("this.isVisible(Parts.EDITOR_PART)"),
            "with the editor hidden (a maximized panel) the panel is the thing being looked at",
        )
    }

    @Test
    fun `the layout state is left alone`() {
        assertFalse(
            addedLines.any { it.contains("setRuntimeValue(LayoutStateKeys.PANEL_HIDDEN") || it.contains("setPanelHidden(") },
            "yielding must touch the grid only: the state says the panel is open, which is what " +
                "brings it back at its size, and what a restart restores",
        )
    }

    @Test
    fun `the grid reporting the panel out is not read as the user closing it`() {
        assertTrue(
            addedLines.any {
                it.contains("part === panelPart && !this.panelYielded && this.stateModel.getRuntimeValue(LayoutStateKeys.PANEL_HIDDEN) === visible")
            },
            "the visibility listener would call setPanelHidden(true) for a change the layout itself made",
        )
    }

    @Test
    fun `a state save in a short window keeps the size the panel had`() {
        assertTrue(
            addedLines.any { it.contains("getRuntimeValue(LayoutStateKeys.PANEL_HIDDEN) || this.panelYielded") },
            "a view outside the grid measures 0, which would be stored as the panel size for the next start",
        )
        assertTrue(
            addedLines.any { it.contains("this.panelHeightBeforeShort ?? this.workbenchGrid.getViewSize(this.panelPartView).height") },
            "a panel that stayed in a short window is squeezed, and its squeezed height would be " +
                "stored as the size for the next start",
        )
    }

    @Test
    fun `a panel the grid has put away counts as hidden to everything that asks`() {
        // Upstream reads isVisible for a toggle, a focus request, a maximize, a move and the
        // sizes it saves. While the state said open and the grid said hidden, Ctrl+J closed a panel
        // nobody could see and the second press showed it squeezed.
        assertTrue(
            addedLines.any { it.contains("return !this.stateModel.getRuntimeValue(LayoutStateKeys.PANEL_HIDDEN) && !this.panelYielded;") },
            "isVisible(PANEL_PART) must be false while the panel is yielded",
        )
    }

    @Test
    fun `showing or hiding the panel ends the yield, after wasHidden is read`() {
        val all = patch.lines()
        val wasHidden = all.indexOfFirst { it.contains("const wasHidden = !this.isVisible(Parts.PANEL_PART);") }
        val end = all.indexOfFirst { it.startsWith("+") && it.contains("this.panelYielded = false;") }
        assertTrue(wasHidden >= 0 && end > wasHidden, "the flag must be cleared after wasHidden is read: a yielded panel was hidden")
        val state = all.indexOfFirst { it.contains("this.stateModel.setRuntimeValue(LayoutStateKeys.PANEL_HIDDEN, hidden);") }
        assertTrue(state > end, "the flag must be cleared before the grid is told, or the visibility listener reads the change")
    }

    @Test
    fun `the height a panel had is remembered before it is hidden and given back after the tall layout`() {
        val all = patch.lines()
        val remember = all.indexOfFirst { it.startsWith("+") && it.contains("this.rememberPanelHeight();") }
        val flag = all.indexOfFirst { it.contains("this.shortWindow = shortWindow;") }
        val yield = all.indexOfFirst { it.startsWith("+") && it.contains("this.updatePanelYield();") }
        val restore = all.indexOfFirst { it.startsWith("+") && it.contains("this.restorePanelHeight();") }
        assertTrue(remember in 0 until flag, "the height is read before the title bar and the yield change the grid")
        assertTrue(restore > yield, "the height is given back after the panel is shown again")
        assertTrue(
            addedLines.any { it.contains("this.workbenchGrid.resizeView(this.panelPartView, { width: size.width, height });") },
            "a panel that stayed in the short window must be resized back, not left at its minimum",
        )
        assertTrue(
            addedLines.any { it.contains("if (size.height < height) {") },
            "a panel the grid already drew back at its size is left alone",
        )
    }

    @Test
    fun `a move to another side decides the yield again`() {
        val all = patch.lines()
        val moved = all.indexOfFirst { it.contains("this.adjustPartPositions(this.getSideBarPosition(), this.getPanelAlignment(), position);") }
        val decide = all.withIndex().indexOfFirst { (i, line) -> i > moved && line.startsWith("+") && line.contains("this.updatePanelYield();") }
        val fire = all.indexOfFirst { it.contains("this._onDidChangePanelPosition.fire(newPositionValue);") }
        assertTrue(moved >= 0 && decide in 0 until fire, "a panel moved beside the editor must come back, one moved below must yield")
    }

    @Test
    fun `a window turning tall is acted on after the grid is sized, one turning short before`() {
        // Shown into a grid that is still short, the panel is squeezed to its minimum and the
        // grid then gives the room the window gained to the editor: measured on an API 37
        // emulator, a 218px panel came back at 69px. Hidden before the grid is sized, it keeps
        // the size the grid remembers for it.
        val all = patch.lines()
        val gridLayout = all.indexOfFirst { it.contains("this.workbenchGrid.layout(this._mainContainerDimension.width") }
        val after = all.indexOfFirst {
            it.startsWith("+") && it.contains("this.updateShortWindow(this._mainContainerDimension.height, true);")
        }
        assertTrue(gridLayout >= 0 && after == gridLayout + 1, "the tall half must run right after the grid layout")
        assertTrue(
            addedLines.any { it.contains("shortWindow !== this.shortWindow && shortWindow !== afterGridLayout") },
            "the call before the grid layout must only act on a window turning short, and the call " +
                "after it only on one turning tall",
        )
    }

    @Test
    fun `the decision is taken when the window turns short or tall, after the bars`() {
        // The status bar call is a context line of the hunk, so the whole patch is searched.
        val all = patch.lines()
        val update = all.indexOfFirst { it.contains("this.updateStatusBarVisibility();") }
        val yield = all.indexOfFirst { it.startsWith("+") && it.contains("this.updatePanelYield();") }
        assertTrue(update >= 0 && yield > update, "updatePanelYield must follow the bars' update in updateShortWindow")
        assertEquals(
            2,
            addedLines.count { it.contains("this.updatePanelYield();") },
            "two call sites: the flip, and a move of the panel to another side",
        )
    }
}
