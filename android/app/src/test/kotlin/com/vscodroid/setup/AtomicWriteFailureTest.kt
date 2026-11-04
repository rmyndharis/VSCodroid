package com.vscodroid.setup

import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files

class AtomicWriteFailureTest {
    @TempDir
    lateinit var directory: File

    @BeforeEach
    fun setUp() = mockkStatic(Files::class)

    @AfterEach
    fun tearDown() = unmockkAll()

    @Test
    fun `closing one failure lease preserves another active destination`() {
        val first = File(directory, "first.txt")
        val second = File(directory, "second.txt")
        val firstFailure = blockAtomicWritesTo(first)
        val secondFailure = blockAtomicWritesTo(second)
        assertFalse(writeAtomically(first) { it.write(1) })
        assertFalse(writeAtomically(second) { it.write(2) })
        firstFailure.close()
        assertTrue(writeAtomically(first) { it.write(3) })
        assertFalse(writeAtomically(second) { it.write(4) })
        secondFailure.close()
        assertTrue(writeAtomically(second) { it.write(5) })
    }

    @Test
    fun `a new fixture registration permits a failure lease after cleanup`() {
        val first = File(directory, "first.txt")
        blockAtomicWritesTo(first).use {
            assertFalse(writeAtomically(first) { it.write(1) })
        }
        unmockkAll()
        setUp()

        val second = File(directory, "second.txt")
        blockAtomicWritesTo(second).use {
            assertFalse(writeAtomically(second) { it.write(2) })
        }
        assertTrue(writeAtomically(second) { it.write(3) })
    }
}
