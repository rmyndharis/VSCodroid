package com.vscodroid.storage

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
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
import io.mockk.spyk
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class SafPendingDirectoryRenameTest {
    @TempDir lateinit var scratch: File
    private lateinit var mirror: File
    private lateinit var filesDir: File
    private lateinit var engine: SafSyncEngine
    private lateinit var tree: Uri
    private val entries = linkedMapOf<String, Entry>()
    private val uris = mutableMapOf<String, Uri>()
    private val deleted = mutableListOf<String>()
    private val written = mutableListOf<String>()
    private val lost = mutableListOf<String>()
    private val kept = mutableListOf<String>()
    private lateinit var providerResolver: ContentResolver
    private var failWrites = false
    private var reusePathIds = false
    private var refuseNextChildCreate = false
    private var childCreateAttempts = 0
    private var failedChildWrites = 0
    private var rewriteNames = false
    private var changeDirectoryIds = false
    private var failRenames = false
    private val failedReads = mutableSetOf<String>()
    private val failedStampQueries = mutableSetOf<String>()
    private var failStoredNameQueries = false
    private var elapsed = 0L
    private val loadingDirectories = mutableSetOf<String>()
    private val refused = mutableListOf<String>()
    private val returnedRenameIds = mutableListOf<String>()
    private var nextId = 0
    private var clock = 1_700_000_000_000L
    private var beforeProviderReadClose: ((String, ByteArray) -> Unit)? = null
    private var afterProviderUpload: ((String) -> Unit)? = null

    private data class Entry(
        val id: String,
        var parent: String,
        var name: String,
        var bytes: ByteArray?,
        var modified: Long,
    )

    private fun uri(key: String): Uri = uris.getOrPut(key) {
        mockk<Uri>(relaxed = true).also { value -> every { value.toString() } returns key }
    }

    private fun id(uri: Uri) = uri.toString().removePrefix("doc:")

    private fun entry(path: String): Entry? {
        var parent = "root"
        var found: Entry? = null
        for (name in path.split('/')) {
            found = entries.values.firstOrNull { it.parent == parent && it.name == name } ?: return null
            parent = found.id
        }
        return found
    }

    private fun add(id: String, parent: String, name: String, text: String? = null) {
        entries[id] = Entry(id, parent, name, text?.toByteArray(), clock)
    }

    private fun nameIn(parent: String, requested: String, excluding: String? = null): String {
        val wanted = if (rewriteNames) requested.replace(':', '_') else requested
        var stored = wanted
        var n = 1
        while (entries.values.any { it.parent == parent && it.name == stored && it.id != excluding }) {
            stored = "$wanted (${n++})"
        }
        return stored
    }

    @BeforeEach fun setUp() {
        mirror = File(scratch, "mirror").apply { mkdirs() }
        filesDir = File(scratch, "files").apply { mkdirs() }
        mockkObject(Logger)
        every { Logger.i(any(), any()) } just Runs
        every { Logger.d(any(), any()) } just Runs
        every { Logger.w(any(), any()) } just Runs
        every { Logger.w(any(), any(), any()) } just Runs
        every { Logger.e(any(), any()) } just Runs
        every { Logger.e(any(), any(), any()) } just Runs
        mockkStatic(DocumentsContract::class)
        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } answers { elapsed }
        every { DocumentsContract.getTreeDocumentId(any()) } returns "root"
        every { DocumentsContract.getDocumentId(any()) } answers { id(firstArg()) }
        every { DocumentsContract.buildDocumentUriUsingTree(any(), any()) } answers {
            uri("doc:${secondArg<String>()}")
        }
        every { DocumentsContract.buildChildDocumentsUriUsingTree(any(), any()) } answers {
            uri("children:${secondArg<String>()}")
        }
        every { DocumentsContract.renameDocument(any(), any(), any()) } answers {
            if (failRenames) null else {
                val oldId = id(secondArg())
                val current = entries.getValue(oldId)
                current.name = nameIn(current.parent, thirdArg(), current.id)
                val renamed = if (changeDirectoryIds && current.bytes == null) {
                    val replacement = current.copy(id = "renamed-${++nextId}")
                    entries.remove(oldId)
                    entries.values.filter { it.parent == oldId }.forEach { it.parent = replacement.id }
                    entries[replacement.id] = replacement
                    replacement
                } else current
                returnedRenameIds += renamed.id
                uri("doc:${renamed.id}")
            }
        }
        every { DocumentsContract.moveDocument(any(), any(), any(), any()) } answers {
            val current = entries.getValue(id(secondArg()))
            check(current.parent == id(thirdArg()))
            current.parent = id(arg(3))
            uri("doc:${current.id}")
        }
        every { DocumentsContract.createDocument(any(), any(), any(), any()) } answers {
            if (arg<String>(3) == "new.txt") {
                childCreateAttempts++
                if (refuseNextChildCreate) {
                    refuseNextChildCreate = false
                    throw FileNotFoundException("temporary create refusal")
                }
            }
            val parent = id(secondArg())
            if (parent != "root" && (parent !in entries || entries.getValue(parent).bytes != null)) {
                throw FileNotFoundException("missing parent")
            }
            val stored = nameIn(parent, arg(3))
            val newId = if (reusePathIds) "$parent/$stored" else "created-${++nextId}"
            val bytes = if (thirdArg<String>() == DocumentsContract.Document.MIME_TYPE_DIR) null else ByteArray(0)
            entries[newId] = Entry(newId, parent, stored, bytes, ++clock)
            uri("doc:$newId")
        }
        every { DocumentsContract.deleteDocument(any(), any()) } answers {
            val target = id(secondArg())
            deleted += target
            val pending = ArrayDeque<String>()
            pending.add(target)
            while (pending.isNotEmpty()) {
                val current = pending.removeFirst()
                entries.values.filter { it.parent == current }.forEach { pending.add(it.id) }
                entries.remove(current)
            }
            true
        }
        val resolver = mockk<ContentResolver>(relaxed = true)
        providerResolver = resolver
        every { resolver.query(any(), any(), any(), any(), any()) } answers {
            val target = firstArg<Uri>().toString()
            val projection = secondArg<Array<String>?>()?.toList()
            if (!target.startsWith("children:") &&
                (projection == listOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                    DocumentsContract.Document.COLUMN_SIZE) && id(firstArg()) in failedStampQueries ||
                    projection == listOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME) && failStoredNameQueries)
            ) return@answers null
            val rows = if (target.startsWith("children:")) {
                entries.values.filter { it.parent == target.removePrefix("children:") }
            } else {
                listOfNotNull(entries[id(firstArg())])
            }
            cursor(rows, target.startsWith("children:") && target.removePrefix("children:") in loadingDirectories)
        }
        every { resolver.openInputStream(any()) } answers {
            if (id(firstArg()) in failedReads) throw FileNotFoundException("unreadable file")
            val documentId = id(firstArg())
            val bytes = entries[documentId]?.bytes?.copyOf() ?: throw FileNotFoundException("missing file")
            object : ByteArrayInputStream(bytes) {
                override fun close() {
                    beforeProviderReadClose?.invoke(documentId, bytes)
                    super.close()
                }
            }
        }
        every { resolver.openOutputStream(any(), any()) } answers {
            val current = entries[id(firstArg())] ?: throw FileNotFoundException("missing file")
            if (current.bytes == null) throw FileNotFoundException("directory")
            if (failWrites) throw FileNotFoundException("temporary output refusal")
            if (current.name == "new.txt" && failedChildWrites > 0) {
                failedChildWrites--
                throw FileNotFoundException("temporary output refusal")
            }
            written += current.id
            object : ByteArrayOutputStream() {
                override fun close() {
                    super.close()
                    current.bytes = toByteArray()
                    current.modified = ++clock
                    afterProviderUpload?.invoke(current.id)
                }
            }
        }
        val context = mockk<Context>(relaxed = true)
        every { context.contentResolver } returns resolver
        every { context.filesDir } returns filesDir
        tree = uri("tree")
        engine = SafSyncEngine(context)
        engine.usableSpaceOf = { Long.MAX_VALUE }
        engine.onWriteBackFailed = { lost += it.relativeTo(mirror).path }
        engine.onKeptOnDevice = { file, _, _ -> kept += file.relativeTo(mirror).path }
        engine.onDeleteRefused = { refused += it.relativeTo(mirror).path }
        add("directory-id", "root", "old")
        add("file-id", "directory-id", "f.txt", "baseline")
    }

    @AfterEach fun tearDown() = unmockkAll()

    private fun cursor(rows: List<Entry>, loading: Boolean = false): Cursor = mockk<Cursor>(relaxed = true).apply {
        var row = -1
        val status = if (loading) mockk<Bundle>(relaxed = true).also { bundle ->
            every { bundle.getBoolean(DocumentsContract.EXTRA_LOADING) } returns true
        } else null
        every { extras } returns status
        every { moveToNext() } answers { ++row < rows.size }
        every { moveToFirst() } answers { row = 0; rows.isNotEmpty() }
        every { getColumnIndex(any()) } answers { column(firstArg()) }
        every { getColumnIndexOrThrow(any()) } answers { column(firstArg()) }
        every { isNull(any<Int>()) } returns false
        every { getString(any()) } answers {
            val current = rows[row]
            when (firstArg<Int>()) {
                0 -> current.id
                1 -> current.name
                2 -> if (current.bytes == null) DocumentsContract.Document.MIME_TYPE_DIR else "text/plain"
                else -> error("not a text column")
            }
        }
        every { getLong(any()) } answers {
            if (firstArg<Int>() == 3) rows[row].bytes?.size?.toLong() ?: 0L else rows[row].modified
        }
    }

    private fun column(name: String): Int = when (name) {
        DocumentsContract.Document.COLUMN_DOCUMENT_ID -> 0
        DocumentsContract.Document.COLUMN_DISPLAY_NAME -> 1
        DocumentsContract.Document.COLUMN_MIME_TYPE -> 2
        DocumentsContract.Document.COLUMN_SIZE -> 3
        DocumentsContract.Document.COLUMN_LAST_MODIFIED -> 4
        else -> -1
    }

    private fun open() = runBlocking { engine.initialSync(tree, mirror) { _, _ -> } }
    private fun drain() = engine.runWriteBackLoop { false }
    private fun observe(event: Int, path: String) =
        engine.handleMirrorEvent(event, File(mirror, path), mirror, tree)
    private fun contents(path: String) = entry(path)?.bytes?.toString(Charsets.UTF_8)
    private fun local(path: String, text: String) =
        File(mirror, path).apply { parentFile!!.mkdirs(); writeText(text) }

    private fun renameDirectory(from: String, to: String) {
        val held = File(filesDir, "holding")
        assertTrue(File(mirror, from).renameTo(held))
        observe(FileObserver.MOVED_FROM or 0x40000000, from)
        observe(FileObserver.MOVED_TO or 0x40000000, to)
        assertTrue(held.renameTo(File(mirror, to)))
    }

    private fun foreignEdit() {
        entries.getValue("file-id").apply {
            bytes = "foreign version".toByteArray()
            modified = ++clock
        }
    }

    private fun incomingDirectory(path: String, event: Int = FileObserver.CREATE) {
        observe(event or 0x40000000, path)
        assertTrue(File(mirror, path).mkdir())
    }

    private fun pendingNestedTree(directoryEvent: Int, duplicateSaves: Boolean = false) {
        incomingDirectory("old/incoming", directoryEvent)
        incomingDirectory("old/incoming/deep", directoryEvent)
        local("old/incoming/deep/new.txt", "nested incoming bytes")
        observe(FileObserver.MOVED_TO, "old/incoming/deep/new.txt")
        if (duplicateSaves) {
            observe(FileObserver.CREATE, "old/incoming/deep/new.txt")
            observe(FileObserver.MODIFY, "old/incoming/deep/new.txt")
            observe(FileObserver.MODIFY, "old/incoming/deep/new.txt")
        }
    }

    private fun assertNestedArrives(directoryEvent: Int, duplicateSaves: Boolean) {
        open()
        pendingNestedTree(directoryEvent, duplicateSaves)
        renameDirectory("old", "new")
        drain()
        val afterDrain = contents("new/incoming/deep/new.txt")
        val writesAtDrain = written.toList()
        val lostAtDrain = lost.toList()
        open()
        assertAll(
            { assertEquals("nested incoming bytes", afterDrain, "the queued nested create never reached the provider") },
            { assertEquals(1, writesAtDrain.size, "one nested file must be written once") },
            { assertEquals(emptyList<String>(), lostAtDrain) },
            { assertEquals("nested incoming bytes", contents("new/incoming/deep/new.txt")) },
            { assertEquals("baseline", contents("new/f.txt")) },
        )
    }

    @Test fun `queued nested directory creates follow their ancestor rename`() =
        assertNestedArrives(FileObserver.CREATE, duplicateSaves = false)

    @Test fun `queued moved directories and duplicate child saves are delivered once`() =
        assertNestedArrives(FileObserver.MOVED_TO, duplicateSaves = true)

    @Test fun `a declined queued child deletion protects its renamed parent deletion`() {
        open()
        foreignEdit()
        assertTrue(File(mirror, "old/f.txt").delete())
        observe(FileObserver.DELETE, "old/f.txt")
        renameDirectory("old", "new")
        drain()
        assertTrue(File(mirror, "new").delete())
        observe(FileObserver.DELETE or 0x40000000, "new")
        drain()
        val providerAfterDeletes = contents("new/f.txt")
        val noticesAfterDeletes = kept.toList()
        val deletedAfterDeletes = deleted.toList()
        open()
        assertAll(
            { assertEquals("foreign version", providerAfterDeletes) },
            { assertEquals(emptyList<String>(), deletedAfterDeletes) },
            { assertTrue("new" in noticesAfterDeletes, "the kept child did not protect the new parent path") },
            { assertEquals("foreign version", File(mirror, "new/f.txt").takeIf { it.isFile }?.readText()) },
        )
    }

    @Test fun `a new name save queued behind its parent rename preserves a foreign edit`() {
        open()
        foreignEdit()
        renameDirectory("old", "new")
        local("new/f.txt", "local queued save")
        observe(FileObserver.MODIFY, "new/f.txt")
        drain()
        val copies = File(mirror, "new").listFiles()!!.filter { it.name.startsWith("f.txt.device-") }
        assertEquals("local queued save", contents("new/f.txt"))
        assertEquals(listOf("foreign version"), copies.map { it.readText() })
        assertEquals(emptyList<String>(), lost)
    }

    @Test fun `a fetched child under an incomplete listing stays writable after a queued rename`() {
        loadingDirectories += "directory-id"
        open()
        loadingDirectories.clear()
        foreignEdit()
        renameDirectory("old", "new")
        local("new/f.txt", "fetched child save")
        observe(FileObserver.MODIFY, "new/f.txt")
        drain()
        assertEquals("fetched child save", contents("new/f.txt"))
        val copies = File(mirror, "new").listFiles()!!.filter { it.name.startsWith("f.txt.device-") }
        assertEquals(listOf("foreign version"), copies.map { it.readText() })
        assertEquals(emptyList<String>(), lost)
    }

    @Test fun `an unread child deleted at its queued new name stays on the provider with a notice`() {
        add("unread-id", "directory-id", "unread.txt", "unread provider bytes")
        failedReads += "unread-id"
        open()
        renameDirectory("old", "new")
        val placeholder = local("new/unread.txt", "placeholder")
        assertTrue(placeholder.delete())
        observe(FileObserver.DELETE, "new/unread.txt")
        drain()
        assertEquals("unread provider bytes", contents("new/unread.txt"))
        assertTrue("new/unread.txt" in kept + refused, "the retained provider document was not announced")
        assertEquals(emptyList<String>(), deleted)
    }

    @Test fun `an unread child callback follows the new name after its provider rename drains`() {
        add("unread-id", "directory-id", "unread.txt", "unread provider bytes")
        failedReads += "unread-id"
        open()
        renameDirectory("old", "new")
        drain()
        createThenDelete("new/unread.txt")
        drain()
        assertEquals("unread provider bytes", contents("new/unread.txt"))
        assertTrue("new/unread.txt" in kept)
        assertEquals(emptyList<String>(), refused)
        assertEquals(emptyList<String>(), deleted)
    }

    private fun assertNormalizedPendingTree(changingId: Boolean) {
        open()
        pendingNestedTree(FileObserver.MOVED_TO, duplicateSaves = true)
        rewriteNames = true
        changeDirectoryIds = changingId
        renameDirectory("old", "a:b")
        drain()
        val beforeReopen = contents("a_b/incoming/deep/new.txt")
        val writesBeforeReopen = written.size
        val lostBeforeReopen = lost.toList()
        val rootCountBeforeReopen = entries.values.count { it.parent == "root" && it.bytes == null }
        open()
        open()
        assertAll(
            { assertEquals("nested incoming bytes", beforeReopen) },
            { assertEquals(1, writesBeforeReopen, "pending nested files must not be replayed twice") },
            { assertEquals(emptyList<String>(), lostBeforeReopen) },
            { assertEquals(1, rootCountBeforeReopen) },
            { assertEquals("nested incoming bytes", File(mirror, "a_b/incoming/deep/new.txt").takeIf { it.isFile }?.readText()) },
            { assertEquals(1, entries.values.count { it.parent == "root" && it.bytes == null }) },
            { if (changingId) assertFalse("directory-id" in entries, "the fixture must invalidate the old directory URI") },
        )
    }

    @Test fun `normalized renames carry pending directory and child creates`() =
        assertNormalizedPendingTree(changingId = false)

    @Test fun `normalized renames use the changed returned directory identity for pending children`() =
        assertNormalizedPendingTree(changingId = true)

    @Test fun `a changed directory identity supports child jobs queued at its new name`() {
        open()
        changeDirectoryIds = true
        renameDirectory("old", "new")
        local("new/after.txt", "new name bytes")
        observe(FileObserver.MOVED_TO, "new/after.txt")
        drain()
        assertFalse("directory-id" in entries)
        assertTrue(returnedRenameIds.single().startsWith("renamed-"))
        assertEquals("new name bytes", contents("new/after.txt"))
        assertEquals("baseline", contents("new/f.txt"))
        assertEquals(emptyList<String>(), lost)
    }

    private fun createThenDelete(path: String) {
        val file = local(path, "temporary local placeholder")
        observe(FileObserver.CREATE, path)
        assertTrue(file.delete())
        observe(FileObserver.DELETE, path)
    }

    @Test fun `a failed directory rename retains guards for the original provider path`() {
        open()
        foreignEdit()
        failRenames = true
        renameDirectory("old", "new")
        drain()
        assertEquals("foreign version", contents("old/f.txt"))
        assertEquals("baseline", contents("new/f.txt"))
        createThenDelete("old/f.txt")
        drain()
        assertEquals("foreign version", contents("old/f.txt"), "fallback creation must not strip the old provider guard")
        assertFalse("file-id" in deleted)
        assertTrue("old/f.txt" in kept)
    }

    @Test fun `a chained rename fallback retains guards at the provider path actually moved`() {
        add("unread-id", "directory-id", "unread.txt", "unread provider bytes")
        failedReads += "unread-id"
        open()
        renameDirectory("old", "middle")
        renameDirectory("middle", "final")
        drain()
        assertEquals("unread provider bytes", contents("middle/unread.txt"))
        assertEquals("baseline", contents("final/f.txt"))
        createThenDelete("middle/unread.txt")
        drain()
        assertEquals("unread provider bytes", contents("middle/unread.txt"), "the fallback moved an unread marker off its real provider path")
        assertFalse("unread-id" in deleted)
    }

    @Test fun `a superseded rename retains guards for its untouched provider directory`() {
        add("unread-id", "directory-id", "unread.txt", "unread provider bytes")
        add("other-directory", "root", "other")
        add("other-file", "other-directory", "other.txt", "other bytes")
        failedReads += "unread-id"
        open()
        renameDirectory("old", "destination")
        renameDirectory("destination", "archive")
        renameDirectory("other", "destination")
        val jobs = engine.session.queue.toList()
        engine.session.queue.clear()
        jobs.forEachIndexed { index, job -> engine.session.queue.offer(job.copy(timestamp = index.toLong())) }
        drain()
        assertEquals("unread provider bytes", contents("old/unread.txt"))
        assertEquals("other bytes", contents("destination/other.txt"))
        assertEquals("baseline", contents("archive/f.txt"))
        createThenDelete("old/unread.txt")
        drain()
        assertEquals("unread provider bytes", contents("old/unread.txt"), "a skipped rename must not strip the untouched provider guard")
        assertFalse("unread-id" in deleted)
    }

    @Test fun `a create deleted before drain never removes a newly appeared provider document`() {
        open()
        assertEquals(null, entry("notes.txt"))
        add("foreign-notes-id", "root", "notes.txt", "foreign notes never read")
        val file = local("notes.txt", "local notes deliberately removed")
        observe(FileObserver.MOVED_TO, "notes.txt")
        assertTrue(file.delete())
        observe(FileObserver.DELETE, "notes.txt")
        drain()
        val afterDrain = contents("notes.txt")
        val deletedAfterDrain = deleted.toList()
        val keptAfterDrain = kept.toList()
        open()
        assertAll(
            { assertEquals("foreign notes never read", afterDrain, "a missing baseline admitted deletion of an unseen foreign document") },
            { assertFalse("foreign-notes-id" in deletedAfterDrain) },
            { assertTrue("notes.txt" in keptAfterDrain, "the unseen foreign version was not announced as kept") },
            { assertEquals("foreign notes never read", File(mirror, "notes.txt").takeIf { it.isFile }?.readText()) },
        )
    }

    @Test fun `a new file delivered before its delete is still removed normally`() {
        open()
        val file = local("notes.txt", "new delivered notes")
        observe(FileObserver.CREATE, "notes.txt")
        drain()
        val deliveredId = entry("notes.txt")!!.id
        assertEquals("new delivered notes", contents("notes.txt"))
        assertTrue(file.delete())
        observe(FileObserver.DELETE, "notes.txt")
        drain()
        assertEquals(null, entry("notes.txt"))
        assertTrue(deliveredId in deleted)
        assertEquals(emptyList<String>(), kept)
    }

    @Test fun `a queued foreign edit delete remains declined without a parent rename`() {
        open()
        foreignEdit()
        assertTrue(File(mirror, "old/f.txt").delete())
        observe(FileObserver.DELETE, "old/f.txt")
        drain()
        assertEquals(emptyList<String>(), deleted)
        assertEquals("foreign version", contents("old/f.txt"))
        assertEquals(listOf("old/f.txt"), kept)
        open()
        assertEquals("foreign version", File(mirror, "old/f.txt").readText())
    }

    @Test fun `a queued foreign edit delete remains declined across its parent rename`() {
        open()
        foreignEdit()
        assertTrue(File(mirror, "old/f.txt").delete())
        observe(FileObserver.DELETE, "old/f.txt")
        renameDirectory("old", "new")
        drain()
        val afterDrain = contents("new/f.txt")
        val deletedAtDrain = deleted.toList()
        val keptAtDrain = kept.toList()
        open()
        assertAll(
            { assertEquals(emptyList<String>(), deletedAtDrain, "the queued delete removed a foreign version") },
            { assertEquals("foreign version", afterDrain, "the provider lost its only foreign copy") },
            { assertTrue(keptAtDrain.any { it.endsWith("/f.txt") }, "the declined deletion was not announced") },
            { assertEquals("foreign version", File(mirror, "new/f.txt").takeIf { it.isFile }?.readText()) },
        )
    }

    @Test fun `a queued unchanged file delete still succeeds across its parent rename`() {
        open()
        assertTrue(File(mirror, "old/f.txt").delete())
        observe(FileObserver.DELETE, "old/f.txt")
        renameDirectory("old", "new")
        drain()
        assertEquals(listOf("file-id"), deleted)
        assertEquals(null, entry("new/f.txt"))
        assertTrue(kept.isEmpty())
    }

    private fun assertPendingCreateFollowsRename(modify: Boolean) {
        open()
        local("old/new.txt", "completed incoming file")
        observe(FileObserver.MOVED_TO, "old/new.txt")
        if (modify) observe(FileObserver.MODIFY, "old/new.txt")
        renameDirectory("old", "new")
        drain()
        val afterDrain = contents("new/new.txt")
        val writesAtDrain = written.size
        val lostAtDrain = lost.toList()
        val receivedAtDrain = File(mirror.path + SafSyncEngine.SYNCED_RECORD_SUFFIX).readLines()
            .any { it.startsWith("new/new.txt\t") }
        open()
        assertAll(
            { assertEquals("completed incoming file", afterDrain, "the admitted create never reached the provider") },
            { assertEquals(1, writesAtDrain, "the pending create must be delivered once") },
            { assertEquals(emptyList<String>(), lostAtDrain, "the new provider child was treated as a missing old child") },
            { assertTrue(receivedAtDrain, "a successful delivery should retain a receipt") },
            { assertEquals("completed incoming file", contents("new/new.txt"), "the reopen lost the new copy") },
        )
    }

    @Test fun `an incoming file queued before its parent rename reaches the provider`() =
        assertPendingCreateFollowsRename(modify = false)

    @Test fun `an unknown save queued before its parent rename creates the provider child`() =
        assertPendingCreateFollowsRename(modify = true)

    @Test fun `an empty directory queued before its parent rename reaches the provider`() {
        open()
        observe(FileObserver.CREATE or 0x40000000, "old/empty")
        assertTrue(File(mirror, "old/empty").mkdir())
        renameDirectory("old", "new")
        drain()
        val createdAtDrain = entry("new/empty")
        open()
        assertTrue(createdAtDrain != null && createdAtDrain.bytes == null, "the pending directory create was skipped")
        assertEquals("baseline", contents("new/f.txt"))
    }

    @Test fun `a normalized directory rename keeps later saves on one provider subtree`() {
        open()
        rewriteNames = true
        renameDirectory("old", "a:b")
        drain()
        val requestedRemained = File(mirror, "a:b").exists()
        val storedFollowed = File(mirror, "a_b/f.txt").isFile
        val path = if (storedFollowed) "a_b/f.txt" else "a:b/f.txt"
        local(path, "saved after rename")
        observe(FileObserver.MODIFY, path)
        drain()
        val afterSave = contents("a_b/f.txt")
        val lostAfterSave = lost.toList()
        open()
        val directoriesAfterReopen = entries.values.filter { it.parent == "root" && it.bytes == null }.map { it.name }
        open()
        assertAll(
            { assertFalse(requestedRemained, "the mirror retained a provider-refused spelling") },
            { assertTrue(storedFollowed, "the mirror did not follow the returned display name") },
            { assertEquals("saved after rename", afterSave, "the later save could not resolve the stored parent") },
            { assertEquals(emptyList<String>(), lostAfterSave) },
            { assertEquals(listOf("a_b"), directoriesAfterReopen, "reopen created a duplicate directory") },
            { assertEquals("saved after rename", contents("a_b/f.txt")) },
            { assertEquals("saved after rename", File(mirror, "a_b/f.txt").readText()) },
        )
    }

    @Test fun `following a normalized directory name never replaces an occupied mirror directory`() {
        open()
        local("a_b/retained.txt", "unique local copy")
        rewriteNames = true
        renameDirectory("old", "a:b")
        drain()
        assertEquals("unique local copy", File(mirror, "a_b/retained.txt").readText())
        assertEquals("baseline", File(mirror, "a:b/f.txt").readText())
        assertEquals("baseline", contents("a_b/f.txt"))
        assertFalse(engine.holdsOnlyVouchedCopies(mirror), "unrecorded copies must remain protected")
    }

    @Test fun `a first unanswered stamp query cannot authorize deleting a foreign version`() {
        open()
        foreignEdit()
        failedStampQueries += "file-id"
        assertTrue(File(mirror, "old/f.txt").delete())
        observe(FileObserver.DELETE, "old/f.txt")
        drain()
        assertEquals("foreign version", contents("old/f.txt"))
        assertTrue(deleted.isEmpty())
        assertEquals(listOf("old/f.txt"), kept)
    }

    @Test fun `a same metadata foreign version cannot be deleted as an unchanged file`() {
        open()
        entries.getValue("file-id").bytes = "new data".toByteArray()
        assertTrue(File(mirror, "old/f.txt").delete())
        observe(FileObserver.DELETE, "old/f.txt")
        drain()
        assertEquals("new data", contents("old/f.txt"))
        assertTrue(deleted.isEmpty())
    }

    @Test fun `a provider read failure cannot authorize deletion using an unchanged stamp`() {
        open()
        failedReads += "file-id"
        assertTrue(File(mirror, "old/f.txt").delete())
        observe(FileObserver.DELETE, "old/f.txt")
        drain()
        assertEquals("baseline", contents("old/f.txt"))
        assertTrue(deleted.isEmpty())
    }

    @Test fun `an unchanged readable file can be deleted without a stamp answer`() {
        open()
        failedStampQueries += "file-id"
        assertTrue(File(mirror, "old/f.txt").delete())
        observe(FileObserver.DELETE, "old/f.txt")
        drain()
        assertEquals(null, entry("old/f.txt"))
        assertEquals(listOf("file-id"), deleted)
        assertTrue(kept.isEmpty())
    }

    @Test fun `a normalized rename carries child work already queued at its requested new name`() {
        open()
        rewriteNames = true
        renameDirectory("old", "a:b")
        local("a:b/after.txt", "queued new name bytes")
        observe(FileObserver.MOVED_TO, "a:b/after.txt")
        observe(FileObserver.MODIFY, "a:b/after.txt")
        drain()
        assertEquals("queued new name bytes", contents("a_b/after.txt"))
        assertEquals(1, written.size)
        assertTrue(lost.isEmpty())
    }

    @Test fun `a completed cross parent move follows its actual name when rename is refused`() {
        add("target-id", "root", "target")
        open()
        foreignEdit()
        failRenames = true
        local("old/incoming.txt", "pending incoming bytes")
        observe(FileObserver.MOVED_TO, "old/incoming.txt")
        renameDirectory("old", "target/new")
        drain()
        assertEquals("foreign version", contents("target/old/f.txt"))
        assertEquals("pending incoming bytes", contents("target/old/incoming.txt"))
        assertFalse(File(mirror, "target/new").exists())
        createThenDelete("target/old/f.txt")
        drain()
        assertEquals("foreign version", contents("target/old/f.txt"))
        assertFalse("file-id" in deleted)
        assertTrue(entry("target/new") == null)
    }

    @Test fun `a landed rename ends old URI retries even when its stored name cannot be read`() {
        loadingDirectories += "directory-id"
        open()
        foreignEdit()
        failedReads += "file-id"
        local("old/f.txt", "held local save")
        observe(FileObserver.MODIFY, "old/f.txt")
        drain()
        assertTrue(lost.isNotEmpty(), "the refused provider read must hold back the first save")
        assertTrue(written.isEmpty())

        failedReads.clear()
        failStoredNameQueries = true
        renameDirectory("old", "new")
        drain()
        local("old/f.txt", "unrelated new local file")
        elapsed = 100_000
        assertFalse(engine.retryHeldBack(WatchSession(mirror)))

        assertEquals("foreign version", contents("new/f.txt"))
        assertTrue(written.isEmpty(), "an old captured URI must never receive the recreated path's bytes")
    }

    @Test fun `normalization callbacks do not upload a renamed directory a second time`() {
        engine = spyk(engine, recordPrivateCalls = true)
        every { engine["watchTree"](any<File>(), any<File>(), any<Uri>()) } returns Unit
        open()
        rewriteNames = true
        local("old/new.txt", "pending incoming bytes")
        observe(FileObserver.MOVED_TO, "old/new.txt")
        renameDirectory("old", "a:b")
        drain()
        assertEquals(1, written.size)

        observe(FileObserver.MOVED_FROM or 0x40000000, "a:b")
        observe(FileObserver.MOVED_TO or 0x40000000, "a_b")
        drain()

        assertEquals("pending incoming bytes", contents("a_b/new.txt"))
        assertEquals("baseline", contents("a_b/f.txt"))
        assertEquals(1, written.size, "the internal name adoption must not resend the subtree")
    }

    @Test fun `a normalization callback cannot retire upload records before they move`() {
        engine = spyk(engine, recordPrivateCalls = true)
        every { engine["watchTree"](any<File>(), any<File>(), any<Uri>()) } returns Unit
        open()
        assertTrue(File(mirror, "old").renameTo(File(mirror, "a:b")))
        local("a:b/new.txt", "undelivered local bytes")
        entries.getValue("directory-id").name = "a_b"
        val pending = File(mirror, "a:b/new.txt").absolutePath
        File(filesDir, SafSyncEngine.UPLOADS_IN_FLIGHT_FILE).writeText(pending)
        val source = object : File(File(mirror, "a:b").path) {
            override fun renameTo(dest: File): Boolean {
                val moved = super.renameTo(dest)
                if (moved) {
                    observe(FileObserver.MOVED_FROM or 0x40000000, "a:b")
                    observe(FileObserver.MOVED_TO or 0x40000000, "a_b")
                }
                return moved
            }
        }
        SafSyncEngine::class.java.getDeclaredMethod(
            "mirrorNamedAsStored", Uri::class.java, File::class.java, String::class.java,
        ).apply { isAccessible = true }.invoke(engine, uri("doc:directory-id"), source, "a_b")

        assertEquals(setOf(File(mirror, "a_b/new.txt").absolutePath), engine.uploadsInFlight())
        assertEquals("undelivered local bytes", File(mirror, "a_b/new.txt").readText())
    }

    @Test fun `a closing delete cannot remove bytes uploaded by a current session`() {
        open()
        assertEquals("baseline", File(mirror, "old/f.txt").readText())
        engine.outboundTurnWaitMs = 30_000

        assertTrue(File(mirror, "old/f.txt").delete())
        observe(FileObserver.DELETE, "old/f.txt")
        val deletion = engine.session.queue.remove()
        val closing = WatchSession(mirror, deletion.generation).also { it.queue.offer(deletion) }

        assertTrue(deletion.generation != null)
        val restored = WatchSession(mirror, deletion.generation)

        val proofClosed = CountDownLatch(1)
        val releaseProof = CountDownLatch(1)
        val newerStarted = CountDownLatch(1)
        val newerUploaded = CountDownLatch(1)
        val proofReads = AtomicInteger()
        val proofTimedOut = AtomicBoolean(false)
        val proofBytes = AtomicReference<String?>(null)
        val deliveredId = AtomicReference<String?>(null)
        val failure = AtomicReference<Throwable?>(null)
        beforeProviderReadClose = { documentId, bytes ->
            if (documentId == "file-id" && Thread.currentThread().name == "closing-delete") {
                proofBytes.set(bytes.toString(Charsets.UTF_8))
                proofReads.incrementAndGet()
                proofClosed.countDown()
                if (!releaseProof.await(15, TimeUnit.SECONDS)) proofTimedOut.set(true)
            }
        }
        afterProviderUpload = { documentId ->
            if (Thread.currentThread().name == "restored-create") {
                deliveredId.set(documentId)
                newerUploaded.countDown()
            }
        }
        val oldDrain = Thread({
            try {
                engine.runWriteBackLoop(closing) { false }
            } catch (error: Throwable) {
                failure.compareAndSet(null, error)
            }
        }, "closing-delete").apply { isDaemon = true }
        val newDrain = Thread({
            newerStarted.countDown()
            try {
                engine.runWriteBackLoop(restored) { false }
            } catch (error: Throwable) {
                failure.compareAndSet(null, error)
            }
        }, "restored-create").apply { isDaemon = true }

        var uploadedDuringProof = false
        oldDrain.start()
        try {
            assertTrue(proofClosed.await(5, TimeUnit.SECONDS), "the delete never read its real baseline bytes")
            local("old/f.txt", "new uploaded bytes")
            observe(FileObserver.CREATE, "old/f.txt")
            val creation = engine.session.queue.remove()
            assertTrue(creation.generation === deletion.generation, "both sessions must use the current generation")
            restored.queue.offer(creation)
            newDrain.start()
            assertTrue(newerStarted.await(5, TimeUnit.SECONDS), "the restored session never started")
            uploadedDuringProof = newerUploaded.await(5, TimeUnit.SECONDS)
        } finally {
            releaseProof.countDown()
            oldDrain.join(5_000)
            newDrain.join(5_000)
        }
        assertFalse(oldDrain.isAlive || newDrain.isAlive, "both bounded drains must finish")
        assertFalse(proofTimedOut.get(), "the proof close barrier timed out")
        failure.get()?.let { throw AssertionError("a drain failed", it) }
        assertEquals("baseline", proofBytes.get(), "the proof must cover the version initialSync fetched")
        assertEquals(1, proofReads.get())
        assertTrue("file-id" in deleted, "the unchanged old provider version must be deleted")
        assertTrue(deliveredId.get() != null && deliveredId.get() in written,
            "the current session must complete a real provider upload")
        assertEquals("new uploaded bytes", contents("old/f.txt"),
            "the current upload was lost; it completed during the old proof: $uploadedDuringProof")
        assertEquals("new uploaded bytes", File(mirror, "old/f.txt").readText())
        assertTrue(lost.isEmpty(), "the current CREATE must resolve the document it can actually write")
    }

    @Test fun `a delete from a closing session cannot remove an already delivered replacement`() {
        open()
        assertTrue(File(mirror, "old/f.txt").delete())
        observe(FileObserver.DELETE, "old/f.txt")
        val deletion = engine.session.queue.remove()
        val closing = WatchSession(mirror, deletion.generation).also { it.queue.offer(deletion) }
        local("old/f.txt", "completed replacement")
        observe(FileObserver.CREATE, "old/f.txt")
        val creation = engine.session.queue.remove()
        assertTrue(creation.generation === deletion.generation)
        val restored = WatchSession(mirror, creation.generation).also { it.queue.offer(creation) }
        engine.runWriteBackLoop(restored) { false }
        assertEquals("completed replacement", contents("old/f.txt"))

        engine.runWriteBackLoop(closing) { false }
        val afterClosing = contents("old/f.txt")
        open()

        assertAll(
            { assertEquals("completed replacement", afterClosing) },
            { assertEquals("completed replacement", contents("old/f.txt")) },
            { assertEquals("completed replacement", File(mirror, "old/f.txt").takeIf { it.isFile }?.readText()) },
            { assertTrue(deleted.isEmpty(), "the older deletion must not consume the new delivery") },
        )
    }
    private fun prepareSlot(directory: Boolean, reuseIds: Boolean): String {
        engine = spyk(engine, recordPrivateCalls = true)
        every { engine["watchTree"](any<File>(), any<File>(), any<Uri>()) } returns Unit
        reusePathIds = reuseIds
        val documentId = if (reuseIds) "root/slot" else "initial-slot"
        add(documentId, "root", "slot", if (directory) null else "original slot bytes")
        open()
        assertTrue(if (directory) File(mirror, "slot").isDirectory else File(mirror, "slot").isFile)
        return documentId
    }

    private fun removeLocalSlot(directory: Boolean) {
        assertTrue(File(mirror, "slot").delete())
        observe(FileObserver.DELETE or if (directory) 0x40000000 else 0, "slot")
    }

    private fun createLocalSlot(directory: Boolean, payload: Boolean) {
        val slot = File(mirror, "slot")
        if (directory) {
            assertTrue(slot.mkdir())
            if (payload) File(slot, "new.txt").writeText("replacement bytes")
        } else {
            slot.writeText(if (payload) "replacement bytes" else "temporary local bytes")
        }
        observe(FileObserver.CREATE or if (directory) 0x40000000 else 0, "slot")
    }

    private fun assertDelivered(directory: Boolean) {
        val target = entry("slot")
        assertTrue(target != null, "the replacement must exist on the provider")
        assertEquals(directory, target?.bytes == null)
        val relative = if (directory) "slot/new.txt" else "slot"
        assertEquals("replacement bytes", contents(relative), "the replacement must have completed its real upload")
        assertTrue(File(mirror.path + SafSyncEngine.SYNCED_RECORD_SUFFIX).readLines()
            .any { it.startsWith("$relative\t") }, "a completed replacement must have a receipt")
        assertTrue(engine.uploadsInFlight().isEmpty(), "completed uploads must retire their journal lines")
    }

    private fun assertClosingDeleteKeepsReplacement(
        oldDirectory: Boolean,
        newDirectory: Boolean,
        reuseIds: Boolean,
    ) {
        val oldId = prepareSlot(oldDirectory, reuseIds)
        removeLocalSlot(oldDirectory)
        val deletion = engine.session.queue.remove()
        assertEquals(SyncType.DELETE, deletion.type)
        assertTrue(deletion.generation != null)
        val closing = WatchSession(mirror, deletion.generation).also { it.queue.offer(deletion) }
        assertTrue(engine.session.queue.isEmpty())

        // A second real unlink removes the old provider kind before the replacement is saved.
        createLocalSlot(oldDirectory, payload = false)
        removeLocalSlot(oldDirectory)
        drain()
        assertEquals(null, entry("slot"))
        assertEquals(listOf(oldId), deleted)
        assertTrue(engine.session.queue.isEmpty())

        createLocalSlot(newDirectory, payload = true)
        drain()
        assertDelivered(newDirectory)
        val replacementId = entry("slot")!!.id
        assertEquals(reuseIds, oldId == replacementId)
        val deletesBeforeClosing = deleted.toList()

        engine.runWriteBackLoop(closing) { false }
        val afterClosing = entry("slot")
        val payloadAfterClosing = contents(if (newDirectory) "slot/new.txt" else "slot")
        val deletesAfterClosing = deleted.toList()
        open()
        val localPayload = File(mirror, if (newDirectory) "slot/new.txt" else "slot")
        assertAll(
            { assertTrue(afterClosing != null, "the closing delete must leave the replacement on the provider") },
            { assertEquals("replacement bytes", payloadAfterClosing) },
            { assertEquals(deletesBeforeClosing, deletesAfterClosing, "the closing delete must not address the delivered replacement") },
            { assertEquals("replacement bytes", contents(if (newDirectory) "slot/new.txt" else "slot")) },
            { assertEquals("replacement bytes", localPayload.takeIf { it.isFile }?.readText(), "reopen must retain the delivered mirror copy") },
        )
    }

    private fun assertQueuedConversion(oldDirectory: Boolean) {
        val oldId = prepareSlot(oldDirectory, reuseIds = true)
        removeLocalSlot(oldDirectory)
        createLocalSlot(!oldDirectory, payload = true)
        assertEquals(listOf(SyncType.DELETE, SyncType.CREATE), engine.session.queue.map { it.type })
        drain()
        assertDelivered(!oldDirectory)
        assertEquals(listOf(oldId), deleted, "the one queued delete must remove the old provider kind")
        open()
        assertDelivered(!oldDirectory)
    }

    @Test fun `a closing directory delete keeps an already delivered file with a reused path identity`() =
        assertClosingDeleteKeepsReplacement(oldDirectory = true, newDirectory = false, reuseIds = true)

    @Test fun `a closing file delete keeps an already delivered directory with a reused path identity`() =
        assertClosingDeleteKeepsReplacement(oldDirectory = false, newDirectory = true, reuseIds = true)

    @Test fun `a closing directory delete keeps an already delivered file with a new identity`() =
        assertClosingDeleteKeepsReplacement(oldDirectory = true, newDirectory = false, reuseIds = false)

    @Test fun `a closing file delete keeps an already delivered directory with a new identity`() =
        assertClosingDeleteKeepsReplacement(oldDirectory = false, newDirectory = true, reuseIds = false)

    @Test fun `a closing file delete keeps an already delivered file with a reused identity`() =
        assertClosingDeleteKeepsReplacement(oldDirectory = false, newDirectory = false, reuseIds = true)

    @Test fun `a closing directory delete keeps an already delivered directory with a reused identity`() =
        assertClosingDeleteKeepsReplacement(oldDirectory = true, newDirectory = true, reuseIds = true)

    @Test fun `one queued directory delete permits a file to replace the old provider kind`() =
        assertQueuedConversion(oldDirectory = true)

    @Test fun `one queued file delete permits a directory to replace the old provider kind`() =
        assertQueuedConversion(oldDirectory = false)

    private fun assertForeignFileKept(reuseIds: Boolean) {
        reusePathIds = reuseIds
        val originalId = if (reuseIds) "root/slot" else "original-slot"
        add(originalId, "root", "slot")
        open()
        val localSlot = File(mirror, "slot")
        assertTrue(localSlot.isDirectory)
        assertTrue(localSlot.delete())
        engine.handleMirrorEvent(FileObserver.DELETE or 0x40000000, localSlot, mirror, tree)
        assertEquals(SyncType.DELETE, engine.session.queue.single().type)

        // An external client replaces the deleted directory through the provider APIs.
        assertTrue(DocumentsContract.deleteDocument(providerResolver, uri("doc:$originalId")))
        val foreign = DocumentsContract.createDocument(providerResolver, uri("doc:root"), "text/plain", "slot")!!
        providerResolver.openOutputStream(foreign, "wt")!!.use { it.write("foreign file bytes".toByteArray()) }
        val foreignId = id(foreign)
        assertEquals(reuseIds, foreignId == originalId)
        assertEquals("foreign file bytes", contents("slot"))
        assertFalse(localSlot.exists(), "no local recreation may cause this control to pass")
        assertFalse(File(mirror.path + SafSyncEngine.SYNCED_RECORD_SUFFIX).readLines()
            .any { it.startsWith("slot\t") }, "an external file must not acquire this engine's delivery receipt")
        val deletesBeforeDrain = deleted.size

        engine.runWriteBackLoop { false }
        val afterDrain = contents("slot")
        val deletesFromDrain = deleted.drop(deletesBeforeDrain)
        open()

        assertAll(
            { assertEquals("foreign file bytes", afterDrain, "the queued directory delete removed an external replacement file") },
            { assertFalse(foreignId in deletesFromDrain, "the old directory delete must not address the external file") },
            { assertEquals("foreign file bytes", contents("slot")) },
            { assertEquals("foreign file bytes", localSlot.takeIf { it.isFile }?.readText()) },
        )
    }

    @Test fun `a queued directory delete keeps an external file at a reused path identity`() =
        assertForeignFileKept(reuseIds = true)

    @Test fun `a queued directory delete keeps an external file with a new identity`() =
        assertForeignFileKept(reuseIds = false)

    private fun assertQueuedChildDelivered(renameParent: Boolean, refuseFirstCreate: Boolean) {
        engine = spyk(engine, recordPrivateCalls = true)
        every { engine["watchTree"](any<File>(), any<File>(), any<Uri>()) } returns Unit
        open()
        assertTrue(File(mirror, "old/incoming").mkdir())
        observe(FileObserver.CREATE or 0x40000000, "old/incoming")
        File(mirror, "old/incoming/new.txt").writeText("queued child bytes")
        observe(FileObserver.CREATE, "old/incoming/new.txt")
        if (renameParent) {
            val holding = File(filesDir, "holding")
            assertTrue(File(mirror, "old").renameTo(holding))
            observe(FileObserver.MOVED_FROM or 0x40000000, "old")
            observe(FileObserver.MOVED_TO or 0x40000000, "new")
            assertTrue(holding.renameTo(File(mirror, "new")))
        }
        refuseNextChildCreate = refuseFirstCreate
        drain()
        val parent = if (renameParent) "new" else "old"
        val afterDrain = contents("$parent/incoming/new.txt")
        val attemptsAfterDrain = childCreateAttempts
        val localAfterDrain = File(mirror, "$parent/incoming/new.txt").readText()
        open()
        assertAll(
            { assertEquals("queued child bytes", afterDrain, "the separately queued child create must survive a single temporary refusal") },
            { assertEquals(if (refuseFirstCreate) 2 else 1, attemptsAfterDrain) },
            { assertEquals("queued child bytes", localAfterDrain) },
            { assertEquals("queued child bytes", contents("$parent/incoming/new.txt")) },
        )
    }

    @Test fun `a queued child create retries a temporary subtree refusal after its parent rename`() =
        assertQueuedChildDelivered(renameParent = true, refuseFirstCreate = true)

    @Test fun `a queued child create retries a temporary subtree refusal without a parent rename`() =
        assertQueuedChildDelivered(renameParent = false, refuseFirstCreate = true)

    @Test fun `a successful subtree upload remains single after its parent rename`() =
        assertQueuedChildDelivered(renameParent = true, refuseFirstCreate = false)
    private fun assertMixedChildrenDelivered(failOutput: Boolean, nested: Boolean) {
        engine = spyk(engine, recordPrivateCalls = true)
        every { engine["watchTree"](any<File>(), any<File>(), any<Uri>()) } returns Unit
        open()
        val parent = if (nested) "old/incoming/nested" else "old/incoming"
        assertTrue(File(mirror, "old/incoming").mkdir())
        observe(FileObserver.CREATE or 0x40000000, "old/incoming")
        if (nested) {
            assertTrue(File(mirror, parent).mkdir())
            observe(FileObserver.CREATE or 0x40000000, parent)
        }
        File(mirror, "$parent/good.txt").writeText("first child bytes")
        observe(FileObserver.CREATE, "$parent/good.txt")
        File(mirror, "$parent/new.txt").writeText("retry child bytes")
        observe(FileObserver.CREATE, "$parent/new.txt")
        val holding = File(filesDir, "holding")
        assertTrue(File(mirror, "old").renameTo(holding))
        observe(FileObserver.MOVED_FROM or 0x40000000, "old")
        observe(FileObserver.MOVED_TO or 0x40000000, "new")
        assertTrue(holding.renameTo(File(mirror, "new")))
        if (failOutput) failedChildWrites = 2 else refuseNextChildCreate = true
        drain()
        val finalParent = parent.replaceFirst("old/", "new/")
        val good = entry("$finalParent/good.txt")!!
        assertAll(
            { assertEquals("first child bytes", contents("$finalParent/good.txt")) },
            { assertEquals(1, written.count { it == good.id }, "a delivered sibling must not be sent twice") },
            { assertEquals("retry child bytes", contents("$finalParent/new.txt")) },
            { assertTrue(engine.uploadsInFlight().isEmpty(), "the queued retry must finish its journal") },
        )
    }

    @Test fun `a renamed subtree retries only a refused child while keeping a delivered sibling single`() =
        assertMixedChildrenDelivered(failOutput = false, nested = false)

    @Test fun `a renamed nested subtree retries only a refused child while keeping a delivered sibling single`() =
        assertMixedChildrenDelivered(failOutput = false, nested = true)

    @Test fun `a renamed subtree retries an incomplete stream without repeating a delivered sibling`() =
        assertMixedChildrenDelivered(failOutput = true, nested = false)

    @Test fun `a closing directory delete preserves the journal for an incomplete replacement file`() {
        prepareSlot(directory = true, reuseIds = true)
        removeLocalSlot(directory = true)
        val deletion = engine.session.queue.remove()
        val closing = WatchSession(mirror, deletion.generation).also { it.queue.offer(deletion) }
        createLocalSlot(directory = true, payload = false)
        removeLocalSlot(directory = true)
        drain()
        assertEquals(null, entry("slot"))
        createLocalSlot(directory = false, payload = true)
        failWrites = true
        drain()
        val pendingPath = File(mirror, "slot").absolutePath
        assertTrue(pendingPath in engine.uploadsInFlight(), "the actual failed stream must retain its journal")
        val deletesBeforeClosing = deleted.toList()

        engine.runWriteBackLoop(closing) { false }

        assertAll(
            { assertTrue(pendingPath in engine.uploadsInFlight(), "the older delete must not retire replacement intent") },
            { assertEquals(deletesBeforeClosing, deleted) },
            { assertEquals("replacement bytes", File(mirror, "slot").readText()) },
        )
        failWrites = false
        open()
        assertEquals("replacement bytes", contents("slot"))
        assertTrue(engine.uploadsInFlight().isEmpty())
    }

}
