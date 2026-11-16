package com.vscodroid.webview

import com.vscodroid.SourceScan
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * That the Activity measures a chosen document through a descriptor of its own opened
 * read-only, and from nothing else.
 *
 * The coordinator removes a document only when this measurement says it is empty, so the
 * measurement is what stands between a failed download and a file the user had. Measured
 * through the descriptor the download writes with, a provider that truncates on "wt" makes
 * every document look empty; measured from the provider's listing, a lagging size or a
 * coarse clock decides. Source reading, because `ContentResolver` and `Os` are not
 * reachable from this JVM.
 *
 * NEGATIVE CONTROL, run: with the mode "r" changed to "w", and with the occupied bytes taken
 * from `st_size`, a case reddens.
 */
class DownloadMeasureWiringTest {
    private val body = SourceScan.withoutComments(
        SourceScan.body(
            SourceScan.read("src/main/kotlin/com/vscodroid/MainActivity.kt"),
            "override fun measureDestination(",
        )
    )

    @Test
    fun `the chosen document is measured through a read-only descriptor`() {
        assertTrue(body.contains("contentResolver.openFileDescriptor(destination, \"r\")")) {
            "measureDestination no longer opens the document read-only, so the look itself can truncate it"
        }
        assertTrue(body.contains("Os.fstat(") && body.contains("OsConstants.S_ISREG(")) {
            "measureDestination no longer reads the length from the descriptor of a regular file"
        }
    }

    @Test
    fun `room is asked of the descriptor's own file system, and blocks are what is credited`() {
        assertTrue(body.contains("Os.fstatvfs(fd)") && body.contains("f_bavail * vfs.f_frsize")) {
            "measureDestination no longer asks the document's own file system for the room an app may use"
        }
        assertTrue(body.contains("occupied = stat.st_blocks * 512")) {
            "measureDestination no longer reports the blocks a document occupies, and its length would " +
                "credit a sparse file with room it does not hold"
        }
    }

    @Test
    fun `staging room uses the app storage helper`() {
        val activity = SourceScan.withoutComments(SourceScan.read("src/main/kotlin/com/vscodroid/MainActivity.kt"))
        val initializer = activity
            .substringAfter("private val downloads: DownloadCoordinator = DownloadCoordinator(", "")
            .substringBefore("private val serviceConnection", "")
        assertTrue(initializer.contains("stagingRoom = { StorageManager.getAvailableStorage(this@MainActivity) }"))
    }

    @Test
    fun `nothing but the descriptor decides`() {
        for (listing in listOf(".query(", "COLUMN_SIZE", "COLUMN_LAST_MODIFIED", "currentTimeMillis")) {
            assertFalse(body.contains(listing)) {
                "measureDestination reads $listing, and a listing or a clock cannot tell a new document from one the user had"
            }
        }
    }

    @Test
    fun `the chosen document's display name is read back from the provider`() {
        val nameBody = SourceScan.withoutComments(
            SourceScan.body(
                SourceScan.read("src/main/kotlin/com/vscodroid/MainActivity.kt"),
                "override fun destinationName(",
            )
        )
        assertTrue(nameBody.contains("contentResolver.query(") && nameBody.contains("DocumentsContract.Document.COLUMN_DISPLAY_NAME")) {
            "destinationName in MainActivity does not query COLUMN_DISPLAY_NAME for the chosen document"
        }
    }
}
