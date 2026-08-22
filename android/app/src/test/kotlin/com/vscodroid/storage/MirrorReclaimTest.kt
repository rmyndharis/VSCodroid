package com.vscodroid.storage

import android.content.ContentResolver
import android.content.Context
import android.content.SharedPreferences
import android.content.UriPermission
import android.net.Uri
import com.vscodroid.util.Logger
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files

/**
 * Removing the local copy of a device folder, at the user's own instruction.
 *
 * This is the only path in the app that deletes a mirror the automatic pass has refused,
 * so it is the only path that can destroy the user's only copy of something. Four
 * separate defects produced the gate the automatic pass applies, and none of them is
 * relaxed here: the gate is re-asked at the moment of removal, and getting past it
 * requires [force], which the caller may only set after a modal that says what is at
 * stake. What this file pins is that the gate still binds without [force], and that the
 * mechanics of the delete itself do not reach outside the mirror.
 */
class MirrorReclaimTest {

    @TempDir
    lateinit var filesDir: File

    private lateinit var manager: SafStorageManager
    private lateinit var resolver: ContentResolver
    private lateinit var context: Context
    private lateinit var mirrorsDir: File
    private var recentJson = "[]"

    private val folderUriText = "content://tree/primary%3AProject"
    private val folderUri = mockk<Uri>()

    @BeforeEach
    fun setUp() {
        mockkObject(Logger)
        every { Logger.i(any(), any()) } just Runs
        every { Logger.d(any(), any()) } just Runs
        every { Logger.w(any(), any()) } just Runs
        every { Logger.e(any(), any()) } just Runs
        every { Logger.e(any(), any(), any()) } just Runs

        every { folderUri.toString() } returns folderUriText
        every { folderUri.lastPathSegment } returns "primary:Project"

        resolver = mockk(relaxed = true)
        context = mockk<Context>(relaxed = true)
        every { context.filesDir } returns filesDir
        // The manager unwraps whatever it is given to the application context, so a
        // relaxed mock that answers a different object for it hands the manager a
        // filesDir that is not the one below.
        every { context.applicationContext } returns context
        every { context.contentResolver } returns resolver
        every { context.getSharedPreferences(any(), any()) } returns fakePrefs()
        // The recent list is stored as JSON and read back through Uri.parse, so a
        // fixture that writes a list has to hand the same mock back: the name and
        // the mirror both resolve from the URI string.
        mockkStatic(Uri::class)
        every { Uri.parse(folderUriText) } returns folderUri
        every { resolver.persistedUriPermissions } returns emptyList()

        manager = SafStorageManager(context)
        mirrorsDir = File(filesDir, "saf-mirrors").apply { mkdirs() }
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    private fun fakePrefs(): SharedPreferences {
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

    private fun permissionFor(granted: Uri): UriPermission = mockk<UriPermission> {
        every { uri } returns granted
        every { isReadPermission } returns true
    }

    /** A mirror whose every file the record vouches for, plus the record beside it. */
    private fun vouchedMirror(): Pair<File, File> {
        val dir = manager.getMirrorDir(folderUri).apply { mkdirs() }
        File(dir, "src").mkdirs()
        val file = File(dir, "src/main.kt").apply { writeText("fun main() {}") }
        val record = File(dir.path + SafSyncEngine.SYNCED_RECORD_SUFFIX).apply {
            writeText(
                SafSyncEngine.RECORD_HEADER + "\n" +
                    SafSyncEngine(context).identityLine("src/main.kt", file)
            )
        }
        check(SafSyncEngine(context).holdsOnlyVouchedCopies(dir)) {
            "the fixture wrote a record the engine does not accept, so every case built " +
                "on it would fail for the wrong reason"
        }
        return dir to record
    }

    /** Runs the deferred half, which a live caller hands to a thread it does not wait on. */
    private fun sweep() = manager.sweepDiscardedMirrors()

    /**
     * The gate the automatic pass applies, applied here too. Without [force] a mirror
     * holding files the device folder does not is left exactly where it was, because
     * removing it is not reclaiming a copy, it is deleting the only copy.
     */
    @Test
    fun `a copy the gate refuses is not removed without force`() {
        val (dir, record) = vouchedMirror()
        File(dir, ".git").mkdirs()
        File(dir, ".git/HEAD").writeText("ref: refs/heads/main\n")

        val answer = manager.reclaimMirror(dir.name, force = false)
        sweep()

        assertEquals(SafStorageManager.RECLAIM_REFUSED, answer)
        assertTrue(dir.isDirectory, "a repository cloned in the terminal was deleted")
        assertTrue(File(dir, ".git/HEAD").isFile, "and its unpushed state with it")
        assertTrue(record.isFile, "the record must not go without its mirror")
    }

    /** The same for the other half of the gate: a write this app never delivered. */
    @Test
    fun `a copy holding an undelivered write is not removed without force`() {
        val (dir, _) = vouchedMirror()
        val stranded = File(dir, "src/main.kt").absolutePath
        File(filesDir, SafSyncEngine.UPLOADS_IN_FLIGHT_FILE).writeText(stranded + "\n")

        val answer = manager.reclaimMirror(dir.name, force = false)
        sweep()

        assertEquals(SafStorageManager.RECLAIM_REFUSED, answer)
        assertTrue(File(dir, "src/main.kt").isFile, "the only copy of an unsent edit went")
    }

    /** The control for both refusals: the same mirror with nothing unvouched in it. */
    @Test
    fun `a copy the device folder fully holds is removed without force`() {
        val (dir, record) = vouchedMirror()

        val freed = manager.reclaimMirror(dir.name, force = false)
        sweep()

        assertTrue(freed > 0, "the answer is the bytes the removal frees, got $freed")
        assertFalse(dir.exists(), "a disposable copy was kept")
        assertFalse(record.exists(), "the record must go with the mirror it describes")
    }

    /** What the confirmation buys, and the only thing that gets past the gate. */
    @Test
    fun `force removes a copy the gate refuses`() {
        val (dir, record) = vouchedMirror()
        File(dir, ".git").mkdirs()
        File(dir, ".git/HEAD").writeText("ref: refs/heads/main\n")

        val freed = manager.reclaimMirror(dir.name, force = true)
        sweep()

        assertTrue(freed > 0)
        assertFalse(dir.exists())
        assertFalse(record.exists())
    }

    /**
     * The rename is the commit point of the removal, and the reason is in
     * [SafStorageManager.reclaimRevokedMirrorsSync]: a delete in place rests on nothing
     * else touching the directory while the walk is inside it, and a re-grant during the
     * walk re-creates the same hash directory under a live watcher, whose remaining
     * deletes then go out to the device as deletions of the user's real documents.
     *
     * Asserted as the state the call returns in, which is the property the caller
     * depends on: the mirror is unreachable by the time the answer arrives, and the
     * recursive delete happens afterwards.
     */
    @Test
    fun `the copy is unreachable as soon as the call returns, before it is deleted`() {
        val (dir, record) = vouchedMirror()

        manager.reclaimMirror(dir.name, force = true)

        assertFalse(dir.exists(), "the mirror was still reachable under its own name")
        assertFalse(record.exists(), "the record was still reachable under its own name")
        val setAside = mirrorsDir.listFiles()!!.map { it.name }
        assertEquals(
            setOf(
                SafStorageManager.DISCARD_PREFIX + dir.name,
                SafStorageManager.DISCARD_PREFIX + record.name,
            ),
            setAside.toSet(),
            "both entries must be set aside together, or the pair can never be resolved",
        )

        assertEquals(2, sweep(), "the sweep is what actually deletes them")
        assertTrue(mirrorsDir.listFiles()!!.isEmpty())
    }

    /**
     * `File.deleteRecursively` asks `isDirectory` and `listFiles`, both of which answer
     * for a link's target, so it descends out of the mirror. On the automatic pass that
     * is masked by the gate refusing any mirror containing a link at all. A forced
     * removal has no such mask, and a mirror is routinely a checked-out repository, so a
     * link inside one is attacker-supplied in the ordinary case.
     */
    @Test
    fun `a forced removal does not follow a link out of the copy`() {
        val (dir, _) = vouchedMirror()
        val outside = File(filesDir, "outside").apply { mkdirs() }
        val keep = File(outside, "keep.txt").apply { writeText("the user's own files") }
        Files.createSymbolicLink(File(dir, "link").toPath(), outside.toPath())

        manager.reclaimMirror(dir.name, force = true)
        sweep()

        assertTrue(keep.isFile, "the removal deleted a directory outside the mirror")
        assertTrue(outside.isDirectory)
        assertFalse(dir.exists(), "and the mirror itself should still have gone")
    }

    /**
     * The journal keys on the mirror's real path, which is the name before the rename.
     * Clearing under the `discarded-` path matched no entry, so records outlived the
     * mirror they distrust: a re-grant of the same folder hashes back to the same path,
     * and the next sync then read the device's own document as this app's interrupted
     * upload and wrote the stale mirror back over it.
     */
    @Test
    fun `removing a copy clears the journal entries under it`() {
        val (dir, _) = vouchedMirror()
        val journal = File(filesDir, SafSyncEngine.UPLOADS_IN_FLIGHT_FILE)
        val mine = File(dir, "src/main.kt").absolutePath
        val foreign = File(mirrorsDir, "999888777666/notes.md").absolutePath
        journal.writeText(mine + "\n" + foreign + "\n")

        manager.reclaimMirror(dir.name, force = true)
        sweep()

        assertFalse(
            journal.readLines().contains(mine),
            "the record outlived the mirror it distrusts, so a re-grant of that folder " +
                "will overwrite the device copy from a stale mirror",
        )
        assertTrue(
            journal.readLines().contains(foreign),
            "an unrelated mirror's record was dropped by the same clear",
        )
    }

    /**
     * The grant and the recent-list entry go with the copy. A grant that survives makes
     * the launch pass treat the folder as live; an entry that survives is an Open Recent
     * row pointing at a directory that is not there, and choosing it starts a sync that
     * copies the whole folder down again, which is the opposite of what the user asked
     * for.
     */
    @Test
    fun `removing a copy releases its grant and drops it from the recent list`() {
        val (dir, _) = vouchedMirror()
        recentJson = """[{"uri":"$folderUri","name":"Project","lastOpened":1700000000}]"""
        every { resolver.persistedUriPermissions } returns listOf(permissionFor(folderUri))

        // The grant stays visible to the resolver for the whole case, deliberately.
        // Dropping it to an empty list once the removal has run answers the question
        // the assertion below asks: [getPersistedFolders] prunes every entry whose
        // permission is gone, so an empty answer would then be the fixture's doing and
        // the case would hold with the recent-list drop deleted from the manager.
        // Releasing the grant is a call on a relaxed mock and changes nothing the mock
        // reports, which is what leaves the row visible to be dropped on its merits.
        assertEquals(
            1, manager.getPersistedFolders().size,
            "the fixture never put the folder in Open Recent, so what follows is not " +
                "measuring the removal",
        )

        manager.reclaimMirror(dir.name, force = true)

        verify { resolver.releasePersistableUriPermission(folderUri, any()) }
        assertEquals(
            emptyList<SafFolderInfo>(), manager.getPersistedFolders(),
            "the folder still appears in Open Recent, pointing at a directory that is gone",
        )
    }

    @Test
    fun `a name that is not a mirror is refused rather than deleted`() {
        val scratch = File(mirrorsDir, "scratch").apply { mkdirs() }
        File(scratch, "wip.txt").writeText("mine")

        assertEquals(SafStorageManager.RECLAIM_UNKNOWN, manager.reclaimMirror("scratch", true))
        assertEquals(
            SafStorageManager.RECLAIM_UNKNOWN,
            manager.reclaimMirror("../../../databases", true),
            "a hash is page-supplied, so a name that escapes the mirrors root must not act",
        )
        sweep()

        assertTrue(File(scratch, "wip.txt").isFile, "a person's own directory was deleted")
    }

    /**
     * The record is half of a mirror, never a removal target of its own. Accepting one
     * would set the record aside while leaving the directory it describes behind, and
     * that mirror can then never be vouched for again: the evidence that would license
     * its removal is what the removal took away.
     *
     * Driven with the record's name worn by a DIRECTORY, which is the only shape where
     * the name is what refuses it. A real record is a file, so `isDirectory` turns it
     * away first and a case built on one would pass with the name check deleted. The
     * shape is reachable rather than contrived: `saf-mirrors` is exported into every
     * terminal as `SAF_MIRRORS_DIR`, so anything can be created there under any name.
     */
    @Test
    fun `an entry wearing the record's name cannot be removed as a folder`() {
        vouchedMirror()
        val impostor = File(mirrorsDir, "abc123def456" + SafSyncEngine.SYNCED_RECORD_SUFFIX)
        impostor.mkdirs()
        File(impostor, "inside.txt").writeText("not a mirror")

        assertEquals(
            SafStorageManager.RECLAIM_UNKNOWN,
            manager.reclaimMirror(impostor.name, force = true),
        )
        sweep()

        assertTrue(File(impostor, "inside.txt").isFile, "it was removed as though a folder")
    }

    @Test
    fun `a copy that is already gone is refused rather than reported as freed`() {
        assertEquals(
            SafStorageManager.RECLAIM_UNKNOWN,
            manager.reclaimMirror("abc123def456", force = true),
        )
    }

    /**
     * The sweep is the resumable half, so it has to finish what a killed process left
     * behind and has to leave everything else alone. That is the same rule the launch
     * pass applies, and the reason is the same: `saf-mirrors` holds files this app did
     * not write.
     */
    @Test
    fun `the sweep finishes earlier removals and touches nothing else`() {
        val leftover = File(mirrorsDir, SafStorageManager.DISCARD_PREFIX + "abc123def456")
        leftover.mkdirs()
        File(leftover, "stale.txt").writeText("already unreachable")
        val impostor = File(mirrorsDir, SafStorageManager.DISCARD_PREFIX + "my-notes")
        impostor.mkdirs()
        File(impostor, "mine.txt").writeText("named to look like ours")
        val (live, _) = vouchedMirror()

        assertEquals(1, sweep())

        assertFalse(leftover.exists(), "an interrupted removal was never finished")
        assertTrue(File(impostor, "mine.txt").isFile, "a person's own directory was deleted")
        assertTrue(live.isDirectory, "a live mirror was swept")
    }
}
