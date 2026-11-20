package com.vscodroid.storage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * What a delete made in the app's copy of a device folder does while the folder is closed and nothing is
 * watching the copy.
 *
 * A deletion reaches the device only as the event it raises while the folder is open. An open that finds a
 * document missing from the copy copies it in again. The other reading, a document missing from the copy being a
 * deletion to send, would let a copy lost for any other reason (a cleared cache, a restored backup, a revoked and
 * granted folder) delete the person's files on the device, so it is not taken. The user guide says so.
 */
class SafClosedFolderDeleteTest : SafInitialSyncTestBase() {

    @Test
    fun `a file deleted in the copy while the folder was closed is copied in again at the next open`() {
        deviceTree(mapOf("root" to listOf(Child("keep.txt", contents = "k"), Child("notes.txt", contents = "kept on the device"))))
        sync()
        created.clear()
        mirrorFile("notes.txt").delete()

        sync()

        assertEquals("kept on the device", mirrorFile("notes.txt").readText(), "the file stayed deleted in the copy")
        assertEquals(emptyList<String>(), created.map { it.second }, "the open created documents on the device")
    }
}
