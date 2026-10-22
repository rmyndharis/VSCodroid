package com.vscodroid.setup

import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A named profile's list of the app's own extensions, across an update.
 *
 * A profile keeps a manifest of its own, and one made by copying the default profile lists
 * the copy of each of our extensions that was current then. An update replaced the default
 * profile's entries and left these, because a directory another profile lists is never
 * swept; the profile then ran the old bridge and the old welcome page for good, without the
 * Run button or any later fix, and the old directory stayed on disk beside the new one.
 * Our extensions are the app and not the user's choice, so for them the entry follows the
 * update, and the old directory is free to go. Everything else a profile lists is left as
 * it is: an older copy of an extension the user installed is theirs to keep.
 */
class ProfileOwnExtensionRepointTest {

    private val extensions = "/data/user/0/com.vscodroid/files/home/.vscodroid/extensions"
    private val oldBridge = "vscodroid.vscodroid-saf-bridge-1.11.0"
    private val newBridge = "vscodroid.vscodroid-saf-bridge-1.12.0"
    private val oldWelcome = "vscodroid.vscodroid-welcome-1.8.1"
    private val newWelcome = "vscodroid.vscodroid-welcome-1.9.0"
    private val oldPython = "ms-python.python-2026.3.0"
    private val newPython = "ms-python.python-2026.4.0"
    private val bundled = listOf(newBridge, newWelcome, newPython)
    private val superseded = listOf(oldBridge, oldWelcome, oldPython)

    private fun entry(dir: String, version: String, id: String, relative: Boolean = true): JSONObject =
        JSONObject()
            .put("identifier", JSONObject().put("id", id))
            .put("version", version)
            .put("location", JSONObject().put("\$mid", 1).put("path", "$extensions/$dir").put("scheme", "file"))
            .put("metadata", JSONObject().put("source", "bundled").put("isBuiltin", true).put("pinned", true))
            .also { if (relative) it.put("relativeLocation", dir) }

    private fun manifest(vararg entries: JSONObject): String =
        JSONArray().apply { entries.forEach { put(it) } }.toString()

    private fun repoint(text: String, superseded: List<String> = this.superseded, bundled: List<String> = this.bundled) =
        repointOwnExtensionEntries(text, superseded, bundled, extensions)

    private fun entriesOf(text: String?): List<JSONObject> {
        val array = JSONArray(checkNotNull(text) { "the manifest was not rewritten" })
        return (0 until array.length()).map { array.getJSONObject(it) }
    }

    @Test
    fun `an entry for a superseded copy of our extension follows the update`() {
        val result = repoint(manifest(entry(oldBridge, "1.11.0", "vscodroid.vscodroid-saf-bridge")))

        val bridge = entriesOf(result).single()
        assertEquals(newBridge, bridge.getString("relativeLocation"))
        assertEquals("1.12.0", bridge.getString("version"))
        assertEquals("$extensions/$newBridge", bridge.getJSONObject("location").getString("path"))
    }

    @Test
    fun `what the profile recorded beside the location is kept`() {
        val result = repoint(manifest(entry(oldBridge, "1.11.0", "vscodroid.vscodroid-saf-bridge")))

        val bridge = entriesOf(result).single()
        assertEquals("vscodroid.vscodroid-saf-bridge", bridge.getJSONObject("identifier").getString("id"))
        assertEquals("file", bridge.getJSONObject("location").getString("scheme"))
        assertEquals(1, bridge.getJSONObject("location").getInt("\$mid"))
        assertTrue(bridge.getJSONObject("metadata").getBoolean("pinned"), "the profile's own metadata was rewritten")
        assertTrue(bridge.getJSONObject("metadata").getBoolean("isBuiltin"))
    }

    @Test
    fun `an entry that names its directory by path alone keeps that shape`() {
        val result = repoint(manifest(entry(oldWelcome, "1.8.1", "vscodroid.vscodroid-welcome", relative = false)))

        val welcome = entriesOf(result).single()
        assertFalse(welcome.has("relativeLocation"), "a key the profile never wrote was added")
        assertEquals("$extensions/$newWelcome", welcome.getJSONObject("location").getString("path"))
        assertEquals("1.9.0", welcome.getString("version"))
    }

    @Test
    fun `a copy of someone else's extension an older version of which the profile lists is left`() {
        val text = manifest(entry(oldPython, "2026.3.0", "ms-python.python"))

        assertNull(repoint(text), "the user's older copy was moved to the bundled one")
    }

    @Test
    fun `only our own entries move when the profile lists both kinds`() {
        val result = repoint(
            manifest(
                entry(oldPython, "2026.3.0", "ms-python.python"),
                entry(oldBridge, "1.11.0", "vscodroid.vscodroid-saf-bridge"),
            ),
        )

        val listed = entriesOf(result)
        assertEquals(listOf(oldPython, newBridge), listed.map { it.getString("relativeLocation") })
    }

    @Test
    fun `an entry already at the current copy is left alone`() {
        val text = manifest(entry(newBridge, "1.12.0", "vscodroid.vscodroid-saf-bridge"))

        assertNull(repoint(text))
    }

    @Test
    fun `a profile that already lists the new copy loses the old entry rather than listing it twice`() {
        val result = repoint(
            manifest(
                entry(oldBridge, "1.11.0", "vscodroid.vscodroid-saf-bridge"),
                entry(newBridge, "1.12.0", "vscodroid.vscodroid-saf-bridge"),
            ),
        )

        assertEquals(listOf(newBridge), entriesOf(result).map { it.getString("relativeLocation") })
    }

    @Test
    fun `an own extension this build no longer ships is not pointed anywhere`() {
        val text = manifest(entry("vscodroid.vscodroid-retired-1.0.0", "1.0.0", "vscodroid.vscodroid-retired"))

        assertNull(repoint(text, superseded = listOf("vscodroid.vscodroid-retired-1.0.0")))
    }

    @Test
    fun `a superseded copy is only followed to a copy that is there`() {
        val text = manifest(entry(oldBridge, "1.11.0", "vscodroid.vscodroid-saf-bridge"))

        assertNull(repoint(text, bundled = listOf(newWelcome, newPython)), "the entry was pointed at a directory not on disk")
    }

    @Test
    fun `nothing superseded means no rewrite`() {
        val text = manifest(entry(oldBridge, "1.11.0", "vscodroid.vscodroid-saf-bridge"))

        assertNull(repoint(text, superseded = emptyList()))
    }

    @Test
    fun `text that is not a manifest is never rewritten`() {
        assertNull(repoint("[{truncated"))
        assertNull(repoint(""))
        assertNull(repoint("{\"not\":\"an array\"}"))
    }

    @Test
    fun `entries the reader cannot place are carried over untouched`() {
        val odd = JSONObject().put("something", "else")
        val text = JSONArray().put(odd).put(entry(oldBridge, "1.11.0", "vscodroid.vscodroid-saf-bridge")).toString()

        val result = entriesOf(repoint(text))

        assertEquals(2, result.size)
        assertEquals("else", result[0].getString("something"))
        assertEquals(newBridge, result[1].getString("relativeLocation"))
    }

    @Test
    fun `entries are kept in the order the profile has them`() {
        val result = repoint(
            manifest(
                entry(oldWelcome, "1.8.1", "vscodroid.vscodroid-welcome"),
                entry(oldPython, "2026.3.0", "ms-python.python"),
                entry(oldBridge, "1.11.0", "vscodroid.vscodroid-saf-bridge"),
            ),
        )

        assertEquals(
            listOf(newWelcome, oldPython, newBridge),
            entriesOf(result).map { it.getString("relativeLocation") },
        )
    }
}
