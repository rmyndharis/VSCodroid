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
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.OutputStream
import java.lang.ref.WeakReference
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

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

    /** Run as a rename reaches the device folder, before it lands there. */
    private var onRenameCall: (() -> Unit)? = null

    /** How many queries the device folder has answered. */
    private var queries = 0

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
            onRenameCall?.invoke()
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
            queries++
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
        File(mirror.path + SafSyncEngine.SORTED_RECORD_SUFFIX).delete()
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
            if (!taken && !engine.hasVanishedCandidate(engine.monotonicClock())) {
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
        // Nothing here read the folder's files, so the device cannot be asked to delete what
        // the event could not name, and the user is told that, rather than the old folder
        // quietly staying under its old name.
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
     * An open over a record it cannot read writes nothing over it, and a save that open sends has its row only in
     * the engine's memory. It was dropped there, so the next open, with the record readable again, found the device
     * holding bytes no row vouched for and kept this app's own delivered save beside the file as another app's edit.
     */
    @Test
    fun `a save an open sends over a record it cannot read is not kept as a device edit at the next open`() {
        onDevice("notes.txt", "baseline")
        runBlocking { engine.initialSync(treeUri, mirror) { _, _ -> } }
        inMirror("notes.txt", "edited while closed")
        val record = File(mirror.path + SafSyncEngine.SYNCED_RECORD_SUFFIX)
        assertTrue(record.setReadable(false, false))
        try {
            assumeFalse(Files.isReadable(record.toPath()), "This account bypasses file read permissions")
            runBlocking { engine.initialSync(treeUri, mirror) { _, _ -> } }
        } finally {
            record.setReadable(true, true)
        }
        assertEquals("edited while closed", text("notes.txt"), "precondition: the open did not send the save")
        val keptBefore = deviceCopies()
        // Edited again and never sent, as an edit made while the folder was not watched is.
        inMirror("notes.txt", "edited again, never sent")

        runBlocking { engine.initialSync(treeUri, mirror) { _, _ -> } }

        assertEquals(keptBefore, deviceCopies(), "the open kept this app's own delivered save as if another app had written it")
        assertEquals("edited again, never sent", text("notes.txt"))
    }

    /** The contents of the `.device-` copies beside the mirror's files. */
    private fun deviceCopies() =
        mirror.list()!!.filter { SafSyncEngine.DEVICE_COPY_SUFFIX in it }.map { File(mirror, it).readText() }.sorted()

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
     * instance, so two observers of one directory share one watch. A watch belongs to the directory
     * itself, not to the name it was asked for by, so it follows a rename, and asking for it again
     * under any name gets the same descriptor. The observer started last is the one the directory's
     * events go to; stopping either removes the watch for both, unless the watch has gone already, as
     * the kernel drops it when its directory is unlinked ([unlinked]).
     */
    private inner class KernelWatches {
        val log = mutableListOf<String>()
        private val descriptors = mutableMapOf<Any, Int>()
        private val receiver = mutableMapOf<Int, (Int, String?) -> Unit>()
        private var next = 1

        /** Whose watches are being made: the open's, or the watcher's once it starts. */
        var owner = "open"

        /** Run as any watch is stopped, before the stop reaches the kernel. */
        var onStop: (() -> Unit)? = null

        /** [onStop] with the watch that stops, as the log names it: `watcher:.` for the watcher's watch of the root. */
        var onStopOf: ((String) -> Unit)? = null

        private fun name(dir: File) = dir.relativeTo(mirror).path.ifEmpty { "." }

        private fun identity(dir: File): Any? = runCatching { identityOf(dir) }.getOrNull()

        fun install() {
            engine.directoryWatchFactory = { dir, deliver ->
                val whose = owner
                var watched: Pair<Any, Int>? = null
                object : OpenWatch {
                    override fun start() {
                        synchronized(this@KernelWatches) {
                            log += "start:$whose:${name(dir)}"
                            val key = identity(dir) ?: return
                            val descriptor = descriptors.getOrPut(key) { next++ }
                            receiver[descriptor] = deliver
                            watched = key to descriptor
                        }
                    }

                    override fun stop() {
                        onStop?.invoke()
                        onStopOf?.invoke("$whose:${name(dir)}")
                        synchronized(this@KernelWatches) {
                            log += "stop:$whose:${name(dir)}"
                            val (key, descriptor) = watched ?: return
                            if (descriptors[key] == descriptor) {
                                descriptors.remove(key)
                                receiver.remove(descriptor)
                            }
                        }
                    }
                }
            }
        }

        /** The directory that had [identity] is gone, and so is its watch. */
        fun unlinked(identity: Any) {
            synchronized(this) { descriptors.remove(identity)?.let { receiver.remove(it) } }
        }

        fun watched(dir: String) = synchronized(this) { identity(File(mirror, dir))?.let { it in descriptors } == true }

        fun fire(event: Int, path: String) {
            val file = File(mirror, path)
            val deliver = synchronized(this) {
                identity(file.parentFile)?.let { descriptors[it] }?.let { receiver[it] }
            }
            deliver?.invoke(event, file.name)
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
        engine.onFilesKeptInApp = { _, count, _ -> notReached += count }
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
        engine.onFilesKeptInApp = { _, count, _ -> notReached += count }
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
        val kept = mutableListOf<Pair<Int, String>>()
        engine.onFilesKeptInApp = { _, count, first -> kept += count to first }
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
        assertEquals(listOf(1 to "g3.txt"), kept, "a file that stayed in the app was not told of by name")
        assertEquals(emptyList<Int>(), notReached, "a file kept in the app was told of as one the device refused")
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
        val told = mutableListOf<Pair<Int, String>>()
        engine.onFilesKeptInApp = { _, count, first -> told += count to first }
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
        assertEquals(listOf(1 to kept), told, "the copy kept inside the app was not told of by name")
        assertEquals(emptyList<Int>(), notReached, "the copy kept inside the app was told of as one the device refused")
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

        // The first rename gave way to the second, so the delete of `b` between them is of
        // what the device still holds as `a`, and is looked up there. Nothing here read
        // what `a` holds, so it is not deleted, as a delete that cannot name its document
        // never is, and it is told of, as such a delete is: the next open brings `a` into
        // the editor again. Where the open read it, it goes ([SafProviderClaimWaitTest]).
        assertEquals(setOf("a", "a/fa.txt", "b", "b/fc.txt"), device.keys)
        assertEquals(emptyList<String>(), lost)
        assertEquals(listOf("a"), refused, "the folder the device kept was not told of")
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
        clock = SafSyncEngine.HELD_BACK_RETRY_FIRST_MS
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
        engine.session.recentDrops += stale to engine.monotonicClock() - SafSyncEngine.RENAME_PAIR_WINDOW_MS - 1

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

    /** Waits up to five seconds for the write-back thread to leave the device folder as [done] says. */
    private fun waitFor(done: () -> Boolean) {
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
        while (!done() && System.nanoTime() < deadline) Thread.sleep(10)
    }

    /** `mv from to` of a folder in the mirror, as the kernel reports it to whoever watches the mirror's root. */
    private fun renameWatched(kernel: KernelWatches, from: String, to: String) {
        assertTrue(File(mirror, from).renameTo(File(mirror, to)))
        kernel.fire(FileObserver.MOVED_FROM or isDirFlag, from)
        kernel.fire(FileObserver.MOVED_TO or isDirFlag, to)
    }

    /**
     * A watch belongs to the folder, not to its name. The open's watch of a folder renamed while it ran was
     * handed over under the old name, which the watcher never asked for, and stopped as left over, which
     * removed the watch the watcher's own observer of the renamed folder shares: no save in it reached the
     * device for the rest of the session.
     */
    @Test
    fun `a folder renamed while its folder opens is still heard under its new name once the watcher starts`() {
        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } returns 0L
        onDevice("0.txt", "zero")
        onDevice("src")
        onDevice("src/a.txt", "alpha")
        onDevice("src/b.txt", "bravo")
        onDevice("src/sub")
        onDevice("src/sub/c.txt", "charlie")
        runBlocking { engine.initialSync(treeUri, mirror) { _, _ -> } }
        val kernel = KernelWatches().also { it.install() }
        var renamed = false
        openRecording { done, _ ->
            if (done == 4 && !renamed) {
                renamed = true
                renameWatched(kernel, "src", "lib")
            }
        }
        assertTrue(renamed, "precondition: the folder was renamed while it opened")
        kernel.owner = "watcher"

        engine.startWatching(mirror, treeUri)
        try {
            waitFor { text("lib/a.txt") == "alpha" }
            assertEquals("alpha", text("lib/a.txt"), "precondition: the renamed folder reached the device")
            inMirror("lib/a.txt", "typed after the watcher started")
            kernel.fire(FileObserver.MODIFY, "lib/a.txt")
            inMirror("lib/sub/c.txt", "typed below it")
            kernel.fire(FileObserver.MODIFY, "lib/sub/c.txt")
            waitFor { text("lib/a.txt") == "typed after the watcher started" && text("lib/sub/c.txt") == "typed below it" }
        } finally {
            engine.shutdown()
        }
        assertEquals("typed after the watcher started", text("lib/a.txt"), "the watcher did not hear the folder renamed while it opened")
        assertEquals("typed below it", text("lib/sub/c.txt"), "the watcher did not hear a folder inside the renamed one")
        assertEquals(
            setOf("0.txt", "lib", "lib/a.txt", "lib/b.txt", "lib/sub", "lib/sub/c.txt"), device.keys,
            "the renamed folder is on the device under both names",
        )
        assertEquals(emptyList<String>(), lost + refused)
    }

    /** An open of another folder that fails once it has started reading it, as a provider that gives up does. */
    private fun failedOpenOfAnotherFolder() {
        val other = File(filesDir, "other-mirror").apply { mkdirs() }
        assertThrows<java.io.IOException> {
            runBlocking {
                engine.initialSync(mockk(relaxed = true), other, recordChanges = true) { _, _ ->
                    throw java.io.IOException("the provider gave up")
                }
            }
        }
    }

    /**
     * Opening another folder stops the watcher of the folder already open, and puts it back when the other folder
     * does not open. What a program saved, deleted or renamed in the open folder meanwhile was never sent: the save
     * stayed inside the app until its next save, the deleted file and the folder's old name came back at the next
     * open, and the notice of the failed switch said the folder already open was unaffected.
     */
    @Test
    fun `what changes in the open folder while a switch to another fails is sent once its watcher is back`() {
        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } returns 0L
        onDevice("a.txt", "alpha")
        onDevice("b.txt", "bravo")
        onDevice("src")
        onDevice("src/c.txt", "charlie")
        runBlocking { engine.initialSync(treeUri, mirror) { _, _ -> } }
        val kernel = KernelWatches().also { it.install() }
        kernel.owner = "watcher"
        engine.startWatching(mirror, treeUri)
        try {
            engine.pauseWatching()
            kernel.owner = "switch"
            inMirror("a.txt", "saved while another folder opened")
            kernel.fire(FileObserver.MODIFY, "a.txt")
            assertTrue(File(mirror, "b.txt").delete())
            kernel.fire(FileObserver.DELETE, "b.txt")
            renameWatched(kernel, "src", "lib")
            inMirror("lib/c.txt", "saved in the renamed folder")
            kernel.fire(FileObserver.MODIFY, "lib/c.txt")
            failedOpenOfAnotherFolder()
            kernel.owner = "watcher"

            engine.startWatching(mirror, treeUri)
            waitFor {
                text("a.txt") == "saved while another folder opened" && "b.txt" !in device && "src" !in device &&
                    text("lib/c.txt") == "saved in the renamed folder"
            }
        } finally {
            engine.shutdown()
        }
        assertEquals("saved while another folder opened", text("a.txt"), "the save made meanwhile did not reach the device")
        assertEquals(
            setOf("a.txt", "lib", "lib/c.txt"), device.keys,
            "the file deleted or the folder renamed meanwhile is on the device as it was",
        )
        assertEquals("saved in the renamed folder", text("lib/c.txt"), "a save in the folder renamed meanwhile did not reach the device")
        assertEquals(emptyList<String>(), lost + refused)
    }

    /**
     * A program that deletes and makes again one file a thousand times while another folder opens. Each deletion is
     * recorded, and the replay of what the switch recorded asked the device folder about each, a lookup per path
     * segment and a scan of every listing it read, on the write-back thread while every save waited behind it, for a
     * file that was there again anyway.
     */
    @Test
    fun `a file deleted and made again many times while another folder opens is replayed without a lookup each time`() {
        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } returns 0L
        val count = 1000
        onDevice("src")
        onDevice("src/tmp.txt", "first")
        onDevice("src/gone.txt", "gone")
        runBlocking { engine.initialSync(treeUri, mirror) { _, _ -> } }
        val kernel = KernelWatches().also { it.install() }
        kernel.owner = "watcher"
        engine.startWatching(mirror, treeUri)
        val before: Int
        try {
            engine.pauseWatching()
            for (i in 1..count) {
                assertTrue(File(mirror, "src/tmp.txt").delete())
                kernel.fire(FileObserver.DELETE, "src/tmp.txt")
                inMirror("src/tmp.txt", "write $i")
                kernel.fire(FileObserver.CREATE, "src/tmp.txt")
                // And one made again and deleted each time, which ends deleted.
                if (i > 1) {
                    inMirror("src/gone.txt", "write $i")
                    kernel.fire(FileObserver.CREATE, "src/gone.txt")
                }
                assertTrue(File(mirror, "src/gone.txt").delete())
                kernel.fire(FileObserver.DELETE, "src/gone.txt")
            }
            failedOpenOfAnotherFolder()
            before = queries

            engine.startWatching(mirror, treeUri)
            waitFor { text("src/tmp.txt") == "write $count" && "src/gone.txt" !in device }
        } finally {
            engine.shutdown()
        }
        val asked = queries - before
        assertEquals("write $count", text("src/tmp.txt"), "the last write never reached the device")
        assertFalse("src/gone.txt" in device, "the deletion never reached the device")
        assertTrue(asked <= 50, "the replay asked the device folder $asked times for $count deletions of two files")
        assertEquals(emptyList<String>(), lost + refused)
    }

    /**
     * What a switch recorded, sent once the watcher of the folder already open is put back, can fail partway as what
     * an open recorded can. It is told as a change made while another folder was opening: the folder it names never
     * opened, and the notice of the failed switch had just said it was unaffected.
     */
    @Test
    fun `a replay of what a switch recorded that fails is told as one made while another folder opened`() {
        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } returns 0L
        onDevice("a.txt", "alpha")
        runBlocking { engine.initialSync(treeUri, mirror) { _, _ -> } }
        val told = mutableListOf<Pair<File, Boolean>>()
        engine.onOpenChangesUnsent = { dir, afterSwitch -> told += dir to afterSwitch }
        val kernel = KernelWatches().also { it.install() }
        kernel.owner = "watcher"
        engine.startWatching(mirror, treeUri)
        try {
            engine.pauseWatching()
            inMirror("a.txt", "saved while another folder opened")
            kernel.fire(FileObserver.MODIFY, "a.txt")
            failedOpenOfAnotherFolder()
            onProviderCall = {
                if (told.isEmpty() && Thread.currentThread().name == "saf-writeback") {
                    throw IllegalStateException("the provider went away")
                }
            }

            engine.startWatching(mirror, treeUri)
            waitFor { told.isNotEmpty() }
        } finally {
            onProviderCall = null
            engine.shutdown()
        }
        assertEquals(listOf(mirror to true), told, "the failed replay of what the switch recorded was not told once, as the switch's")
    }

    /**
     * A file deleted while another folder opened, where the device cannot be asked whether it holds the file when the
     * switch's record is sent. A lookup that failed read as the device not holding it: the deletion was dropped with
     * nothing said, and the file came back at the next open. It is not sent, and that is told once, as a replay that
     * failed is; the path is asked once in the pass, however often it was deleted.
     */
    @Test
    fun `a deletion a switch recorded whose lookup fails is not sent and is told once`() {
        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } returns 0L
        onDevice("a.txt", "alpha")
        onDevice("b.txt", "bravo")
        runBlocking { engine.initialSync(treeUri, mirror) { _, _ -> } }
        val told = mutableListOf<Pair<File, Boolean>>()
        engine.onOpenChangesUnsent = { dir, afterSwitch -> told += dir to afterSwitch }
        val kernel = KernelWatches().also { it.install() }
        kernel.owner = "watcher"
        engine.startWatching(mirror, treeUri)
        var asked = 0
        try {
            engine.pauseWatching()
            assertTrue(File(mirror, "b.txt").delete())
            kernel.fire(FileObserver.DELETE, "b.txt")
            inMirror("b.txt", "made again")
            kernel.fire(FileObserver.CREATE, "b.txt")
            assertTrue(File(mirror, "b.txt").delete())
            kernel.fire(FileObserver.DELETE, "b.txt")
            inMirror("a.txt", "saved while another folder opened")
            kernel.fire(FileObserver.MODIFY, "a.txt")
            failedOpenOfAnotherFolder()
            // Every query of the write-back thread fails, as a provider that went away answers; the save's own lookup
            // is not one of them, it goes through the cache.
            onProviderCall = {
                if (Thread.currentThread().name == "saf-writeback" && Throwable().stackTrace.any { it.methodName == "providerHolds" }) {
                    asked++
                    throw IllegalStateException("the provider went away")
                }
            }

            engine.startWatching(mirror, treeUri)
            waitFor { told.isNotEmpty() && text("a.txt") == "saved while another folder opened" }
        } finally {
            onProviderCall = null
            engine.shutdown()
        }
        assertEquals(listOf(mirror to true), told, "the deletion that could not be sent was not told once, as the switch's")
        assertEquals("bravo", text("b.txt"), "the deletion was sent though the device could not be asked")
        assertEquals("saved while another folder opened", text("a.txt"), "the rest of what the switch recorded was not sent")
        assertEquals(1, asked, "the device was asked about the deleted file $asked times in one pass")
        assertEquals(emptyList<String>(), lost + refused)
    }

    /**
     * The switch ended the open folder's watches before it began recording the folder, and walked the whole folder
     * in between: a save made then reached neither, so once a switch that failed put the watcher back it stayed
     * inside the app with nothing said. Each save is made at the moment the switch ends the watcher's watch of its
     * directory, where a switch does that, and otherwise once the switch has begun.
     */
    @Test
    fun `a save made as a switch to another folder takes the open folder's watch over is sent once its watcher is back`() {
        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } returns 0L
        onDevice("a.txt", "alpha")
        onDevice("src")
        onDevice("src/c.txt", "charlie")
        runBlocking { engine.initialSync(treeUri, mirror) { _, _ -> } }
        val kernel = KernelWatches().also { it.install() }
        kernel.owner = "watcher"
        engine.startWatching(mirror, treeUri)
        val saves = mutableMapOf("watcher:." to "a.txt", "watcher:src" to "src/c.txt")
        val save = { watch: String ->
            val path = saves.remove(watch)
            if (path != null) {
                inMirror(path, "saved as the switch began")
                kernel.fire(FileObserver.MODIFY, path)
            }
        }
        val before = kernel.log.size
        var duringSwitch = emptyList<String>()
        try {
            kernel.owner = "switch"
            kernel.onStopOf = save
            engine.pauseWatching()
            kernel.onStopOf = null
            duringSwitch = kernel.log.drop(before)
            saves.keys.toList().forEach(save)
            failedOpenOfAnotherFolder()
            kernel.owner = "watcher"

            engine.startWatching(mirror, treeUri)
            waitFor { text("a.txt") == "saved as the switch began" && text("src/c.txt") == "saved as the switch began" }
        } finally {
            kernel.onStopOf = null
            engine.shutdown()
        }
        assertEquals("saved as the switch began", text("a.txt"), "a save made as the switch began never reached the device")
        assertEquals("saved as the switch began", text("src/c.txt"), "a save below the folder's root never reached the device")
        assertEquals(emptyList<String>(), lost + refused)
        assertTrue(duringSwitch.none { it.startsWith("stop:") }, "the switch ended a watch of the folder it left: $duringSwitch")
        // The watches the switch kept go when the watcher put back stops: one left running is one only the collector
        // stops, and a FileObserver stops in its finalizer, taking the kernel's watch from whoever shares it.
        val (starts, stops) = kernel.log.partition { it.startsWith("start:") }
        assertEquals(starts.map { it.removePrefix("start:") }.sorted(), stops.map { it.removePrefix("stop:") }.sorted())
    }

    /**
     * The recording of a folder a switch left ends when another folder's watcher starts: the next open reads it. The
     * switch records through the watcher's own watches, so it is their end that ends the recording.
     */
    @Test
    fun `a folder a switch left is no longer recorded once another folder is watched`() {
        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } returns 0L
        onDevice("a.txt", "alpha")
        runBlocking { engine.initialSync(treeUri, mirror) { _, _ -> } }
        val kernel = KernelWatches().also { it.install() }
        kernel.owner = "watcher"
        engine.startWatching(mirror, treeUri)
        try {
            kernel.owner = "switch"
            engine.pauseWatching()
            assertTrue(
                kernel.watched(".") && "stop:watcher:." !in kernel.log,
                "precondition: the switch did not go on watching the folder it left: ${kernel.log}",
            )
            kernel.owner = "other"
            engine.startWatching(File(filesDir, "other-mirror").apply { mkdirs() }, mockk(relaxed = true))
            assertTrue("stop:watcher:." in kernel.log, "the recording of the folder a switch left outlived the switch: ${kernel.log}")
        } finally {
            engine.shutdown()
        }
    }

    /** A switch that fails on the folder itself puts no watcher back, and the recording of that folder ends there. */
    @Test
    fun `a folder a switch left is no longer recorded once its watcher is not put back`() {
        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } returns 0L
        onDevice("a.txt", "alpha")
        runBlocking { engine.initialSync(treeUri, mirror) { _, _ -> } }
        val kernel = KernelWatches().also { it.install() }
        kernel.owner = "watcher"
        engine.startWatching(mirror, treeUri)
        try {
            kernel.owner = "switch"
            engine.pauseWatching()
            assertTrue(
                kernel.watched(".") && "stop:watcher:." !in kernel.log,
                "precondition: the switch did not go on watching the folder it left: ${kernel.log}",
            )
            engine.dropPausedRecording()
            assertTrue("stop:watcher:." in kernel.log, "the recording of a folder whose watcher is not put back went on: ${kernel.log}")
        } finally {
            engine.shutdown()
        }
    }

    /**
     * The same folder made again at its old name: the new folder took the open's watch of the renamed one, and
     * stopping it with the new folder's watch left the renamed folder unheard. The rename is still one rename on
     * the device, beside the new folder.
     */
    @Test
    fun `a folder renamed and made again while its folder opens keeps both folders heard`() {
        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } returns 0L
        onDevice("0.txt", "zero")
        onDevice("src")
        onDevice("src/a.txt", "alpha")
        runBlocking { engine.initialSync(treeUri, mirror) { _, _ -> } }
        val kernel = KernelWatches().also { it.install() }
        openRecording { _, _ -> }
        renameWatched(kernel, "src", "lib")
        assertTrue(File(mirror, "src").mkdir())
        kernel.fire(FileObserver.CREATE or isDirFlag, "src")
        kernel.owner = "watcher"
        var afterReplay = emptySet<String>()

        engine.startWatching(mirror, treeUri)
        try {
            waitFor { text("lib/a.txt") == "alpha" && "src" in device && "src/a.txt" !in device }
            afterReplay = device.keys.toSet()
            val made = identityOf(File(mirror, "src"))
            assertTrue(File(mirror, "src").delete())
            kernel.unlinked(made)
            kernel.fire(FileObserver.DELETE or isDirFlag, "src")
            waitFor { "src" !in device }
            inMirror("lib/a.txt", "typed after the new folder went")
            kernel.fire(FileObserver.MODIFY, "lib/a.txt")
            waitFor { text("lib/a.txt") == "typed after the new folder went" }
        } finally {
            engine.shutdown()
        }
        assertEquals("typed after the new folder went", text("lib/a.txt"), "the renamed folder went unheard with the folder made at its old name")
        assertEquals(setOf("0.txt", "lib", "lib/a.txt", "src"), afterReplay, "the folder renamed while it opened is not one rename on the device")
        assertEquals(setOf("0.txt", "lib", "lib/a.txt"), device.keys)
    }

    /**
     * The open sets a folder aside as `<name>.local-<time>` when the device replaced it with a file. Its watch went
     * with the folder, was handed over under the old name, and stopped: a save in the set-aside folder never
     * reached the device.
     */
    @Test
    fun `a folder the open sets aside is heard once the watcher starts`() {
        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } returns 0L
        onDevice("keep.txt", "kilo")
        onDevice("x")
        onDevice("x/f.txt", "foxtrot")
        runBlocking { engine.initialSync(treeUri, mirror) { _, _ -> } }
        device.remove("x/f.txt")
        onDevice("x", "the device's file")
        val kernel = KernelWatches().also { it.install() }

        openRecording { _, _ -> }
        val kept = mirror.list()!!.single { it.startsWith("x" + SafSyncEngine.LOCAL_COPY_SUFFIX) }
        // What the kernel reports for the open's own move, which reaches the watch after the fact.
        kernel.fire(FileObserver.MOVED_FROM or isDirFlag, "x")
        kernel.fire(FileObserver.MOVED_TO or isDirFlag, kept)
        kernel.fire(FileObserver.MOVED_TO, "x")
        kernel.owner = "watcher"

        engine.startWatching(mirror, treeUri)
        try {
            waitFor { text("$kept/f.txt") == "foxtrot" }
            assertEquals("foxtrot", text("$kept/f.txt"), "precondition: the set-aside folder reached the device")
            inMirror("$kept/f.txt", "typed in the folder set aside")
            kernel.fire(FileObserver.MODIFY, "$kept/f.txt")
            waitFor { text("$kept/f.txt") == "typed in the folder set aside" }
        } finally {
            engine.shutdown()
        }
        assertEquals("typed in the folder set aside", text("$kept/f.txt"), "the watcher did not hear the folder the open set aside")
        assertEquals("the device's file", text("x"))
    }

    /**
     * A save in a folder renamed while its folder opens, made after the rename and before the watcher starts. The
     * open's watch built its path from the name the folder had when it was watched, and the save was recorded
     * under a path that no longer existed, so nothing sent it.
     */
    @Test
    fun `a save in a folder renamed while its folder opens reaches the device under the new name`() {
        onDevice("0.txt", "zero")
        onDevice("src")
        onDevice("src/a.txt", "alpha")
        onDevice("src/b.txt", "bravo")
        onDevice("src/sub")
        onDevice("src/sub/c.txt", "charlie")
        runBlocking { engine.initialSync(treeUri, mirror) { _, _ -> } }
        val kernel = KernelWatches().also { it.install() }
        var renamed = false
        openRecording { done, _ ->
            if (done == 4 && !renamed) {
                renamed = true
                renameWatched(kernel, "src", "lib")
            }
        }
        assertTrue(renamed, "precondition: the folder was renamed while it opened")
        inMirror("lib/a.txt", "saved in the renamed folder")
        kernel.fire(FileObserver.MODIFY, "lib/a.txt")
        inMirror("lib/sub/c.txt", "saved below it")
        kernel.fire(FileObserver.MODIFY, "lib/sub/c.txt")
        writes.clear()

        replayWhatTheOpenRecorded()
        drain()

        assertEquals("saved in the renamed folder", text("lib/a.txt"), "a save in the renamed folder did not reach the device")
        assertEquals("saved below it", text("lib/sub/c.txt"), "a save in a folder inside the renamed one did not reach the device")
        assertEquals(setOf("0.txt", "lib", "lib/a.txt", "lib/b.txt", "lib/sub", "lib/sub/c.txt"), device.keys)
        assertEquals(listOf("lib/a.txt", "lib/sub/c.txt"), writes.sorted(), "a file the rename carried unchanged was sent again")
        assertEquals(emptyList<String>(), lost + refused)
    }

    /**
     * A save made in a folder just before it is renamed, both while its folder opens. The save is recorded under
     * the old name, where nothing is to send any more, and the rename moves the device's copy, which is the one
     * before the save.
     */
    @Test
    fun `a save made just before its folder is renamed while its folder opens reaches the renamed folder`() {
        onDevice("0.txt", "zero")
        onDevice("src")
        onDevice("src/a.txt", "alpha")
        onDevice("src/b.txt", "bravo")
        runBlocking { engine.initialSync(treeUri, mirror) { _, _ -> } }
        val kernel = KernelWatches().also { it.install() }
        openRecording { _, _ -> }
        inMirror("src/a.txt", "saved before the rename")
        kernel.fire(FileObserver.MODIFY, "src/a.txt")
        renameWatched(kernel, "src", "lib")
        writes.clear()

        replayWhatTheOpenRecorded()
        drain()

        assertEquals("saved before the rename", text("lib/a.txt"), "the save made before the rename did not reach the renamed folder")
        assertEquals(setOf("0.txt", "lib", "lib/a.txt", "lib/b.txt"), device.keys)
        assertEquals(listOf("lib/a.txt"), writes, "a file the rename carried unchanged was sent again")
    }

    /**
     * A folder renamed before the open reached it. The open does not read it under either name, so its record
     * vouches for none of its files, and every one of them is sent again into the renamed folder. The open used
     * to send the folder under the new name itself, while the rename's other half kept the old one on the device.
     */
    @Test
    fun `a folder renamed before its open reaches it ends up under the new name only`() {
        onDevice("0.txt", "zero")
        onDevice("src")
        onDevice("src/a.txt", "alpha")
        onDevice("src/b.txt", "bravo")
        runBlocking { engine.initialSync(treeUri, mirror) { _, _ -> } }
        val watches = OpenWatches().also { it.install() }
        var renamed = false
        openRecording { done, _ ->
            if (done == 1 && !renamed) {
                renamed = true
                assertTrue(File(mirror, "src").renameTo(File(mirror, "lib")))
                watches.fire(FileObserver.MOVED_FROM or isDirFlag, "src")
                watches.fire(FileObserver.MOVED_TO or isDirFlag, "lib")
            }
        }
        assertTrue(renamed, "precondition: the folder was renamed before the open reached it")
        assertFalse(File(mirror, "src").exists(), "the open fetched back a folder renamed before it reached it")

        replayWhatTheOpenRecorded()
        drain()

        assertEquals(setOf("0.txt", "lib", "lib/a.txt", "lib/b.txt"), device.keys, "the renamed folder is on the device under both names")
        assertEquals("alpha", text("lib/a.txt"))
        assertEquals("bravo", text("lib/b.txt"))
        assertEquals(emptyList<String>(), lost + refused)
    }

    /**
     * A folder renamed onto an existing empty folder, as `mv -T`, `os.replace` or `fs.rename` do it: the folder
     * that is replaced reports nothing but the end of its watch, and the watcher kept its observer, which watches
     * the folder that left. Nothing watched the files directly inside the one that arrived.
     */
    @Test
    fun `a folder renamed onto an empty folder is watched under that name`() {
        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } returns 0L
        onDevice("a")
        onDevice("x")
        onDevice("x/f.txt", "foxtrot")
        runBlocking { engine.initialSync(treeUri, mirror) { _, _ -> } }
        val kernel = KernelWatches().also { it.install() }
        kernel.owner = "watcher"

        engine.startWatching(mirror, treeUri)
        try {
            val replaced = identityOf(File(mirror, "a"))
            assertTrue(File(mirror, "x").renameTo(File(mirror, "a")), "precondition: the rename replaced the empty folder")
            kernel.unlinked(replaced)
            kernel.fire(FileObserver.MOVED_FROM or isDirFlag, "x")
            kernel.fire(FileObserver.MOVED_TO or isDirFlag, "a")
            waitFor { text("a/f.txt") == "foxtrot" }
            assertEquals("foxtrot", text("a/f.txt"), "precondition: the folder that arrived reached the device")
            inMirror("a/f.txt", "typed in the folder that arrived")
            kernel.fire(FileObserver.MODIFY, "a/f.txt")
            waitFor { text("a/f.txt") == "typed in the folder that arrived" }
        } finally {
            engine.shutdown()
        }
        assertEquals("typed in the folder that arrived", text("a/f.txt"), "nothing watched the folder renamed onto an empty one")
    }

    /**
     * The open's own copies write an event for every 8 KiB they put in place, and a program can write as often.
     * Every event was kept until the folder opened, about 300 bytes each; a file written many times now keeps
     * its last write, which is the only one the replay sends, and the replay sends what it sent before.
     */
    @Test
    fun `a flood of writes while a folder opens is kept once per file and sent the same`() {
        val names = (0 until 10).map { "f$it.txt" }
        names.forEach { onDevice(it, "before") }
        onDevice("zz-gone.txt", "golf")
        runBlocking { engine.initialSync(treeUri, mirror) { _, _ -> } }
        val watches = OpenWatches().also { it.install() }
        var flooded = false
        openRecording { done, _ ->
            // Every file but the last read, so the deletion is made before the open reaches it.
            if (done == names.size && !flooded) {
                flooded = true
                names.forEach { inMirror(it, "after $it") }
                repeat(100_000) { watches.fire(FileObserver.MODIFY, names[it % names.size]) }
                // Written, deleted and written again: one save of what is there at the end.
                watches.fire(FileObserver.DELETE, names[0])
                watches.fire(FileObserver.CREATE, names[0])
                watches.fire(FileObserver.MODIFY, names[0])
                // Written and then deleted: one delete.
                watches.fire(FileObserver.MODIFY, "zz-gone.txt")
                assertTrue(File(mirror, "zz-gone.txt").delete())
                watches.fire(FileObserver.DELETE, "zz-gone.txt")
            }
        }
        assertTrue(flooded, "precondition: the files were written while the folder opened")
        val changes = checkNotNull(engine.takeChangesDuringOpen(mirror))
        assertTrue(changes.recorded().size <= names.size + 3, "the open kept ${changes.recorded().size} events for ${names.size + 1} files")
        writes.clear()

        engine.replayChangesDuringOpen(changes, mirror, treeUri)
        drain()

        names.forEach { assertEquals("after $it", text(it), "$it did not reach the device as last written") }
        assertEquals(names.sorted(), writes.sorted(), "a file was not sent exactly once")
        assertFalse("zz-gone.txt" in device.keys, "the file deleted while the folder opened is still on the device")
        assertEquals(emptyList<String>(), lost + refused)
    }

    /**
     * The watcher starts before the replay of what the open recorded has queued anything. A save in a folder made
     * while the folder opened, seen as the watcher started, was queued ahead of the folder's own create: the
     * device had no such folder yet, and the save was announced as kept inside the app, which the folder's create
     * then disproved by sending it.
     */
    @Test
    fun `a save in a folder made while its folder opens, seen as the watcher starts, is sent without a notice`() {
        onDevice("0.txt", "zero")
        runBlocking { engine.initialSync(treeUri, mirror) { _, _ -> } }
        val watches = OpenWatches().also { it.install() }
        openRecording { _, _ -> }
        inMirror("d/y.txt", "made while it opened")
        watches.fire(FileObserver.CREATE or isDirFlag, "d")
        var live = false
        onProviderCall = {
            if (!live) {
                live = true
                inMirror("d/z.txt", "saved as the watcher started")
                observe(FileObserver.MODIFY, "d/z.txt")
            }
        }

        replayWhatTheOpenRecorded()
        onProviderCall = null
        drain()

        assertTrue(live, "precondition: the save was seen while the replay ran")
        assertEquals("made while it opened", text("d/y.txt"))
        assertEquals("saved as the watcher started", text("d/z.txt"))
        assertEquals(emptyList<String>(), lost, "a save that reached the device was announced as kept inside the app")
    }

    /** The same for a folder renamed while it opened: queued ahead of the rename, the save missed it and stayed in the app. */
    @Test
    fun `a save in a folder renamed while its folder opens, seen as the watcher starts, reaches the renamed folder`() {
        onDevice("0.txt", "zero")
        onDevice("src")
        onDevice("src/a.txt", "alpha")
        runBlocking { engine.initialSync(treeUri, mirror) { _, _ -> } }
        val watches = OpenWatches().also { it.install() }
        openRecording { _, _ -> }
        assertTrue(File(mirror, "src").renameTo(File(mirror, "lib")))
        watches.fire(FileObserver.MOVED_FROM or isDirFlag, "src")
        watches.fire(FileObserver.MOVED_TO or isDirFlag, "lib")
        var live = false
        onProviderCall = {
            if (!live) {
                live = true
                inMirror("lib/a.txt", "saved as the watcher started")
                observe(FileObserver.MODIFY, "lib/a.txt")
            }
        }

        replayWhatTheOpenRecorded()
        onProviderCall = null
        drain()

        assertTrue(live, "precondition: the save was seen while the replay ran")
        assertEquals("saved as the watcher started", text("lib/a.txt"), "the save missed the rename of its folder")
        assertEquals(setOf("0.txt", "lib", "lib/a.txt"), device.keys)
        assertEquals(emptyList<String>(), lost)
    }

    /**
     * A folder moved out of the copy while its folder opens has the identity the open's watch read, and keeps it
     * when the move is replayed: without one, an unrelated folder arriving within the second was taken for its
     * rename, and the device's copy of the departed folder was renamed onto the arriving one's name.
     */
    @Test
    fun `a folder moved away while its folder opens is not taken for the rename of a folder arriving after it`() {
        onDevice("0.txt", "zero")
        onDevice("gone")
        onDevice("gone/g.txt", "golf")
        runBlocking { engine.initialSync(treeUri, mirror) { _, _ -> } }
        val watches = OpenWatches().also { it.install() }
        openRecording { _, _ -> }
        assertTrue(File(mirror, "gone").renameTo(File(filesDir, "elsewhere")))
        watches.fire(FileObserver.MOVED_FROM or isDirFlag, "gone")

        replayWhatTheOpenRecorded()
        File(filesDir, "incoming").mkdirs()
        File(filesDir, "incoming/fresh.txt").writeText("from outside")
        assertTrue(File(filesDir, "incoming").renameTo(File(mirror, "fresh")))
        observe(FileObserver.MOVED_TO or isDirFlag, "fresh")
        drain()

        assertEquals("from outside", text("fresh/fresh.txt"), "the folder that arrived was not sent")
        assertFalse("fresh/g.txt" in device.keys, "the departed folder was renamed onto the one that arrived")
        assertEquals("golf", text("gone/g.txt"))
    }

    /**
     * A folder renamed while its folder opens onto an empty folder the device also holds. The rename cannot be made
     * on the device, where the name is taken, so the folder's files are sent into the folder that holds the name, as
     * the watcher does for the same rename once the folder is open; the device keeps its copy under the old name.
     */
    @Test
    fun `a folder renamed onto an empty folder while its folder opens is sent into that folder`() {
        onDevice("0.txt", "zero")
        onDevice("a")
        onDevice("a/x.txt", "x-ray")
        onDevice("b")
        runBlocking { engine.initialSync(treeUri, mirror) { _, _ -> } }
        val notReached = mutableListOf<Int>()
        engine.onUploadIncomplete = { _, count, _ -> notReached += count }
        val watches = OpenWatches().also { it.install() }
        var renamed = false
        openRecording { done, _ ->
            if (done == 1 && !renamed) {
                renamed = true
                assertTrue(File(mirror, "a").renameTo(File(mirror, "b")), "precondition: the rename replaced the empty folder")
                watches.fire(FileObserver.MOVED_FROM or isDirFlag, "a")
                watches.fire(FileObserver.MOVED_TO or isDirFlag, "b")
            }
        }
        assertTrue(renamed, "precondition: the folder was renamed while it opened")

        replayWhatTheOpenRecorded()
        drain()

        assertEquals("x-ray", text("b/x.txt"), "the folder renamed onto an empty one did not reach it")
        assertEquals(emptyList<Int>(), notReached)
        assertEquals(emptyList<String>(), lost + refused)
    }

    /**
     * A file a program makes while its folder opens, in a folder the device gained since the last open. The open
     * makes that folder itself and has no watch inside it, so nothing records the file, and the open sends it as
     * any file only the copy holds.
     */
    @Test
    fun `a file made while its folder opens in a folder the device gained reaches the device`() {
        onDevice("0.txt", "zero")
        runBlocking { engine.initialSync(treeUri, mirror) { _, _ -> } }
        onDevice("d")
        onDevice("d/e.txt", "echo")
        val notReached = mutableListOf<Int>()
        engine.onUploadIncomplete = { _, count, _ -> notReached += count }
        val watches = OpenWatches().also { it.install() }
        var made = false
        openRecording { done, _ ->
            if (done == 2 && !made) {
                made = true
                // What the kernel reports for the open's own folder, and a file made in it that nothing watches.
                watches.fire(FileObserver.CREATE or isDirFlag, "d")
                inMirror("d/p.txt", "made in the new folder")
            }
        }
        assertTrue(made, "precondition: the file was made while the folder opened")

        replayWhatTheOpenRecorded()
        drain()

        assertEquals("made in the new folder", text("d/p.txt"))
        assertEquals(emptyList<Int>(), notReached)
        assertEquals(emptyList<String>(), lost + refused)
    }

    /**
     * A folder renamed onto an empty folder takes that folder's place in the record too. The folder it replaced kept
     * its place under the same name, and the next departure from that name was given its identity, which no folder
     * has any more and the kernel can give to the next one made. Here the arrival is reported only once the folder
     * has moved on, so the record cannot read who arrived, and only the folder replaced answered for the name.
     */
    @Test
    fun `a folder renamed onto an empty folder while its folder opens takes that folder's place in the record`() {
        onDevice("0.txt", "zero")
        onDevice("a")
        onDevice("x")
        onDevice("x/f.txt", "foxtrot")
        runBlocking { engine.initialSync(treeUri, mirror) { _, _ -> } }
        val kernel = KernelWatches().also { it.install() }
        openRecording { _, _ -> }
        val replaced = identityOf(File(mirror, "a"))
        assertTrue(File(mirror, "x").renameTo(File(mirror, "a")))
        kernel.unlinked(replaced)
        assertTrue(File(mirror, "a").renameTo(File(mirror, "c")))
        for ((event, name) in listOf(FileObserver.MOVED_FROM to "x", FileObserver.MOVED_TO to "a", FileObserver.MOVED_FROM to "a", FileObserver.MOVED_TO to "c")) {
            kernel.fire(event or isDirFlag, name)
        }

        val recorded = checkNotNull(engine.takeChangesDuringOpen(mirror)).recorded()
        val departure = recorded.last { it.event == (FileObserver.MOVED_FROM or isDirFlag) && it.file.name == "a" }
        assertFalse(departure.identity == replaced, "the folder that left a was recorded as the one it replaced")
    }

    /** What [mirror]'s open is recording, as the engine keeps it for the watcher that takes it. */
    private fun recordingOf(mirror: File): SafSyncEngine.ChangesDuringOpen {
        @Suppress("UNCHECKED_CAST")
        val kept = SafSyncEngine::class.java.getDeclaredField("changesDuringOpen")
            .apply { isAccessible = true }.get(engine) as Map<String, SafSyncEngine.ChangesDuringOpen>
        return checkNotNull(kept[mirror.absolutePath]) { "the open kept no record of its changes" }
    }

    /**
     * The watcher keeps what an open recorded for as long as the folder is watched: its write-back thread holds it,
     * and so does the observer of each directory that took a watch over from it. Every event of the open stayed in
     * memory with it, an entry for each 8 KiB the open put in place and each file a program made, after the replay
     * had sent them and nothing would read them again.
     */
    @Test
    fun `what an open recorded is let go once it has been sent`() {
        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } returns 0L
        onDevice("a.txt", "alpha")
        runBlocking { engine.initialSync(treeUri, mirror) { _, _ -> } }
        val kernel = KernelWatches().also { it.install() }
        openRecording { _, _ -> }
        val count = 200
        for (i in 1..count) {
            inMirror("new$i.txt", "made $i")
            kernel.fire(FileObserver.CREATE, "new$i.txt")
        }
        val recording = WeakReference(recordingOf(mirror))
        kernel.owner = "watcher"

        engine.startWatching(mirror, treeUri)
        try {
            waitFor { text("new$count.txt") == "made $count" }
            assertEquals("made $count", text("new$count.txt"), "precondition: the replay did not send what the open recorded")
            System.gc()
            val kept = checkNotNull(recording.get()) { "precondition: the watcher no longer holds what the open recorded" }
            assertEquals(emptyList<SafSyncEngine.ChangesDuringOpen.Recorded>(), kept.recorded(), "what was sent is still held")
            assertFalse(kept.placed(File(mirror, "new1.txt")), "what was sent is still held")
        } finally {
            engine.shutdown()
        }
    }

    /**
     * The replay of what an open recorded runs on the write-back thread before its loop. An exception in it ended
     * the thread there: the saves the watcher had held behind the replay were released into a queue nothing
     * polled, and so was every save after them, for the rest of the session, with nothing said.
     */
    @Test
    fun `a replay that fails still sends the saves made once the watcher starts, and says so once`() {
        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } returns 0L
        onDevice("a.txt", "alpha")
        onDevice("b.txt", "bravo")
        onDevice("c.txt", "charlie")
        runBlocking { engine.initialSync(treeUri, mirror) { _, _ -> } }
        val unsent = mutableListOf<File>()
        engine.onOpenChangesUnsent = { dir, _ -> unsent += dir }
        val kernel = KernelWatches().also { it.install() }
        var saved = false
        openRecording { done, _ ->
            if (done == 3 && !saved) {
                saved = true
                inMirror("a.txt", "typed while it opened")
                kernel.fire(FileObserver.MODIFY, "a.txt")
            }
        }
        assertTrue(saved, "precondition: a save was made while the folder opened")
        kernel.owner = "watcher"
        var failed = false
        onProviderCall = {
            if (!failed && Thread.currentThread().name == "saf-writeback") {
                failed = true
                // Seen while the replay runs, so held behind it.
                inMirror("b.txt", "saved as the watcher started")
                kernel.fire(FileObserver.MODIFY, "b.txt")
                throw IllegalStateException("the provider went away")
            }
        }

        engine.startWatching(mirror, treeUri)
        try {
            waitFor { failed }
            assertTrue(failed, "precondition: the replay failed")
            inMirror("c.txt", "saved after the replay failed")
            kernel.fire(FileObserver.MODIFY, "c.txt")
            waitFor { text("b.txt") == "saved as the watcher started" && text("c.txt") == "saved after the replay failed" }
        } finally {
            onProviderCall = null
            engine.shutdown()
        }
        assertEquals("saved as the watcher started", text("b.txt"), "a save held behind the failed replay was never sent")
        assertEquals("saved after the replay failed", text("c.txt"), "a save after the failed replay was never sent")
        assertEquals(listOf(mirror), unsent, "the failed replay was not said once")
    }

    /**
     * The watcher stops its watch of a folder at the folder's departure and starts the watch of its new name at the
     * arrival. What is made or saved in the folder between the rename on disk and that start raises no event, and
     * the rename moved only what the device already held: none of it reached the device for the session.
     */
    @Test
    fun `what is made or saved in a folder before the watch of its new name starts reaches the renamed folder`() {
        onDevice("src")
        onDevice("src/a.txt", "alpha")
        onDevice("src/b.txt", "bravo")
        runBlocking { engine.initialSync(treeUri, mirror) { _, _ -> } }
        assertTrue(File(mirror, "src").renameTo(File(mirror, "lib")))
        observe(FileObserver.MOVED_FROM or isDirFlag, "src")
        // No watch reports these: the folder's old one has gone and its new one has not started.
        inMirror("lib/a.txt", "saved in between")
        inMirror("lib/new.txt", "made in between")
        inMirror("lib/sub/deep.txt", "made in a folder made in between")
        observe(FileObserver.MOVED_TO or isDirFlag, "lib")
        writes.clear()

        drain()

        assertEquals(setOf("lib", "lib/a.txt", "lib/b.txt", "lib/new.txt", "lib/sub", "lib/sub/deep.txt"), device.keys)
        assertEquals("saved in between", text("lib/a.txt"))
        assertEquals("made in between", text("lib/new.txt"))
        assertEquals("made in a folder made in between", text("lib/sub/deep.txt"))
        assertEquals("bravo", text("lib/b.txt"))
        assertFalse("lib/b.txt" in writes, "a file the rename carried unchanged was sent again")
        assertEquals(emptyList<String>(), lost + refused)
    }

    /**
     * A file deleted while its folder opens, after the open had kept the copy it already held and before it had read
     * the rest. The digest a delete is checked against was taken only once the open had read every file, by opening
     * each kept copy, and a copy deleted by then gave none: the device kept the file and a notice said another app
     * had changed it.
     */
    @Test
    fun `a file deleted while its folder opens, after the open kept its copy, is deleted on the device`() {
        onDevice("a.txt", "alpha")
        onDevice("b.txt", "bravo")
        onDevice("c.txt", "charlie")
        runBlocking { engine.initialSync(treeUri, mirror) { _, _ -> } }
        val kept = mutableListOf<String>()
        engine.onKeptOnDevice = { file, _, changed -> kept += "${file.name}:$changed" }
        val watches = OpenWatches().also { it.install() }
        var deleted = false

        openRecording { done, _ ->
            if (done == 2 && !deleted) {
                deleted = true
                assertTrue(File(mirror, "b.txt").delete())
                watches.fire(FileObserver.DELETE, "b.txt")
            }
        }
        assertTrue(deleted, "precondition: the file was deleted after the open kept it")
        replayWhatTheOpenRecorded()
        drain()

        assertEquals(setOf("a.txt", "c.txt"), device.keys, "the deletion did not reach the device")
        assertEquals(emptyList<String>(), kept)
        assertEquals(emptyList<String>(), lost + refused)
    }

    /**
     * A file deleted in a folder renamed while its folder opens. The replay asked whether the device held the file
     * under the folder's new name before the rename it queues had reached the device, found nothing, and dropped the
     * delete: the rename carried the file and the next open brought it back.
     */
    @Test
    fun `a file deleted in a folder renamed while its folder opens is deleted on the device`() {
        onDevice("0.txt", "zero")
        onDevice("src")
        onDevice("src/a.txt", "alpha")
        onDevice("src/b.txt", "bravo")
        runBlocking { engine.initialSync(treeUri, mirror) { _, _ -> } }
        val kernel = KernelWatches().also { it.install() }
        openRecording { _, _ -> }
        renameWatched(kernel, "src", "lib")
        assertTrue(File(mirror, "lib/a.txt").delete())
        kernel.fire(FileObserver.DELETE, "lib/a.txt")

        replayWhatTheOpenRecorded()
        drain()

        assertEquals(setOf("0.txt", "lib", "lib/b.txt"), device.keys, "the file deleted in the renamed folder is still on the device")
        assertEquals(emptyList<String>(), lost + refused)
    }

    /**
     * The same delete seen live, in the poll that also brings the folder's rename. The delete looked its document up
     * as the event arrived, before the rename queued ahead of it had reached the device, and named nothing: the
     * device kept the file and a notice said it could not be deleted. Looked up when it runs, it is checked against
     * the bytes this engine read before the rename, as any delete.
     */
    @Test
    fun `a file deleted in a folder whose rename is still queued is deleted on the device`() {
        onDevice("src")
        onDevice("src/a.txt", "alpha")
        onDevice("src/b.txt", "bravo")
        runBlocking { engine.initialSync(treeUri, mirror) { _, _ -> } }

        // mv src lib && rm lib/a.txt, inside one poll.
        renameFolder("src", "lib")
        assertTrue(File(mirror, "lib/a.txt").delete())
        observe(FileObserver.DELETE, "lib/a.txt")
        drain()

        assertEquals(setOf("lib", "lib/b.txt"), device.keys, "the file deleted in the renamed folder is still on the device")
        assertEquals(emptyList<String>(), lost + refused)
    }

    /**
     * Two renames close together, the second inside the folder the first moved. The second looked for its source on
     * the device as its event arrived, before the first had reached the device, found none, and made the folder
     * again under its new name: the device kept it under both.
     */
    @Test
    fun `a folder renamed inside a folder whose rename is still queued ends up under its new name only`() {
        onDevice("a")
        onDevice("a/sub")
        onDevice("a/sub/x.txt", "x-ray")
        runBlocking { engine.initialSync(treeUri, mirror) { _, _ -> } }

        // mv a b && mv b/sub b/sub2, inside one poll.
        renameFolder("a", "b")
        renameFolder("b/sub", "b/sub2")
        drain()

        assertEquals(setOf("b", "b/sub2", "b/sub2/x.txt"), device.keys)
        assertEquals("x-ray", text("b/sub2/x.txt"))
        assertEquals(emptyList<String>(), lost + refused)
    }

    /** The same two renames while the folder opens, sent by the replay once the watcher starts. */
    @Test
    fun `a folder renamed inside a folder renamed while its folder opens ends up under its new name only`() {
        onDevice("0.txt", "zero")
        onDevice("a")
        onDevice("a/sub")
        onDevice("a/sub/x.txt", "x-ray")
        runBlocking { engine.initialSync(treeUri, mirror) { _, _ -> } }
        val kernel = KernelWatches().also { it.install() }
        openRecording { _, _ -> }
        renameWatched(kernel, "a", "b")
        renameWatched(kernel, "b/sub", "b/sub2")

        replayWhatTheOpenRecorded()
        drain()

        assertEquals(setOf("0.txt", "b", "b/sub2", "b/sub2/x.txt"), device.keys)
        assertEquals("x-ray", text("b/sub2/x.txt"))
        assertEquals(emptyList<String>(), lost + refused)
    }

    /** `mv a b; mv b c` inside one poll: the second rename's source is the first one's destination. */
    @Test
    fun `a folder renamed twice before the first rename is sent ends up under its last name only`() {
        onDevice("a")
        onDevice("a/x.txt", "x-ray")
        runBlocking { engine.initialSync(treeUri, mirror) { _, _ -> } }

        renameFolder("a", "b")
        renameFolder("b", "c")
        drain()

        assertEquals(setOf("c", "c/x.txt"), device.keys)
        assertEquals(emptyList<String>(), lost + refused)
    }

    /**
     * `mv src lib && rm -r lib` inside one poll, in a folder this engine has read. Neither delete could name a document
     * when it arrived; looked up when they run, after the rename, they are checked against what the open read.
     */
    @Test
    fun `a folder renamed and then removed with what it holds, before the rename is sent, is removed on the device`() {
        onDevice("0.txt", "zero")
        onDevice("src")
        onDevice("src/a.txt", "alpha")
        runBlocking { engine.initialSync(treeUri, mirror) { _, _ -> } }

        renameFolder("src", "lib")
        assertTrue(File(mirror, "lib").deleteRecursively())
        observe(FileObserver.DELETE, "lib/a.txt")
        observe(FileObserver.DELETE or isDirFlag, "lib")
        drain()

        assertEquals(setOf("0.txt"), device.keys)
        assertEquals(emptyList<String>(), lost + refused)
    }

    /**
     * The second rename seen while the first one's job is already running, before it has reached the device. The job
     * was off the queue then, so nothing said the second rename's source was on its way, and the folder was made again
     * under its last name beside the one the first rename carried.
     */
    @Test
    fun `a folder renamed again while its first rename is being sent ends up under its last name only`() {
        onDevice("a")
        onDevice("a/x.txt", "x-ray")
        runBlocking { engine.initialSync(treeUri, mirror) { _, _ -> } }
        renameFolder("a", "b")
        var movedAgain = false
        onRenameCall = {
            if (!movedAgain) {
                movedAgain = true
                renameFolder("b", "c")
            }
        }

        drain()
        onRenameCall = null

        assertTrue(movedAgain, "setup failed: the second rename was not seen while the first was being sent")
        assertEquals(setOf("c", "c/x.txt"), device.keys)
        assertEquals(emptyList<String>(), lost + refused)
    }

    /**
     * `mv a b; mv -T b/sub b/sub2` where the device's `a` also holds an empty `sub2`: the second rename's name is free
     * when its event arrives and taken once the first rename has run. Renamed there, a provider makes up a third name;
     * the folder's files are sent into the one the device holds instead, as for any rename onto a folder it holds.
     */
    @Test
    fun `a rename whose new name the device gains from a queued rename is sent into that folder`() {
        onDevice("a")
        onDevice("a/sub")
        onDevice("a/sub/x.txt", "x-ray")
        onDevice("a/sub2")
        runBlocking { engine.initialSync(treeUri, mirror) { _, _ -> } }

        renameFolder("a", "b")
        renameFolder("b/sub", "b/sub2")
        drain()

        assertEquals("x-ray", text("b/sub2/x.txt"), "the folder's files did not reach the folder holding its new name")
        assertFalse(device.keys.any { it.startsWith("b/sub2 (") }, "a third name was made up: ${device.keys}")
        assertEquals(emptyList<String>(), lost + refused)
    }

    /**
     * A folder moved out of the copy while its folder opens, as the Explorer's Delete moves it to the workbench's own
     * temporary folder, and a new folder made at its name. The departure is sent after the watcher has started and put
     * its own watch on the new folder, and the watch at that name was stopped for it: nothing in the new folder was
     * heard for the rest of the session, and every save in it stayed in the app with nothing said.
     */
    @Test
    fun `a folder moved away and made again while its folder opens is heard once the watcher starts`() {
        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } returns 0L
        onDevice("0.txt", "zero")
        onDevice("src")
        onDevice("src/a.txt", "alpha")
        runBlocking { engine.initialSync(treeUri, mirror) { _, _ -> } }
        val kernel = KernelWatches().also { it.install() }
        openRecording { _, _ -> }
        assertTrue(File(mirror, "src").renameTo(File(filesDir, "elsewhere")))
        kernel.fire(FileObserver.MOVED_FROM or isDirFlag, "src")
        assertTrue(File(mirror, "src").mkdir())
        kernel.fire(FileObserver.CREATE or isDirFlag, "src")
        kernel.owner = "watcher"

        engine.startWatching(mirror, treeUri)
        try {
            // Held behind the replay until it has run, so once this save is on the device the replay is over.
            inMirror("0.txt", "saved once the watcher started")
            kernel.fire(FileObserver.MODIFY, "0.txt")
            waitFor { text("0.txt") == "saved once the watcher started" }
            assertEquals("saved once the watcher started", text("0.txt"), "precondition: the replay ended")
            assertTrue(kernel.watched("src"), "the folder made at the old name went unwatched: ${kernel.log}")
            inMirror("src/new.txt", "made in the new folder")
            kernel.fire(FileObserver.CREATE, "src/new.txt")
            waitFor { text("src/new.txt") == "made in the new folder" }
        } finally {
            engine.shutdown()
        }
        assertEquals("made in the new folder", text("src/new.txt"), "a file made in the new folder never reached the device")
        assertEquals(emptyList<String>(), lost + refused)
        // The folder moved out stays on the device, and the next open brings what it held back into the new one.
        runBlocking { engine.initialSync(treeUri, mirror) { _, _ -> } }
        assertEquals("alpha", File(mirror, "src/a.txt").readText())
    }

    /**
     * The second shape of the same: `mv src lib; mkdir src` while the folder opens, where the device already holds
     * `lib`, so the rename is refused and the folder is sent into it whole. The departure of `src` stopped the watch
     * of the new `src` all the same, and nothing made in it reached the device for the rest of the session.
     */
    @Test
    fun `a folder moved onto a name the device holds and made again while its folder opens is heard once the watcher starts`() {
        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } returns 0L
        onDevice("0.txt", "zero")
        onDevice("src")
        onDevice("src/a.txt", "alpha")
        onDevice("lib")
        runBlocking { engine.initialSync(treeUri, mirror) { _, _ -> } }
        val kernel = KernelWatches().also { it.install() }
        openRecording { _, _ -> }
        renameWatched(kernel, "src", "lib")
        assertTrue(File(mirror, "src").mkdir())
        kernel.fire(FileObserver.CREATE or isDirFlag, "src")
        kernel.owner = "watcher"

        engine.startWatching(mirror, treeUri)
        try {
            // Held behind the replay until it has run, so once this save is on the device the replay is over.
            inMirror("0.txt", "saved once the watcher started")
            kernel.fire(FileObserver.MODIFY, "0.txt")
            waitFor { text("0.txt") == "saved once the watcher started" }
            assertEquals("saved once the watcher started", text("0.txt"), "precondition: the replay ended")
            assertTrue(kernel.watched("src"), "the folder made at the old name went unwatched: ${kernel.log}")
            inMirror("src/new.txt", "made in the new folder")
            kernel.fire(FileObserver.CREATE, "src/new.txt")
            waitFor { text("src/new.txt") == "made in the new folder" }
        } finally {
            engine.shutdown()
        }
        assertEquals("made in the new folder", text("src/new.txt"), "a file made in the new folder never reached the device")
        assertEquals("alpha", text("lib/a.txt"), "the folder moved onto the name the device holds was not sent into it")
        assertEquals(emptyList<String>(), lost + refused)
    }

    /** A replay that fails before it sends a folder's rename leaves the folder under its old name on the device, and says so. */
    @Test
    fun `a folder renamed while its folder opens keeps its old name on the device when sending fails, and that is said`() {
        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } returns 0L
        onDevice("src")
        onDevice("src/a.txt", "alpha")
        runBlocking { engine.initialSync(treeUri, mirror) { _, _ -> } }
        val unsent = mutableListOf<File>()
        val asSwitch = mutableListOf<Boolean>()
        engine.onOpenChangesUnsent = { dir, afterSwitch ->
            unsent += dir
            asSwitch += afterSwitch
        }
        val kernel = KernelWatches().also { it.install() }
        openRecording { _, _ -> }
        renameWatched(kernel, "src", "lib")
        kernel.owner = "watcher"
        onProviderCall = {
            if (unsent.isEmpty() && Thread.currentThread().name == "saf-writeback") {
                throw IllegalStateException("the provider went away")
            }
        }

        engine.startWatching(mirror, treeUri)
        try {
            waitFor { unsent.isNotEmpty() }
        } finally {
            onProviderCall = null
            engine.shutdown()
        }
        assertEquals(listOf(mirror), unsent, "the failed replay was not said once")
        assertEquals(listOf(false), asSwitch, "what the open recorded was told as what a switch recorded")
        assertEquals(setOf("src", "src/a.txt"), device.keys, "the folder renamed while it opened did not keep its old name")
    }

    /**
     * `mv src lib && rm -r lib` while the folder opens. Nothing stands under the new name when the watcher starts, so
     * the folder's arrival there was not sent, nor were the deletes under that name, which the device does not have:
     * the departure stayed held for a rename that never came, the device kept the folder, and the next open copied it
     * back. The same commands seen live are carried out.
     */
    @Test
    fun `a folder renamed and then removed with what it holds while its folder opens is removed on the device`() {
        onDevice("0.txt", "zero")
        onDevice("src")
        onDevice("src/a.txt", "alpha")
        runBlocking { engine.initialSync(treeUri, mirror) { _, _ -> } }
        val kernel = KernelWatches().also { it.install() }
        openRecording { _, _ -> }
        renameWatched(kernel, "src", "lib")
        val moved = identityOf(File(mirror, "lib"))
        assertTrue(File(mirror, "lib/a.txt").delete())
        kernel.fire(FileObserver.DELETE, "lib/a.txt")
        assertTrue(File(mirror, "lib").delete())
        kernel.unlinked(moved)
        kernel.fire(FileObserver.DELETE or isDirFlag, "lib")

        replayWhatTheOpenRecorded()
        drain()

        assertEquals(setOf("0.txt"), device.keys, "the folder renamed and removed while it opened is still on the device")
        assertEquals(emptyList<String>(), lost + refused)
    }

    /**
     * A folder renamed before its open reached it, so every file in it is sent again. Each was looked up on the
     * device under the folder's new name, which the device does not have until the rename runs: two provider walks a
     * file, misses that are not remembered, on the write-back thread while every live save waited behind them.
     */
    @Test
    fun `a folder renamed before its open reaches it is replayed without a provider lookup per file`() {
        val count = 40
        onDevice("0.txt", "zero")
        onDevice("src")
        for (i in 1..count) onDevice("src/f$i.txt", "file $i")
        runBlocking { engine.initialSync(treeUri, mirror) { _, _ -> } }
        val watches = OpenWatches().also { it.install() }
        var renamed = false
        openRecording { done, _ ->
            if (done == 1 && !renamed) {
                renamed = true
                assertTrue(File(mirror, "src").renameTo(File(mirror, "lib")))
                watches.fire(FileObserver.MOVED_FROM or isDirFlag, "src")
                watches.fire(FileObserver.MOVED_TO or isDirFlag, "lib")
            }
        }
        assertTrue(renamed, "precondition: the folder was renamed before the open reached it")

        val before = queries
        replayWhatTheOpenRecorded()
        val replaying = queries - before
        drain()
        val total = queries - before

        assertEquals((1..count).map { "file $it" }.toSet(), (1..count).map { text("lib/f$it.txt") }.toSet())
        assertEquals(setOf("0.txt", "lib") + (1..count).map { "lib/f$it.txt" }, device.keys)
        // A constant for the replay, whatever the folder holds; then what sending each file costs once the rename has
        // run, which the replay used to more than double.
        assertTrue(replaying <= 10, "the replay asked the device folder $replaying times for $count files")
        assertTrue(total <= 3 * count + 20, "sending $count files asked the device folder $total times, $replaying of them while replaying")
        assertEquals(emptyList<String>(), lost + refused)
    }

    /**
     * A folder that left while its folder opened, and one that arrived a minute later with the identity it had. The
     * kernel gives a removed folder's inode to the next folder made, so after the Explorer deletes a folder and the
     * trash is emptied, a new folder moved in can carry the old one's identity; the same folder moved back stands in for
     * it here. The replay paired the two however far apart they came and renamed the departed folder on the device onto
     * the new one's name. The watcher pairs halves at most a second apart, and the replay now does too.
     */
    @Test
    fun `a folder arriving long after one left with its identity while its folder opens is not taken for its rename`() {
        onDevice("0.txt", "zero")
        onDevice("src")
        onDevice("src/a.txt", "alpha")
        runBlocking { engine.initialSync(treeUri, mirror) { _, _ -> } }
        var clock = 1_000_000L
        engine.openEventClock = { clock }
        val kernel = KernelWatches().also { it.install() }
        openRecording { _, _ -> }
        val away = File(filesDir, "elsewhere")
        assertTrue(File(mirror, "src").renameTo(away))
        kernel.fire(FileObserver.MOVED_FROM or isDirFlag, "src")
        clock += 60_000
        File(away, "b.txt").writeText("made in the folder that arrived")
        assertTrue(away.renameTo(File(mirror, "build")))
        kernel.fire(FileObserver.MOVED_TO or isDirFlag, "build")

        replayWhatTheOpenRecorded()
        drain()

        assertEquals("alpha", text("src/a.txt"), "the folder that left was renamed onto one that arrived a minute later")
        assertEquals("made in the folder that arrived", text("build/b.txt"))
        assertEquals(emptyList<String>(), lost + refused)
    }

    /**
     * The same arrival, which the replay sends as a folder made whole by its create rather than as a rename. It then
     * sent each file the record does not name under the old name again behind that create, looking each up on the
     * device twice while every live save waited behind it, for a file the create had already made.
     */
    @Test
    fun `a folder arriving long after one left with its identity while its folder opens is sent by its create alone`() {
        onDevice("0.txt", "zero")
        onDevice("src")
        onDevice("src/a.txt", "alpha")
        runBlocking { engine.initialSync(treeUri, mirror) { _, _ -> } }
        var clock = 1_000_000L
        engine.openEventClock = { clock }
        val kernel = KernelWatches().also { it.install() }
        openRecording { _, _ -> }
        val away = File(filesDir, "elsewhere")
        assertTrue(File(mirror, "src").renameTo(away))
        kernel.fire(FileObserver.MOVED_FROM or isDirFlag, "src")
        clock += 60_000
        File(away, "b.txt").writeText("made in the folder that arrived")
        assertTrue(away.renameTo(File(mirror, "build")))
        kernel.fire(FileObserver.MOVED_TO or isDirFlag, "build")

        replayWhatTheOpenRecorded()
        val queued = engine.session.queue.map { it.localPath.removePrefix(mirror.path + File.separator) }
        drain()

        assertEquals(listOf("build"), queued, "a file of the folder made whole was sent again behind its create")
        assertEquals("made in the folder that arrived", text("build/b.txt"))
        assertEquals(listOf("build/a.txt", "build/b.txt"), writes.sorted(), "a file of the folder made whole was written twice")
        assertEquals(emptyList<String>(), lost + refused)
    }

    /**
     * A stop, a folder switch or a shutdown while the watcher is still sending what changed during the open. The replay
     * went on with the folder closed: each change it had not reached was announced as a save kept in the app, though
     * nothing had tried to send it. What it had not reached is left as a change made while the folder was closed.
     */
    @Test
    fun `a stop while the replay runs ends it without notices for what it never reached`() {
        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } returns 0L
        onDevice("a.txt", "alpha")
        onDevice("b.txt", "bravo")
        onDevice("c.txt", "charlie")
        runBlocking { engine.initialSync(treeUri, mirror) { _, _ -> } }
        val kept = mutableListOf<String>()
        engine.onFilesKeptInApp = { _, _, first -> kept += first }
        val kernel = KernelWatches().also { it.install() }
        openRecording { _, _ -> }
        inMirror("b.txt", "saved while it opened")
        kernel.fire(FileObserver.MODIFY, "b.txt")
        assertTrue(File(mirror, "a.txt").delete())
        kernel.fire(FileObserver.DELETE, "a.txt")
        inMirror("c.txt", "saved while it opened")
        kernel.fire(FileObserver.MODIFY, "c.txt")
        kernel.owner = "watcher"
        var stopper: Thread? = null
        onProviderCall = {
            if (stopper == null && Thread.currentThread().name == "saf-writeback") {
                val opening = engine.session
                stopper = thread { engine.stopWatching() }
                waitFor { engine.session !== opening }
            }
        }

        engine.startWatching(mirror, treeUri)
        try {
            waitFor { stopper != null }
            checkNotNull(stopper) { "precondition: the replay never reached the device folder" }.join(10_000)
        } finally {
            onProviderCall = null
            engine.shutdown()
        }
        assertEquals("saved while it opened", text("b.txt"), "precondition: the change the replay was on when the stop came was sent")
        assertEquals(emptyList<String>(), lost, "a change the stopped replay never reached was announced as kept in the app")
        assertEquals(emptyList<String>(), kept)
        assertEquals("charlie", text("c.txt"), "the replay went on sending after the stop")
        assertEquals("alpha", text("a.txt"), "the replay went on sending after the stop")

        // What it never reached is a change made while the folder was closed: the save goes at the next open, and the
        // file deleted comes back.
        runBlocking { engine.initialSync(treeUri, mirror) { _, _ -> } }
        assertEquals("saved while it opened", text("c.txt"))
        assertEquals("alpha", File(mirror, "a.txt").readText())
    }

    /**
     * A stop that takes the watcher's lock while the replay is between its check of the session and the watch a removal
     * changes. The replay then waited for that lock while the stop, holding it, waited for the replay's thread: every
     * such stop waited out its whole grace, and a folder switch took two seconds longer.
     */
    @Test
    fun `a stop while the replay removes a watch does not wait out its grace for the replay`() {
        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } returns 0L
        onDevice("a.txt", "alpha")
        onDevice("b.txt", "bravo")
        onDevice("c.txt", "charlie")
        runBlocking { engine.initialSync(treeUri, mirror) { _, _ -> } }
        val kernel = KernelWatches().also { it.install() }
        openRecording { _, _ -> }
        for (name in listOf("a.txt", "b.txt", "c.txt")) {
            assertTrue(File(mirror, name).delete())
            kernel.fire(FileObserver.DELETE, name)
        }
        kernel.owner = "watcher"
        val waitedOut = mutableListOf<String>()
        every { Logger.w(any(), any()) } answers {
            if ("still draining" in secondArg<String>()) waitedOut += secondArg<String>()
        }
        var replay: Thread? = null
        var stopper: Thread? = null
        val stopping = CountDownLatch(1)
        var held = false
        val threads = ManagementFactory.getThreadMXBean()
        // Inside the stop, which holds the watcher's locks here: let the replay run on until it waits for one of them.
        kernel.onStop = {
            val worker = replay
            if (!held && worker != null && Thread.currentThread() === stopper) {
                held = true
                stopping.countDown()
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (threads.getThreadInfo(worker.id)?.lockOwnerId != Thread.currentThread().id &&
                    System.nanoTime() < deadline
                ) Thread.sleep(1)
            }
        }
        onProviderCall = {
            if (replay == null && Thread.currentThread().name == "saf-writeback") {
                replay = Thread.currentThread()
                stopper = thread { engine.stopWatching() }
                stopping.await(5, TimeUnit.SECONDS)
            }
        }

        engine.startWatching(mirror, treeUri)
        try {
            waitFor { stopper != null }
            checkNotNull(stopper) { "precondition: the replay never reached the device folder" }.join(10_000)
        } finally {
            onProviderCall = null
            kernel.onStop = null
            engine.shutdown()
        }
        assertTrue(held, "precondition: the stop never ran while the replay did")
        assertEquals(emptyList<String>(), waitedOut, "the stop waited out its grace for the replay")
    }


    /**
     * The same stop while the replay adds the watch of a folder made while the folder opened, which the watcher's own
     * start did not find: the replay waited for the lock the stop held while the stop, holding it, waited for the
     * replay's thread, and a folder switch took two seconds longer.
     */
    @Test
    fun `a stop while the replay adds a watch does not wait out its grace for the replay`() {
        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } returns 0L
        onDevice("a.txt", "alpha")
        runBlocking { engine.initialSync(treeUri, mirror) { _, _ -> } }
        val kernel = KernelWatches().also { it.install() }
        openRecording { _, _ -> }
        inMirror("a.txt", "saved while it opened")
        kernel.fire(FileObserver.MODIFY, "a.txt")
        val made = File(mirror, "d")
        assertTrue(made.mkdir())
        kernel.fire(FileObserver.CREATE or isDirFlag, "d")
        // Gone when the watcher's start walks the folder, and back when the replay comes to it: not watched by then.
        assertTrue(made.delete())
        kernel.owner = "watcher"
        val waitedOut = mutableListOf<String>()
        every { Logger.w(any(), any()) } answers {
            if ("still draining" in secondArg<String>()) waitedOut += secondArg<String>()
        }
        var replay: Thread? = null
        var stopper: Thread? = null
        val stopping = CountDownLatch(1)
        var blocked = false
        val threads = ManagementFactory.getThreadMXBean()
        // Inside the stop, which holds the watcher's locks here: let the replay run on until it waits for one of them.
        kernel.onStop = {
            val worker = replay
            if (!blocked && worker != null && Thread.currentThread() === stopper) {
                stopping.countDown()
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (!blocked && System.nanoTime() < deadline) {
                    blocked = threads.getThreadInfo(worker.id)?.lockOwnerId == Thread.currentThread().id
                    if (!blocked) Thread.sleep(1)
                }
            }
        }
        onProviderCall = {
            if (replay == null && Thread.currentThread().name == "saf-writeback") {
                replay = Thread.currentThread()
                assertTrue(made.mkdir())
                stopper = thread { engine.stopWatching() }
                stopping.await(5, TimeUnit.SECONDS)
            }
        }

        engine.startWatching(mirror, treeUri)
        try {
            waitFor { stopper != null }
            checkNotNull(stopper) { "precondition: the replay never reached the device folder" }.join(10_000)
        } finally {
            onProviderCall = null
            kernel.onStop = null
            engine.shutdown()
        }
        assertTrue(blocked, "precondition: the replay never waited for a lock the stop held")
        assertEquals(emptyList<String>(), waitedOut, "the stop waited out its grace for the replay")
    }
    /**
     * Saves delivered over a record that cannot be read wait in memory for the next write of the record, and the next
     * open writes them into the record before it reads it. Where that write failed, as on a full disk, the open took
     * them out of memory and read the record without them: with the file edited again since, it read this app's own
     * delivered save as another app's edit and kept it beside the file, and the record it wrote once there was room
     * again did not hold them either.
     */
    @Test
    fun `a save delivered over an unreadable record and edited again is not kept as a device edit when the next open's first write fails`() {
        onDevice("notes.txt", "baseline")
        runBlocking { engine.initialSync(treeUri, mirror) { _, _ -> } }
        inMirror("notes.txt", "edited while closed")
        val record = File(mirror.path + SafSyncEngine.SYNCED_RECORD_SUFFIX)
        assertTrue(record.setReadable(false, false))
        try {
            assumeFalse(Files.isReadable(record.toPath()), "This account bypasses file read permissions")
            runBlocking { engine.initialSync(treeUri, mirror) { _, _ -> } }
        } finally {
            record.setReadable(true, true)
        }
        assertEquals("edited while closed", text("notes.txt"), "precondition: the open sent the save")
        val keptBefore = deviceCopies()
        inMirror("notes.txt", "edited again, never sent")
        val staging = File(record.path + SafSyncEngine.PARTIAL_SUFFIX).apply { assertTrue(mkdir()) }

        runBlocking { engine.initialSync(treeUri, mirror) { _, _ -> staging.delete() } }

        assertFalse(staging.exists(), "precondition: the open's own record could be written")
        assertEquals(keptBefore, deviceCopies(), "the open kept this app's own delivered save as if another app had written it")
        assertEquals("edited again, never sent", text("notes.txt"))
    }

    /**
     * An open that fails partway, after it had taken the saves still waiting for the record out of memory, as it does
     * over a record it cannot read. They were put back only by an open that got as far as its own record, so the next
     * open read this app's own delivered save as another app's edit.
     */
    @Test
    fun `a save delivered over an unreadable record is not kept as a device edit after an open that fails`() {
        // Listed first, so the open that fails does so before it reaches notes.txt, which it would send again.
        onDevice("a.txt", "alpha")
        onDevice("notes.txt", "baseline")
        runBlocking { engine.initialSync(treeUri, mirror) { _, _ -> } }
        inMirror("notes.txt", "edited while closed")
        val record = File(mirror.path + SafSyncEngine.SYNCED_RECORD_SUFFIX)
        assertTrue(record.setReadable(false, false))
        try {
            assumeFalse(Files.isReadable(record.toPath()), "This account bypasses file read permissions")
            runBlocking { engine.initialSync(treeUri, mirror) { _, _ -> } }
            assertEquals("edited while closed", text("notes.txt"), "precondition: the open sent the save")
            assertThrows<IllegalStateException> {
                runBlocking { engine.initialSync(treeUri, mirror) { _, _ -> throw IllegalStateException("the progress report failed") } }
            }
        } finally {
            record.setReadable(true, true)
        }
        val keptBefore = deviceCopies()
        inMirror("notes.txt", "edited again, never sent")

        runBlocking { engine.initialSync(treeUri, mirror) { _, _ -> } }

        assertEquals(keptBefore, deviceCopies(), "the open kept this app's own delivered save as if another app had written it")
        assertEquals("edited again, never sent", text("notes.txt"))
    }

    /**
     * The first open after an update from a build that could drop the row of a copy whose document the device deleted,
     * while a terminal renames the folder holding that copy. The open keeps such a copy inside the app and says so;
     * moved with the folder, the copy was left to the replay instead, which sent every file the record did not vouch for
     * under the folder's old name, and the deleted document came back on the device under the new one. A file the
     * device still holds is judged under its old name too, so the rename alone keeps nothing inside the app.
     */
    @Test
    fun `a copy an earlier build dropped the row of, in a folder renamed while its folder opens, stays inside the app`() {
        onDevice("0.txt", "zero")
        onDevice("docs")
        onDevice("docs/kept.txt", "kilo")
        onDevice("docs/stale.txt", "sierra")
        runBlocking { engine.initialSync(treeUri, mirror) { _, _ -> } }
        val record = File(mirror.path + SafSyncEngine.SYNCED_RECORD_SUFFIX)
        // The device deleted stale.txt, and the earlier build dropped its row and kept the copy.
        device.remove("docs/stale.txt")
        record.writeText(record.readLines().filterNot { it.startsWith("docs/stale.txt\t") }.joinToString("\n"))
        File(mirror.path + SafSyncEngine.SORTED_RECORD_SUFFIX).delete()
        for (name in listOf("docs/kept.txt", "docs/stale.txt")) {
            assertTrue(File(mirror, name).setLastModified(record.lastModified() - 60_000))
        }
        val kept = mutableListOf<Pair<Int, String>>()
        engine.onFilesKeptInApp = { _, count, first -> kept += count to first }
        val watches = OpenWatches().also { it.install() }
        var renamed = false
        openRecording { done, _ ->
            if (done == 1 && !renamed) {
                renamed = true
                assertTrue(File(mirror, "docs").renameTo(File(mirror, "docs2")))
                watches.fire(FileObserver.MOVED_FROM or isDirFlag, "docs")
                watches.fire(FileObserver.MOVED_TO or isDirFlag, "docs2")
            }
        }
        assertTrue(renamed, "precondition: the folder was renamed while it opened")

        replayWhatTheOpenRecorded()
        drain()

        assertEquals(setOf("0.txt", "docs2", "docs2/kept.txt"), device.keys, "a copy of a document the device deleted came back")
        assertEquals("kilo", text("docs2/kept.txt"))
        assertEquals(listOf(1 to "docs2/stale.txt"), kept, "the copy kept inside the app was not told of, or another was kept")
        assertEquals("sierra", File(mirror, "docs2/stale.txt").readText())
        assertEquals(emptyList<String>(), lost + refused)
    }

    /**
     * The same copy where the device no longer holds the folder at all: the rename has nothing to move, so the replay
     * makes the renamed folder whole on the device, and that create sent the copy the open had just said stays inside
     * the app, putting the document the device deleted back there.
     */
    @Test
    fun `a copy an earlier build dropped the row of, in a folder renamed while its folder opens, stays inside the app when the device lost the folder`() {
        onDevice("0.txt", "zero")
        onDevice("docs")
        onDevice("docs/stale.txt", "sierra")
        runBlocking { engine.initialSync(treeUri, mirror) { _, _ -> } }
        val record = File(mirror.path + SafSyncEngine.SYNCED_RECORD_SUFFIX)
        // The device deleted the folder, and the earlier build dropped the row of the copy and kept it.
        device.remove("docs/stale.txt")
        device.remove("docs")
        record.writeText(record.readLines().filterNot { it.startsWith("docs/stale.txt\t") }.joinToString("\n"))
        File(mirror.path + SafSyncEngine.SORTED_RECORD_SUFFIX).delete()
        assertTrue(File(mirror, "docs/stale.txt").setLastModified(record.lastModified() - 60_000))
        val kept = mutableListOf<Pair<Int, String>>()
        engine.onFilesKeptInApp = { _, count, first -> kept += count to first }
        val watches = OpenWatches().also { it.install() }
        var renamed = false
        openRecording { done, _ ->
            if (done == 1 && !renamed) {
                renamed = true
                assertTrue(File(mirror, "docs").renameTo(File(mirror, "docs2")))
                watches.fire(FileObserver.MOVED_FROM or isDirFlag, "docs")
                watches.fire(FileObserver.MOVED_TO or isDirFlag, "docs2")
            }
        }
        assertTrue(renamed, "precondition: the folder was renamed while it opened")
        assertEquals(listOf(1 to "docs2/stale.txt"), kept, "precondition: the open did not keep the copy inside the app")

        replayWhatTheOpenRecorded()
        drain()

        assertFalse("docs2/stale.txt" in device.keys, "a copy of a document the device deleted came back: ${device.keys}")
        assertEquals("sierra", File(mirror, "docs2/stale.txt").readText())
        assertEquals(emptyList<String>(), lost + refused)
    }

    /**
     * A folder that was empty on the device when first opened and is empty still, whose copy holds a file older than
     * its record, as an archive unpacked with its times keeps them, while a terminal renames the folder holding that
     * file. The open keeps such a file inside the app and says so, but the device has no folder of either name, so the
     * watcher makes the renamed folder whole once it starts and sent the file the open had said stays only in the app.
     */
    @Test
    fun `a file in a folder renamed while an empty device folder opens is not said to stay in the app when it is sent`() {
        runBlocking { engine.initialSync(treeUri, mirror) { _, _ -> } }
        val record = File(mirror.path + SafSyncEngine.SYNCED_RECORD_SUFFIX)
        assertEquals(listOf(SafSyncEngine.RECORD_HEADER), record.readLines(), "precondition: the first open left a record with no rows")
        inMirror("docs/old.txt", "unpacked")
        assertTrue(File(mirror, "docs/old.txt").setLastModified(record.lastModified() - 60_000))
        val kept = mutableListOf<String>()
        engine.onFilesKeptInApp = { _, _, first -> kept += first }
        val watches = OpenWatches().also { it.install() }
        var renamed = false
        onProviderCall = {
            if (!renamed && watches.watching.isNotEmpty()) {
                renamed = true
                assertTrue(File(mirror, "docs").renameTo(File(mirror, "docs2")))
                watches.fire(FileObserver.MOVED_FROM or isDirFlag, "docs")
                watches.fire(FileObserver.MOVED_TO or isDirFlag, "docs2")
            }
        }
        try {
            openRecording { _, _ -> }
        } finally {
            onProviderCall = null
        }
        assertTrue(renamed, "precondition: the folder was renamed while it opened")

        replayWhatTheOpenRecorded()
        drain()

        assertEquals("unpacked", text("docs2/old.txt"), "precondition: the watcher sent the renamed folder")
        assertEquals(emptyList<String>(), kept, "a file the watcher sent was said to stay only in the app")
        assertEquals(emptyList<String>(), lost + refused)
    }

    /**
     * The same rename made once the open has read the folder's other file: the open's record then names that file
     * under the folder's old name, and the rename moved those rows to the new name and dropped every row already
     * standing there, the row of the copy kept inside the app with them, so the copy was sent after the rename.
     */
    @Test
    fun `a copy an earlier build dropped the row of, in a folder renamed after the open read it, stays inside the app`() {
        onDevice("0.txt", "zero")
        onDevice("docs")
        onDevice("docs/kept.txt", "kilo")
        onDevice("docs/stale.txt", "sierra")
        runBlocking { engine.initialSync(treeUri, mirror) { _, _ -> } }
        val record = File(mirror.path + SafSyncEngine.SYNCED_RECORD_SUFFIX)
        device.remove("docs/stale.txt")
        record.writeText(record.readLines().filterNot { it.startsWith("docs/stale.txt\t") }.joinToString("\n"))
        File(mirror.path + SafSyncEngine.SORTED_RECORD_SUFFIX).delete()
        assertTrue(File(mirror, "docs/stale.txt").setLastModified(record.lastModified() - 60_000))
        val kept = mutableListOf<Pair<Int, String>>()
        engine.onFilesKeptInApp = { _, count, first -> kept += count to first }
        val watches = OpenWatches().also { it.install() }
        var renamed = false
        openRecording { done, _ ->
            if (done == 2 && !renamed) {
                renamed = true
                assertTrue(File(mirror, "docs").renameTo(File(mirror, "docs2")))
                watches.fire(FileObserver.MOVED_FROM or isDirFlag, "docs")
                watches.fire(FileObserver.MOVED_TO or isDirFlag, "docs2")
            }
        }
        assertTrue(renamed, "precondition: the folder was renamed while it opened")
        assertTrue(record.readLines().any { it.startsWith("docs/kept.txt\t") }, "precondition: the open read the folder before the rename")

        replayWhatTheOpenRecorded()
        drain()

        assertEquals(setOf("0.txt", "docs2", "docs2/kept.txt"), device.keys, "a copy of a document the device deleted came back")
        assertEquals(listOf(1 to "docs2/stale.txt"), kept, "the copy kept inside the app was not told of, or another was kept")
        assertTrue(record.readLines().any { it.startsWith("docs2/stale.txt\t") }, "the rename dropped the row of the copy kept inside the app")
        assertEquals("sierra", File(mirror, "docs2/stale.txt").readText())
        assertEquals(emptyList<String>(), lost + refused)
    }

    /**
     * The same rename made once the open has finished and kept the copy under the folder's old name. The renamed
     * folder is followed by each file whose copy the record does not name under that name, and a copy kept inside the
     * app is named only by a row without a digest, which that check did not count: the copy was sent after the rename.
     */
    @Test
    fun `a copy an earlier build dropped the row of, in a folder renamed once the open has finished, stays inside the app`() {
        onDevice("0.txt", "zero")
        onDevice("docs")
        onDevice("docs/kept.txt", "kilo")
        onDevice("docs/stale.txt", "sierra")
        runBlocking { engine.initialSync(treeUri, mirror) { _, _ -> } }
        val record = File(mirror.path + SafSyncEngine.SYNCED_RECORD_SUFFIX)
        device.remove("docs/stale.txt")
        record.writeText(record.readLines().filterNot { it.startsWith("docs/stale.txt\t") }.joinToString("\n"))
        File(mirror.path + SafSyncEngine.SORTED_RECORD_SUFFIX).delete()
        assertTrue(File(mirror, "docs/stale.txt").setLastModified(record.lastModified() - 60_000))
        val kept = mutableListOf<Pair<Int, String>>()
        engine.onFilesKeptInApp = { _, count, first -> kept += count to first }
        val watches = OpenWatches().also { it.install() }
        openRecording { _, _ -> }
        assertEquals(listOf(1 to "docs/stale.txt"), kept, "precondition: the open kept the copy inside the app")
        assertTrue(File(mirror, "docs").renameTo(File(mirror, "docs2")))
        watches.fire(FileObserver.MOVED_FROM or isDirFlag, "docs")
        watches.fire(FileObserver.MOVED_TO or isDirFlag, "docs2")

        replayWhatTheOpenRecorded()
        drain()

        assertEquals(setOf("0.txt", "docs2", "docs2/kept.txt"), device.keys, "a copy of a document the device deleted came back")
        assertTrue(record.readLines().any { it.startsWith("docs2/stale.txt\t") }, "the copy kept inside the app lost its row")
        assertEquals("sierra", File(mirror, "docs2/stale.txt").readText())
        assertEquals(emptyList<String>(), lost + refused)
    }

    /**
     * The same failed first write, where the device has since deleted the file this app had delivered. Read without
     * the save's receipt, the open found the copy changed from its row and kept it as work the device never got; the
     * row went, and the next open sent the file back to the device that had deleted it.
     */
    @Test
    fun `a save delivered over an unreadable record follows its deletion on the device when the next open's first write fails`() {
        onDevice("a.txt", "alpha")
        onDevice("notes.txt", "baseline")
        runBlocking { engine.initialSync(treeUri, mirror) { _, _ -> } }
        inMirror("notes.txt", "edited while closed")
        val record = File(mirror.path + SafSyncEngine.SYNCED_RECORD_SUFFIX)
        assertTrue(record.setReadable(false, false))
        try {
            assumeFalse(Files.isReadable(record.toPath()), "This account bypasses file read permissions")
            runBlocking { engine.initialSync(treeUri, mirror) { _, _ -> } }
        } finally {
            record.setReadable(true, true)
        }
        assertEquals("edited while closed", text("notes.txt"), "precondition: the open sent the save")
        device.remove("notes.txt")
        val staging = File(record.path + SafSyncEngine.PARTIAL_SUFFIX).apply { assertTrue(mkdir()) }

        runBlocking { engine.initialSync(treeUri, mirror) { _, _ -> staging.delete() } }

        assertFalse(staging.exists(), "precondition: the open's own record could be written")
        assertFalse(File(mirror, "notes.txt").exists(), "the copy of a file the device deleted was kept as work")
        assertTrue(record.readLines().none { it.startsWith("notes.txt\t") }, "a row was left for a file that is gone")
        runBlocking { engine.initialSync(treeUri, mirror) { _, _ -> } }
        assertFalse("notes.txt" in device.keys, "the next open sent the deleted file back")
    }
}
