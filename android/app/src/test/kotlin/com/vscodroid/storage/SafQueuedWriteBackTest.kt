package com.vscodroid.storage

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.FileObserver
import android.os.SystemClock
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
import org.junit.jupiter.api.Assertions.assertNull
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

    /** Run before every answer the provider gives, to put the write-back thread's turn inside an event. */
    private var onProviderCall: (() -> Unit)? = null

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
            onProviderCall?.invoke()
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
            onProviderCall?.invoke()
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

    /** Completes the real upload path before exercising deletion of these bytes. */
    private fun deliverKnownFile(path: String) {
        val file = File(mirror, path)
        file.parentFile!!.mkdirs()
        file.writeBytes(checkNotNull(device[path]))
        writeSafFile(engine, file, docUri(docId(path)), mirror)
        assertEquals(file.readText(), text(path), "the provider did not receive the baseline")
        assertTrue(writes.contains(path), "the baseline upload did not reach the provider")
        writes.clear()
        attempts.clear()
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

    /**
     * The write-back thread can take a save while its folder is already renamed away and the
     * other half of the rename has not been seen yet: the file is gone from its path, so the
     * job is dropped, and the rename that claims the folder no longer finds it among the
     * queued saves. The renamed document then held the older bytes until another save or the
     * next open.
     */
    @Test
    fun `a save taken after its folder moved away reaches the renamed folder`() {
        onDevice("old_dir")
        onDevice("old_dir/existing.txt", "old")
        inMirror("old_dir/existing.txt", "edited before rename")
        observe(FileObserver.MODIFY, "old_dir/existing.txt")
        val held = File(filesDir, "renaming")
        assertTrue(File(mirror, "old_dir").renameTo(held))
        observe(FileObserver.MOVED_FROM or isDirFlag, "old_dir")
        drain()
        observe(FileObserver.MOVED_TO or isDirFlag, "lib")
        assertTrue(held.renameTo(File(mirror, "lib")))

        drain()

        assertEquals(setOf("lib", "lib/existing.txt"), device.keys)
        assertEquals("edited before rename", text("lib/existing.txt"))
        assertEquals(emptyList<String>(), lost)
    }

    /**
     * The same, for a save the write-back thread takes after the rename has claimed its folder and
     * before the rename job is offered: the claim has taken the folder's entry, so the drop had no
     * entry to be noted with, and the job was already off the queue, so the offer did not see it.
     */
    @Test
    fun `a save taken after its folder was claimed and before the rename is queued reaches the renamed folder`() {
        onDevice("old_dir")
        onDevice("old_dir/existing.txt", "old")
        inMirror("old_dir/existing.txt", "edited before rename")
        observe(FileObserver.MODIFY, "old_dir/existing.txt")
        val held = File(filesDir, "renaming")
        assertTrue(File(mirror, "old_dir").renameTo(held))
        observe(FileObserver.MOVED_FROM or isDirFlag, "old_dir")
        var taken = false
        // Once the claim has taken the folder's entry, the next answer the provider gives is the old
        // name being resolved: the write-back thread takes the save there.
        onProviderCall = {
            if (!taken && !engine.hasVanishedCandidate(System.currentTimeMillis())) {
                taken = true
                drain()
            }
        }
        observe(FileObserver.MOVED_TO or isDirFlag, "lib")
        onProviderCall = null
        assertTrue(held.renameTo(File(mirror, "lib")))
        assertTrue(taken, "the save was not taken inside the event, so the case never reached the window")

        drain()

        assertEquals(setOf("lib", "lib/existing.txt"), device.keys)
        assertEquals("edited before rename", text("lib/existing.txt"))
        assertEquals(emptyList<String>(), lost)
    }

    @Test
    fun `a file made just before its folder moved away is created in the renamed folder`() {
        onDevice("old_dir")
        onDevice("old_dir/existing.txt", "old")
        inMirror("old_dir/existing.txt", "old")
        inMirror("old_dir/made.txt", "made before rename")
        observe(FileObserver.CREATE, "old_dir/made.txt")
        val held = File(filesDir, "renaming")
        assertTrue(File(mirror, "old_dir").renameTo(held))
        observe(FileObserver.MOVED_FROM or isDirFlag, "old_dir")
        drain()
        observe(FileObserver.MOVED_TO or isDirFlag, "lib")
        assertTrue(held.renameTo(File(mirror, "lib")))

        drain()

        assertEquals(setOf("lib", "lib/existing.txt", "lib/made.txt"), device.keys)
        assertEquals("made before rename", text("lib/made.txt"))
        assertEquals(emptyList<String>(), lost)
    }

    /** What was dropped for a folder that nobody claimed is not carried to a later, unrelated folder of the same name. */
    @Test
    fun `a save dropped for a folder nobody claimed is not replayed into a folder made later`() {
        onDevice("old_dir")
        onDevice("old_dir/existing.txt", "old")
        inMirror("old_dir/existing.txt", "edited before it left")
        observe(FileObserver.MODIFY, "old_dir/existing.txt")
        val held = File(filesDir, "gone")
        assertTrue(File(mirror, "old_dir").renameTo(held))
        observe(FileObserver.MOVED_FROM or isDirFlag, "old_dir")
        drain()
        // the folder is deleted for good, and an unrelated one of the same name is made by hand
        assertTrue(held.deleteRecursively())
        inMirror("lib/existing.txt", "someone else's text")
        observe(FileObserver.CREATE or isDirFlag, "lib")

        drain()

        assertEquals("old", text("old_dir/existing.txt"))
        assertEquals("someone else's text", text("lib/existing.txt"))
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
        deliverKnownFile("notes.txt")

        // mv old_dir lib && mv notes.txt lib/notes.txt, inside one poll. The rename has
        // not run when the second event resolves lib/, so the device has no such folder.
        observe(FileObserver.MOVED_FROM or isDirFlag, "old_dir")
        observe(FileObserver.MOVED_TO or isDirFlag, "lib")
        assertTrue(File(mirror, "notes.txt").delete())
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
        deliverKnownFile("notes.txt")

        observe(FileObserver.MOVED_FROM or isDirFlag, "old_dir")
        observe(FileObserver.MOVED_TO or isDirFlag, "lib")
        inMirror("lib/existing.txt", "old")
        drain()
        assertTrue(File(mirror, "notes.txt").delete())
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
        deliverKnownFile("foo")
        assertTrue(File(mirror, "foo").delete())

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

    /**
     * A save the watcher delivered is vouched for by the record's row for its path. A folder
     * rename moved every other memory of the folder to the new name and left those rows under the
     * old one, so the next open found the renamed file with no row, took the device copy for one
     * another app may have written, and kept this app's own delivered save beside the file as a
     * `.device-` copy, with a notice saying a device edit was kept.
     */
    @Test
    fun `a save delivered before its folder was renamed is not kept as a device edit at the next open`() {
        onDevice("old_dir")
        onDevice("old_dir/notes.txt", "baseline")
        runBlocking { engine.initialSync(treeUri, mirror) { _, _ -> } }
        inMirror("old_dir/notes.txt", "delivered")
        observe(FileObserver.MODIFY, "old_dir/notes.txt")
        drain()
        assertEquals("delivered", text("old_dir/notes.txt"))
        assertTrue(File(mirror, "old_dir").renameTo(File(mirror, "lib")))
        observe(FileObserver.MOVED_FROM or isDirFlag, "old_dir")
        Thread.sleep(2)
        observe(FileObserver.MOVED_TO or isDirFlag, "lib")
        drain()
        assertEquals("delivered", text("lib/notes.txt"), "precondition: the rename reached the device")
        // Edited again and never sent, as an edit made while the folder was not watched is.
        inMirror("lib/notes.txt", "edited again, never sent")

        runBlocking { engine.initialSync(treeUri, mirror) { _, _ -> } }

        assertEquals(
            emptyList<String>(),
            File(mirror, "lib").list()!!.filter { SafSyncEngine.DEVICE_COPY_SUFFIX in it },
            "the open kept this app's own delivered save as if another app had written it",
        )
        assertEquals("edited again, never sent", text("lib/notes.txt"))
    }

    /**
     * Stands in for the watch an open keeps on each directory of the mirror, which a JVM test
     * cannot build: it remembers each directory's delivery, and [fire] reports an event the way
     * the kernel would.
     */
    private inner class OpenWatches {
        private val deliveries = mutableMapOf<String, (Int, String?) -> Unit>()
        val watching = mutableSetOf<String>()

        fun install() {
            engine.directoryWatchFactory = { dir, deliver ->
                deliveries[dir.absolutePath] = deliver
                object : OpenWatch {
                    override fun start() { watching += dir.absolutePath }
                    override fun stop() { watching -= dir.absolutePath }
                }
            }
        }

        fun fire(event: Int, path: String) {
            val file = File(mirror, path)
            deliveries.getValue(file.parentFile.absolutePath)(event, file.name)
        }
    }

    /**
     * Stands in for FileObserver as Android has it: all observers of a process share one inotify
     * instance, so two observers of one directory share one watch. The one started last is the one
     * the directory's events go to, and stopping either removes the watch for both.
     */
    private inner class KernelWatches {
        val log = mutableListOf<String>()
        private val active = mutableMapOf<String, Boolean>()
        private val receiver = mutableMapOf<String, (Int, String?) -> Unit>()

        /** Whose watches are being made: the open's, or the watcher's once it starts. */
        var owner = "open"

        private fun name(dir: File) = dir.relativeTo(mirror).path.ifEmpty { "." }

        fun install() {
            engine.directoryWatchFactory = { dir, deliver ->
                val whose = owner
                object : OpenWatch {
                    override fun start() {
                        log += "start:$whose:${name(dir)}"
                        active[name(dir)] = true
                        receiver[name(dir)] = deliver
                    }

                    override fun stop() {
                        log += "stop:$whose:${name(dir)}"
                        active[name(dir)] = false
                    }
                }
            }
        }

        fun watched(dir: String) = active[dir] == true

        fun fire(event: Int, path: String) {
            val file = File(mirror, path)
            val dir = name(file.parentFile)
            if (watched(dir)) receiver.getValue(dir)(event, file.name)
        }
    }

    /**
     * The watcher takes each directory over from the open's watch by starting its own first, and
     * does not stop the open's then: stopped first, the moment between the two lost what happened
     * in it; stopped after, the shared watch would go with it and leave the watcher deaf. The
     * open's watch goes when the watcher's does.
     */
    @Test
    fun `the watcher starts each directory's watch before the open's watch of it ends`() {
        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } returns 0L
        onDevice("src")
        onDevice("src/a.txt", "alpha")
        runBlocking { engine.initialSync(treeUri, mirror) { _, _ -> } }
        val kernel = KernelWatches().also { it.install() }
        openRecording { _, _ -> }
        kernel.owner = "watcher"

        engine.startWatching(mirror, treeUri)
        try {
            for (dir in listOf(".", "src")) {
                val started = kernel.log.indexOf("start:watcher:$dir")
                val ended = kernel.log.indexOf("stop:open:$dir")
                assertTrue(started >= 0, "the watcher never watched $dir: ${kernel.log}")
                assertTrue(ended == -1 || ended > started, "the open's watch of $dir ended before the watcher's began: ${kernel.log}")
                assertTrue(kernel.watched(dir), "the handover left $dir unwatched: ${kernel.log}")
            }
            inMirror("src/a.txt", "typed after the watcher started")
            kernel.fire(FileObserver.MODIFY, "src/a.txt")
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
            while (text("src/a.txt") != "typed after the watcher started" && System.nanoTime() < deadline) Thread.sleep(10)
            assertEquals("typed after the watcher started", text("src/a.txt"), "the watcher did not hear the directory it took over")
        } finally {
            engine.shutdown()
        }
        assertEquals(1, writes.count { it == "src/a.txt" }, "the save was sent more than once")
        for (dir in listOf(".", "src")) {
            assertTrue(kernel.log.indexOf("stop:open:$dir") > kernel.log.indexOf("stop:watcher:$dir"), "the open's watch of $dir outlived the watcher: ${kernel.log}")
        }
    }

    /** An open that records what changes while it runs, as the folder open the editor waits on does. */
    private fun openRecording(onProgress: (Int, Int) -> Unit) =
        runBlocking { engine.initialSync(treeUri, mirror, recordChanges = true, onProgress = onProgress) }

    /** What the watcher does with that record once it starts, without the observers it builds. */
    private fun replayWhatTheOpenRecorded() {
        val changes = checkNotNull(engine.takeChangesDuringOpen(mirror)) { "the open kept no record of its changes" }
        engine.replayChangesDuringOpen(changes, mirror, treeUri)
    }

    /**
     * The watcher is stopped while a folder opens, so a file deleted before the open reached it was
     * fetched back from the device, and the deletion was undone with nothing said.
     */
    @Test
    fun `a file deleted while its folder opens is deleted on the device and not fetched back`() {
        onDevice("a.txt", "alpha")
        onDevice("b.txt", "bravo")
        runBlocking { engine.initialSync(treeUri, mirror) { _, _ -> } }
        val watches = OpenWatches().also { it.install() }
        var deleted = false

        openRecording { done, _ ->
            if (done == 1 && !deleted) {
                deleted = true
                assertTrue(File(mirror, "b.txt").delete())
                watches.fire(FileObserver.DELETE, "b.txt")
            }
        }
        assertTrue(deleted, "precondition: the file was deleted while the folder opened")
        assertFalse(File(mirror, "b.txt").exists(), "the open fetched back a file deleted while it ran")
        replayWhatTheOpenRecorded()
        drain()

        assertEquals(setOf("a.txt"), device.keys, "the deletion did not reach the device")
        assertEquals(emptyList<String>(), lost + refused)
        assertTrue(watches.watching.isEmpty(), "the open's watches were left running")
    }

    /** What decides the deletion is what decides any other: another app's edit since the last open is kept. */
    @Test
    fun `a file deleted while its folder opens stays on the device when another app changed it since`() {
        onDevice("a.txt", "alpha")
        onDevice("b.txt", "bravo")
        runBlocking { engine.initialSync(treeUri, mirror) { _, _ -> } }
        onDevice("b.txt", "BRAVO")
        val kept = mutableListOf<String>()
        engine.onKeptOnDevice = { file, _, changed -> kept += "${file.name}:$changed" }
        val watches = OpenWatches().also { it.install() }
        var deleted = false

        openRecording { done, _ ->
            if (done == 1 && !deleted) {
                deleted = true
                assertTrue(File(mirror, "b.txt").delete())
                watches.fire(FileObserver.DELETE, "b.txt")
            }
        }
        replayWhatTheOpenRecorded()
        drain()

        assertEquals("BRAVO", text("b.txt"), "another app's edit went with a deletion made before the open read it")
        assertEquals(listOf("b.txt:true"), kept)
    }

    /** A folder removed with what is in it while the open runs, as `rm -r` reports it: each file, then the folder. */
    @Test
    fun `a folder removed while its folder opens is removed on the device and not fetched back`() {
        onDevice("a.txt", "alpha")
        onDevice("d")
        onDevice("d/x.txt", "x-ray")
        runBlocking { engine.initialSync(treeUri, mirror) { _, _ -> } }
        val watches = OpenWatches().also { it.install() }
        var removed = false

        openRecording { done, _ ->
            if (done == 1 && !removed) {
                removed = true
                assertTrue(File(mirror, "d").deleteRecursively())
                watches.fire(FileObserver.DELETE, "d/x.txt")
                watches.fire(FileObserver.DELETE or isDirFlag, "d")
            }
        }
        assertTrue(removed, "precondition: the folder was removed while the open ran")
        assertFalse(File(mirror, "d").exists(), "the open fetched back a folder removed while it ran")
        replayWhatTheOpenRecorded()
        drain()

        assertEquals(setOf("a.txt"), device.keys)
        assertEquals(emptyList<String>(), lost + refused)
    }

    /**
     * A file made while its folder opens is sent once the folder is open, also over a record the open
     * could not read, where the record the open writes names the file without a digest: such a row
     * only says the open could not place the file, not that the device has it.
     */
    @Test
    fun `a file made while its folder opens over an unreadable record reaches the device once the watcher starts`() {
        onDevice("a.txt", "alpha")
        onDevice("b.txt", "bravo")
        runBlocking { engine.initialSync(treeUri, mirror) { _, _ -> } }
        val record = File(mirror.path + SafSyncEngine.SYNCED_RECORD_SUFFIX)
        record.writeText("not a record this build wrote")
        val watches = OpenWatches().also { it.install() }
        var made = false

        openRecording { done, _ ->
            if (done == 1 && !made) {
                made = true
                inMirror("n.txt", "made while it opened")
                watches.fire(FileObserver.CREATE, "n.txt")
                watches.fire(FileObserver.MODIFY, "n.txt")
            }
        }
        assertTrue(made, "precondition: the file was made while the folder opened")
        val row = record.readLines().single { it.startsWith("n.txt\t") }
        assertNull(SafSyncEngine.splitRecordLine(row).second, "precondition: the record names the file without a digest")
        replayWhatTheOpenRecorded()
        drain()

        assertEquals("made while it opened", text("n.txt"))
    }

    /**
     * A shutdown that lands while a folder opens ends the open's watches then. Kept by the open alone,
     * they stayed registered until it finished, and the watch of a directory is shared with any other
     * observer of it, which the close at the end then ended too.
     */
    @Test
    fun `a shutdown during an open stops the open's watches at once`() {
        onDevice("a.txt", "alpha")
        onDevice("b.txt", "bravo")
        runBlocking { engine.initialSync(treeUri, mirror) { _, _ -> } }
        val watches = OpenWatches().also { it.install() }
        var afterShutdown: Set<String>? = null

        openRecording { done, _ ->
            if (done == 1 && afterShutdown == null) {
                assertTrue(watches.watching.isNotEmpty(), "precondition: the open was watching")
                engine.shutdown()
                afterShutdown = watches.watching.toSet()
            }
        }

        assertEquals(emptySet<String>(), afterShutdown, "the shutdown left the open's watches running")
        assertNull(engine.takeChangesDuringOpen(mirror), "a record was kept for a watcher that can never start")
    }

    /** The same window for a save: one made after the open had passed its file waited for the next save or open. */
    @Test
    fun `a save made after its folder's open passed the file reaches the device once the watcher starts`() {
        onDevice("a.txt", "alpha")
        onDevice("b.txt", "bravo")
        runBlocking { engine.initialSync(treeUri, mirror) { _, _ -> } }
        val watches = OpenWatches().also { it.install() }
        var saved = false

        openRecording { done, _ ->
            if (done == 2 && !saved) {
                saved = true
                inMirror("a.txt", "typed after the open passed it")
                watches.fire(FileObserver.MODIFY, "a.txt")
            }
        }
        assertTrue(saved, "precondition: the save was made while the folder opened")
        replayWhatTheOpenRecorded()
        drain()

        assertEquals("typed after the open passed it", text("a.txt"))
        assertEquals(emptyList<String>(), lost)
    }

    /**
     * The open raises events of its own: it puts fetched copies in place, makes the device's
     * folders, and removes what the device no longer holds. None of that is sent back, while the
     * person's deletion in the same open still is.
     */
    @Test
    fun `what the open put in place, made or removed itself is not sent back`() {
        onDevice("0.txt", "zero")
        onDevice("a.txt", "alpha")
        onDevice("c.txt", "charlie")
        runBlocking { engine.initialSync(treeUri, mirror) { _, _ -> } }
        device.remove("c.txt")
        onDevice("d")
        onDevice("d/x.txt", "x-ray")
        onDevice("new.txt", "november")
        val watches = OpenWatches().also { it.install() }
        var deleted = false

        openRecording { done, _ ->
            if (done == 1 && !deleted) {
                deleted = true
                assertTrue(File(mirror, "a.txt").delete())
                watches.fire(FileObserver.DELETE, "a.txt")
            }
        }
        assertTrue(deleted, "precondition: the file was deleted while the folder opened")
        assertFalse(File(mirror, "c.txt").exists(), "precondition: the open removed what the device no longer holds")
        assertEquals("november", File(mirror, "new.txt").readText(), "precondition: the open fetched the new file")
        // What the kernel reports for the open's own work, which reaches the watch after the fact.
        val capture = "removed-00000000-0000-0000-0000-000000000000" + SafSyncEngine.PARTIAL_SUFFIX
        watches.fire(FileObserver.CREATE or isDirFlag, "d")
        watches.fire(FileObserver.MOVED_TO, "new.txt")
        watches.fire(FileObserver.MOVED_FROM, "c.txt")
        watches.fire(FileObserver.MOVED_TO, capture)
        watches.fire(FileObserver.DELETE, capture)
        writes.clear()
        attempts.clear()
        replayWhatTheOpenRecorded()
        drain()

        assertEquals(setOf("0.txt", "d", "d/x.txt", "new.txt"), device.keys)
        assertEquals(emptyList<String>(), attempts, "the open's own work was written back to the device")
        assertEquals(emptyList<String>(), lost + refused)
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
