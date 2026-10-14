package com.vscodroid.setup

import android.content.Context
import android.content.res.AssetManager
import android.system.Os
import android.system.StructStat
import com.google.android.play.core.assetpacks.AssetPackManagerFactory
import com.vscodroid.util.Logger
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * The pack `scripts/download-cpp.sh` wrote, taken through an install and an
 * uninstall by the code that does both on a device.
 *
 * The script writes a manifest from what is on disk, and `ToolchainManager`
 * reads it back by key name. Each side has its own tests, and neither can see
 * the other drift: a key renamed in one, or an entry the manager refuses, is
 * green on both and an uninstall that leaves 250 MB on a phone. This is the one
 * place the real manifest meets the real tree and the real manager.
 *
 * Skipped where the pack has not been built, which is every pull request. The
 * release workflow runs the unit suite after the toolchain downloads, so a tag
 * is where this runs. Read a green as evidence only when it was not skipped.
 */
class ToolchainCppPackTest {

    @TempDir
    lateinit var filesDir: File

    private lateinit var context: Context

    // Interpolated, which is also what keeps this a path the declaration test
    // does not ask the test task to track: the payload is written by a download
    // script and ignored by git, so a source checkout does not carry it.
    private val packName = "toolchain_cpp"
    private val packAssets = File("../$packName/src/main/assets")
    private val manifestFile = File(packAssets, "$packName.json")

    /** The command names that are links to a binary, which is all of them but two. */
    private val linkNames = setOf(
        "clang", "clang++", "cc", "c++", "gcc", "g++", "ld", "ld.lld",
        "ar", "ranlib", "nm", "strip", "objdump", "objcopy", "readelf", "size",
    )

    /**
     * The two names that carry a target, each with the plain name it starts. clang
     * takes its target from the prefix of the name it runs under, and this prefix
     * names no API level, so a link under either name compiles with an unversioned
     * triple and the headers stop. Python's sysconfig names the first as its CC.
     */
    private val targetPrefixed = mapOf(
        "aarch64-linux-android-clang" to "clang",
        "aarch64-linux-android-clang++" to "clang++",
    )

    private fun scriptFor(plain: String) = "#!/system/bin/sh\nexec \"\${0%/*}/$plain\" \"\$@\"\n"

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

        // Backed by the real filesystem, so the links the install makes exist and
        // can be followed, which is what the assertions below read.
        mockkStatic(Os::class)
        every { Os.lstat(any()) } answers {
            val path = Path.of(firstArg<String>())
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                throw java.io.FileNotFoundException(path.toString())
            }
            mockk<StructStat>(relaxed = true)
        }
        every { Os.readlink(any()) } answers {
            Files.readSymbolicLink(Path.of(firstArg<String>())).toString()
        }
        every { Os.symlink(any(), any()) } answers {
            Files.createSymbolicLink(Path.of(secondArg<String>()), Path.of(firstArg<String>()))
            Unit
        }
        every { Os.rename(any(), any()) } answers {
            Files.move(
                Path.of(firstArg<String>()), Path.of(secondArg<String>()),
                StandardCopyOption.REPLACE_EXISTING,
            )
            Unit
        }

        // The base APK's own usr/lib listing when this checkout has one, which
        // is what the uninstall's library guard reads; empty otherwise.
        val assets: AssetManager = mockk(relaxed = true)
        val base = File("src/main/assets/usr/lib").list() ?: emptyArray()
        every { assets.list("usr/lib") } returns base

        context = mockk(relaxed = true)
        every { context.filesDir } returns filesDir
        every { context.cacheDir } returns File(filesDir, "cache")
        every { context.assets } returns assets
        every { context.packageName } returns "com.vscodroid"
        File(filesDir, "home/.vscodroid").mkdirs()
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    private fun manager() = ToolchainManager(context)

    /** Private, and the one entry point both delivery routes converge on. */
    private fun install() {
        val finished = ToolchainManager::class.java
            .getDeclaredMethod("installFromDirectory", String::class.java, File::class.java)
            .apply { isAccessible = true }
            .invoke(manager(), packName, packAssets)
        assertEquals(true, finished, "the install did not finish")
    }

    private fun uninstall() {
        ToolchainManager::class.java
            .getDeclaredMethod("uninstallSync", String::class.java)
            .apply { isAccessible = true }
            .invoke(manager(), "cpp")
    }

    private fun strings(array: JSONArray?): List<String> =
        (0 until (array?.length() ?: 0)).map { array!!.getString(it) }

    private fun manifest() = JSONObject(manifestFile.readText())

    /** Regular files and links, not what a link points at, and not directories. */
    private fun entriesUnder(dir: File): List<File> =
        dir.walkTopDown().filter { !Files.isDirectory(it.toPath(), LinkOption.NOFOLLOW_LINKS) }.toList()

    private fun requirePack() = assumeTrue(
        manifestFile.isFile && File(packAssets, "usr").isDirectory,
        "the C/C++ pack has not been built here; run scripts/download-cpp.sh to reach this",
    )

    @Test
    fun `the manifest the script writes asks for what the manager honours`() {
        requirePack()
        val m = manifest()

        assertEquals("cpp", m.getString("name"))
        assertTrue(m.optBoolean("preloadOnly"), "the toolchain is no longer preloadOnly, so it would be wrapped")
        // `env` has to be there: the env file and the server environment skip a
        // toolchain's PATH entries when it has none.
        assertTrue(m.has("env"), "no env object, so pathDirs is never read")
        assertEquals(listOf("usr/bin"), strings(m.optJSONArray("pathDirs")))
        assertEquals("", m.optString("installRoot", ""), "an install root would be deleted whole")
        for (rel in strings(m.optJSONArray("ownedPaths"))) {
            assertTrue(isOwnedPathName(rel), "the manager refuses '$rel', so it would never be removed")
        }
        val links = m.getJSONObject("symlinks")
        val binaries = strings(m.getJSONArray("binaries")).toSet()
        for (name in links.keys()) {
            assertTrue(links.getString(name) in binaries, "$name points at a file the pack does not ship")
        }

        // Every other command name is still the link it was, and the two that
        // carry a target are files: in binaries, so they get the execute bit and
        // go with the toolchain, and not in symlinks, where the install would
        // delete the file and put a link in its place.
        assertEquals(linkNames, links.keys().asSequence().toSet(), "the command-name links changed")
        for ((name, plain) in targetPrefixed) {
            assertTrue("usr/bin/$name" in binaries, "$name is not in binaries, so it gets no execute bit")
            val file = File(packAssets, "usr/bin/$name")
            assertFalse(Files.isSymbolicLink(file.toPath()), "$name is a link in the pack")
            assertEquals(scriptFor(plain), file.readText(), "$name is not the script that starts $plain")
        }
    }

    @Test
    fun `installing it makes every command name resolve to a program and starts nothing by hand`() {
        requirePack()
        install()

        val state = File(filesDir, "home/.vscodroid/toolchains.json").readText()
        assertTrue("\"cpp\"" in state, "the install was not recorded: $state")

        val m = manifest()
        val links = m.getJSONObject("symlinks")
        for (name in links.keys()) {
            val link = File(filesDir, "usr/bin/$name")
            assertTrue(Files.isSymbolicLink(link.toPath()), "$name was not linked into usr/bin")
            assertTrue(
                link.isFile,
                "$name does not resolve to a file: it points at ${Files.readSymbolicLink(link.toPath())}",
            )
        }
        val binaries = strings(m.getJSONArray("binaries"))
        for (rel in binaries) {
            assertTrue(File(filesDir, rel).isFile, "$rel is named by the manifest and was not copied")
        }
        // The two target-prefixed names are files that start the plain driver
        // names, and they have to be able to run: only the manifest gives them
        // the execute bit, because the copy carries none.
        for ((name, plain) in targetPrefixed) {
            val script = File(filesDir, "usr/bin/$name")
            assertFalse(Files.isSymbolicLink(script.toPath()), "$name is a link, which compiles with an unversioned triple")
            assertTrue(script.canExecute(), "$name has no execute bit after the install")
            assertEquals(scriptFor(plain), script.readText(), "$name is not the script that starts $plain")
        }

        // The point of the flag, read off the two files a terminal and a task
        // read. Both are asserted to exist and to carry something first, so the
        // absences below cannot be a file that was never written.
        val env = File(filesDir, "home/.vscodroid/toolchain-env.sh").readText()
        assertTrue("\$PREFIX/../usr/bin" in env, "the PATH entry is missing, so the file is not what was asked for:\n$env")
        val table = File(filesDir, "home/.vscodroid/toolchain-exec.tsv").readText()
        assertTrue(
            table.lineSequence().any { it.startsWith("xdg-open\t") },
            "the exec table was not regenerated by the install:\n$table",
        )

        val commands = binaries.map { it.substringAfterLast('/') } + links.keys().asSequence()
        for (command in commands) {
            assertFalse(
                env.lineSequence().any { it.startsWith("$command() ") },
                "a bash wrapper was written for $command, which would start it through linker64:\n$env",
            )
            assertFalse(
                table.lineSequence().any { it.startsWith("$command\t") },
                "an exec-table row was written for $command, which would start it through linker64:\n$table",
            )
        }
    }

    /**
     * What an uninstall must not leave, asserted over the whole tree rather than
     * over the manifest's own lists: every file the install put under `usr/` is
     * gone afterwards, which is the claim the build script's last check makes
     * about the manifest and this makes about the manager.
     */
    @Test
    fun `uninstalling it leaves no file of its own behind`() {
        requirePack()
        val shipped = entriesUnder(File(packAssets, "usr")).size
        assertTrue(shipped > 0, "the pack holds no files")
        install()

        // The trampoline's links are written by the generator on every pass and
        // are not the pack's.
        val tcbin = File(filesDir, "usr/libexec/tcbin").path
        fun packFiles() = entriesUnder(File(filesDir, "usr"))
            .filter { !it.path.startsWith(tcbin) }
            .map { it.relativeTo(filesDir).path }
        val links = manifest().getJSONObject("symlinks").length()
        assertEquals(shipped + links, packFiles().size, "the install did not copy what the pack holds, plus its links")

        uninstall()

        val left = packFiles()
        assertEquals(emptyList<String>(), left.take(20), "an uninstall left ${left.size} files behind")
        val state = File(filesDir, "home/.vscodroid/toolchains.json").readText()
        assertFalse("\"cpp\"" in state, "the record still names it: $state")
    }
}
