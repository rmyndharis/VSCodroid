package com.vscodroid.storage

import com.vscodroid.util.Logger
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockkObject
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes

/**
 * How [ExclusiveMove.move] reads what its helper did, with shell scripts standing in for the
 * helper: the program itself makes a Linux system call and is run by
 * `scripts/test-move-noreplace.py` and, as packaged, by `ExclusiveMoveOnDeviceTest`.
 */
class ExclusiveMoveTest {
    @TempDir lateinit var directory: File

    @BeforeEach
    fun quietLog() {
        mockkObject(Logger)
        every { Logger.w(any(), any(), any()) } just Runs
    }

    @AfterEach
    fun cleanup() = unmockkAll()

    private fun key(): Any =
        Files.readAttributes(directory.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).fileKey()

    private fun helper(body: String): File = File(directory, "helper.sh").apply {
        writeText("#!/bin/sh\n$body\n")
        check(setExecutable(true))
    }

    private fun move(helper: File, key: Any = key()) = ExclusiveMove.move(helper, directory, key, "copy", "free name")

    @Test
    fun `the helper is given the directory, its device and inode in decimal, and the two names`() {
        val seen = File(directory, "seen")
        val outcome = move(helper("""printf '%s\n' "$@" > '${seen.path}'"""))

        assertEquals(ExclusiveMove.Outcome.MOVED, outcome)
        val device = Files.getAttribute(directory.toPath(), "unix:dev") as Long
        val inode = Files.getAttribute(directory.toPath(), "unix:ino") as Long
        assertEquals(
            listOf(directory.path, java.lang.Long.toUnsignedString(device), inode.toString(), "copy", "free name"),
            seen.readLines(),
        )
    }

    @Test
    fun `only the helper's own status for a taken name reads as taken`() {
        assertEquals(ExclusiveMove.Outcome.TAKEN, move(helper("exit ${ExclusiveMove.TAKEN_STATUS}")))
        // What the loader exits with when it cannot start a program. Read as a taken name, it
        // sent the caller to the next name, and the next, without end.
        assertEquals(ExclusiveMove.Outcome.UNAVAILABLE, move(helper("exit 1")))
        assertEquals(ExclusiveMove.Outcome.UNAVAILABLE, move(helper("echo refused >&2; exit 2")))
    }

    @Test
    fun `a helper a signal ended has not said whether it moved the entry`() {
        assertEquals(ExclusiveMove.Outcome.UNKNOWN, move(helper("kill -9 $$")))
    }

    @Test
    fun `a helper that does not end is ended, and has not said whether it moved the entry`() {
        val started = System.nanoTime()

        val outcome = ExclusiveMove.move(helper("exec sleep 60"), directory, key(), "copy", "free name", waitMillis = 300)

        assertEquals(ExclusiveMove.Outcome.UNKNOWN, outcome)
        val elapsed = (System.nanoTime() - started) / 1_000_000
        assertTrue(elapsed in 300..5_000, "the helper was waited for $elapsed ms")
    }

    @Test
    fun `a helper that cannot be started, or a directory with no identity to give it, moves nothing`() {
        assertEquals(ExclusiveMove.Outcome.UNAVAILABLE, move(File(directory, "absent")))
        assertEquals(ExclusiveMove.Outcome.UNAVAILABLE, move(helper("exit 0"), key = "not a file key"))
    }
}
