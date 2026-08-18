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
    /**
     * The journal is keyed on the mirror's real path, and the pass renames the
     * mirror before deleting it.
     *
     * Clearing under the renamed path matched nothing, so records naming files
     * inside a deleted mirror survived for ever. They are not inert: a re-grant
     * of the same folder produces the same digest and so the same path, and the
     * next sync reads the device's own document as this app's interrupted upload,
     * keeping the stale mirror and writing it back over the device copy. That is
     * the damage the record exists to prevent, caused by the record.
     *
     * Driven through the set-aside branch, which is where the clearing is
     * actually reachable. On the ordinary branch a mirror with any record under
     * it is now kept rather than deleted, so nothing reaches the clear; a
     * `discarded-` entry is an earlier pass's leftover whose records still name
     * the original path.
     */
    @Test
    fun `records naming a set-aside mirror are cleared under its real path`() {
        val hash = "abc123def456"
        val original = File(mirrorsDir, hash)
        val setAside = File(mirrorsDir, SafStorageManager.DISCARD_PREFIX + hash)
        setAside.mkdirs()
        File(setAside, "notes.md").writeText("left over")
        val journal = File(filesDir, SafSyncEngine.UPLOADS_IN_FLIGHT_FILE)
        val stale = File(original, "notes.md").absolutePath
        val unrelated = File(filesDir, "elsewhere/keep.txt").absolutePath
        journal.writeText(stale + "\n" + unrelated + "\n")
        every { resolver.persistedUriPermissions } returns emptyList()

        manager.reclaimRevokedMirrorsSync()

        assertFalse(setAside.exists(), "the set-aside leftover should have gone")
        assertFalse(
            journal.readLines().contains(stale),
            "the record outlived the mirror it distrusts, so a re-grant of that " +
                "folder will overwrite the device copy from a stale mirror",
        )
        assertTrue(
            journal.readLines().contains(unrelated),
            "an unrelated record was dropped by the same clear",
        )
    }

    /**
     * The mirror is a copy and the device folder is the original, which is what
     * makes reclaiming safe. It stops being a copy when a write-back gave up, or
     * was refused with a SecurityException, which is exactly what a permission
     * withdrawn mid-session produces, and is also what puts the mirror in front
     * of this pass. The two arrive together.
     */
    @Test
    fun `a mirror holding a write that never reached the device is kept`() {
        val (staleDir, _) = mirrorFor(revokedUri)
        mirrorFor(liveUri)
        val journal = File(filesDir, SafSyncEngine.UPLOADS_IN_FLIGHT_FILE)
        journal.writeText(File(staleDir, "src/main.kt").absolutePath + "\n")
        every { resolver.persistedUriPermissions } returns listOf(permissionFor(liveUri))

        val removed = manager.reclaimRevokedMirrorsSync()

        assertTrue(
            staleDir.isDirectory,
            "the only copy of an unsynced edit was deleted on a launch-time thread",
        )
        assertEquals(0, removed, "nothing should have been reclaimed in this pass")
    }

    /** The control: without a record, the same mirror is reclaimed as before. */
    @Test
    fun `the same mirror with nothing stranded is still reclaimed`() {
        val (staleDir, _) = mirrorFor(revokedUri)
        mirrorFor(liveUri)
        every { resolver.persistedUriPermissions } returns listOf(permissionFor(liveUri))

        val removed = manager.reclaimRevokedMirrorsSync()

        assertFalse(staleDir.exists(), "unreachable disk was kept for no reason")
        assertTrue(removed > 0)
    }

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
    fun `a file a person left beside the mirrors is not touched`() {
        // saf-mirrors is not private scratch space: first-run setup exports it into every
        // terminal as SAF_MIRRORS_DIR, and the WebView publishes it as a resource root.
        // Deleting whatever is not a live mirror there reaches a person's own files, and
        // it is a widening -- the version this replaced only ever deleted mirrors the
        // recent list named. The stale mirror is the control: without it, "the file
        // survived" is also what a pass that did nothing produces.
        val (staleDir, _) = mirrorFor(revokedUri)
        val note = File(mirrorsDir, "notes.md").apply { writeText("mine") }
        val scratch = File(mirrorsDir, "scratch").apply { mkdirs() }
        File(scratch, "wip.txt").writeText("also mine")
        every { resolver.persistedUriPermissions } returns emptyList()

        manager.reclaimRevokedMirrorsSync()

        assertFalse(staleDir.exists(), "the stale mirror should still have gone")
        assertTrue(note.isFile, "a file this app did not create was deleted")
        assertTrue(File(scratch, "wip.txt").isFile, "a directory this app did not create was deleted")
    }

    @Test
    fun `the entry pattern matches the names getMirrorDir actually produces`() {
        // The pattern spells out twelve hex characters; the length is decided in
        // Environment.getSafMirrorDir. Drift in either direction is silent and goes both
        // ways -- the pass stops recognising its own mirrors, or starts recognising
        // something it never wrote.
        val mirrorName = manager.getMirrorDir(revokedUri).name

        assertTrue(
            SafStorageManager.MIRROR_ENTRY.matches(mirrorName),
            "the pass no longer recognises a mirror it created: $mirrorName"
        )
        assertTrue(
            SafStorageManager.MIRROR_ENTRY.matches(mirrorName + SafSyncEngine.SYNCED_RECORD_SUFFIX),
            "the pass no longer recognises the record beside a mirror"
        )
    }

    @Test
    fun `an entry left behind by an interrupted pass is finished off`() {
        // A candidate is renamed out of the way before it is deleted, so that a folder
        // re-granted mid-pass gets a fresh directory the walk cannot reach. That leaves a
        // name behind if the process dies in between. Nothing else creates one, so it is
        // reclaimed without consulting the permission list at all.
        val leftover = File(mirrorsDir, SafStorageManager.DISCARD_PREFIX + "abc123abc123")
            .apply { mkdirs() }
        File(leftover, "stale.txt").writeText("orphan")
        every { resolver.persistedUriPermissions } returns emptyList()

        assertEquals(1, manager.reclaimRevokedMirrorsSync())
        assertFalse(leftover.exists(), "a set-aside entry was left to accumulate")
    }

    @Test
    fun `the dispatch actually runs the pass`() {
        // reclaimRevokedMirrors() hands the work to a thread and returns, so "the
        // dispatch never calls the body" is a mutation every other case here survives.
        val (staleDir, _) = mirrorFor(revokedUri)
        every { resolver.persistedUriPermissions } returns emptyList()

        manager.reclaimRevokedMirrors()

        assertTrue(awaitTrue { !staleDir.exists() }, "the background pass never ran")
    }

    private fun awaitTrue(timeoutMs: Long = 5_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(20)
        }
        return condition()
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
