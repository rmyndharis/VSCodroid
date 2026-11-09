package com.vscodroid.setup

import android.content.Context
import android.content.res.AssetManager
import com.vscodroid.util.Logger
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * That an update removes the ICU libraries it replaced, and nothing it cannot account for.
 *
 * ICU is versioned by its soname, so `libicuuc.so.78` and `libicuuc.so.79` are two files, and
 * the Node runtime in the APK asks for exactly one of them. Extraction writes the libraries the
 * APK carries and never removes, so an update across an ICU major kept the previous three, about
 * 38 MB of which `libicudata` is 33, and nothing loads them. No toolchain pack needs them
 * either: none of the Ruby, Java and C/C++ packs shipped from 1.5.0 to 1.7.0 links ICU.
 */
class SupersededIcuTest {

    @TempDir
    lateinit var filesDir: File

    @TempDir
    lateinit var outside: File

    private lateinit var context: Context
    private lateinit var assets: AssetManager

    private val libDir by lazy { File(filesDir, "usr/lib") }

    @BeforeEach
    fun setUp() {
        mockkObject(Logger)
        every { Logger.i(any(), any()) } just Runs
        assets = mockk()
        every { assets.list(any()) } returns emptyArray()
        context = mockk(relaxed = true)
        every { context.filesDir } returns filesDir
        every { context.assets } returns assets

        // What a device updating from the release before carries beside the libraries
        // the update does not touch.
        for (name in listOf(
            "libicudata.so.78", "libicui18n.so.78", "libicuuc.so.78",
            "libz.so.1", "libssl.so.3", "libcrypto.so.3", "libpython3.14.so", "libc++_shared.so",
        )) {
            File(libDir, name).apply { parentFile?.mkdirs() }.writeText("elf")
        }
    }

    @AfterEach
    fun tearDown() = unmockkObject(Logger)

    private fun prune() {
        FirstRunSetup::class.java
            .getDeclaredMethod("pruneSupersededIcu")
            .apply { isAccessible = true }
            .invoke(FirstRunSetup(context))
    }

    private fun shipping(vararg names: String) {
        every { assets.list("usr/lib") } returns arrayOf(*names)
    }

    private fun onDisk() = libDir.list()!!.sorted()

    private val icu79 = arrayOf("libicudata.so.79", "libicui18n.so.79", "libicuuc.so.79")

    /**
     * NEGATIVE CONTROL: drop the `pruneSupersededIcu` call from `runSetupLocked`; the run-level
     * case in [PreExtractionReclaimTest] reddens. This one reddens if the filter is turned into
     * "remove every ICU library".
     */
    @Test
    fun `the libraries of a major the APK replaced are removed and the rest stay`() {
        shipping(*icu79, "libz.so.1", "libssl.so.3")

        prune()

        assertEquals(
            listOf("libc++_shared.so", "libcrypto.so.3", "libpython3.14.so", "libssl.so.3", "libz.so.1"),
            onDisk(),
            "the prune did not remove exactly the three ICU 78 libraries",
        )
    }

    /** Extraction may have run already, or a retry may follow it: the major the APK carries stays. */
    @Test
    fun `the major the APK carries is never removed`() {
        for (name in icu79) File(libDir, name).writeText("elf")
        shipping(*icu79)

        prune()

        assertEquals(icu79.toList(), onDisk().filter { it.startsWith("libicu") })
    }

    @Test
    fun `a library the APK does not carry under another major is left alone`() {
        // The APK has an ICU library, but not these two: nothing says they were replaced.
        shipping("libicuuc.so.79")

        prune()

        assertEquals(
            listOf("libicudata.so.78", "libicui18n.so.78"),
            onDisk().filter { it.startsWith("libicu") },
        )
    }

    @Test
    fun `an APK that carries no ICU removes nothing`() {
        shipping("libz.so.1", "libssl.so.3")
        val before = onDisk()

        prune()

        assertEquals(before, onDisk())
    }

    @Test
    fun `an unreadable listing removes nothing`() {
        every { assets.list("usr/lib") } throws IOException("no listing")
        val before = onDisk()

        prune()

        assertEquals(before, onDisk())
    }

    @Test
    fun `an empty listing removes nothing`() {
        every { assets.list("usr/lib") } returns emptyArray()
        val before = onDisk()

        prune()

        assertEquals(before, onDisk())
    }

    /**
     * A link is unlinked and what it points at is left, the rule every other prune here keeps.
     *
     * NEGATIVE CONTROL: replace `StorageManager.deleteRecursive` with `File.deleteRecursively`
     * and a directory behind a link would be emptied; for a file link the target survives either
     * way, so this one guards the link itself being removed.
     */
    @Test
    fun `a link named like a replaced library is removed as a link, never followed`() {
        val target = File(outside, "not-the-apps.txt").apply { writeText("keep") }
        val link = File(libDir, "libicuuc.so.78")
        assertTrue(link.delete())
        assumeTrue(
            runCatching { Files.createSymbolicLink(link.toPath(), target.toPath()) }.isSuccess,
            "this filesystem does not allow creating symlinks",
        )
        shipping(*icu79)

        prune()

        assertFalse(Files.exists(link.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS), "the link survived")
        assertEquals("keep", target.readText(), "the file behind the link was touched")
    }

    @Test
    fun `a usr lib that is a link out of the app is not entered`() {
        val real = File(outside, "lib").apply { mkdirs() }
        File(real, "libicuuc.so.78").writeText("elf of another app")
        val usr = File(filesDir, "usr")
        libDir.deleteRecursively()
        assumeTrue(
            runCatching { Files.createSymbolicLink(libDir.toPath(), real.toPath()) }.isSuccess,
            "this filesystem does not allow creating symlinks",
        )
        assertTrue(usr.isDirectory)
        shipping(*icu79)

        prune()

        assertTrue(File(real, "libicuuc.so.78").isFile, "a library behind a redirected usr/lib was removed")
    }

    // The decision on its own, away from the filesystem.

    @Test
    fun `only a soname of a carried library at another major is named`() {
        val shipped = listOf("libicudata.so.79", "libicui18n.so.79", "libicuuc.so.79", "libz.so.1")
        val present = listOf(
            "libicudata.so.78", "libicui18n.so.78", "libicuuc.so.78", "libicuuc.so.77",
            "libicuuc.so.79", "libicudata.so.79",
            // Not a soname: a development link, a full version, a backup, another prefix.
            "libicuuc.so", "libicuuc.so.78.1", "libicuuc.so.78.bak", "libicuuc.a",
            "libicu-extra.so.78", "notes-libicuuc.so.78",
            // Other libraries with a major.
            "libz.so.1", "libssl.so.3", "libz.so.0",
        )

        assertEquals(
            listOf("libicudata.so.78", "libicui18n.so.78", "libicuuc.so.78", "libicuuc.so.77"),
            supersededIcuEntries(present, shipped),
        )
    }

    @Test
    fun `an APK that carries two majors of one library keeps both`() {
        val shipped = listOf("libicuuc.so.78", "libicuuc.so.79")

        assertEquals(
            listOf("libicuuc.so.77"),
            supersededIcuEntries(listOf("libicuuc.so.77", "libicuuc.so.78", "libicuuc.so.79"), shipped),
        )
    }

    @Test
    fun `nothing is named without a carried library`() {
        assertEquals(emptyList<String>(), supersededIcuEntries(listOf("libicuuc.so.78"), emptyList()))
        assertEquals(emptyList<String>(), supersededIcuEntries(listOf("libicuuc.so.78"), listOf("libz.so.1")))
        assertEquals(emptyList<String>(), supersededIcuEntries(emptyList(), listOf("libicuuc.so.79")))
    }
}
