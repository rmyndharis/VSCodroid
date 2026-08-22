package com.vscodroid.storage

import android.net.Uri
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * Tests for [SafSyncEngine] pure logic: directory skipping, MIME type detection,
 * the overwrite decision, and constants.
 *
 * These tests validate the sync filtering rules that determine which files get
 * mirrored and how new files are classified when synced back to SAF.
 */
class SafSyncEngineTest {

    // ── shouldSkip ──────────────────────────────────────────────────────

    @Nested
    inner class ShouldSkipTest {

        @ParameterizedTest(name = "should skip directory: {0}")
        @ValueSource(strings = ["node_modules", ".git", "__pycache__", ".gradle", ".idea", "venv", ".env"])
        fun `skips known large or generated directories`(dirName: String) {
            assertTrue(
                SafSyncEngine.shouldSkip(dirName, isDir = true),
                "Directory '$dirName' should be skipped during sync"
            )
        }

        @ParameterizedTest(name = "should NOT skip directory: {0}")
        @ValueSource(strings = ["src", "lib", "app", "test", "docs", "public", "assets"])
        fun `does not skip normal source directories`(dirName: String) {
            assertFalse(
                SafSyncEngine.shouldSkip(dirName, isDir = true),
                "Directory '$dirName' should not be skipped"
            )
        }

        @Test
        fun `does not skip build directory`() {
            // "build" is deliberately not in SKIP_DIRECTORIES: it can contain
            // legitimate source files (e.g., Gradle build scripts, C build outputs)
            assertFalse(
                SafSyncEngine.shouldSkip("build", isDir = true),
                "build/ should NOT be skipped"
            )
        }

        @Test
        fun `does not skip vscode directory`() {
            // ".vscode" is deliberately not in SKIP_DIRECTORIES: it contains workspace
            // settings that users want synced (launch.json, settings.json, extensions.json)
            assertFalse(
                SafSyncEngine.shouldSkip(".vscode", isDir = true),
                ".vscode/ should NOT be skipped"
            )
        }

        @Test
        fun `never skips files regardless of name`() {
            // Files named like skip-directories should still be synced
            // (e.g., a file literally named "node_modules" or ".git")
            assertFalse(SafSyncEngine.shouldSkip("node_modules", isDir = false))
            assertFalse(SafSyncEngine.shouldSkip(".git", isDir = false))
            assertFalse(SafSyncEngine.shouldSkip("__pycache__", isDir = false))
            assertFalse(SafSyncEngine.shouldSkip(".env", isDir = false))
        }

        @Test
        fun `skip check is case-sensitive`() {
            // "Node_Modules" or "NODE_MODULES" should NOT be skipped
            // (file systems can be case-sensitive)
            assertFalse(SafSyncEngine.shouldSkip("Node_Modules", isDir = true))
            assertFalse(SafSyncEngine.shouldSkip("NODE_MODULES", isDir = true))
            assertFalse(SafSyncEngine.shouldSkip(".GIT", isDir = true))
        }
    }

    // ── shouldOverwriteMirror ───────────────────────────────────────────

    @Nested
    inner class ShouldOverwriteMirrorTest {

        @Test
        fun `copies when the mirror has no copy yet`() {
            assertTrue(
                SafSyncEngine.shouldOverwriteMirror(
                    mirrorExists = false, mirrorModified = 0, mirrorSize = 0,
                    sourceModified = 1_700_000_000_000, sourceSize = 4096, mirrorIsOurCopy = { false }
                ),
                "First sync must copy: there is nothing to lose"
            )
        }

        @Test
        fun `overwrites when the device copy is newer`() {
            assertTrue(
                SafSyncEngine.shouldOverwriteMirror(
                    mirrorExists = true, mirrorModified = 1_700_000_000_000, mirrorSize = 4096,
                    sourceModified = 1_700_000_060_000, sourceSize = 4096, mirrorIsOurCopy = { false }
                ),
                "An edit made outside the app should reach the mirror"
            )
        }

        @Test
        fun `keeps local edits that are newer than the device copy`() {
            // The defect this guards: reopening a folder re-ran the full copy and
            // truncated the mirror, discarding edits not yet written back.
            assertFalse(
                SafSyncEngine.shouldOverwriteMirror(
                    mirrorExists = true, mirrorModified = 1_700_000_060_000, mirrorSize = 4096,
                    sourceModified = 1_700_000_000_000, sourceSize = 4096, mirrorIsOurCopy = { false }
                ),
                "A newer local edit must survive reopening the folder"
            )
        }

        @Test
        fun `does not overwrite when both sides match`() {
            assertFalse(
                SafSyncEngine.shouldOverwriteMirror(
                    mirrorExists = true, mirrorModified = 1_700_000_000_000, mirrorSize = 4096,
                    sourceModified = 1_700_000_000_000, sourceSize = 4096, mirrorIsOurCopy = { false }
                ),
                "Same timestamp and same size means the copy is current"
            )
        }

        @Test
        fun `keeps a newer local file even when its length differs`() {
            // This pairing is the common one: almost every edit changes a file's
            // length. Checking size before the timestamps meant almost every unsaved
            // edit was read as "not a copy of the source" and overwritten, the exact
            // case the guard exists for.
            //
            // A copy cut short used to produce the same pairing, which is why size
            // came first. It cannot any more: copies are written beside the target and
            // moved into place only once complete, so a failure leaves no file here.
            assertFalse(
                SafSyncEngine.shouldOverwriteMirror(
                    mirrorExists = true, mirrorModified = 1_700_000_060_000, mirrorSize = 700_000,
                    sourceModified = 1_700_000_000_000, sourceSize = 2_000_000, mirrorIsOurCopy = { false }
                ),
                "A newer local edit must survive regardless of how its length changed"
            )
        }

        @Test
        fun `copies when the timestamps match but the contents differ in length`() {
            // Writers that preserve mtime (unzip, cp -p, rsync -t, git checkout)
            // change content without moving the clock. Size is the tiebreaker there.
            assertTrue(
                SafSyncEngine.shouldOverwriteMirror(
                    mirrorExists = true, mirrorModified = 1_700_000_000_000, mirrorSize = 4096,
                    sourceModified = 1_700_000_000_000, sourceSize = 8192, mirrorIsOurCopy = { false }
                ),
                "Equal timestamps with different lengths means the device copy changed"
            )
        }

        /**
         * COLUMN_LAST_MODIFIED is optional; MTP, some USB-OTG and network providers leave
         * it null, which arrives as 0. With no clock to compare, the record decides.
         *
         * These two are one rule seen from both sides, and each is the other's control.
         * Copying unconditionally, which is what this did before, replaced a local edit
         * on every reopen. Keeping unconditionally would freeze the folder for ever,
         * since nothing in the app can clear a mirror.
         */
        @Test
        fun `an unknown timestamp refreshes a mirror the last sync wrote`() {
            assertTrue(
                SafSyncEngine.shouldOverwriteMirror(
                    mirrorExists = true, mirrorModified = 1_700_000_000_000, mirrorSize = 4096,
                    sourceModified = 0, sourceSize = 4096, mirrorIsOurCopy = { true }
                ),
                "the mirror is this app's own copy, so the folder must not freeze"
            )
        }

        @Test
        fun `an unknown timestamp keeps a mirror that has changed since`() {
            assertFalse(
                SafSyncEngine.shouldOverwriteMirror(
                    mirrorExists = true, mirrorModified = 1_700_000_000_000, mirrorSize = 4096,
                    sourceModified = 0, sourceSize = 4096, mirrorIsOurCopy = { false }
                ),
                "the mirror no longer matches what was recorded for it, so it holds an " +
                    "edit that was never written back and is the only copy"
            )
        }

        @Test
        fun `still copies an absent file when the provider reports no timestamp`() {
            assertTrue(
                SafSyncEngine.shouldOverwriteMirror(
                    mirrorExists = false, mirrorModified = 0, mirrorSize = 0,
                    sourceModified = 0, sourceSize = 4096, mirrorIsOurCopy = { false }
                ),
                "A missing mirror file must still be fetched even without timestamps"
            )
        }

        @Test
        fun `a document with no reported time still refreshes the mirror`() {
            // DocumentInfo.lastModified defaults to 0, and that default is the one
            // field anything depends on: shouldOverwriteMirror keys its unknown-time
            // branch on exactly 0.
            //
            // Change the default to -1 and that branch stops firing. The comparison
            // falls through to mirrorModified > sourceModified, which is true against
            // any real mirror time, so the copy is skipped -- a provider that reports
            // no timestamp gets its first copy and never an update again, silently.
            val doc = DocumentInfo(
                uri = mockk<Uri>(),
                docId = "primary:MyProject/src/main.kt",
                relativePath = "src/main.kt",
                isDirectory = false,
                size = 2048
            )
            assertEquals(0L, doc.lastModified, "an unreported provider time is 0")
            assertTrue(
                SafSyncEngine.shouldOverwriteMirror(
                    mirrorExists = true,
                    mirrorModified = 1_700_000_000_000L,
                    mirrorSize = 1024,
                    sourceModified = doc.lastModified,
                    sourceSize = doc.size, mirrorIsOurCopy = { true }
                ),
                "a document with no reported time must reach the unknown-time branch " +
                    "rather than falling through to the clock comparison"
            )
        }
    }

    // ── Constants ────────────────────────────────────────────────────────

    @Nested
    inner class ConstantsTest {

        @Test
        fun `MAX_FILE_SIZE is 50 MB`() {
            assertEquals(
                50L * 1024 * 1024,
                SafSyncEngine.MAX_FILE_SIZE,
                "Max file size should be exactly 50 MB"
            )
        }

        @Test
        fun `SKIP_DIRECTORIES contains exactly the expected entries`() {
            val expected = setOf("node_modules", ".git", "__pycache__", ".gradle", ".idea", "venv", ".env")
            assertEquals(expected, SafSyncEngine.SKIP_DIRECTORIES)
        }

        @Test
        fun `SKIP_DIRECTORIES contains neither build nor vscode`() {
            // Regression: both hold files users expect to reach the device, so
            // adding either to the skip set breaks sync silently
            assertFalse("build" in SafSyncEngine.SKIP_DIRECTORIES, "build must stay syncable")
            assertFalse(".vscode" in SafSyncEngine.SKIP_DIRECTORIES, ".vscode must stay syncable")
        }
    }
}
