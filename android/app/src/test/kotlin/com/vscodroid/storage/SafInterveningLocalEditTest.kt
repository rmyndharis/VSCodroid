package com.vscodroid.storage

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import com.vscodroid.util.Logger
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class SafInterveningLocalEditTest {
    @TempDir lateinit var mirror: File
    @TempDir lateinit var filesDir: File
    @TempDir lateinit var provider: File
    private lateinit var resolver: ContentResolver
    private lateinit var context: Context
    private lateinit var tree: Uri
    private val record get() = File(mirror.path + SafSyncEngine.SYNCED_RECORD_SUFFIX)
    private val local get() = File(mirror, "notes.txt")
    private val device get() = File(provider, "notes.txt")
    private val deviceTime = 1_700_000_000_000L
    private val localTime = 1_600_000_000_000L

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
        every { DocumentsContract.buildChildDocumentsUriUsingTree(any(), any()) } returns named("children")
        every { DocumentsContract.buildDocumentUriUsingTree(any(), any()) } answers { named(secondArg()) }
        every { DocumentsContract.getDocumentId(any()) } answers { firstArg<Uri>().toString() }
        every { DocumentsContract.createDocument(any(), any(), any(), any()) } answers {
            named(arg<String>(3)).also { File(provider, arg<String>(3)).createNewFile() }
        }
        resolver = mockk(relaxed = true)
        every { resolver.query(any(), any(), any(), any(), any()) } answers {
            if (firstArg<Uri>().toString() == "children") cursor(provider.listFiles()!!.sortedBy { it.name })
            else cursor(listOf(File(provider, firstArg<Uri>().toString())))
        }
        every { resolver.openInputStream(any()) } answers { File(provider, firstArg<Uri>().toString()).inputStream() }
        every { resolver.openOutputStream(any(), any()) } answers { File(provider, firstArg<Uri>().toString()).outputStream() }
        context = mockk(relaxed = true)
        every { context.filesDir } returns filesDir
        every { context.contentResolver } returns resolver
        tree = named("tree")
        device.writeText("DEVICE")
        assertTrue(device.setLastModified(deviceTime))
    }

    @AfterEach
    fun tearDown() {
        record.delete()
        unmockkAll()
    }

    private fun named(value: String) = mockk<Uri>(relaxed = true).also { every { it.toString() } returns value }

    private fun cursor(files: List<File>): Cursor {
        var row = -1
        return mockk(relaxed = true) {
            every { moveToNext() } answers { ++row < files.size }
            every { getColumnIndexOrThrow(any()) } answers {
                when (firstArg<String>()) {
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID -> 0
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME -> 1
                    DocumentsContract.Document.COLUMN_MIME_TYPE -> 2
                    else -> 3
                }
            }
            every { getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED) } returns 4
            every { isNull(any<Int>()) } returns false
            every { getString(0) } answers { files[row].name }
            every { getString(1) } answers { files[row].name }
            every { getString(2) } returns "text/plain"
            every { getLong(3) } answers { files[row].length() }
            every { getLong(4) } answers { files[row].lastModified() }
        }
    }

    private fun existingMirror(recordKind: String = "absent") {
        local.writeText("BEFORE")
        assertTrue(local.setLastModified(localTime))
        when (recordKind) {
            "recognized" -> record.writeText(SafSyncEngine.RECORD_HEADER + "\nnotes.txt\t$localTime\t6\n")
            "unreadable" -> record.writeText("unsupported record\nnotes.txt\t$localTime\t6\n")
        }
    }

    private fun sync(engine: SafSyncEngine = SafSyncEngine(context)) = runBlocking {
        engine.initialSync(tree, mirror) { _, _ -> }
    }

    private fun duringFetch(failRead: Boolean = false, fetchNumber: Int = 1, edit: () -> Unit) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var fetches = 0
        every { resolver.openInputStream(any()) } answers {
            val bytes = File(provider, firstArg<Uri>().toString()).readBytes()
            if (++fetches != fetchNumber) ByteArrayInputStream(bytes) else {
                object : ByteArrayInputStream(bytes) {
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        entered.countDown()
                        check(release.await(5, TimeUnit.SECONDS)) { "The test never released the provider read" }
                        if (failRead) throw IOException("Provider read failed")
                        return super.read(buffer, offset, length)
                    }
                }
            }
        }
        val failure = AtomicReference<Throwable>()
        val worker = Thread { try { sync() } catch (error: Throwable) { failure.set(error) } }.apply { start() }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS), "The fetch never reached its read barrier")
            edit()
        } finally {
            release.countDown()
            worker.join(5_000)
        }
        assertFalse(worker.isAlive, "The fetch did not finish")
        failure.get()?.let { throw AssertionError("The sync failed", it) }
    }

    private fun assertPreservedAndReopened() {
        assertEquals("DEVICE", local.readText())
        val kept = mirror.listFiles()!!.filter { it.name.startsWith("notes.txt.local-") }
        assertEquals(1, kept.size, "The intervening edit needs one preserved copy")
        assertEquals("EDITED", kept.single().readText())
        assertEquals("DEVICE", device.readText())
        sync()
        assertEquals("EDITED", File(provider, kept.single().name).readText())
        assertEquals("DEVICE", local.readText())
        assertEquals("EDITED", kept.single().readText())
        assertEquals(2, mirror.listFiles()!!.size, "Reopening created another conflict copy")
        assertTrue(record.readLines().any { it.startsWith("notes.txt\t$deviceTime\t6\t") },
            "The record must describe the landed device bytes")
    }

    @Test
    fun `a file created during the fetch is preserved before first import`() {
        duringFetch { local.writeText("EDITED") }
        assertPreservedAndReopened()
    }

    @ParameterizedTest
    @ValueSource(strings = ["absent", "recognized", "unreadable"])
    fun `same length edits with restored timestamps survive the fetch`(recordKind: String) {
        existingMirror(recordKind)
        duringFetch {
            local.writeText("EDITED")
            assertTrue(local.setLastModified(localTime))
        }
        assertPreservedAndReopened()
    }

    @Test
    fun `a replacement inode with matching metadata is preserved`() {
        existingMirror("recognized")
        duringFetch {
            val replacement = File(filesDir, "replacement").apply {
                writeText("EDITED")
                assertTrue(setLastModified(localTime))
            }
            Files.move(replacement.toPath(), local.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
        assertPreservedAndReopened()
    }

    @Test
    fun `an intervening edit equal to the fetched document does not create a duplicate`() {
        existingMirror()
        duringFetch { local.writeText("DEVICE") }
        assertEquals(listOf("notes.txt"), mirror.list()!!.toList())
        assertEquals("DEVICE", local.readText())
    }

    @Test
    fun `a failed read leaves the intervening edit and cleans staging`() {
        existingMirror()
        duringFetch(failRead = true) { local.writeText("EDITED") }
        assertEquals("EDITED", local.readText())
        assertEquals("DEVICE", device.readText())
        assertEquals(emptyList<String>(), File(filesDir, "saf-inbound").list()!!.toList())
    }

    @Test
    fun `a failed fetch does not vouch for an intervening same metadata edit on reopen`() {
        existingMirror("recognized")
        duringFetch(failRead = true) {
            local.writeText("EDITED")
            assertTrue(local.setLastModified(localTime))
        }
        assertEquals("EDITED", local.readText())
        sync()
        assertPreservedAndReopened()
    }

    @Test
    fun `a transient fetch failure carries the unchanged previous baseline`() {
        existingMirror("recognized")
        val baseline = SafSyncEngine.recordLine(
            "notes.txt\t$localTime\t6", MessageDigest.getInstance("SHA-256").digest(local.readBytes()),
        )
        record.writeText(SafSyncEngine.RECORD_HEADER + "\n" + baseline)
        duringFetch(failRead = true) { }
        assertTrue(record.readLines().contains(baseline))
        sync()
        assertEquals("DEVICE", local.readText())
        assertEquals(listOf("notes.txt"), mirror.list()!!.toList())
    }

    @Test
    fun `an unproved mirror is retained while the device copy is refreshed`() {
        existingMirror()
        sync()
        assertEquals("DEVICE", local.readText())
        val retained = mirror.listFiles()!!.single { it.name.startsWith("notes.txt.local-") }
        assertEquals("BEFORE", retained.readText())
    }

    @Test
    fun `a missing document still imports normally`() {
        sync()
        assertEquals(listOf("notes.txt"), mirror.list()!!.toList())
        assertEquals("DEVICE", local.readText())
    }

    @Test
    fun `an unreadable destination snapshot defers without opening the provider`() {
        existingMirror("recognized")
        val permissions = Files.getPosixFilePermissions(local.toPath())
        try {
            Files.setPosixFilePermissions(local.toPath(), emptySet())
            assumeFalse(Files.isReadable(local.toPath()), "This account bypasses file read permissions")
            every { resolver.openInputStream(any()) } answers { throw AssertionError("The provider was opened") }
            sync()
        } finally {
            Files.setPosixFilePermissions(local.toPath(), permissions)
        }
        assertEquals("BEFORE", local.readText())
        assertEquals("DEVICE", device.readText())
        assertFalse(record.readLines().any { it.startsWith("notes.txt\t") },
            "An unavailable snapshot cannot vouch for the previous baseline")
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `an intervening device backup edit defers the outgoing replacement`(existed: Boolean) {
        existingMirror("recognized")
        local.writeText("COMPLETE LOCAL EDIT")
        val journal = File(filesDir, SafSyncEngine.UPLOADS_IN_FLIGHT_FILE)
        journal.writeText(local.absolutePath)
        val preserved = File(mirror, "notes.txt.device-$deviceTime")
        if (existed) preserved.writeText("PREVIOUS BACKUP")
        val destination = File(mirror, "notes.txt.device-${deviceTime + if (existed) 1 else 0}")
        duringFetch(fetchNumber = 2) { destination.writeText("UNIQUE BACKUP EDIT") }
        assertEquals("UNIQUE BACKUP EDIT", destination.readText())
        if (existed) assertEquals("PREVIOUS BACKUP", preserved.readText())
        assertEquals("COMPLETE LOCAL EDIT", local.readText())
        assertEquals("DEVICE", device.readText(), "The outgoing replacement was not deferred")
        assertEquals(listOf(local.absolutePath), SafSyncEngine(context).uploadsInFlight().toList())
        assertEquals(emptyList<String>(), File(filesDir, "saf-inbound").list()!!.toList())
    }

    @Test
    fun `a recreated parent at the same pathname refuses the completed copy`() {
        existingMirror()
        val moved = File(mirror.parentFile, mirror.name + "-moved")
        try {
            duringFetch {
                Files.move(mirror.toPath(), moved.toPath())
                assertTrue(mirror.mkdir())
                local.writeText("EDITED")
            }
            assertEquals("EDITED", local.readText(), "The fetch replaced a file in a different directory")
            assertEquals("BEFORE", File(moved, "notes.txt").readText())
            assertEquals(emptyList<String>(), File(filesDir, "saf-inbound").list()!!.toList())
        } finally {
            moved.deleteRecursively()
        }
    }
}
