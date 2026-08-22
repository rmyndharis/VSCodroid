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
 * What the editor looks like on the first screen a user ever sees.
 *
 * The secondary side bar is upstream's home for the chat view, its default is
 * `visibleInWorkspace`, and a brand-new profile opens it regardless of that
 * value. On a desktop that costs a quarter of the width; on the phone this app
 * exists for it takes a large share of a narrow window, and it does it beside
 * whatever the walkthrough opened, which then wraps mid-word.
 *
 * The provider the view exists for is not in this build: the Copilot extension
 * is pruned from the server tree, so the panel is an onboarding pane for
 * something that cannot be onboarded. The width buys nothing back.
 *
 * Written into the first-run defaults rather than anywhere else, which decides
 * who it reaches: a clean install, once. `createDefaultSettings` writes only
 * when `settings.json` is absent, so an upgrading user keeps the window they
 * already arranged, and this is a default rather than a lock, so a user who
 * opens the bar keeps it open.
 *
 * Runs the real writer rather than asserting on the string it embeds, the way
 * `PythonLocatorDefaultTest` and `TerminalShellPathTest` do: the point is the
 * value reaching the file the workbench reads.
 */
class FirstRunEditorDefaultsTest {

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
    fun `a clean install opens with the secondary side bar closed`() {
        createDefaultSettings()

        val written = settingsText()
        assertTrue(
            written.contains(""""workbench.secondarySideBar.defaultVisibility": "hidden""""),
            "the first screen gives a large share of a phone-width window to a chat view " +
                "whose provider is pruned from this build:\n$written",
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
