package com.vscodroid.setup

import android.content.Context
import android.content.pm.ApplicationInfo
import com.vscodroid.util.Logger
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File
import java.util.concurrent.TimeUnit

class GitPagerConfigTest {
    @TempDir lateinit var filesDir: File
    private lateinit var context: Context
    private val systemConfig get() = File(filesDir, "usr/etc/gitconfig")

    @BeforeEach
    fun setUp() {
        mockkObject(Logger)
        every { Logger.d(any(), any()) } just Runs
        every { Logger.i(any(), any()) } just Runs
        every { Logger.w(any(), any(), any()) } just Runs
        context = mockk(relaxed = true)
        every { context.filesDir } returns filesDir
        every { context.applicationInfo } returns ApplicationInfo().apply {
            nativeLibraryDir = File(filesDir, "native-libs").path
        }
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `git repair supplies a pager default when the system file is absent or empty`(empty: Boolean) {
        if (empty) {
            systemConfig.parentFile.mkdirs()
            systemConfig.writeText("")
        }

        FirstRunSetup(context).setupGitCore()

        assertTrue(systemConfig.isFile)
        assertEquals("[core]\n\tpager = cat\n", systemConfig.readText())
    }

    @ParameterizedTest
    @ValueSource(strings = ["[core]\r\n\tpager = less\r\n", "# user configuration\n", " \n"])
    fun `git repair leaves every nonempty system file unchanged`(content: String) {
        systemConfig.parentFile.mkdirs()
        systemConfig.writeText(content)
        val before = systemConfig.readBytes()
        val stamp = 1_000_000_000_000L
        assertTrue(systemConfig.setLastModified(stamp))

        FirstRunSetup(context).setupGitCore()

        assertArrayEquals(before, systemConfig.readBytes())
        assertEquals(stamp, systemConfig.lastModified())
    }

    @Test
    fun `Git reads the system pager default below the user global setting`() {
        val home = File(filesDir, "home").apply { mkdirs() }
        fun git(vararg args: String): String {
            val process = ProcessBuilder(listOf("git") + args).apply {
                directory(filesDir)
                redirectErrorStream(true)
                environment().apply {
                    keys.filter { it.startsWith("GIT_") }.forEach { remove(it) }
                    remove("PAGER")
                    put("HOME", home.path)
                    put("XDG_CONFIG_HOME", File(home, "xdg").path)
                    put("GIT_CONFIG_SYSTEM", systemConfig.path)
                }
            }.start()
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "host git did not finish")
            val output = process.inputStream.bufferedReader().readText().trim()
            assertEquals(0, process.exitValue(), output)
            println("git ${args.joinToString(" ")} -> $output")
            return output
        }
        val version = runCatching { git("--version") }.getOrElse {
            assumeTrue(false, "no usable host git")
            return
        }
        val parts = Regex("(\\d+)\\.(\\d+)").find(version)?.groupValues
        val major = parts?.get(1)?.toIntOrNull() ?: 0
        val minor = parts?.get(2)?.toIntOrNull() ?: 0
        assumeTrue(major > 2 || major == 2 && minor >= 32, "host Git predates GIT_CONFIG_SYSTEM")

        FirstRunSetup(context).setupGitCore()
        assertEquals("cat", git("var", "GIT_PAGER"))
        File(home, ".gitconfig").writeText("[core]\n\tpager = user-pager\n")
        assertEquals("user-pager", git("var", "GIT_PAGER"))
    }
}
