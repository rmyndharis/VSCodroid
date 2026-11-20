package com.vscodroid.storage

import com.vscodroid.SourceScan
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * A copy of a document the device deleted is renamed out of the way before its bytes are proved and removed.
 * A process that dies in between leaves it under that name, and the name decides what the next open does with
 * it: under an ordinary one it is a file only this app has, and it was created on the device again.
 */
class SafRemovalCaptureTest {

    @TempDir
    lateinit var mirror: File

    @Test
    fun `the name of a copy taken for deletion is a machine-temporary one`() {
        val name = SafSyncEngine.removalCaptureName()

        assertFalse(SafSyncEngine.shouldWriteBack("dir/$name", isDirectory = false), "a change to it would be written back")
        assertTrue(name.endsWith(SafSyncEngine.PARTIAL_SUFFIX))
    }

    @Test
    fun `an upload plan leaves it out and keeps the files around it`() {
        File(mirror, "dir").mkdirs()
        File(mirror, "dir/kept.txt").writeText("work")
        File(mirror, "dir/${SafSyncEngine.removalCaptureName()}").writeText("bytes of a deleted document")

        val plan = SafSyncEngine.uploadPlan(mirror, limit = 10)

        assertEquals(setOf("dir", "dir/kept.txt"), plan.entries.map { it.relativeTo(mirror).path }.toSet())
    }

    @Test
    fun `each copy gets a name of its own`() {
        assertNotEquals(SafSyncEngine.removalCaptureName(), SafSyncEngine.removalCaptureName())
    }

    @Test
    fun `the removal takes its copy under that name and no other`() {
        val source = SourceScan.read("src/main/kotlin/com/vscodroid/storage/SafSyncEngine.kt")
        val removal = SourceScan.body(source, "private fun removeRecordedCopy(")

        assertTrue("selected.sibling(removalCaptureName())" in removal) { "the removal names its copy another way" }
        assertFalse("removed\$LOCAL_COPY_SUFFIX" in removal) { "the removal names its copy like a copy that is uploaded" }
    }

    @Test
    fun `a file the device replaced with a folder is removed through the capture too`() {
        val source = SourceScan.read("src/main/kotlin/com/vscodroid/storage/SafSyncEngine.kt")
        val clear = SourceScan.body(source, "private fun clearKindConflict(")

        assertTrue("removeRecordedCopy(" in clear) { "the kind conflict removes the copy by another way than the capture" }
        assertFalse("deleteFile(destination.leaf)" in clear) { "the kind conflict deletes an entry it has not taken from its name" }
    }
}
