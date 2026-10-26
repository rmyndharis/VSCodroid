package com.vscodroid.storage

import android.database.Cursor
import android.os.FileObserver
import android.os.SystemClock
import android.provider.DocumentsContract
import com.vscodroid.setup.finishTestProcess
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.DirectoryStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class SafOutboundAncestorTest : SafInitialSyncTestBase() {
    @TempDir lateinit var elsewhere: File

    private enum class Schedule {
        ORDINARY, QUEUED_PARENT, QUEUED_GRANDPARENT, QUEUED_GRANDPARENT_DEVICE_EDIT,
        OUTPUT_OPEN_PARENT, INPUT_OPEN_PARENT_DEVICE_EDIT, DEVICE_EDIT, QUEUED_ROOT,
        LEAF_DIRECTORY, LEAF_FIFO, UNSUPPORTED_STREAM, CREATE_PARENT,
    }

    @Test
    fun `a queued save refuses a redirected parent before provider truncation`() =
        exercise(Schedule.QUEUED_PARENT)

    @Test
    fun `a queued save refuses a redirected grandparent before provider truncation`() =
        exercise(Schedule.QUEUED_GRANDPARENT)

    @Test
    fun `a parent redirected by provider open cannot replace the pinned input`() =
        exercise(Schedule.OUTPUT_OPEN_PARENT)

    @Test
    fun `an ordinary queued save reaches its provider document`() =
        exercise(Schedule.ORDINARY)

    @Test
    fun `a files root alias retains ordinary queued write back`() =
        exercise(Schedule.ORDINARY, aliasFilesRoot = true)

    @Test
    fun `a queued save refuses a redirected mirror root`() =
        exercise(Schedule.QUEUED_ROOT)

    @Test
    fun `a directory replacing a queued file cannot truncate its provider document`() =
        exercise(Schedule.LEAF_DIRECTORY)

    @Test
    fun `a fifo replacing a queued file cannot truncate its provider document`() =
        exercise(Schedule.LEAF_FIFO)

    @Test
    fun `a filesystem without secure directory streams leaves the provider intact`() =
        exercise(Schedule.UNSUPPORTED_STREAM)

    @Test
    fun `a refused source does not create an empty provider document`() =
        exercise(Schedule.CREATE_PARENT)

    @Test
    fun `a deeply nested queued save reaches its provider document`() {
        mirror = File(filesDir, "saf-mirrors/aaaaaaaaaaaa").apply { assertTrue(mkdirs()) }
        var parent = mirror
        repeat(300) {
            parent = File(parent, "d").apply { assertTrue(mkdir()) }
        }
        val local = File(parent, "notes.txt").apply { writeText("INTENDED DEEP USER EDIT") }
        assertTrue(local.absolutePath.toByteArray(Charsets.UTF_8).size < 4096)
        val document = named("doc-uri:deep-notes")
        val provider = ByteArrayOutputStream()
        var opens = 0
        every { resolver.openOutputStream(document, "wt") } answers {
            opens++
            provider
        }
        val engine = SafSyncEngine(context)
        val lost = mutableListOf<File>()
        engine.onWriteBackFailed = { lost.add(it) }
        engine.session.queue.offer(
            SyncJob(
                type = SyncType.MODIFY,
                localPath = local.absolutePath,
                safDocUri = document,
                safParentUri = null,
                safTreeUri = null,
                timestamp = 1L,
                relativePath = local.relativeTo(mirror).path,
                mirrorRoot = mirror,
            ),
        )

        try {
            engine.runWriteBackLoop { false }

            assertTrue(engine.session.queue.isEmpty())
            assertTrue(lost.isEmpty(), "a valid deep source was refused")
            assertEquals(1, opens)
            assertEquals("INTENDED DEEP USER EDIT", provider.toString(Charsets.UTF_8))
            assertEquals("INTENDED DEEP USER EDIT", local.readText())
        } finally {
            local.delete()
            var directory = parent
            repeat(300) {
                val previous = directory.parentFile
                directory.delete()
                directory = previous
            }
        }
    }

    @Test
    fun `a device conflict cannot create files through a redirected grandparent`() =
        exercise(Schedule.QUEUED_GRANDPARENT_DEVICE_EDIT)

    @Test
    fun `a device conflict refuses a parent redirected by its provider read`() =
        exercise(Schedule.INPUT_OPEN_PARENT_DEVICE_EDIT)

    @Test
    fun `an ordinary queued save preserves its intervening device edit`() =
        exercise(Schedule.DEVICE_EDIT)

    @Test
    fun `a files root alias preserves its intervening device edit`() =
        exercise(Schedule.DEVICE_EDIT, aliasFilesRoot = true)

    private fun exercise(schedule: Schedule, aliasFilesRoot: Boolean = false) {
        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } returns 0L
        val selectedFiles = if (aliasFilesRoot) {
            File(elsewhere, "files-alias").also {
                Files.createSymbolicLink(it.toPath(), filesDir.toPath())
            }
        } else filesDir
        every { context.filesDir } returns selectedFiles
        mirror = File(selectedFiles, "saf-mirrors/aaaaaaaaaaaa").apply { assertTrue(mkdirs()) }
        val deviceConflict = schedule == Schedule.QUEUED_GRANDPARENT_DEVICE_EDIT ||
            schedule == Schedule.INPUT_OPEN_PARENT_DEVICE_EDIT || schedule == Schedule.DEVICE_EDIT
        val nested = schedule == Schedule.QUEUED_GRANDPARENT || deviceConflict
        val relative = when {
            nested -> "docs/nested/notes.txt"
            schedule == Schedule.CREATE_PARENT -> "docs/new.txt"
            else -> "docs/notes.txt"
        }
        val tree = linkedMapOf(
            "root" to listOf(Child("docs", isDirectory = true, id = "docs-id")),
            "docs-id" to if (nested) listOf(Child("nested", isDirectory = true, id = "nested-id"))
                else listOf(Child("notes.txt", contents = "PROVIDER BASELINE", id = "notes-id")),
        )
        if (nested) tree["nested-id"] = listOf(Child("notes.txt", contents = "PROVIDER BASELINE", id = "notes-id"))
        deviceTree(tree)
        var providerBytes = "PROVIDER BASELINE".toByteArray()
        val opened = mutableListOf<ByteArrayOutputStream>()
        val privateRoot = File(elsewhere, "private").apply { assertTrue(mkdirs()) }
        val privateRelative = when {
            nested -> "nested/notes.txt"
            schedule == Schedule.QUEUED_ROOT -> "docs/notes.txt"
            schedule == Schedule.CREATE_PARENT -> "new.txt"
            else -> "notes.txt"
        }
        val privateFile = File(privateRoot, privateRelative).apply {
            parentFile.mkdirs()
            writeText("PRIVATE SENTINEL OUTSIDE MIRROR")
        }
        val directory = File(mirror, "docs")
        val moved = File(elsewhere, "original-docs")
        var redirected = false
        fun redirectParent() {
            assertFalse(redirected)
            assertTrue(directory.renameTo(moved), "the original directory was not moved")
            Files.createSymbolicLink(directory.toPath(), privateRoot.toPath())
            redirected = true
            assertTrue(Files.isSymbolicLink(directory.toPath()))
            assertEquals("PRIVATE SENTINEL OUTSIDE MIRROR", File(mirror, relative).readText())
        }
        var queued = false
        every { resolver.openInputStream(match { it.toString() == "doc-uri:notes-id" }) } answers {
            if (queued && schedule == Schedule.INPUT_OPEN_PARENT_DEVICE_EDIT && !redirected) redirectParent()
            ByteArrayInputStream(providerBytes)
        }
        every { resolver.query(match { it.toString() == "doc-uri:notes-id" }, any(), any(), any(), any()) } answers {
            mockk<Cursor>(relaxed = true) {
                every { moveToFirst() } returns true
                every { getColumnIndex(any()) } answers {
                    when (firstArg<String>()) {
                        DocumentsContract.Document.COLUMN_LAST_MODIFIED -> 0
                        DocumentsContract.Document.COLUMN_SIZE -> 1
                        DocumentsContract.Document.COLUMN_MIME_TYPE -> 2
                        else -> -1
                    }
                }
                every { isNull(any<Int>()) } returns false
                every { getLong(0) } returns DEVICE_TIME
                every { getLong(1) } answers { providerBytes.size.toLong() }
                every { getString(2) } returns "text/plain"
            }
        }
        every { resolver.openOutputStream(any(), "wt") } answers {
            if (schedule == Schedule.OUTPUT_OPEN_PARENT && !redirected) redirectParent()
            providerBytes = byteArrayOf()
            object : ByteArrayOutputStream() {
                override fun close() {
                    providerBytes = toByteArray()
                    super.close()
                }
            }.also { opened.add(it) }
        }
        val engine = sync()
        assertEquals("PROVIDER BASELINE", File(mirror, if (nested) relative else "docs/notes.txt").readText())
        assertTrue(opened.isEmpty(), "the initial import unexpectedly wrote to the provider")
        val local = File(mirror, relative).apply { writeText("INTENDED USER EDIT") }
        val creating = schedule == Schedule.CREATE_PARENT
        engine.handleMirrorEvent(if (creating) FileObserver.CREATE else FileObserver.MODIFY, local, mirror, named("tree"))
        assertEquals(if (creating) SyncType.CREATE else SyncType.MODIFY, engine.session.queue.single().type)
        if (!creating) assertEquals("doc-uri:notes-id", engine.session.queue.single().safDocUri.toString())
        queued = true

        if (deviceConflict) providerBytes = "DEVICE EDIT MADE AFTER SYNC".toByteArray()

        val unsupported = if (schedule == Schedule.UNSUPPORTED_STREAM) {
            mockkStatic(Files::class)
            mockk<DirectoryStream<Path>>(relaxed = true).also { stream ->
                every { Files.newDirectoryStream(mirror.parentFile.toPath().toAbsolutePath().normalize()) } returns stream
            }
        } else null
        when (schedule) {
            Schedule.QUEUED_PARENT, Schedule.QUEUED_GRANDPARENT,
            Schedule.QUEUED_GRANDPARENT_DEVICE_EDIT, Schedule.CREATE_PARENT -> redirectParent()
            Schedule.QUEUED_ROOT -> {
                assertTrue(mirror.renameTo(moved))
                Files.createSymbolicLink(mirror.toPath(), privateRoot.toPath())
                redirected = true
            }
            Schedule.LEAF_DIRECTORY -> {
                assertTrue(local.delete())
                assertTrue(local.mkdir())
            }
            Schedule.LEAF_FIFO -> {
                assertTrue(local.delete())
                val (code, output) = finishTestProcess(
                    ProcessBuilder("mkfifo", local.absolutePath).redirectErrorStream(true).start(),
                    timeoutMillis = 5_000,
                )
                assertEquals(0, code, output)
            }
            else -> Unit
        }
        if (schedule == Schedule.LEAF_FIFO) drainFifo(engine, local) else engine.runWriteBackLoop { false }

        if (deviceConflict) {
            assertEquals(setOf("notes.txt"), File(privateRoot, "nested").list()!!.toSet(),
                "a provider conflict created files outside the mirror")
        }
        val actual = providerBytes.toString(Charsets.UTF_8)
        assertFalse(actual.contains("PRIVATE SENTINEL"), "a redirected ancestor disclosed unrelated private bytes")
        assertEquals("PRIVATE SENTINEL OUTSIDE MIRROR", privateFile.readText())
        val refused = schedule != Schedule.ORDINARY && schedule != Schedule.OUTPUT_OPEN_PARENT &&
            schedule != Schedule.DEVICE_EDIT
        if (refused) {
            assertTrue(opened.isEmpty(), "a rejected source must not truncate its provider document")
            assertEquals(if (deviceConflict) "DEVICE EDIT MADE AFTER SYNC" else "PROVIDER BASELINE", actual)
            if (redirected) {
                val savedRelative = if (schedule == Schedule.QUEUED_ROOT) relative
                    else relative.removePrefix("docs/")
                assertEquals("INTENDED USER EDIT", File(moved, savedRelative).readText())
            }
            if (unsupported != null) verify(atLeast = 1) { unsupported.close() }
            if (creating) assertTrue(created.isEmpty(), "a refused source created an empty document")
        } else {
            if (deviceConflict) {
                assertEquals("DEVICE EDIT MADE AFTER SYNC", File(local.parentFile, "notes.txt.device-$DEVICE_TIME").readText())
            }
            if (schedule == Schedule.OUTPUT_OPEN_PARENT) {
                assertTrue(redirected, "the provider-open schedule was not reached")
                assertEquals("INTENDED USER EDIT", File(moved, "notes.txt").readText())
            }
            assertEquals(1, opened.size, "the accepted save must reach its real provider writer once")
            assertEquals("INTENDED USER EDIT", actual)
            if (aliasFilesRoot) assertTrue(Files.isSymbolicLink(selectedFiles.toPath()))
        }
    }

    /** Release a buggy FIFO reader before asserting, without an unbounded test join. */
    private fun drainFifo(engine: SafSyncEngine, fifo: File) {
        val failure = AtomicReference<Throwable?>()
        val worker = thread(isDaemon = true) {
            try { engine.runWriteBackLoop { false } } catch (error: Throwable) { failure.set(error) }
        }
        worker.join(2_000)
        val feeder = if (worker.isAlive) thread(isDaemon = true) {
            try {
                FileOutputStream(fifo).use { it.write("FIFO SENTINEL".toByteArray()) }
            } catch (error: Throwable) { failure.set(error) }
        } else null
        worker.join(2_000)
        feeder?.join(2_000)
        assertFalse(worker.isAlive, "the FIFO reader did not finish")
        assertFalse(feeder?.isAlive == true, "the FIFO release did not finish")
        failure.get()?.let { throw AssertionError("the FIFO drain failed", it) }
    }
}
