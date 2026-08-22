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
 * inotify reports a rename as MOVED_FROM on the old name and MOVED_TO on the new one, and
 * `FileObserver` cannot pair them by cookie because Android does not expose it. Acting on
 * the MOVED_FROM as a plain delete is `DocumentsContract.deleteDocument` taking the whole
 * subtree on the device, and the MOVED_TO only puts back what the *mirror* held:
 * `createChildrenInSaf` stops at `MAX_UPLOAD_ENTRIES`, and the mirror never held the
 * `.git` or `node_modules` that `SKIP_DIRECTORIES` kept out of it. Renaming a folder of
 * 3000 files left about 1000 of them nowhere but the mirror, which
 * `reclaimRevokedMirrors` removes as soon as the folder's permission lapses.
 *
 * Not deleting cost the opposite: the device kept a second copy under the old name, and
 * reopening the folder copied it back down, so the old name reappeared in the editor
 * beside the new one and one more copy accumulated per rename. What is pinned here is the
 * third option, renaming the device's own document, which moves the subtree in place and
 * so needs neither the delete nor the re-upload.
 *
 * Driven through `handleMirrorEvent` rather than through an observer: constructing a
 * `FileObserver` runs into a stub that a plain JVM test cannot satisfy. That is also why
 * the new name is absent from disk while the event is delivered, `watchTree` builds an
 * observer for a directory that exists, and why the fallback case below creates it
 * between the event and the write-back, which is the thread that would see it in
 * production anyway.
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
        every { DocumentsContract.getDocumentId(any()) } returns "root"
        every { DocumentsContract.buildChildDocumentsUriUsingTree(any(), any()) } returns mockk(relaxed = true)
        every { DocumentsContract.buildDocumentUriUsingTree(any(), any()) } returns mockk(relaxed = true)
        every { DocumentsContract.deleteDocument(any(), any()) } returns true
        every { DocumentsContract.renameDocument(any(), any(), any()) } returns mockk(relaxed = true)
        every { DocumentsContract.createDocument(any(), any(), any(), any()) } returns mockk(relaxed = true)
        every { DocumentsContract.moveDocument(any(), any(), any(), any()) } returns mockk(relaxed = true)

        resolver = mockk(relaxed = true)
        val context = mockk<Context>(relaxed = true)
        every { context.contentResolver } returns resolver
        engine = SafSyncEngine(context)
        treeUri = mockk(relaxed = true)
    }

    /**
     * The device folder answers for a child per name in [names], so the lookup that turns
     * a relative path into a document finds something to point an operation at. Without
     * it every case here would pass for the wrong reason: an unresolved path yields a
     * null document URI and the operation is skipped anyway.
     *
     * Which names are listed is load-bearing in both directions. A name that is absent is
     * what a name the device does not have looks like, which is the state the new half of
     * a rename is normally in, and a case that means to test what happens when the new
     * name *is* already there has to say so, or it passes because the *old* name failed
     * to resolve instead.
     */
    private fun deviceFolderHolding(vararg names: String) {
        val cursor = mockk<Cursor>(relaxed = true)
        var row = -1
        every { cursor.moveToNext() } answers { ++row < names.size }
        every { cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID) } returns 0
        every { cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME) } returns 1
        every { cursor.getString(0) } answers { "doc:${names[row]}" }
        every { cursor.getString(1) } answers { names[row] }
        every { resolver.query(any(), any(), any(), any(), any()) } answers {
            row = -1
            cursor
        }
    }

    /**
     * A device folder with real structure, for the cases that turn on *where* a name is.
     *
     * [deviceFolderHolding] answers the same child list under every parent, which is fine
     * while a case only cares whether a name exists somewhere. It is not fine for a move:
     * claiming the pair requires the destination path to resolve to nothing, and a flat
     * answer makes `lib/util` resolve the moment both `lib` and `util` exist anywhere. A
     * case written against the flat mock therefore passes with no move attempted at all.
     *
     * [tree] maps a parent's document id to the names directly under it, with `root` as
     * the tree's own document id.
     */
    private fun deviceTree(tree: Map<String, List<String>>) =
        deviceTree { parent -> tree[parent] ?: emptyList() }

    /**
     * The same, for a case whose answers change while one event is being handled.
     *
     * A provider that stops answering for a directory it answered for a moment ago is
     * the whole of what a transient failure looks like from here, and the fixed map
     * above cannot express it. [children] is asked per query with the parent's document
     * id, so a case can decide from what has already been asked.
     */
    private fun deviceTree(children: (String) -> List<String>) {
        every { DocumentsContract.buildChildDocumentsUriUsingTree(any(), any()) } answers {
            val parent = secondArg<String>()
            mockk<Uri>(relaxed = true).also { every { it.toString() } returns "children:$parent" }
        }
        // A document URI has to carry which document it is, and getDocumentId has to
        // agree with it. The flat setUp answers "root" for every URI, which made the
        // create-fallback case look at the wrong parent: it asked whether `lib` already
        // held `util`, was answered from `root`, found the old copy still standing there
        // and skipped the create it was supposed to perform.
        every { DocumentsContract.buildDocumentUriUsingTree(any(), any()) } answers {
            val docId = secondArg<String>()
            mockk<Uri>(relaxed = true).also { every { it.toString() } returns "doc-uri:$docId" }
        }
        every { DocumentsContract.getDocumentId(any()) } answers {
            firstArg<Uri>().toString().removePrefix("doc-uri:")
        }
        every { resolver.query(any(), any(), any(), any(), any()) } answers {
            val parent = firstArg<Uri>().toString().removePrefix("children:")
            val names = children(parent)
            val cursor = mockk<Cursor>(relaxed = true)
            var row = -1
            every { cursor.moveToNext() } answers { ++row < names.size }
            every {
                cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            } returns 0
            every {
                cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            } returns 1
            every { cursor.getString(0) } answers { "doc:${names[row]}" }
            every { cursor.getString(1) } answers { names[row] }
            cursor
        }
    }

    /** Delivers one event, without letting the write-back queue run. */
    private fun observe(event: Int, entry: String) {
        engine.handleMirrorEvent(event, File(mirror, entry), mirror, treeUri)
    }

    /** Runs the write-back thread's loop over whatever the events queued. */
    private fun drain() {
        engine.runWriteBackLoop { false }
    }

    /** Delivers one event and lets the write-back thread's loop drain what it queued. */
    private fun deliver(event: Int, entry: String) {
        observe(event, entry)
        drain()
    }

    @Test
    fun `a renamed directory moves the device document rather than copying it`() {
        deviceFolderHolding("util")

        observe(FileObserver.MOVED_FROM or isDirFlag, "util")
        deliver(FileObserver.MOVED_TO or isDirFlag, "helpers")

        verify(exactly = 1) { DocumentsContract.renameDocument(any(), any(), "helpers") }
        // The two costs the rename removes: the subtree the mirror could not re-send, and
        // the second copy left standing under the old name.
        verify(exactly = 0) { DocumentsContract.deleteDocument(any(), any()) }
        verify(exactly = 0) { DocumentsContract.createDocument(any(), any(), any(), any()) }
    }

    /**
     * The case the same-parent rule used to refuse. Dragging `util` into `lib/` is a move,
     * not a rename, and leaving it unclaimed meant the old copy stayed on the device and
     * came back down beside the new one on the next reopen.
     *
     * The name is unchanged here, so no rename should follow the move: asking a provider
     * to rename a document to the name it already has is a request that can be answered
     * with `util (1)`.
     */
    @Test
    fun `a directory dragged into another folder is moved on the device`() {
        deviceTree(mapOf("root" to listOf("util", "lib"), "doc:lib" to emptyList()))
        File(mirror, "lib").mkdirs()

        observe(FileObserver.MOVED_FROM or isDirFlag, "util")
        deliver(FileObserver.MOVED_TO or isDirFlag, "lib/util")

        verify(exactly = 1) { DocumentsContract.moveDocument(any(), any(), any(), any()) }
        verify(exactly = 0) { DocumentsContract.renameDocument(any(), any(), any()) }
        verify(exactly = 0) { DocumentsContract.deleteDocument(any(), any()) }
        verify(exactly = 0) { DocumentsContract.createDocument(any(), any(), any(), any()) }
    }

    /** `mv util lib/helpers`: the move relocates it, the rename gives it the new name. */
    @Test
    fun `a move that also renames does both, in that order`() {
        deviceTree(mapOf("root" to listOf("util", "lib"), "doc:lib" to emptyList()))
        File(mirror, "lib").mkdirs()

        observe(FileObserver.MOVED_FROM or isDirFlag, "util")
        deliver(FileObserver.MOVED_TO or isDirFlag, "lib/helpers")

        verify(exactly = 1) { DocumentsContract.moveDocument(any(), any(), any(), any()) }
        verify(exactly = 1) { DocumentsContract.renameDocument(any(), any(), "helpers") }
        verify(exactly = 0) { DocumentsContract.deleteDocument(any(), any()) }
    }

    /**
     * `mkdir lib && mv util lib/` in one write-back tick. At event time the
     * destination folder's document does not exist yet, its own create job is
     * still ahead of this one in the queue, so the parent URI the move needs
     * resolves to nothing. The job used to carry that null into processing, where
     * the move was skipped entirely while the unchanged name still reported the
     * rename done: the device kept `util` under the old parent forever, `lib`
     * landed empty, and the cache for the subtree had already been forgotten. By
     * processing time the create has run, so the parent is resolvable then.
     */
    @Test
    fun `a move whose destination folder has not reached the device yet waits for it`() {
        deviceTree(mapOf("root" to listOf("util")))
        File(mirror, "lib").mkdirs()

        observe(FileObserver.MOVED_FROM or isDirFlag, "util")
        observe(FileObserver.MOVED_TO or isDirFlag, "lib/util")
        // The destination folder arrives between the event and the write-back,
        // which is the order the queue processes them in.
        deviceTree(mapOf("root" to listOf("util", "lib"), "doc:lib" to emptyList()))
        drain()

        verify(exactly = 1) { DocumentsContract.moveDocument(any(), any(), any(), any()) }
        verify(exactly = 0) { DocumentsContract.renameDocument(any(), any(), any()) }
        verify(exactly = 0) { DocumentsContract.createDocument(any(), any(), any(), any()) }
    }

    /**
     * The same shape with a new name, which is the half that landed worse: the
     * move never ran, and the rename then fired on the document under its *old*
     * parent, putting `helpers` at the root of the device folder.
     */
    @Test
    fun `a move-and-rename whose destination is late renames in the destination, not the origin`() {
        deviceTree(mapOf("root" to listOf("util")))
        File(mirror, "lib").mkdirs()

        observe(FileObserver.MOVED_FROM or isDirFlag, "util")
        observe(FileObserver.MOVED_TO or isDirFlag, "lib/helpers")
        deviceTree(mapOf("root" to listOf("util", "lib"), "doc:lib" to emptyList()))
        drain()

        verify(exactly = 1) { DocumentsContract.moveDocument(any(), any(), any(), any()) }
        verify(exactly = 1) { DocumentsContract.renameDocument(any(), any(), "helpers") }
    }

    /**
     * `rm -rf lib/util` then `mv util lib/util`: the ordinary replace idiom. The
     * delete job removed the device document but left its cache entry behind, and
     * the claim gate asked the cache: the dead document id answered for the new
     * name, the pair went unclaimed, and the device kept the old copy standing
     * beside a rebuilt `lib/util`, the duplicate the pairing exists to prevent,
     * resurfacing through the back door of the cache that was never told the
     * document died.
     */
    @Test
    fun `a document deleted in the editor does not block the rename that replaces it`() {
        deviceTree(mapOf("root" to listOf("util", "lib"), "doc:lib" to listOf("util")))

        deliver(FileObserver.DELETE or isDirFlag, "lib/util")
        verify(exactly = 1) { DocumentsContract.deleteDocument(any(), any()) }
        // The deletion the job performed, as the device would show it.
        deviceTree(mapOf("root" to listOf("util", "lib"), "doc:lib" to emptyList()))

        observe(FileObserver.MOVED_FROM or isDirFlag, "util")
        deliver(FileObserver.MOVED_TO or isDirFlag, "lib/util")

        verify(exactly = 1) { DocumentsContract.moveDocument(any(), any(), any(), any()) }
        verify(exactly = 0) { DocumentsContract.createDocument(any(), any(), any(), any()) }
    }

    /**
     * The same staleness one level down and without a rename anywhere: a file
     * deleted in the editor and then recreated by the next save. The delete job
     * removed the document and left the cache entry; the save then resolved the
     * dead id and wrote into it, so the file quietly existed nowhere, the
     * provider had dropped the document, and the recreate nobody performed was
     * reported as a successful write-back.
     *
     * The ordering pinned here is the observed one, the save arriving after the
     * delete job has run. A delete and a save enqueued back to back, before the
     * thread touches either, still resolves the save against the document the
     * delete is about to remove; that write lands nowhere, and what bounds it is
     * the upload journal, which the failed write leaves behind so the next open
     * keeps the mirror and repairs the device copy.
     */
    @Test
    fun `a file recreated after its delete is created, not written into the dead document`() {
        deviceTree(mapOf("root" to listOf("notes.txt")))

        deliver(FileObserver.DELETE, "notes.txt")
        deviceTree(mapOf("root" to emptyList()))
        File(mirror, "notes.txt").writeText("the editor saved it again")

        deliver(FileObserver.MODIFY, "notes.txt")

        verify(exactly = 1) {
            DocumentsContract.createDocument(any(), any(), any(), "notes.txt")
        }
    }

    /**
     * The cache can also be stale for a document this app never deleted: the
     * device side is shared, and another app removing a file leaves the entry
     * behind just the same. The claim decision therefore asks the provider, not
     * the cache, for whether the new name is really taken.
     */
    @Test
    fun `a stale cache entry does not answer for a document the device no longer holds`() {
        deviceTree(mapOf("root" to listOf("util", "lib"), "doc:lib" to listOf("util")))
        // Seed the cache with a live resolution for the subtree, the way any event
        // under it earlier in the session would have; then the folder it names is
        // emptied from the device without any job of ours, as another app would.
        engine.handleMirrorEvent(
            FileObserver.MODIFY,
            File(mirror, "lib/util/README.md").apply { parentFile!!.mkdirs(); writeText("x") },
            mirror, treeUri
        )
        File(mirror, "lib/util/README.md").delete()
        File(mirror, "lib/util").delete()
        deviceTree(mapOf("root" to listOf("util", "lib"), "doc:lib" to emptyList()))

        observe(FileObserver.MOVED_FROM or isDirFlag, "util")
        deliver(FileObserver.MOVED_TO or isDirFlag, "lib/util")

        verify(exactly = 1) { DocumentsContract.moveDocument(any(), any(), any(), any()) }
        verify(exactly = 0) { DocumentsContract.createDocument(any(), any(), any(), any()) }
    }

    /**
     * The late-parent resolution reads the cache, and the cache belongs to a
     * folder. A write-back drain can outlive its folder: the next one's sync has
     * cleared and refilled the cache for itself by the time the drain gets to a
     * job whose destination parent was unresolved at event time, and resolving
     * that job's `lib` against the new folder's cache hands `moveDocument` a
     * parent from the wrong tree. The resolution declines instead, which costs
     * the documented stale-copy fallback, exactly what the pre-existing
     * behaviour was for a destination that never did resolve.
     */
    @Test
    fun `a rename drain from a folder the cache no longer speaks for declines the late parent`() {
        deviceTree(mapOf("root" to listOf("util")))
        File(mirror, "lib").mkdirs()
        val otherTree = mockk<Uri>(relaxed = true)

        observe(FileObserver.MOVED_FROM or isDirFlag, "util")
        observe(FileObserver.MOVED_TO or isDirFlag, "lib/util")
        // An event for a different folder repoints the cache before the drain
        // runs, which is what a folder switch mid-drain looks like.
        engine.handleMirrorEvent(
            FileObserver.MODIFY,
            File(mirror, "x.txt").apply { writeText("x") },
            mirror, otherTree
        )
        deviceTree(mapOf("root" to listOf("util", "lib"), "doc:lib" to emptyList()))
        drain()

        verify(exactly = 0) { DocumentsContract.moveDocument(any(), any(), any(), any()) }
    }

    /**
     * `moveDocument` sits behind `FLAG_SUPPORTS_MOVE`, which a provider may withhold while
     * still offering rename. The fallback has to be what the code did before there was a
     * move at all: build the new name from the mirror and leave the old copy alone, rather
     * than deleting a subtree the mirror cannot fully replace.
     */
    @Test
    fun `a provider that will not move still gets the new name`() {
        deviceTree(mapOf("root" to listOf("util", "lib"), "doc:lib" to emptyList()))
        File(mirror, "lib").mkdirs()
        every { DocumentsContract.moveDocument(any(), any(), any(), any()) } returns null

        observe(FileObserver.MOVED_FROM or isDirFlag, "util")
        observe(FileObserver.MOVED_TO or isDirFlag, "lib/util")
        File(mirror, "lib/util").mkdirs()
        drain()

        verify(exactly = 1) { DocumentsContract.createDocument(any(), any(), any(), "util") }
        verify(exactly = 0) { DocumentsContract.deleteDocument(any(), any()) }
    }

    /**
     * A device tree that loses `src` the moment it has answered for what is inside it.
     *
     * That is the shape of the transient failure this pair of cases turns on: the pair's
     * old path resolves, and the query for its old *parent*, which comes a moment later,
     * does not. [stillHasSrc] lets the control run the identical sequence with the
     * provider answering throughout.
     */
    private fun providerLosingSrc(stillHasSrc: Boolean) {
        var visible = true
        deviceTree { parent ->
            when (parent) {
                "root" -> if (visible) listOf("src") else emptyList()
                "doc:src" -> listOf("util").also { if (!stillHasSrc) visible = false }
                else -> emptyList()
            }
        }
    }

    /**
     * `mv src/util .` where the provider will not say what `src` is any more.
     *
     * Both reasons for having no source parent used to arrive at the same arm of the
     * write-back: a pair that stayed inside one directory, which genuinely has nothing to
     * move, and a pair that crossed directories whose old parent could not be resolved.
     * That arm hands the write-back the document under its OLD name with nothing to move
     * it, and on the common shape of a move the name does not change either, so no
     * provider call was made at all and the move was still recorded as done. The device
     * kept `src/util`, never received `util`, the mirror's copy was left mirror-only, and
     * the create that would have delivered it was skipped because the job claimed success.
     */
    @Test
    fun `a move whose old parent stops resolving is created at its new path`() {
        providerLosingSrc(stillHasSrc = false)

        observe(FileObserver.MOVED_FROM or isDirFlag, "src/util")
        observe(FileObserver.MOVED_TO or isDirFlag, "util")
        File(mirror, "util").mkdirs()
        drain()

        verify(exactly = 1) { DocumentsContract.createDocument(any(), any(), any(), "util") }
        verify(exactly = 0) { DocumentsContract.renameDocument(any(), any(), any()) }
        verify(exactly = 0) { DocumentsContract.deleteDocument(any(), any()) }
    }

    /**
     * The control, and the reason the case above cannot be satisfied by declining every
     * move. The identical sequence against a provider that keeps answering still moves
     * the document, which is the operation that carries the subtree the mirror does not
     * hold.
     */
    @Test
    fun `the same move is still performed when the old parent resolves`() {
        providerLosingSrc(stillHasSrc = true)

        observe(FileObserver.MOVED_FROM or isDirFlag, "src/util")
        observe(FileObserver.MOVED_TO or isDirFlag, "util")
        File(mirror, "util").mkdirs()
        drain()

        verify(exactly = 1) { DocumentsContract.moveDocument(any(), any(), any(), any()) }
        verify(exactly = 0) { DocumentsContract.createDocument(any(), any(), any(), any()) }
    }

    /**
     * A rename staying inside one directory must not start reaching for `moveDocument`.
     * It is a second provider call behind a second flag, and a provider offering rename
     * but not move would go from working to falling back.
     */
    @Test
    fun `a rename within one directory does not call move`() {
        deviceFolderHolding("util")

        observe(FileObserver.MOVED_FROM or isDirFlag, "util")
        deliver(FileObserver.MOVED_TO or isDirFlag, "helpers")

        verify(exactly = 1) { DocumentsContract.renameDocument(any(), any(), "helpers") }
        verify(exactly = 0) { DocumentsContract.moveDocument(any(), any(), any(), any()) }
    }

    @Test
    fun `a directory moved away with nothing claiming it keeps its contents on the device`() {
        deviceFolderHolding("util")

        deliver(FileObserver.MOVED_FROM or isDirFlag, "util")

        verify(exactly = 0) { DocumentsContract.deleteDocument(any(), any()) }
        verify(exactly = 0) { DocumentsContract.renameDocument(any(), any(), any()) }
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

    @Test
    fun `a file that moved away is not offered as a rename source`() {
        // A file's move is already propagated as a delete, so it is not waiting for
        // anything, and a directory arriving just after one must not pick it up and
        // rename the file's document onto a directory's name.
        deviceFolderHolding("notes.txt")

        observe(FileObserver.MOVED_FROM, "notes.txt")
        deliver(FileObserver.MOVED_TO or isDirFlag, "helpers")

        verify(exactly = 0) { DocumentsContract.renameDocument(any(), any(), any()) }
    }

    @Test
    fun `two directories leaving at once are not paired with the one that arrives`() {
        // Arrival order stops identifying anything once two moves interleave, and
        // guessing would put each subtree under the other's name. Declining costs the
        // old behaviour, which is a stale copy and not a loss.
        deviceFolderHolding("util")

        observe(FileObserver.MOVED_FROM or isDirFlag, "util")
        observe(FileObserver.MOVED_FROM or isDirFlag, "widgets")
        deliver(FileObserver.MOVED_TO or isDirFlag, "helpers")

        verify(exactly = 0) { DocumentsContract.renameDocument(any(), any(), any()) }
        verify(exactly = 0) { DocumentsContract.deleteDocument(any(), any()) }
    }

    @Test
    fun `a name the device already holds is not renamed onto`() {
        // `renameDocument` inherits `createDocument`'s trap: handed a name the folder
        // already has, a provider invents "helpers (1)" and the user is looking at two
        // directories again, one of them named nonsense.
        //
        // Both names are on the device on purpose. With only "helpers" there the refusal
        // could not be told from the old name failing to resolve, and dropping the guard
        // would leave this passing.
        deviceFolderHolding("util", "helpers")

        observe(FileObserver.MOVED_FROM or isDirFlag, "util")
        deliver(FileObserver.MOVED_TO or isDirFlag, "helpers")

        verify(exactly = 0) { DocumentsContract.renameDocument(any(), any(), any()) }
    }

    @Test
    fun `a provider that will not rename still gets the new name`() {
        // Without the fallback, a provider lacking FLAG_SUPPORTS_RENAME would end up with
        // neither name carrying the change: the old one untouched because the delete is
        // declined, and the new one never created because the rename was expected to
        // produce it.
        every { DocumentsContract.renameDocument(any(), any(), any()) } returns null
        deviceFolderHolding("util")

        observe(FileObserver.MOVED_FROM or isDirFlag, "util")
        observe(FileObserver.MOVED_TO or isDirFlag, "helpers")
        File(mirror, "helpers").mkdirs()
        drain()

        verify(exactly = 1) {
            DocumentsContract.createDocument(
                any(), any(), DocumentsContract.Document.MIME_TYPE_DIR, "helpers"
            )
        }
        verify(exactly = 0) { DocumentsContract.deleteDocument(any(), any()) }
    }
}
