package com.vscodroid.storage

import io.mockk.every
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

class SafReopenDataPreservationTest : SafInitialSyncTestBase() {

    @ParameterizedTest
    @ValueSource(strings = ["absent", "unsupported"])
    fun `a newer device document retains an existing copy without usable history`(history: String) {
        localAt("UNIQUE", DEVICE_TIME)
        if (history == "unsupported") record.writeText("#vscodroid-saf-sync 99\nnotes.txt\t$DEVICE_TIME\t6")
        deviceTree(mapOf("root" to listOf(Child("notes.txt", contents = "DEVICE", modified = DEVICE_TIME + 1000))))

        sync()

        assertEquals("DEVICE", mirrorFile("notes.txt").readText())
        assertRetained("UNIQUE")
        assertFalse(written.containsKey("doc-uri:doc:notes.txt"), "the original device document was overwritten")
    }

    @Test
    fun `a failed first record write does not make a later edit disposable`() {
        val blocked = File(record.path + SafSyncEngine.PARTIAL_SUFFIX).apply { assertTrue(mkdirs()) }
        File(blocked, "held").writeText("x")
        deviceTree(mapOf("root" to listOf(Child("notes.txt", contents = "original"))))
        try {
            sync()
            assertEquals("original", mirrorFile("notes.txt").readText())
            assertFalse(record.exists(), "the fixture did not prevent the first record write")
        } finally {
            assertTrue(blocked.deleteRecursively())
        }
        localAt("UNIQUE", DEVICE_TIME + 1000)
        deviceTree(mapOf("root" to listOf(Child("notes.txt", contents = "DEVICE", modified = DEVICE_TIME + 2000))))

        sync()

        assertEquals("DEVICE", mirrorFile("notes.txt").readText())
        assertRetained("UNIQUE")
    }

    @Test
    fun `a new document imports with no history`() {
        deviceTree(mapOf("root" to listOf(Child("notes.txt", contents = "DEVICE"))))

        sync()

        assertEquals("DEVICE", mirrorFile("notes.txt").readText())
        assertEquals(listOf("notes.txt"), mirror.list()!!.toList())
    }

    @Test
    fun `matching bytes without history do not create a conflict copy`() {
        localAt("DEVICE", DEVICE_TIME)
        deviceTree(mapOf("root" to listOf(Child("notes.txt", contents = "DEVICE", modified = DEVICE_TIME + 1000))))

        sync()

        assertEquals(listOf("notes.txt"), mirror.list()!!.toList())
        assertEquals("DEVICE", mirrorFile("notes.txt").readText())
    }

    @Test
    fun `an unchanged recorded copy follows a newer device document`() {
        deviceTree(mapOf("root" to listOf(Child("notes.txt", contents = "old"))))
        sync()
        deviceTree(mapOf("root" to listOf(Child("notes.txt", contents = "DEVICE", modified = DEVICE_TIME + 1000))))

        sync()

        assertEquals(listOf("notes.txt"), mirror.list()!!.toList())
        assertEquals("DEVICE", mirrorFile("notes.txt").readText())
    }

    @ParameterizedTest
    @ValueSource(strings = ["legacy", "absent"])
    fun `matching metadata does not certify different local bytes before a later device deletion`(history: String) {
        localAt("NEW", DEVICE_TIME)
        if (history == "legacy") record.writeText(SafSyncEngine.RECORD_HEADER + "\nnotes.txt\t$DEVICE_TIME\t3")
        deviceTree(mapOf("root" to listOf(Child("notes.txt", contents = "old"), Child("keep.txt", contents = "k"))))

        sync()

        assertRetained("NEW")
        assertFalse(written.containsKey("doc-uri:doc:notes.txt"), "the metadata tie uploaded a version without choosing a winner")
        val firstRecord = record.readText()
        val newDigest = MessageDigest.getInstance("SHA-256").digest("NEW".toByteArray())
        val falseReceipt = SafSyncEngine.recordLine("notes.txt\t$DEVICE_TIME\t3", newDigest)
        deviceTree(mapOf("root" to listOf(Child("keep.txt", contents = "k"))))

        sync()

        assertRetained("NEW", "the preceding metadata-only open wrote this record: $firstRecord")
        assertFalse(firstRecord.lineSequence().any { it == falseReceipt }, "different local bytes became a delivered receipt")
    }

    @Test
    fun `an earlier device digest continues to protect a same metadata local edit`() {
        localAt("NEW", DEVICE_TIME)
        val oldDigest = MessageDigest.getInstance("SHA-256").digest("old".toByteArray())
        record.writeText(SafSyncEngine.RECORD_HEADER + "\n" + SafSyncEngine.recordLine("notes.txt\t$DEVICE_TIME\t3", oldDigest))
        deviceTree(mapOf("root" to listOf(Child("notes.txt", contents = "old"), Child("keep.txt", contents = "k"))))
        sync()
        deviceTree(mapOf("root" to listOf(Child("keep.txt", contents = "k"))))

        sync()

        assertRetained("NEW")
    }

    @Test
    fun `a matching legacy copy gains proof before following a device deletion`() {
        localAt("old", DEVICE_TIME)
        record.writeText(SafSyncEngine.RECORD_HEADER + "\nnotes.txt\t$DEVICE_TIME\t3")
        deviceTree(mapOf("root" to listOf(Child("notes.txt", contents = "old"), Child("keep.txt", contents = "k"))))

        val engine = sync()

        assertTrue(engine.holdsOnlyVouchedCopies(mirror))
        deviceTree(mapOf("root" to listOf(Child("keep.txt", contents = "k"))))
        sync()
        assertFalse(mirrorFile("notes.txt").exists())
    }

    @Test
    fun `a receipt keeps the compared digest when the mirror changes as the provider closes`() {
        localAt("old", DEVICE_TIME)
        record.writeText(SafSyncEngine.RECORD_HEADER + "\nnotes.txt\t$DEVICE_TIME\t3")
        deviceTree(mapOf("root" to listOf(Child("notes.txt", contents = "old"), Child("keep.txt", contents = "k"))))
        var compared = false
        every { resolver.openInputStream(match { it.toString() == "doc-uri:doc:notes.txt" }) } answers {
            object : ByteArrayInputStream("old".toByteArray()) {
                override fun close() {
                    super.close()
                    localAt("NEW", DEVICE_TIME)
                    compared = true
                }
            }
        }

        val engine = sync()

        assertTrue(compared, "the provider bytes were never checked before recording proof")
        assertEquals("NEW", mirrorFile("notes.txt").readText())
        assertFalse(engine.holdsOnlyVouchedCopies(mirror))
        deviceTree(mapOf("root" to listOf(Child("keep.txt", contents = "k"))))
        sync()
        assertRetained("NEW")
    }

    @ParameterizedTest
    @ValueSource(strings = ["hello", ""])
    fun `an unrecorded local suffix entry keeps its filename and length`(contents: String) {
        openDeviceNotes()
        val local = mirrorFile("notes.md${SafSyncEngine.PARTIAL_SUFFIX}").apply { writeText(contents) }

        sync()

        assertTrue(local.isFile, "the independent workspace entry disappeared")
        assertEquals(contents, local.readText())
        assertEquals(contents.toByteArray().size.toLong(), local.length())
        assertEquals("hello world", mirrorFile("notes.md").readText())
    }

    @Test
    fun `a reopen retains an unproved suffix entry or its replacement`() {
        openDeviceNotes()
        val local = mirrorFile("notes.md${SafSyncEngine.PARTIAL_SUFFIX}").apply { writeText("hello") }
        val originalKey = Files.readAttributes(local.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java).fileKey()
        var closed = false
        var replacementKey: Any? = null
        every { resolver.openInputStream(any()) } answers {
            object : ByteArrayInputStream("hello world".toByteArray()) {
                override fun close() {
                    super.close()
                    val replacement = File(filesDir, "replacement").apply { writeText("UNIQUE USER EDIT") }
                    replacementKey = Files.readAttributes(replacement.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java).fileKey()
                    Files.move(replacement.toPath(), local.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    closed = true
                }
            }
        }

        sync()

        assertTrue(local.isFile, "a replacement inode was removed after an earlier inode was compared")
        if (closed) {
            assertTrue(originalKey != replacementKey, "the fixture did not replace the directory entry")
            assertEquals("UNIQUE USER EDIT", local.readText())
        } else {
            assertEquals("hello", local.readText())
        }
        assertEquals("hello world", mirrorFile("notes.md").readText())
    }

    private fun localAt(contents: String, modified: Long) {
        mirrorFile("notes.txt").apply {
            writeText(contents)
            assertTrue(setLastModified(modified))
            assertEquals(modified, lastModified())
        }
    }

    private fun assertRetained(contents: String, detail: String = "the local content was not retained") {
        assertTrue(mirror.walkTopDown().filter { it.isFile }.any { it.readText() == contents }, detail)
    }

    private fun openDeviceNotes() {
        deviceTree(mapOf("root" to listOf(Child("notes.md", contents = "hello world"))))
        sync()
        assertEquals("hello world", mirrorFile("notes.md").readText())
    }
}
