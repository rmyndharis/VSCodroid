package com.vscodroid.storage

import com.vscodroid.SourceScan
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * That every branch which declines to read a device document records it.
 *
 * `unfetched` is the only thing standing between a later editor save and
 * `openOutputStream(uri, "wt")` on a device document this app has never read:
 * both write paths consult it through `writeWouldReplaceUnreadDocument`, and a
 * path missing from it means the save truncates whatever the device holds.
 *
 * The set is therefore a contract, not a cache, and the contract is "the device
 * holds a document this sync did not read". Phase 2 has five branches that end
 * that way, and for a long time only three of them said so. The two that did not
 * were the refusals over a device copy that could not be set aside, which are
 * precisely the branches whose own comment reads "Writing anyway would be the
 * loss this guard exists to prevent": they protected the document for exactly as
 * long as the sync ran, and the first save afterwards did what they refused.
 *
 * Counted rather than named, because what matters is that no branch is left out;
 * a case naming one line would pass while a sixth arrived unrecorded.
 */
class UnreadDocumentGuardTest {

    private fun engine(): String =
        SourceScan.withoutComments(
            SourceScan.read("src/main/kotlin/com/vscodroid/storage/SafSyncEngine.kt"),
        )

    @Test
    fun `both refusals over an unreadable device copy arm the guard`() {
        val source = engine()

        // Each refusal is identified by the message it logs, which is the only
        // thing distinguishing the two branches from their siblings, and the
        // check is that the guard is armed inside the same branch rather than
        // somewhere in the file.
        for (marker in listOf(
            "Not replacing \${doc.relativePath}",
            "Not writing \${doc.relativePath} back",
        )) {
            val at = source.indexOf(marker)
            assertTrue(at >= 0, "the refusal logging `$marker` is gone; if it moved, point this case at it")
            // Look back over the branch that leads to the log, not forward: the
            // guard is armed before the message, and the branch is short.
            val branch = source.substring(maxOf(0, at - 900), at)
            assertTrue(
                branch.contains("unfetched.add(localPath.absolutePath)"),
                "the refusal logging `$marker` does not record the document as unread, " +
                    "so it declines to touch the device copy now and lets the next save " +
                    "truncate it instead",
            )
        }
    }

    @Test
    fun `a directory the walk skips is recorded as unread`() {
        val source = engine()

        assertTrue(
            source.contains("skipped.add(relativePath)"),
            "walkTree drops a SKIP_DIRECTORIES directory without recording it. Nothing " +
                "under it ever becomes a DocumentInfo, so the DELETE guard proves 'the " +
                "device holds the only copy' from a set that cannot contain it, and " +
                "deleteDocument takes the device directory whole -- including the .git " +
                "or node_modules that being skipped is the reason the mirror never held",
        )
        assertTrue(
            source.contains("unfetched.add(File(mirrorDir, relative).absolutePath)"),
            "the directories walkTree skipped are collected but never reach `unfetched`, " +
                "so the delete guard still cannot see them",
        )
    }

    /**
     * Five branches end with the device holding something this sync did not read.
     * The count is the assertion: naming lines would pass while a sixth arrived
     * unrecorded, which is exactly how the two refusals came to be missing.
     */
    @Test
    fun `every branch that declines to read records it`() {
        val adds = Regex("""unfetched\.add\(localPath\.absolutePath\)""")
            .findAll(engine()).count()

        assertEquals(
            5, adds,
            "phase 2 has five branches that leave a device document unread: the confine " +
                "refusal, the size skip, a failed copy, and the two refusals over a " +
                "device copy that could not be set aside. Found $adds recording it",
        )
    }
}
