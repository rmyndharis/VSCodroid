package com.vscodroid.setup

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.res.AssetManager
import android.system.Os
import android.system.StructStat
import com.google.android.play.core.assetpacks.AssetPackManagerFactory
import com.google.android.play.core.assetpacks.model.AssetPackStatus
import com.vscodroid.util.Logger
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption

class ExtractionDestinationPreservationTest {
    @TempDir lateinit var root: File

    private lateinit var files: File
    private lateinit var assets: AssetManager
    private val reports = mutableListOf<Pair<Int, ToolchainFailure?>>()
    private val javaRoot = "usr/lib/jvm/java-21-openjdk"
    private val assetBytes = "bundled replacement bytes"

    @BeforeEach
    fun setUp() {
        files = File(root, "files").apply { mkdirs() }
        mockkObject(Logger)
        every { Logger.d(any(), any()) } just Runs
        every { Logger.i(any(), any()) } just Runs
        every { Logger.w(any(), any()) } just Runs
        every { Logger.w(any(), any(), any()) } just Runs
        every { Logger.e(any(), any()) } just Runs
        every { Logger.e(any(), any(), any()) } just Runs

        mockkStatic(AssetPackManagerFactory::class)
        every { AssetPackManagerFactory.getInstance(any()) } returns mockk(relaxed = true)
        mockkStatic(Os::class)
        every { Os.lstat(any()) } answers {
            val path = File(firstArg<String>()).toPath()
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) throw FileNotFoundException(path.toString())
            mockk<StructStat>(relaxed = true)
        }
        every { Os.symlink(any(), any()) } answers {
            Files.createSymbolicLink(File(secondArg<String>()).toPath(), File(firstArg<String>()).toPath())
            Unit
        }
        every { Os.readlink(any()) } answers {
            Files.readSymbolicLink(File(firstArg<String>()).toPath()).toString()
        }
        every { Os.rename(any(), any()) } answers {
            Files.move(
                File(firstArg<String>()).toPath(), File(secondArg<String>()).toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
            Unit
        }

        assets = mockk(relaxed = true)
        every { assets.list(any()) } returns emptyArray()
        every { assets.open(any()) } throws IOException("absent")
        every { assets.list("vscode-reh") } returns arrayOf("index.js")
        every { assets.open("vscode-reh/index.js") } answers { ByteArrayInputStream(assetBytes.toByteArray()) }
        every { assets.open("server.js") } answers { ByteArrayInputStream(assetBytes.toByteArray()) }
        reports.clear()
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    private fun context(at: File = files): Context {
        val info = mockk<ApplicationInfo>(relaxed = true)
        info.nativeLibraryDir = File(root, "native").absolutePath
        return mockk<Context>(relaxed = true).also { context ->
            every { context.filesDir } returns at
            every { context.cacheDir } returns File(root, "cache")
            every { context.assets } returns assets
            every { context.applicationInfo } returns info
            every { context.packageName } returns "com.vscodroid"
            every { context.getSharedPreferences(any(), any()) } returns mockk(relaxed = true)
        }
    }

    private fun file(parent: File, relative: String, text: String): File = File(parent, relative).apply {
        parentFile?.mkdirs()
        writeText(text)
    }

    private fun link(relative: String, target: File): File = File(files, relative).apply {
        parentFile?.mkdirs()
        Files.createSymbolicLink(toPath(), target.toPath())
    }

    private fun alias(): File = File(root, "files-alias").apply {
        Files.createSymbolicLink(toPath(), files.toPath())
    }

    /** What the last extraction left in `firstWriteFailure`, which the abort quotes to the user. */
    private var cause: String? = null

    private fun causeOf(setup: FirstRunSetup): String? =
        FirstRunSetup::class.java.getDeclaredField("firstWriteFailure")
            .apply { isAccessible = true }.get(setup) as String?

    private fun extractTree(at: File = files): Boolean {
        val setup = FirstRunSetup(context(at), 0, 0, 0, 0)
        return (FirstRunSetup::class.java.declaredMethods.single {
            it.name == "extractAssetDir" && it.parameterCount == 4
        }.apply { isAccessible = true }.invoke(setup, "vscode-reh", "server/vscode-reh", null, false) as Boolean)
            .also { cause = causeOf(setup) }
    }

    private fun extractFile(asset: String, destination: String, at: File = files): Boolean {
        val setup = FirstRunSetup(context(at), 0, 0, 0, 0)
        return (FirstRunSetup::class.java.declaredMethods.single {
            it.name == "extractAssetFile" && it.parameterCount == 5
        }.apply { isAccessible = true }.invoke(setup, asset, destination, null, false, false) as Boolean)
            .also { cause = causeOf(setup) }
    }

    private fun install(at: File = files): Boolean {
        val pack = File(root, "delivery").apply { mkdirs() }
        file(pack, "toolchain_java.json", """{"name":"java","displayName":"Java 21","installRoot":"$javaRoot","binaries":["$javaRoot/bin/java"]}""")
        file(pack, "$javaRoot/bin/java", assetBytes)
        val manager = ToolchainManager(context(at)).apply {
            onStateChange = { _, status, _, reason, _ -> reports.add(status to reason) }
        }
        return ToolchainManager::class.java.getDeclaredMethod(
            "installFromDirectory", String::class.java, File::class.java,
        ).apply { isAccessible = true }.invoke(manager, "toolchain_java", pack) as Boolean
    }

    private fun assertRefusedInstall(result: Boolean, projectFile: File, original: String) {
        assertAll(
            { assertFalse(result, "a redirected destination was treated as a completed install") },
            { assertEquals(original, projectFile.readText(), "copying changed an external project file") },
            { assertTrue(reports.any { it.first == AssetPackStatus.FAILED }) },
            { assertTrue(reports.none { it.first == AssetPackStatus.COMPLETED }) },
            { assertFalse(File(files, "home/.vscodroid/toolchains.json").exists()) },
        )
    }

    @Test
    fun `asset tree extraction refuses a redirected server parent before overwriting project bytes`() {
        val project = File(root, "project")
        val original = file(project, "vscode-reh/index.js", "unique project server")
        val redirected = link("server", project)

        val result = extractTree()

        assertAll(
            { assertFalse(result) },
            { assertEquals("unique project server", original.readText()) },
            { assertTrue(Files.isSymbolicLink(redirected.toPath())) },
            { assertEquals("server/vscode-reh is, or sits behind, a symbolic link", cause) },
        )
    }

    @Test
    fun `direct asset extraction also refuses a redirected server parent`() {
        val project = File(root, "project")
        val original = file(project, "server.js", "unique project bootstrap")
        link("server", project)

        val result = extractFile("server.js", "server/server.js")

        assertAll(
            { assertFalse(result) },
            { assertEquals("unique project bootstrap", original.readText()) },
            { assertEquals("server/server.js is, or sits behind, a symbolic link", cause) },
        )
    }

    @Test
    fun `an absent optional asset remains a no-op through a redirected parent`() {
        val project = File(root, "project")
        val original = file(project, "optional.js", "user optional file")
        link("server", project)

        assertTrue(extractFile("absent.js", "server/optional.js"))
        assertEquals("user optional file", original.readText())
    }

    @Test
    fun `asset extraction accepts an alias for the files root itself`() {
        val alias = alias()

        assertTrue(extractTree(alias))

        assertEquals(assetBytes, File(files, "server/vscode-reh/index.js").readText())
        assertTrue(Files.isSymbolicLink(alias.toPath()))
    }

    @Test
    fun `asset extraction replaces a final file link without touching its target`() {
        val external = file(root, "project/source.js", "unique linked source")
        val leaf = link("server/vscode-reh/index.js", external)

        assertTrue(extractFile("vscode-reh/index.js", "server/vscode-reh/index.js"))

        assertEquals("unique linked source", external.readText())
        assertEquals(assetBytes, leaf.readText())
        assertFalse(Files.isSymbolicLink(leaf.toPath()))
    }

    @Test
    fun `toolchain copying refuses a redirected usr root`() {
        val project = File(root, "project")
        val original = file(project, "lib/jvm/java-21-openjdk/bin/java", "unique project binary")
        link("usr", project)

        assertRefusedInstall(install(), original, "unique project binary")
    }

    @Test
    fun `toolchain copying refuses a redirected nested destination directory`() {
        val project = File(root, "project")
        val original = file(project, "java-21-openjdk/bin/java", "unique nested binary")
        link("usr/lib/jvm", project)

        assertRefusedInstall(install(), original, "unique nested binary")
    }

    @Test
    fun `toolchain copying accepts an alias for the files root itself`() {
        val alias = alias()

        assertTrue(install(alias))

        assertEquals(assetBytes, File(files, "$javaRoot/bin/java").readText())
        assertTrue(Files.isSymbolicLink(alias.toPath()))
        assertEquals(listOf(AssetPackStatus.COMPLETED), reports.map { it.first })
    }

    @Test
    fun `toolchain copying replaces a final file link without touching its target`() {
        val external = file(root, "project/java", "unique linked binary")
        val leaf = link("$javaRoot/bin/java", external)

        assertTrue(install())

        assertEquals("unique linked binary", external.readText())
        assertEquals(assetBytes, leaf.readText())
        assertFalse(Files.isSymbolicLink(leaf.toPath()))
        assertEquals(listOf(AssetPackStatus.COMPLETED), reports.map { it.first })
    }
}
