package com.vscodroid.storage

import android.content.ContentResolver
import android.content.Context
import android.content.SharedPreferences
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * That opening a device folder does not put the user's directory into logcat.
 *
 * A SAF tree URI spells the folder out: `.../tree/primary%3ADocuments%2F<folder>`.
 * `Logger.i`, `Logger.w` and `Logger.e` are not gated on a debuggable build, so anything
 * they are handed ships, and it reaches anyone the user sends a device bug report to as
 * well as anything on the device holding `READ_LOGS`. The app already reduces every tree
 * URI to a six-byte digest to name its mirror, which is stable, is not reversible, and is
 * what every other line about that folder already uses.
 *
 * Asserted at `android.util.Log` rather than at `Logger`, and that is the point of the
 * shape. The failure path both interpolated the URI *and* passed the `SecurityException`
 * along, and `takePersistableUriPermission` quotes the URI in that exception's own
 * message, so dropping the interpolation on its own would have left the same string
 * arriving by the other route. Watching the sink sees both.
 */
class SafFolderPathLoggingTest {

    @TempDir
    lateinit var filesDir: File

    private lateinit var resolver: ContentResolver
    private lateinit var context: Context
    private val folderUri = mockk<Uri>(relaxed = true)

    /** Every string the app handed the log, message and throwable alike. */
    private val emitted = mutableListOf<String>()

    private val treeUri =
        "content://com.android.externalstorage.documents/tree/primary%3ADocuments%2FClientProject"

    /** The part of that URI a reader could turn back into a place on the device. */
    private val folderName = "ClientProject"

    @BeforeEach
    fun setUp() {
        emitted.clear()

        mockkStatic(android.util.Log::class)
        every { android.util.Log.i(any(), any()) } answers { note(args); 0 }
        every { android.util.Log.d(any(), any()) } answers { note(args); 0 }
        every { android.util.Log.w(any(), any<String>()) } answers { note(args); 0 }
        every { android.util.Log.w(any(), any<String>(), any<Throwable>()) } answers { note(args); 0 }
        every { android.util.Log.e(any(), any()) } answers { note(args); 0 }
        every { android.util.Log.e(any(), any<String>(), any<Throwable>()) } answers { note(args); 0 }

        mockkStatic(DocumentsContract::class)
        every { DocumentsContract.getTreeDocumentId(any()) } returns "root"
        every { DocumentsContract.buildDocumentUriUsingTree(any(), any()) } returns
            mockk(relaxed = true)
        every { DocumentsContract.buildChildDocumentsUriUsingTree(any(), any()) } returns
            mockk(relaxed = true)

        every { folderUri.toString() } returns treeUri
        every { folderUri.lastPathSegment } returns null

        resolver = mockk(relaxed = true)
        every { resolver.query(any(), any(), any(), any(), any()) } returns null
        context = mockk(relaxed = true)
        every { context.filesDir } returns filesDir
        // The manager unwraps whatever it is given to the application context, so a
        // relaxed mock that answers a different object for it hands the manager a
        // filesDir that is not the one below.
        every { context.applicationContext } returns context
        every { context.contentResolver } returns resolver
        every { context.getSharedPreferences(any(), any()) } returns fakePrefs()
    }

    /** A recent list that starts empty and remembers what is written to it. */
    private fun fakePrefs(): SharedPreferences {
        var recentJson = "[]"
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        val written = slot<String>()
        every { editor.putString(any(), capture(written)) } answers {
            recentJson = written.captured
            editor
        }
        return mockk<SharedPreferences>(relaxed = true).also { prefs ->
            every { prefs.getString(any(), any()) } answers { recentJson }
            every { prefs.edit() } returns editor
        }
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    /**
     * Records a log call's arguments, a throwable included as the text it would print.
     * A tag is recorded too: it is written by the same statement and is as capable of
     * carrying an interpolation.
     */
    private fun note(args: List<Any?>) {
        args.forEach { arg ->
            emitted += when (arg) {
                is Throwable -> arg.toString() + (arg.message ?: "")
                else -> arg.toString()
            }
        }
    }

    private fun assertSawSomething() =
        assertTrue(
            emitted.isNotEmpty(),
            "nothing reached the log at all, so this run proves nothing about what does",
        )

    private fun assertFolderNotNamed() {
        assertSawSomething()
        val leaked = emitted.filter { it.contains(folderName) || it.contains("tree/primary") }
        assertTrue(
            leaked.isEmpty(),
            "the user's device folder reached release logcat: $leaked",
        )
    }

    @Test
    fun `taking a folder's permission does not log the folder`() {
        val manager = SafStorageManager(context)

        manager.persistPermission(folderUri)

        assertFolderNotNamed()
    }

    @Test
    fun `failing to take a folder's permission does not log the folder`() {
        every { resolver.takePersistableUriPermission(any(), any()) } throws
            SecurityException("Permission Denial: opening provider for $treeUri")
        val manager = SafStorageManager(context)

        manager.persistPermission(folderUri)

        assertFolderNotNamed()
    }

    @Test
    fun `opening a folder does not log the folder`() {
        val mirrorDir = File(filesDir, "saf-mirrors/abc123def456").apply { mkdirs() }
        val engine = SafSyncEngine(context)

        runBlocking { engine.initialSync(folderUri, mirrorDir) { _, _ -> } }

        assertFolderNotNamed()
    }

    /**
     * A device folder holding one document, whose id is what the platform provider makes
     * it: the user's own path, `primary:Documents/<folder>/<file>`.
     *
     * The case above proves nothing about the copy, and that is how two leaks survived a
     * guard written for exactly this. With `query` answering null the walk gives up
     * before phase 2 exists, so `copyDocumentToLocal` is never reached and the only
     * thing covering the gate there was reading it.
     */
    private fun deviceHoldingOneFile(lastModified: Long = 1_700_000_000_000L) {
        val docId = "primary:Documents/$folderName/notes.txt"
        val docUri = mockk<Uri>(relaxed = true)
        every { docUri.lastPathSegment } returns docId
        every { DocumentsContract.buildDocumentUriUsingTree(any(), any()) } returns docUri

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
        every { cursor.getString(0) } returns docId
        every { cursor.getString(1) } returns "notes.txt"
        every { cursor.getString(2) } returns "text/plain"
        every { cursor.getLong(3) } returns 12L
        every { cursor.getLong(4) } returns lastModified
        every { resolver.query(any(), any(), any(), any(), any()) } answers {
            row = -1
            cursor
        }
    }

    private fun syncOneFolder() {
        val mirrorDir = File(filesDir, "saf-mirrors/abc123def456").apply { mkdirs() }
        runBlocking { SafSyncEngine(context).initialSync(folderUri, mirrorDir) { _, _ -> } }
    }

    @Test
    fun `a document that cannot be copied does not name the user's directory`() {
        deviceHoldingOneFile()
        every { resolver.openInputStream(any()) } returns null

        syncOneFolder()

        assertFolderNotNamed()
    }

    /**
     * The half a dropped interpolation does not cover. The stream comes from the
     * provider, and `FileNotFoundException` and `SecurityException` routinely quote the
     * document URI in their own message, so a line that stops naming the document and
     * still repeats `e.message` puts the same string in logcat by the other route.
     */
    @Test
    fun `a copy that throws does not repeat the provider's own message`() {
        deviceHoldingOneFile()
        every { resolver.openInputStream(any()) } throws java.io.FileNotFoundException(
            "open failed for $treeUri/document/primary%3ADocuments%2FClientProject%2Fnotes.txt"
        )

        syncOneFolder()

        assertFolderNotNamed()
    }

    /**
     * The line the copy path's redaction does not cover, on the branch that only exists
     * for providers reporting no `COLUMN_LAST_MODIFIED` (MTP, some USB-OTG bridges, some
     * network providers). With no clock to compare, the sync reads both sides and asks
     * whether they hold the same bytes, and that read comes from the provider too.
     */
    @Test
    fun `a comparison that throws does not repeat the provider's own message`() {
        val mirrorDir = File(filesDir, "saf-mirrors/abc123def456").apply { mkdirs() }
        // The same length the document reports, which is what gets the comparison as far
        // as opening the device copy at all.
        File(mirrorDir, "notes.txt").writeText("123456789012")
        deviceHoldingOneFile(lastModified = 0L)
        every { resolver.openInputStream(any()) } throws java.io.FileNotFoundException(
            "open failed for $treeUri/document/primary%3ADocuments%2FClientProject%2Fnotes.txt"
        )

        runBlocking { SafSyncEngine(context).initialSync(folderUri, mirrorDir) { _, _ -> } }

        assertFolderNotNamed()
        assertTrue(
            emitted.any {
                it.contains("Could not compare notes.txt") &&
                    it.contains("FileNotFoundException")
            },
            "the comparison never ran, so this case is asserting nothing: $emitted",
        )
    }

    @Test
    fun `an enumeration that fails does not name the document it failed on`() {
        every { DocumentsContract.getTreeDocumentId(any()) } returns
            "primary:Documents/$folderName"
        every { resolver.query(any(), any(), any(), any(), any()) } throws
            IllegalStateException("provider died")

        syncOneFolder()

        assertFolderNotNamed()
    }

    /**
     * The control for the copy path, in the same spirit as the one below it: a bug report
     * still has to be able to say which file could not be copied.
     */
    @Test
    fun `the copy failure still says which file it was`() {
        deviceHoldingOneFile()
        every { resolver.openInputStream(any()) } returns null

        syncOneFolder()

        assertSawSomething()
        assertTrue(
            emitted.any { it.contains("notes.txt") },
            "the redaction removed the record rather than the secret",
        )
    }

    /**
     * The control. Redaction that leaves nothing behind is not redaction, it is deletion,
     * and a bug report still has to be able to line these lines up with the folder they
     * are about. The mirror's own name is what does that.
     */
    @Test
    fun `the lines still identify the folder by its mirror name`() {
        val manager = SafStorageManager(context)
        val hash = manager.getMirrorDir(folderUri).name

        manager.persistPermission(folderUri)

        assertSawSomething()
        assertFalse(
            emitted.none { it.contains(hash) },
            "no line named the folder at all, so the redaction removed the record " +
                "rather than the secret",
        )
    }

    /**
     * The channel redacting log lines cannot reach.
     *
     * `MainActivity.openSafFolder` catches this exception and hands the throwable
     * itself to `Logger.e`, which prints its message, so an interpolation here
     * arrives in release logcat whatever the line beside it says. Asserted on the
     * message rather than through the caller because the message is the leak: any
     * `Logger.w`/`Logger.e` that is given this throwable repeats it, and that set
     * is open.
     *
     * Negative control: restore `"Permission revoked for: $safUri"` in
     * `SafStorageManager.syncToLocal` and the first assertion goes red while the
     * second stays green, which is the pair that distinguishes redaction from
     * deletion.
     */
    @Test
    fun `the revoked-permission failure does not name the user's folder`() {
        val manager = SafStorageManager(context)
        val hash = manager.getMirrorDir(folderUri).name

        val thrown = assertThrows(SecurityException::class.java) {
            runBlocking { manager.syncToLocal(folderUri) }
        }

        val message = thrown.message ?: ""
        assertFalse(
            message.contains(folderName) || message.contains("tree/primary"),
            "the user's device folder reached the exception message: $message",
        )
        assertTrue(
            message.contains(hash),
            "the failure names no folder at all, so a report cannot be tied to one: $message",
        )
    }
}
