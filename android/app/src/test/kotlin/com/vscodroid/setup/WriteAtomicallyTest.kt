package com.vscodroid.setup

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException

/**
 * Tests for [writeAtomically], the mechanism that stops a failed extraction from
 * leaving a partial file under the name everything else checks for.
 *
 * These use real files rather than mocks: the property under test is what the
 * filesystem holds after a failure, which a mock would only assert about itself.
 */
class WriteAtomicallyTest {

    @TempDir
    lateinit var dir: File

    private fun tempFiles() = dir.listFiles()?.filter { it.name.endsWith(".tmp~") } ?: emptyList()

    @Test
    fun `writes the content and leaves no temporary file behind`() {
        val dest = File(dir, "payload.so")
        assertTrue(writeAtomically(dest) { it.write("hello".toByteArray()) })
        assertEquals("hello", dest.readText())
        assertTrue(tempFiles().isEmpty(), "a temporary file survived a successful write")
    }

    @Test
    fun `a failed write leaves the previous contents in place`() {
        // The reason this function exists. Before it, the bytes written ahead of
        // the failure stayed under the destination's own name, and exists()
        // accepted them.
        val dest = File(dir, "payload.so")
        dest.writeText("the version that works")

        val ok = writeAtomically(dest) {
            it.write("half of a replace".toByteArray())
            throw IOException("no space left on device")
        }

        assertFalse(ok, "a throwing writer reported success")
        assertEquals("the version that works", dest.readText())
        assertTrue(tempFiles().isEmpty(), "the partial write was left on disk")
    }

    @Test
    fun `a failed write leaves an absent destination absent`() {
        // The case the Python reconciliation depends on: the trigger is the
        // runtime's absence, so a failure has to preserve that absence for the
        // next launch to retry.
        val dest = File(dir, "payload.so")

        val ok = writeAtomically(dest) {
            it.write("partial".toByteArray())
            throw IOException("no space left on device")
        }

        assertFalse(ok)
        assertFalse(dest.exists(), "a failed write created the file it failed to write")
        assertTrue(tempFiles().isEmpty())
    }

    @Test
    fun `replaces an existing destination on success`() {
        val dest = File(dir, "payload.so")
        dest.writeText("the previous version")
        assertTrue(writeAtomically(dest) { it.write("the new version".toByteArray()) })
        assertEquals("the new version", dest.readText())
    }

    @Test
    fun `writes bytes unchanged`() {
        // Binaries go through here; a text-mode assumption would corrupt them.
        val dest = File(dir, "payload.so")
        val bytes = byteArrayOf(0x7f, 0x45, 0x4c, 0x46, 0x00, -1, 0x0a, 0x0d)
        assertTrue(writeAtomically(dest) { it.write(bytes) })
        assertArrayEquals(bytes, dest.readBytes())
    }

    /**
     * Two writers are never inside the body at the same time.
     *
     * The temp name is derived from the destination rather than randomised, for
     * the reason above, so two writers share one temp file. They are reachable
     * together: setup runs on Dispatchers.IO while SplashActivity's per-launch
     * repairs run on the main thread under no lock, and both write `.bashrc`
     * and `settings.json`. Overlapping, they open the same path, write into it
     * at their own offsets, and the first to finish renames it onto the
     * destination -- after which the loser is writing through a descriptor that
     * now points at the destination, and its own rename fails, so it reports
     * "unchanged" having just corrupted the file.
     *
     * This asserts the exclusion rather than the corruption, and that choice is
     * the point. A test that raced two payloads and demanded the file hold one
     * of them intact was written first and measured: it caught the unserialised
     * version once in three runs, because two writers that interleave *evenly*
     * still leave the later one's bytes at every offset. A test that fails a
     * third of the time when the code is broken is worse than none -- it goes
     * red at random, and the first person under time pressure deletes it.
     * Occupancy is the property the lock actually provides and it is decidable
     * on every run.
     */
    @Test
    fun `two writers are never inside the body at once`() {
        val dest = File(dir, "settings.json")
        val inside = java.util.concurrent.atomic.AtomicInteger(0)
        val mostAtOnce = java.util.concurrent.atomic.AtomicInteger(0)
        val start = java.util.concurrent.CountDownLatch(1)
        val done = java.util.concurrent.CountDownLatch(2)

        repeat(2) { n ->
            Thread {
                start.await()
                writeAtomically(dest) { out ->
                    val now = inside.incrementAndGet()
                    mostAtOnce.updateAndGet { maxOf(it, now) }
                    // Long enough that the other thread is certainly awake and
                    // trying, so a missing lock shows up rather than being
                    // outrun by scheduling.
                    Thread.sleep(50)
                    out.write("writer $n".toByteArray())
                    inside.decrementAndGet()
                }
                done.countDown()
            }.start()
        }
        start.countDown()
        assertTrue(done.await(30, java.util.concurrent.TimeUnit.SECONDS), "writers did not finish")

        assertEquals(
            1,
            mostAtOnce.get(),
            "two writers were inside the body together, so they shared one temp file and " +
                "the loser wrote through a descriptor the winner had already renamed",
        )
        assertTrue(tempFiles().isEmpty(), "a temporary file survived")
    }

    @Test
    fun `overwrites a stray temporary file from an earlier failure`() {
        // The name is derived from the destination rather than randomised, so a
        // copy killed outright leaves at most one stray per destination and the
        // next attempt reclaims it. That is the reason there is no sweep.
        val dest = File(dir, "payload.so")
        File(dir, "payload.so.tmp~").writeText("orphan from a killed process")

        assertTrue(writeAtomically(dest) { it.write("fresh".toByteArray()) })
        assertEquals("fresh", dest.readText())
        assertTrue(tempFiles().isEmpty(), "the stray was not reclaimed")
    }
}
