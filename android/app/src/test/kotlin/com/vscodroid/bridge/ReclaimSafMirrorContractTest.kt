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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * That [AndroidBridge.reclaimSafMirror] reports a removal ONLY when the copy was
 * actually removed.
 *
 * The convention reads backwards, which is why it is pinned rather than left to
 * the KDoc: success is the falsy value and every refusal is a non-empty sentence.
 * The opposite mutation is just as quiet: a removal that answered with a
 * sentence, "Removed." being the obvious one, is reported as a failure and puts
 * an error in front of every successful removal.
 *
 * Both directions are asserted here because either one alone is satisfied by a
 * method rewritten to answer the same thing every time.
 *
 * The outcome no longer travels back as the return value, and that is what these
 * tests had to follow. The removal walks the copy twice before it can answer, and
 * a `@JavascriptInterface` call holds the calling JavaScript thread until it
 * returns, which for the relay is the workbench page's own thread; the method
 * therefore hands back at once and posts the outcome later against the reply id
 * its caller sent. So the empty string now means two different things on two
 * different roads. The RETURN says only that the removal was accepted, and
 * `MainActivity.injectBridgeRelay` compares it with `!== ''` to decide whether a
 * refusal has to be posted immediately. The POSTED answer carries the outcome,
 * with the `ok` flag beside it already decided from the same emptiness, and the
 * relay's reply hook turns that flag into a resolved or rejected promise.
 *
 * Nothing about the decision is stubbed. The real [SecurityManager] and its real
 * token are used, and the only fake is the Activity callback, which is where the
 * decision genuinely lives: the bridge cannot know what the editor has open. The
 * executor is real too, which is why every case below waits on a latch rather
 * than reading a value the call could not have produced yet.
 */
class ReclaimSafMirrorContractTest {

    /** The real one, so the token the bridge accepts is not stubbed into existence. */
    private val security = SecurityManager()

    /**
     * Answers the two refusal resources with recognisable sentinels.
     *
     * Not bookkeeping. A relaxed `Context` answers `getString` with the EMPTY
     * STRING, which is this method's answer for "the copy was removed", so an
     * unstubbed mock would turn both refusals below into claims that the user's
     * disk had been freed, and each case would then pass by agreeing with itself.
     */
    private val context: Context = mockk(relaxed = true) {
        every { getString(RECLAIM_STALE_SESSION) } returns STALE
        every { getString(RECLAIM_UNAVAILABLE) } returns UNAVAILABLE
    }

    private companion object {
        const val HASH = "a1b2c3"

        /** The reply id a caller sends, which the answer has to come back under. */
        const val REPLY = "reply-7"

        /**
         * A refusal in the shape the Activity actually produces: a sentence naming
         * what has to change first. See `MainActivity.removeDeviceFolderCopy`.
         */
        const val IN_USE = "That folder is open in the editor. Close it and try again."

        /**
         * What the stubbed refusal resources answer. Distinct from each other, so a
         * method that always returns the wrong one cannot satisfy both cases.
         */
        const val STALE = "stale-session-resource"
        const val UNAVAILABLE = "reclaim-unavailable-resource"

        /** Long enough that a loaded runner is not the reason a case fails. */
        const val WAIT_SECONDS = 5L
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
        every { Logger.e(any(), any(), any()) } just Runs
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    /** One answer posted back to the page, as the reply hook would receive it. */
    private data class Posted(val replyId: String, val ok: Boolean, val payload: String)

    /**
     * What a bridge did: the removals it asked the Activity for, and the answers
     * it posted back.
     *
     * The latch counts posted answers rather than being a sleep. The work runs on
     * the bridge's own worker, so a case reading [posted] without waiting would be
     * asserting on a list the bridge has not filled in yet and would pass or fail
     * by timing.
     */
    private class Recorder {
        val asked = mutableListOf<Pair<String, Boolean>>()
        val posted = mutableListOf<Posted>()
        val answered = CountDownLatch(1)

        fun awaitAnswer() = assertTrue(
            answered.await(WAIT_SECONDS, TimeUnit.SECONDS),
            "no answer was posted; the removal's outcome never reached the page, so the " +
                "caller's promise sits until its own deadline and the user is told the app " +
                "may not be running on Android",
        )
    }

    /** A bridge whose Activity callback answers [answer] and records what it was asked. */
    private fun bridgeAnswering(answer: String): Pair<AndroidBridge, Recorder> {
        val r = Recorder()
        val bridge = AndroidBridge(
            context = context,
            security = security,
            clipboard = mockk(relaxed = true),
            onBackPressed = mockk(relaxed = true),
            onMinimize = mockk(relaxed = true),
            safManager = mockk<SafStorageManager>(relaxed = true),
            onReclaimMirror = { hash, force ->
                r.asked += hash to force
                answer
            },
            onAsyncAnswer = { id, ok, payload ->
                r.posted += Posted(id, ok, payload)
                r.answered.countDown()
            },
        )
        return bridge to r
    }

    @Test
    fun `a removal that went through is posted as ok, with an empty payload`() {
        val (bridge, r) = bridgeAnswering("")

        assertEquals(
            "", bridge.reclaimSafMirror(security.getSessionToken(), HASH, force = false, REPLY),
            "an accepted removal must hand back empty; the relay compares with !== '' and " +
                "posts anything else as a refusal, so a word here puts an error in front of " +
                "every removal it just started",
        )
        r.awaitAnswer()
        assertEquals(
            listOf(HASH to false), r.asked,
            "the removal never reached the Activity, so the answer above says nothing",
        )
        assertEquals(
            listOf(Posted(REPLY, ok = true, payload = "")), r.posted,
            "a removal that happened must be posted as ok against the id the caller sent; " +
                "under a different id no promise is waiting, and under ok:false the user is " +
                "shown an error for disk that was freed",
        )
    }

    @Test
    fun `a refusal is posted as not ok, carrying the sentence`() {
        val (bridge, r) = bridgeAnswering(IN_USE)

        val accepted = bridge.reclaimSafMirror(
            security.getSessionToken(), HASH, force = true, REPLY
        )
        assertEquals(
            "", accepted,
            "the return value says only that the removal was accepted; a refusal decided by " +
                "the Activity is posted, not returned, and putting it here would surface it " +
                "twice",
        )
        r.awaitAnswer()
        val answer = r.posted.single()
        assertNotEquals(
            "", answer.payload,
            "a refused removal answered the way a successful one does, so the user is told " +
                "their disk was freed while the copy is still there",
        )
        assertEquals(
            Posted(REPLY, ok = false, payload = IN_USE), answer,
            "the reason has to travel unchanged and as a rejection: it names the one thing " +
                "that has to change before the removal can happen, a generic substitute is " +
                "unfollowable, and ok:true resolves the caller's promise over it",
        )
    }

    @Test
    fun `a stale session token is refused with a reason, and reaches no removal`() {
        val (bridge, r) = bridgeAnswering("")

        val refusal = bridge.reclaimSafMirror("not the session token", HASH, force = true, REPLY)
        assertEquals(STALE, refusal, "a rejected token must say the session is stale")
        assertTrue(
            refusal.isNotEmpty(),
            "the guard answered the way an accepted removal does, so the caller waits for an " +
                "answer that will never be posted and blames a timeout",
        )
        assertTrue(r.asked.isEmpty(), "a rejected token still reached the removal")
        assertTrue(
            r.posted.isEmpty(),
            "a rejected token posted an answer. The refusal has to travel back on the " +
                "caller's own thread: BridgeTokenUniformityTest decides whether a method " +
                "obeyed the token by asking whether anything outside the bridge was touched, " +
                "and a refusal routed through the reply hook touches one.",
        )
    }

    @Test
    fun `a bridge with no activity behind it refuses rather than claiming a removal`() {
        // The default constructor argument, which is what a bridge built without
        // the SAF wiring gets. Nothing could have been removed, so answering the
        // way a removal does would be a claim about the disk that nobody made.
        val r = Recorder()
        val bridge = AndroidBridge(
            context = context,
            security = security,
            clipboard = mockk(relaxed = true),
            onBackPressed = mockk(relaxed = true),
            onMinimize = mockk(relaxed = true),
            onAsyncAnswer = { id, ok, payload ->
                r.posted += Posted(id, ok, payload)
                r.answered.countDown()
            },
        )

        assertEquals(
            "",
            bridge.reclaimSafMirror(security.getSessionToken(), HASH, force = false, REPLY),
            "the token was good, so the call is accepted; what an unwired bridge cannot do " +
                "is decide the outcome here",
        )
        r.awaitAnswer()
        assertEquals(
            listOf(Posted(REPLY, ok = false, payload = UNAVAILABLE)), r.posted,
            "an unwired bridge must decline in a way the user can read; an empty payload " +
                "here reports a removal that no code path performed",
        )
    }
}
