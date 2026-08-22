package com.vscodroid.service

import org.json.JSONObject
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
 * This reads source, which is the weaker kind of test. What it buys is the one
 * property at issue: that the files a fresh clone contains still say what the
 * guide says they say. What it cannot buy is the runtime behaviour, which lives
 * in the built server bundle, and that bundle is a gitignored artifact absent
 * from a fresh clone. The reverse direction is covered too, by the last case:
 * deleting the guide entry while the mechanism stands fails just as loudly.
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
        //
        // The first half is anchored to the assignment at the start of a line,
        // which is what makes "survives in a comment" something this can tell
        // apart. Commenting the line out is how the pin gets switched off while
        // something else is being tried, and it leaves every character in place,
        // so a substring search reads the disabled line as the live one and
        // reports the limit as still enforced. The variable is written once, as a
        // statement of its own, so the anchor matches what is there today.
        assertTrue(
            Regex("""(?m)^\s*process\.env\.VSCODE_NLS_CONFIG\s*=""").containsMatchIn(source),
            "server.js no longer sets VSCODE_NLS_CONFIG. The editor then resolves a language " +
                "from the environment instead of being held at English, and the Known " +
                "Limitations entry 'The Interface Is English Only' in docs/USER_GUIDE.md is " +
                "no longer true of the server side. If the assignment moved out of a " +
                "statement of its own, retarget this deliberately rather than unanchoring it.",
        )
        assertTrue(
            Regex("""(?m)^\s*process\.env\.VSCODE_NLS_CONFIG[\s\S]{0,120}?locale:\s*'en'""")
                .containsMatchIn(source),
            "server.js no longer pins VSCODE_NLS_CONFIG to the 'en' locale. Rewrite the " +
                "'The Interface Is English Only' entry in docs/USER_GUIDE.md to match whatever " +
                "it now does.",
        )
    }

    /**
     * The key whose absence decides it. `server-main.js` builds the translated
     * bundle's URL only when `nlsCoreBaseUrl` is set, and hands the page an
     * empty URL otherwise, so the key arriving anywhere that reaches
     * `product.json` turns the interface translatable and retires the guide
     * entry.
     *
     * Three writers reach that file, and this covers the two a fresh clone
     * contains.
     *
     * `branding/product.json` is an overlay with two opposite halves and only
     * `set` adds keys: `scripts/build-vscode-oss.sh` pops everything named in
     * `remove` out of the built file, so the same string landing there would
     * make the limit permanent rather than lift it. Reading the file as text
     * cannot tell those apart, and would fail loudest on the one edit that
     * agrees with the guide, which is why this parses instead.
     *
     * `server.js` is JavaScript rather than JSON, so it stays a text check.
     *
     * The third writer is Code - OSS's own `product.json`. The overlay is
     * merged onto it rather than replacing it, so a `VSCODE_VERSION` bump that
     * lands the key upstream carries it into the built file without either of
     * the two files here changing. That one is out of reach from a unit test,
     * because the built tree is a gitignored artifact; `verify-server-tree.py`
     * reads it on every build, where it exists.
     */
    @Test
    fun `no product configuration supplies a translated string bundle`() {
        val overlay = JSONObject(read(branding))
        val set = if (overlay.has("set")) overlay.getJSONObject("set") else JSONObject()

        // Positive control. The assertion below passes on an overlay whose
        // adding half is named something else, or gone, which is a scan of
        // nothing wearing the same green.
        assertTrue(
            set.length() > 0,
            "branding/product.json has no non-empty 'set' object, so nothing was checked here. " +
                "The overlay's shape changed; find the half that adds keys now and point this " +
                "at it.",
        )
        assertFalse(
            set.has("nlsCoreBaseUrl"),
            "branding/product.json sets nlsCoreBaseUrl, which is the address the editor " +
                "downloads translated interface strings from. If that is deliberate, the " +
                "interface is no longer English only and the Known Limitations entry " +
                "'The Interface Is English Only' in docs/USER_GUIDE.md has to go. Naming it " +
                "in the overlay's 'remove' half is the opposite change and is not this.",
        )
        assertFalse(
            read(serverJs).contains("nlsCoreBaseUrl"),
            "server.js names nlsCoreBaseUrl, which it writes into product.json on every " +
                "start, so the editor now has an address to download translated interface " +
                "strings from. If that is deliberate, the Known Limitations entry 'The " +
                "Interface Is English Only' in docs/USER_GUIDE.md has to go.",
        )
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
