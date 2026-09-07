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
 * notice a VS Code bump renaming them. The device rows KB-22 and KB-23 in
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
    }

    @Test
    fun `escape still reaches a menu that does not hold focus`() {
        val script = body("private fun injectTouchContextMenu(")
        assertTrue(
            script.contains("'Escape'") && script.contains("stopImmediatePropagation"),
            "the Escape forwarder is gone. A menu that never took focus never sees Escape, so " +
                "the key-row Esc button stops closing menus, which is the one keyboard route a " +
                "phone has.",
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
        listOf("shadow-root-host", "context-view", "actions-container", "native-edit-context")
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
