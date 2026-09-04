package com.vscodroid

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * That the script keeping the keyboard down is aimed at something that exists,
 * and is installed on every page it has to cover.
 *
 * This is the one piece of this app whose failure is silent in both directions.
 * Aimed at nothing, it does nothing and the keyboard covers half the screen
 * while the user is reading a file: exactly what shipped in a first version of
 * it, which looked for `textarea.inputarea` in a workbench that uses the
 * EditContext API and matched no element at all. Aimed too widely, or installed
 * where a page does not get it, and the editor is one you cannot type into.
 *
 * Source-level, like the rest of this suite, and that is the limit worth
 * stating: it can hold the selector to the name the workbench uses today, and
 * it cannot notice the workbench changing that name in a VS Code bump. What
 * catches that is the device row in `docs/DEVICE_TEST_CHECKLIST.md` (KB-21),
 * which is why that row exists.
 */
class KeyboardGuardWiringTest {

    private companion object {
        const val MAIN_ACTIVITY = "src/main/kotlin/com/vscodroid/MainActivity.kt"
        const val WORKBENCH = "src/main/assets/vscode-reh/out/vs/code/browser/workbench/workbench.js"
    }

    /**
     * The activity's source with its comments blanked.
     *
     * Comments blanked because this file explains the guard at length, and every
     * name the cases below look for appears in that prose. Read raw, a selector
     * deleted from the script still matches its own KDoc and the test passes on
     * a guard that matches nothing, which is the exact failure it exists for.
     */
    private fun mainActivity(): String =
        SourceScan.withoutComments(SourceScan.read(MAIN_ACTIVITY))

    @Test
    fun `the guard is installed from the path every page load takes`() {
        val source = mainActivity()

        assertTrue(
            source.contains("private fun injectKeyboardGuard()"),
            "injectKeyboardGuard is gone, so nothing keeps the keyboard down when a file is " +
                "opened or the Explorer is tapped.",
        )
        // The call sits with the other per-page injections in `injectBridgeToken`,
        // whose one call site is inside the `onPageFinished` lambda, under the
        // `isWorkbenchUrl` test. That is the path which runs again after a folder
        // switch, which the workbench performs by navigating its own WebView
        // without telling this side.
        //
        // Read out of that body rather than off the whole file, because being
        // next to `injectTouchTargetCSS()` is not the property and never was:
        // move both calls into `initBridge`, which runs once per WebView, and
        // they are still adjacent while the guard is gone from every page after
        // the first. That is the regression itself, and an adjacency check
        // passes it.
        val injected = SourceScan.body(source, "private fun injectBridgeToken()")
        for (call in listOf("injectTouchTargetCSS()", "injectKeyboardGuard()")) {
            assertTrue(
                injected.contains(call),
                "`$call` is no longer called from injectBridgeToken, the one path every " +
                    "page load takes. Wherever it went, if that place runs once per WebView " +
                    "rather than once per page, the workbench's first self-navigation drops " +
                    "it: the keyboard then covers half the screen while a file is being read, " +
                    "or the editor is one nothing can be typed into, and neither says why.",
            )
        }
    }

    /**
     * That a scroll is still told apart from a tap.
     *
     * Dragging inside the editor is how a phone scrolls a file, and it goes down
     * on the same text a tap does. A guard that answers at pointerdown raises
     * the keyboard over every scroll, which is the complaint it exists to fix
     * arriving by another route; it was measured doing exactly that before the
     * pointerup branch was written. The three names below are that branch.
     */
    @Test
    fun `a scroll is not a tap`() {
        val source = mainActivity()

        for (name in listOf("pointerup", "pointercancel", "TAP_SLOP")) {
            assertTrue(
                source.contains(name),
                "the guard no longer mentions `$name`, so the decision has moved back to " +
                    "pointerdown and dragging to scroll a file raises the keyboard again.",
            )
        }
    }

    @Test
    fun `the guard aims at the element this workbench actually focuses`() {
        val source = mainActivity()

        assertTrue(
            source.contains("native-edit-context"),
            "the guard no longer names `native-edit-context`. That is the element the shipped " +
                "workbench focuses for the caret, so a guard without it matches nothing and " +
                "silently does nothing at all.",
        )

        // The other direction: that the name still describes the workbench in
        // the tree. Skipped rather than failed when the tree is absent, because
        // it is a gitignored artifact and a fresh clone has none. Stated as an
        // assumption rather than an `if`, so a run that cannot make this check
        // says so: the unit-test job in build.yml stubs `assets/vscode-reh`, and
        // a silent skip there reads as a green check of something nothing ran.
        val workbench = File(WORKBENCH)
        assumeTrue(
            workbench.isFile,
            "no packaged workbench at ${workbench.path}; run scripts/fetch-vscode-oss.sh and " +
                "scripts/package-assets.sh to check the selector against the shipped bundle",
        )
        run {
            assertTrue(
                workbench.readText().contains("native-edit-context"),
                "the packaged workbench no longer mentions `native-edit-context`, so the " +
                    "selector in MainActivity now matches nothing. Open the editor over the " +
                    "DevTools protocol, read `document.activeElement` with the caret in a " +
                    "file, and point the guard at what it answers.",
            )
        }
    }
}
