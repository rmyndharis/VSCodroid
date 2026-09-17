package com.vscodroid

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * That the script turning a soft keyboard's composing Enter into one the
 * workbench recognises is installed where every page load passes, leaves the
 * editor, the terminal and a Chinese or Japanese conversion alone, ends the
 * composition before the replacement, and still matches how the shipped
 * workbench reads a key and filters a picker.
 *
 * Source-level, like [ListEditKeeperWiringTest], with the same ceiling: it cannot
 * see whether Gboard's Enter commits a rename on a device, only that the script
 * and the bundle still agree on what it depends on.
 */
class ComposingEnterWiringTest {

    private companion object {
        const val MAIN_ACTIVITY = "src/main/kotlin/com/vscodroid/MainActivity.kt"
        const val WORKBENCH = "src/main/assets/vscode-reh/out/vs/code/browser/workbench/workbench.js"
    }

    /** Comments blanked, because the KDoc names everything the cases look for. */
    private fun mainActivity(): String =
        SourceScan.withoutComments(SourceScan.read(MAIN_ACTIVITY))

    @Test
    fun `the script is installed from the path every page load takes`() {
        val source = mainActivity()
        assertTrue(
            source.contains("private fun injectComposingEnter()"),
            "injectComposingEnter is gone, so Enter on Gboard after typing a word does not " +
                "commit New File or Rename and does not accept a Command Palette pick.",
        )
        val bridgeToken = SourceScan.body(source, "private fun injectBridgeToken(")
        assertTrue(
            bridgeToken.contains("injectComposingEnter()"),
            "injectComposingEnter() is not called from injectBridgeToken, the one path that " +
                "runs again after the workbench switches folders by navigating its own WebView.",
        )
    }

    @Test
    fun `a composing Enter is replaced outside the editor and the terminal only`() {
        val script = SourceScan.body(mainActivity(), "private fun injectComposingEnter(")
        listOf(
            "e.isComposing" to "the one thing that tells Gboard's Enter from any other; " +
                "without it every Enter is replaced",
            "'compositionupdate'" to "the composed text is what tells a CJK conversion from a " +
                "word; without it Enter can no longer confirm a conversion",
            "\\p{Script=Han}" to "Chinese and Japanese kanji compositions confirm on Enter",
            "\\p{Script=Hiragana}" to "a Japanese kana composition confirms its conversion on Enter",
            ".monaco-editor" to "the editor takes Enter through its own edit context and " +
                "already handles it",
            ".xterm" to "the terminal takes Enter through its helper textarea and already " +
                "handles it",
            "stopImmediatePropagation()" to "the real key has to reach no workbench listener, " +
                "or the one replacing it arrives second",
            "dispatchEvent(enter)" to "the replacement is what the workbench recognises; " +
                "without it the key does nothing at all",
        ).forEach { (name, why) ->
            assertTrue(script.contains(name), "injectComposingEnter no longer names `$name`: $why")
        }
        assertFalse(
            script.contains("\\p{Script=Hangul}"),
            "a Hangul composition is excluded again. Its syllable is already the input's value " +
                "and Enter has nothing to confirm, so the exclusion only costs a Korean user " +
                "a second Enter.",
        )
        assertTrue(
            Regex("""addEventListener\('compositionend',\s*function\(\)\s*\{\s*composing\s*=\s*''""")
                .containsMatchIn(script),
            "injectComposingEnter no longer resets the composed text on compositionend: a " +
                "finished composition has to stop counting, or a CJK word typed earlier keeps " +
                "blocking Enter",
        )
        assertTrue(
            Regex("""dispatchEvent\(new CompositionEvent\('compositionend',\s*\{\s*data:\s*composing[\s\S]*dispatchEvent\(enter\)""")
                .containsMatchIn(script),
            "the composition is no longer ended, with the composed text, before the replacement " +
                "Enter. A filterable picker filters only on compositionend, so Enter accepts the " +
                "row that was focused before the word was typed.",
        )
        assertTrue(
            Regex("""Object\.defineProperty\(enter,\s*'keyCode'""").containsMatchIn(script),
            "the replacement no longer carries keyCode 13, and the workbench maps a key from " +
                "keyCode, so it is not Enter to anything that checks for one",
        )
        assertTrue(
            Regex("""if\s*\(!\w+\.dispatchEvent\(enter\)\)\s*e\.preventDefault\(\)""")
                .containsMatchIn(script),
            "the real key's default no longer follows the replacement's. Cancelled " +
                "unconditionally, Enter in a multi-line setting stops inserting a line.",
        )
        assertTrue(
            Regex("""addEventListener\('keydown'[\s\S]*\},\s*true\)""").containsMatchIn(script),
            "the keydown listener is not in the capture phase on the window, so a workbench " +
                "listener sees the composing key before it is replaced",
        )
    }

    @Test
    fun `the shipped workbench still ignores a composing key and reads keyCode`() {
        val workbench = File(WORKBENCH)
        assumeTrue(
            workbench.isFile,
            "no packaged workbench at ${workbench.path}; run scripts/fetch-vscode-oss.sh and " +
                "scripts/package-assets.sh to check the mapping against the shipped bundle",
        )
        val bundle = workbench.readText()
        val mapping = Regex("""this\.keyCode=(\w+)\.isComposing\?114:([\w$]+)\(\1\)""").find(bundle)
        assertTrue(
            mapping != null,
            "StandardKeyboardEvent no longer maps a composing keydown to KeyCode 114. If a " +
                "composing Enter is Enter now, the script replaces a key that already works.",
        )
        val reader = Regex.escape(mapping!!.groupValues[2])
        assertTrue(
            Regex("""function $reader\((\w+)\)\{if\(\1\.charCode\)[^}]*\}let \w+=\1\.keyCode""")
                .containsMatchIn(bundle),
            "the workbench no longer reads a key from charCode then keyCode, so the " +
                "replacement's keyCode 13 may not make it Enter",
        )
        listOf(
            "[1,46,\"Enter\",3,\"Enter\",13,\"VK_RETURN\"" to
                "keyCode 13 no longer maps to KeyCode.Enter",
            "id:\"quickInput.accept\",primary:3" to
                "Quick Open no longer accepts through an Enter keybinding, so check what the " +
                "replacement has to reach",
            "_applyOrUpdateFilter(){if(!this._delegate.onFilter){this._applyFilter();return}" to
                "a picker's filter may no longer apply before the replacement Enter arrives",
        ).forEach { (name, why) ->
            assertTrue(bundle.contains(name), "the packaged workbench no longer contains `$name`: $why")
        }
        assertTrue(
            Regex("""\(this\._filterInput,"compositionend",\(\)=>\{this\._imeSessionInProgress=!1,\w+\(\)\}\)""")
                .containsMatchIn(bundle),
            "the action list's filter no longer catches up on compositionend, so ending the " +
                "composition before the replacement Enter may not filter the picker",
        )
    }
}
