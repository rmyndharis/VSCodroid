package com.vscodroid

import com.vscodroid.util.Logger
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The WebView pre-warm the application registers, and what happens when the
 * device cannot give us one.
 *
 * The statement is an optimisation worth 200-400 ms of Chromium load, and it sat
 * unguarded at the earliest point in the process. A device with the WebView
 * package disabled, and, far more commonly, one caught while Play was swapping
 * that package, died before `SplashActivity` existed: first-run setup, the
 * symlink rebuild every reinstall needs, the toolchain repairs and the reclaim of
 * revoked SAF mirrors all need no WebView and none of them ran.
 *
 * The failure is raised from the lambda rather than by constructing a real
 * WebView, which no JVM test can do. `AndroidRuntimeException` and
 * `UnsatisfiedLinkError` are not built here either: both are android.jar stubs
 * whose constructors throw on this classpath, which would make the case pass for
 * a reason that has nothing to do with the guard. What is under test is that
 * NOTHING the warm block raises escapes, so the stand-ins only have to be one
 * exception and one error.
 */
class WebViewPreWarmTest {

    @BeforeEach
    fun setUp() {
        mockkObject(Logger)
        every { Logger.w(any(), any()) } just Runs
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    @Test
    fun `a WebView provider that cannot be resolved does not end the process`() {
        var reached = false

        preWarmWebView {
            reached = true
            // What WebViewFactory raises with no provider installed, enabled or
            // resolvable, including while one is being replaced.
            throw IllegalStateException("MissingWebViewPackageException")
        }

        assertTrue(reached, "the pre-warm never ran, so this proves nothing about the guard")
    }

    @Test
    fun `a provider whose native library will not load is not fatal either`() {
        // This arm arrives as an Error rather than an Exception, which is why the
        // catch is on Throwable. Catching Exception would leave it fatal and
        // nothing else here would notice.
        preWarmWebView { throw UnsatisfiedLinkError("libwebviewchromium.so") }
    }

    @Test
    fun `the pre-warm still runs on a device that has a provider`() {
        // The control. A guard that swallowed the call as well as its failure
        // would satisfy both cases above while quietly deleting the optimisation.
        var warmed = false

        preWarmWebView { warmed = true }

        assertTrue(warmed, "the pre-warm did not run at all")
    }

    /**
     * And which processes pay for it.
     *
     * `Application.onCreate` runs for every process instantiation, not for every
     * launch, and `NodeService` declares no `android:process`, so it shares this
     * one. Two service-only starts exist in the shipped build: the notification's
     * Stop action after the process was reclaimed, which routes straight to
     * `shutdown()`, and the START_STICKY re-delivery whose foreground promotion is
     * refused, which calls `stopSelf()` without starting anything. Both used to
     * load the whole Chromium library and build and destroy a WebView first, in a
     * process that then died without ever showing a page.
     *
     * An Activity being created is the fact itself rather than a guess at it, and
     * it costs the launch nothing: `Activity.onCreate` dispatches this callback
     * from `super.onCreate()`, before the Activity has inflated anything.
     *
     * NEGATIVE CONTROL, both measured: move `preWarmWebView(warm)` into an `init`
     * block of `FirstActivityPreWarm` and
     * `a process that only hosts a service never loads Chromium` goes red; drop
     * the `warmed` guard and `only the first Activity pays for the pre-warm` goes
     * red at two calls.
     */
    @Test
    fun `a process that only hosts a service never loads Chromium`() {
        var warmed = 0

        FirstActivityPreWarm { warmed += 1 }

        assertEquals(
            0, warmed,
            "registering the pre-warm is enough to run it, so a process created purely to " +
                "deliver a service intent still pays for a WebView it will never host",
        )
    }

    @Test
    fun `the first Activity still gets the pre-warm`() {
        var warmed = 0
        val callbacks = FirstActivityPreWarm { warmed += 1 }

        callbacks.onActivityCreated(mockk(relaxed = true), null)

        assertEquals(1, warmed, "the launch this optimisation exists for lost it")
    }

    @Test
    fun `only the first Activity pays for the pre-warm`() {
        // The callback stays registered for the life of the process, and the app
        // has more than one Activity. Chromium is loaded once; building a
        // throwaway WebView for every Activity after that buys nothing.
        var warmed = 0
        val callbacks = FirstActivityPreWarm { warmed += 1 }

        repeat(3) { callbacks.onActivityCreated(mockk(relaxed = true), null) }

        assertEquals(1, warmed, "a throwaway WebView was built for every Activity created")
    }
}
