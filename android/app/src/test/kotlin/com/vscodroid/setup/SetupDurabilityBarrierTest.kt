package com.vscodroid.setup

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The flush stands between the payload and the record that certifies it.
 *
 * READ WHAT THIS PINS, WHICH IS ORDERING AND NOT DURABILITY. A JVM test has no
 * page cache to look at, cannot cut power, and cannot tell a file on the medium
 * from a file sitting in a dirty page, so nothing here shows that anything was
 * made durable, and no assertion below should be read as showing it. What it can
 * show is the property the barrier's entire value rests on: that the flush is the
 * last statement before the write that says the work is finished. Out of that
 * order the flush is worse than absent, because the record is fsynced either way
 * and goes on certifying bytes that may never have left memory.
 *
 * Two call sites, one shape. `markSetupComplete()` fsyncs through `commit()`, and
 * `toolchains.json` is the only thing that names the ~155 MB a toolchain install
 * copies, which no later pass reads the contents of.
 *
 * Source reading, with comments dropped first so that prose naming the calls
 * cannot satisfy the scan.
 *
 * NEGATIVE CONTROL: move `flushWritesToMedia()` in `runSetupLocked` below
 * `markSetupComplete()` and the first case names it; move it past
 * `writeState(state)` in `installFromDirectoryHoldingPack` and the second does.
 */
class SetupDurabilityBarrierTest {

    /** [path]'s Kotlin, comments dropped, relative to `android/app`. */
    private fun codeOf(path: String): String {
        val source = File(path)
        check(source.isFile) {
            "$path not found at ${source.absolutePath}; this test would otherwise pass " +
                "by looking at nothing"
        }
        return source.readLines().filterNot {
            val t = it.trimStart()
            t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
        }.joinToString("\n")
    }

    @Test
    fun `first-run setup flushes immediately before it records completion`() {
        val code = codeOf("src/main/kotlin/com/vscodroid/setup/FirstRunSetup.kt")

        // The control on the scan: the declaration is excluded by the lookbehind,
        // so a count other than one means this no longer reads the run it is about.
        val calls = Regex("""(?<!fun )markSetupComplete\(\)""").findAll(code).count()
        assertEquals(1, calls) {
            "expected exactly one markSetupComplete() call outside its declaration, found $calls"
        }

        assertTrue(Regex("""flushWritesToMedia\(\)\s*markSetupComplete\(\)""").containsMatchIn(code)) {
            "nothing flushes between the last write of the run and the commit that certifies " +
                "it. markSetupComplete() commits, and a commit fsyncs, so in any other order " +
                "the flag reaches the medium while the unpacked tree it vouches for is still " +
                "page cache, and no later launch checks that tree's contents"
        }
    }

    @Test
    fun `a toolchain install flushes before the record naming its files`() {
        val code = codeOf("src/main/kotlin/com/vscodroid/setup/ToolchainManager.kt")

        // Scoped to the one function, because writeState(state) is written from
        // three places and only this one has a freshly copied tree behind it.
        val start = code.indexOf("private fun installFromDirectoryHoldingPack")
        assertTrue(start >= 0) {
            "installFromDirectoryHoldingPack not found; the scan has lost the function it reads"
        }
        val end = Regex("""\n    private fun """).find(code, start + 1)?.range?.first ?: code.length
        val body = code.substring(start, end)

        val record = body.indexOf("writeState(state)")
        assertTrue(record >= 0) {
            "writeState(state) not found in installFromDirectoryHoldingPack; the scan has lost " +
                "the write it orders against"
        }
        val flush = body.indexOf("flushWritesToMedia()")
        assertTrue(flush in 0 until record) {
            "the copy into usr/ is not flushed before toolchains.json records it (flush at " +
                "$flush, record at $record). That record is the only thing that names those " +
                "files, and the repair pass re-checks the tree with isDirectory alone, so a " +
                "record that outlives the copy leaves a toolchain listed as installed over " +
                "binaries that are holes"
        }
    }
}
