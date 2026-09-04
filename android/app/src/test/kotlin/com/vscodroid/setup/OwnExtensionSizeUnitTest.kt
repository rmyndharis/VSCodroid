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
 * `formatBytes`, so it pins the divisor rather than the output. It holds today
 * because every occurrence of 1024 across all four of our extensions was a size
 * divisor and none remains. An extension that one day needs 1024 for something
 * that is not a size will need this reworded rather than deleted.
 */
class OwnExtensionSizeUnitTest {

    private val extensionsDir = File("src/main/assets/extensions")

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
                assertTrue(
                    !file.readText().contains("1024"),
                    "${file.parentFile.name}/extension.js mentions 1024. If that is a byte " +
                        "divisor, this screen now prints a smaller MB than the rest of the " +
                        "app: the storage total stops matching Settings > Apps > VSCodroid > " +
                        "Storage, and the free-space figure stops matching what the app asks " +
                        "the user to free. See StorageManager.formatSize for the convention",
                )
            }
        }
    }
}
