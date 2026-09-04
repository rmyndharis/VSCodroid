package com.vscodroid.storage

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException

/**
 * `ShouldOverwriteMirrorTest` pins the decision. This pins the *call to it*: the guard
 * could be deleted from [SafSyncEngine.initialSync] with all seven of those methods
 * green, which is how the fix for #84 shipped without anything holding it in place.
 *
 * These run `initialSync` itself. No production seam was added for them: the engine
 * already takes a `Context`, and its far end is `ContentResolver.openInputStream`:
 * called means it copied, not called means it kept what was there. The mirror is a real
 * directory, so `exists`, `lastModified` and `length` are answered by the filesystem.
 *
 * Fixture values are chosen so the broken path cannot produce them. A local edit here is
 * both *newer* and a *different length* than the source, the combination the first
 * version of the guard got wrong, because it compared sizes before timestamps.
 */
class InitialSyncWiringTest {

    @TempDir
    lateinit var mirror: File

    /**
     * The sync now records what it copied, in a file beside the mirror rather than inside
     * it, so it lands outside the directory `@TempDir` cleans up, and each test method
     * gets a fresh directory and therefore a fresh name. Removed here so the runs do not
     * accumulate one small file apiece in the system temp root.
     *
     * One method rather than two: JUnit 5 orders same-class lifecycle methods
     * deterministically but not by declaration, so a second @AfterEach would leave the
     * order between them written nowhere. Nothing here depends on it today -- the
     * delete touches only real java.io -- but the next line added might.
     *
     * The unmockk is not optional: mockkStatic replaces the class process-wide, and
     * this suite runs in one JVM. See BridgeTokenUniformityTest.tearDown.
     */
    @AfterEach
    fun removeSyncRecord() {
        File(mirror.path + SafSyncEngine.SYNCED_RECORD_SUFFIX).delete()
        unmockkAll()
    }

    private lateinit var resolver: ContentResolver
    private lateinit var context: Context
    private var openedDocuments = mutableListOf<String>()
    private var writtenDocuments = mutableListOf<String>()

    /** One file in the device folder, described to the engine through a fake cursor. */
    private fun deviceFolderHolding(name: String, contents: String, modified: Long) {
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
        every { cursor.getString(2) } returns "text/plain"
        every { cursor.getLong(3) } returns contents.toByteArray().size.toLong()
        every { cursor.getLong(4) } returns modified

        every { resolver.query(any(), any(), any(), any(), any()) } returns cursor
        every { resolver.openInputStream(any()) } answers {
            openedDocuments.add(name)
            ByteArrayInputStream(contents.toByteArray())
        }
        every { resolver.openOutputStream(any(), "wt") } answers {
            writtenDocuments.add(name)
            java.io.ByteArrayOutputStream()
        }
    }

    private fun mirrorHolding(name: String, contents: String, modified: Long): File =
        File(mirror, name).apply {
            writeText(contents)
            setLastModified(modified)
        }

    private fun sync() = runBlocking {
        SafSyncEngine(context).initialSync(mockk<Uri>(relaxed = true), mirror) { _, _ -> }
    }

    @BeforeEach
    fun setUp() {
        openedDocuments = mutableListOf()
        writtenDocuments = mutableListOf()

        mockkStatic(android.util.Log::class)
        every { android.util.Log.i(any(), any()) } returns 0
        every { android.util.Log.d(any(), any()) } returns 0
        every { android.util.Log.w(any(), any<String>()) } returns 0
        every { android.util.Log.e(any(), any()) } returns 0

        mockkStatic(DocumentsContract::class)
        every { DocumentsContract.getTreeDocumentId(any()) } returns "root"
        every { DocumentsContract.buildChildDocumentsUriUsingTree(any(), any()) } returns mockk(relaxed = true)
        every { DocumentsContract.buildDocumentUriUsingTree(any(), any()) } returns mockk(relaxed = true)

        resolver = mockk(relaxed = true)
        context = mockk(relaxed = true)
        every { context.contentResolver } returns resolver
    }

    /**
     * The upload journal, which is a plain list of absolute paths in `filesDir`.
     *
     * Seeded rather than produced, because producing one means killing the
     * process mid-write. `context.filesDir` is stubbed per case rather than in
     * `setUp` so the cases that do not care keep the relaxed mock they have
     * always had.
     */
    private fun journalHolding(path: String, into: File) {
        every { context.filesDir } returns into
        File(into, SafSyncEngine.UPLOADS_IN_FLIGHT_FILE).writeText(path + "\n")
    }

    private fun journalEntries(into: File): List<String> =
        File(into, SafSyncEngine.UPLOADS_IN_FLIGHT_FILE)
            .takeIf { it.isFile }?.readLines()?.filter { it.isNotBlank() } ?: emptyList()

    private fun setAsideCopies(): List<String> =
        mirror.list().orEmpty().filter { SafSyncEngine.DEVICE_COPY_SUFFIX in it }

    /**
     * What the journal actually licenses, which is narrower than it was read as.
     *
     * A line says a write started at this path and did not finish. The repair
     * took that to mean the device document is this app's own truncated copy and
     * pushed the mirror over it with a truncating open. The two are only the same
     * thing when nothing touched the document afterwards, and the window is as
     * long as the app was away.
     *
     * These three are the whole decision: the bytes say which case it is, a copy
     * is kept only when they say the document came from somewhere else, and a
     * document that cannot be read holds the repair back rather than overwriting
     * it. Each asserts on the journal too, because consuming the record is what
     * makes a held-back repair permanent.
     */
    @Test
    fun `a device copy that is this app's own truncated write is replaced without a copy`(
        @TempDir files: File,
    ) {
        val local = mirrorHolding("notes.txt", "the whole file, saved", 1_700_000_060_000)
        // A strict prefix: exactly what "wt" plus a copy cut short leaves behind.
        deviceFolderHolding("notes.txt", "the whole file", 1_700_000_090_000)
        journalHolding(local.absolutePath, files)

        sync()

        assertTrue(
            writtenDocuments.contains("notes.txt"),
            "the repair is the re-upload, so the mirror still has to reach the device",
        )
        assertEquals(
            emptyList<String>(), setAsideCopies(),
            "these are this app's own half-written bytes, and a copy of them is not a " +
                "rescue: phase 2b would put it in the user's folder and nothing removes it",
        )
        assertEquals(
            emptyList<String>(), journalEntries(files),
            "the record was acted on, so it has to be consumed or every later open " +
                "repeats the repair",
        )
    }

    @Test
    fun `a device edit made while the app was away is kept before the mirror replaces it`(
        @TempDir files: File,
    ) {
        val local = mirrorHolding("notes.txt", "the whole file, saved", 1_700_000_060_000)
        // Not a prefix, and that is the only thing separating it from the case
        // above: something other than the interrupted write produced these bytes.
        deviceFolderHolding("notes.txt", "typed on the phone instead", 1_700_000_090_000)
        journalHolding(local.absolutePath, files)

        sync()

        assertEquals(
            1, setAsideCopies().size,
            "the device bytes were overwritten with nothing kept, which is the loss this " +
                "guard exists to prevent. Found: ${mirror.list().orEmpty().toList()}",
        )
    }

    @Test
    fun `a device copy that cannot be read holds the repair back`(
        @TempDir files: File,
    ) {
        val local = mirrorHolding("notes.txt", "the whole file, saved", 1_700_000_060_000)
        deviceFolderHolding("notes.txt", "typed on the phone instead", 1_700_000_090_000)
        journalHolding(local.absolutePath, files)
        // After the fixture, so it replaces the reader the fixture installed.
        every { resolver.openInputStream(any()) } throws IOException("the provider refused")

        sync()

        assertEquals(
            emptyList<String>(), writtenDocuments,
            "the document could not be read, so what it holds is unknown, and writing " +
                "over an unknown is the loss itself",
        )
        assertEquals(
            listOf(local.absolutePath), journalEntries(files),
            "the record has to survive a held-back repair, or the next open has no reason " +
                "to try again and the mirror keeps the only complete copy for ever",
        )
    }

    @Test
    fun `an unsaved local edit survives reopening the folder`() {
        // Newer AND a different length: the pairing the size-first rule overwrote.
        val local = mirrorHolding("notes.txt", "edited in the editor!", 1_700_000_060_000)
        deviceFolderHolding("notes.txt", "from the device", 1_700_000_000_000)

        sync()

        assertEquals("edited in the editor!", local.readText(), "the local edit was overwritten")
        assertEquals(emptyList<String>(), openedDocuments, "the engine read the document it should have left alone")
    }

    /**
     * A provider that reports no modification time, which is legal: COLUMN_LAST_MODIFIED
     * is optional and MTP, some USB-OTG and some network providers omit it.
     *
     * With no clock to compare, the engine used to copy unconditionally, so every reopen
     * of such a folder replaced whatever the editor had written since. The record decides
     * instead, and these two are the wire: they prove initialSync hands
     * shouldOverwriteMirror the answer, not just that the predicate would use it.
     */
    @Test
    fun `with no reported time a mirror the record cannot vouch for is kept`() {
        val local = mirrorHolding("notes.txt", "edited in the editor!", 1_700_000_060_000)
        deviceFolderHolding("notes.txt", "from the device", modified = 0)

        sync()

        assertEquals(
            "edited in the editor!", local.readText(),
            "with no timestamp to compare, an edit the record does not know about is the " +
                "only copy and must not be replaced",
        )
        assertEquals(emptyList<String>(), openedDocuments)
    }

    /** The other side, and the reason "always keep" is not the answer either. */
    @Test
    fun `with no reported time a mirror the record vouches for is refreshed`() {
        val local = mirrorHolding("notes.txt", "what the last sync wrote", 1_700_000_060_000)
        File(mirror.path + SafSyncEngine.SYNCED_RECORD_SUFFIX).writeText(
            SafSyncEngine.RECORD_HEADER + "\n" +
                SafSyncEngine(context).identityLine("notes.txt", local)
        )
        deviceFolderHolding("notes.txt", "changed on the phone", modified = 0)

        sync()

        assertEquals(
            "changed on the phone", local.readText(),
            "the mirror is this app's own copy, so the folder must not freeze",
        )
        assertEquals(listOf("notes.txt"), openedDocuments)
    }

    /**
     * The frozen file comes back, and the signal needs no clock.
     *
     * The old behaviour kept a diverged file and never looked at it again: it could
     * not become vouched, so device changes stopped arriving and the mirror could not
     * be reclaimed. Those two come back here. An edit the watcher never delivered still
     * has no second route to the device, and cannot: it is not on the device, so the
     * comparison below cannot fire for it.
     *
     * What resolves it is the ordinary end of a local edit. The watcher carries the
     * edit to the device, so the two agree in content while the record still describes
     * what was there before. Comparing them is the one question answerable without a
     * clock, and answering it records what is already true on both sides.
     */
    @Test
    fun `a timestamp-less file the watcher already delivered is tracked again`() {
        deviceFolderHolding("notes.txt", "from the device", modified = 0)
        sync()
        val local = File(mirror, "notes.txt")
        assertTrue(local.isFile, "setup failed: the first sync did not create the mirror")

        // The editor edits it and the watcher carries it to the device, so both sides
        // now hold the edit while the record still holds what came before. The length
        // changes too, which is the ordinary case and the one a size test cannot reach.
        local.writeText("a much longer edit made in the editor")
        local.setLastModified(1_700_000_060_000)
        deviceFolderHolding("notes.txt", "a much longer edit made in the editor", modified = 0)
        sync()

        assertEquals(
            emptyList<String>(), writtenDocuments,
            "nothing should have been written to the device: both sides already agree",
        )

        // Tracked again: the next device-side change reaches the mirror.
        deviceFolderHolding("notes.txt", "changed on the device", modified = 0)
        sync()

        assertEquals(
            "changed on the device", local.readText(),
            "still frozen: agreeing with the device should have re-recorded the file, " +
                "so this sync sees its own copy and takes the device change",
        )
    }

    /**
     * And a file the two sides genuinely disagree about is still left alone, on both
     * sides. That is what keeps the fix from being a licence to overwrite.
     */
    @Test
    fun `a timestamp-less file the two sides disagree about is left alone`() {
        deviceFolderHolding("notes.txt", "from the device", modified = 0)
        sync()
        val local = File(mirror, "notes.txt")

        local.writeText("edited in the editor!")
        local.setLastModified(1_700_000_060_000)
        deviceFolderHolding("notes.txt", "a different edit on the device", modified = 0)
        sync()

        assertEquals(
            "edited in the editor!", local.readText(),
            "the local edit survives, which is the point",
        )
        assertEquals(
            emptyList<String>(), writtenDocuments,
            "and the device copy is not overwritten: with no clock, pushing either way " +
                "would destroy the other side's work",
        )

        // And it stays untracked, so a later sync does not quietly take the device copy.
        deviceFolderHolding("notes.txt", "a different edit on the device", modified = 0)
        sync()
        assertEquals(
            "edited in the editor!", local.readText(),
            "the disagreement was recorded away and the device copy taken anyway",
        )
    }

    /**
     * Equal lengths with different bytes must not count as agreement.
     *
     * The length check exists only to skip the read for files that cannot match. If it
     * were mistaken for the answer, a device edit that preserved length would be
     * recorded as agreement and the file would then be overwritten from the device on
     * the next sync, losing the local edit.
     */
    @Test
    fun `equal lengths with different bytes are not agreement`() {
        deviceFolderHolding("notes.txt", "from the device", modified = 0)
        sync()
        val local = File(mirror, "notes.txt")

        local.writeText("from the EDITOR")
        local.setLastModified(1_700_000_060_000)
        deviceFolderHolding("notes.txt", "from the DEVICE", modified = 0)
        sync()
        deviceFolderHolding("notes.txt", "from the DEVICE", modified = 0)
        sync()

        assertEquals(
            "from the EDITOR", local.readText(),
            "the two differ only in content, and treating equal lengths as agreement " +
                "would have recorded the file and let the next sync take the device copy",
        )
    }

    /**
     * And not when the provider reports no time. That arrives as 0, and every real
     * mirror timestamp is greater than 0, so without the guard every file in such a
     * folder looks newer and is pushed over the device document on every reopen.
     */
    @Test
    fun `a mirror is not written back when the provider reports no time`() {
        mirrorHolding("notes.txt", "edited in the editor!", 1_700_000_060_000)
        deviceFolderHolding("notes.txt", "from the device", modified = 0)

        sync()

        assertEquals(emptyList<String>(), writtenDocuments)
    }

    /**
     * Reopening the folder is the way back for an edit the watcher never carried out.
     *
     * The mirror being strictly newer means exactly that: the app was killed, the
     * directory sat past the watch cap, or the write-back gave up. Before this the
     * branch counted the file as "kept" and moved on, so the edit stayed in the mirror
     * for ever and the prose calling reopening a recovery path was not true.
     */
    @Test
    fun `a mirror newer than the device document is written back on reopen`() {
        mirrorHolding("notes.txt", "edited in the editor!", 1_700_000_060_000)
        deviceFolderHolding("notes.txt", "from the device", 1_700_000_000_000)

        sync()

        assertEquals(
            listOf("notes.txt"), writtenDocuments,
            "the newer mirror copy was never offered to the device",
        )
    }

    /**
     * The save the watcher never carried, meeting a device edit made BEFORE it.
     *
     * The mirror copy is newer, so it wins the comparison and is written over the device
     * document with `"wt"`; the device edit, made by another app since the last sync,
     * existed nowhere else. The mirror-side set-aside cannot help, it runs only where the
     * device wins. The record carries the device time this file had at the last sync, so
     * a device document reporting any other time has moved since, and its copy is
     * fetched to a `.device-` name beside the mirror copy before the write.
     */
    @Test
    fun `a device edit made since the last sync is set aside before a newer mirror copy replaces it`() {
        deviceFolderHolding("notes.txt", "from the device", 1_700_000_000_000)
        sync()
        val local = File(mirror, "notes.txt")

        // Edited on the device by something else first...
        deviceFolderHolding("notes.txt", "edited on the device", 1_700_000_060_000)
        // ...and then in the editor, later, with nothing carrying it across.
        local.writeText("edited in the editor, later")
        local.setLastModified(1_700_000_120_000)
        sync()

        assertEquals(
            listOf("notes.txt"), writtenDocuments,
            "the newer mirror copy still has to reach the device",
        )
        val preserved = File(mirror, "notes.txt${SafSyncEngine.DEVICE_COPY_SUFFIX}1700000060000")
        assertTrue(
            preserved.isFile,
            "the only copy of the device edit was written over; the mirror now holds " +
                mirror.list()?.sorted(),
        )
        assertEquals("edited on the device", preserved.readText())
    }

    /**
     * The control that keeps the guard off the ordinary stranded save: a device document
     * still at the time the record holds for it has not moved, so nothing is fetched and
     * nothing is set aside. The provider read is paid only where the record shows the
     * device changed.
     */
    @Test
    fun `a device copy the record vouches for is replaced without a copy left behind`() {
        deviceFolderHolding("notes.txt", "from the device", 1_700_000_000_000)
        sync()
        openedDocuments.clear()
        val local = File(mirror, "notes.txt")

        local.writeText("edited in the editor, later")
        local.setLastModified(1_700_000_120_000)
        // The same device state, declared again: the fake cursor answers one
        // enumeration, so a second sync over the first cursor would see an
        // empty folder rather than an unchanged one.
        deviceFolderHolding("notes.txt", "from the device", 1_700_000_000_000)
        sync()

        assertEquals(listOf("notes.txt"), writtenDocuments)
        assertEquals(
            listOf("notes.txt"), mirror.list()?.sorted(),
            "a device copy this app had already read was set aside as if it were an edit",
        )
        assertEquals(emptyList<String>(), openedDocuments, "the device copy was read for nothing")
    }

    /**
     * The device time moved but the bytes did not, which is what this app's own
     * delivered write-back looks like on a provider with coarse stamps: FAT32
     * stores whole two-second steps, so the write-back's time lands below the
     * mirror's, the record still holds the time before it, and the file reads as
     * a device edit on every reopen after an ordinary save. Setting that aside
     * would put a duplicate of the user's own file into their folder each time.
     */
    @Test
    fun `a device copy with the same bytes under a new time leaves no copy behind`() {
        deviceFolderHolding("notes.txt", "from the device", 1_700_000_000_000)
        sync()
        val local = File(mirror, "notes.txt")

        local.writeText("saved in the editor")
        local.setLastModified(1_700_000_120_900)
        // The write-back landed, stamped by a provider that keeps whole seconds.
        deviceFolderHolding("notes.txt", "saved in the editor", 1_700_000_120_000)
        sync()

        assertEquals(listOf("notes.txt"), writtenDocuments, "the newer mirror copy is still written")
        assertEquals(
            listOf("notes.txt"), mirror.list()?.sorted(),
            "identical bytes under a moved time were kept as a .device- copy",
        )
    }

    /**
     * A device copy that cannot be fetched cannot be set aside, and then the write does
     * not happen: both sides keep what they have, the mirror stays unvouched, and the
     * next open tries again. Writing anyway would be the loss the guard exists for.
     */
    @Test
    fun `a device edit that cannot be set aside is not written over`() {
        deviceFolderHolding("notes.txt", "from the device", 1_700_000_000_000)
        sync()
        val local = File(mirror, "notes.txt")

        deviceFolderHolding("notes.txt", "edited on the device", 1_700_000_060_000)
        local.writeText("edited in the editor, later")
        local.setLastModified(1_700_000_120_000)
        every { resolver.openInputStream(any()) } throws IOException("the provider refused the read")
        sync()

        assertEquals(
            emptyList<String>(), writtenDocuments,
            "the device edit was written over with no copy of it anywhere",
        )
        assertEquals("edited in the editor, later", local.readText(), "and the mirror keeps its own")
    }

    /**
     * A write-back that gives up says so.
     *
     * Both exits already leave the file's journal entry in place, so the data is safe:
     * the reclaim pass reads that journal and refuses to delete the mirror. What was
     * missing is the user knowing, and those are not the same fact. A save that never
     * reached the device folder looks exactly like one that did, and the difference only
     * shows when the app is uninstalled and the work is gone.
     *
     * Driven through the seam rather than the Toast: Toast needs a looper no unit test
     * here has, and what is being asserted is that the exit announces at all.
     */
    @Test
    fun `a write-back that gives up announces the file`() {
        mirrorHolding("notes.txt", "edited in the editor!", 1_700_000_060_000)
        deviceFolderHolding("notes.txt", "from the device", 1_700_000_000_000)
        every { resolver.openOutputStream(any(), "wt") } throws SecurityException("revoked")

        val announced = mutableListOf<String>()
        runBlocking {
            SafSyncEngine(context)
                .apply { onWriteBackFailed = { announced.add(it.name) } }
                .initialSync(mockk<Uri>(relaxed = true), mirror) { _, _ -> }
        }

        assertEquals(
            listOf("notes.txt"), announced,
            "the write-back gave up and told nobody",
        )
    }

    @Test
    fun `an edit made on the device reaches the mirror`() {
        val local = mirrorHolding("notes.txt", "stale copy", 1_700_000_000_000)
        deviceFolderHolding("notes.txt", "changed on the phone", 1_700_000_060_000)

        sync()

        assertEquals("changed on the phone", local.readText())
        assertEquals(listOf("notes.txt"), openedDocuments)
    }

    /**
     * The save the watcher never carried, meeting a device edit made afterwards.
     *
     * `shouldOverwriteMirror` is last-writer-wins on modification time, so the device
     * copy wins and the mirror is replaced in place, after which the record vouches for
     * the result: the edit is gone and nothing anywhere holds it. Reaching that needs
     * only a save the write-back did not deliver (the app killed before the drain ran it,
     * or the file past the watch cap) and then any other app touching the same file.
     */
    @Test
    fun `a mirror edit the record cannot vouch for is set aside before the device replaces it`() {
        deviceFolderHolding("notes.txt", "from the device", 1_700_000_000_000)
        sync()
        val local = File(mirror, "notes.txt")

        // The editor saves, and nothing carries it to the device.
        local.writeText("an hour of writing")
        local.setLastModified(1_700_000_060_000)
        // Then the file is edited on the device by something else, later.
        deviceFolderHolding("notes.txt", "changed on the device", 1_700_000_120_000)
        sync()

        assertEquals(
            "changed on the device", local.readText(),
            "the device copy is newer and still has to arrive",
        )
        val preserved = File(mirror, "notes.txt${SafSyncEngine.LOCAL_COPY_SUFFIX}1700000060000")
        assertTrue(
            preserved.isFile,
            "the only copy of the local edit was overwritten; the mirror now holds " +
                mirror.list()?.sorted(),
        )
        assertEquals("an hour of writing", preserved.readText())
    }

    /**
     * The condition that keeps the guard off the ordinary case, and the one the guard is
     * useless without.
     *
     * A save the watcher DID deliver leaves exactly the same bookkeeping behind: the
     * record still names the pre-edit copy, and the device carries the write-back's own
     * fresh timestamp, so the device wins the comparison and the record cannot vouch for
     * the mirror. What separates it is content, which is why the two sides are compared
     * rather than their timestamps. Without that, every file touched in a session would
     * leave a spare copy in the workspace on the next open.
     */
    @Test
    fun `a save the watcher already delivered is replaced without a copy left behind`() {
        deviceFolderHolding("notes.txt", "from the device", 1_700_000_000_000)
        sync()
        val local = File(mirror, "notes.txt")

        local.writeText("an hour of writing")
        local.setLastModified(1_700_000_060_000)
        // The watcher carried it across, so the device holds the same bytes under the
        // timestamp its own write stamped on them.
        deviceFolderHolding("notes.txt", "an hour of writing", 1_700_000_120_000)
        sync()

        assertEquals(
            listOf("notes.txt"), mirror.list()?.sorted(),
            "an edit that did reach the device left a spare copy in the workspace",
        )
    }

    /**
     * And nothing is set aside when there is no record to be silent about.
     *
     * The record is the only evidence that a mirror file is not this app's own copy, so
     * without one every file in the mirror looks diverged, the ordinary stale copy a
     * previous sync wrote included. A record that is absent, unreadable or in a format
     * this build does not know all arrive as null, which is not the same as a record
     * that parsed and names nothing, and each has to mean the pre-existing behaviour
     * rather than a workspace full of spare copies.
     */
    @Test
    fun `with no record behind it a stale mirror copy is replaced, not set aside`() {
        mirrorHolding("notes.txt", "stale copy", 1_700_000_000_000)
        deviceFolderHolding("notes.txt", "changed on the phone", 1_700_000_060_000)

        sync()

        assertEquals(
            listOf("notes.txt"), mirror.list()?.sorted(),
            "a mirror with no record behind it left a spare copy of every stale file",
        )
    }

    /**
     * A record that parsed and vouches for nothing is not the same as no record, and
     * this is the half of that distinction the case above cannot reach.
     *
     * It is the ordinary state of a folder on a provider that omits
     * COLUMN_LAST_MODIFIED: with no clock to compare, every file is kept rather than
     * refreshed, and a sync that kept everything it saw records nothing. Read as "the
     * record is silent about this file", every stale mirror copy in such a folder then
     * gains a `.local-` twin on every open, and nothing ever removes one.
     */
    @Test
    fun `a record that vouches for nothing sets nothing aside`() {
        deviceFolderHolding("notes.txt", "from the device", 1_700_000_000_000)
        sync()

        // What a sync that kept every file it saw leaves behind: the header, and no
        // line under it.
        File(mirror.path + SafSyncEngine.SYNCED_RECORD_SUFFIX)
            .writeText(SafSyncEngine.RECORD_HEADER)
        deviceFolderHolding("notes.txt", "changed on the device", 1_700_000_120_000)
        sync()

        assertEquals(
            listOf("notes.txt"), mirror.list()?.sorted(),
            "a record that names nothing was read as silence about this file, so the " +
                "workspace gained a spare copy of it",
        )
    }

    @Test
    fun `a file the mirror does not have is fetched`() {
        deviceFolderHolding("fresh.txt", "brand new", 1_700_000_000_000)

        sync()

        assertEquals("brand new", File(mirror, "fresh.txt").readText())
        assertEquals(listOf("fresh.txt"), openedDocuments)
    }

    @Test
    fun `a mirror copy carries the source timestamp so later syncs compare one clock`() {
        deviceFolderHolding("stamped.txt", "content", 1_700_000_000_000)

        sync()

        assertEquals(1_700_000_000_000, File(mirror, "stamped.txt").lastModified())
    }

    /**
     * The second consecutive pass through the write-back arm.
     *
     * Sync 2 pushes and records nothing, so sync 3 finds the record silent about the
     * path, answers "the device did not change" and truncates it unread.
     */
    @Test
    fun `a second write-back pass still sets a foreign device edit aside`() {
        deviceFolderHolding("notes.txt", "from the device", 1_700_000_000_000)
        sync()
        val local = File(mirror, "notes.txt")

        // Pass 1: the delivered write-back under a coarse stamp. Empties the record.
        local.writeText("saved in the editor")
        local.setLastModified(1_700_000_120_900)
        deviceFolderHolding("notes.txt", "saved in the editor", 1_700_000_120_000)
        sync()
        val recordAfterPass1 =
            File(mirror.path + SafSyncEngine.SYNCED_RECORD_SUFFIX).readLines()

        // Pass 2: another app wrote the device document, still stamped below the
        // mirror, and the editor saved again afterwards.
        openedDocuments.clear()
        writtenDocuments.clear()
        deviceFolderHolding("notes.txt", "changed by another app", 1_700_000_120_500)
        local.writeText("saved in the editor, again")
        local.setLastModified(1_700_000_180_000)
        sync()

        val preserved =
            File(mirror, "notes.txt${SafSyncEngine.DEVICE_COPY_SUFFIX}1700000120500")
        assertEquals(listOf("notes.txt"), writtenDocuments, "the newer mirror copy still has to reach the device")
        assertTrue(
            preserved.isFile,
            "the foreign device edit was overwritten with no copy anywhere; mirror now holds " +
                mirror.list()?.sorted() + ", record after pass 1 was " + recordAfterPass1 +
                ", opened during pass 2 was " + openedDocuments,
        )
        assertEquals("changed by another app", preserved.readText())
    }

    /**
     * A record that parsed and does not name the path, meeting a foreign
     * device edit under the write-back arm. Nothing to carry forward, so this is the
     * case that separates "answer true on silence" from "carry the old line".
     */
    @Test
    fun `a record silent about the path still sets a foreign device edit aside`() {
        deviceFolderHolding("notes.txt", "from the device", 1_700_000_000_000)
        sync()
        val local = File(mirror, "notes.txt")

        // The record parses and vouches for nothing: what a sync that kept every file
        // it saw leaves behind, and what the write-back arm leaves behind too.
        File(mirror.path + SafSyncEngine.SYNCED_RECORD_SUFFIX)
            .writeText(SafSyncEngine.RECORD_HEADER)

        openedDocuments.clear()
        writtenDocuments.clear()
        local.writeText("saved in the editor, again")
        local.setLastModified(1_700_000_180_000)
        deviceFolderHolding("notes.txt", "changed by another app", 1_700_000_120_500)
        sync()

        val preserved =
            File(mirror, "notes.txt${SafSyncEngine.DEVICE_COPY_SUFFIX}1700000120500")
        assertEquals(listOf("notes.txt"), writtenDocuments, "the newer mirror copy still has to reach the device")
        assertTrue(
            preserved.isFile,
            "the foreign device edit was overwritten with no copy anywhere; mirror now holds " +
                mirror.list()?.sorted() + ", opened was " + openedDocuments,
        )
        assertEquals("changed by another app", preserved.readText())
    }

    @Test
    fun `a record line whose time will not parse still sets a foreign device edit aside`() {
        deviceFolderHolding("notes.txt", "from the device", 1_700_000_000_000)
        sync()
        val local = File(mirror, "notes.txt")

        // The third producer of silence, and the one the two cases above leave
        // uncovered: a line that names the path but carries a time this build
        // cannot read. A record written by a later format, or one truncated in
        // the middle of a field, arrives here looking exactly like a vouched
        // path. It vouches for nothing, and the arm that reads it has to say so.
        File(mirror.path + SafSyncEngine.SYNCED_RECORD_SUFFIX)
            .writeText(SafSyncEngine.RECORD_HEADER + "\nnotes.txt\tnot-a-number\t15\n")

        openedDocuments.clear()
        writtenDocuments.clear()
        local.writeText("saved in the editor, again")
        local.setLastModified(1_700_000_180_000)
        deviceFolderHolding("notes.txt", "changed by another app", 1_700_000_120_500)
        sync()

        val preserved =
            File(mirror, "notes.txt${SafSyncEngine.DEVICE_COPY_SUFFIX}1700000120500")
        assertEquals(listOf("notes.txt"), writtenDocuments, "the newer mirror copy still has to reach the device")
        assertTrue(
            preserved.isFile,
            "a line this build cannot read was treated as a vouched copy, and the foreign " +
                "device edit was overwritten with no copy anywhere; mirror now holds " +
                mirror.list()?.sorted() + ", opened was " + openedDocuments,
        )
        assertEquals("changed by another app", preserved.readText())
    }
}
