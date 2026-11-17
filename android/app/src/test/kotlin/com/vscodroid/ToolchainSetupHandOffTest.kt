package com.vscodroid

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * That the Toolchains screen does not open over a setup that has not run.
 *
 * The launcher shortcut to this screen is published once setup has completed, and it outlives an
 * update. An update runs setup again, which unpacks `usr/` for about a minute, and a long press on
 * the shortcut in that minute opened this screen: a toolchain installed from it wrote into `usr/`
 * under the unpacking, and each of the two measured storage the other was about to take. The editor
 * hands such a launch to the splash screen (`EditorEntryTest`), and this screen has to as well.
 *
 * Read from the source, because an Activity cannot be created on the JVM: what is pinned is that the
 * question is asked before anything of the screen is built, that it is the question setup answers,
 * and that a screen finished this early is destroyed without touching what it never made.
 */
class ToolchainSetupHandOffTest {

    private val source by lazy {
        SourceScan.withoutComments(SourceScan.read("src/main/kotlin/com/vscodroid/ToolchainActivity.kt"))
    }

    @Test
    fun `a launch before setup goes to the splash screen before the screen is built`() {
        val create = SourceScan.body(source, "override fun onCreate(")
        val handOff = create.indexOf("if (handOffToSetup()) return")

        assertTrue(handOff >= 0, "onCreate no longer hands a launch that precedes setup to the splash screen")
        assertTrue(
            create.indexOf("super.onCreate(savedInstanceState)") in 0 until handOff,
            "the hand-off returns before super.onCreate, which the platform answers by throwing",
        )
        for (built in listOf("setContentView(", "ToolchainManager(")) {
            assertTrue(
                create.indexOf(built) > handOff,
                "$built runs before the hand-off, so the screen is partly built over a setup in progress",
            )
        }
    }

    @Test
    fun `the hand-off asks the question first-run setup answers`() {
        val body = SourceScan.body(source, "private fun handOffToSetup(")

        assertTrue(body.contains("FirstRunSetup(this).isFirstRun()"), "the hand-off must read what markSetupComplete() writes")
        assertTrue(body.contains("SplashActivity::class.java"), "only the splash screen runs setup")
        assertTrue(body.contains("finish()"), "the screen stays in the back stack under the setup it handed to")
    }

    @Test
    fun `a screen finished in onCreate is destroyed without the manager it never made`() {
        assertTrue(
            SourceScan.body(source, "override fun onDestroy(").contains("::toolchainManager.isInitialized"),
            "onDestroy reads the manager a handed-off launch never created, which throws",
        )
    }
}
