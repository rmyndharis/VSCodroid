package com.vscodroid.setup

import android.content.Context
import com.vscodroid.util.Logger
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.unmockkConstructor
import io.mockk.unmockkObject
import org.json.JSONArray
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

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
    private lateinit var extensionsDir: File
    private lateinit var manifestFile: File

    /** Stands in for whatever the real serialiser would produce. */
    private val serialised = """[{"identifier":{"id":"vscodroid.vscodroid-welcome"}}]"""

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

        context = mockk(relaxed = true)
        every { context.filesDir } returns filesDir

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

    @Test
    fun `a failed write leaves no manifest behind`() {
        // Non-empty, so the cleanup delete() cannot quietly reclaim it.
        val blocker = File(extensionsDir, "${manifestFile.name}.tmp~")
        assertTrue(blocker.mkdirs(), "could not stage the blocked temp path")
        File(blocker, "occupied").writeText("x")

        generateExtensionsManifest()

        assertFalse(
            manifestFile.exists(),
            "a truncated manifest was left at the path; the next launch reconciles rather " +
                "than regenerates, cannot parse it, and the extension list stays empty for good",
        )
    }
}
