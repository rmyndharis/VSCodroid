package com.vscodroid

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * That the two screens which reclaim disk are allowed to finish.
 *
 * Every command the bundled bridge extension sends is answered synchronously by
 * the relay in the page, and four of the bridge methods behind them walk
 * directory trees before they can answer: `getStorageBreakdown` sizes each
 * component of the app's storage and then the whole of `filesDir` again for the
 * total, `listSafMirrors` walks every copied device folder twice (once to size
 * it, once to ask whether the device folder holds everything in it),
 * `reclaimSafMirror` re-asks that second question and sizes the copy, and
 * `clearCaches` deletes trees. Both `SafStorageManager.listMirrors` and
 * `SafStorageManager.reclaimMirror` carry a warning in their own documentation
 * that they walk the mirror.
 *
 * All four were sent under one five-second deadline written for a relay hop. The
 * extracted server tree alone is around 875 MB before a project is opened, so on
 * a real install the walk outruns the deadline, the promise rejects, and the user
 * is told "Bridge timeout: is the app running on Android?" by the only two
 * screens in the app that can free space. The answer they were waiting for
 * arrives afterwards and is dropped, because the pending entry is already gone.
 *
 * The deadline is checked rather than the walk, because the walk is the user's
 * disk and nothing here can bound it. What can be pinned is that these four are
 * not given the deadline meant for a command that only has to be relayed.
 *
 * Read out of the shipped script, as `DeviceFolderPayloadTest` reads it: the
 * extension runs in the web extension host inside a WebView, so there is no
 * harness in this suite that can execute it.
 */
class BridgeCommandDeadlineTest {

    /**
     * The bundled extension, found by prefix rather than by version, for the
     * reason `DeviceFolderPayloadTest` gives: the directory name carries the
     * extension's version and moves with every release of it.
     */
    private fun script(): String {
        val root = File("src/main/assets/extensions")
        val matches = root.listFiles { entry: File ->
            entry.isDirectory && entry.name.startsWith("vscodroid.vscodroid-saf-bridge-")
        }?.toList().orEmpty()

        assertEquals(
            1, matches.size,
            "expected exactly one bundled SAF bridge extension under ${root.absolutePath}; " +
                "found ${matches.map { it.name }}",
        )
        val file = File(matches.single(), "extension.js")
        assertTrue(file.isFile) { "no extension.js in ${matches.single().name}" }
        return file.readText()
    }

    /**
     * The text of every `sendBridgeCommand` call for [command], from the command
     * name to the closing parenthesis of the call.
     *
     * Balanced rather than read to the next `)`, because two of these calls pass
     * an object literal whose own parentheses and braces sit between the command
     * name and the deadline.
     */
    private fun callsTo(source: String, command: String): List<String> {
        val opener = "sendBridgeCommand('$command'"
        val found = mutableListOf<String>()
        var from = source.indexOf(opener)
        while (from >= 0) {
            var depth = 0
            var i = source.indexOf('(', from)
            while (i < source.length) {
                if (source[i] == '(') depth += 1
                if (source[i] == ')') {
                    depth -= 1
                    if (depth == 0) {
                        found.add(source.substring(from, i + 1))
                        break
                    }
                }
                i += 1
            }
            from = source.indexOf(opener, from + opener.length)
        }
        return found
    }

    /** The commands whose cost is the size of what the user has on disk. */
    private val diskWalking = listOf(
        "getStorageBreakdown",
        "listSafMirrors",
        "reclaimSafMirror",
        "clearCaches",
    )

    /**
     * Commands the relay answers out of a field or by handing an intent to an
     * Activity. They are the negative control: if they carried the long deadline
     * too, this file would be pinning "everything waits two minutes" rather than
     * "the disk walks are not held to a relay hop", and a bridge that had stopped
     * answering at all would keep the user waiting for it.
     */
    private val instant = listOf("openFolderPicker", "getRecentFolders", "showAboutDialog")

    @Test
    fun `a command that walks the disk is not held to the relay deadline`() {
        val source = script()

        assertTrue(source.contains("const DISK_WALK_TIMEOUT_MS")) {
            "the bundled extension has no separate deadline for the commands that walk " +
                "the disk, so all of them are held to the relay hop's five seconds and the " +
                "storage screens report a timeout on any install with files on it."
        }

        val missing = diskWalking.filter { command ->
            val calls = callsTo(source, command)
            assertTrue(calls.isNotEmpty()) {
                "no sendBridgeCommand('$command') call left in the bundled extension, so " +
                    "this test is measuring nothing for it. If the command was renamed, " +
                    "rename it here."
            }
            calls.any { !it.contains("DISK_WALK_TIMEOUT_MS") }
        }

        assertEquals(
            emptyList<String>(), missing,
            "these commands walk the user's disk before they can answer and are still sent " +
                "under the deadline meant for a relay hop. The promise rejects while the " +
                "walk is still running, the user is told the app may not be running on " +
                "Android, and the answer is discarded when it arrives.",
        )
    }

    @Test
    fun `a command the relay answers at once keeps the short deadline`() {
        val source = script()

        val overlong = instant.filter { command ->
            val calls = callsTo(source, command)
            assertTrue(calls.isNotEmpty()) {
                "no sendBridgeCommand('$command') call left in the bundled extension, so " +
                    "this control is measuring nothing"
            }
            calls.any { it.contains("DISK_WALK_TIMEOUT_MS") }
        }

        assertEquals(
            emptyList<String>(), overlong,
            "a command the relay answers out of a field is waiting on the disk-walk " +
                "deadline. A bridge that is not there at all would then take two minutes " +
                "to say so instead of five seconds, which is what the short deadline is for.",
        )
    }
}
