package com.vscodroid.storage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.security.MessageDigest

class SafRecordSafetyTest : SafInitialSyncTestBase() {

    @Test
    fun `a completed upload receipt makes a delivered mirror edit follow a device deletion`() {
        deviceTree(mapOf("root" to listOf(Child("keep.txt", contents = "k"), Child("notes.txt", contents = "old"))))
        val engine = sync()
        val local = mirrorFile("notes.txt").apply { writeText("delivered edit") }
        val method = SafSyncEngine::class.java.getDeclaredMethod(
            "recordDeliveredCopy", File::class.java, Long::class.javaPrimitiveType,
            Long::class.javaPrimitiveType, ByteArray::class.java,
        ).apply { isAccessible = true }
        method.invoke(
            engine, local, local.lastModified(), local.length(),
            MessageDigest.getInstance("SHA-256").digest(local.readBytes()),
        )
        deviceTree(mapOf("root" to listOf(Child("keep.txt", contents = "k"))))

        sync()
        sync()

        assertFalse(local.exists())
        assertTrue(created.isEmpty(), "the device deletion was undone after a delivered upload")
    }

    @Test
    fun `a failed record replacement leaves the existing record unchanged`() {
        val parent = File(filesDir, "records").apply { mkdirs() }
        val record = File(parent, "state.synced")
        record.writeText(SafSyncEngine.RECORD_HEADER + "\nold.txt\t1\t3")
        val partial = File(record.path + SafSyncEngine.PARTIAL_SUFFIX).apply { writeText("old scratch") }
        val before = record.readText()
        val engine = SafSyncEngine(context)
        val method = SafSyncEngine::class.java.getDeclaredMethod(
            "writeSyncedRecord", File::class.java, List::class.java,
        ).apply { isAccessible = true }
        assertTrue(parent.setWritable(false, false))
        try {
            assertFalse(parent.canWrite(), "the fixture still permits replacing directory entries")
            method.invoke(engine, record, listOf("new.txt\t2\t3"))

            assertEquals(before, record.readText(), "failed replacement fell back to truncating the live record")
        } finally {
            parent.setWritable(true, true)
            partial.delete()
        }
    }

    @Test
    fun `an unreadable directory prevents reclaim even when every visible file is vouched for`() {
        record.writeText(SafSyncEngine.RECORD_HEADER)
        val closed = mirrorFile("closed").apply { mkdirs() }
        assertTrue(closed.setReadable(false, false))
        try {
            assertNull(closed.listFiles(), "the fixture directory is still readable")

            assertFalse(SafSyncEngine(context).holdsOnlyVouchedCopies(mirror))
        } finally {
            closed.setReadable(true, true)
        }
    }
}
