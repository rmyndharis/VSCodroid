package com.vscodroid.bridge

import com.vscodroid.util.Logger
import io.mockk.every
import io.mockk.just
import io.mockk.mockkObject
import io.mockk.Runs
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * Tests for [SecurityManager] — token generation/validation and the URL allowlist.
 */
class SecurityManagerTest {

    private lateinit var manager: SecurityManager

    @BeforeEach
    fun setUp() {
        // Mock Logger to avoid android.util.Log crashes in JVM tests
        mockkObject(Logger)
        every { Logger.w(any(), any(), any()) } just Runs
        every { Logger.i(any(), any()) } just Runs
        every { Logger.d(any(), any()) } just Runs
        every { Logger.e(any(), any(), any()) } just Runs
        manager = SecurityManager()
    }

    /** mockkObject replaces the singleton process-wide; see BridgeTokenUniformityTest.tearDown. */
    @AfterEach
    fun tearDown() = unmockkAll()

    // ── Token Generation ─────────────────────────────────────────────────

    @Nested
    inner class TokenGenerationTest {

        @Test
        fun `generates a token with more than one distinct character`() {
            // `assertNotNull` stood here and could not fail. getSessionToken()
            // returns a non-null Kotlin type, so the only production change it
            // refused was one that threw before any test ran, and a generator
            // emitting one character 64 times satisfied it exactly as readily as
            // a random one. Length and alphabet are pinned next door and accept
            // that value too, since "aaaa..." is 64 lowercase hex characters.
            //
            // The floor, then, is that the value varies within itself at all.
            // How far it varies is SessionTokenEntropyTest's question.
            val token = manager.getSessionToken()
            assertTrue(
                token.toSet().size > 1,
                "the token is one character repeated, so it is a constant: $token",
            )
        }

        @Test
        fun `generates 64-character hex token`() {
            val token = manager.getSessionToken()
            assertEquals(64, token.length, "Token should be 64 hex chars (32 bytes)")
            assertTrue(token.matches(Regex("[0-9a-f]{64}")), "Token should be lowercase hex")
        }

        @Test
        fun `returns same token on repeated calls`() {
            val first = manager.getSessionToken()
            val second = manager.getSessionToken()
            assertEquals(first, second, "Session token should be stable within same instance")
        }

        @Test
        fun `different instances generate different tokens`() {
            val other = SecurityManager()
            assertNotEquals(
                manager.getSessionToken(),
                other.getSessionToken(),
                "Different SecurityManager instances should have unique tokens"
            )
        }
    }

    // ── Token Validation ─────────────────────────────────────────────────

    @Nested
    inner class TokenValidationTest {

        @Test
        fun `validates correct token`() {
            assertTrue(manager.validateToken(manager.getSessionToken()))
        }

        @Test
        fun `rejects empty token`() {
            assertFalse(manager.validateToken(""))
        }

        @Test
        fun `rejects wrong token`() {
            assertFalse(manager.validateToken("0000000000000000000000000000000000000000000000000000000000000000"))
        }

        @Test
        fun `rejects token with different case`() {
            val token = manager.getSessionToken()
            val upper = token.uppercase()

            // Uppercasing a lowercase-hex token changes it unless all 64 nibbles came
            // out as digits. The guard that used to stand here skipped the assertion in
            // that case -- and would have skipped it just as quietly if the generator
            // stopped producing letters at all, which is a real entropy regression:
            // narrowing the alphabet to digits leaves `generates 64-character hex token`
            // green, because digits match [0-9a-f] too. Assert the precondition instead
            // of hiding it.
            assertNotEquals(token, upper, "token carries no hex letters, so its alphabet narrowed")
            assertFalse(manager.validateToken(upper))
        }
    }

    // The URL allow-list that stood here is gone, deliberately, and so are its
    // cases. `SecurityManager` no longer decides anything about destinations —
    // see the note where the method was, and `ExternalUrlHandoffTest` for what
    // the app does with a URL now.
}
