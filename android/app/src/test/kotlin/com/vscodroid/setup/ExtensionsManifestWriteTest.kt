package com.vscodroid.setup

import android.content.Context
import android.content.SharedPreferences
import android.content.res.AssetManager
import com.vscodroid.util.Logger
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.unmockkConstructor
import io.mockk.unmockkObject
import io.mockk.verify
import org.json.JSONArray
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.lang.reflect.InvocationTargetException

/**
 * Tests that the first-run write of `extensions.json` cannot leave a truncated
 * manifest behind.
 *
 * The hazard is already written down beside the other writer of this file:
 * reconcileExtensionsManifest goes through [writeAtomically] and says why --
 * "a truncated manifest is read as an empty extension list, so every bundled
 * extension disappears rather than the write visibly failing". The generating
 * path wrote the same file straight to its own name, so one file had two
 * treatments of one hazard, with the justification already sitting next to the
 * safe one.
 *
 * On this path the loss is permanent rather than merely silent. Once anything
 * exists at the path, extractBundledExtensions() stops generating and starts
 * reconciling; reconciliation cannot parse a half-written document, catches its
 * own exception and returns. So a truncated manifest is never regenerated and
 * never repaired, and the extension list stays empty for the life of the
 * install.
 *
 * `org.json` is unusable here -- every method on this module's unit-test
 * classpath throws "not mocked" (see [BundledExtensionHostTest]) -- and the
 * method opens by constructing a JSONArray. The repo's usual answer is to test
 * the pure decision instead, but there is no decision here: the defect is
 * entirely in how the bytes land, so the write has to be reached. Constructing
 * the array is therefore intercepted and asked only for the two things this
 * method uses of it. manifestEntryFor needs no stub: it wraps its own JSON in a
 * try/catch and answers null for every directory, which is why `put` is never
 * called.
 */
class ExtensionsManifestWriteTest {

    @TempDir
    lateinit var filesDir: File

    private lateinit var context: Context
    private lateinit var assets: AssetManager
    private lateinit var editor: SharedPreferences.Editor
    private lateinit var extensionsDir: File
    private lateinit var manifestFile: File

    /** Stands in for whatever the real serialiser would produce. */
    private val serialised = """[{"identifier":{"id":"vscodroid.vscodroid-welcome"}}]"""

    private val dirName = "vscodroid.vscodroid-welcome-1.2.0"
    private val pkgJson =
        """{"publisher":"vscodroid","name":"vscodroid-welcome","version":"1.2.0"}"""

    @BeforeEach
    fun setUp() {
        mockkObject(Logger)
        every { Logger.d(any(), any()) } just Runs
        every { Logger.i(any(), any()) } just Runs
        every { Logger.w(any(), any(), any()) } just Runs
        every { Logger.e(any(), any(), any()) } just Runs

        mockkConstructor(JSONArray::class)
        every { anyConstructed<JSONArray>().toString(2) } returns serialised
        every { anyConstructed<JSONArray>().length() } returns 1

        assets = mockk()
        every { assets.list("extensions") } returns arrayOf(dirName)
        every { assets.list("extensions/$dirName") } returns arrayOf("package.json")
        every { assets.list("extensions/$dirName/package.json") } returns emptyArray()
        every { assets.open("extensions/$dirName/package.json") } answers
            { ByteArrayInputStream(pkgJson.toByteArray()) }

        // Captured rather than relaxed-through, because the whole point of one
        // test below is that a particular editor call never happens.
        editor = mockk(relaxed = true)
        val prefs = mockk<SharedPreferences>(relaxed = true)
        every { prefs.edit() } returns editor

        context = mockk(relaxed = true)
        every { context.filesDir } returns filesDir
        every { context.assets } returns assets
        every { context.getSharedPreferences(any(), any()) } returns prefs

        extensionsDir = File(filesDir, "home/.vscodroid/extensions")
        extensionsDir.mkdirs()
        manifestFile = File(extensionsDir, "extensions.json")
    }

    @AfterEach
    fun tearDown() {
        unmockkConstructor(JSONArray::class)
        unmockkObject(Logger)
    }

    private fun generateExtensionsManifest() {
        FirstRunSetup::class.java
            .getDeclaredMethod("generateExtensionsManifest", File::class.java, Array<String>::class.java)
            .apply { isAccessible = true }
            .invoke(FirstRunSetup(context), extensionsDir, arrayOf("vscodroid.vscodroid-welcome-1.2.0"))
    }

    /**
     * The control. Without it the assertion below would also hold for a method
     * that had stopped writing anything at all, and for a harness whose
     * constructor interception silently did not take.
     */
    @Test
    fun `writes the manifest on a first run`() {
        generateExtensionsManifest()

        assertTrue(manifestFile.isFile, "no manifest was written")
        assertEquals(serialised, manifestFile.readText())
    }

    /** Non-empty, so the cleanup delete() cannot quietly reclaim it. */
    private fun blockTheWrite() {
        val blocker = File(extensionsDir, "${manifestFile.name}.tmp~")
        assertTrue(blocker.mkdirs(), "could not stage the blocked temp path")
        File(blocker, "occupied").writeText("x")
    }

    @Test
    fun `a failed write leaves no manifest behind and fails loudly`() {
        blockTheWrite()

        val thrown = assertThrows(InvocationTargetException::class.java) { generateExtensionsManifest() }

        assertTrue(
            thrown.cause is IOException,
            "the failure must reach runSetupLocked's catch so markSetupComplete is skipped; " +
                "it surfaced as ${thrown.cause}",
        )
        assertFalse(
            manifestFile.exists(),
            "a truncated manifest was left at the path; the next launch reconciles rather " +
                "than regenerates, cannot parse it, and the extension list stays empty for good",
        )
    }

    private fun extractBundledExtensions() {
        FirstRunSetup::class.java
            .getDeclaredMethod("extractBundledExtensions")
            .apply { isAccessible = true }
            .invoke(FirstRunSetup(context))
    }

    /**
     * The caller must not record what the manifest failed to say.
     *
     * `rememberBundledIds` persists the bundled identifier set into
     * SharedPreferences, and that record outlives the process and the app
     * upgrade. It exists so a later reconcile can tell an extension the user
     * uninstalled from one this app has never shipped -- an identifier already
     * in the record with no manifest entry reads as "the user removed it" and is
     * never listed again.
     *
     * Writing it when no manifest was written is therefore not a harmless stale
     * value, and the reasoning that it was rested on "nothing else creates
     * extensions.json". The comment fourteen lines above the branch says
     * otherwise in this file's own words: "The server manages this file for
     * marketplace installs". So the sequence is reachable -- failed write, no
     * manifest, ids recorded; user installs anything from Open VSX and the
     * server creates the file; the next upgrade takes the reconcile branch and
     * reads every bundled extension as deliberately removed, permanently.
     */
    @Test
    fun `a failed manifest write records no bundled ids`() {
        blockTheWrite()

        assertThrows(InvocationTargetException::class.java) { extractBundledExtensions() }

        verify(exactly = 0) {
            editor.putStringSet(any(), any())
        }
    }

    /**
     * The control for the test above: on the path that works, the record IS
     * written. Without this, a build where rememberBundledIds had been deleted
     * outright would satisfy the assertion above.
     */
    @Test
    fun `a successful generate records the bundled ids`() {
        extractBundledExtensions()

        assertTrue(manifestFile.isFile, "the manifest was not written; the harness is wrong")
        verify { editor.putStringSet(any(), any()) }
    }
}
