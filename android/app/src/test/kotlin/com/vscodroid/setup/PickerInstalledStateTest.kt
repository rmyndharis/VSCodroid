package com.vscodroid.setup

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * That the first-run picker is told what is already on disk.
 *
 * [ToolchainCardState] can now say a toolchain is installed, and
 * ToolchainCardStateTest pins that it does. Nothing in that half notices if the
 * screen never hands it the install record: every card is then an offer, exactly
 * as before, and ticking one spends a download the user has already paid for.
 * That is the failure this file exists for, and it is the one no other test here
 * can see.
 *
 * The screen used to be able to assume nothing was installed while it was up,
 * because it was reached only from the end of a fresh first run. It is now
 * offered on any launch that has not answered it, and `ToolchainActivity`
 * installs toolchains without touching the preference that records the answer,
 * so the assumption is gone and the read is what replaces it.
 *
 * Source reading, because `showToolchainPicker` is an Activity method that
 * inflates a layout, and this project has no Robolectric. It proves the call is
 * written and reached from that function, not that the record is read correctly;
 * `ToolchainManager.getInstalledToolchains` has its own cover.
 */
class PickerInstalledStateTest {

    private val source = File("src/main/kotlin/com/vscodroid/SplashActivity.kt")

    /**
     * The body of one function, brace-matched from its signature, with braces
     * inside comments stepped over.
     *
     * A single `{` in a comment makes the depth never return to zero at the real
     * closing brace, so the body runs on into whatever follows and every search
     * below silently becomes a file-wide one. Measured on the adapter in
     * [PickerAccessibilityWiringTest]: one commented brace widened a body from
     * 1877 characters to 5295.
     */
    private fun bodyOf(signature: String): String {
        assertTrue(
            source.isFile,
            "${source.path} is not at ${source.absolutePath}; this test would " +
                "otherwise pass by reading nothing",
        )
        val text = source.readText()
        val start = text.indexOf(signature)
        assertTrue(start >= 0, "no function matching `$signature` in ${source.name}")
        var i = text.indexOf('{', start)
        assertTrue(i >= 0, "no body follows `$signature` in ${source.name}")
        val open = i
        var depth = 0
        while (i < text.length) {
            when {
                text.startsWith("//", i) -> while (i < text.length && text[i] != '\n') i++
                text.startsWith("/*", i) -> {
                    i += 2
                    while (i < text.length && !text.startsWith("*/", i)) i++
                    i += 2
                }
                text[i] == '{' -> { depth++; i++ }
                text[i] == '}' -> {
                    depth--
                    if (depth == 0) return text.substring(open, i + 1)
                    i++
                }
                else -> i++
            }
        }
        error("unbalanced braces after `$signature` in ${source.name}")
    }

    /**
     * Comments dropped, so prose about the read cannot satisfy a search for it.
     * The comment beside the call names it at length, which is exactly the shape
     * that passes while the call is gone.
     */
    private fun code(body: String) = body
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), " ")
        .replace(Regex("""//[^\n]*"""), " ")

    @Test
    fun `the first-run picker is told what is already installed`() {
        val body = code(bodyOf("private fun showToolchainPicker("))

        // Bounded rather than trusted: an extraction that ran to the end of the
        // file would find the call somewhere for entirely the wrong reason. Around
        // 1,100 characters of code when this was written.
        assertTrue(
            body.length in 200..4_000,
            "extracted ${body.length} characters of showToolchainPicker, which means the " +
                "extraction is wrong rather than the code",
        )
        // Anchored to the start of a line: a commented-out call is still a call to
        // a substring search, and commenting one out is how a developer disables
        // something while debugging.
        assertTrue(
            Regex("""(?m)^\s*adapter\.setInstalled\(""").containsMatchIn(body),
            "the picker is never told what is already on disk, so every card is drawn " +
                "as a fresh download and ticking an installed toolchain fetches it again",
        )
    }

    /**
     * The control for the extraction, because a body that quietly ran past its
     * function would satisfy the case above by finding the call somewhere else.
     * `startDownloads` is the declaration after `showToolchainPicker`, so its name
     * appearing in the extracted body is precisely that failure.
     */
    @Test
    fun `the extracted body stops at the function it names`() {
        val body = bodyOf("private fun showToolchainPicker(")

        assertTrue(
            "fun startDownloads" !in body,
            "bodyOf ran past showToolchainPicker and swallowed the next declaration, so " +
                "the assertion above is really a file-wide search: ${body.length} chars",
        )
    }
}
