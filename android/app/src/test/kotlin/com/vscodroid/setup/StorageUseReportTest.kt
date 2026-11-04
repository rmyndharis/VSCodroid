package com.vscodroid.setup

import android.content.Context
import com.vscodroid.util.Logger
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The line a first run leaves about its own storage cost.
 *
 * The pre-flight asks for the payload plus a modelled allocation overhead, and the model is
 * only as good as the measurements behind it. One was taken by hand on a device with f2fs and
 * file-based encryption; every first run on every device is another, if it says what it saw.
 * These pin the arithmetic, the filesystem lookup that makes a report attributable, and the
 * rule that a run which wrote little reports nothing.
 */
class StorageUseReportTest {

    @TempDir
    lateinit var filesDir: File

    private lateinit var context: Context

    @BeforeEach
    fun setUp() {
        mockkObject(Logger)
        every { Logger.d(any(), any()) } just Runs
        every { Logger.i(any(), any()) } just Runs
        every { Logger.w(any(), any(), any()) } just Runs
        every { Logger.e(any(), any(), any()) } just Runs
        context = mockk(relaxed = true)
        every { context.filesDir } returns filesDir
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    @Test
    fun `the report names what was used, what the files weigh, the difference and the model`() {
        assertEquals(
            "First-run setup used 700 MB of storage for 599 MB of files on f2fs: " +
                "101 MB beyond the files, against 178 MB modelled",
            FirstRunSetup.storageUseReport(700_000_000L, 599_000_000L, 178_000_000L, "f2fs"),
        )
    }

    /** Other apps write while setup runs, so the difference can come out negative, and is still said. */
    @Test
    fun `a negative difference is reported as measured`() {
        assertEquals(
            "First-run setup used 590 MB of storage for 599 MB of files on ext4: " +
                "-9 MB beyond the files, against 178 MB modelled",
            FirstRunSetup.storageUseReport(590_000_000L, 599_000_000L, 178_000_000L, "ext4"),
        )
    }

    private val mounts = listOf(
        "rootfs / rootfs ro 0 0",
        "/dev/block/dm-5 /data ext4 rw,seclabel 0 0",
        "/dev/block/dm-48 /data/media f2fs rw,seclabel 0 0",
        "tmpfs /data/with\\040space tmpfs rw 0 0",
    )

    @Test
    fun `the filesystem is the type of the longest mount point that holds the path`() {
        assertEquals("ext4", FirstRunSetup.fileSystemOf("/data/user/0/com.vscodroid/files", mounts))
        assertEquals("f2fs", FirstRunSetup.fileSystemOf("/data/media/0/Download", mounts))
        assertEquals("f2fs", FirstRunSetup.fileSystemOf("/data/media", mounts))
        assertEquals("rootfs", FirstRunSetup.fileSystemOf("/system/bin", mounts))
    }

    /** `/data/media` does not hold `/data/mediaplayer`: compared by components, not by characters. */
    @Test
    fun `a mount point holds whole path components only`() {
        assertEquals("ext4", FirstRunSetup.fileSystemOf("/data/mediaplayer/x", mounts))
    }

    @Test
    fun `a space written as an escape in a mount point is undone`() {
        assertEquals("tmpfs", FirstRunSetup.fileSystemOf("/data/with space/x", mounts))
    }

    /** A later mount over the same point is the one in effect, as `/proc/mounts` lists them. */
    @Test
    fun `a later mount at the same point wins`() {
        assertEquals(
            "f2fs",
            FirstRunSetup.fileSystemOf("/data/x", listOf("a /data ext4 rw 0 0", "b /data f2fs rw 0 0")),
        )
    }

    @Test
    fun `no mounts name no filesystem`() {
        assertEquals("unknown", FirstRunSetup.fileSystemOf("/data/user/0", emptyList()))
        assertEquals("unknown", FirstRunSetup.fileSystemOf("/data/user/0", listOf("malformed")))
    }

    private fun logStorageUse(before: Long, payload: Long, modelled: Long) {
        FirstRunSetup::class.java
            .getDeclaredMethod("logStorageUse", Long::class.javaPrimitiveType, Long::class.javaPrimitiveType, Long::class.javaPrimitiveType)
            .apply { isAccessible = true }
            .invoke(FirstRunSetup(context), before, payload, modelled)
    }

    /** What a repair-only run writes tells nothing about allocation overhead. */
    @Test
    fun `a run that wrote little says nothing`() {
        logStorageUse(filesDir.usableSpace + 50_000_000L, FirstRunSetup.MEASURABLE_PAYLOAD_BYTES - 1, 0L)

        verify(exactly = 0) { Logger.i(any(), match { it.startsWith("First-run setup used") }) }
    }

    @Test
    fun `a run that wrote the tree reports itself`() {
        val line = slot<String>()
        every { Logger.i(any(), capture(line)) } just Runs

        logStorageUse(filesDir.usableSpace + 650_000_000L, 599_000_000L, 178_000_000L)

        assertTrue(line.isCaptured, "nothing was logged")
        assertTrue(
            line.captured.startsWith("First-run setup used 6") &&
                line.captured.contains("MB of storage for 599 MB of files on ") &&
                line.captured.endsWith("against 178 MB modelled"),
            "the line does not carry the figures: ${line.captured}",
        )
    }
}
