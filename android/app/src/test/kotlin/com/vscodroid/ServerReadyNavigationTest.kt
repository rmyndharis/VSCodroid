package com.vscodroid

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * That the two recovery navigations ask whether the server is READY, not merely
 * whether a port was once allocated.
 *
 * `serverPort` is written in `onServerReady` and in the late-binding decision and
 * is never cleared, so it stays non-zero through a whole crash loop and past
 * `NodeService.enterTerminalState`. The two questions therefore differ for the
 * seconds of every restart and for ever after a server that gave up.
 *
 * The cost of asking the wrong one is not a slow load, it is a dead end.
 * `onReceivedError` only logs, so a connection-refused page is never cleared, and
 * on the renderer-crash path that page replaces the gave-up page, which carries
 * the only control able to start the server again. `ServerReadinessCallSiteTest`
 * covers `isServerRunning` having been removed; it cannot see a call site that
 * asks a different wrong question, which its own KDoc says in as many words.
 *
 * `isReady()` is a cached flag rather than a probe, so reading it here costs
 * nothing and is safe on the main thread.
 */
class ServerReadyNavigationTest {

    private fun mainActivity(): String =
        SourceScan.withoutComments(SourceScan.read("src/main/kotlin/com/vscodroid/MainActivity.kt"))

    @Test
    fun `a renderer-crash reload waits for readiness`() {
        val body = SourceScan.body(mainActivity(), "private fun recreateWebView(")

        assertTrue(
            body.contains("isServerReady() == true"),
            "recreateWebView navigates on the port alone. After the server has given " +
                "up, serverPort is still set, so the new WebView is pointed at a socket " +
                "nothing is listening on and the gave-up page -- the only place with a " +
                "Retry control -- is replaced by a connection-refused page nothing clears",
        )
        assertTrue(
            body.contains("retryServerStart()"),
            "recreateWebView has no branch for a server that is not ready. Showing " +
                "nothing leaves the crashed renderer's blank page; retryServerStart is " +
                "a no-op for a server merely starting and the only revival for one that " +
                "has stopped",
        )
    }

    @Test
    fun `opening a device folder waits for readiness`() {
        val body = SourceScan.body(mainActivity(), "private fun openSafFolder(")

        assertTrue(
            body.contains("isServerReady() == true"),
            "openSafFolder navigates on the port alone. Mirroring a device folder can " +
                "run for minutes, and a server that died during it leaves serverPort set " +
                "with nothing behind it, so the folder the user just picked opens onto a " +
                "connection-refused page",
        )
    }
}
