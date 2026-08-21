package com.vscodroid.setup

import android.content.Context
import android.content.pm.ApplicationInfo
import com.google.android.play.core.assetpacks.AssetPackManagerFactory
import com.vscodroid.util.Environment
import com.vscodroid.util.Logger
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Where the trampoline directory sits on PATH, and that the variable naming its
 * table names the file the manager writes.
 *
 * PATH order is the whole of the fix. For a toolchain that installs into
 * `usr/bin` the interpreter itself is there under the command's own name:
 * `usr/bin/ruby` IS the Ruby ELF, and `usr/bin/java` is a symlink onto the
 * JDK's. Both are inodes under filesDir, which SELinux refuses to execve. So
 * whichever directory comes first decides whether a bare-name lookup finds a
 * program that runs or one that answers EACCES, and getting the order wrong
 * leaves every symptom exactly as it was while every test about the table's
 * CONTENT stays green.
 *
 * The second assertion is the drift guard between a Kotlin writer and a C
 * reader that share nothing but one string.
 */
class EnvironmentPathOrderTest {

    @TempDir
    lateinit var filesDir: File

    private lateinit var context: Context

    private val nativeLibDir = "/data/app/~~hash==/com.vscodroid-hash==/lib/arm64"

    @BeforeEach
    fun setUp() {
        mockkObject(Logger)
        every { Logger.d(any(), any()) } just Runs
        every { Logger.i(any(), any()) } just Runs
        every { Logger.w(any(), any()) } just Runs
        every { Logger.w(any(), any(), any()) } just Runs
        every { Logger.e(any(), any(), any()) } just Runs

        mockkStatic(AssetPackManagerFactory::class)
        every { AssetPackManagerFactory.getInstance(any()) } returns mockk(relaxed = true)

        context = mockk(relaxed = true)
        every { context.filesDir } returns filesDir
        every { context.cacheDir } returns File(filesDir, "cache")
        every { context.applicationInfo } returns ApplicationInfo().apply {
            nativeLibraryDir = this@EnvironmentPathOrderTest.nativeLibDir
        }
        every { context.getExternalFilesDir(null) } returns File(filesDir, "external")

        File(filesDir, "home/.vscodroid").mkdirs()
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    /**
     * A Ruby-shaped record: the payload lives in `usr/bin`, which is also a
     * `pathDirs` entry.
     *
     * The `env` block is not decoration. `getAllToolchainEnv` skips an entry
     * with no `env` key before it ever looks at `pathDirs`, so a fixture without
     * one contributes no PATH additions at all and an assertion about their
     * position would pass against an absence.
     */
    private fun installRuby() {
        File(filesDir, "usr/bin").mkdirs()
        File(filesDir, "usr/bin/ruby").writeBytes(
            byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()) +
                ByteArray(64)
        )
        File(filesDir, "home/.vscodroid/toolchains.json").writeText(
            """[{"name":"ruby","installRoot":"usr/lib/ruby",""" +
                """"binaries":["usr/bin/ruby"],"env":{"RUBYLIB":"${'$'}FILESDIR/usr/lib/ruby"},""" +
                """"pathDirs":["usr/bin"]}]"""
        )
    }

    private fun pathEntries(): List<String> =
        Environment.buildProcessEnvironment(context, 1234)["PATH"]!!.split(":")

    @Test
    fun `the trampoline directory precedes usr slash bin and the toolchain path dirs`() {
        installRuby()
        val entries = pathEntries()

        val native = entries.indexOf(nativeLibDir)
        val tcbin = entries.indexOf(Environment.getTrampolineBinDir(context))
        val usrBin = entries.indexOf("${filesDir.absolutePath}/usr/bin")
        val system = entries.indexOf("/system/bin")

        assertTrue(native >= 0 && tcbin >= 0 && usrBin >= 0 && system >= 0,
            "an expected PATH entry is missing entirely: $entries")
        assertTrue(
            native < tcbin,
            "a toolchain can now shadow the bundled bash, node, git and rg: $entries",
        )
        assertTrue(
            tcbin < usrBin,
            "usr/bin comes first, so `ruby` resolves to the raw ELF that SELinux " +
                "refuses and the trampoline is never reached: $entries",
        )
        assertTrue(
            usrBin < system,
            "the bundled tools no longer win over the system ones: $entries",
        )
    }

    /**
     * `pathDirs` is the manifest's own list of directories, and its entries are
     * the raw payload: the JDK's `bin` holds `java` as an ELF under filesDir.
     * The list is kept as insurance for a manifest whose `binaries` list is
     * incomplete, so it has to stay strictly behind the trampoline directory.
     *
     * A Java-shaped record rather than a Ruby-shaped one on purpose. Ruby's
     * `pathDirs` is `usr/bin`, which is already on PATH from the base
     * composition, so the two copies are indistinguishable and an assertion
     * about their order can be satisfied by the wrong one.
     */
    @Test
    fun `a toolchain's own path dirs sit behind the trampoline directory`() {
        val jdkBin = "usr/lib/jvm/java-17-openjdk/bin"
        File(filesDir, jdkBin).mkdirs()
        File(filesDir, "$jdkBin/java").writeBytes(
            byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()) +
                ByteArray(64)
        )
        File(filesDir, "home/.vscodroid/toolchains.json").writeText(
            """[{"name":"java","installRoot":"usr/lib/jvm/java-17-openjdk",""" +
                """"binaries":["$jdkBin/java"],""" +
                """"env":{"JAVA_HOME":"${'$'}FILESDIR/usr/lib/jvm/java-17-openjdk"},""" +
                """"pathDirs":["$jdkBin"]}]"""
        )

        val entries = pathEntries()
        val tcbin = entries.indexOf(Environment.getTrampolineBinDir(context))
        val extra = entries.indexOf("${filesDir.absolutePath}/$jdkBin")

        assertTrue(extra >= 0, "the toolchain's own PATH entry is missing entirely: $entries")
        assertTrue(
            extra > tcbin,
            "the JDK's bin directory is ahead of the trampoline, so `java` resolves to " +
                "the ELF SELinux refuses: $entries",
        )
    }

    @Test
    fun `the exported table variable names the file the manager writes`() {
        installRuby()

        val named = Environment.buildProcessEnvironment(context, 1234)["VSCODROID_EXEC_TABLE"]
        assertEquals(
            Environment.getExecTablePath(context), named,
            "VSCODROID_EXEC_TABLE does not name the accessor both sides read",
        )

        ToolchainManager(context).regenerateDerivedFiles()
        assertTrue(
            File(named!!).isFile,
            "the variable names a path nothing writes, so every toolchain command " +
                "answers `no toolchain table in the environment`",
        )
    }
}
