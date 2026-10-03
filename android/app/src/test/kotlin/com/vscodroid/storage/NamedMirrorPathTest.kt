package com.vscodroid.storage

import android.content.ContentResolver
import android.content.Context
import android.content.UriPermission
import android.net.Uri
import com.vscodroid.util.Logger
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths

/**
 * The path a device folder's copy is opened by, so that the workbench shows the
 * folder's own name.
 *
 * The workbench names a folder after the last segment of its path, and a copy lives
 * at `saf-mirrors/<hash>`, so the Explorer root and the window title read
 * `8e440ff38c8e` where the folder's name belongs. The copy stays where it is, since
 * everything that keeps it safe names it by that hash, and is opened through
 * `saf-mirrors/by-name/<hash>/<name>`, a link to it.
 *
 * Two kinds of case. The pure ones pin how a path is read back to its copy, which is
 * what keeps a folder opened by name watched and guarded; missing it is a folder whose
 * saves never reach the device. The filesystem ones make real links in a temporary
 * directory, because the property worth having is what a link resolves to and what a
 * cleanup does and does not follow.
 */
class NamedMirrorPathTest {

    @TempDir
    lateinit var filesDir: File

    private lateinit var manager: SafStorageManager
    private lateinit var resolver: ContentResolver
    private lateinit var mirrors: File
    private lateinit var root: String

    private val hash = "8e440ff38c8e"
    private val other = "0f0f0f0f0f0f"

    @BeforeEach
    fun setUp() {
        mockkObject(Logger)
        every { Logger.i(any(), any()) } just Runs
        every { Logger.d(any(), any()) } just Runs
        every { Logger.w(any(), any()) } just Runs
        every { Logger.w(any(), any(), any()) } just Runs

        resolver = mockk(relaxed = true)
        every { resolver.persistedUriPermissions } returns emptyList()
        val context = mockk<Context>(relaxed = true)
        every { context.filesDir } returns filesDir
        every { context.applicationContext } returns context
        every { context.contentResolver } returns resolver

        manager = SafStorageManager(context)
        mirrors = File(filesDir, "saf-mirrors").apply { mkdirs() }
        root = mirrors.absolutePath
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    private fun copy(name: String = hash) = File(mirrors, name).apply {
        mkdirs()
        File(this, "README.md").writeText("hello")
    }

    private fun folder(dir: File, displayName: String = "reviewtest2") = SafFolderInfo(
        uri = mockk<Uri>(relaxed = true),
        displayName = displayName,
        lastOpened = 0L,
        mirrorPath = dir.absolutePath,
    )

    private fun named(name: String = "reviewtest2", of: String = hash) = "$root/by-name/$of/$name"

    // -- The name --

    @Test
    fun `a folder's name is used as it is`() {
        assertEquals("reviewtest2", SafStorageManager.nameSegment("reviewtest2"))
        assertEquals("我的 项目", SafStorageManager.nameSegment("我的 项目"))
    }

    /**
     * The fallback `getDisplayName` answers when the provider does not, a tree's
     * document id. Its last segment is the folder.
     */
    @Test
    fun `a document id stands for the folder at its end`() {
        assertEquals("notes", SafStorageManager.nameSegment("primary:Documents/notes"))
    }

    @Test
    fun `a control character never reaches a path`() {
        assertEquals("a_b_c", SafStorageManager.nameSegment("a\nb\u0000c"))
    }

    @Test
    fun `a name that would be another directory or none is refused`() {
        listOf("", "   ", ".", "..", "Documents/").forEach {
            assertNull(SafStorageManager.nameSegment(it), "'$it' was taken as a folder name")
        }
    }

    /**
     * A name longer than a filesystem takes would fail the link and leave the folder on
     * its hash, so it is cut, and never inside a character: a lone surrogate or a split
     * UTF-8 sequence is a name the workbench cannot show.
     */
    @Test
    fun `a long name is cut to what a filesystem takes, on a character boundary`() {
        val chinese = SafStorageManager.nameSegment("文".repeat(100))!!
        assertEquals("文".repeat(85), chinese, "85 three-byte characters are the 255 bytes that fit")

        val emoji = SafStorageManager.nameSegment("😀".repeat(70))!!
        assertTrue(emoji.toByteArray(Charsets.UTF_8).size <= 255)
        assertEquals("😀".repeat(63), emoji, "a four-byte character was split")
    }

    // -- Reading a named path back --

    @Test
    fun `a named path is read as the copy it names`() {
        val path = named()
        assertEquals("$root/$hash", SafStorageManager.mirrorSpelling(path, root))
        assertEquals("$root/$hash/src/app.js", SafStorageManager.mirrorSpelling("$path/src/app.js", root))
        assertEquals(path to hash, SafStorageManager.namedRootOf("$path/src", root))
        assertEquals(hash, SafStorageManager.mirrorNameFor(path, root))
        assertEquals(
            hash, SafStorageManager.mirrorNameFor("$path/src/app.js", root),
            "the guard that refuses to remove an open folder would not see this one as open",
        )
    }

    @Test
    fun `nothing else is read as a named path`() {
        listOf(
            "$root/by-name/$hash",
            "$root/by-name/$hash/",
            "$root/by-name/not-a-hash/notes",
            "$root/by-name/${hash}a/notes",
            "$root/$hash/by-name/$other/notes",
            "/storage/emulated/0/by-name/$hash/notes",
        ).forEach {
            assertNull(SafStorageManager.namedRootOf(it, root), "$it was read as a named path")
            assertEquals(it, SafStorageManager.mirrorSpelling(it, root), "$it was respelled")
        }
    }

    @Test
    fun `a page on a named path is the folder of its copy`() {
        val ours = folder(copy(hash))
        val theirs = folder(copy(other))

        assertEquals(
            ours,
            SafStorageManager.folderForOpenedPath(listOf(theirs, ours), named() + "/src"),
            "a folder opened by name would get no watcher, and its saves would stay in the copy",
        )
        assertNull(
            SafStorageManager.folderForOpenedPath(listOf(theirs), named()),
            "a named path was matched to a copy it does not name",
        )
    }

    // -- Making one --

    @Test
    fun `the named path is a relative link that reads as the copy`() {
        val dir = copy()

        val link = manager.namedPathFor(dir, "reviewtest2")

        assertEquals(File(named()), link)
        assertEquals(Paths.get("..", "..", hash), Files.readSymbolicLink(link!!.toPath()))
        assertEquals("hello", File(link, "README.md").readText())
        assertEquals(dir.canonicalPath, link.canonicalPath)
    }

    @Test
    fun `asking again answers the same link`() {
        val dir = copy()
        val first = manager.namedPathFor(dir, "reviewtest2")

        assertEquals(first, manager.namedPathFor(dir, "reviewtest2"))
        assertEquals(listOf("reviewtest2"), File(mirrors, "by-name/$hash").list()?.toList())
    }

    /**
     * The old link may be the path the page is on, and taking it away would pull the
     * open folder out from under the workbench.
     */
    @Test
    fun `a renamed folder gets a link beside the old one`() {
        val dir = copy()
        manager.namedPathFor(dir, "old")

        manager.namedPathFor(dir, "new")

        assertEquals(setOf("old", "new"), File(mirrors, "by-name/$hash").list()?.toSet())
    }

    @Test
    fun `nothing but this app's own link under the name is used or touched`() {
        val dir = copy()
        val occupied = File(named()).apply { mkdirs() }
        File(occupied, "mine.txt").writeText("left here by hand")

        assertNull(manager.namedPathFor(dir, "reviewtest2"))
        assertEquals("left here by hand", File(occupied, "mine.txt").readText())

        val elsewhere = File(filesDir, "home").apply { mkdirs() }
        val foreign = File(named("other"))
        Files.createSymbolicLink(foreign.toPath(), elsewhere.toPath())

        assertNull(manager.namedPathFor(dir, "other"), "a link to somewhere else was used")
        assertEquals(elsewhere.toPath(), Files.readSymbolicLink(foreign.toPath()))
    }

    @Test
    fun `a name that cannot be a path gives no named path`() {
        assertNull(manager.namedPathFor(copy(), ".."))
        assertFalse(File(mirrors, "by-name").exists(), "a directory was made for nothing")
    }

    // -- The record of a page opened by name --

    @Test
    fun `a page on the hash gets a named path, and only a page on it is recorded`() {
        val dir = copy()
        val info = folder(dir)
        assertFalse(manager.wasShownByName(dir))

        manager.noteOpened(dir.absolutePath, info)

        assertTrue(
            Files.isSymbolicLink(File(named()).toPath()),
            "a page on the hash had no named path made, so nothing can move it there",
        )
        assertFalse(manager.wasShownByName(dir), "a page on the hash was recorded as by name")

        manager.noteOpened(named() + "/src", info)

        assertTrue(manager.wasShownByName(dir))
    }

    // -- The launch pass --

    @Test
    fun `the names of a copy that has gone go with it`() {
        val dir = copy()
        val info = folder(dir)
        manager.noteOpened(dir.absolutePath, info)
        manager.noteOpened(named(), info)
        dir.deleteRecursively()

        manager.reclaimRevokedMirrorsSync()

        assertFalse(File(mirrors, "by-name/$hash").exists(), "the folder's name outlived its copy")
        assertFalse(File(mirrors, "by-name/$hash.shown").exists(), "the mark outlived its copy")
    }

    @Test
    fun `the names of a copy still on disk or still granted stay`() {
        // On disk with no grant: holding a file no record vouches for, the pass keeps it.
        val kept = copy()
        manager.namedPathFor(kept, "reviewtest2")
        // Granted, with no copy yet: a folder picked again whose sync has not run.
        val granted = mockk<Uri>()
        every { granted.toString() } returns "content://tree/primary%3AProject"
        val grantedHash = manager.getMirrorDir(granted).name
        File(mirrors, "by-name/$grantedHash").mkdirs()
        Files.createSymbolicLink(File(mirrors, "by-name/$grantedHash/project").toPath(), Paths.get("..", "..", grantedHash))
        every { resolver.persistedUriPermissions } returns listOf(mockk<UriPermission> { every { uri } returns granted })

        manager.reclaimRevokedMirrorsSync()

        assertTrue(kept.isDirectory)
        assertTrue(Files.isSymbolicLink(File(named()).toPath()), "a copy on disk lost its name")
        assertTrue(
            Files.isSymbolicLink(File(mirrors, "by-name/$grantedHash/project").toPath()),
            "a granted folder lost its name before its copy was made",
        )
    }

    /**
     * `saf-mirrors` is exported to every terminal, so anything can be put in place of a
     * names directory, a link out of the app included. Following it would delete the
     * links somewhere else.
     */
    @Test
    fun `the cleanup never follows a link out of the names directory`() {
        val elsewhere = File(filesDir, "home/projects/app").apply { mkdirs() }
        File(elsewhere, "keep.txt").writeText("mine")
        Files.createSymbolicLink(File(elsewhere, "lnk").toPath(), Paths.get("keep.txt"))
        File(mirrors, "by-name").mkdirs()
        Files.createSymbolicLink(File(mirrors, "by-name/$hash").toPath(), elsewhere.toPath())

        manager.reclaimRevokedMirrorsSync()

        assertEquals("mine", File(elsewhere, "keep.txt").readText())
        assertTrue(Files.isSymbolicLink(File(elsewhere, "lnk").toPath()), "a link outside saf-mirrors was deleted")
    }

    @Test
    fun `a file someone left among the names is kept`() {
        val dir = copy()
        manager.namedPathFor(dir, "reviewtest2")
        File(mirrors, "by-name/$hash/notes.txt").writeText("not ours")
        dir.deleteRecursively()

        manager.reclaimRevokedMirrorsSync()

        assertFalse(Files.exists(File(named()).toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
        assertEquals("not ours", File(mirrors, "by-name/$hash/notes.txt").readText())
    }
}
