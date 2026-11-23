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
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * A delete, a create and a rename wait a bounded time for another folder's write to the same
 * provider, instead of being refused with a notice.
 *
 * Writes to the folders of one provider authority share one claim, held for the whole write.
 * A save that meets it is held back and tried again on its own, but the other three were
 * refused and had to be repeated by the user, which showed when a closing folder's write was
 * still running as the next folder opened. They now take the claim with a wait, and the wait
 * has two limits: a ceiling, so a provider call that never returns costs a notice and not a
 * thread, and once per holder, so the drain of a removed tree does not pay the ceiling for
 * every file in it.
 *
 * The first half drives the claim itself, by reflection, because it is private to the
 * engine's companion. The second half drives a delete and a create through a real engine
 * with another folder's claim held, because the claim means nothing until the paths use it.
 */
class SafProviderClaimWaitTest {

    @TempDir
    lateinit var mirror: File

    @TempDir
    lateinit var filesDir: File

    private val companion = SafSyncEngine.Companion

    private fun uri(authority: String) = mockk<Uri>(relaxed = true).also {
        every { it.authority } returns authority
    }

    private fun tryClaim(uri: Uri): Any? = companion.javaClass
        .getDeclaredMethod("claimProviderMutation", Uri::class.java)
        .apply { isAccessible = true }.invoke(companion, uri)

    private fun claimWithin(uri: Uri, waitMs: Long): Any? = companion.javaClass
        .getDeclaredMethod("claimProviderMutationWithin", Uri::class.java, Long::class.javaPrimitiveType)
        .apply { isAccessible = true }.invoke(companion, uri, waitMs)

    private fun release(mutation: Any) {
        companion.javaClass.getDeclaredMethod("releaseProviderMutation", mutation.javaClass)
            .apply { isAccessible = true }.invoke(companion, mutation)
    }

    private val held = mutableListOf<Any>()

    /** Takes the claim of [authority] for the test, as another folder's write does. */
    private fun hold(authority: String): Any = (tryClaim(uri(authority)) ?: error("the claim was already held"))
        .also { held.add(it) }

    private fun releaseAfter(mutation: Any, delayMs: Long) = Thread {
        Thread.sleep(delayMs)
        release(mutation)
    }.apply { isDaemon = true; start() }

    private inline fun <T> timed(block: () -> T): Pair<T, Long> {
        val start = System.nanoTime()
        val result = block()
        return result to (System.nanoTime() - start) / 1_000_000
    }

    @BeforeEach
    fun setUpClaims() {
        held.clear()
        mockkObject(Logger)
        every { Logger.i(any(), any()) } just Runs
        every { Logger.d(any(), any()) } just Runs
        every { Logger.w(any(), any()) } just Runs
        every { Logger.w(any(), any(), any()) } just Runs
        every { Logger.e(any(), any()) } just Runs
        every { Logger.e(any(), any(), any()) } just Runs
    }

    @AfterEach
    fun tearDownClaims() {
        // A claim a failing case left behind would refuse every later one in this JVM.
        held.forEach { runCatching { release(it) } }
        // The record and its mark sit beside the copy, outside the temporary folder that is removed with it.
        File(mirror.path + SafSyncEngine.SYNCED_RECORD_SUFFIX).delete()
        File(mirror.path + SafSyncEngine.SORTED_RECORD_SUFFIX).delete()
        unmockkAll()
    }

    @Test
    fun `a free claim is taken at once`() {
        val test = uri("test")
        val (claim, ms) = timed { claimWithin(test, 5_000) }

        assertNotNull(claim)
        held.add(claim!!)
        assertTrue(ms < 500, "a free claim waited $ms ms")
    }

    @Test
    fun `a claim that comes free during the wait is taken, and promptly`() {
        val mutation = hold("test")
        releaseAfter(mutation, 300)
        val test = uri("test")

        val (claim, ms) = timed { claimWithin(test, 5_000) }

        assertNotNull(claim, "the waiter was refused although the holder let go within the wait")
        held.add(claim!!)
        assertTrue(ms >= 200, "the claim was taken before its holder let go ($ms ms)")
        assertTrue(ms < 2_000, "the release did not wake the waiter: it took $ms ms of a 5000 ms wait")
    }

    @Test
    fun `a claim that stays held is refused once the wait is over`() {
        hold("test")
        val test = uri("test")

        val (claim, ms) = timed { claimWithin(test, 300) }

        assertNull(claim)
        assertTrue(ms >= 250, "the waiter gave up after $ms ms of a 300 ms wait")
        assertTrue(ms < 2_000, "the wait ran on to $ms ms")
    }

    /** What keeps the drain of a removed tree from paying the ceiling for every file in it. */
    @Test
    fun `after one waiter has given up the next is refused at once`() {
        hold("test")
        assertNull(claimWithin(uri("test"), 300))
        val test = uri("test")

        val (second, ms) = timed { claimWithin(test, 10_000) }

        assertNull(second)
        assertTrue(ms < 200, "the second waiter waited $ms ms for a holder the first had already given up on")
    }

    /** The mark belongs to a holder, not to the provider: whoever claims next is waited for again. */
    @Test
    fun `a new holder is waited for again`() {
        val first = hold("test")
        assertNull(claimWithin(uri("test"), 200))
        release(first)
        held.remove(first)
        hold("test")
        val test = uri("test")

        val (claim, ms) = timed { claimWithin(test, 300) }

        assertNull(claim)
        assertTrue(ms >= 250, "a new holder was refused without being waited for ($ms ms)")
    }

    /** A zero wait is the old refusal, and does not mark the holder: nothing waited. */
    @Test
    fun `a zero wait refuses at once without marking the holder`() {
        hold("test")
        assertNull(claimWithin(uri("test"), 0))
        val test = uri("test")

        val (claim, ms) = timed { claimWithin(test, 300) }

        assertNull(claim)
        assertTrue(ms >= 250, "a zero wait marked the holder as given up on, so the next waiter did not wait ($ms ms)")
    }

    /**
     * A waiter whose time expires after a handover must not mark the new holder. The handover waits until the waiter
     * is inside its wait on the first holder: on a timer, a waiter slow to get there saw the second holder first and
     * marked it, as it should, and the case failed for a reason it does not test.
     */
    @Test
    fun `a waiter whose time runs out after a handover does not mark the new holder`() {
        val first = hold("test")
        val mutations = SafSyncEngine::class.java.getDeclaredField("providerMutations")
            .apply { isAccessible = true }.get(null)!!
        val test = uri("test")
        val waiter = Thread.currentThread()
        val second = AtomicReference<Any?>()
        val handover = Thread {
            waitUntilInClaimWait(waiter)
            synchronized(mutations) {
                release(first)
                second.set(tryClaim(test))
            }
        }.apply { isDaemon = true; start() }

        try {
            assertNull(claimWithin(test, 300))
            handover.join(2_000)
            assertNotNull(second.get(), "handover failed to claim the provider")

            val (third, ms) = timed { claimWithin(test, 300) }

            assertNull(third)
            assertTrue(
                ms >= 250,
                "the new holder was marked as given up on although no waiter waited a full turn on it ($ms ms)",
            )
        } finally {
            handover.join(2_000)
            second.get()?.let { runCatching { release(it) } }
        }
    }

    @Test
    fun `another authority is not held up`() {
        hold("test")
        val other = uri("other")

        val (claim, ms) = timed { claimWithin(other, 5_000) }

        assertNotNull(claim)
        held.add(claim!!)
        assertTrue(ms < 500, "a claim on another authority waited $ms ms")
    }

    /** The thread is a write-back drain, and an interrupt is it being told to stop. */
    @Test
    fun `an interrupt ends the wait as a refusal and is handed back`() {
        hold("test")
        val result = AtomicReference<Any?>("unset")
        val stillInterrupted = AtomicBoolean(false)
        val waiter = Thread {
            result.set(claimWithin(uri("test"), 10_000))
            stillInterrupted.set(Thread.currentThread().isInterrupted)
        }.apply { isDaemon = true; start() }
        Thread.sleep(150)

        waiter.interrupt()
        waiter.join(3_000)

        assertFalse(waiter.isAlive, "the interrupt did not end the wait")
        assertNull(result.get())
        assertTrue(stillInterrupted.get(), "the interrupt was swallowed")
    }

    // -- through a real engine --------------------------------------------------------------

    private lateinit var resolver: ContentResolver
    private lateinit var context: Context
    private lateinit var treeUri: Uri
    private lateinit var document: Uri

    private fun engineOnDevice(): SafSyncEngine {
        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } answers { System.nanoTime() / 1_000_000 }
        mockkStatic(DocumentsContract::class)
        treeUri = mockk(relaxed = true)
        document = mockk<Uri>(relaxed = true).also {
            every { it.toString() } returns "content://test/tree/t/document/notes"
            every { it.authority } returns "test"
        }
        every { DocumentsContract.getTreeDocumentId(any()) } returns "root"
        every { DocumentsContract.buildChildDocumentsUriUsingTree(any(), any()) } returns mockk(relaxed = true)
        every { DocumentsContract.buildDocumentUriUsingTree(any(), any()) } returns document
        every { DocumentsContract.getDocumentId(any()) } returns "doc:notes.md"

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
        every { cursor.getString(0) } returns "doc:notes.md"
        every { cursor.getString(1) } returns "notes.md"
        every { cursor.getString(2) } returns "text/plain"
        every { cursor.getLong(3) } returns 8L
        every { cursor.getLong(4) } returns 1_700_000_000_000L

        resolver = mockk(relaxed = true)
        every { resolver.query(any(), any(), any(), any(), any()) } answers {
            row = -1
            cursor
        }
        every { resolver.openInputStream(any()) } answers { ByteArrayInputStream("12345678".toByteArray()) }
        every { resolver.openOutputStream(any(), any()) } answers { ByteArrayOutputStream() }
        context = mockk<Context>(relaxed = true)
        every { context.contentResolver } returns resolver
        every { context.filesDir } returns filesDir
        return SafSyncEngine(context)
    }

    /**
     * What the delete does with the device document, and which notice the user got: the provider
     * refusing the delete, or the engine keeping the document on the device without asking.
     */
    private class DeleteOutcome(
        val deleted: AtomicBoolean = AtomicBoolean(),
        val refused: AtomicBoolean = AtomicBoolean(),
        val kept: AtomicBoolean = AtomicBoolean(),
        val busy: AtomicBoolean = AtomicBoolean(),
    )

    private fun deleteWith(engine: SafSyncEngine, outcome: DeleteOutcome) {
        runBlocking { engine.initialSync(treeUri, mirror) { _, _ -> } }
        val file = File(mirror, "notes.md")
        assertTrue(file.exists(), "the fixture's folder did not import the document")
        every { DocumentsContract.deleteDocument(resolver, document) } answers {
            outcome.deleted.set(true)
            true
        }
        engine.onDeleteRefused = { outcome.refused.set(true) }
        engine.onDeleteBusy = { outcome.busy.set(true) }
        engine.onKeptOnDevice = { _, _, _ -> outcome.kept.set(true) }
        assertTrue(file.delete())
        engine.handleMirrorEvent(FileObserver.DELETE, file, mirror, treeUri)
        engine.runWriteBackLoop { false }
    }

    @Test
    fun `a delete that meets another folder's write waits for it and goes through`() {
        val engine = engineOnDevice()
        engine.providerClaimWaitMs = 5_000
        val outcome = DeleteOutcome()
        runBlocking { engine.initialSync(treeUri, mirror) { _, _ -> } }
        val mutation = hold("test")
        releaseAfter(mutation, 400)

        val (_, ms) = timed { deleteWith(engine, outcome) }

        assertTrue(outcome.deleted.get(), "the delete was refused although the other write ended within the wait")
        assertFalse(outcome.refused.get() || outcome.kept.get() || outcome.busy.get(), "the user was told about a delete that went through")
        assertTrue(ms >= 300, "the delete did not wait for the claim ($ms ms)")
    }

    /**
     * A delete that gave up on a busy claim was announced in the wording for a device folder that
     * refused it, which tells the person to go and delete the file in their file manager. Nothing
     * refused it: another write to the same storage was still running. It has a notice of its own.
     */
    @Test
    fun `a delete is announced as not done yet when the claim stays held past the wait`() {
        val engine = engineOnDevice()
        engine.providerClaimWaitMs = 300
        val outcome = DeleteOutcome()
        hold("test")

        deleteWith(engine, outcome)

        assertFalse(outcome.deleted.get(), "the device document was deleted without the claim")
        assertTrue(outcome.busy.get(), "a delete given up on a busy provider was not announced")
        assertFalse(outcome.refused.get(), "a delete given up on a busy provider was announced as refused by the device folder")
        assertFalse(outcome.kept.get(), "a delete refused while the provider is busy reported that another app may have changed the document")
    }

    @Test
    fun `a busy delete does not mark the file so a parent folder delete goes through once free`() {
        val engine = engineOnDevice()
        engine.providerClaimWaitMs = 200
        fun uriWithId(id: String) = mockk<Uri>(relaxed = true).also {
            every { it.authority } returns "test"
            every { it.toString() } returns "doc-uri:$id"
        }
        every { DocumentsContract.buildChildDocumentsUriUsingTree(any(), any()) } answers {
            uriWithId("children:${secondArg<String>()}")
        }
        every { DocumentsContract.buildDocumentUriUsingTree(any(), any()) } answers {
            uriWithId(secondArg<String>())
        }
        every { DocumentsContract.getDocumentId(any()) } answers {
            firstArg<Uri>().toString().removePrefix("doc-uri:")
        }
        every { resolver.query(any(), any(), any(), any(), any()) } answers {
            val id = firstArg<Uri>().toString().removePrefix("doc-uri:")
            val entries = when (id) {
                "children:root" -> listOf(Triple("doc:sub", "sub", DocumentsContract.Document.MIME_TYPE_DIR))
                "children:doc:sub" -> listOf(Triple("doc:sub/notes.md", "notes.md", "text/plain"))
                "doc:sub" -> listOf(Triple("doc:sub", "sub", DocumentsContract.Document.MIME_TYPE_DIR))
                else -> emptyList()
            }
            var idx = -1
            mockk<Cursor>(relaxed = true) {
                every { moveToFirst() } answers {
                    idx = 0
                    entries.isNotEmpty()
                }
                every { moveToNext() } answers { ++idx < entries.size }
                every { getColumnIndexOrThrow(any()) } answers {
                    when (firstArg<String>()) {
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID -> 0
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME -> 1
                        DocumentsContract.Document.COLUMN_MIME_TYPE -> 2
                        else -> 3
                    }
                }
                every { getColumnIndex(any()) } answers {
                    when (firstArg<String>()) {
                        DocumentsContract.Document.COLUMN_MIME_TYPE -> 2
                        DocumentsContract.Document.COLUMN_LAST_MODIFIED -> 4
                        else -> -1
                    }
                }
                every { isNull(any<Int>()) } returns false
                every { getString(0) } answers { entries[idx].first }
                every { getString(1) } answers { entries[idx].second }
                every { getString(2) } answers { entries[idx].third }
                every { getLong(3) } returns 8L
                every { getLong(4) } returns 1_700_000_000_000L
            }
        }
        val deletedIds = mutableListOf<String>()
        every { DocumentsContract.deleteDocument(any(), any()) } answers {
            deletedIds += secondArg<Uri>().toString().removePrefix("doc-uri:")
            true
        }
        val refused = mutableListOf<String>()
        val busy = mutableListOf<String>()
        val kept = mutableListOf<String>()
        engine.onDeleteRefused = { refused += it.name }
        engine.onDeleteBusy = { busy += it.name }
        engine.onKeptOnDevice = { f, _, _ -> kept += f.name }

        runBlocking { engine.initialSync(treeUri, mirror) { _, _ -> } }
        val file = File(mirror, "sub/notes.md")
        val sub = File(mirror, "sub")
        assertTrue(file.isFile)

        val claim = hold("test")
        assertTrue(file.delete())
        engine.handleMirrorEvent(FileObserver.DELETE, file, mirror, treeUri)
        engine.runWriteBackLoop { false }
        assertEquals(listOf("notes.md"), busy)
        assertTrue(refused.isEmpty(), "busy delete was announced as refused by the device folder")
        assertTrue(kept.isEmpty(), "busy delete recorded kept-on-device notice")

        release(claim)
        held.remove(claim)
        assertTrue(sub.delete())
        engine.handleMirrorEvent(FileObserver.DELETE or 0x40000000, sub, mirror, treeUri)
        engine.runWriteBackLoop { false }

        assertTrue(kept.isEmpty(), "parent folder delete was blocked by an earlier busy file delete")
        assertEquals(listOf("doc:sub"), deletedIds)
    }

    @Test
    fun `a create that meets another folder's write waits for it and goes through`() {
        val engine = engineOnDevice()
        engine.providerClaimWaitMs = 5_000
        val created = AtomicBoolean()
        val failed = AtomicBoolean()
        every { DocumentsContract.createDocument(any(), any(), any(), any()) } answers {
            created.set(true)
            document
        }
        engine.onWriteBackFailed = { failed.set(true) }
        val file = File(mirror, "fresh.txt").apply { writeText("new") }
        val mutation = hold("test")
        releaseAfter(mutation, 400)

        val (_, ms) = timed {
            engine.handleMirrorEvent(FileObserver.CREATE, file, mirror, treeUri)
            engine.runWriteBackLoop { false }
        }

        assertTrue(created.get(), "the create was refused although the other write ended within the wait")
        assertFalse(failed.get(), "the user was told a file that reached the device had not")
        assertTrue(ms >= 300, "the create did not wait for the claim ($ms ms)")
    }

    @Test
    fun `a create is announced as kept in the app when the claim stays held past the wait`() {
        val engine = engineOnDevice()
        engine.providerClaimWaitMs = 300
        val created = AtomicBoolean()
        val failed = AtomicBoolean()
        every { DocumentsContract.createDocument(any(), any(), any(), any()) } answers {
            created.set(true)
            document
        }
        engine.onWriteBackFailed = { failed.set(true) }
        val file = File(mirror, "fresh.txt").apply { writeText("new") }
        hold("test")

        engine.handleMirrorEvent(FileObserver.CREATE, file, mirror, treeUri)
        engine.runWriteBackLoop { false }

        assertFalse(created.get(), "a document was created without the claim")
        assertTrue(failed.get(), "the refusal was not announced")
    }

    /**
     * Waits until [thread] is parked in the claim's wait, which is the one timed wait these drains make while
     * nobody else holds the mirror's turn.
     */
    private fun waitingForClaim(thread: Thread): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            if (thread.state == Thread.State.TIMED_WAITING) return true
            Thread.sleep(5)
        }
        return false
    }

    /**
     * A delete takes the provider claim before the mirror's turn, as a save and a rename do, so the
     * order is the same on every path. Taken inside the turn, a delete held the mirror for the whole
     * wait while a save of the same mirror held the claim and waited for that turn: two drains of one
     * mirror, which a busy reopen leaves, stalled each other until the wait ran out, the delete was
     * refused, and the holder, marked as waited out, had every other mutation of that provider refused
     * at once until it let go.
     *
     * Read off the thread rather than raced: the claim is held here, and while the delete waits for it
     * the mirror's turn must be free.
     */
    @Test
    fun `a delete waiting for the provider claim does not hold the mirror's turn`() {
        val engine = engineOnDevice()
        engine.providerClaimWaitMs = 10_000
        runBlocking { engine.initialSync(treeUri, mirror) { _, _ -> } }
        val deleted = AtomicBoolean()
        every { DocumentsContract.deleteDocument(resolver, document) } answers {
            deleted.set(true)
            true
        }
        val file = File(mirror, "notes.md")
        assertTrue(file.delete())
        engine.handleMirrorEvent(FileObserver.DELETE, file, mirror, treeUri)
        val claim = hold("test")
        val drain = Thread { engine.runWriteBackLoop { false } }.apply { isDaemon = true; start() }
        try {
            assertTrue(waitingForClaim(drain), "the delete never came to wait for the claim")
            assertFalse(turnOf(mirror).isLocked, "the delete held the mirror's turn while it waited for the provider claim")
        } finally {
            release(claim)
            held.remove(claim)
            drain.join(10_000)
        }
        assertFalse(drain.isAlive)
        assertTrue(deleted.get(), "the delete did not go through once the claim was free")
    }

    /** The same for a create, which asks for the claim of the folder it makes the document in. */
    @Test
    fun `a create waiting for the provider claim does not hold the mirror's turn`() {
        val engine = engineOnDevice()
        engine.providerClaimWaitMs = 10_000
        val created = AtomicBoolean()
        every { DocumentsContract.createDocument(any(), any(), any(), any()) } answers {
            created.set(true)
            document
        }
        val file = File(mirror, "fresh.txt").apply { writeText("new") }
        engine.handleMirrorEvent(FileObserver.CREATE, file, mirror, treeUri)
        val claim = hold("test")
        val drain = Thread { engine.runWriteBackLoop { false } }.apply { isDaemon = true; start() }
        try {
            assertTrue(waitingForClaim(drain), "the create never came to wait for the claim")
            assertFalse(turnOf(mirror).isLocked, "the create held the mirror's turn while it waited for the provider claim")
        } finally {
            release(claim)
            held.remove(claim)
            drain.join(10_000)
        }
        assertFalse(drain.isAlive)
        assertTrue(created.get(), "the create did not go through once the claim was free")
    }

    /**
     * Saves are not among them. A save that meets the claim is held back and retried by
     * [SafSyncEngine.retryHeldBack] on its own schedule, and making it wait here would hold the
     * mirror's turn for nothing.
     */
    @Test
    fun `only the paths a user repeats wait, and a save is still held back`() {
        val engineFiles = listOf(
            "SafSyncEngine.kt",
            "SafSyncModels.kt",
            "SafSyncRecordStore.kt",
            "SafUploadJournal.kt",
            "SafDocumentClient.kt",
            "SafInboundCopier.kt",
            "SafOutboundWriter.kt",
            "SafMirrorWatcher.kt",
        ).map { File("src/main/kotlin/com/vscodroid/storage/$it") }
        val source = engineFiles.flatMap { it.readLines() }
            .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") || it.trimStart().startsWith("/*") }
        val waiting = source.count {
            it.contains("claimProviderMutationWithin(") &&
                !it.contains("private fun") &&
                !it.contains("internal fun")
        }
        val trying = source.count {
            it.contains("claimProviderMutation(") &&
                !it.contains("private fun") &&
                !it.contains("internal fun")
        }

        assertEquals(3, waiting, "a delete, a create and a rename take the claim with a wait")
        assertEquals(3, trying, "the two open-time repairs and the save still take it without waiting")
    }

    private class RenameFixture(
        val engine: SafSyncEngine,
        val renamedCalls: MutableList<Pair<String, String>>,
        val createdCalls: MutableList<Pair<String, String>>,
        val failedNames: MutableList<String>,
        val renamedOnDevice: AtomicBoolean,
        val blockOtherWrite: AtomicBoolean,
        val otherWriteStarted: CountDownLatch,
        val allowOtherWriteFinish: CountDownLatch,
        val holdRename: AtomicBoolean,
        val renameInside: CountDownLatch,
        val releaseRename: CountDownLatch,
        val writtenDocs: MutableList<String>,
    )

    private fun setupRenameFixture(): RenameFixture {
        val engine = engineOnDevice()
        fun uriWithId(id: String) = mockk<Uri>(relaxed = true).also {
            every { it.authority } returns "test"
            every { it.toString() } returns "doc-uri:$id"
        }
        every { DocumentsContract.buildChildDocumentsUriUsingTree(any(), any()) } answers {
            uriWithId("children:${secondArg<String>()}")
        }
        every { DocumentsContract.buildDocumentUriUsingTree(any(), any()) } answers {
            uriWithId(secondArg<String>())
        }
        every { DocumentsContract.getDocumentId(any()) } answers {
            firstArg<Uri>().toString().removePrefix("doc-uri:")
        }
        val renamedOnDevice = AtomicBoolean(false)
        every { resolver.query(any(), any(), any(), any(), any()) } answers {
            val uriStr = firstArg<Uri>().toString().removePrefix("doc-uri:")
            if (!uriStr.startsWith("children:")) {
                val displayName = uriStr.removePrefix("doc:")
                var moved = false
                return@answers mockk<Cursor>(relaxed = true) {
                    every { moveToFirst() } returns true
                    every { moveToNext() } answers { !moved.also { moved = true } }
                    every { getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME) } returns 0
                    every { getString(0) } returns displayName
                }
            }
            val entries = when (uriStr.removePrefix("children:")) {
                "root" -> listOf(
                    Triple(
                        if (renamedOnDevice.get()) "doc:renamed" else "doc:old",
                        if (renamedOnDevice.get()) "renamed" else "old",
                        DocumentsContract.Document.MIME_TYPE_DIR,
                    ),
                    Triple("doc:other.txt", "other.txt", "text/plain"),
                )
                "doc:old", "doc:renamed" -> listOf(Triple("doc:old/child.txt", "child.txt", "text/plain"))
                else -> emptyList()
            }
            var idx = -1
            mockk<Cursor>(relaxed = true) {
                every { moveToNext() } answers { ++idx < entries.size }
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
                every { getString(0) } answers { entries[idx].first }
                every { getString(1) } answers { entries[idx].second }
                every { getString(2) } answers { entries[idx].third }
                every { getLong(3) } returns 8L
                every { getLong(4) } returns 1_700_000_000_000L
            }
        }
        val renamedCalls = mutableListOf<Pair<String, String>>()
        val createdCalls = mutableListOf<Pair<String, String>>()
        val failedNames = mutableListOf<String>()
        val holdRename = AtomicBoolean(false)
        val renameInside = CountDownLatch(1)
        val releaseRename = CountDownLatch(1)
        val writtenDocs = java.util.concurrent.CopyOnWriteArrayList<String>()
        every { DocumentsContract.renameDocument(any(), any(), any()) } answers {
            val docId = secondArg<Uri>().toString().removePrefix("doc-uri:")
            val targetName = thirdArg<String>()
            if (holdRename.get()) {
                renameInside.countDown()
                releaseRename.await(15, TimeUnit.SECONDS)
            }
            renamedCalls += docId to targetName
            renamedOnDevice.set(true)
            uriWithId("doc:$targetName")
        }
        every { DocumentsContract.createDocument(any(), any(), any(), any()) } answers {
            val parentId = secondArg<Uri>().toString().removePrefix("doc-uri:")
            val displayName = arg<String>(3)
            createdCalls += parentId to displayName
            uriWithId("doc:$displayName")
        }
        val blockOtherWrite = AtomicBoolean(false)
        val otherWriteStarted = CountDownLatch(1)
        val allowOtherWriteFinish = CountDownLatch(1)
        every { resolver.openOutputStream(any(), any()) } answers {
            val docId = firstArg<Uri>().toString().removePrefix("doc-uri:")
            if (blockOtherWrite.get() && docId == "doc:other.txt") {
                otherWriteStarted.countDown()
                allowOtherWriteFinish.await(5, TimeUnit.SECONDS)
            }
            writtenDocs += docId
            ByteArrayOutputStream()
        }
        engine.onWriteBackFailed = { failedNames += it.name }
        runBlocking { engine.initialSync(treeUri, mirror) { _, _ -> } }
        return RenameFixture(
            engine = engine,
            renamedCalls = renamedCalls,
            createdCalls = createdCalls,
            failedNames = failedNames,
            renamedOnDevice = renamedOnDevice,
            blockOtherWrite = blockOtherWrite,
            otherWriteStarted = otherWriteStarted,
            allowOtherWriteFinish = allowOtherWriteFinish,
            holdRename = holdRename,
            renameInside = renameInside,
            releaseRename = releaseRename,
            writtenDocs = writtenDocs,
        )
    }

    private fun renameDirectory(engine: SafSyncEngine, fromName: String = "old", toName: String = "renamed") {
        val oldDir = File(mirror, fromName)
        val newDir = File(mirror, toName)
        assertTrue(oldDir.isDirectory, "missing source directory $fromName")
        assertTrue(oldDir.renameTo(newDir), "mirror rename $fromName -> $toName failed")
        engine.handleMirrorEvent(FileObserver.MOVED_FROM or 0x40000000, oldDir, mirror, treeUri)
        Thread.sleep(2)
        engine.handleMirrorEvent(FileObserver.MOVED_TO or 0x40000000, newDir, mirror, treeUri)
    }

    @Test
    fun `a rename that meets another folder's write waits for it and goes through`() {
        val fixture = setupRenameFixture()
        fixture.engine.providerClaimWaitMs = 5_000L
        val claim = hold("test")
        releaseAfter(claim, 400)

        val (_, ms) = timed {
            renameDirectory(fixture.engine)
            fixture.engine.runWriteBackLoop { false }
        }

        assertEquals(listOf("doc:old" to "renamed"), fixture.renamedCalls)
        assertTrue(fixture.failedNames.isEmpty(), "a rename that completed within the wait raised a notice")
        assertTrue(ms >= 300, "the rename did not wait for the claim ($ms ms)")
        assertFalse(fixture.engine.retryHeldBack(WatchSession(mirror)), "completed rename left a held retry entry")
    }

    /**
     * A rename gives its provider claim back before it gives the turn back, as a save does.
     *
     * The claim is per provider, and a save asks for it without waiting. A save that has waited
     * for the turn behind a rename takes the turn the moment it is freed and asks for the claim
     * next, so with the claim given back after the turn the two met in that gap now and then:
     * the claim was found held and the save that had only had to wait was held back and
     * announced as kept inside the app.
     *
     * Read off the threads instead of raced: the claim registry's monitor is held here, which
     * stops whoever gives a claim back or takes one. The rename, let go, comes to give its claim
     * back and stops there. The save must still be waiting for the turn, not at the registry.
     */
    /** The mirror's turn: the lock a write holds while it runs, which a waiting write is queued on. */
    private fun turnOf(mirror: File): java.util.concurrent.locks.ReentrantLock {
        val access = (SafSyncEngine::class.java.getDeclaredField("mirrorAccess").apply { isAccessible = true }
            .get(null) as Map<*, *>)[mirror.absolutePath] ?: error("no turn was made for ${mirror.absolutePath}")
        return access.javaClass.getDeclaredMethod("getLock").apply { isAccessible = true }
            .invoke(access) as java.util.concurrent.locks.ReentrantLock
    }

    /**
     * Whether [waiter] is still queued on the mirror's turn while somebody holds it. Read as a state, not raced: the
     * one that gave back its claim after the turn left the turn free, or to the waiter, by the time it reached the
     * registry, and then this is never true.
     */
    private fun waitsOnHeldTurn(mirror: File, waiter: Thread): Boolean {
        val turn = turnOf(mirror)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            if (turn.isLocked && turn.hasQueuedThread(waiter)) return true
            Thread.sleep(5)
        }
        return false
    }

    @Test
    fun `a save waiting for its turn behind a rename is not handed it before the rename gives back the provider claim`() {
        val fixture = setupRenameFixture()
        fixture.holdRename.set(true)
        val otherFile = File(mirror, "other.txt").apply { writeText("updated bytes") }
        fixture.engine.handleMirrorEvent(FileObserver.MODIFY, otherFile, mirror, treeUri)
        val waiting = WatchSession(mirror).also {
            it.queue.add(checkNotNull(fixture.engine.session.queue.poll()) { "no save was queued" })
        }
        renameDirectory(fixture.engine)
        val renaming = Thread { fixture.engine.runWriteBackLoop { false } }.apply { isDaemon = true; start() }
        try {
            assertTrue(fixture.renameInside.await(5, TimeUnit.SECONDS), "the rename never reached the provider")
            val saving = Thread { fixture.engine.runWriteBackLoop(waiting) { false } }.apply { isDaemon = true; start() }
            saving.join(400)
            assertTrue(saving.isAlive, "the save did not wait for the rename's turn")

            val registry = SafSyncEngine::class.java.getDeclaredField("providerMutations")
                .apply { isAccessible = true }.get(null)!!
            synchronized(registry) {
                fixture.releaseRename.countDown()
                // Generous: the polling stops as soon as the rename is there, and a loaded machine is slow to get it there.
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
                while (renaming.state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.sleep(5)
                assertEquals(Thread.State.BLOCKED, renaming.state, "the rename never came to give its claim back")
                assertTrue(
                    waitsOnHeldTurn(mirror, saving),
                    "the save was handed the turn while the rename still held the provider claim",
                )
            }
            renaming.join(15_000)
            saving.join(15_000)

            assertFalse(renaming.isAlive)
            assertFalse(saving.isAlive)
            assertEquals(listOf("doc:old" to "renamed"), fixture.renamedCalls)
            assertTrue("doc:other.txt" in fixture.writtenDocs, "the save was held back and not sent")
            assertEquals(emptyList<String>(), fixture.failedNames, "a save that only had to wait was announced as lost")
        } finally {
            fixture.releaseRename.countDown()
            renaming.join(5_000)
        }
    }

    @Test
    fun `a rename is dropped with one notice when the claim stays held past the wait`() {
        val fixture = setupRenameFixture()
        fixture.engine.providerClaimWaitMs = 250L
        hold("test")

        renameDirectory(fixture.engine)
        fixture.engine.runWriteBackLoop { false }

        assertTrue(fixture.renamedCalls.isEmpty(), "rename ran without the provider claim")
        assertTrue(fixture.createdCalls.isEmpty(), "dropped rename fell back to creating a duplicate directory")
        assertEquals(listOf("renamed"), fixture.failedNames)
        assertFalse(fixture.renamedOnDevice.get(), "device folder was renamed despite held claim")
        assertTrue(File(mirror, "renamed/child.txt").isFile, "local mirror lost its renamed folder contents")
        assertFalse(fixture.engine.retryHeldBack(WatchSession(mirror)), "dropped busy rename left a held retry entry")
    }

    @Test
    fun `a rename whose mirror lock times out ends after one attempt without spinning`() {
        val fixture = setupRenameFixture()
        fixture.engine.outboundTurnWaitMs = 50L
        fixture.engine.providerClaimWaitMs = 5_000L
        fixture.blockOtherWrite.set(true)

        val otherFile = File(mirror, "other.txt").apply { writeText("updated bytes") }
        val blocker = Thread {
            fixture.engine.handleMirrorEvent(FileObserver.MODIFY, otherFile, mirror, treeUri)
            fixture.engine.runWriteBackLoop { false }
        }.apply { isDaemon = true; start() }

        try {
            assertTrue(
                fixture.otherWriteStarted.await(5, TimeUnit.SECONDS),
                "setup failed: in-flight write did not acquire the mirror lock",
            )
            val (_, ms) = timed {
                renameDirectory(fixture.engine)
                fixture.engine.runWriteBackLoop { false }
            }
            var extraRetries = 0
            while (extraRetries < 5 && fixture.engine.retryHeldBack(WatchSession(mirror))) {
                extraRetries++
            }

            assertTrue(fixture.renamedCalls.isEmpty(), "rename ran while the mirror lock was held")
            assertTrue(fixture.engine.session.queue.isEmpty(), "timed-out rename stayed queued")
            assertEquals(0, extraRetries, "timed-out rename kept retrying after its one attempt")
            assertTrue(ms < 2_000, "timed-out rename blocked or spun past one turn wait ($ms ms)")
            assertEquals(listOf("renamed"), fixture.failedNames)
        } finally {
            fixture.allowOtherWriteFinish.countDown()
            blocker.join(5_000)
        }
    }

    @Test
    fun `a rename whose mirror lock times out records the path and notifies once`() {
        val fixture = setupRenameFixture()
        fixture.engine.outboundTurnWaitMs = 50L
        fixture.engine.providerClaimWaitMs = 5_000L
        fixture.blockOtherWrite.set(true)

        val otherFile = File(mirror, "other.txt").apply { writeText("updated bytes") }
        val blocker = Thread {
            fixture.engine.handleMirrorEvent(FileObserver.MODIFY, otherFile, mirror, treeUri)
            fixture.engine.runWriteBackLoop { false }
        }.apply { isDaemon = true; start() }

        try {
            assertTrue(
                fixture.otherWriteStarted.await(5, TimeUnit.SECONDS),
                "setup failed: in-flight write did not acquire the mirror lock",
            )
            renameDirectory(fixture.engine)
            fixture.engine.runWriteBackLoop { false }

            // A subsequent write-back failure for the same target path in the open session must
            // see it already recorded in refusalsAnnounced rather than emitting a second notice.
            val renamedDir = File(mirror, "renamed")
            fixture.engine.handleMirrorEvent(FileObserver.CREATE or 0x40000000, renamedDir, mirror, treeUri)
            fixture.engine.runWriteBackLoop { false }

            assertEquals(
                listOf("renamed"),
                fixture.failedNames,
                "mirror-lock timeout bypassed announceLost and allowed a duplicate notice",
            )
        } finally {
            fixture.allowOtherWriteFinish.countDown()
            blocker.join(5_000)
        }
    }

    private class VirtualNode(
        val id: String,
        val treeTag: String,
        var parentId: String?,
        var name: String,
        val isDirectory: Boolean,
        @Volatile var bytes: ByteArray = ByteArray(0),
        @Volatile var lastModified: Long = 1_700_000_000_000L,
    )

    private inner class VirtualSafHarness(
        authorities: Map<String, String> = mapOf("treeA" to "test"),
    ) {
        private val lock = Any()
        private val nodes = LinkedHashMap<String, VirtualNode>()
        private val uris = HashMap<Pair<String, String>, Uri>()
        private var seq = 0

        var clockOffsetMs: Long = 0L
        val treeUris = HashMap<String, Uri>()
        val treeAuthorities = authorities.toMap()
        val renamedCalls = mutableListOf<Pair<String, String>>()
        val createdCalls = mutableListOf<Pair<String, String>>()
        val deletedIds = mutableListOf<String>()
        val failedNames = mutableListOf<String>()
        val deleteRefusedNames = mutableListOf<String>()
        val queriedTargets = mutableListOf<String>()
        @Volatile var beforeOpenOutput: ((String) -> Unit)? = null
        @Volatile var shouldFailQuery: ((String) -> Boolean)? = null

        init {
            for ((treeTag, auth) in treeAuthorities) {
                val rootId = "$treeTag::root"
                nodes[rootId] = VirtualNode(rootId, treeTag, null, "", isDirectory = true)
                treeUris[treeTag] = uriFor(auth, rootId)
            }
        }

        private fun uriFor(authority: String, docId: String): Uri = synchronized(lock) {
            uris.getOrPut(authority to docId) {
                mockk<Uri>(relaxed = true).also {
                    every { it.authority } returns authority
                    every { it.toString() } returns "doc-uri:$docId"
                }
            }
        }

        private fun treeTagOf(docOrTreeId: String): String = docOrTreeId.substringBefore("::")

        private fun cursorFor(rows: List<VirtualNode>): Cursor {
            var idx = -1
            return mockk<Cursor>(relaxed = true) {
                every { moveToFirst() } answers {
                    idx = 0
                    rows.isNotEmpty()
                }
                every { moveToNext() } answers { ++idx < rows.size }
                every { getColumnIndexOrThrow(any()) } answers {
                    when (firstArg<String>()) {
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID -> 0
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME -> 1
                        DocumentsContract.Document.COLUMN_MIME_TYPE -> 2
                        DocumentsContract.Document.COLUMN_SIZE -> 3
                        else -> 4
                    }
                }
                every { getColumnIndex(any()) } answers {
                    when (firstArg<String>()) {
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID -> 0
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME -> 1
                        DocumentsContract.Document.COLUMN_MIME_TYPE -> 2
                        DocumentsContract.Document.COLUMN_SIZE -> 3
                        DocumentsContract.Document.COLUMN_LAST_MODIFIED -> 4
                        else -> -1
                    }
                }
                every { isNull(any<Int>()) } returns false
                every { getString(0) } answers { rows[idx].id }
                every { getString(1) } answers { rows[idx].name }
                every { getString(2) } answers {
                    if (rows[idx].isDirectory) DocumentsContract.Document.MIME_TYPE_DIR else "text/plain"
                }
                every { getLong(3) } answers { rows[idx].bytes.size.toLong() }
                every { getLong(4) } answers { rows[idx].lastModified }
            }
        }

        fun addDir(treeTag: String, relativePath: String): String = synchronized(lock) {
            var parentId = "$treeTag::root"
            for (segment in relativePath.split('/').filter { it.isNotEmpty() }) {
                val existing = nodes.values.firstOrNull { it.parentId == parentId && it.name == segment }
                parentId = existing?.id ?: "$treeTag::doc:${++seq}".also { id ->
                    nodes[id] = VirtualNode(id, treeTag, parentId, segment, isDirectory = true)
                }
            }
            parentId
        }

        fun addFile(treeTag: String, relativePath: String, content: String): String = synchronized(lock) {
            val parts = relativePath.split('/').filter { it.isNotEmpty() }
            val parentRel = parts.dropLast(1).joinToString("/")
            val parentId = if (parentRel.isEmpty()) "$treeTag::root" else addDir(treeTag, parentRel)
            val name = parts.last()
            val id = "$treeTag::doc:${++seq}"
            nodes[id] = VirtualNode(id, treeTag, parentId, name, isDirectory = false, bytes = content.toByteArray())
            id
        }

        fun findByPath(treeTag: String, relativePath: String): VirtualNode? = synchronized(lock) {
            var currentId = "$treeTag::root"
            for (segment in relativePath.split('/').filter { it.isNotEmpty() }) {
                val next = nodes.values.firstOrNull { it.parentId == currentId && it.name == segment }
                    ?: return@synchronized null
                currentId = next.id
            }
            nodes[currentId]
        }

        fun reparent(docId: String, parentId: String) = synchronized(lock) { nodes[docId]?.parentId = parentId }

        /** The names of the documents in the folder at [relativePath], one per document, sorted. */
        fun childNames(treeTag: String, relativePath: String = ""): List<String> = synchronized(lock) {
            val parent = if (relativePath.isEmpty()) nodes["$treeTag::root"] else findByPath(treeTag, relativePath)
            nodes.values.filter { it.parentId == parent?.id }.map { it.name }.sorted()
        }

        private fun deleteRecursive(docId: String) {
            val children = nodes.values.filter { it.parentId == docId }.map { it.id }
            children.forEach { deleteRecursive(it) }
            nodes.remove(docId)
        }

        fun buildEngine(): SafSyncEngine {
            mockkStatic(SystemClock::class)
            every { SystemClock.elapsedRealtime() } answers { System.nanoTime() / 1_000_000 + clockOffsetMs }
            mockkStatic(DocumentsContract::class)
            every { DocumentsContract.getTreeDocumentId(any()) } answers {
                firstArg<Uri>().toString().removePrefix("doc-uri:")
            }
            every { DocumentsContract.buildChildDocumentsUriUsingTree(any(), any()) } answers {
                val auth = firstArg<Uri>().authority ?: "test"
                val parentId = secondArg<String>()
                uriFor(auth, "children|$parentId")
            }
            every { DocumentsContract.buildDocumentUriUsingTree(any(), any()) } answers {
                val auth = firstArg<Uri>().authority ?: "test"
                val docId = secondArg<String>()
                uriFor(auth, docId)
            }
            every { DocumentsContract.getDocumentId(any()) } answers {
                firstArg<Uri>().toString().removePrefix("doc-uri:")
            }
            every {
                DocumentsContract.renameDocument(any(), any(), any())
            } answers {
                val docUri = secondArg<Uri>()
                val targetName = thirdArg<String>()
                val docId = docUri.toString().removePrefix("doc-uri:")
                synchronized(lock) {
                    renamedCalls += docId to targetName
                    nodes[docId]?.name = targetName
                }
                docUri
            }
            every {
                DocumentsContract.createDocument(any(), any(), any(), any())
            } answers {
                val parentUri = secondArg<Uri>()
                val mime = thirdArg<String>()
                val displayName = arg<String>(3)
                val parentId = parentUri.toString().removePrefix("doc-uri:")
                val created = synchronized(lock) {
                    createdCalls += parentId to displayName
                    val tag = treeTagOf(parentId)
                    val id = "$tag::doc:${++seq}"
                    nodes[id] = VirtualNode(
                        id = id,
                        treeTag = tag,
                        parentId = parentId,
                        name = displayName,
                        isDirectory = mime == DocumentsContract.Document.MIME_TYPE_DIR,
                    )
                    uriFor(treeAuthorities.getValue(tag), id)
                }
                created
            }
            every { DocumentsContract.deleteDocument(any(), any()) } answers {
                val docId = secondArg<Uri>().toString().removePrefix("doc-uri:")
                synchronized(lock) {
                    deletedIds += docId
                    val existed = nodes.containsKey(docId)
                    if (existed) deleteRecursive(docId)
                    existed
                }
            }

            resolver = mockk(relaxed = true)
            every { resolver.query(any(), any(), any(), any(), any()) } answers {
                val raw = firstArg<Uri>().toString().removePrefix("doc-uri:")
                if (shouldFailQuery?.invoke(raw) == true) {
                    throw IllegalStateException("simulated query failure for $raw")
                }
                val rows = synchronized(lock) {
                    queriedTargets += raw
                    if (raw.startsWith("children|")) {
                        val parentId = raw.removePrefix("children|")
                        nodes.values.filter { it.parentId == parentId }.toList()
                    } else {
                        listOfNotNull(nodes[raw])
                    }
                }
                cursorFor(rows)
            }
            every { resolver.openInputStream(any()) } answers {
                val docId = firstArg<Uri>().toString().removePrefix("doc-uri:")
                val bytes = synchronized(lock) { nodes[docId]?.bytes ?: ByteArray(0) }
                ByteArrayInputStream(bytes)
            }
            every { resolver.openOutputStream(any(), any()) } answers {
                val docId = firstArg<Uri>().toString().removePrefix("doc-uri:")
                beforeOpenOutput?.invoke(docId)
                object : ByteArrayOutputStream() {
                    override fun close() {
                        super.close()
                        synchronized(lock) {
                            nodes[docId]?.let { node ->
                                node.bytes = toByteArray()
                                node.lastModified += 1_000L
                            }
                        }
                    }
                }
            }

            context = mockk<Context>(relaxed = true)
            every { context.contentResolver } returns resolver
            every { context.filesDir } returns filesDir
            return SafSyncEngine(context).apply {
                providerClaimWaitMs = 50L
                onWriteBackFailed = { failedNames += it.name }
                onDeleteRefused = { deleteRefusedNames += it.name }
            }
        }
    }

    private fun waitUntilInClaimWait(thread: Thread) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            val state = thread.state
            if ((state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING) &&
                thread.stackTrace.any { it.methodName.startsWith("claimProviderMutationWithin") }
            ) {
                return
            }
            Thread.sleep(5)
        }
        error("worker thread did not reach claimProviderMutationWithin; state=${thread.state}")
    }

    @Test
    fun `a file save on the same mirror defers without blocking on mirror lock while a rename waits for the provider claim`() {
        val harness = VirtualSafHarness()
        harness.addDir("treeA", "old")
        harness.addFile("treeA", "old/child.txt", "original")
        val otherId = harness.addFile("treeA", "other.txt", "before")
        val treeA = harness.treeUris.getValue("treeA")
        val engine = harness.buildEngine()
        engine.outboundTurnWaitMs = 5_000L
        engine.providerClaimWaitMs = 5_000L

        runBlocking { engine.initialSync(treeA, mirror) { _, _ -> } }
        val oldDir = File(mirror, "old")
        val renamedDir = File(mirror, "renamed")
        val otherFile = File(mirror, "other.txt")

        val claim = hold("test")
        assertTrue(oldDir.renameTo(renamedDir))
        engine.handleMirrorEvent(FileObserver.MOVED_FROM or 0x40000000, oldDir, mirror, treeA)
        Thread.sleep(2)
        engine.handleMirrorEvent(FileObserver.MOVED_TO or 0x40000000, renamedDir, mirror, treeA)

        val worker = Thread { engine.runWriteBackLoop { false } }.apply { start() }
        waitUntilInClaimWait(worker)

        otherFile.writeText("concurrent save")
        val concurrentSession = WatchSession(mirror, engine.session.generation)
        concurrentSession.queue.offer(
            SyncJob(
                type = SyncType.MODIFY,
                localPath = otherFile.absolutePath,
                safDocUri = DocumentsContract.buildDocumentUriUsingTree(treeA, otherId),
                safParentUri = treeA,
                safTreeUri = treeA,
                timestamp = System.currentTimeMillis(),
                relativePath = "other.txt",
                mirrorRoot = mirror,
                generation = engine.session.generation,
            ),
        )
        val (_, saveMs) = timed {
            engine.runWriteBackLoop(concurrentSession) { false }
        }
        assertTrue(
            saveMs < 1_000,
            "same-mirror save blocked on mirror lock while rename waited for provider claim ($saveMs ms)",
        )

        release(claim)
        held.remove(claim)
        worker.join(5_000)
        assertFalse(worker.isAlive)

        harness.clockOffsetMs += SafSyncEngine.HELD_BACK_RETRY_FIRST_MS
        assertTrue(engine.retryHeldBack(WatchSession(mirror, engine.session.generation)))
        assertEquals(
            "concurrent save",
            harness.findByPath("treeA", "other.txt")?.bytes?.toString(Charsets.UTF_8),
        )
        assertEquals(
            "original",
            harness.findByPath("treeA", "renamed/child.txt")?.bytes?.toString(Charsets.UTF_8),
        )
        assertEquals(listOf("other.txt"), harness.failedNames)
    }

    @Test
    fun `a completed or normalized folder rename runs once and never re-enters the queue or retry loop`() {
        val harness = VirtualSafHarness()
        val oldId = harness.addDir("treeA", "old")
        val oldNode = harness.findByPath("treeA", "old")!!
        harness.addFile("treeA", "old/child.txt", "original")
        val treeA = harness.treeUris.getValue("treeA")
        val engine = harness.buildEngine()

        every { DocumentsContract.renameDocument(any(), any(), any()) } answers {
            val docUri = secondArg<Uri>()
            val docId = docUri.toString().removePrefix("doc-uri:")
            val normalized = "${thirdArg<String>()}_norm"
            harness.renamedCalls += docId to normalized
            if (docId == oldId) oldNode.name = normalized
            docUri
        }

        runBlocking { engine.initialSync(treeA, mirror) { _, _ -> } }
        val oldDir = File(mirror, "old")
        val renamedDir = File(mirror, "renamed")
        assertTrue(oldDir.renameTo(renamedDir))
        engine.handleMirrorEvent(FileObserver.MOVED_FROM or 0x40000000, oldDir, mirror, treeA)
        Thread.sleep(2)
        engine.handleMirrorEvent(FileObserver.MOVED_TO or 0x40000000, renamedDir, mirror, treeA)

        engine.runWriteBackLoop { false }
        engine.runWriteBackLoop { false }
        harness.clockOffsetMs += SafSyncEngine.HELD_BACK_RETRY_MAX_MS
        assertFalse(engine.retryHeldBack(WatchSession(mirror, engine.session.generation)))

        assertEquals(1, harness.renamedCalls.size, "folder rename executed more than once: ${harness.renamedCalls}")
        assertTrue(harness.createdCalls.isEmpty(), "normalized rename triggered fallback creation: ${harness.createdCalls}")
        assertTrue(File(mirror, "renamed_norm/child.txt").isFile)
        assertTrue(harness.failedNames.isEmpty())
    }

    @Test
    fun `a cross-directory move whose target parent reaches the device during the claim wait moves the folder whole`() {
        // The target parent is looked up once the claim is held. Looked up before the wait, a parent that reached the
        // device while it ran was missed: the move was dropped with a notice and the device kept the folder unmoved.
        val harness = VirtualSafHarness()
        val oldId = harness.addDir("treeA", "old")
        val oldNode = harness.findByPath("treeA", "old")!!
        harness.addFile("treeA", "old/child.txt", "original")
        harness.addFile("treeA", "old/.git/HEAD", "ref: refs/heads/main")
        val treeA = harness.treeUris.getValue("treeA")
        val engine = harness.buildEngine()
        engine.providerClaimWaitMs = 5_000L

        every { DocumentsContract.moveDocument(any(), any(), any(), any()) } answers {
            val docUri = secondArg<Uri>()
            val targetParentUri = arg<Uri>(3)
            val docId = docUri.toString().removePrefix("doc-uri:")
            val targetParentId = targetParentUri.toString().removePrefix("doc-uri:")
            if (docId == oldId) {
                oldNode.parentId = targetParentId
                docUri
            } else {
                null
            }
        }

        runBlocking { engine.initialSync(treeA, mirror) { _, _ -> } }
        val claim = hold("test")
        val dstDir = File(mirror, "dst").apply { mkdirs() }
        val oldDir = File(mirror, "old")
        val movedDir = File(dstDir, "old")
        assertTrue(oldDir.renameTo(movedDir))
        engine.handleMirrorEvent(FileObserver.MOVED_FROM or 0x40000000, oldDir, mirror, treeA)
        Thread.sleep(2)
        engine.handleMirrorEvent(FileObserver.MOVED_TO or 0x40000000, movedDir, mirror, treeA)

        val worker = Thread { engine.runWriteBackLoop { false } }.apply { start() }
        waitUntilInClaimWait(worker)

        harness.addDir("treeA", "dst")
        release(claim)
        held.remove(claim)
        worker.join(5_000)
        assertFalse(worker.isAlive)

        assertNull(harness.findByPath("treeA", "old"), "the device kept the folder unmoved")
        assertEquals("original", harness.findByPath("treeA", "dst/old/child.txt")?.bytes?.toString(Charsets.UTF_8))
        assertNotNull(harness.findByPath("treeA", "dst/old/.git/HEAD"), "the folder was not moved whole")
        assertTrue(harness.createdCalls.isEmpty(), "the folder was made again from the copy: ${harness.createdCalls}")
        assertEquals(emptyList<String>(), harness.failedNames)
    }

    @Test
    fun `a cross-directory move whose target parent never reaches the device announces the unmoved folder`() {
        val harness = VirtualSafHarness()
        harness.addDir("treeA", "old")
        harness.addFile("treeA", "old/child.txt", "original")
        val treeA = harness.treeUris.getValue("treeA")
        val engine = harness.buildEngine()
        engine.providerClaimWaitMs = 100L

        runBlocking { engine.initialSync(treeA, mirror) { _, _ -> } }

        val claim = hold("test")
        val dstDir = File(mirror, "dst").apply { mkdirs() }
        engine.handleMirrorEvent(FileObserver.CREATE or 0x40000000, dstDir, mirror, treeA)
        engine.runWriteBackLoop { false }
        assertEquals(listOf("dst"), harness.failedNames)

        release(claim)
        held.remove(claim)

        val oldDir = File(mirror, "old")
        val movedDir = File(dstDir, "old")
        assertTrue(oldDir.renameTo(movedDir))
        engine.handleMirrorEvent(FileObserver.MOVED_FROM or 0x40000000, oldDir, mirror, treeA)
        Thread.sleep(2)
        engine.handleMirrorEvent(FileObserver.MOVED_TO or 0x40000000, movedDir, mirror, treeA)
        engine.runWriteBackLoop { false }

        assertNull(harness.findByPath("treeA", "dst/old"))
        assertNotNull(harness.findByPath("treeA", "old"))
        assertEquals(
            listOf("dst", "old"),
            harness.failedNames,
            "cross-directory move into missing device parent dropped without announcing the unmoved folder",
        )
    }

    @Test
    fun `a dropped rename still notifies when an earlier refused entry at the same path was deleted`() {
        val harness = VirtualSafHarness()
        harness.addDir("treeA", "old")
        harness.addFile("treeA", "old/child.txt", "original")
        val treeA = harness.treeUris.getValue("treeA")
        val engine = harness.buildEngine()
        engine.providerClaimWaitMs = 100L

        runBlocking { engine.initialSync(treeA, mirror) { _, _ -> } }

        val firstClaim = hold("test")
        val tempSameName = File(mirror, "renamed").apply { mkdirs() }
        engine.handleMirrorEvent(FileObserver.CREATE or 0x40000000, tempSameName, mirror, treeA)
        engine.runWriteBackLoop { false }
        assertEquals(listOf("renamed"), harness.failedNames)

        release(firstClaim)
        held.remove(firstClaim)

        assertTrue(tempSameName.delete())
        engine.handleMirrorEvent(FileObserver.DELETE or 0x40000000, tempSameName, mirror, treeA)
        engine.runWriteBackLoop { false }
        harness.failedNames.clear()

        val secondClaim = hold("test")
        val oldDir = File(mirror, "old")
        val renamedDir = File(mirror, "renamed")
        assertTrue(oldDir.renameTo(renamedDir))
        engine.handleMirrorEvent(FileObserver.MOVED_FROM or 0x40000000, oldDir, mirror, treeA)
        Thread.sleep(2)
        engine.handleMirrorEvent(FileObserver.MOVED_TO or 0x40000000, renamedDir, mirror, treeA)
        engine.runWriteBackLoop { false }

        release(secondClaim)
        held.remove(secondClaim)

        assertNotNull(harness.findByPath("treeA", "old"))
        assertNull(harness.findByPath("treeA", "renamed"))
        assertEquals(
            listOf("renamed"),
            harness.failedNames,
            "dropped rename emitted zero notice because refusalsAnnounced kept a deleted entry's path",
        )
    }

    @Test
    fun `deleting a refused folder clears refusal tracking for children inside its subtree`() {
        val harness = VirtualSafHarness()
        val treeA = harness.treeUris.getValue("treeA")
        val engine = harness.buildEngine()
        engine.providerClaimWaitMs = 100L

        runBlocking { engine.initialSync(treeA, mirror) { _, _ -> } }

        val folder = File(mirror, "folder").apply { mkdirs() }
        val child = File(folder, "note.txt").apply { writeText("first") }
        val firstClaim = hold("test")
        engine.handleMirrorEvent(FileObserver.CREATE or 0x40000000, folder, mirror, treeA)
        engine.handleMirrorEvent(FileObserver.CREATE, child, mirror, treeA)
        engine.runWriteBackLoop { false }
        release(firstClaim)
        held.remove(firstClaim)
        assertTrue("note.txt" in harness.failedNames)

        assertTrue(child.delete())
        assertTrue(folder.delete())
        engine.handleMirrorEvent(FileObserver.DELETE or 0x40000000, folder, mirror, treeA)
        harness.failedNames.clear()

        assertTrue(folder.mkdirs())
        child.writeText("second")
        val secondClaim = hold("test")
        engine.handleMirrorEvent(FileObserver.CREATE or 0x40000000, folder, mirror, treeA)
        engine.handleMirrorEvent(FileObserver.CREATE, child, mirror, treeA)
        engine.runWriteBackLoop { false }
        release(secondClaim)
        held.remove(secondClaim)

        assertTrue("folder" in harness.failedNames)
        assertTrue("note.txt" in harness.failedNames)
    }

    @Test
    fun `writes and deletes queued after a dropped rename fail against the missing device folder even after the claim frees`() {
        val harness = VirtualSafHarness()
        harness.addDir("treeA", "old")
        harness.addFile("treeA", "old/child.txt", "original")
        harness.addFile("treeA", "old/gone.txt", "keep on device")
        val treeA = harness.treeUris.getValue("treeA")
        val engine = harness.buildEngine()
        engine.providerClaimWaitMs = 100L

        runBlocking { engine.initialSync(treeA, mirror) { _, _ -> } }

        val claim = hold("test")
        val oldDir = File(mirror, "old")
        val renamedDir = File(mirror, "renamed")
        assertTrue(oldDir.renameTo(renamedDir))
        engine.handleMirrorEvent(FileObserver.MOVED_FROM or 0x40000000, oldDir, mirror, treeA)
        Thread.sleep(2)
        engine.handleMirrorEvent(FileObserver.MOVED_TO or 0x40000000, renamedDir, mirror, treeA)

        val editedChild = File(renamedDir, "child.txt").apply { writeText("edited after rename") }
        engine.handleMirrorEvent(FileObserver.MODIFY, editedChild, mirror, treeA)
        val createdChild = File(renamedDir, "after.txt").apply { writeText("created after rename") }
        engine.handleMirrorEvent(FileObserver.CREATE, createdChild, mirror, treeA)
        val deletedChild = File(renamedDir, "gone.txt")
        assertTrue(deletedChild.delete())
        engine.handleMirrorEvent(FileObserver.DELETE, deletedChild, mirror, treeA)

        val worker = Thread { engine.runWriteBackLoop { false } }.apply { start() }
        worker.join(5_000)
        assertFalse(worker.isAlive)

        release(claim)
        held.remove(claim)
        harness.clockOffsetMs += SafSyncEngine.HELD_BACK_RETRY_FIRST_MS
        assertFalse(engine.retryHeldBack(WatchSession(mirror, engine.session.generation)))

        assertEquals(listOf("renamed", "child.txt", "after.txt"), harness.failedNames)
        assertTrue(harness.deleteRefusedNames.isEmpty())
        assertEquals("original", harness.findByPath("treeA", "old/child.txt")?.bytes?.toString(Charsets.UTF_8))
        assertEquals("keep on device", harness.findByPath("treeA", "old/gone.txt")?.bytes?.toString(Charsets.UTF_8))
        assertNull(harness.findByPath("treeA", "renamed"))
    }

    @Test
    fun `edits queued before a folder rename still reach the device or notify per file when the rename drops`() {
        val harness = VirtualSafHarness()
        harness.addDir("treeA", "old")
        harness.addFile("treeA", "old/child.txt", "original")
        val treeA = harness.treeUris.getValue("treeA")
        val engine = harness.buildEngine()
        engine.providerClaimWaitMs = 100L

        runBlocking { engine.initialSync(treeA, mirror) { _, _ -> } }

        val oldDir = File(mirror, "old")
        val editedBeforeRename = File(oldDir, "child.txt").apply { writeText("edited before rename") }
        engine.handleMirrorEvent(FileObserver.MODIFY, editedBeforeRename, mirror, treeA)
        val createdBeforeRename = File(oldDir, "before.txt").apply { writeText("created before rename") }
        engine.handleMirrorEvent(FileObserver.CREATE, createdBeforeRename, mirror, treeA)

        val renamedDir = File(mirror, "renamed")
        assertTrue(oldDir.renameTo(renamedDir))
        engine.handleMirrorEvent(FileObserver.MOVED_FROM or 0x40000000, oldDir, mirror, treeA)
        Thread.sleep(2)
        engine.handleMirrorEvent(FileObserver.MOVED_TO or 0x40000000, renamedDir, mirror, treeA)

        val claim = hold("test")
        engine.runWriteBackLoop { false }
        release(claim)
        held.remove(claim)

        harness.clockOffsetMs += SafSyncEngine.HELD_BACK_RETRY_FIRST_MS
        engine.retryHeldBack(WatchSession(mirror, engine.session.generation))

        assertEquals(listOf("renamed", "child.txt", "before.txt"), harness.failedNames)
        assertEquals("original", harness.findByPath("treeA", "old/child.txt")?.bytes?.toString(Charsets.UTF_8))
    }

    @Test
    fun `a folder renamed twice before the first rename is sent carries earlier edits to its last name and leaves no intermediate folder`() {
        // mid has no document when mid -> final arrives, since the device still holds it as old: the second rename
        // looks its source up when it runs, after old -> mid, instead of making final beside the renamed mid.
        val harness = VirtualSafHarness()
        harness.addDir("treeA", "old")
        harness.addFile("treeA", "old/child.txt", "initial")
        val treeA = harness.treeUris.getValue("treeA")
        val engine = harness.buildEngine()

        runBlocking { engine.initialSync(treeA, mirror) { _, _ -> } }
        val oldDir = File(mirror, "old")
        val midDir = File(mirror, "mid")
        val finalDir = File(mirror, "final")

        val edited = File(oldDir, "child.txt").apply { writeText("edited before renames") }
        engine.handleMirrorEvent(FileObserver.MODIFY, edited, mirror, treeA)
        val added = File(oldDir, "added.txt").apply { writeText("created before renames") }
        engine.handleMirrorEvent(FileObserver.CREATE, added, mirror, treeA)

        assertTrue(oldDir.renameTo(midDir))
        engine.handleMirrorEvent(FileObserver.MOVED_FROM or 0x40000000, oldDir, mirror, treeA)
        Thread.sleep(2)
        engine.handleMirrorEvent(FileObserver.MOVED_TO or 0x40000000, midDir, mirror, treeA)

        assertTrue(midDir.renameTo(finalDir))
        engine.handleMirrorEvent(FileObserver.MOVED_FROM or 0x40000000, midDir, mirror, treeA)
        Thread.sleep(2)
        engine.handleMirrorEvent(FileObserver.MOVED_TO or 0x40000000, finalDir, mirror, treeA)

        engine.runWriteBackLoop { false }

        assertNull(harness.findByPath("treeA", "old"))
        assertNull(harness.findByPath("treeA", "mid"), "the folder is on the device under its intermediate name too")
        assertEquals(
            "edited before renames",
            harness.findByPath("treeA", "final/child.txt")?.bytes?.toString(Charsets.UTF_8),
        )
        assertEquals(
            "created before renames",
            harness.findByPath("treeA", "final/added.txt")?.bytes?.toString(Charsets.UTF_8),
        )
    }

    @Test
    fun `renaming an unsynced folder then deleting it and replacing it with a file writes the file once without delete refusal`() {
        val harness = VirtualSafHarness()
        val treeA = harness.treeUris.getValue("treeA")
        val engine = harness.buildEngine()
        val outputsById = mutableMapOf<String, Int>()
        harness.beforeOpenOutput = { docId ->
            outputsById[docId] = (outputsById[docId] ?: 0) + 1
        }

        runBlocking { engine.initialSync(treeA, mirror) { _, _ -> } }

        val ghostDir = File(mirror, "ghost").apply { mkdirs() }
        File(ghostDir, "note.txt").writeText("local only")
        val target = File(mirror, "replaced")
        assertTrue(ghostDir.renameTo(target))
        engine.handleMirrorEvent(FileObserver.MOVED_FROM or 0x40000000, ghostDir, mirror, treeA)
        Thread.sleep(2)
        engine.handleMirrorEvent(FileObserver.MOVED_TO or 0x40000000, target, mirror, treeA)

        val oldNote = File(target, "note.txt")
        assertTrue(oldNote.delete())
        engine.handleMirrorEvent(FileObserver.DELETE, oldNote, mirror, treeA)
        assertTrue(target.delete())
        engine.handleMirrorEvent(FileObserver.DELETE or 0x40000000, target, mirror, treeA)

        target.writeText("replacement file")
        engine.handleMirrorEvent(FileObserver.CREATE, target, mirror, treeA)

        engine.runWriteBackLoop { false }

        val node = harness.findByPath("treeA", "replaced")
        assertNotNull(node)
        assertFalse(node!!.isDirectory)
        assertEquals("replacement file", node.bytes.toString(Charsets.UTF_8))
        assertTrue(harness.deleteRefusedNames.isEmpty(), "unexpected delete refusal: ${harness.deleteRefusedNames}")
        assertEquals(1, outputsById[node.id], "replacement file was written ${outputsById[node.id]} times")
    }

    @Test
    fun `renaming an unsynced folder then deleting and recreating it as a folder uploads each child once`() {
        val harness = VirtualSafHarness()
        val treeA = harness.treeUris.getValue("treeA")
        val engine = harness.buildEngine()
        val outputsById = mutableMapOf<String, Int>()
        harness.beforeOpenOutput = { docId ->
            outputsById[docId] = (outputsById[docId] ?: 0) + 1
        }

        runBlocking { engine.initialSync(treeA, mirror) { _, _ -> } }

        val ghostDir = File(mirror, "ghost").apply { mkdirs() }
        File(ghostDir, "old.txt").writeText("old")
        val recreated = File(mirror, "recreated")
        assertTrue(ghostDir.renameTo(recreated))
        engine.handleMirrorEvent(FileObserver.MOVED_FROM or 0x40000000, ghostDir, mirror, treeA)
        Thread.sleep(2)
        engine.handleMirrorEvent(FileObserver.MOVED_TO or 0x40000000, recreated, mirror, treeA)

        val oldFile = File(recreated, "old.txt")
        assertTrue(oldFile.delete())
        engine.handleMirrorEvent(FileObserver.DELETE, oldFile, mirror, treeA)
        assertTrue(recreated.delete())
        engine.handleMirrorEvent(FileObserver.DELETE or 0x40000000, recreated, mirror, treeA)

        assertTrue(recreated.mkdirs())
        engine.handleMirrorEvent(FileObserver.CREATE or 0x40000000, recreated, mirror, treeA)
        val fresh = File(recreated, "fresh.txt").apply { writeText("fresh") }
        engine.handleMirrorEvent(FileObserver.CREATE, fresh, mirror, treeA)

        engine.runWriteBackLoop { false }

        val freshNode = harness.findByPath("treeA", "recreated/fresh.txt")
        assertNotNull(freshNode)
        assertEquals("fresh", freshNode!!.bytes.toString(Charsets.UTF_8))
        assertTrue(harness.deleteRefusedNames.isEmpty())
        assertEquals(1, outputsById[freshNode.id], "recreated child was uploaded ${outputsById[freshNode.id]} times")
    }

    @Test
    fun `chaining a second rename after folder deletion and file replacement writes the replacement file`() {
        val harness = VirtualSafHarness()
        harness.addDir("treeA", "x")
        harness.addFile("treeA", "x/child.txt", "original")
        val treeA = harness.treeUris.getValue("treeA")
        val engine = harness.buildEngine()

        runBlocking { engine.initialSync(treeA, mirror) { _, _ -> } }
        val xDir = File(mirror, "x")
        val aDir = File(mirror, "a")
        val bDir = File(mirror, "b")

        assertTrue(xDir.renameTo(aDir))
        engine.handleMirrorEvent(FileObserver.MOVED_FROM or 0x40000000, xDir, mirror, treeA)
        Thread.sleep(2)
        engine.handleMirrorEvent(FileObserver.MOVED_TO or 0x40000000, aDir, mirror, treeA)

        assertTrue(aDir.renameTo(bDir))
        engine.handleMirrorEvent(FileObserver.MOVED_FROM or 0x40000000, aDir, mirror, treeA)
        Thread.sleep(2)
        engine.handleMirrorEvent(FileObserver.MOVED_TO or 0x40000000, bDir, mirror, treeA)

        val child = File(bDir, "child.txt")
        assertTrue(child.delete())
        engine.handleMirrorEvent(FileObserver.DELETE, child, mirror, treeA)
        assertTrue(bDir.delete())
        engine.handleMirrorEvent(FileObserver.DELETE or 0x40000000, bDir, mirror, treeA)

        bDir.writeText("replacement")
        engine.handleMirrorEvent(FileObserver.CREATE, bDir, mirror, treeA)

        engine.runWriteBackLoop { false }

        val fileNode = harness.findByPath("treeA", "b")
        assertNotNull(fileNode)
        assertFalse(fileNode!!.isDirectory)
        assertEquals("replacement", fileNode.bytes.toString(Charsets.UTF_8))
        assertTrue(harness.deleteRefusedNames.isEmpty())
        assertTrue(harness.failedNames.isEmpty())
    }

    @Test
    fun `a folder renamed through a name another folder takes next ends up whole under its last name`() {
        // mv old tmp; mv tmp final; mv other tmp, inside one poll. The first rename is not given way to the third,
        // which is onto the same name, because the second takes the first's result away from that name in between:
        // given way, the device kept old, and final was made again from the copy without what the copy never held.
        val harness = VirtualSafHarness()
        harness.addDir("treeA", "old")
        harness.addFile("treeA", "old/from_old.txt", "from old")
        harness.addFile("treeA", "old/.git/head", "ref: old")
        harness.addDir("treeA", "other")
        harness.addFile("treeA", "other/from_other.txt", "from other")
        val treeA = harness.treeUris.getValue("treeA")
        val engine = harness.buildEngine()

        runBlocking { engine.initialSync(treeA, mirror) { _, _ -> } }
        val oldDir = File(mirror, "old")
        val tmpDir = File(mirror, "tmp")
        val finalDir = File(mirror, "final")
        val otherDir = File(mirror, "other")

        assertTrue(oldDir.renameTo(tmpDir))
        engine.handleMirrorEvent(FileObserver.MOVED_FROM or 0x40000000, oldDir, mirror, treeA)
        Thread.sleep(2)
        engine.handleMirrorEvent(FileObserver.MOVED_TO or 0x40000000, tmpDir, mirror, treeA)

        assertTrue(tmpDir.renameTo(finalDir))
        engine.handleMirrorEvent(FileObserver.MOVED_FROM or 0x40000000, tmpDir, mirror, treeA)
        Thread.sleep(2)
        engine.handleMirrorEvent(FileObserver.MOVED_TO or 0x40000000, finalDir, mirror, treeA)

        assertTrue(otherDir.renameTo(tmpDir))
        engine.handleMirrorEvent(FileObserver.MOVED_FROM or 0x40000000, otherDir, mirror, treeA)
        Thread.sleep(2)
        engine.handleMirrorEvent(FileObserver.MOVED_TO or 0x40000000, tmpDir, mirror, treeA)

        engine.runWriteBackLoop { false }

        assertNull(harness.findByPath("treeA", "old"), "the device kept the folder under its first name")
        assertNull(harness.findByPath("treeA", "other"), "the device kept the other folder under its first name")
        assertEquals("from old", harness.findByPath("treeA", "final/from_old.txt")?.bytes?.toString(Charsets.UTF_8))
        assertNotNull(harness.findByPath("treeA", "final/.git/head"), "the folder was not renamed whole")
        assertEquals("from other", harness.findByPath("treeA", "tmp/from_other.txt")?.bytes?.toString(Charsets.UTF_8))
        assertNull(harness.findByPath("treeA", "tmp/from_old.txt"), "the two folders were merged under one name")
        assertTrue(harness.createdCalls.isEmpty(), "a folder was made again from the copy: ${harness.createdCalls}")
        assertEquals(emptyList<String>(), harness.failedNames)
    }

    @Test
    fun `a folder renamed onto a name and removed there before another folder takes that name is gone from the device`() {
        // mv old tmp; rm -r tmp; mv other tmp, inside one poll. The first rename gave way to the third, onto the same
        // name, so the deletes between looked up a tmp the device did not hold yet and did nothing: the device kept
        // old, and the next open brought it back.
        val harness = VirtualSafHarness()
        harness.addDir("treeA", "old")
        harness.addFile("treeA", "old/from_old.txt", "from old")
        harness.addDir("treeA", "other")
        harness.addFile("treeA", "other/from_other.txt", "from other")
        val tree = harness.treeUris.getValue("treeA")
        val engine = harness.buildEngine()
        runBlocking { engine.initialSync(tree, mirror) { _, _ -> } }

        moveFolder(engine, tree, "old", "tmp")
        val file = File(mirror, "tmp/from_old.txt")
        assertTrue(file.delete())
        engine.handleMirrorEvent(FileObserver.DELETE, file, mirror, tree)
        assertTrue(File(mirror, "tmp").delete())
        engine.handleMirrorEvent(FileObserver.DELETE or 0x40000000, File(mirror, "tmp"), mirror, tree)
        moveFolder(engine, tree, "other", "tmp")

        engine.runWriteBackLoop { false }

        assertEquals(listOf("tmp"), harness.childNames("treeA"), "the removed folder is on the device under some name")
        assertEquals(listOf("from_other.txt"), harness.childNames("treeA", "tmp"))
        assertEquals("from other", harness.findByPath("treeA", "tmp/from_other.txt")?.bytes?.toString(Charsets.UTF_8))
        assertTrue(harness.createdCalls.isEmpty(), "a folder was made again from the copy: ${harness.createdCalls}")
        assertEquals(emptyList<String>(), harness.failedNames + harness.deleteRefusedNames)
    }

    /** The device folder `old` holding a file the open reads, and `other` beside it. */
    private fun twoReadFolders(): Pair<VirtualSafHarness, SafSyncEngine> {
        val harness = VirtualSafHarness()
        harness.addDir("treeA", "old")
        harness.addFile("treeA", "old/from_old.txt", "from old")
        harness.addDir("treeA", "other")
        harness.addFile("treeA", "other/from_other.txt", "from other")
        val engine = harness.buildEngine()
        runBlocking { engine.initialSync(harness.treeUris.getValue("treeA"), mirror) { _, _ -> } }
        return harness to engine
    }

    @Test
    fun `a delete after the folder renamed onto a name has arrived there is of that folder`() {
        // mv old tmp; rm -r tmp; mv other tmp; rm tmp/from_other.txt, inside one poll. The last delete is of what other
        // brought to tmp, not of what old left there.
        val (harness, engine) = twoReadFolders()
        val tree = harness.treeUris.getValue("treeA")
        moveFolder(engine, tree, "old", "tmp")
        for (file in listOf(File(mirror, "tmp/from_old.txt"), File(mirror, "tmp"))) {
            assertTrue(file.delete())
            engine.handleMirrorEvent(FileObserver.DELETE or (if (file.name == "tmp") 0x40000000 else 0), file, mirror, tree)
        }
        moveFolder(engine, tree, "other", "tmp")
        val later = File(mirror, "tmp/from_other.txt")
        assertTrue(later.delete())
        engine.handleMirrorEvent(FileObserver.DELETE, later, mirror, tree)

        engine.runWriteBackLoop { false }

        assertEquals(listOf("tmp"), harness.childNames("treeA"))
        assertEquals(emptyList<String>(), harness.childNames("treeA", "tmp"), "the file deleted from the folder that arrived is still there")
        assertEquals(emptyList<String>(), harness.failedNames + harness.deleteRefusedNames)
    }

    @Test
    fun `a folder renamed onto a name and moved out of the folder stays on the device when something made there since is removed`() {
        // mv old tmp; mv tmp <out of the folder>; mkdir tmp; rmdir tmp; mv other tmp, inside one poll. A folder moved out
        // stays on the device, and the folder removed at tmp is the one made there since, not old.
        val (harness, engine) = twoReadFolders()
        val tree = harness.treeUris.getValue("treeA")
        moveFolder(engine, tree, "old", "tmp")
        assertTrue(File(mirror, "tmp").renameTo(File(filesDir, "moved-out")))
        engine.handleMirrorEvent(FileObserver.MOVED_FROM or 0x40000000, File(mirror, "tmp"), mirror, tree)
        // Past the window in which a folder arriving at tmp could be taken for the one that left it, which an event
        // without the watcher's identities would otherwise do.
        Thread.sleep(SafSyncEngine.RENAME_PAIR_WINDOW_MS + 100)
        val made = File(mirror, "tmp").apply { assertTrue(mkdir()) }
        engine.handleMirrorEvent(FileObserver.CREATE or 0x40000000, made, mirror, tree)
        assertTrue(made.delete())
        engine.handleMirrorEvent(FileObserver.DELETE or 0x40000000, made, mirror, tree)
        moveFolder(engine, tree, "other", "tmp")

        engine.runWriteBackLoop { false }

        assertEquals("from old", harness.findByPath("treeA", "old/from_old.txt")?.bytes?.toString(Charsets.UTF_8), "a folder moved out was deleted on the device")
        assertEquals(listOf("from_other.txt"), harness.childNames("treeA", "tmp"))
    }

    /**
     * `mv old tmp; rm -r tmp; mv other tmp` where a delete cannot go through: [beforeOpen] and [afterOpen] leave the
     * device folder `old` as the shape needs it. The delete is refused under the name the device holds the folder by,
     * as any delete there is, and the folder renamed onto `tmp` next lands there alone. Sent first instead, the rename
     * left the folder at `tmp`, and the next one was put beside it under a name the provider made up.
     */
    private fun renamedRemovedAndReplaced(
        beforeOpen: (VirtualSafHarness) -> Unit,
        afterOpen: (VirtualSafHarness) -> Unit = {},
    ): VirtualSafHarness {
        val harness = VirtualSafHarness()
        harness.addDir("treeA", "old")
        harness.addDir("treeA", "other")
        harness.addFile("treeA", "other/from_other.txt", "from other")
        val tree = harness.treeUris.getValue("treeA")
        val engine = harness.buildEngine()
        beforeOpen(harness)
        runBlocking { engine.initialSync(tree, mirror) { _, _ -> } }
        afterOpen(harness)

        moveFolder(engine, tree, "old", "tmp")
        File(mirror, "tmp").listFiles()!!.forEach { file ->
            assertTrue(file.delete())
            engine.handleMirrorEvent(FileObserver.DELETE, file, mirror, tree)
        }
        assertTrue(File(mirror, "tmp").delete())
        engine.handleMirrorEvent(FileObserver.DELETE or 0x40000000, File(mirror, "tmp"), mirror, tree)
        moveFolder(engine, tree, "other", "tmp")

        engine.runWriteBackLoop { false }

        assertEquals(listOf("old", "tmp"), harness.childNames("treeA"), "the device does not hold each folder once")
        assertEquals(listOf("from_other.txt"), harness.childNames("treeA", "tmp"))
        assertTrue(harness.createdCalls.isEmpty(), "a folder was made again from the copy: ${harness.createdCalls}")
        return harness
    }

    @Test
    fun `an empty folder renamed onto a name and removed there stays under its first name, said, and the name goes to the next folder`() {
        // Nothing this engine read lies under an empty folder, so a delete of it cannot name its document.
        val harness = renamedRemovedAndReplaced(beforeOpen = {})
        assertEquals(listOf("old"), harness.deleteRefusedNames, "the folder the device kept was not told of")
    }

    @Test
    fun `a folder holding what the copy never held, renamed onto a name and removed there, keeps only that under its first name`() {
        val harness = renamedRemovedAndReplaced(beforeOpen = {
            it.addFile("treeA", "old/from_old.txt", "from old")
            it.addFile("treeA", "old/.git/head", "ref: old")
        })
        assertEquals(listOf(".git"), harness.childNames("treeA", "old"), "the delete of what the copy held did not reach the device")
    }

    @Test
    fun `a folder another app changed, renamed onto a name and removed there, keeps the change under its first name`() {
        val harness = renamedRemovedAndReplaced(
            beforeOpen = { it.addFile("treeA", "old/from_old.txt", "from old") },
            afterOpen = {
                it.findByPath("treeA", "old/from_old.txt")!!.apply {
                    bytes = "changed by another app".toByteArray()
                    lastModified += 60_000L
                }
            },
        )
        assertEquals(
            "changed by another app",
            harness.findByPath("treeA", "old/from_old.txt")?.bytes?.toString(Charsets.UTF_8),
            "the delete went over another app's change",
        )
    }

    @Test
    fun `a folder moved into a folder made just before it that is then renamed ends up whole under the last name`() {
        // mkdir sub; mv old sub/mid; mv sub sub2, inside one poll. The device holds no sub, and the move into it lost
        // its place when sub was renamed: the device kept old, and sub2/mid was made from the copy without its .git.
        val harness = VirtualSafHarness()
        harness.addDir("treeA", "old")
        harness.addFile("treeA", "old/child.txt", "original")
        harness.addFile("treeA", "old/.git/head", "ref: old")
        val treeA = harness.treeUris.getValue("treeA")
        val engine = harness.buildEngine()
        movesDocuments(harness)

        runBlocking { engine.initialSync(treeA, mirror) { _, _ -> } }
        val subDir = File(mirror, "sub").apply { mkdirs() }
        engine.handleMirrorEvent(FileObserver.CREATE or 0x40000000, subDir, mirror, treeA)

        val oldDir = File(mirror, "old")
        val midDir = File(subDir, "mid")
        assertTrue(oldDir.renameTo(midDir))
        engine.handleMirrorEvent(FileObserver.MOVED_FROM or 0x40000000, oldDir, mirror, treeA)
        Thread.sleep(2)
        engine.handleMirrorEvent(FileObserver.MOVED_TO or 0x40000000, midDir, mirror, treeA)

        val subRenamed = File(mirror, "sub2")
        assertTrue(subDir.renameTo(subRenamed))
        engine.handleMirrorEvent(FileObserver.MOVED_FROM or 0x40000000, subDir, mirror, treeA)
        Thread.sleep(2)
        engine.handleMirrorEvent(FileObserver.MOVED_TO or 0x40000000, subRenamed, mirror, treeA)

        engine.runWriteBackLoop { false }

        assertNull(harness.findByPath("treeA", "old"), "the device kept the folder under its first name")
        assertNull(harness.findByPath("treeA", "sub"), "the device holds the folder under its first name too")
        assertNotNull(harness.findByPath("treeA", "sub2/mid/.git/head"), "the folder was not moved whole")
        assertEquals("original", harness.findByPath("treeA", "sub2/mid/child.txt")?.bytes?.toString(Charsets.UTF_8))
        assertTrue(
            harness.createdCalls.none { it.second == "mid" || it.second == "child.txt" },
            "the moved folder was made again from the copy: ${harness.createdCalls}",
        )
        assertEquals(emptyList<String>(), harness.failedNames)
    }

    /**
     * The move into a folder the device does not hold follows that folder's rename, and so does the rename made inside
     * the folder of what the move brings, behind it: that rename finds its source on the device only once the move has
     * run, and left where it was queued it ran first.
     */
    @Test
    fun `a folder made just before a move into it and renamed takes the move and the rename of what it moves along`() {
        val harness = VirtualSafHarness()
        harness.addDir("treeA", "old")
        harness.addFile("treeA", "old/child.txt", "original")
        val treeA = harness.treeUris.getValue("treeA")
        val engine = harness.buildEngine()

        runBlocking { engine.initialSync(treeA, mirror) { _, _ -> } }
        val subDir = File(mirror, "sub").apply { mkdirs() }
        engine.handleMirrorEvent(FileObserver.CREATE or 0x40000000, subDir, mirror, treeA)
        val midDir = File(subDir, "mid")
        assertTrue(File(mirror, "old").renameTo(midDir))
        engine.handleMirrorEvent(FileObserver.MOVED_FROM or 0x40000000, File(mirror, "old"), mirror, treeA)
        Thread.sleep(2)
        engine.handleMirrorEvent(FileObserver.MOVED_TO or 0x40000000, midDir, mirror, treeA)
        val inner = File(subDir, "inner")
        assertTrue(midDir.renameTo(inner))
        engine.handleMirrorEvent(FileObserver.MOVED_FROM or 0x40000000, midDir, mirror, treeA)
        Thread.sleep(2)
        engine.handleMirrorEvent(FileObserver.MOVED_TO or 0x40000000, inner, mirror, treeA)
        assertTrue(subDir.renameTo(File(mirror, "sub2")))
        engine.handleMirrorEvent(FileObserver.MOVED_FROM or 0x40000000, subDir, mirror, treeA)
        Thread.sleep(2)
        engine.handleMirrorEvent(FileObserver.MOVED_TO or 0x40000000, File(mirror, "sub2"), mirror, treeA)

        val renames = engine.session.queue.filter { it.type == SyncType.RENAME }
            .map { File(it.previousPath!!).toRelativeString(mirror) to it.relativePath }
        assertEquals(listOf("old" to "sub2/mid", "sub2/mid" to "sub2/inner"), renames)
    }

    /** `mv` of a folder in [mirror] as the watcher reports it: the departure, then the arrival a moment later. */
    private fun moveFolder(engine: SafSyncEngine, tree: Uri, from: String, to: String) {
        val source = File(mirror, from)
        val target = File(mirror, to)
        assertTrue(source.renameTo(target))
        engine.handleMirrorEvent(FileObserver.MOVED_FROM or 0x40000000, source, mirror, tree)
        Thread.sleep(2)
        engine.handleMirrorEvent(FileObserver.MOVED_TO or 0x40000000, target, mirror, tree)
    }

    /** A device folder `old` holding a file and, below `.git`, what the copy never holds. */
    private fun deviceFolderWithGit(): Pair<VirtualSafHarness, SafSyncEngine> {
        val harness = VirtualSafHarness()
        harness.addDir("treeA", "old")
        harness.addFile("treeA", "old/child.txt", "original")
        harness.addFile("treeA", "old/.git/head", "ref: old")
        val engine = harness.buildEngine()
        movesDocuments(harness)
        runBlocking { engine.initialSync(harness.treeUris.getValue("treeA"), mirror) { _, _ -> } }
        return harness to engine
    }

    /** Asserts that the device folder `old` ended up whole at [path] and nowhere else. */
    private fun assertMovedWhole(harness: VirtualSafHarness, path: String) {
        assertNull(harness.findByPath("treeA", "old"), "the device kept the folder under its first name")
        assertNotNull(harness.findByPath("treeA", "$path/.git/head"), "the folder was not moved whole to $path")
        assertEquals("original", harness.findByPath("treeA", "$path/child.txt")?.bytes?.toString(Charsets.UTF_8))
        assertTrue(
            harness.createdCalls.none { it.second == "child.txt" || it.second == File(path).name },
            "the moved folder was made again from the copy: ${harness.createdCalls}",
        )
        assertEquals(emptyList<String>(), harness.failedNames)
    }

    @Test
    fun `a folder moved into a folder made just before it and renamed there ends up whole under its last name when that folder is renamed`() {
        // mkdir sub; mv old sub/mid; mv sub/mid sub/inner; mv sub sub2, inside one poll. The rename inside sub looks
        // its source up when it runs, and it ran before the move it waits for, which follows sub's rename: it found
        // nothing, sub2 was made with inner from the copy, and the move then put the whole folder at sub2/mid.
        val (harness, engine) = deviceFolderWithGit()
        val tree = harness.treeUris.getValue("treeA")
        val subDir = File(mirror, "sub").apply { mkdirs() }
        engine.handleMirrorEvent(FileObserver.CREATE or 0x40000000, subDir, mirror, tree)
        moveFolder(engine, tree, "old", "sub/mid")
        moveFolder(engine, tree, "sub/mid", "sub/inner")
        moveFolder(engine, tree, "sub", "sub2")

        engine.runWriteBackLoop { false }

        assertMovedWhole(harness, "sub2/inner")
        assertNull(harness.findByPath("treeA", "sub2/mid"), "the folder is on the device under the name it had in sub too")
        assertNull(harness.findByPath("treeA", "sub"), "the device holds the folder made first under its first name")
        // A save dropped under a rename's source is filed by that source until its job takes it; filed under the name
        // the source had before it was followed, it was never taken, and caught every later drop under that name.
        assertEquals(emptyMap<String, List<String>>(), engine.session.claimedDrops.toMap(), "a dropped save is filed for no job")
    }

    @Test
    fun `a folder renamed twice inside a folder made just before the move into it ends up whole under its last name`() {
        // mkdir sub; mv old sub/a; mv sub/a sub/b; mv sub/b sub/c; mv sub sub2, inside one poll.
        val (harness, engine) = deviceFolderWithGit()
        val tree = harness.treeUris.getValue("treeA")
        val subDir = File(mirror, "sub").apply { mkdirs() }
        engine.handleMirrorEvent(FileObserver.CREATE or 0x40000000, subDir, mirror, tree)
        moveFolder(engine, tree, "old", "sub/a")
        moveFolder(engine, tree, "sub/a", "sub/b")
        moveFolder(engine, tree, "sub/b", "sub/c")
        moveFolder(engine, tree, "sub", "sub2")

        engine.runWriteBackLoop { false }

        assertMovedWhole(harness, "sub2/c")
        for (name in listOf("sub2/a", "sub2/b", "sub")) {
            assertNull(harness.findByPath("treeA", name), "the device holds the folder at $name too")
        }
    }

    @Test
    fun `a folder moved on from one folder made just before it into another, both then renamed, ends up whole in the second`() {
        // mkdir sub sub3; mv old sub/mid; mv sub/mid sub3/mid; mv sub sub2; mv sub3 sub4, inside one poll.
        val (harness, engine) = deviceFolderWithGit()
        val tree = harness.treeUris.getValue("treeA")
        for (name in listOf("sub", "sub3")) {
            val made = File(mirror, name).apply { mkdirs() }
            engine.handleMirrorEvent(FileObserver.CREATE or 0x40000000, made, mirror, tree)
        }
        moveFolder(engine, tree, "old", "sub/mid")
        moveFolder(engine, tree, "sub/mid", "sub3/mid")
        moveFolder(engine, tree, "sub", "sub2")
        moveFolder(engine, tree, "sub3", "sub4")

        engine.runWriteBackLoop { false }

        assertMovedWhole(harness, "sub4/mid")
        assertNotNull(harness.findByPath("treeA", "sub2"), "the first folder made did not reach the device")
        assertNull(harness.findByPath("treeA", "sub2/mid"), "the folder is on the device in the first folder too")
    }

    @Test
    fun `a folder moved into a folder made just before it and deleted there is gone from the device once that folder is renamed`() {
        // mkdir sub; mv old sub/mid; rm -r sub/mid; mv sub sub2, inside one poll. The deletes look their documents up
        // when they run, and ran before the move they wait for, which follows sub's rename: they found nothing, and the
        // move then put the deleted folder at sub2/mid, where the next open fetched it back.
        val harness = VirtualSafHarness()
        harness.addDir("treeA", "old")
        harness.addFile("treeA", "old/child.txt", "original")
        val tree = harness.treeUris.getValue("treeA")
        val engine = harness.buildEngine()
        movesDocuments(harness)
        runBlocking { engine.initialSync(tree, mirror) { _, _ -> } }
        val subDir = File(mirror, "sub").apply { mkdirs() }
        engine.handleMirrorEvent(FileObserver.CREATE or 0x40000000, subDir, mirror, tree)
        moveFolder(engine, tree, "old", "sub/mid")
        val child = File(mirror, "sub/mid/child.txt")
        assertTrue(child.delete())
        engine.handleMirrorEvent(FileObserver.DELETE, child, mirror, tree)
        assertTrue(File(mirror, "sub/mid").delete())
        engine.handleMirrorEvent(FileObserver.DELETE or 0x40000000, File(mirror, "sub/mid"), mirror, tree)
        moveFolder(engine, tree, "sub", "sub2")

        engine.runWriteBackLoop { false }

        assertNull(harness.findByPath("treeA", "old"), "the device kept the deleted folder under its first name")
        assertNull(harness.findByPath("treeA", "sub2/mid"), "the deleted folder is on the device in the renamed folder")
        assertNotNull(harness.findByPath("treeA", "sub2"), "the renamed folder did not reach the device")
        assertEquals(emptyList<String>(), harness.failedNames + harness.deleteRefusedNames)
    }

    /** `moveDocument` as a provider that supports it answers: the document goes under the target parent, name kept. */
    private fun movesDocuments(harness: VirtualSafHarness) {
        every { DocumentsContract.moveDocument(any(), any(), any(), any()) } answers {
            val docUri = secondArg<Uri>()
            harness.reparent(docUri.toString().removePrefix("doc-uri:"), arg<Uri>(3).toString().removePrefix("doc-uri:"))
            docUri
        }
    }

    @Test
    fun `a folder moved into a folder made just before it is moved whole and not also made from the copy`() {
        // mkdir sub; mv old sub/mid, inside one poll. The create of sub runs first and used to send what the copy
        // holds under it, the moved folder included, so the move that followed met a folder of its name there.
        val harness = VirtualSafHarness()
        harness.addDir("treeA", "old")
        harness.addFile("treeA", "old/child.txt", "original")
        harness.addFile("treeA", "old/.git/head", "ref: old")
        val treeA = harness.treeUris.getValue("treeA")
        val engine = harness.buildEngine()
        movesDocuments(harness)

        runBlocking { engine.initialSync(treeA, mirror) { _, _ -> } }
        val subDir = File(mirror, "sub").apply { mkdirs() }
        engine.handleMirrorEvent(FileObserver.CREATE or 0x40000000, subDir, mirror, treeA)
        val oldDir = File(mirror, "old")
        val midDir = File(subDir, "mid")
        assertTrue(oldDir.renameTo(midDir))
        engine.handleMirrorEvent(FileObserver.MOVED_FROM or 0x40000000, oldDir, mirror, treeA)
        Thread.sleep(2)
        engine.handleMirrorEvent(FileObserver.MOVED_TO or 0x40000000, midDir, mirror, treeA)

        engine.runWriteBackLoop { false }

        assertNull(harness.findByPath("treeA", "old"), "the device kept the folder under its first name")
        assertNotNull(harness.findByPath("treeA", "sub/mid/.git/head"), "the folder was not moved whole")
        assertEquals("original", harness.findByPath("treeA", "sub/mid/child.txt")?.bytes?.toString(Charsets.UTF_8))
        assertEquals(listOf("sub"), harness.createdCalls.map { it.second }, "the moved folder was made from the copy too")
        assertEquals(emptyList<String>(), harness.failedNames)
    }

    @Test
    fun `missing-source fallback after folder switch with known parent writes to the queued folder`() {
        val harness = VirtualSafHarness(mapOf("treeA" to "testA", "treeB" to "testB"))
        val treeA = harness.treeUris.getValue("treeA")
        val treeB = harness.treeUris.getValue("treeB")
        val engine = harness.buildEngine()

        runBlocking { engine.initialSync(treeA, mirror) { _, _ -> } }

        val ghostDir = File(mirror, "ghost").apply { mkdirs() }
        File(ghostDir, "note.txt").writeText("local only")
        val materialized = File(mirror, "materialized")
        assertTrue(ghostDir.renameTo(materialized))
        engine.handleMirrorEvent(FileObserver.MOVED_FROM or 0x40000000, ghostDir, mirror, treeA)
        Thread.sleep(2)
        engine.handleMirrorEvent(FileObserver.MOVED_TO or 0x40000000, materialized, mirror, treeA)

        val mirrorB = File(filesDir, "mirrorB").apply { mkdirs() }
        runBlocking { engine.initialSync(treeB, mirrorB) { _, _ -> } }

        engine.runWriteBackLoop { false }

        assertEquals(
            "local only",
            harness.findByPath("treeA", "materialized/note.txt")?.bytes?.toString(Charsets.UTF_8),
        )
    }

    @Test
    fun `stale generation reports write-back failure for an unsynced moved folder deleted before drain`() {
        val harness = VirtualSafHarness()
        val treeA = harness.treeUris.getValue("treeA")
        val engine = harness.buildEngine()

        runBlocking { engine.initialSync(treeA, mirror) { _, _ -> } }

        val ghostDir = File(mirror, "ghost").apply { mkdirs() }
        File(ghostDir, "note.txt").writeText("local only")
        val vanished = File(mirror, "vanished")
        assertTrue(ghostDir.renameTo(vanished))
        engine.handleMirrorEvent(FileObserver.MOVED_FROM or 0x40000000, ghostDir, mirror, treeA)
        Thread.sleep(2)
        engine.handleMirrorEvent(FileObserver.MOVED_TO or 0x40000000, vanished, mirror, treeA)

        val note = File(vanished, "note.txt")
        assertTrue(note.delete())
        engine.handleMirrorEvent(FileObserver.DELETE, note, mirror, treeA)
        assertTrue(vanished.delete())
        engine.handleMirrorEvent(FileObserver.DELETE or 0x40000000, vanished, mirror, treeA)

        runBlocking { engine.initialSync(treeA, mirror) { _, _ -> } }
        engine.runWriteBackLoop { false }

        assertTrue(harness.failedNames.isEmpty(), "reported write-back failure for already deleted path: ${harness.failedNames}")
    }

    @Test
    fun `failed same-dir rename with unresolved event parent falls back to create without overwriting external device child`() {
        val harness = VirtualSafHarness()
        harness.addDir("treeA", "d0")
        val oldId = harness.addDir("treeA", "d0/old")
        val oldNode = harness.findByPath("treeA", "d0/old")!!
        harness.addFile("treeA", "d0/old/child.txt", "from mirror")
        val treeA = harness.treeUris.getValue("treeA")
        val engine = harness.buildEngine()
        val outputsById = mutableMapOf<String, Int>()
        harness.beforeOpenOutput = { docId ->
            outputsById[docId] = (outputsById[docId] ?: 0) + 1
        }
        every { DocumentsContract.renameDocument(any(), any(), any()) } answers {
            val docUri = secondArg<Uri>()
            val targetName = thirdArg<String>()
            val docId = docUri.toString().removePrefix("doc-uri:")
            if (docId == oldId && targetName == "target") {
                null
            } else {
                harness.renamedCalls += docId to targetName
                if (docId == oldId) oldNode.name = targetName
                docUri
            }
        }

        runBlocking { engine.initialSync(treeA, mirror) { _, _ -> } }

        engine.resetCache(treeA)
        var rootQueries = 0
        harness.shouldFailQuery = { raw ->
            raw == "children|treeA::root" && ++rootQueries == 2
        }

        val dir = File(mirror, "d0")
        val oldDir = File(dir, "old")
        val targetDir = File(dir, "target")
        assertTrue(oldDir.renameTo(targetDir))
        engine.handleMirrorEvent(FileObserver.MOVED_FROM or 0x40000000, oldDir, mirror, treeA)
        Thread.sleep(2)
        engine.handleMirrorEvent(FileObserver.MOVED_TO or 0x40000000, targetDir, mirror, treeA)
        harness.shouldFailQuery = null

        val externalId = harness.addFile("treeA", "d0/target/child.txt", "external on device")

        engine.runWriteBackLoop { false }

        assertEquals(0, outputsById[externalId] ?: 0, "external device child was overwritten")
    }

    @Test
    fun `a subfolder moved out of a folder whose rename is still queued leaves only its new place on the device`() {
        // p1_mid/old has no document at event time, since the device still holds it as p1/old: the move's job looks
        // it up when it runs, after the parent's rename, instead of leaving it under the renamed parent.
        val harness = VirtualSafHarness()
        val p1Id = harness.addDir("treeA", "p1")
        val p1Node = harness.findByPath("treeA", "p1")!!
        val oldId = harness.addDir("treeA", "p1/old")
        val oldNode = harness.findByPath("treeA", "p1/old")!!
        harness.addFile("treeA", "p1/old/child.txt", "deep")
        harness.addDir("treeA", "p2")
        val treeA = harness.treeUris.getValue("treeA")
        val engine = harness.buildEngine()
        every { DocumentsContract.moveDocument(any(), any(), any(), any()) } answers {
            val docUri = secondArg<Uri>()
            val targetParentUri = arg<Uri>(3)
            val docId = docUri.toString().removePrefix("doc-uri:")
            val targetParentId = targetParentUri.toString().removePrefix("doc-uri:")
            if (docId == oldId) oldNode.parentId = targetParentId
            docUri
        }
        every { DocumentsContract.renameDocument(any(), any(), any()) } answers {
            val docUri = secondArg<Uri>()
            val targetName = thirdArg<String>()
            val docId = docUri.toString().removePrefix("doc-uri:")
            harness.renamedCalls += docId to targetName
            when (docId) {
                p1Id -> p1Node.name = targetName
                oldId -> oldNode.name = targetName
            }
            docUri
        }

        runBlocking { engine.initialSync(treeA, mirror) { _, _ -> } }

        val p1Dir = File(mirror, "p1")
        val p1Mid = File(mirror, "p1_mid")
        assertTrue(p1Dir.renameTo(p1Mid))
        engine.handleMirrorEvent(FileObserver.MOVED_FROM or 0x40000000, p1Dir, mirror, treeA)
        Thread.sleep(2)
        engine.handleMirrorEvent(FileObserver.MOVED_TO or 0x40000000, p1Mid, mirror, treeA)

        val movingChild = File(p1Mid, "old")
        val dest = File(mirror, "p2/moved")
        assertTrue(movingChild.renameTo(dest))
        engine.handleMirrorEvent(FileObserver.MOVED_FROM or 0x40000000, movingChild, mirror, treeA)
        Thread.sleep(2)
        engine.handleMirrorEvent(FileObserver.MOVED_TO or 0x40000000, dest, mirror, treeA)

        engine.runWriteBackLoop { false }

        assertNotNull(harness.findByPath("treeA", "p1_mid"))
        assertNull(harness.findByPath("treeA", "p1_mid/old"), "the moved subfolder stayed under the renamed folder too")
        assertEquals("deep", harness.findByPath("treeA", "p2/moved/child.txt")?.bytes?.toString(Charsets.UTF_8))
    }

    @Test
    fun `reopening the same tree into a new mirror does not let an earlier unresolved rename rename an external folder`() {
        val harness = VirtualSafHarness()
        val treeA = harness.treeUris.getValue("treeA")
        val engine = harness.buildEngine()

        runBlocking { engine.initialSync(treeA, mirror) { _, _ -> } }

        val ghostDir = File(mirror, "mid").apply { mkdirs() }
        File(ghostDir, "child.txt").writeText("local")
        val finalDir = File(mirror, "final")
        assertTrue(ghostDir.renameTo(finalDir))
        engine.handleMirrorEvent(FileObserver.MOVED_FROM or 0x40000000, ghostDir, mirror, treeA)
        Thread.sleep(2)
        engine.handleMirrorEvent(FileObserver.MOVED_TO or 0x40000000, finalDir, mirror, treeA)

        harness.addDir("treeA", "mid")
        harness.addFile("treeA", "mid/from_device.txt", "device copy")
        val mirrorSecond = File(filesDir, "mirrorSecond").apply { mkdirs() }
        runBlocking { engine.initialSync(treeA, mirrorSecond) { _, _ -> } }
        harness.queriedTargets.clear()

        engine.runWriteBackLoop { false }

        assertTrue(harness.renamedCalls.isEmpty(), "unresolved earlier rename touched external device folder: ${harness.renamedCalls}")
        assertEquals(
            "device copy",
            harness.findByPath("treeA", "mid/from_device.txt")?.bytes?.toString(Charsets.UTF_8),
        )
    }

    @Test
    fun `draining a pre-resolved rename with queued child saves after switching folders leaves the new tree untouched`() {
        val harness = VirtualSafHarness(mapOf("treeA" to "testA", "treeB" to "testB"))
        harness.addDir("treeA", "old")
        harness.addFile("treeA", "old/child.txt", "from A")
        harness.addDir("treeB", "renamed")
        harness.addFile("treeB", "renamed/child.txt", "kept in B")
        val treeA = harness.treeUris.getValue("treeA")
        val treeB = harness.treeUris.getValue("treeB")
        val engine = harness.buildEngine()

        runBlocking { engine.initialSync(treeA, mirror) { _, _ -> } }
        val oldDir = File(mirror, "old")
        val renamedDir = File(mirror, "renamed")
        val child = File(oldDir, "child.txt").apply { writeText("edited A") }
        engine.handleMirrorEvent(FileObserver.MODIFY, child, mirror, treeA)

        assertTrue(oldDir.renameTo(renamedDir))
        engine.handleMirrorEvent(FileObserver.MOVED_FROM or 0x40000000, oldDir, mirror, treeA)
        Thread.sleep(2)
        engine.handleMirrorEvent(FileObserver.MOVED_TO or 0x40000000, renamedDir, mirror, treeA)

        val mirrorB = File(filesDir, "mirrorB").apply { mkdirs() }
        runBlocking { engine.initialSync(treeB, mirrorB) { _, _ -> } }
        harness.queriedTargets.clear()
        harness.renamedCalls.clear()

        engine.runWriteBackLoop { false }

        assertTrue(harness.queriedTargets.none { it.contains("treeB::") })
        assertTrue(harness.renamedCalls.none { it.first.startsWith("treeB::") })
        assertEquals("kept in B", harness.findByPath("treeB", "renamed/child.txt")?.bytes?.toString(Charsets.UTF_8))
        assertEquals("edited A", harness.findByPath("treeA", "renamed/child.txt")?.bytes?.toString(Charsets.UTF_8))
    }
}
