package com.vscodroid

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * That the script letting a finger drag the caret is installed where every page load
 * passes, takes the touch before the editor does, and still names what the shipped
 * workbench calls the things it reaches for.
 *
 * Source-level, like [ComposingEnterWiringTest], with the same ceiling: it cannot see
 * whether a finger drags a caret on a device, only that the script, the workflows that
 * run its self-check and the bundle still agree. What the script does with a touch is
 * `scripts/test-caret-drag.js`.
 */
class CaretDragWiringTest {

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
            source.contains("private fun injectCaretDrag()"),
            "injectCaretDrag is gone, so a finger can no longer drag the caret.",
        )
        val bridgeToken = SourceScan.body(source, "private fun injectBridgeToken(")
        assertTrue(
            bridgeToken.contains("injectCaretDrag()"),
            "injectCaretDrag() is not called from injectBridgeToken, the one path that runs " +
                "again after the workbench switches folders by navigating its own WebView.",
        )
    }

    @Test
    fun `the touch is taken before the editor hears it and the move can be cancelled`() {
        val script = SourceScan.body(mainActivity(), "private fun injectCaretDrag(")
        assertTrue(
            script.contains("window.addEventListener('touchmove'") &&
                script.contains("}, { capture: true, passive: false });"),
            "the move listener must be in the capture phase and not passive: the editor's own " +
                "gesture listens on the document and would scroll under the drag, and a passive " +
                "listener cannot cancel the page's scroll",
        )
        assertTrue(
            script.contains("e.stopImmediatePropagation();"),
            "a taken touch has to be kept from the editor, whose gesture would scroll it",
        )
    }

    @Test
    fun `the caret is moved with the editor's own tap`() {
        val script = SourceScan.body(mainActivity(), "private fun injectCaretDrag(")
        assertTrue(
            script.contains("'-monaco-gesturetap'"),
            "the caret is moved by the editor's tap, which the keyboard guard also listens for " +
                "to end a composing word; another route would leave a composition standing",
        )
    }

    @Test
    fun `the keyboard guard reads the mark the script leaves on a taken drag`() {
        val source = mainActivity()
        val guard = SourceScan.body(source, "private fun injectKeyboardGuard(")
        val script = SourceScan.body(source, "private fun injectCaretDrag(")
        assertTrue(
            script.contains("window.__vscodroidCaretDragged = true;"),
            "the script no longer marks the touch it takes, so the guard cannot tell a drag of " +
                "a character from a tap",
        )
        assertTrue(
            guard.contains("if (window.__vscodroidCaretDragged) return;"),
            "the keyboard guard no longer lets a marked touch go by: a drag of a character or two " +
                "travels no further than a tap, and the guard raises a held keyboard for a tap",
        )
    }

    @Test
    fun `the self-check runs in the lint and release workflows`() {
        for (workflow in listOf("lint.yml", "release.yml")) {
            val text = File("../../.github/workflows/$workflow").also {
                check(it.isFile) { "${it.absolutePath} not found" }
            }.readText()
            assertTrue(
                text.contains("node scripts/test-caret-drag.js"),
                "$workflow does not run scripts/test-caret-drag.js, so a change to the script " +
                    "is checked by nothing that runs on a pull request or a release",
            )
        }
    }

    @Test
    fun `the names it takes from the workbench are still in the shipped bundle`() {
        val workbench = File(WORKBENCH)
        assumeTrue(
            workbench.isFile,
            "no packaged workbench at ${workbench.path}; run scripts/fetch-vscode-oss.sh and " +
                "scripts/package-assets.sh to check the names against the shipped bundle",
        )
        val bundle = workbench.readText()
        val script = SourceScan.body(mainActivity(), "private fun injectCaretDrag(")
        val selectors = Regex("""querySelector(?:All)?\('([^']+)'\)""")
            .findAll(script).joinToString(" ") { it.groupValues[1] }
        val classes = Regex("""\.([a-zA-Z][\w-]*)""")
            .findAll(selectors).map { it.groupValues[1] }.toSet()
        assertTrue(classes.isNotEmpty(), "no caret drag selectors were read")
        for (name in classes + "-monaco-gesturetap") {
            assertTrue(
                bundle.contains(name),
                "the shipped workbench no longer contains `$name`, which the caret drag script " +
                    "looks for; after a pin bump it would find nothing and quietly do nothing",
            )
        }
    }
}
