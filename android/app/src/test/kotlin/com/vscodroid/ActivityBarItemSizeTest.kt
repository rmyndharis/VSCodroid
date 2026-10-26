package com.vscodroid

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * That the workbench sizes the activity bar overflow at the height the touch
 * stylesheet draws an item at.
 *
 * `MainActivity` floors `.activitybar .action-item` at 44px for a finger
 * pointer. The workbench decides how many view icons fit from its own idea of
 * an item, and patch 0030 raises that idea to the floor under the same media
 * query. In 1.139.1 the workbench's own number for the compact bar is 28px, so
 * with the two apart the bar admits icons it has no room for: on a phone in
 * landscape no overflow menu appears and the Accounts and Manage buttons are
 * pushed below the bar. Up to 1.133 the workbench used a constant 52, above the
 * floor, which is why nothing disagreed until the pin moved.
 *
 * The stylesheet and patch must agree on the floor and pointer. Node fixtures also
 * execute the patched height getter for both pointers and compact and larger items.
 * These checks cover the sizing decision; they do not render the workbench.
 */
class ActivityBarItemSizeTest {

    @Test
    fun `coarse pointer item height respects both the touch floor and larger items`() {
        WorkbenchPatchBehavior.run("activity")
    }

    @Test
    fun `pointer changes recapture view overflow and global action sizes`() {
        WorkbenchPatchBehavior.run("activity-lifecycle")
    }

    @Test
    fun `live overflow budgets preserve the first gap and explicit insets`() {
        WorkbenchPatchBehavior.run("activity-overflow")
    }

    private val stylesheet: String =
        SourceScan.body(
            SourceScan.read("src/main/kotlin/com/vscodroid/MainActivity.kt"),
            "private fun injectTouchTargetCSS(",
        )

    /** The repository root is two levels above `android/app`, this suite's working directory. */
    private val patch: String = File("../../patches/0030-activitybar-touch-item-size.patch").also {
        check(it.isFile) {
            "${it.absolutePath} not found; if the patch was renumbered, point this case at the " +
                "new name rather than letting it pass by looking at nothing"
        }
    }.readText()

    @Test
    fun `the patch sizes an item at the floor the stylesheet draws`() {
        val floor = Regex("""\.activitybar \.action-item \{ min-height: (\d+)px""")
            .find(stylesheet)?.groupValues?.get(1)
        val sized = Regex("""TOUCH_ACTION_HEIGHT = (\d+);""")
            .find(patch)?.groupValues?.get(1)

        assertNotNull(floor, "MainActivity no longer floors .activitybar .action-item in px")
        assertNotNull(sized, "patch 0030 no longer names TOUCH_ACTION_HEIGHT")
        assertEquals(
            floor,
            sized,
            "the workbench sizes the overflow at ${sized}px while the stylesheet draws " +
                "${floor}px items: the bar admits icons it has no room for and the Accounts " +
                "and Manage buttons are pushed out of view. Change both numbers together",
        )
    }

    @Test
    fun `the patch and the stylesheet answer to the same pointer`() {
        assertTrue(
            stylesheet.contains("@media (pointer: coarse)"),
            "the touch stylesheet no longer gates its floors on a coarse pointer",
        )
        assertTrue(
            patch.contains("matchMedia('(pointer: coarse)')"),
            "patch 0030 must ask the question the stylesheet asks, or one of them sizes an " +
                "item the other does not draw",
        )
    }
}
