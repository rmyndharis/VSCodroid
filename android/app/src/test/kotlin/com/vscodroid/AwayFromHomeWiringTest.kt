package com.vscodroid

import android.widget.Toast
import com.vscodroid.keyboard.KeyboardDismissal
import com.vscodroid.service.EditorGateway
import com.vscodroid.service.NodeService
import com.vscodroid.service.mockEditorGateway
import com.vscodroid.util.AppNotices
import com.vscodroid.util.Logger
import com.vscodroid.webview.DownloadCoordinator
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.InvocationTargetException

/**
 * That a start away from the home port is said where the person sees it, and that
 * a stop gives the temporary port up.
 *
 * `HomePortTest` runs the decision in `ProcessManager`: home kept, the temporary port
 * used for one process, the port handed out once, the release. What it cannot see
 * is the two ends of the wire: the editor load that tells the person, and the stop
 * from the notification that lets the next start go home. The load is run here on an
 * activity whose framework half is mocked, and the stop in `NodeServiceStopTest`. The
 * dialog itself is read from the source: androidx's `AlertDialog.Builder` needs a real
 * context and cannot be built on the JVM.
 */
class AwayFromHomeWiringTest {

    private fun read(path: String): String = SourceScan.withoutComments(SourceScan.read(path))

    private val main by lazy { read("src/main/kotlin/com/vscodroid/MainActivity.kt") }

    /**
     * The editor load, run on an activity whose framework half is mocked, for a start that found home
     * busy: it asks the service, and shows the dialog with the home port. Never as a toast, which
     * Android 12 and later cut to two lines, leaving out the port and the way back. A screen that is
     * going away, finishing or already destroyed, does not ask at all, so the one notice of the process
     * is not spent on it.
     */
    @Test
    fun `the editor load tells the person once when the start could not use home`() {
        val screen = mockk<MainActivity>(relaxed = true)
        val service = mockk<NodeService>(relaxed = true)
        setField(screen, "tag", "MainActivity")
        setField(screen, "nodeService", service)
        mockkObject(Logger)
        every { Logger.w(any(), any(), any()) } just Runs
        every { Logger.i(any(), any()) } just Runs
        mockkObject(AppNotices)
        mockkStatic(Toast::class)
        every { screen["loadVSCode"](any<Int>(), any<String>(), isNull<String>()) } answers { callOriginal() }
        val load = MainActivity::class.java.getDeclaredMethod(
            "loadVSCode", Int::class.javaPrimitiveType, String::class.java, String::class.java,
        ).apply { isAccessible = true }

        every { service.takeAwayFromHome() } returns 13337
        load.invoke(screen, 13340, "/projects", null)
        verify(exactly = 1) { screen["showAwayFromHomeNotice"](13337) }

        every { service.takeAwayFromHome() } returns 0
        load.invoke(screen, 13340, "/projects", null)
        verify(exactly = 1) { screen["showAwayFromHomeNotice"](any<Int>()) }

        every { screen.isFinishing } returns true
        every { service.takeAwayFromHome() } returns 13337
        load.invoke(screen, 13340, "/projects", null)
        verify(exactly = 2) { service.takeAwayFromHome() }
        verify(exactly = 1) { screen["showAwayFromHomeNotice"](any<Int>()) }

        // Destroyed and not finishing: a screen the platform took down without finishing it, which a
        // configuration change the manifest does not list does, is as unable to show the dialog.
        every { screen.isFinishing } returns false
        every { screen.isDestroyed } returns true
        load.invoke(screen, 13340, "/projects", null)
        verify(exactly = 2) { service.takeAwayFromHome() }
        verify(exactly = 1) { screen["showAwayFromHomeNotice"](any<Int>()) }

        verify(exactly = 0) { Toast.makeText(any<android.content.Context>(), any<CharSequence>(), any()) }
        verify(exactly = 0) { AppNotices.showNotice(any(), any<CharSequence>(), any(), any(), any(), any()) }
        verify(exactly = 0) { AppNotices.showNotice(any(), any<Int>(), any(), any(), any()) }
    }

    @Test
    fun `the notice is a dialog with the whole sentence, the platform's OK, Stop Server and no title`() {
        val notice = SourceScan.body(main, "private fun showAwayFromHomeNotice(")
        assertTrue(
            notice.contains("AlertDialog.Builder(this)"),
            "the notice for a start away from home is no longer a dialog",
        )
        assertTrue(
            notice.contains(".setMessage(getString(R.string.notice_editor_away_from_home, home))"),
            "the dialog no longer carries the notice, with the home port in it",
        )
        assertTrue(
            notice.contains(".setPositiveButton(android.R.string.ok, null)"),
            "the dialog's one button is no longer the platform's OK",
        )
        for (call in listOf("setTitle", "setNegativeButton", "Toast")) {
            assertFalse(
                notice.contains(call),
                "the notice gained $call; it is one message, the platform's OK and the " +
                    "notification's own Stop Server, so that no new string needs translating",
            )
        }
        assertTrue(
            notice.contains(".setNeutralButton(R.string.action_stop)") &&
                notice.contains("setAction(NodeService.ACTION_STOP)"),
            "the notice sends the person to Stop Server, and a phone with notifications turned " +
                "off shows no notification: the dialog has to offer the same action",
        )
    }

    /**
     * The service hands the home port out once. A dialog nobody answered, taken down with its
     * activity (a font size or language change makes the activity again, and closing the app from
     * the recent apps ends it while the server goes on) or by a crash page, was the only
     * explanation of where the settings went, so the port goes back to the gateway and the next
     * editor load says it again. After Stop Server the hand-back is spent on a gateway that has
     * released the port, and the next start's bind writes the note again.
     */
    @Test
    fun `a notice nobody answered is handed back when its screen is taken down`() {
        val notice = SourceScan.body(main, "private fun showAwayFromHomeNotice(")
        assertTrue(
            notice.indexOf("awayFromHomeUnanswered = home") in 0 until notice.indexOf("fun show()"),
            "the port is not kept from the moment the notice is asked for, so one still waiting " +
                "behind the previous-crash dialog is lost with its activity",
        )
        assertTrue(
            notice.contains(".setOnDismissListener { awayFromHomeAnswered() }"),
            "answering the dialog no longer ends the keeping of its port",
        )
        assertTrue(
            SourceScan.body(main, "private fun awayFromHomeAnswered(").contains("awayFromHomeUnanswered = 0"),
            "an answered notice would be said again by the next activity",
        )
        assertTrue(
            notice.indexOf("awayFromHomeListener = EditorGateway.instance.listenersOpened") in 0 until notice.indexOf("fun show()"),
            "the notice no longer records the listener it was said on, so it cannot be handed back to it alone",
        )
        val destroy = SourceScan.body(main, "override fun onDestroy(")
        val handBack = destroy.indexOf("returnUnansweredAwayFromHome()")
        assertTrue(
            handBack >= 0,
            "an activity that is taken down no longer hands the notice to the next editor load",
        )
        assertTrue(
            destroy.indexOf("awayFromHomeNotice?.setOnDismissListener(null)") in 0 until handBack,
            "the dismiss in onDestroy runs the answer listener first, which forgets the port before it is " +
                "handed back",
        )
        assertTrue(
            destroy.contains("awayFromHomeNotice?.takeIf { it.isShowing }?.dismiss()"),
            "a dialog left up when its activity is destroyed is a leaked window",
        )
        assertTrue(
            SourceScan.body(main, "private fun showErrorPage(").contains("returnUnansweredAwayFromHome()"),
            "a crash page takes the notice down for good, and the load after it says nothing",
        )
        assertTrue(
            SourceScan.body(main, "override fun waitForBlockingNoticeDismiss(")
                .contains("away.setOnDismissListener { awayFromHomeAnswered() }"),
            "a notice waiting behind this dialog replaces its dismiss listener, and the answer is lost",
        )
    }

    private fun setField(screen: MainActivity, name: String, value: Any?) {
        MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.set(screen, value)
    }

    // What showAwayFromHomeNotice records before it shows anything; the dialog itself needs a window.
    private fun said(screen: MainActivity, gateway: EditorGateway, home: Int) {
        setField(screen, "awayFromHomeUnanswered", home)
        setField(screen, "awayFromHomeListener", gateway.listenersOpened)
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    /**
     * Stop Server releases the temporary origin and closes the editor, and the screen it closes is
     * destroyed only later: after the next resumed screen goes idle, or at the system's timeout. A
     * start opened in between binds its own listener and writes its own note. The late hand-back must
     * not reach that listener: at home the next load said another app holds home, and away again it
     * said the notice a second time.
     */
    @Test
    fun `an unanswered notice goes back only to the listener it was said on`() {
        val gateway = mockEditorGateway()
        val screen = mockk<MainActivity>(relaxed = true)
        every { screen["returnUnansweredAwayFromHome"]() } answers { callOriginal() }
        val handBack = MainActivity::class.java.getDeclaredMethod("returnUnansweredAwayFromHome")
            .apply { isAccessible = true }
        val home = 13337
        val away = gateway.bind(13340)

        said(screen, gateway, home)
        handBack.invoke(screen)
        handBack.invoke(screen)
        assertEquals(home, gateway.takeAwayFromHome(), "a screen made again on the same origin is not told")
        assertEquals(0, gateway.takeAwayFromHome(), "the port went back twice")

        said(screen, gateway, home)
        gateway.release(away)
        handBack.invoke(screen)
        assertEquals(0, gateway.takeAwayFromHome(), "handed back to a gateway that has released the origin")

        said(screen, gateway, home)
        gateway.release(away)
        gateway.bind(home)
        gateway.noteAwayFromHome(0)
        handBack.invoke(screen)
        assertEquals(0, gateway.takeAwayFromHome(), "the start that went home is told another app holds home")
        gateway.release(home)

        val first = gateway.bind(13341)
        said(screen, gateway, home)
        gateway.release(first)
        gateway.bind(13342)
        gateway.noteAwayFromHome(home)
        assertEquals(home, gateway.takeAwayFromHome(), "the start that went away again is told by its own note")
        handBack.invoke(screen)
        assertEquals(0, gateway.takeAwayFromHome(), "the start that went away again is told a second time")
    }

    /**
     * Run rather than read: a guard on isFinishing has many spellings, braced, negated, on `this`, and
     * a pattern over the source missed each one it did not name.
     */
    @Test
    fun `a screen that is finishing hands its unanswered notice back`() {
        val gateway = mockEditorGateway()
        val screen = mockk<MainActivity>(relaxed = true)
        every { screen.isFinishing } returns true
        every { screen["returnUnansweredAwayFromHome"]() } answers { callOriginal() }
        every { screen["onDestroy"]() } answers { callOriginal() }
        // Filled by the constructor, which a mock does not run; onDestroy reaches both before the hand-back.
        setField(screen, "keyboardDismissal", mockk<KeyboardDismissal>(relaxed = true))
        setField(screen, "downloads", mockk<DownloadCoordinator>(relaxed = true))
        gateway.bind(13340)
        said(screen, gateway, 13337)

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

        assertEquals(
            13337,
            gateway.takeAwayFromHome(),
            "a screen closed from the recent apps keeps the notice to itself, while the service and the " +
                "editor on the temporary port go on running and the next load says nothing",
        )
    }

    @Test
    fun `the notice never covers a crash screen`() {
        val dialogs = read("src/main/kotlin/com/vscodroid/MainActivityDialogs.kt")
        assertTrue(
            SourceScan.body(dialogs, "internal fun MainActivity.checkPreviousCrash(").contains("crashDialog = "),
            "the previous-crash dialog is no longer held, so the notice cannot wait for it",
        )
        val notice = SourceScan.body(main, "private fun showAwayFromHomeNotice(")
        assertTrue(
            notice.contains("crash.isShowing") && notice.contains("crash.setOnDismissListener { show() }"),
            "the notice no longer waits for an open previous-crash dialog, and comes up over it",
        )
        assertTrue(
            notice.contains("if (isFinishing || isDestroyed) return"),
            "the notice is shown without checking that the activity is still there; a dialog " +
                "on a window that has gone throws",
        )
        val page = SourceScan.body(main, "private fun showErrorPage(")
        assertTrue(
            page.contains("crashDialog?.setOnDismissListener(null)") &&
                page.contains("awayFromHomeNotice?.dismiss()"),
            "a crash page no longer takes the notice down, or a notice still waiting for the " +
                "previous-crash dialog comes up over the page",
        )
        assertTrue(
            page.contains("if (!isDestroyed) awayFromHomeNotice?.dismiss()"),
            "a crash page dismisses the notice without asking whether the window is still " +
                "there, which throws once it has gone, or leaves the notice up over a screen " +
                "that is only finishing and still has its window",
        )
    }

    @Test
    fun `only the service's take reaches the notice`() {
        assertEquals(
            2,
            Regex("""showAwayFromHomeNotice\(""").findAll(main).count(),
            "the notice is declared and called from loadVSCode, behind the service's take, and " +
                "nowhere else; another caller would show it again in an activity recreated in the " +
                "same process",
        )
    }

    @Test
    fun `the process-wide gateway hands the home port out once`() {
        EditorGateway().use { gateway ->
            gateway.noteAwayFromHome(13338)
            assertEquals(13338, gateway.takeAwayFromHome(), "the first editor load of that start is told")
            assertEquals(
                0,
                gateway.takeAwayFromHome(),
                "a later load, an activity recreated in the same process among them, is not told again",
            )
        }
    }
}
