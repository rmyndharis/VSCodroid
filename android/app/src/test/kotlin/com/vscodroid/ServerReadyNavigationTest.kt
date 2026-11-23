package com.vscodroid

import org.junit.jupiter.api.Assertions.assertEquals
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
 * runs `setupServiceCallbacks` and `handleResumeFromBackground` against a
 * mocked `NodeService`; it never reaches these two navigations, so a wrong
 * question asked here is invisible to it.
 *
 * `isReady()` is a cached flag rather than a probe, so reading it here costs
 * nothing and is safe on the main thread.
 */
class ServerReadyNavigationTest {

    private fun mainActivity(): String =
        SourceScan.withoutComments(
            SourceScan.readAll(
                listOf(
                    "src/main/kotlin/com/vscodroid/MainActivity.kt",
                    "src/main/kotlin/com/vscodroid/MainActivitySafCoordinator.kt",
                )
            )
        )

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
        val body = SourceScan.body(mainActivity(), "internal fun MainActivity.openSafFolder(")

        assertTrue(
            body.contains("isServerReady() == true"),
            "openSafFolder navigates on the port alone. Mirroring a device folder can " +
                "run for minutes, and a server that died during it leaves serverPort set " +
                "with nothing behind it, so the folder the user just picked opens onto a " +
                "connection-refused page",
        )
    }

    /**
     * And waiting is not dropping. The grant is taken and the mirror is copied by
     * the time readiness is asked about, so a guard that simply declines to
     * navigate throws away minutes of the user's copy and says nothing, which is
     * worse than the error page it avoids: an error page can be read. The first
     * version of this guard did exactly that.
     */
    @Test
    fun `a folder picked while the server is down is not thrown away`() {
        val body = SourceScan.body(mainActivity(), "internal fun MainActivity.openSafFolder(")
        val remembered = body.indexOf("rememberWorkspaceFolder(target)")

        assertTrue(
            remembered >= 0,
            "openSafFolder does not remember the folder it declined to open, so the " +
                "grant and the copy are spent and nothing will reopen it",
        )
        // Where the remembering sits, and not only that it is written somewhere. A
        // picked folder is handled inside the branch the `navigate` test opens, so a
        // port in that test skips everything for the screen that has not seen the
        // server answer yet: a folder picked across a process restart, whose result
        // arrives before the binding. It was synced and then neither opened nor
        // remembered, and the first load opened the folder remembered before it.
        val branch = Regex("""if \((navigate\b[^{]*)\) \{""").findAll(body)
            .lastOrNull { it.range.first < remembered }
        assertTrue(branch != null, "the branch that opens a picked folder is gone; this test reads nothing")
        assertEquals(
            "navigate", branch!!.groupValues[1].trim(),
            "a picked folder is handled only when a port is known, so one picked before " +
                "the server answered this screen is dropped: `if (${branch.groupValues[1]})`",
        )
        assertTrue(
            body.contains("if (serverPort > 0 && nodeService?.isServerReady() == true) {"),
            "a picked folder is navigated to without a port and a ready server behind it",
        )
        assertTrue(
            body.substring(remembered).contains("if (serverPort > 0) retryServerStart()"),
            "openSafFolder declines to navigate and does not ask the server back, so " +
                "the remembered folder waits for a readiness callback that is never " +
                "coming once the service has given up; with no port yet, that start is " +
                "already on its way and must not be restarted",
        )
    }
}
