package com.vscodroid.setup

import android.widget.ProgressBar
import android.widget.TextView
import com.google.android.play.core.assetpacks.model.AssetPackStatus
import com.vscodroid.R
import com.vscodroid.SourceScan
import com.vscodroid.SplashActivity
import com.vscodroid.util.Logger
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.lang.reflect.InvocationTargetException

/**
 * Play's cellular-data question during the first-run downloads, and when it is put.
 *
 * Play holds a large download on mobile data until the app shows its question, and the question
 * is a window that takes the foreground. The queue keeps running while the person is in another
 * app, so the question used to be put from a stopped screen: over whatever they had moved to, or
 * refused by the platform's rules on starts from the background with nothing said, after which
 * nothing asked again and the queue sat on that pack until Cancel. It now waits for the screen's
 * next start, as the Toolchains screen's question does, and a screen that is destroyed before that
 * start ends the pack the way a question it cannot put always has, so the packs behind it still come.
 */
class SplashCellularQuestionTest {

    private lateinit var screen: SplashActivity
    private lateinit var manager: ToolchainManager
    private val events = mutableListOf<String>()
    private val statusTexts = mutableMapOf<String, TextView>()
    private var destroyed = false

    private fun field(name: String) = SplashActivity::class.java.getDeclaredField(name).apply { isAccessible = true }

    private fun state(pack: String, status: Int) {
        SplashActivity::class.java.getDeclaredMethod(
            "handleDownloadState", String::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
        ).apply { isAccessible = true }.invoke(screen, pack, status, 0)
    }

    @BeforeEach
    fun setUp() {
        mockkObject(Logger)
        every { Logger.d(any(), any()) } just Runs
        every { Logger.i(any(), any()) } just Runs
        every { Logger.w(any(), any()) } just Runs
        every { Logger.e(any(), any(), any()) } just Runs
        manager = mockk(relaxed = true)
        every { manager.showConfirmationDialog(any()) } answers { events += "asked" }
        screen = mockk(relaxed = true)
        field("tag").set(screen, "SplashActivity")
        field("toolchainManager").set(screen, manager)
        field("downloadQueue").set(screen, mutableListOf("toolchain_java", "toolchain_ruby"))
        field("currentDownloadIndex").set(screen, 0)
        val rowType = Class.forName("com.vscodroid.SplashActivity\$ProgressRow")
        val rowConstructor = rowType.getDeclaredConstructor(TextView::class.java, ProgressBar::class.java, TextView::class.java)
            .apply { isAccessible = true }
        val rows = mutableMapOf<String, Any>()
        for (pack in listOf("toolchain_java", "toolchain_ruby")) {
            val status = mockk<TextView>(relaxed = true)
            statusTexts[pack] = status
            rows[pack] = rowConstructor.newInstance(mockk<TextView>(relaxed = true), mockk<ProgressBar>(relaxed = true), status)
        }
        field("progressRows").set(screen, rows)
        every { screen.isFinishing } returns false
        every { screen.isDestroyed } answers { destroyed }
        every { screen.getString(R.string.progress_failed) } returns "Failed"
        every { screen["handleDownloadState"](any<String>(), any<Int>(), any<Int>()) } answers { callOriginal() }
        every { screen["downloadNext"]() } answers { events += "next" }
        every { screen["askForCellularConfirmation"]() } answers { callOriginal() }
        every { screen["onDestroy"]() } answers { callOriginal() }
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    @Test
    fun `a question asked while the screen is stopped waits for its next start`() {
        field("stopped").setBoolean(screen, true)

        state("toolchain_java", AssetPackStatus.REQUIRES_USER_CONFIRMATION)

        verify(exactly = 0) { manager.showConfirmationDialog(any()) }
        assertEquals(emptyList<String>(), events, "the pack waiting on the question was skipped")
        assertTrue(field("confirmationDeferred").getBoolean(screen), "the question is not kept for the next start")

        val onStart = SourceScan.withoutComments(
            SourceScan.body(SourceScan.read("src/main/kotlin/com/vscodroid/SplashActivity.kt"), "override fun onStart("),
        )
        assertTrue(
            Regex("""if \(confirmationDeferred\) \{\s*confirmationDeferred = false\s*askForCellularConfirmation\(\)""")
                .containsMatchIn(onStart),
            "onStart does not put a question that waited for it, so the pack waits for ever",
        )
    }

    @Test
    fun `a started screen puts the question at once`() {
        state("toolchain_java", AssetPackStatus.REQUIRES_USER_CONFIRMATION)

        verify(exactly = 1) { manager.showConfirmationDialog(screen) }
        assertFalse(field("confirmationDeferred").getBoolean(screen))
    }

    @Test
    fun `a status that moves the pack on takes the waiting question back`() {
        field("stopped").setBoolean(screen, true)
        state("toolchain_java", AssetPackStatus.REQUIRES_USER_CONFIRMATION)

        // Answered from somewhere else, the Toolchains screen or Play's own notification.
        state("toolchain_java", AssetPackStatus.DOWNLOADING)

        assertFalse(field("confirmationDeferred").getBoolean(screen), "a question nobody waits on is put at the next start")
        verify(exactly = 0) { manager.showConfirmationDialog(any()) }
    }

    @Test
    fun `a waiting question on a screen that is destroyed ends the pack and moves the queue on`() {
        field("stopped").setBoolean(screen, true)
        state("toolchain_java", AssetPackStatus.REQUIRES_USER_CONFIRMATION)

        // The platform marks the screen destroyed before it calls onDestroy.
        destroyed = true
        try {
            SplashActivity::class.java.getDeclaredMethod("onDestroy").apply { isAccessible = true }.invoke(screen)
        } catch (e: InvocationTargetException) {
            val cause = e.cause ?: throw e
            assertTrue(
                cause is RuntimeException && cause.message?.startsWith("Method onDestroy in android.app.Activity not mocked.") == true,
                "unexpected teardown failure: $cause",
            )
        }

        assertEquals(
            listOf("next"), events.filter { it == "next" },
            "the queue stopped on a question no screen will ever put, so the packs behind it never come " +
                "and this destroyed screen stays registered with Play for the life of the process",
        )
        verify { statusTexts.getValue("toolchain_java").text = "Failed" }
        verify(exactly = 0) { manager.showConfirmationDialog(any()) }
    }
}
