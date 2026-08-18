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
