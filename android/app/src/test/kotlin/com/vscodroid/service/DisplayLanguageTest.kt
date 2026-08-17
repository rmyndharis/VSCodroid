package com.vscodroid.service

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The user guide tells people the interface cannot be shown in their language.
 * These are the two pieces of configuration that make that true.
 *
 * The editor pulls its interface strings from two script tags in the page it
 * serves: an English bundle that ships inside the app, and a translated bundle
 * whose URL the server builds from `nlsCoreBaseUrl` in `product.json`. Code -
 * OSS ships no such key and this repository adds none, so the second URL is
 * always empty and the English bundle is the only one that ever loads. On the
 * server side `assets/server.js` fixes `VSCODE_NLS_CONFIG` to English at every
 * start, which is what keeps an extension's own contributions in English too.
 *
 * Neither piece announces itself. A language pack from Open VSX installs, shows
 * as enabled, and offers to reload; the editor comes back in English with
 * nothing said, so the only thing standing between a user and half an hour of
 * confusion is the guide entry. If somebody supplies a translated bundle the
 * limit stops being real and the guide becomes a lie, which is the direction
 * this file is pointed: change either mechanism and it fails, naming the
 * section to rewrite.
 *
 * This reads source text, which is the weaker kind of test. What it buys is the
 * one property at issue: that the two files still say what the guide says they
 * say. What it cannot buy is the runtime behaviour, which lives in the built
 * server bundle, and that bundle is a gitignored artifact absent from a fresh
 * clone. The reverse direction is covered too, by the last case: deleting the
 * guide entry while the mechanism stands fails just as loudly.
 */
class DisplayLanguageTest {

    private val serverJs = File("src/main/assets/server.js")
    private val branding = File("../../branding/product.json")
    private val guide = File("../../docs/USER_GUIDE.md")

    /**
     * Paths are resolved from the Gradle test working directory, which is the
     * module directory (`android/app`). Reading is centralised here so no case
     * can forget the existence check: a missing file would otherwise reach an
     * assertion as an empty string, and an empty string satisfies every
     * "does not contain" case in this file while proving nothing.
     */
    private fun read(file: File): String {
        assertTrue(
            file.isFile,
            "${file.absolutePath} not found. This test resolves paths relative to the Gradle " +
                "test working directory, which is the module directory (android/app).",
        )
        val text = file.readText()
        assertTrue(text.isNotBlank(), "${file.absolutePath} is empty, so nothing was checked")
        return text
    }

    @Test
    fun `the server fixes the editor's language to English at every start`() {
        val source = read(serverJs)

        // Both halves matter and they are separately losable: the environment
        // variable can be dropped while the literal survives in a comment, and
        // the locale can be changed while the assignment stays.
        assertTrue(
            source.contains("VSCODE_NLS_CONFIG"),
            "server.js no longer sets VSCODE_NLS_CONFIG. The editor then resolves a language " +
                "from the environment instead of being held at English, and the Known " +
                "Limitations entry 'The Interface Is English Only' in docs/USER_GUIDE.md is " +
                "no longer true of the server side.",
        )
        assertTrue(
            Regex("""VSCODE_NLS_CONFIG[\s\S]{0,120}?locale:\s*'en'""").containsMatchIn(source),
            "server.js no longer pins VSCODE_NLS_CONFIG to the 'en' locale. Rewrite the " +
                "'The Interface Is English Only' entry in docs/USER_GUIDE.md to match whatever " +
                "it now does.",
        )
    }

    /**
     * The key whose absence decides it. `server-main.js` builds the translated
     * bundle's URL only when `nlsCoreBaseUrl` is set, and hands the page an
     * empty URL otherwise, so adding it anywhere that reaches `product.json`
     * turns the interface translatable and retires the guide entry.
     *
     * Both writers are checked because `product.json` has two: the branding
     * overlay applied before the build, and `server.js`, which rewrites the
     * file on every start. Checking one leaves the other free to add the key.
     */
    @Test
    fun `no product configuration supplies a translated string bundle`() {
        for (file in listOf(branding, serverJs)) {
            assertFalse(
                read(file).contains("nlsCoreBaseUrl"),
                "${file.name} now names nlsCoreBaseUrl, which is the address the editor " +
                    "downloads translated interface strings from. If that is deliberate, the " +
                    "interface is no longer English only and the Known Limitations entry " +
                    "'The Interface Is English Only' in docs/USER_GUIDE.md has to go.",
            )
        }
    }

    @Test
    fun `the guide states the limit those two produce`() {
        assertTrue(
            read(guide).contains("### The Interface Is English Only"),
            "docs/USER_GUIDE.md no longer carries the 'The Interface Is English Only' entry, " +
                "but the configuration that makes it true is still in place. A user who " +
                "installs a language pack still gets no explanation from anywhere.",
        )
    }
}
