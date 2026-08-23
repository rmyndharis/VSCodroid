package com.vscodroid.setup

import android.content.Context
import android.content.SharedPreferences
import android.content.res.AssetManager
import com.vscodroid.util.Logger
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.File

/**
 * What a retry re-copies, and what it is allowed to leave alone.
 *
 * Nothing skipped anything: `extractAssetFile` wrote every asset unconditionally,
 * so a Retry after a LOW_STORAGE refusal or a failure at the 800th MiB began the
 * whole 810 MiB again, minutes behind a bar the user had already watched once,
 * and did it again for someone who frees space in two goes.
 *
 * Skipping on equal length alone would be wrong, which is why the record and not
 * the length is the subject of these cases. Equal length is not equal content: an
 * upgrade whose new asset happens to weigh what the old one did HAS to be
 * written, and reading a stale file as installed is the failure the whole
 * extraction is built to avoid. So the fast path is licensed by a preference
 * naming the build whose extraction is in flight, written before the first byte
 * and removed by `markSetupComplete`, and it applies only to a retry of that same
 * build on an install no earlier build ever completed under. That second
 * condition is not belt and braces: the previous release's whole tree is already
 * on disk when the marker is written, so on an upgrade it stands over the
 * previous release's files. Nor is it the whole answer, because two unfinished
 * builds leave no completion for it to see, which is why replacing the marker
 * poisons it rather than overwriting it.
 *
 * Driven through the real `runSetup()` against a real temporary tree, with the
 * failure arranged as the rest of this package arranges it: by occupying the
 * temporary path `writeAtomically` derives from its destination. The file that is
 * supposed to be skipped is tampered with between the two runs at the SAME
 * length, so a skip and a rewrite leave different bytes and the assertion can
 * tell them apart.
 */
class ExtractionResumeTest {

    @TempDir
    lateinit var filesDir: File

    @TempDir
    lateinit var cacheDir: File

    private lateinit var context: Context
    private lateinit var assets: AssetManager

    /** Two assets under the server tree; the second one is what gets blocked. */
    private val firstAsset = "// alpha"

    /** Same length as [firstAsset], so only a rewrite can change what is on disk. */
    private val tampered = "// omega"

    private val secondAsset = "// bravo"

    private val stored = mutableMapOf<String, String>()

    private val first by lazy { File(filesDir, "server/vscode-reh/a.js") }
    private val second by lazy { File(filesDir, "server/vscode-reh/b.js") }

    @BeforeEach
    fun setUp() {
        mockkObject(Logger)
        every { Logger.d(any(), any()) } just Runs
        every { Logger.i(any(), any()) } just Runs
        every { Logger.w(any(), any(), any()) } just Runs
        every { Logger.e(any(), any(), any()) } just Runs

        assets = mockk()
        every { assets.list(any()) } returns emptyArray()
        every { assets.open(any()) } throws java.io.IOException("absent")
        every { assets.list("vscode-reh") } returns arrayOf("a.js", "b.js")
        every { assets.list("vscode-reh/a.js") } returns emptyArray()
        every { assets.list("vscode-reh/b.js") } returns emptyArray()
        // answers, not returns: a stream is consumed, and each run opens it again.
        every { assets.open("vscode-reh/a.js") } answers { ByteArrayInputStream(firstAsset.toByteArray()) }
        every { assets.open("vscode-reh/b.js") } answers { ByteArrayInputStream(secondAsset.toByteArray()) }

        // Preferences that remember, because the whole decision under test is a
        // record one run leaves for the next. A relaxed mock drops every write
        // and answers every read with a default, so both runs would read the
        // same nothing whatever the first one did.
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        every { editor.putString(any(), any()) } answers {
            stored[firstArg()] = secondArg()
            editor
        }
        // Returning the same editor rather than a fresh relaxed one, or the
        // chained `remove()` in markSetupComplete would land on a mock nothing
        // here can see and the record would look as if it had survived.
        every { editor.putInt(any(), any()) } returns editor
        every { editor.remove(any()) } answers {
            stored.remove(firstArg<String>())
            editor
        }
        val prefs = mockk<SharedPreferences>(relaxed = true)
        every { prefs.edit() } returns editor
        every { prefs.getString(any(), any()) } answers { stored[firstArg<String>()] ?: secondArg() }
        every { prefs.getInt(any(), any()) } returns 0

        context = mockk(relaxed = true)
        every { context.filesDir } returns filesDir
        every { context.cacheDir } returns cacheDir
        every { context.assets } returns assets
        every { context.getSharedPreferences(any(), any()) } returns prefs
        every { context.getExternalFilesDir(null) } returns File(filesDir, "external")
    }

    @AfterEach
    fun tearDown() = unmockkObject(Logger)

    /**
     * The subject, with the tree figures supplied rather than read from
     * `BuildConfig`.
     *
     * They are measured from `src/main/assets` at build time, so they are 810 MiB
     * on a developer's checkout and zero on the CI runner, which stubs those
     * directories. Zero here keeps the storage pre-flight out of the way of what
     * these cases are about: the gate then asks only for its slack, which any
     * temporary directory has.
     *
     * The one case that wants the gate in the way passes a figure. `filesDir` is
     * a real directory on a real disk here, so free space cannot be mocked down;
     * a demand larger than any disk is what makes the pre-flight refuse.
     */
    private fun setup(assetBytes: Long = 0) = FirstRunSetup(
        context,
        assetBytes = assetBytes,
        largestAssetBytes = 0,
        bundledUsrBytes = 0,
        bundledExtensionBytes = 0,
    )

    /** Non-empty, so the cleanup `delete()` cannot quietly reclaim it. */
    private fun blockSecondWrite(): File {
        assertTrue(second.parentFile!!.mkdirs(), "could not stage the server directory")
        val blocker = File(second.parentFile, "${second.name}.tmp~")
        assertTrue(blocker.mkdirs(), "could not stage the blocked temp path")
        File(blocker, "occupied").writeText("x")
        return blocker
    }

    /**
     * A run that writes the first asset and then cannot write the second, which
     * is the state every retry starts from: some of the tree on disk, the record
     * of the attempt still standing, and nothing marked complete.
     */
    private fun interruptedFirstRun(): File {
        val blocker = blockSecondWrite()
        val result = runBlocking { setup().runSetup() }
        assertEquals(
            FirstRunSetup.SetupResult.ERROR, result,
            "the staged failure did not fail the run, so there is no retry to measure",
        )
        assertEquals(firstAsset, first.readText(), "the first asset never landed")
        return blocker
    }

    @Test
    fun `a retry of the same build leaves a file it has already written alone`() {
        val blocker = interruptedFirstRun()

        // Same length, different bytes: the only way to tell a skip from a
        // rewrite without depending on timing.
        first.writeText(tampered)
        assertTrue(blocker.deleteRecursively(), "could not free the temp path")

        val result = runBlocking { setup().runSetup() }

        assertEquals(FirstRunSetup.SetupResult.SUCCESS, result)
        assertEquals(secondAsset, second.readText(), "the retry did not finish the tree")
        assertEquals(
            tampered, first.readText(),
            "the retry re-copied a file it had already written at the right length; on the " +
                "real tree that is 23,000 files and minutes of a progress bar the user has " +
                "already watched once",
        )
    }

    @Test
    fun `a retry rewrites a file an interruption left short`() {
        val blocker = interruptedFirstRun()

        // What a kill or a full disk leaves. The length no longer matches, so the
        // skip must not claim it.
        first.writeText("//")
        assertTrue(blocker.deleteRecursively(), "could not free the temp path")

        runBlocking { setup().runSetup() }

        assertEquals(
            firstAsset, first.readText(),
            "a truncated file was read as already extracted, which is the state the whole " +
                "extraction is built to refuse",
        )
    }

    /**
     * The control, and the case that decides whether the fast path is safe at
     * all: a run that is not a retry of the same build must write everything.
     *
     * Staged by leaving the record of a DIFFERENT build behind, which is what an
     * upgrade looks like from here. Without this, skipping on length alone would
     * satisfy the two cases above and would silently keep the previous release's
     * file whenever the new one happened to weigh the same.
     */
    @Test
    fun `a run that is not a retry of the same build rewrites everything`() {
        interruptedFirstRun().also { assertTrue(it.deleteRecursively(), "could not free the temp path") }
        first.writeText(tampered)

        // The attempt record now names a build this is not.
        stored["extraction_attempt"] = "9.9.9/999"

        val result = runBlocking { setup().runSetup() }

        assertEquals(FirstRunSetup.SetupResult.SUCCESS, result)
        assertEquals(
            firstAsset, first.readText(),
            "an extraction that is not a retry of its own attempt kept a file of the same " +
                "length that another build wrote",
        )
    }

    /**
     * The case the marker cannot speak for on its own, and the reason the fast
     * path asks a second question.
     *
     * The marker is written before the first byte, and on an upgrade the previous
     * release's whole tree is already on disk by then, so it stands over files
     * this build did not write. An attempt that stops part way leaves that
     * mixture, and licensed on the marker alone the retry keeps every file of the
     * old release whose length happens to match the new asset, with
     * `markSetupComplete()` certifying an install that is part one release and
     * part the other.
     *
     * Staged as what it is: an install a build has already completed under, a
     * marker naming this build, and a file of exactly the right length that this
     * build did not write.
     *
     * NEGATIVE CONTROL: drop the `nothingCompletedHere` half of the condition in
     * `runSetupLocked`. The tampered bytes then survive and this reddens.
     */
    @Test
    fun `a retry on an install an earlier build completed rewrites everything`() {
        interruptedFirstRun().also { assertTrue(it.deleteRecursively(), "could not free the temp path") }

        // Same length, different bytes, and not this build's: what an upgrade
        // finds sitting under its own attempt marker.
        first.writeText(tampered)
        // The completion record of an earlier build, which is the whole
        // difference between this case and the same-build retry above.
        stored["setup_version"] = "1.0.0"

        val result = runBlocking { setup().runSetup() }

        assertEquals(FirstRunSetup.SetupResult.SUCCESS, result)
        assertEquals(
            firstAsset, first.readText(),
            "a retry kept a file of the right length that an earlier build wrote, so the " +
                "install ends part one release and part the other with nothing to show it",
        )
    }

    /**
     * The gap the completion keys cannot see, and the reason replacing the marker
     * poisons it.
     *
     * "No build ever COMPLETED here" is not "no OTHER build ever WROTE here", and
     * both completion keys are written only by `markSetupComplete()`, so a fresh
     * install where one build wrote half the tree and died leaves both unset with
     * that build's files on disk. Update before it ever finishes and the next
     * build writes into the same tree, records its own marker, and stops part way
     * too; its retry would then read its own marker back over a tree half of
     * which the previous build wrote, with `nothingCompletedHere` still true.
     * Nothing else catches it either: the pre-extraction migrations are gated on
     * an upgrade, and this install has never completed one.
     *
     * So the marker is poisoned when it is replaced, and the retry is refused
     * until a completion clears it.
     *
     * NEGATIVE CONTROL: write `attempt` unconditionally in `runSetupLocked`, as
     * this did. The tampered bytes then survive and this reddens.
     */
    @Test
    fun `a retry of a tree two unfinished builds wrote rewrites everything`() {
        // The earlier build's marker, and no completion key beside it, because
        // there was never a completion: this install has never finished a setup.
        stored["extraction_attempt"] = "0.9/1"

        // This build's first attempt: it writes into the tree it found and stops
        // at the blocked file, so its own marker goes over a tree another build
        // began.
        interruptedFirstRun().also { assertTrue(it.deleteRecursively(), "could not free the temp path") }

        // Same length, different bytes: what the earlier build left where this
        // one is about to look.
        first.writeText(tampered)

        val result = runBlocking { setup().runSetup() }

        assertEquals(FirstRunSetup.SetupResult.SUCCESS, result)
        assertEquals(
            firstAsset, first.readText(),
            "a retry kept a file of the right length that a different unfinished build wrote, " +
                "so markSetupComplete certifies an install that is part one build and part " +
                "the other with nothing on disk to show it",
        )
    }

    /**
     * A run that writes nothing claims nothing.
     *
     * The marker was recorded at the top of the run, above the storage
     * pre-flight, so an attempt refused for storage claimed a tree it had not
     * touched a byte of. That defeats the skip on the one user it was built for.
     * A fresh install refused under v1 leaves v1's marker standing over an empty
     * tree; the app updates before the user frees space; v2 reads a marker naming
     * another build and poisons its own for ever, so every v2 retry re-copies the
     * whole 810 MiB although v2 is the only build that ever wrote a byte.
     *
     * Refused with a demand larger than any disk, because `filesDir` is a real
     * directory here and free space cannot be mocked down.
     *
     * NEGATIVE CONTROL: move the marker write back above the pre-flight. The
     * first assertion then finds this build's own attempt recorded and the second
     * finds the earlier build's marker poisoned, and both redden.
     */
    @Test
    fun `a run refused for storage leaves the record of the attempt as it found it`() {
        val refused = runBlocking { setup(assetBytes = 1L shl 60).runSetup() }
        assertEquals(
            FirstRunSetup.SetupResult.LOW_STORAGE, refused,
            "the pre-flight let the run through, so there is no refusal to measure",
        )
        assertNull(
            stored["extraction_attempt"],
            "a run that wrote no byte recorded an attempt over the tree anyway",
        )

        // And with another unfinished build's marker already there, which is what
        // a fresh install that updated before it ever finished carries: a run
        // that writes nothing must not poison it either, or the build that does
        // write is refused the skip for the rest of the install.
        stored["extraction_attempt"] = "0.9/1"
        val refusedAgain = runBlocking { setup(assetBytes = 1L shl 60).runSetup() }
        assertEquals(
            FirstRunSetup.SetupResult.LOW_STORAGE, refusedAgain,
            "the second run was not refused, so the record below was cleared by a completion " +
                "rather than left alone",
        )

        assertEquals(
            "0.9/1", stored["extraction_attempt"],
            "a run refused for storage poisoned a record it never wrote under, which costs " +
                "every later retry a full re-copy of the tree",
        )
    }

    /**
     * And the record must not outlive the run it describes. Left behind after a
     * success, the next genuine extraction over the same version would be handed
     * a skip it did not earn.
     */
    @Test
    fun `a completed setup retires the record of the attempt`() {
        runBlocking { setup().runSetup() }

        assertNull(stored["extraction_attempt"], "the attempt record survived a completed setup")
        assertNotNull(stored["setup_version"], "the run under test did not complete")
    }
}
