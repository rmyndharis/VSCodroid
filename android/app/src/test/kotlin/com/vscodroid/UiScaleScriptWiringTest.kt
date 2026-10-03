package com.vscodroid

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * That every WebView gets the UI scale script before it loads anything.
 *
 * The script itself, and what it does to the page, is exercised by
 * `scripts/test-ui-scale.js`, which runs it. What that cannot see is whether a
 * WebView is ever given it: a document-start script runs only in documents that
 * begin loading after it was added, so one added after the first load, or only to
 * the first WebView and not to the one [MainActivity] builds after a renderer
 * crash, leaves the user's size unapplied with nothing anywhere saying so.
 *
 * Source reading, the weaker layer, for the reason `RendererDeathNoteTest` reads
 * the bootstrap client: the sites are inside an Activity, and the androidx.webkit
 * statics cannot be mocked on this JVM (mockk's retransformation of
 * `WebViewFeature` fails with "class redefinition failed: invalid class").
 *
 * NEGATIVE CONTROL, run: with the call removed from `setupWebView`, with the
 * feature test removed, with the try removed, and with a rule naming a port, the
 * case for each reddens.
 */
class UiScaleScriptWiringTest {

    private val source = SourceScan.read("src/main/kotlin/com/vscodroid/MainActivity.kt")

    private fun code(declaration: String): String =
        SourceScan.withoutComments(SourceScan.body(source, declaration))

    @Test
    fun `every WebView gets the script before its first load`() {
        val setup = code("private fun setupWebView()")
        val added = setup.indexOf("addUiScaleScript(wv)")
        val loaded = setup.indexOf("wv.loadData(")
        assertTrue(loaded >= 0) { "setupWebView no longer loads its placeholder, so this case measures nothing" }
        assertTrue(added in 0 until loaded) {
            "setupWebView does not add the UI scale script before the WebView loads its first " +
                "page, and a document-start script runs only in pages that start loading after it"
        }

        // The other WebView: the one built after a renderer crash, before the
        // workbench is loaded into it again.
        val recreate = code("private fun recreateWebView()")
        val setUp = recreate.indexOf("setupWebView()")
        val reloaded = recreate.indexOf("loadVSCode(")
        assertTrue(setUp in 0 until reloaded) {
            "recreateWebView no longer sets up the new WebView through setupWebView before " +
                "loading the workbench into it, so a renderer crash loses the UI scale"
        }
    }

    @Test
    fun `the script is added for every origin, only where it can run, and never fatally`() {
        val add = code("internal fun addUiScaleScript(")
        // Every origin, because a rule cannot leave the port open and one without
        // a port matches port 80 only, while the server's port is not known when
        // the WebView is set up. The script checks the frame and the address.
        val call = add.indexOf(
            "WebViewCompat.addDocumentStartJavaScript(webView, uiScaleScript(), setOf(\"*\"))"
        )
        assertTrue(call >= 0) {
            "addUiScaleScript no longer adds uiScaleScript() for every origin. A rule naming " +
                "127.0.0.1 without a port matches port 80, which the server never uses"
        }
        val tested = add.indexOf("WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)")
        assertTrue(tested in 0 until call) {
            "the script is added without asking whether the WebView supports document-start " +
                "scripts, and on one that does not the call throws"
        }
        // This runs in onCreate, and an exception there closes the app.
        val tried = add.indexOf("try {")
        val caught = add.indexOf("catch (e: RuntimeException)")
        assertTrue(tried in 0 until call && caught > call) {
            "a WebView provider that refuses the script would close the app from onCreate, " +
                "where the editor matters more than its size"
        }
    }
}
