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
        unmockkAll()
    }

    @Test
    fun `a free claim is taken at once`() {
        val (claim, ms) = timed { claimWithin(uri("test"), 5_000) }

        assertNotNull(claim)
        held.add(claim!!)
        assertTrue(ms < 500, "a free claim waited $ms ms")
    }

    @Test
    fun `a claim that comes free during the wait is taken, and promptly`() {
        val mutation = hold("test")
        releaseAfter(mutation, 300)

        val (claim, ms) = timed { claimWithin(uri("test"), 5_000) }

        assertNotNull(claim, "the waiter was refused although the holder let go within the wait")
        held.add(claim!!)
        assertTrue(ms >= 200, "the claim was taken before its holder let go ($ms ms)")
        assertTrue(ms < 2_000, "the release did not wake the waiter: it took $ms ms of a 5000 ms wait")
    }

    @Test
    fun `a claim that stays held is refused once the wait is over`() {
        hold("test")

        val (claim, ms) = timed { claimWithin(uri("test"), 300) }

        assertNull(claim)
        assertTrue(ms >= 250, "the waiter gave up after $ms ms of a 300 ms wait")
        assertTrue(ms < 2_000, "the wait ran on to $ms ms")
    }

    /** What keeps the drain of a removed tree from paying the ceiling for every file in it. */
    @Test
    fun `after one waiter has given up the next is refused at once`() {
        hold("test")
        assertNull(claimWithin(uri("test"), 300))

        val (second, ms) = timed { claimWithin(uri("test"), 10_000) }

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

        val (claim, ms) = timed { claimWithin(uri("test"), 300) }

        assertNull(claim)
        assertTrue(ms >= 250, "a new holder was refused without being waited for ($ms ms)")
    }

    /** A zero wait is the old refusal, and does not mark the holder: nothing waited. */
    @Test
    fun `a zero wait refuses at once without marking the holder`() {
        hold("test")
        assertNull(claimWithin(uri("test"), 0))

        val (claim, ms) = timed { claimWithin(uri("test"), 300) }

        assertNull(claim)
        assertTrue(ms >= 250, "a zero wait marked the holder as given up on, so the next waiter did not wait ($ms ms)")
    }

    @Test
    fun `another authority is not held up`() {
        hold("test")

        val (claim, ms) = timed { claimWithin(uri("other"), 5_000) }

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
        assertFalse(outcome.refused.get() || outcome.kept.get(), "the user was told about a delete that went through")
        assertTrue(ms >= 300, "the delete did not wait for the claim ($ms ms)")
    }

    @Test
    fun `a delete is refused with the notice when the claim stays held past the wait`() {
        val engine = engineOnDevice()
        engine.providerClaimWaitMs = 300
        val outcome = DeleteOutcome()
        hold("test")

        deleteWith(engine, outcome)

        assertFalse(outcome.deleted.get(), "the device document was deleted without the claim")
        assertTrue(outcome.kept.get(), "the document was not kept on the device, and the user was not told")
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
     * Saves are not among them. A save that meets the claim is held back and retried by
     * [SafSyncEngine.retryHeldBack] on its own schedule, and making it wait here would hold the
     * mirror's turn for nothing.
     */
    @Test
    fun `only the paths a user repeats wait, and a save is still held back`() {
        val source = File("src/main/kotlin/com/vscodroid/storage/SafSyncEngine.kt").readLines()
            .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") || it.trimStart().startsWith("/*") }
        val waiting = source.count { it.contains("claimProviderMutationWithin(") && !it.contains("private fun") }
        val trying = source.count { it.contains("claimProviderMutation(") && !it.contains("private fun") }

        assertEquals(3, waiting, "a delete, a create and a rename take the claim with a wait")
        assertEquals(3, trying, "the two open-time repairs and the save still take it without waiting")
    }
}
