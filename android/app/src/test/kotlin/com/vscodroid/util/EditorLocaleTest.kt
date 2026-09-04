package com.vscodroid.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Which bundle a phone's language tag gets, which is the whole of what decides
 * the language the editor comes up in.
 *
 * [EditorLocale.resolveTag] answers for both halves of the feature: the page's
 * request, arriving at `VSCodroidWebViewClient`, and the single bundle unpacked
 * for the server process. So a wrong answer here is not a mistranslation, it is
 * either an editor in two languages at once or, far more likely, an editor in
 * English with nothing said about why.
 *
 * The mapping is not the identity, and the cases below are the places it is not:
 * the bundle names are vscode-loc's, which are lower case, name Chinese by
 * script rather than by country, and have exactly one Portuguese.
 *
 * `available` is a parameter rather than a listing of `assets/nls`, which is what
 * makes this a plain JVM test: no `AssetManager`, no device. The set below is the
 * thirteen `scripts/build-nls-bundles.py` writes, spelled as it spells them. It
 * is a fixture, not an assertion about the build: a language added there needs no
 * change here, and the rules these cases pin are the same rules whatever the set
 * holds.
 */
class EditorLocaleTest {

    private val shipped = setOf(
        "cs", "de", "es", "fr", "it", "ja", "ko",
        "pl", "pt-br", "ru", "tr", "zh-hans", "zh-hant",
    )

    private fun resolve(tag: String) = EditorLocale.resolveTag(tag, shipped)

    @Test
    fun `a tag that is already a bundle name is that bundle`() {
        // The base case, and the control for every case below: without it, a
        // resolveTag that answered null to everything would satisfy half of them.
        assertEquals("de", resolve("de"))
        assertEquals("ja", resolve("ja"))
        assertEquals("pt-br", resolve("pt-BR"), "the bundle names are lower case, tags are not")
    }

    @Test
    fun `a language with a region falls back to the language`() {
        // What most phones actually report. Android hands out a full tag, and
        // vscode-loc names its packs by language alone outside Chinese and
        // Portuguese, so this is the common path rather than an edge.
        assertEquals("ko", resolve("ko-KR"))
        assertEquals("de", resolve("de-AT"), "Austria has no bundle of its own and needs none")
    }

    @Test
    fun `Chinese is resolved by script, not by country`() {
        // Chinese is written, not located: a phone says zh-CN or zh-TW, and
        // sometimes zh-Hans-CN, while the bundles are named for the script.
        // Getting this wrong is the one case that fails without falling back to
        // English: a Taiwanese phone would come up in Simplified, which is
        // legible and wrong.
        assertEquals("zh-hans", resolve("zh-CN"))
        assertEquals("zh-hans", resolve("zh-Hans-CN"))
        assertEquals("zh-hant", resolve("zh-TW"))
        assertEquals("zh-hant", resolve("zh-HK"))
        assertEquals("zh-hant", resolve("zh-Hant"))
    }

    @Test
    fun `Portuguese from Portugal gets the Brazilian bundle`() {
        // The alternative to this is English, for the whole of Portugal. `pt` is
        // what a phone reports and `pt-br` is the only Portuguese the editor has
        // been translated into, so a regional bundle of the same language is
        // preferred over no bundle at all.
        assertEquals("pt-br", resolve("pt-PT"))
        assertEquals("pt-br", resolve("pt"))
    }

    @Test
    fun `English and no language at all stay untranslated`() {
        // Not merely "nothing to translate". `server-main.js` refuses to build a
        // translated bundle's URL when the requested locale starts with "en", so
        // an English bundle could never be asked for even if one shipped, and
        // answering with a name here would only unpack a file nothing reads.
        assertNull(resolve("en"))
        assertNull(resolve("en-GB"))
        assertNull(resolve(""), "an empty tag is what a locale nobody set looks like")
    }

    @Test
    fun `a language nobody has translated the editor into stays English`() {
        // Vietnamese has no vscode-loc pack, so there is nothing to serve and the
        // right answer is the English the page has already loaded. The prefix
        // search must not reach for a different language that happens to sort
        // nearby.
        assertNull(resolve("vi-VN"))
        assertNull(resolve("vi"))
    }
}
