package com.vscodroid.storage

import android.content.ContentResolver
import android.content.Context
import android.content.SharedPreferences
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
}
