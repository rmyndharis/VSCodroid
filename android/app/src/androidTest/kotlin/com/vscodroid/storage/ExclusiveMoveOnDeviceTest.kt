package com.vscodroid.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vscodroid.util.Environment
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID

/**
 * The packaged helper, run as the app runs it. Only a device can show the three things the move
 * rests on: that the app may start the program, that the kernel lets an app make the call, and
 * that the directory identity java.nio prints is the one the program reads back.
 */
@RunWith(AndroidJUnit4::class)
class ExclusiveMoveOnDeviceTest {
    private lateinit var helper: File
    private lateinit var directory: File

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        helper = File(Environment.getMoveHelperPath(context))
        assertTrue("the helper is not in this build", helper.isFile)
        directory = File(context.cacheDir, "move-" + UUID.randomUUID().toString().take(12))
        check(directory.mkdir())
    }

    @After
    fun cleanup() {
        directory.deleteRecursively()
    }

    private fun key(of: File): Any =
        Files.readAttributes(of.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).fileKey()

    @Test
    fun aTakenNameIsRefusedAndNothingIsReplaced() {
        File(directory, "copy").writeText("the copy")
        File(directory, "taken").writeText("made meanwhile")

        assertEquals(ExclusiveMove.Outcome.TAKEN, ExclusiveMove.move(helper, directory, key(directory), "copy", "taken"))

        assertEquals("the copy", File(directory, "copy").readText())
        assertEquals("made meanwhile", File(directory, "taken").readText())
    }

    @Test
    fun aFreeNameTakesTheEntry() {
        File(directory, "copy").writeText("the copy")

        assertEquals(ExclusiveMove.Outcome.MOVED, ExclusiveMove.move(helper, directory, key(directory), "copy", "free name.txt"))

        assertFalse(File(directory, "copy").exists())
        assertEquals("the copy", File(directory, "free name.txt").readText())
    }

    @Test
    fun aDirectoryIsMovedAndRefusedTheSameWay() {
        File(directory, "folder").mkdir()
        File(directory, "folder/inside").writeText("kept")
        File(directory, "other").mkdir()

        assertEquals(ExclusiveMove.Outcome.TAKEN, ExclusiveMove.move(helper, directory, key(directory), "folder", "other"))
        assertEquals(ExclusiveMove.Outcome.MOVED, ExclusiveMove.move(helper, directory, key(directory), "folder", "moved"))

        assertEquals("kept", File(directory, "moved/inside").readText())
    }

    @Test
    fun aPathThatLeadsToAnotherDirectoryMovesNothing() {
        File(directory, "copy").writeText("the copy")
        val other = File(directory, "elsewhere").apply { mkdir() }

        assertEquals(ExclusiveMove.Outcome.UNAVAILABLE, ExclusiveMove.move(helper, directory, key(other), "copy", "free"))

        assertEquals("the copy", File(directory, "copy").readText())
        assertFalse(File(directory, "free").exists())
    }

    /**
     * The helper ends within milliseconds, and the platform's timed wait for a process looks once
     * and then sleeps a tenth of a second, so waited for that way every move takes at least that.
     *
     * Measured against the same helper started ten times and waited for with the untimed
     * `waitFor()`, which returns when the process ends, rather than against a fixed figure. A
     * start of the helper costs about 65 ms on an API 36 emulator, so ten of them were 650 of the
     * 800 ms a fixed bound allowed and a slower device would have gone red with nothing wrong;
     * what this asks is the time the wait adds to each move, which is what the timed wait wasted.
     */
    @Test
    fun aMoveIsNotWaitedForLongerThanItTakes() {
        File(directory, "a").writeText("the copy")
        assertEquals(ExclusiveMove.Outcome.MOVED, ExclusiveMove.move(helper, directory, key(directory), "a", "b"))

        // Started with no arguments it refuses at once, so this is the start and the exit alone.
        val controlStarted = System.nanoTime()
        repeat(10) {
            val process = ProcessBuilder(helper.path).redirectErrorStream(true).start()
            process.inputStream.use { it.readBytes() }
            process.waitFor()
        }
        val control = (System.nanoTime() - controlStarted) / 1_000_000

        val started = System.nanoTime()
        repeat(5) {
            assertEquals(ExclusiveMove.Outcome.MOVED, ExclusiveMove.move(helper, directory, key(directory), "b", "a"))
            assertEquals(ExclusiveMove.Outcome.MOVED, ExclusiveMove.move(helper, directory, key(directory), "a", "b"))
        }
        val elapsed = (System.nanoTime() - started) / 1_000_000

        // 25 ms a move over the control: the poll's 2 ms with room for scheduling, and below
        // the 35 ms or more the timed wait added to a helper that ends in 65 ms.
        assertTrue("ten moves took $elapsed ms against $control ms for ten bare starts", elapsed < control + 250)
    }

    @Test
    fun aMissingEntryIsNotReportedAsATakenName() {
        File(directory, "taken").writeText("made meanwhile")

        assertEquals(ExclusiveMove.Outcome.UNAVAILABLE, ExclusiveMove.move(helper, directory, key(directory), "gone", "taken"))
        assertEquals(ExclusiveMove.Outcome.UNAVAILABLE, ExclusiveMove.move(helper, directory, key(directory), "gone", "free"))

        assertEquals("made meanwhile", File(directory, "taken").readText())
    }
}
