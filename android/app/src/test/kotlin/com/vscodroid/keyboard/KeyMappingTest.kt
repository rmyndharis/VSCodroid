package com.vscodroid.keyboard

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * Tests for [KeyMapping] — keyboard key definitions and fallback logic.
 */
class KeyMappingTest {

    @Nested
    inner class GetKeyDefTest {

        @ParameterizedTest(name = "known key: {0}")
        @ValueSource(strings = ["Tab", "Escape", "ArrowLeft", "ArrowUp", "ArrowRight", "ArrowDown", "Enter", "Backspace", " "])
        fun `returns KeyDef for known special keys`(key: String) {
            val keyDef = KeyMapping.getKeyDef(key)
            assertNotNull(keyDef, "KeyDef for '$key' should not be null")
            assertEquals(key, keyDef!!.key, "KeyDef.key should match input")
        }

        @ParameterizedTest(name = "shift-required key: {0}")
        @ValueSource(strings = ["{", "}", "(", ")", ":", "\"", "|", "~", "!", "#", "@", "&", "_", "<", ">"])
        fun `shift-required characters have requiresShift true`(key: String) {
            val keyDef = KeyMapping.getKeyDef(key)
            assertNotNull(keyDef, "KeyDef for '$key' should exist")
            assertTrue(keyDef!!.requiresShift, "'$key' should require shift")
        }

        @ParameterizedTest(name = "non-shift key: {0}")
        @ValueSource(strings = [";", "/", "[", "]", "\\", "`", "'", "="])
        fun `non-shift characters have requiresShift false`(key: String) {
            val keyDef = KeyMapping.getKeyDef(key)
            assertNotNull(keyDef, "KeyDef for '$key' should exist")
            assertFalse(keyDef!!.requiresShift, "'$key' should NOT require shift")
        }

        @Test
        fun `returns null for unknown keys`() {
            assertNull(KeyMapping.getKeyDef("UnknownKey"))
            assertNull(KeyMapping.getKeyDef("F13"))
            assertNull(KeyMapping.getKeyDef(""))
        }
    }

    @Nested
    inner class GetKeyDefOrLetterTest {

        @Test
        fun `returns mapped KeyDef for known keys`() {
            val tabDef = KeyMapping.getKeyDefOrLetter("Tab")
            assertEquals("Tab", tabDef.key)
            assertEquals("Tab", tabDef.code)
            assertEquals(9, tabDef.keyCode)
        }

        @Test
        fun `falls back to letter KeyDef for single lowercase letter`() {
            val keyDef = KeyMapping.getKeyDefOrLetter("a")
            assertEquals("a", keyDef.key)
            assertEquals("KeyA", keyDef.code)
            assertEquals('A'.code, keyDef.keyCode, "keyCode should be uppercase char code")
        }

        @Test
        fun `falls back to letter KeyDef for single uppercase letter`() {
            val keyDef = KeyMapping.getKeyDefOrLetter("Z")
            assertEquals("Z", keyDef.key)
            assertEquals("KeyZ", keyDef.code)
            assertEquals('Z'.code, keyDef.keyCode)
        }

        @ParameterizedTest(name = "letter fallback generates correct keyCode for: {0}")
        @ValueSource(strings = ["a", "b", "c", "x", "y", "z"])
        fun `letter fallback generates correct keyCode`(letter: String) {
            val keyDef = KeyMapping.getKeyDefOrLetter(letter)
            val expectedCode = letter.first().uppercaseChar().code
            assertEquals(expectedCode, keyDef.keyCode)
            assertEquals("Key${letter.first().uppercaseChar()}", keyDef.code)
        }
    }

    @Nested
    inner class MappingIntegrityTest {

        @Test
        fun `all mappings have non-empty key and code`() {
            // Test via known keys that should exist
            val expectedKeys = listOf(
                "Tab", "Escape", "Enter", "Backspace", " ",
                "{", "}", "(", ")", ";", ":", "/", "[", "]",
                "|", "\\", "~", "`", "'", "=", "!", "#", "@", "&", "_"
            )
            for (key in expectedKeys) {
                val keyDef = KeyMapping.getKeyDef(key)
                assertNotNull(keyDef, "KeyDef for '$key' should exist")
                assertTrue(keyDef!!.key.isNotEmpty(), "key should not be empty for '$key'")
                assertTrue(keyDef.code.isNotEmpty(), "code should not be empty for '$key'")
                assertTrue(keyDef.keyCode > 0, "keyCode should be positive for '$key'")
            }
        }

        @Test
        fun `Tab has correct keyCode 9`() {
            assertEquals(9, KeyMapping.getKeyDef("Tab")!!.keyCode)
        }

        @Test
        fun `Escape has correct keyCode 27`() {
            assertEquals(27, KeyMapping.getKeyDef("Escape")!!.keyCode)
        }

        @Test
        fun `Enter has correct keyCode 13`() {
            assertEquals(13, KeyMapping.getKeyDef("Enter")!!.keyCode)
        }

        @Test
        fun `Space has correct keyCode 32`() {
            assertEquals(32, KeyMapping.getKeyDef(" ")!!.keyCode)
        }
    }
}
