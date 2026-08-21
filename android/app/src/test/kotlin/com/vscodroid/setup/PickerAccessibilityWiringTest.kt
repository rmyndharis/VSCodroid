package com.vscodroid.setup

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The accessibility wiring on the first-run picker, and the one on the Toolchains
 * toolbar, kept from being deleted by someone who cannot see what it does.
 *
 * Both are invisible to a sighted reviewer and to every other test here. Selection
 * on a picker card was drawn as a stroke, a background colour and a checkmark, and
 * reported `checkable=false, checked=false` to a screen reader in both states; the
 * toolbar's back arrow, the only visible way off the Toolchains screen, announced
 * as an unlabelled button. Neither had a symptom anyone would notice while looking
 * at the screen, which is why neither was noticed.
 *
 * ⚠️ What this cannot see, stated because the alternative is a reader trusting it
 * for more than it proves. These read source and XML, so they prove the calls are
 * written, not that a screen reader speaks anything. The behaviour lives in
 * `announceForAccessibility` and in the framework's node building, and no runner
 * family GitHub offers can execute the instrumented suite that would reach it.
 * `docs/DEVICE_TEST_CHECKLIST.md` is the instrument for that half.
 *
 * Scoped to the function that owns each call rather than to the file. A search over
 * a whole file is satisfied by any copy of the token anywhere in it, which is how a
 * guard of this shape passes while the thing it guards is gone.
 */
class PickerAccessibilityWiringTest {

    private val adapter = File("src/main/kotlin/com/vscodroid/setup/ToolchainPickerAdapter.kt")
    private val toolbarLayout = File("src/main/res/layout/activity_toolchain.xml")

    /**
     * The body of one function, brace-matched from its signature.
     *
     * Brace counting rather than a line range: a range drifts the moment anything
     * above it grows, and a guard anchored to a line number stops guarding the
     * function it was written for without saying so.
     */
    private fun bodyOf(source: File, signature: String): String {
        assertTrue(
            source.isFile,
            "${source.path} is not at ${source.absolutePath}; this test would " +
                "otherwise pass by reading nothing",
        )
        val text = source.readText()
        val start = text.indexOf(signature)
        assertTrue(start >= 0, "no function matching `$signature` in ${source.name}")
        var depth = 0
        var i = text.indexOf('{', start)
        // Before the loop, not after it: at -1 the loop's first statement reads
        // text[-1] and the reader gets an index-out-of-bounds instead of the
        // sentence written for them below.
        assertTrue(i >= 0, "no body follows `$signature` in ${source.name}")
        val open = i
        // Braces inside comments do not count. A single `{` in a comment anywhere
        // in the function makes the depth never return to zero at the real closing
        // brace, so the body runs on into whatever follows. Measured on this file:
        // one `{` in a comment beside the checkable call widened the body from
        // 1877 characters to 5295, swallowing the next function whole. Scoping to
        // the owning function is the entire reason this helper exists rather than
        // a file-wide search, so a comment that silently unscopes it defeats the
        // guard exactly as a file-wide search would.
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
     * The control for [bodyOf], because a body that quietly ran past its function
     * would satisfy every assertion above by finding the token somewhere else.
     *
     * `bindManagerMode` is the declaration immediately after `bindPickerMode`, so
     * its name appearing inside the extracted body is precisely the failure this
     * guards, and its absence is what says the scope held.
     */
    @Test
    fun `the extracted body stops at the function it names`() {
        val body = bodyOf(adapter, "private fun bindPickerMode(")

        assertTrue(
            "bindManagerMode" !in body,
            "bodyOf ran past bindPickerMode and swallowed the next declaration, so " +
                "every assertion here is really a file-wide search: ${body.length} chars",
        )
    }

    /**
     * `MaterialCardView` implements `Checkable` and already forwards these into the
     * node and the change event. Nothing called them, so the only accessible trace
     * of selection was a checkmark whose label vanishes when deselected, and a
     * deselected card said nothing distinguishing at all.
     */
    @Test
    fun `picker binding sets both halves of the checked state`() {
        val body = bodyOf(adapter, "private fun bindPickerMode(")

        // Anchored to the start of a line, with only whitespace allowed in front.
        // A substring search finds the call inside `// card.isCheckable = true`
        // just as readily as the call itself, and commenting a line out is how a
        // developer disables something while debugging. Measured: with all three
        // of the calls below commented rather than deleted, every case here stayed
        // green while the wiring was entirely dead. The anchor also drops the
        // whitespace-exact literal this used to be, so `isCheckable=true` no
        // longer reddens it for a reformat that changed nothing.
        assertTrue(
            Regex("""(?m)^\s*card\.isCheckable\s*=\s*true""").containsMatchIn(body),
            "bindPickerMode does not make the card checkable, so it reports " +
                "checkable=false and a screen reader offers no state at all",
        )
        assertTrue(
            Regex("""(?m)^\s*card\.isChecked\s*=\s*isSelected""").containsMatchIn(body),
            "bindPickerMode does not tie isChecked to the selection, so the state " +
                "it reports is fixed and the card sounds the same either way",
        )
    }

    /**
     * The rebind has to carry a payload. Without one RecyclerView runs the default
     * change animation, which swaps the item view and takes accessibility focus
     * with it, so a user who just double-tapped a card is returned to the top of
     * the list and has to find their way back to hear what they changed.
     */
    @Test
    fun `the selection rebind carries a payload`() {
        val body = bodyOf(adapter, "private fun bindPickerMode(")
        // Line-anchored for the same reason as the checked-state case above: a
        // commented-out call is still a call to a substring search, and this one
        // counts calls, so a commented one would also inflate the count.
        val calls = Regex("""(?m)^\s*(notifyItemChanged\([^)]*\))""")
            .findAll(body).map { it.groupValues[1] }.toList()

        assertEquals(
            1, calls.size,
            "expected exactly one notifyItemChanged in bindPickerMode, found $calls",
        )
        assertTrue(
            calls[0].contains(","),
            "${calls[0]} passes no payload, so the default change animation runs and " +
                "drops accessibility focus off the card that was just tapped",
        )
    }

    /**
     * `setSupportActionBar` is never called anywhere in this app, so the framework's
     * own "Navigate up" default never applies and no toolbar style supplies one.
     * The attribute in the layout is the only thing standing between a screen reader
     * user and an unlabelled button where the only visible exit is.
     */
    @Test
    fun `the toolchains toolbar names its navigation icon`() {
        assertTrue(
            toolbarLayout.isFile,
            "${toolbarLayout.path} is missing; this test would otherwise pass by " +
                "reading nothing",
        )
        val xml = toolbarLayout.readText()

        // Anchored on the `=` rather than written as a substring. `app:navigationIcon`
        // is a prefix of `app:navigationIconTint`, which this toolbar also sets, so a
        // contains() check stays satisfied by the tint after the icon itself is gone.
        // Measured: deleting only `app:navigationIcon` left all four cases green while
        // the screen lost its one visible way out, and the label below went on
        // describing an icon that no longer existed.
        assertTrue(
            Regex("""app:navigationIcon\s*=""").containsMatchIn(xml),
            "the toolbar has no navigation icon at all, which makes the assertion " +
                "below vacuous rather than satisfied",
        )
        assertTrue(
            Regex("""app:navigationContentDescription="@string/\w+"""").containsMatchIn(xml),
            "the toolbar's navigation icon has no label, and nothing else supplies " +
                "one: setSupportActionBar is never called in this app",
        )
    }

    /**
     * The control for the assertion above, and the reason it names a resource rather
     * than accepting any value: a literal would satisfy the pattern while being
     * untranslatable, which is the shape lint's HardcodedText exists to refuse.
     */
    @Test
    fun `the navigation label is a resource, and that resource exists`() {
        val strings = File("src/main/res/values/strings.xml")
        assertTrue(
            toolbarLayout.isFile && strings.isFile,
            "${toolbarLayout.path} or ${strings.path} is missing; without this the " +
                "reader gets a FileNotFoundException instead of the sentence above",
        )
        val xml = toolbarLayout.readText()
        val name = Regex("""app:navigationContentDescription="@string/(\w+)"""")
            .find(xml)?.groupValues?.get(1)

        assertTrue(name != null, "the navigation label is not a string resource")
        assertTrue(
            strings.readText().contains("""name="$name""""),
            "activity_toolchain.xml names @string/$name, which strings.xml does not declare",
        )
    }
}
