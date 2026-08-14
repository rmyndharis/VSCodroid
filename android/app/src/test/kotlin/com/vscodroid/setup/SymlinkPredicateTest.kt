package com.vscodroid.setup

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files

/**
 * Tests for [isSymlinkMode], the decision behind [isSymlink].
 *
 * What can and cannot be checked here is worth stating rather than leaving to be
 * discovered. [isSymlink] itself cannot be: it calls `Os.lstat`, a platform
 * method that throws in a JVM unit test, and the function catches everything and
 * answers false. A test asserting "a regular file is not a symlink" against it
 * would pass on this JVM no matter what the body said -- including against the
 * broken version -- which is the one kind of test worth less than none. So the
 * mode arithmetic is what is pinned, with modes supplied directly, and the
 * `lstat` call that feeds it is not covered.
 *
 * The mutations these kill, in the order they would be tempting to write:
 *
 *  - dropping the mask, `stMode == 0xA000`: a real symlink carries permission
 *    bits too, so it arrives as 0xA1FF and would answer false. Killed by the
 *    symlink cases, which all carry permissions.
 *  - testing the bits instead of the field, `(stMode and 0xA000) != 0`: a
 *    regular file is 0x81A4 and shares the 0x8000 bit, so it would answer true.
 *    Killed by the regular-file case.
 *  - returning a constant: killed by having both directions.
 */
class SymlinkPredicateTest {

    @TempDir
    lateinit var dir: File

    // The POSIX file-type field and the values it takes, spelled out here rather
    // than imported so the test does not agree with the implementation by
    // construction. These are the octal constants from the Linux ABI that Bionic
    // implements: S_IFREG 0100000, S_IFDIR 0040000, S_IFLNK 0120000,
    // S_IFIFO 0010000, S_IFSOCK 0140000.
    private val regularFile = 0x8000
    private val directory = 0x4000
    private val symbolicLink = 0xA000
    private val fifo = 0x1000
    private val socket = 0xC000

    private val rw_r__r__ = 0x1A4   // 0644
    private val rwxr_xr_x = 0x1ED   // 0755
    private val rwxrwxrwx = 0x1FF   // 0777

    @Test
    fun `a regular file is not a symlink`() {
        // createNpmWrappers asks this of a plain file and deletes only when the
        // answer is no, so a yes here silently stops it removing the stale
        // script wrappers it exists to remove.
        assertFalse(
            isSymlinkMode(regularFile or rw_r__r__),
            "a regular file was read as a symlink, so createNpmWrappers would never delete one"
        )
    }

    @Test
    fun `a symlink is a symlink`() {
        // Carries permission bits on purpose: without them a maskless
        // comparison would pass and the mask would look unnecessary.
        assertTrue(
            isSymlinkMode(symbolicLink or rwxrwxrwx),
            "a symlink with its usual 0777 mode was not recognised; the file-type mask is missing"
        )
    }

    @Test
    fun `a directory is not a symlink`() {
        // The repair pass walks directories and asks this of each one before
        // descending, so a directory read as a link would stop the walk dead.
        assertFalse(isSymlinkMode(directory or rwxr_xr_x), "a directory was read as a symlink")
    }

    @Test
    fun `other file types are not symlinks`() {
        assertFalse(isSymlinkMode(fifo or rw_r__r__), "a fifo was read as a symlink")
        assertFalse(isSymlinkMode(socket or rwxrwxrwx), "a socket was read as a symlink")
    }

    @Test
    fun `permission bits do not change the answer`() {
        // Every permission combination of one file type has to agree, or the
        // predicate is reading something other than the file type.
        for (perms in 0..0x1FF) {
            assertFalse(isSymlinkMode(regularFile or perms), "regular file with mode $perms")
            assertTrue(isSymlinkMode(symbolicLink or perms), "symlink with mode $perms")
        }
    }

    /**
     * Why the replaced rule was the wrong question, shown against a real
     * filesystem rather than argued.
     *
     * This does not exercise [isSymlink]. It builds the path shape the old rule
     * gets wrong: an ordinary file, no link in sight at its own name, reached
     * through a parent that is one. `canonicalPath` resolves that parent, so it
     * differs from `absolutePath` -- and that comparison was the entire old
     * predicate, which therefore calls this plain file a symlink.
     *
     * What this is not is a description of the device. `/data/user/0` was
     * measured on API 36 and 37 to be a separate mount rather than a link to
     * `/data/data`, so app-private paths canonicalise to themselves and the old
     * rule happened to answer correctly there. The shape below is the one that
     * breaks it, it is a shape Android has used before, and it is not ours to
     * guarantee stays absent -- which is the whole argument for a predicate that
     * does not care either way.
     */
    @Test
    fun `the replaced rule misreads a plain file under a symlinked parent`() {
        val real = File(dir, "real").apply { mkdirs() }
        val plain = File(real, "npm").apply { writeText("#!/bin/sh\n") }

        val link = File(dir, "via")
        val linked = try {
            Files.createSymbolicLink(link.toPath(), real.toPath())
            true
        } catch (e: Exception) {
            false
        }
        assumeTrue(linked, "this filesystem does not allow creating symlinks")

        val reached = File(link, "npm")
        assertTrue(reached.isFile, "the file could not be reached through the link")
        // Nothing degenerate about the fixture: it is the same file, with its
        // contents, reached by another name.
        assertTrue(plain.readText().isNotEmpty())

        // The truth, from the JDK's own lstat equivalent -- the same answer
        // Os.lstat gives the predicate that replaced the rule below.
        assertFalse(
            Files.isSymbolicLink(reached.toPath()),
            "the file reached through the link is a regular file, not a link"
        )

        // The replaced rule, written out here because it no longer exists in
        // the source. It disagrees, and calls this plain file a symlink.
        val replacedRuleSaysLink = reached.canonicalPath != reached.absolutePath
        assertTrue(
            replacedRuleSaysLink,
            "the canonical-path rule no longer misreads a plain file under a symlinked parent, " +
                "so the argument for replacing it needs rechecking rather than the code"
        )
    }
}
