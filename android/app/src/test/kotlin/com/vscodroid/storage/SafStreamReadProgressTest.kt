package com.vscodroid.storage

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.lang.reflect.InvocationTargetException
import java.security.MessageDigest

class SafStreamReadProgressTest {
    private class Clock {
        var nanos = 0L
        val pauses = mutableListOf<Long>()
        fun reader() = SafSyncEngine.Companion.StreamReader(
            idleWaitMs = 25L, pauseMs = 10L, nanoTime = { nanos },
            pause = { milliseconds -> pauses += milliseconds; nanos += milliseconds * 1_000_000L },
        )
    }

    private class Source(val text: String, private val zeros: Int, private val chunk: Int = 1) : InputStream() {
        private val bytes = text.toByteArray()
        private var offset = 0
        private var pendingZeros = zeros
        var reads = 0
        override fun read(): Int = error("Only buffered reads are expected")
        override fun read(buffer: ByteArray, start: Int, length: Int): Int {
            if (++reads > 32) throw AssertionError("The reader made 33 calls without progress")
            if (length == 0) return 0
            if (pendingZeros > 0) { pendingZeros--; return 0 }
            if (offset == bytes.size) return -1
            val count = minOf(length, chunk, bytes.size - offset)
            bytes.copyInto(buffer, start, offset, offset + count)
            offset += count
            pendingZeros = if (offset < bytes.size) zeros else 0
            return count
        }
        override fun read(buffer: ByteArray): Int = read(buffer, 0, buffer.size)
    }

    @ParameterizedTest(name = "{displayName}: {0}")
    @ValueSource(strings = ["copyAtMost", "sha256", "readFully"])
    fun `a stream that never supplies a byte ends without busy retrying`(name: String) {
        val source = Source("", Int.MAX_VALUE)
        val clock = Clock()
        assertThrows(IOException::class.java) { invoke(name, source, clock.reader()) }
        assertTrue(source.reads <= 5, "The idle bound must also bound retries")
        assertEquals(25_000_000L, clock.nanos)
        assertTrue(clock.pauses.all { it in 1L..10L })
    }

    @ParameterizedTest(name = "{displayName}: {0}")
    @ValueSource(strings = ["copyAtMost", "sha256", "readFully"])
    fun `temporary empty reads recover and every byte restarts the idle bound`(name: String) {
        val source = Source("ab", 2)
        val clock = Clock()
        assertContents(name, source, clock.reader())
        assertEquals(40_000_000L, clock.nanos, "Total time can exceed the idle bound while bytes arrive")
    }

    @ParameterizedTest(name = "{displayName}: {0}")
    @ValueSource(strings = ["copyAtMost", "sha256", "readFully"])
    fun `short reads retain every byte without a pause`(name: String) {
        val clock = Clock()
        assertContents(name, Source("abc", 0), clock.reader())
        assertTrue(clock.pauses.isEmpty())
    }

    @ParameterizedTest(name = "{displayName}: {0}")
    @ValueSource(strings = ["copyAtMost", "sha256", "readFully"])
    fun `end of file does not wait`(name: String) {
        val clock = Clock()
        assertContents(name, Source("", 0), clock.reader())
        assertTrue(clock.pauses.isEmpty())
    }

    @Test
    fun `an interrupted pause keeps the interrupt and ends as an io failure`() {
        val reader = SafSyncEngine.Companion.StreamReader(pause = { throw InterruptedException() })
        try {
            assertThrows(InterruptedIOException::class.java) { reader.read(Source("", Int.MAX_VALUE), ByteArray(1)) }
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `an empty requested range returns zero without waiting`() {
        val clock = Clock()
        assertEquals(0, clock.reader().read(Source("", 0), ByteArray(0)))
        assertTrue(clock.pauses.isEmpty())
    }

    private fun assertContents(name: String, source: Source, reader: SafSyncEngine.Companion.StreamReader) {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8)
        val result = invoke(name, source, reader, output, buffer)
        val expected = source.text.toByteArray()
        when (name) {
            "copyAtMost" -> { assertEquals(expected.size.toLong(), result); assertArrayEquals(expected, output.toByteArray()) }
            "sha256" -> {
                val digest = result as Pair<*, *>
                assertEquals(expected.size.toLong(), digest.second)
                assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(expected), digest.first as ByteArray)
            }
            else -> { assertEquals(expected.size, result); assertArrayEquals(expected, buffer.copyOf(expected.size)) }
        }
    }

    private fun invoke(
        name: String, source: InputStream, reader: SafSyncEngine.Companion.StreamReader,
        output: ByteArrayOutputStream = ByteArrayOutputStream(), buffer: ByteArray = ByteArray(8),
    ): Any? {
        val target = SafSyncEngine.Companion
        val method = target.javaClass.declaredMethods.single { it.name == name }.apply { isAccessible = true }
        val arguments: Array<Any> = when (name) {
            "copyAtMost" -> arrayOf(source, output, 8L, reader)
            "sha256" -> arrayOf(source, 8L, reader)
            else -> arrayOf(source, buffer, buffer.size, reader)
        }
        return try { method.invoke(target, *arguments) }
        catch (failure: InvocationTargetException) { throw failure.targetException }
    }
}
