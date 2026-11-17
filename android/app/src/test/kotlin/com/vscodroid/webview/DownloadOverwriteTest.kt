package com.vscodroid.webview

import android.net.Uri
import com.vscodroid.util.Logger
import com.vscodroid.util.StorageManager
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.OutputStream
import java.util.Base64
import java.util.concurrent.Executor

/**
 * That saving a download over a document the user already has replaces it whole,
 * and that a download which fails before it is complete leaves that document as
 * it was.
 *
 * The save picker answers "Overwrite" with the existing document's own URI, and
 * the platform's providers open a document for mode "w" without truncating it.
 * So a download written straight into the answer kept the old file's tail when
 * it was shorter, and a download that failed deleted a file the user had before
 * they ever asked for this one.
 *
 * The provider here keeps documents the way the platform does: a write starts at
 * the first byte and stops where the bytes stop, and only a "t" in the mode cuts
 * anything. It can also be told to refuse that mode, to ignore it, to hand back
 * a pipe, or to fail a cut, which are the ways other providers differ. It is
 * measured the way a read-only descriptor shows a document: a length for a
 * regular file and nothing for a pipe, with no time and no listing.
 */
class DownloadOverwriteTest {

    /** A document as a provider keeps it. */
    private class StoredDocument(var content: ByteArray) {
        /** The bytes its blocks occupy when that is less than its length: a sparse file. */
        var sparseOccupied: Long? = null

        /** Set when the provider does not say how many blocks it occupies. */
        var occupiedUnknown = false

        fun blocks(): Long = sparseOccupied ?: content.size.toLong()
    }

    /** What the stand-in provider does with a "t" in the mode. */
    private enum class Truncation { HONOURED, REFUSED, IGNORED }

    /** Every document in the stand-in provider, by URI. */
    private val store = mutableMapOf<Uri, StoredDocument>()

    private var truncation = Truncation.HONOURED

    /** Set to make the provider hand back a pipe instead of a file. */
    private var pipes = false

    /** Set to make cutting a document to length fail. */
    private var cutFails = false

    /** Cleared to make the provider refuse to open documents for reading. */
    private var reads = true

    /** Set to make the provider hand back a pipe when a document is opened for reading. */
    private var readsAsPipe = false

    /**
     * The bytes the stand-in file system has free, or null for room it does not report and
     * never runs out of. A truncation gives back what the document's blocks occupied.
     */
    private var free: Long? = null

    /** The free space of the volume downloads are staged on, or null when it cannot be told. */
    private var stagingRoom: Long? = null

    /** Thrown by every open for writing, whatever the mode, when set. */
    private var openThrows: Exception? = null

    /** Run when a written document is flushed, which is just before its length is checked. */
    private var beforeCheck: (() -> Unit)? = null

    /** Set to make the provider empty a document whenever it is opened for writing, "w" included. */
    private var emptiesOnWrite = false

    /** The modes documents were opened with, in order. */
    private val modes = mutableListOf<String>()

    /** The request each picker was opened for, in order. */
    private val pickerIds = mutableListOf<String>()

    /** Every outcome reported, in order. */
    private val reported = mutableListOf<Pair<DownloadOutcome, String>>()

    /** The names the provider gives the documents the picker returned; one it does not know has none. */
    private val names = mutableMapOf<Uri, String>()

    /** The request ids the page was asked to read. */
    private val readRequests = mutableListOf<String>()

    @TempDir
    lateinit var stagingDir: File

    private lateinit var coordinator: DownloadCoordinator

    /**
     * Writes from the first byte of [doc] and cuts nothing, which is what an open
     * without "t" does on the platform's providers.
     */
    private inner class OverwritingStream(private val doc: StoredDocument) : OutputStream() {
        private var position = 0

        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        /** Like a full disk: what fits is written, then the write fails. */
        override fun write(b: ByteArray, off: Int, len: Int) {
            val growth = maxOf(0L, (position + len - doc.content.size).toLong())
            val room = free
            if (room != null && growth > room) {
                put(b, off, (len - (growth - room)).toInt())
                throw IOException("no space left on the device")
            }
            if (room != null) free = room - growth
            put(b, off, len)
        }

        override fun flush() {
            beforeCheck?.invoke()
        }

        private fun put(b: ByteArray, off: Int, len: Int) {
            val end = position + len
            if (end > doc.content.size) doc.content = doc.content.copyOf(end)
            System.arraycopy(b, off, doc.content, position, len)
            position = end
        }
    }

    /** A pipe: the provider takes whatever arrived as the whole document once it is closed. */
    private class PipeStream(private val doc: StoredDocument) : OutputStream() {
        private val received = ByteArrayOutputStream()

        override fun write(b: Int) = received.write(b)

        override fun write(b: ByteArray, off: Int, len: Int) = received.write(b, off, len)

        override fun close() {
            doc.content = received.toByteArray()
        }
    }

    private val host = object : DownloadHost {
        override fun askDestination(requestId: String, fileName: String) {
            pickerIds += requestId
        }

        override fun measureDestination(destination: Uri): DocumentMeasure? {
            if (!reads) throw FileNotFoundException("open failed: EACCES (Permission denied)")
            val doc = store[destination] ?: return null
            if (readsAsPipe) return DocumentMeasure(length = null)
            return DocumentMeasure(
                length = doc.content.size.toLong(),
                occupied = if (doc.occupiedUnknown) null else doc.blocks(),
                free = free,
            )
        }

        override fun openDestination(destination: Uri, mode: String): OpenDocument? {
            val doc = store[destination] ?: return null
            modes += mode
            openThrows?.let { throw it }
            if (emptiesOnWrite) doc.content = ByteArray(0)
            if ('t' in mode) when (truncation) {
                Truncation.HONOURED -> {
                    free = free?.plus(doc.blocks())
                    doc.content = ByteArray(0)
                    doc.sparseOccupied = null
                }
                Truncation.REFUSED -> throw IllegalArgumentException("Unsupported mode: $mode")
                Truncation.IGNORED -> Unit
            }
            if (pipes) return OpenDocument(PipeStream(doc))
            return OpenDocument(
                OverwritingStream(doc),
                length = { doc.content.size.toLong() },
                truncate = { size ->
                    if (cutFails) throw IOException("this document cannot be cut")
                    doc.content = doc.content.copyOf(size.toInt())
                },
            )
        }

        override fun discardDestination(destination: Uri) {
            store.remove(destination)
        }

        override fun destinationName(destination: Uri): String? = names[destination]

        override fun requestBytes(requestId: String, url: String) {
            readRequests += requestId
        }

        override fun releaseBytes(url: String) = Unit

        override fun report(outcome: DownloadOutcome, fileName: String, detail: String?) {
            reported += outcome to fileName
        }
    }

    @BeforeEach
    fun setUp() {
        mockkObject(Logger)
        every { Logger.d(any(), any()) } just Runs
        every { Logger.w(any(), any()) } just Runs
        every { Logger.w(any(), any(), any()) } just Runs
        coordinator = DownloadCoordinator(
            host,
            providerWork = Executor { it.run() },
            mainThread = Executor { it.run() },
            stagingDir = { stagingDir },
            stagingRoom = { stagingRoom },
        )
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    /** A document the user already had, as the picker returns it after "Overwrite". */
    private fun existingDocument(text: String): Uri {
        val uri = mockk<Uri>(relaxed = true, name = "existing")
        store[uri] = StoredDocument(text.toByteArray())
        return uri
    }

    /** A document the picker creates for a new name: empty. */
    private fun newDocument(): Uri {
        val uri = mockk<Uri>(relaxed = true, name = "new")
        store[uri] = StoredDocument(ByteArray(0))
        return uri
    }

    private fun contentOf(uri: Uri): String? = store[uri]?.content?.toString(Charsets.UTF_8)

    private fun encode(text: String): String =
        Base64.getEncoder().encodeToString(text.toByteArray())

    /** Starts a download of notes.md and answers its picker with [target]. */
    private fun startAndChoose(target: Uri): String {
        coordinator.onDownloadNamed("blob:notes", "notes.md")
        coordinator.onDownloadStart("blob:notes", null)
        coordinator.onDestinationChosen(pickerIds.last(), target)
        return readRequests.last()
    }

    /** Starts a download into [target] and has the page fail it before any byte is complete. */
    private fun failedSaveInto(target: Uri) {
        val id = startAndChoose(target)
        coordinator.onComplete(id, "the page cannot read this download")
    }

    /** Saves [text] over [target] from start to finish. */
    private fun save(target: Uri, text: String) {
        val id = startAndChoose(target)
        coordinator.onBytes(id, encode(text))
        coordinator.onComplete(id, null)
    }

    @Test
    fun `a shorter download saved over a longer document leaves exactly the download`() {
        val target = existingDocument("OLD LINE ONE\nOLD LINE TWO\n")
        val id = startAndChoose(target)

        coordinator.onBytes(id, encode("NEW\n"))
        coordinator.onComplete(id, null)

        assertEquals(listOf(DownloadOutcome.SAVED to "notes.md"), reported)
        assertEquals("NEW\n", contentOf(target),
            "a save reported as done has to hold the download and nothing of the old file")
    }

    /**
     * A coordinator whose provider thread runs only when the test says so, so that "the caller did
     * not wait" and "the work never happened" can be told apart. The queue is what the provider
     * thread has been handed and has not run.
     */
    private fun deferredCoordinator(queued: ArrayDeque<Runnable>) = DownloadCoordinator(
        host,
        providerWork = Executor { queued.add(it) },
        mainThread = Executor { it.run() },
        stagingDir = { stagingDir },
        stagingRoom = { stagingRoom },
    )

    /** Starts a download of notes.md, answers its picker with [target] and runs the open that follows. */
    private fun startAndChooseOn(deferred: DownloadCoordinator, queued: ArrayDeque<Runnable>, target: Uri): String {
        deferred.onDownloadNamed("blob:notes", "notes.md")
        deferred.onDownloadStart("blob:notes", null)
        deferred.onDestinationChosen(pickerIds.last(), target)
        queued.removeFirst().run()
        return readRequests.last()
    }

    @Test
    fun `finishing a save over an existing document hands the copy to the provider thread`() {
        val queued = ArrayDeque<Runnable>()
        val deferred = deferredCoordinator(queued)
        val target = existingDocument("OLD LINE ONE\nOLD LINE TWO\n")
        val id = startAndChooseOn(deferred, queued, target)
        deferred.onBytes(id, encode("NEW\n"))

        // The page's own thread is blocked until this returns, and the copy is the whole file.
        deferred.onComplete(id, null)

        assertEquals(1, queued.size, "the copy into the provider was not handed to its thread")
        assertEquals(emptyList<Pair<DownloadOutcome, String>>(), reported, "the save was reported before the copy ran")
        assertEquals("OLD LINE ONE\nOLD LINE TWO\n", contentOf(target), "the caller made the copy itself")

        queued.removeFirst().run()

        assertEquals(listOf(DownloadOutcome.SAVED to "notes.md"), reported)
        assertEquals("NEW\n", contentOf(target))
    }

    @Test
    fun `a copy that fails on the provider thread is reported as failed and leaves the document alone`() {
        val queued = ArrayDeque<Runnable>()
        val deferred = deferredCoordinator(queued)
        val target = existingDocument("KEEP ORIGINAL\n")
        val id = startAndChooseOn(deferred, queued, target)
        deferred.onBytes(id, encode("NEW\n"))
        openThrows = IOException("the provider went away")

        deferred.onComplete(id, null)
        queued.removeFirst().run()

        assertEquals(listOf(DownloadOutcome.FAILED to "notes.md"), reported)
        assertEquals("KEEP ORIGINAL\n", contentOf(target), "a copy that never started changed the document")
        assertTrue(queued.isEmpty(), "the failure left work for the provider thread: the stage is closed by the copy and the user's document is not discarded")
    }

    @Test
    fun `a page that goes away during the copy still gets its finished save kept`() {
        val queued = ArrayDeque<Runnable>()
        val deferred = deferredCoordinator(queued)
        val target = existingDocument("OLD\n")
        val id = startAndChooseOn(deferred, queued, target)
        deferred.onBytes(id, encode("NEW\n"))
        deferred.onComplete(id, null)

        // The renderer goes while the provider thread has the copy.
        deferred.onPageGone()
        while (queued.isNotEmpty()) queued.removeFirst().run()

        assertEquals("NEW\n", contentOf(target), "a save that finished was removed because the page went after it")
    }

    /**
     * The copy runs on the provider thread after the page's call has returned, so the page can be
     * replaced while it runs (a folder opened, a window reloaded, the screen made again). The person
     * confirmed the overwrite and the document is theirs: however the copy ends it is said.
     */
    @Test
    fun `a copy that fails after the page went away is still reported as failed`() {
        val queued = ArrayDeque<Runnable>()
        val deferred = deferredCoordinator(queued)
        // A provider that ignores the "t" of "wt" and cannot cut: the copy writes over the start of the file and fails at the cut.
        truncation = Truncation.IGNORED
        cutFails = true
        val target = existingDocument("OLD LINE ONE\nOLD LINE TWO\n")
        val id = startAndChooseOn(deferred, queued, target)
        deferred.onBytes(id, encode("NEW\n"))
        deferred.onComplete(id, null)

        deferred.onPageGone()
        while (queued.isNotEmpty()) queued.removeFirst().run()

        assertEquals(listOf(DownloadOutcome.FAILED to "notes.md"), reported, "the person was left with a damaged file and no word of it")
    }

    @Test
    fun `a copy refused for room after the page went away is still reported as failed`() {
        val queued = ArrayDeque<Runnable>()
        val deferred = deferredCoordinator(queued)
        val target = existingDocument("OLD\n")
        free = 10L
        val id = startAndChooseOn(deferred, queued, target)
        deferred.onBytes(id, encode("X".repeat(100)))
        deferred.onComplete(id, null)

        deferred.onPageGone()
        while (queued.isNotEmpty()) queued.removeFirst().run()

        assertEquals(listOf(DownloadOutcome.FAILED to "notes.md"), reported)
        assertEquals("OLD\n", contentOf(target), "a copy that was refused changed the document")
    }

    @Test
    fun `a copy that succeeds after the page went away is reported as saved`() {
        val queued = ArrayDeque<Runnable>()
        val deferred = deferredCoordinator(queued)
        val target = existingDocument("OLD\n")
        val id = startAndChooseOn(deferred, queued, target)
        deferred.onBytes(id, encode("NEW\n"))
        deferred.onComplete(id, null)

        deferred.onPageGone()
        while (queued.isNotEmpty()) queued.removeFirst().run()

        assertEquals(listOf(DownloadOutcome.SAVED to "notes.md"), reported)
        assertEquals("NEW\n", contentOf(target))
    }

    /**
     * The screen can be made again while the picker is up (a language change, process death), and
     * the answer then reaches a coordinator that has no download for it. The person chose a name and
     * confirmed it; nothing else tells them there is no file.
     */
    @Test
    fun `a picker answer for a download that is gone is reported by the name the person chose`() {
        val created = newDocument()
        names[created] = "report.md"

        coordinator.onDestinationChosen("gone", created)

        assertEquals(listOf(DownloadOutcome.FAILED to "report.md"), reported)
        assertEquals(null, contentOf(created), "the empty document the picker made stayed in the folder")
    }

    @Test
    fun `a picker answer for a download that is gone leaves a document the person had and says so`() {
        val existing = existingDocument("KEEP\n")
        names[existing] = "notes.md"

        coordinator.onDestinationChosen("gone", existing)

        assertEquals(listOf(DownloadOutcome.FAILED to "notes.md"), reported)
        assertEquals("KEEP\n", contentOf(existing))
    }

    @Test
    fun `a picker answer for a download that is gone, for a document the provider will not name, is not reported`() {
        val created = newDocument()

        coordinator.onDestinationChosen("gone", created)

        assertEquals(emptyList<Pair<DownloadOutcome, String>>(), reported)
        assertEquals(null, contentOf(created))
    }

    @Test
    fun `a cancelled picker for a download that is gone says nothing`() {
        coordinator.onDestinationChosen("gone", null)

        assertEquals(emptyList<Pair<DownloadOutcome, String>>(), reported)
    }

    @Test
    fun `a download that fails before it is complete leaves an existing document as it was`() {
        val target = existingDocument("KEEP ORIGINAL\n")
        val id = startAndChoose(target)

        coordinator.onBytes(id, encode("NEW"))
        coordinator.onComplete(id, "the page cannot read this download")

        assertEquals(listOf(DownloadOutcome.FAILED to "notes.md"), reported)
        assertEquals("KEEP ORIGINAL\n", contentOf(target),
            "the user had this file before the download began, and it did not finish")
    }

    @Test
    fun `a page that goes away before the download is complete leaves an existing document as it was`() {
        val target = existingDocument("KEEP ORIGINAL\n")
        val id = startAndChoose(target)

        coordinator.onBytes(id, encode("NEW"))
        coordinator.onPageGone()

        assertEquals("KEEP ORIGINAL\n", contentOf(target),
            "a renderer death or a reload in the middle of a download is not the user's " +
                "decision to lose the file they chose to replace")
    }

    @Test
    fun `a picker answer that comes after its download is gone leaves an existing document alone`() {
        coordinator.onDownloadNamed("blob:notes", "notes.md")
        coordinator.onDownloadStart("blob:notes", null)
        // The renderer dies while the picker is on screen; the user then picks an
        // existing file and confirms the overwrite.
        coordinator.onPageGone()
        val target = existingDocument("KEEP ORIGINAL\n")
        coordinator.onDestinationChosen(pickerIds.last(), target)

        assertEquals("KEEP ORIGINAL\n", contentOf(target),
            "nothing was ever written, so nothing may be removed")
    }

    @Test
    fun `a document the picker created is still removed when its download fails`() {
        val target = newDocument()
        val id = startAndChoose(target)

        coordinator.onBytes(id, encode("NEW"))
        coordinator.onComplete(id, "the page cannot read this download")

        assertEquals(null, contentOf(target),
            "an empty file wearing the name the user asked for looks like a finished download")
    }

    @Test
    fun `a document the picker created for a late answer is still removed`() {
        coordinator.onDownloadStart("blob:notes", null)
        coordinator.onPageGone()
        val target = newDocument()
        coordinator.onDestinationChosen(pickerIds.last(), target)

        assertEquals(null, contentOf(target))
    }

    @Test
    fun `a new document holds exactly the download`() {
        val target = newDocument()
        val id = startAndChoose(target)

        coordinator.onBytes(id, encode("fun main"))
        coordinator.onBytes(id, encode("() {}"))
        coordinator.onComplete(id, null)

        assertEquals(listOf(DownloadOutcome.SAVED to "notes.md"), reported)
        assertEquals("fun main() {}", contentOf(target))
        assertTrue(store.containsKey(target))
    }

    @Test
    fun `an existing document is not written to until the last byte has arrived`() {
        val target = existingDocument("KEEP ORIGINAL\n")
        val id = startAndChoose(target)

        coordinator.onBytes(id, encode("NEW"))

        assertEquals("KEEP ORIGINAL\n", contentOf(target),
            "a download can still fail at this point, so the file has to be untouched")
        assertEquals(emptyList<String>(), modes, "the document is not even opened yet")
        coordinator.onComplete(id, null)
        assertEquals("NEW", contentOf(target))
    }

    @Test
    fun `an empty document the user chose is removed when its download fails, which loses nothing`() {
        // Nothing tells it from the empty document the picker makes for a new name: no time,
        // no listing, only what a read-only descriptor shows.
        val target = existingDocument("")
        failedSaveInto(target)

        assertEquals(null, contentOf(target))
    }

    @Test
    fun `a document that cannot be measured is staged, and kept when the download fails`() {
        for (setUp in listOf({ reads = false }, { readsAsPipe = true })) {
            setUp()
            val target = existingDocument("")
            val id = startAndChoose(target)
            coordinator.onBytes(id, encode("NEW"))
            assertEquals(emptyList<String>(), modes, "a document that cannot be measured is not written early")
            coordinator.onComplete(id, "the page cannot read this download")

            assertEquals("", contentOf(target), "and it is the user's, so it is never removed")
            reads = true
            readsAsPipe = false
        }
    }

    @Test
    fun `a document is measured before anything opens it for writing`() {
        // A provider that empties a document on any open for writing would otherwise make a
        // document with bytes look like the empty one a new name gets, and the failure below
        // would remove it.
        emptiesOnWrite = true
        val target = existingDocument("KEEP ORIGINAL\n")
        failedSaveInto(target)

        assertEquals("KEEP ORIGINAL\n", contentOf(target))
    }

    @Test
    fun `a late answer removes an empty document and keeps one with bytes`() {
        val empty = existingDocument("")
        coordinator.onDownloadStart("blob:a", null)
        coordinator.onPageGone()
        coordinator.onDestinationChosen(pickerIds.last(), empty)
        val full = existingDocument("KEEP ORIGINAL\n")
        coordinator.onDownloadStart("blob:b", null)
        coordinator.onPageGone()
        coordinator.onDestinationChosen(pickerIds.last(), full)

        assertEquals(null, contentOf(empty))
        assertEquals("KEEP ORIGINAL\n", contentOf(full))
    }

    @Test
    fun `an answer handed to a recreated screen removes an empty document and keeps one with bytes`() {
        // The new coordinator has no request and no picker of its own to compare the answer with.
        val empty = existingDocument("")
        val full = existingDocument("KEEP ORIGINAL\n")
        val fresh = DownloadCoordinator(host, Executor { it.run() }, Executor { it.run() }, stagingDir = { stagingDir })
        fresh.onDestinationChosen(null, empty)
        fresh.onDestinationChosen(null, full)

        assertEquals(null, contentOf(empty))
        assertEquals("KEEP ORIGINAL\n", contentOf(full))
    }

    @Test
    fun `a second save that fails keeps what the first one wrote`() {
        val target = newDocument()
        save(target, "FIRST")
        failedSaveInto(target)
        assertEquals("FIRST", contentOf(target))

        // A first save of nothing leaves an empty document, which the failed second one removes.
        val emptied = newDocument()
        save(emptied, "")
        failedSaveInto(emptied)
        assertEquals(null, contentOf(emptied))
    }

    @Test
    fun `a provider that refuses the truncating mode still ends with exactly the download`() {
        truncation = Truncation.REFUSED
        val target = existingDocument("OLD LINE ONE\nOLD LINE TWO\n")

        save(target, "NEW\n")

        assertEquals(listOf(DownloadOutcome.SAVED to "notes.md"), reported)
        assertEquals("NEW\n", contentOf(target))
        assertEquals(listOf("wt", "w"), modes, "asked to truncate, then without the t it refused")
    }

    @Test
    fun `a provider that ignores truncation is cut to the length of the download`() {
        truncation = Truncation.IGNORED
        val target = existingDocument("OLD LINE ONE\nOLD LINE TWO\n")

        save(target, "NEW\n")

        assertEquals(listOf(DownloadOutcome.SAVED to "notes.md"), reported)
        assertEquals("NEW\n", contentOf(target))
    }

    @Test
    fun `a document that cannot be cut to the length of the download is not reported saved`() {
        truncation = Truncation.IGNORED
        cutFails = true
        val target = existingDocument("OLD LINE ONE\nOLD LINE TWO\n")

        save(target, "NEW\n")

        assertEquals(listOf(DownloadOutcome.FAILED to "notes.md"), reported,
            "a document holding the download followed by the old file's tail is not a save")
        assertTrue(store.containsKey(target), "and the user's file is not removed for it")
    }

    @Test
    fun `a provider that hands back a pipe receives the whole download`() {
        pipes = true
        val target = existingDocument("OLD LINE ONE\nOLD LINE TWO\n")

        save(target, "NEW\n")

        assertEquals(listOf(DownloadOutcome.SAVED to "notes.md"), reported)
        assertEquals("NEW\n", contentOf(target))
    }

    @Test
    fun `nothing is left in app storage by a download over an existing document`() {
        save(existingDocument("OLD\n"), "NEW\n")
        val failing = startAndChoose(existingDocument("OLD\n"))
        coordinator.onBytes(failing, encode("NEW"))
        coordinator.onComplete(failing, "the page cannot read this download")

        assertEquals(emptyList<String>(), stagingDir.list()!!.toList(),
            "the stage is unlinked as soon as it is open, so neither ending leaves a file behind")
    }

    @Test
    fun `a save over a document its file system has no room for fails and leaves it untouched`() {
        free = 0L
        val target = existingDocument("ORIGINAL CONTENT")

        save(target, "REPLACEMENT THAT IS LONGER")

        assertEquals(listOf(DownloadOutcome.FAILED to "notes.md"), reported)
        assertEquals("ORIGINAL CONTENT", contentOf(target))
        assertEquals(emptyList<String>(), modes, "the document is not even opened for writing")
    }

    @Test
    fun `the room a replaced document gives back is counted`() {
        free = 10L
        val target = existingDocument("ORIGINAL CONTENT")

        save(target, "REPLACEMENT THAT IS LONGER")

        assertEquals(listOf(DownloadOutcome.SAVED to "notes.md"), reported)
        assertEquals("REPLACEMENT THAT IS LONGER", contentOf(target))
    }

    @Test
    fun `only the blocks a document occupies are counted, never its length`() {
        free = 10L
        val sparse = existingDocument("")
        store[sparse]!!.apply { content = ByteArray(4096); sparseOccupied = 8L }
        save(sparse, "REPLACEMENT THAT IS LONGER")
        assertEquals(4096, store[sparse]!!.content.size, "a sparse file is credited with its blocks")

        // Blocks the provider does not report earn nothing, though this file has 16 of them.
        val unknown = existingDocument("ORIGINAL CONTENT")
        store[unknown]!!.occupiedUnknown = true
        save(unknown, "REPLACEMENT THAT IS LONGER")
        assertEquals("ORIGINAL CONTENT", contentOf(unknown))

        assertEquals(List(2) { DownloadOutcome.FAILED to "notes.md" }, reported)
        assertEquals(emptyList<String>(), modes, "neither was opened for writing")
    }

    @Test
    fun `a document another writer shortened before the check is not reported saved`() {
        val target = existingDocument("ORIGINAL CONTENT")
        // Another writer cuts the document between the copy and the length check.
        beforeCheck = { store[target]!!.content = store[target]!!.content.copyOf(2) }

        save(target, "REPLACEMENT")

        assertEquals(listOf(DownloadOutcome.FAILED to "notes.md"), reported,
            "growing it back to length would report zeros as the download")
        assertEquals("RE", contentOf(target), "and the document is not padded with them")
    }

    @Test
    fun `a new document is opened once, in mode w, as it always was`() {
        save(newDocument(), "NEW\n")

        assertEquals(listOf(DownloadOutcome.SAVED to "notes.md"), reported)
        assertEquals(listOf("w"), modes, "an empty document has nothing for a t to drop, and a provider that refuses wt must not cost a new name its save")
    }

    @Test
    fun `an open that fails for any reason is tried once more without the t, and a second failure leaves the document as it was`() {
        openThrows = FileNotFoundException("open failed: EACCES (Permission denied)")
        val target = existingDocument("KEEP ORIGINAL\n")

        save(target, "NEW\n")

        assertEquals(listOf("wt", "w"), modes, "nothing tells a refused mode from another failed open")
        assertEquals(listOf(DownloadOutcome.FAILED to "notes.md"), reported)
        assertEquals("KEEP ORIGINAL\n", contentOf(target))
    }

    @Test
    fun `a save over an existing document fails before staging takes app storage below its floor`() {
        stagingRoom = StorageManager.LOW_STORAGE_BYTES + 5
        val target = existingDocument("KEEP ORIGINAL\n")
        val id = startAndChoose(target)

        assertEquals(false, coordinator.onBytes(id, encode("MORE THAN FIVE BYTES")),
            "the page stops reading")
        assertEquals(listOf(DownloadOutcome.FAILED to "notes.md"), reported)
        assertEquals("KEEP ORIGINAL\n", contentOf(target))
        assertEquals(emptyList<String>(), modes, "the document was never opened for writing")
    }

    @Test
    fun `a one byte overwrite succeeds with 200 MiB available in app storage`() {
        stagingRoom = 200L * 1024 * 1024
        val target = existingDocument("KEEP ORIGINAL\n")

        save(target, "x")

        assertEquals(listOf(DownloadOutcome.SAVED to "notes.md"), reported)
        assertEquals("x", contentOf(target))
    }

    @Test
    fun `staging accepts the byte at the low storage floor but not below it`() {
        stagingRoom = StorageManager.LOW_STORAGE_BYTES + 1
        val target = existingDocument("KEEP ORIGINAL\n")
        val id = startAndChoose(target)

        assertEquals(true, coordinator.onBytes(id, encode("x")))
        coordinator.onComplete(id, null)
        assertEquals(listOf(DownloadOutcome.SAVED to "notes.md"), reported)
        assertEquals("x", contentOf(target))

        stagingRoom = StorageManager.LOW_STORAGE_BYTES
        val second = existingDocument("KEEP SECOND\n")
        val next = startAndChoose(second)
        assertEquals(false, coordinator.onBytes(next, encode("y")))
        assertEquals(DownloadOutcome.FAILED, reported.last().first)
        assertEquals("KEEP SECOND\n", contentOf(second))
    }

    @Test
    fun `a save to a new name is written as before however full app storage is`() {
        stagingRoom = 0L
        val target = newDocument()

        save(target, "NEW\n")

        assertEquals(listOf(DownloadOutcome.SAVED to "notes.md"), reported)
        assertEquals("NEW\n", contentOf(target))
    }
}
