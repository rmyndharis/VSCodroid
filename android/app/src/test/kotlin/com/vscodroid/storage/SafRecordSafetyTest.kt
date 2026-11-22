package com.vscodroid.storage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

class SafRecordSafetyTest : SafInitialSyncTestBase() {

    @Test
    fun `a completed upload receipt makes a delivered mirror edit follow a device deletion`() {
        deviceTree(mapOf("root" to listOf(Child("keep.txt", contents = "k"), Child("notes.txt", contents = "old"))))
        val engine = sync()
        val local = mirrorFile("notes.txt").apply { writeText("delivered edit") }
        val method = SafSyncEngine::class.java.getDeclaredMethod(
            "recordDeliveredCopy", File::class.java, File::class.java, Long::class.javaPrimitiveType,
            Long::class.javaPrimitiveType, ByteArray::class.java,
        ).apply { isAccessible = true }
        method.invoke(
            engine, local, mirror, local.lastModified(), local.length(),
            MessageDigest.getInstance("SHA-256").digest(local.readBytes()),
        )
        deviceTree(mapOf("root" to listOf(Child("keep.txt", contents = "k"))))

        sync()
        sync()

        assertFalse(local.exists())
        assertTrue(created.isEmpty(), "the device deletion was undone after a delivered upload")
    }

    /**
     * A record that could not be read says nothing about the folder, so the open writes nothing in its place.
     * Read as empty, it was replaced: every document the device no longer listed got a row without a digest,
     * and its copy stayed in the app for good, neither removed nor sent.
     */
    @Test
    fun `an open that cannot read the record leaves it to the next open, which finishes the deletion`() {
        deviceTree(mapOf("root" to listOf(Child("keep.txt", contents = "k"), Child("gone.txt", contents = "deleted on the device"))))
        sync()
        val before = record.readText()
        deviceTree(mapOf("root" to listOf(Child("keep.txt", contents = "k"))))
        assertTrue(record.setReadable(false, false))
        try {
            assumeFalse(Files.isReadable(record.toPath()), "This account bypasses file read permissions")
            sync()
        } finally {
            record.setReadable(true, true)
        }
        assertEquals(before, record.readText(), "the open replaced a record it could not read")
        assertEquals(emptyList<Int>(), didNotReach, "the open told of files on the word of a record it could not read")

        sync()

        assertFalse(mirrorFile("gone.txt").exists(), "the copy of a document deleted on the device stayed in the app")
        assertTrue(created.isEmpty(), "a document deleted on the device was put back")
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
            assumeFalse(parent.canWrite(), "This account bypasses directory write permissions")
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
            assumeTrue(closed.listFiles() == null, "This account bypasses directory read permissions")

            assertFalse(SafSyncEngine(context).holdsOnlyVouchedCopies(mirror))
        } finally {
            closed.setReadable(true, true)
        }
    }
}
