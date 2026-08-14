package com.vscodroid

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The workbench ships as an ES module and no AMD loader is packaged with it, so
 * `window.require` does not exist in the page. Injected JavaScript that reaches for it
 * throws on its first line — and because those injections retry on a timer inside an
 * empty catch, the failure leaves nothing behind: no command, no log, no error.
 *
 * Four commands were advertised for a year on that basis. This test is the thing that
 * notices the next time.
 */
class InjectedScriptApiTest {

    private val mainActivity = File("src/main/kotlin/com/vscodroid/MainActivity.kt")

    @Test
    fun `injected JavaScript does not reach for the AMD loader`() {
        check(mainActivity.isFile) {
            "MainActivity.kt not found at ${mainActivity.absolutePath} — this test would " +
                "otherwise pass by looking at nothing"
        }

        val offenders = mainActivity.readLines()
            .withIndex()
            .filter { (_, line) -> Regex("""\b(window|globalThis|self)\.require\b""").containsMatchIn(line) }
            .map { (i, line) -> "MainActivity.kt:${i + 1}: ${line.trim()}" }

        assertEquals(
            emptyList<String>(), offenders,
            "Injected script uses an AMD loader the shipped workbench does not have. " +
                "Commands must be contributed by a bundled extension instead, which " +
                "reaches Android through the BroadcastChannel relay."
        )
    }
}
