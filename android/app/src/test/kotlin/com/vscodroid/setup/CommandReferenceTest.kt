package com.vscodroid.setup

import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
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
 * half that rots on its own: someone renames a palette entry, and a message pointing at the
 * old name keeps compiling and keeps being wrong.
 *
 * What it deliberately does NOT catch, because no test can decide it: prose that points
 * somewhere that is not a command at all. "Clear caches in Settings" names no command, so
 * this test would have stayed green through the whole of #153. That case needs a reader.
 */
class CommandReferenceTest {

    private val extensionsDir = File("src/main/assets/extensions")
    private val kotlinDir = File("src/main/kotlin")
    private val stringsFile = File("src/main/res/values")

    /**
     * Every command title contributed by a bundled extension, e.g. "VSCodroid: About".
     *
     * Parsed rather than pattern-matched, because a title is now written into the
     * manifest as `%command.about%` and the English text lives in
     * `package.nls.json` beside it. A regex over the raw manifest returns an empty
     * set against that, and an empty set makes every reference in the app look
     * dangling.
     *
     * The ENGLISH base only, deliberately. What this test compares against are
     * references in `res/values/strings.xml` and in English JavaScript literals,
     * and those name the English title on purpose: the Command Palette keeps a
     * translated command findable by its English name, so telling a user to type
     * it stays correct in every language. Resolving through a translation would
     * compare an English reference with a French title and report a defect that
     * is not one.
     */
    private fun contributedTitles(): Set<String> {
        val dirs = extensionsDir.listFiles { f -> f.isDirectory }?.toList() ?: emptyList()
        check(dirs.isNotEmpty()) {
            "No bundled extensions under ${extensionsDir.absolutePath}; this test would " +
                "otherwise pass by finding nothing to check against"
        }
        val titles = dirs.flatMap { dir ->
            val manifest = File(dir, "package.json").takeIf { it.isFile }
                ?: return@flatMap emptyList<String>()
            val base = File(dir, BASE_BUNDLE).takeIf { it.isFile }?.let { JSONObject(it.readText()) }
            val commands = JSONObject(manifest.readText())
                .optJSONObject("contributes")
                ?.optJSONArray("commands")
                ?: return@flatMap emptyList<String>()
            (0 until commands.length()).mapNotNull { i ->
                val raw = commands.optJSONObject(i)?.optString("title").orEmpty()
                val key = PLACEHOLDER.matchEntire(raw)?.groupValues?.get(1)
                    ?: return@mapNotNull raw
                base?.optString(key)?.takeIf { it.isNotEmpty() }
            }
        }.filter { it.startsWith(PREFIX) }.toSet()

        // The control the parse made necessary. A placeholder this cannot resolve
        // yields nothing, and an empty set turns every reference below into a
        // dangling one, which reads as a swarm of unrelated failures rather than
        // as "the titles could not be read".
        check(titles.isNotEmpty()) {
            "No command title starting with '$PREFIX' could be read from any bundled " +
                "manifest. The titles are %key% placeholders resolved out of $BASE_BUNDLE; " +
                "if that file is missing or its keys were renamed, nothing here can be checked."
        }
        return titles
    }

    /**
     * Files whose strings reach a user: string resources, our Kotlin, and our
     * bundled extensions' code.
     *
     * strings.xml is where the Kotlin ones went. A message that names a palette
     * entry is user-facing text by definition, so it belongs in a resource a
     * translator can reach, and following it there is not optional for this
     * check: leaving the scan on Kotlin alone would have kept it green while
     * seeing nothing, which is the failure it exists to prevent. Kotlin stays in
     * the list because nothing stops a new literal being written there, and this
     * is one of the two things that would notice.
     */
    private fun userFacingSources(): List<File> =
        (stringsFile.walkTopDown().filter { it.isFile && it.extension == "xml" } +
            kotlinDir.walkTopDown().filter { it.isFile && it.extension == "kt" } +
            extensionsDir.walkTopDown().filter { it.isFile && it.name == "extension.js" })
            .toList()

    @Test
    fun `every command named in user-facing text is contributed by a bundled extension`() {
        val titles = contributedTitles()
        val sources = userFacingSources()
        check(sources.isNotEmpty()) { "No sources found; the test is looking in the wrong place" }

        val dangling = sortedMapOf<String, MutableSet<String>>()
        val foundIn = sortedMapOf<String, MutableSet<String>>()
        for (file in sources) {
            for (m in REFERENCE.findAll(file.readText())) {
                val named = "VSCodroid: " + m.groupValues[1].trim()
                foundIn.getOrPut(file.extension) { sortedSetOf() }.add(named)
                if (named !in titles) {
                    dangling.getOrPut(named) { sortedSetOf() }.add(file.name)
                }
            }
        }

        // Positive control, and the one this test was missing. Everything above
        // reports success by finding nothing, so a REFERENCE that stops matching
        // -- a quoting style that changes, a character class that stops covering a
        // real title -- turns the whole check into a scan of zero references that
        // passes. The existing checks cover "are there sources" and "are there
        // extensions"; neither covers "did the pattern match anything in them".
        //
        // Asserted per source kind rather than in total, because the two quote the
        // name differently and only one of them is fragile: a string resource
        // escapes it as \"VSCodroid: ...\" while JavaScript writes it plainly. A
        // pattern that quietly stopped handling the escaped form would still match
        // the JS references and keep a total-count assertion green.
        //
        // The escaped form is checked on xml rather than kt, which is where it
        // used to be. Every message this app shows moved into strings.xml so that
        // a translation could reach it, and Kotlin now carries no reference at
        // all: an assertion still keyed on "kt" would fail on a correct tree, and
        // deleting it rather than moving it would leave the escaped form
        // unguarded. Kotlin is still scanned for dangling names above.
        assertTrue(
            foundIn["xml"]?.isNotEmpty() == true,
            "no command reference matched in any string resource, so the escaped form " +
                "\\\"VSCodroid: ...\\\" is no longer being recognised and this test is " +
                "checking nothing there. Found: $foundIn",
        )
        assertTrue(
            foundIn["js"]?.isNotEmpty() == true,
            "no command reference matched in any bundled extension, so this test is " +
                "checking nothing there. Found: $foundIn",
        )

        assertEquals(
            emptyMap<String, Set<String>>(), dangling.toMap(),
            "these texts name a Command Palette entry that no bundled extension contributes, " +
                "so a user who follows them finds nothing. Contributed today: " +
                titles.sorted().joinToString(", ")
        )
    }

    private companion object {
        /** The prefix that makes a title one of ours, and the palette's grouping. */
        const val PREFIX = "VSCodroid: "

        /** `%command.about%` as a whole title value, which is what the editor substitutes. */
        val PLACEHOLDER = Regex("""%([^%]+)%""")

        /** The English base, and the per-key fallback for every translation. */
        const val BASE_BUNDLE = "package.nls.json"

        /**
         * A reference in source text: the name must be QUOTED, which is how it appears
         * when it is being given to a user to type: `\"VSCodroid: Clear Caches\"` in a
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
