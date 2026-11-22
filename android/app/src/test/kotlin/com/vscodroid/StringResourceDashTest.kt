package com.vscodroid

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * That no string a user reads carries an em dash, an en dash or a horizontal
 * bar, in any of the three forms a resource file can hold one.
 *
 * The character itself is caught tree-wide by `check-plain-punctuation.py`,
 * which compares raw code points. A string resource has two more forms that
 * check could not see until it learned to read resources for it: `\uXXXX`, six
 * ASCII characters on disk that AAPT2 expands into the real character in the
 * compiled resource, and an XML character reference, decimal or hexadecimal,
 * which the XML parser expands before AAPT2 sees the text. One had been sitting
 * in the first-run picker's subtitle, drawn to every user who installs the app,
 * while both halves of the enforcement reported a clean tree. This asks the same
 * question next to the file it is about.
 *
 * The three code points are named by their numbers rather than written out, for
 * the same reason that gate names them that way: a check that spells its own
 * subject fails itself, and the local editing hook refuses to write the file at
 * all.
 */
class StringResourceDashTest {

    private val banned = mapOf(
        0x2014 to "U+2014 EM DASH",
        0x2013 to "U+2013 EN DASH",
        0x2015 to "U+2015 HORIZONTAL BAR",
    )

    /** Every strings.xml under a values directory, translations included. */
    private fun stringFiles(): List<File> =
        (File("src/main/res").listFiles() ?: emptyArray())
            .filter { it.isDirectory && it.name.startsWith("values") }
            .map { File(it, "strings.xml") }
            .filter { it.isFile }

    /** What [text], one line of a strings file, carries of [banned], each named with its form. */
    private fun dashesIn(text: String): List<String> = banned.flatMap { (code, name) ->
        val literal = String(Character.toChars(code))
        // The escape form, which AAPT2 expands: the compiled string holds the
        // character even though the file does not, so the gate over raw code
        // points reports nothing.
        val escape = "\\u%04x".format(code)
        // A character reference, which the XML parser expands, in decimal or in
        // hexadecimal of either case, with or without leading zeros.
        val reference = Regex("&#(?:x0*${Integer.toHexString(code)}|0*$code);", RegexOption.IGNORE_CASE)
        listOfNotNull(
            name.takeIf { literal in text },
            "$name escaped".takeIf { escape in text.lowercase() },
            "$name as a character reference".takeIf { reference.containsMatchIn(text) },
        )
    }

    @Test
    fun `each form of a dash is found`() {
        val dash = 0x2014
        val forms = listOf(
            String(Character.toChars(dash)),
            "\\u%04x".format(dash),
            "\\U%04X".format(dash),
            "&#$dash;",
            "&#0$dash;",
            "&#x%x;".format(dash),
            "&#X%X;".format(dash),
        )
        for (form in forms) {
            assertTrue(dashesIn("<string name=\"a\">one $form two</string>").isNotEmpty(), "missed $form")
        }
        // The control: what this project writes instead of a dash is not one.
        assertTrue(dashesIn("<string name=\"a\">one - two -- three &#45; &amp;#$dash;</string>").isEmpty())
    }

    @Test
    fun `no user-facing string carries a dash this project does not use`() {
        val files = stringFiles()
        // The control. An empty list would report a clean tree, which is exactly
        // what this test would say if the resources ever moved.
        assertTrue(
            files.isNotEmpty(),
            "no strings.xml found under ${File("src/main/res").absolutePath}; this test " +
                "would otherwise pass by reading nothing",
        )

        for (file in files) {
            for ((line, text) in file.readLines().withIndex()) {
                val found = dashesIn(text)
                assertTrue(
                    found.isEmpty(),
                    "${file.parentFile.name}/${file.name}:${line + 1} carries $found, which a user " +
                        "reads as the character itself. Use a comma, a colon, a semicolon, a full " +
                        "stop or parentheses",
                )
            }
        }
    }
}
