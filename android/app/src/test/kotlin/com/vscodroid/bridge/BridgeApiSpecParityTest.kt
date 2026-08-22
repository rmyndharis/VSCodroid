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
 * ```
 * ╔════════════════════════════════════════════════════════════════════════════╗
 * ║ IF YOU ARE DELETING THIS TEST, READ THIS FIRST.                            ║
 * ║                                                                            ║
 * ║ It looks redundant beside scripts/check-bridge-api-spec.py, which compares  ║
 * ║ the same two files and checks MORE axes. It is not redundant.               ║
 * ║                                                                            ║
 * ║ WHICH METHODS THE PAGE CAN CALL IS DECIDED HERE AND NOWHERE ELSE. The       ║
 * ║ script reads source text and its window is one file, so it cannot see a     ║
 * ║ method whose annotation is spelled `@android.webkit.JavascriptInterface`,   ║
 * ║ one reached through an aliased import, or one the bridge INHERITS from a    ║
 * ║ base class in another file. All three are callable from the page. All three ║
 * ║ were measured green against the script. Delete this test and they stop      ║
 * ║ being checked -- and NOTHING GOES RED.                                      ║
 * ║                                                                            ║
 * ║ Neither half is complete. There is no third thing checking that this        ║
 * ║ division is still correct, which is why this notice exists in both files.   ║
 * ╚════════════════════════════════════════════════════════════════════════════╝
 * ```
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
 * This test cannot be spelled around. `isAnnotationPresent` asks the compiled class, so a
 * qualified annotation, an aliased import, an expression body, a default parameter value
 * with parentheses in it and an overload are all simply methods by the time it looks --
 * and a commented-out method is not there at all, which is the case the script gets
 * backwards by reporting it as undocumented.
 *
 * WHAT IT DOES NOT DO, stated because the previous version of this paragraph said
 * "reflection settles WHICH methods exist" and that sentence is what stopped the next
 * reader checking. It was written to close the spelling class and was wrong about
 * inheritance: `declaredMethods` skipped inherited methods that the page could call, and
 * the confident comment outlived the gap. Three guards on this pair have now claimed a
 * property they did not have, so this list is the point of the paragraph, not an aside.
 *
 *  - It compares names and ARITY. It does NOT compare return types: those are checked
 *    only by `check-bridge-api-spec.py`. A method whose documented return type is wrong
 *    passes here: measured on a five-way merge, 751 tests, 0 failures, while the script
 *    reported the mismatch.
 *  - It does not see parameter NAMES, their order, or nullability. None survive erasure.
 *  - `@JvmOverloads` would produce two annotated methods sharing a name with different
 *    arities. `documented()` collapses names, so whichever arity the spec states, the
 *    other mismatches. No such method exists today; this fails loudly if one appears.
 *  - `documented()` counts parameters by splitting on commas, so a documented generic
 *    like `Map<String, Int>`, or a default value containing a comma, inflates the count
 *    and fails a correct document. Also loud, also absent today.
 *  - `documented()` builds its map with `associate`, which keeps the LAST entry for a
 *    repeated key. A method documented TWICE (two blocks, two arities) collapses to
 *    whichever came last, and the first is never compared against anything. This is the
 *    one limit in this list that is silent rather than loud, so it is the one worth
 *    re-reading if the spec ever grows a second entry for a name. No name appears twice
 *    today; `check-bridge-api-spec.py` has the same shape and the same exposure.
 *
 * So the pair is deliberate rather than redundant, and BOTH halves are load-bearing and
 * NEITHER is complete: reflection settles the set of callable methods, text settles their
 * parameter names, order, nullability and return type. Do not delete one because the other
 * passes, and do not read a green from either as "the spec matches the bridge".
 */
class BridgeApiSpecParityTest {

    private val spec = File("../../docs/05-API_SPEC.md")

    /**
     * Every entry point the page can actually call, read from the compiled class.
     *
     * `.methods`, NOT `.declaredMethods`, and the difference is the whole point of this
     * function. `addJavascriptInterface` exposes the class's *public* methods, which
     * includes the ones it inherits; `declaredMethods` returns what this class declares
     * and nothing it inherits. So a base class carrying one annotated method used to be
     * callable from the page and invisible here. Measured, with a `BridgeBase` holding a
     * single method: twenty-nine callable methods, this test green, the script green too
     * because its window is one file.
     *
     * Filtering `Any` is belt and braces (`java.lang.Object` declares nothing annotated),
     * but it says what the set is meant to be rather than relying on that staying true.
     *
     * The swap also drops private methods, which is correct rather than incidental:
     * `declaredMethods` returned them, so an annotated private method was demanded in the
     * spec although no page can call it. `.methods` is public-only, which is exactly the
     * set the bridge exposes.
     */
    private fun registered(): List<Method> = AndroidBridge::class.java.methods
        .filter { it.declaringClass != Any::class.java }
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
     *
     * The existence check lives HERE rather than in each caller, and that placement is the
     * fix for a real defect: one of the two tests asserted `spec.exists()` first and the
     * other did not, so deleting the file failed one of them with the path named and the
     * other with a bare `FileNotFoundException` out of `readText()`. Both failed, so
     * nothing shipped wrong -- but a caller could forget the guard, and one already had.
     * Checking inside the reader means no caller can.
     */
    private fun documented(): Map<String, Int> {
        assertTrue(
            spec.exists(),
            "spec not found at ${spec.absolutePath} -- this test resolves it relative to " +
                "the Gradle test working directory, which is the module dir (android/app)",
        )
        return Regex("""(?m)^fun\s+(\w+)\s*\(([^)]*)\)""")
            .findAll(spec.readText())
            .associate { m ->
                val params = m.groupValues[2].trim()
                m.groupValues[1] to if (params.isEmpty()) 0 else params.split(',').size
            }
    }

    @Test
    fun `the spec documents exactly the methods the bridge registers`() {
        val registered = registered()
        // Both sides asserted non-empty before anything is compared. An empty set compares
        // clean against anything, and this project has repeatedly been handed a green that
        // meant "the pattern found nothing" rather than "the two agree".
        assertTrue(
            registered.isNotEmpty(),
            "no @JavascriptInterface methods found on AndroidBridge: reflection returned " +
                "nothing, so any comparison below would pass vacuously",
        )
        assertTrue(spec.exists(), "spec not found at ${spec.absolutePath}")

        val documented = documented()
        assertTrue(
            documented.isNotEmpty(),
            "parsed no signatures out of ${spec.name}: the pattern stopped matching, so a " +
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
