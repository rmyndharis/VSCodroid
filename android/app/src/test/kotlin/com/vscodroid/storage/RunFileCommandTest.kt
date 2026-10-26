package com.vscodroid.storage

import com.vscodroid.SourceScan
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * That the Run button appears for exactly the languages it knows how to run.
 *
 * The button is a menu entry in `package.json`, shown by a `when` over `resourceLangId`,
 * and what it runs is a table in `extension.js` keyed by the same ids. The two are written
 * in different files and nothing between them is compiled. A language in the `when` but
 * not in the table shows a button that answers "open a file to run it" on the very file it
 * sits on; one in the table but not in the `when` has code nothing can reach. Both are
 * quiet, which is why the pair is held here.
 *
 * Each toolchain a language needs is also checked against the packs the app ships, since
 * the command asks `toolchains.json` for it by name and a misspelling would answer "not
 * installed" on a device that has it. Those names are read from tracked sources, the
 * registry and the scripts that write each pack's manifest: the pack manifests themselves
 * are downloaded, so they are absent from a clean clone and from the CI unit job.
 *
 * The language is not enough to choose a file the button can run. The built-in C++ language
 * claims every header (`.h`, `.hpp`, `.inl`, ...), and `c++ foo.h` writes a precompiled header
 * that the next `&&` cannot execute, while the shell language claims `.zsh`, `.fish`, rc files
 * and more that bash would run wrongly. So the condition also names the extensions, and the
 * last case here keeps headers and those shells out of it.
 */
class RunFileCommandTest {

    private fun extensionDir(): File {
        val root = File("src/main/assets/extensions")
        val matches = root.listFiles { entry: File ->
            entry.isDirectory && entry.name.startsWith("vscodroid.vscodroid-saf-bridge-")
        }?.toList().orEmpty()
        assertEquals(1, matches.size, "expected one bundled SAF bridge extension; found ${matches.map { it.name }}")
        return matches.single()
    }

    private val source: String get() = File(extensionDir(), "extension.js").readText()

    /** The table's body: everything between `const RUN_COMMANDS = {` and the `};` closing it. */
    private fun table(): String {
        val start = source.indexOf("const RUN_COMMANDS = {")
        check(start >= 0) { "extension.js no longer defines RUN_COMMANDS; point this case at its new name" }
        return source.substring(start, source.indexOf("\n};", start))
    }

    private fun tableLanguages(): Set<String> =
        Regex("""^ {4}(\w+): \{""", RegexOption.MULTILINE).findAll(table()).map { it.groupValues[1] }.toSet()

    private fun menuWhen(menu: String): String {
        val entries = JSONObject(File(extensionDir(), "package.json").readText())
            .getJSONObject("contributes").getJSONObject("menus").getJSONArray(menu)
        val entry = (0 until entries.length()).map { entries.getJSONObject(it) }
            .single { it.getString("command") == "vscodroid.runFile" }
        return entry.getString("when")
    }

    private fun languagesIn(whenClause: String): Set<String> {
        val alternatives = Regex("""resourceLangId =~ /\^\(([\w|]+)\)\$/""").find(whenClause)?.groupValues?.get(1)
        assertNotNull(alternatives, "no resourceLangId alternatives in `$whenClause`")
        return alternatives!!.split('|').toSet()
    }

    @Test
    fun `the button is shown for exactly the languages the table runs`() {
        val table = tableLanguages()
        check(table.isNotEmpty()) { "no language was read from RUN_COMMANDS, so this would compare two empty sets" }

        assertEquals(
            table, languagesIn(menuWhen("editor/title/run")),
            "the title bar button and the RUN_COMMANDS table name different languages",
        )
        assertEquals(
            table, languagesIn(menuWhen("commandPalette")),
            "the palette entry and the RUN_COMMANDS table name different languages",
        )
    }

    @Test
    fun `every toolchain a language needs is one the app ships`() {
        val needed = Regex("""toolchain: '(\w+)'""").findAll(table()).map { it.groupValues[1] }.toSet()
        check(needed.isNotEmpty()) { "no toolchain was read from RUN_COMMANDS" }

        val registry = SourceScan.read("src/main/kotlin/com/vscodroid/setup/ToolchainRegistry.kt")
        for (name in needed) {
            assertTrue(
                registry.contains("packName = \"toolchain_$name\""),
                "RUN_COMMANDS asks toolchains.json for `$name`, but ToolchainRegistry offers no such pack",
            )
            val script = File("../../scripts/download-$name.sh")
            assertTrue(script.isFile, "${script.path} not found; it is what writes the `$name` pack's manifest")
            assertTrue(
                script.readText().contains("\"name\": \"$name\""),
                "the record a `$name` install writes to toolchains.json is not named `$name`",
            )
        }
    }

    @Test
    fun `the button is kept off headers and shells bash would run wrongly`() {
        val extensions = Regex("""resourceExtname =~ /\^\\\.\(([^)]+)\)\$/""")
            .find(menuWhen("editor/title/run"))?.groupValues?.get(1)
        assertNotNull(extensions, "the button's condition no longer names the file extensions")
        val allowed = extensions!!.replace("\\+", "+").split('|').toSet()

        for (source in listOf("js", "ts", "rb", "java", "c", "cpp", "sh", "bash")) {
            assertTrue(source in allowed, "`.$source` should get the button; allowed: $allowed")
        }
        for (other in listOf("h", "hpp", "hh", "hxx", "inl", "ipp", "tpp", "ino", "zsh", "fish", "ksh", "csh")) {
            assertTrue(other !in allowed, "`.$other` must not get the button; allowed: $allowed")
        }
        assertEquals(
            menuWhen("editor/title/run"), menuWhen("commandPalette"),
            "the palette entry and the title bar button must be shown for the same files",
        )
    }

    /**
     * The path of the open file goes into a shell line, and a file in a cloned repository can be
     * called anything: `a;cmd;.js` must reach the shell as one word. Everything the runner builds
     * is given the quoted path and never the raw one.
     */
    @Test
    fun `the path reaches the shell quoted as one word`() {
        assertTrue(
            source.contains("terminal.sendText(runner.command(shellQuote(document.uri.path)));"),
            "the run line must be built from the quoted path, not document.uri.path itself",
        )
        val body = source.substringAfter("function shellQuote").substringBefore("\n}")
        assertTrue(
            Regex("""path\.replace\(/'/g, `'\\\\''`\)""").containsMatchIn(body),
            "shellQuote must close and reopen the single quotes around each one inside the path: $body",
        )
        assertTrue(
            table().lines().filter { it.contains("command:") }.none { it.contains("document.uri") },
            "a table entry reached for the raw document path",
        )
    }
}
