package com.vscodroid.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * The attribution documents have to reach the device, and the pieces that make
 * that happen sit in three files that nothing else holds together.
 *
 * `app/build.gradle.kts` names the repository paths to copy. [Notices.BUNDLED]
 * names the asset basenames to open. `MainActivity.showLicensesDialog` opens
 * them. Rename `docs/LEGAL_NOTICES.md`, or drop a line from the task's document
 * list, and the build stays green, the APK still installs, and the licences
 * dialog quietly comes up half empty on a device. There is no compiler
 * relationship between the three, which is what this test supplies. Nor is
 * there one behind the buttons that open those dialogs, so the calls are held
 * here as well: a document nothing on screen reaches is packaged and
 * undelivered.
 *
 * It supplies it only because the build script is declared an input to the test
 * task. These tests read `build.gradle.kts` through the file system, which Gradle
 * cannot see: before that declaration existed, editing the build script left the
 * task UP-TO-DATE and every mutation to it passed. The `inputs.file` line in
 * `tasks.withType<Test>` is what makes the assertions below reachable, and
 * deleting it makes them silent rather than wrong.
 *
 * The stake is not cosmetic. The GPL requires its written offer of source to
 * accompany the binary, and this screen is the only place it does. It requires a
 * copy of the licence itself as well, which is what `licenses/COPYING.*` are, so
 * those are checked here too and are checked for more: a document that has been
 * truncated or reflowed is no longer the licence it is offered as.
 */
class NoticesTest {

    // Unit tests run with app/ as the working directory.
    private val repoRoot = File("../..")
    private val buildScript = File("build.gradle.kts")
    private val mainActivity = File("src/main/kotlin/com/vscodroid/MainActivity.kt")

    /** The body of the bundleNotices task, ended on a closing brace in the first column. */
    private fun taskBody(): String {
        val task = buildScript.readText().substringAfter("(\"bundleNotices\") {", "")
        assertFalse(task.isEmpty(), "bundleNotices task is gone from build.gradle.kts")
        return task.substringBefore("\n}")
    }

    /**
     * The entries of the bundleNotices task's `val documents = listOf(...)`.
     *
     * Found by name, not by task type. The type has an assertion of its own
     * below, and a parse anchored on it would answer a change of type with
     * "the task is gone", which is a different defect and the wrong one to fix.
     *
     * Each entry is anchored to the start of a line, with only whitespace
     * allowed in front. `build.gradle.kts` is Kotlin, so `//` disables a line
     * here exactly as it does anywhere else, and a substring search reads a
     * commented-out entry as a document the build copies. That is the shape
     * this whole file exists to refuse: the list below would go on agreeing
     * with [Notices.BUNDLED] while the Sync copied one document fewer and the
     * dialog came up short on a device. Every entry begins its own line, so
     * the anchor costs nothing.
     */
    private fun copiedPaths(): List<String> {
        val list = Regex("""(?m)^\s*val documents = listOf\(([^)]*)\)""")
            .find(taskBody())?.groupValues?.get(1)
        assertNotNull(list, "bundleNotices no longer lists its documents as `val documents = listOf(...)`")
        return Regex("""(?m)^\s*"([^"]+)",?\s*$""")
            .findAll(list!!).map { it.groupValues[1] }.toList()
    }

    @Test
    fun `every bundled name is a document the build actually copies`() {
        val copied = copiedPaths()
        assertEquals(
            (Notices.BUNDLED + Notices.LICENSE_TEXTS.values).sorted(),
            copied.map { File(it).name }.sorted(),
            "Notices.BUNDLED / Notices.LICENSE_TEXTS and bundleNotices disagree about " +
                "which documents ship. The app opens assets by basename; the task " +
                "decides which basenames exist."
        )
        for (path in copied) {
            val source = File(repoRoot, path)
            assertTrue(source.isFile, "bundleNotices copies $path, which does not exist")
            assertTrue(source.length() > 0, "$path is empty")
        }
    }

    @Test
    fun `what the copy writes is packaged, and something makes the copy run`() {
        val script = buildScript.readText()
        val body = taskBody()
        val destination = Regex("""(?m)^\s*into\(layout\.buildDirectory\.dir\("([^"]+)"\)\)""")
            .find(body)?.groupValues?.get(1)
        assertNotNull(destination, "bundleNotices names no destination directory")

        // The list is what the task copies. Parsing the list and not the copy
        // would let the two part company: a `from(...)` naming something else
        // leaves every assertion on the list green over an APK that ships it.
        assertTrue(
            Regex("""(?m)^\s*from\(\s*documents\s*\)""").containsMatchIn(body),
            "bundleNotices does not copy its `documents` list, so what this file " +
                "checks is not what the APK carries."
        )

        // Copying the documents is not packaging them. The destination has to be
        // an assets source directory or they land in build/ and stop there,
        // leaving the dialog with nothing but its two missing-document markers.
        //
        // Either spelling counts, because both register the same directory and
        // only one of them is a path. Naming the task is the better one: a bare
        // path leaves every consumer to declare the producer itself, and Gradle
        // fails the build when one does not, which is a thing that happens in
        // the release graph and nowhere a pull request would see it. Pinning the
        // path spelling alone would have turned that improvement red.
        //
        // Both are anchored at the start of a line, and the character class in
        // front is the anchor rather than the `^`: the live registration is
        // `sourceSets["main"].assets.srcDir(...)`, so a receiver has to be
        // allowed through, and what may not get through is a slash. A `//` in
        // front of the call cannot then satisfy either alternative. Without
        // that, commenting the registration out leaves this case green over an
        // APK whose notices directory is copied and never packaged, which is
        // the state the assertion exists to name.
        val anchored = """(?m)^\s*[^/\n]*"""
        val registersPath = Regex(anchored + "assets\\.srcDir\\([^)]*" +
            Regex.escape(destination!!) + "[^)]*\\)").containsMatchIn(script)
        val registersTask = Regex(anchored + """assets\.srcDir\(\s*bundleNotices\s*\)""")
            .containsMatchIn(script)
        assertTrue(
            registersPath || registersTask,
            "bundleNotices writes to $destination, and no assets.srcDir(...) " +
                "registers it, by that path or by naming the task. The documents " +
                "would be copied and never packaged."
        )

        // And the copy has to be made to run. Nothing infers it from the source
        // directory: on a clean build the directory is simply empty, and every
        // gate stays green while the APK ships no notices at all.
        // Line-anchored for the reason the registration above is: one of the two
        // live wirings is `.configureEach { dependsOn(bundleNotices) }`, so the
        // anchor has to let a receiver through and refuse a slash.
        assertTrue(
            Regex(anchored + "dependsOn\\([^)]*bundleNotices").containsMatchIn(script),
            "Nothing depends on bundleNotices, so a clean build packages an empty " +
                "notices directory."
        )

        // And it has to clear out what it no longer writes. A plain Copy leaves
        // the last run's files where they are: with a document renamed or
        // removed, the old one stayed in that directory and went into the next
        // incremental APK under a name the repository no longer had. CI checks
        // out clean and never saw it, which is what makes it worth a gate rather
        // than a habit.
        assertTrue(
            Regex(anchored + """tasks\.register<Sync>\("bundleNotices"\)""")
                .containsMatchIn(script),
            "bundleNotices is not a Sync, so a document removed or renamed stays " +
                "in the notices directory and ships from any incremental build."
        )
    }

    @Test
    fun `a document that is missing fails the copy rather than shortening it`() {
        // Sync and Copy both skip a from() that does not exist without a word,
        // so a checkout with a licence text moved aside reaches a signed AAB
        // with every packaging gate green, and Notices.readOne opens that
        // entry of the chooser on its missing-document marker. The sha256 pins
        // below would say so, but the test task is not in the packaging graph:
        // a local bundleRelease never runs them. The guard has to be in the
        // task.
        //
        // Read as text, like everything else here: a unit test cannot execute
        // a build-script task. What is asserted is that the task's own action
        // walks the same list the copy reads and throws on a member that is
        // not a file. Line-anchored, because a commented-out guard is no guard.
        val body = taskBody()
        val guard = Regex("""(?m)^\s*doFirst\s*\{""").find(body)
        assertNotNull(guard, "bundleNotices has no doFirst, so a missing source is skipped silently")
        val action = body.substring(guard!!.range.first)
        assertTrue(
            Regex("""(?m)^\s*[^/\n]*documents\.filterNot\s*\{\s*it\.isFile\s*}""").containsMatchIn(action),
            "bundleNotices' doFirst does not test each of `documents` with isFile, so " +
                "a missing one is not what it refuses"
        )
        assertTrue(
            Regex("""(?m)^\s*[^/\n]*throw GradleException\(""").containsMatchIn(action),
            "bundleNotices' doFirst throws nothing, so a missing document is reported " +
                "at most as a log line and the build stays green"
        )
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
    fun `every step of the route to these documents is still taken`() {
        // Packaging a document is not offering it. About opens the notices, the
        // notices open the licence texts, and each step is a private method with
        // one caller: drop the button that calls it and the APK still carries all
        // five documents, every assertion here still passes, and nothing on a
        // device can reach any of them. Nothing else notices either. Both methods
        // stay compiled, their own KDoc keeps naming them, and a string resource
        // left unreferenced is a lint warning, which does not fail a build whose
        // abortOnError covers errors.
        //
        // Reading the source is what is available: the activity binds a service
        // and builds a WebView on the way to these dialogs, so there is no seam
        // to reach them through. It cannot see a caller that is itself
        // unreachable, or a button whose label stops saying what it opens.
        check(mainActivity.isFile) {
            "MainActivity.kt not found at ${mainActivity.absolutePath}, so this " +
                "test would pass by looking at nothing"
        }
        val code = mainActivity.readLines().filterNot {
            val trimmed = it.trimStart()
            trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")
        }
        for (dialog in listOf("showLicensesDialog", "showLicenseTextsDialog")) {
            assertTrue(
                code.any { it.contains("$dialog()") && !it.contains("fun $dialog(") },
                "Nothing calls $dialog(), so what it shows is packaged and out of " +
                    "reach: the same state as when these documents lived only in " +
                    "the repository, at more cost."
            )
        }
    }

    /**
     * What each licence text has to still be: the name the chooser offers it
     * under, the version line it opens with, the line it ends on, and the sha256
     * of every byte.
     *
     * The hash is the whole check on the contents; the version and last lines are
     * there so a failure says which file and which defect rather than "two hex
     * strings differ". A truncation loses the last line, a file swapped for
     * another loses the version line, and either way the app would be handing out
     * something that is not the licence it claims to be.
     *
     * The name is a check on something else, and it needs one: these are keyed by
     * file, so swapping which name opens which file leaves every hash here
     * satisfied and still puts the wrong licence in front of the reader.
     *
     * Three of the four are the FSF texts as shipped in Termux's liblzma
     * package, which this APK redistributes; LGPL-3.0 is the FSF publication,
     * because no package in the base APK carries a copy of it. Re-derive with:
     *   shasum -a 256 licenses/COPYING.*
     */
    private data class Verbatim(
        val title: String,
        val version: String,
        val lastLine: String,
        val sha256: String
    )

    private val licenseTexts = mapOf(
        "COPYING.GPLv2" to Verbatim(
            "GNU General Public License v2.0",
            "Version 2, June 1991",
            "Public License instead of this License.",
            "edaef632cbb643e4e7a221717a6c441a4c1a7c918e6e4d56debc3d8739b233f6"
        ),
        "COPYING.GPLv3" to Verbatim(
            "GNU General Public License v3.0",
            "Version 3, 29 June 2007",
            "<https://www.gnu.org/licenses/why-not-lgpl.html>.",
            "3972dc9744f6499f0f9b2dbf76696f2ae7ad8af9b23dde66d6af86c9dfb36986"
        ),
        "COPYING.LGPLv2.1" to Verbatim(
            "GNU Lesser General Public License v2.1",
            "Version 2.1, February 1999",
            "That's all there is to it!",
            "20e50fe7aae3e56378ebf0417d9de904f55a0e61e4df315333e632a4d3555d95"
        ),
        // The one text here that no binary in the base APK needs. GMP is
        // LGPL-3.0 and ships in the Ruby toolchain pack, which has no licence
        // screen of its own, so this dialog is the only route to it on a device
        // that installed Ruby. The FSF publication at
        // https://www.gnu.org/licenses/lgpl-3.0.txt, byte for byte.
        "COPYING.LGPLv3" to Verbatim(
            "GNU Lesser General Public License v3.0",
            "Version 3, 29 June 2007",
            "Library.",
            "e3a994d82e644b03a792a930f574002658412f62407f5fee083f2555c5f23118"
        )
    )

    @Test
    fun `every licence text the app offers opens under the name it is offered as`() {
        // Two defects, one assertion. A fourth text could be added to the chooser
        // and ship unchecked, which is the state this whole file exists to end.
        // And the chooser is a list of names: whoever taps one is told which
        // licence they are about to be handed, so a name pointing at the other
        // file hands over the wrong licence while every pin below still passes,
        // since those are keyed by the file. GPL-2.0 and GPL-3.0 differ on
        // patents, on tivoization and on how a violation is cured, so which of
        // the two a recipient is given is the whole of the question.
        assertEquals(
            licenseTexts.entries.associate { (asset, pinned) -> pinned.title to asset },
            Notices.LICENSE_TEXTS.toMap(),
            "Notices.LICENSE_TEXTS offers a text with no verbatim check here, " +
                "pins one the app no longer offers, or opens a text under a name " +
                "that belongs to another licence."
        )

        // And the name has to be the version the text opens with, or both halves
        // of a pin could be swapped together and stay green. The FSF writes
        // "Version 2" where a name in SPDX shape writes v2.0.
        for (pinned in licenseTexts.values) {
            val numbered = pinned.title.substringAfterLast(" v").removeSuffix(".0")
            assertTrue(
                pinned.version.startsWith("Version $numbered,"),
                "'${pinned.title}' is pinned to a text opening '${pinned.version}'"
            )
        }
    }

    @Test
    fun `each licence text is the whole licence, byte for byte`() {
        // The obligation the source offer does not discharge. GPL-2.0 section 1,
        // GPL-3.0 section 4, LGPL-2.1 section 1 and LGPL-3.0 section 4 each
        // require a copy of the licence to reach whoever receives the binary,
        // and an edited or half-copied licence is not a copy of it.
        val copied = copiedPaths().associateBy { File(it).name }
        for ((asset, expected) in licenseTexts) {
            val path = copied[asset]
            assertNotNull(path, "bundleNotices copies no $asset, so the APK carries no such text")
            val file = File(repoRoot, path!!)
            assertTrue(file.isFile, "$path does not exist")

            val bytes = file.readBytes()
            val text = String(bytes, Charsets.UTF_8)
            assertTrue(
                text.lineSequence().take(3).any { it.contains(expected.version) },
                "$path does not open with '${expected.version}'. It is not the licence " +
                    "its name claims."
            )
            assertEquals(
                expected.lastLine,
                text.trimEnd().lines().last().trim(),
                "$path does not end where the licence ends. A truncated licence text " +
                    "satisfies nothing."
            )

            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            assertEquals(
                expected.sha256,
                digest.joinToString("") { "%02x".format(it) },
                "$path is no longer byte-for-byte the licence text. Reflowing, " +
                    "re-wrapping or 'tidying' one is a modification, and a modified " +
                    "licence is not the licence."
            )
        }
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
    fun `a licence text that cannot be read is named, not shown empty`() {
        // The chooser opens one text at a time and has no other reader, so an
        // empty view is the failure mode: it reads as a build that ships no
        // licence rather than one whose asset would not open.
        val asset = Notices.LICENSE_TEXTS.values.first()
        assertEquals(
            Notices.missingMarker(asset),
            Notices.readOne(asset) { throw IOException("not packaged") },
            "An unreadable licence text came back as something other than a marker"
        )
        assertEquals(
            "the licence",
            Notices.readOne(asset) { ByteArrayInputStream("the licence".toByteArray()) },
            "A readable licence text did not come back whole"
        )
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
