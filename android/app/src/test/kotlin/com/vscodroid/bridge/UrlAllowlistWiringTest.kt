package com.vscodroid.bridge

import com.vscodroid.util.Logger
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockkObject
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * The `http://` branch of [SecurityManager.isAllowedUrl] — the one the method exists for,
 * and the only part of it that is a trust decision — never ran in a test. `Uri.parse`
 * throws on the JVM, the bare `catch` swallowed that, and the method fell through to
 * `return false`. Every "blocks X" case was passing through the catch, so the host
 * comparison could be rewritten to allow `http://evil.com` with the whole suite green.
 *
 * The other half of the gap: nothing asserted that `http://localhost:PORT` **is**
 * allowed. A change that rejected everything would also have passed — and quietly broken
 * previewing a dev server, which is what the branch is for.
 *
 * These tests run the real parser. Both directions are covered: what must be let
 * through, and what must not.
 */
class UrlAllowlistWiringTest {

    private val manager = SecurityManager(allowedPort = 13337)

    @BeforeEach
    fun silenceLogger() {
        // Same reason as SecurityManagerTest: android.util.Log is a stub on the JVM.
        mockkObject(Logger)
        every { Logger.w(any(), any(), any()) } just Runs
        every { Logger.i(any(), any()) } just Runs
    }

    // ── must be allowed ──────────────────────────────────────────────────

    @ParameterizedTest(name = "allows {0}")
    @ValueSource(strings = [
        "http://localhost:3000",
        "http://localhost:5173/path?query=1",
        "http://127.0.0.1:8080",
        "http://localhost",
        "http://127.0.0.1",
    ])
    fun `local dev servers are allowed over plain http`(url: String) {
        assertTrue(
            manager.isAllowedUrl(url),
            "$url is a local dev server; rejecting it breaks previewing your own app"
        )
    }

    @Test
    fun `https and mailto stay allowed`() {
        assertTrue(manager.isAllowedUrl("https://github.com"))
        assertTrue(manager.isAllowedUrl("mailto:someone@example.com"))
    }

    // ── must not be allowed ──────────────────────────────────────────────

    @ParameterizedTest(name = "blocks {0}")
    @ValueSource(strings = [
        "http://evil.com",
        "http://evil.com/localhost",
        // Suffix and prefix tricks: the comparison has to be on the whole host.
        "http://localhost.evil.com",
        "http://notlocalhost",
        "http://127.0.0.1.evil.com",
        // Credentials put the real destination after the @.
        "http://localhost@evil.com",
        // Not http at all.
        "javascript:alert(1)",
        "file:///etc/passwd",
        "content://com.android.externalstorage/document",
        "intent://scan/#Intent;scheme=zxing;end",
    ])
    fun `everything else is blocked`(url: String) {
        assertFalse(manager.isAllowedUrl(url), "$url must not reach the browser")
    }

    @Test
    fun `a malformed url is rejected rather than thrown out of`() {
        assertFalse(manager.isAllowedUrl("http://[not a url"))
        assertFalse(manager.isAllowedUrl(""))
        assertFalse(manager.isAllowedUrl("http://"))
    }

    @Test
    fun `a url whose host the parser cannot read is refused`() {
        // `%61` is `a`, so this spells localhost — and the parser returns a null host
        // for it without throwing. The check asks whether the host IS allowed, so null
        // fails to match. Phrased as "reject if there is a host and it is not allowed",
        // this would be let through.
        assertFalse(manager.isAllowedUrl("http://loc%61lhost:3000"))
    }

    @Test
    fun `a url with no scheme is refused even when the host looks local`() {
        assertFalse(manager.isAllowedUrl("//localhost:3000"))
    }

    @Test
    fun `ipv6 loopback is refused because the allow-list names two hosts`() {
        // Documented rather than fixed: the parser yields `[::1]` with brackets, and
        // the allow-list is two literal names. This is the behaviour before and after
        // the change; the test exists so a future edit to the list is a deliberate one.
        assertFalse(manager.isAllowedUrl("http://[::1]:3000"))
    }

    @Test
    fun `the host comparison is what decides, not the scheme prefix`() {
        // Guards the mutation that made the issue visible: replacing the host test with
        // one that is true for everything leaves the suite green unless something
        // asserts a non-local http host is refused.
        assertFalse(manager.isAllowedUrl("http://example.com"))
        assertTrue(manager.isAllowedUrl("http://localhost:1234"))
    }
}
