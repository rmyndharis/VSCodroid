package com.vscodroid.setup

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The two ends of an exec-table row: what the generator writes and what the
 * trampoline reads.
 *
 * The trampoline is a C program with a fixed array for a row's arguments, and it
 * refuses a longer row whole when the command runs, with a message that names
 * nothing the user chose. The generator declines such a row first, which only
 * works while the two numbers are the same one.
 */
class ExecTrampolineRowLimitTest {

    private val source = File("../../scripts/exec-trampoline.c").also {
        check(it.isFile) { "scripts/exec-trampoline.c is not where this test looks: ${it.absolutePath}" }
    }.readText()

    @Test
    fun `a row is never written with more arguments than the trampoline reads`() {
        val limit = Regex("""#define\s+MAX_ROW_ARGS\s+(\d+)""").find(source)?.groupValues?.get(1)?.toInt()

        assertEquals(
            limit, ToolchainManager.MAX_ROW_ARGS,
            "scripts/exec-trampoline.c reads $limit arguments after a target and the generator writes " +
                "${ToolchainManager.MAX_ROW_ARGS}",
        )
    }
}
