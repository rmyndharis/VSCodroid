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
            // The script wins where the tag states one, and the region decides
            // only where it does not. Reading the region first is wrong in one
            // direction that a device can actually be set to: `zh-Hans-HK` and
            // `zh-Hans-MO` are both in Android's own language picker, and a
            // region test applied to them hands a user who asked for Simplified
            // a Traditional editor, legibly and with nothing on screen to
            // explain it. The mirror case reads correctly by accident, since
            // `zh-Hant-*` is caught by the script before the region is consulted.
            val traditional = when {
                rest.startsWith("hans") -> false
                rest.startsWith("hant") -> true
                else -> rest.substringAfterLast('-') in TRADITIONAL_CHINESE
            }
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

    /**
     * The language id VS Code answers with, for one of our bundle names.
     *
     * The two are the same string for eleven of the thirteen and are not for
     * Chinese, and the difference is not cosmetic. The name that reaches the page
     * as `_VSCODE_NLS_LANGUAGE` is what the workbench reports as
     * `vscode.env.language` AND what it passes to `scanExtensions`, where the
     * remote scanner appends it to `package.nls.` to find an extension's
     * translated manifest. Extensions name those files with VS Code's
     * language-pack ids, `zh-cn` and `zh-tw`; vscode-loc names its own directories
     * `zh-hans` and `zh-hant`. Sending the bundle name made the scanner look for
     * `package.nls.zh-hans.json`, then strip to `package.nls.zh.json`, then give
     * up on the English base, so a Chinese user got a fully translated workbench
     * with every extension's commands and settings in English.
     *
     * Kept apart from [resolveTag] rather than folded into it, because that
     * function answers a different question: which FILE under `assets/nls` to
     * serve, and those are ours and named for vscode-loc. This maps one to the
     * other at the single point the id leaves the app.
     */
    fun languageId(bundle: String): String = LANGUAGE_IDS[bundle] ?: bundle

    /**
     * Bundle name to VS Code language id, for the two that differ.
     *
     * Only Chinese: the other eleven bundle names are already VS Code's ids.
     */
    private val LANGUAGE_IDS = mapOf("zh-hans" to "zh-cn", "zh-hant" to "zh-tw")

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
