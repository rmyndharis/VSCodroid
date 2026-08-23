package com.vscodroid

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * That no touch-target floor this project writes lands on a menu separator.
 *
 * A separator in a Monaco menu is an `.action-item` like every other row, and
 * its label carries `.separator`. Two places here raise a minimum height on
 * `.action-item` so a finger has something to hit: the CSS `MainActivity`
 * injects, and the block `scripts/build-vscode-oss.sh` appends to
 * `workbench.css` at build time. Both floors reach the divider, and a 1px line
 * held open to 44px is a blank band.
 *
 * Measured on an API 37 emulator at 411px portrait before the exemptions: each
 * of the File menu's seven separators was 71px and the menu was 1427px tall in
 * an 810px viewport. With them: 11px and 1007px, every real row still 44px.
 * That is 420px of nothing, on the one menu that is the only route to File,
 * Edit, Selection, View, Go, Run and Terminal on a phone.
 *
 * Reading source rather than rendering, because the property is a CSS rule
 * inside a string literal in one file and inside a heredoc in another, and no
 * JVM test can lay either out. So this cannot see a rule that is present and
 * wrong, only one that is absent: what it defends against is the next floor
 * being added without its exemption, which is exactly how these two arrived.
 */
class MenuSeparatorFloorTest {

    private val injected: String =
        SourceScan.body(
            SourceScan.read("src/main/kotlin/com/vscodroid/MainActivity.kt"),
            "private fun injectTouchTargetCSS(",
        )

    /** The repository root is two levels above `android/app`, this suite's working directory. */
    private val buildScript: String = File("../../scripts/build-vscode-oss.sh").also {
        check(it.isFile) {
            "${it.absolutePath} not found; a case reading it would pass by looking at nothing"
        }
    }.readText()

    private val mobileMenuBlock: String =
        buildScript.substringAfter("/* VSCodroid: Mobile-friendly hamburger menu overrides */")
            .substringBefore("CSSEOF")

    @Test
    fun `the injected floors exempt a separator label`() {
        assertTrue(
            injected.contains(".activitybar .action-label.separator") &&
                injected.contains(".context-view .action-label.separator"),
            "MainActivity raises .activitybar/.context-view .action-label to 44px and the " +
                "compact menubar's menus render inside both, so a separator label needs the " +
                "floor lifted or the divider is drawn as a blank band",
        )
    }

    @Test
    fun `the injected floors exempt the separator's own row`() {
        assertTrue(
            injected.contains(".activitybar .action-item:has(> .action-label.separator)") &&
                injected.contains(".context-view .action-item:has(> .action-label.separator)"),
            "the label exemption alone is not enough: the row's own min-height still holds " +
                "it open, measured at 44px with the label already flat",
        )
    }

    @Test
    fun `the build-time menu block exempts a separator`() {
        assertTrue(
            mobileMenuBlock.contains(".action-label.separator") &&
                mobileMenuBlock.contains("min-height: 0 !important"),
            "the block appended to workbench.css floors .action-item at 44px and gives every " +
                "label 8px of padding with a 28px line box; both reach the divider",
        )
        assertTrue(
            mobileMenuBlock.contains(
                ".monaco-menu .monaco-action-bar.vertical .action-item:has(> .action-label.separator)",
            ),
            "and so does the row's own floor",
        )
    }

    @Test
    fun `every action-item floor this project writes is paired with an exemption`() {
        val floors = Regex("""\.action-item\s*\{[^}]*min-height:\s*(\d+)px""")
        listOf("injected CSS" to injected, "build-time menu block" to mobileMenuBlock)
            .forEach { (where, css) ->
                floors.findAll(css).forEach { m ->
                    val px = m.groupValues[1].toInt()
                    assertTrue(
                        px == 0 || css.contains(".action-item:has(> .action-label.separator)"),
                        "$where floors .action-item at ${px}px with no separator exemption " +
                            "beside it, which renders every menu divider as a ${px}px band",
                    )
                }
            }
    }
}
