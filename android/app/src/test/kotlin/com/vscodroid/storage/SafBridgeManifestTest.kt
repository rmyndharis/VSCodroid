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
        return Regex("""registerCommand\(\s*['"`]([\w.]+)['"`]""")
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

    /**
     * Symmetric for our own namespace only. A built-in id is declared purely to give a
     * menu entry a command the workbench will accept, and its handler is the editor's
     * own; registering it here as well would put every invocation of it, Ctrl+A
     * included, through the extension host.
     */
    @Test
    fun `the manifest declares exactly the commands the extension registers`() {
        val registered = registered()
        check(registered.isNotEmpty()) {
            "no registerCommand call was read from extension.js, so this would pass by " +
                "comparing two empty sets"
        }
        val (ours, builtIn) = declared().partition { it.startsWith(OWN_NAMESPACE) }
        assertEquals(
            emptySet<String>(), registered - declared(),
            "these commands are registered and not declared, so nothing can invoke them",
        )
        assertEquals(
            emptySet<String>(), ours.toSet() - registered,
            "these commands are declared and never registered, so the palette offers " +
                "them and each one fails with \"command not found\"",
        )
        assertEquals(
            emptySet<String>(), builtIn.toSet().intersect(registered),
            "these built-in commands are registered by the extension, which replaces the " +
                "editor's own handler with one that waits on the extension host",
        )
    }

    /**
     * The editor's context menu has Cut, Copy and Paste and no Select All: upstream adds
     * that only to the Selection menu and the simple editor's menu, so a long-press
     * offered no way to select the whole file.
     *
     * Each assertion is a way the entry silently vanishes or misbehaves. The workbench
     * drops a menu item whose command no extension declares, so the declaration is
     * required. A `when` or an `enablement` would hide or grey it on conditions the
     * editor already checks itself. A literal title would skip translation. Order 4 is
     * Paste, so anything above it lands after Paste in the same group.
     */
    @Test
    fun `the editor context menu offers Select All beside Cut, Copy and Paste`() {
        val entries = manifest().getJSONObject("contributes")
            .getJSONObject("menus")
            .getJSONArray("editor/context")
        val entry = (0 until entries.length()).map { entries.getJSONObject(it) }
            .singleOrNull { it.getString("command") == SELECT_ALL }
        assertTrue(entry != null, "no editor/context entry for $SELECT_ALL")

        val group = entry!!.getString("group")
        val (name, order) = group.split('@').let { it[0] to it.getOrNull(1)?.toIntOrNull() }
        assertEquals("9_cutcopypaste", name, "Select All belongs with Cut, Copy and Paste")
        assertTrue(
            order != null && order > 4,
            "the order must place Select All after Paste (order 4); group was $group",
        )
        assertTrue(!entry.has("when"), "a when clause would hide Select All: ${entry.opt("when")}")

        assertTrue(
            SELECT_ALL in declared(),
            "the workbench drops a menu item whose command is not in contributes.commands",
        )
        val commands = manifest().getJSONObject("contributes").getJSONArray("commands")
        val command = (0 until commands.length()).map { commands.getJSONObject(it) }
            .single { it.getString("command") == SELECT_ALL }
        assertTrue(
            !command.has("enablement"),
            "the editor's own precondition already governs Select All; " +
                "found ${command.opt("enablement")}",
        )
        val title = command.getString("title")
        assertTrue(
            Regex("%[^%]+%").matches(title),
            "the title must be a %key% placeholder so it is translated; was $title",
        )
        assertTrue(
            SELECT_ALL !in registered(),
            "registering $SELECT_ALL in extension.js would route Ctrl+A through the extension host",
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

    @Test
    fun `the remote indicator menu keeps a way back to a hidden key row`() {
        val entries = manifest().getJSONObject("contributes")
            .getJSONObject("menus")
            .getJSONArray("statusBar/remoteIndicator")
        val commands = (0 until entries.length())
            .map { entries.getJSONObject(it).getString("command") }
        assertTrue(
            "vscodroid.toggleExtraKeyRow" in commands,
            "with the row hidden and no hardware keyboard, Ctrl+Shift+P cannot be typed, " +
                "so a tap on the remote indicator is the discoverable way to bring the row " +
                "back; found $commands",
        )
    }

    private companion object {
        const val OWN_NAMESPACE = "vscodroid."
        const val SELECT_ALL = "editor.action.selectAll"
    }
}
