package com.vscodroid.storage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Which vanished directory a newly arrived one is allowed to claim.
 *
 * inotify pairs the two halves of a rename with a cookie and `FileObserver` does not
 * expose it, so `renameSourceFor` is a heuristic: arrival time and parent are all that is
 * left. What it decides is which device document gets renamed, so the interesting cases
 * are the ones where it has to refuse — a wrong pair puts one directory's subtree under
 * another's name on the user's device.
 *
 * The rule is pinned here rather than through the engine because the two refusals that
 * matter most cannot be driven from an event: expiry needs a clock the engine reads for
 * itself, and ambiguity needs the list in a state no single event produces.
 */
class SafRenamePairingTest {

    private val window = SafSyncEngine.RENAME_PAIR_WINDOW_MS
    private val now = 1_000_000L

    private fun vanished(path: String, ago: Long) =
        VanishedDirectory(path, now - ago)

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
        // for a busy observer thread. Past it, an arrival is an arrival -- joining it to
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
    fun `a directory that arrives in another parent is not a rename`() {
        // `renameDocument` cannot express a move between parents -- that is
        // `moveDocument`, a different call behind a different provider flag -- so this
        // falls back to the old behaviour rather than being half-performed.
        assertNull(sourceFor("lib/util", vanished("util", ago = 5)))
        assertNull(sourceFor("util", vanished("lib/util", ago = 5)))
    }

    @Test
    fun `a rename inside a subdirectory pairs on that subdirectory`() {
        assertEquals("src/util", sourceFor("src/helpers", vanished("src/util", ago = 5)))
    }
}
