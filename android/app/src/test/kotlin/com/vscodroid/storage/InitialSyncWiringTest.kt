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

/**
 * `ShouldOverwriteMirrorTest` pins the decision. This pins the *call to it*: the guard
 * could be deleted from [SafSyncEngine.initialSync] with all seven of those methods
 * green, which is how the fix for #84 shipped without anything holding it in place.
 *
 * These run `initialSync` itself. No production seam was added for them — the engine
 * already takes a `Context`, and its far end is `ContentResolver.openInputStream`:
 * called means it copied, not called means it kept what was there. The mirror is a real
 * directory, so `exists`, `lastModified` and `length` are answered by the filesystem.
 *
 * Fixture values are chosen so the broken path cannot produce them. A local edit here is
 * both *newer* and a *different length* than the source — the combination the first
 * version of the guard got wrong, because it compared sizes before timestamps.
 */
class InitialSyncWiringTest {

    @TempDir
    lateinit var mirror: File

    /**
     * The sync now records what it copied, in a file beside the mirror rather than inside
     * it — so it lands outside the directory `@TempDir` cleans up, and each test method
     * gets a fresh directory and therefore a fresh name. Removed here so the runs do not
     * accumulate one small file apiece in the system temp root.
     */
    /**
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

    @Test
    fun `an unsaved local edit survives reopening the folder`() {
        // Newer AND a different length — the pairing the size-first rule overwrote.
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
     * The frozen case, now resolved by data the sync already has.
     *
     * With no clock there is nothing to say whether a difference came from the device
     * or from the editor, and the old answer was to keep the mirror and stop looking.
     * That was safe and permanent: the file never became vouched again, so device
     * changes stopped arriving for the life of the mirror.
     *
     * What separates the two cases is the size the record already carries. The cursor
     * carries the device document's length for free, so when the two agree the device
     * copy has not moved since the last sync and the divergence is local only. The
     * mirror is written back, recorded, and the next sync treats it as its own again.
     *
     * The two syncs are the point: the first leaves the file diverged, the second is
     * where the old behaviour would have given up.
     */
    @Test
    fun `a local edit on a timestamp-less provider is written back and tracked again`() {
        // The fake cursor is single use: `moveToNext` answers true once. So the device
        // folder is re-armed before every sync, or the next one enumerates nothing and
        // the assertions below would pass over an empty folder rather than a real one.
        deviceFolderHolding("notes.txt", "from the device", modified = 0)
        sync()
        val local = File(mirror, "notes.txt")
        assertTrue(local.isFile, "setup failed: the first sync did not create the mirror")
        val rec = File(mirror.path + ".synced")
        assertTrue(rec.readText().contains("notes.txt"), "setup failed: nothing was recorded")

        // The editor edits it, keeping the same length, so the device copy is still the
        // length the record says this app wrote.
        local.writeText("from the EDITOR")
        local.setLastModified(1_700_000_060_000)
        deviceFolderHolding("notes.txt", "from the device", modified = 0)
        sync()

        assertEquals(
            listOf("notes.txt"), writtenDocuments,
            "the local edit should have been written back: the device copy is still the " +
                "length the record says this app wrote, so nothing of the user's is lost",
        )

        // And now it is tracked again: a device-side change reaches the mirror.
        deviceFolderHolding("notes.txt", "changed on the device", modified = 0)
        sync()

        assertEquals(
            "changed on the device", local.readText(),
            "the file is still frozen: being written back should have re-recorded it, " +
                "so this sync sees its own copy and takes the device change",
        )
    }

    /**
     * And the genuine conflict is still refused, which is what keeps the fix honest.
     *
     * When the device document's length no longer matches what the record says was
     * written, both sides moved. Nothing here can say which is newer, so the mirror is
     * kept and the device copy is left alone rather than overwritten.
     */
    @Test
    fun `a timestamp-less file both sides changed is kept, and neither side is pushed`() {
        deviceFolderHolding("notes.txt", "from the device", modified = 0)
        sync()
        val local = File(mirror, "notes.txt")
        assertTrue(local.isFile, "setup failed: the first sync did not create the mirror")

        local.writeText("edited in the editor!")
        local.setLastModified(1_700_000_060_000)
        deviceFolderHolding("notes.txt", "a different length on the device", modified = 0)
        sync()

        assertEquals(
            "edited in the editor!", local.readText(),
            "the local edit survives, which is the point",
        )
        assertEquals(
            emptyList<String>(), writtenDocuments,
            "and the device copy is not overwritten either: both sides changed, so " +
                "pushing the mirror over it would destroy the device's version",
        )
    }

    /**
     * A write-back that fails must not leave the mirror vouched for.
     *
     * The record means "the device holds these bytes". Writing that line when the
     * write-back did not land would claim the device has an edit it never received, and
     * `holdsOnlyVouchedCopies` reads the record with exactly that meaning to decide a
     * mirror is disposable. The reclaim pass would then be free to delete the only copy
     * of the user's edit.
     *
     * So the record is gated on the write succeeding, and this is what proves the gate
     * is load-bearing rather than decorative.
     */
    @Test
    fun `a failed write-back on a timestamp-less provider vouches for nothing`() {
        deviceFolderHolding("notes.txt", "from the device", modified = 0)
        sync()
        val local = File(mirror, "notes.txt")
        assertTrue(local.isFile, "setup failed: the first sync did not create the mirror")
        local.writeText("from the EDITOR")
        local.setLastModified(1_700_000_060_000)
        deviceFolderHolding("notes.txt", "from the device", modified = 0)
        // The device refuses the write. Everything else about this sync is the case
        // above, which does record.
        every { resolver.openOutputStream(any(), "wt") } returns null
        sync()

        val after = File(mirror.path + ".synced").readText()
        assertTrue(
            "notes.txt\t${local.lastModified()}\t${local.length()}" !in after,
            "the record now names the edit's own identity, so it claims the device holds " +
                "bytes it never received and the reclaim pass is free to delete the only " +
                "copy. The record holds:\n$after",
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
}
