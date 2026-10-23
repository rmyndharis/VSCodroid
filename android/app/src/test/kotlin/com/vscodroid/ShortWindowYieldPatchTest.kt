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
 * TypeScript on top of 0033's `shortWindow`. Source checks keep the wiring aligned,
 * and Node fixtures execute the yield decision, deferred snap handling, and saved
 * move height. They do not render the workbench.
 */
class ShortWindowYieldPatchTest {

    @Test
    fun `the executed panel decision and move retain visibility and height`() {
        WorkbenchPatchBehavior.run("panel")
    }

    @Test
    fun `an editor sash cannot reveal a yielded panel`() {
        WorkbenchPatchBehavior.run("sash")
    }

    @Test
    fun `saving a closed or yielded panel retains its unsqueezed height`() {
        WorkbenchPatchBehavior.run("panel-save")
    }

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
            decision.contains("!this.isAuxiliaryBarMaximized()"),
            "a maximized secondary side bar hides the editor and is the thing being looked at, and the " +
                "panel put away beside it came back open beside it when the window turned tall",
        )
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
    fun `the layout state is left alone, except to close a panel with nothing in it`() {
        assertFalse(
            addedLines.any { it.contains("setRuntimeValue(LayoutStateKeys.PANEL_HIDDEN") },
            "yielding must touch the grid only: the state says the panel is open, which is what " +
                "brings it back at its size, and what a restart restores",
        )
        // The one exception is the panel whose last view was closed while it was out.
        assertEquals(
            listOf("this.setPanelHidden(true, true);"),
            addedLines.map { it.trim() }.filter { it.contains("setPanelHidden(") },
            "the only call that may change the state is the one that closes an empty panel",
        )
    }

    @Test
    fun `a panel whose last view was closed while it was out is closed, not shown empty`() {
        // hideActivePaneComposite only hides the part while isVisible says it is showing, and isVisible
        // answers false for a yielded panel, so the composite went and the state kept saying open.
        val all = patch.lines()
        val test = all.indexOfFirst {
            it.startsWith("+") && it.contains("!this.paneCompositeService.getActivePaneComposite(ViewContainerLocation.Panel)")
        }
        val close = all.indexOfFirst { it.startsWith("+") && it.contains("this.setPanelHidden(true, true);") }
        val show = all.indexOfFirst { it.startsWith("+") && it.contains("this.workbenchGrid.setViewVisible(this.panelPartView, !yielded && open);") }
        assertTrue(test >= 0 && close > test && show > close, "the empty panel must be closed before the grid shows it")
        assertTrue(
            all.subList(test, show).any { it.contains("return;") },
            "after closing it the decision must stop: showing the grid view would undo the close",
        )
    }

    @Test
    fun `the order inside the decision keeps the visibility listener from reading it as a close`() {
        // The flag is set before the grid is told. Swapped, the listener would see PANEL_HIDDEN false
        // against visible false and really close the panel on every keyboard raise.
        val all = patch.lines()
        val flag = all.indexOfFirst { it.contains("this.panelYielded = yielded;") }
        val css = all.indexOfFirst { it.contains("this.mainContainer.classList.toggle(LayoutClasses.PANEL_HIDDEN, yielded || !open);") }
        val grid = all.indexOfFirst { it.contains("this.workbenchGrid.setViewVisible(this.panelPartView, !yielded && open);") }
        assertTrue(flag >= 0 && css > flag && grid > css, "flag, then the class, then the grid")
    }

    @Test
    fun `zen mode and a maximized secondary side bar remember a panel that was out as open`() {
        assertEquals(
            2,
            addedLines.count { it.contains("this.isVisible(Parts.PANEL_PART) || this.panelYielded") },
            "isVisible answers false for a yielded panel, so both would record it closed and leave it " +
                "closed for good when they end",
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
            addedLines.any { it.contains("isHorizontal(this.getPanelPosition()) && this.panelHeightBeforeShort !== undefined") },
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
        // Two call sites: one when a panel is shown in a tall window, one at the tall flip.
        val restores = all.withIndex().filter { (_, l) -> l.startsWith("+") && l.contains("this.restorePanelHeight();") }.map { it.index }
        assertEquals(2, restores.size, "the tall flip and a show in a tall window")
        assertTrue(remember in 0 until flag, "the height is read before the title bar and the yield change the grid")
        val restore = restores.firstOrNull { it > yield } ?: -1
        assertTrue(restore > yield, "the height is given back after the panel is shown again")
        val guard = all.indexOfFirst { it.startsWith("+") && it.contains("if (!shortWindow) {") }
        assertTrue(guard in 0 until restore, "only a window turning tall gives the height back: unconditionally it clears the memory at the short flip")
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
    fun `what is remembered is a height the panel really had, and never a maximized one`() {
        assertTrue(
            addedLines.any { it.contains("? this.stateModel.getRuntimeValue(LayoutStateKeys.PANEL_LAST_NON_MAXIMIZED_HEIGHT)") },
            "a maximized panel measures the whole branch, and giving that back crushes the editor: what is " +
                "remembered for it is the height it returns to when it is un-maximized",
        )
        assertTrue(
            addedLines.any { it.contains(": this.workbenchGrid.getViewCachedVisibleSize(this.panelPartView);") },
            "a closed panel has no live height; the grid's memory of it is what it comes back at",
        )
        assertTrue(
            addedLines.any { it.contains("Math.min(remembered, Math.floor(this._mainContainerDimension.height * 0.7))") },
            "the window it comes back to can be another one after a turn",
        )
    }

    @Test
    fun `a panel maximized meanwhile, or closed, is handled and not resized`() {
        val all = patch.lines()
        val maximized = all.indexOfFirst { it.startsWith("+") && it.contains("if (this.isPanelMaximized()) {") }
        val last = all.indexOfFirst { it.startsWith("+") && it.contains("LayoutStateKeys.PANEL_LAST_NON_MAXIMIZED_HEIGHT, height") }
        val closed = all.indexOfFirst { it.startsWith("+") && it.contains("if (!this.isVisible(Parts.PANEL_PART)) {") }
        val resize = all.indexOfFirst { it.startsWith("+") && it.contains("this.workbenchGrid.resizeView(this.panelPartView, { width: size.width, height });") }
        assertTrue(maximized in 0 until last && last < closed && closed < resize, "maximized first, then closed, then the resize")
        assertTrue(
            all.subList(closed, resize).none { it.contains("this.panelHeightBeforeShort = undefined;") && all.indexOf(it) < closed + 2 },
            "a closed panel keeps the memory for when it is next shown",
        )
    }

    @Test
    fun `showing a panel in a tall window gives the height back, hiding it there drops it`() {
        val all = patch.lines()
        val grid = all.indexOfFirst { it.contains("this.workbenchGrid.setViewVisible(this.panelPartView, !hidden);") }
        val block = all.drop(grid).take(14).joinToString("\n")
        assertTrue(block.contains("if (!this.shortWindow) {"), "only in a tall window")
        assertTrue(block.contains("this.panelHeightBeforeShort = undefined;"), "a panel the user hides in a tall window has no height to come back to")
        assertTrue(block.contains("this.restorePanelHeight();"), "a panel shown in a tall window after a short one is given its height")
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
