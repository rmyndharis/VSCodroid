package com.vscodroid.setup

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File

/**
 * That the app's own extensions count a megabyte the way the app does.
 *
 * `StorageManager.formatSize` decides the unit for every size on a native
 * screen, and a Kotlin test can hold it there. It cannot see these: the storage
 * screen a user actually opens is drawn by the saf-bridge extension, in
 * JavaScript, with its own formatter, and the process monitor prints free space
 * with a second one. Both divided by 1,048,576 and wrote "MB" while the app's
 * own low-storage message asked in decimal MB, which is how one app came to
 * print two meanings of the same word on two adjacent screens.
 *
 * A text check over the source, and the ceiling is worth stating: it cannot run
 * `formatBytes`, so it pins the divisor rather than the output.
 *
 * It reads for the divisor and not for one spelling of it. This used to be a
 * search for the substring "1024", which is how the regression happened to be
 * written the first time and is not how it would be written the second:
 * "1048576" does not contain "1024", and neither does a shift by 20, so the two
 * shortest ways to divide by a MiB were both invisible to the only gate the
 * process monitor's figure has. Every pattern below is a way of dividing by a
 * power of two; an extension that one day needs one for something that is not a
 * size will need this narrowed rather than deleted.
 */
class OwnExtensionSizeUnitTest {

    private val extensionsDir = File("src/main/assets/extensions")

    /**
     * The literals are matched wherever they stand, because a binary divisor is
     * routinely spread across lines no division appears on: `const MIB = 1024 *
     * 1024` and `bytes / MIB` are one divisor and neither half looks like one on
     * its own. The arithmetic forms are anchored on the division or the shift
     * instead, since `2 ** 8` and `flags >> 1` are not sizes.
     */
    private val binaryDivisors = listOf(
        Regex("""\b(?:1024|1048576|1073741824)\b"""),
        Regex("""/\s*\(?\s*(?:1\s*<<|2\s*\*\*|Math\.pow\(\s*2\s*,)\s*\d+"""),
        Regex(""">>>?\s*(?:10|20|30)\b"""),
    )

    @TestFactory
    fun `no bundled extension divides bytes by a power of two`(): List<DynamicTest> {
        val sources = extensionsDir
            .listFiles { f -> f.isDirectory && f.name.startsWith("vscodroid.vscodroid-") }
            ?.sortedBy { it.name }
            ?.map { File(it, "extension.js") }
            ?.filter { it.isFile }
            .orEmpty()

        check(sources.isNotEmpty()) {
            "No bundled extension sources under ${extensionsDir.absolutePath}; the test is " +
                "looking in the wrong place, which would let it pass by finding nothing"
        }

        return sources.map { file ->
            DynamicTest.dynamicTest(file.parentFile.name) {
                val hits = file.readLines().flatMapIndexed { index, line ->
                    binaryDivisors
                        .mapNotNull { it.find(line) }
                        .map { "line ${index + 1}: ${it.value.trim()}" }
                }
                assertTrue(
                    hits.isEmpty(),
                    "${file.parentFile.name}/extension.js divides by a power of two at " +
                        "${hits.joinToString("; ")}. If that is a byte divisor, this screen " +
                        "now prints a smaller MB than the rest of the app: the storage total " +
                        "stops matching Settings > Apps > VSCodroid > Storage, and the " +
                        "free-space figure stops matching what the app asks the user to free. " +
                        "See StorageManager.formatSize for the convention",
                )
            }
        }
    }
}
