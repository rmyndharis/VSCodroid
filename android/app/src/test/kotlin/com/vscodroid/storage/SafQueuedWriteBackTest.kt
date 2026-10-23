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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileNotFoundException
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

    /** Every document a write was tried into, whether or not the provider took it. */
    private val attempts = mutableListOf<String>()

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
            val wanted = if (parent.isEmpty()) thirdArg<String>() else "$parent/${thirdArg<String>()}"
            // A name the folder already holds is answered with an invented one, as providers
            // that do not refuse it do.
            var to = wanted
            var n = 1
            while (to in device) to = "$wanted (${n++})"
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
                listOf(pathOf(firstArg<Uri>())).filter { it in device }
            }
            val cursor = mockk<Cursor>(relaxed = true)
            var row = -1
            every { cursor.moveToNext() } answers { ++row < rows.size }
            every { cursor.moveToFirst() } answers { row = 0; rows.isNotEmpty() }
            every { cursor.getColumnIndex(any()) } answers {
                when (firstArg<String>()) {
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID -> 0
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME -> 1
                    DocumentsContract.Document.COLUMN_MIME_TYPE -> 2
                    DocumentsContract.Document.COLUMN_SIZE -> 3
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED -> 4
                    else -> -1
                }
            }
            every { cursor.getLong(3) } answers { device[rows[row]]?.size?.toLong() ?: 0L }
            every { cursor.getLong(4) } returns 1_700_000_000_000L
            every { cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID) } returns 0
            every { cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME) } returns 1
            every { cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE) } returns 2
            every { cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE) } returns 3
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
        every { resolver.openInputStream(any()) } answers {
            ByteArrayInputStream(checkNotNull(device[pathOf(firstArg<Uri>())]))
        }
        every { resolver.openOutputStream(any(), any()) } answers {
            val path = pathOf(firstArg<Uri>())
            attempts += path
            // A directory cannot be written and a deleted document is gone, which is how
            // a provider answers a write through a URI that went stale.
            if (device[path] == null) throw FileNotFoundException(path)
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
        File(mirror, path).apply { checkNotNull(parentFile).mkdirs() }.writeText(text)
    }

    /** One event, without the write-back thread's pass over it. */
    private fun observe(event: Int, entry: String) {
        engine.handleMirrorEvent(event, File(mirror, entry), mirror, treeUri)
    }

    /** The write-back thread's pass over whatever the events queued. */
    private fun drain() = engine.runWriteBackLoop { false }

    private fun text(path: String) = device[path]?.toString(Charsets.UTF_8)

    @Test
    fun `a queued save preserves a device edit reported by its document query`() {
        onDevice("notes.txt", "baseline")
        runBlocking { engine.initialSync(treeUri, mirror) { _, _ -> } }
        onDevice("notes.txt", "foreign device edit")
        inMirror("notes.txt", "local edit")
        observe(FileObserver.MODIFY, "notes.txt")

        drain()

        assertEquals("local edit", text("notes.txt"))
        val copies = mirror.listFiles()!!.filter { it.name.startsWith("notes.txt.device-") }
        assertEquals(1, copies.size, "a document query failed open over a foreign device edit")
        assertEquals("foreign device edit", copies.single().readText())
    }

    @Test
    fun `a persistent temporary-looking filename reaches the device live`() {
        inMirror("notes.tmp", "saved user file")
        observe(FileObserver.CREATE, "notes.tmp")
        drain()
        assertEquals("saved user file", text("notes.tmp"))
        assertEquals(emptyList<String>(), lost)
    }

    @Test
    fun `an atomic save from a temporary name delivers the final file`() {
        inMirror("notes.tmp", "completed save")
        observe(FileObserver.CREATE, "notes.tmp")
        assertTrue(File(mirror, "notes.tmp").renameTo(File(mirror, "notes.txt")))
        observe(FileObserver.MOVED_FROM, "notes.tmp")
        observe(FileObserver.MOVED_TO, "notes.txt")
        drain()
        assertEquals(setOf("notes.txt"), device.keys)
        assertEquals("completed save", text("notes.txt"))
        assertEquals(emptyList<String>(), lost)
    }

    @Test
    fun `a queued edit follows its folder rename before the drain`() {
        onDevice("old_dir")
        onDevice("old_dir/existing.txt", "old")
        inMirror("old_dir/existing.txt", "edited before rename")
        observe(FileObserver.MODIFY, "old_dir/existing.txt")
        val held = File(filesDir, "renaming")
        assertTrue(File(mirror, "old_dir").renameTo(held))
        observe(FileObserver.MOVED_FROM or isDirFlag, "old_dir")
        observe(FileObserver.MOVED_TO or isDirFlag, "lib")
        assertTrue(held.renameTo(File(mirror, "lib")))

        drain()

        assertEquals(setOf("lib", "lib/existing.txt"), device.keys)
        assertEquals("edited before rename", text("lib/existing.txt"))
        assertEquals(emptyList<String>(), lost)
    }

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
    fun `a folder made inside a folder renamed a moment before is uploaded once with its files`() {
        onDevice("old_dir")

        // mv old_dir lib && mkdir lib/sub && echo x > lib/sub/f, inside one poll. lib/ is not
        // on the device when the events resolve, so the create of sub and the file's own job
        // both queue with no parent. The create of sub runs after the rename and uploads the
        // file with it, which leaves the file's job nothing to do.
        observe(FileObserver.MOVED_FROM or isDirFlag, "old_dir")
        observe(FileObserver.MOVED_TO or isDirFlag, "lib")
        observe(FileObserver.CREATE or isDirFlag, "lib/sub")
        inMirror("lib/sub/f", "x")
        observe(FileObserver.CREATE, "lib/sub/f")
        observe(FileObserver.MODIFY, "lib/sub/f")

        drain()

        assertEquals(setOf("lib", "lib/sub", "lib/sub/f"), device.keys)
        assertEquals(mapOf("lib/sub/f" to 1), writes.groupingBy { it }.eachCount())
        assertEquals("x", text("lib/sub/f"))
        assertEquals(emptyList<String>(), lost)
    }

    @Test
    fun `a file made where the device holds a folder is announced and never written into the folder`() {
        onDevice("foo")
        onDevice("foo/kid.txt", "k")
        inMirror("foo", "my notes")

        observe(FileObserver.CREATE, "foo")
        drain()

        assertEquals(listOf("foo"), lost, "the file that cannot be sent was not announced")
        assertEquals(emptyList<String>(), attempts, "a write was tried into a folder")
        assertEquals(setOf("foo", "foo/kid.txt"), device.keys)
    }

    @Test
    fun `a folder made where the device holds a file is announced and nothing is made inside the file`() {
        onDevice("foo", "device file")

        // Observed before the folder is filled in, as the first case in this class does: a
        // folder that already holds directories would register real watches.
        observe(FileObserver.CREATE or isDirFlag, "foo")
        File(mirror, "foo").mkdirs()
        inMirror("foo/a.txt", "mine")
        drain()

        assertEquals(listOf("foo"), lost, "the folder that cannot be sent was not announced")
        assertEquals(setOf("foo"), device.keys, "something was made inside a file")
        assertEquals("device file", text("foo"))
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

    @Test
    fun `a folder rename still runs when the renamed folder is deleted before the drain`() {
        onDevice("dirA")
        onDevice("dirA/x.txt", "x")

        // mv dirA dirB && rm -r dirB, inside one poll.
        observe(FileObserver.MOVED_FROM or isDirFlag, "dirA")
        observe(FileObserver.MOVED_TO or isDirFlag, "dirB")
        Thread.sleep(3)
        observe(FileObserver.DELETE, "dirB/x.txt")
        observe(FileObserver.DELETE or isDirFlag, "dirB")

        drain()

        verify(exactly = 1) { DocumentsContract.renameDocument(any(), any(), "dirB") }
        assertEquals(setOf("dirB", "dirB/x.txt"), device.keys)
        // The device cannot be asked to delete what the event could not name, and the
        // user is told that, rather than the old folder quietly staying under its old name.
        assertEquals(setOf("dirB/x.txt", "dirB"), refused.toSet())
    }

    @Test
    fun `a folder replaced by a file before the drain is deleted and the file created`() {
        onDevice("foo")

        // rmdir foo && echo hi > foo
        observe(FileObserver.DELETE or isDirFlag, "foo")
        Thread.sleep(3)
        inMirror("foo", "hi")
        observe(FileObserver.CREATE, "foo")

        drain()

        assertEquals(setOf("foo"), device.keys)
        assertEquals("hi", text("foo"))
        assertEquals(emptyList<String>(), lost + refused)
    }

    @Test
    fun `a file replaced by a folder before the drain is deleted and the folder created`() {
        onDevice("foo", "hi")

        // rm foo && mkdir foo. The directory event arrives while the directory is not yet on
        // disk, as a JVM test can only deliver one, and appears before the drain.
        observe(FileObserver.DELETE, "foo")
        Thread.sleep(3)
        observe(FileObserver.CREATE or isDirFlag, "foo")
        File(mirror, "foo").mkdirs()

        drain()

        assertEquals(setOf("foo"), device.keys)
        assertEquals(null, device["foo"], "foo should be a folder on the device")
        assertEquals(emptyList<String>(), lost + refused)
    }

    @Test
    fun `a file removed and written again before the drain keeps its document`() {
        onDevice("f", "old")

        // What a checkout does: unlink, create, write. The save at the end holds the
        // document the device had when the event arrived, so a delete that ran first would
        // leave it writing through a URI that no longer names anything.
        observe(FileObserver.DELETE, "f")
        Thread.sleep(3)
        inMirror("f", "new")
        observe(FileObserver.CREATE, "f")
        Thread.sleep(3)
        observe(FileObserver.MODIFY, "f")

        drain()

        verify(exactly = 0) { DocumentsContract.deleteDocument(any(), any()) }
        assertEquals("new", text("f"))
        assertEquals(listOf("f"), writes)
        assertEquals(emptyList<String>(), lost + refused)
    }

    @Test
    fun `a later job drops an earlier save, a rename only for a rename onto its name, and a delete only for a write of its kind`() {
        fun job(type: SyncType, isDirectory: Boolean = false) =
            SyncJob(type, "/m/p", null, null, null, 0L, isDirectory = isDirectory)

        for (later in SyncType.values()) {
            assertTrue(SafSyncEngine.supersededByLaterJob(job(SyncType.CREATE), job(later)), "create before $later")
            assertTrue(SafSyncEngine.supersededByLaterJob(job(SyncType.MODIFY), job(later)), "modify before $later")
            assertEquals(
                later == SyncType.RENAME,
                SafSyncEngine.supersededByLaterJob(job(SyncType.RENAME, true), job(later, true)),
                "rename before $later",
            )
        }

        assertTrue(SafSyncEngine.supersededByLaterJob(job(SyncType.DELETE), job(SyncType.DELETE)))
        assertTrue(SafSyncEngine.supersededByLaterJob(job(SyncType.DELETE), job(SyncType.CREATE)))
        assertTrue(SafSyncEngine.supersededByLaterJob(job(SyncType.DELETE), job(SyncType.MODIFY)))
        assertTrue(SafSyncEngine.supersededByLaterJob(job(SyncType.DELETE, true), job(SyncType.CREATE, true)))
        assertFalse(SafSyncEngine.supersededByLaterJob(job(SyncType.DELETE), job(SyncType.CREATE, true)))
        assertFalse(SafSyncEngine.supersededByLaterJob(job(SyncType.DELETE, true), job(SyncType.CREATE)))
        assertFalse(SafSyncEngine.supersededByLaterJob(job(SyncType.DELETE), job(SyncType.MODIFY, true)))
        assertFalse(SafSyncEngine.supersededByLaterJob(job(SyncType.DELETE, true), job(SyncType.RENAME, true)))
    }

    @Test
    fun `two renames onto one name leave the later one in place and invent nothing`() {
        onDevice("a")
        onDevice("a/fa.txt", "fa")
        onDevice("c")
        onDevice("c/fc.txt", "fc")

        // mv a b && rm -r b && mv c b, inside one poll. The device holds no b at either
        // pairing, so both renames are offered for the same name, and only one can land.
        observe(FileObserver.MOVED_FROM or isDirFlag, "a")
        observe(FileObserver.MOVED_TO or isDirFlag, "b")
        Thread.sleep(3)
        observe(FileObserver.DELETE or isDirFlag, "b")
        Thread.sleep(3)
        observe(FileObserver.MOVED_FROM or isDirFlag, "c")
        observe(FileObserver.MOVED_TO or isDirFlag, "b")

        drain()

        // The first rename gave way to the second, so `a` is the one thing the device keeps
        // of what the user removed. It is not deleted: nothing in the queue can say the
        // folder that left `b` was `a` and not something renamed away, and a delete is the
        // one call that cannot be taken back. The next open brings `a` into the editor
        // again, where it can be removed once more.
        assertEquals(setOf("a", "a/fa.txt", "b", "b/fc.txt"), device.keys)
        assertEquals(emptyList<String>(), lost + refused)
    }
}
