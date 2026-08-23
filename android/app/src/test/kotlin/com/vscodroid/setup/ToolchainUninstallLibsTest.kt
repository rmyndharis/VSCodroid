package com.vscodroid.setup

import android.content.Context
import android.content.res.AssetManager
import com.google.android.play.core.assetpacks.AssetPackManagerFactory
import com.vscodroid.util.Logger
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException

/**
 * Tests for the library deletion in `ToolchainManager.uninstallSync`: the call site,
 * not the decision.
 *
 * [toolchainLibsSafeToRemove] is covered by [ToolchainLibsTest] in both directions:
 * a null listing removes nothing, an empty listing removes everything named. Both stayed
 * green while the call site could be changed from passing `null` to passing `emptySet()`
 * when the base listing cannot be read, which turns "keep everything, we cannot tell"
 * into "delete everything, the base ships nothing".
 *
 * That is the `libffi.so` bug the comment above the call site describes: Ruby's manifest
 * names it, Python's `_ctypes` links it, and deleting it on a Ruby uninstall broke
 * `import ctypes` until the next app update, with nothing pointing at the uninstall.
 *
 * Both directions are asserted here. A test that only checked the unreadable case would
 * also pass against a call site that never deletes anything at all.
 */
class ToolchainUninstallLibsTest {

    @TempDir
    lateinit var filesDir: File

    private lateinit var context: Context
    private lateinit var assets: AssetManager
    private lateinit var libDir: File

    /** Ships in the base APK too: Python's _ctypes links it. */
    private val shared = "libffi.so"

    /** Ships only with this toolchain. */
    private val ownOnly = "libruby.so"

    @BeforeEach
    fun setUp() {
        mockkObject(Logger)
        every { Logger.d(any(), any()) } just Runs
        every { Logger.i(any(), any()) } just Runs
        every { Logger.w(any(), any(), any()) } just Runs
        every { Logger.e(any(), any(), any()) } just Runs

        // The constructor reaches Play Core through field initialisation, so it runs
        // before any method can be called. mockkConstructor does not help: the
        // constructor body still executes.
        mockkStatic(AssetPackManagerFactory::class)
        every { AssetPackManagerFactory.getInstance(any()) } returns mockk(relaxed = true)

        assets = mockk(relaxed = true)
        context = mockk(relaxed = true)
        every { context.filesDir } returns filesDir
        every { context.assets } returns assets

        libDir = File(filesDir, "usr/lib").apply { mkdirs() }
        File(libDir, shared).writeText("elf")
        File(libDir, ownOnly).writeText("elf")

        File(filesDir, "home/.vscodroid").mkdirs()
        File(filesDir, "home/.vscodroid/toolchains.json").writeText(
            """[{"name":"ruby","libs":["$shared","$ownOnly"]}]"""
        )
    }

    @AfterEach
    fun tearDown() {
        unmockkObject(Logger)
        unmockkStatic(AssetPackManagerFactory::class)
    }

    /** `uninstallSync` is private; `uninstall` hands the work to an executor. */
    private fun uninstallSync(name: String) {
        val m = ToolchainManager::class.java
            .getDeclaredMethod("uninstallSync", String::class.java)
            .apply { isAccessible = true }
        m.invoke(ToolchainManager(context), name)
    }

    /**
     * When the base listing cannot be read the uninstall must delete nothing. A leftover
     * library is reclaimed by the next install; a deleted base library is not.
     */
    @Test
    fun `an unreadable base listing deletes no libraries`() {
        every { assets.list("usr/lib") } throws IOException("assets unavailable")

        uninstallSync("ruby")

        assertTrue(File(libDir, shared).exists(), "$shared was deleted despite an unreadable base listing")
        assertTrue(File(libDir, ownOnly).exists(), "$ownOnly was deleted despite an unreadable base listing")
    }

    /**
     * The other direction, and it is what makes the test above mean something: with a
     * readable listing the uninstall does delete, and keeps only what the base also
     * ships. Without this, a call site that never deleted anything would pass.
     */
    @Test
    fun `a readable base listing deletes only what the base does not ship`() {
        every { assets.list("usr/lib") } returns arrayOf(shared)

        uninstallSync("ruby")

        assertTrue(File(libDir, shared).exists(), "$shared is shipped by the base install and must survive")
        assertFalse(File(libDir, ownOnly).exists(), "$ownOnly belongs only to this toolchain and should be gone")
    }

    /**
     * The base APK is not the only other owner of `usr/lib`.
     *
     * A second installed toolchain ships its libraries into the same directory,
     * and the protected set counted one of the two sharers. Removing Ruby then
     * took a library Java also ships, and Java failed at its next `dlopen` with
     * an error naming the library rather than the uninstall that removed it.
     * No two shipped manifests name the same library today, which is why this is
     * a floor rather than a repair, and why it needs a fixture to be visible.
     */
    @Test
    fun `a library another installed toolchain also ships survives`() {
        every { assets.list("usr/lib") } returns emptyArray()
        File(filesDir, "home/.vscodroid/toolchains.json").writeText(
            """
            [{"name":"ruby","libs":["$shared","$ownOnly"]},
             {"name":"java","libs":["$shared"]}]
            """.trimIndent()
        )

        uninstallSync("ruby")

        assertTrue(
            File(libDir, shared).exists(),
            "$shared is named by the Java install too, and removing Ruby deleted it",
        )
        assertFalse(File(libDir, ownOnly).exists(), "$ownOnly belongs only to Ruby")
    }

    /** `list` returning null is the same "cannot tell" as throwing, and must be treated alike. */
    @Test
    fun `a null base listing deletes no libraries`() {
        every { assets.list("usr/lib") } returns null

        uninstallSync("ruby")

        assertTrue(File(libDir, shared).exists(), "$shared was deleted on a null listing")
        assertTrue(File(libDir, ownOnly).exists(), "$ownOnly was deleted on a null listing")
    }
}
