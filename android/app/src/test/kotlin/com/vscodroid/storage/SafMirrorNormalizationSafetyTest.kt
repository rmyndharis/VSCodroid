package com.vscodroid.storage

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.vscodroid.util.Logger
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
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
import java.util.concurrent.atomic.AtomicBoolean

class SafMirrorNormalizationSafetyTest {

    @TempDir
    lateinit var root: File

    private lateinit var mirror: File
    private lateinit var filesDir: File
    private lateinit var engine: SafSyncEngine
    private val docUri: Uri = mockk(relaxed = true)

    @BeforeEach
    fun setUp() {
        mirror = File(root, "mirror").apply { mkdirs() }
        filesDir = File(root, "files").apply { mkdirs() }
        mockkObject(Logger)
        every { Logger.i(any(), any()) } just Runs
        every { Logger.d(any(), any()) } just Runs
        every { Logger.w(any(), any()) } just Runs
        every { Logger.w(any(), any(), any()) } just Runs
        every { Logger.e(any(), any()) } just Runs
        every { Logger.e(any(), any(), any()) } just Runs

        val resolver = mockk<ContentResolver>(relaxed = true)
        val context = mockk<Context>(relaxed = true)
        every { context.contentResolver } returns resolver
        every { context.filesDir } returns filesDir
        engine = SafSyncEngine(context)
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    private fun invokeMirrorNamedAsStored(file: File, stored: String): File =
        SafSyncEngine::class.java
            .getDeclaredMethod("mirrorNamedAsStored", Uri::class.java, File::class.java, String::class.java)
            .apply { isAccessible = true }
            .invoke(engine, docUri, file, stored) as File

    @Suppress("UNCHECKED_CAST")
    private fun writeBackSessionLocal(): ThreadLocal<WatchSession?> =
        SafSyncEngine::class.java
            .getDeclaredField("writeBackSession")
            .apply { isAccessible = true }
            .get(engine) as ThreadLocal<WatchSession?>

    @Test
    fun `mirrorNamedAsStored without an active write-back session does not mutate engine session`() {
        val dir = File(mirror, "a:b").apply { mkdirs() }
        val activeSession = engine.session

        val result = invokeMirrorNamedAsStored(dir, "a_b")

        assertEquals(File(mirror, "a_b"), result)
        assertTrue(
            activeSession.normalizedDirectories.isEmpty(),
            "normalization outside a draining write-back mutated the active session: ${activeSession.normalizedDirectories}",
        )
    }

    @Test
    fun `mirrorNamedAsStored records directory normalization on the draining session rather than a newly switched one`() {
        val dir = File(mirror, "a:b").apply { mkdirs() }
        val drainingSession = WatchSession(mirror)
        val activeSession = engine.session
        val tl = writeBackSessionLocal()
        tl.set(drainingSession)
        try {
            val result = invokeMirrorNamedAsStored(dir, "a_b")

            assertEquals(File(mirror, "a_b"), result)
            assertTrue(
                activeSession.normalizedDirectories.isEmpty(),
                "a draining session wrote into the newly active session",
            )
            assertTrue(
                drainingSession.normalizedDirectories.containsKey(File(mirror, "a_b").absolutePath),
                "the draining session did not record its own normalized directory",
            )
        } finally {
            tl.remove()
        }
    }

    @Test
    fun `mirrorNamedAsStored uses exclusiveMove and preserves a concurrently created destination`() {
        val source = File(mirror, "a:b.txt").apply { writeText("local copy") }
        val destination = File(mirror, "a_b.txt")
        val usedExclusiveMove = AtomicBoolean(false)
        engine.exclusiveMove = { directory, _, _, to ->
            usedExclusiveMove.set(true)
            File(directory, to).writeText("concurrent file")
            ExclusiveMove.Outcome.TAKEN
        }

        val result = invokeMirrorNamedAsStored(source, "a_b.txt")

        assertTrue(usedExclusiveMove.get(), "mirrorNamedAsStored bypassed exclusiveMove")
        assertEquals(source, result)
        assertEquals("local copy", source.readText())
        assertEquals("concurrent file", destination.readText())
        assertFalse(
            destination.absolutePath in engine.uploadsInFlight(),
            "a failed normalization left a journal record for a concurrently created file",
        )
    }

    @Test
    fun `mirrorNamedAsStored clears its upload intent when rename fails because destination appeared concurrently`() {
        val destination = File(mirror, "a_b.txt")
        val source = object : File(File(mirror, "a:b.txt").path) {
            override fun renameTo(dest: File): Boolean {
                dest.writeText("concurrent file")
                return false
            }
        }
        source.writeText("local copy")
        engine.exclusiveMove = { _, _, _, _ -> ExclusiveMove.Outcome.UNAVAILABLE }

        val result = invokeMirrorNamedAsStored(source, "a_b.txt")

        assertEquals(source, result)
        assertEquals("concurrent file", destination.readText())
        assertFalse(
            destination.absolutePath in engine.uploadsInFlight(),
            "a failed normalization left an orphaned upload journal record when the destination existed after failure",
        )
    }

    @Test
    fun `mirrorNamedAsStored refuses to rename inside a symlinked parent directory`() {
        val realParent = File(root, "outside").apply { mkdirs() }
        val realFile = File(realParent, "a:b.txt").apply { writeText("outside data") }
        val linkedParent = File(mirror, "linked")
        Files.createSymbolicLink(linkedParent.toPath(), realParent.toPath())
        val viaLink = File(linkedParent, "a:b.txt")

        val result = invokeMirrorNamedAsStored(viaLink, "a_b.txt")

        assertEquals(viaLink, result)
        assertTrue(realFile.isFile, "normalization followed a symlinked parent directory")
        assertFalse(File(realParent, "a_b.txt").exists(), "normalization renamed a file through a symlinked parent")
        assertTrue(engine.uploadsInFlight().isEmpty())
    }

    @Test
    fun `mirrorNamedAsStored preserves a pre-existing standing upload journal entry when move fails with TAKEN`() {
        val source = File(mirror, "a:b.txt").apply { writeText("new file") }
        val destination = File(mirror, "a_b.txt")
        val journal = File(filesDir, SafSyncEngine.UPLOADS_IN_FLIGHT_FILE)
        val standingContent = listOf(
            "#vscodroid-saf-uploads 1",
            destination.absolutePath,
            "unwritten\t${destination.absolutePath}",
        ).joinToString("\n")
        journal.writeText(standingContent)
        assertTrue(destination.absolutePath in engine.uploadsInFlight(), "setup: standing journal entry not loaded")
        assertFalse(destination.absolutePath in engine.partlyWrittenUploads(), "setup: unwritten mark not loaded")

        val usedExclusiveMove = AtomicBoolean(false)
        engine.exclusiveMove = { directory, _, _, to ->
            usedExclusiveMove.set(true)
            File(directory, to).writeText("concurrent file")
            ExclusiveMove.Outcome.TAKEN
        }

        val result = invokeMirrorNamedAsStored(source, "a_b.txt")

        assertTrue(usedExclusiveMove.get(), "mirrorNamedAsStored did not reach exclusiveMove")
        assertEquals(source, result)
        assertTrue(
            destination.absolutePath in engine.uploadsInFlight(),
            "failed normalization erased a pre-existing standing upload record for the destination",
        )
        assertFalse(
            destination.absolutePath in engine.partlyWrittenUploads(),
            "failed normalization stripped the unwritten mark from a pre-existing standing journal entry",
        )
        assertEquals(
            standingContent,
            journal.readText(),
            "failed normalization modified a pre-existing standing journal entry",
        )
    }
}
