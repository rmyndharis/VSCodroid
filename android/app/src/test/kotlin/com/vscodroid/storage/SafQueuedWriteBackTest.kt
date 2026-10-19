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
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.nio.file.Files

/**
 * What the device folder ends up holding when changes are queued behind one another.
 *
 * The watcher resolves each event's document and parent when the event arrives, on the
 * observer thread, and the write-back thread acts on those answers up to a poll later.
 * Anything queued ahead of an event that has not run yet, a rename or the create of a
 * folder, has not changed the provider by then, so the answers can be missing for a path
 * the device is about to hold.
 *
 * Driven through `handleMirrorEvent` and `runWriteBackLoop` against a device folder that
 * really changes: creates, renames, deletes and writes act on a path-keyed model, so a
 * case asserts on the folder it was left with and not on which calls were made.
 */
class SafQueuedWriteBackTest {

    @TempDir
    lateinit var mirror: File

    @TempDir
    lateinit var filesDir: File

    private lateinit var resolver: ContentResolver
    private lateinit var engine: SafSyncEngine
    private lateinit var treeUri: Uri

    /** The device folder: path relative to the tree to its bytes, null for a directory. */
    private val device = sortedMapOf<String, ByteArray?>()

    /** Every document opened for writing, by path, in the order it happened. */
    private val writes = mutableListOf<String>()

    /** Files announced as existing only inside the app, by mirror-relative path. */
    private val lost = mutableListOf<String>()

    /** Deletes the device folder was not asked to perform, by mirror-relative path. */
    private val refused = mutableListOf<String>()

    private val isDirFlag = 0x40000000

    private fun docId(path: String) = if (path.isEmpty()) "root" else "doc:$path"

    private fun pathOf(docId: String) = if (docId == "root") "" else docId.removePrefix("doc:")

    private fun parentOf(path: String) = path.substringBeforeLast('/', "")

    private fun docUri(docId: String): Uri =
        mockk<Uri>(relaxed = true).also { every { it.toString() } returns "doc-uri:$docId" }

    private fun pathOf(uri: Uri) = pathOf(uri.toString().removePrefix("doc-uri:"))

    private fun moveSubtree(from: String, to: String) {
        fun moved(path: String) = when {
            path == from -> to
            path.startsWith("$from/") -> to + path.removePrefix(from)
            else -> path
        }
        val before = device.toMap()
        device.clear()
        before.forEach { (path, bytes) -> device[moved(path)] = bytes }
    }

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
        every { DocumentsContract.buildChildDocumentsUriUsingTree(any(), any()) } answers {
            mockk<Uri>(relaxed = true).also { every { it.toString() } returns "children:${secondArg<String>()}" }
        }
        every { DocumentsContract.buildDocumentUriUsingTree(any(), any()) } answers {
            docUri(secondArg())
        }
        every { DocumentsContract.getDocumentId(any()) } answers {
            firstArg<Uri>().toString().removePrefix("doc-uri:")
        }
        every { DocumentsContract.createDocument(any(), any(), any(), any()) } answers {
            val parent = pathOf(secondArg<Uri>())
            val name = arg<String>(3)
            val path = if (parent.isEmpty()) name else "$parent/$name"
            val directory = thirdArg<String>() == DocumentsContract.Document.MIME_TYPE_DIR
            device[path] = if (directory) null else ByteArray(0)
            docUri(docId(path))
        }
        every { DocumentsContract.renameDocument(any(), any(), any()) } answers {
            val from = pathOf(secondArg<Uri>())
            val parent = parentOf(from)
            val to = if (parent.isEmpty()) thirdArg<String>() else "$parent/${thirdArg<String>()}"
            moveSubtree(from, to)
            docUri(docId(to))
        }
        every { DocumentsContract.deleteDocument(any(), any()) } answers {
            val path = pathOf(secondArg<Uri>())
            device.keys.removeAll { it == path || it.startsWith("$path/") }
            true
        }

        resolver = mockk(relaxed = true)
        every { resolver.query(any(), any(), any(), any(), any()) } answers {
            val target = firstArg<Uri>().toString()
            val rows = if (target.startsWith("children:")) {
                val parent = pathOf(target.removePrefix("children:"))
                device.keys.filter { parentOf(it) == parent }
            } else {
                emptyList()
            }
            val cursor = mockk<Cursor>(relaxed = true)
            var row = -1
            every { cursor.moveToNext() } answers { ++row < rows.size }
            every { cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID) } returns 0
            every { cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME) } returns 1
            every { cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE) } returns 2
            every { cursor.getString(0) } answers { docId(rows[row]) }
            every { cursor.getString(1) } answers { rows[row].substringAfterLast('/') }
            every { cursor.getString(2) } answers {
                if (device[rows[row]] == null) {
                    DocumentsContract.Document.MIME_TYPE_DIR
                } else {
                    "application/octet-stream"
                }
            }
            every { cursor.extras } returns null
            cursor
        }
        every { resolver.openOutputStream(any(), any()) } answers {
            val path = pathOf(firstArg<Uri>())
            writes += path
            object : ByteArrayOutputStream() {
                override fun close() {
                    super.close()
                    device[path] = toByteArray()
                }
            } as OutputStream
        }

        val context = mockk<Context>(relaxed = true)
        every { context.contentResolver } returns resolver
        every { context.filesDir } returns filesDir

        engine = SafSyncEngine(context)
        engine.onWriteBackFailed = { lost += it.relativeTo(mirror).path }
        engine.onDeleteRefused = { refused += it.relativeTo(mirror).path }
        treeUri = mockk(relaxed = true)
        engine.resetCache(treeUri)
        // What a real sync leaves behind and a bare reset does not: the root under the
        // empty path. Without it a document the engine creates is never remembered, and
        // a case about work done twice would pass with nothing to compare.
        SafSyncEngine::class.java
            .getDeclaredMethod("cacheDocId", Uri::class.java, String::class.java, String::class.java)
            .apply { isAccessible = true }
            .invoke(engine, treeUri, "", "root")
    }

    @AfterEach
    fun tearDown() {
        File(mirror.path + SafSyncEngine.SYNCED_RECORD_SUFFIX).delete()
        unmockkAll()
    }

    /** Puts a file or, with null text, a directory on the device folder. */
    private fun onDevice(path: String, text: String? = null) {
        device[path] = text?.toByteArray()
    }

    /** Puts a file in the mirror, directories included. */
    private fun inMirror(path: String, text: String) {
        File(mirror, path).apply { parentFile.mkdirs() }.writeText(text)
    }

    /** One event, without the write-back thread's pass over it. */
    private fun observe(event: Int, entry: String) {
        engine.handleMirrorEvent(event, File(mirror, entry), mirror, treeUri)
    }

    /** The write-back thread's pass over whatever the events queued. */
    private fun drain() = engine.runWriteBackLoop { false }

    private fun text(path: String) = device[path]?.toString(Charsets.UTF_8)

    @Test
    fun `files written into a new folder before its create runs are uploaded once`() {
        // `mkdir lib` and then a burst of files, as a clone or an install makes. The
        // folder's create uploads whatever the mirror holds when it runs, so the files'
        // own jobs, queued with no parent because the folder was not on the device yet,
        // have nothing left to do. A second write of each would double the provider
        // traffic of the most common heavy workload there is.
        observe(FileObserver.CREATE or isDirFlag, "lib")
        inMirror("lib/a", "a1")
        observe(FileObserver.CREATE, "lib/a")
        observe(FileObserver.MODIFY, "lib/a")
        inMirror("lib/b", "b")
        observe(FileObserver.CREATE, "lib/b")

        drain()

        assertEquals(mapOf("lib/a" to 1, "lib/b" to 1), writes.groupingBy { it }.eachCount())
        assertEquals("a1", text("lib/a"))
        assertEquals("b", text("lib/b"))
        assertEquals(emptyList<String>(), lost)
    }

    @Test
    fun `a file moved into a folder renamed a moment before is created in the renamed folder`() {
        onDevice("old_dir")
        onDevice("old_dir/existing.txt", "old")
        onDevice("notes.txt", "notes")

        // mv old_dir lib && mv notes.txt lib/notes.txt, inside one poll. The rename has
        // not run when the second event resolves lib/, so the device has no such folder.
        observe(FileObserver.MOVED_FROM or isDirFlag, "old_dir")
        observe(FileObserver.MOVED_TO or isDirFlag, "lib")
        observe(FileObserver.MOVED_FROM, "notes.txt")
        inMirror("lib/existing.txt", "old")
        inMirror("lib/notes.txt", "notes")
        observe(FileObserver.MOVED_TO, "lib/notes.txt")

        drain()

        assertEquals(setOf("lib", "lib/existing.txt", "lib/notes.txt"), device.keys)
        assertEquals("notes", text("lib/notes.txt"))
        assertEquals(emptyList<String>(), lost)
    }

    @Test
    fun `an edit inside a folder renamed a moment before reaches the renamed folder`() {
        onDevice("old_dir")
        onDevice("old_dir/existing.txt", "old")

        observe(FileObserver.MOVED_FROM or isDirFlag, "old_dir")
        observe(FileObserver.MOVED_TO or isDirFlag, "lib")
        inMirror("lib/existing.txt", "edited")
        observe(FileObserver.MODIFY, "lib/existing.txt")

        drain()

        assertEquals(setOf("lib", "lib/existing.txt"), device.keys)
        assertEquals("edited", text("lib/existing.txt"))
        assertEquals(emptyList<String>(), lost)
    }

    @Test
    fun `a file moved into a renamed folder after the rename ran is created too`() {
        onDevice("old_dir")
        onDevice("old_dir/existing.txt", "old")
        onDevice("notes.txt", "notes")

        observe(FileObserver.MOVED_FROM or isDirFlag, "old_dir")
        observe(FileObserver.MOVED_TO or isDirFlag, "lib")
        inMirror("lib/existing.txt", "old")
        drain()
        observe(FileObserver.MOVED_FROM, "notes.txt")
        inMirror("lib/notes.txt", "notes")
        observe(FileObserver.MOVED_TO, "lib/notes.txt")
        drain()

        assertEquals(setOf("lib", "lib/existing.txt", "lib/notes.txt"), device.keys)
        assertEquals("notes", text("lib/notes.txt"))
        assertEquals(emptyList<String>(), lost)
    }

    @Test
    fun `a drain that outlives its folder reports nothing it cannot place`() {
        // The folder was switched while this one was still draining. The cache then speaks
        // for another tree, so whether the file was carried by its own folder's create
        // cannot be told, and saying it never reached the device would often be wrong.
        inMirror("ghost/a.txt", "a")
        observe(FileObserver.CREATE, "ghost/a.txt")
        engine.resetCache(mockk(relaxed = true))

        drain()

        assertEquals(emptyList<String>(), lost)
        assertEquals(emptySet<String>(), device.keys)
    }

    @Test
    fun `a link made inside a new folder is not reported`() {
        // The folder's create does not upload a link and neither does the job queued for
        // the link itself, so there is nothing that failed to reach the device.
        observe(FileObserver.CREATE or isDirFlag, "lib")
        inMirror("lib/real.txt", "r")
        Files.createSymbolicLink(File(mirror, "lib/link").toPath(), File(mirror, "lib/real.txt").toPath())
        observe(FileObserver.CREATE, "lib/real.txt")
        observe(FileObserver.CREATE, "lib/link")

        drain()

        assertEquals(setOf("lib", "lib/real.txt"), device.keys)
        assertEquals(emptyList<String>(), lost)
    }

    @Test
    fun `a file whose folder never reaches the device is reported`() {
        // Nothing queued makes ghost/ and the device has none, so there is no parent to
        // create the file in at the drain either. The file exists only in the mirror, and
        // the editor shows it as saved.
        inMirror("ghost/a.txt", "a")
        observe(FileObserver.CREATE, "ghost/a.txt")

        drain()

        assertEquals(emptySet<String>(), device.keys)
        assertEquals(listOf("ghost/a.txt"), lost)
    }
}
