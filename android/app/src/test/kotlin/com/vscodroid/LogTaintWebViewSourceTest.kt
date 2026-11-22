package com.vscodroid

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * That [LogTaint] reads the address a WebView holds as the token-bearing value it is, whatever the
 * WebView is called.
 *
 * The page's URL carries the connection token in `tkn=`. The reader took it as a source only when
 * read off a name spelled `wv` or `webView`, and MainActivity reads it off `targetView` too, into
 * a local that a `Logger.i` could print into a release build's logcat with every guard green.
 */
class LogTaintWebViewSourceTest {

    private fun method(vararg body: String): List<String> =
        listOf("    private fun restore(port: Int) {", "        val targetView = webView ?: return") +
            body + listOf("    }")

    private fun assertLeaks(source: List<String>) {
        assertTrue(LogTaint.leaks(source).isNotEmpty()) {
            "a token-bearing address reaches Logger here and the reader did not see it:\n" + source.joinToString("\n")
        }
    }

    @Test
    fun `an address read off a WebView of any name and printed is a leak`() {
        assertLeaks(method(
            "        val currentUrl = targetView.url",
            """        Logger.i(tag, "Restoring ${'$'}currentUrl")""",
        ))
        assertLeaks(method(
            "        val currentUrl = targetView?.url",
            """        Logger.w(tag, "Restoring " + currentUrl)""",
        ))
    }

    @Test
    fun `an address read off a name declared as a WebView is a leak`() {
        assertLeaks(listOf(
            "    private fun restore(page: EditorWebView) {",
            "        val shown = page.url",
            """        Logger.i(tag, "Restoring ${'$'}shown")""",
            "    }",
        ))
    }

    @Test
    fun `an address printed straight off the WebView is a leak`() {
        assertLeaks(method("""        Logger.i(tag, "Restoring ${'$'}{targetView.url}")"""))
        assertLeaks(method("""        Logger.i(tag, "Restoring ${'$'}{webView?.url}")"""))
    }

    @Test
    fun `the address named by its host alone is not a leak`() {
        val labelled = method(
            "        val currentUrl = targetView.url",
            """        Logger.i(tag, "Restoring ${'$'}{urlLogLabel(currentUrl)}")""",
            """        Logger.i(tag, "Restoring ${'$'}{urlLogLabel(targetView.url)}")""",
        )

        assertEquals(emptyList<String>(), LogTaint.leaks(labelled))
        assertEquals(2, LogTaint.reducedLogs(labelled).size) {
            "the reader did not see the address in the statements that name it by its host, so the case " +
                "above passes for want of looking"
        }
    }

    /**
     * The two lines MainActivity reads the address with, taken from the file and read alone. Read with the
     * rest of the file the local is tainted anyway, for a reason that has nothing to do with the WebView:
     * names are file-scoped and `url`, which holds the workbench address elsewhere, matches the `.url`
     * of any initialiser. A rename there would have left this one unguarded.
     */
    @Test
    fun `the reader finds the address MainActivity reads off its target view`() {
        val source = File("src/main/kotlin/com/vscodroid/MainActivity.kt").readLines().map { it.trim() }
        val read = listOf("val targetView = webView ?: return", "val currentUrl = targetView.url")
        assertTrue(source.containsAll(read)) {
            "MainActivity no longer reads the page's address into currentUrl off targetView; point this at " +
                "wherever it reads it now"
        }

        assertTrue("currentUrl" in LogTaint.taintedNames(read)) {
            "LogTaint does not follow the page's address out of targetView.url, so a log statement printing " +
                "currentUrl would put the connection token into a release build's logcat unseen"
        }
    }
}
