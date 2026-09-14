package com.vscodroid

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * That the script keeping a context menu open under the keyboard is installed
 * where every page load passes, aimed at names the workbench still uses, and
 * that the one rule it copies into a shadow root is the rule the page sheet
 * carries.
 *
 * Source-level, like [KeyboardGuardWiringTest], and with the same ceiling: it
 * holds the selectors to the names the shipped workbench uses today and cannot
 * notice a VS Code bump renaming them. The device rows KB-26 and KB-27 in
 * `docs/DEVICE_TEST_CHECKLIST.md` are what catch that.
 */
class TouchContextMenuWiringTest {

    private companion object {
        const val MAIN_ACTIVITY = "src/main/kotlin/com/vscodroid/MainActivity.kt"
        const val WORKBENCH = "src/main/assets/vscode-reh/out/vs/code/browser/workbench/workbench.js"
        const val KEYBINDING_RULE = ".monaco-menu .keybinding { display: none !important; }"
    }

    /** Comments blanked, because the KDoc names everything the cases look for. */
    private fun mainActivity(): String =
        SourceScan.withoutComments(SourceScan.read(MAIN_ACTIVITY))

    private fun body(declaration: String): String =
        SourceScan.body(mainActivity(), declaration)

    /**
     * One listener's own text, from [opener] to the next `addEventListener`. Several
     * listeners in the script share names (Escape, `stopImmediatePropagation`,
     * `bars.length - 1`), so a check over the whole script cannot fail when the
     * listener it is about is deleted.
     */
    private fun listener(script: String, opener: String): String {
        val start = script.indexOf(opener)
        assertTrue(start >= 0, "`$opener` is gone from injectTouchContextMenu")
        val end = script.indexOf("addEventListener", start + opener.length)
        return script.substring(start, if (end < 0) script.length else end)
    }

    @Test
    fun `the script is installed from the path every page load takes`() {
        val source = mainActivity()
        assertTrue(
            source.contains("private fun injectTouchContextMenu()"),
            "injectTouchContextMenu is gone, so a long press with the keyboard up opens a " +
                "menu that closes itself 60ms later.",
        )
        val bridgeToken = SourceScan.body(source, "private fun injectBridgeToken(")
        assertTrue(
            bridgeToken.contains("injectTouchContextMenu()"),
            "injectTouchContextMenu() is not called from injectBridgeToken, which is the one " +
                "path that runs again after the workbench switches folders by navigating its " +
                "own WebView. Installed anywhere else it is missing from the second folder on.",
        )
    }

    @Test
    fun `focus is refused only out of an editing host, into a context view, on a coarse pointer`() {
        val script = body("private fun injectTouchContextMenu(")
        listOf(
            "native-edit-context" to "the element the workbench focuses for the caret; without it " +
                "the gate matches nothing and every menu still bounces the keyboard",
            "shadow-root-host" to "the host the workbench creates for a shadow-DOM context menu; " +
                "without it the editor's own menu, the one the report is about, is not covered",
            ".context-view" to "the light-DOM menu container; without it the explorer and " +
                "terminal menus are not covered",
            "pointer: coarse" to "the gate that keeps a mouse-and-keyboard device out of this; " +
                "without it a desktop-style tablet loses arrow-key menu navigation for nothing",
        ).forEach { (name, why) ->
            assertTrue(script.contains(name), "injectTouchContextMenu no longer names `$name`: $why")
        }
        val start = script.indexOf("HTMLElement.prototype.focus = function")
        assertTrue(start >= 0, "the focus refusal is gone, so a long press with the keyboard up opens a menu that closes itself")
        val refusal = script.substring(start, script.indexOf("};", start))
        listOf(
            "EDITING_HOST" to "the refusal no longer starts from an editing host",
            "inMenu(this)" to "the refusal reaches beyond menus: an action widget or a select dropdown " +
                "that closes on blur can then never lose focus, and a tap outside leaves it open",
            "matchMedia(COARSE)" to "the refusal applies to a fine pointer",
            "__vscodroidImeVisible !== false" to "the refusal applies with the keyboard down or a " +
                "hardware keyboard in use, so Enter and arrows edit the file behind an open menu",
            "return;" to "the refusal no longer refuses anything",
        ).forEach { (name, why) -> assertTrue(refusal.contains(name), why) }
        assertTrue(
            script.contains("closest('.monaco-menu-container')"),
            "inMenu no longer recognises a menu by the container the workbench's Menu marks",
        )
    }

    @Test
    fun `the page is told whether the soft keyboard is up`() {
        val setup = body("private fun setupExtraKeyRow(")
        assertTrue(
            setup.contains("onImeVisibilityChanged") && setup.contains("__vscodroidImeVisible"),
            "the keyboard's state no longer reaches the page, so the focus refusal cannot tell a " +
                "soft keyboard it must protect from a hardware one it must not get in the way of",
        )
        val script = body("private fun injectTouchContextMenu(")
        assertTrue(
            script.contains("extraKeyRow?.imeVisible") &&
                script.indexOf("__vscodroidImeVisible = reported") < script.indexOf("__vscodroidTouchContextMenu) return"),
            "a new document does not start from the last reported keyboard state, or reads it " +
                "only on the first injection",
        )
    }

    @Test
    fun `a tap on a menubar item does not reach the menubar button`() {
        val script = body("private fun injectTouchContextMenu(")
        // The workbench delivers one tap to every gesture target containing the first
        // touch. The menu lives inside its button, so the tap that runs an item then
        // reopens the menu it closed, and after `File > New File...` that took focus
        // off the quick pick, which closed. Measured on an API 33 emulator.
        listOf(
            "-monaco-gesturetap" to "the workbench's tap event; without it nothing is intercepted",
            ".menubar-menu-items-holder" to "the menu and submenu container inside the button; " +
                "without it every tap on the button is swallowed or none is",
            "menubar-menu-button" to "the target whose listener reopens the menu",
        ).forEach { (name, why) ->
            assertTrue(script.contains(name), "injectTouchContextMenu no longer names `$name`: $why")
        }
        // Only this listener's own text: the next listener in the script also ends in
        // `}, true)`, and a pattern allowed to run on reaches it. The event does not
        // bubble, so outside the capture phase the listener never sees the button's turn.
        val listener = listener(script, "'-monaco-gesturetap'")
        assertTrue(
            listener.contains("stopImmediatePropagation") && listener.contains("__vscodroidMenuItemTap") &&
                listener.contains(".menubar-menu-items-holder"),
            "the tap listener no longer marks a tap from inside the menu and stops it at the button, " +
                "so the button's own listener reopens the menu and a picker it opened closes",
        )
        assertTrue(
            Regex("""\},\s*true\)""").containsMatchIn(listener),
            "the tap listener is not in the capture phase; the event does not bubble, so it never " +
                "sees the tap reach the menubar button and the menu reopens again",
        )
    }

    @Test
    fun `the keyboard guard ignores a touch that landed in a context menu`() {
        val guard = body("private fun injectKeyboardGuard(")
        // The editor's menu is built in a shadow root whose host is a child of the
        // editor, so a touch inside it retargets to a node that matches the guard's
        // own TEXT selector. Without this test the guard reads a tap on a menu item
        // as a tap on the file, raises the keyboard, and the resize takes the menu:
        // measured on an API 36 emulator as viewport 845 to 458 with the menu gone.
        assertTrue(
            guard.contains("shadow-root-host") && guard.contains(".context-view"),
            "injectKeyboardGuard no longer excludes a touch inside a context view, so " +
                "tapping a menu item raises the keyboard and the resize closes the menu " +
                "before the item can be used. A submenu becomes unreachable entirely.",
        )
        // It has to leave the keyboard state alone, not reset it: the branch below
        // the exclusion clears aimedAtText and re-applies inputmode="none", which
        // would take the keyboard away from a menu opened while the user was typing.
        val exclusion = guard.substringAfter("shadow-root-host").substringBefore("closest(TEXT)")
        assertTrue(
            exclusion.contains("return"),
            "the context-view exclusion falls through instead of returning, so a touch " +
                "in a menu still resets the keyboard state it was meant to leave alone",
        )
        assertTrue(
            !exclusion.contains("aimedAtText"),
            "the context-view exclusion changes aimedAtText; a touch in a menu must " +
                "decide nothing about the keyboard in either direction",
        )
    }

    @Test
    fun `escape still reaches a menu that does not hold focus`() {
        val script = body("private fun injectTouchContextMenu(")
        val forwarder = listener(script, "addEventListener('keydown'")
        assertTrue(
            forwarder.contains("'Escape'") && forwarder.contains("stopImmediatePropagation") &&
                forwarder.contains("dispatchEvent") && forwarder.contains("bars.length - 1"),
            "the Escape forwarder is gone, or no longer reaches the innermost menu first. A menu that never took focus never sees Escape, so " +
                "the key-row Esc button stops closing menus, which is the one keyboard route a " +
                "phone has.",
        )
    }

    @Test
    fun `tapping away from a menu closes it`() {
        val script = body("private fun injectTouchContextMenu(")
        // The menu's own full-viewport dismiss layer closes nothing on this WebView,
        // measured with a real Android tap and again with a synthetic mouse press.
        // It only ever appeared to work because the tap was read as a tap on the file
        // and the keyboard that came up resized the window out from under the menu.
        // With that excluded from the keyboard guard, this is the dismissal.
        // To the next function rather than the next listener: this one registers the
        // click swallow inside itself.
        val awayStart = script.indexOf("addEventListener('pointerdown'")
        assertTrue(awayStart >= 0, "the tap-away dismissal is gone")
        val away = script.substring(awayStart, script.indexOf("function adopt(", awayStart))
        assertTrue(
            away.contains("getBoundingClientRect") && away.contains("clientX"),
            "the tap-away dismissal is gone or no longer decides by geometry. Containment " +
                "cannot decide it: every touch inside a shadow-DOM menu retargets to the " +
                "same host, so the inside and the outside of the menu look identical.",
        )
        assertTrue(
            away.contains("bars.length - 1"),
            "the dismissal no longer walks the menus innermost first, so tapping away " +
                "from a submenu leaves its parent open",
        )
        assertTrue(
            away.contains("closest('.monaco-menu')"),
            "the dismissal measures the action bar instead of the menu that clips it, so a tap " +
                "just outside a scrolling menu counts as inside and closes nothing",
        )
        assertTrue(
            away.contains("e.preventDefault()") && away.contains("addEventListener('click', swallow, true)") &&
                away.contains("removeEventListener('click', swallow, true)"),
            "the tap that closes a menu goes on to click whatever was under it: a file row opens, " +
                "a status bar entry runs",
        )
    }

    @Test
    fun `the shadow root gets the same keybinding rule as the page`() {
        val menuScript = body("private fun injectTouchContextMenu(")
        val pageCss = body("private fun injectTouchTargetCSS(")
        assertTrue(
            pageCss.contains(KEYBINDING_RULE),
            "the page stylesheet no longer hides `.monaco-menu .keybinding` on a coarse pointer, " +
                "so light-DOM menus (explorer, menubar, terminal) show chords a finger cannot press",
        )
        assertTrue(
            menuScript.contains(KEYBINDING_RULE),
            "the adopted sheet no longer hides `.monaco-menu .keybinding`, so the editor's own " +
                "menu, which lives in a shadow root the page sheet cannot reach, still shows them",
        )
        // Two copies of one rule drift; this is what keeps them one rule.
        val inPage = Regex(Regex.escape(KEYBINDING_RULE)).findAll(pageCss).count()
        val inMenu = Regex(Regex.escape(KEYBINDING_RULE)).findAll(menuScript).count()
        assertEquals(1, inPage, "the page sheet should carry the keybinding rule exactly once")
        assertEquals(1, inMenu, "the adopted sheet should carry the keybinding rule exactly once")
        assertTrue(
            menuScript.contains("attachShadow") && menuScript.contains("adoptedStyleSheets"),
            "the rule is no longer adopted by the shadow root as it is created; a sheet that " +
                "only reaches a host which already exists misses the first menu of every load",
        )
    }

    @Test
    fun `the names still describe the shipped workbench`() {
        val workbench = File(WORKBENCH)
        assumeTrue(
            workbench.isFile,
            "no packaged workbench at ${workbench.path}; run scripts/fetch-vscode-oss.sh and " +
                "scripts/package-assets.sh to check the selectors against the shipped bundle",
        )
        val bundle = workbench.readText()
        listOf(
            "shadow-root-host",
            "context-view",
            "actions-container",
            "native-edit-context",
            "menubar-menu-items-holder",
            "menubar-menu-button",
            "-monaco-gesturetap",
        )
            .forEach { name ->
                assertTrue(
                    bundle.contains(name),
                    "the packaged workbench no longer mentions `$name`, so the script now " +
                        "matches nothing there. Open a menu over the DevTools protocol and " +
                        "read the class names it renders with.",
                )
            }
    }
}
