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
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.file.Files

class ExtractionAllocationTest {
    @TempDir
    lateinit var files: File

    private lateinit var assets: AssetManager
    private lateinit var setup: FirstRunSetup
    private lateinit var context: Context
    private val writtenKeys = mutableListOf<String>()
    private val listings = mutableMapOf<String, Array<String>>()
    private val lengths = mutableMapOf<String, Int>()

    @BeforeEach
    fun setUp() {
        assets = mockk()
        every { assets.list(any()) } answers { listings[firstArg()] ?: emptyArray() }
        every { assets.open(any()) } answers {
            ByteArrayInputStream(ByteArray(lengths[firstArg()] ?: throw IOException("Missing asset")))
        }
        context = mockk<Context>(relaxed = true)
        every { context.filesDir } returns files
        every { context.assets } returns assets
        setup = FirstRunSetup(context)
        listings[""] = arrayOf("usr")
        listings["usr"] = arrayOf("one")
        lengths["usr/one"] = 1
    }

    @AfterEach
    fun tearDown() = unmockkObject(Logger)

    private fun runWithSpace(assetBytes: Long, largest: Long, free: Long, usrBytes: Long = 0): FirstRunSetup.SetupResult {
        mockkObject(Logger)
        every { Logger.d(any(), any()) } just Runs
        every { Logger.i(any(), any()) } just Runs
        every { Logger.w(any(), any(), any()) } just Runs
        every { Logger.e(any(), any(), any()) } just Runs
        val room = object : File(files.path) { override fun getUsableSpace() = free }
        every { context.filesDir } returns room
        every { context.cacheDir } returns File(files, "cache")
        every { context.getExternalFilesDir(null) } returns File(files, "external")
        val editor = mockk<android.content.SharedPreferences.Editor>(relaxed = true)
        every { editor.putString(any(), any()) } answers { writtenKeys += firstArg<String>(); editor }
        val prefs = mockk<android.content.SharedPreferences>(relaxed = true)
        every { prefs.edit() } returns editor
        every { prefs.getString(any(), any()) } returns null
        every { context.getSharedPreferences(any(), any()) } returns prefs
        return runBlocking { FirstRunSetup(context, assetBytes, largest, usrBytes, 0).runSetup() }
    }

    @Test
    fun `a missing file pays block rounding and a node plus its directory node`() {
        assertEquals(4_096L + 4_095L + 4_096L, setup.extractionSpace().missingOverhead)
    }

    @Test
    fun `an aligned missing file needs a node without extra data rounding`() {
        lengths["usr/one"] = 4_096
        assertEquals(8_192L, setup.extractionSpace().missingOverhead)
    }

    @Test
    fun `an empty missing file still needs a node`() {
        lengths["usr/one"] = 0
        assertEquals(8_192L, setup.extractionSpace().missingOverhead)
    }

    @Test
    fun `already unpacked entries add no allocation allowance`() {
        File(files, "usr/one").apply { parentFile!!.mkdirs(); writeText("x") }
        assertEquals(0L, setup.extractionSpace().missingOverhead)
    }

    @Test
    fun `a partial tree is charged only for its missing entries`() {
        File(files, "usr/one").apply { parentFile!!.mkdirs(); writeText("x") }
        listings["usr"] = arrayOf("one", "nested")
        listings["usr/nested"] = arrayOf("two")
        lengths["usr/nested/two"] = 4_096
        assertEquals(8_192L, setup.extractionSpace().missingOverhead)
    }

    @Test
    fun `files not shipped in the APK cannot pay for a missing bundled file`() {
        File(files, "usr/user-package").apply { parentFile!!.mkdirs(); writeText("x") }
        assertEquals(8_191L, setup.extractionSpace().missingOverhead)
        assertEquals(0L, setup.extractionSpace().usrBytes)
    }

    @Test
    fun `a larger old file is credited only up to the corresponding asset length`() {
        File(files, "usr/one").apply { parentFile!!.mkdirs(); writeText("old contents") }
        assertEquals(1L, setup.extractionSpace().usrBytes)
    }

    @Test
    fun `a symbolic link cannot make the bundled subtree count as unpacked`() {
        val other = File(files, "other").apply { mkdirs(); File(this, "one").writeText("x") }
        Files.createSymbolicLink(File(files, "usr").toPath(), other.toPath())
        assertEquals(12_287L, setup.extractionSpace().missingOverhead)
        assertEquals("x", File(other, "one").readText())
    }

    @Test
    fun `a link above an extraction root cannot provide allocation credit`() {
        listings[""] = arrayOf("vscode-reh")
        listings["vscode-reh"] = arrayOf("one")
        lengths["vscode-reh/one"] = 1
        val other = File(files, "other/vscode-reh").apply { mkdirs(); File(this, "one").writeText("x") }
        Files.createSymbolicLink(File(files, "server").toPath(), other.parentFile!!.toPath())
        assertEquals(12_287L, setup.extractionSpace().missingOverhead)
    }

    @Test
    fun `setup includes the allocation of many small missing files before recording an attempt`() {
        listings["usr"] = Array(20_000) { "file-$it" }
        every { assets.open(any()) } answers { ByteArrayInputStream(byteArrayOf(1)) }

        assertEquals(FirstRunSetup.SetupResult.LOW_STORAGE, runWithSpace(20_000, 1, 140 * 1_048_576L))
        assertEquals(emptyList<String>(), writtenKeys)
    }

    @Test
    fun `retired server files do not pay for missing bundled files`() {
        val mib = 1_048_576L
        listings[""] = arrayOf("vscode-reh")
        listings["vscode-reh"] = Array(32) { "new-$it" }
        every { assets.open(any()) } answers {
            object : InputStream() {
                override fun available() = (8 * mib).toInt()
                override fun read(): Int = throw IOException("Stop after preflight")
            }
        }
        val retired = File(files, "server/vscode-reh/node_modules/old-package/old.bin")
        retired.parentFile!!.mkdirs()
        RandomAccessFile(retired, "rw").use { it.setLength(256 * mib) }

        assertEquals(FirstRunSetup.SetupResult.LOW_STORAGE, runWithSpace(256 * mib, 8 * mib, 140 * mib))
        assertEquals(emptyList<String>(), writtenKeys)
        assertEquals(256 * mib, retired.length())
    }

    @Test
    fun `a missing server still reserves space to rewrite an installed tool atomically`() {
        val mib = 1_048_576L
        val toolBytes = 33 * mib
        listings[""] = arrayOf("vscode-reh", "usr")
        listings["vscode-reh"] = Array(20_000) { "file-$it" }
        every { assets.open(any()) } answers {
            val size = if (firstArg<String>() == "usr/one") toolBytes.toInt() else 1
            object : InputStream() {
                override fun available() = size
                override fun read(): Int = throw IOException("Stop after preflight")
            }
        }
        val tool = File(files, "usr/one").apply { parentFile!!.mkdirs() }
        RandomAccessFile(tool, "rw").use { it.setLength(toolBytes) }

        assertEquals(FirstRunSetup.SetupResult.LOW_STORAGE,
            runWithSpace(toolBytes + 20_000, toolBytes, 190 * mib, usrBytes = toolBytes))
        assertEquals(emptyList<String>(), writtenKeys)
        assertEquals(toolBytes, tool.length())
    }

    @Test
    fun `notice and translation assets are never charged as extracted files`() {
        listings[""] = arrayOf("NOTICE.md", "nls")
        assertEquals(0L, setup.extractionSpace().missingOverhead)
    }

    @Test
    fun `an unreadable asset listing does not silently waive its allocation`() {
        every { assets.list("usr") } returns null
        assertThrows(IOException::class.java) { setup.extractionSpace().missingOverhead }
    }

    @Test
    fun `large missing allocation is covered without raising a complete upgrade's reserve`() {
        val mib = 1_048_576L
        assertEquals(224 * mib, FirstRunSetup.requiredExtractionBytes(8 * mib, 2 * mib, 0, 0, 200 * mib))
        assertEquals(130 * mib, FirstRunSetup.requiredExtractionBytes(8 * mib, 2 * mib, 8 * mib, 8 * mib, 0))
        assertEquals(134 * mib, FirstRunSetup.requiredExtractionBytes(8 * mib, 2 * mib, 4 * mib, 4 * mib, 100 * mib))
    }
}
