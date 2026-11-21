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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

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

    private fun previousDirectory(): File = File(filesDir, "previous/projects").apply {
        assertTrue(mkdirs())
        File(this, "notes.txt").writeText("previous workspace bytes")
        Files.createSymbolicLink(link.toPath(), toPath())
    }

    @Test
    fun `a regular file at the new target keeps the previous usable projects link`() {
        val previous = previousDirectory()
        target.parentFile!!.mkdirs()
        target.writeText("target occupant")
        val setup = FirstRunSetup(context)

        setup.ensureProjectsDir()
        setup.createStorageSymlinks()

        assertEquals(previous.absolutePath, linkTarget())
        assertTrue(link.isDirectory)
        assertEquals("previous workspace bytes", File(link, "notes.txt").readText())
        assertEquals("target occupant", target.readText())
        verify(exactly = 0) { Os.symlink(any(), any()) }
    }

    @Test
    fun `a regular file at the target does not become a new projects link`() {
        target.parentFile!!.mkdirs()
        target.writeText("target occupant")
        val setup = FirstRunSetup(context)
        setup.ensureProjectsDir()

        setup.createStorageSymlinks()

        assertFalse(Files.exists(link.toPath(), LinkOption.NOFOLLOW_LINKS))
        assertEquals("target occupant", target.readText())
        verify(exactly = 0) { Os.symlink(any(), any()) }
    }

    @Test
    fun `repointing a usable link once preserves both project directories`() {
        val previous = previousDirectory()
        assertTrue(target.mkdirs())
        File(target, "current.txt").writeText("current workspace bytes")
        val setup = FirstRunSetup(context)

        setup.createStorageSymlinks()
        setup.createStorageSymlinks()

        assertEquals(target.absolutePath, linkTarget())
        assertEquals("current workspace bytes", File(link, "current.txt").readText())
        assertEquals("previous workspace bytes", File(previous, "notes.txt").readText())
        verify(exactly = 1) { Os.symlink(target.absolutePath, link.absolutePath) }
    }

    /** One launch's two repairs, in the order SplashActivity runs them. */
    private fun launch(sharedStorage: Boolean) {
        every { context.getExternalFilesDir(null) } returns if (sharedStorage) File(filesDir, "external") else null
        val setup = FirstRunSetup(context)
        setup.ensureProjectsDir()
        setup.createStorageSymlinks()
    }

    /**
     * A launch that cannot see shared storage knows nothing new about where the install works.
     * It used to answer with the internal folder, which made that folder and pointed `~/projects`
     * at it, and with both folders there and the link naming the internal one, every later launch
     * read the install as one that had always worked there: the person's folder on shared storage
     * was left behind for good while `.bashrc` went on exporting it.
     */
    @Test
    fun `a launch that cannot see shared storage leaves an older install on its shared-storage folder`() {
        val shared = File(filesDir, "external/projects").apply { assertTrue(mkdirs()) }
        File(shared, "work.txt").writeText("the person's project")
        Files.createSymbolicLink(link.toPath(), shared.toPath())

        launch(sharedStorage = false)
        launch(sharedStorage = true)

        assertEquals(shared.absolutePath, Environment.projectsDirFor(context, 34))
        assertEquals(shared.absolutePath, linkTarget())
        assertFalse(File(filesDir, "projects").exists(), "a folder the install does not use was made")
    }

    /**
     * The link is the record of which folder the install works in, and it is read as one only when it
     * names a folder called `projects`. `~/projects` is a root the webview serves as a whole, so a link
     * that something re-pointed at `~` or at a folder of keys must not become the answer on the launch
     * that cannot see shared storage, where the link is all there is to go on.
     */
    @ParameterizedTest
    @ValueSource(strings = ["home", "home/.ssh", "keys"])
    fun `a link that names no projects folder is not taken as the record`(target: String) {
        val named = File(filesDir, target).apply { mkdirs() }
        Files.createSymbolicLink(link.toPath(), named.toPath())
        every { context.getExternalFilesDir(null) } returns null

        assertEquals(
            File(filesDir, "projects").absolutePath,
            Environment.projectsDirFor(context, 34),
            "a link to ${named.name} was taken as the projects folder",
        )
    }

    /**
     * Two links the name test above lets through, because it reads the target as written and one link deep: a
     * `projects` folder inside a sensitive one (`~/.claude/projects` holds Claude Code's session logs), and a
     * `projects` that is itself a link to `~/.ssh`. Either becomes the projects folder on a launch that cannot see
     * shared storage, and that folder is a root the webview serves as a whole, so it is left out of the published
     * roots when it overlaps a location that must stay unreadable.
     */
    @ParameterizedTest
    @ValueSource(strings = ["inside .claude", "through a second link to .ssh"])
    fun `a projects folder that resolves into a sensitive location is not published`(shape: String) {
        val home = File(filesDir, "home")
        val named = if (shape == "inside .claude") {
            File(home, ".claude/projects").apply { assertTrue(mkdirs()) }
        } else {
            File(home, ".ssh").mkdirs()
            File(home, "x").mkdirs()
            File(home, "x/projects").also { Files.createSymbolicLink(it.toPath(), File(home, ".ssh").toPath()) }
        }
        Files.createSymbolicLink(link.toPath(), named.toPath())
        every { context.getExternalFilesDir(null) } returns null
        every { context.dataDir } returns filesDir.parentFile
        every { context.noBackupFilesDir } returns File(filesDir.parentFile, "no_backup")
        every { context.cacheDir } returns File(filesDir.parentFile, "cache")
        assertEquals(named.absolutePath, Environment.projectsDirFor(context, 34), "the fixture did not reach the hole")

        val sensitive = com.vscodroid.webview.sensitiveLocations(context)
        val published = com.vscodroid.webview.publishedResourceRoots(context)

        val exposed = published.filter { root -> sensitive.any { it == root || it.startsWith("$root/") || root.startsWith("$it/") } }
        assertEquals(emptyList<String>(), exposed, "a root the webview serves as a whole holds a sensitive location")
    }

    /**
     * The other direction: an install that works in the internal folder, with a folder of that
     * name on shared storage that another app made, stays where it is when its own folder has
     * been deleted (a terminal can), whether the link spells the folder as written or relative to
     * `~`, and across a launch that cannot see shared storage.
     */
    @ParameterizedTest
    @ValueSource(strings = ["as written", "relative", "as written, after a launch without shared storage"])
    fun `an install on its internal folder is not moved onto another app's shared-storage folder`(spelling: String) {
        val internal = File(filesDir, "projects")
        val target = if (spelling == "relative") Path.of("../projects") else internal.toPath()
        Files.createSymbolicLink(link.toPath(), target)
        assertTrue(File(filesDir, "external/projects").mkdirs(), "could not stage another app's folder")

        if (spelling.endsWith("shared storage")) launch(sharedStorage = false)
        launch(sharedStorage = true)
        launch(sharedStorage = true)

        assertEquals(internal.absolutePath, Environment.projectsDirFor(context, 34))
        assertEquals(internal.absolutePath, linkTarget())
        assertTrue(internal.isDirectory, "the install's own folder was not made again")
    }

    @Test
    fun `a refused stale unlink keeps the old target and reports the refusal`() {
        val previous = previousDirectory()
        assertTrue(target.mkdirs())
        val home = link.parentFile!!
        val permissions = Files.getPosixFilePermissions(home.toPath())
        try {
            Files.setPosixFilePermissions(home.toPath(), PosixFilePermissions.fromString("r-xr-xr-x"))
            assumeFalse(home.canWrite(), "this process can bypass the directory permission fixture")

            FirstRunSetup(context).createStorageSymlinks()

            assertEquals(previous.absolutePath, linkTarget())
            assertEquals("previous workspace bytes", File(link, "notes.txt").readText())
            verify(exactly = 0) { Os.symlink(any(), any()) }
            verify(atLeast = 1) {
                Logger.w(any(), match { it.contains("Could not remove the stale projects symlink") })
            }
        } finally {
            Files.setPosixFilePermissions(home.toPath(), permissions)
        }
    }
}
