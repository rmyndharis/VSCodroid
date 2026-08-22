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
 * `visibleInWorkspace`, and on a phone it takes roughly 45 percent of the width,
 * beside whatever the walkthrough opened, which then wraps to one word per line.
 * The provider the view exists for is not in this build: the Copilot extension
 * is pruned from the server tree, so the width buys nothing back.
 *
 * Three things have to hold together, and this file covers all three because
 * only the first of them used to, while the bar stayed open on the device.
 *
 * 1. A clean install carries `workbench.secondarySideBar.defaultVisibility`.
 * 2. So does an install that predates the key. `createDefaultSettings` writes
 *    only when `settings.json` is absent, so on its own it reaches nobody who
 *    already has one, and v1.1.0 shipped no such key. [refreshManagedPaths] is
 *    what closes that gap, and leaves a value the user chose alone.
 * 3. Something acts on it. The setting decides a workspace with no recorded
 *    layout, and by the time the web client can read it the record exists: the
 *    workbench starts from a copy of these settings held in browser storage,
 *    which the first load in a profile has not written yet, so that load falls
 *    back to `visibleInWorkspace`, opens the bar and stores
 *    `workbench.auxiliaryBar.hidden: false` against the workspace. Later loads
 *    read the record and never consult the default again. The bundled welcome
 *    extension corrects the record once per workspace; that call is the third
 *    check here.
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

    @Test
    fun `an install that predates the key gains it`() {
        // The shape v1.1.0 left behind: a settings.json with no mention of the
        // secondary side bar at all.
        val existing = """
            {
                "editor.fontSize": 14,
                "workbench.colorTheme": "Default Dark Modern"
            }
        """.trimIndent()

        val updated = refreshManagedPaths(existing, "/bin/bash", "/lib/libgit.so", "/lib/libldmusl.so")

        assertTrue(
            updated != null &&
                updated.contains(""""workbench.secondarySideBar.defaultVisibility": "hidden""""),
            "every device upgrading from a release without the key keeps upstream's " +
                "visibleInWorkspace, so the chat view keeps the width:\n$updated",
        )
    }

    @Test
    fun `a bar the user asked for is left alone`() {
        val chosen = """
            {
                "workbench.secondarySideBar.defaultVisibility": "visible"
            }
        """.trimIndent()

        val updated = refreshManagedPaths(chosen, "/bin/bash", "/lib/libgit.so", "/lib/libldmusl.so")

        assertTrue(
            updated == null || !updated.contains(""""defaultVisibility": "hidden""""),
            "a user who opened the secondary side bar had the choice taken back:\n$updated",
        )
    }

    /**
     * The half that acts on the setting, read out of the extension this app
     * bundles.
     *
     * Three properties, and each one was a way to get this wrong:
     *
     * - The command must be `closeAuxiliaryBar`, which hides the part outright.
     *   `toggleAuxiliaryBar` reads as the same fix and is the opposite one: on a
     *   workspace that already agrees it opens the bar.
     * - The marker must be workspace-scoped. A global one would align the first
     *   workspace a user opens and leave every other one as it found it.
     * - The call must be reached without the walkthrough's own marker file,
     *   which every device upgrading into this release already has, and which is
     *   therefore exactly the population whose stored layout needs correcting.
     *   Position is what settles that: a call sited before the marker gate
     *   cannot be inside it.
     */
    @Test
    fun `the bundled welcome extension corrects the stored layout`() {
        val welcome = File("src/main/assets/extensions")
            .listFiles { f -> f.isDirectory && f.name.startsWith("vscodroid.vscodroid-welcome-") }
            ?.singleOrNull()
        check(welcome != null) {
            "no single bundled welcome extension under src/main/assets/extensions; this test " +
                "would otherwise pass by finding nothing to read"
        }

        val source = File(welcome, "extension.js").readText()

        assertTrue(
            source.contains("'workbench.action.closeAuxiliaryBar'"),
            "nothing closes the secondary side bar, so the setting decides a layout record " +
                "that the first load has already written",
        )
        assertTrue(
            !source.contains("toggleAuxiliaryBar"),
            "toggling reopens the bar on a workspace that already agrees",
        )
        // Both halves named in full, and against the code rather than the word.
        // "workspaceState" on its own is satisfied by the comment above the
        // walkthrough marker, which names it while saying nothing about scope:
        // swapping the two reads for globalState left that check green.
        assertTrue(
            source.contains("context.workspaceState.get(SIDE_BAR_ALIGNED)") &&
                source.contains("context.workspaceState.update(SIDE_BAR_ALIGNED"),
            "the marker has to be workspace-scoped, or only the first workspace is aligned",
        )

        // The call, not the declaration. `function alignSecondarySideBar(context)`
        // contains the same text and sits near the top of the file, so an
        // unqualified search found it and reported an ordering that held however
        // the call itself was moved. The trailing semicolon is what tells them
        // apart.
        val call = source.indexOf("alignSecondarySideBar(context);")
        val walkthroughGate = source.indexOf("existsSync(markerFile)")
        assertTrue(
            call >= 0 && walkthroughGate >= 0,
            "neither anchor was found, so the ordering below is checking nothing: " +
                "call=$call gate=$walkthroughGate",
        )
        assertTrue(
            call < walkthroughGate,
            "the alignment sits inside the walkthrough's once-ever gate, so every device " +
                "that has already seen the walkthrough keeps the bar open",
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
