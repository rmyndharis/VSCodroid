package com.vscodroid.util

import android.content.res.AssetManager
import java.io.IOException
import java.util.Locale

/**
 * Which translated interface bundle, if any, this device's language gets.
 *
 * The editor keeps its interface strings in one flat array that the page loads
 * as a script tag. `out/server-main.js` builds that script's URL from the
 * locale it reads off the request -- a `vscode.nls.locale` cookie, else the
 * first `Accept-Language` entry, lowercased -- and hands the page an empty URL
 * unless `nlsCoreBaseUrl` is set in `product.json`. `assets/server.js` sets it
 * to a path on the app's own origin, so the URL the page asks for arrives at
 * `VSCodroidWebViewClient.shouldInterceptRequest`, which answers it from the
 * bundles `scripts/build-nls-bundles.py` put in `assets/nls/`.
 *
 * Everything here is that one mapping, from a language tag to a bundle name,
 * and it is shared deliberately: the same function answers the request coming
 * back from the page and picks the file the server process is pointed at.
 *
 * The tag the page asks with is this app's answer rather than the browser's:
 * `MainActivity.applyEditorLanguage` writes the resolved name into the
 * `vscode.nls.locale` cookie before the page loads, so the `Accept-Language`
 * fallback in the server is never reached. That matters because the two can
 * genuinely differ: `android:localeConfig` lets someone set this app to Korean
 * on an English phone, and whether the WebView's header follows the app or the
 * system is not something this app controls.
 *
 * The page is not the whole of what this reaches. The resolved name is also the
 * filename the server's extension scanner appends to `package.nls.`, because
 * `VSCodroidWebViewClient` sets `_VSCODE_NLS_LANGUAGE` from it and the web
 * client sends that to `scanExtensions`. A bundle name here is therefore a
 * filename contract with the bundled extensions' manifests, not only a URL
 * segment.
 *
 * What stays English is a property of the server rather than a choice made here.
 * `out/server-main.js` resolves its own NLS configuration with `userLocale`
 * hardcoded to "en" and assigns it over `VSCODE_NLS_CONFIG` before reading it,
 * and it builds the extension host's environment through the language-pack
 * machinery, which needs a pack installed in the user data directory. So the
 * strings the server logs and the messages an extension shows at runtime stay
 * English however this resolves, and an earlier version of this file that
 * exported the answer to that process was writing to nobody. See
 * `assets/server.js`, which records the same finding beside the line it
 * explains.
 *
 * The bundle names are vscode-loc's, which are lower case and not always what a
 * device would call itself: Portuguese exists only as `pt-br`, and Chinese as
 * `zh-hans` and `zh-hant` rather than by country. [resolveTag] is where those
 * two facts live.
 */
object EditorLocale {

    private const val ASSET_DIR = "nls"
    private const val TAG = "EditorLocale"

    /** Bundle names shipped in this build, without the `.json` suffix. */
    fun available(assets: AssetManager): Set<String> =
        try {
            assets.list(ASSET_DIR).orEmpty()
                .filter { it.endsWith(".json") }
                .map { it.removeSuffix(".json") }
                .toSet()
        } catch (e: IOException) {
            Logger.w(TAG, "Could not list bundled interface translations: ${e.message}")
            emptySet()
        }

    /**
     * The bundle for [tag], or null to leave the interface in English.
     *
     * English returns null rather than a bundle name on purpose, and not only
     * because there is nothing to translate: the server refuses to build a
     * translated bundle's URL at all when the requested locale starts with
     * "en", so an `en` bundle could never be asked for.
     */
    fun resolveTag(tag: String, available: Set<String>): String? {
        val normalized = tag.lowercase(Locale.ROOT).replace('_', '-')
        if (normalized.isEmpty() || normalized == "en" || normalized.startsWith("en-")) return null
        if (normalized in available) return normalized

        val language = normalized.substringBefore('-')
        val rest = normalized.removePrefix(language).removePrefix("-")

        // Chinese is written, not located: a device says zh-CN or zh-TW, and
        // occasionally zh-Hans-CN, while the bundles are named for the script.
        // Simplified is the default because it is what every zh region except
        // the three below uses.
        if (language == "zh") {
            val traditional = rest.startsWith("hant") || rest.substringAfterLast('-') in TRADITIONAL_CHINESE
            val bundle = if (traditional) "zh-hant" else "zh-hans"
            return bundle.takeIf { it in available }
        }

        // Then the language on its own, and failing that any regional bundle of
        // the same language. The second step is what gives a Portugal device
        // Brazilian Portuguese: `pt` is the language a phone reports and `pt-br`
        // is the only Portuguese anyone has translated the editor into, so the
        // alternative to this line is English.
        return language.takeIf { it in available }
            ?: available.filter { it.startsWith("$language-") }.minOrNull()
    }

    /** The bundle for the device's current language, or null for English. */
    fun forDevice(assets: AssetManager): String? =
        resolveTag(Locale.getDefault().toLanguageTag(), available(assets))

    /**
     * Opens a bundle for the page, or null when this build does not carry it.
     *
     * Streamed straight out of the APK rather than unpacked first: these are
     * 1 MiB apiece and thirteen of them ship, so copying even one into
     * `filesDir` would spend a user's storage on a file only this function
     * reads, once per page load.
     */
    fun open(assets: AssetManager, bundle: String): java.io.InputStream? =
        try {
            assets.open("$ASSET_DIR/$bundle.json")
        } catch (e: IOException) {
            null
        }

    private val TRADITIONAL_CHINESE = setOf("tw", "hk", "mo")
}
