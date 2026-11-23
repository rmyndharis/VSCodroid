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

    private val source = SourceScan.readAll(
        listOf(
            "src/main/kotlin/com/vscodroid/MainActivity.kt",
            "src/main/kotlin/com/vscodroid/MainActivityDialogs.kt",
            "src/main/kotlin/com/vscodroid/MainActivitySafCoordinator.kt",
        )
    )

    private fun code(declaration: String) = SourceScan.withoutComments(SourceScan.body(source, declaration))

    @Test
    fun `the About dialog is not shown on a screen that is gone`() {
        val about = code("fun MainActivity.showAboutDialog(")
        assertTrue(about.trimStart('{').trimStart().startsWith("if (isFinishing || isDestroyed) return")) {
            "showAboutDialog does not start by checking that its screen is still there:\n$about"
        }
    }

    @Test
    fun `the old WebView warning is marked as given when its dialog is dismissed`() {
        val check = code("internal fun MainActivity.checkWebViewVersion(")
        val mark = "workspacePrefs.edit { putString(KEY_WEBVIEW_WARNED, version) }"

        assertTrue(check.indexOf("setOnDismissListener { $mark }") > check.indexOf("AlertDialog.Builder(this)")) {
            "the warning is not marked as given by the dialog's own dismissal:\n$check"
        }
        assertTrue(check.indexOf(mark) == check.lastIndexOf(mark)) {
            "the warning is also marked as given before the dialog is shown:\n$check"
        }
    }

    @Test
    fun `all modal dialogs are tracked and dismissed on destroy`() {
        val destroy = code("override fun onDestroy(")
        assertTrue(destroy.contains("dismissActiveDialogs()")) {
            "onDestroy must dismiss tracked modal dialogs to prevent leaked windows:\n$destroy"
        }
        assertTrue(code("internal fun MainActivity.openSafFolder(").contains("trackDialog(dialog)")) {
            "openSafFolder sync dialog is not tracked for teardown dismissal"
        }
        assertTrue(code("internal fun MainActivity.confirmForcedRemoval(").contains("trackDialog(")) {
            "confirmForcedRemoval dialog is not tracked for teardown dismissal"
        }
        assertTrue(code("internal fun MainActivity.showWithReachableButtons(").contains("trackDialog(")) {
            "showWithReachableButtons does not track About and Licenses dialogs for teardown dismissal"
        }
        val licenseTexts = code("internal fun MainActivity.showLicenseTextsDialog(")
        assertTrue(Regex("""trackDialog\(""").findAll(licenseTexts).count() >= 2) {
            "both chooser and nested dialogs in showLicenseTextsDialog must be tracked:\n$licenseTexts"
        }
        assertTrue(code("internal fun MainActivity.checkWebViewVersion(").contains("trackDialog(")) {
            "checkWebViewVersion dialog is not tracked for teardown dismissal"
        }
    }

    @Test
    fun `onDestroy unblocks pending forced removal confirmation latches`() {
        val confirm = code("internal fun MainActivity.confirmForcedRemoval(")
        val destroy = code("override fun onDestroy(")
        assertTrue(confirm.contains("removalLatches()") && confirm.contains("latches.add(answered)")) {
            "confirmForcedRemoval does not register its latch for teardown release:\n$confirm"
        }
        assertTrue(confirm.contains("latches.remove(answered)")) {
            "confirmForcedRemoval does not unregister its latch after waiting:\n$confirm"
        }
        assertTrue(destroy.contains("pendingRemovalLatches") && destroy.contains("it.countDown()")) {
            "onDestroy does not count down pending forced removal latches:\n$destroy"
        }
    }
}
