package com.vscodroid.storage

import android.content.Context
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File

/**
 * A write-back that gives up has to reach a screen, and nothing in the sync engine has
 * one.
 *
 * Two halves, and the failure this pins is the first one going missing quietly. The
 * engine's seam defaults to a no-op, deliberately: a default that reached for
 * `Looper.getMainLooper()` threw "not mocked" in every existing test that drives a
 * failing write-back. That makes the wiring load-bearing rather than decorative, and an
 * unwired seam is indistinguishable from the silence this was meant to end.
 */
class WriteBackNoticeWiringTest {

    private val activity = File("../../android/app/src/main/kotlin/com/vscodroid/MainActivity.kt")
    private val manager = File("src/main/kotlin/com/vscodroid/storage/SafStorageManager.kt")

    /**
     * A manager per case, so no case inherits a throttle another case has already moved.
     *
     * A relaxed [Context] is the whole of what construction needs: the constructor takes
     * shared preferences and builds a [SafSyncEngine], and neither is touched by the
     * claim below. [SafMirrorReclamationTest] builds one the same way.
     */
    private lateinit var safManager: SafStorageManager

    @BeforeEach
    fun setUp() {
        safManager = SafStorageManager(mockk<Context>(relaxed = true))
    }

    /**
     * Read from the source rather than driven through the Activity, which needs a device.
     *
     * ⚠️ Its ceiling, measured rather than assumed: wrapping the call in `if (false)`
     * leaves this green. It catches the wiring being deleted, which is the likely
     * regression, and cannot catch it being made unreachable. The behaviour either side
     * of it is covered where it can be: the engine announcing is pinned by
     * InitialSyncWiringTest, and the throttle by the case below.
     */
    @Test
    fun `MainActivity asks to be told when a write-back gives up`() {
        assertTrue(activity.isFile, "MainActivity.kt is not where this test expects it")
        val source = activity.readText()

        // Anchored at the start of a line, and for the second pattern the character
        // class is the anchor: nothing between the indent and the string name may be a
        // slash, so a `//` cannot get in front of it. Unanchored, a substring search
        // finds `// safManager.onWriteBackFailed { file ->` as readily as the call
        // itself, and commenting a line out is how a developer disables something, so
        // wiring that is already dead is the one shape these two could not see.
        assertTrue(
            Regex("""(?m)^\s*safManager\.onWriteBackFailed\s*\{""").containsMatchIn(source),
            "nothing wires the write-back notice, so a save that never reached the " +
                "device folder is silent again",
        )
        assertTrue(
            Regex("""(?m)^\s*[^/\n]*saf_write_back_failed""").containsMatchIn(source),
            "the notice does not name the string that tells the user what happened",
        )
    }

    /**
     * The same silence a folder at a time, and a seam of its own because it says
     * something else: a folder created in the editor that reached the device short of
     * some of its files, or that stopped at the cap this app imposes rather than at
     * anything the device refused. Unwired, a folder that arrived incomplete is
     * indistinguishable from one that arrived whole, and the user finds out when they
     * open it somewhere else.
     *
     * Both wordings are named, and separately: the capped one exists because telling a
     * person the device refused their folder, when the limit is ours, sends them looking
     * at the wrong machine.
     *
     * Same ceiling and same anchoring as the case above.
     */
    @Test
    fun `MainActivity asks to be told when a folder did not arrive whole`() {
        assertTrue(activity.isFile, "MainActivity.kt is not where this test expects it")
        val source = activity.readText()

        assertTrue(
            Regex("""(?m)^\s*safManager\.onUploadIncomplete\s*\{""").containsMatchIn(source),
            "nothing wires the upload notice, so a folder that reached the device with " +
                "files missing looks exactly like one that arrived whole",
        )
        assertTrue(
            Regex("""(?m)^\s*[^/\n]*saf_upload_incomplete""").containsMatchIn(source),
            "the notice does not name the string that says how much did not arrive",
        )
        assertTrue(
            Regex("""(?m)^\s*[^/\n]*saf_upload_capped""").containsMatchIn(source),
            "the cap this app imposes is not named, so it is announced in the wording " +
                "for a device that refused and the user goes looking at their device",
        )
    }

    /**
     * The inbound direction, which is the one where the wording matters most and the
     * one a shared seam would have got wrong: these documents are on the device and did
     * not reach the editor, so "the only copy is inside VSCodroid" is the opposite of
     * true for them. Running out of room is its own wording again, because that one the
     * user can act on.
     *
     * The two string names are matched on separate lines rather than twice over one:
     * `\b` refuses the longer name, so the ordinary plural cannot be satisfied by the
     * out-of-room one and the pair stays two checks rather than one counted twice.
     */
    @Test
    fun `MainActivity asks to be told when documents did not reach the editor`() {
        assertTrue(activity.isFile, "MainActivity.kt is not where this test expects it")
        val source = activity.readText()

        assertTrue(
            Regex("""(?m)^\s*safManager\.onDocumentsNotCopied\s*\{""").containsMatchIn(source),
            "nothing wires the notice for documents that never arrived, so a folder " +
                "opens missing files and says nothing about it",
        )
        assertTrue(
            Regex("""(?m)^\s*[^/\n]*saf_documents_not_copied\b""").containsMatchIn(source),
            "the notice does not name the string that says which way the copy failed",
        )
        assertTrue(
            Regex("""(?m)^\s*[^/\n]*saf_documents_not_copied_no_room""").containsMatchIn(source),
            "running out of room is announced in the wording for a provider that " +
                "refused, so the one cause the user can fix is not the one they are told",
        )
    }

    /**
     * The throttle belongs to the manager, so one refusing provider is one notice.
     *
     * Asserted as a rule rather than as a constant: the number is a product decision and
     * may move, but "the first failure speaks and the next one inside the interval does
     * not" is the behaviour.
     */
    @Test
    fun `the first failure is announced and a prompt second one is not`() {
        val interval = SafStorageManager.FAILURE_NOTICE_INTERVAL_MS
        // A reading well past the interval. Toy numbers put the first failure inside
        // the interval of a zero last-announced time and made this assert the opposite
        // of the shipped behaviour; the clause named in `the first failure is said even
        // seconds after boot` is what settles that case now, and it is asserted there
        // rather than smuggled in here. The caller supplies this from the monotonic
        // clock, not the wall clock.
        val now = 1_700_000_000_000L

        assertTrue(
            SafStorageManager.shouldAnnounce(now = now, lastAnnouncedAt = 0),
            "the first failure of a session has to be said",
        )
        assertFalse(
            SafStorageManager.shouldAnnounce(now = now + interval - 1, lastAnnouncedAt = now),
            "a provider that refuses everything would otherwise bury the editor",
        )
        assertTrue(
            SafStorageManager.shouldAnnounce(now = now + interval, lastAnnouncedAt = now),
            "the notice has to come back, or a later failure is silent again",
        )
    }

    /**
     * That the reading the throttle subtracts from cannot move backwards.
     *
     * ⚠️ Source text, because the reading is taken inside the lambda this method installs
     * on a private [SafSyncEngine], which no JVM test here can make fire. The behaviour
     * either side of it is covered: the rule by the cases above, and the clock by the one
     * below.
     *
     * The stamp lives in an `AtomicLong` for the life of the manager, and the rule is a
     * subtraction against it, so a wall clock corrected backwards by more than the
     * interval (NTP after a drifted RTC, or the user setting the date) makes every
     * later difference negative and silences the notice until the clock catches up. A
     * folder whose provider is refusing every save then stops saying so, which is the
     * failure this notice exists to prevent. `AuthTabWindow` states the same reasoning
     * one file over and takes `SystemClock.elapsedRealtime()`.
     *
     * Negative control, measured: put `System.currentTimeMillis()` back and this goes red.
     */
    @Test
    fun `the write-back throttle reads the monotonic clock`() {
        assertTrue(manager.isFile, "SafStorageManager.kt is not where this test expects it")
        val body = manager.readText()
            .substringAfter("fun onWriteBackFailed(", "")
            .substringBefore("\n    }")
        assertTrue(
            body.contains("claimAnnouncement("),
            "onWriteBackFailed is not where this test expects it, so the assertions below " +
                "would be asked of nothing",
        )

        assertTrue(
            Regex("""(?m)^\s*if \(claimAnnouncement\(SystemClock\.elapsedRealtime\(\)""")
                .containsMatchIn(body),
            "the throttle no longer reads the monotonic clock, so a device clock moved " +
                "backwards silences every write-back failure notice for the rest of the " +
                "session",
        )
        assertFalse(
            body.contains("System.currentTimeMillis()"),
            "the throttle reads wall time, which can move backwards under it",
        )
    }

    /**
     * The first notice of a session is given however recently the device booted.
     *
     * The clock above is milliseconds since boot, not since 1970, so "nothing announced
     * yet" stopped being a reading further back than any interval and became one that
     * can sit inside it. A device a few seconds up, with a provider already refusing,
     * would have swallowed the one notice that always has to be given.
     *
     * Negative control, measured: drop the `lastAnnouncedAt == NEVER_ANNOUNCED` clause
     * from `shouldAnnounce` and this goes red while every case above stays green, which
     * is why it is a case of its own.
     */
    @Test
    fun `the first failure is said even seconds after boot`() {
        assertTrue(
            SafStorageManager.shouldAnnounce(
                now = 3_000L,
                lastAnnouncedAt = SafStorageManager.NEVER_ANNOUNCED,
            ),
            "a folder that started failing before the device had been up for one " +
                "interval said nothing at all",
        )
    }

    /**
     * The predicate above says whether a failure is far enough from the last one to be
     * said. It cannot say who says it, and that is the half these four cover.
     *
     * Two threads genuinely reach the throttle: the `saf-writeback` daemon draining the
     * queue, and `Dispatchers.IO` running the write-backs `initialSync` issues itself.
     * With a plain read, compare and assign, both can read the same stale value, both
     * find the interval elapsed and both announce for one burst, which is the wall of
     * toasts the throttle exists to prevent.
     *
     * Driven through the reading rather than through threads, deliberately, and that is
     * a correctness point rather than a convenience one. Racing two callers does not
     * measure this: with both shapes released together from a `CyclicBarrier` on this
     * JDK, the read-compare-assign version announced twice in 0 of 50,000 two-thread
     * trials, 0 of 20,000 eight-thread trials and 0 of 2,000 sixteen-thread ones. The
     * window is one read, a subtraction and one write, and the throttle confines it to
     * the first instant of each interval, so a race test would have called the shape
     * this replaced green and pinned nothing. Handing one caller a reading a previous
     * caller has already consumed is that losing thread's exact situation, and it is
     * decidable.
     */
    @Test
    fun `the first failure of a session claims the notice`() {
        assertTrue(
            safManager.claimAnnouncement(now = 1_700_000_000_000L, last = 0),
            "the first failure of a session has to be said, and nothing has claimed it",
        )
    }

    @Test
    fun `a second caller holding the same stale reading loses the claim`() {
        val now = 1_700_000_000_000L

        assertTrue(safManager.claimAnnouncement(now = now, last = 0), "the first caller wins")
        assertFalse(
            safManager.claimAnnouncement(now = now, last = 0),
            "a caller holding a reading another caller has already consumed announced " +
                "as well, so one burst of failures reaches the user as two toasts and " +
                "the throttle only appears to hold",
        )
    }

    /**
     * The control that keeps the case above from passing for the wrong reason. A claim
     * that refused everything after the first would satisfy it and would also silence
     * every later failure for the rest of the session.
     */
    @Test
    fun `the claim comes back once the interval has passed`() {
        val interval = SafStorageManager.FAILURE_NOTICE_INTERVAL_MS
        val now = 1_700_000_000_000L

        assertTrue(safManager.claimAnnouncement(now = now, last = 0), "the first caller wins")
        assertTrue(
            safManager.claimAnnouncement(now = now + interval, last = now),
            "a failure a full interval later was refused, so a folder that starts " +
                "refusing again an hour on says nothing at all",
        )
    }

    /**
     * And the interval is still consulted. Without this a claim reduced to its
     * `compareAndSet` would pass every case above: the reading moves each time, so the
     * swap always succeeds and the throttle collapses into no throttle.
     */
    @Test
    fun `a claim inside the interval is refused even with nothing to race`() {
        val interval = SafStorageManager.FAILURE_NOTICE_INTERVAL_MS
        val now = 1_700_000_000_000L

        assertTrue(safManager.claimAnnouncement(now = now, last = 0), "the first caller wins")
        assertFalse(
            safManager.claimAnnouncement(now = now + interval - 1, last = now),
            "a failure inside the interval was announced, so a provider that refuses " +
                "everything buries the editor under its own error",
        )
    }

    /**
     * That the seam above is the one the notice actually goes through.
     *
     * ⚠️ Source text, because the behaviour is out of reach here: the wrapper installs
     * itself on the manager's own [SafSyncEngine], which is private and exposed by no
     * accessor, so a JVM test can neither read the installed lambda nor make the engine
     * call it. Everything else about the claim is behaviour; this one line is not.
     *
     * Anchored to the start of a line so commenting the call out cannot satisfy it,
     * which is how a guard of this shape passes over wiring that is already dead.
     */
    @Test
    fun `the write-back notice decides through the claim rather than beside it`() {
        assertTrue(manager.isFile, "SafStorageManager.kt is not where this test expects it")

        assertTrue(
            Regex("""(?m)^\s*if \(claimAnnouncement\(""").containsMatchIn(manager.readText()),
            "onWriteBackFailed no longer decides through claimAnnouncement, so whatever " +
                "the four cases above pin is not what the user's screen goes through",
        )
    }
}
