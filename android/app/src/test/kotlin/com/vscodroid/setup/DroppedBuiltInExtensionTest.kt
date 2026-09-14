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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException

/**
 * That an upgrade removes a built-in extension its server tree dropped, and nothing
 * it cannot account for. Extraction merges, and the server loads every directory
 * under `server/vscode-reh/extensions`, so a dropped or renamed built-in kept loading
 * beside its replacement.
 */
class DroppedBuiltInExtensionTest {

    @TempDir
    lateinit var filesDir: File

    private lateinit var context: Context
    private lateinit var assets: AssetManager
    private val builtIns by lazy { File(filesDir, "server/vscode-reh/extensions") }

    @BeforeEach
    fun setUp() {
        mockkObject(Logger)
        every { Logger.i(any(), any()) } just Runs
        assets = mockk()
        context = mockk(relaxed = true)
        every { context.filesDir } returns filesDir
        every { context.assets } returns assets
        File(builtIns, "git").mkdirs()
        File(builtIns, "copilot/node_modules").mkdirs()
        File(builtIns, "image-preview").mkdirs()
        File(builtIns, "image-preview/package.json").writeText("{}")
    }

    @AfterEach
    fun tearDown() = unmockkObject(Logger)

    private fun prune() {
        FirstRunSetup::class.java
            .getDeclaredMethod("pruneDroppedBuiltInExtensions")
            .apply { isAccessible = true }
            .invoke(FirstRunSetup(context))
    }

    @Test
    fun `a built-in the new tree no longer carries is removed and the rest stay`() {
        every { assets.list("vscode-reh/extensions") } returns arrayOf("git", "copilot", "media-preview")

        prune()

        assertFalse(File(builtIns, "image-preview").exists(), "the dropped built-in still loads beside its replacement")
        assertTrue(File(builtIns, "git").isDirectory, "a built-in the build still ships was removed")
        assertTrue(File(builtIns, "copilot/node_modules").isDirectory, "the Copilot extension was removed")
    }

    @Test
    fun `a listing that cannot be read removes nothing`() {
        every { assets.list("vscode-reh/extensions") } throws IOException("assets unavailable")
        prune()
        assertTrue(File(builtIns, "image-preview").isDirectory, "an unreadable listing was read as an empty build")

        every { assets.list("vscode-reh/extensions") } returns emptyArray()
        prune()
        assertTrue(File(builtIns, "git").isDirectory, "an empty listing removed every built-in")
    }
}
