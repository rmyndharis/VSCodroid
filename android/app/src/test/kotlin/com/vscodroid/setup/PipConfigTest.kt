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
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The file that points pip at the wheelhouse, and the user's configuration it
 * must never touch.
 *
 * pip reads `~/.pip/pip.conf` and then `~/.config/pip/pip.conf`, the second
 * overriding the first key by key, and `pip config set` writes the second. So
 * the app owns the first and nothing else: whatever a user sets wins, and no
 * launch edits a file they wrote. Without the wheelhouse, `pip install
 * ipykernel` stops at psutil, which has no Android build on PyPI, and the
 * Jupyter extension cannot start a kernel.
 */
class PipConfigTest {

    @TempDir
    lateinit var filesDir: File

    private lateinit var context: Context
    private val owned get() = File(filesDir, "home/.pip/pip.conf")
    private val users get() = File(filesDir, "home/.config/pip/pip.conf")

    private val url = wheelhouseUrl("3.14")

    @BeforeEach
    fun setUp() {
        mockkObject(Logger)
        every { Logger.i(any(), any()) } just Runs
        every { Logger.w(any(), any()) } just Runs
        val assets = mockk<AssetManager>()
        every { assets.list("usr/lib") } returns arrayOf("libc++_shared.so", "libpython3.14.so", "libpython3.so")
        context = mockk(relaxed = true)
        every { context.filesDir } returns filesDir
        every { context.assets } returns assets
    }

    @AfterEach
    fun tearDown() = unmockkObject(Logger)

    @Test
    fun `a fresh install is pointed at the wheelhouse`() {
        FirstRunSetup(context).ensurePipConfig()

        val text = owned.readText()
        assertTrue(text.startsWith(PIP_CONF_HEADER), "the file cannot be recognised as ours next launch")
        assertEquals(1, text.lines().count { it.trim() == "[global]" })
        assertTrue(text.contains("find-links = $url"), "pip is not shown the wheelhouse")
        assertTrue(text.contains("prefer-binary = true"))
        assertFalse(users.exists(), "the user's own configuration file was created")
    }

    /** A changed URL has to reach a device already installed, on its next launch. */
    @Test
    fun `a file this app wrote earlier is brought up to date`() {
        owned.parentFile!!.mkdirs()
        owned.writeText("$PIP_CONF_HEADER\n[global]\nfind-links = https://old.test/wheels.html\n")

        FirstRunSetup(context).ensurePipConfig()

        assertEquals(pipConfigContent("3.14"), owned.readText())
    }

    @Test
    fun `a legacy pip conf the user wrote is left exactly as it was`() {
        val theirs = "[global]\nindex-url = https://mirror.test/simple\n"
        owned.parentFile!!.mkdirs()
        owned.writeText(theirs)

        FirstRunSetup(context).ensurePipConfig()

        assertEquals(theirs, owned.readText(), "a file the user wrote was replaced")
    }

    @Test
    fun `the user's own pip conf is never read or written`() {
        val theirs = "[global]\nfind-links = https://mine.test/wheels.html\n"
        users.parentFile!!.mkdirs()
        users.writeText(theirs)

        FirstRunSetup(context).ensurePipConfig()

        assertEquals(theirs, users.readText())
        assertTrue(owned.readText().contains(url))
    }

    @Test
    fun `a second launch leaves the file alone`() {
        FirstRunSetup(context).ensurePipConfig()
        val first = owned.lastModified()
        owned.setLastModified(first - 10_000)

        FirstRunSetup(context).ensurePipConfig()

        assertEquals(first - 10_000, owned.lastModified(), "every launch rewrites the file")
    }

    /**
     * One page per Python minor, so a device on an older APK keeps reading the
     * wheels its own interpreter can install after the bundled Python moves on.
     */
    @Test
    fun `the page named is the one for the bundled interpreter`() {
        assertEquals("https://rmyndharis.github.io/VSCodroid/wheels/3.14/wheels.html", url)
    }

    /** What a first write cut short by power loss leaves; skipping it would be forever. */
    @Test
    fun `an emptied file is written again`() {
        owned.parentFile!!.mkdirs()
        owned.writeText("")

        FirstRunSetup(context).ensurePipConfig()

        assertEquals(pipConfigContent("3.14"), owned.readText())
    }

    @Test
    fun `a file of NUL bytes is written again`() {
        owned.parentFile!!.mkdirs()
        owned.writeBytes(ByteArray(200))

        FirstRunSetup(context).ensurePipConfig()

        assertEquals(pipConfigContent("3.14"), owned.readText())
    }

    @Test
    fun `a file that cannot be read is not replaced`() {
        owned.parentFile!!.mkdirs()
        owned.writeText("[global]\nindex-url = https://mirror.test/simple\n")
        owned.setReadable(false)
        try {
            FirstRunSetup(context).ensurePipConfig()
        } finally {
            owned.setReadable(true)
        }

        assertEquals("[global]\nindex-url = https://mirror.test/simple\n", owned.readText())
    }

    @Test
    fun `an APK with no Python writes nothing`() {
        every { context.assets.list("usr/lib") } returns arrayOf("libc++_shared.so")

        FirstRunSetup(context).ensurePipConfig()

        assertFalse(owned.exists())
    }
}
