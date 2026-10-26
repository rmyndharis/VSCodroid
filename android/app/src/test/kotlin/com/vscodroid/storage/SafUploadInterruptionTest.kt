package com.vscodroid.storage

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.FileObserver
import android.provider.DocumentsContract
import com.vscodroid.util.Logger
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission

/**
 * What an interrupted upload leaves behind, and what the next sync does with it.
 *
 * A write-back opens its document with `"wt"`, which truncates at open, and a
 * provider stamps a fresh modification time on the truncated bytes. When the copy
 * dies partway -- the low-memory killer, a swipe-away during the drain, or any
 * provider error -- the device holds a *newer, shorter* copy of a file whose only
 * complete version is the mirror's. That is precisely the shape
 * [SafSyncEngine.shouldOverwriteMirror] treats as "the device edited this", so
 * the next reopen copied the truncation over the edit and the work was gone from
 * both sides. Nothing in the comparison can tell the two apart on its own: the
 * difference is whether this app was mid-upload, and only this app can know that.
 *
 * The journal here is that memory: a path is recorded before its stream opens
 * and removed once the copy lands, so a path still listed at the next sync is one
 * whose device copy must not be trusted, whatever its clock says. The mirror is
 * kept instead and the upload attempted again, which is also the repair: a
 * journal entry that outlived its write can only mean the write never finished.
 */
class SafUploadInterruptionTest {

    @TempDir
    lateinit var mirror: File

    @TempDir
    lateinit var filesDir: File

    private lateinit var resolver: ContentResolver
    private lateinit var context: Context
    private lateinit var engine: SafSyncEngine
    private lateinit var treeUri: Uri
    private val uris = mutableMapOf<String, Uri>()

    /** The journal, as the engine names it beside the mirrors it serves. */
    private val journal: File get() = File(filesDir, SafSyncEngine.UPLOADS_IN_FLIGHT_FILE)

    @BeforeEach
    fun setUp() {
        mockkObject(Logger)
        every { Logger.i(any(), any()) } just Runs
        every { Logger.d(any(), any()) } just Runs
        every { Logger.w(any(), any()) } just Runs
        every { Logger.w(any(), any(), any()) } just Runs
        every { Logger.e(any(), any()) } just Runs
        every { Logger.e(any(), any(), any()) } just Runs

        mockkStatic(DocumentsContract::class)
        every { DocumentsContract.getTreeDocumentId(any()) } returns "root"
        every { DocumentsContract.buildChildDocumentsUriUsingTree(any(), any()) } returns
            mockk(relaxed = true)
        every { DocumentsContract.buildDocumentUriUsingTree(any(), any()) } answers {
            uris.getOrPut(secondArg()) { mockk(relaxed = true) }
        }
        every { DocumentsContract.getDocumentId(any()) } answers {
            uris.entries.first { it.value === firstArg<Uri>() }.key
        }

        resolver = mockk(relaxed = true)
        context = mockk<Context>(relaxed = true)
        every { context.contentResolver } returns resolver
        every { context.filesDir } returns filesDir
        engine = SafSyncEngine(context)
        treeUri = mockk(relaxed = true)
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    /**
     * The device folder answers for one file, the way the write-back path needs:
     * a child query that names it, so the event resolves a document to write into.
     */
    private fun deviceHolding(name: String) {
        val cursor = mockk<Cursor>(relaxed = true)
        var row = -1
        every { cursor.moveToNext() } answers { ++row == 0 }
        every { cursor.getColumnIndexOrThrow(any()) } answers {
            if (firstArg<String>() == DocumentsContract.Document.COLUMN_DOCUMENT_ID) 0 else 1
        }
        every { cursor.getString(0) } answers { "doc:$name" }
        every { cursor.getString(1) } answers { name }
        every { resolver.query(any(), any(), any(), any(), any()) } answers {
            row = -1
            cursor
        }
        // A real stream, and not decoration. The repair path reads the document
        // before it replaces it, and a relaxed mock answers 0 from `read`, which
        // `InputStream.copyTo` treats as "keep going": its loop is
        // `while (bytes >= 0)`, so the copy spins for ever and takes the test JVM
        // with it. A real stream ends at -1, and the fixture has to keep the
        // contract the engine is entitled to rely on.
        every { resolver.openInputStream(any()) } answers { ByteArrayInputStream(ByteArray(0)) }
    }

    /** One event, and the write-back thread's pass over what it queued. */
    private fun deliver(event: Int, entry: String) {
        engine.handleMirrorEvent(event, File(mirror, entry), mirror, treeUri)
        engine.runWriteBackLoop { false }
    }

    @ParameterizedTest
    @ValueSource(strings = ["Icon\r", "line\nname"])
    fun `an interrupted upload with a line break name is repaired at reopen`(name: String) {
        deviceHolding(name)
        val file = File(mirror, name).apply { writeText("complete local edit") }
        every { resolver.openOutputStream(any(), "wt") } answers {
            object : OutputStream() {
                override fun write(value: Int) = throw IOException("provider interrupted")
            }
        }
        deliver(FileObserver.MODIFY, name)
        assertEquals(setOf(file.absolutePath), engine.uploadsInFlight())
        val repaired = ByteArrayOutputStream()
        every { resolver.openOutputStream(any(), "wt") } returns repaired
        runBlocking { engine.initialSync(treeUri, mirror) { _, _ -> } }
        assertEquals("complete local edit", repaired.toString())
        assertEquals("complete local edit", file.readText())
        assertTrue(engine.uploadsInFlight().isEmpty())
        File(mirror.path + SafSyncEngine.SYNCED_RECORD_SUFFIX).delete()
    }

    @Test
    fun `corrupt journal lines do not permit replacing a foreign document without a copy`() {
        deviceHolding("notes.txt")
        val file = File(mirror, "notes.txt").apply { writeText("local edit") }
        journal.writeText("junk\n\u0000bad\n${file.absolutePath}")
        every { resolver.openInputStream(any()) } answers {
            ByteArrayInputStream("foreign device edit".toByteArray())
        }
        val repaired = ByteArrayOutputStream()
        every { resolver.openOutputStream(any(), "wt") } returns repaired

        runBlocking { engine.initialSync(treeUri, mirror) { _, _ -> } }

        assertEquals("local edit", repaired.toString())
        val copies = mirror.listFiles()!!.filter { it.name.startsWith("notes.txt.device-") }
        assertEquals(1, copies.size)
        assertEquals("foreign device edit", copies.single().readText())
        assertFalse(file.absolutePath in engine.uploadsInFlight())
        File(mirror.path + SafSyncEngine.SYNCED_RECORD_SUFFIX).delete()
    }

    @Test
    fun `journal round trips line breaks and literal escape sequences`() {
        val names = listOf("Icon\r", "line\nname", "literal\\nname")
        val paths = names.map { File(mirror, it).apply { writeText("pending") }.absolutePath }
        val record = SafSyncEngine::class.java.getDeclaredMethod("recordUploadIntent", String::class.java)
            .apply { isAccessible = true }
        val consume = SafSyncEngine::class.java.getDeclaredMethod("consumeStaleUploadRecord", String::class.java)
            .apply { isAccessible = true }
        for (path in paths) assertEquals(true, record.invoke(engine, path))
        assertEquals(paths.toSet(), engine.uploadsInFlight())
        for (path in paths) consume.invoke(engine, path)
        assertTrue(engine.uploadsInFlight().isEmpty())
        assertFalse(journal.exists())
    }

    @Test
    fun `a legacy journal keeps literal backslash names while corrupt lines stay inert`() {
        val local = File(mirror, "literal\\nname").apply { writeText("keep") }
        journal.writeText("junk\n\u0000bad\n${local.absolutePath}")
        assertTrue(local.absolutePath in engine.uploadsInFlight())
        engine.clearUploadsUnder(mirror)
        assertEquals("keep", local.readText())
        assertFalse(local.absolutePath in engine.uploadsInFlight())
    }

    @Test
    fun `an unavailable journal refuses truncation and permits a later retry`() {
        assertUnavailableJournalRefusesUpload(journal)
    }

    @Test
    fun `an unavailable journal refuses creation of an empty device file`() {
        assertUnavailableJournalRefusesCreation(FileObserver.CREATE)
    }

    @Test
    fun `an unavailable journal refuses creation for an unknown modified file`() {
        assertUnavailableJournalRefusesCreation(FileObserver.MODIFY)
    }

    private fun assertUnavailableJournalRefusesCreation(event: Int) {
        deviceHolding("other.txt")
        val localBytes = byteArrayOf(0, 1, 2, -1, 10, 42)
        val local = File(mirror, "notes.txt").apply { writeBytes(localBytes) }
        val created = mutableListOf<String>()
        every { DocumentsContract.createDocument(any(), any(), any(), any()) } answers {
            created.add(arg(3))
            uris.getOrPut("doc:notes.txt") { mockk(relaxed = true) }
        }
        val device = ByteArrayOutputStream()
        every { resolver.openOutputStream(any(), "wt") } returns device
        val failed = mutableListOf<File>()
        engine.onWriteBackFailed = { failed.add(it) }
        assertTrue(journal.mkdir())
        File(journal, "occupied").writeText("keep")

        deliver(event, local.name)

        assertTrue(created.isEmpty(), "a refused save left an empty provider document")
        verify(exactly = 0) { resolver.openOutputStream(any(), any()) }
        assertArrayEquals(localBytes, local.readBytes())
        assertEquals(listOf(local), failed)

        every { resolver.query(any(), any(), any(), any(), any()) } returns
            mockk<Cursor>(relaxed = true) { every { moveToNext() } returns false }
        engine = SafSyncEngine(context).also { it.onWriteBackFailed = { file -> failed.add(file) } }
        runBlocking { engine.initialSync(treeUri, mirror) { _, _ -> } }
        assertArrayEquals(localBytes, local.readBytes())
        assertTrue(created.isEmpty())

        assertTrue(journal.deleteRecursively())
        deliver(event, local.name)

        assertEquals(listOf("notes.txt"), created)
        assertArrayEquals(localBytes, device.toByteArray())
        assertArrayEquals(localBytes, local.readBytes())
        assertEquals(listOf(local), failed)
        assertFalse(journal.exists())
    }

    @Test
    fun `an unavailable journal temporary file preserves existing recovery records`() {
        val other = File(filesDir, "other-mirror/other.txt").apply {
            parentFile!!.mkdirs()
            writeText("another pending edit")
        }
        journal.writeText(other.absolutePath)

        assertUnavailableJournalRefusesUpload(
            File(filesDir, SafSyncEngine.UPLOADS_IN_FLIGHT_FILE + SafSyncEngine.PARTIAL_SUFFIX),
            listOf(other.absolutePath),
        )
    }

    private fun assertUnavailableJournalRefusesUpload(
        blocked: File,
        previousRecords: List<String> = emptyList(),
    ) {
        deviceHolding("notes.txt")
        val localBytes = byteArrayOf(0, 1, 2, -1, 10, 42)
        val local = File(mirror, "notes.txt").apply { writeBytes(localBytes) }
        val device = ByteArrayOutputStream().apply { write("device original".toByteArray()) }
        val failed = mutableListOf<File>()
        engine.onWriteBackFailed = { failed.add(it) }
        every { resolver.openOutputStream(any(), "wt") } answers {
            device.reset()
            device
        }
        assertTrue(blocked.mkdir())
        File(blocked, "occupied").writeText("keep")

        deliver(FileObserver.MODIFY, local.name)

        assertEquals("device original", device.toString(Charsets.UTF_8.name()))
        verify(exactly = 0) { resolver.openOutputStream(any(), any()) }
        assertArrayEquals(localBytes, local.readBytes())
        assertEquals(listOf(local), failed)
        if (previousRecords.isNotEmpty()) assertEquals(previousRecords, journal.readLines())

        assertTrue(blocked.deleteRecursively())
        deliver(FileObserver.MODIFY, local.name)

        assertArrayEquals(localBytes, device.toByteArray())
        assertArrayEquals(localBytes, local.readBytes())
        assertEquals(listOf(local), failed)
        assertEquals(previousRecords, if (journal.isFile) journal.readLines() else emptyList())
    }

    @Test
    fun `a refused atomic journal replacement preserves every recovery record`() {
        deviceHolding("notes.txt")
        val local = File(mirror, "notes.txt").apply { writeText("the whole edit") }
        val other = File(filesDir, "other.txt").apply { writeText("another pending edit") }
        val original = "${other.absolutePath}\n".toByteArray()
        journal.writeBytes(original)
        val temporary = File(filesDir, journal.name + SafSyncEngine.PARTIAL_SUFFIX)
            .apply { writeText("existing temporary file") }
        val probe = File(filesDir, "rename-probe").apply { writeText("probe") }
        val failed = mutableListOf<File>()
        engine.onWriteBackFailed = { failed.add(it) }
        val device = ByteArrayOutputStream().apply { write("device original".toByteArray()) }
        every { resolver.openOutputStream(any(), "wt") } answers {
            device.reset()
            device
        }
        val permissions = Files.getPosixFilePermissions(filesDir.toPath())
        val writePermissions = setOf(
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.GROUP_WRITE,
            PosixFilePermission.OTHERS_WRITE,
        )
        try {
            Files.setPosixFilePermissions(filesDir.toPath(), permissions - writePermissions)
            assertFalse(probe.renameTo(File(filesDir, "rename-target")))
            // Existing files remain writable even though their directory refuses renames.
            journal.writeBytes(original)
            temporary.writeText("still writable")
            assertEquals("still writable", temporary.readText())

            deliver(FileObserver.MODIFY, local.name)

            assertArrayEquals(original, journal.readBytes())
            assertEquals("device original", device.toString(Charsets.UTF_8.name()))
            verify(exactly = 0) { resolver.openOutputStream(any(), any()) }
            assertEquals("the whole edit", local.readText())
            assertEquals(listOf(local), failed)
        } finally {
            Files.setPosixFilePermissions(filesDir.toPath(), permissions)
        }
    }

    @Test
    fun `an existing recovery record permits a write without rewriting the journal`() {
        deviceHolding("notes.txt")
        val local = File(mirror, "notes.txt").apply { writeText("the whole edit") }
        journal.writeText(local.absolutePath)
        val blocked = File(filesDir, SafSyncEngine.UPLOADS_IN_FLIGHT_FILE + SafSyncEngine.PARTIAL_SUFFIX)
        assertTrue(blocked.mkdir())
        File(blocked, "occupied").writeText("keep")
        val device = ByteArrayOutputStream()
        every { resolver.openOutputStream(any(), "wt") } returns device

        deliver(FileObserver.MODIFY, local.name)

        assertEquals("the whole edit", device.toString(Charsets.UTF_8.name()))
        assertFalse(journal.exists())
    }

    @Test
    fun `a failed journal retirement does not report a landed write as failed`() {
        deviceHolding("notes.txt")
        val local = File(mirror, "notes.txt").apply { writeText("the whole edit") }
        val other = File(filesDir, "other-mirror/other.txt").apply {
            parentFile!!.mkdirs()
            writeText("another pending edit")
        }
        journal.writeText(other.absolutePath)
        val blocked = File(filesDir, SafSyncEngine.UPLOADS_IN_FLIGHT_FILE + SafSyncEngine.PARTIAL_SUFFIX)
        val failed = mutableListOf<File>()
        engine.onWriteBackFailed = { failed.add(it) }
        val device = ByteArrayOutputStream()
        every { resolver.openOutputStream(any(), "wt") } answers {
            assertTrue(blocked.mkdir())
            File(blocked, "occupied").writeText("keep")
            device
        }

        deliver(FileObserver.MODIFY, local.name)

        assertEquals("the whole edit", device.toString(Charsets.UTF_8.name()))
        assertTrue(failed.isEmpty())
        assertEquals(listOf(other.absolutePath, local.absolutePath), journal.readLines())

        assertTrue(blocked.deleteRecursively())
        every { resolver.openOutputStream(any(), "wt") } returns ByteArrayOutputStream()
        deliver(FileObserver.MODIFY, local.name)
        assertEquals(listOf(other.absolutePath), journal.readLines())
        assertTrue(failed.isEmpty())
    }

    @Test
    fun `a write-back that cannot finish leaves its path recorded`() {
        deviceHolding("notes.txt")
        File(mirror, "notes.txt").writeText("the whole edit")
        every { resolver.openOutputStream(any(), any()) } throws java.io.IOException("cut short")

        deliver(android.os.FileObserver.MODIFY, "notes.txt")

        assertEquals(
            listOf(File(mirror, "notes.txt").absolutePath),
            journal.readLines(),
            "the one fact that separates a truncated copy from a device edit is " +
                "whether an upload to it never finished, and that fact lives here"
        )
    }

    @Test
    fun `a write-back that lands removes the record`() {
        deviceHolding("notes.txt")
        File(mirror, "notes.txt").writeText("the whole edit")
        every { resolver.openOutputStream(any(), any()) } returns
            java.io.ByteArrayOutputStream()

        deliver(android.os.FileObserver.MODIFY, "notes.txt")

        assertFalse(journal.isFile, "a finished upload must not distrust its own document")
    }

    /**
     * The claim a failed write-back takes has to be given back, or the journal line
     * outlives the failure that wrote it.
     *
     * The count is per path and process-wide, and the failure exits used to return
     * without releasing it. From then on a landed write-back saw a writer that no
     * longer existed and kept the line, and the case below shows what that costs.
     * Neither existing case reaches it: each drives one outcome on a fresh path,
     * and the defect needs both outcomes on the same one.
     */
    @Test
    fun `a write-back that fails and then lands stops distrusting the document`() {
        deviceHolding("notes.txt")
        val local = File(mirror, "notes.txt").apply { writeText("the whole edit") }
        every { resolver.openOutputStream(any(), any()) } throws java.io.IOException("cut short")

        deliver(android.os.FileObserver.MODIFY, "notes.txt")
        assertEquals(
            listOf(local.absolutePath), journal.readLines(),
            "the failed write-back must record the edit that never reached the device",
        )

        every { resolver.openOutputStream(any(), any()) } returns java.io.ByteArrayOutputStream()
        deliver(android.os.FileObserver.MODIFY, "notes.txt")

        val listed = if (journal.isFile) journal.readLines() else emptyList()
        assertFalse(
            listed.contains(local.absolutePath),
            "the document is current now and the journal still distrusts it, so every " +
                "later sync will force the mirror over whatever the device holds",
        )
    }

    /**
     * What the leak above costs, driven all the way to the file.
     *
     * With the claim stranded, the sync takes the branch that exists to rescue a
     * truncated upload and applies it to a document that was edited on the device
     * after the upload had already landed. The device's edit is overwritten with
     * the mirror, silently, and it happens again on every reopen because the line
     * that triggers it can no longer be consumed.
     */
    @Test
    fun `a device edit survives a write-back that failed once and then landed`() {
        deviceHolding("notes.txt")
        val local = File(mirror, "notes.txt").apply { writeText("the whole edit") }
        local.setLastModified(1_000_000_000_000L)
        every { resolver.openOutputStream(any(), any()) } throws java.io.IOException("cut short")
        deliver(android.os.FileObserver.MODIFY, "notes.txt")
        every { resolver.openOutputStream(any(), any()) } returns java.io.ByteArrayOutputStream()
        deliver(android.os.FileObserver.MODIFY, "notes.txt")

        // Now the device copy moves ahead: newer, and holding text the mirror does
        // not. Nothing about it is a truncation, and nothing should overwrite it.
        val cursor = mockk<Cursor>(relaxed = true)
        var row = -1
        every { cursor.moveToNext() } answers { ++row == 0 }
        every { cursor.getColumnIndexOrThrow(any()) } answers {
            when (firstArg<String>()) {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID -> 0
                DocumentsContract.Document.COLUMN_DISPLAY_NAME -> 1
                DocumentsContract.Document.COLUMN_MIME_TYPE -> 2
                else -> 3
            }
        }
        every { cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED) } returns 4
        every { cursor.isNull(any()) } returns false
        every { cursor.getString(0) } returns "doc:notes.txt"
        every { cursor.getString(1) } returns "notes.txt"
        every { cursor.getString(2) } returns "text/plain"
        every { cursor.getLong(3) } returns 11L
        every { cursor.getLong(4) } returns 2_000_000_000_000L
        every { resolver.query(any(), any(), any(), any(), any()) } returns cursor
        every { resolver.openInputStream(any()) } answers {
            ByteArrayInputStream("DEVICE EDIT".toByteArray())
        }

        runBlocking { engine.initialSync(treeUri, mirror) { _, _ -> } }

        assertEquals(
            "DEVICE EDIT", local.readText(),
            "the sync treated a landed upload as an unfinished one and forced the " +
                "mirror over an edit made on the device, which existed only there",
        )
    }

    @Test
    fun `a sync prefers the mirror of a file whose upload was cut short`() {
        // The device's copy is newer and shorter, and its bytes are a PREFIX of
        // the mirror's, which is what `"wt"` plus a copy cut short leaves: the
        // open truncates and the copy runs forward from zero. Length alone is not
        // the shape, and this fixture said "TRUNC" until it was pointed at the
        // bytes: an edit made on the device is also newer and shorter, and telling
        // the two apart is the whole job. The journal says a write started here;
        // the bytes say whether it is still the thing that finished last.
        val local = File(mirror, "notes.txt").apply { writeText("the whole edit") }
        local.setLastModified(1_000_000_000_000L)
        journal.writeText(local.absolutePath)
        val byDocId = mapOf("doc:notes.txt" to "the wh")

        val cursor = mockk<Cursor>(relaxed = true)
        var row = -1
        every { cursor.moveToNext() } answers { ++row == 0 }
        every { cursor.getColumnIndexOrThrow(any()) } answers {
            when (firstArg<String>()) {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID -> 0
                DocumentsContract.Document.COLUMN_DISPLAY_NAME -> 1
                DocumentsContract.Document.COLUMN_MIME_TYPE -> 2
                else -> 3
            }
        }
        every { cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED) } returns 4
        every { cursor.isNull(any()) } returns false
        every { cursor.getString(0) } returns "doc:notes.txt"
        every { cursor.getString(1) } returns "notes.txt"
        every { cursor.getString(2) } returns "text/plain"
        every { cursor.getLong(3) } returns 6L
        every { cursor.getLong(4) } returns 2_000_000_000_000L
        every { resolver.query(any(), any(), any(), any(), any()) } returns cursor
        every { resolver.openInputStream(any()) } answers {
            ByteArrayInputStream(byDocId.getValue("doc:notes.txt").toByteArray())
        }

        runBlocking { engine.initialSync(treeUri, mirror) { _, _ -> } }

        assertEquals(
            "the whole edit", local.readText(),
            "the device's newer mtime was this app's own interrupted upload; the " +
                "edit existed only here"
        )
        // A missing journal is the emptied one: the sync deleted it on consuming
        // the last line, and reading the file would mistake repair for failure.
        val listed = if (journal.isFile) journal.readLines() else emptyList()
        assertFalse(
            listed.contains(local.absolutePath),
            "the record was consumed by the sync that acted on it"
        )
        // Delivery, not enqueueing: the write-back queue belongs to the watcher's
        // lifecycle, and startWatching opens by clearing it, so a job offered
        // between the sync and the thread that would run it never executes. The
        // repair has to have reached the document inside the sync itself.
        verify(exactly = 1) { resolver.openOutputStream(any(), "wt") }
    }

    @Test
    fun `reclaiming a mirror takes its journal entries with it`() {
        val keptMirror = File(mirror, "kept").apply { mkdirs() }
        val gone = File(filesDir, "saf-mirrors/gone-hash").apply { mkdirs() }
        File(gone, "notes.txt").writeText("x")
        journal.writeText(
            listOf(
                File(gone, "notes.txt").absolutePath,
                File(keptMirror, "other.txt").absolutePath
            ).joinToString("\n")
        )

        engine.clearUploadsUnder(gone)

        val listed = if (journal.isFile) journal.readLines() else emptyList()
        assertEquals(
            listOf(File(keptMirror, "other.txt").absolutePath), listed,
            "entries under a mirror being reclaimed must not outlive it; the " +
                "same folder re-granted weeks later hashes back to the same path, " +
                "and a surviving entry would throw away the device edits that " +
                "followed"
        )
    }

    /**
     * Two write-backs of one file overlap: a drain that outlived the folder it belongs
     * to is still streaming when the next sync writes the same path. Both bracket the
     * copy with the journal, and the journal has one line per path, so the writer that
     * finished first removed the bracket the other was still inside. A death in that
     * window then left a device copy that is newer, shorter and unrecorded, which the
     * next sync reads as an edit made on the device and copies over the mirror.
     */
    @Test
    fun `a path stays recorded while another write to it is still in flight`() {
        deviceHolding("notes.txt")
        val file = File(mirror, "notes.txt").apply { writeText("the whole edit") }
        val released = AtomicBoolean(false)
        val firstWriteStarted = CountDownLatch(1)
        var opens = 0
        every { resolver.openOutputStream(any(), any()) } answers {
            if (opens++ == 0) {
                object : OutputStream() {
                    override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
                    override fun write(b: ByteArray, off: Int, len: Int) {
                        firstWriteStarted.countDown()
                        // Uninterruptible: the point is a write still in flight.
                        while (!released.get()) {
                            try { Thread.sleep(10) } catch (_: InterruptedException) { }
                        }
                    }
                }
            } else {
                ByteArrayOutputStream()
            }
        }

        // The first writer: this engine's drain, still streaming.
        engine.handleMirrorEvent(FileObserver.MODIFY, file, mirror, treeUri)
        val drain = thread(isDaemon = true) { engine.runWriteBackLoop { false } }
        assertTrue(
            firstWriteStarted.await(5, TimeUnit.SECONDS),
            "setup failed: the first write never reached the provider",
        )

        try {
            // A different document permits both writers to hold this mirror path.
            val otherDocument = mockk<Uri>(relaxed = true)
            every { otherDocument.toString() } returns "content://test/other/notes.txt"
            val second = SafSyncEngine(context)
            // Exercise the writer independently of the event's per-mirror admission.
            SafSyncEngine::class.java.getDeclaredMethod(
                "writeLocalToSaf", File::class.java, Uri::class.java, File::class.java,
            ).apply { isAccessible = true }.invoke(second, file, otherDocument, mirror)

            assertEquals(2, opens, "both document writes must reach the provider")
            assertEquals(
                listOf(file.absolutePath), engine.uploadsInFlight().toList(),
                "the writer that landed first cleared the record the other one is inside",
            )
        } finally {
            released.set(true)
            drain.join(5_000)
        }
        assertFalse(drain.isAlive)
        assertFalse(journal.isFile, "the record outlived the last writer")
    }

    /** A reopen cannot consume the journal or replace a mirror during a live write. */
    @Test
    fun `a busy reopen leaves a live writer's claim alone`() {
        deviceHolding("notes.txt")
        val file = File(mirror, "notes.txt").apply { writeText("the whole edit") }
        val device = ByteArrayOutputStream()
        val released = AtomicBoolean(false)
        val firstWriteStarted = CountDownLatch(1)
        var opens = 0
        every { resolver.openInputStream(any()) } answers {
            ByteArrayInputStream(device.toByteArray())
        }
        every { resolver.openOutputStream(any(), any()) } answers {
            if (opens++ == 0) {
                object : OutputStream() {
                    override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
                    override fun write(b: ByteArray, off: Int, len: Int) {
                        firstWriteStarted.countDown()
                        while (!released.get()) {
                            try { Thread.sleep(10) } catch (_: InterruptedException) { }
                        }
                        device.write(b, off, len)
                    }
                }
            } else {
                ByteArrayOutputStream()
            }
        }

        engine.handleMirrorEvent(FileObserver.MODIFY, file, mirror, treeUri)
        val drain = thread(isDaemon = true) { engine.runWriteBackLoop { false } }
        try {
            assertTrue(
                firstWriteStarted.await(5, TimeUnit.SECONDS),
                "setup failed: the write never reached the provider",
            )

            val started = System.nanoTime()
            assertThrows(IOException::class.java) {
                runBlocking { engine.initialSync(treeUri, mirror) { _, _ -> } }
            }
            assertTrue(
                (System.nanoTime() - started) / 1_000_000 < 1_000,
                "the busy reopen waited for the provider",
            )
            assertEquals(
                listOf(file.absolutePath), engine.uploadsInFlight().toList(),
                "the sync consumed a record belonging to a writer that is still streaming",
            )
            assertEquals(1, opens, "the reopen started another upload")
            assertEquals("the whole edit", file.readText())
        } finally {
            released.set(true)
            drain.join(5_000)
        }

        assertFalse(drain.isAlive, "the writer did not finish")
        assertFalse(journal.isFile, "the finished writer did not release its claim")
        assertEquals("the whole edit", device.toString(Charsets.UTF_8.name()))

        runBlocking { engine.initialSync(treeUri, mirror) { _, _ -> } }
        assertEquals("the whole edit", file.readText())
        assertEquals("the whole edit", device.toString(Charsets.UTF_8.name()))
    }
}
