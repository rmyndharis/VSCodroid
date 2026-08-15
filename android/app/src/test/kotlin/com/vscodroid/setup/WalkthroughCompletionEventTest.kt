package com.vscodroid.setup

import org.json.JSONObject
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
 * `onCommand:` is the replacement, and it is worth being exact about what that
 * buys, because it is not "the name is verified". `workbench.view.extensions`
 * is as unverifiable from this classpath as the view id it replaced -- both
 * are workbench names that only the built bundle knows. What changes is that
 * the name is now written twice, in the step's completionEvent and in the
 * step's own button, and the second test below compares them. A typo rots one
 * half and fails; the view id had no second half to disagree with. It is also
 * what the workbench derives by itself for a step declaring no completionEvents.
 *
 * A target in our own `vscodroid.` namespace is the exception: that one IS
 * fully checkable here, against the manifests' `contributes.commands`, and the
 * third test does check it.
 *
 * `onSettingChanged:` is untouched and stays allowed -- a setting key is a
 * different kind of name, and VS Code's own Get Started walkthrough completes
 * its theme step on exactly the string ours does.
 *
 * The manifests are parsed with `org.json`, which `build.gradle.kts` puts on
 * the test classpath for exactly this. An earlier version of this file read
 * them with a regex and said in a comment that org.json throws "not mocked"
 * here -- copied from a neighbouring test, true when that neighbour was
 * written, and false since the real parser was added. The regex it justified
 * had a matching bug the parser cannot have: it ended a `completionEvents`
 * array at the first `]`, so an event containing one truncated the array and
 * the events after it went unread.
 */
class WalkthroughCompletionEventTest {

    private val extensionsDir = File("src/main/assets/extensions")

    private fun ourManifests(): List<File> =
        extensionsDir.listFiles { f -> f.isDirectory && f.name.startsWith("vscodroid.vscodroid-") }
            ?.map { File(it, "package.json") }
            ?.filter { it.isFile }
            ?.sortedBy { it.path }
            ?: emptyList()

    /** One walkthrough step: where it lives, what it links to, how it completes. */
    private data class Step(
        val where: String,
        val commandLinks: Set<String>,
        val events: List<String>,
    )

    private fun ourSteps(): List<Step> {
        val found = mutableListOf<Step>()
        for (manifest in ourManifests()) {
            val walkthroughs = JSONObject(manifest.readText())
                .optJSONObject("contributes")
                ?.optJSONArray("walkthroughs")
                ?: continue
            for (w in 0 until walkthroughs.length()) {
                val walkthrough = walkthroughs.getJSONObject(w)
                val steps = walkthrough.optJSONArray("steps") ?: continue
                for (s in 0 until steps.length()) {
                    val step = steps.getJSONObject(s)
                    val events = step.optJSONArray("completionEvents")
                    found += Step(
                        where = "${manifest.parentFile.name} step '${step.optString("id", "?")}'",
                        commandLinks = COMMAND_LINK
                            .findAll(step.optString("description", ""))
                            .map { it.groupValues[1] }
                            .toSet(),
                        events = (0 until (events?.length() ?: 0)).map { events!!.getString(it) },
                    )
                }
            }
        }
        return found
    }

    @Test
    fun `no step of ours completes on a view id`() {
        val manifests = ourManifests()
        check(manifests.isNotEmpty()) {
            "No bundled manifests under ${extensionsDir.absolutePath} — the test is looking " +
                "in the wrong place, which would let it pass by finding nothing"
        }

        val found = ourSteps().flatMap { step -> step.events.map { step.where to it } }

        // The positive control, and the one this test cannot do without. The
        // assertion below reports success by finding nothing, so a manifest that
        // stopped declaring completionEvents -- or a walkthrough moved under a
        // key this no longer walks -- would turn the whole check into a scan of
        // zero events that passes. "Are there manifests" does not cover it.
        assertTrue(
            found.isNotEmpty(),
            "no completionEvents were read from any bundled manifest, so this test is checking " +
                "nothing. Either the manifests stopped declaring them, or they moved somewhere " +
                "ourSteps() no longer walks.",
        )

        val onView = found.filter { (_, event) -> event.startsWith("onView:") }

        assertEquals(
            emptyList<Pair<String, String>>(), onView,
            "a walkthrough step cannot complete on a view id here. Verifying one needs the " +
                "built workbench bundle, which is gitignored and absent from every worktree, " +
                "so a wrong id fails silently and the step stays unfinished forever. Complete " +
                "on onCommand: naming the command the step's own button runs — that is " +
                "checkable against the step itself, and it is what the workbench infers when " +
                "a step declares no completionEvents at all. Read ${found.size} events.",
        )
    }

    /**
     * The claim the rule above rests on, enforced rather than asserted in prose.
     *
     * `onCommand:` is preferred over `onView:` because the command it names is
     * the one the step's own button runs, so the two cannot drift apart. That
     * is only true while something checks it. Nothing did: a single letter --
     * `workbench.view.extension` against a button reading
     * `workbench.view.extensions` -- would leave the step exactly as
     * unfinishable as the view id it replaced, and the rule above would pass it.
     *
     * This forbids a step that completes on a command it does not offer. VS
     * Code allows one; it would complete when the user happened to run that
     * command from the palette, and no step of ours is built that way. If one
     * ever should be, this fails loudly with the id in the message and the
     * choice can be made deliberately, which is the point.
     */
    @Test
    fun `a step completing on a command offers that command`() {
        val steps = ourSteps()
        check(steps.isNotEmpty()) { "no walkthrough steps found — nothing was checked" }

        val commandEvents = steps.flatMap { step ->
            step.events.filter { it.startsWith(COMMAND_EVENT) }.map { step to it }
        }

        // Without this the assertion below is satisfied by a manifest whose
        // steps complete on nothing at all, or by a description format these
        // links stop being found in.
        assertTrue(
            commandEvents.isNotEmpty(),
            "no onCommand: completion events were found, so this check is inspecting nothing",
        )

        val orphans = commandEvents
            .filter { (step, event) -> event.removePrefix(COMMAND_EVENT) !in step.commandLinks }
            .map { (step, event) -> "${step.where}: $event" }

        assertEquals(
            emptyList<String>(), orphans,
            "these steps complete on a command their own description does not offer, so the " +
                "button and the completion event can no longer be wrong together — which is " +
                "the only reason onCommand: is trusted here. Point the event at the command " +
                "the step's button runs, or give the step a button that runs it.",
        )
    }

    /**
     * The half that IS verifiable without the workbench bundle.
     *
     * A step completing on `vscodroid.something` names a command this repo
     * contributes, so a typo in it is a local, checkable fact -- unlike
     * `workbench.view.extensions`, which no test here can confirm exists. The
     * check above only proves the event and the button agree; two halves can
     * agree on a name that was never registered, and the step then stays
     * unfinishable exactly as if it had completed on a bad view id.
     */
    @Test
    fun `a step completing on one of our commands names a command we contribute`() {
        val contributed = ourManifests().flatMap { manifest ->
            val commands = JSONObject(manifest.readText())
                .optJSONObject("contributes")
                ?.optJSONArray("commands")
                ?: return@flatMap emptyList<String>()
            (0 until commands.length()).map { commands.getJSONObject(it).getString("command") }
        }.toSet()

        val ours = ourSteps().flatMap { step ->
            step.events
                .filter { it.startsWith(COMMAND_EVENT) }
                .map { it.removePrefix(COMMAND_EVENT) }
                .filter { it.startsWith(OUR_NAMESPACE) }
                .map { step.where to it }
        }

        // Reporting success by finding nothing is this check's failure mode too:
        // if no step completes on a command of ours, the assertion below is
        // satisfied by an empty list and says so.
        assertTrue(
            ours.isNotEmpty(),
            "no step completes on a command in the $OUR_NAMESPACE namespace, so this check " +
                "inspected nothing. Read ${contributed.size} contributed commands.",
        )

        val unregistered = ours
            .filter { (_, command) -> command !in contributed }
            .map { (where, command) -> "$where: $command" }

        assertEquals(
            emptyList<String>(), unregistered,
            "these steps complete on a command of ours that no bundled manifest contributes, " +
                "so the event can never fire and the step stays unfinished forever. Contributed: " +
                contributed.sorted().joinToString(", "),
        )
    }

    private companion object {
        /**
         * `[Browse Extensions](command:workbench.view.extensions)` in a step description.
         *
         * Whitespace ends the id as surely as `)` does: markdown allows
         * `(command:x "title")`, and without `\s` in the class the title came
         * back as part of the command name -- so the id would never match its
         * completionEvent and the check would fail on a step that is correct.
         * The same class kept a runaway match from crossing a newline.
         */
        val COMMAND_LINK = Regex("""\(command:([^\s)?]+)""")
        const val COMMAND_EVENT = "onCommand:"
        const val OUR_NAMESPACE = "vscodroid."
    }
}
