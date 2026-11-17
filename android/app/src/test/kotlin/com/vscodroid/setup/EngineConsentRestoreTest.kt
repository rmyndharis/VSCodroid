package com.vscodroid.setup

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * `vscodroid.antigravity.runEngine` is consent given for the apps on one phone,
 * and the machine settings file it lives in travels with a backup and a move to
 * a new phone. Setup on an install no build has completed setup on, which is
 * what a restore is, turns it off and leaves the rest of the file alone.
 */
class EngineConsentRestoreTest {
    private val key = "vscodroid.antigravity.runEngine"

    @Test
    fun `the setting on in the file the app writes is turned off and nothing else moves`() {
        val before = "{\n    // what the app wrote\n    \"git.path\": \"/x/libgit.so\",\n" +
            "    \"$key\": true,\n    \"update.mode\": \"none\"\n}\n"
        assertEquals(before.replace("\"$key\": true", "\"$key\": false"), withEngineConsentWithdrawn(before))
    }

    @Test
    fun `every spelling VS Code reads as the top-level key is turned off`() {
        for ((label, before, after) in listOf(
            Triple("a single line", "{\"$key\": true}", "{\"$key\": false}"),
            Triple("a byte order mark", "﻿{\"$key\": true}", "﻿{\"$key\": false}"),
            Triple("CRLF", "{\r\n\t\"$key\": true\r\n}\r\n", "{\r\n\t\"$key\": false\r\n}\r\n"),
            Triple(
                "an escaped dot",
                "{\n    \"vscodroid.antigravity\\u002erunEngine\": true\n}\n",
                "{\n    \"vscodroid.antigravity\\u002erunEngine\": false\n}\n",
            ),
            Triple(
                "comments around the value",
                "{ /* x */\n    \"$key\" /* y */ : /* z */ true // on\n}\n",
                "{ /* x */\n    \"$key\" /* y */ : /* z */ false // on\n}\n",
            ),
            Triple(
                "the key twice, so the last is in force",
                "{\n    \"$key\": true,\n    \"a\": \"/*\",\n    \"$key\": true\n}\n",
                "{\n    \"$key\": false,\n    \"a\": \"/*\",\n    \"$key\": false\n}\n",
            ),
        )) {
            assertEquals(after, withEngineConsentWithdrawn(before), label)
        }
    }

    @Test
    fun `what VS Code does not read as the setting is left as it is`() {
        for ((label, text) in listOf(
            "off" to "{\n    \"$key\": false\n}\n",
            "absent" to "{\n    \"update.mode\": \"none\"\n}\n",
            "in a line comment" to "{\n    // \"$key\": true,\n    \"update.mode\": \"none\"\n}\n",
            "in a block comment" to "{\n    /* \"$key\": true */\n}\n",
            "inside another object" to "{\n    \"some.object\": {\n        \"$key\": true\n    }\n}\n",
            "inside a language block" to "{\n    \"[python]\": {\n        \"$key\": true\n    }\n}\n",
            "quoted inside another value" to "{\n    \"note\": \"\\\"$key\\\": true\"\n}\n",
            "not an object" to "[{\"$key\": true}]\n",
        )) {
            assertNull(withEngineConsentWithdrawn(text), label)
        }
    }

    @Test
    fun `setup withdraws it only where no build has completed setup`() {
        val source = File("src/main/kotlin/com/vscodroid/setup/FirstRunSetup.kt")
        check(source.isFile) { "FirstRunSetup.kt not found at ${source.absolutePath}" }
        val text = source.readText()
        val defaults = text.indexOf("            createDefaultSettings()\n")
        assertTrue(defaults >= 0, "runSetupLocked no longer writes the default settings where this looks")
        val next = text.indexOf("\n            reportProgress(", defaults)
        assertTrue(
            text.substring(defaults, next).contains("if (nothingCompletedHere) withdrawRestoredEngineConsent()"),
            "setup does not turn the restored consent off after it writes the settings",
        )
    }
}
