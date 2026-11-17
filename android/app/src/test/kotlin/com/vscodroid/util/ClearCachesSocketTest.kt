package com.vscodroid.util

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.ProtocolFamily
import java.net.SocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path

/**
 * That clearing the caches does not take the running server's sockets from it.
 *
 * `cacheDir/tmp` is the editor server's TMPDIR. The git extension keeps the socket its
 * credential prompts and empty-message commits connect to there, each terminal keeps one for the
 * `vscodroid` command, and tmux keeps its own. The action runs while the editor is open, and a
 * socket whose path was unlinked still accepts nothing new: the next connect answers "No such
 * file or directory" until the process that made it restarts, under a message that says only that
 * cached data was freed.
 *
 * The sockets are real ones. A server socket bound to a path is what the extension has, and
 * connecting to it afterwards is what a prompt does.
 */
class ClearCachesSocketTest {

    @TempDir
    lateinit var tmp: File

    private lateinit var context: Context
    private lateinit var cacheDir: File

    @BeforeEach
    fun setUp() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.i(any(), any()) } returns 0
        every { android.util.Log.d(any(), any()) } returns 0
        every { android.util.Log.w(any(), any<String>()) } returns 0
        every { android.util.Log.e(any(), any()) } returns 0
        cacheDir = File(tmp, "c").apply { mkdirs() }
        context = mockk(relaxed = true)
        every { context.filesDir } returns File(tmp, "f").apply { mkdirs() }
        every { context.cacheDir } returns cacheDir
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    // android.jar hides the JDK 16 overloads for unix sockets; the JVM running the test has them.
    private fun addressOf(path: Path): SocketAddress =
        Class.forName("java.net.UnixDomainSocketAddress").getMethod("of", Path::class.java).invoke(null, path) as SocketAddress

    private fun listenOn(path: Path): ServerSocketChannel {
        val unix = Class.forName("java.net.StandardProtocolFamily").getField("UNIX").get(null) as ProtocolFamily
        val server = ServerSocketChannel::class.java.getMethod("open", ProtocolFamily::class.java).invoke(null, unix) as ServerSocketChannel
        return server.also { it.bind(addressOf(path)) }
    }

    private fun connectTo(path: Path) {
        (SocketChannel::class.java.getMethod("open", SocketAddress::class.java).invoke(null, addressOf(path)) as SocketChannel).close()
    }

    @Test
    fun `a socket in the temporary directory can still be connected to after the caches are cleared`() {
        val tmpDir = File(cacheDir, "tmp").apply { mkdirs() }
        val socket = File(tmpDir, "g.sock").toPath()
        val leftover = File(tmpDir, "build.log").apply { writeText("x".repeat(100)) }
        listenOn(socket).use {
            connectTo(socket)

            val freed = StorageManager.clearCaches(context)

            assertTrue(Files.exists(socket), "the socket path was unlinked, so nothing can reach the server's socket again")
            connectTo(socket)
            assertFalse(leftover.exists(), "an ordinary file in the temporary directory was left")
            assertTrue(freed >= 100, "the file's bytes were not counted: $freed")
        }
    }

    @Test
    fun `a socket inside a subdirectory keeps the directory, and the files beside it go`() {
        val dir = File(cacheDir, "tmp/t").apply { mkdirs() }
        val socket = File(dir, "d").toPath()
        val beside = File(dir, "note").apply { writeText("x") }
        val elsewhere = File(cacheDir, "tmp/loose").apply { writeText("x") }
        listenOn(socket).use {
            StorageManager.clearCaches(context)

            connectTo(socket)
            assertFalse(beside.exists())
            assertFalse(elsewhere.exists())
        }
    }

    @Test
    fun `the other cache directories are still emptied whole`() {
        val npm = File(cacheDir, "npm-cache/pkg").apply { mkdirs() }
        File(npm, "a").writeText("x")
        File(File(cacheDir, "tmp").apply { mkdirs() }, "gone").writeText("x")

        StorageManager.clearCaches(context)

        assertFalse(File(cacheDir, "npm-cache").exists())
        assertFalse(File(cacheDir, "tmp/gone").exists())
        assertTrue(File(cacheDir, "tmp").isDirectory, "the temporary directory the runtime needs was not recreated")
    }
}
