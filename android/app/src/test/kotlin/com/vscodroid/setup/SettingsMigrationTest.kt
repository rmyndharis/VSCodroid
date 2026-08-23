package com.vscodroid.setup

import android.content.Context
import android.content.pm.ApplicationInfo
import com.vscodroid.util.Environment
import com.vscodroid.util.Logger
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.lang.reflect.InvocationTargetException

/**
 * Tests the one-shot move of settings.json from the path this app used to write
 * to the path the workbench reads.
 *
 * The move is guarded by "does the destination already exist", which makes any
 * partial arrival permanent: a byte-copy interrupted by a full disk or by
 * process death leaves a truncated file under the destination name, the guard
 * sees it on every later launch and returns, and the intact original is left
 * behind unread. The workbench does not report a truncated settings.json as an
 * error -- it reads it as the settings -- so the user simply loses preferences
 * with nothing to say why.
 *
 * The first-run write is covered here too, for the same reason and against the
 * same guard: it also decides by asking whether the file is there.
 *
 * The failure is arranged the way [UpdateSettingsPathsTest] arranges its own:
 * by occupying the temporary path [writeAtomically] derives from the
 * destination. That fails the write without depending on file permissions,
 * which differ depending on who runs the tests.
 */
class SettingsMigrationTest {

    @TempDir
    lateinit var filesDir: File

    private lateinit var context: Context
    private lateinit var legacy: File
    private lateinit var current: File

    /** Distinctive enough that its survival can be asserted on by name. */
    private val userSettings = """
        {
            "workbench.colorTheme": "Monokai",
            "editor.fontSize": 17
        }
    """.trimIndent()

    @BeforeEach
    fun setUp() {
        mockkObject(Logger)
        every { Logger.d(any(), any()) } just Runs
        every { Logger.i(any(), any()) } just Runs
        every { Logger.w(any(), any(), any()) } just Runs
        every { Logger.e(any(), any(), any()) } just Runs

        context = mockk(relaxed = true)
        every { context.filesDir } returns filesDir
        every { context.applicationInfo } returns ApplicationInfo().apply {
            nativeLibraryDir = "/data/app/~~hash==/com.vscodroid-hash==/lib/arm64"
        }

        legacy = File(filesDir, "home/.vscodroid/User/settings.json")
        legacy.parentFile?.mkdirs()
        legacy.writeText(userSettings)

        current = File(Environment.getMachineSettingsPath(context))
        current.parentFile?.mkdirs()
    }

    @AfterEach
    fun tearDown() = unmockkObject(Logger)

    /** Non-empty, so the cleanup `delete()` cannot quietly reclaim it. */
    private fun blockTheWrite(): File =
        File(current.parentFile, "${current.name}.tmp~").also {
            assertTrue(it.mkdirs(), "could not stage the blocked temp path")
            File(it, "occupied").writeText("x")
        }

    /**
     * The control. Without it, every assertion below would also hold for a
     * migration that never runs at all.
     */
    @Test
    fun `moves the settings the workbench never read to the path it does`() {
        FirstRunSetup(context).updateSettingsNativeLibPaths()

        assertTrue(current.isFile, "settings.json never reached the path the workbench reads")
        assertTrue(current.readText().contains("Monokai"), "the user's settings did not survive the move")
        assertFalse(legacy.exists(), "the original was left behind, so the move can run twice")
    }

    /**
     * The defect: what must be true at the destination when the write cannot be
     * completed. Nothing -- because anything there at all is what the guard
     * reads on the next launch, and a partial file is indistinguishable from a
     * complete one to `exists()`.
     */
    @Test
    fun `a failed move puts nothing at the destination`() {
        blockTheWrite()

        FirstRunSetup(context).updateSettingsNativeLibPaths()

        assertFalse(
            current.exists(),
            "a file was left at the destination despite the write failing; the guard reads " +
                "that as a completed migration and the original is orphaned unread",
        )
        assertEquals(
            userSettings,
            legacy.readText(),
            "the original was consumed by a move that did not complete",
        )
    }

    /**
     * And the reason the assertion above is worth having: the failure has to be
     * something a later launch can still finish. A destination left occupied by
     * a partial copy is not.
     */
    @Test
    fun `the move completes on a later launch once the write can succeed`() {
        val blocker = blockTheWrite()
        FirstRunSetup(context).updateSettingsNativeLibPaths()

        blocker.deleteRecursively()
        FirstRunSetup(context).updateSettingsNativeLibPaths()

        assertTrue(current.isFile, "the migration never recovered")
        assertTrue(current.readText().contains("Monokai"), "the user's settings were lost in the retry")
        assertFalse(legacy.exists(), "the original survived a completed move")
    }

    /** Reflection rather than widened visibility, as [TerminalShellPathTest] does. */
    private fun createDefaultSettings() {
        FirstRunSetup::class.java
            .getDeclaredMethod("createDefaultSettings")
            .apply { isAccessible = true }
            .invoke(FirstRunSetup(context))
    }

    @Test
    fun `writes the defaults on a first run`() {
        legacy.delete()

        createDefaultSettings()

        assertTrue(current.isFile, "no settings file was written")
        assertTrue(current.readText().contains("workbench.startupEditor"), "the defaults are not in it")
    }

    /**
     * Two properties, and the second is the one that took a correction.
     *
     * Leaving nothing at the destination is necessary but not sufficient. This
     * runs inside `runSetupLocked`, whose `markSetupComplete()` sits at the end
     * of the same try block -- so a failure that merely logs lets setup be
     * certified with no settings file at all, and `isFirstRun()` is keyed on
     * versionName or versionCode, meaning nothing writes it again until the app
     * updates. The
     * every-launch repair cannot help either: `updateSettingsNativeLibPaths`
     * opens with `if (!settingsFile.exists()) return`.
     *
     * So the write has to fail *loudly*. An exception reaches
     * `runSetupLocked`'s catch, becomes SetupResult.ERROR, and SplashActivity
     * puts a Retry button on screen. Atomicity is what makes that retry worth
     * offering: the destination is absent rather than truncated, so the retry's
     * `if (!settingsFile.exists())` is true and it writes the file properly.
     * Neither half achieves that alone.
     */
    @Test
    fun `a failed first-run write leaves nothing behind and fails loudly`() {
        legacy.delete()
        blockTheWrite()

        val thrown = assertThrows(InvocationTargetException::class.java) { createDefaultSettings() }

        assertTrue(
            thrown.cause is IOException,
            "the failure must reach runSetupLocked's catch as an exception so markSetupComplete " +
                "is skipped; it surfaced as ${thrown.cause}",
        )
        assertFalse(
            current.exists(),
            "a truncated defaults file was left where the guard reads it, so the retry would " +
                "skip it and the missing settings would never be written",
        )
    }
}
