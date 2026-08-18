package com.vscodroid.setup

import android.content.Context
import android.content.pm.ApplicationInfo
import com.google.android.play.core.assetpacks.AssetPackManagerFactory
import com.vscodroid.util.Environment
import com.vscodroid.util.Logger
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The settings a clean install starts on already keep Python discovery off the
 * native locator.
 *
 * [refreshManagedPaths] pins the same two keys and is covered by
 * `SettingsPathsTest`, so this looks redundant until the order is read:
 * `SplashActivity` runs `updateSettingsNativeLibPaths()` before `runSetup()`,
 * and that refresh returns at its own `!exists()` guard when there is no
 * settings.json yet. On a clean install the refresh therefore cannot be what
 * writes these, and a first session would run on the unpinned document, spawn
 * a `pet` that is not in the artefact Open VSX publishes, and put "Python
 * Locator failed to start" in front of a user who has not opened a file yet.
 *
 * Runs the real writer rather than asserting on the string it embeds, the same
 * way `TerminalShellPathTest` does and for the same reason: the point is the
 * value reaching the file the workbench reads.
 */
class PythonLocatorDefaultTest {

    @TempDir
    lateinit var tempDir: File

    private lateinit var context: Context

    @BeforeEach
    fun setUp() {
        mockkObject(Logger)
        every { Logger.i(any(), any()) } just Runs
        every { Logger.w(any(), any()) } just Runs
        every { Logger.d(any(), any()) } just Runs

        val nativeLibDir = File(tempDir, "nativeLibs").apply { mkdirs() }
        context = mockk(relaxed = true)
        every { context.applicationInfo } returns
            ApplicationInfo().apply { nativeLibraryDir = nativeLibDir.absolutePath }
        every { context.filesDir } returns tempDir
        every { context.cacheDir } returns tempDir
        every { context.getExternalFilesDir(any()) } returns File(tempDir, "external")

        // Constructing ToolchainManager runs Play Core's static setup, which
        // cannot complete off-device; stubbing the factory is enough.
        mockkStatic(AssetPackManagerFactory::class)
        every { AssetPackManagerFactory.getInstance(any()) } returns mockk(relaxed = true)
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    @Test
    fun `a clean install starts with the native locator unreachable`() {
        createDefaultSettings()

        val written = settingsText()
        assertTrue(
            written.contains(""""python.locator": "js""""),
            "the first-run defaults leave the locator unpinned:\n$written",
        )
    }

    @Test
    fun `a clean install does not hand discovery to the environments extension`() {
        // Checked before the locator in the extension's own initialize(), so a
        // true value would bypass the pin above entirely.
        createDefaultSettings()

        val written = settingsText()
        assertTrue(
            written.contains(""""python.useEnvironmentsExtension": false"""),
            "the first-run defaults leave delegation unpinned:\n$written",
        )
    }

    private fun createDefaultSettings() {
        FirstRunSetup::class.java
            .getDeclaredMethod("createDefaultSettings")
            .apply { isAccessible = true }
            .invoke(FirstRunSetup(context))
    }

    private fun settingsText(): String {
        val settings = File(Environment.getMachineSettingsPath(context))
        assertTrue(settings.isFile, "no settings file was written at $settings")
        return settings.readText()
    }
}
