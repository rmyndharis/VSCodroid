package com.vscodroid

import com.vscodroid.keyboard.KeyboardDismissal
import com.vscodroid.service.NodeService
import com.vscodroid.webview.DownloadCoordinator
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.InvocationTargetException

/**
 * The callbacks a screen puts on the service, and which of them its teardown may take off.
 *
 * The service is started as well as bound, so it outlives every screen, and a screen leaves nothing
 * of itself on it: the callbacks close over the screen, its WebView and its views. But Stop Server
 * finishes the screen and removes its task, and the app opened again just after that makes a new
 * screen that binds to the same service, and installs its own callbacks, while the old one is still
 * waiting to be destroyed. The teardown that cleared whatever it found there took the new screen's
 * callbacks away: the server came up behind a loading page that nothing ever replaced. Measured on
 * an API 36 emulator, a relaunch half a second after Stop Server left "Starting server..." on the
 * screen for good, with the server ready behind it.
 */
class ServiceCallbacksTeardownTest {

    private class OnTheService {
        var ready: ((Int, Boolean) -> Unit)? = null
        var error: ((String) -> Unit)? = null
        var gaveUp: ((Boolean) -> Unit)? = null
        var stopped: (() -> Unit)? = null
    }

    private fun serviceOver(held: OnTheService): NodeService {
        val service = mockk<NodeService>(relaxed = true)
        every { service.onServerReady } answers { held.ready }
        every { service.onServerReady = any() } answers { held.ready = firstArg() }
        every { service.onServerError } answers { held.error }
        every { service.onServerError = any() } answers { held.error = firstArg() }
        every { service.onServerGaveUp } answers { held.gaveUp }
        every { service.onServerGaveUp = any() } answers { held.gaveUp = firstArg() }
        every { service.onServerStopped } answers { held.stopped }
        every { service.onServerStopped = any() } answers { held.stopped = firstArg() }
        return service
    }

    private fun setField(screen: MainActivity, name: String, value: Any?) {
        MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.set(screen, value)
    }

    private fun screenBoundTo(service: NodeService, installed: Array<Any?>?): MainActivity {
        val screen = mockk<MainActivity>(relaxed = true)
        every { screen["onDestroy"]() } answers { callOriginal() }
        // Filled by the constructor, which a mock does not run; onDestroy reaches both before the service.
        setField(screen, "keyboardDismissal", mockk<KeyboardDismissal>(relaxed = true))
        setField(screen, "downloads", mockk<DownloadCoordinator>(relaxed = true))
        setField(screen, "nodeService", service)
        setField(screen, "serviceCallbacks", installed)
        return screen
    }

    private fun destroy(screen: MainActivity) {
        try {
            MainActivity::class.java.getDeclaredMethod("onDestroy").apply { isAccessible = true }.invoke(screen)
        } catch (e: InvocationTargetException) {
            // The framework's own onDestroy, last in the override, has no state on a JVM mock.
            val cause = e.cause ?: throw e
            assertTrue(
                cause.message?.startsWith("Method onDestroy in android.app.Activity not mocked.") == true,
                "unexpected teardown failure: $cause",
            )
        }
    }

    private val readyA: (Int, Boolean) -> Unit = { _, _ -> }
    private val errorA: (String) -> Unit = { }
    private val gaveUpA: (Boolean) -> Unit = { }
    private val stoppedA: () -> Unit = { }

    @AfterEach
    fun tearDown() = unmockkAll()

    @Test
    fun `a screen destroyed while its callbacks are still on the service takes them off`() {
        val held = OnTheService().apply { ready = readyA; error = errorA; gaveUp = gaveUpA; stopped = stoppedA }
        val screen = screenBoundTo(serviceOver(held), arrayOf(readyA, errorA, gaveUpA, stoppedA))

        destroy(screen)

        assertNull(held.ready, "the destroyed screen stayed reachable from the service")
        assertNull(held.error)
        assertNull(held.gaveUp)
        assertNull(held.stopped)
    }

    @Test
    fun `a screen destroyed after a newer one installed its own leaves the newer one's callbacks`() {
        val readyB: (Int, Boolean) -> Unit = { _, _ -> }
        val errorB: (String) -> Unit = { }
        val gaveUpB: (Boolean) -> Unit = { }
        val stoppedB: () -> Unit = { }
        val held = OnTheService().apply { ready = readyB; error = errorB; gaveUp = gaveUpB; stopped = stoppedB }
        val old = screenBoundTo(serviceOver(held), arrayOf(readyA, errorA, gaveUpA, stoppedA))

        destroy(old)

        assertSame(readyB, held.ready, "the new screen lost the callback that loads the editor when the server is ready")
        assertSame(errorB, held.error)
        assertSame(gaveUpB, held.gaveUp)
        assertSame(stoppedB, held.stopped)
    }

    @Test
    fun `each callback is judged on its own`() {
        val readyB: (Int, Boolean) -> Unit = { _, _ -> }
        val held = OnTheService().apply { ready = readyB; error = errorA; gaveUp = gaveUpA; stopped = stoppedA }
        val old = screenBoundTo(serviceOver(held), arrayOf(readyA, errorA, gaveUpA, stoppedA))

        destroy(old)

        assertSame(readyB, held.ready)
        assertNull(held.error)
        assertNull(held.gaveUp)
        assertNull(held.stopped)
    }

    @Test
    fun `a screen that never installed callbacks leaves those on the service alone`() {
        val held = OnTheService().apply { ready = readyA; error = errorA; gaveUp = gaveUpA; stopped = stoppedA }
        val screen = screenBoundTo(serviceOver(held), null)

        destroy(screen)

        assertNotNull(held.ready, "a screen that put nothing on the service took another screen's callbacks off it")
        assertNotNull(held.error)
        assertNotNull(held.gaveUp)
        assertNotNull(held.stopped)
    }
}
