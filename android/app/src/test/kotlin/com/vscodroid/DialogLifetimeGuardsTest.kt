package com.vscodroid

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Two dialogs of `MainActivity` that outlive the moment they were asked for.
 *
 * The About dialog is requested by a page through the bridge, which posts to the main thread, so it can run after the
 * screen has been destroyed; `show()` on a window whose token is dead throws there. The old-WebView warning is shown
 * once per WebView version, and was marked as given before it was shown, so a screen that ended under it, or a
 * process that was killed, left the warning unseen and never offered again.
 *
 * Read out of the source, because no JVM test here can build the Activity.
 */
class DialogLifetimeGuardsTest {

    private val source = SourceScan.read("src/main/kotlin/com/vscodroid/MainActivity.kt")

    private fun code(declaration: String) = SourceScan.withoutComments(SourceScan.body(source, declaration))

    @Test
    fun `the About dialog is not shown on a screen that is gone`() {
        val about = code("fun showAboutDialog(")
        assertTrue(about.trimStart('{').trimStart().startsWith("if (isFinishing || isDestroyed) return")) {
            "showAboutDialog does not start by checking that its screen is still there:\n$about"
        }
    }

    @Test
    fun `the old WebView warning is marked as given when its dialog is dismissed`() {
        val check = code("private fun checkWebViewVersion(")
        val mark = "workspacePrefs.edit { putString(KEY_WEBVIEW_WARNED, version) }"

        assertTrue(check.indexOf("setOnDismissListener { $mark }") > check.indexOf("AlertDialog.Builder(this)")) {
            "the warning is not marked as given by the dialog's own dismissal:\n$check"
        }
        assertTrue(check.indexOf(mark) == check.lastIndexOf(mark)) {
            "the warning is also marked as given before the dialog is shown:\n$check"
        }
    }
}
