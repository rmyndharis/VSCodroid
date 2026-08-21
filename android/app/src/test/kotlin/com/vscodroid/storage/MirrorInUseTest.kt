package com.vscodroid.storage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Tests for [SafStorageManager.reclaimRefusal], the question asked before a mirror the
 * user has chosen to remove is touched at all.
 *
 * This is the half of the removal that the storage manager cannot answer. It knows
 * whether removing a mirror would lose anything ([SafStorageManager.mayReclaim] and
 * [SafSyncEngine.holdsOnlyVouchedCopies]); it does not know whether anything is still
 * using it, and that state lives in the Activity.
 *
 * Getting it wrong is not an inconvenience. A mirror the editor still has open is
 * covered by live observers, a `FileObserver.DELETE` becomes a DELETE write-back job,
 * and `deleteFromSaf` then replays the deletion onto the user's real documents through
 * the provider. A recursive delete of a watched mirror is a device-wide data loss event
 * that looks, from the watcher's side, exactly like a person deleting files.
 *
 * Each case asserts WHICH refusal comes back rather than only that one did. The four
 * checks overlap by design, so an outcome-only assertion would stay green with three of
 * them deleted: the widest one, [SafStorageManager.RECLAIM_FOLDER_THIS_SESSION], covers
 * every mirror the narrower ones name. Naming the sentence is what makes each check
 * independently load-bearing.
 */
class MirrorInUseTest {

    private val hash = "abc123def456"
    private val other = "999888777666"
    private val sep = File.separator
    private val root = "/data/user/0/com.vscodroid/files/saf-mirrors"

    private fun refusal(
        watched: String? = null,
        syncing: String? = null,
        open: String? = null,
        thisSession: Set<String> = emptySet(),
    ) = SafStorageManager.reclaimRefusal(hash, watched, syncing, open, thisSession)

    @Test
    fun `a mirror nothing is using may be removed`() {
        assertNull(
            refusal(watched = other, syncing = other, open = other, thisSession = setOf(other)),
            "every input names a different mirror, so nothing here is about this one",
        )
    }

    @Test
    fun `the mirror the watcher is on is refused as open`() {
        assertEquals(
            SafStorageManager.RECLAIM_FOLDER_OPEN,
            refusal(watched = hash, thisSession = setOf(hash)),
            "deleting a watched mirror replays every delete onto the device documents",
        )
    }

    /**
     * The mirror a sync is half way through is the one case where the files on disk are
     * not a copy of anything yet, so it gets its own sentence: the user's action is to
     * wait rather than to close something.
     */
    @Test
    fun `the mirror being synced is refused as still opening`() {
        assertEquals(
            SafStorageManager.RECLAIM_FOLDER_OPENING,
            refusal(syncing = hash, thisSession = setOf(hash)),
        )
    }

    /**
     * The workbench switches folders by navigating its own WebView, so it can be inside
     * a mirror that this activity has not adopted and has no watcher on. That is the
     * hole the watcher check alone leaves.
     */
    @Test
    fun `the mirror the workbench has open is refused as open`() {
        assertEquals(
            SafStorageManager.RECLAIM_FOLDER_OPEN,
            refusal(open = hash),
            "a folder the workbench opened without going through Kotlin was removable",
        )
    }

    /**
     * The one that has to survive every other check being deleted.
     *
     * `stopWatching` waits two seconds for the write-back worker and then leaves it
     * running rather than discarding writes the user is expecting on the device. So a
     * closed folder can still have a thread streaming out of its mirror, and each write
     * opens the device document with `"wt"`, which truncates at open: deleting the
     * mirror underneath that thread empties the device file rather than leaving it
     * alone. A drain only ever touches the mirror it was started for, which is why a
     * set of names is the whole instrument.
     */
    @Test
    fun `a mirror watched earlier in this session needs a restart`() {
        assertEquals(
            SafStorageManager.RECLAIM_FOLDER_THIS_SESSION,
            refusal(thisSession = setOf(other, hash)),
            "a write-back drain outliving its folder could still be inside this mirror",
        )
    }

    @Test
    fun `the mirror name is read out of the path the workbench has open`() {
        assertEquals(hash, SafStorageManager.mirrorNameFor("$root$sep$hash", root))
        assertEquals(
            hash,
            SafStorageManager.mirrorNameFor("$root$sep$hash${sep}src${sep}main.kt", root),
            "Open Folder can point inside a mirror, and that is still that mirror",
        )
    }

    @Test
    fun `an ordinary project folder names no mirror`() {
        assertNull(
            SafStorageManager.mirrorNameFor(
                "/data/user/0/com.vscodroid/files/home/projects/app", root
            ),
        )
        assertNull(SafStorageManager.mirrorNameFor(null, root))
        assertNull(
            SafStorageManager.mirrorNameFor(root, root),
            "the mirrors root itself is not a mirror",
        )
    }

    /**
     * The separator rule [SafStorageManager.folderForOpenedPath] and
     * [SafStorageManager.mayReclaim] both carry, for the reason they carry it: mirror
     * names are a hash prefix, so one being a textual prefix of another is ordinary.
     * Here reading the wrong name means refusing to remove a mirror that is free while
     * allowing one that is open.
     */
    @Test
    fun `a sibling whose name extends this one is a different mirror`() {
        assertEquals(
            hash + "x",
            SafStorageManager.mirrorNameFor("$root$sep${hash}x${sep}src", root),
        )
    }
}
