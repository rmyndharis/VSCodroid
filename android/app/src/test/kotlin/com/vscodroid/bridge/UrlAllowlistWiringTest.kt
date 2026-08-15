package com.vscodroid.bridge

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.vscodroid.util.Logger
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * That the bridge opens whatever URL it is given.
 *
 * This file used to pin the opposite. `SecurityManager.isAllowedUrl` permitted
 * https, mailto, and http only to localhost, and the cases here were a
 * host-confusion suite — `http://evil.com`, `http://localhost.evil.com`,
 * `http://127.0.0.1.evil.com`, `http://localhost@evil.com` — written to stop a
 * lookalike host slipping past. That was careful work against the wrong
 * requirement.
 *
 * VSCodroid is a development environment and everything has to be reachable. The
 * list refused the work the app exists for, and it did so asymmetrically, which
 * is how it was found: `http://192.168.1.50:5173` opened when followed as a link,
 * because `VSCodroidWebViewClient.shouldOverrideUrlLoading` has never filtered
 * anything, and was silently dropped when VS Code sent the same click through
 * `window.open`, which `MainActivity` relays to `openExternalUrl`. Same gesture,
 * same URL, two outcomes, one of them saying nothing at all.
 *
 * So the allow-list is gone rather than widened — a dead one is what somebody
 * re-wires later — and these cases pin its absence, including for the shapes it
 * used to refuse most confidently. If a destination filter is ever wanted again
 * it needs a product decision, and these tests are what will fail first to say
 * there was not one.
 *
 * What is still checked is the session token, which asks a different question:
 * whether the caller is our own page. `BridgeTokenUniformityTest` covers that
 * across every bridge method; the last case here pins it for this one, so that
 * "opens anything" cannot quietly become "opens anything for anyone".
 */
class UrlAllowlistWiringTest {

    private lateinit var context: Context
    private lateinit var security: SecurityManager
    private lateinit var bridge: AndroidBridge

    @BeforeEach
    fun setUp() {
        mockkObject(Logger)
        every { Logger.w(any(), any(), any()) } just Runs
        every { Logger.w(any(), any()) } just Runs
        every { Logger.i(any(), any()) } just Runs
        every { Logger.e(any(), any(), any()) } just Runs

        // Uri is a stub on the JVM. Parsed for real would be better; what matters
        // to these cases is which branch of openExternalUrl runs, and that turns
        // on scheme and host, so both are answered explicitly per URL.
        mockkStatic(Uri::class)

        // Intent is a stub too, and openExternalUrl builds one inside a try/catch:
        // without this the constructor throws, the catch swallows it, and
        // startActivity is never reached — which reads exactly like a URL being
        // refused. The first run of these cases failed that way, on a fixture
        // fault rather than on the code.
        mockkConstructor(Intent::class)
        every { anyConstructed<Intent>().addFlags(any()) } returns mockk(relaxed = true)

        context = mockk(relaxed = true)
        security = mockk(relaxed = true)
        every { security.validateToken(any()) } returns true

        bridge = AndroidBridge(
            context = context,
            security = security,
            clipboard = mockk(relaxed = true),
            onBackPressed = { false },
            onMinimize = { },
        )
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    private fun stubUri(url: String, scheme: String, host: String?) {
        val uri = mockk<Uri>(relaxed = true)
        every { uri.scheme } returns scheme
        every { uri.host } returns host
        every { Uri.parse(url) } returns uri
    }

    /**
     * The case the removal exists for. Plain http to a LAN address is what a dev
     * server looks like, and it was the shape most confidently refused.
     */
    @Test
    fun `a plain-http LAN dev server is opened`() {
        stubUri("http://192.168.1.50:5173", "http", "192.168.1.50")

        bridge.openExternalUrl("http://192.168.1.50:5173", "ok")

        verify(exactly = 1) { context.startActivity(any()) }
    }

    /**
     * The shapes the old suite refused hardest. They are listed by name rather
     * than as "anything", because a reader who finds this file needs to see that
     * their removal was deliberate and not an oversight in a rewrite.
     */
    @ParameterizedTest(name = "opens {0}")
    @ValueSource(strings = [
        "http://example.com",
        "http://evil.com",
        "http://localhost.evil.com",
        "http://127.0.0.1.evil.com",
        "ftp://server/file",
    ])
    fun `a URL the old allow-list refused is opened`(url: String) {
        stubUri(url, url.substringBefore(':'), "host.example")

        bridge.openExternalUrl(url, "ok")

        verify(exactly = 1) { context.startActivity(any()) }
    }

    /**
     * The one check that remains, and the reason "no allow-list" is not the same
     * sentence as "no gate". Without this, a later change that dropped the token
     * check too would pass everything above.
     */
    @Test
    fun `a rejected session token opens nothing`() {
        every { security.validateToken(any()) } returns false
        stubUri("https://github.com", "https", "github.com")

        bridge.openExternalUrl("https://github.com", "wrong")

        verify(exactly = 0) { context.startActivity(any()) }
    }
}
