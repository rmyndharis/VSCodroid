package com.vscodroid.storage

import android.content.ContentResolver
import android.content.Context
import android.content.SharedPreferences
import android.content.UriPermission
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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Which half of a grant a folder has to carry before it is opened.
 *
 * `persistPermission` takes read and write together, and every local-to-device call a
 * watched session then makes needs the write half: `openOutputStream(uri, "wt")`,
 * `createDocument`, `renameDocument`, `moveDocument`, `deleteDocument`. The gate in front
 * of all of it asked about read alone, so a grant carrying one and not the other opened
 * the folder, completed the sync and started the watcher, and the user's saves then
 * failed one at a time behind a notice throttled to one per ten seconds for the whole
 * folder.
 *
 * Not a state this app can reach on its own today, which is why the gate is asked here
 * rather than through a scenario: `takePersistableUriPermission` is all-or-nothing over
 * the flags it is handed, and both release sites pass both flags. What it stops is a
 * system that hands back one half, and the cost of asking is a field read.
 */
class SafGrantGateTest {

    @TempDir
    lateinit var filesDir: File

    private lateinit var resolver: ContentResolver
    private lateinit var context: Context
    private lateinit var folderUri: Uri

    @BeforeEach
    fun setUp() {
        mockkObject(Logger)
        every { Logger.i(any(), any()) } just Runs
        every { Logger.d(any(), any()) } just Runs
        every { Logger.w(any(), any()) } just Runs
        every { Logger.e(any(), any()) } just Runs

        mockkStatic(DocumentsContract::class)
        every { DocumentsContract.getTreeDocumentId(any()) } returns "root"
        every { DocumentsContract.buildDocumentUriUsingTree(any(), any()) } returns
            mockk(relaxed = true)

        folderUri = mockk<Uri>(relaxed = true).also {
            every { it.toString() } returns "content://tree/primary%3AProjects"
        }

        resolver = mockk(relaxed = true)
        context = mockk(relaxed = true)
        every { context.applicationContext } returns context
        every { context.contentResolver } returns resolver
        every { context.filesDir } returns filesDir
        every { context.getSharedPreferences(any(), any()) } returns fakePrefs()
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    private fun fakePrefs(): SharedPreferences =
        mockk<SharedPreferences>(relaxed = true).also {
            every { it.getString(any(), any()) } returns "[]"
        }

    /** The system server's answer: one persisted grant on the folder, with these halves. */
    private fun grantCarrying(read: Boolean, write: Boolean) {
        every { resolver.persistedUriPermissions } returns listOf(
            mockk<UriPermission> {
                every { uri } returns folderUri
                every { isReadPermission } returns read
                every { isWritePermission } returns write
            }
        )
    }

    @Test
    fun `a folder granted both halves is accepted`() {
        grantCarrying(read = true, write = true)

        assertTrue(
            SafStorageManager(context).hasPersistedPermission(folderUri),
            "the ordinary grant was refused, so no folder can be opened at all",
        )
    }

    @Test
    fun `a grant carrying only read is refused`() {
        grantCarrying(read = true, write = false)

        assertFalse(
            SafStorageManager(context).hasPersistedPermission(folderUri),
            "a folder this app cannot write to was accepted, so every save would fail " +
                "one at a time after the folder had already opened",
        )
    }

    /**
     * And the refusal arrives where it can still be acted on: before the sync, the
     * watcher and the first save, through the path a revoked grant already takes.
     */
    @Test
    fun `syncing a read-only folder fails before anything is copied`() {
        grantCarrying(read = true, write = false)
        val manager = SafStorageManager(context)

        assertThrows(SecurityException::class.java) {
            runBlocking { manager.syncToLocal(folderUri) }
        }
    }
}
