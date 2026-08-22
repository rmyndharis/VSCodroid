package com.vscodroid.setup

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * What a screen reader is told about the first screen the app shows.
 *
 * The launcher icon carried `contentDescription="@string/app_name"` and the
 * TextView directly beneath it renders that same string as visible text, so
 * traversing the splash screen said "VSCodroid" twice before reaching the status
 * line that says what the app is actually doing. The icon is decorative: the name
 * is already on screen.
 *
 * ⚠️ What this cannot see, for the reason `PickerAccessibilityWiringTest` states
 * at length: it reads XML, so it proves the attribute is written, not that a
 * screen reader says anything. `docs/DEVICE_TEST_CHECKLIST.md` is the instrument
 * for that half.
 */
class SplashAccessibilityTest {

    private val layout = File("src/main/res/layout/activity_splash.xml")

    private fun elementFor(id: String): String {
        assertTrue(
            layout.isFile,
            "${layout.path} is not at ${layout.absolutePath}; this test would " +
                "otherwise pass by reading nothing",
        )
        val text = layout.readText()
        val anchor = text.indexOf("""android:id="@+id/$id"""")
        assertTrue(anchor >= 0, "no view with id $id in ${layout.name}")
        // Back to the tag that opens it, forward to the one that closes it.
        val start = text.lastIndexOf('<', anchor)
        val end = text.indexOf("/>", anchor)
        assertTrue(start >= 0 && end > start, "the $id element is not a self-closing tag")
        return text.substring(start, end)
    }

    @Test
    fun `the decorative app icon is not announced`() {
        val icon = elementFor("appIcon")

        assertTrue(
            icon.contains("""android:importantForAccessibility="no""""),
            "the splash icon is still in the traversal order:\n$icon",
        )
        assertFalse(
            icon.contains("android:contentDescription"),
            "the icon describes itself with the same string the label below it renders, " +
                "so the screen opens by saying the app name twice:\n$icon",
        )
    }

    /**
     * The control: the label really does carry that string, so the assertion
     * above is about a duplicate rather than about a description that was never
     * spoken by anything.
     */
    @Test
    fun `the label below it is what carries the name`() {
        assertTrue(
            elementFor("appName").contains("""android:text="@string/app_name""""),
            "nothing on the splash screen says the app name any more",
        )
    }
}
