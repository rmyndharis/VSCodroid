package com.vscodroid.storage

import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * That the bridge extension's manifest and its code agree about which commands exist.
 *
 * The two are written in different files and nothing between them is checked by a
 * compiler, a lint pass or the extension host. A command declared in `package.json` and
 * never registered appears in the palette and fails with "command not found"; one
 * registered and never declared is reachable from nowhere at all, which is not a
 * failure anybody sees, only a feature that quietly does not exist. Both have shipped
 * in editors that look exactly like a working one.
 *
 * The menu entry is here for a stronger reason than symmetry. `vscodroid.about` opens
 * the only screen in the app carrying the written offer of source and the licence texts
 * the bundled libraries require, and the palette is not a route a user finds by
 * looking. The remote indicator is: it is the one control always on screen. An edit
 * that drops the entry leaves the notices shipped and unreachable.
 */
class SafBridgeManifestTest {

    /**
     * The bundled extension, found by prefix rather than by version, because the
     * directory name carries the version and moves with every release of it.
     */
    private fun extensionDir(): File {
        val root = File("src/main/assets/extensions")
        val matches = root.listFiles { entry: File ->
            entry.isDirectory && entry.name.startsWith("vscodroid.vscodroid-saf-bridge-")
        }?.toList().orEmpty()
        assertEquals(
            1, matches.size,
            "expected exactly one bundled SAF bridge extension under ${root.absolutePath}; " +
                "found ${matches.map { it.name }}",
        )
        return matches.single()
    }

    private fun manifest(): JSONObject =
        JSONObject(File(extensionDir(), "package.json").readText())

    /** Every command id the extension actually registers at activation. */
    private fun registered(): Set<String> {
        val source = File(extensionDir(), "extension.js")
        assertTrue(source.isFile) { "no extension.js in ${extensionDir().name}" }
        return Regex("""registerCommand\(\s*'([\w.]+)'""")
            .findAll(source.readText())
            .map { it.groupValues[1] }
            .toSet()
    }

    private fun declared(): Set<String> {
        val commands = manifest().getJSONObject("contributes").getJSONArray("commands")
        return (0 until commands.length())
            .map { commands.getJSONObject(it).getString("command") }
            .toSet()
    }

    @Test
    fun `the manifest declares exactly the commands the extension registers`() {
        val registered = registered()
        check(registered.isNotEmpty()) {
            "no registerCommand call was read from extension.js, so this would pass by " +
                "comparing two empty sets"
        }
        assertEquals(
            emptySet<String>(), registered - declared(),
            "these commands are registered and not declared, so nothing can invoke them",
        )
        assertEquals(
            emptySet<String>(), declared() - registered,
            "these commands are declared and never registered, so the palette offers " +
                "them and each one fails with \"command not found\"",
        )
    }

    @Test
    fun `the remote indicator menu keeps a way into the licence notices`() {
        val entries = manifest().getJSONObject("contributes")
            .getJSONObject("menus")
            .getJSONArray("statusBar/remoteIndicator")
        val commands = (0 until entries.length())
            .map { entries.getJSONObject(it).getString("command") }
        assertTrue(
            "vscodroid.about" in commands,
            "the About screen carries the written offer of source and the licence " +
                "texts, and the remote indicator is the only route to it that a user " +
                "does not have to already know about; found $commands",
        )
    }
}
