package com.vscodroid.setup

import android.content.Context
import android.system.Os
import android.system.StructStat
import com.vscodroid.util.Environment
import com.vscodroid.util.Logger
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * `~/projects`, the link the bundled terminal starts in.
 *
 * It points at app-external storage, which routes outside the app can still
 * reach, so the directory under it disappears while the app is not running and
 * `ensureProjectsDir()` remakes it on every launch. The link has to keep up with
 * that, and the two ways it failed to are what these measure: `exists()` FOLLOWS
 * a symlink, so a dangling one reads as present and was left dangling for the
 * life of the install, and the repair that replaced it took the old link away
 * before it knew it could write a new one.
 *
 * `Os` cannot run in a JVM unit test, so the three calls the method makes are
 * routed to `java.nio.file` here, as [ToolchainTrampolineLinkTest] does. The
 * links and their targets are therefore real, and the assertions read them back;
 * what is stubbed is the syscall wrapper, not the logic under test.
 */
class ProjectsSymlinkTest {

    @TempDir
    lateinit var filesDir: File

    private lateinit var context: Context

    /** `~/projects`, which is the link. */
    private val link by lazy { File(filesDir, "home/projects") }

    /** What it should point at: the app-external projects directory. */
    private val target by lazy { File(Environment.getProjectsDir(context)) }

    @BeforeEach
    fun setUp() {
        mockkObject(Logger)
        every { Logger.d(any(), any()) } just Runs
        every { Logger.i(any(), any()) } just Runs
        every { Logger.w(any(), any(), any()) } just Runs
        every { Logger.e(any(), any(), any()) } just Runs

        // lstat is asked only whether the path is there, and it must answer
        // WITHOUT following the link, which is the whole reason the production
        // code calls it rather than File.exists().
        mockkStatic(Os::class)
        every { Os.lstat(any()) } answers {
            val path = Path.of(firstArg<String>())
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                throw java.io.FileNotFoundException(path.toString())
            }
            mockk<StructStat>(relaxed = true)
        }
        every { Os.readlink(any()) } answers {
            Files.readSymbolicLink(Path.of(firstArg<String>())).toString()
        }
        every { Os.symlink(any(), any()) } answers {
            Files.createSymbolicLink(Path.of(secondArg<String>()), Path.of(firstArg<String>()))
            Unit
        }

        context = mockk(relaxed = true)
        every { context.filesDir } returns filesDir
        every { context.getExternalFilesDir(null) } returns File(filesDir, "external")

        assertTrue(File(filesDir, "home").mkdirs(), "could not stage the home directory")
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    private fun linkTarget(): String? =
        runCatching { Files.readSymbolicLink(link.toPath()).toString() }.getOrNull()

    @Test
    fun `a launch with no link writes one at the projects directory`() {
        assertTrue(target.mkdirs(), "could not stage the projects directory")

        FirstRunSetup(context).createStorageSymlinks()

        assertEquals(target.absolutePath, linkTarget())
    }

    /**
     * The everyday reinstall. `getExternalFilesDir` hands back a path built from
     * the package, and a debug build, a rename or a restore can move it, which
     * leaves the link pointing at a directory that no longer exists while
     * `exists()` reports the link as present.
     *
     * NEGATIVE CONTROL: restore the `!link.exists()` guard this replaced. The
     * link then reads as absent, `Os.symlink` answers EEXIST into a debug log,
     * and the assertion sees the old target.
     */
    @Test
    fun `a link whose target has moved is repointed`() {
        assertTrue(target.mkdirs(), "could not stage the projects directory")
        Files.createSymbolicLink(link.toPath(), Path.of(filesDir.absolutePath, "old-external", "projects"))

        FirstRunSetup(context).createStorageSymlinks()

        assertEquals(target.absolutePath, linkTarget())
    }

    /**
     * The ordering this method now keeps, and the one case where getting it
     * wrong costs the user something.
     *
     * The target check used to sit BELOW the delete, so a link that had to be
     * repointed was unlinked and then, with the new target not yet on disk, the
     * method returned. `~/projects` was gone where a wrong but usable link had
     * been, and the terminal starts there. Narrow in practice because
     * `ensureProjectsDir()` runs immediately before this on both call paths, but
     * this method is public and runs on every launch, so that ordering was the
     * only thing holding it shut.
     *
     * NEGATIVE CONTROL: move `if (!File(projectsDir).exists()) return` back below
     * the delete. Nothing is at the path afterwards and this reddens.
     */
    @Test
    fun `a stale link is left in place while there is nothing to point it at`() {
        val stale = Path.of(filesDir.absolutePath, "old-external", "projects")
        Files.createSymbolicLink(link.toPath(), stale)

        FirstRunSetup(context).createStorageSymlinks()

        assertEquals(
            stale.toString(), linkTarget(),
            "the stale link was removed and nothing replaced it, so ~/projects is now absent",
        )
    }

    /**
     * A real directory under our name is the user's, whatever it holds. Deleting
     * it to write a link would take their files with it.
     */
    @Test
    fun `a real directory at the path is left alone`() {
        assertTrue(target.mkdirs(), "could not stage the projects directory")
        assertTrue(link.mkdirs(), "could not stage a real directory at ~/projects")
        File(link, "theirs.txt").writeText("mine")

        FirstRunSetup(context).createStorageSymlinks()

        assertTrue(link.isDirectory && !Files.isSymbolicLink(link.toPath()))
        assertEquals("mine", File(link, "theirs.txt").readText())
    }

    /**
     * The control. A link that is already right must not be rewritten, or every
     * launch would unlink and relink the path the terminal is starting in.
     */
    @Test
    fun `a link that is already right is not touched`() {
        assertTrue(target.mkdirs(), "could not stage the projects directory")
        Files.createSymbolicLink(link.toPath(), target.toPath())

        FirstRunSetup(context).createStorageSymlinks()

        assertEquals(target.absolutePath, linkTarget())
        verify(exactly = 0) { Os.symlink(any(), any()) }
    }
}
