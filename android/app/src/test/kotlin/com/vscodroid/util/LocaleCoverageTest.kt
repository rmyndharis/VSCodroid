package com.vscodroid.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File

/**
 * That the three lists of languages this app ships are one list.
 *
 * A language exists in three places and they are written in three notations,
 * none of which the compiler compares:
 *
 * - `scripts/build-nls-bundles.py` decides which translated bundles the editor
 *   gets, named the way vscode-loc names them (`pt-br`, `zh-hans`);
 * - `res/values-*` decides which languages the app's own screens have, named
 *   the way Android names resource directories (`pt-rBR`, `zh-rCN`);
 * - `res/xml/locales_config.xml` decides which languages Android offers in the
 *   per-app language picker, named in BCP 47 (`pt-BR`, `zh-Hans`).
 *
 * Every way they can disagree is a half-translated app, and each half fails
 * quietly. A language in the picker with no `values-` directory offers a user a
 * language whose screens are English. A language with resources but no bundle
 * gives them translated screens around an English editor. A bundle with neither
 * is 1 MiB of an APK nobody can reach.
 *
 * English is the exception, and only in the picker: it is the language every
 * other file is written in, so it has no resource directory and no bundle, and
 * `EditorLocale.resolveTag` answers null for it on purpose.
 */
class LocaleCoverageTest {

    private companion object {
        /** Paths from the Gradle test working directory, which is `android/app`. */
        const val BUNDLE_SCRIPT = "../../scripts/build-nls-bundles.py"
        const val LOCALES_CONFIG = "src/main/res/xml/locales_config.xml"
        const val RES = "src/main/res"

        /**
         * The three languages the notations spell differently.
         *
         * Keyed by the bundle name, which is the one this app resolves to.
         */
        /** `fr`, `pt-rBR`: a language, optionally a region. Nothing else. */
        val LANGUAGE_QUALIFIER = Regex("""^[a-z]{2,3}(-r[A-Z]{2})?$""")

        val SPELLINGS = mapOf(
            "pt-br" to Pair("pt-rBR", "pt-BR"),
            "zh-hans" to Pair("zh-rCN", "zh-Hans"),
            "zh-hant" to Pair("zh-rTW", "zh-Hant"),
        )
    }

    private fun read(path: String): String {
        val file = File(path)
        check(file.isFile) { "${file.absolutePath} not found; tests run from android/app" }
        return file.readText()
    }

    /** The bundle names in the script's LOCALES list. */
    private fun bundles(): Set<String> {
        val list = Regex("""LOCALES = \[(.*?)]""", RegexOption.DOT_MATCHES_ALL)
            .find(read(BUNDLE_SCRIPT))
            ?.groupValues?.get(1)
        checkNotNull(list) { "build-nls-bundles.py no longer declares LOCALES as a list literal" }
        return Regex(""""([a-z-]+)"""").findAll(list).map { it.groupValues[1] }.toSet()
    }

    /**
     * The language qualifiers under `res/`, and only those.
     *
     * `values-` prefixes every alternate resource directory, not only the
     * translated ones: `values-night`, `values-land`, `values-sw600dp` and
     * `values-v34` are all ordinary Android qualifiers this app may grow at any
     * time, and comparing one of those against the list of shipped languages
     * would fail this test for a change that has nothing to do with language.
     * Hence the shape test rather than the prefix.
     */
    private fun resourceDirectories(): Set<String> =
        File(RES).listFiles().orEmpty()
            .filter { it.isDirectory && it.name.startsWith("values-") }
            .map { it.name.removePrefix("values-") }
            .filter { LANGUAGE_QUALIFIER.matches(it) }
            .toSet()

    private fun pickerLocales(): Set<String> =
        Regex("""android:name="([^"]+)"""").findAll(read(LOCALES_CONFIG))
            .map { it.groupValues[1] }
            .toSet()

    @Test
    fun `every translated editor language has translated app screens`() {
        val expected = bundles().map { SPELLINGS[it]?.first ?: it }.toSet()

        assertEquals(
            expected, resourceDirectories(),
            "the editor's languages and the app's own screens have drifted apart. A language " +
                "only the editor has leaves the setup screen, the toolchain picker and every " +
                "dialog in English around a translated editor; one only the resources have is " +
                "the reverse. Add or remove it in both scripts/build-nls-bundles.py and " +
                "src/main/res, and in src/main/res/xml/locales_config.xml with it.",
        )
    }

    @Test
    fun `the language picker offers exactly what is shipped, plus English`() {
        val expected = bundles().map { SPELLINGS[it]?.second ?: it }.toSet() + "en"

        assertEquals(
            expected, pickerLocales(),
            "locales_config.xml and the shipped languages have drifted apart. Android reads " +
                "that file for Settings > Apps > VSCodroid > Language, so a name here with " +
                "nothing behind it offers a user a language the app does not have, and a " +
                "language missing from here cannot be chosen without changing the whole phone. " +
                "English belongs here and nowhere else: it is what the untranslated files are " +
                "written in.",
        )
    }
}
