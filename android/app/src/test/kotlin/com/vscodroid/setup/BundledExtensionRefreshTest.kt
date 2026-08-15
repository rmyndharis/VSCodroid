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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.lang.reflect.InvocationTargetException

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
 * No `extensions.json` fixture, and its absence is deliberate rather than an
 * omission. These used to pre-create one because the manifest branch taken when
 * the file exists catches its own exceptions, which mattered while org.json was
 * believed to throw "not mocked" here. It does not -- build.gradle.kts puts the
 * real org.json on the test classpath -- so the manifest work runs for real and
 * these exercise the same path a clean install takes.
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
     * The other side of the split, driven through the real method.
     *
     * An extension `download-extensions.sh` fetches at a pinned version cannot
     * have changed its bytes under that version, so its directory being present
     * is a sound staleness test -- and re-copying it would overwrite whatever it
     * has generated for itself since installation. The generated file here
     * stands in for that: `PKief.material-icon-theme` rewrites a 450 KB icon
     * definition inside its own directory when its settings change, and its
     * activation returns early while the stored settings still match, so a
     * reverted copy stays reverted.
     */
    @Test
    fun `an extension fetched by version is not re-extracted over what it generated`() {
        val fetched = "PKief.material-icon-theme-5.37.0"
        every { assets.list("extensions") } returns arrayOf(fetched)
        every { assets.list("extensions/$fetched") } returns arrayOf("extension.js")
        every { assets.list("extensions/$fetched/extension.js") } returns emptyArray()
        every { assets.open("extensions/$fetched/extension.js") } answers
            { ByteArrayInputStream(shippedSource.toByteArray()) }

        val dir = File(extensionsDir, fetched)
        dir.mkdirs()
        val generated = File(dir, "extension.js")
        generated.writeText("// regenerated on the device")

        extractBundledExtensions()

        assertEquals(
            "// regenerated on the device",
            generated.readText(),
            "a fetched extension was re-copied over its own generated state; its version " +
                "has not moved, so its bytes in the APK cannot have changed",
        )
    }

    /**
     * The control for the test above. Without it, a build that had stopped
     * extracting anything at all would satisfy that assertion too.
     */
    @Test
    fun `an extension fetched by version is extracted when its directory is absent`() {
        val fetched = "PKief.material-icon-theme-5.37.0"
        every { assets.list("extensions") } returns arrayOf(fetched)
        every { assets.list("extensions/$fetched") } returns arrayOf("extension.js")
        every { assets.list("extensions/$fetched/extension.js") } returns emptyArray()
        every { assets.open("extensions/$fetched/extension.js") } answers
            { ByteArrayInputStream(shippedSource.toByteArray()) }

        extractBundledExtensions()

        assertEquals(shippedSource, File(extensionsDir, "$fetched/extension.js").readText())
    }

    /**
     * The delivery guarantee has to hold all the way down, not just at the
     * decision.
     *
     * Deciding to re-unpack our own extension buys nothing if the unpack then
     * fails quietly: extractAssetFile logs a warning and carries on, which is
     * right for the 390 MB server tree and wrong here, because what survives is
     * the previous release's code -- the exact thing the split exists to
     * replace. The failure has to reach runSetupLocked's catch, which is
     * upstream of markSetupComplete(), or setup certifies an install that is
     * still running the old extension.
     */
    @Test
    fun `an unpack that fails stops the setup rather than leaving the old code`() {
        installed.mkdirs()
        File(installed, "extension.js").writeText(installedSource)
        File(installed, "package.json").writeText(shippedManifest)
        // Occupy the temp path the atomic write derives for extension.js.
        val blocker = File(installed, "extension.js.tmp~")
        assertTrue(blocker.mkdirs(), "could not stage the blocked temp path")
        File(blocker, "occupied").writeText("x")

        assertThrows(InvocationTargetException::class.java) { extractBundledExtensions() }

        assertEquals(
            installedSource,
            File(installed, "extension.js").readText(),
            "the previous file should be intact -- the atomic write leaves it alone on failure",
        )
    }

    /**
     * The retry has to actually retry, and for a fetched extension it did not.
     *
     * The test above drives one of ours, which is re-unpacked unconditionally --
     * so the throw survives into the next attempt there no matter what state the
     * directory is left in. That is the case that cannot fail. A fetched
     * extension is kept only while its directory is ABSENT, and extractAssetDir
     * creates the directory before copying into it, so a copy that failed
     * partway left exactly the evidence that removes it from the next attempt's
     * list: the loop then had nothing to do, threw nothing, and setup completed
     * with an extension half on disk. The manifest lists it from the
     * package.json that did land, so it reads as installed and fails to activate
     * on every launch until its version string changes.
     *
     * The stream here opens cleanly and fails on first read, which is the shape
     * a full disk has -- not an absent asset, which is a different answer.
     */
    @Test
    fun `a fetched extension whose unpack failed is retried, not skipped`() {
        val fetched = "PKief.material-icon-theme-5.37.0"
        val fetchedManifest = """{"publisher":"PKief","name":"material-icon-theme","version":"5.37.0"}"""
        every { assets.list("extensions") } returns arrayOf(fetched)
        every { assets.list("extensions/$fetched") } returns arrayOf("package.json", "extension.js")
        every { assets.list("extensions/$fetched/package.json") } returns emptyArray()
        every { assets.list("extensions/$fetched/extension.js") } returns emptyArray()
        every { assets.open("extensions/$fetched/package.json") } answers
            { ByteArrayInputStream(fetchedManifest.toByteArray()) }
        var outOfSpace = true
        every { assets.open("extensions/$fetched/extension.js") } answers {
            if (outOfSpace) {
                object : InputStream() {
                    override fun read(): Int = throw IOException("no space left on device")
                }
            } else {
                ByteArrayInputStream(shippedSource.toByteArray())
            }
        }

        assertThrows(InvocationTargetException::class.java) { extractBundledExtensions() }
        assertFalse(
            File(extensionsDir, fetched).exists(),
            "the half-unpacked directory survived the failure, and its presence is exactly " +
                "what makes the next attempt skip it",
        )

        outOfSpace = false
        extractBundledExtensions()

        assertEquals(
            shippedSource,
            File(extensionsDir, "$fetched/extension.js").readText(),
            "the retry did not re-attempt the extension whose unpack had failed",
        )
    }

    /**
     * A plain file where the extension directory belongs is cleared, not kept.
     *
     * The failure path preserves what was already there, because a directory
     * from the previous release holds files that are still whole. A plain file
     * is not that: it defeats `mkdirs` on this attempt and on every future one,
     * and for a fetched extension the retry drops it from the list, so the
     * extension can never be unpacked again. Asking `isDirectory` rather than
     * `exists` reads it as nothing worth keeping, so the attempt clears it and
     * the retry succeeds.
     *
     * Nothing this app writes produces that state; the value is that the state
     * is recoverable rather than terminal if anything ever does.
     */
    @Test
    fun `a plain file occupying an extension path is cleared so the retry can work`() {
        val fetched = "PKief.material-icon-theme-5.37.0"
        every { assets.list("extensions") } returns arrayOf(fetched)
        every { assets.list("extensions/$fetched") } returns arrayOf("extension.js")
        every { assets.list("extensions/$fetched/extension.js") } returns emptyArray()
        every { assets.open("extensions/$fetched/extension.js") } answers
            { ByteArrayInputStream(shippedSource.toByteArray()) }

        // A file, not a directory, at the path the extension needs.
        File(extensionsDir, fetched).writeText("not a directory")

        assertThrows(InvocationTargetException::class.java) { extractBundledExtensions() }
        assertFalse(
            File(extensionsDir, fetched).exists(),
            "the occupying file was preserved, so mkdirs will fail forever and the retry " +
                "will skip the extension",
        )

        extractBundledExtensions()

        assertEquals(shippedSource, File(extensionsDir, "$fetched/extension.js").readText())
    }

    /**
     * The stopped-shipping sweep is actually wired into the run.
     *
     * [retiredFetchedExtensionDirs] is tested on its own, and a pure function
     * nobody calls is the same nothing as no function at all -- this project has
     * spent several passes on guards that were correct and never reached. So
     * this drives the real method and asserts the directory is gone from disk.
     */
    @Test
    fun `an extension this build stopped shipping is removed from the device`() {
        val gitlens = File(extensionsDir, "eamodio.gitlens-2026.2.1114")
        assertTrue(File(gitlens, "dist").mkdirs())
        File(gitlens, "package.json").writeText("""{"publisher":"eamodio","name":"gitlens"}""")
        File(gitlens, "dist/gitlens.js").writeText("// 22 MB in real life")

        extractBundledExtensions()

        assertFalse(
            gitlens.exists(),
            "the extension this build no longer bundles is still on disk; no sweep reaches it, " +
                "so it would stay for the life of the install",
        )
        // The control: the sweep must not take the extension that IS bundled.
        assertTrue(installed.isDirectory, "the bundled extension was removed too")
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
