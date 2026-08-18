package com.vscodroid.setup

import android.content.Context
import com.google.android.play.core.assetpacks.AssetPackManager
import com.google.android.play.core.assetpacks.AssetPackManagerFactory
import com.vscodroid.util.Logger
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.json.JSONArray
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * A toolchain the app has stopped offering has to leave the devices that already
 * have it.
 *
 * The two halves of the record disagree on purpose: the card list is built from
 * [ToolchainRegistry.available], while the install record, the payload and the
 * loader wrappers are all read from `toolchains.json`. Dropping a registry entry
 * therefore takes the Remove button away and leaves everything else, which is
 * the one outcome that helps nobody: a toolchain the user cannot use and cannot
 * delete. These pin the sweep that closes that gap.
 */
class RetiredToolchainTest {

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

        // Play Core is reached through field initialisation, so it runs before
        // any method can be called on the manager.
        val packManager = mockk<AssetPackManager>(relaxed = true)
        mockkStatic(AssetPackManagerFactory::class)
        every { AssetPackManagerFactory.getInstance(any()) } returns packManager

        context = mockk(relaxed = true)
        every { context.filesDir } returns filesDir
        every { context.cacheDir } returns File(filesDir, "cache")
        every { context.packageName } returns "com.vscodroid"
        File(filesDir, "home/.vscodroid").mkdirs()
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    private fun manager() = ToolchainManager(context)

    private fun writeState(json: String) {
        File(filesDir, "home/.vscodroid").mkdirs()
        stateFile().writeText(json)
    }

    private fun stateFile() = File(filesDir, "home/.vscodroid/toolchains.json")

    private fun sweep(m: ToolchainManager) {
        val method = ToolchainManager::class.java
            .getDeclaredMethod("removeRetiredToolchainsSync")
        method.isAccessible = true
        method.invoke(m)
    }

    private fun stateNames(): List<String> {
        val f = stateFile()
        if (!f.isFile) return emptyList()
        val arr = JSONArray(f.readText())
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("name") }
    }

    @Test
    fun `a retired toolchain is removed from the record`() {
        writeState("""[{"name":"go","installRoot":"usr/opt/go"},{"name":"ruby","installRoot":"usr/opt/ruby"}]""")
        val root = File(filesDir, "usr/opt/go").apply { mkdirs() }
        File(root, "bin").mkdirs()
        File(root, "bin/go").writeText("payload")

        sweep(manager())

        assertEquals(
            listOf("ruby"),
            stateNames(),
            "the retired toolchain is still recorded as installed, so its wrappers " +
                "keep being written and its payload keeps its disk",
        )
    }

    @Test
    fun `the payload goes with it`() {
        writeState("""[{"name":"go","installRoot":"usr/opt/go"}]""")
        val root = File(filesDir, "usr/opt/go").apply { mkdirs() }
        File(root, "bin").mkdirs()
        File(root, "bin/go").writeText("179 MB, in spirit")

        sweep(manager())

        assertFalse(
            root.exists(),
            "the record was cleared but the files were left, which is the worst " +
                "half of both outcomes: no way to run it and no space back",
        )
    }

    @Test
    fun `a toolchain the app still offers is left alone`() {
        writeState("""[{"name":"ruby","installRoot":"usr/opt/ruby"},{"name":"java","installRoot":"usr/opt/java"}]""")
        val ruby = File(filesDir, "usr/opt/ruby").apply { mkdirs() }
        File(ruby, "keep").writeText("x")

        sweep(manager())

        assertEquals(listOf("ruby", "java"), stateNames(), "the sweep took something it was not asked for")
        assertTrue(ruby.exists(), "a toolchain still on offer had its payload deleted")
    }

    @Test
    fun `running twice is not an error`() {
        writeState("""[{"name":"go","installRoot":"usr/opt/go"}]""")
        File(filesDir, "usr/opt/go").mkdirs()

        val m = manager()
        sweep(m)
        sweep(m)

        assertEquals(emptyList<String>(), stateNames())
    }

    @Test
    fun `every retired name is one the registry no longer offers`() {
        // The two lists have to disagree, and this is the direction that matters:
        // a name in both would be offered on the picker and deleted on the next
        // launch, so the user downloads it repeatedly and never keeps it.
        val retiredField = Class.forName("com.vscodroid.setup.ToolchainManagerKt")
            .getDeclaredField("RETIRED_TOOLCHAINS")
        retiredField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val retired = (retiredField.get(null) as Map<String, Long>).keys

        assertTrue(retired.isNotEmpty(), "nothing is retired, so this test proves nothing; delete it or the sweep")
        val offered = ToolchainRegistry.available
            .map { it.packName.removePrefix("toolchain_") }
            .toSet()
        val both = retired intersect offered
        assertTrue(
            both.isEmpty(),
            "offered and retired at once: $both. The picker would install these and the " +
                "next launch would delete them.",
        )
    }
}
