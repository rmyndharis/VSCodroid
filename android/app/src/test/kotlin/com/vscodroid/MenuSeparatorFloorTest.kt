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
            SourceScan.read("src/main/kotlin/com/vscodroid/MainActivityPageScripts.kt"),
            "internal fun MainActivity.injectTouchTargetCSS(",
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

    @Test
    fun `menu item highlights fill the enlarged row and labels clear the check column`() {
        assertTrue(
            mobileMenuBlock.contains(".action-menu-item") &&
                mobileMenuBlock.contains("min-height: 44px") &&
                mobileMenuBlock.contains("height: auto"),
            "workbench.css raises .action-item to 44px while leaving .action-menu-item at 24px, " +
                "so the hover/focus highlight covers only a 24px strip above the label",
        )
        assertTrue(
            mobileMenuBlock.contains("max-height: none") &&
                mobileMenuBlock.contains("2em") &&
                !mobileMenuBlock.contains("padding: 8px 24px 8px 12px"),
            "workbench.css caps label max-height at 100% of 24px and sets 12px left padding, " +
                "overlapping the 2em check mark with the first letter",
        )
        assertTrue(
            injected.contains(".context-view .monaco-menu .action-menu-item") &&
                injected.contains("min-height: 40px") &&
                injected.contains("height: auto"),
            "MainActivity floors .context-view .action-item at 40px without expanding .action-menu-item",
        )
        assertTrue(
            injected.contains("max-height: none") &&
                injected.contains("2em") &&
                !injected.contains("padding: 6px 12px !important"),
            "MainActivity sets 12px left padding and inherits 100% max-height on .context-view menu labels",
        )
    }

    @Test
    fun `titlebar chat toggle and more actions expand to touch-usable targets`() {
        val pillRule = Regex("""\.part\.titlebar \.agent-status-pill\s*\{[^}]+\}""").find(injected)?.value
        val chatRule = Regex("""\.part\.titlebar \.agent-status-badge-section\.sparkle \.action-container\s*\{[^}]+\}""")
            .find(injected)?.value
        val moreRule = Regex("""\.part\.titlebar \.agent-status-badge-section\.sparkle \.dropdown-action-container\s*\{[^}]+\}""")
            .find(injected)?.value
        assertTrue(pillRule != null && pillRule.contains("height:"), "titlebar pill height missing in coarse sheet")
        assertTrue(chatRule != null && chatRule.contains("min-width: 36px"), "titlebar Chat toggle min-width < 36px")
        assertTrue(moreRule != null && moreRule.contains("width: 32px"), "titlebar More Actions width < 32px")
    }

    @Test
    fun `portrait welcome walkthrough wraps shortcut messages and constrains step buttons`() {
        val narrowBlock = injected.substringAfter("@media (max-width: 533px) {", "")
        assertTrue(narrowBlock.isNotEmpty(), "narrow viewport @media block missing in injectTouchTargetCSS")

        val stepContainerRule = Regex("""\.gettingStartedSlideDetails \.getting-started-step \.step-container\s*\{[^}]+\}""")
            .find(narrowBlock)?.value
        val shortcutRule = Regex("""\.gettingStartedSlideDetails \.getting-started-step \.shortcut-message\s*\{[^}]+\}""")
            .find(narrowBlock)?.value
        val keybindingRule = Regex("""\.gettingStartedSlideDetails \.getting-started-step \.shortcut-message \.monaco-keybinding\s*\{[^}]+\}""")
            .find(narrowBlock)?.value
        val buttonRule = Regex("""\.gettingStartedSlideDetails \.getting-started-step \.step-description-container \.monaco-button\s*\{[^}]+\}""")
            .find(narrowBlock)?.value

        assertTrue(
            stepContainerRule != null && stepContainerRule.contains("min-width: 0"),
            "narrow viewport CSS must set min-width: 0 on .gettingStartedSlideDetails .getting-started-step .step-container",
        )
        assertTrue(
            shortcutRule != null &&
                shortcutRule.contains("flex-wrap: wrap") &&
                shortcutRule.contains("white-space: normal") &&
                shortcutRule.contains("row-gap: 4px"),
            "narrow viewport CSS must wrap .shortcut-message with normal whitespace so keycaps do not clip",
        )
        assertTrue(
            keybindingRule != null &&
                keybindingRule.contains("flex-wrap: wrap") &&
                keybindingRule.contains("row-gap: 2px"),
            "narrow viewport CSS must wrap .shortcut-message .monaco-keybinding chords",
        )
        assertTrue(
            buttonRule != null &&
                buttonRule.contains("max-width: 100%") &&
                buttonRule.contains("min-width: 0"),
            "narrow viewport CSS must clamp .step-description-container .monaco-button within 100% width",
        )
    }
}
