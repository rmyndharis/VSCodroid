package com.vscodroid.webview

import com.vscodroid.SourceScan
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * That the page is told VS Code's language id, not the name of our bundle file.
 *
 * The two are the same for eleven of the thirteen languages, which is why sending
 * the bundle name looked right for a year. For Chinese they differ, and the string
 * written here is what the remote extension scanner appends to `package.nls.` when
 * it looks for a translated manifest. Sent `zh-hans`, it finds nothing and every
 * extension renders in English inside a fully translated workbench.
 *
 * Asserted at this statement rather than on the function's output, because the
 * defect is not a wrong value computed somewhere: it is the right value for one
 * purpose used for another, and only the statement says which purpose it serves.
 */
class AnnouncedLanguageIdTest {

    @Test
    fun `the language handed to the page goes through languageId`() {
        val source = SourceScan.withoutComments(
            SourceScan.read("src/main/kotlin/com/vscodroid/webview/VSCodroidWebViewClient.kt"),
        )

        assertTrue(
            source.contains("_VSCODE_NLS_LANGUAGE=\\\"\${EditorLocale.languageId(bundle)}\\\""),
            "the page is no longer told EditorLocale.languageId of the bundle. If the " +
                "raw bundle name went back, a Chinese device gets a translated editor " +
                "whose every extension is in English, and nothing else looks wrong",
        )
        assertFalse(
            source.contains("_VSCODE_NLS_LANGUAGE=\\\"\$bundle\\\""),
            "the raw bundle name is being announced again",
        )
    }
}
