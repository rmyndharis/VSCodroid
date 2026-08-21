package com.vscodroid.setup

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException

/**
 * What an install reserves, and what it does when part of the tree cannot be read.
 *
 * Both cases are about the same habit: reading a hopeful answer out of an
 * ambiguous one. The reservation described the finished product rather than the
 * work, and `listFiles` returning null was read as "empty" rather than "could
 * not tell".
 */
class ToolchainInstallSpaceTest {

    /**
     * The install holds the unpacked tree and the copy of it at the same moment,
     * so one tree's worth was never the requirement. Java 17 is the toolchain
     * this was measured against: 146 MB unpacked, so 196 MB asked against about
     * 342 MB needed, and every device between those two figures downloaded
     * 55 MB before failing partway through the copy.
     */
    @Test
    fun `the reservation covers both copies the install holds at once`() {
        assertEquals(342_000_000L, toolchainInstallBytes(146_000_000L))
    }

    @Test
    fun `and scales with the tree rather than being a fixed figure`() {
        assertEquals(SPACE_BUFFER, toolchainInstallBytes(0L))
        assertEquals(2 * 34_000_000L + SPACE_BUFFER, toolchainInstallBytes(34_000_000L))
    }

    /**
     * The Play path is charged for one copy, not two, and the difference is when the
     * first one is written rather than where.
     *
     * Play delivers into `filesDir/assetpacks`, so by the time this figure is asked for,
     * the delivered tree is already sitting on the filesystem `StatFs(filesDir)` measures
     * and is already counted against it. The only NEW allocation the install makes is the
     * copy into `usr/`, which is what this charges for; `removePack` frees Play's copy
     * afterwards. Charging for both would double-count a tree already on disk.
     *
     * Peak usage is still two trees, which is why the reason matters more than the
     * figure: it decides what to do when an install is refused for space. See
     * `packInstallBytes`' KDoc for the bytecode this was verified from, and for the
     * consequence, which is that deleting Play's copy on a refusal buys the retry
     * nothing. The reason recorded here used to be that Play writes the pack outside
     * `filesDir`, which is not what it does; the assertions below are the same either
     * way, so nothing failed when that was disproved.
     */
    @Test
    fun `the Play reservation charges for the copy it makes and not for Play's own`() {
        assertEquals(146_000_000L + SPACE_BUFFER, packInstallBytes(146_000_000L))
        assertEquals(SPACE_BUFFER, packInstallBytes(0L))
    }

    /** And it is genuinely the smaller of the two, or the two paths have been swapped. */
    @Test
    fun `the Play reservation is smaller than the HTTP one for every shipped toolchain`() {
        for (info in ToolchainRegistry.available) {
            assertTrue(
                packInstallBytes(info.estimatedSize) < toolchainInstallBytes(info.estimatedSize),
                "${info.packName}: the Play path should ask for less than the HTTP path, " +
                    "since Play has already written the tree it copies from",
            )
        }
    }

    /** Ruby cleared the old gate only by accident; state the margin rather than rely on it. */
    @Test
    fun `every shipped toolchain is charged for both copies`() {
        for (info in ToolchainRegistry.available) {
            val asked = toolchainInstallBytes(info.estimatedSize)
            assertTrue(
                asked >= info.estimatedSize * 2,
                "${info.displayName} is charged $asked for a tree of ${info.estimatedSize} " +
                    "held twice; a device could pass the gate and fail during the copy",
            )
        }
    }

    @Test
    fun `a tree that reads cleanly is copied whole`() {
        val src = File(dir, "src").apply { mkdirs() }
        File(src, "bin").mkdirs()
        File(src, "bin/ruby").writeText("payload")
        File(src, "manifest.json").writeText("{}")
        val dest = File(dir, "dest")

        copyDirectoryTree(src, dest)

        assertEquals("payload", File(dest, "bin/ruby").readText())
        assertTrue(File(dest, "manifest.json").isFile)
    }

    /**
     * The case that used to be recorded as a complete install.
     *
     * A directory that exists but cannot be enumerated is an I/O fault, a
     * permission refusal, or the tree vanishing underneath the copy, which the
     * shared temp directory made a routine event. Returning quietly left the
     * caller free to write the manifest and report COMPLETED, and the user got a
     * green card and a command that is not on disk.
     */
    @Test
    fun `a directory that cannot be listed refuses instead of copying nothing`() {
        val src = File(dir, "src").apply { mkdirs() }
        File(src, "bin").mkdirs()
        File(src, "bin/ruby").writeText("payload")
        val dest = File(dir, "dest")

        assertThrows(IOException::class.java) {
            copyDirectoryTree(src, dest) { if (it.name == "bin") null else it.listFiles() }
        }
    }

    /** The control: the same injection point, answering normally, copies. */
    @Test
    fun `the injected lister is what the case above bends, not the copy itself`() {
        val src = File(dir, "src").apply { mkdirs() }
        File(src, "bin").mkdirs()
        File(src, "bin/ruby").writeText("payload")
        val dest = File(dir, "dest")

        copyDirectoryTree(src, dest) { it.listFiles() }

        assertEquals("payload", File(dest, "bin/ruby").readText())
    }

    /**
     * Two downloads of the same pack must not stage into one directory.
     *
     * The cleanup deletes its staging directory whole, so a shared path meant
     * each download's `finally` deleted the other's archive and half-extracted
     * tree. Reachable by rotating the toolchain screen, which rebuilds the
     * manager while the first download is still running on its own executor.
     */
    @Test
    fun `each download stages somewhere of its own`() {
        val first = toolchainTempDir(dir, "toolchain_java")
        val second = toolchainTempDir(dir, "toolchain_java")

        assertTrue(
            first != second,
            "two downloads of one pack share $first, so each one's cleanup deletes the other's",
        )
        assertEquals(first.parentFile, second.parentFile, "both belong under one staging root")
        assertTrue(first.name.startsWith("toolchain_java-"), "the pack is still named in the path")
    }

    @TempDir
    lateinit var dir: File
}
