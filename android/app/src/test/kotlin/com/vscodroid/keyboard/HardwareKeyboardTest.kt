package com.vscodroid.keyboard

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
 * because nothing in this app touches a key event: the system dispatches it,
 * the WebView receives it unchanged, and the workbench binds it. The window
 * survives a keyboard being plugged in because the manifest claims the keyboard
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

    private val kotlinSources = File("src/main/kotlin")

    private val manifest = File("src/main/AndroidManifest.xml")

    /** Every override of [names] under src/main/kotlin, as `path:line: text`. */
    private fun overridesOf(vararg names: String): List<String> {
        val declaration = Regex("""\boverride\s+fun\s+(${names.joinToString("|")})\s*\(""")
        return kotlinSources.walkTopDown()
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
        check(kotlinSources.isDirectory) {
            "no Kotlin sources at ${kotlinSources.absolutePath}: this test would " +
                "otherwise pass by looking at nothing"
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
        )

        assertEquals(
            emptyList<String>(), interceptors,
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

        val unguarded = guarded.filterNot { name ->
            val qualifiers = declared[name].orEmpty().split("|")
            "keyboard" in qualifiers && "keyboardHidden" in qualifiers
        }

        assertEquals(
            emptyList<String>(), unguarded,
            "Plugging in or unplugging a keyboard is a configuration change. Without " +
                "`keyboard` and `keyboardHidden` in android:configChanges, Android " +
                "answers it by destroying the activity and creating a new one, which " +
                "takes the WebView with it: the open workspace, its editors and " +
                "whatever was not yet saved all reload the moment a Bluetooth " +
                "keyboard connects. Keep the rest of the attribute too; the same " +
                "string is what stops a rotation doing the same thing.",
        )
    }
}
