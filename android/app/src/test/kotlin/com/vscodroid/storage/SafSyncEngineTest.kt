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
 * constants, and data classes.
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
        fun `does not skip build directory - Q3 fix`() {
            // Q3: "build" was removed from SKIP_DIRECTORIES because it can contain
            // legitimate source files (e.g., Gradle build scripts, C build outputs)
            assertFalse(
                SafSyncEngine.shouldSkip("build", isDir = true),
                "build/ should NOT be skipped (Q3 review fix)"
            )
        }

        @Test
        fun `does not skip vscode directory - Q3 fix`() {
            // Q3: ".vscode" was removed because it contains workspace settings
            // that users want synced (launch.json, settings.json, extensions.json)
            assertFalse(
                SafSyncEngine.shouldSkip(".vscode", isDir = true),
                ".vscode/ should NOT be skipped (Q3 review fix)"
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

    // ── guessMimeType ───────────────────────────────────────────────────

    @Nested
    inner class GuessMimeTypeTest {

        @Test
        fun `text files`() {
            assertEquals("text/plain", SafSyncEngine.guessMimeType("readme.txt"))
            assertEquals("text/plain", SafSyncEngine.guessMimeType("CHANGELOG.md"))
        }

        @Test
        fun `web files`() {
            assertEquals("text/html", SafSyncEngine.guessMimeType("index.html"))
            assertEquals("text/css", SafSyncEngine.guessMimeType("styles.css"))
            assertEquals("text/javascript", SafSyncEngine.guessMimeType("app.js"))
            assertEquals("text/javascript", SafSyncEngine.guessMimeType("main.ts"))
        }

        @Test
        fun `data files`() {
            assertEquals("application/json", SafSyncEngine.guessMimeType("package.json"))
            assertEquals("text/xml", SafSyncEngine.guessMimeType("AndroidManifest.xml"))
        }

        @Test
        fun `programming language files`() {
            assertEquals("text/x-python", SafSyncEngine.guessMimeType("script.py"))
            assertEquals("text/plain", SafSyncEngine.guessMimeType("Main.kt"))
            assertEquals("text/plain", SafSyncEngine.guessMimeType("App.java"))
            assertEquals("text/x-shellscript", SafSyncEngine.guessMimeType("deploy.sh"))
        }

        @Test
        fun `unknown extensions fall back to octet-stream`() {
            assertEquals("application/octet-stream", SafSyncEngine.guessMimeType("binary.dat"))
            assertEquals("application/octet-stream", SafSyncEngine.guessMimeType("archive.zip"))
            assertEquals("application/octet-stream", SafSyncEngine.guessMimeType("image.png"))
            assertEquals("application/octet-stream", SafSyncEngine.guessMimeType("noext"))
        }

        @Test
        fun `extension matching is case-sensitive`() {
            // .TXT != .txt — this is the current behavior (could be a limitation)
            assertEquals("application/octet-stream", SafSyncEngine.guessMimeType("README.TXT"))
            assertEquals("application/octet-stream", SafSyncEngine.guessMimeType("APP.JS"))
        }

        @Test
        fun `handles dotfiles without real extension`() {
            assertEquals("application/octet-stream", SafSyncEngine.guessMimeType(".gitignore"))
            assertEquals("application/octet-stream", SafSyncEngine.guessMimeType(".dockerignore"))
        }

        @Test
        fun `handles files with multiple dots`() {
            assertEquals("text/javascript", SafSyncEngine.guessMimeType("app.config.js"))
            assertEquals("text/javascript", SafSyncEngine.guessMimeType("vite.config.ts"))
            assertEquals("application/json", SafSyncEngine.guessMimeType("tsconfig.build.json"))
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
        fun `SKIP_DIRECTORIES does not contain Q3 removed entries`() {
            // Regression: these were removed in the Q3 review fix
            assertFalse("build" in SafSyncEngine.SKIP_DIRECTORIES, "build was removed in Q3")
            assertFalse(".vscode" in SafSyncEngine.SKIP_DIRECTORIES, ".vscode was removed in Q3")
        }
    }

    // ── Data Classes ────────────────────────────────────────────────────

    @Nested
    inner class DataClassTest {

        @Test
        fun `DocumentInfo stores fields correctly`() {
            val uri = mockk<Uri>()
            val doc = DocumentInfo(
                uri = uri,
                docId = "primary:MyProject/src/main.kt",
                relativePath = "src/main.kt",
                isDirectory = false,
                size = 1024
            )
            assertEquals("primary:MyProject/src/main.kt", doc.docId)
            assertEquals("src/main.kt", doc.relativePath)
            assertFalse(doc.isDirectory)
            assertEquals(1024L, doc.size)
        }

        @Test
        fun `SyncJob stores fields correctly`() {
            val docUri = mockk<Uri>()
            val parentUri = mockk<Uri>()
            val treeUri = mockk<Uri>()
            val job = SyncJob(
                type = SyncType.MODIFY,
                localPath = "/data/data/com.vscodroid/files/saf-mirrors/abc123/test.txt",
                safDocUri = docUri,
                safParentUri = parentUri,
                safTreeUri = treeUri,
                timestamp = 1700000000000L
            )
            assertEquals(SyncType.MODIFY, job.type)
            assertEquals(1700000000000L, job.timestamp)
        }

        @Test
        fun `SyncType has all expected values`() {
            val types = SyncType.entries
            assertEquals(3, types.size)
            assertTrue(types.contains(SyncType.MODIFY))
            assertTrue(types.contains(SyncType.CREATE))
            assertTrue(types.contains(SyncType.DELETE))
        }

        @Test
        fun `SyncJob allows null SAF URIs for CREATE and DELETE`() {
            // CREATE jobs may have null safDocUri (file doesn't exist in SAF yet)
            val createJob = SyncJob(SyncType.CREATE, "/path", null, null, null, 0)
            assertEquals(null, createJob.safDocUri)

            // DELETE jobs may have null safParentUri
            val deleteJob = SyncJob(SyncType.DELETE, "/path", mockk(), null, null, 0)
            assertEquals(null, deleteJob.safParentUri)
        }
    }
}
