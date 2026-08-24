package com.vscodroid.setup

import android.content.Context
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Which screen the running extraction reports to, when there is more than one.
 *
 * `setupMutex` is process-wide, so the unpack and the screen watching it need
 * not belong to the same [FirstRunSetup]. SplashActivity declares neither locale
 * nor font scale nor display size in its `configChanges`, so changing any of
 * them during an extraction that runs for minutes destroys the activity and
 * creates a new one while the unpack carries on. With the sink held per
 * instance, that unpack went on reporting into the DESTROYED activity's closure,
 * which kept it and its whole view hierarchy reachable for the rest of the run,
 * while the replacement blocked on the lock for the same minutes with its bar at
 * 0 and nothing to move it. A bar stuck at 0% for minutes is what makes someone
 * force-quit, and that costs the whole extraction.
 *
 * So installing a sink is a handover, and giving one back is identity-checked.
 * Both are reachable from a plain JVM test: the property is public and
 * `detachProgress` is on the companion.
 *
 * The sink is process-wide state, which is exactly the point, so each test here
 * hands it back afterwards. Without that a leftover from one case is what the
 * next one reads.
 */
class ProgressSinkHandoverTest {

    private lateinit var context: Context

    private val first: (String, Int) -> Unit = { _, _ -> }
    private val second: (String, Int) -> Unit = { _, _ -> }

    @BeforeEach
    fun setUp() {
        context = mockk(relaxed = true)
    }

    @AfterEach
    fun tearDown() {
        FirstRunSetup(context).onProgress = null
    }

    /**
     * NEGATIVE CONTROL: make `progressSink` an instance field again. The second
     * instance then answers null and this reddens on the first assertion.
     */
    @Test
    fun `a screen created during the unpack reads the sink the previous one installed`() {
        val watching = FirstRunSetup(context)
        watching.onProgress = first

        val relaunched = FirstRunSetup(context)

        assertSame(
            first, relaunched.onProgress,
            "a screen created while the unpack runs cannot see where progress is going, " +
                "so its bar sits at 0 for the rest of the extraction",
        )

        relaunched.onProgress = second

        assertSame(
            second, watching.onProgress,
            "installing a sink is a handover: the newest screen is the one that is drawing",
        )
    }

    /**
     * The half that needs identity rather than ordering.
     *
     * Two Splash instances can exist at once -- MainActivity hands a VIEW
     * launch back to a fresh Splash while a launcher-task one may be mid-setup,
     * which `runSetup`'s own comment names -- and then the departing
     * one's `onDestroy` can run after the replacement has installed its own sink,
     * so a blanket clear there silences the screen the user is looking at.
     *
     * Not the config-change relaunch, which this used to name and which runs the
     * other way round: `ActivityThread.handleRelaunchActivityInner` destroys the
     * old instance before it launches the new one, so ordering alone would have
     * covered that case and the momentary null between the two is harmless.
     *
     * NEGATIVE CONTROL: drop the `progressSink === sink` test from
     * `detachProgress`. The first assertion then finds null.
     */
    @Test
    fun `a departing screen does not silence the one that replaced it`() {
        FirstRunSetup(context).onProgress = first
        FirstRunSetup(context).onProgress = second

        FirstRunSetup.detachProgress(first)

        assertSame(
            second, FirstRunSetup(context).onProgress,
            "the screen that went away took the replacement's sink with it",
        )

        FirstRunSetup.detachProgress(second)

        assertNull(
            FirstRunSetup(context).onProgress,
            "the last screen gave nothing back, so the extraction keeps reporting into a " +
                "closure that holds a destroyed Activity",
        )
    }

    /** Null is not an identity anything can claim. */
    @Test
    fun `handing back nothing changes nothing`() {
        FirstRunSetup(context).onProgress = first

        FirstRunSetup.detachProgress(null)

        assertSame(first, FirstRunSetup(context).onProgress)
    }
}
