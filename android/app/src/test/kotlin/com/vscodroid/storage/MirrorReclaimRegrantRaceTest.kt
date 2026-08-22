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
 * A folder granted again while the launch-time reclaim pass is deciding about its mirror.
 *
 * The pass runs on a detached daemon thread started before anything is drawn, so it
 * outlives the splash screen: the user reaches `MainActivity`, picks the same device
 * folder out of the SAF picker, and the sync repopulates and starts watching the very
 * directory the pass is holding a verdict about. From that moment the rename the pass
 * performs is a rename of a live mirror out from under a running watcher, and every
 * `FileObserver.DELETE` the recursive delete then raises becomes a write-back that
 * deletes the user's real document on the device.
 *
 * What keeps that shut is asking who owns the directory next to the rename rather than
 * at the top of the loop, because a rename is atomic and a folder granted after it gets a
 * fresh directory the pass cannot reach. The vouching walk sits between the two now, and
 * it is a full traversal of the mirror: on a project tree that is the difference between
 * a window of microseconds and one of seconds.
 *
 * The re-grant is expressed as the permission set answering differently on a later read,
 * which is exactly what a grant taken meanwhile looks like from here, and needs no
 * threads to arrange.
 */
class MirrorReclaimRegrantRaceTest {

    @TempDir
    lateinit var filesDir: File

    private lateinit var manager: SafStorageManager
    private lateinit var resolver: ContentResolver
    private lateinit var context: Context
    private lateinit var mirrorsDir: File
    private lateinit var mirror: File
    private lateinit var record: File

    private val folderUri = mockk<Uri>()

    @BeforeEach
    fun setUp() {
        mockkObject(Logger)
        every { Logger.i(any(), any()) } just Runs
        every { Logger.d(any(), any()) } just Runs
        every { Logger.w(any(), any()) } just Runs
        every { Logger.w(any(), any(), any()) } just Runs
        every { Logger.e(any(), any()) } just Runs
        every { Logger.e(any(), any(), any()) } just Runs

        every { folderUri.toString() } returns "content://tree/primary%3AClientProject"

        resolver = mockk(relaxed = true)
        context = mockk<Context>(relaxed = true)
        every { context.filesDir } returns filesDir
        every { context.contentResolver } returns resolver

        manager = SafStorageManager(context)
        mirrorsDir = File(filesDir, "saf-mirrors").apply { mkdirs() }

        // A mirror the pass would otherwise remove: nothing in it that the record beside
        // it cannot vouch for, so the walk runs to the end and answers yes. That is the
        // slowest case and the only one that reaches the rename, which is why it is the
        // case the window matters in.
        val hash = manager.getMirrorDir(folderUri).name
        mirror = File(mirrorsDir, hash).apply { mkdirs() }
        val copied = File(mirror, "notes.md").apply { writeText("copied from the device") }
        record = File(mirrorsDir, hash + SafSyncEngine.SYNCED_RECORD_SUFFIX)
        record.writeText(
            listOf(
                SafSyncEngine.RECORD_HEADER,
                SafSyncEngine(context).identityLine("notes.md", copied),
            ).joinToString("\n")
        )
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    private fun permissionFor(granted: Uri): UriPermission = mockk<UriPermission> {
        every { uri } returns granted
    }

    /** Nobody holds a grant for this folder, and nobody takes one. */
    private fun neverGranted() {
        every { resolver.persistedUriPermissions } returns emptyList()
    }

    /**
     * Nobody holds a grant when the pass first looks, and somebody does by the time it
     * looks again. The read that flips is the first one, so whichever of the mirror's two
     * entries `listFiles` hands over first is the one that got as far as its verdict.
     */
    private fun grantedAfterTheFirstLook() {
        var looks = 0
        every { resolver.persistedUriPermissions } answers {
            if (looks++ == 0) emptyList() else listOf(permissionFor(folderUri))
        }
    }

    @Test
    fun `a mirror re-granted while the pass runs is left alone`() {
        grantedAfterTheFirstLook()

        val removed = manager.reclaimRevokedMirrorsSync()

        assertEquals(0, removed, "the pass removed a mirror whose folder had been re-granted")
        assertTrue(
            mirror.isDirectory,
            "the mirror the editor had just been given was renamed away underneath it; " +
                "the watcher is live on it, so the delete that follows is replayed onto " +
                "the user's documents on the device",
        )
        assertTrue(
            record.isFile,
            "the live mirror's sync record was removed, so nothing can vouch for it again",
        )
        assertFalse(
            File(mirrorsDir, SafStorageManager.DISCARD_PREFIX + mirror.name).exists(),
            "the live mirror was set aside, which is the commit point of its removal",
        )
    }

    /**
     * The control, and it is the one that stops the case above passing because the pass
     * stopped removing anything. With no grant appearing, the same mirror goes.
     */
    @Test
    fun `a mirror whose folder is never granted again is still removed`() {
        neverGranted()

        val removed = manager.reclaimRevokedMirrorsSync()

        assertEquals(2, removed, "the mirror and its record should both have gone")
        assertFalse(mirror.exists(), "the orphan mirror survived")
        assertFalse(record.exists(), "the orphan mirror's record survived")
    }
}
