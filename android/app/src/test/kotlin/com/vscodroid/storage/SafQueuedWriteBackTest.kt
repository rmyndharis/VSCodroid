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
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes

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

    /** `mv from to` in the mirror: the folder leaves, both halves are seen, and it lands under the new name. */
    private fun renameFolder(from: String, to: String) {
        val held = File(filesDir, "renaming")
        assertTrue(File(mirror, from).renameTo(held))
        observe(FileObserver.MOVED_FROM or isDirFlag, from)
        observe(FileObserver.MOVED_TO or isDirFlag, to)
        assertTrue(held.renameTo(File(mirror, to)))
    }

    /** The identity the watch of a folder records for it. */
    private fun identityOf(dir: File): Any = Files.readAttributes(
        dir.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS,
    ).fileKey()

    private val companion = SafSyncEngine.Companion

    /** Takes the device folder's write claim, as another folder's write in progress holds it. */
    private fun holdProviderClaim(): Any = companion.javaClass
        .getDeclaredMethod("claimProviderMutation", Uri::class.java)
        .apply { isAccessible = true }
        .invoke(companion, mockk<Uri>(relaxed = true)) ?: error("the claim was already held")

    private fun releaseProviderClaim(claim: Any) {
        companion.javaClass.getDeclaredMethod("releaseProviderMutation", claim.javaClass)
            .apply { isAccessible = true }.invoke(companion, claim)
    }

    /** A save of [path] that meets another write's claim and is held back, as the drain leaves it. */
    private fun holdBackSave(path: String) {
        val claim = holdProviderClaim()
        try {
            observe(FileObserver.MODIFY, path)
            drain()
        } finally {
            releaseProviderClaim(claim)
        }
        assertEquals(listOf(path), lost, "setup failed: the save was not held back")
    }

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
     * could not read. The open leaves such a file to the watcher: it writes no row for it and does not
     * say the file stayed in the app, which the watcher then disproves by sending it.
     */
    @Test
    fun `a file made while its folder opens over an unreadable record reaches the device once the watcher starts`() {
        onDevice("a.txt", "alpha")
        onDevice("b.txt", "bravo")
        runBlocking { engine.initialSync(treeUri, mirror) { _, _ -> } }
        val record = File(mirror.path + SafSyncEngine.SYNCED_RECORD_SUFFIX)
        record.writeText("not a record this build wrote")
        val notReached = mutableListOf<Int>()
        engine.onUploadIncomplete = { _, count, _ -> notReached += count }
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
        assertTrue(record.readLines().none { it.startsWith("n.txt\t") }, "the open kept a file the watcher is to send")
        assertEquals(emptyList<Int>(), notReached, "the open said a file the watcher sends did not reach the device folder")
        replayWhatTheOpenRecorded()
        drain()

        assertEquals("made while it opened", text("n.txt"))
        assertEquals(emptyList<Int>(), notReached)
    }

    /**
     * The first open of a folder, with no record beside its copy, while the person renames a file the open has
     * just copied. The file the rename made is only in the copy when the open ends, and with no record the open
     * cannot tell it from a document deleted on the device: it kept it and said it had not reached the device
     * folder, and the watcher sent it a moment later from what the open recorded.
     */
    @Test
    fun `a file renamed into place while a folder first opens reaches the device and is not announced as kept`() {
        onDevice("a.txt", "alpha")
        onDevice("g1.txt", "golf")
        val notReached = mutableListOf<Int>()
        engine.onUploadIncomplete = { _, count, _ -> notReached += count }
        val watches = OpenWatches().also { it.install() }
        var renamed = false

        openRecording { done, _ ->
            if (done == 2 && !renamed) {
                renamed = true
                assertTrue(File(mirror, "g1.txt").renameTo(File(mirror, "g3.txt")))
                watches.fire(FileObserver.MOVED_FROM, "g1.txt")
                watches.fire(FileObserver.MOVED_TO, "g3.txt")
            }
        }
        assertTrue(renamed, "precondition: the file was renamed while the folder opened")
        assertEquals(emptyList<Int>(), notReached, "the open said a file the watcher sends did not reach the device folder")
        replayWhatTheOpenRecorded()
        drain()

        assertEquals("golf", text("g3.txt"), "the renamed file did not reach the device")
        assertEquals(setOf("a.txt", "g3.txt"), device.keys)
        assertEquals(emptyList<Int>(), notReached)
        assertEquals(emptyList<String>(), lost + refused)
    }

    /**
     * The same file, where the watcher does not send it: the record cannot be read when it starts, so nothing
     * tells the open's own copies from the person's. The file stays in the app, and the person is told so.
     */
    @Test
    fun `a file renamed into place while a folder first opens is announced when the watcher does not send it`() {
        onDevice("a.txt", "alpha")
        onDevice("g1.txt", "golf")
        val notReached = mutableListOf<Int>()
        engine.onUploadIncomplete = { _, count, _ -> notReached += count }
        val watches = OpenWatches().also { it.install() }
        var renamed = false
        openRecording { done, _ ->
            if (done == 2 && !renamed) {
                renamed = true
                assertTrue(File(mirror, "g1.txt").renameTo(File(mirror, "g3.txt")))
                watches.fire(FileObserver.MOVED_FROM, "g1.txt")
                watches.fire(FileObserver.MOVED_TO, "g3.txt")
            }
        }
        assertEquals(emptyList<Int>(), notReached, "precondition: the open left the file to the watcher")
        val record = File(mirror.path + SafSyncEngine.SYNCED_RECORD_SUFFIX)
        assertTrue(record.setReadable(false, false))
        try {
            assumeFalse(Files.isReadable(record.toPath()), "This account bypasses file read permissions")
            replayWhatTheOpenRecorded()
            drain()
        } finally {
            record.setReadable(true, true)
        }

        assertFalse("g3.txt" in device.keys, "precondition: the watcher sent the file")
        assertEquals("golf", File(mirror, "g3.txt").readText(), "the file did not stay in the app")
        assertEquals(listOf(1), notReached, "a file that stayed in the app was not told of")
    }

    /**
     * A copy the open sets aside beside a folder that replaced its file, where the record names the file without
     * a digest, is kept inside the app: nothing shows it is the person's work rather than the device's old bytes.
     * The open's own move of it reaches the open's watch, and the watcher sent it from there.
     */
    @Test
    fun `a copy the open keeps beside the folder that replaced it is not sent when the watcher starts`() {
        onDevice("keep.txt", "kilo")
        onDevice("foo", "first draft")
        runBlocking { engine.initialSync(treeUri, mirror) { _, _ -> } }
        val record = File(mirror.path + SafSyncEngine.SYNCED_RECORD_SUFFIX)
        record.writeText(record.readLines().joinToString("\n") { if (it.startsWith("foo\t")) it.substringBeforeLast('\t') else it })
        onDevice("foo")
        val notReached = mutableListOf<Int>()
        engine.onUploadIncomplete = { _, count, _ -> notReached += count }
        val watches = OpenWatches().also { it.install() }

        openRecording { _, _ -> }
        val kept = mirror.list()!!.single { it.startsWith("foo" + SafSyncEngine.LOCAL_COPY_SUFFIX) }
        // What the kernel reports for the open's own move, which reaches the watch after the fact.
        watches.fire(FileObserver.MOVED_FROM, "foo")
        watches.fire(FileObserver.MOVED_TO, kept)
        watches.fire(FileObserver.CREATE or isDirFlag, "foo")
        replayWhatTheOpenRecorded()
        drain()

        assertFalse(kept in device.keys, "the watcher sent a copy the open keeps inside the app")
        assertEquals("first draft", File(mirror, kept).readText())
        assertEquals(listOf(1), notReached)
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

    /**
     * A save held back because another write had the device folder's claim names its document by the
     * URI it had, which a folder rename makes stale. The rename ended the hold and sent nothing, so the
     * renamed document kept its older bytes until the file was saved again.
     */
    @Test
    fun `a save held back before its folder is renamed reaches the renamed folder`() {
        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } returns 0L
        onDevice("old_dir")
        onDevice("old_dir/existing.txt", "old")
        inMirror("old_dir/existing.txt", "edited before rename")
        holdBackSave("old_dir/existing.txt")

        renameFolder("old_dir", "lib")
        drain()

        assertEquals(setOf("lib", "lib/existing.txt"), device.keys)
        assertEquals("edited before rename", text("lib/existing.txt"))
        assertEquals(listOf("old_dir/existing.txt"), lost, "a save that arrived was announced again")
    }

    /**
     * The same hold, tried again while its folder is between the two halves of a rename: the file is
     * not at its path, so the try ended the hold and nothing carried the save to the new name.
     */
    @Test
    fun `a held save that comes due while its folder moves reaches the renamed folder`() {
        var clock = 0L
        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } answers { clock }
        onDevice("old_dir")
        onDevice("old_dir/existing.txt", "old")
        inMirror("old_dir/existing.txt", "edited before rename")
        holdBackSave("old_dir/existing.txt")
        val held = File(filesDir, "renaming")
        assertTrue(File(mirror, "old_dir").renameTo(held))
        observe(FileObserver.MOVED_FROM or isDirFlag, "old_dir")

        clock = SafSyncEngine.HELD_BACK_RETRY_FIRST_MS + 1
        assertFalse(engine.retryHeldBack(WatchSession(mirror)), "a save was tried with its file gone")
        observe(FileObserver.MOVED_TO or isDirFlag, "lib")
        assertTrue(held.renameTo(File(mirror, "lib")))
        drain()

        assertEquals(setOf("lib", "lib/existing.txt"), device.keys)
        assertEquals("edited before rename", text("lib/existing.txt"))
    }

    /**
     * A folder's move lands on disk before the observer reports it, so the write-back thread can take a
     * save of a file in it in between, with no departed folder known yet to note the dropped save with.
     */
    @Test
    fun `a save taken after its folder moved and before the move is reported reaches the renamed folder`() {
        onDevice("old_dir")
        onDevice("old_dir/existing.txt", "old")
        inMirror("old_dir/existing.txt", "edited before rename")
        observe(FileObserver.MODIFY, "old_dir/existing.txt")
        val held = File(filesDir, "renaming")
        assertTrue(File(mirror, "old_dir").renameTo(held))
        drain()
        observe(FileObserver.MOVED_FROM or isDirFlag, "old_dir")
        observe(FileObserver.MOVED_TO or isDirFlag, "lib")
        assertTrue(held.renameTo(File(mirror, "lib")))

        drain()

        assertEquals(setOf("lib", "lib/existing.txt"), device.keys)
        assertEquals("edited before rename", text("lib/existing.txt"))
        assertEquals(emptyList<String>(), lost)
    }

    /** A save dropped well before its folder moves is not one the move explains, and is not sent with it. */
    @Test
    fun `a save dropped long before its folder moves is not sent with the move`() {
        onDevice("old_dir")
        onDevice("old_dir/existing.txt", "old")
        inMirror("old_dir/existing.txt", "old")
        val stale = File(mirror, "old_dir/existing.txt").absolutePath
        engine.session.recentDrops += stale to System.currentTimeMillis() - SafSyncEngine.RENAME_PAIR_WINDOW_MS - 1

        renameFolder("old_dir", "lib")
        drain()

        assertEquals(setOf("lib", "lib/existing.txt"), device.keys)
        assertEquals(emptyList<String>(), writes, "a save nobody made for this folder was sent with its rename")
    }

    /**
     * A folder renamed twice in quick succession, the second move landing while the first rename's job
     * is sending the saves it carried: the file is no longer under the first new name, and that job
     * skipped it without a word while the second rename's job had never heard of it.
     */
    @Test
    fun `a save carried by a rename reaches the folder's last name when it moves again meanwhile`() {
        onDevice("a")
        onDevice("a/f", "old")
        inMirror("a/f", "edited")
        observe(FileObserver.MODIFY, "a/f")
        renameFolder("a", "b")
        var movedAgain = false
        // The first provider answer after the first rename has landed on the device.
        onProviderCall = {
            if (!movedAgain && "b" in device) {
                movedAgain = true
                renameFolder("b", "c")
            }
        }

        drain()
        onProviderCall = null

        assertTrue(movedAgain, "setup failed: the second move did not land inside the first rename's job")
        assertEquals(setOf("c", "c/f"), device.keys)
        assertEquals("edited", text("c/f"))
        assertEquals(emptyList<String>(), lost)
    }

    /**
     * A folder that leaves for good and an unrelated one arriving within the pairing window were joined
     * as one rename: the device's copy of the departed folder took the arriving folder's name, and the
     * departed folder's queued save then wrote the arriving folder's file over the device's copy of its own.
     */
    @Test
    fun `a folder arriving as another leaves is not taken for its rename`() {
        onDevice("gone")
        onDevice("gone/f", "the departed folder's device copy")
        inMirror("gone/f", "edited before it left")
        engine.watchedDirectoryKeys[File(mirror, "gone").absolutePath] = identityOf(File(mirror, "gone"))
        observe(FileObserver.MODIFY, "gone/f")
        assertTrue(File(mirror, "gone").renameTo(File(filesDir, "elsewhere")))
        observe(FileObserver.MOVED_FROM or isDirFlag, "gone")
        val arriving = File(filesDir, "arriving").apply { mkdirs() }
        File(arriving, "f").writeText("the arriving folder's own file")
        assertTrue(arriving.renameTo(File(mirror, "lib")))
        observe(FileObserver.MOVED_TO or isDirFlag, "lib")

        drain()

        assertEquals("the departed folder's device copy", text("gone/f"))
        assertEquals("the arriving folder's own file", text("lib/f"))
    }

    @Test
    fun `a folder renamed with its identity recorded is still renamed on the device`() {
        onDevice("old_dir")
        onDevice("old_dir/existing.txt", "old")
        inMirror("old_dir/existing.txt", "old")
        engine.watchedDirectoryKeys[File(mirror, "old_dir").absolutePath] = identityOf(File(mirror, "old_dir"))
        assertTrue(File(mirror, "old_dir").renameTo(File(mirror, "lib")))
        observe(FileObserver.MOVED_FROM or isDirFlag, "old_dir")
        observe(FileObserver.MOVED_TO or isDirFlag, "lib")

        drain()

        verify(exactly = 1) { DocumentsContract.renameDocument(any(), any(), "lib") }
        assertEquals(setOf("lib", "lib/existing.txt"), device.keys)
        assertEquals(emptyList<String>(), writes)
    }

    /**
     * A rename that gave way to a later rename onto the same name left the saves it had claimed with the
     * session, and the next rename of the same old name sent them again.
     */
    @Test
    fun `a rename given way to a later one leaves no save behind for the next rename of its old name`() {
        onDevice("a")
        onDevice("a/f", "old")
        onDevice("c")
        onDevice("c/g", "g")
        inMirror("a/f", "edited")
        inMirror("c/g", "g")
        observe(FileObserver.MODIFY, "a/f")
        // mv a b && rm -r b && mv c b, with the save of a/f taken after a left.
        val held = File(filesDir, "renaming")
        assertTrue(File(mirror, "a").renameTo(held))
        observe(FileObserver.MOVED_FROM or isDirFlag, "a")
        drain()
        observe(FileObserver.MOVED_TO or isDirFlag, "b")
        assertTrue(held.deleteRecursively())
        Thread.sleep(3)
        observe(FileObserver.DELETE or isDirFlag, "b")
        Thread.sleep(3)
        renameFolder("c", "b")
        drain()
        assertEquals("old", text("a/f"), "setup failed: the first rename was not the one given way")
        // A new a, delivered, then renamed.
        inMirror("a/f", "fresh")
        observe(FileObserver.CREATE or isDirFlag, "a")
        drain()
        assertEquals("fresh", text("a/f"))
        writes.clear()

        renameFolder("a", "x")
        drain()

        assertEquals("fresh", text("x/f"))
        assertEquals(emptyList<String>(), writes, "a save of the earlier a was sent again")
    }

    /** An event that finishes after the folder's drain has ended reaches no device, and the user is told. */
    @Test
    fun `a save observed after the folder's drain ended is announced`() {
        engine.stopWatching()
        inMirror("notes.txt", "late save")

        observe(FileObserver.MODIFY, "notes.txt")

        assertEquals(listOf("notes.txt"), lost)
    }
}
