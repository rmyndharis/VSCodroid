package com.vscodroid.bridge

import android.webkit.JavascriptInterface
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.lang.reflect.Method

/**
 * The spec must name exactly the methods the bridge registers, and with the same arity.
 *
 * `scripts/check-bridge-api-spec.py` compares the same two files and is the richer of the
 * pair -- it sees parameter names, order and nullability, none of which survive into
 * bytecode. What it cannot do is see a method at all when the source does not spell the
 * annotation the way its pattern expects, and that gap was not theoretical: a method
 * carrying `@android.webkit.JavascriptInterface`, or an import alias, was invisible to
 * both of that script's regexes at once, so its two counts agreed and it reported "ok, all
 * 28 match" while an undocumented method sat in the class. Two patterns keyed on one
 * literal are one check counted twice.
 *
 * This test is the half that cannot be spelled around. `isAnnotationPresent` asks the
 * compiled class, so a qualified annotation, an aliased import, an expression body, a
 * default parameter value with parentheses in it and an overload are all simply methods by
 * the time it looks -- and a commented-out method is not there at all, which is the case
 * the script gets backwards by reporting it as undocumented.
 *
 * So the pair is deliberate rather than redundant: reflection settles WHICH methods exist,
 * text settles what their parameters are called and whether they are nullable. Neither
 * alone is the gate. Do not delete one because the other passes.
 */
class BridgeApiSpecParityTest {

    private val spec = File("../../docs/05-API_SPEC.md")

    /** Every entry point the page can actually call, read from the compiled class. */
    private fun registered(): List<Method> = AndroidBridge::class.java.declaredMethods
        .filter { it.isAnnotationPresent(JavascriptInterface::class.java) }
        .sortedBy { it.name }

    /**
     * Method name to parameter count, as the spec declares them.
     *
     * Anchored at the start of a line, which is how the fenced blocks in §2.4 write a
     * signature and how nothing else in the document does. Kept deliberately simple: this
     * side only has to be good enough to notice a name or an argument going missing,
     * because a doc-side miss fails loudly -- the method turns up as registered-but-absent
     * rather than passing quietly.
     */
    private fun documented(): Map<String, Int> =
        Regex("""(?m)^fun\s+(\w+)\s*\(([^)]*)\)""")
            .findAll(spec.readText())
            .associate { m ->
                val params = m.groupValues[2].trim()
                m.groupValues[1] to if (params.isEmpty()) 0 else params.split(',').size
            }

    @Test
    fun `the spec documents exactly the methods the bridge registers`() {
        val registered = registered()
        // Both sides asserted non-empty before anything is compared. An empty set compares
        // clean against anything, and this project has repeatedly been handed a green that
        // meant "the pattern found nothing" rather than "the two agree".
        assertTrue(
            registered.isNotEmpty(),
            "no @JavascriptInterface methods found on AndroidBridge — reflection returned " +
                "nothing, so any comparison below would pass vacuously",
        )
        assertTrue(spec.exists(), "spec not found at ${spec.absolutePath}")

        val documented = documented()
        assertTrue(
            documented.isNotEmpty(),
            "parsed no signatures out of ${spec.name} — the pattern stopped matching, so a " +
                "clean result here would mean nothing",
        )

        assertEquals(
            registered.map { it.name }.toSortedSet(),
            documented.keys.toSortedSet(),
            "the spec and the bridge disagree about which methods exist. A method the spec " +
                "omits is one nobody can call on purpose; one it invents fails at runtime",
        )
    }

    @Test
    fun `every documented method takes the number of arguments it really takes`() {
        val registered = registered()
        assertTrue(registered.isNotEmpty(), "no @JavascriptInterface methods found")
        val documented = documented()
        assertTrue(documented.isNotEmpty(), "parsed no signatures out of ${spec.name}")

        val wrong = registered.mapNotNull { m ->
            val declared = documented[m.name] ?: return@mapNotNull null
            if (declared == m.parameterCount) null
            else "${m.name}: bridge takes ${m.parameterCount}, spec documents $declared"
        }

        assertEquals(
            emptyList<String>(),
            wrong,
            "a documented signature takes a different number of arguments than the method does",
        )
    }
}
