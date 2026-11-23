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
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File

class SafMonotonicOrderingTest {

    @TempDir
    lateinit var root: File

    private lateinit var mirror: File
    private lateinit var filesDir: File
    private lateinit var resolver: ContentResolver
    private lateinit var engine: SafSyncEngine
    private lateinit var treeUri: Uri
    private var elapsedMs = 10_000L

    private val isDirFlag = 0x40000000

    @BeforeEach
    fun setUp() {
        elapsedMs = 10_000L
        mirror = File(root, "mirror").apply { mkdirs() }
        filesDir = File(root, "files").apply { mkdirs() }

        mockkObject(Logger)
        every { Logger.i(any(), any()) } just Runs
        every { Logger.d(any(), any()) } just Runs
        every { Logger.w(any(), any()) } just Runs
        every { Logger.w(any(), any(), any()) } just Runs
        every { Logger.e(any(), any()) } just Runs
        every { Logger.e(any(), any(), any()) } just Runs

        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } answers { elapsedMs }

        treeUri = mockk<Uri>(relaxed = true).also { every { it.toString() } returns "tree:mono" }
        mockkStatic(DocumentsContract::class)
        every { DocumentsContract.getTreeDocumentId(any()) } returns "root"
        every { DocumentsContract.buildChildDocumentsUriUsingTree(any(), any()) } answers {
            mockk<Uri>(relaxed = true).also { every { it.toString() } returns "children:${secondArg<String>()}" }
        }
        every { DocumentsContract.buildDocumentUriUsingTree(any(), any()) } answers {
            mockk<Uri>(relaxed = true).also { every { it.toString() } returns "doc:${secondArg<String>()}" }
        }
        every { DocumentsContract.getDocumentId(any()) } answers {
            firstArg<Uri>().toString().removePrefix("doc:")
        }
        every { DocumentsContract.deleteDocument(any(), any()) } returns true
        every { DocumentsContract.createDocument(any(), any(), any(), any()) } answers {
            mockk<Uri>(relaxed = true).also { every { it.toString() } returns "doc:created:${arg<String>(3)}" }
        }

        resolver = mockk(relaxed = true)
        every { resolver.query(any(), any(), any(), any(), any()) } answers { emptyCursor() }
        every { resolver.openOutputStream(any(), "wt") } answers { ByteArrayOutputStream() }

        val context = mockk<Context>(relaxed = true)
        every { context.contentResolver } returns resolver
        every { context.filesDir } returns filesDir
        engine = SafSyncEngine(context)
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    private fun emptyCursor(): Cursor = mockk(relaxed = true) {
        every { extras } returns null
        every { moveToNext() } returns false
        every { moveToFirst() } returns false
        every { getColumnIndexOrThrow(any()) } returns 0
        every { getColumnIndex(any()) } returns -1
    }

    @Test
    fun `same-millisecond jobs supersede strictly by enqueue sequence`() {
        val file = File(mirror, "f.txt").apply { writeText("recreated") }
        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, "doc:f.txt")
        val failed = mutableListOf<File>()
        engine.onWriteBackFailed = { failed += it }

        // Enqueue two MODIFY jobs within the exact same millisecond timestamp.
        // The first has mirrorRoot = null so it announces failure if not superseded.
        engine.session.offer(
            SyncJob(
                type = SyncType.MODIFY,
                localPath = file.absolutePath,
                safDocUri = docUri,
                safParentUri = null,
                safTreeUri = treeUri,
                timestamp = 5_000L,
                relativePath = "f.txt",
                mirrorRoot = null,
            ),
            live = false,
        )
        engine.session.offer(
            SyncJob(
                type = SyncType.MODIFY,
                localPath = file.absolutePath,
                safDocUri = docUri,
                safParentUri = null,
                safTreeUri = treeUri,
                timestamp = 5_000L,
                relativePath = "f.txt",
                mirrorRoot = mirror,
            ),
            live = false,
        )

        // Process only the first queued job; it must be superseded by the same-ms later job.
        val first = checkNotNull(engine.session.take())
        SafSyncEngine::class.java
            .getDeclaredMethod("processWriteBack", WatchSession::class.java, SyncJob::class.java)
            .apply { isAccessible = true }
            .invoke(engine, engine.session, first)
        engine.session.finished()

        assertTrue(
            failed.isEmpty(),
            "earlier same-millisecond job ran instead of being superseded by the later queued job",
        )
    }

    @Test
    fun `rename pairing window uses monotonic elapsedRealtime rather than wall clock`() {
        elapsedMs = 10_000L
        engine.handleMirrorEvent(FileObserver.MOVED_FROM or isDirFlag, File(mirror, "dirA"), mirror, treeUri)

        assertTrue(
            engine.hasVanishedCandidate(10_000L + SafSyncEngine.RENAME_PAIR_WINDOW_MS),
            "candidate should remain valid at the end of the monotonic window",
        )
        assertFalse(
            engine.hasVanishedCandidate(10_000L + SafSyncEngine.RENAME_PAIR_WINDOW_MS + 1),
            "candidate did not expire on monotonic clock advancement because wall clock was used",
        )
    }

    @Test
    fun `recentDrops expiry uses monotonic elapsedRealtime rather than wall clock`() {
        // Trigger rememberDroppedSave for a missing file under old_dir at elapsedMs = 10_000L.
        val missing = File(mirror, "old_dir/notes.txt")
        engine.session.offer(
            SyncJob(
                type = SyncType.MODIFY,
                localPath = missing.absolutePath,
                safDocUri = null,
                safParentUri = null,
                safTreeUri = treeUri,
                timestamp = elapsedMs,
                relativePath = "old_dir/notes.txt",
                mirrorRoot = mirror,
            ),
            live = false,
        )
        engine.runWriteBackLoop { false }
        assertEquals(1, engine.session.recentDrops.size, "setup: dropped save was not recorded")

        // Advance monotonic clock past the pairing window without sleeping wall clock.
        elapsedMs += SafSyncEngine.RENAME_PAIR_WINDOW_MS + 1
        engine.handleMirrorEvent(FileObserver.MOVED_FROM or isDirFlag, File(mirror, "old_dir"), mirror, treeUri)

        @Suppress("UNCHECKED_CAST")
        val droppedSaves = SafSyncEngine::class.java
            .getDeclaredField("droppedSaves")
            .apply { isAccessible = true }
            .get(engine) as Map<String, List<String>>
        assertTrue(
            droppedSaves[File(mirror, "old_dir").absolutePath].isNullOrEmpty(),
            "a dropped save expired on the monotonic clock was still attached to MOVED_FROM: $droppedSaves",
        )
    }
}
