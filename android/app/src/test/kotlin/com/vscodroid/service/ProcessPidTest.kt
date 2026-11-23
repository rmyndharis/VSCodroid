package com.vscodroid.service

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/**
 * Verifies [ProcessManager.getServerPid] and reflective pid lookup against both
 * real OS subprocesses (package-private ProcessImpl on JDK 9+ / UNIXProcess on Android)
 * and custom [Process] subclasses.
 */
class ProcessPidTest {

    @TempDir
    lateinit var temp: File

    private lateinit var manager: ProcessManager

    @BeforeEach
    fun setUp() {
        val context = mockk<Context>(relaxed = true)
        every { context.filesDir } returns temp
        every { context.cacheDir } returns temp
        manager = ProcessManager(context, null)
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    private fun invokePidOf(process: Process): Long? {
        val method = ProcessManager::class.java.getDeclaredMethod("pidOf", Process::class.java)
        method.isAccessible = true
        return method.invoke(manager, process) as Long?
    }

    @Test
    fun `pidOf returns the real OS pid for a spawned subprocess`() {
        val process = ProcessBuilder("/bin/sleep", "30").start()
        try {
            val expected = Process::class.java.getMethod("pid").invoke(process) as Long
            assertTrue(expected > 0, "expected a positive OS pid")
            assertEquals(expected, invokePidOf(process), "pidOf must invoke public Process.pid()")

            ProcessManager::class.java.getDeclaredField("serverProcess").apply {
                isAccessible = true
                set(manager, process)
            }
            assertEquals(expected, manager.getServerPid())
        } finally {
            process.destroyForcibly()
            assertTrue(process.waitFor(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `pidOf returns null cleanly for a custom Process without pid method or field`() {
        val fake = object : Process() {
            override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
            override fun getInputStream(): InputStream = ByteArrayInputStream(byteArrayOf())
            override fun getErrorStream(): InputStream = ByteArrayInputStream(byteArrayOf())
            override fun waitFor(): Int = 0
            override fun exitValue(): Int = 0
            override fun destroy() {}
        }
        assertNull(invokePidOf(fake))
    }

    class CustomPidProcess(private val customPid: Long) : Process() {
        fun pid(): Long = customPid
        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getInputStream(): InputStream = ByteArrayInputStream(byteArrayOf())
        override fun getErrorStream(): InputStream = ByteArrayInputStream(byteArrayOf())
        override fun waitFor(): Int = 0
        override fun exitValue(): Int = 0
        override fun destroy() {}
    }

    @Test
    fun `pidOf dispatches virtually for a custom public Process subclass defining pid`() {
        assertEquals(7777L, invokePidOf(CustomPidProcess(7777L)))
    }

    private class FieldIntPidProcess(private val pid: Int) : Process() {
        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getInputStream(): InputStream = ByteArrayInputStream(byteArrayOf())
        override fun getErrorStream(): InputStream = ByteArrayInputStream(byteArrayOf())
        override fun waitFor(): Int = 0
        override fun exitValue(): Int = 0
        override fun destroy() {}
    }

    private class FieldLongPidProcess(private val pid: Long) : Process() {
        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getInputStream(): InputStream = ByteArrayInputStream(byteArrayOf())
        override fun getErrorStream(): InputStream = ByteArrayInputStream(byteArrayOf())
        override fun waitFor(): Int = 0
        override fun exitValue(): Int = 0
        override fun destroy() {}
    }

    @Test
    fun `pidOf falls back to private pid field when default Process pid throws UnsupportedOperationException`() {
        assertEquals(5432L, invokePidOf(FieldIntPidProcess(5432)))
        assertEquals(654321L, invokePidOf(FieldLongPidProcess(654321L)))
    }
}
