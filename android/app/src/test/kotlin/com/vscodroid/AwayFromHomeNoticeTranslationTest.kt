package com.vscodroid

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * That the notice for a start away from the home port names, in every language, the
 * button it sends the person to.
 *
 * The notice tells the person to tap Stop Server, and the button beside it and the
 * notification's action are both `action_stop`. A translation that words the sentence
 * one way and the button another sends the person looking for a label that is not on
 * the screen, and a language missing either string shows that one in English beside a
 * translated other. The strings are read with an XML parser, the way Android reads them,
 * and the backslash before an apostrophe or a quote is dropped the way the resource
 * compiler drops it, so the comparison is between the texts a person sees.
 */
class AwayFromHomeNoticeTranslationTest {

    private companion object {
        /** From the Gradle test working directory, which is `android/app`. */
        const val RES = "src/main/res"
        const val NOTICE = "notice_editor_away_from_home"
        const val STOP = "action_stop"
    }

    /** Resource directory name to the two strings, for every `values*` directory with a strings.xml. */
    private fun strings(): Map<String, Map<String, String>> {
        val root = File(RES)
        check(root.isDirectory) { "${root.absolutePath} not found; tests run from android/app" }
        val parser = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        return root.listFiles { f -> f.isDirectory && f.name.startsWith("values") }!!
            .map { it to File(it, "strings.xml") }
            .filter { (_, file) -> file.isFile }
            .associate { (dir, file) ->
                val strings = parser.parse(file).getElementsByTagName("string")
                dir.name to (0 until strings.length)
                    .map { strings.item(it) as Element }
                    .filter { it.getAttribute("name") == NOTICE || it.getAttribute("name") == STOP }
                    .associate { it.getAttribute("name") to shown(it.textContent) }
            }
    }

    /** The text after the resource compiler has dropped the backslash of `\'` and `\"`. */
    private fun shown(text: String) = text.replace("\\'", "'").replace("\\\"", "\"")

    @Test
    fun `every language names its own Stop Server label in the notice and keeps the port once`() {
        val all = strings()
        assertTrue(all.size > 1, "only ${all.keys} was scanned, so no translation was checked")
        for ((dir, found) in all) {
            assertEquals(setOf(NOTICE, STOP), found.keys, "$dir is missing one of $NOTICE and $STOP")
            val notice = found.getValue(NOTICE)
            val stop = found.getValue(STOP)
            assertTrue(
                stop in notice,
                "$dir: the notice does not name the button it sends the person to, \"$stop\": $notice",
            )
            assertEquals(
                1,
                Regex("%1\\\$d").findAll(notice).count(),
                "$dir: the port placeholder %1\$d is not in the notice exactly once: $notice",
            )
        }
    }
}
