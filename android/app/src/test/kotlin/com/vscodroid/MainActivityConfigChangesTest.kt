package com.vscodroid

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The configuration changes the editor absorbs instead of being made again for.
 *
 * Made again, the editor reloads its page, so a change the screen does not need to redraw for is
 * absorbed. Attaching or removing an input device that has a D-pad (a game controller, a remote, a
 * keyboard with a select key) moves the navigation type between none and D-pad, which the
 * activity was not declared to absorb: on an API 36 emulator a keyboard with a select key
 * recreated the editor when it was attached and again when it was removed, and each time the page
 * loaded again. A keyboard without one changes only `keyboard`, which was already absorbed.
 */
class MainActivityConfigChangesTest {

    private fun declared(activity: String): Set<String> {
        val manifest = File("src/main/AndroidManifest.xml")
        assertTrue(manifest.isFile, "${manifest.absolutePath} is missing")
        val document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(manifest)
        val activities = document.getElementsByTagName("activity")
        for (i in 0 until activities.length) {
            val element = activities.item(i) as org.w3c.dom.Element
            if (element.getAttributeNS("http://schemas.android.com/apk/res/android", "name") == activity) {
                return element.getAttributeNS("http://schemas.android.com/apk/res/android", "configChanges")
                    .split('|').filter { it.isNotBlank() }.toSet()
            }
        }
        throw AssertionError("$activity is not declared in the manifest")
    }

    @Test
    fun `the editor absorbs a change of navigation type`() {
        assertTrue(
            "navigation" in declared(".MainActivity"),
            "a device with a D-pad being attached or removed recreates the editor, which reloads its page",
        )
    }

    @Test
    fun `the editor still absorbs what it absorbed`() {
        assertEquals(
            setOf(
                "orientation", "screenSize", "keyboard", "keyboardHidden", "navigation",
                "screenLayout", "smallestScreenSize", "uiMode", "density",
            ),
            declared(".MainActivity"),
        )
    }
}
