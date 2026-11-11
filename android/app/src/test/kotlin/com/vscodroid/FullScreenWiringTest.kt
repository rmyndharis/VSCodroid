package com.vscodroid

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * That the script which lets the editor's View > Toggle Full Screen hide the device's
 * bars is installed where every page load passes, reaches the bridge method that does
 * it, keeps what the user chose, and still matches how the shipped workbench asks for
 * full screen.
 *
 * Source-level, like [LeaveCurtainWiringTest], with the same ceiling: it cannot see
 * what the window does, only that the pieces still agree on the names they share. What
 * the script does with a request is `scripts/test-full-screen.js` against a stub page.
 */
class FullScreenWiringTest {

    private companion object {
        const val MAIN_ACTIVITY = "src/main/kotlin/com/vscodroid/MainActivity.kt"
        const val BRIDGE = "src/main/kotlin/com/vscodroid/bridge/AndroidBridge.kt"
        const val WORKBENCH = "src/main/assets/vscode-reh/out/vs/code/browser/workbench/workbench.js"
    }

    /** Comments blanked, because the KDoc names everything the cases look for. */
    private fun mainActivity(): String =
        SourceScan.withoutComments(SourceScan.read(MAIN_ACTIVITY))

    @Test
    fun `the script is installed from the path every page load takes`() {
        val source = mainActivity()
        assertTrue(
            source.contains("private fun injectFullScreenShim()"),
            "injectFullScreenShim is gone, so View > Toggle Full Screen answers a " +
                "fullscreenerror again and does nothing.",
        )
        val bridgeToken = SourceScan.body(source, "private fun injectBridgeToken(")
        assertTrue(
            bridgeToken.contains("injectFullScreenShim()"),
            "injectFullScreenShim() is not called from injectBridgeToken, the one path that " +
                "runs again after the workbench reloads or switches folders.",
        )
    }

    @Test
    fun `the script hands the request to the bridge and answers the workbench's own calls`() {
        val script = SourceScan.body(mainActivity(), "private fun injectFullScreenShim(")
        listOf(
            "AndroidBridge.setFullScreen(t, next)" to "the request has to reach the bridge " +
                "with the session token, or nothing on the device changes",
            "Element.prototype.requestFullscreen" to "the workbench asks its container",
            "Document.prototype.exitFullscreen" to "the workbench asks the document to leave",
            "property('fullscreen'" to "the workbench picks request or exit from " +
                "`document.fullscreen`",
            "new Event('fullscreenchange')" to "the workbench lays itself out again on this event",
            "document.querySelector('.monaco-workbench')" to "a page that starts as full screen " +
                "announces it once the workbench container exists; the workbench is not " +
                "following the event while it is still starting, so one raised earlier is lost " +
                "and the page shows no full screen state until the first toggle",
            "new MutationObserver(" to "the wait for that container",
        ).forEach { (name, why) ->
            assertTrue(script.contains(name), "injectFullScreenShim no longer has `$name`: $why")
        }
    }

    @Test
    fun `the choice is kept, applied at launch and again when the focus returns`() {
        val source = mainActivity()
        assertTrue(
            source.contains("onSetFullScreen = { on ->") &&
                source.contains("putBoolean(KEY_FULL_SCREEN, on)"),
            "the bridge callback no longer records the choice, so full screen ends with the " +
                "next launch.",
        )
        val shim = SourceScan.body(source, "private fun injectFullScreenShim(")
        assertTrue(
            shim.contains("getBoolean(KEY_FULL_SCREEN, false)") || source.contains(
                "val on = workspacePrefs.getBoolean(KEY_FULL_SCREEN, false)",
            ),
            "the script is not told the saved state, so a page that loads while full screen " +
                "is on thinks it is off and the first toggle asks for what is already true.",
        )
        val focus = SourceScan.body(source, "override fun onWindowFocusChanged(")
        assertTrue(
            focus.contains("hasFocus") && focus.contains("KEY_FULL_SCREEN") &&
                focus.contains("applyFullScreen(true)"),
            "the choice is not applied again when the window gets the focus back. The system " +
                "shows its bars when a permission prompt or the recents screen takes it.",
        )
        val apply = SourceScan.body(source, "private fun applyFullScreen(")
        assertTrue(
            apply.contains("Type.systemBars()") &&
                apply.contains("BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE"),
            "applyFullScreen no longer hides both bars with the swipe-to-peek behaviour, so " +
                "either the navigation bar stays or the bars cannot be looked at.",
        )
    }

    @Test
    fun `both inset listeners ask for the visible bars so the page grows into the room`() {
        // The reason nothing else follows the toggle: hidden bars report zero here.
        // getInsetsIgnoringVisibility would keep the padding and the room would stay empty.
        val row = SourceScan.withoutComments(
            SourceScan.read("src/main/kotlin/com/vscodroid/keyboard/ExtraKeyRow.kt"),
        )
        val webView = SourceScan.body(mainActivity(), "private fun applyWindowInsetsPadding(")
        listOf("ExtraKeyRow.setupWithRootView" to row, "applyWindowInsetsPadding" to webView).forEach { (name, text) ->
            assertTrue(
                text.contains("insets.getInsets("),
                "$name no longer reads the visible insets.",
            )
            assertTrue(
                !text.contains("getInsetsIgnoringVisibility"),
                "$name reads the insets as if the bars were still there, so hiding them frees " +
                    "no room.",
            )
        }
    }

    @Test
    fun `the bridge method checks the token before it acts`() {
        val bridge = SourceScan.withoutComments(SourceScan.read(BRIDGE))
        val method = SourceScan.body(bridge, "fun setFullScreen(")
        assertTrue(
            method.indexOf("security.validateToken(authToken)") in 0 until method.indexOf("onSetFullScreen(on)"),
            "setFullScreen reaches its callback without asking the token first. " +
                "BridgeTokenUniformityTest runs it too; this names the reason.",
        )
    }

    @Test
    fun `the shipped workbench still toggles full screen through the document and its container`() {
        val workbench = File(WORKBENCH)
        assumeTrue(
            workbench.isFile,
            "no packaged workbench at ${workbench.path}; run scripts/fetch-vscode-oss.sh and " +
                "scripts/package-assets.sh to check the mapping against the shipped bundle",
        )
        val bundle = workbench.readText()
        assertTrue(
            Regex("""async toggleFullScreen\(([\w$]+)\)\{let ([\w$]+)=this\.layoutService\.getContainer\(\1\);if\(\1\.document\.fullscreen!==void 0\)if\(\1\.document\.fullscreen\)try\{return await \1\.document\.exitFullscreen\(\)""")
                .containsMatchIn(bundle),
            "the workbench no longer toggles through `document.fullscreen` and " +
                "`document.exitFullscreen()`. The script answers those two; if it moved to " +
                "another API, View > Toggle Full Screen is a fullscreenerror again.",
        )
        assertTrue(
            bundle.contains("registerFullScreenListeners(") && bundle.contains("\"monaco-workbench\""),
            "the workbench no longer registers its full screen listener in a window contribution " +
                "or no longer names its container `monaco-workbench`. The script announces a " +
                "saved full screen state when that container appears, which is after the listener " +
                "exists only while the order below holds.",
        )
        assertTrue(
            bundle.contains("\"fullscreenchange\"") && bundle.contains(".requestFullscreen()"),
            "the workbench no longer follows `fullscreenchange` or asks its container with " +
                "`requestFullscreen()`.",
        )
    }
}
