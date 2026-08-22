package com.vscodroid

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * That no string a user reads carries an em dash, an en dash or a horizontal
 * bar, in either of the two forms a resource file can hold one.
 *
 * The character itself is caught tree-wide by `check-plain-punctuation.py`,
 * which compares raw code points. A string resource has a second form that
 * check cannot see: `\uXXXX`, six ASCII characters on disk that AAPT2 expands
 * into the real character in the compiled resource. One had been sitting in the
 * first-run picker's subtitle, drawn to every user who installs the app, while
 * both halves of the enforcement reported a clean tree. This is the half of the
 * question the tree-wide gate cannot answer, kept next to the file it is about.
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
                for ((code, name) in banned) {
                    val literal = String(Character.toChars(code))
                    assertTrue(
                        !text.contains(literal),
                        "${file.name}:${line + 1} carries $name. Use a comma, a colon, a " +
                            "semicolon, a full stop or parentheses",
                    )
                    // The escape form, which AAPT2 expands: the compiled string
                    // holds the character even though the file does not, so the
                    // gate over raw code points reports nothing.
                    val escape = "\\u%04x".format(code)
                    assertTrue(
                        !text.lowercase().contains(escape),
                        "${file.name}:${line + 1} escapes $name, which AAPT2 expands into " +
                            "the character itself before a user reads it",
                    )
                }
            }
        }
    }
}
