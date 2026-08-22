package com.vscodroid.storage

import android.net.Uri
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Tests for [SafStorageManager.folderForOpenedPath], which turns a path the
 * workbench navigated to back into the device folder it mirrors.
 *
 * VS Code switches folders by loading its own `?folder=` URL, so Open Recent,
 * the Get Started list and Open Folder never reach the picker. Kotlin sees only
 * the finished page load, and without this lookup it had no way to tell a mirror
 * from an ordinary project directory: the folder was served read-write with
 * nothing syncing it, and every save stayed local.
 *
 * The two answers cost very different things, which is why both directions are
 * here. Missing a mirror leaves the folder unwatched, the defect this exists to
 * end. Claiming a directory that is not one starts a sync against a device folder
 * it does not belong to.
 */
class MirrorLookupTest {

    private val sep = File.separator
    private val root = "/data/user/0/com.vscodroid/files/saf-mirrors"

    private fun folder(hash: String) = SafFolderInfo(
        uri = mockk<Uri>(relaxed = true),
        displayName = hash,
        lastOpened = 0L,
        mirrorPath = "$root$sep$hash",
    )

    private val abc = folder("abc123def456")
    private val other = folder("999888777666")
    private val all = listOf(abc, other)

    @Test
    fun `the mirror root itself is the folder`() {
        assertEquals(abc, SafStorageManager.folderForOpenedPath(all, abc.mirrorPath))
    }

    /**
     * Open Folder can point inside a mirror, and the watcher root must still be
     * the mirror root: every relative path the sync computes is taken against
     * that base, so watching the subdirectory resolves them all wrongly.
     */
    @Test
    fun `a directory inside a mirror still names the folder that owns it`() {
        val inside = "${abc.mirrorPath}${sep}src${sep}main"

        assertEquals(abc, SafStorageManager.folderForOpenedPath(all, inside))
    }

    @Test
    fun `an ordinary project folder is not a mirror`() {
        assertNull(
            SafStorageManager.folderForOpenedPath(all, "/data/user/0/com.vscodroid/files/home/projects"),
        )
    }

    /**
     * Mirror names are a fixed-length hash, so one being a textual prefix of
     * another cannot happen for real names. The separator is still required,
     * because the cost of getting it wrong is starting a sync against the wrong
     * device folder, and a rule that is right only because of an invariant kept
     * somewhere else is the kind that stops being right quietly.
     */
    @Test
    fun `a sibling whose name extends this one is not a match`() {
        val longer = folder("abc123def456x")

        assertNull(
            SafStorageManager.folderForOpenedPath(listOf(abc), longer.mirrorPath),
            "a longer mirror name was read as living inside the shorter one",
        )
    }

    /**
     * Does this line carry [fragment] as something the compiler sees?
     *
     * A search over raw source text finds a call inside a line a `//` has
     * disabled exactly as readily as inside a live one, and commenting a line
     * out is how a developer turns something off while chasing something else.
     * So a guard for wiring that must exist stays green over wiring that is
     * already dead, which is the one shape it was written to catch. The KDoc
     * asterisk is excluded for the same reason from the other side: this file's
     * subject is named in the prose around the lines it checks.
     *
     * Covers the two forms a disabled line takes here, a leading `//` and a
     * doc-comment continuation. A block comment opened on an earlier line is not
     * covered; nothing in this repository disables a single call that way.
     */
    private fun String.carriesLive(fragment: String): Boolean {
        val at = indexOf(fragment)
        if (at < 0) return false
        val before = substring(0, at)
        return "//" !in before && !before.trimStart().startsWith("*")
    }

    /**
     * The wiring, which no JVM test can drive: the reconciliation needs a live
     * Activity, `lifecycleScope` and an `AlertDialog`. Read off the source
     * instead, the way `SyncCancellationTest` reads the same file, because the
     * lookup being correct is worth nothing if nothing calls it.
     */
    @Test
    fun `the page-load callback reconciles the watcher`() {
        val source = File("src/main/kotlin/com/vscodroid/MainActivity.kt")
        check(source.isFile) {
            "MainActivity.kt not found at ${source.absolutePath}; this test would " +
                "otherwise pass by looking at nothing"
        }
        val lines = source.readLines()

        val callback = lines.indexOfFirst { it.carriesLive("onPageLoaded = { url ->") }
        assertTrue(callback >= 0, "the page-load callback was renamed or removed")
        assertTrue(
            (callback until minOf(callback + 12, lines.size))
                .any { lines[it].carriesLive("adoptWorkbenchFolder(") },
            "nothing reconciles the watcher when the workbench opens a folder itself, " +
                "so edits to it stay in the mirror and never reach the device",
        )
    }

    /**
     * The recent list is trimmed and the grants are not, which left every folder
     * past the tenth holding a permission for ever. The reclaim pass judges a
     * mirror by whether a permission is still persisted, so those mirrors could
     * never be reclaimed by anything the app does, and nothing in the UI removes
     * a folder either.
     */
    @Test
    fun `what falls off the recent list is reported, not silently dropped`() {
        val many = (1..12).map { folder("mirror%06d".format(it)) }

        val (kept, dropped) = SafStorageManager.splitRecent(many, 10)

        assertEquals(10, kept.size)
        assertEquals(2, dropped.size, "the tail has to be returned so its grants can be released")
        assertEquals(many.take(10), kept)
        assertEquals(many.drop(10), dropped)
    }

    @Test
    fun `a list within the limit drops nothing`() {
        val few = listOf(abc, other)

        val (kept, dropped) = SafStorageManager.splitRecent(few, 10)

        assertEquals(few, kept)
        assertTrue(dropped.isEmpty(), "a grant was released for a folder still on the list")
    }

    @Test
    fun `nothing persisted means nothing to adopt`() {
        assertNull(SafStorageManager.folderForOpenedPath(emptyList(), abc.mirrorPath))
    }
}
