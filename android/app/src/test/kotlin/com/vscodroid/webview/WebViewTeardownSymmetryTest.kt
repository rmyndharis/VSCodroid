package com.vscodroid.webview

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * That both ways a WebView is destroyed drop what still calls into it.
 *
 * There are two, and they were written years apart. `recreateWebView` replaces a
 * renderer that died; `onDestroy` ends the Activity. Only the first cleared the
 * extra key row's `KeyInjector`, and the row's modifier poll is what makes the
 * difference visible: while a modifier is latched it re-posts itself every
 * 200 ms through the view's own handler, so a tick queued before the Activity
 * goes still runs after `onDestroy` has returned. With the injector left in
 * place that tick asks a destroyed WebView, which answers by logging and never
 * calling back.
 *
 * That costs a warning and a dead entry in a run queue nothing will drain, and
 * it is put here as symmetry rather than as a repair: the asymmetry is what
 * makes the next reader of either method believe the other one is doing
 * something it is not.
 *
 * Source-reading, and in this package rather than beside the row, for the reason
 * `ExtraKeyModifierSyncTest` sets out at length: `MainActivity` and `ExtraKeyRow`
 * are both unbuildable on the JVM, so no test can run a `postDelayed` and the
 * timing is argued rather than executed. What is checked is the shape carrying
 * it, in both places at once, which is the half no per-method case can see.
 */
class WebViewTeardownSymmetryTest {

    private val file = File("src/main/kotlin/com/vscodroid/MainActivity.kt")

    private val source by lazy {
        check(file.isFile) {
            "MainActivity.kt not found at ${file.absolutePath}; this test would " +
                "otherwise pass by looking at nothing"
        }
        file.readText()
    }

    /**
     * The lines of one method, comments dropped.
     *
     * By indentation rather than by brace matching: every rule here is argued in
     * prose beside the line it governs, and a comment is not a call.
     */
    private fun code(declaration: String): List<String> {
        val lines = source.lines()
        val start = lines.indexOfFirst { it.contains(declaration) }
        assertTrue(start >= 0) {
            "`$declaration` is gone from MainActivity.kt, so this test is measuring " +
                "nothing. If it moved or was renamed, point this at the new site rather " +
                "than deleting it."
        }
        return lines.drop(start + 1)
            .takeWhile { it != "    }" }
            .filterNot {
                val t = it.trimStart()
                t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
            }
    }

    /**
     * NEGATIVE CONTROL: delete `extraKeyRow?.keyInjector = null` from either
     * method and the matching half goes red naming that method. Move it below
     * the destroy in either and the ordering assertion goes red instead.
     */
    @Test
    fun `every path that destroys the WebView drops the key row's injector first`() {
        val paths = listOf(
            "override fun onDestroy()" to "webView?.destroy()",
            "private fun recreateWebView()" to "wv.destroy()",
        )

        for ((declaration, destroy) in paths) {
            val body = code(declaration)
            val destroyed = body.indexOfFirst { it.contains(destroy) }
            assertTrue(destroyed >= 0) {
                "`$destroy` is gone from $declaration, so this half is measuring nothing"
            }
            val cleared = body.indexOfFirst { it.contains("extraKeyRow?.keyInjector = null") }
            assertTrue(cleared >= 0) {
                "$declaration destroys the WebView without dropping the key row's " +
                    "injector, so a modifier poll already queued calls into a destroyed " +
                    "WebView after this method has returned"
            }
            assertTrue(cleared < destroyed) {
                "$declaration drops the injector after destroying the view it wraps, " +
                    "which leaves the window between them holding exactly what the " +
                    "clearing exists to prevent"
            }
        }
    }
}
