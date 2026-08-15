package com.vscodroid.setup

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

/**
 * The repair pass that gives the execute bit back to a toolchain installed by an
 * earlier version.
 *
 * A toolchain keeps the manifest it was installed with, so a packaging fix
 * reaches new installs only. Go was the case that showed it: its manifest named
 * `go` and `gofmt`, the recursive copy carries no modes, and the `pkg/tool`
 * binaries `go` starts arrived without the bit.
 *
 * Worth being exact about what this buys, because the code once claimed more. The
 * bit is necessary and not sufficient: nothing under `filesDir` can be executed
 * whatever its mode, which is why toolchain commands go through the system loader.
 * The loader will not run a file the mode denies either, so the bit still has to
 * be there.
 *
 * The walk had no test at all. [isElfHeader] was covered, which is the cheapest
 * part; what decided the outcome — which files are visited, which are skipped, and
 * whether the bit is actually set — was not.
 *
 * The symlink predicate is injected because the production one uses `Os.lstat`,
 * which cannot run in a JVM test: it throws, the catch reads that as "not a link",
 * and every entry then looks like a regular file. A test using the real predicate
 * would assert nothing about links while appearing to.
 */
class ExecutableRepairTest {

    /** The four bytes every ELF object starts with, plus enough body to be a file. */
    private fun elf(f: File) = f.apply {
        parentFile?.mkdirs()
        writeBytes(byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()) +
            ByteArray(64))
        setExecutable(false, false)
    }

    private fun script(f: File) = f.apply {
        parentFile?.mkdirs()
        writeText("#!/usr/bin/env ruby\nputs 1\n")
        setExecutable(false, false)
    }

    /** JVM-native, unlike the production predicate. */
    private val jvmIsLink: (File) -> Boolean = { Files.isSymbolicLink(it.toPath()) }

    @Test
    fun `an ELF without the execute bit gets it`(@TempDir tmp: File) {
        val go = elf(File(tmp, "pkg/tool/android_arm64/compile"))
        assertFalse(go.canExecute(), "fixture must start without the bit")

        assertEquals(1, markExecutablesIn(tmp, jvmIsLink))
        assertTrue(go.canExecute(), "the binary go starts is still not executable")
    }

    @Test
    fun `the whole tree is walked, not just the top level`(@TempDir tmp: File) {
        elf(File(tmp, "bin/go"))
        elf(File(tmp, "pkg/tool/android_arm64/link"))
        elf(File(tmp, "pkg/tool/android_arm64/asm"))

        assertEquals(3, markExecutablesIn(tmp, jvmIsLink))
    }

    @Test
    fun `a script is left alone`(@TempDir tmp: File) {
        // Not an oversight: a shebang file under filesDir cannot be executed at
        // all, so the manifests wrap scripts in shell functions that call their
        // interpreter. Marking one executable would suggest a route that does not
        // work.
        val rake = script(File(tmp, "bin/rake"))

        assertEquals(0, markExecutablesIn(tmp, jvmIsLink))
        assertFalse(rake.canExecute())
    }

    @Test
    fun `an ELF that already has the bit is not counted`(@TempDir tmp: File) {
        val go = elf(File(tmp, "bin/go"))
        check(go.setExecutable(true, true))

        assertEquals(0, markExecutablesIn(tmp, jvmIsLink),
            "the count is what gets logged, so an unchanged tree must report zero")
        assertTrue(go.canExecute())
    }

    @Test
    fun `a symlinked file is stepped over`(@TempDir tmp: File) {
        // usr/lib is shared with the base install, so a link inside a toolchain can
        // point at a file this pass has no business changing.
        val outside = elf(File(tmp, "outside/libpython.so"))
        val root = File(tmp, "ruby").apply { mkdirs() }
        Files.createSymbolicLink(File(root, "libpython.so").toPath(), outside.toPath())

        assertEquals(0, markExecutablesIn(root, jvmIsLink))
        assertFalse(outside.canExecute(), "a link was followed and its target changed")
    }

    @Test
    fun `a symlinked directory is not descended into`(@TempDir tmp: File) {
        val outside = File(tmp, "outside").apply { mkdirs() }
        elf(File(outside, "deep/libssl.so"))
        val root = File(tmp, "ruby").apply { mkdirs() }
        Files.createSymbolicLink(File(root, "vendor").toPath(), outside.toPath())

        assertEquals(0, markExecutablesIn(root, jvmIsLink))
        assertFalse(File(outside, "deep/libssl.so").canExecute())
    }

    @Test
    fun `a missing root is not an error`(@TempDir tmp: File) {
        assertEquals(0, markExecutablesIn(File(tmp, "never-installed"), jvmIsLink))
    }

    @Test
    fun `a file too short to have a header is not treated as an ELF`(@TempDir tmp: File) {
        val stub = File(tmp, "bin/tiny").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(0x7F)) }
        stub.setExecutable(false, false)

        assertEquals(0, markExecutablesIn(tmp, jvmIsLink))
        assertFalse(stub.canExecute())
    }
}
