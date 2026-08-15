package com.vscodroid.storage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

/**
 * Tests for [SafSyncEngine.uploadableEntries], which decides what gets created on the
 * device when a directory appears in the mirror.
 *
 * The case that matters is a rename. inotify reports it as MOVED_FROM on the old name
 * and MOVED_TO on the new one, and `FileObserver` cannot pair them because Android does
 * not expose inotify's cookie. The two therefore arrive as an unrelated delete and
 * create: the delete removes the directory on the device *and everything under it*, and
 * the create used to put back an empty one. Walking the new name is what restores the
 * contents.
 *
 * The stakes here are one-directional. Listing too little loses the user's files on the
 * device; listing too much only costs an upload. So the assertions lean on what must be
 * present, and the exclusions are asserted individually rather than by a total count.
 */
class SafUploadPlanTest {

    private fun tree(root: File) {
        File(root, "src/util").mkdirs()
        File(root, "src/util/a.kt").writeText("a")
        File(root, "src/util/b.kt").writeText("b")
        File(root, "src/util/nested/deep").mkdirs()
        File(root, "src/util/nested/deep/c.kt").writeText("c")
        File(root, "README.md").writeText("r")
    }

    @Test
    fun `a renamed directory brings its whole subtree`(@TempDir tmp: File) {
        val root = File(tmp, "helpers").apply { mkdirs() }
        tree(root)

        val names = SafSyncEngine.uploadableEntries(root, limit = 1000)
            .map { it.relativeTo(root).path }

        // Every file that existed under the old name has to be created under the new
        // one. Missing any of these is the data loss this exists to prevent.
        assertTrue(names.contains("src/util/a.kt"), "expected a.kt, got $names")
        assertTrue(names.contains("src/util/b.kt"), "expected b.kt, got $names")
        assertTrue(names.contains("src/util/nested/deep/c.kt"), "expected c.kt, got $names")
        assertTrue(names.contains("README.md"))
        // The directories themselves too, or their children have no parent to land in.
        assertTrue(names.contains("src"))
        assertTrue(names.contains("src/util"))
        assertTrue(names.contains("src/util/nested"))
    }

    @Test
    fun `parents come before their children`(@TempDir tmp: File) {
        val root = File(tmp, "helpers").apply { mkdirs() }
        tree(root)

        val names = SafSyncEngine.uploadableEntries(root, limit = 1000)
            .map { it.relativeTo(root).path }

        // A document cannot be created before the directory that holds it exists, so
        // this ordering is a correctness requirement rather than tidiness.
        for (name in names) {
            val parent = File(name).parent ?: continue
            assertTrue(
                names.indexOf(parent) < names.indexOf(name),
                "$parent must precede $name in $names",
            )
        }
    }

    @Test
    fun `skipped directories are not uploaded`(@TempDir tmp: File) {
        val root = File(tmp, "proj").apply { mkdirs() }
        File(root, "node_modules/left-pad").mkdirs()
        File(root, "node_modules/left-pad/index.js").writeText("x")
        File(root, "keep.txt").writeText("k")

        val names = SafSyncEngine.uploadableEntries(root, limit = 1000)
            .map { it.relativeTo(root).path }

        assertTrue(names.contains("keep.txt"))
        assertFalse(names.any { it.startsWith("node_modules") },
            "node_modules is excluded from the mirror, so it has nothing to upload: $names")
    }

    @Test
    fun `a symlink is not followed out of the folder`(@TempDir tmp: File) {
        val outside = File(tmp, "outside").apply { mkdirs() }
        File(outside, "secret.txt").writeText("s")
        val root = File(tmp, "proj").apply { mkdirs() }
        File(root, "own.txt").writeText("o")
        Files.createSymbolicLink(File(root, "escape").toPath(), outside.toPath())

        val names = SafSyncEngine.uploadableEntries(root, limit = 1000)
            .map { it.relativeTo(root).path }

        assertTrue(names.contains("own.txt"))
        assertFalse(names.any { it.contains("escape") },
            "a mirror is routinely a git checkout, so a link in it is attacker-supplied " +
                "in the ordinary case: $names")
        assertFalse(names.any { it.contains("secret") })
    }

    @Test
    fun `the cap bounds the work and keeps the shallow entries`(@TempDir tmp: File) {
        val root = File(tmp, "proj").apply { mkdirs() }
        File(root, "a.txt").writeText("a")
        File(root, "b.txt").writeText("b")
        File(root, "deep").mkdirs()
        repeat(20) { File(root, "deep/f$it.txt").writeText("$it") }

        val names = SafSyncEngine.uploadableEntries(root, limit = 3)
            .map { it.relativeTo(root).path }

        assertEquals(3, names.size)
        // Breadth-first, so the budget goes to what is at the top rather than to
        // whichever branch a depth-first descent happened to enter.
        assertTrue(names.contains("a.txt"), "got $names")
        assertTrue(names.contains("b.txt"), "got $names")
    }

    @Test
    fun `a symlinked root is not walked at all`(@TempDir tmp: File) {
        // The case that reaches outside the granted folder, and the one the
        // children loop cannot cover because the root never goes through it.
        // `File.isDirectory` follows links, and so does the observer that produces
        // these jobs, so a symlink to a directory arrives looking like a directory.
        // Walking it lists the target's contents: point one at a home directory
        // inside a synced folder and the whole of it uploads to the device.
        val outside = File(tmp, "home").apply { mkdirs() }
        File(outside, "private.txt").writeText("s")
        File(outside, "nested/deeper.txt").apply { parentFile!!.mkdirs(); writeText("s") }
        val link = File(tmp, "proj/escape")
        link.parentFile!!.mkdirs()
        Files.createSymbolicLink(link.toPath(), outside.toPath())

        assertTrue(link.isDirectory, "fixture check: the link does look like a directory")
        assertTrue(
            SafSyncEngine.uploadableEntries(link, limit = 1000).isEmpty(),
            "a symlinked root must not be walked; its target is outside the folder " +
                "the user granted",
        )
    }

    @Test
    fun `a file, a missing directory and a zero cap all yield nothing`(@TempDir tmp: File) {
        val file = File(tmp, "plain.txt").apply { writeText("x") }
        assertTrue(SafSyncEngine.uploadableEntries(file, 1000).isEmpty())
        assertTrue(SafSyncEngine.uploadableEntries(File(tmp, "gone"), 1000).isEmpty())
        assertTrue(SafSyncEngine.uploadableEntries(tmp, 0).isEmpty())
    }
}
