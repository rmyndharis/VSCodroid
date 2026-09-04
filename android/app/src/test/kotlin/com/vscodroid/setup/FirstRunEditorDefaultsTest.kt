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
import org.json.JSONObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
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
 * The provider the view exists for does ship: the Copilot extension is in the
 * server tree and `verify-server-tree.py` refuses a tree without it. So the
 * default is about width, not an empty view: the walkthrough is what a first
 * screen is for, and the chat view is a tap away for whoever wants it. This
 * file once said the extension was pruned, which would have sent anyone
 * weighing the default off after a pruning step that does not exist, so the
 * premise is pinned by a case of its own.
 *
 * Four things have to hold together, and this file covers all four because
 * only the first of them used to, while the bar stayed open on the device.
 *
 * 1. The value ships. It is a contributed default in the bundled welcome
 *    extension's manifest, which lands in the DEFAULT layer, below every user
 *    file. That is what a default is, and it is not where this app put it: the
 *    value went into `Machine/settings.json`, which the workbench parses as the
 *    REMOTE USER settings and merges ON TOP of the user's own, so the app's
 *    choice beat whatever the user changed in the Settings editor.
 * 2. The app no longer writes it. A clean install's `settings.json` carries
 *    machine facts and server-only keys and none of these preferences.
 * 3. An install that already has one loses the override.
 *    `createDefaultSettings` writes only when the file is absent, so without a
 *    prune every existing device would keep it for ever; and the prune must
 *    leave a value the user changed exactly where it is.
 * 4. Something acts on the setting, and the view has a provider. The secondary
 *    side bar's default decides a workspace with no recorded layout, and by the
 *    time the web client can read it the record exists: the workbench starts
 *    from a copy of these settings held in browser storage, which the first load
 *    in a profile has not written yet, so that load falls back to
 *    `visibleInWorkspace`, opens the bar and stores
 *    `workbench.auxiliaryBar.hidden: false` against the workspace. Later loads
 *    read the record and never consult the default again. The bundled welcome
 *    extension corrects the record once per workspace.
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
    fun `a clean install writes none of the app's preferences`() {
        createDefaultSettings()

        val written = settingsText()
        val found = movedKeys().filter { written.contains("\"$it\"") }

        assertTrue(
            found.isEmpty(),
            "$found went back into the machine settings file. The workbench merges that " +
                "file ON TOP of the user's own settings, so every key here is one the " +
                "Settings editor cannot change:\n$written",
        )
    }

    /**
     * And that they are still delivered, from the layer a default belongs in.
     *
     * The handoff is silent in both directions. A key dropped from the Kotlin
     * block and forgotten in the manifest loses its default for everyone, with
     * nothing to say so; a key added back to the Kotlin block silently restores
     * the override this whole arrangement exists to remove.
     */
    @Test
    fun `every preference the app used to write is a contributed default`() {
        val contributed = JSONObject(welcomeManifest())
            .getJSONObject("contributes")
            .getJSONObject("configurationDefaults")

        for (key in MOVED_TO_MANIFEST) {
            assertTrue(
                contributed.has(key),
                "`$key` is written nowhere now: the app stopped writing it and the " +
                    "welcome extension does not contribute it, so the default is simply " +
                    "gone. Contributed keys: ${contributed.keys().asSequence().toList()}",
            )
        }

        assertEquals(
            "hidden",
            contributed.getString("workbench.secondarySideBar.defaultVisibility"),
            "the first screen gives roughly 45 percent of a phone-width window to the " +
                "chat view, beside the walkthrough it opened with",
        )
    }

    /**
     * The premise the reason above rests on. If Copilot stops shipping the bar
     * is empty and the reason for hiding it is a different one, which is worth
     * being told by a red case rather than by a reader who trusted the comment.
     */
    @Test
    fun `the chat view this default hides has a provider`() {
        val gate = File("../../scripts/verify-server-tree.py")
        check(gate.isFile) {
            "${gate.absolutePath} not found; a case reading it would pass by looking at nothing"
        }

        // The REQUIRED list alone, not the whole file: the gate also names the
        // extension's licence path outside that list, in a read that is not a
        // failure when the file is absent, and the self-test names it too. A
        // whole-file search stayed green with both REQUIRED entries deleted.
        val required = gate.readText().substringAfter("REQUIRED = [", "").substringBefore("\n]")
        assertTrue(required.isNotEmpty()) { "verify-server-tree.py no longer has a REQUIRED list" }
        assertTrue(
            required.contains("\"extensions/copilot/node_modules/@github/copilot/sdk/index.js\""),
            "the server tree is no longer required to carry the Copilot extension, so " +
                "the chat view has no provider and the reason this file gives for hiding " +
                "the secondary side bar is stale",
        )
    }

    @Test
    fun `an install that already has the override loses it`() {
        // The shape the previous release left behind, written by this app.
        val existing = """
            {
                "workbench.secondarySideBar.defaultVisibility": "hidden",
                "editor.wordWrap": "on",
                "git.path": "/lib/libgit.so"
            }
        """.trimIndent()

        val updated = pruneMovedDefaults(existing, autoHideSideBar = true)

        assertTrue(
            updated != null && movedKeys().none { updated.contains("\"$it\"") },
            "the preferences the app wrote are still in the file, so they go on beating " +
                "the user's own settings on every device that has one, and " +
                "createDefaultSettings never rewrites a file that exists:\n$updated",
        )
        assertTrue(
            updated != null && updated.contains(""""git.path": "/lib/libgit.so""""),
            "the prune took a line that is not a preference; git.path is a machine fact " +
                "that has to stay:\n$updated",
        )
    }

    @Test
    fun `a value the user changed is left exactly where it is`() {
        val chosen = """
            {
                "workbench.secondarySideBar.defaultVisibility": "visible",
                "editor.wordWrap": "off"
            }
        """.trimIndent()

        assertNull(
            pruneMovedDefaults(chosen, autoHideSideBar = true),
            "a user who opened the secondary side bar, or turned word wrap off, had the " +
                "choice deleted from under them. Only the value this app itself wrote is " +
                "removable; anything else is theirs, and it goes on outranking their " +
                "local settings, which is the honest outcome for a file they were handed",
        )
    }

    /**
     * The one layout value the app still writes, and why it is not a preference.
     *
     * Nothing in the extension API reports how wide the window is, so the
     * extension that closes the side bar cannot decide for itself whether it
     * should. This process knows, and writes the answer down as a fact about the
     * device. The user's own answer is a different key, in their own settings
     * file, and it wins where they have given one.
     *
     * Pinned rather than inserted-when-absent, which is the opposite of the keys
     * beside it: a stale device fact is worse than an absent one, and there is
     * no user decision here to preserve.
     *
     * The flag is a parameter rather than a device call, which is what makes
     * this a plain JVM test. Its one caller passes `FirstRunSetup.isCompactScreen()`.
     */
    @Test
    fun `the phone flag is written for an install that never had it`() {
        val existing = """
            {
                "git.path": "/lib/libgit.so"
            }
        """.trimIndent()

        val updated = settingsWithLayoutDefaults(existing, compactScreen = true)

        assertTrue(
            updated != null && updated.contains(""""vscodroid.layout.compactScreen": true"""),
            "the welcome extension has nothing to read, so the side bar stays open over " +
                "the file the user just opened:\n$updated",
        )
    }

    @Test
    fun `a tablet is recorded as a tablet`() {
        val updated = settingsWithLayoutDefaults("{}", compactScreen = false)

        assertTrue(
            updated != null && updated.contains(""""vscodroid.layout.compactScreen": false"""),
            "a tablet was given the phone's answer, so its file tree closes on every file " +
                "it opens, on a screen with room for both:\n$updated",
        )
    }

    @Test
    fun `a stale device fact is corrected, not left`() {
        val stale = """
            {
                "vscodroid.layout.compactScreen": true
            }
        """.trimIndent()

        val updated = settingsWithLayoutDefaults(stale, compactScreen = false)

        assertTrue(
            updated != null && updated.contains(""""vscodroid.layout.compactScreen": false"""),
            "the file still describes a phone on a device that is not one. This is the " +
                "one key here that is not the user's, so leaving it as found is the " +
                "wrong kindness:\n$updated",
        )
    }

    @Test
    fun `a user's own answer is never written here`() {
        val chosen = """
            {
                "vscodroid.layout.compactScreen": true,
                "vscodroid.layout.autoHideSideBar": false
            }
        """.trimIndent()

        assertNull(
            settingsWithLayoutDefaults(chosen, compactScreen = true),
            "the device fact already agrees, so there is nothing to write. The user's " +
                "own key must never be touched from this file: it is the one they set, " +
                "and this file outranks the one they set it in",
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

        // Both layout keys, because the split between them is the whole fix. The
        // app writes the device fact; the user writes the preference, in their
        // own settings file; and an extension that reads only one of them either
        // ignores the user or ignores the screen.
        assertTrue(
            source.contains("'vscodroid.layout.autoHideSideBar'"),
            "the extension no longer reads the user's own key, so a preference set in " +
                "Settings decides nothing and the screen decides everything",
        )
        assertTrue(
            source.contains("'vscodroid.layout.compactScreen'"),
            "the extension no longer reads the device fact, so a phone with no " +
                "preference set keeps the side bar open over every file it opens",
        )
    }

    /**
     * The three launch configurations the user guide's Running and Debugging
     * table names, one by one.
     *
     * They have shipped since the first milestone and were documented only in a
     * changelog line, so nothing would have noticed a rename, a fourth entry or
     * a switch of debug type: the guide would simply have started describing a
     * dropdown that no longer matched. `node` is js-debug's current type;
     * `pwa-node` still runs and its own manifest marks it deprecated.
     *
     * The file matters as much as the content. `launch` has to sit at the root
     * of the MACHINE settings document, which is the one the workbench reads;
     * written into the `User/` path it disappears from the dropdown with a
     * settings.json that still looks right, which is the failure
     * `migrateSettingsToMachinePath` exists to undo.
     */
    @Test
    fun `the launch configurations the guide documents are the ones written`() {
        createDefaultSettings()

        val launch = JSONObject(settingsText()).getJSONObject("launch")
        val configurations = launch.getJSONArray("configurations")
        val names = (0 until configurations.length())
            .map { configurations.getJSONObject(it).getString("name") }
            .sorted()

        assertEquals(
            listOf("Attach to Node.js", "NestJS: Debug", "Node.js: Run Current File"),
            names,
            "the Running and Debugging section of docs/USER_GUIDE.md names these three in a " +
                "table. A user reads that table against the dropdown, so the two move together " +
                "or not at all",
        )
        for (i in 0 until configurations.length()) {
            assertEquals(
                "node",
                configurations.getJSONObject(i).getString("type"),
                "js-debug's own manifest marks `pwa-node` deprecated in favour of `node`, so " +
                    "a configuration on the old type documents something upstream is retiring",
            )
        }
    }

    /**
     * The keys that moved from the app's writer to the extension's manifest.
     *
     * Written out rather than read from `MOVED_DEFAULTS`, which is private, and
     * the duplication is the point: this list is the specification, and a key
     * dropped from the Kotlin side is meant to fail here until it is added to
     * the manifest.
     */
    private val MOVED_TO_MANIFEST = listOf(
        "workbench.startupEditor",
        "workbench.colorTheme",
        "workbench.sash.size",
        "workbench.activityBar.compact",
        "workbench.secondarySideBar.defaultVisibility",
        "editor.wordWrap",
        "editor.minimap.enabled",
        "terminal.integrated.fontSize",
    )

    /**
     * Every key the app must no longer write into the machine settings file.
     *
     * The contributed ones, plus the three it stopped writing outright because
     * each was already the platform's own default: `editor.fontSize` 14,
     * `diffEditor.wordWrap` inheriting from `editor.wordWrap`, and shell
     * integration, which is on unless someone turns it off.
     */
    private fun movedKeys(): List<String> = MOVED_TO_MANIFEST + listOf(
        "editor.fontSize",
        "diffEditor.wordWrap",
        "terminal.integrated.shellIntegration.enabled",
        "vscodroid.layout.autoHideSideBar",
    )

    private fun welcomeManifest(): String {
        val welcome = File("src/main/assets/extensions")
            .listFiles { f -> f.isDirectory && f.name.startsWith("vscodroid.vscodroid-welcome-") }
            ?.singleOrNull()
        check(welcome != null) {
            "no single bundled welcome extension under src/main/assets/extensions; this " +
                "test would otherwise pass by finding nothing to read"
        }
        return File(welcome, "package.json").readText()
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
