package com.vscodroid.storage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Which vanished directory a newly arrived one is allowed to claim.
 *
 * inotify pairs the two halves of a rename with a cookie and `FileObserver` does not
 * expose it, so `renameSourceFor` is a heuristic: arrival time and how many are in flight
 * are all that is left. What it decides is which device document gets renamed, so the
 * interesting cases are the ones where it has to refuse. A wrong pair puts one directory's
 * subtree under another's name on the user's device.
 *
 * A third rule once required both halves to share a parent. It went when the write-back
 * learned `moveDocument`, and its absence is itself pinned below: the two remaining rules
 * now carry a wider set of arrivals than they used to.
 *
 * The rule is pinned here rather than through the engine because the two refusals that
 * matter most cannot be driven from an event: expiry needs a clock the engine reads for
 * itself, and ambiguity needs the list in a state no single event produces.
 */
class SafRenamePairingTest {

    private val window = SafSyncEngine.RENAME_PAIR_WINDOW_MS
    private val now = 1_000_000L

    // The mirror path is the record's other half, and it is not what is being decided
    // here: it names the upload records that follow the directory, while the pairing
    // reads the mirror-relative path and the clock.
    private fun vanished(path: String, ago: Long) =
        VanishedDirectory(path, now - ago, "/mirror/$path")

    private fun sourceFor(newPath: String, vararg vanished: VanishedDirectory): String? =
        SafSyncEngine.renameSourceFor(vanished.toList(), newPath, now)

    @Test
    fun `one directory that just left is the source`() {
        assertEquals("util", sourceFor("helpers", vanished("util", ago = 5)))
    }

    @Test
    fun `nothing having left means nothing to claim`() {
        assertNull(sourceFor("helpers"))
    }

    @Test
    fun `a directory that left too long ago is not a rename`() {
        // The pair is milliseconds apart in the kernel's own queue; the window is slack
        // for a busy observer thread. Past it, an arrival is an arrival, joining it to
        // a departure that happened a minute earlier would rename a document the user
        // moved away deliberately.
        assertNull(sourceFor("helpers", vanished("util", ago = window + 1)))
    }

    @Test
    fun `two live candidates are ambiguous and neither is claimed`() {
        // Interleaved moves: MOVED_FROM a, MOVED_FROM c, MOVED_TO b. Order no longer
        // identifies anything, and pairing b with c would put c's subtree under b's name
        // on the device while a's stayed behind.
        assertNull(sourceFor("b", vanished("a", ago = 10), vanished("c", ago = 5)))
    }

    @Test
    fun `an expired candidate does not make a live one ambiguous`() {
        // Otherwise one directory moved out of the folder earlier in the session would
        // suppress every rename after it, which is the failure mode of never forgetting.
        assertEquals(
            "util",
            sourceFor("helpers", vanished("gone", ago = window + 1), vanished("util", ago = 5))
        )
    }

    @Test
    fun `a directory that arrives in another parent is still a rename`() {
        // This used to be refused, because `renameDocument` cannot express a move between
        // parents and a pair the engine could not act on was better left unclaimed. The
        // write-back now reaches for `moveDocument` when the parents differ, so the pair
        // is claimable in both directions: down into a subdirectory and back out of one.
        assertEquals("util", sourceFor("lib/util", vanished("util", ago = 5)))
        assertEquals("lib/util", sourceFor("util", vanished("lib/util", ago = 5)))
    }

    @Test
    fun `a move that also changes the name pairs`() {
        // `mv src/util src/legacy/helpers`: both halves change, which is the case needing
        // a move followed by a rename rather than either alone.
        assertEquals("src/util", sourceFor("src/legacy/helpers", vanished("src/util", ago = 5)))
    }

    /**
     * The parent no longer gates pairing, so the two surviving rules are the only thing
     * bounding a mis-pair. Pinned here because widening the pairing widened what they have
     * to carry: before, an unrelated arrival had to land in the same directory to be
     * joined to a departure; now it can land anywhere in the mirror.
     */
    @Test
    fun `dropping the parent rule did not weaken the other two`() {
        assertNull(
            sourceFor("lib/b", vanished("a", ago = 10), vanished("c", ago = 5)),
            "two live candidates stay ambiguous across parents",
        )
        assertNull(
            sourceFor("lib/helpers", vanished("util", ago = window + 1)),
            "an expired candidate stays expired across parents",
        )
    }

    @Test
    fun `a rename inside a subdirectory pairs on that subdirectory`() {
        assertEquals("src/util", sourceFor("src/helpers", vanished("src/util", ago = 5)))
    }
}
