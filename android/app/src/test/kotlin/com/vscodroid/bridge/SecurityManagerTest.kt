package com.vscodroid.bridge

import com.vscodroid.util.Logger
import io.mockk.every
import io.mockk.just
import io.mockk.mockkObject
import io.mockk.Runs
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
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

    // ── Token Generation ─────────────────────────────────────────────────

    @Nested
    inner class TokenGenerationTest {

        @Test
        fun `generates non-null token`() {
            assertNotNull(manager.getSessionToken())
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
            val upper = manager.getSessionToken().uppercase()
            // Token is generated as lowercase hex, uppercase should fail
            if (upper != manager.getSessionToken()) {
                assertFalse(manager.validateToken(upper))
            }
        }
    }

    // ── URL Allowlist ────────────────────────────────────────────────────

    @Nested
    inner class UrlAllowlistTest {

        @ParameterizedTest(name = "should allow URL: {0}")
        @ValueSource(strings = [
            "https://github.com",
            "https://open-vsx.org/extension/...",
            "https://example.com/path?query=1",
            "mailto:user@example.com"
        ])
        fun `allows https and mailto URLs`(url: String) {
            assertTrue(manager.isAllowedUrl(url), "URL should be allowed: $url")
        }

        @ParameterizedTest(name = "should block URL: {0}")
        @ValueSource(strings = [
            "http://example.com",
            "javascript:alert(1)",
            "file:///etc/passwd",
            "ftp://server/file",
            "data:text/html,<h1>hi</h1>",
            "intent://scan/#Intent;scheme=zxing;end"
        ])
        fun `blocks non-https non-mailto URLs`(url: String) {
            assertFalse(manager.isAllowedUrl(url), "URL should be blocked: $url")
        }
    }
}
