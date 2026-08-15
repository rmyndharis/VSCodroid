package com.vscodroid.setup

import android.content.Context
import android.content.res.AssetManager
import com.vscodroid.util.Logger
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.File

/**
 * Tests that a bundled extension reaches a device that already has a directory
 * of the same name -- the upgrade case.
 *
 * Extraction used to ask `dest.exists()`, and the destination is
 * `publisher.name-version`, so the question it really asked was "has a
 * directory with this version in its name been written before". Content is not
 * part of that name. An extension whose code changed without a version bump
 * therefore reached clean installs and nobody else, and no gate noticed:
 * `scripts/test-process-monitor-extension.js` runs in the pull-request and
 * release workflows against the *asset*, so it was green on the very release
 * whose devices kept the previous file.
 *
 * These drive the real private method against a real temporary tree and a
 * stubbed AssetManager, because the property under test is which bytes are on
 * disk afterwards.
 *
 * One fixture detail is load-bearing: `extensions.json` is pre-created. Every
 * `org.json` method throws "not mocked" on this module's unit-test classpath
 * (see [BundledExtensionHostTest]), and the manifest branch taken when the file
 * already exists is the one that catches its own exceptions. Without the file,
 * the fresh-manifest branch would throw straight out of the method and these
 * would fail for a reason that has nothing to do with extraction.
 */
class BundledExtensionRefreshTest {

    @TempDir
    lateinit var filesDir: File

    private lateinit var context: Context
    private lateinit var assets: AssetManager

    private val dirName = "vscodroid.vscodroid-process-monitor-1.0.0"

    private val extensionsDir by lazy { File(filesDir, "home/.vscodroid/extensions") }
    private val installed by lazy { File(extensionsDir, dirName) }

    /** What this build of the APK carries. */
    private val shippedSource = "// the rewritten notification code\n"
    private val shippedManifest =
        """{"publisher":"vscodroid","name":"vscodroid-process-monitor","version":"1.0.0"}"""

    /** What the previous release left on the device, under the same directory name. */
    private val installedSource = "// the notification code that froze its count\n"

    @BeforeEach
    fun setUp() {
        mockkObject(Logger)
        every { Logger.d(any(), any()) } just Runs
        every { Logger.i(any(), any()) } just Runs
        every { Logger.w(any(), any(), any()) } just Runs
        every { Logger.e(any(), any(), any()) } just Runs

        assets = mockk()
        every { assets.list("extensions") } returns arrayOf(dirName)
        every { assets.list("extensions/$dirName") } returns arrayOf("extension.js", "package.json")
        // Empty is how extractAssetDir recognises a leaf and switches to copying.
        every { assets.list("extensions/$dirName/extension.js") } returns emptyArray()
        every { assets.list("extensions/$dirName/package.json") } returns emptyArray()
        // answers, not returns: a stream is consumed, and the second test in a
        // run would otherwise read an exhausted one and write an empty file.
        every { assets.open("extensions/$dirName/extension.js") } answers
            { ByteArrayInputStream(shippedSource.toByteArray()) }
        every { assets.open("extensions/$dirName/package.json") } answers
            { ByteArrayInputStream(shippedManifest.toByteArray()) }

        context = mockk(relaxed = true)
        every { context.filesDir } returns filesDir
        every { context.assets } returns assets

        extensionsDir.mkdirs()
        File(extensionsDir, "extensions.json").writeText("[]")
    }

    @AfterEach
    fun tearDown() = unmockkObject(Logger)

    /**
     * Reflection rather than widened visibility, the way [TerminalShellPathTest]
     * reaches `createDefaultSettings`.
     */
    private fun extractBundledExtensions() {
        FirstRunSetup::class.java
            .getDeclaredMethod("extractBundledExtensions")
            .apply { isAccessible = true }
            .invoke(FirstRunSetup(context))
    }

    /**
     * The upgrade. This is the whole defect: the directory is there, so nothing
     * was copied over it, and the device kept running last release's code.
     */
    @Test
    fun `an extension whose content changed under the same version is refreshed`() {
        installed.mkdirs()
        File(installed, "extension.js").writeText(installedSource)
        File(installed, "package.json").writeText(shippedManifest)

        extractBundledExtensions()

        assertEquals(
            shippedSource,
            File(installed, "extension.js").readText(),
            "the upgrade kept the previous extension.js -- the directory already existed, " +
                "so extraction was skipped and the fix never reached the device",
        )
    }

    /**
     * The positive control, and it is not decoration. Every assertion above is
     * about bytes arriving on disk, so a harness whose AssetManager stubs did
     * not match the paths the code asks for would copy nothing at all -- and a
     * test that asserts "the new content is there" would then be the only thing
     * failing, with no way to tell a broken fix from a broken fixture.
     */
    @Test
    fun `a clean install extracts what the APK carries`() {
        assertTrue(!installed.exists(), "the fixture staged a directory this test needs absent")

        extractBundledExtensions()

        assertTrue(installed.isDirectory, "nothing was extracted; the asset stubs do not match")
        assertEquals(shippedSource, File(installed, "extension.js").readText())
        assertEquals(shippedManifest, File(installed, "package.json").readText())
    }

    /**
     * Extraction merges over what is there and never deletes, so a file that an
     * earlier release wrote into the directory survives. That is deliberate and
     * cheap to live with -- nothing loads a file `package.json` does not name --
     * but it should be a recorded property rather than a surprise, because the
     * alternative (delete the tree, then extract) trades this for a window in
     * which a crash leaves no extension at all.
     */
    @Test
    fun `a file the current build no longer ships is left in place`() {
        installed.mkdirs()
        File(installed, "extension.js").writeText(installedSource)
        File(installed, "package.json").writeText(shippedManifest)
        File(installed, "retired-helper.js").writeText("// dropped from the build")

        extractBundledExtensions()

        assertEquals(shippedSource, File(installed, "extension.js").readText())
        assertTrue(
            File(installed, "retired-helper.js").exists(),
            "extraction deleted a file it does not ship; it is documented as a merge",
        )
    }
}
