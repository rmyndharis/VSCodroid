package com.vscodroid.setup

import android.content.Context
import com.google.android.play.core.assetpacks.AssetPackManagerFactory
import com.vscodroid.util.Logger
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The launch repair pass, against the binaries a toolchain keeps outside its own
 * install root.
 *
 * [ExecutableRepairTest] covers the tree walk itself, which is the part that was
 * already testable. What it cannot see is which trees the pass is pointed at,
 * and that is where Ruby fell through: its `installRoot` is `usr/lib/ruby` while
 * the interpreter is `usr/bin/ruby`, so the one file the toolchain cannot run
 * without sat outside the only directory being walked. The pass then marked the
 * entry repaired and never looked at it again.
 *
 * Java is unaffected and is not a useful fixture here: every binary it ships is
 * under its install root, so a walk of that root reaches all of them.
 */
class ToolchainRepairBinariesTest {

    @TempDir
    lateinit var filesDir: File

    private lateinit var context: Context

    @BeforeEach
    fun setUp() {
        mockkObject(Logger)
        every { Logger.d(any(), any()) } just Runs
        every { Logger.i(any(), any()) } just Runs
        every { Logger.w(any(), any()) } just Runs
        every { Logger.w(any(), any(), any()) } just Runs
        every { Logger.e(any(), any(), any()) } just Runs

        // Play Core is reached through field initialisation, so it runs before
        // any method can be called on the manager.
        mockkStatic(AssetPackManagerFactory::class)
        every { AssetPackManagerFactory.getInstance(any()) } returns mockk(relaxed = true)

        context = mockk(relaxed = true)
        every { context.filesDir } returns filesDir

        File(filesDir, "home/.vscodroid").mkdirs()
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    /** The four bytes every ELF object starts with, and no execute bit. */
    private fun elf(path: String): File = File(filesDir, path).apply {
        parentFile?.mkdirs()
        writeBytes(
            byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()) +
                ByteArray(64)
        )
        setExecutable(false, false)
    }

    private fun stateFile() = File(filesDir, "home/.vscodroid/toolchains.json")

    /** A Ruby install as it is actually recorded, minus what this does not read. */
    private fun recordRuby() = stateFile().writeText(
        """
        [{"name":"ruby",
          "installRoot":"usr/lib/ruby",
          "binaries":["usr/bin/ruby","usr/bin/gem"]}]
        """.trimIndent()
    )

    /** The pass runs on an executor in production; this is its body. */
    private fun repair() =
        ToolchainManager::class.java
            .getDeclaredMethod("repairInstalledToolchainsSync")
            .apply { isAccessible = true }
            .invoke(ToolchainManager(context))

    @Test
    fun `the interpreter outside the install root gets its execute bit back`() {
        recordRuby()
        val interpreter = elf("usr/bin/ruby")
        val stdlib = elf("usr/lib/ruby/3.4.0/ext.so")
        assertFalse(interpreter.canExecute(), "fixture must start without the bit")

        repair()

        assertTrue(
            interpreter.canExecute(),
            "usr/bin/ruby is still not executable: the pass walked usr/lib/ruby and " +
                "nothing else, which is every file except the one that has to run",
        )
        assertTrue(stdlib.canExecute(), "the install root walk stopped working")
    }

    @Test
    fun `a toolchain the pass has finished with is marked and not walked again`() {
        recordRuby()
        elf("usr/bin/ruby")

        repair()

        assertTrue(
            stateFile().readText().contains("execBitsChecked"),
            "the entry was not marked, so this walk runs again on every launch",
        )

        // Marked, so a second pass leaves even a binary the manifest names alone:
        // that is what keeps a launch from walking several thousand files it has
        // already seen. `gem` is in that list, so an unmarked entry would take it.
        val late = elf("usr/bin/gem")
        repair()
        assertFalse(late.canExecute(), "a marked toolchain was walked a second time")
    }

    @Test
    fun `a binary the payload never shipped is not a reason to keep walking`() {
        // The manifest names two and only one is there. Nothing can give a bit to
        // a file that does not exist, now or on any later launch, so the entry is
        // still finished with.
        recordRuby()
        elf("usr/bin/ruby")

        repair()

        assertTrue(
            stateFile().readText().contains("execBitsChecked"),
            "a manifest naming a binary this payload does not ship left the toolchain " +
                "unmarked, so every launch walks its tree again for nothing",
        )
    }
}
