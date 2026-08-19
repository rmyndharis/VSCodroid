package com.vscodroid.storage

import org.junit.jupiter.api.Assertions.assertEquals
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

    @ParameterizedTest(name = "agree on: {0}")
    @ValueSource(
        strings = ["node_modules", ".git", "__pycache__", ".gradle", ".idea", "venv", ".env",
            "src", "build", ".vscode", "lib", "docs"]
    )
    fun `both filters reach the same verdict on a top-level entry`(name: String) {
        // The two rules are one rule seen from either side. Stated as an equality so
        // that changing SKIP_DIRECTORIES moves both together or fails here: a name
        // added to the set that the write-back still allows means edits pushed to a
        // device folder the mirror does not have, and a name removed that the
        // write-back still blocks is the .vscode defect again under another name.
        for (isDir in listOf(true, false)) {
            assertEquals(
                !SafSyncEngine.shouldSkip(name, isDir),
                SafSyncEngine.shouldWriteBack(name, isDir),
                "'$name' (isDir=$isDir) is filtered on one side only"
            )
        }
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
