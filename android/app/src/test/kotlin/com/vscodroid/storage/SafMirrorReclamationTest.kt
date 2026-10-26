package com.vscodroid.storage

import android.content.ContentResolver
import android.content.Context
import android.content.SharedPreferences
import android.content.UriPermission
import android.net.Uri
import com.vscodroid.util.Logger
import com.vscodroid.util.StorageManager
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.unmockkObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.security.MessageDigest

/**
 * Automatic cleanup retains ordinary copies and their metadata, finishes committed
 * removals, and removes orphan records. Positive deletion controls ensure that a
 * retained copy cannot pass solely because cleanup never ran.
 */
class SafMirrorReclamationTest {

    @TempDir
    lateinit var filesDir: File

    private lateinit var manager: SafStorageManager
    private lateinit var resolver: ContentResolver
    private lateinit var context: Context
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
        context = mockk<Context>(relaxed = true)
        every { context.filesDir } returns filesDir
        // The manager unwraps whatever it is given to the application context, so a
        // relaxed mock that answers a different object for it hands the manager a
        // filesDir that is not the one below.
        every { context.applicationContext } returns context
        every { context.contentResolver } returns resolver
        val prefs = mockk<SharedPreferences>(relaxed = true)
        every { prefs.getString(any(), any()) } returns "[]"
        every { context.getSharedPreferences(any(), any()) } returns prefs

        manager = SafStorageManager(context)
        mirrorsDir = File(filesDir, "saf-mirrors").apply { mkdirs() }
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    /** A permission the system still reports as held for [granted]. */
    private fun permissionFor(granted: Uri): UriPermission = mockk<UriPermission> {
        every { uri } returns granted
    }

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
     * The case the two paths cannot be told apart in, and the reason the records
     * move with the directory rather than being cleared by the name the mirror had.
     *
     * A directory name is a digest of the tree URI, not of the session, so the path a
     * removal frees is the path the same folder returns to the moment the user grants
     * it again. The sweep runs on a thread that outlives the removal and takes as long
     * as the tree is big, so a re-grant landing inside that window is ordinary rather
     * than contrived. If the sweep then retires records by the old name, it retires the
     * new mirror's, and those are the records that stop the next sync copying a
     * truncated device document over the only complete copy.
     */
    @Test
    fun `a re-granted mirror keeps its records when the old one is swept`() {
        val hash = "abc123def456"
        val original = File(mirrorsDir, hash)
        val setAside = File(mirrorsDir, SafStorageManager.DISCARD_PREFIX + hash)
        setAside.mkdirs()
        File(setAside, "old.md").writeText("the copy being removed")
        // The re-grant: the same folder, so the same digest, so the same path, live
        // again while the sweep of its predecessor is still to run.
        original.mkdirs()
        File(original, "fresh.kt").writeText("written since the re-grant")

        val journal = File(filesDir, SafSyncEngine.UPLOADS_IN_FLIGHT_FILE)
        val departing = File(setAside, "old.md").absolutePath
        val live = File(original, "fresh.kt").absolutePath
        journal.writeText(departing + "\n" + live + "\n")
        every { resolver.persistedUriPermissions } returns emptyList()

        manager.reclaimRevokedMirrorsSync()

        assertFalse(setAside.exists(), "the set-aside leftover should have gone")
        assertFalse(
            journal.readLines().contains(departing),
            "the departing mirror's own record outlived it",
        )
        assertTrue(
            journal.readLines().contains(live),
            "the sweep took the live mirror's record with it, so the next sync will " +
                "trust a device copy this app already knows is not current",
        )
        assertTrue(original.isDirectory, "the re-granted mirror was deleted by the sweep")
    }

    /**
     * A refused write may leave the mirror holding the only complete edit. Revoked
     * permission cannot authorize cleanup to discard that local copy.
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

    /** A matching snapshot still requires consent to remove the ordinary copy. */
    @Test
    fun `the same mirror with nothing stranded is retained`() {
        val (staleDir, record) = mirrorFor(revokedUri)
        val savedRecord = record.readBytes()
        mirrorFor(liveUri)
        every { resolver.persistedUriPermissions } returns listOf(permissionFor(liveUri))

        val removed = manager.reclaimRevokedMirrorsSync()

        assertEquals("fun main() {}", File(staleDir, "src/main.kt").readText())
        org.junit.jupiter.api.Assertions.assertArrayEquals(savedRecord, record.readBytes())
        assertEquals(0, removed)
    }

    @Test
    fun `an unrecorded workspace file with the partial suffix keeps its mirror`() {
        val (staleDir, record) = mirrorFor(revokedUri)
        mirrorFor(liveUri)
        val originalRecord = record.readText()
        val local = File(staleDir, "src/notes.txt" + SafSyncEngine.PARTIAL_SUFFIX)
        val uniqueBytes = "workspace bytes absent from the device folder"
        local.writeText(uniqueBytes)
        every { resolver.persistedUriPermissions } returns listOf(permissionFor(liveUri))

        val removed = manager.reclaimRevokedMirrorsSync()

        assertTrue(staleDir.isDirectory, "the suffix was treated as proof of a disposable copy")
        assertEquals(uniqueBytes, local.readText())
        assertEquals(originalRecord, record.readText())
        assertEquals(0, removed)
    }

    @Test
    fun `a recorded file with the partial suffix remains in its ordinary copy`() {
        val (staleDir, record) = mirrorFor(revokedUri)
        mirrorFor(liveUri)
        val local = File(staleDir, "src/notes.txt" + SafSyncEngine.PARTIAL_SUFFIX)
            .apply { writeText("a copy the device already holds") }
        record.appendText(
            "\n" + SafSyncEngine.recordLine(
                SafSyncEngine(context).identityLine("src/" + local.name, local),
                MessageDigest.getInstance("SHA-256").digest(local.readBytes()),
            )
        )
        every { resolver.persistedUriPermissions } returns listOf(permissionFor(liveUri))
        val savedRecord = record.readBytes()
        assertTrue(SafSyncEngine(context).holdsOnlyVouchedCopies(staleDir))

        val removed = manager.reclaimRevokedMirrorsSync()

        assertEquals("a copy the device already holds", local.readText())
        org.junit.jupiter.api.Assertions.assertArrayEquals(savedRecord, record.readBytes())
        assertEquals(0, removed)
    }

    /**
     * `.tmp` and `~` are temporary to the writer that made them and nothing else, and
     * the write-back declines both names, so a file a terminal left in the mirror under
     * one is on no device folder anywhere. It is the user's only copy, and reclaiming
     * over it would lose data absent from the device folder.
     */
    @Test
    fun `a mirror holding a temporary file of the user's own is kept`() {
        val (staleDir, _) = mirrorFor(revokedUri)
        mirrorFor(liveUri)
        File(staleDir, "src/notes.tmp").writeText("half an hour of typing, on this device only")
        every { resolver.persistedUriPermissions } returns listOf(permissionFor(liveUri))

        val removed = manager.reclaimRevokedMirrorsSync()

        assertTrue(
            staleDir.isDirectory,
            "a file that exists nowhere but the mirror was deleted on a launch-time thread",
        )
        assertEquals(0, removed, "nothing should have been reclaimed in this pass")
    }

    /**
     * The record's own scratch file outlives everything else when a write is
     * interrupted between it and the rename.
     *
     * `MIRROR_ENTRY` does not match its name, which is what keeps the pass from
     * treating it as half of a mirror and is also why nothing ever removed it: once the
     * mirror and record it belonged to had gone, it sat in `saf-mirrors` for the life
     * of the install, in a directory every terminal is handed as SAF_MIRRORS_DIR.
     */
    @Test
    fun `a record's scratch file remains while its ordinary mirror exists`() {
        val (staleDir, _) = mirrorFor(revokedUri)
        val (liveDir, _) = mirrorFor(liveUri)
        val orphan = File(
            mirrorsDir,
            staleDir.name + SafSyncEngine.SYNCED_RECORD_SUFFIX + SafSyncEngine.PARTIAL_SUFFIX,
        ).apply { writeText(SafSyncEngine.RECORD_HEADER) }
        // The same file beside a folder the user still grants belongs to a record that
        // is still being kept up to date, and the next sync of that folder writes over
        // it. Removing it would be removing a live write's destination.
        val inUse = File(
            mirrorsDir,
            liveDir.name + SafSyncEngine.SYNCED_RECORD_SUFFIX + SafSyncEngine.PARTIAL_SUFFIX,
        ).apply { writeText(SafSyncEngine.RECORD_HEADER) }
        every { resolver.persistedUriPermissions } returns listOf(permissionFor(liveUri))

        manager.reclaimRevokedMirrorsSync()

        assertTrue(orphan.isFile, "a retained ordinary copy lost its scratch record")
        assertTrue(inUse.isFile, "a live folder's own scratch file was taken with it")
    }

    /**
     * Builds the mirror directory and the sync record the engine keeps beside it,
     * which is what the pair it returns holds.
     *
     * Every file matches a delivered snapshot, in the format the engine writes.
     *
     * The identity is read back off the disk rather than predicted, for the reason
     * that function gives: a filesystem that truncates the timestamp would otherwise
     * put the fixture out of step with what the walk sees, and the test would pass or
     * fail on the host's filesystem rather than on the code.
     */
    private fun mirrorFor(uri: Uri): Pair<File, File> {
        val dir = manager.getMirrorDir(uri).apply { mkdirs() }
        File(dir, "src").mkdirs()
        val file = File(dir, "src/main.kt").apply { writeText("fun main() {}") }
        val record = File(dir.path + SafSyncEngine.SYNCED_RECORD_SUFFIX).apply {
            writeText(
                SafSyncEngine.RECORD_HEADER + "\n" +
                    SafSyncEngine.recordLine(
                        SafSyncEngine(context).identityLine("src/main.kt", file),
                        MessageDigest.getInstance("SHA-256").digest(file.readBytes()),
                    )
            )
        }
        // The fixture's own precondition, asserted rather than assumed. Composing the
        // record by hand made this a second reader of the same bytes, and the two
        // disagreed on a Linux runner while agreeing on macOS -- which surfaced as four
        // unrelated-looking behaviour failures rather than as "the fixture is wrong".
        check(SafSyncEngine(context).holdsOnlyVouchedCopies(dir)) {
            "the fixture wrote a record the engine does not accept, so every case built " +
                "on it would fail for the wrong reason"
        }
        return dir to record
    }

    /** Both actual enumeration orders must preserve the directory and its record. */
    @Test
    fun `an ordinary pair stays intact in either enumeration order`() {
        val (staleDir, staleRecord) = mirrorFor(revokedUri)
        val savedRecord = staleRecord.readBytes()
        every { resolver.persistedUriPermissions } returns emptyList()
        for (recordFirst in listOf(false, true)) {
            assertEquals(0, manager.reclaimRevokedMirrorsSync { root ->
                val entries = root.listFiles() ?: error("the actual mirrors directory was unreadable")
                assertEquals(setOf(staleDir.name, staleRecord.name), entries.map { it.name }.toSet())
                entries.sortedBy {
                    if (it.name.endsWith(SafSyncEngine.SYNCED_RECORD_SUFFIX) == recordFirst) 0 else 1
                }.toTypedArray()
            })
            assertEquals("fun main() {}", File(staleDir, "src/main.kt").readText())
            org.junit.jupiter.api.Assertions.assertArrayEquals(savedRecord, staleRecord.readBytes())
        }
    }

    /**
     * A file the record cannot vouch for is the user's only copy, and the pass has to
     * keep the mirror around it.
     *
     * `.git` is the case that matters and the reason the journal alone was not enough:
     * it is in [SafSyncEngine.SKIP_DIRECTORIES], so no write-back is ever queued for
     * anything inside it, so it can never appear in the upload journal however long the
     * user works. A repository cloned in the terminal lives only here.
     */
    @Test
    fun `a mirror holding a file no sync vouched for is kept`() {
        val (staleDir, _) = mirrorFor(revokedUri)
        mirrorFor(liveUri)
        File(staleDir, ".git").mkdirs()
        File(staleDir, ".git/HEAD").writeText("ref: refs/heads/main\n")
        every { resolver.persistedUriPermissions } returns listOf(permissionFor(liveUri))

        val removed = manager.reclaimRevokedMirrorsSync()

        assertTrue(
            staleDir.isDirectory,
            "a repository cloned in the terminal was deleted with its unpushed commits",
        )
        assertEquals(0, removed, "nothing should have been reclaimed in this pass")
    }

    /**
     * The same shape one step subtler: the file IS recorded, but is no longer the bytes
     * recorded for it. That is an edit the device never received, and it is the rule
     * [SafSyncEngine.reconcileDeletions] already applies to a single file, applied here
     * to the whole mirror.
     */
    @Test
    fun `a mirror whose recorded file no longer matches is kept`() {
        val (staleDir, _) = mirrorFor(revokedUri)
        mirrorFor(liveUri)
        File(staleDir, "src/main.kt").writeText("fun main() { edited() }")
        every { resolver.persistedUriPermissions } returns listOf(permissionFor(liveUri))

        val removed = manager.reclaimRevokedMirrorsSync()

        assertTrue(staleDir.isDirectory, "an edit the device never received was deleted")
        assertEquals(0, removed, "nothing should have been reclaimed in this pass")
    }

    /**
     * A directory without any sync record still belongs to the user and is retained.
     */
    @Test
    fun `a mirror with no record at all is kept`() {
        val bare = manager.getMirrorDir(revokedUri).apply { mkdirs() }
        File(bare, "notes.md").writeText("local only")
        mirrorFor(liveUri)
        every { resolver.persistedUriPermissions } returns listOf(permissionFor(liveUri))

        val removed = manager.reclaimRevokedMirrorsSync()

        assertTrue(bare.isDirectory, "a mirror nothing vouches for was deleted anyway")
        assertEquals(0, removed)
    }

    @Test
    fun `revoked and granted ordinary copies stay while orphan metadata goes`() {
        val (staleDir, staleRecord) = mirrorFor(revokedUri)
        val (liveDir, liveRecord) = mirrorFor(liveUri)
        every { resolver.persistedUriPermissions } returns listOf(permissionFor(liveUri))
        val orphan = File(mirrorsDir, "0123456789ab" + SafSyncEngine.SYNCED_RECORD_SUFFIX)
            .apply { writeText(SafSyncEngine.RECORD_HEADER) }
        val staleBytes = staleRecord.readBytes()

        val removed = manager.reclaimRevokedMirrorsSync()

        assertEquals("fun main() {}", File(staleDir, "src/main.kt").readText())
        org.junit.jupiter.api.Assertions.assertArrayEquals(staleBytes, staleRecord.readBytes())
        assertTrue(liveDir.isDirectory, "the granted folder's mirror must survive")
        assertTrue(
            File(liveDir, "src/main.kt").isFile,
            "surviving means its contents too, not just the directory"
        )
        assertTrue(liveRecord.isFile, "the surviving mirror keeps the record it needs")
        assertFalse(orphan.exists(), "the orphan record must still be removed")
        assertEquals(1, removed)
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
        // recent list named. An orphan record proves cleanup actually ran.
        val (staleDir, _) = mirrorFor(revokedUri)
        val note = File(mirrorsDir, "notes.md").apply { writeText("mine") }
        val scratch = File(mirrorsDir, "scratch").apply { mkdirs() }
        File(scratch, "wip.txt").writeText("also mine")
        every { resolver.persistedUriPermissions } returns emptyList()
        val orphan = File(mirrorsDir, "0123456789ab" + SafSyncEngine.SYNCED_RECORD_SUFFIX)
            .apply { writeText(SafSyncEngine.RECORD_HEADER) }

        manager.reclaimRevokedMirrorsSync()

        assertTrue(staleDir.isDirectory, "the ordinary copy must remain")
        assertFalse(orphan.exists(), "the orphan record should still have gone")
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
    fun `a committed directory whose deletion fails is retried by a fresh manager`() {
        val (dir, _) = mirrorFor(revokedUri)
        every { resolver.persistedUriPermissions } returns emptyList()
        assertTrue(manager.reclaimMirror(dir.name, force = true) >= 0)
        val committed = File(mirrorsDir, SafStorageManager.DISCARD_PREFIX + dir.name)
        assertEquals("fun main() {}", File(committed, "src/main.kt").readText())
        mockkObject(StorageManager)
        every { StorageManager.deleteRecursive(any()) } answers {
            if (firstArg<File>().absolutePath == committed.absolutePath) 0L else callOriginal()
        }
        try {
            assertEquals(1, manager.reclaimRevokedMirrorsSync(), "only the committed record was deleted")
            assertEquals("fun main() {}", File(committed, "src/main.kt").readText())
        } finally {
            unmockkObject(StorageManager)
        }

        assertEquals(1, SafStorageManager(context).reclaimRevokedMirrorsSync())
        assertFalse(committed.exists())
    }

    @Test
    fun `the dispatch actually runs the pass`() {
        // reclaimRevokedMirrors() hands the work to a thread and returns, so "the
        // dispatch never calls the body" is a mutation every other case here survives.
        val (staleDir, _) = mirrorFor(revokedUri)
        every { resolver.persistedUriPermissions } returns emptyList()
        assertTrue(manager.reclaimMirror(staleDir.name, force = true) >= 0)
        val committed = File(mirrorsDir, SafStorageManager.DISCARD_PREFIX + staleDir.name)
        assertTrue(committed.isDirectory)

        manager.reclaimRevokedMirrors()

        assertTrue(awaitTrue { !committed.exists() }, "the background pass never ran")
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
