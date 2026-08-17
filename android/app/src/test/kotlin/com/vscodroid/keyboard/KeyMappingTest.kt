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
        @ValueSource(strings = [
            "{", "}", "(", ")", ":", "\"", "|", "~", "!", "#", "@", "&", "_", "<", ">",
            "+", "*", "%", "?", "^", "$"
        ])
        fun `shift-required characters have requiresShift true`(key: String) {
            val keyDef = KeyMapping.getKeyDef(key)
            assertNotNull(keyDef, "KeyDef for '$key' should exist")
            assertTrue(keyDef!!.requiresShift, "'$key' should require shift")
        }

        @ParameterizedTest(name = "non-shift key: {0}")
        @ValueSource(strings = [";", "/", "[", "]", "\\", "`", "'", "=", ",", ".", "-"])
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
        fun `a shifted symbol shares its physical key with the unshifted one`() {
            // The test above cannot see a wrong code or keyCode -- it asserts they
            // are non-empty and positive, which every wrong value also is. Only
            // Tab, Escape, Enter and Space have theirs pinned, so the other 26
            // entries rest on this.
            //
            // These six are one key each on a US layout, which is why KeyInjector
            // sends the same code and keyCode for both and lets requiresShift pick
            // the character. Giving '{' the pair belonging to '(' -- Digit9, 57 --
            // makes the extra key row type '(' when the user presses '{', and every
            // test in this file stays green.
            // Every pair in the table where both halves are present, not the six
            // that were visible in the first forty lines of it. The original list
            // stopped at '<' because that is where the read stopped, which left
            // five pairs asserting nothing -- including '?' and '_', both of
            // which the extra key row sends.
            val pairs = listOf(
                "{" to "[", "}" to "]", ":" to ";", "\"" to "'", "|" to "\\", "~" to "`",
                "<" to ",", ">" to ".", "_" to "-", "+" to "=", "?" to "/"
            )
            for ((shifted, plain) in pairs) {
                val s = KeyMapping.getKeyDef(shifted)
                val p = KeyMapping.getKeyDef(plain)
                assertNotNull(s, "KeyDef for '$shifted' should exist")
                assertNotNull(p, "KeyDef for '$plain' should exist")
                assertEquals(
                    p!!.code, s!!.code,
                    "'$shifted' and '$plain' are the same physical key, so code must match"
                )
                assertEquals(
                    p.keyCode, s.keyCode,
                    "'$shifted' and '$plain' are the same physical key, so keyCode must match"
                )
                assertTrue(s.requiresShift, "'$shifted' is the shifted half of the pair")
                assertFalse(p.requiresShift, "'$plain' is the unshifted half of the pair")
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

    @Nested
    inner class FunctionAndNavigationKeyTest {

        /**
         * An absent entry is not a dead key. [KeyMapping.getKeyDefOrLetter]
         * derives the fields from the first character, so a missing "F2" is sent
         * as `KeyF` with keyCode 70 and types an F into the file, and "Home" is
         * sent as `KeyH`. Only the real code and keyCode reach a binding.
         */
        @ParameterizedTest(name = "F{0} carries its own code and keyCode")
        @ValueSource(ints = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12])
        fun `function keys carry the function key code and keyCode`(n: Int) {
            val def = KeyMapping.getKeyDef("F$n")
            assertNotNull(def, "'F$n' has no definition, so it falls back to the letter heuristic")
            assertEquals("F$n", def!!.code, "'F$n' must carry its own physical code")
            assertEquals(111 + n, def.keyCode, "F1 to F12 are keyCodes 112 to 123")
            assertFalse(def.requiresShift, "'F$n' is typed without Shift")
        }

        @Test
        fun `navigation keys carry their own code and keyCode`() {
            val expected = mapOf("Home" to 36, "End" to 35, "PageUp" to 33, "PageDown" to 34)
            for ((key, keyCode) in expected) {
                val def = KeyMapping.getKeyDef(key)
                assertNotNull(def, "'$key' has no definition, so it falls back to the letter heuristic")
                assertEquals(key, def!!.code, "'$key' must carry its own physical code")
                assertEquals(keyCode, def.keyCode, "'$key' has one keyCode and it is not negotiable")
                assertFalse(def.requiresShift, "'$key' is typed without Shift")
            }
        }
    }

    @Nested
    inner class PunctuationCoverageTest {

        /**
         * A comma reaches VS Code only if the event carries `Comma`/188. Deriving the
         * fields from the character gives `''`/44, which matches no binding, so Ctrl+,
         * did nothing. This is the specific case behind that.
         */
        @Test
        fun `comma maps to Comma and 188 without shift`() {
            val def = KeyMapping.getKeyDef(",")
            assertNotNull(def, "',' must be mapped")
            assertEquals("Comma", def!!.code)
            assertEquals(188, def.keyCode)
            assertFalse(def.requiresShift, "',' is unshifted; '<' is the shifted one")
        }

        @Test
        fun `comma and less-than share a physical key but differ in shift`() {
            val comma = KeyMapping.getKeyDef(",")!!
            val lessThan = KeyMapping.getKeyDef("<")!!
            assertEquals(comma.code, lessThan.code, "same physical key")
            assertEquals(comma.keyCode, lessThan.keyCode)
            assertFalse(comma.requiresShift)
            assertTrue(lessThan.requiresShift)
        }

        @ParameterizedTest(name = "every US-layout punctuation character is mapped: {0}")
        @ValueSource(strings = [
            "`", "~", "!", "@", "#", "$", "%", "^", "&", "*", "(", ")",
            "-", "_", "=", "+", "[", "{", "]", "}", "\\", "|",
            ";", ":", "'", "\"", ",", "<", ".", ">", "/", "?"
        ])
        fun `every printable US punctuation character has a definition`(key: String) {
            val def = KeyMapping.getKeyDef(key)
            assertNotNull(def, "'$key' has no definition, so it falls back to the letter heuristic")
            assertTrue(def!!.code.isNotEmpty(), "'$key' needs a physical code")
            assertTrue(def.keyCode > 0, "'$key' needs a keyCode")
        }

        @ParameterizedTest(name = "keyCode is the unshifted key's code, not the character's: {0}")
        @ValueSource(strings = ["!", "@", "#", "$", "%", "^", "&", "*", "(", ")"])
        fun `shifted digit symbols carry the digit keyCode`(key: String) {
            val def = KeyMapping.getKeyDef(key)!!
            assertTrue(def.requiresShift, "'$key' is typed with Shift")
            assertTrue(def.code.startsWith("Digit"), "'$key' sits on a digit key, got ${def.code}")
            assertEquals(
                def.code.removePrefix("Digit").first().code,
                def.keyCode,
                "keyCode must be the digit's code, not '$key'.code"
            )
        }
    }

    @Nested
    inner class JsLookupTest {

        @Test
        fun `is a single object literal`() {
            val lookup = KeyMapping.toJsLookup()
            assertTrue(lookup.startsWith("{"), "must open as an object")
            assertTrue(lookup.endsWith("}"), "must close as an object")
        }

        @Test
        fun `carries one entry per mapping`() {
            val lookup = KeyMapping.toJsLookup()
            // Every entry contributes exactly one `:[`, so this counts entries without
            // needing to parse, and moves when the table does.
            val entries = Regex(":\\[").findAll(lookup).count()
            val known = listOf(
                "Tab", "Escape", "ArrowLeft", "ArrowUp", "ArrowRight", "ArrowDown",
                "Home", "End", "PageUp", "PageDown",
                "F1", "F2", "F3", "F4", "F5", "F6", "F7", "F8", "F9", "F10", "F11", "F12",
                "{", "}", "(", ")", ";", ":", "\"", "/", "[", "]", "|", "\\", "~", "`",
                "'", "=", "!", "#", "@", "&", "_", "<", ">", ",", ".", "-", "+", "*",
                "%", "?", "^", "$", "Enter", "Backspace", " "
            )
            assertEquals(known.size, entries, "one entry per mapping")
            for (key in known) {
                assertNotNull(KeyMapping.getKeyDef(key), "'$key' should be in the table")
            }
        }

        @ParameterizedTest(name = "lookup carries the definition for: {0}")
        @ValueSource(strings = [",", ".", "-", "+", "*", "%", "?", "^", ";", "/", "="])
        fun `lookup value matches the KeyDef`(key: String) {
            val def = KeyMapping.getKeyDef(key)!!
            val expected = "\"$key\":[\"${def.code}\",${def.keyCode},${if (def.requiresShift) 1 else 0}]"
            assertTrue(
                KeyMapping.toJsLookup().contains(expected),
                "lookup should contain $expected"
            )
        }

        /**
         * `"` and `\` are both keys in the table. Unescaped, either one ends the JS
         * string it sits in and the whole object becomes a syntax error, which would
         * take the interceptor down with it rather than degrade.
         */
        @Test
        fun `escapes the quote and backslash keys`() {
            val lookup = KeyMapping.toJsLookup()
            assertTrue(lookup.contains("\"\\\"\":[\"Quote\""), "the '\"' key must be escaped")
            assertTrue(lookup.contains("\"\\\\\":[\"Backslash\""), "the '\\' key must be escaped")
        }

        /**
         * Scans the way a parser would, honouring escapes. An unescaped `"` inside a
         * key ends that string early, which shifts every string boundary after it —
         * so the count is what catches it, not the appearance of any one entry.
         */
        @Test
        fun `every key and code is a properly terminated string`() {
            val lookup = KeyMapping.toJsLookup()
            var i = 0
            var strings = 0
            while (i < lookup.length) {
                if (lookup[i] == '"') {
                    i++
                    while (i < lookup.length && lookup[i] != '"') {
                        if (lookup[i] == '\\') i++
                        i++
                    }
                    assertTrue(i < lookup.length, "unterminated string after $strings strings")
                    strings++
                }
                i++
            }
            val entries = Regex(":\\[").findAll(lookup).count()
            assertEquals(2 * entries, strings, "each entry is exactly one key and one code")
        }
    }
}
