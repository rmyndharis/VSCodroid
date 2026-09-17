package com.vscodroid.setup

import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * That the editor context menu offers Select All, and that offering it leaves Ctrl+A
 * on the editor's own handler.
 *
 * The workbench drops a menu item whose command no extension declares, so a built-in
 * id has to be declared in `contributes.commands` before a menu entry can name it.
 * The declaration is not free. For a manifest with a `main` or `browser` entry point
 * the workbench derives an implicit `onCommand:<id>` activation event from every
 * declared command, and `CommandService.executeCommand` then awaits that event on
 * every extension host before it runs the handler. The first Ctrl+A after each
 * extension host start waits for the hosts to answer, and never runs at all if the web
 * worker host has wedged. A manifest with neither entry point yields no activation
 * events (`_readActivationEvents` in workbench.js returns an empty list first), so no
 * event exists for the id, `activationEventIsDone` answers true and the handler runs at
 * once. That is why the entry lives in an extension that carries no code.
 */
class EditorMenusManifestTest {

    private val extensionsDir = File("src/main/assets/extensions")

    private fun ourDirs(): List<File> =
        extensionsDir.listFiles { f -> f.isDirectory && f.name.startsWith(OWN_DIR_PREFIX) }
            ?.sortedBy { it.name }
            .orEmpty()

    /** Found by prefix, because the directory name carries the version. */
    private fun editorMenus(): File {
        val matches = ourDirs().filter { it.name.startsWith("$OWN_DIR_PREFIX$EDITOR_MENUS-") }
        assertEquals(
            1, matches.size,
            "expected exactly one bundled $EDITOR_MENUS extension under " +
                "${extensionsDir.absolutePath}; found ${matches.map { it.name }}",
        )
        return matches.single()
    }

    private fun manifestOf(dir: File): JSONObject = JSONObject(File(dir, "package.json").readText())

    private fun commandsOf(manifest: JSONObject): List<JSONObject> {
        val commands = manifest.optJSONObject("contributes")?.optJSONArray("commands")
            ?: return emptyList()
        return (0 until commands.length()).map { commands.getJSONObject(it) }
    }

    /**
     * Upstream adds Select All only to the Selection menu and the simple editor's menu,
     * so a long-press in the editor offered Cut, Copy and Paste and no way to select the
     * whole file.
     *
     * Each assertion is a way the entry silently vanishes or misbehaves. Without the
     * declaration the workbench drops the entry. A `when` or an `enablement` would hide
     * or grey it on conditions the editor already checks itself. A literal title would
     * skip translation. Order 4 is Paste, so anything above it lands after Paste in the
     * same group.
     */
    @Test
    fun `the editor context menu offers Select All beside Cut, Copy and Paste`() {
        val manifest = manifestOf(editorMenus())
        val entries = manifest.getJSONObject("contributes")
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

        val command = commandsOf(manifest).singleOrNull { it.getString("command") == SELECT_ALL }
        assertTrue(
            command != null,
            "the workbench drops a menu item whose command is not in contributes.commands",
        )
        assertTrue(
            !command!!.has("enablement"),
            "the editor's own precondition already governs Select All; " +
                "found ${command.opt("enablement")}",
        )
        val title = command.getString("title")
        assertTrue(
            Regex("%[^%]+%").matches(title),
            "the title must be a %key% placeholder so it is translated; was $title",
        )
    }

    @Test
    fun `the extension declaring Select All has no entry point`() {
        val manifest = manifestOf(editorMenus())
        assertEquals(
            emptyList<String>(), listOf("main", "browser").filter { manifest.has(it) },
            "an entry point makes the workbench derive onCommand:$SELECT_ALL from the " +
                "declaration, and the first Select All after each extension host start, " +
                "Ctrl+A included, then waits on the extension hosts before the editor's " +
                "handler runs",
        )
    }

    /**
     * The general form of the rule above, so the next built-in id declared for a menu
     * does not land in an extension that already has code.
     */
    @Test
    fun `no extension of ours with an entry point declares a command outside our namespace`() {
        val withCode = ourDirs().map { it to manifestOf(it) }
            .filter { (_, manifest) -> manifest.has("main") || manifest.has("browser") }
        check(withCode.isNotEmpty()) {
            "no extension of ours with an entry point under ${extensionsDir.absolutePath}, " +
                "so this would pass by checking nothing"
        }

        val foreign = withCode.flatMap { (dir, manifest) ->
            commandsOf(manifest).map { it.getString("command") }
                .filterNot { it.startsWith(OWN_NAMESPACE) }
                .map { "${dir.name}: $it" }
        }
        assertEquals(
            emptyList<String>(), foreign,
            "each of these gains an implicit onCommand activation event, so the first " +
                "invocation of the built-in command after each extension host start waits " +
                "on the extension hosts. Declare it in $EDITOR_MENUS, which has no entry point.",
        )
    }

    @Test
    fun `no extension of ours registers Select All`() {
        val sources = ourDirs().map { it.name to File(it, "extension.js") }.filter { it.second.isFile }
        check(sources.any { REGISTER_COMMAND.containsMatchIn(it.second.readText()) }) {
            "no registerCommand call was read from any extension of ours, so this would " +
                "pass by reading nothing"
        }

        val registering = sources
            .filter { REGISTER_SELECT_ALL.containsMatchIn(it.second.readText()) }
            .map { it.first }
        assertEquals(
            emptyList<String>(), registering,
            "registering $SELECT_ALL replaces the editor's own handler with one that runs " +
                "in the extension host, for Ctrl+A as much as for the menu entry",
        )
    }

    private companion object {
        const val OWN_DIR_PREFIX = "vscodroid.vscodroid-"
        const val OWN_NAMESPACE = "vscodroid."
        const val EDITOR_MENUS = "editor-menus"
        const val SELECT_ALL = "editor.action.selectAll"
        val REGISTER_COMMAND = Regex("""registerCommand\(\s*['"`]""")
        val REGISTER_SELECT_ALL =
            Regex("""registerCommand\(\s*['"`]${Regex.escape(SELECT_ALL)}['"`]""")
    }
}
