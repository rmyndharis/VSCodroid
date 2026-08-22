package com.vscodroid.storage

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.FileObserver
import android.provider.DocumentsContract
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * That the cheap pre-filter in front of the rename pairing reads the same window the
 * decision does.
 *
 * `hasVanishedCandidate` exists to keep `providerHoldsDocument` off the common path, and
 * that walk is one binder round trip per path segment run on `FileObserver`'s shared
 * reader thread, the thread that has to keep up with every inotify event for the whole
 * mirror. It asked only whether the list was non-empty, while `renameSourceFor` decides
 * by a one-second window, and expired entries were pruned nowhere but on the next
 * directory MOVED_FROM. So one directory leaving the mirror for good (`mv dist
 * /sdcard/...`) with nothing arriving to pair with it left the filter answering yes for
 * the rest of the session, and every directory a `git checkout` or an `npm install`
 * created after that paid the provider walk before it could be queued.
 *
 * The clock is a parameter here for the same reason it is one in the code: an entry can
 * be judged against a later instant without any waiting, and both readers judge one event
 * against one reading.
 */
class SafVanishedCandidateWindowTest {

    /** inotify's "the entry this event is about is a directory", as the kernel sends it. */
    private val isDirFlag = 0x40000000

    @TempDir
    lateinit var mirror: File

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
        every { DocumentsContract.getDocumentId(any()) } returns "root"
        every { DocumentsContract.buildChildDocumentsUriUsingTree(any(), any()) } returns
            mockk(relaxed = true)
        every { DocumentsContract.buildDocumentUriUsingTree(any(), any()) } returns
            mockk(relaxed = true)

        val resolver = mockk<ContentResolver>(relaxed = true)
        val context = mockk<Context>(relaxed = true)
        every { context.contentResolver } returns resolver
        engine = SafSyncEngine(context)
        treeUri = mockk(relaxed = true)
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    /**
     * When one directory left the mirror, as a pair of bounds rather than one reading.
     *
     * The engine stamps the entry from its own clock inside the event, so a test that
     * kept a single reading taken on one side of the call is asserting against a number
     * that may be off by however long the call took. Measured: bounding the expiry case
     * from *before* the event made it fail whenever the event took more than a
     * millisecond, which on a loaded machine it does. Each case below is anchored to the
     * bound that makes its own claim hold whatever the stamp turned out to be.
     */
    private data class Departure(val notBefore: Long, val notAfter: Long)

    private fun directoryLeaves(name: String): Departure {
        val notBefore = System.currentTimeMillis()
        engine.handleMirrorEvent(
            FileObserver.MOVED_FROM or isDirFlag, File(mirror, name), mirror, treeUri
        )
        val notAfter = System.currentTimeMillis()
        assertTrue(
            engine.hasVanishedCandidate(notBefore),
            "the departure was not recorded, so nothing below is being tested",
        )
        return Departure(notBefore, notAfter)
    }

    @Test
    fun `a departure nothing claimed stops being a candidate once its window closes`() {
        val left = directoryLeaves("dist")

        assertFalse(
            engine.hasVanishedCandidate(left.notAfter + SafSyncEngine.RENAME_PAIR_WINDOW_MS + 1),
            "an expired departure still sends every arriving directory through a " +
                "per-segment provider walk on the observer thread, for a pairing " +
                "renameSourceFor would decline",
        )
    }

    /**
     * The control. A filter that answered no to everything would pass the case above and
     * silently stop every rename from ever being paired, which is the worse failure: an
     * unpaired directory rename leaves a stale copy on the device that comes back on
     * every reopen.
     */
    @Test
    fun `a departure inside its window is still a candidate`() {
        val left = directoryLeaves("dist")

        assertTrue(
            engine.hasVanishedCandidate(left.notBefore),
            "a departure was refused as a rename source at the instant it happened",
        )
        assertTrue(
            engine.hasVanishedCandidate(left.notBefore + SafSyncEngine.RENAME_PAIR_WINDOW_MS),
            "a departure was refused at the last instant of its own window",
        )
    }
}
