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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Collections

/**
 * What the bar does while the long steps run.
 *
 * Two of the three extraction steps used to report nothing at all: `usr/` copied
 * its files with the bar pinned at 62 and the bundled extensions theirs with it
 * pinned at 88, which is a fifth of the tree spent on a bar that does not move,
 * on a screen the user is asked to wait minutes at. A still bar reads as a hang,
 * and a force-quit at that point costs the whole extraction.
 *
 * The counter that fixed it divides bytes written by the figure the build
 * measured from the tree it packaged, which is the part that has to be pinned:
 * those two numbers can disagree. A tree fetched after the APK was built weighs
 * MORE than the recorded total, and without the cap the step then runs the bar
 * into the step behind it, so a bar that was stuck at 62 becomes one that
 * reaches 90 and jumps back to 82.
 *
 * Driven through the real `runSetup()` with a stubbed asset tree, so what is
 * measured is what the screen would be told. The `usr/` step is the one staged
 * with more bytes than its recorded total; every other step reports its own
 * fixed percent and the run as a whole must never go backwards.
 */
class ExtractionProgressBandTest {

    @TempDir
    lateinit var filesDir: File

    @TempDir
    lateinit var cacheDir: File

    private lateinit var context: Context

    /** Every (message, percent) the run reported, in order. */
    private val reports: MutableList<Pair<String, Int>> =
        Collections.synchronizedList(mutableListOf())

    /**
     * Four files of 1 KiB under `usr/`, against a recorded total of one of them.
     * The mismatch is the point: it is the shape a server tree fetched after the
     * APK was built produces, and it is what the cap exists for.
     */
    private val fileBytes = 1024
    private val recordedUsrBytes = 1024L

    @BeforeEach
    fun setUp() {
        mockkObject(Logger)
        every { Logger.d(any(), any()) } just Runs
        every { Logger.i(any(), any()) } just Runs
        every { Logger.w(any(), any(), any()) } just Runs
        every { Logger.e(any(), any(), any()) } just Runs

        val assets = mockk<AssetManager>()
        // Everything the run asks for that this test does not stage answers
        // "absent", which extractAssetFile treats as routine rather than failure.
        every { assets.list(any()) } returns emptyArray()
        every { assets.open(any()) } throws java.io.IOException("absent")
        every { assets.list("usr") } returns arrayOf("bin")
        every { assets.list("usr/bin") } returns arrayOf("one", "two", "three", "four")
        for (name in listOf("one", "two", "three", "four")) {
            every { assets.list("usr/bin/$name") } returns emptyArray()
            every { assets.open("usr/bin/$name") } answers
                { ByteArrayInputStream(ByteArray(fileBytes)) }
        }

        val prefs = mockk<SharedPreferences>(relaxed = true)
        every { prefs.edit() } returns mockk(relaxed = true)
        every { prefs.getString(any(), any()) } returns null
        every { prefs.getInt(any(), any()) } returns 0

        context = mockk(relaxed = true)
        every { context.filesDir } returns filesDir
        every { context.cacheDir } returns cacheDir
        every { context.assets } returns assets
        every { context.getSharedPreferences(any(), any()) } returns prefs
        every { context.getExternalFilesDir(null) } returns File(filesDir, "external")
        SetupStepStrings.stub(context)
    }

    @AfterEach
    fun tearDown() {
        // The sink lives in the companion, so a run that left one installed is
        // what the next test would report into.
        FirstRunSetup(context).onProgress = null
        unmockkObject(Logger)
    }

    private fun run(): List<Pair<String, Int>> {
        val setup = FirstRunSetup(
            context,
            assetBytes = 4L * fileBytes,
            largestAssetBytes = fileBytes.toLong(),
            bundledUsrBytes = recordedUsrBytes,
            bundledExtensionBytes = 0,
        )
        setup.onProgress = { message, percent -> reports += message to percent }
        val result = runBlocking { setup.runSetup() }
        assertEquals(
            FirstRunSetup.SetupResult.SUCCESS, result,
            "the staged run did not finish, so what it reported says nothing about the bands",
        )
        return reports.toList()
    }

    /**
     * NEGATIVE CONTROL: drop `.coerceAtMost(span)` from `byteProgress`. The
     * second staged file then reports 102 and this reddens on both assertions.
     */
    @Test
    fun `the tools step stays inside its own band however much it copies`() {
        val tools = run().filter { it.first == SetupStepStrings.text("setup_step_tools") }

        assertTrue(tools.isNotEmpty(), "the tools step reported nothing, so the bar sat still")
        for ((_, percent) in tools) {
            assertTrue(
                percent in 62..82,
                "the tools step reported $percent, which is outside the band it owns and " +
                    "inside a step that has not started",
            )
        }
    }

    /**
     * The bar only ever moves forward, which is the property a user reads. A
     * counter that overran its band would satisfy the per-step check on its own
     * band and break this one at the step boundary.
     */
    @Test
    fun `the bar never goes backwards`() {
        var previous = 0
        for ((message, percent) in run()) {
            assertTrue(
                percent >= previous,
                "the bar went from $previous back to $percent at \"$message\"",
            )
            assertTrue(percent in 0..100, "the bar reported $percent at \"$message\"")
            previous = percent
        }
        assertEquals(100, previous, "the run finished without reporting completion")
    }
}
