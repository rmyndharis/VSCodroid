package com.vscodroid.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException

/**
 * The attribution documents have to reach the device, and the pieces that make
 * that happen sit in three files that nothing else holds together.
 *
 * `app/build.gradle.kts` names the repository paths to copy. [Notices.BUNDLED]
 * names the asset basenames to open. `MainActivity.showLicensesDialog` opens
 * them. Rename `docs/LEGAL_NOTICES.md`, or drop a `from(...)` line, and the
 * build stays green, the APK still installs, and the licences dialog quietly
 * comes up half empty on a device. There is no compiler relationship between the
 * three, which is what this test supplies.
 *
 * The stake is not cosmetic. The GPL requires its written offer of source to
 * accompany the binary, and this screen is the only place it does.
 */
class NoticesTest {

    // Unit tests run with app/ as the working directory.
    private val repoRoot = File("../..")
    private val buildScript = File("build.gradle.kts")

    /** The `from(File(repoRoot, "..."))` arguments of the bundleNotices task. */
    private fun copiedPaths(): List<String> {
        val script = buildScript.readText()
        val task = script.substringAfter("tasks.register<Copy>(\"bundleNotices\")", "")
        assertFalse(task.isEmpty(), "bundleNotices task is gone from build.gradle.kts")
        val body = task.substringBefore("\n}")
        return Regex("""from\(File\(repoRoot,\s*"([^"]+)"\)\)""")
            .findAll(body).map { it.groupValues[1] }.toList()
    }

    @Test
    fun `every bundled name is a document the build actually copies`() {
        val copied = copiedPaths()
        assertEquals(
            Notices.BUNDLED.sorted(),
            copied.map { File(it).name }.sorted(),
            "Notices.BUNDLED and bundleNotices disagree about which documents ship. " +
                "The app opens assets by basename; the task decides which basenames exist."
        )
        for (path in copied) {
            val source = File(repoRoot, path)
            assertTrue(source.isFile, "bundleNotices copies $path, which does not exist")
            assertTrue(source.length() > 0, "$path is empty")
        }
    }

    @Test
    fun `the written offer of source is among what reaches the device`() {
        // The one clause that is a legal obligation rather than a courtesy.
        // Bundling the attribution table alone would look like compliance and
        // discharge nothing, so the heading is asserted rather than assumed from
        // the file being present.
        val offer = copiedPaths().map { File(repoRoot, it) }
            .filter { it.isFile }
            .any { it.readText().contains("## GPL Source Code Availability") }
        assertTrue(
            offer,
            "No bundled document carries the '## GPL Source Code Availability' " +
                "section. The GPL binaries in this APK would ship with no written offer."
        )
    }

    @Test
    fun `read returns every document, in order`() {
        val text = Notices.read { name -> ByteArrayInputStream("body of $name".toByteArray()) }
        var cursor = -1
        for (name in Notices.BUNDLED) {
            val at = text.indexOf("body of $name")
            assertTrue(at > cursor, "$name is missing from the joined text, or out of order")
            cursor = at
        }
    }

    @Test
    fun `a document that cannot be read is named, not dropped`() {
        val missing = Notices.BUNDLED.last()
        val text = Notices.read { name ->
            if (name == missing) throw IOException("not packaged")
            ByteArrayInputStream("body of $name".toByteArray())
        }
        // Named on screen rather than swallowed: a silently shortened notices
        // screen reads exactly like a complete one.
        assertTrue(
            text.contains(Notices.missingMarker(missing)),
            "An unreadable document vanished from the notices text instead of saying so"
        )
        assertTrue(text.contains("body of ${Notices.BUNDLED.first()}"), "The readable document was lost too")
    }
}
