package com.vscodroid.storage

import android.os.FileObserver
import io.mockk.every
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File

class SafUndeliveredContentNoticeTest : SafInitialSyncTestBase() {
    @Test
    fun `a reopened mirror counts content that the provider did not accept`() {
        deviceTree(mapOf("root" to listOf(Child("sentinel.md", contents = "present"))))
        record.writeText(SafSyncEngine.RECORD_HEADER + "\n")
        mirrorFile("notes.md").writeText("unique local content")
        every { resolver.openOutputStream(any(), "wt") } returns null
        val incomplete = mutableListOf<Int>()
        val engine = SafSyncEngine(context)
        engine.onUploadIncomplete = { _, count, _ -> incomplete += count }
        runBlocking { engine.initialSync(named("tree"), mirror) { _, _ -> } }
        assertEquals(listOf(1), incomplete)
        assertEquals("unique local content", mirrorFile("notes.md").readText())
    }

    @Test
    fun `a directory copy counts files whose content the provider did not accept`() {
        deviceTree(mapOf("root" to emptyList()))
        every { resolver.openOutputStream(any(), "wt") } returns null
        val incomplete = mutableListOf<Int>()
        val engine = SafSyncEngine(context)
        engine.onUploadIncomplete = { _, count, _ -> incomplete += count }
        val directory = mirrorFile("project")
        engine.handleMirrorEvent(FileObserver.CREATE, directory, mirror, named("tree"))
        directory.mkdir()
        File(directory, "one.md").writeText("one")
        File(directory, "two.md").writeText("two")
        engine.runWriteBackLoop { false }
        assertEquals(listOf(2), incomplete)
    }
}
