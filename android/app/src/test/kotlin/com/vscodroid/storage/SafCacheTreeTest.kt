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
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * The document-id cache speaks for one folder at a time, and a folder switch is where
 * that has to hold under concurrency.
 *
 * Closing a folder stops its observers and then the next folder's sync clears and refills
 * the cache, but an event that was already inside its provider round trips when the
 * folder closed is not stopped: it arrives at the engine after the next sync has
 * relabelled the cache and while that sync is still filling it. The event used to clear
 * the cache and adopt it for its own tree, and from then on the two interleaved in one
 * map: the sync went on writing its entries, the event wrote the previous folder's
 * directories beside them, and the new folder's phase 2b, which resolves the parent of
 * every stranded file through the cache, could be handed the previous folder's directory
 * for a same-named path. Where one granted folder sits inside another, that directory is
 * writable, and the write opens it with `"wt"`.
 *
 * The interleaving is produced here by delivering the late event from inside the
 * provider query the new folder's enumeration makes, which is where a real one lands:
 * on another thread, part way through `walkTree`.
 */
class SafCacheTreeTest {

    @TempDir
    lateinit var mirrorA: File

    @TempDir
    lateinit var mirrorB: File

    @TempDir
    lateinit var filesDir: File

    private lateinit var resolver: ContentResolver
    private lateinit var engine: SafSyncEngine
    private lateinit var treeA: Uri
    private lateinit var treeB: Uri

    /** Every `createDocument`: the parent document URI it was asked to create under, and the name. */
    private val created = mutableListOf<Pair<String, String>>()

    /** Child listings the provider was asked for. */
    private val queries = AtomicInteger(0)

    /** Runs once, from inside the provider's listing of the parent it names. */
    private var duringListingOf: Pair<String, () -> Unit>? = null

    /** One entry as the provider lists it; document ids differ per tree, as they do for real. */
    private data class Entry(val name: String, val docId: String, val isDirectory: Boolean = false)

    /** Two granted folders, each with a `src` directory holding one file. */
    private val device: Map<String, List<Entry>> = mapOf(
        "rootA" to listOf(Entry("src", "srcA", isDirectory = true)),
        "srcA" to listOf(Entry("a.txt", "a")),
        "rootB" to listOf(Entry("src", "srcB", isDirectory = true)),
        "srcB" to listOf(Entry("b.txt", "b")),
    )

    @BeforeEach
    fun setUp() {
        mockkObject(Logger)
        every { Logger.i(any(), any()) } just Runs
        every { Logger.d(any(), any()) } just Runs
        every { Logger.w(any(), any()) } just Runs
        every { Logger.w(any(), any(), any()) } just Runs
        every { Logger.e(any(), any()) } just Runs
        every { Logger.e(any(), any(), any()) } just Runs

        treeA = named("tree:A")
        treeB = named("tree:B")

        mockkStatic(DocumentsContract::class)
        every { DocumentsContract.getTreeDocumentId(any()) } answers {
            if (firstArg<Uri>() === treeA) "rootA" else "rootB"
        }
        every { DocumentsContract.buildChildDocumentsUriUsingTree(any(), any()) } answers {
            named("children:${secondArg<String>()}")
        }
        every { DocumentsContract.buildDocumentUriUsingTree(any(), any()) } answers {
            named("doc-uri:${secondArg<String>()}")
        }
        every { DocumentsContract.getDocumentId(any()) } answers {
            firstArg<Uri>().toString().removePrefix("doc-uri:")
        }
        every { DocumentsContract.createDocument(any(), any(), any(), any()) } answers {
            val name = arg<String>(3)
            created.add(secondArg<Uri>().toString() to name)
            named("doc-uri:new:$name")
        }

        resolver = mockk(relaxed = true)
        every { resolver.query(any(), any(), any(), any(), any()) } answers {
            queries.incrementAndGet()
            val parent = firstArg<Uri>().toString().removePrefix("children:")
            duringListingOf?.takeIf { it.first == parent }?.let { hook ->
                duringListingOf = null
                hook.second()
            }
            cursorOver(device[parent].orEmpty())
        }
        every { resolver.openInputStream(any()) } answers {
            ByteArrayInputStream("contents".toByteArray())
        }
        every { resolver.openOutputStream(any(), "wt") } answers { ByteArrayOutputStream() }

        val context = mockk<Context>(relaxed = true)
        every { context.contentResolver } returns resolver
        every { context.filesDir } returns filesDir
        engine = SafSyncEngine(context)
    }

    @AfterEach
    fun tearDown() {
        File(mirrorA.path + SafSyncEngine.SYNCED_RECORD_SUFFIX).delete()
        File(mirrorB.path + SafSyncEngine.SYNCED_RECORD_SUFFIX).delete()
        unmockkAll()
    }

    private fun named(text: String): Uri =
        mockk<Uri>(relaxed = true).also { every { it.toString() } returns text }

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
            every { getString(0) } answers { entries[row].docId }
            every { getString(1) } answers { entries[row].name }
            every { getString(2) } answers {
                if (entries[row].isDirectory) DocumentsContract.Document.MIME_TYPE_DIR
                else "text/plain"
            }
            every { getLong(3) } returns "contents".toByteArray().size.toLong()
            every { getLong(4) } returns 1_700_000_000_000
        }
    }

    private fun sync(tree: Uri, mirror: File) = runBlocking {
        engine.initialSync(tree, mirror) { _, _ -> }
    }

    /**
     * Folder B is open, with a save stranded under `src` that its next open puts across.
     * Folder A was open before it, and a save from A is still resolving when B's sync
     * lists `src`.
     */
    private fun openBothFoldersWithAStrandedSaveInB() {
        sync(treeA, mirrorA)
        sync(treeB, mirrorB)
        File(mirrorB, "src/stranded.md").writeText("only here")
        File(mirrorA, "src/late.txt").writeText("saved as the folder closed")
    }

    @Test
    fun `a late event from the previous folder does not put its documents into the next folder's cache`() {
        openBothFoldersWithAStrandedSaveInB()

        duringListingOf = "srcB" to {
            engine.handleMirrorEvent(
                FileObserver.MODIFY, File(mirrorA, "src/late.txt"), mirrorA, treeA,
            )
        }
        sync(treeB, mirrorB)

        assertEquals(
            listOf("doc-uri:srcB" to "stranded.md"), created,
            "B's stranded save was created under a directory of A",
        )
        // The late save still resolved its own parent, from its own folder.
        val late = engine.session.queue.single()
        assertEquals("doc-uri:srcA", late.safParentUri.toString())
    }

    /** The control: with nothing arriving late, the cache the sync filled is what answers. */
    @Test
    fun `a stranded save is created under the directory its own folder's sync found`() {
        openBothFoldersWithAStrandedSaveInB()

        sync(treeB, mirrorB)

        assertEquals(listOf("doc-uri:srcB" to "stranded.md"), created)
    }

    /**
     * And the folder the cache does speak for still gets its fast path: a save under a
     * directory the sync enumerated resolves both the file and its parent without asking
     * the provider anything. Without this a label test that always failed would pass the
     * case above by sending everything to the provider.
     */
    @Test
    fun `a save in the folder the cache speaks for is resolved without the provider`() {
        sync(treeB, mirrorB)
        File(mirrorB, "src/b.txt").writeText("edited")
        queries.set(0)

        engine.handleMirrorEvent(FileObserver.MODIFY, File(mirrorB, "src/b.txt"), mirrorB, treeB)

        assertEquals(0, queries.get(), "the save walked the provider for a path the sync had cached")
    }
}
