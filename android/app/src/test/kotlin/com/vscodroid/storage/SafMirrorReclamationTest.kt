package com.vscodroid.storage

import android.content.ContentResolver
import android.content.Context
import android.content.UriPermission
import android.net.Uri
import com.vscodroid.util.Logger
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Reclaiming the mirrors of folders the user no longer grants.
 *
 * This deletes recursively, and it used to run inside [SafStorageManager.getPersistedFolders]
 * -- the method the workbench calls whenever it wants the recent folder list, and which
 * three of this class's own write paths call as well. A permission that read as absent
 * for a moment was enough to take the mirror of the folder open in the editor with it.
 *
 * Splitting the query from the reclamation is what fixes that, so what matters here is
 * not only that a stale mirror goes: it is that a live one stays, measured in the same
 * run so that "it stayed" cannot be explained by nothing having happened.
 */
class SafMirrorReclamationTest {

    @TempDir
    lateinit var filesDir: File

    private lateinit var manager: SafStorageManager
    private lateinit var resolver: ContentResolver
    private lateinit var mirrorsDir: File

    private val revokedUri = mockk<Uri>()
    private val liveUri = mockk<Uri>()

    @BeforeEach
    fun setUp() {
        mockkObject(Logger)
        every { Logger.i(any(), any()) } just Runs
        every { Logger.d(any(), any()) } just Runs
        every { Logger.w(any(), any()) } just Runs
        every { Logger.w(any(), any(), any()) } just Runs
        every { Logger.e(any(), any()) } just Runs
        every { Logger.e(any(), any(), any()) } just Runs

        // The mirror name is a digest of the URI string, so this is all a Uri has to be.
        every { revokedUri.toString() } returns "content://tree/primary%3AOldProject"
        every { liveUri.toString() } returns "content://tree/primary%3ACurrentProject"

        resolver = mockk(relaxed = true)
        val context = mockk<Context>(relaxed = true)
        every { context.filesDir } returns filesDir
        every { context.contentResolver } returns resolver

        manager = SafStorageManager(context)
        mirrorsDir = File(filesDir, "saf-mirrors").apply { mkdirs() }
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    /** A permission the system still reports as held for [granted]. */
    private fun permissionFor(granted: Uri): UriPermission = mockk<UriPermission> {
        every { uri } returns granted
    }

    /** Builds the mirror directory and the sync record the engine keeps beside it. */
    private fun mirrorFor(uri: Uri): Pair<File, File> {
        val dir = manager.getMirrorDir(uri).apply { mkdirs() }
        File(dir, "src").mkdirs()
        File(dir, "src/main.kt").writeText("fun main() {}")
        val record = File(dir.path + SafSyncEngine.SYNCED_RECORD_SUFFIX)
            .apply { writeText("src/main.kt") }
        return dir to record
    }

    @Test
    fun `a stale mirror goes and a live one stays, in the same pass`() {
        val (staleDir, staleRecord) = mirrorFor(revokedUri)
        val (liveDir, liveRecord) = mirrorFor(liveUri)
        every { resolver.persistedUriPermissions } returns listOf(permissionFor(liveUri))

        val removed = manager.reclaimRevokedMirrorsSync()

        assertFalse(staleDir.exists(), "a mirror with no permission behind it is unreachable disk")
        assertFalse(staleRecord.exists(), "the record is named after the same hash and goes with it")
        assertTrue(liveDir.isDirectory, "the granted folder's mirror must survive")
        assertTrue(
            File(liveDir, "src/main.kt").isFile,
            "surviving means its contents too, not just the directory"
        )
        assertTrue(liveRecord.isFile, "the surviving mirror keeps the record it needs")
        assertEquals(2, removed, "the directory and its record are the two entries removed")
    }

    @Test
    fun `nothing is reclaimed while every mirror is still granted`() {
        val (dir, record) = mirrorFor(liveUri)
        every { resolver.persistedUriPermissions } returns listOf(permissionFor(liveUri))

        assertEquals(0, manager.reclaimRevokedMirrorsSync())
        assertTrue(dir.isDirectory)
        assertTrue(record.isFile)
    }

    @Test
    fun `an empty mirrors directory is not an error`() {
        every { resolver.persistedUriPermissions } returns emptyList()

        assertEquals(0, manager.reclaimRevokedMirrorsSync())
    }

    @Test
    fun `a missing mirrors directory is not an error`() {
        // Reached on any install that has never opened a device folder, which is the
        // common case at the launch-time call site.
        mirrorsDir.deleteRecursively()
        every { resolver.persistedUriPermissions } returns emptyList()

        assertEquals(0, manager.reclaimRevokedMirrorsSync())
    }
}
