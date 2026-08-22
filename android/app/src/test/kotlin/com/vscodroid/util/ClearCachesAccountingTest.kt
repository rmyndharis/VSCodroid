package com.vscodroid.util

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission

/**
 * That the figure the clear-caches action reports is what went, not what was tried.
 *
 * The number reaches a person through the bridge and is rendered as
 * "Freed N of cached data.", and the person reading it opened that screen because they
 * are out of disk. A total assembled from the lengths of files the walk merely *reached*
 * cannot be wrong: whatever refuses to unlink is charged to it in full, so a clear that
 * released nothing still reports the whole tree, free space does not move, and the next
 * operation fails with ENOSPC against a message that said it had been fixed.
 *
 * The refusal is made real rather than mocked: the file sits in a directory with its
 * write bit cleared, which is what `unlink` consults. That is also why every case here
 * asserts the file survived. Run as root the permission would not bite, and without that
 * assertion this file would pass by measuring an ordinary successful delete.
 */
class ClearCachesAccountingTest {

    @TempDir
    lateinit var tmp: File

    private lateinit var context: Context
    private lateinit var filesDir: File
    private lateinit var cacheDir: File
    private lateinit var sealed: File

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
        sealed = File(cacheDir, "npm-cache/sealed")
    }

    @AfterEach
    fun tearDown() {
        // Given back before the temporary directory is swept, or the sweep inherits the
        // very refusal this file arranges.
        if (sealed.isDirectory) openUp(sealed)
    }

    private fun seal(dir: File) = Files.setPosixFilePermissions(
        dir.toPath(),
        setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE),
    )

    private fun openUp(dir: File) = Files.setPosixFilePermissions(
        dir.toPath(),
        setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE,
        ),
    )

    private fun write(path: String, bytes: Int): File =
        File(cacheDir, path).apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(bytes))
        }

    /** The case the count exists for: something under the cache will not unlink. */
    @Test
    fun `a file that will not unlink is not counted as freed`() {
        val gone = write("tmp/scratch.bin", 100)
        val stays = write("npm-cache/sealed/big.bin", 1_000)
        seal(sealed)

        val freed = StorageManager.clearCaches(context)

        assertTrue(
            stays.isFile,
            "the sealed file was removed anyway, so this run measured an ordinary " +
                "delete and proves nothing about a refusal",
        )
        assertTrue(!gone.exists(), "the ordinary file should have gone")
        assertEquals(
            100L, freed,
            "the refused file's 1000 bytes were reported as freed, so the figure is " +
                "what the walk reached rather than what the filesystem released",
        )
    }

    /**
     * The control, and it is what stops the case above passing because the count stopped
     * counting. Nothing refuses here, so every byte is still reported.
     */
    @Test
    fun `everything that does unlink is still counted`() {
        write("tmp/scratch.bin", 100)
        write("npm-cache/sealed/big.bin", 1_000)

        val freed = StorageManager.clearCaches(context)

        assertEquals(1_100L, freed, "an unobstructed clear under-reported what it freed")
    }
}
