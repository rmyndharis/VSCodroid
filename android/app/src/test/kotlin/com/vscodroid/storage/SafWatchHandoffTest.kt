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
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Which folder's queue a save observed just before a folder switch is offered to.
 *
 * The observer checks liveness at entry and then spends provider round trips, a query per
 * path segment and several of them, before it ever looks at a queue. A stop runs to
 * completion inside that window: it hands the closing folder's queue to the drain that
 * owns it and installs a fresh session for the folder opened next. That fresh one is an
 * orphan until `startWatching` starts a worker for it, and `startWatching` opens by
 * calling `stopWatching`, whose no-worker branch clears it again, so a job offered there
 * is discarded with no log line, no notice and no retry.
 *
 * What that costs is bounded but real: the mirror keeps the file, the record cannot vouch
 * for it, and reopening the folder writes the newer mirror copy back. Where the provider
 * reports no modification time there is no clock to compare and neither side is pushed,
 * so the save simply never reaches the device.
 *
 * The session is read at entry instead, so a late offer lands in the queue the closing
 * folder's drain is still emptying, which is what the grace period exists for. The
 * residue is stated rather than claimed closed: a stop landing between the observer's
 * liveness check and the capture two instructions later still hands over the orphan.
 * Closing that needs the capture to move into the observer and be passed in, which
 * changes the signature every test in this package calls. What that residue no longer
 * does is happen in silence: the session a stop installs is abandoned from birth, so an
 * offer into it is reported like any other save a drain did not take. The delivery stays
 * open; the telling does not.
 *
 * The offer that arrives once every drain has ended is not silent either. The stop's own
 * count cannot reach that one: it reads the queue and clears it while the event is still
 * inside the provider, so what is left to report is reported by the offer itself.
 *
 * NEGATIVE CONTROL, run by hand: put the offer back on the mutable field
 * (`target.queue.offer` to `session.queue.offer`). The first case goes red on both
 * assertions and in opposite directions, the closing queue empty and the newly opened one
 * holding the save, which is what shows it is measuring the handoff rather than merely
 * that a job exists. Deleting the count and its log line reddens the second case, and
 * giving `stopWatching` a plain `WatchSession()` to install reddens the fourth alone.
 */
class SafWatchHandoffTest {

    @TempDir
    lateinit var mirror: File

    @TempDir
    lateinit var filesDir: File

    private lateinit var resolver: ContentResolver
    private lateinit var engine: SafSyncEngine
    private lateinit var treeUri: Uri

    /** Every string the app handed the log, from any thread. */
    private val emitted: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** Counted down once the observer thread is inside the provider. */
    private val entered = CountDownLatch(1)

    /** Held closed until the test lets that provider call return. */
    private val release = CountDownLatch(1)

    @BeforeEach
    fun setUp() {
        emitted.clear()

        mockkStatic(android.util.Log::class)
        every { android.util.Log.i(any(), any()) } answers { emitted += secondArg<String>(); 0 }
        every { android.util.Log.d(any(), any()) } answers { emitted += secondArg<String>(); 0 }
        every { android.util.Log.w(any(), any<String>()) } answers { emitted += secondArg<String>(); 0 }
        every { android.util.Log.e(any(), any()) } answers { emitted += secondArg<String>(); 0 }

        mockkStatic(DocumentsContract::class)
        every { DocumentsContract.getTreeDocumentId(any()) } returns "root"
        every { DocumentsContract.buildDocumentUriUsingTree(any(), any()) } returns
            mockk(relaxed = true)
        every { DocumentsContract.buildChildDocumentsUriUsingTree(any(), any()) } returns
            mockk(relaxed = true)

        resolver = mockk(relaxed = true)
        // The gate, and the reason it sits in the provider rather than in a sleep: a
        // MODIFY on a top-level file reaches exactly one query, the segment walk that
        // resolves `notes.txt`, so parking there puts the observer deterministically
        // inside the window a stop has to be able to land in. Nothing here depends on
        // timing to produce the interleaving.
        every { resolver.query(any(), any(), any(), any(), any()) } answers {
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
            oneChildNamed("notes.txt")
        }

        val context = mockk<Context>(relaxed = true)
        every { context.contentResolver } returns resolver
        every { context.filesDir } returns filesDir

        engine = SafSyncEngine(context)
        treeUri = mockk(relaxed = true)
    }

    @AfterEach
    fun tearDown() {
        // However the assertions ended, no case may leave a thread parked in the gate.
        release.countDown()
        unmockkAll()
    }

    private fun oneChildNamed(name: String): Cursor {
        val cursor = mockk<Cursor>(relaxed = true)
        var row = -1
        every { cursor.moveToNext() } answers { ++row == 0 }
        every { cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID) } returns 0
        every { cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME) } returns 1
        every { cursor.getString(0) } returns "doc:$name"
        every { cursor.getString(1) } returns name
        return cursor
    }

    /** A save of [name] in the mirror, addressed to its own device document. */
    private fun saveOf(name: String): SyncJob {
        val local = File(mirror, name).apply { writeText("a save that missed the drain") }
        return SyncJob(
            type = SyncType.MODIFY,
            localPath = local.absolutePath,
            safDocUri = mockk(relaxed = true),
            safParentUri = null,
            safTreeUri = null,
            timestamp = 1_700_000_000_000,
        )
    }

    @Test
    fun `a save observed before the stop goes to the session the drain owns`() {
        val closing = engine.session
        File(mirror, "notes.txt").writeText("a save mid-flight")

        val observer = thread(isDaemon = true) {
            engine.handleMirrorEvent(FileObserver.MODIFY, File(mirror, "notes.txt"), mirror, treeUri)
        }
        assertTrue(
            entered.await(5, TimeUnit.SECONDS),
            "setup failed: the event never reached the provider, so the stop below did " +
                "not land inside the window this case is about",
        )

        engine.stopWatching()
        val opened = engine.session
        assertNotSame(
            closing, opened,
            "setup failed: the stop did not hand the next folder a session of its own",
        )

        release.countDown()
        observer.join(5_000)

        assertEquals(
            1, closing.queue.size,
            "the save was offered to a session nothing drains: only startWatching starts " +
                "a worker, and it opens with a stop that clears that queue again",
        )
        assertTrue(
            opened.queue.isEmpty(),
            "the save landed in the queue of a folder that is not open",
        )
    }

    /**
     * The one drop that remains, made visible.
     *
     * A job offered after the drain has taken its last look is cleared here, and the
     * mirror keeps the file, so nothing is destroyed. What is lost is the user knowing:
     * the save is not on the device and only reopening the folder puts it there.
     *
     * Driven by offering the job directly rather than through a second stop. The offer of
     * the case above lands in the session the FIRST stop closed, so a second stop clears
     * a different, empty queue and reports nothing; this is the shape that actually
     * reaches the branch.
     */
    @Test
    fun `a save the stop could not deliver is reported rather than dropped in silence`() {
        val closing = engine.session
        closing.queue.offer(saveOf("notes.txt"))

        engine.stopWatching()

        assertTrue(
            closing.queue.isEmpty(),
            "setup failed: the stop never reached the clear, so nothing was there to report",
        )
        assertTrue(
            emitted.any { it.contains("arrived after the drain ended") },
            "a save that never reached the device was dropped without a word, and a user " +
                "asking why needs this line to exist in a bug report: $emitted",
        )
    }

    /**
     * The drop the capture itself creates, made visible where the count cannot see it.
     *
     * The count above reads a queue and clears it. On an ordinary folder switch the idle
     * drain exits in microseconds while this event is still inside the provider, so the
     * count runs first, reports nothing, and the save arrives afterwards into a queue with
     * no worker: exactly the interleaving the capture produces, and the one the count is
     * blind to. Nothing is destroyed, the mirror keeps the file, but the save is not on
     * the device and only reopening the folder puts it there.
     *
     * NEGATIVE CONTROL, run by hand: delete the `if (target.abandoned)` warning at the
     * offer in `handleMirrorEvent`, or stop `stopWatching`'s no-worker branch setting
     * `abandoned`. This case goes red while the two above stay green, since neither reads
     * a line that names a file.
     */
    @Test
    fun `a save that arrives after the drain has ended says so`() {
        File(mirror, "notes.txt").writeText("a save mid-flight")

        val observer = thread(isDaemon = true) {
            engine.handleMirrorEvent(FileObserver.MODIFY, File(mirror, "notes.txt"), mirror, treeUri)
        }
        assertTrue(
            entered.await(5, TimeUnit.SECONDS),
            "setup failed: the event never reached the provider, so the stop below did " +
                "not land inside the window this case is about",
        )

        engine.stopWatching()
        release.countDown()
        observer.join(5_000)

        assertTrue(
            emitted.any { it.contains("Write-back of notes.txt arrived after the drain ended") },
            "the save reached no device, nothing will retry it, and no line says so: " +
                "$emitted",
        )
    }

    /**
     * The window reading at entry narrows and does not close, made visible.
     *
     * The liveness test is the observer's, two instructions before the capture, so a stop
     * completing between the two hands the event the session installed for the folder
     * opened next. Nothing polls that one: only `startWatching` starts a worker, and it
     * installs a session of its own first. The save is as undelivered as any other that
     * misses a drain, and it used to be the only one of them that said nothing, because
     * the fresh session carried no `abandoned` flag and the stop's own count had already
     * been taken.
     *
     * Driven by stopping first and handling the event after, which is the state that race
     * ends in. The interleaving itself cannot be produced here: constructing a real
     * `DirectoryObserver` runs a static initializer that reaches native code.
     */
    @Test
    fun `a save handed the session nothing will ever drain says so`() {
        File(mirror, "notes.txt").writeText("a save mid-flight")
        engine.stopWatching()
        val orphaned = engine.session
        release.countDown()

        engine.handleMirrorEvent(FileObserver.MODIFY, File(mirror, "notes.txt"), mirror, treeUri)

        assertEquals(
            1, orphaned.queue.size,
            "setup failed: the save went somewhere other than the session the stop left " +
                "current, so this case is not measuring that session at all",
        )
        assertTrue(
            emitted.any { it.contains("Write-back of notes.txt arrived after the drain ended") },
            "the save landed in a queue with no worker and no successor, and nothing " +
                "said so: $emitted",
        )
    }
}
