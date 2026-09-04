package com.vscodroid.service

import com.vscodroid.SourceScan
import com.vscodroid.webview.VSCodroidWebViewClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

/**
 * The wiring that shows the interface in the phone's language, and the two
 * places it is written down twice.
 *
 * This file used to pin the opposite. The editor was English whatever the device
 * was set to, because the workbench builds its translated bundle's URL from
 * `nlsCoreBaseUrl` in `product.json` and nothing supplied one. `server.js` now
 * names an address on the app's own origin, and the WebView answers it.
 *
 * Nothing here fails loudly. A prefix that stops matching brings the editor up
 * in English with nothing on screen to notice, which is precisely what every
 * build before this one did, so there is no user report to expect either. Hence
 * two contracts that no compiler checks, and a third case for what a user is
 * told:
 *
 *  - the path in `nlsCoreBaseUrl` against [VSCodroidWebViewClient.NLS_PATH_PREFIX],
 *    which is what decides whether the page's request is answered at all: nothing
 *    serves that address over HTTP, `shouldInterceptRequest` does, and only for
 *    paths whose first segment is that constant;
 *  - the two globals the served bundle assigns. The strings alone leave the page
 *    translated and every extension told the editor is English.
 *
 * What is deliberately NOT pinned here is the server process, because there is
 * nothing to pin: the built `out/server-main.js` resolves its own configuration
 * with the locale hardcoded and assigns it over `VSCODE_NLS_CONFIG` before
 * reading it, so no environment this app writes can reach it. `assets/server.js`
 * records that beside the line it explains.
 *
 * The last case is the user guide, which described the limit while it was real
 * and would be a lie now. It is the reverse direction of the same pin: restoring
 * the entry while the mechanism stands fails as loudly as losing the mechanism.
 *
 * This reads source, which is the weaker kind of test. What it buys is that the
 * files a fresh clone contains still agree with each other. What it cannot buy is
 * the runtime behaviour, which needs the built server tree and the translated
 * bundles, both gitignored artifacts absent from a fresh clone. The comment-blank
 * pass matters for the same reason it always did here: commenting a line out is
 * how a pin gets switched off while something else is being tried, and it leaves
 * every character in place for a substring search to find.
 */
class DisplayLanguageTest {

    private companion object {
        const val SERVER_JS = "src/main/assets/server.js"
        const val WEBVIEW_CLIENT = "src/main/kotlin/com/vscodroid/webview/VSCodroidWebViewClient.kt"
        const val MAIN_ACTIVITY = "src/main/kotlin/com/vscodroid/MainActivity.kt"
        const val USER_GUIDE = "../../docs/USER_GUIDE.md"
    }

    /**
     * The bootstrap's source with its comments blanked.
     *
     * Not used by the case below it, deliberately: [SourceScan.withoutComments]
     * cuts a line at `//`, which is inside every URL there is, and the value that
     * case reads is a URL. It reads the raw text and anchors on a line whose
     * first non-blank characters are the key, which a commented-out line is not.
     */
    private fun serverJsCode() = SourceScan.withoutComments(SourceScan.read(SERVER_JS))

    /**
     * The address the server advertises has to be one this app answers.
     *
     * `out/server-main.js` appends `<commit>/<version>/<locale>/nls.messages.js`
     * to `nlsCoreBaseUrl` and puts the result in a script tag; the WebView
     * answers it from the bundles in the APK, and only when the first path
     * segment is [VSCodroidWebViewClient.NLS_PATH_PREFIX]. The two are the same
     * decision written in two files, so this compares them rather than restating
     * either.
     *
     * The trailing slash is part of the comparison and not tidiness: the commit
     * is concatenated straight onto the base, so a base ending at the prefix
     * makes the first segment `_nls<commit>`, which matches nothing.
     */
    @Test
    fun `the address the server advertises is the one this app serves`() {
        val base = Regex("""(?m)^\s*nlsCoreBaseUrl:\s*[`'"]([^`'"]+)[`'"]""")
            .find(SourceScan.read(SERVER_JS))?.groupValues?.get(1)
            ?: fail(
                "server.js no longer sets nlsCoreBaseUrl in productOverrides. Without it " +
                    "server-main.js hands the page an empty script src and the interface is " +
                    "English on every device, silently. If the key moved to another writer, " +
                    "point this case at it; if the feature was withdrawn, restore the " +
                    "'The Interface Is English Only' entry in docs/USER_GUIDE.md with it."
            )

        // Everything after the authority, interpolation included: the host and
        // port are runtime values and are not what this compares.
        val path = base.substringAfter("://").substringAfter('/', "")

        assertEquals(
            "${VSCodroidWebViewClient.NLS_PATH_PREFIX}/",
            path,
            "the path in server.js's nlsCoreBaseUrl ($base) is not the path " +
                "VSCodroidWebViewClient.nlsBundleRequested answers. The page then asks for a " +
                "bundle nothing serves, the request falls through to the editor server, and " +
                "the interface stays English with nothing logged. Note the trailing slash: " +
                "the commit is appended directly to this value, so without it the first path " +
                "segment is the prefix and the commit run together.",
        )
    }

    /**
     * That the page is told which language it is in, not only given the strings.
     *
     * Two globals travel in one bundle upstream, and this app assembles that
     * bundle itself, so it can ship half of it. `_VSCODE_NLS_MESSAGES` is what
     * the interface renders from; `_VSCODE_NLS_LANGUAGE` is the answer to
     * `vscode.env.language`, it selects the date and number formats extensions
     * are handed, and the workbench copies both into every worker it starts.
     * The English fallback bundle in the tree sets neither, so nothing else on
     * the page can supply the second one.
     *
     * Serving only the first is invisible: every string is translated and every
     * extension is told the editor is English. That is what this case is for.
     */
    @Test
    fun `the page is told which language it is in`() {
        val client = SourceScan.withoutComments(SourceScan.read(WEBVIEW_CLIENT))

        assertTrue(
            client.contains("_VSCODE_NLS_MESSAGES"),
            "the interface bundle no longer assigns _VSCODE_NLS_MESSAGES, so the page is " +
                "served a script that translates nothing.",
        )
        assertTrue(
            client.contains("_VSCODE_NLS_LANGUAGE"),
            "the interface bundle no longer assigns _VSCODE_NLS_LANGUAGE. Every string is " +
                "still translated, so nothing looks wrong, but vscode.env.language answers " +
                "\"en\" to every extension and the workbench formats dates as English.",
        )
    }

    /**
     * That the two wires this feature hangs on are still connected.
     *
     * Both are call sites rather than code, and both fail silently. The client
     * takes its bundles through a constructor parameter that defaults to null,
     * so dropping the argument compiles and leaves every request answered with a
     * 404 and the interface in English. The cookie is written from `loadVSCode`
     * before the navigation it decides, so moving or losing that call hands the
     * page whatever `Accept-Language` the WebView felt like sending, which is
     * the disagreement `EditorLocale` exists to prevent.
     *
     * Read as text because that is what a call site is; the alternative is to
     * make the parameter non-defaulted, which would put a null in six unrelated
     * test call sites to say what this one line says.
     */
    @Test
    fun `the activity still hands the client its bundles and sets the cookie`() {
        val activity = SourceScan.withoutComments(SourceScan.read(MAIN_ACTIVITY))

        assertTrue(
            activity.contains("interfaceTranslations ="),
            "MainActivity no longer passes interfaceTranslations to VSCodroidWebViewClient. The " +
                "parameter defaults to null, so this compiles and every request for a " +
                "translated bundle is answered with a 404: the interface comes up in English " +
                "with nothing on screen to say why.",
        )
        assertTrue(
            activity.contains("applyEditorLanguage()"),
            "MainActivity no longer calls applyEditorLanguage, so nothing writes the " +
                "vscode.nls.locale cookie and the page's language is decided by the " +
                "Accept-Language header instead of by EditorLocale.",
        )
    }

    @Test
    fun `the guide no longer tells people the interface is English`() {
        val guide = SourceScan.read(USER_GUIDE)

        // Positive control. The case below is an absence, and an absence is also
        // what reading the wrong file, or a file whose sections were renamed
        // wholesale, looks like.
        assertTrue(
            guide.contains("## Known Limitations"),
            "docs/USER_GUIDE.md has no 'Known Limitations' section, so the case below " +
                "checked nothing. Find where the limits are listed now and point this at it.",
        )
        assertFalse(
            guide.contains("The Interface Is English Only"),
            "docs/USER_GUIDE.md still tells people the interface cannot be shown in their " +
                "language, which stopped being true when server.js began supplying " +
                "nlsCoreBaseUrl and the app began shipping translated bundles. Describe what " +
                "it does now: the phone's language, for the languages the app carries.",
        )
    }
}
