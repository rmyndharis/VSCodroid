package com.vscodroid

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * That the script keeping an inline list edit on screen while the keyboard
 * rises is installed where every page load passes, reads the row before the
 * workbench lays out and scrolls after, and still names what the shipped
 * workbench uses.
 *
 * Source-level, like [TouchContextMenuWiringTest], with the same ceiling: it
 * cannot see whether the scroll lands on a device, only that the script and the
 * bundle still agree on the names it depends on.
 */
class ListEditKeeperWiringTest {

    private companion object {
        const val MAIN_ACTIVITY = "src/main/kotlin/com/vscodroid/MainActivity.kt"
        const val WORKBENCH = "src/main/assets/vscode-reh/out/vs/code/browser/workbench/workbench.js"
    }

    /** Comments blanked, because the KDoc names everything the cases look for. */
    private fun mainActivity(): String =
        SourceScan.withoutComments(SourceScan.read(MAIN_ACTIVITY))

    @Test
    fun `the script is installed from the path every page load takes`() {
        val source = mainActivity()
        assertTrue(
            source.contains("private fun injectListEditKeeper()"),
            "injectListEditKeeper is gone, so New File or Rename on a row low in a long " +
                "Explorer tree makes the keyboard rise and fall without end.",
        )
        val bridgeToken = SourceScan.body(source, "private fun injectBridgeToken(")
        assertTrue(
            bridgeToken.contains("injectListEditKeeper()"),
            "injectListEditKeeper() is not called from injectBridgeToken, the one path that " +
                "runs again after the workbench switches folders by navigating its own WebView.",
        )
    }

    @Test
    fun `the row is read before layout and scrolled after it`() {
        val script = SourceScan.body(mainActivity(), "private fun injectListEditKeeper(")
        listOf(
            "addEventListener('resize'" to "the keyboard reaches the workbench as a resize; " +
                "without this listener nothing runs",
            ".monaco-list-row" to "the row holding the focused input; without it nothing is read",
            "monaco-list-rows" to "the class ListView gives its rows container, which is what " +
                "tells a list row from any other ancestor",
            "requestAnimationFrame" to "the scroll has to run after the workbench's layout and " +
                "before the frame is drawn; run at once, the list has no room to scroll yet",
            "'-monaco-gesturechange'" to "the touch-scroll event ListView listens for; a native " +
                "scroll of the list is thrown away",
            "Math.min(hidden + height, top)" to "the extra row of margin, capped at the row's " +
                "top; scrolled exactly to the bottom edge a pixel short cancels the edit, and a " +
                "full row in a short list pushes the row's top out and cancels it too",
        ).forEach { (name, why) ->
            assertTrue(script.contains(name), "injectListEditKeeper no longer names `$name`: $why")
        }
        assertTrue(
            Regex("""\},\s*true\)""").containsMatchIn(script),
            "the resize listener is not in the capture phase, so it can run after the " +
                "workbench has already taken the row down and has nothing left to read",
        )
    }

    @Test
    fun `the names still describe the shipped workbench`() {
        val workbench = File(WORKBENCH)
        assumeTrue(
            workbench.isFile,
            "no packaged workbench at ${workbench.path}; run scripts/fetch-vscode-oss.sh and " +
                "scripts/package-assets.sh to check the names against the shipped bundle",
        )
        val bundle = workbench.readText()
        listOf(
            "this.rowsContainer.className=\"monaco-list-rows\"" to
                "ListView no longer names its rows container this way, so the script matches no row",
            "\"-monaco-gesturechange\"" to
                "the touch-scroll event was renamed, so the dispatched scroll reaches no listener",
            "tryGetRelativeTop(l.stat)===null&&await l.data.onFinish(\"\",!1)" to
                "the Explorer's cancel-on-scroll changed; the margin the script scrolls by was " +
                "chosen against this check, so measure it again",
        ).forEach { (name, why) ->
            assertTrue(bundle.contains(name), "the packaged workbench no longer contains `$name`: $why")
        }
        assertTrue(
            Regex("""scrollTop-=\w+\.translationY""").containsMatchIn(bundle),
            "ListView no longer scrolls by the gesture's translationY, so the dispatched " +
                "event moves nothing",
        )
    }
}
