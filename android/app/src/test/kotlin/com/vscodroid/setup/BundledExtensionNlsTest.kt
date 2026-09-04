package com.vscodroid.setup

import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * That our own extensions' translated manifests hold together.
 *
 * The editor resolves a manifest string of the form `%key%` out of
 * `package.nls.<language>.json` beside the manifest, falling back per key to
 * `package.nls.json`. Every failure below is silent on a phone: the server logs
 * a warning nobody reads, and the screen shows either the English string or the
 * placeholder itself.
 *
 * Four rules:
 *
 *  * every placeholder in a manifest has an entry in that extension's English
 *    base, which is what a rename on one side alone breaks;
 *  * every translated bundle carries exactly the base's keys, since a key added
 *    to the base and forgotten in a translation degrades to English with nothing
 *    said, and a key a translation has and the base does not is the fingerprint
 *    of a rename that only touched some files;
 *  * no bundle VALUE is itself a placeholder, which would substitute a key for
 *    itself and leave `%key%` on the screen;
 *  * no bundle carries one of the three dash characters this project does not
 *    use.
 *
 * The last rule is the one nothing else can see. `check-plain-punctuation.py`
 * compares raw codepoints over tracked files, and an em dash written as a JSON
 * `\uXXXX` escape is six ASCII bytes there and one character after `JSON.parse`,
 * exactly the way an AAPT2 escape once put one on the first-run picker while
 * that gate reported a clean tree. Both forms are refused here. The codepoints
 * are named by number for the same reason: writing them out would put them in
 * this file.
 */
class BundledExtensionNlsTest {

    private val extensionsDir = File("src/main/assets/extensions")

    private fun ourDirs(): List<File> =
        extensionsDir.listFiles { f -> f.isDirectory && f.name.startsWith("vscodroid.vscodroid-") }
            ?.sortedBy { it.name }
            .orEmpty()

    /** Every string value anywhere in a manifest, which is where the walker substitutes. */
    private fun stringsIn(node: Any?): Sequence<String> = when (node) {
        is String -> sequenceOf(node)
        is JSONObject -> node.keys().asSequence().flatMap { stringsIn(node.get(it)) }
        is JSONArray -> (0 until node.length()).asSequence().flatMap { stringsIn(node.get(it)) }
        else -> emptySequence()
    }

    private fun placeholdersIn(dir: File): Set<String> =
        stringsIn(JSONObject(File(dir, "package.json").readText()))
            .mapNotNull { PLACEHOLDER.matchEntire(it)?.groupValues?.get(1) }
            .toSet()

    private fun bundlesIn(dir: File): Map<String, JSONObject> =
        dir.listFiles { f -> f.isFile && BUNDLE.matches(f.name) }
            ?.sortedBy { it.name }
            ?.associate { it.name to JSONObject(it.readText()) }
            .orEmpty()

    private fun keysOf(bundle: JSONObject): Set<String> = bundle.keys().asSequence().toSet()

    @Test
    fun `every placeholder in a manifest resolves in its English bundle`() {
        val dirs = ourDirs()
        assertEquals(
            EXPECTED_EXTENSIONS, dirs.size,
            "expected $EXPECTED_EXTENSIONS bundled extensions of ours under " +
                "${extensionsDir.absolutePath}, found ${dirs.map { it.name }}. Paths here " +
                "resolve from the Gradle test working directory, which is android/app.",
        )

        var placeholdersRead = 0
        val missing = dirs.flatMap { dir ->
            val keys = placeholdersIn(dir)
            placeholdersRead += keys.size
            val base = File(dir, "package.nls.json")
            if (!base.isFile) {
                return@flatMap keys.map { "${dir.name}: no package.nls.json for %$it%" }
            }
            val known = keysOf(JSONObject(base.readText()))
            keys.filterNot { it in known }.map { "${dir.name}: %$it% is in no bundle" }
        }

        // The positive control. Everything above reports success by finding no
        // disagreement, so a manifest that stopped using placeholders, or a walk
        // that stopped reaching them, would pass by comparing nothing.
        assertTrue(
            placeholdersRead > 0,
            "no %key% placeholder was read from any of our manifests, so this test compared " +
                "nothing. Either the manifests hold their strings literally again, or " +
                "stringsIn() no longer reaches them.",
        )

        assertEquals(
            emptyList<String>(), missing,
            "these manifest placeholders have no entry in their extension's English bundle. " +
                "The editor answers one with a log warning on the server and puts the literal " +
                "%key% in the Command Palette and the Extensions view.",
        )
    }

    @Test
    fun `every locale bundle carries exactly the base key set`() {
        var compared = 0
        val drift = ourDirs().flatMap { dir ->
            val bundles = bundlesIn(dir)
            val base = bundles[BASE] ?: return@flatMap listOf("${dir.name}: no $BASE")
            val expected = keysOf(base)
            bundles.filterKeys { it != BASE }.flatMap { (name, bundle) ->
                compared++
                val found = keysOf(bundle)
                listOf(
                    (expected - found).map { "${dir.name}/$name: missing $it" },
                    (found - expected).map { "${dir.name}/$name: unknown $it" },
                ).flatten()
            }
        }

        assertTrue(
            compared > 0,
            "no translated bundle was compared against a base, so this test checked nothing",
        )
        assertEquals(
            emptyList<String>(), drift,
            "a translated bundle and its English base disagree about which keys exist. A key " +
                "the translation lacks falls back to English with nothing said; a key it has " +
                "and the base does not is a rename that only touched some files.",
        )
    }

    @Test
    fun `no bundle value is itself a placeholder`() {
        var values = 0
        val circular = ourDirs().flatMap { dir ->
            bundlesIn(dir).flatMap { (name, bundle) ->
                keysOf(bundle).mapNotNull { key ->
                    values++
                    if (PLACEHOLDER.matches(bundle.getString(key))) "${dir.name}/$name: $key" else null
                }
            }
        }

        assertTrue(values > 0, "no bundle value was read, so this test checked nothing")
        assertEquals(
            emptyList<String>(), circular,
            "these bundle entries hold a placeholder rather than text. The walker would " +
                "substitute the key for itself and the manifest would still read %key%.",
        )
    }

    @Test
    fun `no bundle carries a dash this project does not use`() {
        var filesRead = 0
        val offences = ourDirs().flatMap { dir ->
            dir.listFiles { f -> f.isFile && BUNDLE.matches(f.name) }.orEmpty().sortedBy { it.name }
                .flatMap { file ->
                    filesRead++
                    val text = file.readText()
                    BANNED.flatMap { codepoint ->
                        val escaped = "\\u%04x".format(codepoint)
                        listOfNotNull(
                            "${dir.name}/${file.name}: U+%04X".format(codepoint)
                                .takeIf { text.contains(codepoint.toChar()) },
                            "${dir.name}/${file.name}: U+%04X written as $escaped".format(codepoint)
                                .takeIf { text.contains(escaped, ignoreCase = true) },
                        )
                    }
                }
        }

        assertTrue(filesRead > 0, "no bundle file was read, so this test checked nothing")
        assertEquals(
            emptyList<String>(), offences,
            "use a comma, a colon, a period or parentheses. The escaped form is here because " +
                "the repository's punctuation gate compares raw codepoints over tracked files " +
                "and cannot see one: JSON expands it at parse time, and the user reads the " +
                "character.",
        )
    }

    private companion object {
        /** The whole value, never a fragment: that is what the editor's walker substitutes. */
        val PLACEHOLDER = Regex("""%([^%]+)%""")

        /** `package.nls.json` and `package.nls.<language>.json`, nothing else. */
        val BUNDLE = Regex("""package\.nls(\.[a-z-]+)?\.json""")

        const val BASE = "package.nls.json"

        /** welcome, saf-bridge, process-monitor, serve-network. */
        const val EXPECTED_EXTENSIONS = 4

        /** Em dash, en dash and horizontal bar, by number so they are not written here. */
        val BANNED = listOf(0x2014, 0x2013, 0x2015)
    }
}
