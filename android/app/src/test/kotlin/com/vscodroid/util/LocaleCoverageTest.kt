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
        const val OWN_EXTENSIONS = "src/main/assets/extensions"

        /** welcome, saf-bridge, process-monitor, serve-network. */
        const val OWN_EXTENSION_COUNT = 4

        /** `package.nls.fr.json`. The English base carries no suffix and is not a language. */
        val BUNDLE_FILE = Regex("""package\.nls\.([a-z-]+)\.json""")

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

    /**
     * `{extension directory: the languages its manifest is translated into}`.
     *
     * The suffixes here are filenames the editor's extension scanner appends to
     * `package.nls.`, and it appends exactly the id the web client sends it, which
     * is `EditorLocale.languageId` of the served bundle. For Chinese those differ:
     * vscode-loc names its directories `zh-hans`/`zh-hant` and VS Code's language
     * ids, which every extension names its manifests for, are `zh-cn`/`zh-tw`. A
     * file named the other way is stepped over in silence and the extension shows
     * in English, which is what these files were called until the id and the bundle
     * name were told apart.
     */
    private fun extensionBundles(): Map<String, Set<String>> =
        File(OWN_EXTENSIONS).listFiles().orEmpty()
            .filter { it.isDirectory && it.name.startsWith("vscodroid.vscodroid-") }
            .sortedBy { it.name }
            .associate { dir ->
                dir.name to dir.listFiles().orEmpty()
                    .mapNotNull { BUNDLE_FILE.matchEntire(it.name)?.groupValues?.get(1) }
                    .toSet()
            }

    @Test
    fun `every translated editor language has translated extension manifests`() {
        val found = extensionBundles()

        // The control. Everything below compares maps, and an empty map compares
        // equal to an empty expectation: a renamed assets path would pass by
        // finding no extension to check.
        assertEquals(
            OWN_EXTENSION_COUNT, found.size,
            "expected $OWN_EXTENSION_COUNT of our own extensions under $OWN_EXTENSIONS, " +
                "found ${found.keys}. Paths resolve from android/app.",
        )

        // Through languageId, because these filenames answer to the scanner rather
        // than to our asset directory: the served bundle is `zh-hans`, the manifest
        // beside it must be `zh-cn`.
        val expected = bundles().map { EditorLocale.languageId(it) }.toSet()
        assertEquals(
            found.keys.associateWith { expected }, found,
            "an extension of ours is translated into a different set of languages than the " +
                "editor is. The editor's own interface and our commands, settings and " +
                "walkthrough are resolved from two different places, so a language in one " +
                "and not the other gives a user a translated editor with English VSCodroid " +
                "commands inside it, and nothing else says so. Name the files with the " +
                "bundle names in $BUNDLE_SCRIPT, not with VS Code language-pack ids.",
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
