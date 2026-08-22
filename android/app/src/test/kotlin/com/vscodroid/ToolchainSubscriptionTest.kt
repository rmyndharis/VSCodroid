package com.vscodroid

import com.google.android.play.core.assetpacks.model.AssetPackStatus
import com.vscodroid.setup.ToolchainFailure
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * When the Toolchains screen is allowed to stop listening to Play Core.
 *
 * The screen used to drop its subscription in `onStop`, which is what
 * `AndroidBridge` refuses to do with the same manager: the COMPLETED branch of
 * the manager's state handler is the only thing that copies a delivered pack
 * into `usr/`, and Play Core promises nothing about re-emitting a state to a
 * listener that subscribes afterwards. A user who started a 155 MB install and
 * pressed Home therefore paid for a pack that was delivered and never installed,
 * repaired only by the next launch that runs `SplashActivity`, which resuming a
 * live task never does.
 *
 * [shouldReleaseSubscription] is the replacement rule, and these pin it. The
 * wiring, that `onStop` and `onDestroy` consult it rather than unsubscribing
 * outright, is read from the source below for the reason `PackStatusTest` gives:
 * an Activity method cannot be reached from a JVM test here.
 */
class ToolchainSubscriptionTest {

    private fun outstandingOf(vararg packs: String): MutableSet<String> =
        ConcurrentHashMap.newKeySet<String>().apply { addAll(packs) }

    private fun gone(value: Boolean) = AtomicBoolean(value)

    /**
     * NEGATIVE CONTROL: drop the `screenGone &&` from the return in
     * [shouldReleaseSubscription] and `a screen still on show keeps listening`
     * goes red; return `true` unconditionally and both of the others do.
     */
    @Test
    fun `the last download settling with no screen left hands the subscription back`() {
        val outstanding = outstandingOf("toolchain_java")

        assertTrue(
            shouldReleaseSubscription("toolchain_java", AssetPackStatus.COMPLETED, outstanding, gone(true)),
            "a settled download with no screen behind it has nothing left to hear, so the " +
                "subscription would be held for the life of the process",
        )
        assertTrue(outstanding.isEmpty(), "the settled pack was left in the set")
    }

    @Test
    fun `a download still running keeps the subscription`() {
        val outstanding = outstandingOf("toolchain_java", "toolchain_ruby")

        assertFalse(
            shouldReleaseSubscription("toolchain_ruby", AssetPackStatus.COMPLETED, outstanding, gone(true)),
            "the subscription went while Java was still downloading, so the COMPLETED " +
                "that installs it reaches nobody and the pack stays delivered and uncopied",
        )
    }

    @Test
    fun `a screen still on show keeps listening`() {
        val outstanding = outstandingOf("toolchain_java")

        assertFalse(
            shouldReleaseSubscription("toolchain_java", AssetPackStatus.COMPLETED, outstanding, gone(false)),
            "a screen still in front stopped hearing Play, so a second install started " +
                "from those same cards would draw no progress",
        )
        assertTrue(
            outstanding.isEmpty(),
            "the pack has to leave the set even while the screen is up, or the onStop " +
                "that follows finds it non-empty and never unsubscribes at all",
        )
    }

    /**
     * NEGATIVE CONTROL: move the `outstanding.remove(packName)` above the
     * `isTerminalPackStatus` guard and this goes red.
     */
    @Test
    fun `a download still going somewhere neither settles nor releases`() {
        listOf(
            AssetPackStatus.DOWNLOADING,
            AssetPackStatus.TRANSFERRING,
            AssetPackStatus.PENDING,
            AssetPackStatus.WAITING_FOR_WIFI,
            // The one that matters most: a confirmation prompt the user walked
            // away from is the state AndroidBridge names as the reason a
            // subscription cannot be dropped on teardown.
            AssetPackStatus.REQUIRES_USER_CONFIRMATION,
        ).forEach { status ->
            val outstanding = outstandingOf("toolchain_java")

            assertFalse(
                shouldReleaseSubscription("toolchain_java", status, outstanding, gone(true)),
                "status $status is still on its way, and unsubscribing on it drops the " +
                    "install that finishes it",
            )
            assertTrue(
                "toolchain_java" in outstanding,
                "status $status took the pack out of the set, so the next status to " +
                    "arrive would find nothing outstanding and release the subscription",
            )
        }
    }

    /**
     * The one interleaving `onDestroy` and this function have to survive between
     * them, placed rather than waited for.
     *
     * `onDestroy` writes the flag and then tests the set. If this asked in the
     * same order, reading the flag before taking the pack out, a report preempted
     * between its two steps would leave the screen finding the set non-empty and
     * the report finding a screen that was not gone yet: neither unregisters, and
     * Play Core keeps a listener with no screen and no download behind it.
     *
     * The set below stands in for that preemption exactly, by flipping the flag
     * at the instant the last pack leaves. A reading taken before the call cannot
     * see it; a read taken after the removal has to.
     *
     * NEGATIVE CONTROL: hoist the read in [shouldReleaseSubscription] above
     * `outstanding.remove(packName)`, which is the shape the call site used to
     * impose by passing a `Boolean`, and this goes red on its own.
     */
    @Test
    fun `a screen destroyed as the last pack leaves the set is still seen`() {
        val screenGone = AtomicBoolean(false)
        val outstanding = FlippingSet(outstandingOf("toolchain_java")) { screenGone.set(true) }

        assertTrue(
            shouldReleaseSubscription(
                "toolchain_java", AssetPackStatus.COMPLETED, outstanding, screenGone,
            ),
            "the flag was read before the pack left the set, so this report and the " +
                "onDestroy it raced each decided the other would hand the subscription " +
                "back, and Play Core holds a listener nothing will ever unregister",
        )
    }

    /**
     * NEGATIVE CONTROL: spell the settled states out as COMPLETED, FAILED and
     * CANCELED, the way `AndroidBridge` does, and the UNKNOWN case goes red.
     */
    @Test
    fun `a declined request settles, because nothing further is coming for it`() {
        // UNKNOWN is how ToolchainManager declines a request for a pack another
        // install already holds, and the declined caller hears nothing more about
        // it. Left in the set it would hold the subscription open for good.
        listOf(
            AssetPackStatus.UNKNOWN,
            AssetPackStatus.FAILED,
            AssetPackStatus.CANCELED,
            AssetPackStatus.NOT_INSTALLED,
        ).forEach { status ->
            val outstanding = outstandingOf("toolchain_java")

            assertTrue(
                shouldReleaseSubscription("toolchain_java", status, outstanding, gone(true)),
                "status $status leaves nothing to wait for, so the subscription must go",
            )
        }
    }
}

/**
 * Who the "already installing" line is addressed to.
 *
 * The retention [shouldReleaseSubscription] introduced is what makes the question
 * exist. A rotation mid-download leaves the destroyed screen's listener
 * registered until the pack settles, the rebuilt screen registers a second one,
 * Play delivers COMPLETED to both managers, and the one that loses the
 * process-wide claim declines with UNKNOWN. Roughly half the time the loser is
 * the rebuilt screen's manager, and the line then landed on a screen where the
 * user had tapped nothing.
 */
class ToolchainDeclineToastTest {

    /**
     * NEGATIVE CONTROL: drop the `asked &&` from [shouldSayAlreadyInstalling] and
     * this goes red; return `false` unconditionally and
     * `a decline answering this screen's own tap is said` goes red.
     */
    @Test
    fun `a decline for a pack this screen never asked for says nothing`() {
        assertFalse(
            shouldSayAlreadyInstalling(AssetPackStatus.UNKNOWN, null, asked = false),
            "a decline this screen did not ask for is announced to it. That report is " +
                "the losing half of a claim race between two managers after a rotation, " +
                "so the user sees a toolchain popping up an explanation for a tap they " +
                "never made",
        )
    }

    @Test
    fun `a decline answering this screen's own tap is said`() {
        assertTrue(
            shouldSayAlreadyInstalling(AssetPackStatus.UNKNOWN, null, asked = true),
            "a tap on Install was answered with nothing at all: the card cannot say it " +
                "either, so the user taps again and gets the same silence",
        )
    }

    /**
     * NEGATIVE CONTROL: drop the `why == null` from [shouldSayAlreadyInstalling]
     * and the failure case goes red; drop `status == AssetPackStatus.UNKNOWN` and
     * the rest do.
     */
    @Test
    fun `nothing but a decline is read as one`() {
        assertFalse(
            shouldSayAlreadyInstalling(AssetPackStatus.UNKNOWN, ToolchainFailure.STORAGE, asked = true),
            "a failure carrying a reason is also announced as an install someone else " +
                "is doing, so the user is told two contradictory things about one pack",
        )
        listOf(
            AssetPackStatus.COMPLETED,
            AssetPackStatus.FAILED,
            AssetPackStatus.CANCELED,
            AssetPackStatus.NOT_INSTALLED,
            AssetPackStatus.DOWNLOADING,
        ).forEach { status ->
            assertFalse(
                shouldSayAlreadyInstalling(status, null, asked = true),
                "status $status is announced as a decline, and a completed install is " +
                    "the worst of them: the toolchain is on disk and the screen says " +
                    "someone else is still fetching it",
            )
        }
    }

    /**
     * That the reading [shouldSayAlreadyInstalling] rests on can ever be true.
     *
     * [shouldReleaseSubscription] removes any settled pack and a decline settles,
     * so a membership taken after that call is false for every decline there is
     * and the gate refuses the user's own tap as well as the overheard one. This
     * pins the property the call site's ordering has to respect; the ordering
     * itself is read from the source in [ToolchainScreenRetentionTest].
     */
    @Test
    fun `a decline takes the pack out of the set it is recognised by`() {
        val outstanding = ConcurrentHashMap.newKeySet<String>().apply { add("toolchain_java") }

        shouldReleaseSubscription(
            "toolchain_java", AssetPackStatus.UNKNOWN, outstanding, AtomicBoolean(false),
        )

        assertFalse(
            "toolchain_java" in outstanding,
            "the decline left the pack in the set, so the ordering rule the callback " +
                "and ToolchainScreenRetentionTest both carry is measuring nothing",
        )
    }
}

/**
 * A set that runs [onRemove] as a removal happens, so the other thread's step
 * can be placed at the one instruction where a race would put it.
 *
 * Nothing here is concurrent, deliberately: a test that starts threads and hopes
 * to hit a few-instruction window measures the scheduler, not the code.
 */
private class FlippingSet(
    private val delegate: MutableSet<String>,
    private val onRemove: () -> Unit,
) : MutableSet<String> by delegate {
    override fun remove(element: String): Boolean {
        onRemove()
        return delegate.remove(element)
    }
}

/**
 * That the Toolchains screen asks [shouldReleaseSubscription] rather than
 * unsubscribing on its own, and that nothing the manager outlives is a screen.
 *
 * Source reading, because both live in Activity callbacks and this project has
 * no Robolectric. It proves the calls are written where they have to be, not
 * that Android delivers the callbacks.
 */
class ToolchainScreenRetentionTest {

    private val file = File("src/main/kotlin/com/vscodroid/ToolchainActivity.kt")

    private val source by lazy {
        assertTrue(
            file.isFile,
            "${file.path} is not at ${file.absolutePath}; this test would otherwise " +
                "pass by reading nothing",
        )
        file.readText()
    }

    /** Comments dropped: every rule here is argued in prose beside the line it governs. */
    private val code by lazy {
        source
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), " ")
            .replace(Regex("""//[^\n]*"""), " ")
    }

    /**
     * NEGATIVE CONTROL: write `ToolchainManager(this)` back into `onCreate` and
     * both halves of this go red.
     */
    @Test
    fun `the manager that outlives this screen is not built with it`() {
        assertTrue(
            "ToolchainManager(applicationContext)" in code,
            "the Toolchains screen builds its manager with something other than the " +
                "application context. An HTTP transfer runs on that manager's own " +
                "executor and reads cacheDir and filesDir from the Context it was given, " +
                "so a finished or rotated-away Activity, its adapter and its whole view " +
                "tree stay reachable from a live thread until the download ends.",
        )
        assertFalse(
            "ToolchainManager(this)" in code,
            "the screen hands itself to a ToolchainManager again",
        )
    }

    /**
     * NEGATIVE CONTROL: change the callback to capture the Activity, for instance
     * by replacing `screen.get()` with a direct `runOnUiThread`, and this goes
     * red.
     */
    @Test
    fun `the state callback reaches the screen weakly or not at all`() {
        assertTrue(
            "WeakReference(this)" in code,
            "the state callback holds this Activity directly. It outlives the screen by " +
                "design now, kept by Play Core while a download settles and by the " +
                "manager's executor on the HTTP path, so anything it captures is a leak " +
                "and a toast over the editor from a screen the user closed.",
        )
        assertTrue(
            Regex("""(?m)^\s*manager\.onStateChange = \{""").containsMatchIn(code),
            "the callback is assigned through something other than the local manager. " +
                "Naming the field instead captures the Activity as completely as naming " +
                "the Activity would, which is the correction AndroidBridge had to make " +
                "in bytecode.",
        )
    }

    /** The body of a declaration, by brace matching from it over comment-free source. */
    private fun body(declaration: String): String {
        val start = code.indexOf(declaration)
        assertTrue(start >= 0, "`$declaration` is gone from ToolchainActivity.kt")
        val open = code.indexOf('{', start)
        var depth = 0
        var i = open
        while (i < code.length) {
            if (code[i] == '{') depth += 1
            if (code[i] == '}') {
                depth -= 1
                if (depth == 0) return code.substring(open, i + 1)
            }
            i += 1
        }
        throw AssertionError("Could not find the end of `$declaration` in ToolchainActivity.kt")
    }

    /**
     * That a state report cannot put anything in front of a screen the user has
     * left, which is not the same screen as one that has been destroyed.
     *
     * `onStop` keeps the Play Core subscription while a download is outstanding,
     * because the COMPLETED branch is the only thing that copies a delivered pack
     * into `usr/`. So reports now arrive at a stopped screen by design, and the
     * only thing standing between them and a toolchain toast over the editor is
     * this gate. `destroyed` does not answer it: pressing Home stops this screen
     * and destroys nothing.
     *
     * NEGATIVE CONTROL: put `if (!screenGone.get())` back in place of the
     * lifecycle test, or delete the test outright, and this goes red. Measured
     * for both.
     */
    @Test
    fun `a stopped screen shows nothing, not merely a destroyed one`() {
        val shown = body("private fun showPackState(")
        val gate = shown.indexOf("lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)")
        val toast = shown.indexOf("Toast.makeText")
        val confirm = shown.indexOf("askForCellularConfirmation()")

        assertTrue(
            gate >= 0,
            "showPackState no longer asks the lifecycle whether this screen is in " +
                "front. A Play report landing while the user is in the editor then " +
                "toasts over it, and REQUIRES_USER_CONFIRMATION takes the foreground " +
                "from them: $shown",
        )
        assertTrue(toast >= 0 && confirm >= 0, "the guarded calls are gone; this measures nothing")
        assertTrue(
            "return" in shown.substring(gate, toast),
            "the lifecycle test does not stop anything: no return between it and the " +
                "first Toast",
        )
        assertTrue(
            gate < toast && gate < confirm,
            "a Toast or Play's confirmation window is reached before the screen is " +
                "asked whether it is in front (gate $gate, toast $toast, confirm $confirm)",
        )
    }

    /**
     * The other branch of that gate. Suppressing Play's cellular-data question is
     * only right if it is put again, since REQUIRES_USER_CONFIRMATION never
     * settles: no further report follows it, so a pack whose question was
     * swallowed waits for an answer nobody is asked for.
     *
     * NEGATIVE CONTROL: delete the `confirmationDeferred` block from `onStart`
     * and this goes red; delete the `confirmationDeferred = true` from the gate
     * and it goes red on the other assertion. Measured for both.
     */
    @Test
    fun `a confirmation asked for while the screen was away is put again on return`() {
        val shown = body("private fun showPackState(")
        val started = body("override fun onStart()")

        assertTrue(
            "confirmationDeferred = true" in shown,
            "the gate drops Play's cellular-data question instead of holding it, and " +
                "nothing else will ever ask: the download waits for good",
        )
        assertTrue(
            "confirmationDeferred" in started && "askForCellularConfirmation()" in started,
            "onStart does not put the question the gate held back, so suppressing it " +
                "turned a rude interruption into a download that never finishes",
        )
        assertTrue(
            "confirmationDeferred = false" in started,
            "onStart asks but never clears the flag, so every later onStart asks Play " +
                "again for a confirmation nothing is waiting on",
        )
    }

    /**
     * NEGATIVE CONTROL: drop either `if (outstanding.isEmpty())` guard and this
     * goes red; that is exactly the code that shipped, and it left a Play pack
     * delivered and never installed when the user pressed Home mid-download.
     */
    @Test
    fun `the subscription is only dropped when nothing is outstanding`() {
        val guarded = Regex(
            """(?m)^\s*if \(outstanding\.isEmpty\(\)\) toolchainManager\.unregisterListener\(\)"""
        ).findAll(code).count()
        val total = Regex("""toolchainManager\.unregisterListener\(\)""").findAll(code).count()

        assertTrue(
            guarded == 2,
            "expected the guarded call in onStop and in onDestroy, found $guarded. An " +
                "unguarded one unsubscribes mid-download, and the COMPLETED that copies " +
                "a delivered pack into usr/ then reaches nobody.",
        )
        assertTrue(
            total == guarded,
            "an unguarded unregisterListener() is back in the Toolchains screen: " +
                "$total calls, $guarded of them guarded",
        )
    }

    /**
     * That the "did this screen ask for it" reading is taken before the set that
     * answers it is emptied.
     *
     * `shouldReleaseSubscription` removes any settled pack, and a decline settles.
     * Taken after that call, or taken on the main thread inside `showPackState`,
     * the reading is false for every decline there is, so the gate refuses the
     * user's own tap too and the screen answers Install with nothing again. That
     * is a silent failure in the direction opposite to the one the gate exists
     * for, which is why the ordering is pinned rather than left to the comment.
     *
     * NEGATIVE CONTROL: move the `val asked` line below the
     * `shouldReleaseSubscription` call and this goes red; move the reading into
     * `showPackState` and it goes red on the first assertion. Measured for both.
     */
    @Test
    fun `what this screen asked for is read before the settled pack leaves the set`() {
        val created = body("override fun onCreate(")
        val asked = created.indexOf("val asked = packName in pending")
        val release = created.indexOf("shouldReleaseSubscription(")

        assertTrue(
            asked >= 0,
            "nothing records whether the report answers a tap this screen made, so a " +
                "decline from a claim race after a rotation announces itself on a " +
                "screen the user tapped nothing on: $created",
        )
        assertTrue(release >= 0, "the release decision is gone; this test measures nothing")
        assertTrue(
            asked < release,
            "the reading is taken after shouldReleaseSubscription, which removes any " +
                "settled pack. A decline is settled, so the reading is false for every " +
                "one of them and a tap on Install is answered with silence again " +
                "(asked $asked, release $release)",
        )
    }

    /**
     * NEGATIVE CONTROL: delete the `outstanding.add(packName)` line above
     * `install(packName)` and this goes red, and the guard above becomes an
     * `isEmpty()` that is always true.
     */
    @Test
    fun `a download this screen starts is recorded before it starts`() {
        val add = code.indexOf("outstanding.add(packName)")
        val install = code.indexOf("toolchainManager.install(packName)")

        assertTrue(add >= 0, "nothing records what this screen asked for, so the guard above is empty")
        assertTrue(install >= 0, "the install call is gone; this test is measuring nothing")
        assertTrue(
            add < install,
            "the pack is recorded after install(), which can report a failure on the " +
                "calling thread: the removal then runs against a set the pack is not in " +
                "yet, and the subscription is held for a download that never began",
        )
    }
}
