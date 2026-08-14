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
import java.io.File
import java.nio.file.Files

/**
 * Deletions on the device, on their way into the mirror.
 *
 * Reopening a folder only ever created and overwrote, so a file deleted on the device
 * came straight back out of the stale mirror. Editing that ghost then made it real
 * again: the write-back looks the file up, the tree no longer knows it, and the
 * "MODIFY on unknown file, treating as CREATE" fallback put it back on the device.
 *
 * The pass that fixes it can destroy work if it is wrong, so what it is *not* allowed
 * to remove is pinned here as carefully as what it is. It removes only files a previous
 * complete enumeration recorded and this one did not return.
 */
class SafReconcileDeletionsTest {

    @TempDir
    lateinit var mirror: File

    private lateinit var resolver: ContentResolver
    private lateinit var context: Context
    private val uris = mutableMapOf<String, Uri>()

    /** The record the engine keeps beside the mirror, not inside it. */
    private val record: File get() = File(mirror.path + ".synced")

    /**
     * The paths the record names. Each line also carries the modification time and length
     * the mirror copy had when it was recorded, which is what makes a deletion provable;
     * the tests that care about those assert on the behaviour rather than on the text.
     */
    private fun recordedPaths(): List<String> =
        record.readLines()
            .filterNot { it == SafSyncEngine.RECORD_HEADER }
            .map { it.substringBefore('\t') }
            .sorted()

    /**
     * Writes a record in this build's format, header included.
     *
     * The header is taken from the engine rather than spelled out here: a hand-written
     * record missing it is ignored wholesale, which would make the tests below pass
     * without reaching the behaviour they are about.
     */
    private fun writeRecord(vararg entries: String) {
        record.writeText((listOf(SafSyncEngine.RECORD_HEADER) + entries).joinToString("\n"))
    }

    @BeforeEach
    fun setUp() {
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
        every { DocumentsContract.buildDocumentUriUsingTree(any(), any()) } answers {
            uriFor(secondArg<String>())
        }

        resolver = mockk(relaxed = true)
        context = mockk(relaxed = true)
        every { context.contentResolver } returns resolver
    }

    @AfterEach
    fun tearDown() {
        record.delete()
        unmockkAll()
    }

    private fun uriFor(docId: String): Uri = uris.getOrPut(docId) { mockk(relaxed = true) }

    /**
     * Describes the device folder as holding exactly [files], each a name to contents,
     * all carrying [modified] — old by default, so nothing is kept merely for being newer.
     *
     * ⚠️ One cursor serves every query of a sync, and its row counter is shared. That is
     * sound only while no row here is a directory: the walk then issues exactly one query
     * and never re-enters the counter. Add a directory row and the recursive query gets
     * the same, already-exhausted cursor — `moveToNext()` simply returns false, with no
     * error — so the walk reports a *complete* enumeration of an empty subtree and a test
     * passes having enumerated nothing. [directoryRow] exists for that case: it hands out
     * a separate cursor and lets the caller decide which query receives it.
     *
     * ⚠️ Also: a document opened from an earlier sync raises out of the reverse lookup
     * below, and [copyDocumentToLocal] swallows it — so a redundant copy fails silently
     * and the mirror keeps its old bytes. No assertion in this file can tell "kept the
     * local copy" from "re-copied identical content"; `InitialSyncWiringTest` is where
     * that distinction is observable.
     */
    private fun deviceFolderHolding(
        vararg files: Pair<String, String>,
        oversized: Set<String> = emptySet(),
        modified: Long = DEVICE_TIME
    ) {
        val byDocId = files.associate { (name, contents) -> "doc:$name" to contents }
        var row = -1

        val cursor = mockk<Cursor>(relaxed = true)
        every { cursor.moveToNext() } answers { ++row < files.size }
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
        every { cursor.getString(0) } answers { "doc:${files[row].first}" }
        every { cursor.getString(1) } answers { files[row].first }
        every { cursor.getString(2) } returns "text/plain"
        every { cursor.getLong(3) } answers {
            val (name, contents) = files[row]
            if (name in oversized) {
                SafSyncEngine.MAX_FILE_SIZE + 1
            } else {
                contents.toByteArray().size.toLong()
            }
        }
        every { cursor.getLong(4) } returns modified

        every { resolver.query(any(), any(), any(), any(), any()) } returns cursor
        every { resolver.openInputStream(any()) } answers {
            val wanted = firstArg<Uri>()
            val docId = uris.entries.first { it.value === wanted }.key
            ByteArrayInputStream(byDocId.getValue(docId).toByteArray())
        }
    }

    /**
     * A cursor holding one directory row, for driving the walk one level deeper. Does not
     * install itself on the resolver: the caller decides which query gets it.
     */
    private fun directoryRow(name: String): Cursor {
        var row = -1
        val cursor = mockk<Cursor>(relaxed = true)
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
        every { cursor.getString(0) } returns "doc:$name"
        every { cursor.getString(1) } returns name
        every { cursor.getString(2) } returns DocumentsContract.Document.MIME_TYPE_DIR
        every { cursor.getLong(3) } returns 0L
        every { cursor.getLong(4) } returns DEVICE_TIME
        return cursor
    }

    private fun sync() = runBlocking {
        SafSyncEngine(context).initialSync(mockk<Uri>(relaxed = true), mirror) { _, _ -> }
    }

    private fun mirrored(name: String) = File(mirror, name)

    @Test
    fun `a document deleted on the device is dropped from the mirror`() {
        deviceFolderHolding("a.txt" to "kept", "b.txt" to "doomed")
        sync()
        assertTrue(mirrored("b.txt").isFile, "the first sync has to bring both down")

        deviceFolderHolding("a.txt" to "kept")
        sync()

        assertFalse(mirrored("b.txt").exists(), "a file the device no longer has must go")
        assertEquals("kept", mirrored("a.txt").readText(), "the surviving file must not be touched")
    }

    @Test
    fun `nothing is removed on the first sync of a folder`() {
        // There is no record yet, so nothing has been proven absent -- including
        // whatever a previous install or a crashed sync left behind.
        mirrored("stranger.txt").writeText("no idea where this came from")

        deviceFolderHolding("a.txt" to "kept")
        sync()

        assertTrue(mirrored("stranger.txt").isFile, "an unrecorded file is not a candidate")
        // The half with teeth. Survival on its own proves little here -- an empty record
        // keeps everything however the candidates were chosen. This says where the record
        // comes from: what the device answered with, filtered to what the sync can vouch
        // for. Build it from the mirror's own listing instead and a file the app never
        // wrote becomes a candidate the moment anything else changes.
        assertEquals(
            listOf("a.txt"), recordedPaths(),
            "the record must be built from the enumeration, not from what the mirror holds"
        )
    }

    @Test
    fun `a recorded path that resolves outside the mirror is never deleted`() {
        // The record is built from provider-supplied display names, so a name carrying
        // ".." puts a path in it that points out of the folder it belongs to. Resolving
        // is what catches it, and lexical handling would not: a mirror is routinely a
        // checked-out repository, so a link inside one is attacker-supplied in the
        // ordinary case.
        val outside = File(mirror.parentFile, "not-in-the-mirror.txt").apply {
            writeText("someone else's file")
            setLastModified(DEVICE_TIME)
        }
        deviceFolderHolding("a.txt" to "kept")
        sync()
        // Hand-written: no provider fixture here can produce such a display name, and the
        // values are made to match so that only the confinement check can save the file.
        writeRecord("../${outside.name}\t${outside.lastModified()}\t${outside.length()}")

        deviceFolderHolding("a.txt" to "kept")
        sync()

        val survived = outside.isFile
        outside.delete()
        assertTrue(survived, "a delete followed a recorded path out of the mirror")
    }

    @Test
    fun `a recorded path that is now a directory is left alone`() {
        // `stale.isFile` is the last thing between a corrupted record and a delete()
        // aimed at a directory. File.delete() refuses a non-empty one, but an empty
        // directory would go -- and a record is the wrong evidence for removing a
        // directory at all, which is why the pass never does.
        deviceFolderHolding("a.txt" to "kept")
        sync()
        val nowADirectory = File(mirror, "notes.txt").apply { mkdirs() }
        writeRecord("notes.txt\t${nowADirectory.lastModified()}\t${nowADirectory.length()}")

        deviceFolderHolding("a.txt" to "kept")
        sync()

        assertTrue(nowADirectory.isDirectory, "a delete was aimed at a directory")
    }

    @Test
    fun `a file made in the editor and never written back survives`() {
        // The case that would destroy work: write-back failed, or the process died
        // before it ran, so the mirror holds the only copy and the device has never
        // heard of it. It is not in the record, so it is never examined.
        deviceFolderHolding("a.txt" to "kept")
        sync()

        val local = mirrored("draft.md").apply {
            writeText("an hour of writing")
            // Older than the record, so only the record rule can be what saves it.
            setLastModified(DEVICE_TIME)
        }

        deviceFolderHolding("a.txt" to "kept")
        sync()

        assertEquals("an hour of writing", local.readText(), "local-only work was deleted")
    }

    @Test
    fun `a recorded file edited since the last sync survives its removal on the device`() {
        deviceFolderHolding("a.txt" to "kept", "notes.txt" to "from the device")
        sync()

        val edited = mirrored("notes.txt").apply {
            writeText("edited here after the last sync")
            setLastModified(record.lastModified() + 60_000)
        }

        deviceFolderHolding("a.txt" to "kept")
        sync()

        assertEquals(
            "edited here after the last sync", edited.readText(),
            "the device dropped it, but the newer local edit is the only copy of that work"
        )
    }

    @Test
    fun `a provider that stopped answering entirely removes nothing`() {
        // Named for what it covers. Both halves of the guard are true here -- every query
        // fails, so the walk reports incomplete *and* `documents` comes back empty -- so
        // removing either half alone leaves this green. `!enumerationComplete` is the left
        // operand and short-circuits, so `documents.isEmpty()` is never even evaluated.
        // The two tests that isolate the halves are the ones with teeth; this one only
        // states that a dead provider costs nothing.
        deviceFolderHolding("a.txt" to "kept", "b.txt" to "still here")
        sync()
        val recordedBefore = record.readText()

        every { resolver.query(any(), any(), any(), any(), any()) } throws
            RuntimeException("provider went away")
        sync()

        assertTrue(mirrored("b.txt").isFile, "a dead provider proves nothing is absent")
        assertEquals(
            recordedBefore, record.readText(),
            "the last complete record must survive so the next good sync can still use it"
        )
    }

    @Test
    fun `an enumeration that failed inside a subdirectory removes nothing`() {
        // walkTree logs and carries on when a provider query fails, so a folder that
        // answered for one directory out of twenty would otherwise read as nineteen
        // directories' worth of deletions.
        //
        // The root answers here -- with a directory -- and only the walk into that
        // directory fails. That is what makes `documents` non-empty, and it is the only
        // arrangement that reaches the enumerationComplete guard rather than the
        // empty-enumeration one beside it. Drop `!enumerationComplete ||` from that
        // condition and every recorded file is deleted.
        deviceFolderHolding("a.txt" to "kept", "b.txt" to "still here")
        sync()
        val recordedBefore = record.readText()

        var call = 0
        every { resolver.query(any(), any(), any(), any(), any()) } answers {
            if (call++ == 0) directoryRow("src") else throw RuntimeException("provider went away")
        }
        sync()

        assertTrue(mirrored("a.txt").isFile, "a partial enumeration proves nothing is absent")
        assertTrue(mirrored("b.txt").isFile, "a partial enumeration proves nothing is absent")
        assertEquals(
            recordedBefore, record.readText(),
            "the last complete record must survive so the next good sync can still use it"
        )
    }

    @Test
    fun `a local edit that never reached the device survives a reopen and then its removal`() {
        // The longer of the two shapes that lose work, and the one the mtime rule does
        // not cover. The edit is made, the write-back never lands -- a swallowed
        // provider error, a killed process, a directory past the watch limit -- the
        // folder is reopened once, which is simply how a person gets back into it, and
        // only then does the file go away on the device.
        //
        // Every reopen rewrites the record. Recording a path the sync just decided NOT
        // to overwrite moves the record's timestamp past the local edit, so the mtime
        // rule stops protecting it, and the next sync after the device-side removal
        // deletes the only copy that edit ever had.
        deviceFolderHolding("a.txt" to "kept", "notes.txt" to "from the device")
        sync()

        // Newer than the device copy, and older than the record any later sync writes.
        // Both halves are needed: the first is what makes the engine keep it, the second
        // is what takes it out of the mtime rule's reach.
        val edited = mirrored("notes.txt").apply {
            writeText("an hour of writing")
            setLastModified(DEVICE_TIME + 1_000)
        }

        // Reopened while the device still has the file. The engine keeps the local copy.
        deviceFolderHolding("a.txt" to "kept", "notes.txt" to "from the device")
        sync()
        assertEquals("an hour of writing", edited.readText(), "the reopen overwrote the local edit")

        // Now it goes away on the device.
        deviceFolderHolding("a.txt" to "kept")
        sync()

        assertEquals(
            "an hour of writing", edited.readText(),
            "the reopen re-armed the file and the next sync deleted the only copy of that work"
        )
    }

    @Test
    fun `a reopen that changed nothing leaves the record intact for the sync after it`() {
        // The record is rebuilt every sync, and the obvious way to write the rule that
        // keeps a local edit out of it is "everything enumerated, minus everything the
        // copy was skipped for". On a reopen where nothing changed, the copy is skipped
        // for *every* file -- the mirror is already identical to the device -- so that
        // version writes an empty record, and from the second reopen onward nothing is
        // ever a deletion candidate again. It fails silently and no two-sync test can see
        // it, which is why this one runs three.
        deviceFolderHolding("a.txt" to "kept", "b.txt" to "doomed")
        sync()

        deviceFolderHolding("a.txt" to "kept", "b.txt" to "doomed")
        sync()
        assertEquals(
            listOf("a.txt", "b.txt"), recordedPaths(),
            "a reopen that changed nothing emptied the record"
        )

        deviceFolderHolding("a.txt" to "kept")
        sync()

        assertFalse(
            mirrored("b.txt").exists(),
            "the record stopped covering a file it had already synced, so the removal never landed"
        )
    }

    @Test
    fun `an enumeration that succeeded and returned nothing removes nothing`() {
        // A folder the user emptied and a provider answering for a volume that is no
        // longer mounted are the same two rows of nothing from here. Acting on it costs
        // the entire mirror in the second case; declining costs some stale copies in the
        // first, and those can be deleted in the editor, which propagates outward.
        deviceFolderHolding("a.txt" to "kept", "b.txt" to "also kept")
        sync()
        val recordedBefore = record.readText()

        deviceFolderHolding()
        sync()

        assertTrue(mirrored("a.txt").isFile, "an empty answer is not proof the folder is empty")
        assertTrue(mirrored("b.txt").isFile, "an empty answer is not proof the folder is empty")
        assertEquals(recordedBefore, record.readText(), "nothing was learned, so nothing is recorded")
    }

    @Test
    fun `a file past the limit is never copied and never recorded`() {
        // Half of what the oversize branch has to do, and the half a first sync can show:
        // nothing is fetched, and the path stays out of the record. The other half -- that
        // the branch must `continue` before recording even when a file *is* sitting there
        // -- needs a file to exist at that path first, and is the test below.
        deviceFolderHolding(
            "a.txt" to "kept",
            "video.mp4" to "stands in for something enormous",
            oversized = setOf("video.mp4")
        )
        sync()

        assertFalse(mirrored("video.mp4").exists(), "a file past the limit must not be copied")
        assertEquals(
            listOf("a.txt"), recordedPaths(),
            "a path no sync has ever written must stay out of the record"
        )

        // The editor puts something of the user's own at that path, older than the record.
        val mine = mirrored("video.mp4").apply {
            writeText("my own notes")
            setLastModified(DEVICE_TIME)
        }

        deviceFolderHolding("a.txt" to "kept")
        sync()

        assertEquals(
            "my own notes", mine.readText(),
            "the user's file at a path the sync skipped was deleted"
        )
    }

    @Test
    fun `a recorded file whose content changed without its timestamp is kept`() {
        // The case that says why the record holds identity rather than paths and a single
        // "when was this written" stamp. Writers that preserve mtime -- unzip, cp -p,
        // rsync -t, git checkout -- are named in shouldOverwriteMirror's own reasoning as
        // things that happen in these folders. Under the rule this replaced, such a file
        // was deleted: its timestamp was not newer than the record, so nothing stood
        // between it and the delete. The length moved, and that is enough to know it is
        // no longer the copy that was recorded.
        deviceFolderHolding("a.txt" to "kept", "notes.txt" to "from the device")
        sync()

        val restored = mirrored("notes.txt").apply {
            writeText("restored by a checkout, and the clock did not move")
            setLastModified(DEVICE_TIME)
        }
        // Without this the timestamp half could be what saves the file, and the length
        // half -- the one this test exists for -- would go unpinned.
        assertEquals(
            DEVICE_TIME, restored.lastModified(),
            "the fixture failed to hold the timestamp still, so it is testing the wrong half"
        )

        deviceFolderHolding("a.txt" to "kept")
        sync()

        assertEquals(
            "restored by a checkout, and the clock did not move", restored.readText(),
            "a file whose length changed under an unchanged timestamp is not the copy recorded"
        )
    }

    @Test
    fun `a record written by an older build never triggers a deletion`() {
        // The first format was one path per line, with nothing in it that could show the
        // file is still the copy it names. Reading such a line as a candidate would
        // delete on exactly the evidence this change exists to stop trusting -- and every
        // install that already has a mirror carries one. Two things now refuse it, the
        // missing header and the field count, and either alone is enough.
        deviceFolderHolding("a.txt" to "kept", "b.txt" to "doomed")
        sync()
        record.writeText("a.txt\nb.txt")

        deviceFolderHolding("a.txt" to "kept")
        sync()

        assertTrue(
            mirrored("b.txt").isFile,
            "an unverifiable record line must never be acted on"
        )
        assertEquals(
            listOf("a.txt"), recordedPaths(),
            "the pass should still leave a record this build can use"
        )
    }

    @Test
    fun `a file that grew past the limit keeps its mirror copy when the device drops it`() {
        // The mutation the test above cannot reach: move `recordIdentity` into the
        // oversize branch. On a first sync nothing is at that path, so its `isFile` guard
        // hides the mutation. Here the file was under the limit at sync 1 -- so a real
        // copy exists -- and is over it at sync 2, which is when the branch would vouch
        // for a file it did not fetch. Sync 3 then deletes it.
        deviceFolderHolding("a.txt" to "kept", "report.pdf" to "small enough for now")
        sync()
        assertTrue(mirrored("report.pdf").isFile, "the first sync has to bring it down")

        deviceFolderHolding(
            "a.txt" to "kept",
            "report.pdf" to "small enough for now",
            oversized = setOf("report.pdf")
        )
        sync()
        assertEquals(
            listOf("a.txt"), recordedPaths(),
            "a sync that did not fetch a file cannot vouch for what is at its path"
        )

        deviceFolderHolding("a.txt" to "kept")
        sync()

        assertTrue(
            mirrored("report.pdf").isFile,
            "the mirror copy was deleted on the word of a sync that never read the file"
        )
    }

    @Test
    fun `a recorded file rewritten to the same length is kept`() {
        // The timestamp half of the identity check. Drop it and only length is compared,
        // which misses every in-place edit that does not change a file's size -- a version
        // string, a flag, a fixed-width field, a database page. The sibling test covers
        // the mirror image, a length change under an unchanged timestamp; neither half is
        // pinned without both.
        deviceFolderHolding("a.txt" to "kept", "notes.txt" to "from the device")
        sync()

        val rewritten = mirrored("notes.txt").apply { writeText("edited in place") }
        assertEquals(
            "from the device".length, rewritten.readText().length,
            "the fixture must change the content without changing the length"
        )

        deviceFolderHolding("a.txt" to "kept")
        sync()

        assertEquals(
            "edited in place", rewritten.readText(),
            "a same-length edit is invisible to a length-only check, and was deleted"
        )
    }

    @Test
    fun `a recorded path reached through a link out of the mirror is never deleted`() {
        // What separates resolving the path from checking it for "..". A mirror is
        // routinely a checked-out repository, and a repository can hold a symlinked
        // directory; a record entry naming a file through one carries no ".." at all, so
        // a lexical check waves it through and the delete lands on a real file outside
        // the folder the user opened.
        val outsideDir = File(mirror.parentFile, "outside-dir").apply { mkdirs() }
        val victim = File(outsideDir, "victim.txt").apply {
            writeText("not ours to delete")
            setLastModified(DEVICE_TIME)
        }
        Files.createSymbolicLink(File(mirror, "linked").toPath(), outsideDir.toPath())

        deviceFolderHolding("a.txt" to "kept")
        sync()
        writeRecord("linked/victim.txt\t${victim.lastModified()}\t${victim.length()}")

        deviceFolderHolding("a.txt" to "kept")
        sync()

        val survived = victim.isFile
        outsideDir.deleteRecursively()
        assertTrue(survived, "a delete followed a link out of the mirror")
    }

    @Test
    fun `a copy that failed does not vouch for what was already there`() {
        // copyDocumentToLocal writes beside its destination and renames, deliberately
        // leaving the destination untouched when it fails. So after a failed copy the
        // path still holds whatever was there -- which can be an edit of the user's that
        // no sync ever wrote. Reading identity back from disk then records that edit as
        // ours, and matching is exactly what licenses the delete.
        deviceFolderHolding("a.txt" to "kept", "notes.txt" to "from the device")
        sync()

        val onlyCopy = mirrored("notes.txt").apply {
            writeText("an hour of writing")
            setLastModified(DEVICE_TIME + 1_000)
        }

        // The device's copy moves ahead, so the sync tries to overwrite -- and cannot.
        deviceFolderHolding(
            "a.txt" to "kept",
            "notes.txt" to "changed on the device",
            modified = DEVICE_TIME + 5_000
        )
        every { resolver.openInputStream(any()) } returns null
        sync()

        assertEquals(
            "an hour of writing", onlyCopy.readText(),
            "a failed copy must leave the destination alone"
        )

        deviceFolderHolding("a.txt" to "kept")
        sync()

        assertEquals(
            "an hour of writing", onlyCopy.readText(),
            "the failed copy vouched for the user's edit, and the next sync deleted it"
        )
    }

    @Test
    fun `a record with no header is ignored even when its lines parse`() {
        // The header is the only thing that says which format follows, and until this
        // test nothing would have noticed its removal: every record this build writes
        // carries the header at line 0, where the field-count check skips it anyway, and
        // the only header-less fixture in this file has one-field lines that the same
        // check rejects. So the guard whose entire purpose is to make a future format
        // fail closed was the one guard here with no anchor of its own -- deletable as
        // dead code, with a green suite.
        deviceFolderHolding("a.txt" to "kept", "b.txt" to "doomed")
        sync()
        val doomed = mirrored("b.txt")
        // Exactly what this build would have written for it, minus the header. Every
        // other check passes: three fields, a real path, an identity that matches.
        record.writeText("b.txt\t${doomed.lastModified()}\t${doomed.length()}")

        deviceFolderHolding("a.txt" to "kept")
        sync()

        assertTrue(doomed.isFile, "a record this build cannot identify was acted on anyway")
    }

    @Test
    fun `the record is kept beside the mirror, not inside it`() {
        // Anything inside the mirror is inside the folder VS Code opens: it shows up in
        // the explorer, in search, and in the write-back's own event stream.
        deviceFolderHolding("a.txt" to "kept")
        sync()

        assertTrue(record.isFile, "the pass needs somewhere to record what it found")
        assertEquals(
            listOf("a.txt"), mirror.list()?.sorted(),
            "the mirror must hold the workspace and nothing else"
        )
    }

    private companion object {
        /** Old enough that no mirror file is ever kept merely for being newer. */
        const val DEVICE_TIME = 1_700_000_000_000L
    }
}
