package com.vscodroid.setup

import android.content.Context
import android.system.Os
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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * A reinstall over a record, and what each delivery path claims.
 *
 * Java moved its install root from `usr/lib/jvm/java-17-openjdk` to
 * `java-21-openjdk` under the same pack name, so a reinstall over an old record
 * wrote a second tree and left the first, 155 MB that no Remove could reach, since
 * the new record no longer named it.
 */
class ToolchainReinstallTest {

    @TempDir
    lateinit var filesDir: File

    private lateinit var context: Context
    private val statuses = mutableListOf<Int>()

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

        // Backed by the real filesystem, so a link is found, followed and unlinked the
        // way it is on a device.
        mockkStatic(Os::class)
        every { Os.lstat(any()) } answers {
            val path = File(firstArg<String>()).toPath()
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                throw java.io.FileNotFoundException(path.toString())
            }
            mockk(relaxed = true)
        }
        every { Os.symlink(any(), any()) } answers {
            Files.createSymbolicLink(File(secondArg<String>()).toPath(), File(firstArg<String>()).toPath())
            Unit
        }

        context = mockk(relaxed = true)
        every { context.filesDir } returns filesDir
        every { context.cacheDir } returns File(filesDir, "cache")
        every { context.packageName } returns "com.vscodroid"

        File(filesDir, "home/.vscodroid").mkdirs()
        statuses.clear()
    }

    @AfterEach
    fun tearDown() {
        removalsInFlight().clear()
        unmockkAll()
    }

    private fun manager() = ToolchainManager(context).apply {
        onStateChange = { _, status, _, _ -> statuses.add(status) }
    }

    private fun record(body: String) =
        File(filesDir, "home/.vscodroid/toolchains.json").writeText(body)

    private fun file(rel: String, text: String = "x") = File(filesDir, rel).apply {
        parentFile?.mkdirs()
        writeText(text)
    }

    private fun exists(rel: String) =
        Files.exists(File(filesDir, rel).toPath(), LinkOption.NOFOLLOW_LINKS)

    private fun link(rel: String, target: String) {
        val l = File(filesDir, rel)
        l.parentFile?.mkdirs()
        Files.createSymbolicLink(l.toPath(), File(filesDir, target).toPath())
    }

    /** A delivered pack of `java` rooted at [root], linking [links] into `usr/bin`. */
    private fun pack(root: String, links: List<String>): File {
        val dir = File(filesDir, "pack-java").apply { deleteRecursively(); mkdirs() }
        val symlinks = links.joinToString(",") { "\"$it\":\"$root/bin/$it\"" }
        File(dir, "toolchain_java.json").writeText(
            """{"name":"java","installRoot":"$root","symlinks":{$symlinks}}""",
        )
        File(dir, "$root/bin").mkdirs()
        for (l in links) File(dir, "$root/bin/$l").writeText("new $l")
        return dir
    }

    private fun install(m: ToolchainManager, dir: File) =
        ToolchainManager::class.java
            .getDeclaredMethod("installFromDirectory", String::class.java, File::class.java)
            .apply { isAccessible = true }
            .invoke(m, "toolchain_java", dir)

    private fun installWithoutPlayCopy(m: ToolchainManager, dir: File) =
        ToolchainManager::class.java
            .getDeclaredMethod(
                "installFromDirectory", String::class.java, File::class.java, Boolean::class.javaPrimitiveType,
            )
            .apply { isAccessible = true }
            .invoke(m, "toolchain_java", dir, false)

    @Suppress("UNCHECKED_CAST")
    private fun removalsInFlight() =
        ToolchainManager::class.java.getDeclaredField("packRemovalsInFlight")
            .apply { isAccessible = true }
            .get(null) as MutableSet<String>

    private fun oldJava17() {
        record(
            """[{"name":"java","displayName":"Java 17","installRoot":"usr/lib/jvm/java-17-openjdk",
                "symlinks":{"java":"usr/lib/jvm/java-17-openjdk/bin/java",
                            "jshell":"usr/lib/jvm/java-17-openjdk/bin/jshell"}}]""",
        )
        file("usr/lib/jvm/java-17-openjdk/bin/java")
        file("usr/lib/jvm/java-17-openjdk/bin/jshell")
        link("usr/bin/java", "usr/lib/jvm/java-17-openjdk/bin/java")
        link("usr/bin/jshell", "usr/lib/jvm/java-17-openjdk/bin/jshell")
    }

    @Test
    fun `a reinstall that moves the install root deletes the old tree`() {
        oldJava17()

        install(manager(), pack("usr/lib/jvm/java-21-openjdk", listOf("java")))

        assertEquals(listOf(AssetPackStatus.COMPLETED), statuses)
        assertTrue(exists("usr/lib/jvm/java-21-openjdk/bin/java"), "the new tree was not installed")
        assertFalse(exists("usr/lib/jvm/java-17-openjdk"), "the old 155 MB tree is still on disk")
    }

    @Test
    fun `a link only the old manifest named is removed and one both name points at the new tree`() {
        oldJava17()

        install(manager(), pack("usr/lib/jvm/java-21-openjdk", listOf("java")))

        assertFalse(exists("usr/bin/jshell"), "a link into the deleted tree was left dangling")
        assertEquals(
            File(filesDir, "usr/lib/jvm/java-21-openjdk/bin/java").path,
            Files.readSymbolicLink(File(filesDir, "usr/bin/java").toPath()).toString(),
        )
    }

    @Test
    fun `a reinstall at the same root keeps writing over its own tree`() {
        record(
            """[{"name":"java","displayName":"Java 21","installRoot":"usr/lib/jvm/java-21-openjdk"}]""",
        )
        file("usr/lib/jvm/java-21-openjdk/lib/modules", "old")

        install(manager(), pack("usr/lib/jvm/java-21-openjdk", listOf("java")))

        assertTrue(exists("usr/lib/jvm/java-21-openjdk/lib/modules"), "the same root was treated as superseded")
        assertTrue(exists("usr/lib/jvm/java-21-openjdk/bin/java"))
    }

    @Test
    fun `a root that holds the new one is left in place`() {
        record("""[{"name":"java","installRoot":"usr/lib/jvm"}]""")
        file("usr/lib/jvm/keep", "other tools live here")

        install(manager(), pack("usr/lib/jvm/java-21-openjdk", listOf("java")))

        assertTrue(exists("usr/lib/jvm/java-21-openjdk/bin/java"), "the new tree went with the old root")
        assertTrue(exists("usr/lib/jvm/keep"))
    }

    @Test
    fun `an old root that climbs out of filesDir is never deleted`() {
        val outside = File(filesDir.parentFile, "outside-${filesDir.name}").apply { mkdirs() }
        File(outside, "precious").writeText("x")
        record("""[{"name":"java","installRoot":"../${outside.name}"}]""")

        install(manager(), pack("usr/lib/jvm/java-21-openjdk", listOf("java")))

        assertTrue(File(outside, "precious").exists(), "a root outside filesDir was deleted")
        outside.deleteRecursively()
    }

    @Test
    fun `an install over a pack Play is removing is declined, and one from a ZIP is not`() {
        removalsInFlight().add("toolchain_java")

        install(manager(), pack("usr/lib/jvm/java-21-openjdk", listOf("java")))
        assertEquals(listOf(AssetPackStatus.UNKNOWN), statuses, "a Play delivery being removed was copied from")
        assertFalse(exists("usr/lib/jvm/java-21-openjdk"))

        statuses.clear()
        installWithoutPlayCopy(manager(), pack("usr/lib/jvm/java-21-openjdk", listOf("java")))
        assertEquals(
            listOf(AssetPackStatus.COMPLETED), statuses,
            "a ZIP install was refused over a directory it never reads",
        )
    }
    @Test
    fun `an installed build older than the registry's is named by its record`() {
        record(
            """[{"name":"java","displayName":"Java 17"},{"name":"ruby","displayName":"Ruby"},""" +
                """{"name":"cpp"},{"name":"gone","displayName":"Gone 1"}]""",
        )

        assertEquals(mapOf("toolchain_java" to "Java 17"), ToolchainManager(context).installedLabels())
    }

    @Test
    fun `the card names the older build only while it is installed`() {
        val adapter = ToolchainCardState(ToolchainCardMode.MANAGER)
        val offered = ToolchainRegistry.find("toolchain_java")!!.shortLabel

        adapter.setInstalled(listOf("java"), mapOf("toolchain_java" to "Java 17"))
        assertEquals("Java 17", adapter.labelFor("toolchain_java", offered))

        adapter.setInstalled(emptyList(), mapOf("toolchain_java" to "Java 17"))
        assertEquals(offered, adapter.labelFor("toolchain_java", offered), "a removed toolchain kept its old name")
    }
}
