package com.vscodroid.setup

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
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
     * so one tree's worth was never the requirement. Java 21 is the toolchain
     * this was measured against, at the 146 MB its registry entry recorded at the
     * time: 196 MB asked against about 342 MB needed, and every device between
     * those two figures downloaded 55 MB before failing partway through the copy.
     * The figure below is that input, not the registry's, so the arithmetic stays
     * pinned when the pack is rebuilt against a newer JDK.
     */
    @Test
    fun `the reservation covers both copies the install holds at once`() {
        assertEquals(342_000_000L, toolchainInstallBytes(146_000_000L, 55_000_000L))
    }

    @Test
    fun `and scales with the tree rather than being a fixed figure`() {
        assertEquals(SPACE_BUFFER, toolchainInstallBytes(0L, 0L))
        assertEquals(
            2 * 34_000_000L + SPACE_BUFFER,
            toolchainInstallBytes(34_000_000L, 9_000_000L),
        )
    }

    /**
     * A reinstall holds the staging tree and adds only what the tree on disk lacks,
     * and replaces a file by writing its sibling first, so it is charged one tree
     * plus the largest file. C and C++ is the toolchain this was measured against:
     * 260 MB unpacked, 74 MB to download, one file of 100 MB.
     */
    @Test
    fun `a reinstall is charged one tree plus the largest file it replaces`() {
        assertEquals(
            410_000_000L,
            toolchainInstallBytes(260_000_000L, 74_000_000L, InstalledTree(260_000_000L, 100_000_000L)),
        )
    }

    /**
     * The extraction peak holds the archive and the tree together, and a credit
     * must not carry the reservation under it.
     */
    @Test
    fun `a reinstall is never charged less than the extraction needs`() {
        assertEquals(
            74_000_000L + 260_000_000L + SPACE_BUFFER,
            toolchainInstallBytes(260_000_000L, 74_000_000L, InstalledTree(260_000_000L, 0L)),
        )
    }

    /** Nothing installed is the first-install figure, whatever default a caller leans on. */
    @Test
    fun `no installed tree is charged for both copies`() {
        assertEquals(
            toolchainInstallBytes(260_000_000L, 74_000_000L, InstalledTree.NONE),
            toolchainInstallBytes(260_000_000L, 74_000_000L),
        )
        assertEquals(2 * 260_000_000L + SPACE_BUFFER, toolchainInstallBytes(260_000_000L, 74_000_000L))
    }

    /**
     * The copy asks for the same figure whichever route delivered the files. Pinned
     * against the three shapes it has: nothing replaced, everything replaced, and a
     * tree that replaces more than it adds, where the margin takes over.
     */
    @Test
    fun `the copy needs the new bytes plus room for the largest replacement`() {
        assertEquals(
            186_000_000L + SPACE_BUFFER,
            toolchainCopyBytes(186_000_000L, ToolchainCopySpace(0L, 0L)),
        )
        assertEquals(
            SPACE_BUFFER + 30_000_000L,
            toolchainCopyBytes(186_000_000L, ToolchainCopySpace(186_000_000L, 30_000_000L)),
        )
        assertEquals(
            (186_000_000L + SPACE_BUFFER - 150_000_000L) + 30_000_000L,
            toolchainCopyBytes(186_000_000L, ToolchainCopySpace(150_000_000L, 30_000_000L)),
        )
        // A credit larger than the tree is clamped, never subtracted below the margin.
        assertEquals(
            SPACE_BUFFER + 1L,
            toolchainCopyBytes(100L, ToolchainCopySpace(1_000_000_000L, 1L)),
        )
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
                packInstallBytes(info.estimatedSize) <
                    toolchainInstallBytes(info.estimatedSize, info.downloadSize),
                "${info.packName}: the Play path should ask for less than the HTTP path, " +
                    "since Play has already written the tree it copies from",
            )
        }
    }

    /** Ruby cleared the old gate only by accident; state the margin rather than rely on it. */
    @Test
    fun `every shipped toolchain is charged for both copies`() {
        for (info in ToolchainRegistry.available) {
            val asked = toolchainInstallBytes(info.estimatedSize, info.downloadSize)
            assertTrue(
                asked >= info.estimatedSize * 2,
                "${info.displayName} is charged $asked for a tree of ${info.estimatedSize} " +
                    "held twice; a device could pass the gate and fail during the copy",
            )
        }
    }

    @TempDir
    lateinit var filesDir: File

    private fun sparse(path: String, bytes: Long): File {
        val file = File(filesDir, path)
        file.parentFile?.mkdirs()
        java.io.RandomAccessFile(file, "rw").use { it.setLength(bytes) }
        return file
    }

    @Test
    fun `an installed tree is measured by its bytes and its largest file`() {
        sparse("usr/opt/x/a", 30L)
        sparse("usr/opt/x/deep/b", 10L)

        assertEquals(
            InstalledTree(40L, 30L),
            installedTreeSpace(File(filesDir, "usr/opt/x"), filesDir, recordedBytes = 1_000L),
        )
    }

    /** A directory can hold more than the pack writes back, and that excess is not returned. */
    @Test
    fun `the credit is clamped at the size the pack records`() {
        sparse("usr/opt/x/a", 300L)
        sparse("usr/opt/x/b", 100L)

        assertEquals(
            InstalledTree(250L, 250L),
            installedTreeSpace(File(filesDir, "usr/opt/x"), filesDir, recordedBytes = 250L),
        )
    }

    @Test
    fun `no recorded root and a missing tree credit nothing`() {
        assertEquals(InstalledTree.NONE, installedTreeSpace(null, filesDir, 1_000L))
        assertEquals(InstalledTree.NONE, installedTreeSpace(File(filesDir, "usr/opt/gone"), filesDir, 1_000L))
    }

    /** `..` escapes `File(base, relative)`, and a credit is the first decision taken on that string. */
    @Test
    fun `a root outside the app files credits nothing`() {
        val outside = File(filesDir.parentFile, "elsewhere-${System.nanoTime()}").apply { mkdirs() }
        try {
            java.io.RandomAccessFile(File(outside, "big"), "rw").use { it.setLength(5_000L) }

            assertEquals(
                InstalledTree.NONE,
                installedTreeSpace(File(filesDir, "../${outside.name}"), filesDir, 10_000L),
            )
        } finally {
            outside.deleteRecursively()
        }
    }

    /** A link's target is not in the directory being measured, so it is neither followed nor counted. */
    @Test
    fun `links are not followed or counted`() {
        val target = sparse("usr/opt/target/huge", 9_000L)
        sparse("usr/opt/x/real", 20L)
        java.nio.file.Files.createSymbolicLink(
            File(filesDir, "usr/opt/x/link").toPath(),
            target.toPath(),
        )
        java.nio.file.Files.createSymbolicLink(
            File(filesDir, "usr/opt/x/dirlink").toPath(),
            File(filesDir, "usr/opt/target").toPath(),
        )

        assertEquals(
            InstalledTree(20L, 20L),
            installedTreeSpace(File(filesDir, "usr/opt/x"), filesDir, recordedBytes = 100_000L),
        )
    }


    @Test
    fun `only matching regular files are credited`() {
        val src = File(filesDir, "source").apply { mkdirs() }
        val dest = File(filesDir, "destination").apply { mkdirs() }
        File(src, "shared").writeText("new")
        File(src, "added").writeText("new")
        File(dest, "shared").writeText("old shared content")
        File(dest, "retired").writeText("old retired content")

        assertEquals(ToolchainCopySpace(3, 3), toolchainCopySpace(src, dest))
    }

    @Test
    fun `an expanding replacement credits only its previous allocation`() {
        val src = File(filesDir, "source").apply { mkdirs() }
        val dest = File(filesDir, "destination").apply { mkdirs() }
        File(src, "shared").writeText("new larger content")
        File(dest, "shared").writeText("old")

        assertEquals(ToolchainCopySpace(3, 3), toolchainCopySpace(src, dest))
    }

    @Test
    fun `redirected destination files and parents are never credited`() {
        val src = File(filesDir, "source").apply { mkdirs() }
        val dest = File(filesDir, "destination").apply { mkdirs() }
        File(src, "leaf").writeText("new")
        File(src, "child").mkdirs()
        File(src, "child/payload").writeText("new")
        val outside = File(filesDir, "outside").apply { mkdirs() }
        File(outside, "payload").writeText("old")
        java.nio.file.Files.createSymbolicLink(File(dest, "leaf").toPath(), File(outside, "payload").toPath())
        java.nio.file.Files.createSymbolicLink(File(dest, "child").toPath(), outside.toPath())

        assertEquals(ToolchainCopySpace(0, 0), toolchainCopySpace(src, dest))
    }

    @Test
    fun `an alias above the destination root still credits matching files`() {
        val src = File(filesDir, "source").apply { mkdirs() }
        val real = File(filesDir, "real").apply { mkdirs() }
        val alias = File(filesDir, "alias")
        java.nio.file.Files.createSymbolicLink(alias.toPath(), real.toPath())
        File(src, "payload").writeText("new")
        val dest = File(alias, "usr").apply { mkdirs() }
        File(dest, "payload").writeText("old")

        assertEquals(ToolchainCopySpace(3, 3), toolchainCopySpace(src, dest))
    }

    // -- what the pre-flight is told a pack unpacks to --

    /**
     * A withdrawn pack still occupies its bytes, and this is the reason the
     * function exists at all.
     *
     * `ToolchainRegistry.find` answers null for a retired pack, deliberately, so
     * a caller spelling the lookup `find(...)?.estimatedSize ?: 0L` turns "I do
     * not know" into "it needs nothing". The Play gate is built on the answer:
     * with 0 the reservation collapses to the bare buffer, so a gate that asks
     * for 50 MB before copying 180 MB passes exactly the devices it exists to
     * refuse, and reports success while doing it.
     *
     * The withdrawn case is first because it is the one a device is most likely
     * to be holding: `RETIRED_TOOLCHAINS` exists for installs made before a
     * withdrawal, and the registry is precisely where those are not.
     */
    @Test
    fun `a withdrawn pack still has a recorded size`() {
        assertEquals(179_000_000L, packUnpackedBytes("toolchain_go"))
    }

    /** And under the short form a record may equally carry. */
    @Test
    fun `a withdrawn pack is found under either name`() {
        assertEquals(179_000_000L, packUnpackedBytes("go"))
    }

    /**
     * Null, not zero, and the distinction is the whole point: zero is a real
     * answer meaning "this occupies nothing", which no pack does. The caller
     * branches on null to say out loud that it is skipping the pre-flight, and a
     * zero would make that branch unreachable and the skip silent.
     */
    @Test
    fun `a pack nothing here has heard of yields no figure at all`() {
        assertNull(packUnpackedBytes("toolchain_cobol"))
    }

    /** The control: an offered pack is read straight from the registry. */
    @Test
    fun `an offered pack is read from the registry`() {
        for (info in ToolchainRegistry.available) {
            assertEquals(
                info.estimatedSize, packUnpackedBytes(info.packName),
                "${info.packName} does not answer the size its registry row records",
            )
        }
    }

    // -- staging directories a download never cleaned up --

    /**
     * [toolchainTempDir] gives every request a directory of its own, which
     * stopped two downloads deleting each other's work and turned an abandoned
     * one from "overwritten by the next attempt" into "kept for ever". Nothing
     * else removes them: `StorageManager.clearCaches` names four other
     * directories, so the storage screen counts these bytes under "cache" and
     * its Clear action does not free them, while every toolchain space
     * pre-flight reads the free space they are occupying.
     */
    @Test
    fun `a staging directory older than a day is abandoned`() {
        val now = 10L * ABANDONED_DOWNLOAD_AGE_MS
        assertTrue(isAbandonedDownload(now - ABANDONED_DOWNLOAD_AGE_MS - 1, now))
    }

    /**
     * A running download must never have its staging directory taken away, so
     * anything recent is left alone even though the timestamp is a weak witness:
     * writing 56 MB into a file that already exists does not touch the directory.
     * A day is far beyond any transfer this app performs.
     */
    @Test
    fun `a staging directory touched recently is left alone`() {
        val now = 10L * ABANDONED_DOWNLOAD_AGE_MS
        assertFalse(isAbandonedDownload(now - ABANDONED_DOWNLOAD_AGE_MS + 1, now))
        assertFalse(isAbandonedDownload(now, now))
    }

    /**
     * Zero is what `File.lastModified` answers for a timestamp it could not
     * read, and reading it as the epoch would make every such directory look
     * abandoned. Deleting one a download is writing into is the failure worth
     * avoiding; an extra day of disk is the price.
     */
    @Test
    fun `a timestamp that cannot be read is not evidence of abandonment`() {
        assertFalse(isAbandonedDownload(0L, 10L * ABANDONED_DOWNLOAD_AGE_MS))
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
