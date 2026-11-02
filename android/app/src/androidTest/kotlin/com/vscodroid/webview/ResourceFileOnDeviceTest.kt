package com.vscodroid.webview

import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
class ResourceFileOnDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val temp = File(context.cacheDir, "resource-${UUID.randomUUID()}").apply { check(mkdirs()) }
    private val root = File(temp, "project").apply { check(mkdirs()) }
    private val roots get() = listOf(root.canonicalPath)

    @After
    fun tearDown() {
        Files.walk(temp.toPath()).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    @Test
    fun regularAndEmptyResourcesUseTheOpenedFileAndCloseItsDescriptor() {
        for (text in listOf("resource bytes", "")) {
            val file = File(root, "image.txt").apply { writeText(text) }
            val stream = openResourceFile(file, roots)
            val descriptor = stream.fd
            stream.use {
                assertTrue(descriptor.valid())
                assertEquals(text.length.toLong(), it.channel.size())
                assertEquals(text, it.bufferedReader().readText())
            }
            assertFalse("the returned stream left its descriptor open", descriptor.valid())
        }
    }

    @Test
    fun aLinkInsideThePublishedTreeStillResolvesToItsRegularTarget() {
        val file = File(root, "image.txt").apply { writeText("image bytes") }
        val link = File(root, "link.txt")
        Files.createSymbolicLink(link.toPath(), file.toPath())
        val admitted = resourceOutcome(link.path, roots) as ResourceOutcome.Serve
        openResourceFile(admitted.file, roots).use { assertEquals("image bytes", it.bufferedReader().readText()) }
    }

    @Test
    fun spacesAndUnicodeInThePublishedRootKeepTheirResourceAccess() {
        val folder = File(temp, "project \u65e5\u672c\ud83d\ude00").apply { check(mkdirs()) }
        val file = File(folder, "my image.txt").apply { writeText("image bytes") }

        openResourceFile(file, listOf(folder.canonicalPath)).use {
            assertEquals("image bytes", it.bufferedReader().readText())
        }
    }

    @Test
    fun anAdmittedFileReplacedByALinkCannotExposeAnUnpublishedFile() {
        val file = File(root, "image.txt").apply { writeText("image bytes") }
        val admitted = resourceOutcome(file.path, roots) as ResourceOutcome.Serve
        val privateFile = File(temp, "private-key").apply { writeText("private bytes") }
        check(file.delete())
        Files.createSymbolicLink(file.toPath(), privateFile.toPath())

        assertRefused(admitted.file)
        assertNoDescriptorFor(privateFile)
    }

    @Test
    fun aReplacedParentCannotMoveTheOpenedResourceOutsideItsRoots() {
        val parent = File(root, "assets").apply { check(mkdirs()) }
        val file = File(parent, "image.txt").apply { writeText("image bytes") }
        val admitted = resourceOutcome(file.path, roots) as ResourceOutcome.Serve
        val outside = File(temp, "private").apply { check(mkdirs()) }
        val privateFile = File(outside, file.name).apply { writeText("private bytes") }
        Files.move(parent.toPath(), File(temp, "old-assets").toPath())
        Files.createSymbolicLink(parent.toPath(), outside.toPath())

        assertRefused(admitted.file)
        assertNoDescriptorFor(privateFile)
    }

    @Test
    fun aFileReplacedByADirectoryIsRefusedWithoutRetainingItsDescriptor() {
        val file = File(root, "image.txt").apply { writeText("image bytes") }
        val admitted = resourceOutcome(file.path, roots) as ResourceOutcome.Serve
        check(file.delete())
        check(file.mkdir())

        assertRefused(admitted.file)
        assertNoDescriptorFor(file)
    }

    @Test
    fun aFileReplacedByAFifoIsRefusedWithoutWaitingForAWriter() {
        val file = File(root, "image.txt").apply { writeText("image bytes") }
        val admitted = resourceOutcome(file.path, roots) as ResourceOutcome.Serve
        check(file.delete())
        Os.mkfifo(file.path, 384)
        val task = FutureTask { assertRefused(admitted.file) }
        val worker = thread(isDaemon = true, name = "resource-fifo-check") { task.run() }
        try {
            task.get(2, TimeUnit.SECONDS)
        } finally {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (worker.isAlive && System.nanoTime() < deadline) {
                runCatching { Os.open(file.path, OsConstants.O_WRONLY or OsConstants.O_NONBLOCK, 0) }
                    .onSuccess { Os.close(it) }
                worker.join(10)
            }
            assertFalse("the resource open remained blocked", worker.isAlive)
        }
        assertNoDescriptorFor(file)
    }

    private fun assertRefused(file: File) {
        val result = runCatching { openResourceFile(file, roots).use { } }
        assertTrue("an invalid resource was opened", result.isFailure)
    }

    private fun assertNoDescriptorFor(file: File) {
        val target = file.canonicalPath
        val retained = File("/proc/self/fd").listFiles().orEmpty().any {
            runCatching { Os.readlink(it.path) == target }.getOrDefault(false)
        }
        assertFalse("an invalid resource retained an open descriptor", retained)
    }
}
