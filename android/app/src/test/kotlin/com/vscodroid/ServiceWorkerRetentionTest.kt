package com.vscodroid

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * What the process-global `ServiceWorkerClient` is allowed to hold.
 *
 * The client handed to `ServiceWorkerController` is replaced only when some
 * future Activity registers its own, so whatever its suppliers close over stays
 * reachable until then, at worst for the life of the process. Reading a
 * `MainActivity` member directly closes over the Activity, and a destroyed one
 * then survives a task swipe with its view tree.
 *
 * **This is a whitelist, and deliberately a strict one.** An earlier version
 * asked whether any known `MainActivity` property was named inside the call,
 * and that question is the wrong one: capture does not need a property. Both of
 * these compile to a lambda holding the Activity and neither names a property
 * this file declares, so both passed:
 *
 * ```
 * { filesDir.absolutePath }   // inherited from Context
 * { recreateWebView() }       // a member function, the idiom used four lines below
 * ```
 *
 * The second is the shape a future supplier is most likely to take, since the
 * `VSCodroidWebViewClient` built immediately after this call passes
 * `onCrash = { recreateWebView() }` and `onRetryServer = { retryServerStart() }`.
 * So the check is now over what a supplier IS rather than what it mentions:
 * every one has to be a read through the weak reference and nothing else.
 *
 * A legitimate supplier that this refuses is a supplier worth stopping to think
 * about. The failure message says so rather than suggesting a workaround.
 *
 * This reads source, which is weaker than the rest of this suite, and the
 * ceiling is worth stating. A `MainActivity` cannot be built in a plain JVM
 * test, and a retention test depending on `System.gc()` would be the first
 * flaky test here, so neither the leak nor its absence is demonstrated. What is
 * checked is the property that decides it. Source-reading is an established
 * pattern in this suite rather than an exception; [ServerReadinessCallSiteTest]
 * is the closest sibling and reads the same file.
 *
 * What it still cannot see: a supplier assembled elsewhere and passed in by
 * name, since only the call site is read, and the same mistake made in a file
 * this does not open.
 */
class ServiceWorkerRetentionTest {

    private val source = File("src/main/kotlin/com/vscodroid/MainActivity.kt")

    /**
     * Source with line comments and string literals blanked out.
     *
     * Both can contain brackets and either the call's own name, which would
     * move where the walk below starts or how it balances. Blanking rather than
     * deleting keeps every offset where it was, so a failure message still
     * points at the right place.
     */
    private fun scannableSource(): String {
        check(source.isFile) {
            "MainActivity.kt not found at ${source.absolutePath}; this test would " +
                "otherwise pass by looking at nothing"
        }
        val text = source.readText()
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val rest = text.length - i
            when {
                rest >= 2 && text.startsWith("//", i) -> {
                    while (i < text.length && text[i] != '\n') { out.append(' '); i++ }
                }
                text[i] == '"' -> {
                    out.append(' '); i++
                    while (i < text.length && text[i] != '"' && text[i] != '\n') {
                        if (text[i] == '\\' && i + 1 < text.length) { out.append(' '); i++ }
                        out.append(' '); i++
                    }
                    if (i < text.length && text[i] == '"') { out.append(' '); i++ }
                }
                else -> { out.append(text[i]); i++ }
            }
        }
        return out.toString()
    }

    /**
     * The arguments to `setupServiceWorkerInterception`, trailing lambda included.
     *
     * A balanced walk rather than a line count, so reformatting the call cannot
     * silently empty what this inspects.
     */
    private fun callArguments(text: String): String {
        val name = text.indexOf(CALL)
        check(name >= 0) {
            "MainActivity no longer calls $CALL. If the registration was removed on " +
                "purpose, delete this test; if it was renamed, this test stopped " +
                "inspecting anything."
        }
        var i = text.indexOf('(', name)
        val start = i
        var depth = 0
        do {
            when (text[i]) {
                '(' -> depth++
                ')' -> depth--
            }
            i++
        } while (depth > 0 && i < text.length)
        check(depth == 0) { "unbalanced parentheses after $CALL" }

        // The token supplier is a trailing lambda, outside the parentheses and
        // just as able to capture the Activity.
        var j = i
        while (j < text.length && text[j].isWhitespace()) j++
        if (j >= text.length || text[j] != '{') return text.substring(start, i)
        var braces = 0
        do {
            when (text[j]) {
                '{' -> braces++
                '}' -> braces--
            }
            j++
        } while (braces > 0 && j < text.length)
        check(braces == 0) { "unbalanced braces in the trailing lambda of $CALL" }
        return text.substring(start, j)
    }

    /** Each `{ ... }` group in [span], outermost only. */
    private fun lambdaBodies(span: String): List<String> {
        val bodies = mutableListOf<String>()
        var depth = 0
        var open = -1
        span.forEachIndexed { i, c ->
            when (c) {
                '{' -> { if (depth == 0) open = i; depth++ }
                '}' -> { depth--; if (depth == 0 && open >= 0) bodies += span.substring(open + 1, i) }
            }
        }
        return bodies.map { it.trim() }
    }

    @Test
    fun `the shape check accepts a weak read and refuses every way of capturing`() {
        // The control, and it runs against literals rather than the repository,
        // so it stays honest even if MainActivity changes shape entirely.
        listOf(
            "self.get()?.openWorkspaceFolder",
            "self.get()?.nodeService?.getConnectionToken()",
        ).forEach {
            assertTrue(readsWeakly(it), "the real supplier shape `$it` was refused")
        }

        listOf(
            "openWorkspaceFolder" to "a property read, the original defect",
            "nodeService?.getConnectionToken()" to "the other original defect",
            "recreateWebView()" to "a member function, the idiom used four lines below",
            "filesDir.absolutePath" to "a member inherited from Context",
            "folderSupplier" to "a supplier hoisted into a local first",
            "self.get()?.openWorkspaceFolder ?: fallbackFolder()" to "a weak read plus a capture",
        ).forEach { (body, why) ->
            assertTrue(!readsWeakly(body), "`$body` was accepted, but it captures: $why")
        }
    }

    @Test
    fun `every service worker supplier reads through the weak reference`() {
        val span = callArguments(scannableSource())
        val bodies = lambdaBodies(span)

        assertTrue(
            bodies.size >= 2,
            "only ${bodies.size} lambdas were found in the $CALL call, and it takes " +
                "two suppliers. The walk is inspecting the wrong span, so this test " +
                "would pass against anything. Span:\n$span",
        )

        val capturing = bodies.filterNot { readsWeakly(it) }

        assertEquals(
            emptyList<String>(), capturing,
            "these suppliers do not read through the weak reference, so the lambda " +
                "holds the Activity and ServiceWorkerController keeps it until some " +
                "future Activity registers its own: $capturing\n" +
                "Every supplier here has to be `self.get()?....` and nothing else. " +
                "A supplier that cannot be written that way is one to stop and think " +
                "about, not one to widen this check for.",
        )
    }

    @Test
    fun `the reference the suppliers read through is a weak one`() {
        // Without this, `val self = this` would satisfy the check above while
        // leaking exactly as before, under a name that reads as though it did not.
        val text = scannableSource()
        val declaration = text.indexOf("val self = WeakReference(this)")
        val call = text.indexOf(CALL)

        assertTrue(
            declaration in 0 until call,
            "the suppliers read through `self`, but no `val self = WeakReference(this)` " +
                "is declared before the registration. A strong reference spelled the " +
                "same way leaks the Activity and reads as though it does not.",
        )
    }

    private companion object {
        const val CALL = "setupServiceWorkerInterception("

        /** Whether a lambda body is a read through `self` and nothing else. */
        fun readsWeakly(body: String): Boolean =
            Regex("""^self\.get\(\)\?\.[A-Za-z0-9_.?()]*$""").matches(body.trim())
    }
}
