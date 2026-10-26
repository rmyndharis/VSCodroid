package com.vscodroid.setup

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

class TestProcessTest {
    private class Child(
        text: String = "",
        private val hangs: Boolean = false,
        private val status: Int = 0,
        private val readFails: Boolean = false,
    ) : Process() {
        private var alive = true
        var destroyed = false
        var reaped = false
        var pipeClosed = false
        var eofReads = 0
        private val bytes = ByteArrayInputStream(text.toByteArray())
        private val pipe = object : InputStream() {
            override fun available() = bytes.available()
            override fun read(): Int = throw AssertionError("Unexpected single-byte read")
            override fun read(buffer: ByteArray, offset: Int, count: Int): Int {
                if (bytes.available() == 0) {
                    eofReads++
                    throw AssertionError("Read would wait for an inherited writer")
                }
                if (readFails) throw IOException("pipe read failed")
                return bytes.read(buffer, offset, count)
            }
            override fun close() { pipeClosed = true }
        }
        override fun getInputStream() = pipe
        override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun isAlive() = alive
        override fun waitFor(): Int = throw AssertionError("Unbounded wait")
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            if (!hangs) alive = false
            if (!alive) reaped = true
            return !alive
        }
        override fun exitValue(): Int {
            if (alive) throw IllegalThreadStateException()
            return status
        }
        override fun destroy() { destroyed = true; alive = false }
        override fun destroyForcibly(): Process { destroy(); return this }
    }

    @Test
    fun `completed output and exit status are preserved without an EOF read`() {
        val child = Child("first\nsecond\n", status = 7)
        assertEquals(7 to "first\nsecond\n", finishTestProcess(child))
        assertEquals(0, child.eofReads)
        assertTrue(child.reaped)
        assertTrue(child.pipeClosed)
        assertFalse(child.destroyed)
    }

    @Test
    fun `a direct child can exit while an inherited pipe remains open`() {
        val child = Child()
        assertEquals(0 to "", finishTestProcess(child))
        assertEquals(0, child.eofReads)
        assertTrue(child.reaped)
    }

    @Test
    fun `a timeout fails and reaps the direct child`() {
        val child = Child("started\n", hangs = true)
        val error = assertThrows(AssertionError::class.java) { finishTestProcess(child, timeoutMillis = 100) }
        assertTrue(error.message!!.contains("timed out"))
        assertTrue(error.message!!.contains("started"))
        assertTrue(child.destroyed)
        assertTrue(child.reaped)
        assertTrue(child.pipeClosed)
    }

    @Test
    fun `output overflow stops and reaps the direct child`() {
        val child = Child("too much output", hangs = true)
        val error = assertThrows(AssertionError::class.java) { finishTestProcess(child, maxOutputBytes = 4) }
        assertTrue(error.message!!.contains("exceeded 4 bytes"))
        assertTrue(child.destroyed)
        assertTrue(child.reaped)
    }

    @Test
    fun `a read failure is propagated after child cleanup`() {
        val child = Child("x", hangs = true, readFails = true)
        val error = assertThrows(IOException::class.java) { finishTestProcess(child) }
        assertEquals("pipe read failed", error.message)
        assertTrue(child.destroyed)
        assertTrue(child.reaped)
    }

    @Test
    fun `interruption is preserved after child cleanup`() {
        val child = Child(hangs = true)
        Thread.currentThread().interrupt()
        try {
            // The real Process timed wait consumes interruption.
            val interruptedChild = object : Process() {
                override fun getInputStream() = child.inputStream
                override fun getErrorStream() = child.errorStream
                override fun getOutputStream() = child.outputStream
                override fun isAlive() = child.isAlive
                override fun waitFor(): Int = throw AssertionError("Unbounded wait")
                override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
                    if (Thread.interrupted()) throw InterruptedException()
                    return child.waitFor(timeout, unit)
                }
                override fun exitValue() = child.exitValue()
                override fun destroy() = child.destroy()
                override fun destroyForcibly(): Process { child.destroyForcibly(); return this }
            }
            assertThrows(InterruptedException::class.java) { finishTestProcess(interruptedChild) }
            assertTrue(Thread.currentThread().isInterrupted)
            assertTrue(child.destroyed)
            assertTrue(child.reaped)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `an actual exit status 126 remains a refused execution result`() {
        assertEquals(126 to "denied\n", finishTestProcess(Child("denied\n", status = 126)))
    }
}
