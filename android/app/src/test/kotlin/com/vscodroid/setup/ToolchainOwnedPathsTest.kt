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
import java.nio.file.Path

/**
 * A toolchain whose files are spread through directories it shares with the base
 * install, and what removing it, or an install of it that was stopped, takes.
 *
 * C/C++ is the first one. clang finds its headers, its resource directory and
 * its sysroot relative to where it runs, so they have to sit in `usr/include`
 * and `usr/lib/clang` rather than under one install root, and 197 MB of the
 * 254 MB it unpacks to is two libraries in `usr/lib`. The manifest names what
 * it owns in `ownedPaths`, entry by entry, and that is all an uninstall or a
 * reclaim may take besides what `binaries`, `symlinks` and `libs` already name.
 */
class ToolchainOwnedPathsTest {

    @TempDir
    lateinit var filesDir: File

    private lateinit var context: Context
    private lateinit var assets: AssetManager

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

        // Backed by the real filesystem, so a link in usr/bin is found and unlinked
        // the way it is on a device.
        mockkStatic(Os::class)
        every { Os.lstat(any()) } answers {
            val path = Path.of(firstArg<String>())
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                throw java.io.FileNotFoundException(path.toString())
            }
            mockk<StructStat>(relaxed = true)
        }

        assets = mockk(relaxed = true)
        // The base APK ships libffi, which libLLVM links and the pack does not carry
        // but a manifest may still name.
        every { assets.list("usr/lib") } returns arrayOf("libffi.so", "libz.so.1")
        context = mockk(relaxed = true)
        every { context.filesDir } returns filesDir
        every { context.cacheDir } returns File(filesDir, "cache")
        every { context.assets } returns assets
        every { context.packageName } returns "com.vscodroid"

        File(filesDir, "home/.vscodroid").mkdirs()
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    private fun file(rel: String, text: String = "x") = File(filesDir, rel).apply {
        parentFile?.mkdirs()
        writeText(text)
    }

    private fun exists(rel: String) =
        Files.exists(File(filesDir, rel).toPath(), LinkOption.NOFOLLOW_LINKS)

    private fun record(body: String) =
        File(filesDir, "home/.vscodroid/toolchains.json").writeText(body)

    private fun manager() = ToolchainManager(context)

    private fun uninstallSync(name: String) {
        ToolchainManager::class.java
            .getDeclaredMethod("uninstallSync", String::class.java)
            .apply { isAccessible = true }
            .invoke(manager(), name)
    }

    private fun reclaimInterruptedInstalls() {
        ToolchainManager::class.java
            .getDeclaredMethod("reclaimInterruptedInstallsSync")
            .apply { isAccessible = true }
            .invoke(manager())
    }

    private fun plantMarker(pack: String, body: String) =
        File(filesDir, "home/.vscodroid/toolchain-installing/$pack.json").apply {
            parentFile?.mkdirs()
            writeText(body)
        }

    /**
     * What the C/C++ pack looks like on a device, in miniature, with a few files
     * that are not its own: the base install's `node` and `libffi.so`, and a
     * header a user put into `usr/include` beside the pack's.
     */
    private fun lay() {
        file("usr/bin/clang-21")
        file("usr/bin/lld")
        file("usr/bin/node") // base
        Files.createSymbolicLink(
            File(filesDir, "usr/bin/cc").toPath(), File(filesDir, "usr/bin/clang-21").toPath(),
        )
        file("usr/lib/libLLVM.so")
        file("usr/lib/libffi.so") // base
        file("usr/lib/crtbegin_dynamic.o")
        file("usr/lib/clang/21/include/stddef.h")
        file("usr/include/stdio.h")
        file("usr/include/c++/v1/vector")
        file("usr/include/python3.14/Python.h")
        file("usr/include/mylib.h") // the user's, beside the pack's
        file("usr/share/doc/libllvm/LICENSE.TXT")
    }

    private val manifest = """
        {"name":"cpp","preloadOnly":true,
         "binaries":["usr/bin/clang-21","usr/bin/lld"],
         "symlinks":{"cc":"usr/bin/clang-21"},
         "libs":["libLLVM.so","libffi.so"],
         "ownedPaths":["usr/lib/clang","usr/lib/crtbegin_dynamic.o","usr/include/stdio.h",
                       "usr/include/c++","usr/include/python3.14","usr/share/doc/libllvm"]}
    """.trimIndent().replace("\n", "")

    @Test
    fun `removing the toolchain takes what it owns and nothing beside it`() {
        lay()
        record("[$manifest]")

        uninstallSync("cpp")

        for (gone in listOf(
            "usr/bin/clang-21", "usr/bin/lld", "usr/bin/cc",
            "usr/lib/libLLVM.so", "usr/lib/crtbegin_dynamic.o", "usr/lib/clang",
            "usr/include/stdio.h", "usr/include/c++", "usr/include/python3.14",
            "usr/share/doc/libllvm",
        )) {
            assertFalse(exists(gone), "$gone survived the uninstall, and nothing else will take it")
        }
        for (kept in listOf("usr/bin/node", "usr/lib/libffi.so", "usr/include/mylib.h")) {
            assertTrue(exists(kept), "$kept is not the toolchain's and was deleted with it")
        }
    }

    /**
     * The one a test of the entries alone would not catch: the directory a name
     * lives in is left alone while anything of someone else's is in it, so a
     * header the user installed keeps its `usr/include`.
     */
    @Test
    fun `a directory it shares keeps standing while anything of someone else's is in it`() {
        lay()
        record("[$manifest]")

        uninstallSync("cpp")

        assertTrue(File(filesDir, "usr/include").isDirectory, "usr/include was removed whole")
        assertEquals(
            listOf("mylib.h"), File(filesDir, "usr/include").list().orEmpty().toList(),
            "something other than the user's header is left in usr/include",
        )
    }

    /**
     * The check that makes `ownedPaths` safe to persist and read back: it names
     * what to delete, so a name that is not a path inside the toolchain's own
     * share of a shared directory is skipped. Each of these would otherwise take
     * a directory the base install lives in, or leave the app's own data.
     */
    @Test
    fun `an entry that names a shared directory or escapes is ignored`() {
        lay()
        file("home/keep/notes.txt")
        record(
            """[{"name":"cpp","ownedPaths":["usr","usr/lib","usr/include","usr/bin",""" +
                """"../outside","/data/data/x","usr/lib/../../home","usr//include/stdio.h",""" +
                """"usr/include/./stdio.h","","home/keep","usr/include/stdio.h"]}]"""
        )

        uninstallSync("cpp")

        assertFalse(exists("usr/include/stdio.h"), "the one valid entry was not honoured")
        for (kept in listOf(
            "usr/bin/node", "usr/lib/libffi.so", "usr/lib/libLLVM.so", "usr/include/mylib.h",
            "usr/include/c++/v1/vector", "home/keep/notes.txt",
        )) {
            assertTrue(exists(kept), "$kept was deleted by an entry that is not a path the toolchain owns")
        }
    }

    @Test
    fun `which names may be owned`() {
        for (ok in listOf(
            "usr/include/stdio.h", "usr/lib/clang", "usr/share/doc/libllvm", "usr/lib/crtend_so.o",
        )) {
            assertTrue(isOwnedPathName(ok), "$ok is an ordinary owned path")
        }
        for (bad in listOf(
            "", "usr", "usr/", "usr/lib", "usr/include", "home/x/y", "/usr/lib/clang",
            "usr/lib/../bin", "usr/./lib/x", "usr//lib/x", "usr/lib/x/", "../usr/lib/x",
        )) {
            assertFalse(isOwnedPathName(bad), "'$bad' must not name something to delete")
        }
    }

    /** An install the system stopped: a marker, and no record for it. */
    @Test
    fun `an interrupted install is reclaimed from its marker`() {
        lay()
        plantMarker("toolchain_cpp", manifest)

        reclaimInterruptedInstalls()

        for (gone in listOf(
            "usr/bin/clang-21", "usr/bin/lld", "usr/bin/cc", "usr/lib/libLLVM.so",
            "usr/lib/clang", "usr/include/stdio.h", "usr/include/c++", "usr/share/doc/libllvm",
        )) {
            assertFalse(
                exists(gone),
                "$gone was left by an install nothing names, which is 197 MB of it for the " +
                    "two libraries alone",
            )
        }
        for (kept in listOf("usr/bin/node", "usr/lib/libffi.so", "usr/include/mylib.h")) {
            assertTrue(exists(kept), "$kept belongs to someone else and went with the reclaim")
        }
        assertFalse(
            File(filesDir, "home/.vscodroid/toolchain-installing/toolchain_cpp.json").exists(),
            "the marker outlived the reclaim it asked for",
        )
    }

    /**
     * The other direction, which is the reason the reclaim asks the record first:
     * a marker beside a record that names the toolchain is a finished install, or
     * a reinstall over a working copy, and neither is the reclaim's to delete.
     */
    @Test
    fun `a marker beside a record that names the toolchain deletes nothing`() {
        lay()
        record("[$manifest]")
        plantMarker("toolchain_cpp", manifest)

        reclaimInterruptedInstalls()

        for (kept in listOf(
            "usr/bin/clang-21", "usr/lib/libLLVM.so", "usr/lib/clang/21/include/stddef.h",
            "usr/include/stdio.h", "usr/include/c++/v1/vector",
        )) {
            assertTrue(exists(kept), "$kept was deleted from a toolchain the record calls installed")
        }
    }

    /**
     * Two toolchains that name one library. The base install is the only other
     * owner an old reclaim could be asked about, because it left `usr/lib`
     * alone; with the libraries in scope the other installed toolchain's are
     * protected exactly as the uninstall protects them.
     */
    @Test
    fun `a reclaim keeps a library another installed toolchain also ships`() {
        lay()
        file("usr/lib/libshared.so")
        record("""[{"name":"ruby","libs":["libshared.so"]}]""")
        plantMarker(
            "toolchain_cpp",
            """{"name":"cpp","libs":["libLLVM.so","libshared.so"]}""",
        )

        reclaimInterruptedInstalls()

        assertFalse(exists("usr/lib/libLLVM.so"), "the partial install's own library was kept")
        assertTrue(exists("usr/lib/libshared.so"), "a library the installed Ruby also names was deleted")
    }

    /** `list` failing is "cannot tell", and the reclaim then takes no library at all. */
    @Test
    fun `a reclaim takes no library when the base listing cannot be read`() {
        lay()
        every { assets.list("usr/lib") } throws java.io.IOException("assets unavailable")
        plantMarker("toolchain_cpp", manifest)

        reclaimInterruptedInstalls()

        assertTrue(
            exists("usr/lib/libLLVM.so") && exists("usr/lib/libffi.so"),
            "a library was deleted although what the base ships could not be read",
        )
        assertFalse(exists("usr/include/stdio.h"), "the owned paths do not depend on that listing")
    }
}
