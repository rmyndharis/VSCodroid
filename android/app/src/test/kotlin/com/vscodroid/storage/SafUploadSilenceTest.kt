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
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * What the user is told when work created in the editor does not reach the device.
 *
 * Three announcement channels already existed and all three face the same way: a
 * write-back that failed, a write refused because the device holds a document this
 * sync never read, and documents that did not arrive when a folder was opened. What
 * none of them covered is the plainest loss of all, a file created in the editor
 * that the provider refuses to create. `createOneInSaf` returned null, the caller
 * returned on it, and the only trace was a `Logger.w` no release build shows anyone.
 *
 * A file in that state is not merely unsynced. It exists in one place, inside the
 * app's private storage, and the user has no reason to suspect it: the editor shows
 * it, the folder is open, everything looks synced. The difference appears when the
 * app is uninstalled and the work is gone with it.
 */
class SafUploadSilenceTest {

    @TempDir
    lateinit var mirror: File

    @TempDir
    lateinit var filesDir: File

    private lateinit var resolver: ContentResolver
    private lateinit var engine: SafSyncEngine
    private lateinit var treeUri: Uri

    /** Files announced as existing only inside the app. */
    private val lost = mutableListOf<String>()

    /** Directories announced as arrived incomplete: name, count, capped. */
    private val incomplete = mutableListOf<Triple<String, Int, Boolean>>()

    /** Whether the provider will accept a create of a file. */
    private var filesAccepted = true

    /** Whether the provider will accept a create of a directory. */
    private var directoriesAccepted = true

    @BeforeEach
    fun setUp() {
        lost.clear()
        incomplete.clear()
        filesAccepted = true
        directoriesAccepted = true

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
        every { DocumentsContract.buildDocumentUriUsingTree(any(), any()) } returns
            mockk(relaxed = true)
        every { DocumentsContract.getDocumentId(any()) } returns "root"
        // The whole of what these cases turn on. A provider that refuses a create
        // answers null here, which is the documented contract, not an exception.
        every { DocumentsContract.createDocument(any(), any(), any(), any()) } answers {
            val isDir = thirdArg<String>() == DocumentsContract.Document.MIME_TYPE_DIR
            val accepted = if (isDir) directoriesAccepted else filesAccepted
            if (accepted) mockk<Uri>(relaxed = true) else null
        }

        resolver = mockk(relaxed = true)
        // An empty device folder: every local file is a create, never a write into an
        // existing document.
        val empty = mockk<Cursor>(relaxed = true)
        every { empty.moveToNext() } returns false
        every { resolver.query(any(), any(), any(), any(), any()) } returns empty
        every { resolver.openOutputStream(any(), any()) } returns ByteArrayOutputStream()

        val context = mockk<Context>(relaxed = true)
        every { context.contentResolver } returns resolver
        every { context.filesDir } returns filesDir

        engine = SafSyncEngine(context)
        engine.onWriteBackFailed = { lost += it.name }
        engine.onUploadIncomplete = { dir, count, capped ->
            incomplete += Triple(dir.name, count, capped)
        }
        treeUri = mockk(relaxed = true)
    }

    @AfterEach
    fun tearDown() {
        File(mirror.path + SafSyncEngine.SYNCED_RECORD_SUFFIX).delete()
        unmockkAll()
    }

    /** The event alone, without the write-back thread's pass over it. */
    private fun observe(entry: String) {
        engine.handleMirrorEvent(FileObserver.CREATE, File(mirror, entry), mirror, treeUri)
    }

    /** The write-back thread's pass over whatever the events queued. */
    private fun drain() = engine.runWriteBackLoop { false }

    /** One event and its drain, for the cases that need no gap between them. */
    private fun deliver(entry: String) {
        observe(entry)
        drain()
    }

    /**
     * A directory event, delivered the only way a JVM test can.
     *
     * `watchTree` registers a watch for a directory that exists when the event
     * arrives, and building a `FileObserver` runs a static initializer that reaches
     * native code. So the directory is absent at event time and appears before the
     * drain, which is also the order the watcher's own thread sees in production:
     * inotify reports the create, and the tree fills in while the queue is drained.
     */
    private fun deliverDirectory(name: String, fill: (File) -> Unit) {
        observe(name)
        File(mirror, name).apply { mkdirs() }.also(fill)
        drain()
    }

    /**
     * The case this exists for. A file created in the editor, a provider that refuses
     * it, and until now nothing said so.
     */
    @Test
    fun `a file the provider refuses to create is announced`() {
        filesAccepted = false
        File(mirror, "notes.md").writeText("work the user just did")

        deliver("notes.md")

        assertEquals(listOf("notes.md"), lost)
    }

    /**
     * The control, and it is what stops the case above from passing because the channel
     * fires on every create. A file that reached the device is not lost, and a notice
     * on every save would be worse than the silence it replaced.
     */
    @Test
    fun `a file the provider accepts announces nothing`() {
        File(mirror, "notes.md").writeText("work the user just did")

        deliver("notes.md")

        assertTrue(lost.isEmpty(), "announced $lost for a file that reached the device")
    }

    /**
     * The watcher re-queues a file on every save, so an unthrottled notice would fire
     * on every keystroke that triggers a write. Once per path is the whole point of
     * the set the refusal path already used.
     */
    @Test
    fun `a file that keeps failing is announced once`() {
        filesAccepted = false
        File(mirror, "notes.md").writeText("first")

        deliver("notes.md")
        File(mirror, "notes.md").writeText("second")
        deliver("notes.md")
        File(mirror, "notes.md").writeText("third")
        deliver("notes.md")

        assertEquals(listOf("notes.md"), lost, "the same file was announced repeatedly")
    }

    /**
     * A folder is the case where per-file notices stop being help. Forty refusals means
     * forty toasts, which is another way of telling the user nothing, so the directory
     * is announced once with the count.
     */
    @Test
    fun `a directory whose children are all refused is announced once with the count`() {
        deliverDirectory("project") { dir ->
            repeat(3) { File(dir, "file$it.txt").writeText("x") }
            filesAccepted = false
        }

        assertTrue(lost.isEmpty(), "a directory-wide failure produced per-file notices: $lost")
        assertEquals(1, incomplete.size, "expected one notice for the directory, got $incomplete")
        assertEquals("project", incomplete[0].first)
        assertTrue(incomplete[0].second > 0, "the notice carries no count")
        assertTrue(!incomplete[0].third, "a refusal was reported as the enumeration cap")
    }

    /**
     * When the folder itself is refused, nothing under it was even attempted, so the
     * folder is the thing that exists only inside the app and the per-file wording is
     * the accurate one. This is the boundary between the two channels, and getting it
     * backwards would either under-report a whole subtree or overstate a partial one.
     */
    @Test
    fun `a directory the provider refuses is announced as lost, not as incomplete`() {
        deliverDirectory("project") { dir ->
            repeat(3) { File(dir, "file$it.txt").writeText("x") }
            directoriesAccepted = false
        }

        assertEquals(listOf("project"), lost)
        assertTrue(incomplete.isEmpty(), "a folder that never existed was reported as partial")
    }

    /**
     * The other way a folder arrives incomplete, and the one no provider is at fault
     * for: the enumeration stops at a cap this app chose. Every entry that was reached
     * succeeded, so the shortfall count is zero and only the flag distinguishes it. A
     * notice that reported this as a refusal would send the user looking at the device.
     */
    @Test
    fun `a directory stopped by the enumeration cap is announced as capped`() {
        deliverDirectory("huge") { dir ->
            repeat(SafSyncEngine.MAX_UPLOAD_ENTRIES + 5) { File(dir, "f$it.txt").writeText("x") }
        }

        assertEquals(1, incomplete.size, "expected one notice, got $incomplete")
        assertEquals("huge", incomplete[0].first)
        assertTrue(incomplete[0].third, "the cap was reported as an ordinary failure")
    }

    /**
     * The control for the directory path. Everything arrived, so there is nothing to
     * say. Without this the case above would pass for an announcement that fires on
     * every folder copied out.
     */
    @Test
    fun `a directory that arrives whole announces nothing`() {
        deliverDirectory("project") { dir ->
            repeat(3) { File(dir, "file$it.txt").writeText("x") }
        }

        assertTrue(incomplete.isEmpty(), "announced $incomplete for a folder that arrived whole")
        assertTrue(lost.isEmpty(), "announced $lost for a folder that arrived whole")
    }
}
