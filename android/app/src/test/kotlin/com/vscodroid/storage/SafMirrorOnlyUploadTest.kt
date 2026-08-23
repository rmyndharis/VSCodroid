package com.vscodroid.storage

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Files that reached the mirror and never reached the device folder.
 *
 * Nothing in a sync used to look at the mirror at all. Phase 2 iterates the *device*
 * tree, and its only outbound arm needs a device document to compare against; phase 3
 * only deletes. So a file that exists in the mirror and nowhere else was invisible to
 * every recovery path, and reopening the folder, which the design calls the way back,
 * did nothing for it. Three ordinary ways to produce one: a create whose write-back job
 * died with the process before the 200 ms drain ran it, a file made in a directory past
 * `MAX_WATCHED_DIRECTORIES` where no observer exists, and a file made while the sync
 * itself is running and the watcher is deliberately stopped. The mirror keeps it, so
 * nothing is destroyed, but it lives in app-private storage where no other app can see
 * it and an uninstall takes it.
 *
 * The pass that fixes that can undo a deletion if it is wrong, so what it must NOT put
 * back is pinned here as carefully as what it must. A document the user deleted on the
 * device is also absent from the enumeration and also still in the mirror; the only thing
 * telling the two apart is the record the last sync wrote, which phase 3 is about to act
 * on.
 */
class SafMirrorOnlyUploadTest {

    @TempDir
    lateinit var mirror: File

    @TempDir
    lateinit var filesDir: File

    private lateinit var resolver: ContentResolver
    private lateinit var context: Context

    /** Every `createDocument` the sync performed, as (mime type, display name). */
    private val created = mutableListOf<Pair<String, String>>()

    /** What each created document was written with, by display name. */
    private val written = mutableMapOf<String, ByteArrayOutputStream>()

    /**
     * Whether the provider accepts a create. A refusal is a null answer, which is the
     * documented contract, not an exception.
     */
    private var createsAccepted = true

    /** Every file the sync said exists only inside the app, by name. */
    private val lost = mutableListOf<String>()

    /** Every "arrived incomplete" notice: mirror directory name, shortfall, capped. */
    private val incomplete = mutableListOf<Triple<String, Int, Boolean>>()

    private val record: File get() = File(mirror.path + SafSyncEngine.SYNCED_RECORD_SUFFIX)

    /** One entry of the device folder. */
    private data class Child(
        val name: String,
        val isDirectory: Boolean = false,
        val contents: String = "",
    )

    @BeforeEach
    fun setUp() {
        created.clear()
        written.clear()
        createsAccepted = true

        mockkObject(Logger)
        every { Logger.i(any(), any()) } just Runs
        every { Logger.d(any(), any()) } just Runs
        every { Logger.w(any(), any()) } just Runs
        every { Logger.w(any(), any(), any()) } just Runs
        every { Logger.e(any(), any()) } just Runs
        every { Logger.e(any(), any(), any()) } just Runs

        mockkStatic(DocumentsContract::class)
        every { DocumentsContract.getTreeDocumentId(any()) } returns "root"
        every { DocumentsContract.buildChildDocumentsUriUsingTree(any(), any()) } answers {
            named("children:${secondArg<String>()}")
        }
        every { DocumentsContract.buildDocumentUriUsingTree(any(), any()) } answers {
            named("doc-uri:${secondArg<String>()}")
        }
        every { DocumentsContract.getDocumentId(any()) } answers {
            firstArg<Uri>().toString().removePrefix("doc-uri:")
        }
        every {
            DocumentsContract.createDocument(any(), any(), any(), any())
        } answers {
            val mime = thirdArg<String>()
            val name = arg<String>(3)
            created.add(mime to name)
            if (createsAccepted) named("doc-uri:doc:$name") else null
        }

        resolver = mockk(relaxed = true)
        every { resolver.openOutputStream(any(), "wt") } answers {
            written.getOrPut(firstArg<Uri>().toString()) { ByteArrayOutputStream() }
        }
        context = mockk(relaxed = true)
        every { context.contentResolver } returns resolver
        every { context.filesDir } returns filesDir
    }

    @AfterEach
    fun tearDown() {
        record.delete()
        unmockkAll()
    }

    private fun named(text: String): Uri =
        mockk<Uri>(relaxed = true).also { every { it.toString() } returns text }

    /**
     * A device folder with real structure, keyed by each parent's document id, with
     * `root` as the tree's own. A parent absent from [tree] answers with nothing; a
     * parent mapped to null makes the provider refuse, which is what an incomplete
     * enumeration looks like from here.
     */
    private fun deviceTree(tree: Map<String, List<Child>?>) {
        every { resolver.query(any(), any(), any(), any(), any()) } answers {
            val parent = firstArg<Uri>().toString().removePrefix("children:")
            tree[parent]?.let { cursorOver(it) }
        }
        every { resolver.openInputStream(any()) } answers {
            val docId = firstArg<Uri>().toString().removePrefix("doc-uri:")
            val child = tree.values.filterNotNull().flatten()
                .firstOrNull { "doc:${it.name}" == docId }
            ByteArrayInputStream((child?.contents ?: "").toByteArray())
        }
    }

    private fun cursorOver(children: List<Child>): Cursor {
        var row = -1
        return mockk<Cursor>(relaxed = true) {
            every { moveToNext() } answers { ++row < children.size }
            every { getColumnIndexOrThrow(any()) } answers {
                when (firstArg<String>()) {
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID -> 0
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME -> 1
                    DocumentsContract.Document.COLUMN_MIME_TYPE -> 2
                    else -> 3
                }
            }
            every {
                getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            } returns 4
            every { isNull(any<Int>()) } returns false
            every { getString(0) } answers { "doc:${children[row].name}" }
            every { getString(1) } answers { children[row].name }
            every { getString(2) } answers {
                if (children[row].isDirectory) DocumentsContract.Document.MIME_TYPE_DIR
                else "text/plain"
            }
            every { getLong(3) } answers { children[row].contents.toByteArray().size.toLong() }
            every { getLong(4) } returns DEVICE_TIME
        }
    }

    /** Every (done, total) the sync reported, in order. */
    private val progress = mutableListOf<Pair<Int, Int>>()

    private fun sync() = runBlocking {
        progress.clear()
        lost.clear()
        incomplete.clear()
        val engine = SafSyncEngine(context)
        engine.onWriteBackFailed = { lost += it.name }
        engine.onUploadIncomplete = { dir, count, capped ->
            incomplete += Triple(dir.name, count, capped)
        }
        engine.initialSync(named("tree"), mirror) { done, total ->
            progress.add(done to total)
        }
    }

    /** The names `createDocument` was asked for, whatever their type. */
    private fun createdNames() = created.map { it.second }

    @Test
    fun `a file only the mirror has is put onto the device`() {
        deviceTree(mapOf("root" to listOf(Child("a.txt", contents = "kept"))))
        sync()

        // A save the write-back never delivered: it is in the mirror and the device
        // knows nothing about it.
        File(mirror, "stranded.md").writeText("an hour of writing")
        sync()

        assertEquals(
            listOf("stranded.md"), createdNames(),
            "reopening the folder left the only copy inside the app",
        )
        assertEquals(
            "an hour of writing",
            written["doc-uri:doc:stranded.md"]?.toString(),
            "the document was created empty",
        )
    }

    /**
     * The half that has to stay refused. `reconcileDeletions` is about to remove this
     * very file from the mirror because the record proves the device had it and no
     * longer does; putting it back would make the two passes fight, and the user's
     * deletion would never take.
     */
    @Test
    fun `a file the record names and the device no longer has is not put back`() {
        deviceTree(
            mapOf(
                "root" to listOf(
                    Child("a.txt", contents = "kept"),
                    Child("doomed.txt", contents = "deleted later"),
                )
            )
        )
        sync()
        assertTrue(File(mirror, "doomed.txt").isFile, "the first sync has to bring both down")

        deviceTree(mapOf("root" to listOf(Child("a.txt", contents = "kept"))))
        sync()

        assertEquals(
            emptyList<String>(), createdNames(),
            "a document the user deleted on the device was created again",
        )
        assertFalse(
            File(mirror, "doomed.txt").exists(),
            "the deletion did not take, so the two passes are fighting",
        )
    }

    /**
     * The record is the only thing telling a stranded save from a deletion made on the
     * device, so a record that did not parse has to stop this pass rather than be read
     * as "the record names nothing".
     *
     * Reachable on an ordinary upgrade: records written before the header existed are
     * one path per line, and this build cannot verify them. With no guard, every file
     * the enumeration did not return became a candidate, so a document the user deleted
     * on the device came back on the next open, and phase 3 could not undo it because it
     * declines on the same unreadable record.
     */
    @Test
    fun `a record this build cannot read puts nothing onto the device`() {
        deviceTree(
            mapOf(
                "root" to listOf(
                    Child("a.txt", contents = "kept"),
                    Child("doomed.txt", contents = "deleted on the device later"),
                )
            )
        )
        sync()
        assertTrue(File(mirror, "doomed.txt").isFile, "the first sync has to bring both down")

        // What the build before the header wrote: one path per line, nothing to check a
        // mirror copy against.
        record.writeText("a.txt\ndoomed.txt\n")
        deviceTree(mapOf("root" to listOf(Child("a.txt", contents = "kept"))))
        sync()

        assertEquals(
            emptyList<String>(), createdNames(),
            "a deletion the user made on the device was undone from a record that " +
                "says nothing about any path",
        )
        assertTrue(
            File(mirror, "doomed.txt").isFile,
            "the mirror copy was removed on evidence the record cannot give either",
        )
    }

    /**
     * The dialog driving this is not cancellable, and phase 2's own count has reached its
     * total by the time the upload pass starts, so without a tick of its own the user
     * watches "N of N" for one createDocument plus one provider write per stranded file.
     */
    @Test
    fun `putting stranded files across keeps the progress count moving`() {
        deviceTree(mapOf("root" to listOf(Child("a.txt", contents = "kept"))))
        sync()

        File(mirror, "stranded.md").writeText("an hour of writing")
        sync()

        assertEquals(
            2 to 2, progress.lastOrNull(),
            "the last thing the user was shown was phase 2's own total, so the dialog " +
                "stood still for the whole of the upload pass",
        )
    }

    /** The control: an ordinary reopen puts nothing anywhere. */
    @Test
    fun `a folder whose mirror matches the device is left alone`() {
        deviceTree(mapOf("root" to listOf(Child("a.txt", contents = "kept"))))
        sync()
        sync()

        assertEquals(emptyList<String>(), createdNames())
    }

    /**
     * An enumeration that failed part way cannot say what the device holds, so a mirror
     * file it did not see is not evidence of anything. Creating one there would put a
     * second document beside one that is already on the device, or write into it.
     *
     * A complete sync has to run first, and that is what makes this case say anything.
     * Driven from an empty mirror the pass stops at the record instead: a folder synced
     * for the first time has no record to read, the header check answers null, and the
     * candidate never survives that far. So the enumeration guard could be deleted
     * outright and nothing here would notice. With a record in hand and a stranded file
     * the record does not name, removing it creates `App.kt` on the device.
     */
    @Test
    fun `a partial enumeration puts nothing onto the device`() {
        deviceTree(
            mapOf(
                "root" to listOf(Child("src", isDirectory = true)),
                "doc:src" to listOf(Child("Main.kt", contents = "on the device")),
            )
        )
        sync()
        assertTrue(File(mirror, "src/Main.kt").isFile, "the first sync has to bring it down")

        // A save the write-back never delivered, sitting below the directory the second
        // enumeration cannot read.
        File(mirror, "src/App.kt").writeText("only here")
        deviceTree(
            mapOf(
                "root" to listOf(Child("src", isDirectory = true)),
                // The provider refuses this one, which is what walkTree reports as an
                // incomplete enumeration. The record written by the sync above survives
                // it: reconcileDeletions rewrites the record only after the same guard.
                "doc:src" to null,
            )
        )

        sync()

        assertEquals(
            emptyList<String>(), createdNames(),
            "an enumeration that proved nothing was still acted on",
        )
    }

    /**
     * A directory the device does not have is created, but only because a file below it
     * is going to be put there. A directory created for its own sake would put back every
     * folder the user deleted on the device, empty, on every open.
     */
    @Test
    fun `a stranded file below a new directory brings its directory with it`() {
        deviceTree(mapOf("root" to listOf(Child("a.txt", contents = "kept"))))
        sync()

        File(mirror, "docs").mkdirs()
        File(mirror, "docs/notes.md").writeText("written in the editor")
        File(mirror, "empty").mkdirs()
        sync()

        assertEquals(
            listOf(
                DocumentsContract.Document.MIME_TYPE_DIR to "docs",
                SafSyncEngine.CREATED_FILE_MIME_TYPE to "notes.md",
            ),
            created,
            "the directory holding a stranded file has to arrive before it, and a " +
                "directory holding nothing must not arrive at all",
        )
    }

    /**
     * A provider that refuses one create refuses them all, so the shortfall is one
     * notice for the pass and not one per file. Announcing each of them separately put
     * a three-and-a-half-second toast in front of the user for every stranded file,
     * queued behind a folder dialog they cannot cancel, which on the mirror this pass
     * exists for is the longest part of opening the folder. It is the policy
     * `createChildrenInSaf` already states for the identical situation, and the two
     * bulk paths spelling it differently is what this pins.
     */
    @Test
    fun `stranded files the provider refuses are one notice, not one per file`() {
        deviceTree(mapOf("root" to listOf(Child("a.txt", contents = "kept"))))
        sync()

        repeat(3) { File(mirror, "stranded$it.md").writeText("an hour of writing") }
        createsAccepted = false
        sync()

        assertEquals(
            listOf(Triple(mirror.name, 3, false)), incomplete,
            "the shortfall did not reach the notice channel as one folder-wide fact",
        )
        assertTrue(
            lost.isEmpty(),
            "a toast per stranded file was announced on top of the folder notice: $lost",
        )
    }

    /**
     * The mirror is walked breadth-first and stops at `MAX_UPLOAD_ENTRIES`, and that
     * walk is deterministic: same root, same cap, same `sortedBy { it.name }`. So a
     * stranded file below the cap is not "left for a later open", which is what the log
     * line here used to claim, because no later open ever reaches it. With a stranded
     * file in hand the cap is worth telling the user about, and it goes through the
     * seam the directory path uses for the identical fact.
     */
    @Test
    fun `a mirror walked only as far as the cap says so when it puts a file across`() {
        deviceTree(mapOf("root" to listOf(Child("a.txt", contents = "kept"))))
        sync()

        // Named to sort ahead of the padding, so the walk reaches it before the cap
        // bites. Directories for the padding rather than files: they spend the entry
        // budget without becoming candidates of their own.
        File(mirror, "0stranded.md").writeText("an hour of writing")
        repeat(SafSyncEngine.MAX_UPLOAD_ENTRIES + 5) {
            File(mirror, "d%04d".format(it)).mkdir()
        }
        sync()

        assertEquals(
            listOf("0stranded.md"), createdNames(),
            "the stranded file the walk did reach was not put across",
        )
        assertEquals(
            listOf(Triple(mirror.name, 0, true)), incomplete,
            "the cap reached the log and nothing else, so nothing told the user the " +
                "pass had stopped looking",
        )
    }

    /**
     * The control for the cap, and a decision rather than an oversight. A mirror large
     * enough to hit it is an ordinary repository, the cap is permanent for that folder,
     * and the walk found nothing stranded in what it did examine. Announcing anyway
     * would put the same toast in front of the user on every open of that folder, for a
     * condition they cannot act on and that usually hides nothing. The log keeps the
     * fact for a bug report.
     */
    @Test
    fun `a mirror larger than the cap with nothing stranded says nothing`() {
        deviceTree(mapOf("root" to listOf(Child("a.txt", contents = "kept"))))
        sync()

        repeat(SafSyncEngine.MAX_UPLOAD_ENTRIES + 5) {
            File(mirror, "d%04d".format(it)).mkdir()
        }
        sync()

        assertTrue(
            incomplete.isEmpty(),
            "announced $incomplete on an ordinary open of a large folder",
        )
        assertEquals(emptyList<String>(), createdNames())
    }

    private companion object {
        /** Old enough that no mirror file is ever kept merely for being newer. */
        const val DEVICE_TIME = 1_700_000_000_000L
    }
}
