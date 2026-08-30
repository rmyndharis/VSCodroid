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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.File

/**
 * What happens to a bundled extension whose unpack is never allowed to finish.
 *
 * The failure this covers is not an exception. `extractBundledExtensions`
 * already deletes what a failed attempt created and throws, so the retry works.
 * A process kill runs none of that: setup lives in SplashActivity's scope, and
 * the foreground hold it takes makes a kill during the 57 MB extension copy
 * rare rather than impossible; a low-memory kill still ends the process where
 * it stands. What
 * it leaves is a directory holding some of an extension, and for one fetched at
 * a pinned version the directory's presence was the whole staleness test. The
 * next run therefore read a few hundred of 3787 files as an install, dropped it
 * from the list, threw nothing, and let `markSetupComplete()` certify the run.
 * Nothing on the device would touch it again: no per-launch repair reads the
 * extensions directory, and the three sweeps all refuse a name this build still
 * bundles. Clear Data or an app update were the only ways out.
 *
 * So these pin the two halves that make presence truthful again: the directory
 * carries [UNPACK_MARKER_NAME] for as long as the copy is in flight, and a
 * directory carrying it (or carrying no `package.json`, which is the same
 * answer for the installs broken before the mark existed) is unpacked again.
 *
 * Driven through the real private method against a real temporary tree, in the
 * shape [BundledExtensionRefreshTest] established, because what is under test
 * is which bytes are on disk afterwards.
 */
class AbandonedUnpackTest {

    @TempDir
    lateinit var filesDir: File

    private lateinit var context: Context
    private lateinit var assets: AssetManager

    /** Fetched at a pinned version, so presence is what decides its fate. */
    private val fetched = "PKief.material-icon-theme-5.37.0"

    private val shippedSource = "// the icon theme this APK carries\n"
    private val shippedManifest =
        """{"publisher":"PKief","name":"material-icon-theme","version":"5.37.0"}"""

    private val extensionsDir by lazy { File(filesDir, "home/.vscodroid/extensions") }
    private val installed by lazy { File(extensionsDir, fetched) }
    private val marker by lazy { File(installed, UNPACK_MARKER_NAME) }

    /** Set by the stubbed stream, so the copy can be observed while it runs. */
    private var markedWhileCopying: Boolean? = null

    @BeforeEach
    fun setUp() {
        mockkObject(Logger)
        every { Logger.d(any(), any()) } just Runs
        every { Logger.i(any(), any()) } just Runs
        every { Logger.w(any(), any(), any()) } just Runs
        every { Logger.e(any(), any(), any()) } just Runs

        assets = mockk()
        every { assets.list("extensions") } returns arrayOf(fetched)
        every { assets.list("extensions/$fetched") } returns arrayOf("package.json", "extension.js")
        // Empty is how extractAssetDir recognises a leaf and switches to copying.
        every { assets.list("extensions/$fetched/package.json") } returns emptyArray()
        every { assets.list("extensions/$fetched/extension.js") } returns emptyArray()
        every { assets.open("extensions/$fetched/package.json") } answers
            { ByteArrayInputStream(shippedManifest.toByteArray()) }
        // answers, not returns: a stream is consumed, and a second call in the
        // same test would otherwise read an exhausted one. The look at the mark
        // rides along here because this is the only moment inside the copy the
        // test can reach.
        every { assets.open("extensions/$fetched/extension.js") } answers {
            markedWhileCopying = marker.isFile
            ByteArrayInputStream(shippedSource.toByteArray())
        }

        context = mockk(relaxed = true)
        every { context.filesDir } returns filesDir
        every { context.assets } returns assets

        extensionsDir.mkdirs()
    }

    @AfterEach
    fun tearDown() = unmockkObject(Logger)

    private fun extractBundledExtensions() {
        FirstRunSetup::class.java
            .getDeclaredMethod("extractBundledExtensions")
            .apply { isAccessible = true }
            .invoke(FirstRunSetup(context))
    }

    /** The state a kill during the copy leaves: some files, and the mark. */
    private fun stageKilledUnpack() {
        assertTrue(installed.mkdirs(), "could not stage the half-unpacked directory")
        // The manifest landed, which is the worse of the two shapes: it is what
        // gets the wreckage listed as an installed extension.
        File(installed, "package.json").writeText(shippedManifest)
        assertTrue(marker.createNewFile(), "could not stage the unpacking mark")
    }

    /**
     * The defect, end to end.
     *
     * NEGATIVE CONTROL: in `bundledDirsToExtract`, restore the test to
     * `if (dir in present) return@filter false`. The directory is present, so
     * the extension is dropped from the list, nothing is copied and this fails
     * on the missing `extension.js`.
     */
    @Test
    fun `a fetched extension a kill left half copied is unpacked again`() {
        stageKilledUnpack()

        extractBundledExtensions()

        assertEquals(
            shippedSource,
            File(installed, "extension.js").readText(),
            "the half-unpacked extension was read as installed and skipped; nothing on the " +
                "device would ever have written the rest of it",
        )
    }

    /**
     * The mark has to be written BEFORE the first file, not after the last.
     *
     * Written afterwards it would be a record of success, and a kill during the
     * copy is precisely the case where nothing gets to write anything: the
     * directory would be back to answering "installed" on its own.
     *
     * NEGATIVE CONTROL: move the `marker.createNewFile()` block in
     * `extractBundledExtensions` below the `extractAssetDir` call. The stream
     * stub then sees no mark and this fails.
     */
    @Test
    fun `the mark is on disk while the copy is running`() {
        extractBundledExtensions()

        assertEquals(
            true,
            markedWhileCopying,
            "the directory was being written with nothing on it to say so; a kill at that " +
                "moment leaves a partial extension that reads as installed",
        )
    }

    /**
     * And it has to be gone afterwards, or the fix costs what it saves: every
     * later run would re-copy 57 MB, and the on-device file count
     * (`ExtractionOnDeviceTest.extractsTheRealBundledTree`) would find one file
     * per extension that the APK does not carry.
     *
     * NEGATIVE CONTROL: delete the `marker.delete()` block. This fails, and so
     * does the fetched-extension control below it.
     */
    @Test
    fun `the mark is gone once the unpack lands`() {
        extractBundledExtensions()

        assertFalse(
            marker.exists(),
            "the unpacking mark outlived the unpack, so every later run re-copies the tree",
        )
    }

    /**
     * The control, and it is the whole reason the mark exists rather than a
     * blanket re-copy. A fetched extension that is genuinely installed must
     * still be left alone: its bytes cannot have changed under a pinned
     * version, and copying over it discards whatever it has generated inside
     * its own directory since install (`PKief.material-icon-theme` rewrites a
     * 450 KB icon definition there).
     *
     * NEGATIVE CONTROL: make `unpackWasAbandoned` return true unconditionally.
     * The generated file is overwritten and this fails.
     */
    @Test
    fun `a finished fetched extension is still left alone`() {
        assertTrue(installed.mkdirs())
        File(installed, "package.json").writeText(shippedManifest)
        val generated = File(installed, "extension.js")
        generated.writeText("// regenerated on the device")

        extractBundledExtensions()

        assertEquals(
            "// regenerated on the device",
            generated.readText(),
            "a complete fetched extension was re-copied over its own generated state",
        )
    }

    /**
     * The installs this app has already broken, which carry no mark whatever
     * state they are in.
     *
     * An extension directory with no `package.json` is loadable by nothing:
     * `manifestEntryFor` declines it and the workbench's scanner cannot see it.
     * Reading that as unfinished costs a copy the extension needed anyway.
     *
     * NEGATIVE CONTROL: drop the `|| !File(dir, "package.json").isFile` half of
     * `unpackWasAbandoned`. The directory is present and unmarked, so nothing
     * is copied and this fails.
     */
    @Test
    fun `a directory with no manifest is unpacked again even with no mark`() {
        assertTrue(installed.mkdirs())
        File(installed, "extension.js").writeText("// half of a copy from an older release")

        extractBundledExtensions()

        assertEquals(
            shippedManifest,
            File(installed, "package.json").readText(),
            "a directory holding no manifest was treated as an install; it can never load, " +
                "and no sweep here would ever reclaim it",
        )
    }

    /**
     * The failure path, for the class of directory the mark itself created.
     *
     * Selecting a present-but-abandoned fetched directory widened this loop, and
     * it left the failure branch behind: that branch skips the delete for any
     * directory that was already there, on the reasoning that a fetched one is
     * only ever in the list because it was ABSENT, which stopped being true in
     * the same change. For those the mark is then the whole of the retry, and
     * the mark is written best-effort. When both fail together the directory is
     * present, holds the manifest that did land and carries no mark, so
     * `unpackWasAbandoned` answers no, `bundledDirsToExtract` drops it, the loop
     * does not touch it, nothing throws, and `markSetupComplete()` certifies a
     * half-unpacked extension: listed in `extensions.json`, dead on every
     * activation, and reachable by no sweep here.
     *
     * Driven as two runs, because "the retry retries" is the property and one
     * run cannot show it. The mark is refused by putting a DIRECTORY at its
     * path, which `createNewFile()` declines, and the copy is failed by putting
     * one at the temporary path `writeAtomically` opens, which `FileOutputStream`
     * declines on every platform. Both go with the tree the first run removes, so
     * the second run finds nothing in its way.
     *
     * The block is on the TEMPORARY path rather than on `extension.js` itself,
     * and that is not a detail. Measured on this project's own macOS build host:
     * `File.renameTo` a plain file onto an existing empty directory returns TRUE
     * on APFS, so blocking the destination fails nothing there and the case
     * quietly proved nothing, while on the device's ext4 the same rename gives
     * EISDIR. Opening a directory for writing fails on both, so the injection
     * below is the one that means the same thing everywhere it runs.
     *
     * NEGATIVE CONTROL, measured: restore `if (!existedBefore &&
     * !dest.deleteRecursively())` in `extractBundledExtensions`. The first run
     * leaves the wreckage, the second reads it as installed and copies nothing,
     * and the assertion on `extension.js` goes red.
     */
    @Test
    fun `an unmarked fetched directory whose unpack failed is cleared for the retry`() {
        assertTrue(installed.mkdirs(), "could not stage the wreckage")
        // No package.json, which is what makes it abandoned on a device that
        // predates the mark: the state a kill during a pre-marker release left.
        assertTrue(File(installed, UNPACK_MARKER_NAME).mkdirs(), "could not block the mark")
        assertTrue(File(installed, "extension.js.tmp~").mkdirs(), "could not block the copy")

        val failed = runCatching { extractBundledExtensions() }
        assertTrue(
            failed.isFailure,
            "an unpack that could not copy its files reported success, so setup certifies it",
        )
        assertFalse(
            installed.exists(),
            "the failed unpack left a directory that carries the manifest and no mark, so " +
                "nothing on the device names it again: it is listed as installed and is dead " +
                "on every activation",
        )

        extractBundledExtensions()

        assertEquals(
            shippedSource,
            File(installed, "extension.js").readText(),
            "the retry did not unpack the extension, so the first attempt was the only one",
        )
        assertEquals(shippedManifest, File(installed, "package.json").readText())
    }

    /**
     * The three answers the failure branch has to give, without a tree.
     *
     * The middle one is why this is not simply "delete whatever is there": a
     * directory that was already there and IS marked is named again by
     * [unpackWasAbandoned], and what it holds is the previous release's install
     * with part of this one merged over it, every file written through a rename.
     * Deleting that would spend the copy again and leave the user with no
     * extension at all in between. The same reasoning covers ours unmarked,
     * which [bundledDirsToExtract] re-unpacks unconditionally.
     *
     * NEGATIVE CONTROL, measured: return `!existedBefore` from
     * [failedUnpackMustBeRemoved], which is what the branch tested before, and
     * the fetched-unmarked case goes red while the other three stay green.
     */
    @Test
    fun `the failure branch removes only what nothing else would name again`() {
        val ours = "vscodroid.vscodroid-saf-bridge-1.5.0"

        assertTrue(
            failedUnpackMustBeRemoved(fetched, existedBefore = false, marked = true),
            "a directory this attempt created has to go: present is the whole of the test a " +
                "fetched extension gets, and the manifest that landed lists it as installed",
        )
        assertFalse(
            failedUnpackMustBeRemoved(fetched, existedBefore = true, marked = true),
            "the mark names it again, so what is on disk is the previous release's install " +
                "with part of this one merged over it, and removing it costs the copy twice",
        )
        assertTrue(
            failedUnpackMustBeRemoved(fetched, existedBefore = true, marked = false),
            "a fetched directory that was already here and could not be marked is named by " +
                "nothing: the retry has no way back to it",
        )
        assertFalse(
            failedUnpackMustBeRemoved(ours, existedBefore = true, marked = false),
            "ours are re-unpacked on every run, so the previous release's copy is worth more " +
                "kept than deleted",
        )
    }

    @Test
    fun `the decision names an abandoned directory and not a finished one`() {
        // The pure half, so the two cases can be told apart without a tree.
        assertEquals(
            listOf(fetched),
            bundledDirsToExtract(
                present = listOf(fetched),
                bundled = listOf(fetched),
                abandoned = setOf(fetched),
            ),
        )
        assertTrue(
            bundledDirsToExtract(present = listOf(fetched), bundled = listOf(fetched)).isEmpty(),
            "an unqualified call must keep the behaviour it had before the third argument",
        )
    }

    /**
     * A user's own newer install still wins, and that is deliberate rather than
     * an oversight in the widening. The copy the user chose is the one that
     * runs either way, so unpacking beside it writes 29 MiB that nothing loads
     * and nothing removes; the wreckage is unlisted and inert. Re-copying it
     * would spend the transfer to change neither.
     */
    @Test
    fun `a newer install still suppresses an abandoned bundled copy`() {
        val theirs = "PKief.material-icon-theme-9.99.0"

        assertTrue(
            bundledDirsToExtract(
                present = listOf(fetched, theirs),
                bundled = listOf(fetched),
                abandoned = setOf(fetched),
            ).isEmpty(),
        )
    }

    @Test
    fun `the mark and the missing manifest each answer on their own`() {
        val dir = File(filesDir, "probe")
        assertTrue(File(dir, "sub").mkdirs())

        assertTrue(unpackWasAbandoned(dir), "no manifest, so nothing there can load")

        File(dir, "package.json").writeText("{}")
        assertFalse(unpackWasAbandoned(dir), "a manifest and no mark is what every install has")

        File(dir, UNPACK_MARKER_NAME).writeText("")
        assertTrue(unpackWasAbandoned(dir), "the mark must outweigh a manifest that did land")
    }
}
