package com.vscodroid

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for [isWorkbenchUrl], which decides whether a finished page load was the
 * workbench or a page this app drew itself.
 *
 * `onPageFinished` answers a different question, "a main-frame load finished",
 * and the two differ exactly where it costs something. The loading page and the
 * server-gave-up page are `loadData` documents with no origin, and treating
 * either as the workbench does two things: it writes the session token into a
 * page with no bridge to spend it, and it raises the flag that tells an arriving
 * OAuth callback there is a workbench to land in. The callback then lands in a
 * page that cannot consume it, and the user is told nothing.
 *
 * The port is half the test rather than decoration. A loopback listener is
 * something any app on the device can open without a permission, so the host
 * alone does not tie a page to this server.
 *
 * The helper parses with `java.net.URI` for exactly this reason: `Uri.parse` is
 * a framework method that answers null off-device, so a test of a version built
 * on it would pass every case without deciding anything.
 */
class WorkbenchPageTest {

    private val port = 41234

    @Test
    fun `a page the server served is the workbench`() {
        assertTrue(isWorkbenchUrl("http://127.0.0.1:41234/?folder=%2Fhome%2Fp&tkn=abc", port))
    }

    @Test
    fun `localhost by name counts too`() {
        assertTrue(isWorkbenchUrl("http://localhost:41234/", port))
    }

    @Test
    fun `the pages this app draws itself do not`() {
        // What loadData and loadDataWithBaseURL produce: the loading page and
        // the server-gave-up page, which is the one that mattered.
        assertFalse(isWorkbenchUrl("about:blank", port))
        assertFalse(isWorkbenchUrl("data:text/html;charset=utf-8,%3Chtml%3E", port))
    }

    @Test
    fun `another loopback listener is not this server`() {
        // Binding a loopback port on Android needs no permission, so the host on
        // its own says nothing about who answered.
        assertFalse(isWorkbenchUrl("http://127.0.0.1:8080/", port))
    }

    @Test
    fun `a remote host is not the workbench however it is spelled`() {
        assertFalse(isWorkbenchUrl("https://example.com:41234/", port))
    }

    @Test
    fun `nothing loaded and no server are both false`() {
        assertFalse(isWorkbenchUrl(null, port))
        assertFalse(isWorkbenchUrl("http://127.0.0.1:41234/", 0))
    }
}
