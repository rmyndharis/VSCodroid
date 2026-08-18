package com.vscodroid

import com.vscodroid.service.StartupNotice
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The two decisions `MainActivity` makes about a server that may not be serving.
 *
 * [ServerReadinessCallSiteTest] beside this one reads the source and checks which
 * *method* is called. That catches one regression — putting `isServerRunning`
 * back — and cannot catch the other: calling the right method and then ignoring
 * what it said. Both call sites were mutated that way and the whole suite stayed
 * green, because a source-reading test sees the token and not the branch.
 *
 * These are the branches, as values. A mutation that keeps the call and drops the
 * verdict has to change one of these functions to compile away, and changing
 * either one fails here.
 *
 * What each protects, if it stops holding:
 *
 *  - [bindDecision] runs when an activity binds to a service that is already up.
 *    A server that became ready before the activity bound never fires
 *    `onServerReady` at it, so the state is asked for rather than waited on. Load
 *    on a port that is not listening and the user gets a connection-refused page;
 *    `onReceivedError` only logs, so nothing takes it away.
 *  - [shouldActOnResume] runs when the app comes back from the background. The
 *    same page is reloaded into the same port, so the same failure applies.
 */
class ServerReadinessDecisionTest {

    // -- bindDecision --

    @Test
    fun `a serving server on a real port is loaded`() {
        assertEquals(
            BindDecision.Load(13337),
            bindDecision(notice = null, port = 13337, ready = true),
        )
    }

    @Test
    fun `a server that is not serving is waited for, not loaded`() {
        // The mutation this exists for: keep asking isServerReady(), discard the
        // answer, load on `port > 0` alone. That is exactly a caller which passes
        // ready=false and expects Load.
        assertEquals(
            BindDecision.Wait,
            bindDecision(notice = null, port = 13337, ready = false),
            "a port with nothing listening on it must not be navigated to",
        )
    }

    @Test
    fun `no port yet means wait, whatever readiness says`() {
        assertEquals(BindDecision.Wait, bindDecision(notice = null, port = 0, ready = true))
        assertEquals(BindDecision.Wait, bindDecision(notice = null, port = -1, ready = true))
    }

    @Test
    fun `a startup notice wins over everything`() {
        // The notice is checked first on purpose: it may be terminal, or it may be
        // a slow start still coming up. Either way the server is not serving, and
        // a slow one that arrives later comes through onServerReady instead.
        assertEquals(
            BindDecision.ShowNotice("Server failed to start"),
            bindDecision(
                notice = StartupNotice("Server failed to start", terminal = false),
                port = 13337,
                ready = true,
            ),
            "a notice must not be swallowed by a port that happens to look fine",
        )
    }

    /**
     * The half the notice did not used to carry, and the reason it now does.
     *
     * A slow start and a server that has stopped trying reached this function as
     * the same `String?`, so both produced a toast over the loading page. For the
     * first that is honest: the server may still come up, and `onServerReady`
     * will say so. For the second the toast expires in three and a half seconds
     * and leaves "Starting server..." on screen for a server that is never
     * coming, with no control on the page to change it.
     */
    @Test
    fun `a server that gave up gets the page, not only a toast`() {
        assertEquals(
            BindDecision.ShowGaveUp("Server stopped"),
            bindDecision(
                notice = StartupNotice("Server stopped", terminal = true),
                port = 13337,
                ready = true,
            ),
            "a terminal notice must be distinguishable from a slow start",
        )
    }

    // -- shouldActOnResume --

    @Test
    fun `a resume with a serving server acts`() {
        assertTrue(shouldActOnResume(ready = true, backgroundedAt = 1_000L, serverPort = 13337))
    }

    @Test
    fun `an unbound service does not act`() {
        // `nodeService?.isServerReady()` yields null when nothing is bound. The
        // mutation measured here was `!= true` becoming `== null`, which inverts
        // exactly this case and lets an un-ready server through.
        assertFalse(
            shouldActOnResume(ready = null, backgroundedAt = 1_000L, serverPort = 13337),
            "no bound service is not evidence the server is serving",
        )
    }

    @Test
    fun `a server that is not serving does not act`() {
        assertFalse(
            shouldActOnResume(ready = false, backgroundedAt = 1_000L, serverPort = 13337),
            "reloading into a dead port leaves a connection-refused page nothing clears",
        )
    }

    @Test
    fun `never backgrounded, or no port, does not act`() {
        assertFalse(shouldActOnResume(ready = true, backgroundedAt = 0L, serverPort = 13337))
        assertFalse(shouldActOnResume(ready = true, backgroundedAt = 1_000L, serverPort = 0))
    }
}
