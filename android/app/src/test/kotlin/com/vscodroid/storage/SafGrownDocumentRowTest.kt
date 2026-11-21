package com.vscodroid.storage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A document that has grown past the size limit since the app copied it, and what the app's older copy of it
 * stands for.
 *
 * Builds up to 1.9.0 dropped the row of such a file from the record at the open that found it too large. The
 * copy then read as work only this app had, and a deletion of the document on the device put it back there with
 * its old bytes. The copy's time is the time the device reported when it was made, so a copy older than the
 * document is that earlier copy and keeps a row, which keeps it in the mirror and keeps it off the device. A copy
 * made or saved after the document last changed is the person's, and goes to the device when the document is
 * dropped. Only a record an earlier build wrote can have lost such a row: this build keeps the row of a copy it
 * did not fetch, so a copy with none under a record of its own is the person's work.
 */
class SafGrownDocumentRowTest : SafInitialSyncTestBase() {

    private fun names() = created.map { it.second }

    private val grown = SafSyncEngine.MAX_FILE_SIZE + 1

    /**
     * The first open copies both files and records them; an earlier build then dropped the row of big.bin, and
     * the app was updated since.
     */
    private fun openedByAnEarlierBuild() {
        deviceTree(mapOf("root" to listOf(Child("keep.txt", contents = "k"), Child("big.bin", contents = "v1"))))
        sync()
        record.writeText(record.readLines().filterNot { it.startsWith("big.bin\t") }.joinToString("\n"))
        recordLeftByEarlierBuild()
        created.clear()
    }

    private fun deviceHoldsBig(modified: Long) = deviceTree(
        mapOf("root" to listOf(Child("keep.txt", contents = "k"), Child("big.bin", contents = "v1", size = grown, modified = modified))),
    )

    private fun deviceDeletesBig() = deviceTree(mapOf("root" to listOf(Child("keep.txt", contents = "k"))))

    @Test
    fun `an older copy of a grown document whose row was dropped is not put back when the device deletes it`() {
        openedByAnEarlierBuild()
        deviceHoldsBig(DEVICE_TIME + 60_000)
        sync()
        deviceDeletesBig()

        sync()

        assertEquals(emptyList<String>(), names(), "the old copy of a document the device deleted was put back there")
        assertEquals("v1", mirrorFile("big.bin").readText(), "the copy was removed instead of kept")
    }

    /**
     * The device deleted the document before the first open after the update, so no open of this build saw it
     * grown. The copy is older than the record, has no row and no upload in flight: it was sent back to the device
     * with its old bytes. It is kept inside the app, and the person is told once.
     */
    @Test
    fun `an older copy whose row an earlier build dropped is not put back when the device deleted it before the update`() {
        openedByAnEarlierBuild()
        deviceDeletesBig()

        sync()

        assertEquals(emptyList<String>(), names(), "the old copy of a document the device deleted was put back there")
        assertEquals("v1", mirrorFile("big.bin").readText(), "the copy was removed instead of kept")
        assertEquals(listOf(1 to "big.bin"), keptInApp, "the copy kept inside the app was not told of")

        sync()

        assertEquals(emptyList<String>(), names(), "a later open put the old copy back")
        assertEquals(emptyList<Pair<Int, String>>(), keptInApp, "a later open told of the same copy again")
    }

    /**
     * A copy the person saved while the document was too large to fetch is let go from the record by the open
     * that finds it changed. A later change of the document on the device made the copy older than it, and the
     * next open gave the copy a row without a digest as if an earlier build had dropped it: once the device
     * deleted the document, the person's edit was kept inside the app for good and nothing said so.
     */
    @Test
    fun `an edit of a grown document whose row this build let go is sent when the device deletes it`() {
        deviceTree(mapOf("root" to listOf(Child("keep.txt", contents = "k"), Child("big.bin", contents = "v1"))))
        sync()
        mirrorFile("big.bin").apply { writeText("v1 and an edit"); setLastModified(DEVICE_TIME + 1_000) }
        deviceHoldsBig(DEVICE_TIME + 60_000)
        sync()
        deviceHoldsBig(DEVICE_TIME + 120_000)
        sync()
        deviceDeletesBig()

        sync()

        assertEquals(listOf("big.bin"), names(), "the person's edit never reached the device")
        assertEquals("v1 and an edit", written["doc-uri:doc:big.bin"]?.toString())
    }

    @Test
    fun `a copy saved after the document last changed is still put back when the device deletes it`() {
        openedByAnEarlierBuild()
        mirrorFile("big.bin").setLastModified(DEVICE_TIME + 120_000)
        deviceHoldsBig(DEVICE_TIME + 60_000)
        sync()
        deviceDeletesBig()

        sync()

        assertEquals(listOf("big.bin"), names(), "work the person saved was not sent when the document was dropped")
    }

    @Test
    fun `a document that reports no time gets no row for its copy`() {
        openedByAnEarlierBuild()
        deviceHoldsBig(0L)
        sync()
        deviceDeletesBig()

        sync()

        assertEquals(listOf("big.bin"), names())
    }
}
