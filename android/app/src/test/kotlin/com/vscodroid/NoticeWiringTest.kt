package com.vscodroid

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class NoticeWiringTest {

    private val sourceRoot = File("src/main/kotlin")

    @Test
    fun `no direct Toast makeText call exists outside AppNotices`() {
        assertTrue(sourceRoot.isDirectory, "${sourceRoot.absolutePath} not found")
        val offenders = sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .sortedBy { it.path }
            .flatMap { file ->
                val rel = file.relativeTo(sourceRoot).path.replace(File.separatorChar, '/')
                val code = SourceScan.withoutComments(file.readText())
                val matches = Regex("""\bToast\.makeText\s*\(""").findAll(code).toList()
                if (rel == "com/vscodroid/util/AppNotices.kt") {
                    assertEquals(
                        1, matches.size,
                        "AppNotices.kt must hold the single Toast.makeText call in the app",
                    )
                    emptyList()
                } else {
                    matches.map { match ->
                        val line = code.substring(0, match.range.first).count { it == '\n' } + 1
                        "$rel:$line"
                    }
                }
            }
            .toList()

        assertEquals(
            emptyList<String>(), offenders,
            "direct Toast.makeText calls found outside AppNotices: $offenders",
        )
    }

    @Test
    fun `all three activities route notices through AppNotices and tear down on destroy`() {
        val main = SourceScan.withoutComments(SourceScan.readAll(SourceScan.MAIN_ACTIVITY_FILES))
        val toolchain = SourceScan.withoutComments(SourceScan.read("src/main/kotlin/com/vscodroid/ToolchainActivity.kt"))
        val splash = SourceScan.withoutComments(SourceScan.read("src/main/kotlin/com/vscodroid/SplashActivity.kt"))

        assertEquals(29, Regex("""\bAppNotices\.showNotice\s*\(""").findAll(main).count())
        assertEquals(2, Regex("""\bAppNotices\.showNotice\s*\(""").findAll(toolchain).count())
        assertEquals(2, Regex("""\bAppNotices\.showNotice\s*\(""").findAll(splash).count())

        for ((name, source) in listOf(
            "MainActivity.kt" to main,
            "ToolchainActivity.kt" to toolchain,
            "SplashActivity.kt" to splash,
        )) {
            val onDestroy = SourceScan.body(source, "override fun onDestroy(")
            assertTrue(
                onDestroy.contains("AppNotices.drop(this, carry = true)"),
                "$name onDestroy must drop AppNotices to avoid leaking an open dialog window, " +
                    "and hand what it held to the screen made in its place unless it is finishing",
            )
        }

        for ((name, source) in listOf(
            "MainActivity.kt" to main,
            "ToolchainActivity.kt" to toolchain,
            "SplashActivity.kt" to splash,
        )) {
            val onStart = SourceScan.body(source, "override fun onStart(")
            assertTrue(
                onStart.contains("AppNotices.flush(this)"),
                "$name onStart must show the long notices held while the screen was stopped",
            )
        }

        val helper = SourceScan.withoutComments(SourceScan.read("src/main/kotlin/com/vscodroid/util/AppNotices.kt"))
        val canShow = SourceScan.body(helper, "override fun canShowDialog(")
        assertTrue(
            canShow.contains("isAtLeast(Lifecycle.State.STARTED)") && canShow.contains("return screenCanShowDialog("),
            "a dialog goes only on a started screen; a stopped one holds the notice",
        )
        assertTrue(
            SourceScan.body(helper, "fun flush(activity: Activity)").contains("starting = true"),
            "the flush from onStart runs before the lifecycle reaches STARTED and must say the screen is starting",
        )

        val flush = SourceScan.body(helper, "fun flush(activity: Activity)")
        assertTrue(
            flush.indexOf("heldForNextScreen.remove(activity.javaClass)") in 0 until flush.indexOf("coordinators[activity] ?: return"),
            "a screen made again takes what its predecessor held before it looks for notices of its own",
        )
        assertTrue(
            flush.contains("coordinators.getOrPut(activity) { NoticeDialogCoordinator(onceKeys = shownOnceKeys) }.take(it)"),
            "what a screen made again takes goes into that screen's own coordinator",
        )
        for (member in listOf("override val isShowing: Boolean", "override fun dismiss()")) {
            val body = SourceScan.withoutComments(SourceScan.body(helper, member))
            assertTrue(
                body.contains("isUp()") && !body.contains("isDestroyed"),
                "$member is asked from onDestroy, where the screen already reads as destroyed while its dialog is still up",
            )
        }
        assertTrue(
            SourceScan.withoutComments(SourceScan.body(helper, "private fun isUp()")).contains("peekDecorView()?.parent != null"),
            "a dialog whose window the window manager has removed is not up, and dismissing it throws",
        )
        assertTrue(
            SourceScan.body(helper, "fun drop(activity: Activity, carry: Boolean = false)")
                .contains("if (carry && held != null && !activity.isFinishing) heldForNextScreen[activity.javaClass] = held"),
            "only onDestroy hands notices on, and only for a screen that is not finishing",
        )

        val errorPage = SourceScan.body(main, "private fun showErrorPage(")
        assertTrue(
            errorPage.contains("AppNotices.drop(this)") && !errorPage.contains("carry"),
            "MainActivity.showErrorPage must drop pending or open long notices",
        )
        assertTrue(
            main.contains("BlockingNoticeHost"),
            "MainActivity must implement BlockingNoticeHost so long notices defer behind crashDialog",
        )
    }

    @Test
    fun `AppNotices builds a plain guarded dialog with platform OK and no title`() {
        val helper = SourceScan.withoutComments(
            SourceScan.read("src/main/kotlin/com/vscodroid/util/AppNotices.kt"),
        )
        assertTrue(helper.contains("AlertDialog.Builder("))
        assertTrue(helper.contains(".setMessage("))
        assertTrue(helper.contains(".setPositiveButton(android.R.string.ok, null)"))
        assertTrue(helper.contains("isFinishing || activity.isDestroyed") ||
            helper.contains("activity.isFinishing || activity.isDestroyed"))
        for (forbidden in listOf("setTitle", "setNegativeButton", "setNeutralButton")) {
            assertFalse(helper.contains(forbidden), "notice dialog must stay plain without $forbidden")
        }
    }

    @Test
    fun `heap override suspended foreground notification stays untouched`() {
        val service = SourceScan.withoutComments(
            SourceScan.read("src/main/kotlin/com/vscodroid/service/NodeService.kt"),
        )
        val degradable = SourceScan.body(service, "private fun degradableNotificationText(")
        assertTrue(degradable.contains("R.string.heap_override_suspended"))
        val notif = SourceScan.body(service, "private fun createNotification(")
        assertTrue(notif.contains("NotificationCompat.BigTextStyle().bigText(text)"))
        assertFalse(notif.contains("AppNotices"))
    }

    @Test
    fun `storage low and multi-device-folder notices pass process-once keys through AppNotices`() {
        val main = SourceScan.withoutComments(SourceScan.read("src/main/kotlin/com/vscodroid/MainActivity.kt"))
        val dialogs = SourceScan.withoutComments(SourceScan.read("src/main/kotlin/com/vscodroid/MainActivityDialogs.kt"))
        val helper = SourceScan.withoutComments(SourceScan.read("src/main/kotlin/com/vscodroid/util/AppNotices.kt"))

        val storageCheck = SourceScan.body(dialogs, "internal fun MainActivity.checkStorageHealth(")
        assertTrue(
            storageCheck.contains("AppNotices.ONCE_KEY_STORAGE_LOW"),
            "checkStorageHealth must pass a process-once key to avoid repeating storage_low_warning on activity recreate",
        )
        val bridge = SourceScan.body(main, "private fun initBridge(")
        assertTrue(
            bridge.contains("AppNotices.ONCE_KEY_MULTI_DEVICE_FOLDERS"),
            "onWorkspaceRoots must pass a process-once key to avoid repeating saf_workspace_multiple_folders on reload",
        )
        assertTrue(
            helper.contains("ConcurrentHashMap.newKeySet<String>()"),
            "AppNotices process-once tracking must live on the process singleton rather than an Activity field",
        )
    }

    /**
     * The slow start notice is recorded with the topic that takes it back once the server is up. Raised
     * from inside the late readiness poll, a coroutine on the service's main dispatcher that a JVM test
     * cannot reach, so this is read from the source; what the screens do with the topic, and that a
     * terminal notice is recorded and not raised, is run in `SlowStartNoticeTest`.
     */
    @Test
    fun `the slow start notice is recorded with its topic`() {
        val service = SourceScan.withoutComments(SourceScan.read("src/main/kotlin/com/vscodroid/service/NodeService.kt"))
        assertTrue(
            service.contains("reportStartupNotice(getString(R.string.status_server_slow_start), topic = TOPIC_SLOW_START)"),
            "the slow start notice is recorded without its topic",
        )
        val main = SourceScan.withoutComments(SourceScan.readAll(SourceScan.MAIN_ACTIVITY_FILES))
        assertFalse(
            main.contains("AppNotices.retract("),
            "a notice is taken back by its text again, which a language change makes miss",
        )
    }

    /**
     * From Android 12 the storage warning does not fit a toast, so it is a dialog, which goes only on
     * a started screen. `onCreate` runs before the start, and a notice with a process-once key raised
     * there is held, its key unspent, and shown whole by the flush at `onStart` (`AppNoticesTest`, "a
     * notice with a once key waits for the screen to start"). So the check needs no post to the
     * window; what is pinned is that it runs once per screen, from `onCreate`.
     */
    @Test
    fun `the storage warning is asked for once from onCreate and held for the start`() {
        val main = SourceScan.withoutComments(SourceScan.read("src/main/kotlin/com/vscodroid/MainActivity.kt"))
        val create = SourceScan.body(main, "override fun onCreate(")

        assertEquals(
            1,
            Regex("""\bcheckStorageHealth\(\)""").findAll(create).count(),
            "onCreate no longer asks for the storage warning, or asks twice",
        )
        val helper = SourceScan.withoutComments(SourceScan.read("src/main/kotlin/com/vscodroid/util/AppNotices.kt"))
        assertTrue(
            SourceScan.body(helper, "internal fun showNoticeWithPolicy(")
                .contains("coordinator.holdOnce(message, processOnceKey)"),
            "a once notice raised before the screen has started is no longer held for the start",
        )
    }
}
