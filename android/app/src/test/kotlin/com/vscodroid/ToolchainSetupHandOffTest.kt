package com.vscodroid

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * That no toolchain is installed over a setup that has not finished, and that the Toolchains screen
 * still opens then.
 *
 * The launcher shortcut to this screen is published once setup has completed, and it outlives an
 * update. An update runs setup again, which unpacks `usr/` for about a minute, and an install started
 * from this screen in that minute wrote into `usr/` under the unpacking, each of the two counting on
 * storage the other was about to take. So an install asked for then goes to the splash screen, which
 * runs setup.
 *
 * The screen itself has to open: a setup refused for lack of space waits on its error screen with
 * setup still not done, and removing a large toolchain here is how the person frees the space. A
 * screen that handed every launch to the splash screen sent them back to the same refusal.
 *
 * Read from the source, because an Activity cannot be created on the JVM: what is pinned is that the
 * question is asked before an install starts, that it is the question setup answers, and that opening
 * the screen does not ask it.
 */
class ToolchainSetupHandOffTest {

    private val source by lazy {
        SourceScan.withoutComments(SourceScan.read("src/main/kotlin/com/vscodroid/ToolchainActivity.kt"))
    }

    @Test
    fun `the screen opens whatever the state of setup, so Remove can free space`() {
        val create = SourceScan.body(source, "override fun onCreate(")

        assertFalse(
            create.contains("handOffToSetup()"),
            "onCreate sends every launch to the splash screen while setup is not done, so a setup refused " +
                "for lack of space leaves no way to remove a toolchain and free it",
        )
        assertFalse(
            SourceScan.body(source, "override fun onDestroy(").contains("::toolchainManager.isInitialized"),
            "onDestroy still allows for a screen that never built its manager, which only a hand-off in onCreate made",
        )
    }

    @Test
    fun `an install asked for before setup has finished goes to the splash screen and installs nothing`() {
        val install = SourceScan.body(source, "private fun requestInstall(")
        val handOff = install.indexOf("if (handOffToSetup()) return")

        assertTrue(handOff >= 0, "an install no longer asks whether setup has finished")
        for (step in listOf("outstanding.add(packName)", "toolchainManager.install(packName)")) {
            assertTrue(
                install.indexOf(step) > handOff,
                "$step runs before the hand-off, so an install starts over a setup that has not finished",
            )
        }
        assertEquals(
            1,
            Regex("""toolchainManager\.install\(""").findAll(source).count(),
            "an install starts somewhere other than requestInstall, where nothing asks about setup",
        )
        assertTrue(
            SourceScan.body(source, "private fun retryToolchain(").contains("requestInstall(packName)"),
            "Retry no longer installs through requestInstall",
        )
    }

    @Test
    fun `the hand-off asks the question first-run setup answers and keeps this screen`() {
        val body = SourceScan.body(source, "private fun handOffToSetup(")

        assertTrue(body.contains("FirstRunSetup(this).isFirstRun()"), "the hand-off must read what markSetupComplete() writes")
        assertTrue(body.contains("SplashActivity::class.java"), "only the splash screen runs setup")
        assertFalse(
            body.contains("finish()"),
            "the screen is closed by the hand-off, so Back from a setup refused for lack of space no longer " +
                "returns to Remove",
        )
    }

    /**
     * Said by the splash screen, the one in front. Said by this screen before it started the splash, a
     * text too long for a toast at the person's font size (German from a scale of 1.15) became a
     * dialog here, under the splash, and the tap was dropped without a word after all.
     *
     * And said from onResume. A screen counts as started only once onStart has returned, so a long
     * text raised inside onStart or onCreate is cut to a toast and its dialog kept for a later start.
     */
    @Test
    fun `the person is told by the splash screen, once it can show a dialog`() {
        val body = SourceScan.body(source, "private fun handOffToSetup(")
        val splash = SourceScan.withoutComments(SourceScan.read("src/main/kotlin/com/vscodroid/SplashActivity.kt"))
        val create = SourceScan.body(splash, "override fun onCreate(")
        val start = SourceScan.body(splash, "override fun onStart(")
        val resume = SourceScan.body(splash, "override fun onResume(")
        val notice = "AppNotices.showNotice(this, R.string.toolchain_install_after_setup)"

        assertTrue(
            body.contains(".setAction(ACTION_INSTALL_WAITS_FOR_SETUP)"),
            "the splash screen is not told that it was a tap on Install that sent the person there",
        )
        assertFalse(
            body.contains("AppNotices.showNotice("),
            "the notice is raised on this screen, which the splash screen is about to cover",
        )
        assertTrue(
            create.contains("installWaitsNotice = savedInstanceState == null && intent?.action == ACTION_INSTALL_WAITS_FOR_SETUP"),
            "a splash screen made again in place of the first would say it a second time, or the first never reads the intent",
        )
        val told = resume.indexOf(notice)
        assertTrue(told >= 0, "an install asked for before setup has finished is dropped without a word")
        assertTrue(
            resume.indexOf("installWaitsNotice = false") in 0 until told,
            "the notice is not spent before it is shown, so every return to the splash screen repeats it",
        )
        assertFalse(
            create.contains(notice) || start.contains(notice),
            "raised before onStart has returned, a long text is a cut toast now and a dialog only at a later start",
        )
    }

    /**
     * The splash screen is exported. Reading an extra unparcels the whole bundle the sender put on
     * the intent, and up to Android 12 that throws on a class this app does not have, so another app
     * could end the process, and the server with it, by starting this screen.
     */
    @Test
    fun `the splash screen reads no extra of the intent that started it`() {
        val splash = SourceScan.withoutComments(SourceScan.read("src/main/kotlin/com/vscodroid/SplashActivity.kt"))

        assertFalse(
            Regex("""\bintent\??\.(extras|get\w*Extra\w*|has\w*Extra)\b""").containsMatchIn(splash),
            "the splash screen reads an extra of an intent any app can send",
        )
    }
}
