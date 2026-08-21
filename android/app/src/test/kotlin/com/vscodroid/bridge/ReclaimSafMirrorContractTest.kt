package com.vscodroid.bridge

import android.content.Context
import com.vscodroid.storage.SafStorageManager
import com.vscodroid.util.Logger
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * That [AndroidBridge.reclaimSafMirror] answers with the empty string ONLY when
 * the copy was actually removed.
 *
 * The convention reads backwards, which is why it is pinned rather than left to
 * the KDoc: success is the falsy value and every refusal is a non-empty sentence.
 * The relay in `MainActivity.injectBridgeRelay` compares with `=== ''`, so a
 * refusal that came back empty posts `ok: true`, the bundled saf-bridge
 * extension resolves its promise, and the user is shown a removal that did not
 * happen for a folder that is still on disk. The opposite mutation is just as
 * quiet: a removal that answered with a sentence, "Removed." being the obvious
 * one, posts `ok: false` and puts an error in front of every successful removal.
 *
 * Both directions are asserted here because either one alone is satisfied by a
 * method rewritten to answer the same thing every time.
 *
 * Nothing about the decision is stubbed. The real [SecurityManager] and its real
 * token are used, and the only fake is the Activity callback, which is where the
 * decision genuinely lives: the bridge cannot know what the editor has open.
 */
class ReclaimSafMirrorContractTest {

    /** The real one, so the token the bridge accepts is not stubbed into existence. */
    private val security = SecurityManager()

    private val context: Context = mockk(relaxed = true)

    private companion object {
        const val HASH = "a1b2c3"

        /**
         * A refusal in the shape the Activity actually produces: a sentence naming
         * what has to change first. See `MainActivity.removeDeviceFolderCopy`.
         */
        const val IN_USE = "That folder is open in the editor. Close it and try again."
    }

    /**
     * The rejected-token case logs, and `android.util.Log` throws on this
     * classpath. Stubbed rather than the log being asserted on: what the log says
     * is `SecurityManagerTest`'s subject, not this one's.
     */
    @BeforeEach
    fun silenceRejectionLog() {
        mockkObject(Logger)
        every { Logger.w(any(), any()) } just Runs
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    /** A bridge whose Activity callback answers [answer] and records what it was asked. */
    private fun bridgeAnswering(answer: String): Pair<AndroidBridge, MutableList<Pair<String, Boolean>>> {
        val asked = mutableListOf<Pair<String, Boolean>>()
        val bridge = AndroidBridge(
            context = context,
            security = security,
            clipboard = mockk(relaxed = true),
            onBackPressed = mockk(relaxed = true),
            onMinimize = mockk(relaxed = true),
            safManager = mockk<SafStorageManager>(relaxed = true),
            onReclaimMirror = { hash, force ->
                asked += hash to force
                answer
            },
        )
        return bridge to asked
    }

    @Test
    fun `a removal that went through comes back as the empty string`() {
        val (bridge, asked) = bridgeAnswering("")

        assertEquals(
            "", bridge.reclaimSafMirror(security.getSessionToken(), HASH, force = false),
            "a removal that happened must come back empty; the relay compares with === '' " +
                "and posts ok:false on anything else, so a word here puts an error in front " +
                "of every successful removal",
        )
        assertEquals(
            listOf(HASH to false), asked,
            "the removal never reached the Activity, so the empty answer above says nothing",
        )
    }

    @Test
    fun `a refusal comes back as a sentence, not as the empty string`() {
        val (bridge, _) = bridgeAnswering(IN_USE)

        val refusal = bridge.reclaimSafMirror(security.getSessionToken(), HASH, force = true)
        assertNotEquals(
            "", refusal,
            "a refused removal answered the way a successful one does. The relay posts " +
                "ok:true on the empty string, so the user is told their disk was freed while " +
                "the copy is still there.",
        )
        assertEquals(
            IN_USE, refusal,
            "the reason has to travel unchanged: it names the one thing that has to change " +
                "before the removal can happen, and a generic substitute is unfollowable",
        )
    }

    @Test
    fun `a stale session token is refused with a reason, and reaches no removal`() {
        val (bridge, asked) = bridgeAnswering("")

        val refusal = bridge.reclaimSafMirror("not the session token", HASH, force = true)
        assertEquals(RECLAIM_STALE_SESSION, refusal, "a rejected token must say the session is stale")
        assertTrue(
            refusal.isNotEmpty(),
            "the guard answered the way a completed removal does, so a caller holding no " +
                "valid token is told the folder's copy is gone",
        )
        assertTrue(asked.isEmpty(), "a rejected token still reached the removal")
    }

    @Test
    fun `a bridge with no activity behind it refuses rather than claiming a removal`() {
        // The default constructor argument, which is what a bridge built without
        // the SAF wiring gets. Nothing could have been removed, so answering the
        // way a removal does would be a claim about the disk that nobody made.
        val bridge = AndroidBridge(
            context = context,
            security = security,
            clipboard = mockk(relaxed = true),
            onBackPressed = mockk(relaxed = true),
            onMinimize = mockk(relaxed = true),
        )

        assertEquals(
            RECLAIM_UNAVAILABLE,
            bridge.reclaimSafMirror(security.getSessionToken(), HASH, force = false),
            "an unwired bridge must decline in a way the user can read; the empty string " +
                "here reports a removal that no code path performed",
        )
    }
}
