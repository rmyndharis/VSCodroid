package com.vscodroid.storage

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.FileObserver
import android.provider.DocumentsContract
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * What a renamed directory does to the copy on the device.
 *
 * inotify reports a rename as MOVED_FROM on the old name and MOVED_TO on the new one,
 * and `FileObserver` cannot pair them because Android does not expose inotify's cookie.
 * The MOVED_FROM therefore arrived as a plain delete, and on a directory that is
 * `DocumentsContract.deleteDocument` taking the whole subtree on the device. The
 * MOVED_TO put back what the *mirror* held, and the mirror is not all of it:
 * `createChildrenInSaf` stops at `MAX_UPLOAD_ENTRIES`, and the mirror never held the
 * `.git` or `node_modules` that `SKIP_DIRECTORIES` kept out of it. Renaming a folder of
 * 3000 files left about 1000 of them nowhere but the mirror, which
 * `reclaimRevokedMirrors` removes as soon as the folder's permission lapses. One logcat
 * line was the whole record.
 *
 * Driven through `handleMirrorEvent` rather than through an observer: constructing a
 * `FileObserver` runs a static initializer that reaches native code a JVM test cannot
 * satisfy, which is also why the MOVED_TO half is not exercised here — it registers
 * watches for the new name.
 */
class SafDirectoryRenameTest {

    /** inotify's "the entry this event is about is a directory", as the kernel sends it. */
    private val isDirFlag = 0x40000000

    @TempDir
    lateinit var mirror: File

    private lateinit var resolver: ContentResolver
    private lateinit var engine: SafSyncEngine
    private lateinit var treeUri: Uri

    @BeforeEach
    fun setUp() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.i(any(), any()) } returns 0
        every { android.util.Log.d(any(), any()) } returns 0
        every { android.util.Log.w(any(), any<String>()) } returns 0
        every { android.util.Log.e(any(), any()) } returns 0

        mockkStatic(DocumentsContract::class)
        every { DocumentsContract.getTreeDocumentId(any()) } returns "root"
        every { DocumentsContract.buildChildDocumentsUriUsingTree(any(), any()) } returns mockk(relaxed = true)
        every { DocumentsContract.buildDocumentUriUsingTree(any(), any()) } returns mockk(relaxed = true)
        every { DocumentsContract.deleteDocument(any(), any()) } returns true

        resolver = mockk(relaxed = true)
        val context = mockk<Context>(relaxed = true)
        every { context.contentResolver } returns resolver
        engine = SafSyncEngine(context)
        treeUri = mockk(relaxed = true)
    }

    /**
     * The device folder answers for one child called [name], so the lookup that turns a
     * relative path into a document finds something to point the delete at. Without it
     * every case here would pass for the wrong reason: an unresolved path yields a null
     * document URI and the delete is skipped anyway.
     */
    private fun deviceFolderHolding(name: String) {
        val cursor = mockk<Cursor>(relaxed = true)
        var row = -1
        every { cursor.moveToNext() } answers { ++row == 0 }
        every { cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID) } returns 0
        every { cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME) } returns 1
        every { cursor.getString(0) } returns "doc:$name"
        every { cursor.getString(1) } returns name
        every { resolver.query(any(), any(), any(), any(), any()) } answers {
            row = -1
            cursor
        }
    }

    /** Delivers one event and lets the write-back thread's loop drain what it queued. */
    private fun deliver(event: Int, entry: String) {
        engine.handleMirrorEvent(event, File(mirror, entry), mirror, treeUri)
        engine.runWriteBackLoop { false }
    }

    @Test
    fun `a directory moved away keeps its contents on the device`() {
        deviceFolderHolding("util")

        deliver(FileObserver.MOVED_FROM or isDirFlag, "util")

        verify(exactly = 0) { DocumentsContract.deleteDocument(any(), any()) }
    }

    @Test
    fun `a directory deleted outright is still deleted on the device`() {
        // The other half of the boundary. Refusing every directory delete would be the
        // easy over-correction: a folder the user removes in the editor would come
        // straight back the next time the folder is opened, because reopening copies
        // the device tree down again.
        deviceFolderHolding("util")

        deliver(FileObserver.DELETE or isDirFlag, "util")

        verify(exactly = 1) { DocumentsContract.deleteDocument(any(), any()) }
    }

    @Test
    fun `a file moved away is still deleted on the device`() {
        // A renamed file loses nothing: the create half writes its whole contents under
        // the new name. Only a directory's subtree is unrecoverable, so only a directory
        // is spared.
        deviceFolderHolding("notes.txt")
        assertTrue(!File(mirror, "notes.txt").isDirectory, "fixture check: the old name is gone")

        deliver(FileObserver.MOVED_FROM, "notes.txt")

        verify(exactly = 1) { DocumentsContract.deleteDocument(any(), any()) }
    }
}
