package com.vscodroid.util

import android.content.Context
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Which storage rows the clear action can actually free.
 *
 * The screen offered every row to one action. Five of the seven are directories
 * that action does not touch, so choosing the device-folder mirrors, the server
 * tree, the extensions or the installed tools ran a cache clear that freed
 * nothing and then reported either "Freed 0 B" or "nothing to clear" -- neither
 * of which was about the row picked. A user out of space was told their largest
 * directory had already been dealt with.
 *
 * Pinned by behaviour rather than by reading the set back. A list that merely
 * agrees with itself is what the two sides had before; what has to hold is that
 * every key named as clearable is one `clearCaches` empties, and every key not
 * named is one it leaves alone.
 */
class ClearableStorageTest {

    @TempDir
    lateinit var filesDir: File

    @TempDir
    lateinit var cacheDir: File

    private lateinit var context: Context

    @BeforeEach
    fun setUp() {
        mockkObject(Logger)
        every { Logger.i(any(), any()) } just Runs
        every { Logger.d(any(), any()) } just Runs
        every { Logger.w(any(), any()) } just Runs
        every { Logger.e(any(), any()) } just Runs
        every { Logger.e(any(), any(), any()) } just Runs

        context = mockk(relaxed = true)
        every { context.filesDir } returns filesDir
        every { context.cacheDir } returns cacheDir
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    /** Where each breakdown key measures, so a file can be planted in it. */
    private fun directoryFor(key: String): File = when (key) {
        "vscode_server" -> File(filesDir, "server")
        "extensions" -> File(filesDir, "home/.vscodroid/extensions")
        "user_data" -> File(filesDir, "home/.vscodroid/data/User")
        "logs" -> File(filesDir, "home/.vscodroid/data/logs")
        "tools" -> File(filesDir, "usr")
        "saf_mirrors" -> File(filesDir, "saf-mirrors")
        "cache" -> File(cacheDir, "npm-cache")
        else -> error("no directory known for the breakdown key '$key'")
    }

    private val allKeys = listOf(
        "vscode_server", "extensions", "user_data", "logs", "tools", "saf_mirrors", "cache",
    )

    private fun plant(key: String): File =
        File(directoryFor(key), "planted.bin").apply {
            parentFile?.mkdirs()
            writeText("x".repeat(4096))
        }

    @Test
    fun `every key named clearable is one the action empties`() {
        for (key in StorageManager.CLEARABLE_KEYS) {
            val planted = plant(key)

            val freed = StorageManager.clearCaches(context)

            assertTrue(
                !planted.exists(),
                "'$key' is offered as clearable but $planted survived the clear",
            )
            assertTrue(freed > 0, "'$key' is offered as clearable but nothing was freed")
        }
    }

    @Test
    fun `every other key is one the action leaves alone`() {
        val others = allKeys.filterNot { it in StorageManager.CLEARABLE_KEYS }
        assertTrue(others.isNotEmpty(), "nothing is unclearable, so this proves nothing")

        for (key in others) {
            val planted = plant(key)

            StorageManager.clearCaches(context)

            assertTrue(
                planted.exists(),
                "'$key' is not offered as clearable, yet the clear deleted $planted",
            )
            planted.delete()
        }
    }

    @Test
    fun `the breakdown carries the set the screen has to branch on`() {
        val json = StorageManager.getStorageBreakdown(context)
        val clearable = json.getJSONArray("clearable")

        assertEquals(
            StorageManager.CLEARABLE_KEYS,
            (0 until clearable.length()).map { clearable.getString(it) }.toSet(),
            "the screen reads this array; without it every row is offered to one action",
        )
    }
}
