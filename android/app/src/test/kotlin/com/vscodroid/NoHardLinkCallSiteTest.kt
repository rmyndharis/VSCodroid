package com.vscodroid

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Nothing under `src/main` hard links a file.
 *
 * SELinux gives untrusted apps no `link` on any file (`neverallow all_untrusted_apps
 * file_type:file link;` in AOSP's `app_neverallows.te`), so `Files.createLink` and
 * `Os.link` fail with EACCES on a device while succeeding on the JVM that runs this
 * suite. Generate SSH Key stopped working that way. A source scan is the only
 * check here that can see it.
 */
class NoHardLinkCallSiteTest {

    private val hardLink = Regex("""\b(createLink|Os\.link)\s*\(""")

    @Test
    fun `no production source creates a hard link`() {
        val root = File("src/main")
        check(root.isDirectory) { "${root.absolutePath} not found; this test would pass by scanning nothing" }
        val scanned = root.walkTopDown().filter { it.isFile && it.extension in setOf("kt", "java") }.toList()
        assertTrue(
            scanned.any { it.name == "AndroidBridge.kt" },
            "the walk of ${scanned.size} sources never reached AndroidBridge.kt",
        )

        val offenders = scanned.flatMap { file ->
            file.readLines().withIndex()
                .filterNot { (_, line) -> line.trimStart().let { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") } }
                .filter { (_, line) -> hardLink.containsMatchIn(line) }
                .map { (i, line) -> "${file.path}:${i + 1}: ${line.trim()}" }
        }

        assertTrue(offenders.isEmpty(), "link(2) is denied to apps; use a copy or a rename:\n" + offenders.joinToString("\n"))
    }
}
