package com.vscodroid.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.text.DecimalFormatSymbols
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.NodeList

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
 *
 * The rest of the class holds each translation to its English source where a
 * difference fails just as quietly: the same strings, plurals and blanks, the
 * same notes beside them, the same button named where a sentence sends someone
 * to it, one spelling for each thing a language names, and sizes written in the
 * language's own units and decimal style.
 */
class LocaleCoverageTest {

    private companion object {
        /** Paths from the Gradle test working directory, which is `android/app`. */
        const val BUNDLE_SCRIPT = "../../scripts/build-nls-bundles.py"
        const val LOCALES_CONFIG = "src/main/res/xml/locales_config.xml"
        const val RES = "src/main/res"
        const val OWN_EXTENSIONS = "src/main/assets/extensions"

        /** welcome, saf-bridge, process-monitor, serve-network, editor-menus. */
        const val OWN_EXTENSION_COUNT = 5

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

        /** `%1$s`, `%2$d`, `%s`: what a format call fills, after `%%` is taken out. */
        val PLACEHOLDER = Regex("""%(\d+\$)?[a-z]""")

        /**
         * The languages whose `one` item may leave the count out: for the counts these
         * plurals are given, which start at 1, `one` there is the number one and
         * nothing else. French and Portuguese also put 0 under `one`, which no caller
         * passes. Russian is the case the list exists for: its `one` is also 21, 31
         * and 101, so a `one` item without the count tells someone with 21 kept
         * files that there is one. Japanese, Korean and Chinese have no `one`.
         */
        val ONE_MEANS_ONLY_ONE = setOf("", "cs", "de", "es", "fr", "it", "pl", "pt-rBR", "tr")

        /**
         * The word each language uses for the phone and for the app in the two
         * notices that say whose edit was kept beside a file. The notices exist to
         * tell the two apart, so a count that drops the word says nothing the
         * `.device-` and `.local-` names do not.
         */
        val KEPT_EDIT_WORDS = mapOf(
            "" to Pair("device", "app"),
            "cs" to Pair("zařízení", "aplikace"),
            "de" to Pair("Gerät", "App"),
            "es" to Pair("dispositivo", "aplicación"),
            "fr" to Pair("appareil", "application"),
            "it" to Pair("dispositivo", "app"),
            "ja" to Pair("デバイス", "アプリ"),
            "ko" to Pair("기기", "앱"),
            "pl" to Pair("urządzenia", "aplikacji"),
            "pt-rBR" to Pair("dispositivo", "app"),
            "ru" to Pair("устройства", "приложени"),
            "tr" to Pair("Cihaz", "Uygulama"),
            "zh-rCN" to Pair("设备", "应用"),
            "zh-rTW" to Pair("裝置", "應用程式"),
        )

        /**
         * Spellings a language's files settled against. Each is the minority form of
         * a word the file otherwise writes one way, so a screen beside another names
         * the same thing twice over.
         */
        val SETTLED_SPELLINGS = mapOf(
            // デバイス everywhere else.
            "ja" to listOf(Regex("端末")),
            // "app" everywhere else, as in the English source.
            "pt-rBR" to listOf(Regex("aplicativo")),
            // "folder na urządzeniu" everywhere else.
            "pl" to listOf(Regex("w urządzeniu")),
            // A particle is written against the word it follows, as every other string does.
            "ko" to listOf(Regex("""%(\d+\$)?[a-z] (을\(를\)|이\(가\)|은\(는\)|와\(과\)|과\(와\)|\(으\)로)""")),
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

    /** A language's strings and plurals as a person reads them, keyed by resource name. */
    private class Texts(val strings: Map<String, String>, val plurals: Map<String, Map<String, String>>)

    /**
     * The texts of one `values` directory: "" for the English base, else a language qualifier.
     *
     * Read with an XML parser, the way Android reads them, and with the backslash
     * before an apostrophe or a quote dropped the way the resource compiler drops it.
     */
    private fun texts(language: String): Texts {
        val path = if (language.isEmpty()) "$RES/values/strings.xml" else "$RES/values-$language/strings.xml"
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(path))
        fun elements(parent: NodeList) = (0 until parent.length).map { parent.item(it) as Element }
        fun shown(text: String) = text.replace("\\'", "'").replace("\\\"", "\"")
        return Texts(
            strings = elements(document.getElementsByTagName("string"))
                .filter { it.getAttribute("translatable") != "false" }
                .associate { it.getAttribute("name") to shown(it.textContent) },
            plurals = elements(document.getElementsByTagName("plurals")).associate { plural ->
                plural.getAttribute("name") to elements(plural.getElementsByTagName("item"))
                    .associate { it.getAttribute("quantity") to shown(it.textContent) }
            },
        )
    }

    /** English first, as "", then every shipped language. */
    private fun everyLanguage(): List<String> = listOf("") + resourceDirectories().sorted()

    @Test
    fun `every native string and plural is available in every shipped language`() {
        val english = texts("")
        for (language in resourceDirectories().sorted()) {
            val found = texts(language)
            for ((kind, expected, actual) in listOf(
                Triple("strings", english.strings.keys, found.strings.keys),
                Triple("plurals", english.plurals.keys, found.plurals.keys),
            )) {
                assertTrue(expected == actual,
                    "$language $kind missing ${expected - actual}; extra ${actual - expected}")
            }
        }
    }

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

    private fun placeholders(text: String): Set<String> =
        PLACEHOLDER.findAll(text.replace("%%", "")).map { it.value }.toSet()

    /**
     * A translation fills the same blanks as its English source.
     *
     * The format call passes its arguments by number, so a translation that drops
     * one, renumbers it or changes its type prints the wrong thing or throws, and
     * nothing at compile time compares the files. A set rather than a list: a
     * sentence may name an argument twice.
     *
     * A plural's items are held to the English `other`, which is the one carrying
     * every argument; a `one` item may leave out the count in the languages
     * [ONE_MEANS_ONLY_ONE] names, which is how "One file" is written.
     */
    @Test
    fun `every translation fills the blanks its English source fills`() {
        val english = texts("")
        val wrong = mutableListOf<String>()
        for (language in everyLanguage()) {
            val found = texts(language)
            for ((name, text) in found.strings) {
                val expected = placeholders(english.strings[name] ?: continue)
                if (placeholders(text) != expected) wrong += "$language/$name has ${placeholders(text)}, not $expected"
            }
            for ((name, items) in found.plurals) {
                val expected = placeholders(english.plurals[name]?.get("other") ?: continue)
                if ("other" !in items) wrong += "$language/$name has no other item, which Android falls back to"
                for ((quantity, text) in items) {
                    val allowed = if (quantity == "one" && language in ONE_MEANS_ONLY_ONE) {
                        setOf(expected, expected - "%1\$d")
                    } else {
                        setOf(expected)
                    }
                    if (placeholders(text) !in allowed) {
                        wrong += "$language/$name[$quantity] has ${placeholders(text)}, not $expected"
                    }
                }
            }
        }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n"))
    }

    /**
     * Every sentence that sends the person to the setup screen's Retry button
     * names it as that language labels it (`progress_retry`, the button
     * `activity_splash.xml` draws). A sentence worded one way beside a button
     * worded another sends the person looking for a label that is not there.
     */
    @Test
    fun `every language names its own Retry label where it sends the person to it`() {
        for (language in everyLanguage()) {
            val strings = texts(language).strings
            val retry = strings.getValue("progress_retry")
            for (name in listOf("error_storage_full", "error_setup_failed", "error_setup_failed_at")) {
                assertTrue(
                    retry in strings.getValue(name),
                    "$language/$name does not name the Retry button, \"$retry\": ${strings.getValue(name)}",
                )
            }
        }
    }

    /**
     * The two notices for files kept inside the app are numbered the same way in
     * every language (count, folder, file), so the call has to pass them in that
     * order: the folder and the file are both strings, and swapping them reads as
     * a sentence about a folder named after the file.
     */
    @Test
    fun `the kept-in-app notices pass the folder and the file in the order every language numbers them`() {
        val source = read("src/main/kotlin/com/vscodroid/MainActivityHelpers.kt")
        assertTrue(
            Regex("""getString\(\s*R\.string\.saf_file_kept_in_app,\s*folder,\s*first\s*\)""")
                .containsMatchIn(source),
            "saf_file_kept_in_app is not called with (folder, first), which its %1\$s and %2\$s name",
        )
        assertTrue(
            Regex("""getQuantityString\(\s*R\.plurals\.saf_files_kept_in_app,\s*count,\s*count,\s*folder,\s*first\s*\)""")
                .containsMatchIn(source),
            "saf_files_kept_in_app is not called with (count, folder, first), which its %1\$d, %2\$s " +
                "and %3\$s name",
        )
    }

    @Test
    fun `a count of kept edits says whose edits they are, in every language`() {
        assertEquals(everyLanguage().toSet(), KEPT_EDIT_WORDS.keys, "a language has no entry here")
        val wrong = mutableListOf<String>()
        for (language in everyLanguage()) {
            val found = texts(language)
            val (device, app) = KEPT_EDIT_WORDS.getValue(language)
            for ((word, single, plural) in listOf(
                Triple(device, "saf_device_copy_preserved", "saf_device_copies_preserved"),
                Triple(app, "saf_local_copy_preserved", "saf_local_copies_preserved"),
            )) {
                val said = listOf(found.strings.getValue(single)) + found.plurals.getValue(plural).values
                said.filter { !it.contains(word, ignoreCase = true) }.forEach { wrong += "$language: \"$word\" is missing from $it" }
            }
        }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n"))
    }

    /**
     * One spelling for each thing a language names, across all its screens; see
     * [SETTLED_SPELLINGS]. Also, in every language, the product's name is not
     * inflected with a Latin ending ("VSCodroidzie", "VSCodroidu"): every other
     * string writes it as it is, after a word that takes the ending.
     */
    @Test
    fun `each language spells the device, the app and the product one way`() {
        val inflectedName = Regex("""VSCodroid\p{IsLatin}""")
        val wrong = mutableListOf<String>()
        for (language in everyLanguage()) {
            val found = texts(language)
            val all = found.strings.values + found.plurals.values.flatMap { it.values }
            for (pattern in SETTLED_SPELLINGS[language].orEmpty() + inflectedName) {
                all.filter { pattern.containsMatchIn(it) }.forEach { wrong += "$language: \"${pattern.pattern}\" in $it" }
            }
        }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n"))
    }

    /**
     * The notes beside the strings are the English file's, in every language.
     *
     * Each translation carries the English comments so that whoever edits it reads
     * why a sentence is worded as it is, beside the sentence. A note rewritten in
     * English and not in the copies leaves thirteen files telling the next editor
     * something the code no longer does (one said the toolchain screen could be
     * reached by no bundled extension's command, long after the Manage Toolchains
     * command reached it), and a note added only in English is missing exactly
     * where a translator looks.
     */
    @Test
    fun `every translation carries the English notes`() {
        fun notes(language: String): List<String> {
            val path = if (language.isEmpty()) "$RES/values/strings.xml" else "$RES/values-$language/strings.xml"
            return Regex("""<!--(.*?)-->""", RegexOption.DOT_MATCHES_ALL).findAll(read(path))
                .map { it.groupValues[1].replace(Regex("""\s+"""), " ").trim() }.toList()
        }
        val english = notes("")
        for (language in resourceDirectories().sorted()) {
            val found = notes(language)
            val first = english.indices.firstOrNull { found.getOrNull(it) != english[it] }
                ?: if (found.size > english.size) english.size else null
            assertTrue(
                first == null,
                "values-$language/strings.xml: note ${first?.plus(1)} differs from the English file's.\n" +
                    "English: ${english.getOrNull(first ?: 0)}\nvalues-$language: ${found.getOrNull(first ?: 0)}",
            )
        }
    }

    /** `values-pt-rBR` to the locale Android resolves it for; "" is English. */
    private fun localeOf(language: String): Locale =
        if (language.isEmpty()) Locale.ENGLISH else Locale.forLanguageTag(language.replace("-r", "-"))

    /**
     * A size inside a translated sentence is written the way that language writes
     * it: its decimal separator, and the unit the same file already uses for the
     * amount the setup screen asks to free (`error_storage_full`), which the
     * translators spelled themselves ("Mo" in French, "МБ" in Russian).
     */
    @Test
    fun `a size is written in the units and decimal style of each shipped language`() {
        val wrong = mutableListOf<String>()
        for (language in everyLanguage()) {
            val locale = localeOf(language)
            val figure = StorageManager.formatSize(2_500_000_000L, locale)
            val separator = DecimalFormatSymbols.getInstance(locale).decimalSeparator
            if (!figure.startsWith("2${separator}5 ")) wrong += "$language: $figure does not use \"$separator\""
            val unit = StorageManager.formatSize(5_000_000L, locale).substringAfter(' ')
            val request = texts(language).strings.getValue("error_storage_full")
            if (unit !in request) wrong += "$language: \"$unit\" is not the unit of \"$request\""
        }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n"))

        assertEquals("2,5 Go", StorageManager.formatSize(2_500_000_000L, Locale.FRANCE))
        assertEquals("512 o", StorageManager.formatSize(512L, Locale.FRANCE))
        assertEquals("2,5 ГБ", StorageManager.formatSize(2_500_000_000L, Locale.forLanguageTag("ru-RU")))
        assertEquals("1,5 кБ", StorageManager.formatSize(1_500L, Locale.forLanguageTag("ru-RU")))
        assertEquals("2,5 GB", StorageManager.formatSize(2_500_000_000L, Locale.GERMANY))
        assertEquals("2.5 GB", StorageManager.formatSize(2_500_000_000L, Locale.JAPAN))
    }

    /**
     * A phone set to a language this app has no screens in reads the English
     * sentences, so it gets the English figure: "2,5 GB" inside "Storage low
     * (...available)" is half of one convention and half of another. English
     * screens keep the figure they always printed, whatever region the phone is
     * set to. The English figure is also what the log lines and the one-argument
     * call write.
     */
    @Test
    fun `English screens and untranslated languages get the English figure`() {
        assertEquals("2.5 GB", StorageManager.formatSize(2_500_000_000L, Locale.forLanguageTag("id-ID")))
        assertEquals("1.5 MB", StorageManager.formatSize(1_500_000L, Locale.forLanguageTag("nl-NL")))
        assertEquals("2.5 GB", StorageManager.formatSize(2_500_000_000L, Locale.forLanguageTag("en-ZA")))
        assertEquals("2.5 GB", StorageManager.formatSize(2_500_000_000L, Locale.forLanguageTag("en-DE")))
        for (bytes in listOf(0L, 999L, 1_500L, 999_999L, 2_500_000L, 128_000_000_000L)) {
            assertEquals(StorageManager.formatSize(bytes), StorageManager.formatSize(bytes, Locale.US))
        }
    }

    /**
     * Every size a sentence carries is formatted for the language the sentence is
     * in. The one-argument call writes the English figure and is for log lines; a
     * screen that reaches for it puts "1.2 GB" inside a French sentence that asks
     * elsewhere for "312 Mo". Read from the source, so it also holds for code no
     * JVM test can run.
     */
    @Test
    fun `only log lines write a size in the English-only form`() {
        val sources = File("src/main/kotlin").walk().filter { it.isFile && it.extension == "kt" }.toList()
        check(sources.size > 10) { "no Kotlin sources found under src/main/kotlin; tests run from android/app" }
        val englishOnly = sources.flatMap { file ->
            val text = file.readText()
            Regex("""(?<!fun )\bformatSize\(""").findAll(text).mapNotNull { call ->
                val lineStart = text.lastIndexOf('\n', call.range.first) + 1
                val line = text.substring(lineStart, text.indexOf('\n', call.range.first).let { if (it < 0) text.length else it })
                val trimmed = line.trimStart()
                if (trimmed.startsWith("*") || trimmed.startsWith("//") || "Logger." in line) return@mapNotNull null
                var depth = 0
                var commas = 0
                for (c in text.substring(call.range.last)) {
                    when (c) {
                        '(' -> depth++
                        ')' -> if (--depth == 0) break
                        ',' -> if (depth == 1) commas++
                    }
                }
                if (commas == 0) "${file.name}: ${trimmed}" else null
            }.toList()
        }
        assertTrue(englishOnly.isEmpty(), "sizes written in English inside translated sentences:\n" + englishOnly.joinToString("\n"))
    }
}
