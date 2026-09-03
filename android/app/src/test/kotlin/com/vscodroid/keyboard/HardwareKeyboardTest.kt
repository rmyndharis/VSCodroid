package com.vscodroid.keyboard

import com.vscodroid.SourceScan
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The two properties a physical keyboard's support rests on.
 *
 * Neither one is a feature anybody wrote, and that is the problem. Keys work
 * because nothing in this app takes a key event away from the page: the system
 * dispatches it, the WebView receives it unchanged, and the workbench binds it.
 * Three overrides stand in that path and none of them takes a key from the
 * page: the Activity delegates to `super` before widening the verdict on Escape
 * alone, and the two webview clients refuse only Escape, and only once the page
 * has already declined it, before handing every other key back. The first case
 * below pins each of those shapes by the ORDER of its statements, and refuses
 * any further override anywhere in the tree. The window survives a
 * keyboard being plugged in because the manifest claims the keyboard
 * configuration qualifiers, so Android hands the activity a configuration
 * change instead of destroying it and building a new one.
 *
 * A property with no code to point at has nothing to break when it stops
 * holding. The user guide recommends a Bluetooth keyboard for the best
 * experience with complex editing, and until these two tests there was nothing
 * anywhere that would have noticed that stopping being true.
 *
 * Both read files rather than behaviour, which is the weaker kind of test, so
 * each says below how it fails when the reading itself has stopped working: a
 * scan that has gone blind must go red, never green.
 *
 * What neither covers: a key swallowed some other way than an override, e.g.
 * `View.setOnKeyListener` on the WebView, and anything a real HID device does
 * that the dispatch path does not (pairing, layout mapping, its own modifier
 * reporting). Those stay with checklist KB-4, which needs hardware.
 */
class HardwareKeyboardTest {

    /**
     * Both source roots the build compiles Kotlin from, not just the populated
     * one. Every file lives under src/main/kotlin today, but src/main/java is
     * the other default root and is the location an IDE offers first when a new
     * class is added to an Android module. A `.kt` file landing there is
     * compiled into the app identically, so a scan of one root reports a clean
     * tree while the override is in the APK. The control below cannot reveal
     * that, because it looks for a file in the root that is read either way.
     */
    private val kotlinSources = listOf(File("src/main/kotlin"), File("src/main/java"))

    private val manifest = File("src/main/AndroidManifest.xml")

    /** A declaration's body with its comments blanked; see [SourceScan]. */
    private fun bodyOf(path: String, declaration: String): String =
        SourceScan.withoutComments(SourceScan.body(SourceScan.read(path), declaration))

    /** Every override of [names] under [kotlinSources], as `path:line: text`. */
    private fun overridesOf(vararg names: String): List<String> {
        val declaration = Regex("""\boverride\s+fun\s+(${names.joinToString("|")})\s*\(""")
        return kotlinSources.asSequence()
            .flatMap { it.walkTopDown() }
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                file.readLines().withIndex()
                    .filterNot { (_, line) ->
                        // Prose naming a callback is not an override of it, and
                        // the reason this app has none is worth writing down
                        // somewhere without tripping the guard that keeps it so.
                        val start = line.trimStart()
                        start.startsWith("//") || start.startsWith("*") || start.startsWith("/*")
                    }
                    .filter { (_, line) -> declaration.containsMatchIn(line) }
                    .map { (i, line) -> "${file.path}:${i + 1}: ${line.trim()}" }
            }
            .toList()
    }

    @Test
    fun `no key event is intercepted before the WebView sees it`() {
        check(kotlinSources.any { it.isDirectory }) {
            "no Kotlin sources at ${kotlinSources.map { it.absolutePath }}: this test " +
                "would otherwise pass by looking at nothing"
        }

        // The control, and it has to come first, because this guard is the kind
        // that passes by finding nothing. A clean tree and a scan that has
        // stopped recognising `override fun` produce the identical empty list.
        // What separates them is the one input callback this app does override:
        // if the same scan cannot find GestureTrackpad's onTouchEvent, its
        // verdict on key events below is worth nothing.
        val touch = overridesOf("onTouchEvent")
        assertTrue(
            touch.any { it.contains("GestureTrackpad.kt") },
            "the scan found no override of onTouchEvent, so it is not reading these " +
                "sources and cannot be trusted to have found a key-event override " +
                "either. It returned: $touch",
        )

        val interceptors = overridesOf(
            "dispatchKeyEvent",
            "onKeyDown",
            "onKeyUp",
            "onKeyLongPress",
            "onKeyMultiple",
            "onKeyPreIme",
            "onKeyShortcut",
            // Not a View callback like the rest, and the one that would be easy
            // to miss for that reason. WebViewClient.shouldOverrideKeyEvent is
            // asked before the page is given the key, and returning true keeps
            // it, so it swallows a keystroke exactly as the others do.
            "shouldOverrideKeyEvent",
            // Where an unconsumed key is handed back to the platform, and the only
            // place the fallback can still be stopped. The default implementation
            // re-injects the event, and the re-injected copy is queued with
            // FLAG_UNHANDLED, which the framework routes to the stage that reads
            // the keyboard's fallback action INSTEAD of to the view tree, so
            // nothing above it can intervene. An override here is therefore both
            // the fix below and, left unguarded, a way to swallow every unhandled
            // key in silence.
            "onUnhandledKeyEvent",
        )

        // The three overrides this app has, each pinned by file and by name rather
        // than dropped from the list above: dropping a name would stop guarding it
        // in every other file, which is most of what this case is for.
        //
        // They earn the exception between them. A key nothing consumed is offered
        // to the producing keyboard's own character map for a fallback action, and
        // some maps still read `ESCAPE base: fallback BACK`, which arrives at the
        // back callback as an ordinary press and minimises the app mid-keystroke.
        // There are two routes to that and they do not meet: the Activity closes
        // the one where the view tree declined the key, and the two clients close
        // the one the workbench takes, which never reaches the Activity at all.
        val activity = "src/main/kotlin/com/vscodroid/MainActivity.kt"
        val client = "src/main/kotlin/com/vscodroid/webview/VSCodroidWebViewClient.kt"
        val expected = listOf(
            activity to "dispatchKeyEvent",
            // The bootstrap client, which owns the whole cold start and every
            // window after a renderer crash.
            activity to "onUnhandledKeyEvent",
            client to "onUnhandledKeyEvent",
        )

        fun sitesFor(file: String, name: String) =
            interceptors.filter { it.startsWith("$file:") && it.contains("fun $name(") }

        val allowed = expected.flatMap { (file, name) -> sitesFor(file, name) }

        for ((file, name) in expected) {
            assertEquals(
                1, sitesFor(file, name).size,
                "$file no longer overrides $name exactly once. Between them these three " +
                    "are what stop a keyboard whose character map carries " +
                    "`ESCAPE base: fallback BACK` from minimising the app when Esc is " +
                    "pressed, and they are not interchangeable: the re-injected event the " +
                    "clients see never reaches the Activity, and an Escape the view tree " +
                    "declined never reaches a client. Overrides found: $interceptors",
            )
        }

        // Pinned by ORDER, not by presence, and the two methods need opposite
        // orders. A pair of `contains` checks on the Activity is satisfied by
        // `if (keyCode == KEYCODE_ESCAPE) return true` followed by a `super` call,
        // which is exactly the swallow the message below warns about.
        val dispatch = bodyOf(activity, "override fun dispatchKeyEvent(")
        val delegatesAt = dispatch.indexOf("super.dispatchKeyEvent(event)")
        val escapeAt = dispatch.indexOf("KEYCODE_ESCAPE")

        assertTrue(delegatesAt >= 0 && escapeAt > delegatesAt) {
            "MainActivity.dispatchKeyEvent must delegate BEFORE it decides anything " +
                "about Escape, so that the WebView receives the key exactly as " +
                "dispatched and only the verdict reported back to the platform widens. " +
                "Deciding first swallows the key and the workbench never sees it. " +
                "Found super at $delegatesAt and the Escape test at $escapeAt in: $dispatch"
        }

        for (file in listOf(activity, client)) {
            val unhandled = bodyOf(file, "override fun onUnhandledKeyEvent(")
            val refusesAt = unhandled.indexOf("KEYCODE_ESCAPE")
            val handsBackAt = unhandled.indexOf("super.onUnhandledKeyEvent(")

            assertTrue(refusesAt >= 0 && handsBackAt > refusesAt) {
                "$file must refuse Escape BEFORE handing the event back, and must still " +
                    "hand every other key back. Losing the refusal restores the fallback " +
                    "that minimises the app; losing the hand-back swallows every other " +
                    "unhandled key, which is what the ban list above exists to prevent. " +
                    "Found the Escape test at $refusesAt and super at $handsBackAt " +
                    "in: $unhandled"
            }
        }

        assertEquals(
            emptyList<String>(), interceptors - allowed.toSet(),
            "Hardware keyboard support here IS the absence of these overrides. Every " +
                "keystroke reaches the WebView exactly as the system dispatched it, " +
                "and the workbench binds it; an override is a chance to swallow one " +
                "before the editor sees it. Modifier state added here would also be " +
                "applied twice for anyone typing on a physical keyboard while the " +
                "extra key row's Ctrl or Alt is latched, since the row's modifiers " +
                "are already applied in the page by KeyInjector.setupModifierInterceptor. " +
                "Handling a key in Kotlin is a deliberate decision to be re-tested " +
                "against a real keyboard (checklist KB-4), not something to arrive at " +
                "by adding an override.",
        )
    }

    /** `android:name` to `android:configChanges` for every activity in the manifest. */
    private fun activityConfigChanges(): Map<String, String> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(manifest)
        val activities = document.getElementsByTagName("activity")
        return (0 until activities.length)
            .map { activities.item(it) as Element }
            .associate { it.getAttribute("android:name") to it.getAttribute("android:configChanges") }
    }

    @Test
    fun `attaching a keyboard does not tear the window down`() {
        check(manifest.isFile) {
            "AndroidManifest.xml not found at ${manifest.absolutePath}: this test " +
                "would otherwise pass by looking at nothing"
        }

        // The two that claim the qualifiers today, and the two where being
        // rebuilt costs something: the editor's WebView lives in one, first-run
        // extraction runs in the other.
        val guarded = listOf(".SplashActivity", ".MainActivity")
        val declared = activityConfigChanges()

        // The control. Unlike the scan above, this guard fails closed on its own
        // instrument: an attribute it cannot read comes back empty and is
        // reported as a missing qualifier. The one way it could still pass by
        // reading nothing is if the parse found no activities at all, so that is
        // what this asserts.
        assertTrue(
            declared.keys.containsAll(guarded),
            "the manifest parse did not find $guarded, so it is not reading the " +
                "activity declarations. It found: ${declared.keys}",
        )

        // Rotation is included rather than left to the message. It is the same
        // attribute, it costs the same teardown, and a message that says to keep
        // the rest of the string while checking none of it is an invitation to
        // trim the attribute down to what this test reads.
        // uiMode joins them for the same reason and a worse consequence. It is the
        // only qualifier outside this list that fires with nobody touching the
        // device: a sunset-to-sunrise dark schedule, and battery saver's forced
        // dark, both flip it mid-session. In MainActivity that used to move the
        // user out of their workspace outright, because the rebuilt WebView
        // carries only the data: placeholder and loadVSCode fell through to the
        // default projects directory; the folder is remembered now, so what a
        // relaunch costs is the reload rather than the workspace, and the reason
        // to declare the attribute is the teardown itself.
        // In SplashActivity it restarts the whole first-run
        // extraction, because runSetup() lives in lifecycleScope and the relaunch
        // cancels it before markSetupComplete() runs.
        //
        // Declaring it is only safe while almost nothing on those two screens
        // resolves by night, and the manifest comment states that condition
        // precisely, including the one exception it originally missed. This list
        // is what fails if the declaration is removed, not if the condition stops
        // holding, and no test can see the second half.
        val required = listOf("keyboard", "keyboardHidden", "orientation", "screenSize", "uiMode")
        val unguarded = guarded.filterNot { name ->
            declared[name].orEmpty().split("|").containsAll(required)
        }

        assertEquals(
            emptyList<String>(), unguarded,
            "Plugging in or unplugging a keyboard is a configuration change, and so is " +
                "turning the phone. Without $required in android:configChanges, Android " +
                "answers one by destroying the activity and creating a new one, which " +
                "takes the WebView with it: the open workspace, its editors and " +
                "whatever was not yet saved all reload the moment a Bluetooth " +
                "keyboard connects or the screen rotates. Declared: $declared.",
        )
    }
}
