package com.vscodroid.storage

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * That the filter on the way in and the filter on the way out agree.
 *
 * They did not. The walk decides with `shouldSkip`, which deliberately does not list
 * `.vscode` -- `SafSyncEngineTest` pins that removal twice -- so workspace settings,
 * `.gitignore` and `.editorconfig` were copied into the mirror. The write-back decided
 * with `relativePath.startsWith(".")` and dropped every one of them. Edit
 * `.vscode/settings.json` in the editor, save it, and the device copy never moved.
 *
 * One asymmetry is kept on purpose and is pinned below so it is not mistaken for the
 * same bug: files the machine writes for itself and is about to rename away.
 */
class SafWriteBackFilterTest {

    /**
     * Read from the engine rather than written out here, so that changing the suffix
     * in one place and not the other fails. The copy writes beside its destination
     * with this suffix and renames when the stream finishes; the rename arrives as a
     * create, and a create is uploaded.
     */
    private val partialSuffix: String =
        SafSyncEngine::class.java
            .getDeclaredField("PARTIAL_SUFFIX")
            .apply { isAccessible = true }
            .get(null) as String

    // ── the asymmetry that was a defect ──────────────────────────────────

    @ParameterizedTest(name = "writes back: {0}")
    @ValueSource(
        strings = [
            ".vscode/settings.json",
            ".vscode/launch.json",
            ".gitignore",
            ".editorconfig",
            "src/main.ts",
            "src/deeply/nested/module.kt",
        ]
    )
    fun `what the walk mirrors in is written back out`(path: String) {
        assertTrue(
            SafSyncEngine.shouldWriteBack(path, isDirectory = false),
            "'$path' is copied into the mirror, so an edit to it has to reach the device"
        )
    }

    @Test
    fun `a file named like a skipped directory is still written back`() {
        // shouldSkip answers false for anything that is not a directory, and the walk
        // mirrors such a file in. A file called .git is a worktree pointer; dropping
        // its edits breaks the repository it points at.
        assertTrue(SafSyncEngine.shouldWriteBack(".git", isDirectory = false))
        assertTrue(SafSyncEngine.shouldWriteBack(".env", isDirectory = false))
    }

    // ── what stays excluded ──────────────────────────────────────────────

    @ParameterizedTest(name = "does not write back: {0}")
    @ValueSource(
        strings = [
            "node_modules/left-pad/index.js",
            ".git/config",
            "src/__pycache__/mod.pyc",
            "venv/bin/activate",
        ]
    )
    fun `nothing under a skipped directory is written back`(path: String) {
        assertFalse(
            SafSyncEngine.shouldWriteBack(path, isDirectory = false),
            "'$path' was never mirrored in, so there is nothing on the device to update"
        )
    }

    @Test
    fun `a skipped directory is not written back as a directory`() {
        assertFalse(SafSyncEngine.shouldWriteBack("node_modules", isDirectory = true))
        assertFalse(SafSyncEngine.shouldWriteBack("src/.git", isDirectory = true))
    }

    @Test
    fun `an empty path is not a change`() {
        assertFalse(SafSyncEngine.shouldWriteBack("", isDirectory = false))
        assertFalse(SafSyncEngine.shouldWriteBack("", isDirectory = true))
    }

    // ── the asymmetry that is deliberate ─────────────────────────────────

    @Test
    fun `the engine never uploads its own half-written copy`() {
        // copyDocumentToLocal writes "<name><suffix>" and renames it into place. The
        // ignore list covered names starting with "." and ending in "~" or ".tmp" and
        // this suffix is none of those, so every file the initial sync copied was
        // uploaded twice: once as a partial, once as itself.
        assertFalse(
            SafSyncEngine.shouldWriteBack("notes.txt$partialSuffix", isDirectory = false),
            "a partial copy must never be pushed to the device"
        )
        assertFalse(
            SafSyncEngine.shouldWriteBack("src/main.ts$partialSuffix", isDirectory = false),
            "the same holds one directory down, which is where the watch now reaches"
        )
    }

    @Test
    fun `scratch files a writer is about to rename away are left alone`() {
        assertFalse(SafSyncEngine.shouldWriteBack("notes.txt~", isDirectory = false))
        assertFalse(SafSyncEngine.shouldWriteBack("build/out.tmp", isDirectory = false))
    }

    // ── the two filters, side by side ────────────────────────────────────

    /**
     * The write-back filter answers to [SafSyncEngine.SKIP_DIRECTORIES] itself.
     *
     * This used to assert `!shouldSkip(name, isDir) == shouldWriteBack(name, isDir)`,
     * which cannot fail: for a single-segment, non-machine-temporary path
     * `shouldWriteBack` **is** `!shouldSkip`, so the equality restates the
     * implementation. A test that cannot go red is worse than no test, because its
     * comment claimed it would catch a name added to the set that the write-back still
     * allows, and someone reading the list of green tests would believe that.
     *
     * Asked of the data instead. The set is the single source of what the walk keeps
     * out of the mirror, so reading it here is what would catch `shouldWriteBack` being
     * reimplemented against a second, drifting list of its own, which is the shape the
     * `.vscode` defect took: two filters, one of them written out by hand.
     */
    @Test
    fun `every skipped directory is refused by the write-back filter`() {
        val skipped = SafSyncEngine.SKIP_DIRECTORIES
        // The set is the fixture, so an empty one would make the loop vacuous and the
        // whole file agreeable.
        assertTrue(skipped.size >= 5, "SKIP_DIRECTORIES has ${skipped.size} entries; too few to mean anything")

        skipped.forEach { name ->
            assertFalse(
                SafSyncEngine.shouldWriteBack(name, isDirectory = true),
                "'$name' is in SKIP_DIRECTORIES, so it was never mirrored in and there " +
                    "is nothing on the device for a write-back to update",
            )
            assertFalse(
                SafSyncEngine.shouldWriteBack("$name/child.txt", isDirectory = false),
                "'$name/child.txt' lives under a skipped directory, so the walk never " +
                    "copied it in",
            )
        }
    }

    @ParameterizedTest(name = "written back: {0}")
    @ValueSource(strings = ["src", "build", ".vscode", "lib", "docs", "app"])
    fun `a directory the walk mirrors in is written back`(name: String) {
        // The other direction, and the one the `.vscode` defect was. Named literals
        // rather than "everything not in the set", because the interesting cases are
        // the dot-directories the walk deliberately does NOT skip.
        assertFalse(
            name in SafSyncEngine.SKIP_DIRECTORIES,
            "'$name' is in SKIP_DIRECTORIES, so this case is asserting the wrong thing",
        )
        assertTrue(
            SafSyncEngine.shouldWriteBack(name, isDirectory = true),
            "'$name' is mirrored in, so an edit under it has to reach the device",
        )
    }

    /**
     * The rule both write-back paths apply, asserted directly.
     *
     * It is a predicate rather than an inline condition because only one of its two call
     * sites can be reached from a JVM test: delivering the directory event that drives
     * the other constructs a `FileObserver`, whose static initializer reaches native code
     * (see SafWatchCoverageTest). The recursive path went without this check for exactly
     * that reason, so naming the rule is what makes the second site visible.
     */
    @Test
    fun `a write is refused only when the device still holds a document the sync never read`() {
        val unread = "/data/mirror/archive/big.zip"
        val read = "/data/mirror/notes.md"
        val unfetched = setOf(unread)

        assertTrue(
            SafSyncEngine.writeWouldReplaceUnreadDocument(unread, true, unfetched),
            "a document the sync never read, still on the device, must not be written over",
        )
        assertFalse(
            SafSyncEngine.writeWouldReplaceUnreadDocument(unread, false, unfetched),
            "the set is a memory, not a permanent refusal: with the device document gone " +
                "the local file is an ordinary new file",
        )
        assertFalse(
            SafSyncEngine.writeWouldReplaceUnreadDocument(read, true, unfetched),
            "a document the sync did read is the mirror's own copy and belongs on the device",
        )
    }
}
