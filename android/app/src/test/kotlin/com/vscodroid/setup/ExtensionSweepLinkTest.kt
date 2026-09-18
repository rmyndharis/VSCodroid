package com.vscodroid.setup

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files

/**
 * Tests for [removeExtensionDir], which is how every sweep of the user's
 * extensions directory removes a directory.
 *
 * The directory is the user's, not the app's: it is what the server is given as
 * `--extensions-dir`, and it is `~/.vscodroid/extensions` in the bundled
 * terminal. A link under it is ordinary, from `npm link` or from a hand-made one
 * into a project, and `File.deleteRecursively` follows a directory link because
 * `isDirectory` and `listFiles` both answer for the target. Sweeping a
 * superseded bundled extension then emptied whatever the link pointed at before
 * unlinking it, which is the same defect the toolchain uninstall already fixed
 * for its install root.
 *
 * The sweeps run on every upgrade that moves a bundled version, so this is not a
 * rare path: v1.3.0 shipped saf-bridge 1.7.0 and welcome 1.7.0, and both are
 * superseded by the current build.
 */
class ExtensionSweepLinkTest {

    @Test
    fun `a directory linked into the extensions directory keeps its files`(@TempDir root: File) {
        val project = File(root, "home/projects/mypkg").apply { mkdirs() }
        File(project, "index.js").writeText("module.exports = 1")
        val extensions = File(root, "home/.vscodroid/extensions").apply { mkdirs() }
        val swept = File(extensions, "ms-python.python-2026.3.0")
        Files.createSymbolicLink(swept.toPath(), project.toPath())

        assertTrue(removeExtensionDir(swept), "the link was reported as still there")

        assertFalse(swept.exists(), "the link itself was left behind")
        assertTrue(
            File(project, "index.js").exists(),
            "sweeping an extension directory deleted a file outside it through a link",
        )
    }

    @Test
    fun `a link nested inside a swept directory is unlinked, not followed`(@TempDir root: File) {
        val project = File(root, "home/projects/mypkg").apply { mkdirs() }
        File(project, "index.js").writeText("module.exports = 1")
        val swept = File(root, "home/.vscodroid/extensions/publisher.name-1.0.0").apply { mkdirs() }
        File(swept, "package.json").writeText("{}")
        val nested = File(swept, "node_modules").apply { mkdirs() }
        Files.createSymbolicLink(File(nested, "mypkg").toPath(), project.toPath())

        assertTrue(removeExtensionDir(swept), "the directory is still on disk")

        assertFalse(swept.exists(), "the swept directory was left behind")
        assertTrue(
            File(project, "index.js").exists(),
            "a link one level down was followed, so `npm link` cost the user their package",
        )
    }

    /**
     * The ordinary case still works: a real directory goes, and the answer is
     * what the callers log on.
     */
    @Test
    fun `a superseded extension directory is removed`(@TempDir root: File) {
        val swept = File(root, "extensions/vscodroid.vscodroid-welcome-1.7.0").apply { mkdirs() }
        File(swept, "package.json").writeText("{}")
        File(swept, "media").apply { mkdirs() }
        File(swept, "media/walkthrough.md").writeText("hello")

        assertTrue(removeExtensionDir(swept))
        assertFalse(swept.exists())
    }

    /**
     * A dangling link answers false to `exists()`, so an early return on that
     * alone would leave it on disk while reporting it gone. It costs no space,
     * but the sweep that records the debt as discharged is the one that would
     * never look again.
     */
    @Test
    fun `a dangling link is unlinked and reported gone`(@TempDir root: File) {
        val extensions = File(root, "extensions").apply { mkdirs() }
        val swept = File(extensions, "publisher.name-1.0.0")
        Files.createSymbolicLink(swept.toPath(), File(root, "gone").toPath())

        assertTrue(removeExtensionDir(swept), "a dangling link was reported as still there")
        assertTrue(
            extensions.list()?.isEmpty() == true,
            "the dangling link is still in the extensions directory",
        )
    }
}
