package com.vscodroid.setup

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Text shown to a user may name a Command Palette entry, and every entry it names has to
 * exist. Issue #153 was the version of this that nothing caught: a storage warning told the
 * user to "Clear caches in Settings" while the app had no Settings screen and the two
 * storage commands had no sender at all.
 *
 * What this pins is the narrower, mechanical half of that: a reference of the form
 * `VSCodroid: Something` must match a `title` contributed by a bundled extension. It is the
 * half that rots on its own — someone renames a palette entry, and a message pointing at the
 * old name keeps compiling and keeps being wrong.
 *
 * What it deliberately does NOT catch, because no test can decide it: prose that points
 * somewhere that is not a command at all. "Clear caches in Settings" names no command, so
 * this test would have stayed green through the whole of #153. That case needs a reader.
 */
class CommandReferenceTest {

    private val extensionsDir = File("src/main/assets/extensions")
    private val kotlinDir = File("src/main/kotlin")

    /** Every command title contributed by a bundled extension, e.g. "VSCodroid: About". */
    private fun contributedTitles(): Set<String> {
        val dirs = extensionsDir.listFiles { f -> f.isDirectory }?.toList() ?: emptyList()
        check(dirs.isNotEmpty()) {
            "No bundled extensions under ${extensionsDir.absolutePath} — this test would " +
                "otherwise pass by finding nothing to check against"
        }
        return dirs.flatMap { dir ->
            val manifest = File(dir, "package.json").takeIf { it.isFile }?.readText().orEmpty()
            TITLE.findAll(manifest).map { it.groupValues[1] }.toList()
        }.toSet()
    }

    /** Files whose strings reach a user: our Kotlin, and our bundled extensions' code. */
    private fun userFacingSources(): List<File> =
        (kotlinDir.walkTopDown().filter { it.isFile && it.extension == "kt" } +
            extensionsDir.walkTopDown().filter { it.isFile && it.name == "extension.js" })
            .toList()

    @Test
    fun `every command named in user-facing text is contributed by a bundled extension`() {
        val titles = contributedTitles()
        val sources = userFacingSources()
        check(sources.isNotEmpty()) { "No sources found — the test is looking in the wrong place" }

        val dangling = sortedMapOf<String, MutableSet<String>>()
        for (file in sources) {
            for (m in REFERENCE.findAll(file.readText())) {
                val named = "VSCodroid: " + m.groupValues[1].trim()
                if (named !in titles) {
                    dangling.getOrPut(named) { sortedSetOf() }.add(file.name)
                }
            }
        }

        assertEquals(
            emptyMap<String, Set<String>>(), dangling.toMap(),
            "these texts name a Command Palette entry that no bundled extension contributes, " +
                "so a user who follows them finds nothing. Contributed today: " +
                titles.sorted().joinToString(", ")
        )
    }

    private companion object {
        /** `"title": "VSCodroid: About"` in an extension manifest. */
        val TITLE = Regex(""""title"\s*:\s*"(VSCodroid: [^"]+)"""")

        /**
         * A reference in source text: the name must be QUOTED, which is how it appears
         * when it is being given to a user to type — `\"VSCodroid: Clear Caches\"` in a
         * Kotlin literal, `"VSCodroid: Copy SSH Public Key"` inside a JavaScript one.
         *
         * The quotes are what make this specific rather than a search for the word. An
         * earlier version matched the bare prefix and flagged two CSS comments inside
         * injected stylesheets -- banner lines beginning "VSCodroid: Safe area padding"
         * and "VSCodroid: Enlarged touch targets" -- which name no command and never
         * reach a user.
         */
        val REFERENCE = Regex("""\\?"VSCodroid: ([A-Z][^"\\\n]*)\\?"""")
    }
}
