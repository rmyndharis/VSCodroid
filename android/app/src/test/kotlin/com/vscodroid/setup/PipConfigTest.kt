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
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files

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

    /**
     * Opens with this app's header, so a launch that could read it would bring it up to date: what keeps it
     * as it is can only be that it was not read. A user who can read anything (root) proves nothing here.
     */
    @Test
    fun `a file that cannot be read is not replaced`() {
        val stale = "$PIP_CONF_HEADER\n[global]\nfind-links = https://old.test/wheels.html\n"
        owned.parentFile!!.mkdirs()
        owned.writeText(stale)
        owned.setReadable(false)
        try {
            assumeFalse(owned.canRead(), "this user reads a file whatever its mode says")
            FirstRunSetup(context).ensurePipConfig()
        } finally {
            owned.setReadable(true)
        }

        assertEquals(stale, owned.readText())
    }

    /**
     * A pip.conf kept in a dotfiles folder and linked here, whose file has gone: the app's text is written
     * where the link points and the link stays. The rename used to land on the link and replace it with a
     * copy, which pip then read while the person's own file came back beside it unread.
     */
    @Test
    fun `a pip conf that is a link to nothing is written where it points and stays a link`() {
        val target = File(filesDir, "projects/dotfiles/pip.conf").apply { parentFile!!.mkdirs() }
        owned.parentFile!!.mkdirs()
        Files.createSymbolicLink(owned.toPath(), target.toPath())

        FirstRunSetup(context).ensurePipConfig()

        assertTrue(Files.isSymbolicLink(owned.toPath()), "the link was replaced by a copy")
        assertEquals(pipConfigContent("3.14"), target.readText())
    }

    /** Brought up to date like an unlinked one, in the file the link names, and the link stays. */
    @Test
    fun `a linked pip conf this app wrote earlier is brought up to date through the link`() {
        val target = File(filesDir, "projects/dotfiles/pip.conf").apply { parentFile!!.mkdirs() }
        target.writeText("$PIP_CONF_HEADER\n[global]\nfind-links = https://old.test/wheels.html\n")
        owned.parentFile!!.mkdirs()
        Files.createSymbolicLink(owned.toPath(), target.toPath())

        FirstRunSetup(context).ensurePipConfig()

        assertTrue(Files.isSymbolicLink(owned.toPath()), "the link was replaced by a copy")
        assertEquals(pipConfigContent("3.14"), target.readText())
    }

    /** A link into a folder that has gone is left as it is, and the folder is not made again. */
    @Test
    fun `a pip conf linked into a folder that has gone stays a link and nothing is made`() {
        val gone = File(filesDir, "projects/dotfiles")
        owned.parentFile!!.mkdirs()
        Files.createSymbolicLink(owned.toPath(), File(gone, "pip.conf").toPath())

        FirstRunSetup(context).ensurePipConfig()

        assertTrue(Files.isSymbolicLink(owned.toPath()), "the link was replaced by a copy")
        assertFalse(gone.exists(), "the folder the person moved was made again")
    }

    @Test
    fun `an APK with no Python writes nothing`() {
        every { context.assets.list("usr/lib") } returns arrayOf("libc++_shared.so")

        FirstRunSetup(context).ensurePipConfig()

        assertFalse(owned.exists())
    }
}
