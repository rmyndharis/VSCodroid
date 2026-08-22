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
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

/**
 * What a directory rename does to the upload records of the files beneath it.
 *
 * A journal line is an absolute mirror path, and it means "this mirror file holds an edit
 * the device never received". The one path that moves those keys served a mirror ROOT
 * being renamed out of the way by a removal; nothing moved them when a directory INSIDE
 * the mirror was renamed in the editor. That is precisely the path that can afford to
 * lose them least: a claimed rename deliberately re-uploads nothing, because the watcher
 * has already carried every change, so the journal is the only record of what it did not
 * carry, and the old path it names no longer exists.
 *
 * The cost is not bookkeeping. The next sync's repair branch is gated on the recorded
 * file being there, so a line under a path that has gone can never be consumed: the
 * device's own truncated upload is read as a device edit and copied over the mirror's
 * only complete copy. Until that happens the same line refuses the removal the user asked
 * for and mislabels the row on the device-folder screen.
 *
 * Driven through `handleMirrorEvent` rather than through an observer, and the MOVED_TO is
 * delivered while the new name is still ABSENT from disk: `watchTree` would otherwise
 * build a `FileObserver`, whose static initializer reaches native code a JVM test cannot
 * satisfy. The mirror-side rename is performed between the event and the drain, which
 * changes nothing about what is asserted here, because moving the records is a rewrite of
 * strings that reads no directory.
 *
 * NEGATIVE CONTROL, run by hand: delete the single `renameUploadsUnder` call from
 * `handleMirrorEvent`'s claim. The first three cases go red on the journal assertions and
 * the last goes red with the mirror reading "TRUNC", while `a directory that simply
 * appears takes nothing with it` stays green, which is what makes it a control rather
 * than a second copy of the first case. A second control, for the decision not to
 * condition the move on the device: narrowing the guard to
 * `renamedFrom != null && renamedFromUri != null`, which is the mutation a developer
 * copying the neighbouring `forgetCachedSubtree` guard would make, reddens only
 * `the records move even when the device holds nothing under the old name`.
 */
class SafRenamedUploadRecordTest {

    /** inotify's "the entry this event is about is a directory", as the kernel sends it. */
    private val isDirFlag = 0x40000000

    @TempDir
    lateinit var filesDir: File

    private lateinit var resolver: ContentResolver
    private lateinit var engine: SafSyncEngine
    private lateinit var treeUri: Uri
    private lateinit var mirrorsRoot: File
    private lateinit var mirror: File

    private val hash = "abc123def456"

    /** Set to have every write-back attempt fail the way a provider out of space does. */
    private var refuseWrites = false

    /** Document writes the provider accepted, so a case can count the ones it caused. */
    private var writesAccepted = 0

    @BeforeEach
    fun setUp() {
        refuseWrites = false
        writesAccepted = 0

        mockkObject(Logger)
        every { Logger.i(any(), any()) } just Runs
        every { Logger.d(any(), any()) } just Runs
        every { Logger.w(any(), any()) } just Runs
        every { Logger.w(any(), any(), any()) } just Runs
        every { Logger.e(any(), any()) } just Runs
        every { Logger.e(any(), any(), any()) } just Runs

        mockkStatic(DocumentsContract::class)
        every { DocumentsContract.getTreeDocumentId(any()) } returns "root"
        every { DocumentsContract.deleteDocument(any(), any()) } returns true
        every { DocumentsContract.renameDocument(any(), any(), any()) } returns mockk(relaxed = true)
        every { DocumentsContract.createDocument(any(), any(), any(), any()) } returns
            mockk(relaxed = true)
        every { DocumentsContract.moveDocument(any(), any(), any(), any()) } returns
            mockk(relaxed = true)

        resolver = mockk(relaxed = true)
        every { resolver.openOutputStream(any(), any()) } answers {
            if (refuseWrites) throw IOException("no space left on device")
            writesAccepted++
            ByteArrayOutputStream()
        }

        val context = mockk<Context>(relaxed = true)
        every { context.contentResolver } returns resolver
        every { context.filesDir } returns filesDir

        engine = SafSyncEngine(context)
        treeUri = mockk(relaxed = true)

        mirrorsRoot = File(filesDir, "saf-mirrors").apply { mkdirs() }
        mirror = File(mirrorsRoot, hash).apply { mkdirs() }
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    /** One child of a device folder, with the columns a sync reads as well as a lookup's. */
    private class Child(
        val name: String,
        val isDirectory: Boolean = false,
        val size: Long = 0L,
        val lastModified: Long = 0L,
    )

    /**
     * A device folder with real structure, keyed by each parent's document id with `root`
     * as the tree's own.
     *
     * Parent-aware rather than a single flat child list, because these cases turn on
     * *where* a name is: claiming a rename requires the new path to resolve to nothing,
     * and a mock answering the same children under every parent makes `lib` resolve the
     * moment `lib` exists anywhere, so the pair is refused and the case passes with
     * nothing renamed at all.
     */
    private fun deviceTree(tree: Map<String, List<Child>>) {
        every { DocumentsContract.buildChildDocumentsUriUsingTree(any(), any()) } answers {
            val parent = secondArg<String>()
            mockk<Uri>(relaxed = true).also { every { it.toString() } returns "children:$parent" }
        }
        every { DocumentsContract.buildDocumentUriUsingTree(any(), any()) } answers {
            val docId = secondArg<String>()
            mockk<Uri>(relaxed = true).also { every { it.toString() } returns "doc-uri:$docId" }
        }
        every { DocumentsContract.getDocumentId(any()) } answers {
            firstArg<Uri>().toString().removePrefix("doc-uri:")
        }
        every { resolver.query(any(), any(), any(), any(), any()) } answers {
            val parent = firstArg<Uri>().toString().removePrefix("children:")
            val children = tree[parent] ?: emptyList()
            val cursor = mockk<Cursor>(relaxed = true)
            var row = -1
            every { cursor.moveToNext() } answers { ++row < children.size }
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
            every { cursor.getString(0) } answers { "doc:${children[row].name}" }
            every { cursor.getString(1) } answers { children[row].name }
            every { cursor.getString(2) } answers {
                if (children[row].isDirectory) DocumentsContract.Document.MIME_TYPE_DIR
                else "text/plain"
            }
            every { cursor.getLong(3) } answers { children[row].size }
            every { cursor.getLong(4) } answers { children[row].lastModified }
            cursor
        }
    }

    /** Delivers one event, without letting the write-back queue run. */
    private fun observe(event: Int, entry: String) {
        engine.handleMirrorEvent(event, File(mirror, entry), mirror, treeUri)
    }

    /** Runs the write-back thread's loop over whatever the events queued. */
    private fun drain() {
        engine.runWriteBackLoop { false }
    }

    /** Delivers one event and lets the write-back thread's loop drain what it queued. */
    private fun deliver(event: Int, entry: String) {
        observe(event, entry)
        drain()
    }

    /**
     * A save the provider refuses, which is what puts a line in the journal at all, and
     * the assertion that it did: without a stranded record none of the cases below is
     * testing anything.
     */
    private fun strandOneWrite(path: String): File {
        val file = File(mirror, path)
        file.parentFile?.mkdirs()
        file.writeText("the whole edit")
        refuseWrites = true
        deliver(FileObserver.MODIFY, path)
        refuseWrites = false
        assertTrue(
            file.absolutePath in engine.uploadsInFlight(),
            "no record was stranded, so nothing below is being tested",
        )
        return file
    }

    @Test
    fun `a renamed directory takes its stranded upload records with it`() {
        deviceTree(
            mapOf(
                "root" to listOf(Child("src", isDirectory = true)),
                "doc:src" to listOf(Child("App.kt")),
            )
        )
        strandOneWrite("src/App.kt")

        observe(FileObserver.MOVED_FROM or isDirFlag, "src")
        observe(FileObserver.MOVED_TO or isDirFlag, "lib")
        File(mirror, "src").renameTo(File(mirror, "lib"))
        drain()

        val listed = engine.uploadsInFlight()
        assertTrue(
            File(mirror, "lib/App.kt").absolutePath in listed,
            "the record stayed behind on a path that no longer exists, so the next sync " +
                "cannot consume it and copies the device's truncated upload over the " +
                "mirror's only complete copy",
        )
        assertFalse(
            File(mirror, "src/App.kt").absolutePath in listed,
            "the old path still stands, which refuses the removal the user asks for and " +
                "describes the mirror as holding work that is not where it says",
        )
    }

    @Test
    fun `a directory dragged into another folder takes its records with it`() {
        deviceTree(
            mapOf(
                "root" to listOf(Child("src", isDirectory = true), Child("lib", isDirectory = true)),
                "doc:src" to listOf(Child("App.kt")),
                "doc:lib" to emptyList(),
            )
        )
        strandOneWrite("src/App.kt")

        observe(FileObserver.MOVED_FROM or isDirFlag, "src")
        observe(FileObserver.MOVED_TO or isDirFlag, "lib/src")
        File(mirror, "lib").mkdirs()
        File(mirror, "src").renameTo(File(mirror, "lib/src"))
        drain()

        val listed = engine.uploadsInFlight()
        assertTrue(
            File(mirror, "lib/src/App.kt").absolutePath in listed,
            "a move across directories leaves the record under the old parent",
        )
        assertFalse(
            File(mirror, "src/App.kt").absolutePath in listed,
            "the old path still stands after the directory left it",
        )
    }

    /**
     * The records describe the MIRROR, so what the device answers for the old name
     * decides nothing. A provider that has no document under it, or one that refuses the
     * rename outright, changes nothing about where those files now are.
     *
     * The stranded file sits one level below the directory that moves, so that resolving
     * the old name is a real question rather than a cache hit: an event for `src/util/…`
     * caches `src/util`, never `src`.
     */
    @Test
    fun `the records move even when the device holds nothing under the old name`() {
        deviceTree(
            mapOf(
                "root" to listOf(Child("src", isDirectory = true)),
                "doc:src" to listOf(Child("util", isDirectory = true)),
                "doc:util" to listOf(Child("App.kt")),
            )
        )
        strandOneWrite("src/util/App.kt")

        // The device folder no longer answers for anything: the old name resolves to no
        // document, so the job degrades to a create and nothing is renamed there.
        deviceTree(mapOf("root" to emptyList()))
        // Held refused across the drain so the fallback create cannot land a write and
        // retire the very line this case is following.
        refuseWrites = true
        observe(FileObserver.MOVED_FROM or isDirFlag, "src")
        observe(FileObserver.MOVED_TO or isDirFlag, "lib")
        File(mirror, "src").renameTo(File(mirror, "lib"))
        drain()

        val listed = engine.uploadsInFlight()
        assertTrue(
            File(mirror, "lib/util/App.kt").absolutePath in listed,
            "the records were held back on what the device answered, which is a fact " +
                "about the device and not about where the mirror's files are",
        )
        assertFalse(
            File(mirror, "src/util/App.kt").absolutePath in listed,
            "the old path still stands after the directory left it",
        )
    }

    /**
     * The control. A directory that simply appears is not the other half of anything, and
     * a move that fired on any arrival would carry records off paths that still hold the
     * user's only copy.
     */
    @Test
    fun `a directory that simply appears takes nothing with it`() {
        deviceTree(
            mapOf(
                "root" to listOf(Child("src", isDirectory = true)),
                "doc:src" to listOf(Child("App.kt")),
            )
        )
        val stranded = strandOneWrite("src/App.kt")

        deliver(FileObserver.MOVED_TO or isDirFlag, "lib")

        assertTrue(
            stranded.absolutePath in engine.uploadsInFlight(),
            "an unpaired arrival moved a record off a file that is still there and still " +
                "holds an edit the device never received",
        )
    }

    /**
     * The whole cost, driven to the file. The mirror holds the only complete copy, the
     * device holds this app's own truncated upload with a newer time, and between the two
     * the user renames the directory in the editor.
     */
    @Test
    fun `a sync keeps the mirror of a file whose directory was renamed after a failed upload`() {
        deviceTree(
            mapOf(
                "root" to listOf(Child("src", isDirectory = true)),
                "doc:src" to listOf(Child("App.kt")),
            )
        )
        val stranded = strandOneWrite("src/App.kt")
        stranded.setLastModified(1_000_000_000_000L)

        observe(FileObserver.MOVED_FROM or isDirFlag, "src")
        observe(FileObserver.MOVED_TO or isDirFlag, "lib")
        File(mirror, "src").renameTo(File(mirror, "lib"))
        drain()
        // The pair was claimed, so the device's own document was renamed and nothing was
        // re-uploaded. Without that this case would be watching the ordinary create path,
        // which brackets each of its own writes with the journal and would answer the
        // question by accident.
        verify(exactly = 1) { DocumentsContract.renameDocument(any(), any(), "lib") }

        // The device folder as it is after the rename: the truncated upload, newer and
        // shorter, under the new name.
        deviceTree(
            mapOf(
                "root" to listOf(Child("lib", isDirectory = true)),
                "doc:lib" to listOf(
                    Child("App.kt", size = 5L, lastModified = 2_000_000_000_000L)
                ),
            )
        )
        every { resolver.openInputStream(any()) } answers {
            ByteArrayInputStream("TRUNC".toByteArray())
        }
        writesAccepted = 0

        runBlocking { engine.initialSync(treeUri, mirror) { _, _ -> } }

        assertEquals(
            "the whole edit", File(mirror, "lib/App.kt").readText(),
            "the device's newer copy was this app's own interrupted upload, and the " +
                "rename moved the file out from under the record that said so",
        )
        // Delivery, not enqueueing: the write-back queue belongs to the watcher's
        // lifecycle, and startWatching opens by clearing it, so a job offered between the
        // sync and the thread that would run it never executes. The repair has to have
        // reached the document inside the sync itself.
        assertEquals(
            1, writesAccepted,
            "the repair upload never reached the provider inside the sync",
        )
    }
}
