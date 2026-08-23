package com.vscodroid.bridge

import android.content.Context
import com.vscodroid.storage.SafStorageManager
import com.vscodroid.util.Logger
import com.vscodroid.util.StorageManager
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import org.json.JSONObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * That the four bridge commands whose cost is the size of the user's disk answer
 * their caller immediately and deliver the value afterwards.
 *
 * This is a property about a thread, and about which thread. A method reached
 * through `@JavascriptInterface` holds the JavaScript thread that called it until
 * it returns; the relay that calls these four is injected into the workbench
 * page's own main frame, so the thread it holds is the one the editor renders and
 * takes input on. `SafStorageManager.listMirrors` says in its own documentation
 * that it walks every copied device folder twice, `StorageManager` walks seven
 * directories and then the whole of `filesDir` again for the total, and the
 * extracted tree is around 875 MB before a project is opened. Answered inline,
 * every one of those walks was a freeze of the whole workbench for its whole
 * duration, and no `WebViewRenderProcessClient` is installed anywhere, so nothing
 * in the app noticed or reported it.
 *
 * The deadline the bundled extension gives these four (`DISK_WALK_TIMEOUT_MS`,
 * two minutes) is not the cover it looks like. It stops the promise rejecting
 * mid-walk, which is a fact about the promise; the freeze is not the promise
 * waiting, it is the thread not running.
 *
 * So each case below drives the real executor the bridge ships with, rather than
 * a direct one supplied here. That choice is the point: a bridge that ran the
 * work inline would satisfy every assertion about the value that eventually
 * arrives, and only the first case, which reads the return value while the work
 * is still blocked, can tell the two apart.
 */
class BridgeDiskWorkOffThreadTest {

    /** The real one, so the token the bridge accepts is not stubbed into existence. */
    private val security = SecurityManager()

    /**
     * Answers the refusal resource with a recognisable sentinel.
     *
     * Not bookkeeping. A relaxed `Context` answers `getString` with the EMPTY
     * STRING, and the empty string is what these four return to say the work has
     * STARTED, so an unstubbed mock turns every refusal below into an acceptance
     * and each case then passes by agreeing with itself.
     *
     * The catch-all comes first for the same reason, and it is not redundant with
     * the two named stubs under it. [STORAGE_BUSY] has a resource of its own and
     * is deliberately not among them: a Context stubbed only by name answers `""`
     * for whatever it was not given, which is this bridge's word for accepted, so
     * every post in the queue-bound case below would read as accepted and the case
     * would fail saying there is no bound over a bound that is intact. Anything
     * this Context is asked for is a refusal, so it answers like one.
     */
    private val context: Context = mockk(relaxed = true) {
        every { getString(any<Int>()) } returns REFUSED
        every { getString(STORAGE_STALE_SESSION) } returns STALE
        every { getString(RECLAIM_STALE_SESSION) } returns STALE
    }

    private companion object {
        const val REPLY = "reply-1"
        const val HASH = "a1b2c3"
        const val STALE = "stale-session-resource"

        /** Any other refusal sentence, so none of them can come back empty. */
        const val REFUSED = "refusal-resource"
        const val LISTING = """[{"hash":"a1b2c3","bytes":12}]"""
        const val BREAKDOWN = """{"total":7}"""
        const val FREED = 4096L

        /** Long enough that a loaded runner is not the reason a case fails. */
        const val WAIT_SECONDS = 5L

        /**
         * More outstanding disk walks than any sequence a person drives.
         *
         * The cap the bridge applies is its own business and is not asserted; this
         * is the point past which "there is no cap" is the only explanation left.
         */
        const val ABSURD = 64
    }

    @BeforeEach
    fun setUp() {
        mockkObject(Logger)
        every { Logger.w(any(), any()) } just Runs
        every { Logger.e(any(), any(), any()) } just Runs

        // Stubbed so the two storage commands can be driven without a real
        // filesystem. What is under test is where the work runs, not what it
        // computes; `StorageSymlinkTest` and `ClearableStorageTest` own the
        // figures themselves.
        mockkObject(StorageManager)
        every { StorageManager.getStorageBreakdown(any()) } returns JSONObject(BREAKDOWN)
        every { StorageManager.clearCaches(any()) } returns FREED
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    /** One answer posted back to the page, as the relay's reply hook would receive it. */
    private data class Posted(val replyId: String, val ok: Boolean, val payload: String)

    /** The answers a bridge posted, and a latch that says when the first arrived. */
    private class Answers {
        val posted = mutableListOf<Posted>()
        val arrived = CountDownLatch(1)

        fun await() = assertTrue(
            arrived.await(WAIT_SECONDS, TimeUnit.SECONDS),
            "no answer was posted within $WAIT_SECONDS seconds, so nothing ever reaches the " +
                "caller and its promise sits until its own deadline",
        )
    }

    private fun bridgeWith(
        answers: Answers,
        onListMirrors: () -> String = { LISTING },
        onReclaimMirror: (String, Boolean) -> String = { _, _ -> "" },
    ) = AndroidBridge(
        context = context,
        security = security,
        clipboard = mockk(relaxed = true),
        onBackPressed = mockk(relaxed = true),
        onMinimize = mockk(relaxed = true),
        safManager = mockk<SafStorageManager>(relaxed = true),
        onListMirrors = onListMirrors,
        onReclaimMirror = onReclaimMirror,
        onAsyncAnswer = { id, ok, payload ->
            synchronized(answers.posted) { answers.posted += Posted(id, ok, payload) }
            answers.arrived.countDown()
        },
    )

    /**
     * The case the whole change exists for, and the only one that can fail while
     * the value still arrives correctly.
     *
     * The walk is held open on a latch, so "did the method return?" is asked at a
     * moment when an inline implementation could not have returned. A bridge that
     * kept the old shape blocks inside the call and never reaches the assertion;
     * the case then fails by timing out rather than by comparing values, which is
     * the correct verdict for a workbench that has stopped drawing.
     */
    @Test
    fun `a listing hands back before its walk has finished`() {
        val answers = Answers()
        val walking = CountDownLatch(1)
        val letWalkFinish = CountDownLatch(1)
        val bridge = bridgeWith(
            answers,
            onListMirrors = {
                walking.countDown()
                assertTrue(
                    letWalkFinish.await(WAIT_SECONDS, TimeUnit.SECONDS),
                    "the walk was never released. Read this as the defect first: a bridge " +
                        "that runs the work inline never returns to the caller, so the " +
                        "caller never reaches the line that releases it, and the two wait " +
                        "on each other exactly as the workbench waits on a real walk.",
                )
                LISTING
            },
        )

        val accepted = bridge.listSafMirrors(security.getSessionToken(), REPLY)

        assertEquals(
            "", accepted,
            "the listing must hand back the empty string to say the walk has started. A " +
                "sentence here is a refusal, and the relay posts it as an error over a walk " +
                "that is running.",
        )
        assertTrue(
            walking.await(WAIT_SECONDS, TimeUnit.SECONDS),
            "the walk never started, so the empty answer above only means nothing happened",
        )
        assertTrue(
            synchronized(answers.posted) { answers.posted.isEmpty() },
            "an answer was posted before the walk finished, so it cannot be the walk's",
        )

        letWalkFinish.countDown()
        answers.await()
        assertEquals(
            listOf(Posted(REPLY, ok = true, payload = LISTING)), answers.posted,
            "the listing has to arrive under the id its caller sent; under any other id no " +
                "promise is waiting and the caller times out having been answered",
        )
    }

    /**
     * The queue behind that hand-off is bounded.
     *
     * Handing back at once is what keeps the workbench drawing; it also means the
     * caller can hand back and post again, and again. The executor is single and
     * its queue was unbounded, so a caller posting `listSafMirrors` in a loop
     * queued a walk of every copied device folder per message, `getStorageBreakdown`
     * a walk of the whole of `filesDir`, and `clearCaches` a fresh deletion pass on
     * each queued copy. Nothing dropped, nothing deduplicated: the disk and the
     * battery ran for as long as the caller liked and every legitimate storage call
     * waited behind it. That caller is not only the bundled extension, since the
     * relay is shared by everything on the workbench's origin.
     *
     * The bound's value is not asserted, only that there is one and that it is
     * reached long before a number no person produces. What matters at the
     * boundary is the shape of the refusal: it comes back on the caller's own
     * thread, like the stale-token refusal, and posts nothing, because a caller
     * that was refused must not also be left waiting for an answer.
     */
    @Test
    fun `a caller cannot queue disk walks without bound`() {
        val answers = Answers()
        val letWalkFinish = CountDownLatch(1)
        val bridge = bridgeWith(
            answers,
            onListMirrors = {
                assertTrue(
                    letWalkFinish.await(WAIT_SECONDS, TimeUnit.SECONDS),
                    "the walk was never released, so the queue could not have filled",
                )
                LISTING
            },
        )
        val token = security.getSessionToken()

        // Posted until one is refused. The first occupies the thread and the rest
        // pile up behind it, exactly as a caller in a loop does; the cap is read
        // off the behaviour rather than from the constant, which is private to the
        // bridge and is a number this case has no opinion about.
        var refusedAt = 0
        var refusal = ""
        for (i in 1..ABSURD) {
            val answer = bridge.listSafMirrors(token, "reply-$i")
            if (answer != "") {
                refusedAt = i
                refusal = answer
                break
            }
        }

        assertTrue(refusedAt != 0) {
            "$ABSURD disk walks were accepted onto one thread and none was refused. Each " +
                "walks the whole of filesDir or every copied device folder, so this is " +
                "the disk and the battery pinned for as long as a caller cares to post."
        }
        assertTrue(refusedAt > 1) {
            "the very first storage command was refused, so the bound is not a bound on " +
                "a queue, it is the feature switched off"
        }
        assertEquals(
            context.getString(STORAGE_BUSY), refusal,
            "a full queue is refused with some other sentence. Read from the same " +
                "constant the bridge reads, so it pins which refusal was given and " +
                "not its wording, which is the resource's business.",
        )
        assertNotEquals(
            STORAGE_STALE_SESSION, STORAGE_BUSY,
            "a full queue is refused with the stale-session sentence, which tells the " +
                "user to reload the window. That does not drain the single disk thread, " +
                "so they wait exactly as long and lose their editor state on the way.",
        )

        letWalkFinish.countDown()
        answers.await()
        val accepted = refusedAt - 1
        val deadline = System.currentTimeMillis() + WAIT_SECONDS * 1000
        while (System.currentTimeMillis() < deadline &&
            synchronized(answers.posted) { answers.posted.size } < accepted
        ) {
            Thread.sleep(10)
        }

        val posted = synchronized(answers.posted) { answers.posted.toList() }
        assertEquals(
            accepted, posted.size,
            "every command that was accepted has to be answered; a refusal must not cost " +
                "the ones already queued their answers. Got: $posted",
        )
        assertTrue(posted.none { it.replyId == "reply-$refusedAt" }) {
            "the refused command also posted an answer, so its caller is told twice and " +
                "the second one arrives against a promise the first already rejected"
        }
    }

    @Test
    fun `a storage breakdown is posted as ok, under the caller's id`() {
        val answers = Answers()
        val bridge = bridgeWith(answers)

        assertEquals("", bridge.getStorageBreakdown(security.getSessionToken(), REPLY))
        answers.await()
        assertEquals(
            listOf(Posted(REPLY, ok = true, payload = BREAKDOWN)), answers.posted,
            "the breakdown is what the caller asked for and it has to arrive as the answer, " +
                "not as the return value the caller is told to ignore",
        )
    }

    @Test
    fun `the bytes a cache clear freed are posted as text`() {
        val answers = Answers()
        val bridge = bridgeWith(answers)

        assertEquals("", bridge.clearCaches(security.getSessionToken(), REPLY))
        answers.await()
        assertEquals(
            listOf(Posted(REPLY, ok = true, payload = FREED.toString())), answers.posted,
            "the bridge carries text, so the figure has to be spelled out; a caller parses " +
                "it back and shows the user how much was freed",
        )
    }

    /**
     * The polarity, at the boundary it now leaves through.
     *
     * `ReclaimSafMirrorContractTest` owns the convention itself; what is pinned
     * here is that the convention survives the move off the caller's thread. The
     * Activity's answer is a sentence, and the flag beside it has to say the
     * removal did not happen.
     */
    @Test
    fun `a removal the Activity refused is posted as not ok`() {
        val answers = Answers()
        val refusal = "That folder is open in the editor. Close it and try again."
        val bridge = bridgeWith(answers, onReclaimMirror = { _, _ -> refusal })

        assertEquals(
            "", bridge.reclaimSafMirror(security.getSessionToken(), HASH, false, REPLY)
        )
        answers.await()
        assertEquals(
            listOf(Posted(REPLY, ok = false, payload = refusal)), answers.posted,
            "a refusal posted as ok resolves the caller's promise, and the user is told " +
                "their disk was freed while the copy is still there",
        )
    }

    /**
     * A walk that throws still answers.
     *
     * Without this the caller has no way to tell a crash apart from a walk that
     * is taking its time, and the difference is two minutes of a progress
     * indicator followed by a message blaming the app for not running on Android.
     */
    @Test
    fun `work that throws is posted as a failure rather than dropped`() {
        val answers = Answers()
        val bridge = bridgeWith(answers, onListMirrors = { error("the mirrors root is gone") })

        assertEquals("", bridge.listSafMirrors(security.getSessionToken(), REPLY))
        answers.await()
        val answer = answers.posted.single()
        assertEquals(REPLY to false, answer.replyId to answer.ok, "a failed walk must reject")
        assertTrue(
            answer.payload.contains("the mirrors root is gone"),
            "the failure has to say what went wrong; got: ${answer.payload}",
        )
    }

    /**
     * A rejected token is answered where the caller waits, and starts nothing.
     *
     * Both halves are load-bearing and the second is the reason the first is
     * written the way it is. `BridgeTokenUniformityTest` decides whether a method
     * OBEYED the token by asking whether anything outside the bridge was touched,
     * so a refusal routed through the reply hook would touch a collaborator and
     * put four methods back in the class of methods that consult the validator
     * without obeying it.
     */
    @Test
    fun `a stale token refuses on the caller's thread and starts nothing`() {
        val answers = Answers()
        val asked = mutableListOf<String>()
        val bridge = bridgeWith(
            answers,
            onListMirrors = { asked += "list"; LISTING },
            onReclaimMirror = { _, _ -> asked += "reclaim"; "" },
        )
        val wrong = "not the session token"

        assertEquals(STALE, bridge.getStorageBreakdown(wrong, REPLY))
        assertEquals(STALE, bridge.clearCaches(wrong, REPLY))
        assertEquals(STALE, bridge.listSafMirrors(wrong, REPLY))
        assertEquals(STALE, bridge.reclaimSafMirror(wrong, HASH, true, REPLY))

        assertTrue(asked.isEmpty(), "a rejected token still reached the Activity: $asked")
        assertTrue(
            !answers.arrived.await(200, TimeUnit.MILLISECONDS) && answers.posted.isEmpty(),
            "a rejected token posted an answer, so the refusal travelled through a " +
                "collaborator instead of coming straight back to its caller",
        )
    }
}
