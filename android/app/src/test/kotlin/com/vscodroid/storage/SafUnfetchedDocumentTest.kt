package com.vscodroid.storage

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.os.FileObserver
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
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException

/**
 * What happens to a device document the sync could not read, when a local file
 * of that name appears afterwards.
 *
 * The sync leaves no trace of a document it skipped, so "absent from the mirror"
 * carries two meanings at once: not on the device, and on the device holding
 * content this app has never seen. Every write-back path used to resolve that
 * the destructive way, and by design rather than by accident: `createOneInSaf`
 * exists precisely to write into an existing document rather than fork it, so
 * the write opens the device's file with truncation. A 60 MB archive skipped for
 * size became whatever the user typed into a new file of that name, and nothing
 * on screen or in a release logcat said so, because the skip was logged at debug.
 *
 * A running watcher is required for any of this, so it does its damage in the
 * sessions the picker opened, which is the opposite population from the folders
 * the workbench opens for itself.
 */
class SafUnfetchedDocumentTest {

    @TempDir
    lateinit var mirror: File

    @TempDir
    lateinit var filesDir: File

    private lateinit var resolver: ContentResolver
    private lateinit var engine: SafSyncEngine
    private lateinit var treeUri: Uri
    private val uris = mutableMapOf<String, Uri>()

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
        // Needed by the control that uploads a genuinely new file: without it the
        // static answers null, no document exists to write into, and the case
        // would pass by doing nothing, which is the failure it is here to exclude.
        every { DocumentsContract.createDocument(any(), any(), any(), any()) } answers {
            uris.getOrPut("doc:" + lastArg<String>()) { mockk(relaxed = true) }
        }
        // Needed by the directory cases: a mocked static with no answer throws, and
        // `deleteFromSaf` swallows the throw, so a control asserting the delete went
        // through would be asserting on a call that never completed.
        every { DocumentsContract.deleteDocument(any(), any()) } returns true
        every { DocumentsContract.renameDocument(any(), any(), any()) } returns mockk(relaxed = true)

        resolver = mockk(relaxed = true)
        val context = mockk<Context>(relaxed = true)
        every { context.contentResolver } returns resolver
        every { context.filesDir } returns filesDir
        engine = SafSyncEngine(context)
        treeUri = mockk(relaxed = true)
    }

    @AfterEach
    fun tearDown() {
        File(mirror.path + SafSyncEngine.SYNCED_RECORD_SUFFIX).delete()
        unmockkAll()
    }

    /**
     * One file in the device folder, described through a fake cursor. [size]
     * decides whether the sync fetches it; [readable] decides whether the fetch
     * succeeds.
     */
    private fun deviceHolding(name: String, size: Long, readable: Boolean = true) {
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
        every { cursor.getString(0) } returns "doc:$name"
        every { cursor.getString(1) } returns name
        every { cursor.getString(2) } returns "text/plain"
        every { cursor.getLong(3) } returns size
        every { cursor.getLong(4) } returns 1_700_000_000_000
        every { resolver.query(any(), any(), any(), any(), any()) } answers {
            row = -1
            cursor
        }
        every { resolver.openInputStream(any()) } answers {
            if (readable) ByteArrayInputStream("device contents".toByteArray())
            else throw IOException("the provider refused the read")
        }
    }

    /** One event, and the write-back thread's pass over what it queued. */
    private fun deliver(event: Int, entry: String) {
        engine.handleMirrorEvent(event, File(mirror, entry), mirror, treeUri)
        engine.runWriteBackLoop { false }
    }

    /** inotify's "the entry this event is about is a directory", as the kernel sends it. */
    private val isDirFlag = 0x40000000

    /** One entry as the provider lists it. */
    private data class Entry(
        val name: String,
        val size: Long = 64,
        val isDirectory: Boolean = false,
    )

    /**
     * A device folder with structure, for the cases that turn on where a document is.
     *
     * [deviceHolding] answers the same one row under every parent, which cannot
     * describe a directory holding a file: the walk would find the file again inside
     * itself, for ever. [tree] maps a parent's document id to the entries directly
     * under it, with `root` as the tree's own. Every file reads as the same short
     * contents, and only ones under [SafSyncEngine.MAX_FILE_SIZE] are ever read.
     */
    private fun deviceTree(tree: Map<String, List<Entry>>) {
        every { DocumentsContract.buildChildDocumentsUriUsingTree(any(), any()) } answers {
            val parent = secondArg<String>()
            mockk<Uri>(relaxed = true).also { every { it.toString() } returns "children:$parent" }
        }
        every { resolver.query(any(), any(), any(), any(), any()) } answers {
            val parent = firstArg<Uri>().toString().removePrefix("children:")
            cursorOver(tree[parent].orEmpty())
        }
        every { resolver.openInputStream(any()) } answers {
            ByteArrayInputStream("device contents".toByteArray())
        }
    }

    private fun cursorOver(entries: List<Entry>): Cursor {
        var row = -1
        return mockk<Cursor>(relaxed = true) {
            every { moveToNext() } answers { ++row < entries.size }
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
            every { getString(0) } answers { "doc:${entries[row].name}" }
            every { getString(1) } answers { entries[row].name }
            every { getString(2) } answers {
                if (entries[row].isDirectory) DocumentsContract.Document.MIME_TYPE_DIR
                else "text/plain"
            }
            every { getLong(3) } answers { entries[row].size }
            every { getLong(4) } returns 1_700_000_000_000
        }
    }

    /** A `docs` directory whose only document is one the sync will not read. */
    private val docsHoldingUnread = mapOf(
        "root" to listOf(Entry("docs", isDirectory = true)),
        "doc:docs" to listOf(Entry("big.zip", size = SafSyncEngine.MAX_FILE_SIZE + 1)),
    )

    /**
     * Deleting a directory in the editor, as inotify reports it: the mirror entry is
     * already gone when the event arrives, and the flag is the only thing left that
     * says it was a directory.
     */
    private fun deleteDirectory(relativePath: String) {
        File(mirror, relativePath).deleteRecursively()
        deliver(FileObserver.DELETE or isDirFlag, relativePath)
    }

    /**
     * The same skip, one level up. The document is never in the mirror, so the editor
     * shows its directory as empty; the user deletes the directory; and the delete
     * that reaches the device is `deleteDocument` on a directory document, which takes
     * the archive with it. The file guard tests the directory's own path, which no
     * skip ever records, so nothing stood in the way.
     */
    @Test
    fun `a directory holding a document the sync never read is kept on the device`() {
        val kept = mutableListOf<String>()
        engine.onDirectoryKeptOnDevice = { kept.add(it.name) }
        deviceTree(docsHoldingUnread)
        runBlocking { engine.initialSync(treeUri, mirror) { _, _ -> } }

        deleteDirectory("docs")

        verify(exactly = 0) { DocumentsContract.deleteDocument(any(), any()) }
        assertEquals(
            listOf("docs"),
            kept,
            "the directory was kept, or deleted, without the user being told which",
        )
    }

    /**
     * The control that keeps the case above honest: a directory the sync read whole
     * is deleted on the device when it is deleted in the editor, and nothing is said.
     * A guard matching on anything other than a child of this directory fails here.
     */
    @Test
    fun `a directory the sync read whole is deleted from the device`() {
        val kept = mutableListOf<String>()
        engine.onDirectoryKeptOnDevice = { kept.add(it.name) }
        deviceTree(
            mapOf(
                "root" to listOf(Entry("docs", isDirectory = true)),
                "doc:docs" to listOf(Entry("notes.md")),
            )
        )
        runBlocking { engine.initialSync(treeUri, mirror) { _, _ -> } }

        deleteDirectory("docs")

        verify(exactly = 1) { DocumentsContract.deleteDocument(any(), uris.getValue("doc:docs")) }
        assertTrue(kept.isEmpty(), "a delete that went through was announced as kept: $kept")
    }

    /** The unread document sits two levels down; the ancestor is still what holds it. */
    @Test
    fun `a directory is kept for a document the sync never read at any depth`() {
        val kept = mutableListOf<String>()
        engine.onDirectoryKeptOnDevice = { kept.add(it.name) }
        deviceTree(
            mapOf(
                "root" to listOf(Entry("docs", isDirectory = true)),
                "doc:docs" to listOf(Entry("sub", isDirectory = true)),
                "doc:sub" to listOf(Entry("big.zip", size = SafSyncEngine.MAX_FILE_SIZE + 1)),
            )
        )
        runBlocking { engine.initialSync(treeUri, mirror) { _, _ -> } }

        deleteDirectory("docs")

        verify(exactly = 0) { DocumentsContract.deleteDocument(any(), any()) }
        assertEquals(listOf("docs"), kept, "a nested unread document did not keep its ancestor")
    }

    /**
     * `docs` is a prefix of `docs2` as a string and not as a path. A guard comparing
     * without the separator would keep `docs` for a document that lives under `docs2`,
     * and the user would be told their empty directory holds files.
     */
    @Test
    fun `a sibling whose name merely starts the same does not keep the directory`() {
        val kept = mutableListOf<String>()
        engine.onDirectoryKeptOnDevice = { kept.add(it.name) }
        deviceTree(
            mapOf(
                "root" to listOf(
                    Entry("docs", isDirectory = true),
                    Entry("docs2", isDirectory = true),
                ),
                "doc:docs" to listOf(Entry("notes.md")),
                "doc:docs2" to listOf(Entry("big.zip", size = SafSyncEngine.MAX_FILE_SIZE + 1)),
            )
        )
        runBlocking { engine.initialSync(treeUri, mirror) { _, _ -> } }

        deleteDirectory("docs")

        verify(exactly = 1) { DocumentsContract.deleteDocument(any(), uris.getValue("doc:docs")) }
        assertTrue(kept.isEmpty(), "docs was kept for a document under docs2: $kept")
    }

    /**
     * The set is a memory of what this sync could not read, not a fact about the
     * device. When the directory is no longer there, there is nothing to keep, and the
     * delete goes through exactly as it did before the guard existed.
     */
    @Test
    fun `a directory the device no longer holds is not kept`() {
        val kept = mutableListOf<String>()
        engine.onDirectoryKeptOnDevice = { kept.add(it.name) }
        deviceTree(docsHoldingUnread)
        runBlocking { engine.initialSync(treeUri, mirror) { _, _ -> } }

        // The directory, archive and all, was deleted on the device between the sync
        // and the event.
        deviceTree(mapOf("root" to emptyList()))
        deleteDirectory("docs")

        verify(exactly = 1) { DocumentsContract.deleteDocument(any(), uris.getValue("doc:docs")) }
        assertTrue(
            kept.isEmpty(),
            "a directory the device had already lost was announced as kept: $kept",
        )
    }

    /**
     * A rename arrives as a MOVED_FROM and a MOVED_TO, and the first half is a delete
     * to everything but the pairing. A directory with an unread document in it has to
     * keep renaming as one provider move, because that move is the one path that
     * carries the document to the new name; a guard that refused the MOVED_FROM would
     * leave the pair unclaimed, the new name built from a mirror that never held the
     * document, and the old name standing on the device with it.
     */
    @Test
    fun `renaming a directory that holds a document the sync never read still renames it`() {
        val kept = mutableListOf<String>()
        engine.onDirectoryKeptOnDevice = { kept.add(it.name) }
        deviceTree(docsHoldingUnread)
        runBlocking { engine.initialSync(treeUri, mirror) { _, _ -> } }

        engine.handleMirrorEvent(
            FileObserver.MOVED_FROM or isDirFlag, File(mirror, "docs"), mirror, treeUri
        )
        deliver(FileObserver.MOVED_TO or isDirFlag, "archive")

        verify(exactly = 1) {
            DocumentsContract.renameDocument(any(), uris.getValue("doc:docs"), "archive")
        }
        verify(exactly = 0) { DocumentsContract.deleteDocument(any(), any()) }
        assertTrue(kept.isEmpty(), "half of a rename was announced as a kept directory: $kept")
    }

    /**
     * A claimed rename moves the unread document to the new name on the device, so the
     * memory of it has to move too: kept at the old path, the guard stopped answering
     * for the directory the moment it was renamed, and `rm -r` on the new name sent the
     * archive to `deleteDocument`.
     */
    @Test
    fun `a renamed directory still keeps the document the sync never read`() {
        val kept = mutableListOf<String>()
        engine.onDirectoryKeptOnDevice = { kept.add(it.name) }
        deviceTree(docsHoldingUnread)
        runBlocking { engine.initialSync(treeUri, mirror) { _, _ -> } }
        engine.handleMirrorEvent(
            FileObserver.MOVED_FROM or isDirFlag, File(mirror, "docs"), mirror, treeUri
        )
        deliver(FileObserver.MOVED_TO or isDirFlag, "archive")
        // The device now lists the archive under its new name.
        deviceTree(
            mapOf(
                "root" to listOf(Entry("archive", isDirectory = true)),
                "doc:archive" to listOf(Entry("big.zip", size = SafSyncEngine.MAX_FILE_SIZE + 1)),
            )
        )

        deleteDirectory("archive")

        verify(exactly = 0) { DocumentsContract.deleteDocument(any(), any()) }
        assertEquals(listOf("archive"), kept, "the rename left the unread memory at the old name")
    }

    /**
     * A provider that cannot be asked is not a provider that said "gone". The delete is
     * `deleteDocument` on a subtree the set says holds an unread document, so an
     * unanswered lookup keeps; the cost of keeping wrongly is one stale directory.
     */
    @Test
    fun `a directory is kept when the provider cannot say whether it still holds it`() {
        val kept = mutableListOf<String>()
        engine.onDirectoryKeptOnDevice = { kept.add(it.name) }
        deviceTree(docsHoldingUnread)
        runBlocking { engine.initialSync(treeUri, mirror) { _, _ -> } }
        every { resolver.query(any(), any(), any(), any(), any()) } throws
            IllegalStateException("the provider is gone")

        deleteDirectory("docs")

        verify(exactly = 0) { DocumentsContract.deleteDocument(any(), any()) }
        assertEquals(listOf("docs"), kept, "an unanswerable provider was read as \"gone\"")
    }

    /**
     * The case the guard exists for. The document is past [SafSyncEngine.MAX_FILE_SIZE],
     * so the sync never reads it and the mirror never holds it. A local file of
     * that name then arrives, and the write opens the device's document with
     * truncation.
     */
    @Test
    fun `a document too large to mirror is not replaced by a local file of that name`() {
        deviceHolding("big.zip", size = SafSyncEngine.MAX_FILE_SIZE + 1)
        runBlocking { engine.initialSync(treeUri, mirror) { _, _ -> } }

        File(mirror, "big.zip").writeText("stub")
        deliver(FileObserver.CREATE, "big.zip")

        verify(exactly = 0) { resolver.openOutputStream(any(), "wt") }
    }

    /** The same shape when the copy was attempted and failed rather than skipped. */
    @Test
    fun `a document whose copy failed is not replaced either`() {
        deviceHolding("notes.md", size = 64, readable = false)
        runBlocking { engine.initialSync(treeUri, mirror) { _, _ -> } }

        File(mirror, "notes.md").writeText("stub")
        deliver(FileObserver.CREATE, "notes.md")

        verify(exactly = 0) { resolver.openOutputStream(any(), "wt") }
    }

    /**
     * The control, and it is the one that keeps the two above from passing for
     * the wrong reason. A document the sync did read is mirrored, so a later edit
     * to the mirror is an edit to something this app has a copy of, and writing
     * it back is the whole point of the feature.
     */
    @Test
    fun `a document the sync did read is still written back`() {
        deviceHolding("notes.md", size = 64)
        runBlocking { engine.initialSync(treeUri, mirror) { _, _ -> } }

        File(mirror, "notes.md").writeText("edited in the editor")
        deliver(FileObserver.MODIFY, "notes.md")

        verify(atLeast = 1) { resolver.openOutputStream(any(), "wt") }
    }

    /**
     * The set is a memory of what this sync could not read, not a permanent
     * refusal. If the device no longer holds a document at that name, the local
     * file is an ordinary new file and belongs on the device.
     */
    @Test
    fun `a name the device no longer holds is uploaded normally`() {
        deviceHolding("big.zip", size = SafSyncEngine.MAX_FILE_SIZE + 1)
        runBlocking { engine.initialSync(treeUri, mirror) { _, _ -> } }

        // The device folder is now empty: the oversized document was deleted
        // there between the sync and the event.
        val empty = mockk<Cursor>(relaxed = true)
        every { empty.moveToNext() } returns false
        every { resolver.query(any(), any(), any(), any(), any()) } returns empty

        File(mirror, "big.zip").writeText("a new file that happens to share the name")
        deliver(FileObserver.CREATE, "big.zip")

        // A name the device does not hold is created and then written, so the
        // write happening at all is what separates this from the guarded cases.
        verify(atLeast = 1) { resolver.openOutputStream(any(), "wt") }
    }

    /**
     * The skip has to name the document at a severity a release build keeps.
     *
     * `Logger.d` is gated on a debuggable build, so on a release build the only trace of
     * a document the editor does not have was a count in the summary line. The file is
     * intact on the device and the folder opened successfully, so nothing else says
     * anything at all, and a user asking why they cannot find it needs the name.
     */
    @Test
    fun `a document skipped for size is named at a severity a release build keeps`() {
        deviceHolding("big.zip", size = SafSyncEngine.MAX_FILE_SIZE + 1)

        runBlocking { engine.initialSync(treeUri, mirror) { _, _ -> } }

        verify(atLeast = 1) { Logger.i(any(), match { it.contains("big.zip") }) }
    }

    /**
     * The four cases above pin that the device document survives. These pin the other
     * half, which was missing: that the user is told their edit did not travel.
     *
     * Refusing the write is correct and it is also invisible. The mirror holds the
     * edit, the editor reports the save, the tab goes clean, and the device folder
     * still holds a different document. That is the shape the project's own rule
     * forbids, a save that looks identical to one that worked, and the engine's KDoc
     * names this group as "the user's only copy" while announcing nothing about it.
     *
     * Asserted on the seam rather than on a Toast: the engine has no screen, and
     * `WriteBackNoticeWiringTest` covers the Activity end of the same channel.
     */
    @Test
    fun `a refused write-back tells the user their edit stayed in the app`() {
        val announced = mutableListOf<String>()
        engine.onWriteBackFailed = { announced.add(it.name) }

        deviceHolding("big.zip", size = SafSyncEngine.MAX_FILE_SIZE + 1)
        runBlocking { engine.initialSync(treeUri, mirror) { _, _ -> } }

        File(mirror, "big.zip").writeText("stub")
        deliver(FileObserver.CREATE, "big.zip")

        assertEquals(
            listOf("big.zip"),
            announced,
            "the write was refused and nothing said so, which is the silence this " +
                "guard was supposed to end rather than create",
        )
    }

    /** The same, for a document the sync tried to read and could not. */
    @Test
    fun `a copy that failed is announced too`() {
        val announced = mutableListOf<String>()
        engine.onWriteBackFailed = { announced.add(it.name) }

        deviceHolding("notes.md", size = 64, readable = false)
        runBlocking { engine.initialSync(treeUri, mirror) { _, _ -> } }

        File(mirror, "notes.md").writeText("stub")
        deliver(FileObserver.MODIFY, "notes.md")

        assertEquals(listOf("notes.md"), announced, "a failed copy refused the write silently")
    }

    /**
     * The negative control, and the one that stops the two above from passing for the
     * wrong reason. A write that actually lands must announce nothing: an app that
     * warns on every successful save teaches the user to ignore the warning, which
     * costs more than the silence did.
     */
    @Test
    fun `a write-back that lands announces nothing`() {
        val announced = mutableListOf<String>()
        engine.onWriteBackFailed = { announced.add(it.name) }

        deviceHolding("notes.md", size = 64)
        runBlocking { engine.initialSync(treeUri, mirror) { _, _ -> } }

        File(mirror, "notes.md").writeText("edited in the editor")
        deliver(FileObserver.MODIFY, "notes.md")

        verify(atLeast = 1) { resolver.openOutputStream(any(), "wt") }
        assertTrue(
            announced.isEmpty(),
            "a save that reached the device folder still warned the user: $announced",
        )
    }

    /**
     * Once per file, not once per save. The refusal fires on every inotify MODIFY, so an
     * editor autosaving reaches it every few seconds, and the notice is throttled by one
     * timer for the whole folder: a wall of the same message would also swallow an
     * unrelated write-back failure for as long as it lasted.
     */
    @Test
    fun `an editor saving the same file again is not announced again`() {
        val announced = mutableListOf<String>()
        engine.onWriteBackFailed = { announced.add(it.name) }

        deviceHolding("big.zip", size = SafSyncEngine.MAX_FILE_SIZE + 1)
        runBlocking { engine.initialSync(treeUri, mirror) { _, _ -> } }
        File(mirror, "big.zip").writeText("stub")

        repeat(3) { deliver(FileObserver.MODIFY, "big.zip") }

        assertEquals(listOf("big.zip"), announced, "one notice per file, not one per save")
    }

    /**
     * The other refusal site cannot be driven from here at all: reaching it means walking
     * into a directory, and delivering a directory event builds a `FileObserver`, whose
     * static initializer needs native code. What holds it to the behaviour above is that
     * there is one refusal in the file, so this reads the source and pins that.
     *
     * Two claims, and they fail differently. The message having one home says that a
     * second refusal cannot have been written that forgets to announce. The call count
     * says both refusals are still there: the sentence can sit in the file, spelt exactly
     * once, while the site that reaches it has been commented out. That site is the
     * destructive one (with the call gone, `createOneInSaf` falls through to the write
     * and truncates a device document this sync never read), and no behavioural test in
     * this class can reach it.
     *
     * The call count counts only what the compiler sees. A plain search over raw text
     * finds the call inside a line a `//` has disabled exactly as readily as inside a
     * live one, which is how a guard of this shape reports wiring that is already dead
     * as present. Excluded for the same reason: the declaration, which wears the same
     * spelling, and a doc comment naming the helper.
     */
    @Test
    fun `the refusal message has one home, so both sites announce`() {
        val engineSource =
            File("../../android/app/src/main/kotlin/com/vscodroid/storage/SafSyncEngine.kt")
        assertTrue(engineSource.isFile, "SafSyncEngine.kt is not where this test expects it")
        val text = engineSource.readText()

        assertEquals(
            1,
            Regex("sync never read, and writing would replace it").findAll(text).count(),
            "a second copy of the refusal means a site that refuses without saying so",
        )
        val callSites = text.lines().count { line ->
            val at = line.indexOf("refuseUnreadDocument(")
            val before = if (at < 0) "" else line.substring(0, at)
            at >= 0 && "//" !in before && !before.trimStart().startsWith("*") &&
                "private fun " !in before
        }
        assertEquals(
            2,
            callSites,
            "the two write paths that can find a document this sync never read are the " +
                "event path and the directory walk, and each has to refuse through the " +
                "announcing helper. If a third path legitimately grew one, raise this " +
                "number; if one went away, the write it used to refuse now replaces a " +
                "document the user has no other copy of",
        )
    }
}
