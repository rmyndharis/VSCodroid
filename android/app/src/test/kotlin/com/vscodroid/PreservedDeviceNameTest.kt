package com.vscodroid

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class PreservedDeviceNameTest {
    @Test
    fun `a short preserved name is unchanged`() {
        val name = "notes.txt.device-1700000060000"
        assertEquals(name, preservedDeviceNameForNotice(name))
    }

    @Test
    fun `one name and a batch count use different notices`() {
        val source = SourceScan.read("src/main/kotlin/com/vscodroid/MainActivity.kt")
        val listener = SourceScan.withoutComments(
            SourceScan.body(source, "safManager.onDeviceCopiesPreserved {"),
        )
        assertTrue(listener.contains("preservedDeviceNameForNotice(copies.single().name)"))
        assertTrue(listener.contains("R.plurals.saf_device_copies_preserved, copies.size, copies.size"))
        assertTrue(listener.contains("Toast.LENGTH_LONG"))
    }

    @Test
    fun `a long name keeps its marker and time without splitting a surrogate`() {
        val suffix = ".device-1700000060000"
        val name = "\uD83D\uDE00".repeat(60) + suffix
        val shown = preservedDeviceNameForNotice(name)
        assertTrue(shown.startsWith("\uD83D\uDE00"))
        assertTrue(shown.endsWith("...$suffix"))
        assertTrue(shown.codePointCount(0, shown.length) <= 32)
        assertEquals(-1, shown.indexOf('\uFFFD'))
        assertTrue(shown.codePoints().allMatch { it !in 0xD800..0xDFFF })
    }

    @Test
    fun `a short local preserved name is unchanged and a long one keeps its marker and time`() {
        val shortName = "notes.txt.local-1700000060000"
        assertEquals(shortName, preservedLocalNameForNotice(shortName))

        val suffix = ".local-1700000060000"
        val longName = "\uD83D\uDE00".repeat(60) + suffix
        val shown = preservedLocalNameForNotice(longName)
        assertTrue(shown.startsWith("\uD83D\uDE00"))
        assertTrue(shown.endsWith("...$suffix"))
        assertTrue(shown.codePointCount(0, shown.length) <= 32)
        assertEquals(-1, shown.indexOf('\uFFFD'))
        assertTrue(shown.codePoints().allMatch { it !in 0xD800..0xDFFF })
    }

    @Test
    fun `one local name and a batch count use different notices`() {
        val source = SourceScan.read("src/main/kotlin/com/vscodroid/MainActivity.kt")
        val listener = SourceScan.withoutComments(
            SourceScan.body(source, "safManager.onLocalCopiesPreserved {"),
        )
        assertTrue(listener.contains("preservedLocalNameForNotice(copies.single().name)"))
        assertTrue(listener.contains("R.string.saf_local_copy_preserved"))
        assertTrue(listener.contains("getQuantityString"))
        assertTrue(listener.contains("R.plurals.saf_local_copies_preserved"))
        assertTrue(listener.contains("Toast.LENGTH_LONG"))
    }

    @Test
    fun `every locale translates both local copy notice resources`() {
        val res = File("src/main/res")
        val files = res.listFiles { f -> f.isDirectory && f.name.startsWith("values") }
            .orEmpty().map { File(it, "strings.xml") }.filter { it.isFile }
        assertTrue(files.size >= 14, "expected default and thirteen locales, found ${files.size}")
        for (file in files) {
            val text = file.readText()
            // The placeholder is read from this string's own element: anywhere else in the file would do for a
            // text.contains, and a translation that left it out would pass.
            val single = Regex("""<string name="saf_local_copy_preserved">([^<]*)</string>""").find(text)?.groupValues?.get(1)
            assertTrue(
                single != null && single.contains("%1\$s"),
                "${file.parentFile?.name} is missing saf_local_copy_preserved with %1\$s in that string",
            )
            val block = text.substringAfter("""<plurals name="saf_local_copies_preserved">""", "")
                .substringBefore("</plurals>", "")
            assertTrue(block.isNotBlank(), "${file.parentFile?.name} is missing saf_local_copies_preserved plurals")
            val items = Regex("""<item quantity="[^"]+">([^<]+)</item>""").findAll(block).toList()
            assertTrue(items.isNotEmpty(), "${file.parentFile?.name} has no plural items for saf_local_copies_preserved")
            assertTrue(
                items.all { "%1\$d" in it.groupValues[1] },
                "${file.parentFile?.name} is missing %1\$d in at least one saf_local_copies_preserved item",
            )
        }
    }
}
