package com.vscodroid.setup

import android.content.Context
import com.vscodroid.util.Logger
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Tests the directory creation every later step writes into.
 *
 * The predicate is the whole of it. Asking `exists()` answers yes for a plain
 * file sitting at the path, so `mkdirs()` was skipped and the run continued as
 * though the directory were there -- every later write into it then failing on
 * a state this method exists to prevent. `isDirectory` asks the question that
 * was meant.
 *
 * `mkdirs()` cannot repair that case; it will not replace a file. So the
 * difference this makes is not a directory that now appears, it is a failure
 * that is now reported instead of passed over in silence. That is the whole
 * claim, and it is what these assert.
 */
class CreateDirectoriesTest {

    @TempDir
    lateinit var filesDir: File

    @TempDir
    lateinit var cacheDir: File

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
        every { context.cacheDir } returns cacheDir
        every { context.getExternalFilesDir(null) } returns File(filesDir, "external")
    }

    @AfterEach
    fun tearDown() = unmockkObject(Logger)

    private fun createDirectories() {
        FirstRunSetup::class.java
            .getDeclaredMethod("createDirectories")
            .apply { isAccessible = true }
            .invoke(FirstRunSetup(context))
    }

    /**
     * The control. Without it every assertion below would also hold for a
     * method that had stopped creating anything.
     */
    @Test
    fun `creates the tree and says nothing about it`() {
        createDirectories()

        for (dir in listOf("home/.ssh", "home/.vscodroid/extensions", "usr/lib/git-core")) {
            assertTrue(File(filesDir, dir).isDirectory, "$dir was not created")
        }
        assertTrue(File(cacheDir, "tmp").isDirectory, "the tmp directory was not created")
        verify(exactly = 0) { Logger.w(any(), match { it.startsWith("Could not create") }, any()) }
    }

    @Test
    fun `reports a path occupied by a plain file instead of passing over it`() {
        val blocked = File(filesDir, "home/.ssh")
        blocked.parentFile?.mkdirs()
        blocked.writeText("not a directory")

        createDirectories()

        verify {
            Logger.w(any(), match { it.startsWith("Could not create home/.ssh") }, any())
        }
    }

    @Test
    fun `reports an occupied tmp path too`() {
        // The second writer of this path. ProcessManager re-checks it on every
        // server start and is the effective backstop, but a first run that
        // cannot make it should not be the one staying quiet.
        File(cacheDir, "tmp").writeText("not a directory")

        createDirectories()

        verify {
            Logger.w(any(), match { it.startsWith("Could not create the tmp directory") }, any())
        }
    }
}
