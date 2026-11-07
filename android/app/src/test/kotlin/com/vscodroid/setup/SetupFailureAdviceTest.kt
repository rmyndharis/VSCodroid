package com.vscodroid.setup

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * That the setup failure screen does not send anyone to a reinstall without saying
 * what it costs.
 *
 * When setup fails during an update, every launch returns to that screen and the
 * editor cannot start. The projects a user made live in the app's own storage
 * (`files/projects`) and the backup rules leave them out, so a reinstall deletes
 * them. The screen used to say only "Tap Retry or reinstall the app".
 *
 * The two messages are read from the resource files with an XML parser, the way
 * Android reads them, so the check follows the strings and not the layout of the
 * file. The English wording is judged for what it says. Every other language is
 * judged for its shape only, because a JVM test cannot read Czech: both messages
 * present, and the placeholders `SplashActivity` fills (`%1$s` the step and `%2$s`
 * the cause in the longer one, none in the shorter one).
 */
class SetupFailureAdviceTest {

    private companion object {
        /** From the Gradle test working directory, which is `android/app`. */
        const val RES = "src/main/res"
        const val SHORT = "error_setup_failed"
        const val LONG = "error_setup_failed_at"
    }

    /** Resource directory name to the two messages, for every `values*` directory. */
    private fun messages(): Map<String, Map<String, String>> {
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
                    .filter { it.getAttribute("name") == SHORT || it.getAttribute("name") == LONG }
                    .associate { it.getAttribute("name") to it.textContent }
            }
    }

    @Test
    fun `the English messages say that a reinstall deletes the projects stored in the app`() {
        val english = messages().getValue("values")
        for (key in listOf(SHORT, LONG)) {
            val text = english.getValue(key).lowercase()
            assertTrue("reinstall" in text, "$key no longer names the reinstall: $text")
            assertTrue("delete" in text, "$key suggests a reinstall without saying it deletes something: $text")
            assertTrue("project" in text, "$key does not say what a reinstall deletes: $text")
        }
    }

    @Test
    fun `every language has both messages with the placeholders the code fills`() {
        val all = messages()
        assertTrue(all.size > 1, "only ${all.keys} was scanned, so no translation was checked")
        for ((dir, found) in all) {
            assertEquals(setOf(SHORT, LONG), found.keys, "$dir is missing one of the setup failure messages")
            val short = found.getValue(SHORT)
            val long = found.getValue(LONG)
            assertTrue('%' !in short, "$dir: the short message takes no arguments but has a %: $short")
            assertEquals(1, Regex("%1\\\$s").findAll(long).count(), "$dir: the step placeholder is not there exactly once")
            assertEquals(1, Regex("%2\\\$s").findAll(long).count(), "$dir: the cause placeholder is not there exactly once")
        }
    }
}
