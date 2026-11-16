package com.vscodroid

import com.vscodroid.service.EditorGateway
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * That a start away from the home port is said where the person sees it, and that
 * a stop gives the temporary port up.
 *
 * `HomePortTest` runs the decision in `ProcessManager`: home kept, the temporary port
 * used for one process, the port handed out once, the release. What it cannot see
 * is the two ends of the wire: the editor load that tells the person, and the stop
 * from the notification that lets the next start go home. Read from the source,
 * because neither is reachable without an activity and a running service.
 */
class AwayFromHomeWiringTest {

    private fun read(path: String): String = SourceScan.withoutComments(SourceScan.read(path))

    private val main by lazy { read("src/main/kotlin/com/vscodroid/MainActivity.kt") }

    @Test
    fun `the editor load tells the person once when the start could not use home`() {
        val load = SourceScan.body(main, "private fun loadVSCode(")
        val take = load.indexOf("takeAwayFromHome()")
        assertTrue(
            take >= 0,
            "loadVSCode no longer asks the service whether this start found home busy, so an " +
                "editor without the person's settings opens with nothing said",
        )
        assertTrue(
            load.indexOf("if (!isFinishing && !isDestroyed)") in 0 until take,
            "loadVSCode takes the notice without first checking that this activity can still " +
                "show it, so an activity that is going away spends the one notice of the process",
        )
        assertTrue(
            load.indexOf("showAwayFromHomeNotice(home)") > take,
            "loadVSCode no longer shows the notice for a start away from home",
        )
        assertFalse(
            load.contains("Toast"),
            "loadVSCode says it in a toast again; Android 12 and later cut a text toast to two " +
                "lines, which leaves out the port and the way back",
        )
    }

    @Test
    fun `the notice is a dialog with the whole sentence, the platform's OK and no title`() {
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
        for (call in listOf("setTitle", "setNegativeButton", "setNeutralButton", "Toast")) {
            assertFalse(
                notice.contains(call),
                "the notice gained $call; it is one message and the platform's OK, so that no " +
                    "new string needs translating",
            )
        }
    }

    @Test
    fun `the notice never covers a crash screen`() {
        assertTrue(
            SourceScan.body(main, "private fun checkPreviousCrash(").contains("crashDialog = "),
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
            page.contains("if (!isFinishing && !isDestroyed) awayFromHomeNotice?.dismiss()"),
            "a crash page dismisses the notice without asking whether the window is still " +
                "there; dismissing a dialog whose window has gone throws",
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

    @Test
    fun `a stop from the notification gives up a temporary origin`() {
        val shutdown = SourceScan.body(
            read("src/main/kotlin/com/vscodroid/service/NodeService.kt"),
            "private fun shutdown(",
        )
        val stop = shutdown.indexOf("processManager.stopServer()")
        val release = shutdown.indexOf("processManager.releaseAwayOrigin()")
        assertTrue(
            stop >= 0 && release > stop,
            "shutdown no longer gives up a temporary origin after stopping the server, so " +
                "opening the editor again in the same process inherits it instead of trying home",
        )
    }
}
