package com.vscodroid

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The Open Folder picker is another app, and the page it answers is judged on its way back from the background:
 * a trip that takes more than five minutes would reload the page ahead of the answer, raise the leave-page dialog
 * over the sync progress and load the page again for the chosen folder. The file chooser and the download's save
 * picker are kept out of that reload; this pins that the folder picker is too.
 *
 * Read out of the source, because no JVM test here can build the Activity.
 */
class FolderPickerPendingTest {

    private val source = SourceScan.readAll(
        listOf(
            "src/main/kotlin/com/vscodroid/MainActivity.kt",
            "src/main/kotlin/com/vscodroid/MainActivitySafCoordinator.kt",
        )
    )

    private fun code(declaration: String) = SourceScan.withoutComments(SourceScan.body(source, declaration))

    @Test
    fun `the resume decision counts an open folder picker as a pending picker`() {
        // An expression body, so there is no brace for SourceScan.body to take.
        val at = source.indexOf("private fun fileChooserIsPending(): Boolean =")
        check(at >= 0) { "fileChooserIsPending moved; point this case at the new site" }
        val expression = source.substring(at, source.indexOf("\n\n", at))
        assertTrue("|| folderPickerPending" in expression) {
            "a return from the folder picker after more than five minutes reloads the page ahead of its answer:\n$expression"
        }
    }

    @Test
    fun `the flag is set before the launch and cleared by the answer and by a launch that fails`() {
        val open = code("fun MainActivity.openFolderPicker(")
        val set = open.indexOf("folderPickerPending = true")
        val launch = open.indexOf("folderPickerLauncher.launch(null)")
        assertTrue(set in 0 until launch) { "the flag is not set before the folder picker is launched:\n$open" }
        assertTrue(Regex("""catch \(e: ActivityNotFoundException\) \{\s*folderPickerPending = false""").containsMatchIn(open)) {
            "a device with no document tree picker keeps the page from reloading for good:\n$open"
        }
        assertTrue(Regex("""catch \(e: IllegalStateException\) \{\s*folderPickerPending = false""").containsMatchIn(open)) {
            "a launch that is refused keeps the page from reloading for good:\n$open"
        }
        val answer = code("internal val folderPickerLauncher =")
        assertTrue(Regex("""\{ uri ->\s*folderPickerPending = false\s*uri\?\.let""").containsMatchIn(answer)) {
            "the picker's answer, or its cancellation, does not clear the flag:\n$answer"
        }
    }
}
