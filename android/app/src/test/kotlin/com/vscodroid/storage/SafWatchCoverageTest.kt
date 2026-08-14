package com.vscodroid.storage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Which directories the mirror watch has to cover.
 *
 * The engine used to build one observer over the mirror root and call it recursive on
 * the strength of a comment: "On API 29+, FileObserver supports watching entire
 * directory trees via the File constructor variant." It does not. The API 29 addition
 * is the `List<File>` constructor, `startWatching()` opens one watch descriptor per
 * path it was handed, and an inotify watch covers a directory rather than a tree. So
 * saving `src/main.ts` updated the mirror and the device copy stayed as it was, with
 * nothing logged and nothing to notice.
 *
 * The set is pinned here rather than the observers themselves: constructing one runs
 * FileObserver's static initializer, which reaches native code that a plain JVM test
 * has no way to satisfy. What is asserted is the decision the registration consumes.
 */
class SafWatchCoverageTest {

    @TempDir
    lateinit var root: File

    private fun makeDir(path: String): File = File(root, path).apply { mkdirs() }

    /** Coverage as paths relative to the root, with the root itself as ".". */
    private fun covered(limit: Int = 64): List<String> =
        SafSyncEngine.watchableDirectories(root, limit)
            .map { it.relativeTo(root).path.ifEmpty { "." } }

    @Test
    fun `the root and every directory under it are covered`() {
        // The regression itself: with one watch on the root, "src" and below are
        // invisible, and an edit saved there never leaves the mirror.
        makeDir("src/main/kotlin")

        assertEquals(
            listOf(".", "src", "src/main", "src/main/kotlin"),
            covered(),
            "a watch has to be registered for each directory, not just the root"
        )
    }

    @Test
    fun `a skipped directory and everything under it stays uncovered`() {
        // Same exclusions the walk obeys, and the reason the count stays survivable:
        // node_modules is thousands of directories and none of them is mirrored in,
        // so a watch there spends a kernel descriptor on a change with nowhere to go.
        makeDir("node_modules/left-pad/dist")
        makeDir("src")

        assertEquals(listOf(".", "src"), covered())
    }

    @Test
    fun `a file is never watched, whatever it is called`() {
        // shouldSkip answers on names, and a file named like a directory is a real
        // thing. Watching one would open a descriptor that reports nothing.
        File(root, "node_modules").writeText("not a directory")
        File(root, "README.md").writeText("#")

        assertEquals(listOf("."), covered())
    }

    @Test
    fun `the cap is honoured`() {
        // Every watch is a kernel inotify descriptor out of a per-uid budget that the
        // VS Code server's own file watcher draws on too. Removing the limit lets one
        // unusually wide folder exhaust it, and the failure lands in the editor's
        // watcher rather than here.
        makeDir("a/b/c/d/e")

        val covered = covered(limit = 3)

        assertEquals(3, covered.size, "the cap has to bound the result")
        assertEquals(listOf(".", "a", "a/b"), covered)
    }

    @Test
    fun `the budget is spent on the shallow directories first`() {
        // Depth-first would sink the whole budget into one branch and leave the other
        // top-level directories -- the ones a person is editing in -- unwatched.
        makeDir("deep/one/two/three")
        makeDir("shallow")

        val covered = covered(limit = 3)

        assertTrue("shallow" in covered, "a sibling one level down must not be starved")
        assertFalse("deep/one/two" in covered, "depth must not consume the budget first")
    }

    @Test
    fun `an exhausted budget yields nothing rather than one more watch`() {
        makeDir("src")

        assertEquals(emptyList<File>(), SafSyncEngine.watchableDirectories(root, limit = 0))
        assertEquals(emptyList<File>(), SafSyncEngine.watchableDirectories(root, limit = -1))
    }

    @Test
    fun `a path that is not a directory yields nothing`() {
        val file = File(root, "notes.txt").apply { writeText("hello") }

        assertEquals(emptyList<File>(), SafSyncEngine.watchableDirectories(file, limit = 64))
        assertEquals(
            emptyList<File>(),
            SafSyncEngine.watchableDirectories(File(root, "gone"), limit = 64),
            "a directory removed between the event and the registration is not an error"
        )
    }
}
