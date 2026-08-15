package com.vscodroid.setup

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * A walkthrough step of ours may not complete on `onView:`.
 *
 * The Extensions step waited on `onView:extensions.listView`, and the workbench
 * registers no such view -- it has `workbench.views.extensions.installed`,
 * `.marketplace` and twenty-odd siblings, and nothing by that name. An unknown
 * view id is not an error: `registerDoneListeners` accepts the event, the
 * global `onDidChangeViewVisibility` listener never emits a matching string,
 * and the step simply stays unfinished forever.
 *
 * This refuses the event kind rather than checking the id, and that is the
 * whole point. Checking an id would need the built workbench bundle, which is
 * a gitignored artifact absent from a fresh clone and from every worktree, so
 * a checker for it could only ever write down a limit it cannot close -- a
 * documented hole the next person forgets. Removing the affordance makes the
 * limit irrelevant instead of permanently true.
 *
 * `onCommand:` is the replacement and it needs no build artifact to be worth
 * trusting: the command it names is the one the step's own button already
 * runs, so the two are edited together or not at all. It is also what the
 * workbench derives by itself for a step that declares no completionEvents.
 *
 * `onSettingChanged:` is untouched and stays allowed -- a setting key is a
 * different kind of name, and VS Code's own Get Started walkthrough completes
 * its theme step on exactly the string ours does.
 */
class WalkthroughCompletionEventTest {

    private val extensionsDir = File("src/main/assets/extensions")

    private fun ourManifests(): List<File> =
        extensionsDir.listFiles { f -> f.isDirectory && f.name.startsWith("vscodroid.vscodroid-") }
            ?.map { File(it, "package.json") }
            ?.filter { it.isFile }
            ?.sortedBy { it.path }
            ?: emptyList()

    @Test
    fun `no step of ours completes on a view id`() {
        val manifests = ourManifests()
        check(manifests.isNotEmpty()) {
            "No bundled manifests under ${extensionsDir.absolutePath} — the test is looking " +
                "in the wrong place, which would let it pass by finding nothing"
        }

        // Not org.json: every method on JSONObject throws "not mocked" on this
        // project's unit-test classpath. Same reason BundledExtensionHostTest
        // reads its manifests with a pattern.
        val found = mutableListOf<Pair<String, String>>()
        for (manifest in manifests) {
            val text = manifest.readText()
            for (block in COMPLETION_EVENTS.findAll(text)) {
                for (entry in ENTRY.findAll(block.groupValues[1])) {
                    found += manifest.parentFile.name to entry.groupValues[1]
                }
            }
        }

        // The positive control, and the one this test cannot do without. Every
        // assertion below reports success by finding nothing, so a manifest
        // layout this pattern stops matching turns the whole check into a scan
        // of zero events that passes. "Are there manifests" does not cover it.
        assertTrue(
            found.isNotEmpty(),
            "no completionEvents matched in any bundled manifest, so this test is checking " +
                "nothing. Either the manifests stopped declaring them or COMPLETION_EVENTS " +
                "no longer matches how they are written.",
        )

        val onView = found.filter { (_, event) -> event.startsWith("onView:") }

        assertEquals(
            emptyList<Pair<String, String>>(), onView,
            "a walkthrough step cannot complete on a view id here. Verifying one needs the " +
                "built workbench bundle, which is gitignored and absent from every worktree, " +
                "so a wrong id fails silently and the step stays unfinished forever. Complete " +
                "on onCommand: naming the command the step's own button runs — that is " +
                "checkable against the step itself, and it is what the workbench infers when " +
                "a step declares no completionEvents at all. Scanned ${found.size} events.",
        )
    }

    private companion object {
        /** The body of a `"completionEvents": [ ... ]` array, across newlines. */
        val COMPLETION_EVENTS =
            Regex(""""completionEvents"\s*:\s*\[(.*?)]""", RegexOption.DOT_MATCHES_ALL)

        /** One quoted entry inside that array. */
        val ENTRY = Regex(""""([^"]+)"""")
    }
}
