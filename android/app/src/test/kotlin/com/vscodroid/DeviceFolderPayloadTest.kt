package com.vscodroid

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * That the storage screen reads the keys the app actually sends it.
 *
 * `deviceFolderCopiesAsJson` writes JSON and the `vscodroid.manageDeviceFolders`
 * command in the bundled bridge extension reads it, and between the two there is
 * nothing a compiler or a lint pass can check. Measured before this file
 * existed: renaming a key in the producer left the whole unit suite green.
 *
 * What that costs is not cosmetic. The quick pick's `detail` and its
 * confirmation both branch on `reclaimable`, and the removal it sends carries
 * `force: !m.reclaimable`. A key the consumer cannot find is `undefined`, so
 * every copy would be described as holding files the device folder does not,
 * and every confirmed removal would go out with `force` set, which is precisely
 * the flag that skips the at-removal-time re-check `SafStorageManager` performs
 * because a write-back can strand a file while the confirmation is on screen.
 *
 * The producer is allowed to send more than is read: `lastOpened` is sent and
 * nothing uses it today. The direction that costs something is a key the
 * consumer reads and the producer does not send.
 *
 * `scripts/check-bridge-api-spec.py` does not cover this and says so in its own
 * header: it compares method names, parameters and return types, and what the
 * JSON keys are is left to a person reading. `BridgeApiSpecParityTest` states
 * the same limit.
 */
class DeviceFolderPayloadTest {

    private val producerSource = File("src/main/kotlin/com/vscodroid/MainActivity.kt")

    /** Every key `deviceFolderCopiesAsJson` puts, read off the one loop that builds them. */
    private fun sent(): Set<String> {
        check(producerSource.isFile) {
            "MainActivity.kt not found at ${producerSource.absolutePath}; this test " +
                "would otherwise pass by looking at nothing"
        }
        return Regex("""put\("(\w+)", mirror\.\w+\)""")
            .findAll(producerSource.readText())
            .map { it.groupValues[1] }
            .toSet()
    }

    /**
     * The bundled extension, found by prefix rather than by version.
     *
     * The directory name carries the extension's version and moves with every
     * release of it, so naming one here would leave this test silently reading
     * nothing the next time it is bumped.
     */
    private fun consumerSource(): File {
        val root = File("src/main/assets/extensions")
        val matches = root.listFiles { entry: File ->
            entry.isDirectory && entry.name.startsWith("vscodroid.vscodroid-saf-bridge-")
        }?.toList().orEmpty()

        assertEquals(
            1, matches.size,
            "expected exactly one bundled SAF bridge extension under ${root.absolutePath}; " +
                "found ${matches.map { it.name }}",
        )
        val script = File(matches.single(), "extension.js")
        assertTrue(script.isFile) { "no extension.js in ${matches.single().name}" }
        return script
    }

    /**
     * Every key the quick pick reads off one listed copy.
     *
     * `m` is the loop and pick variable of `manageDeviceFolders` and is used
     * nowhere else in the file, so the whole script can be scanned without
     * slicing a region out of it, which is the part that would rot.
     */
    private fun read(): Set<String> =
        Regex("""\bm\.([A-Za-z_][A-Za-z0-9_]*)""")
            .findAll(consumerSource().readText())
            .map { it.groupValues[1] }
            .toSet()

    @Test
    fun `the screen finds every field it reads`() {
        val sent = sent()
        val read = read()

        // Controls, both directions. A regex that matched nothing would make the
        // subset check below pass by comparing two empty sets, and this file
        // would go on reporting green over a payload nobody was reading.
        assertTrue(sent.size >= 5) {
            "only found ${sent.size} key(s) in deviceFolderCopiesAsJson ($sent); the " +
                "producer scan is not reading the payload any more"
        }
        assertTrue(read.size >= 5) {
            "only found ${read.size} field read(s) in the bridge extension ($read); the " +
                "consumer scan is not reading manageDeviceFolders any more"
        }

        assertEquals(
            emptySet<String>(), read - sent,
            "the storage screen reads fields the app does not send. Each one arrives as " +
                "undefined: `reclaimable` missing describes every copy as holding files " +
                "the device folder does not and sends every removal with force set, " +
                "which skips the check that keeps a stranded file from being deleted. " +
                "Producer: ${producerSource.path}; consumer: ${consumerSource().path}.",
        )
    }
}
