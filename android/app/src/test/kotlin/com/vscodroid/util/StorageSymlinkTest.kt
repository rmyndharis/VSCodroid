package com.vscodroid.util

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * What the storage commands do when they meet a symbolic link.
 *
 * Both of them walk a directory with `isFile`, `isDirectory`, `length` and `listFiles`,
 * and every one of those resolves the link — so both were answering for whatever the
 * link pointed at, which is routinely somewhere else entirely.
 *
 * Neither situation is exotic. `cacheDir/tmp` is exported as TMPDIR and TMUX_TMPDIR to
 * every terminal and to the server, so a shortcut left there by a person or a build tool
 * is ordinary; and `filesDir/usr` is relinked to `nativeLibraryDir` on every launch,
 * about 160 times, because Android moves that directory on each reinstall.
 *
 * Driven through the public entry points with a `Context` whose two directories are real
 * ones, rather than through the private walkers: the walkers are reachable only from
 * these two commands, and it is the reported figure and the surviving files that matter.
 */
class StorageSymlinkTest {

    @TempDir
    lateinit var tmp: File

    private lateinit var context: Context
    private lateinit var filesDir: File
    private lateinit var cacheDir: File

    @BeforeEach
    fun setUp() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.i(any(), any()) } returns 0
        every { android.util.Log.d(any(), any()) } returns 0
        every { android.util.Log.w(any(), any<String>()) } returns 0
        every { android.util.Log.e(any(), any()) } returns 0

        filesDir = File(tmp, "files").apply { mkdirs() }
        cacheDir = File(tmp, "cache").apply { mkdirs() }
        context = mockk(relaxed = true)
        every { context.filesDir } returns filesDir
        every { context.cacheDir } returns cacheDir
    }

    private fun link(from: File, to: File) {
        from.parentFile?.mkdirs()
        Files.createSymbolicLink(from.toPath(), to.toPath())
    }

    private fun isLink(f: File) = Files.isSymbolicLink(f.toPath())

    @Test
    fun `clearing caches unlinks a shortcut instead of emptying what it points at`() {
        // `ln -s ~/projects/app $TMPDIR/app` in a terminal, then storage runs low and the
        // toast says to run "VSCodroid: Clear Caches". Following the link deleted every
        // file in ~/projects/app and counted their bytes as cache reclaimed.
        val precious = File(tmp, "home/projects/app").apply { mkdirs() }
        val thesis = File(precious, "thesis.txt").apply { writeText("years of work") }
        File(precious, "nested/data.db").apply { parentFile!!.mkdirs(); writeText("rows") }

        val tmpDir = File(cacheDir, "tmp").apply { mkdirs() }
        val junk = File(tmpDir, "scratch.log").apply { writeText("0123456789") }
        val shortcut = File(tmpDir, "app")
        link(shortcut, precious)

        val freed = StorageManager.clearCaches(context)

        assertTrue(thesis.isFile, "clearing the cache deleted a file outside the cache")
        assertTrue(File(precious, "nested/data.db").isFile, "and one a level further down")
        assertFalse(junk.exists(), "the real cache file still has to go")
        assertFalse(
            Files.exists(shortcut.toPath(), LinkOption.NOFOLLOW_LINKS),
            "the link itself belongs to the cache and should be unlinked",
        )
        // The ten bytes of scratch.log and nothing else: the command tells the user how
        // much it freed, and the target's bytes were never the cache's to report.
        assertEquals(10L, freed, "the reported figure counted bytes outside the cache")
    }

    @Test
    fun `a dangling link is cleared rather than left holding its directory`() {
        // Neither isFile nor isDirectory is true for one, so the walk used to step over
        // it — and the directory containing it then failed to delete, quietly, every
        // time the command ran.
        val crashLogs = File(cacheDir, "crash-logs").apply { mkdirs() }
        val dead = File(crashLogs, "latest")
        link(dead, File(tmp, "never-existed"))

        StorageManager.clearCaches(context)

        assertFalse(
            Files.exists(dead.toPath(), LinkOption.NOFOLLOW_LINKS),
            "a dangling link is cache too, and it keeps crash-logs/ from being removed",
        )
        assertFalse(crashLogs.exists(), "the directory could not be removed while the link sat in it")
    }

    @Test
    fun `the breakdown does not charge tools with the size of what the tool links point at`() {
        // usr/bin/node is a link to nativeLibraryDir/libnode.so, and git-core is 146
        // links to one libgit.so. Counting each target put several hundred MB of APK
        // payload into "tools" and into "total" — bytes that are not in filesDir at all,
        // and that no action offered to the user could ever free.
        val nativeLibs = File(tmp, "nativeLibraryDir").apply { mkdirs() }
        val node = File(nativeLibs, "libnode.so").apply { writeBytes(ByteArray(40_000)) }
        val usrBin = File(filesDir, "usr/bin").apply { mkdirs() }
        link(File(usrBin, "node"), node)
        link(File(usrBin, "npm"), node)
        File(usrBin, "own-script.sh").writeText("#!/bin/sh\n")

        val breakdown = StorageManager.getStorageBreakdown(context)

        assertEquals(
            10L, breakdown.getLong("tools"),
            "tools must report what usr/ occupies, not what its links resolve to",
        )
        assertEquals(10L, breakdown.getLong("total"), "and total is built from the same walk")
        assertTrue(isLink(File(usrBin, "node")), "fixture check: measuring must not have altered the tree")
    }

    @Test
    fun `the breakdown does not walk into a linked directory`() {
        // A link to a directory answers isDirectory=true and listFiles() returns the
        // target's entries, so this counts a whole tree that lives elsewhere — and
        // counts it again under whichever component actually contains it.
        val elsewhere = File(tmp, "elsewhere").apply { mkdirs() }
        File(elsewhere, "big.bin").writeBytes(ByteArray(30_000))
        File(filesDir, "saf-mirrors").mkdirs()
        link(File(filesDir, "saf-mirrors/shortcut"), elsewhere)

        val breakdown = StorageManager.getStorageBreakdown(context)

        assertEquals(0L, breakdown.getLong("saf_mirrors"))
    }
}
