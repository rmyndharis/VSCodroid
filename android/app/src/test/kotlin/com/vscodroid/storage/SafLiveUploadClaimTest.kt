package com.vscodroid.storage

import android.content.Context
import com.vscodroid.util.Logger
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * A journal line belongs to the write that is streaming into its file.
 *
 * Retiring a stale line (the file was deleted, renamed away or made again) must not take
 * the line of a write that is still going: that write retires its own line when it lands,
 * and a write that is cut short leaves it standing, which is the record the next open
 * repairs from. A line taken out from under it loses exactly that, and no later step puts
 * it back.
 *
 * Reached through the engine's own methods rather than a full write-back, because the
 * overlap needs a second path to the journal while a writer is inside its stream, and a
 * JVM test can only make that overlap by calling the two halves itself.
 */
class SafLiveUploadClaimTest {

    @TempDir
    lateinit var filesDir: File

    private lateinit var engine: SafSyncEngine
    private val path get() = File(filesDir, "saf-mirrors/abc123def456/notes.md").absolutePath

    @BeforeEach
    fun setUp() {
        mockkObject(Logger)
        every { Logger.i(any(), any()) } just Runs
        every { Logger.d(any(), any()) } just Runs
        every { Logger.w(any(), any()) } just Runs
        every { Logger.w(any(), any(), any()) } just Runs
        every { Logger.e(any(), any()) } just Runs
        every { Logger.e(any(), any(), any()) } just Runs
        val context = mockk<Context>(relaxed = true)
        every { context.filesDir } returns filesDir
        engine = SafSyncEngine(context)
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    private fun call(name: String, vararg arguments: Any): Any? {
        val method = SafSyncEngine::class.java.declaredMethods
            .single { it.name == name && it.parameterCount == arguments.size && !it.isSynthetic }
        method.isAccessible = true
        return try {
            method.invoke(engine, *arguments)
        } catch (e: IllegalArgumentException) {
            throw AssertionError(
                "$method does not take ${arguments.map { it.javaClass.name }}", e,
            )
        }
    }

    /** The mirror file the line names, which has to exist for a failed write to keep its line. */
    private fun mirrorFileExists() {
        File(path).apply { parentFile!!.mkdirs(); writeText("an edit that did not reach the device") }
    }

    @Test
    fun `retiring a stale record leaves the line of a write that is still streaming`() {
        mirrorFileExists()
        val claim = checkNotNull(call("markUploadInFlight", path)) { "the journal refused the line" }

        call("consumeStaleUploadRecord", path)

        assertEquals(setOf(path), engine.uploadsInFlight(), "the line of a live write was taken")
        // The writer's own end is what retires it: here, a write that never landed, which
        // keeps the line for the next open to repair from.
        call("releaseUploadClaim", claim)
        assertEquals(setOf(path), engine.uploadsInFlight(), "a write that did not land lost its record")
    }

    @Test
    fun `retiring a stale record takes the line once no write is streaming`() {
        mirrorFileExists()
        val claim = checkNotNull(call("markUploadInFlight", path)) { "the journal refused the line" }
        call("releaseUploadClaim", claim)
        assertEquals(setOf(path), engine.uploadsInFlight(), "a failed write must keep its line")

        call("consumeStaleUploadRecord", path)

        assertEquals(emptySet<String>(), engine.uploadsInFlight(), "a stale line nobody is writing was kept")
    }
}
