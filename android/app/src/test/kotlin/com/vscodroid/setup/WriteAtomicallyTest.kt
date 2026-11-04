package com.vscodroid.setup

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic

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

    private fun tempFiles() = dir.listFiles()?.filter {
        it.name.startsWith(".vscodroid-") && it.name.endsWith(".tmp~")
    } ?: emptyList()

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

    /**
     * The cause has to leave this function, because nothing else knows it.
     *
     * The catch here swallowed the exception whole: the boolean reached the
     * caller and the message reached nothing, so a device that filled up
     * mid-extraction produced a splash screen and a logcat trace that said which
     * step failed and never the words "No space left on device" -- on a screen
     * whose only control is Retry.
     */
    @Test
    fun `a failed write reports why it failed`() {
        val dest = File(dir, "payload.so")
        var reported: String? = null

        val ok = writeAtomically(dest, onError = { reported = it }) {
            throw IOException("No space left on device")
        }

        assertFalse(ok)
        assertEquals("No space left on device", reported)
    }

    /**
     * A rename that fails reports too, and it is a different failure: the bytes
     * were written and could not be moved into place. Reported as a sentence of
     * its own rather than left as the silent half of the same boolean.
     */
    @Test
    fun `a failed rename reports why it failed`() {
        // A non-empty directory under the destination's name: renameTo refuses to
        // replace it, which is the failure without needing a permission trick.
        val dest = File(dir, "payload.so")
        assertTrue(dest.mkdirs(), "could not stage the blocked destination")
        File(dest, "occupied").writeText("x")
        var reported: String? = null

        val ok = writeAtomically(dest, onError = { reported = it }) { it.write("hello".toByteArray()) }

        assertFalse(ok, "a write that could not be moved into place reported success")
        assertTrue(
            reported != null && reported!!.isNotEmpty(),
            "the rename failure reported no cause at all",
        )
    }

    /**
     * The control: a caller that asks for no cause is unaffected, which is what
     * keeps every existing writer in the file unchanged.
     */
    @Test
    fun `a successful write reports no cause`() {
        val dest = File(dir, "payload.so")
        var reported: String? = null

        assertTrue(writeAtomically(dest, onError = { reported = it }) { it.write("hello".toByteArray()) })
        assertNull(reported, "a successful write reported a cause")
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

    @Test
    fun `a neighboring user backup survives a successful replacement`() {
        val dest = File(dir, "settings.json").apply { writeText("previous settings") }
        val backup = File(dir, "settings.json.tmp~").apply { writeText("user backup") }

        assertTrue(writeAtomically(dest) { it.write("updated settings".toByteArray()) })

        assertEquals("updated settings", dest.readText())
        assertTrue(backup.isFile, "the writer erased a neighboring user backup")
        assertEquals("user backup", backup.readText())
    }

    @Test
    fun `a valid maximum length destination name remains writable`() {
        val dest = File(dir, "a".repeat(255)).apply { writeText("previous payload") }

        assertTrue(writeAtomically(dest) { it.write("updated payload".toByteArray()) })

        assertEquals("updated payload", dest.readText())
        assertEquals(setOf(dest.name), dir.list()!!.toSet())
    }

    @Test
    fun `an occupied exclusive temporary name preserves both existing files`() {
        val dest = File(dir, "payload.so").apply { writeText("working version") }
        // The first write into a directory sweeps strays, and this test is about a name taken after that.
        assertTrue(writeAtomically(File(dir, "warm")) { it.write("warm".toByteArray()) })
        val id = UUID.fromString("24ffb347-264c-4878-ab2d-bb552c07a03d")
        val occupied = File(dir, ".vscodroid-${Integer.toUnsignedString(dest.name.hashCode(), 16)}-$id.tmp~").apply { writeText("existing neighbor") }
        var entered = false
        mockkStatic(UUID::class)
        try {
            every { UUID.randomUUID() } returns id
            assertFalse(writeAtomically(dest) { entered = true; it.write("replacement".toByteArray()) })
        } finally {
            unmockkStatic(UUID::class)
        }
        assertFalse(entered, "an occupied path was opened as this write's temporary file")
        assertEquals("working version", dest.readText())
        assertEquals("existing neighbor", occupied.readText())
    }

    /**
     * A process that dies inside a write leaves its temporary file, and the unique name means
     * no later write of that file reuses it, so each kill used to leak a file as large as the
     * one being written.
     */
    @Test
    fun `the first write into a directory sweeps the temporary files a dead process left`() {
        val ofThisFile = File(dir, ".vscodroid-${Integer.toUnsignedString("payload.so".hashCode(), 16)}-24ffb347-264c-4878-ab2d-bb552c07a03d.tmp~")
            .apply { writeText("half a payload") }
        val ofAnotherFile = File(dir, ".vscodroid-1f-5d1c9a3e-0b6f-4c38-9c1f-7d3a2f4e8b10.tmp~")
            .apply { writeText("half of another file") }
        val neighbor = File(dir, ".vscodroid-backup").apply { writeText("not a temporary file") }
        val dest = File(dir, "payload.so")

        assertTrue(writeAtomically(dest) { it.write("whole".toByteArray()) })

        assertEquals("whole", dest.readText())
        assertFalse(ofThisFile.exists(), "a stray of the file being written was left")
        assertFalse(ofAnotherFile.exists(), "a stray of another file in the directory was left")
        assertEquals("not a temporary file", neighbor.readText())
        assertTrue(tempFiles().isEmpty())
    }

    @Test
    fun `a directory is swept once and not again by every write into it`() {
        assertTrue(writeAtomically(File(dir, "first")) { it.write("1".toByteArray()) })
        val late = File(dir, ".vscodroid-2a-0e9d7c61-3b52-4a0f-8f4e-6c1d9b7a5e32.tmp~")
            .apply { writeText("a stray after the sweep") }

        assertTrue(writeAtomically(File(dir, "second")) { it.write("2".toByteArray()) })

        assertTrue(late.exists(), "every write listed the directory again")
    }

    @Test
    fun `a link or a folder named like a temporary file is neither followed nor removed`() {
        val target = File(dir, "target.bin").apply { writeText("not a temporary file") }
        val link = File(dir, ".vscodroid-3b-1a2b3c4d-0000-4000-8000-000000000001.tmp~")
        Files.createSymbolicLink(link.toPath(), target.toPath())
        val folder = File(dir, ".vscodroid-3c-1a2b3c4d-0000-4000-8000-000000000002.tmp~").apply { mkdirs() }
        File(folder, "kept").writeText("inside")

        assertTrue(writeAtomically(File(dir, "payload.so")) { it.write("x".toByteArray()) })

        assertTrue(Files.isSymbolicLink(link.toPath()), "a link was removed")
        assertEquals("not a temporary file", target.readText())
        assertEquals("inside", File(folder, "kept").readText())
    }

    @Test
    fun `an unchecked writer failure removes only its own temporary file`() {
        val dest = File(dir, "payload.so").apply { writeText("working version") }

        assertThrows(IllegalStateException::class.java) {
            writeAtomically(dest) {
                it.write("partial".toByteArray())
                throw IllegalStateException("writer failed")
            }
        }

        assertEquals("working version", dest.readText())
        assertTrue(tempFiles().isEmpty())
    }

    /**
     * Two writers are never inside the body at the same time.
     *
     * Config and payload writes to one destination retain a single admission
     * order even though their temporary files now have different names.
     */
    @Test
    fun `two writers are never inside the body at once`() {
        val dest = File(dir, "settings.json")
        val inside = java.util.concurrent.atomic.AtomicInteger(0)
        val entered = java.util.concurrent.atomic.AtomicInteger(0)
        val mostAtOnce = java.util.concurrent.atomic.AtomicInteger(0)
        val start = java.util.concurrent.CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(2)
        try {
            val results = (0..1).map { n ->
                workers.submit(Callable {
                    check(start.await(10, TimeUnit.SECONDS))
                    writeAtomically(dest) { out ->
                        entered.incrementAndGet()
                        val now = inside.incrementAndGet()
                        mostAtOnce.updateAndGet { maxOf(it, now) }
                        try {
                            Thread.sleep(50)
                            out.write("writer $n".toByteArray())
                        } finally {
                            inside.decrementAndGet()
                        }
                    }
                })
            }
            start.countDown()
            results.forEach { assertTrue(it.get(30, TimeUnit.SECONDS), "a writer failed") }
        } finally {
            start.countDown()
            workers.shutdownNow()
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS), "writers did not stop")
        }

        assertEquals(2, entered.get(), "both writer bodies must run")
        assertEquals(
            1,
            mostAtOnce.get(),
            "writers to the same destination were not serialized",
        )
        assertTrue(tempFiles().isEmpty(), "a temporary file survived")
    }

    /**
     * Two writers to DIFFERENT destinations never wait on each other.
     *
     * The exclusion above is per destination, and the test above cannot tell that
     * from one global monitor because both its writers share a file. This is the
     * half that can: one monitor for every write put an asset copy and a
     * launch-time config write on the same lock, and the asset copies here are
     * the 118 MiB and 96 MiB Copilot runtimes inflating out of the APK. A second
     * SplashActivity entering while the first instance's extraction was still
     * running (Home and relaunch, or a configuration change the manifest does not
     * declare; neither stops the extraction, which checks for cancellation
     * nowhere) reached `createBashEnvFile` on the MAIN thread and blocked there
     * for the length of one such copy, before the first frame was drawn.
     *
     * Written so that a global monitor DEADLOCKS the pair rather than merely
     * slowing it, and then times out: the slow writer will not leave the body
     * until the quick one has finished, which a shared monitor makes impossible.
     * The waits are bounded so the failure is a red assertion rather than a hung
     * suite.
     */
    @Test
    fun `a write to one destination does not hold up a write to another`() {
        val slow = File(dir, "runtime.node")
        val quick = File(dir, "bash-env.sh")
        val slowInside = java.util.concurrent.CountDownLatch(1)
        val quickFinished = java.util.concurrent.CountDownLatch(1)
        val quickGotThrough = java.util.concurrent.atomic.AtomicBoolean(false)

        val worker = Executors.newSingleThreadExecutor()
        try {
            val slowWriter = worker.submit(Callable {
                writeAtomically(slow) { out ->
                    slowInside.countDown()
                    quickGotThrough.set(quickFinished.await(10, TimeUnit.SECONDS))
                    out.write("the asset copy".toByteArray())
                }
            })
            assertTrue(slowInside.await(10, TimeUnit.SECONDS), "the slow writer never entered the body")
            assertTrue(
                writeAtomically(quick) { it.write("the config write".toByteArray()) },
                "the config write failed",
            )
            quickFinished.countDown()
            assertTrue(slowWriter.get(15, TimeUnit.SECONDS), "the asset write failed")
        } finally {
            quickFinished.countDown()
            worker.shutdownNow()
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS), "the asset writer did not stop")
        }

        assertTrue(
            quickGotThrough.get(),
            "a write to one destination was held up by a write to another, so a " +
                "launch-time config write waits on however long an asset copy takes",
        )
        assertEquals("the asset copy", slow.readText())
        assertEquals("the config write", quick.readText())
        assertTrue(tempFiles().isEmpty(), "a temporary file survived")
    }

    @Test
    fun `a neighboring temporary-looking file remains untouched`() {
        val dest = File(dir, "payload.so")
        val neighbor = File(dir, "payload.so.tmp~").apply { writeText("unclaimed neighboring content") }

        assertTrue(writeAtomically(dest) { it.write("fresh".toByteArray()) })
        assertEquals("fresh", dest.readText())
        assertEquals("unclaimed neighboring content", neighbor.readText())
        assertTrue(tempFiles().isEmpty())
    }

    @Test
    fun `a temporary symlink does not overwrite another file`() {
        val project = File(dir, "project.txt").apply { writeText("project contents") }
        val dest = File(dir, "payload.so")
        Files.createSymbolicLink(File(dir, "payload.so.tmp~").toPath(), project.toPath())

        assertTrue(writeAtomically(dest) { it.write("fresh".toByteArray()) })

        assertEquals("project contents", project.readText())
        assertEquals("fresh", dest.readText())
        assertFalse(Files.isSymbolicLink(dest.toPath()))
        assertTrue(Files.isSymbolicLink(File(dir, "payload.so.tmp~").toPath()))
        assertTrue(tempFiles().isEmpty())
    }

    @Test
    fun `a temporary hardlink does not overwrite another file`() {
        val project = File(dir, "project.txt").apply { writeText("project contents") }
        val dest = File(dir, "payload.so")
        Files.createLink(File(dir, "payload.so.tmp~").toPath(), project.toPath())

        assertTrue(writeAtomically(dest) { it.write("fresh".toByteArray()) })

        assertEquals("project contents", project.readText())
        assertEquals("fresh", dest.readText())
        assertFalse(Files.isSameFile(dest.toPath(), project.toPath()))
        assertTrue(Files.isSameFile(File(dir, "payload.so.tmp~").toPath(), project.toPath()))
        assertTrue(tempFiles().isEmpty())
    }

    @Test
    fun `a failed write through a temporary hardlink keeps the destination intact`() {
        val dest = File(dir, "payload.so").apply { writeText("working version") }
        Files.createLink(File(dir, "payload.so.tmp~").toPath(), dest.toPath())

        assertFalse(writeAtomically(dest) {
            it.write("partial".toByteArray())
            throw IOException("no space left on device")
        })

        assertEquals("working version", dest.readText())
        assertTrue(Files.isSameFile(File(dir, "payload.so.tmp~").toPath(), dest.toPath()))
        assertTrue(tempFiles().isEmpty())
    }

    @Test
    fun `a dangling temporary symlink does not create its target`() {
        val project = File(dir, "project.txt")
        val dest = File(dir, "payload.so")
        Files.createSymbolicLink(File(dir, "payload.so.tmp~").toPath(), project.toPath())

        assertTrue(writeAtomically(dest) { it.write("fresh".toByteArray()) })

        assertFalse(project.exists())
        assertEquals("fresh", dest.readText())
        assertFalse(Files.isSymbolicLink(dest.toPath()))
        assertTrue(Files.isSymbolicLink(File(dir, "payload.so.tmp~").toPath()))
        assertTrue(tempFiles().isEmpty())
    }

    @Test
    fun `a neighboring temporary-looking directory survives replacement`() {
        val dest = File(dir, "payload.so").apply { writeText("working version") }
        val blocker = File(dir, "payload.so.tmp~").apply { mkdirs() }
        File(blocker, "keep.txt").writeText("keep")
        var reported: String? = null

        assertTrue(writeAtomically(dest, onError = { reported = it }) { it.write("fresh".toByteArray()) })

        assertEquals("fresh", dest.readText())
        assertEquals("keep", File(blocker, "keep.txt").readText())
        assertNull(reported)
    }
}
