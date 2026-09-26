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
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * That an upgrade removes the server's `node_modules/@github` packages the new
 * tree no longer ships, and nothing it cannot account for.
 *
 * Up to Code - OSS 1.138 the tree carried the agent host's Copilot CLI there,
 * `copilot` and `copilot-linux-arm64`, and earlier releases built a
 * `copilot-android-arm64` alias over it at runtime. From 1.139 none of the three
 * is shipped or loaded, and extraction merges without deleting, so an upgraded
 * device kept all of it. `copilot-sdk` is still shipped and has to stay.
 */
class UnshippedServerPackageTest {

    @TempDir
    lateinit var filesDir: File

    @TempDir
    lateinit var outside: File

    private lateinit var context: Context
    private lateinit var assets: AssetManager

    private val gh by lazy { File(filesDir, "server/vscode-reh/node_modules/@github") }
    private val extensionGh by lazy {
        File(filesDir, "server/vscode-reh/extensions/copilot/node_modules/@github")
    }

    @BeforeEach
    fun setUp() {
        mockkObject(Logger)
        every { Logger.i(any(), any()) } just Runs
        assets = mockk()
        context = mockk(relaxed = true)
        every { context.filesDir } returns filesDir
        every { context.assets } returns assets

        // What a device upgrading from 1.4.0 carries.
        File(gh, "copilot").mkdirs()
        File(gh, "copilot/package.json").writeText("{}")
        File(gh, "copilot-linux-arm64").mkdirs()
        File(gh, "copilot-linux-arm64/index.js").writeText("// the cli")
        File(gh, "copilot-sdk/dist").mkdirs()
        File(gh, "copilot-sdk/package.json").writeText("{}")
        File(gh, "copilot-android-arm64").mkdirs()
        File(gh, "copilot-android-arm64/package.json").writeText("{}")
        Files.createSymbolicLink(
            File(gh, "copilot-android-arm64/index.js").toPath(),
            Path.of("../copilot-linux-arm64/index.js"),
        )

        // The Copilot extension's own packages, which this prune must never reach.
        File(extensionGh, "copilot/sdk").mkdirs()
        File(extensionGh, "copilot/sdk/index.js").writeText("// the sdk")
        File(extensionGh, "copilot-android-arm64").mkdirs()
        File(extensionGh, "copilot-android-arm64/package.json").writeText("{}")
    }

    @AfterEach
    fun tearDown() = unmockkObject(Logger)

    private fun prune() {
        FirstRunSetup::class.java
            .getDeclaredMethod("pruneUnshippedServerEntries", String::class.java)
            .apply { isAccessible = true }
            .invoke(FirstRunSetup(context), "vscode-reh/node_modules/@github")
    }

    private fun shipping(vararg names: String) {
        every { assets.list("vscode-reh/node_modules/@github") } returns arrayOf(*names)
    }

    /**
     * NEGATIVE CONTROL: drop the `pruneUnshippedServerEntries` call for
     * `node_modules/@github` from `runPreExtractionMigrations`; the run-level case
     * in [PreExtractionReclaimTest] reddens. This one reddens if the filter is
     * turned into "remove everything".
     */
    @Test
    fun `packages the tree no longer ships are removed and the shipped one stays`() {
        shipping("copilot-sdk")

        prune()

        assertEquals(
            listOf("copilot-sdk"), gh.list()!!.sorted(),
            "the prune did not leave exactly the package the tree ships",
        )
        assertTrue(
            File(gh, "copilot-sdk/package.json").isFile,
            "the SDK the new tree ships was removed along with the orphans",
        )
    }

    /**
     * An entry that is itself a link is unlinked, and what it points at is left.
     *
     * NEGATIVE CONTROL: replace `StorageManager.deleteRecursive` with
     * `File.deleteRecursively` in `pruneUnshippedServerEntries`. It follows the
     * link, empties the directory behind it, and this reddens.
     */
    @Test
    fun `an alias is removed as a link, never followed`() {
        val target = File(outside, "somewhere-else")
        assertTrue(target.mkdirs())
        File(target, "keep.txt").writeText("not the app's")
        val link = File(gh, "copilot-linux-x64")
        assumeTrue(
            runCatching { Files.createSymbolicLink(link.toPath(), target.toPath()) }.isSuccess,
            "this filesystem does not allow creating symlinks",
        )
        shipping("copilot-sdk")

        prune()

        assertFalse(Files.exists(link.toPath(), LinkOption.NOFOLLOW_LINKS), "the link survived")
        assertFalse(
            Files.exists(File(gh, "copilot-android-arm64").toPath(), LinkOption.NOFOLLOW_LINKS),
            "the alias directory built over the old runtime survived",
        )
        assertEquals(
            "not the app's", File(target, "keep.txt").readText(),
            "removing a link emptied the directory it points at",
        )
    }

    /**
     * NEGATIVE CONTROL: narrow the `isNullOrEmpty` guard to a null check. An
     * empty listing then reads as a build that ships nothing, everything goes,
     * and this reddens.
     */
    @Test
    fun `a listing that cannot be read or is empty removes nothing`() {
        every { assets.list("vscode-reh/node_modules/@github") } throws IOException("assets unavailable")
        prune()
        assertTrue(
            File(gh, "copilot-linux-arm64").isDirectory,
            "an unreadable listing was read as an empty build",
        )

        every { assets.list("vscode-reh/node_modules/@github") } returns emptyArray()
        prune()
        assertTrue(File(gh, "copilot-sdk").isDirectory, "an empty listing removed every package")

        every { assets.list("vscode-reh/node_modules/@github") } returns null
        prune()
        assertTrue(File(gh, "copilot").isDirectory, "a null listing removed every package")
    }

    @Test
    fun `the Copilot extension's own packages are untouched`() {
        shipping("copilot-sdk")

        prune()

        assertTrue(File(extensionGh, "copilot/sdk/index.js").isFile, "the extension's SDK was removed")
        assertTrue(
            File(extensionGh, "copilot-android-arm64/package.json").isFile,
            "the extension's alias was removed",
        )
    }
}
