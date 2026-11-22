package com.vscodroid.util

import android.app.Activity
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

class AppNoticesTest {

    private class FakeNoticeWindow(
        var text: String,
        private val onDismissCallback: () -> Unit,
    ) : NoticeWindow {
        override var isShowing: Boolean = true
        val messageUpdates = mutableListOf<String>()
        var dismissCount = 0

        override fun setMessage(text: CharSequence) {
            this.text = text.toString()
            messageUpdates += this.text
        }

        override fun dismiss() {
            dismissCount += 1
            isShowing = false
        }

        fun simulateUserDismiss() {
            if (!isShowing) return
            isShowing = false
            onDismissCallback()
        }
    }

    private class FakeNoticeHost(
        var canShow: Boolean = true,
        var blocking: Boolean = false,
        // A screen that cannot show a dialog is gone unless a test says it is only stopped.
        var canHold: Boolean = canShow,
        var fitsInOneToast: Boolean = false,
    ) : NoticeHostState {
        val createdWindows = mutableListOf<FakeNoticeWindow>()
        val toasts = mutableListOf<String>()
        var blockingWaiter: (() -> Unit)? = null
        var clearWaiterCount = 0

        override fun canShowDialog(): Boolean = canShow

        override fun canHoldNotices(): Boolean = canHold

        override fun toastFits(text: CharSequence): Boolean = fitsInOneToast

        override fun isBlockingDialogShowing(): Boolean = blocking

        override fun waitForBlockingDialogDismiss(onDismissed: () -> Unit) {
            blockingWaiter = onDismissed
        }

        override fun clearBlockingWaiter() {
            blockingWaiter = null
            clearWaiterCount += 1
        }

        override fun createAndShowDialog(text: CharSequence, onDismissed: () -> Unit): NoticeWindow {
            val win = FakeNoticeWindow(text.toString(), onDismissed)
            createdWindows += win
            return win
        }

        override fun showToast(text: CharSequence) {
            toasts += text.toString()
        }

        fun dismissBlockingDialog() {
            blocking = false
            val cb = blockingWaiter
            blockingWaiter = null
            cb?.invoke()
        }
    }

    @Test
    fun `toast display width counts wide CJK and Hangul code points as two`() {
        assertEquals(5, toastDisplayWidth("abcde"))
        assertEquals(6, toastDisplayWidth("日本語"))
        assertEquals(4, toastDisplayWidth("한글"))
        assertEquals(4, toastDisplayWidth("中文"))
        // CJK Extension B supplementary plane (U+20000) is 1 code point in UTF-16 surrogate pair, width 2.
        assertEquals(2, toastDisplayWidth("\uD840\uDC00"))
        // Ordinary emoji (U+1F600) is outside the CJK wide ranges, counted as 1.
        assertEquals(1, toastDisplayWidth("\uD83D\uDE00"))
        assertTrue(isWideToastCodePoint('字'.code))
        assertTrue(isWideToastCodePoint('Ａ'.code))
        assertFalse(isWideToastCodePoint('A'.code))
    }

    @Test
    fun `fitsInToast respects the 75 width ceiling on Android 12 and later`() {
        assertTrue(fitsInToast("a".repeat(75), fontScale = 1.0f, sdkInt = 31))
        assertFalse(fitsInToast("a".repeat(76), fontScale = 1.0f, sdkInt = 31))

        assertTrue(fitsInToast("字".repeat(37), fontScale = 1.0f, sdkInt = 35))
        assertFalse(fitsInToast("字".repeat(38), fontScale = 1.0f, sdkInt = 35))
    }

    @Test
    fun `fitsInToast scales capacity by configuration fontScale`() {
        // At fontScale 2.0, half as many fit: 37 Latin chars fit (74 <= 75), 38 exceed (76 > 75).
        assertTrue(fitsInToast("a".repeat(37), fontScale = 2.0f, sdkInt = 33))
        assertFalse(fitsInToast("a".repeat(38), fontScale = 2.0f, sdkInt = 33))

        // Smaller font scale allows proportionally longer notices.
        assertTrue(fitsInToast("a".repeat(88), fontScale = 0.85f, sdkInt = 33))
        assertFalse(fitsInToast("a".repeat(89), fontScale = 0.85f, sdkInt = 33))

        // Corrupted or non-positive fontScale falls back to 1.0 rather than suppressing all toasts.
        assertTrue(fitsInToast("a".repeat(75), fontScale = 0f, sdkInt = 33))
        assertTrue(fitsInToast("a".repeat(75), fontScale = Float.NaN, sdkInt = 33))
        assertFalse(fitsInToast("a".repeat(76), fontScale = 0f, sdkInt = 33))
    }

    /**
     * A line break starts a new line of the pill whatever room the line before it had left, so a
     * text is measured line by line. Counted as one narrow unit, two notices joined by a blank line
     * measured as one short line, and the toast that joins them showed the first alone while its
     * count said nothing was missing.
     */
    @Test
    fun `fitsInToast counts the lines a line break starts`() {
        assertTrue(fitsInToast("First line\nSecond line", fontScale = 1.0f, sdkInt = 31))
        assertFalse(fitsInToast("A\n\nB", fontScale = 1.0f, sdkInt = 31), "three lines in a two-line toast")
        assertFalse(
            fitsInToast("a".repeat(40) + "\nb", fontScale = 1.0f, sdkInt = 31),
            "a first line that wraps leaves no room for a second",
        )
        assertTrue(fitsInToast("a".repeat(37) + "\nb", fontScale = 1.0f, sdkInt = 31))
        assertTrue(fitsInToast("A\n\nB", fontScale = 1.0f, sdkInt = 30), "below Android 12 a toast wraps")
        assertEquals("(+1) A\n\nB", noticesToast(listOf("A", "B"), 0) { fitsInToast(it, 1f, 31) })
    }

    @Test
    fun `fitsInToast always returns true below Android 12`() {
        val hugeCjk = "日本語テスト".repeat(50)
        assertTrue(fitsInToast(hugeCjk, fontScale = 2.0f, sdkInt = 30))
        assertTrue(fitsInToast(hugeCjk, fontScale = 2.0f, sdkInt = 29))
        assertTrue(fitsInToast(hugeCjk, fontScale = 1.0f, sdkInt = 0))
    }

    @Test
    fun `consecutive long notices append to the open dialog with blank lines up to the bound`() {
        val host = FakeNoticeHost()
        val coordinator = NoticeDialogCoordinator(maxEntries = MAX_COMBINED_NOTICE_ENTRIES)

        coordinator.enqueueAndShow("First notice", host)
        assertEquals(1, host.createdWindows.size)
        assertEquals("First notice", host.createdWindows[0].text)

        coordinator.enqueueAndShow("Second notice", host)
        assertEquals(1, host.createdWindows.size, "must append rather than stacking dialogs")
        assertEquals("First notice\n\nSecond notice", host.createdWindows[0].text)

        coordinator.enqueueAndShow("Third notice", host)
        coordinator.enqueueAndShow("Fourth notice", host)
        coordinator.enqueueAndShow("Fifth notice", host)
        coordinator.enqueueAndShow("Sixth notice", host)
        assertEquals(1, host.createdWindows.size)
        assertEquals(MAX_COMBINED_NOTICE_ENTRIES, coordinator.entryCount)
        assertEquals(
            "Second notice\n\nThird notice\n\nFourth notice\n\nFifth notice\n\nSixth notice",
            host.createdWindows[0].text,
        )

        host.createdWindows[0].simulateUserDismiss()
        assertEquals(0, coordinator.entryCount)
        assertNull(coordinator.currentText)

        coordinator.enqueueAndShow("Fresh notice", host)
        assertEquals(2, host.createdWindows.size)
        assertEquals("Fresh notice", host.createdWindows[1].text)
    }

    /**
     * What waits for a notice dialog: the editor's start on the splash screen, whose finish would
     * take the dialog down unread. It runs when the dialog closes, whoever closes it, and only then.
     */
    @Test
    fun `an action waits for the open dialog and runs once it is closed`() {
        val host = FakeNoticeHost()
        val coordinator = NoticeDialogCoordinator()
        var ran = 0

        assertFalse(coordinator.afterDialog { ran += 1 }, "with no dialog open there is nothing to wait for")
        assertEquals(0, ran, "an action with nothing to wait for is the caller's to run, not the coordinator's")

        coordinator.enqueueAndShow("First long notice", host)
        assertTrue(coordinator.afterDialog { ran += 1 })
        coordinator.enqueueAndShow("Second long notice", host)
        assertEquals(0, ran, "the action ran while the dialog was still open")

        host.createdWindows.single().simulateUserDismiss()
        assertEquals(1, ran)

        coordinator.enqueueAndShow("Later notice", host)
        host.createdWindows.last().simulateUserDismiss()
        assertEquals(1, ran, "an action ran a second time, for a dialog it was not waiting on")
    }

    @Test
    fun `an action waiting on a dialog the coordinator closes still runs`() {
        val host = FakeNoticeHost()
        val coordinator = NoticeDialogCoordinator()
        var ran = 0
        coordinator.enqueueAndShow("Slow start", host, topic = "slow")
        assertTrue(coordinator.afterDialog { ran += 1 })

        coordinator.retractTopic("slow")

        assertEquals(1, host.createdWindows.single().dismissCount)
        assertEquals(1, ran, "a dialog taken back left what waited on it waiting for ever")

        // The other close of the coordinator's own: what is left on the dialog is a notice another
        // screen has shown meanwhile, so the next start takes it off and closes the dialog.
        val spent = mutableSetOf<String>()
        val keyed = NoticeDialogCoordinator(onceKeys = spent)
        keyed.enqueueAndShow("Taken back", host, topic = "gone")
        keyed.holdOnce("Shown elsewhere", "once")
        keyed.retractTopic("gone")
        val window = host.createdWindows.last()
        assertTrue(window.isShowing)
        assertTrue(keyed.afterDialog { ran += 1 })
        spent += "once"

        keyed.flush(host)

        assertEquals(1, window.dismissCount, "the dialog left with nothing on it was not closed")
        assertEquals(2, ran, "a dialog closed at the start left what waited on it waiting for ever")
    }

    @Test
    fun `a screen going away ends what waited on its dialog without running it`() {
        val host = FakeNoticeHost()
        val coordinator = NoticeDialogCoordinator()
        var ran = 0
        coordinator.enqueueAndShow("Long notice", host)
        assertTrue(coordinator.afterDialog { ran += 1 })

        coordinator.drop(host)
        coordinator.enqueueAndShow("A notice after", host)
        host.createdWindows.last().simulateUserDismiss()

        assertEquals(0, ran, "the action of a screen that went away ran at the next dialog's close")
    }

    @Test
    fun `notice dialog does not show on a finishing or destroyed host`() {
        val host = FakeNoticeHost(canShow = false)
        val coordinator = NoticeDialogCoordinator()

        coordinator.enqueueAndShow("Should be dropped", host)
        assertEquals(0, host.createdWindows.size)
        assertEquals(0, coordinator.entryCount)
    }

    @Test
    fun `notice waits behind the previous crash dialog and shows once dismissed`() {
        val host = FakeNoticeHost(canShow = true, blocking = true)
        val coordinator = NoticeDialogCoordinator()

        coordinator.enqueueAndShow("Storage low", host)
        coordinator.enqueueAndShow("Multiple device folders", host)
        assertEquals(0, host.createdWindows.size, "must wait while crash dialog is showing")
        assertTrue(host.blockingWaiter != null)

        host.dismissBlockingDialog()
        assertEquals(1, host.createdWindows.size)
        assertEquals("Storage low\n\nMultiple device folders", host.createdWindows[0].text)
    }

    @Test
    fun `crash page drop cancels a pending notice and dismisses an active notice`() {
        val host = FakeNoticeHost(canShow = true, blocking = true)
        val coordinator = NoticeDialogCoordinator()

        coordinator.enqueueAndShow("Pending before crash page", host)
        assertTrue(host.blockingWaiter != null)

        coordinator.drop(host)
        assertNull(host.blockingWaiter)
        assertEquals(0, coordinator.entryCount)

        assertEquals(listOf("Pending before crash page"), host.toasts, "a notice nobody saw goes out as a toast")

        host.dismissBlockingDialog()
        assertEquals(0, host.createdWindows.size, "dropped pending notice must stay dropped")

        coordinator.enqueueAndShow("Shown before crash page", host)
        assertEquals(1, host.createdWindows.size)
        val openWindow = host.createdWindows[0]
        assertTrue(openWindow.isShowing)

        coordinator.drop(host)
        assertEquals(1, openWindow.dismissCount)
        assertFalse(openWindow.isShowing)
        assertEquals(0, coordinator.entryCount)
        assertEquals(1, host.toasts.size, "a notice that was on the screen is not repeated as a toast")
    }

    @Test
    fun `deferred notice becomes a toast if activity finishes before crash dialog closes`() {
        val host = FakeNoticeHost(canShow = true, blocking = true)
        val coordinator = NoticeDialogCoordinator()

        coordinator.enqueueAndShow("Waiting notice", host)
        host.canShow = false
        host.canHold = false
        host.dismissBlockingDialog()

        assertEquals(0, host.createdWindows.size)
        assertEquals(0, coordinator.entryCount)
        assertEquals(listOf("Waiting notice"), host.toasts)
    }

    @Test
    fun `a deferred notice whose screen only stopped waits for its next start`() {
        val host = FakeNoticeHost(canShow = true, blocking = true)
        val coordinator = NoticeDialogCoordinator()

        coordinator.enqueueAndShow("Waiting notice", host)
        host.canShow = false
        host.dismissBlockingDialog()

        assertEquals(0, host.createdWindows.size)
        assertEquals(emptyList<String>(), host.toasts, "a stopped screen keeps the notice")
        assertEquals(1, coordinator.entryCount)

        host.canShow = true
        coordinator.flush(host)
        assertEquals(listOf("Waiting notice"), host.createdWindows.map { it.text })
    }

    @Test
    fun `a stopped screen toasts a long notice at once and holds it for its next start`() {
        val host = FakeNoticeHost(canShow = false, canHold = true)
        val coordinator = NoticeDialogCoordinator()
        val toasts = mutableListOf<String>()

        showNoticeWithPolicy(
            message = "Long ".repeat(20),
            fontScale = 1.0f,
            sdkInt = 34,
            showToast = { toasts += "toast" },
            coordinator = coordinator,
            host = host,
        )
        assertEquals(listOf("toast"), toasts, "the cut toast at once, in whatever app is in front")
        assertEquals(0, host.createdWindows.size, "no dialog on a stopped screen")
        assertEquals(1, coordinator.entryCount, "and the notice kept for the dialog")

        host.canShow = true
        coordinator.flush(host)
        assertEquals(listOf("Long ".repeat(20)), host.createdWindows.map { it.text })

        coordinator.drop(host)
        assertEquals(emptyList<String>(), host.toasts, "a notice shown at the start is not repeated")
    }
    /**
     * The low storage warning is asked for while the screen is being made, and a workspace's folders are
     * published while the app is in the background: a long notice with a once key raised then cannot be a
     * dialog yet. Dropped, it never came back, because nothing asks twice. It waits for the start of the
     * screen, and its key is spent only when a person is shown it.
     */
    @Test
    fun `a notice with a once key waits for the screen to start and spends its key when shown`() {
        val host = FakeNoticeHost(canShow = false, canHold = true)
        val processFlags = mutableSetOf<String>()
        val coordinator = NoticeDialogCoordinator(onceKeys = processFlags)
        val raise = {
            showNoticeWithPolicy(
                message = "Long ".repeat(20),
                fontScale = 1.0f,
                sdkInt = 34,
                showToast = { fail("a notice with a once key must wait for a screen, not become a toast") },
                coordinator = coordinator,
                host = host,
                processOnceKey = AppNotices.ONCE_KEY_STORAGE_LOW,
                shownOnceKeys = processFlags,
            )
        }

        raise()
        raise()

        assertEquals(1, coordinator.entryCount, "raised twice while it waits, it is held once")
        assertTrue(processFlags.isEmpty(), "nothing was shown, so the key is not spent")
        assertEquals(emptyList<String>(), host.toasts)

        host.canShow = true
        coordinator.flush(host)

        assertEquals(1, host.createdWindows.size, "the start of the screen shows the notice whole")
        assertEquals(setOf(AppNotices.ONCE_KEY_STORAGE_LOW), processFlags, "showing it spends the key")

        host.createdWindows[0].simulateUserDismiss()
        raise()
        assertEquals(1, host.createdWindows.size, "a spent key is not shown again")
    }

    @Test
    fun `a notice taken back leaves an open dialog, which closes when nothing else is on it`() {
        val host = FakeNoticeHost()
        val coordinator = NoticeDialogCoordinator()
        coordinator.enqueueAndShow("The server is slow to start", host, topic = "slow")
        coordinator.enqueueAndShow("A copy was kept beside notes.md", host, topic = "copy")
        val window = host.createdWindows.single()

        coordinator.retractTopic("slow")

        assertEquals("A copy was kept beside notes.md", window.text, "the notice on another topic stays, alone")
        assertTrue(window.isShowing)

        coordinator.retractTopic("copy")

        assertFalse(window.isShowing, "a dialog with nothing left to say stayed up")
        assertEquals(0, coordinator.entryCount)

        coordinator.enqueueAndShow("Next", host)
        assertEquals(2, host.createdWindows.size, "a notice after the dialog closed opens a dialog of its own")
        assertEquals("Next", host.createdWindows[1].text)
    }

    @Test
    fun `a notice taken back while its screen is stopped is not shown at the next start`() {
        val host = FakeNoticeHost(canShow = false, canHold = true)
        val coordinator = NoticeDialogCoordinator()
        coordinator.hold("The server is slow to start", topic = "slow")

        coordinator.retractTopic("slow")
        coordinator.retractTopic("never raised")
        host.canShow = true
        coordinator.flush(host)

        assertEquals(0, host.createdWindows.size)
    }

    /**
     * A dialog stays open while its screen is stopped, and a notice held then is put on its text. The
     * start of the screen is when a person sees it, so that is when its once key is spent: left
     * unspent, the next call with the key put the same notice on the dialog a second time.
     */
    @Test
    fun `a once notice held under a dialog that stayed open spends its key when the screen starts`() {
        val host = FakeNoticeHost()
        val processFlags = mutableSetOf<String>()
        val coordinator = NoticeDialogCoordinator(onceKeys = processFlags)
        val once = "Long ".repeat(20)
        val raise = {
            showNoticeWithPolicy(
                message = once,
                fontScale = 1.0f,
                sdkInt = 34,
                showToast = { fail("a notice with a once key is never a toast cut after two lines") },
                coordinator = coordinator,
                host = host,
                processOnceKey = AppNotices.ONCE_KEY_MULTI_DEVICE_FOLDERS,
                shownOnceKeys = processFlags,
            )
        }
        coordinator.enqueueAndShow("A copy was kept beside notes.md", host)
        val window = host.createdWindows.single()

        host.canShow = false
        host.canHold = true
        raise()
        assertTrue(processFlags.isEmpty(), "nobody has seen it yet")

        host.canShow = true
        coordinator.flush(host)

        assertEquals(setOf(AppNotices.ONCE_KEY_MULTI_DEVICE_FOLDERS), processFlags, "seen on the open dialog, so spent")
        val shown = window.text
        assertTrue(shown.endsWith(once), "the dialog does not carry the notice: $shown")

        raise()
        assertEquals(shown, window.text, "the notice went onto the dialog a second time")
        assertEquals(1, host.createdWindows.size)
    }

    @Test
    fun `a once notice held under an open dialog comes off it when another screen spent the key`() {
        val host = FakeNoticeHost()
        val processFlags = mutableSetOf<String>()
        val coordinator = NoticeDialogCoordinator(onceKeys = processFlags)
        coordinator.enqueueAndShow("A copy was kept beside notes.md", host)
        val window = host.createdWindows.single()
        host.canShow = false
        host.canHold = true
        coordinator.holdOnce("Long ".repeat(20), AppNotices.ONCE_KEY_STORAGE_LOW)
        processFlags += AppNotices.ONCE_KEY_STORAGE_LOW

        host.canShow = true
        coordinator.flush(host)

        assertEquals("A copy was kept beside notes.md", window.text)
        assertTrue(window.isShowing)
        assertEquals(1, coordinator.entryCount)
    }

    /**
     * A screen made again for a change of language carries the dialog in the language it had, and the
     * service's record, read when the new screen binds, is in that language too. The new language's
     * string matches neither, so a notice taken back by its text stayed over the working editor.
     */
    @Test
    fun `a notice is taken back by its topic after a language change, from a dialog carried over`() {
        val old = FakeNoticeHost()
        val before = NoticeDialogCoordinator()
        before.enqueueAndShow("The server is taking longer than usual to start.", old, topic = "slow")
        before.enqueueAndShow("A copy was kept beside notes.md", old)

        val held = before.drop(old)
        assertEquals(listOf<String?>("slow", null), held?.topics, "the topic does not survive the screen being made again")

        val next = FakeNoticeHost()
        val after = NoticeDialogCoordinator()
        after.take(held!!)
        after.flush(next)
        val window = next.createdWindows.single()

        after.retractTopic("slow")

        assertTrue(window.isShowing, "the notice on no topic went with it")
        assertEquals("A copy was kept beside notes.md", window.text)
    }

    /**
     * The screen made again shows the slow start dialog it was handed, and the service tells it the
     * same thing when it binds. Added again, the dialog said it twice for as long as the server took.
     */
    @Test
    fun `a notice on a topic already shown is not added twice`() {
        val host = FakeNoticeHost()
        val coordinator = NoticeDialogCoordinator()
        coordinator.take(HeldNotices(listOf("Slow to start"), 0, topics = listOf("slow")))
        coordinator.flush(host)

        coordinator.enqueueAndShow("Slow to start", host, topic = "slow")
        coordinator.hold("Slow to start", topic = "slow")
        coordinator.enqueueAndShow("Something else", host)

        assertEquals("Slow to start\n\nSomething else", host.createdWindows.single().text)
    }

    /**
     * A server that comes up between one screen going and the next binding is told to nobody, and the
     * slow start dialog the first screen carried waits for the next in the process-wide hand-over.
     * Taken back from there, it is never shown over the editor.
     */
    @Test
    fun `notices handed to the next screen lose those on a topic taken back, and keep the rest`() {
        val held = HeldNotices(
            texts = listOf("Slow to start", "A copy was kept", "Slow to start, again"),
            pushedOut = 2,
            onceKeys = listOf(null, AppNotices.ONCE_KEY_STORAGE_LOW, null),
            topics = listOf("slow", "copy", "slow"),
        )

        val left = held.withoutTopic("slow")

        assertEquals(listOf("A copy was kept"), left?.texts)
        assertEquals(listOf<String?>(AppNotices.ONCE_KEY_STORAGE_LOW), left?.onceKeys, "a once key moved to another notice")
        assertEquals(listOf<String?>("copy"), left?.topics)
        assertEquals(2, left?.pushedOut)
        assertNull(left?.withoutTopic("copy"), "a count with no notice left would only prefix the next, unrelated dialog")
        assertEquals(listOf("Plain"), HeldNotices(listOf("Plain"), 0).withoutTopic("slow")?.texts, "notices held with no topics")
    }

    @Test
    fun `a topic taken back for a screen reaches what the screen before it left for it`() {
        @Suppress("UNCHECKED_CAST")
        val handOver = AppNotices::class.java.getDeclaredField("heldForNextScreen")
            .apply { isAccessible = true }.get(null) as HashMap<Class<out Activity>, HeldNotices>
        val screen = mockk<Activity>(relaxed = true)
        try {
            handOver[screen.javaClass] = HeldNotices(listOf("Slow", "Kept"), 0, topics = listOf("slow", null))
            AppNotices.retractTopic(screen, "slow")
            assertEquals(
                listOf("Kept"), handOver[screen.javaClass]?.texts,
                "the next screen is handed the notice taken back, or loses the one on another topic",
            )

            handOver[screen.javaClass] = HeldNotices(listOf("Slow"), 3, topics = listOf("slow"))
            AppNotices.retractTopic(screen, "slow")
            assertFalse(screen.javaClass in handOver, "the next screen takes a hand-over with nothing left to show")
        } finally {
            handOver.remove(screen.javaClass)
        }
    }

    @Test
    fun `a held once notice whose key another screen spent is not shown`() {
        val host = FakeNoticeHost(canShow = false, canHold = true)
        val processFlags = mutableSetOf<String>()
        val coordinator = NoticeDialogCoordinator(onceKeys = processFlags)
        coordinator.holdOnce("Long ".repeat(20), AppNotices.ONCE_KEY_MULTI_DEVICE_FOLDERS)
        processFlags += AppNotices.ONCE_KEY_MULTI_DEVICE_FOLDERS

        host.canShow = true
        coordinator.flush(host)

        assertEquals(0, host.createdWindows.size)
        assertEquals(0, coordinator.entryCount)
    }

    @Test
    fun `a held once notice goes to the screen made in place of one taken down, untoasted and unspent`() {
        val host = FakeNoticeHost(canShow = false, canHold = true)
        val processFlags = mutableSetOf<String>()
        val coordinator = NoticeDialogCoordinator(onceKeys = processFlags)
        coordinator.holdOnce("Long ".repeat(20), AppNotices.ONCE_KEY_STORAGE_LOW)

        val held = coordinator.drop(host)

        assertEquals(emptyList<String>(), host.toasts, "a once notice is never a toast cut after two lines")
        assertEquals(listOf<String?>(AppNotices.ONCE_KEY_STORAGE_LOW), held?.onceKeys)
        assertTrue(processFlags.isEmpty())

        val next = NoticeDialogCoordinator(onceKeys = processFlags)
        val nextHost = FakeNoticeHost()
        next.take(held!!)
        next.flush(nextHost)

        assertEquals(1, nextHost.createdWindows.size)
        assertEquals(setOf(AppNotices.ONCE_KEY_STORAGE_LOW), processFlags)
    }

    @Test
    fun `a held notice toasted when raised is not toasted again when its screen is destroyed`() {
        val host = FakeNoticeHost(canShow = false, canHold = true)
        val coordinator = NoticeDialogCoordinator()
        val toasts = mutableListOf<String>()

        for (message in listOf("First held ".repeat(10), "Second held ".repeat(10))) {
            showNoticeWithPolicy(
                message = message,
                fontScale = 1.0f,
                sdkInt = 34,
                showToast = { toasts += message },
                coordinator = coordinator,
                host = host,
            )
        }
        host.canHold = false
        coordinator.drop(host)

        assertEquals(2, toasts.size, "each toasted once, when it was raised")
        assertEquals(emptyList<String>(), host.toasts, "and not again when the screen goes")
        assertEquals(0, host.createdWindows.size)
    }
    @Test
    fun `a screen made again takes over the notices its predecessor held and shows them whole at its first start`() {
        val old = FakeNoticeHost(canShow = false, canHold = true)
        val before = NoticeDialogCoordinator()
        before.hold("First held ".repeat(10))
        before.hold("Second held ".repeat(10))

        val held = before.drop(old)
        assertEquals(listOf("First held ".repeat(10), "Second held ".repeat(10)), held?.texts)
        assertEquals(emptyList<String>(), old.toasts, "toasted when raised, not again as the screen goes")

        val next = FakeNoticeHost()
        val after = NoticeDialogCoordinator()
        after.take(held!!)
        assertEquals(0, next.createdWindows.size, "nothing before the new screen starts")
        after.flush(next)
        assertEquals(
            listOf("First held ".repeat(10) + "\n\n" + "Second held ".repeat(10)),
            next.createdWindows.map { it.text },
        )

        next.createdWindows.single().simulateUserDismiss()
        assertNull(after.drop(next), "a dialog a person closed is not handed on")
        after.flush(next)
        assertEquals(1, next.createdWindows.size, "and nothing comes at a later start")
    }

    @Test
    fun `notices taken over are handed on again when the new screen is taken down before it starts`() {
        val before = NoticeDialogCoordinator()
        before.hold("Held ".repeat(20))
        val after = NoticeDialogCoordinator()
        after.take(before.drop(FakeNoticeHost(canShow = false, canHold = true))!!)

        val gone = FakeNoticeHost(canShow = false, canHold = true)
        val again = after.drop(gone)
        assertEquals(listOf("Held ".repeat(20)), again?.texts)
        assertEquals(emptyList<String>(), gone.toasts, "toasted when raised, not again by the screen that took it over")
    }

    @Test
    fun `a dialog still open when its screen is made again comes back on the new screen`() {
        val old = FakeNoticeHost()
        val before = NoticeDialogCoordinator()
        before.enqueueAndShow("Open ".repeat(20), old)
        val shown = old.createdWindows.single()

        val held = before.drop(old)
        assertEquals(1, shown.dismissCount, "the old window goes with its screen")
        assertEquals(listOf("Open ".repeat(20)), held?.texts)
        assertEquals(emptyList<String>(), old.toasts)

        val next = FakeNoticeHost()
        val after = NoticeDialogCoordinator()
        after.take(held!!)
        after.flush(next)
        assertEquals(listOf("Open ".repeat(20)), next.createdWindows.map { it.text })
    }

    /**
     * A notice with a once key raised while its screen is being made waits for the start, which shows
     * it and spends the key. Still open when a font size or language change makes the screen again, it
     * comes back like any other open dialog: the new screen's own call finds the key spent and adds
     * nothing, so what the old screen hands over is the only copy, and it must not be dropped as one
     * whose key some other screen spent.
     */
    @Test
    fun `a once notice shown at the start comes back when its screen is made again, and only once`() {
        for (key in listOf(AppNotices.ONCE_KEY_STORAGE_LOW, AppNotices.ONCE_KEY_MULTI_DEVICE_FOLDERS)) {
            val processFlags = mutableSetOf<String>()
            val once = "Long ".repeat(20)
            fun raise(coordinator: NoticeDialogCoordinator, host: FakeNoticeHost) = showNoticeWithPolicy(
                message = once,
                fontScale = 1.0f,
                sdkInt = 34,
                showToast = { fail("a notice with a once key is never a toast cut after two lines") },
                coordinator = coordinator,
                host = host,
                processOnceKey = key,
                shownOnceKeys = processFlags,
            )
            val first = NoticeDialogCoordinator(onceKeys = processFlags)
            val firstHost = FakeNoticeHost(canShow = false, canHold = true)
            raise(first, firstHost)
            firstHost.canShow = true
            first.flush(firstHost)
            assertEquals(listOf(once), firstHost.createdWindows.map { it.text }, key)

            val held = first.drop(firstHost)
            val second = NoticeDialogCoordinator(onceKeys = processFlags)
            val secondHost = FakeNoticeHost(canShow = false, canHold = true)
            raise(second, secondHost)
            second.take(held!!)
            secondHost.canShow = true
            second.flush(secondHost)
            assertEquals(
                listOf(once), secondHost.createdWindows.map { it.text },
                "$key: the dialog on screen went with its screen and the new one never showed it",
            )

            secondHost.createdWindows.single().simulateUserDismiss()
            assertNull(second.drop(secondHost), "$key: a dialog a person closed is handed on")
            val third = NoticeDialogCoordinator(onceKeys = processFlags)
            val thirdHost = FakeNoticeHost(canShow = false, canHold = true)
            raise(third, thirdHost)
            thirdHost.canShow = true
            third.flush(thirdHost)
            assertEquals(0, thirdHost.createdWindows.size, "$key: shown again after it was closed")
        }
    }

    @Test
    fun `a dialog that stopped showing is not handed to the screen made again`() {
        val host = FakeNoticeHost()
        val coordinator = NoticeDialogCoordinator()
        coordinator.enqueueAndShow("Gone ".repeat(20), host)
        host.createdWindows.single().isShowing = false

        assertNull(coordinator.drop(host))
    }

    @Test
    fun `notices that waited behind another dialog are toasted as the screen goes and handed on with the count`() {
        val old = FakeNoticeHost(blocking = true)
        val before = NoticeDialogCoordinator(maxUnshown = 2)
        for (i in 1..3) before.enqueueAndShow("Waiting $i ".repeat(10), old)

        val held = before.drop(old)
        assertEquals(1, old.toasts.size, "one toast for what a person had nothing of")
        assertEquals(listOf("Waiting 2 ".repeat(10), "Waiting 3 ".repeat(10)), held?.texts)
        assertEquals(1, held?.pushedOut)

        val next = FakeNoticeHost()
        val after = NoticeDialogCoordinator(maxUnshown = 2)
        after.take(held!!)
        after.flush(next)
        assertTrue(next.createdWindows.single().text.startsWith("(+1) Waiting 2 "), next.createdWindows.single().text)
    }

    @Test
    fun `a notice held under a dialog from before joins it and counts as shown only at the next start`() {
        val host = FakeNoticeHost()
        val coordinator = NoticeDialogCoordinator(maxEntries = 2)

        coordinator.enqueueAndShow("Shown before stopping", host)
        val window = host.createdWindows.single()
        host.canShow = false
        coordinator.hold("Held while stopped")
        assertEquals("Shown before stopping\n\nHeld while stopped", window.text, "the open dialog takes the text")

        host.canShow = true
        coordinator.flush(host)
        coordinator.enqueueAndShow("After the start", host)
        coordinator.enqueueAndShow("Next", host)
        assertEquals(1, host.createdWindows.size, "the open dialog shows it; no second dialog")
        assertEquals("After the start\n\nNext", window.text, "shown at the start, so the bound pushes it out")

        coordinator.drop(host)
        assertEquals(emptyList<String>(), host.toasts)
    }
    @Test
    fun `a notice added to a dialog on a started screen is not repeated when the screen goes`() {
        val host = FakeNoticeHost()
        val coordinator = NoticeDialogCoordinator()

        coordinator.enqueueAndShow("First on screen", host)
        coordinator.enqueueAndShow("Second on screen", host)
        host.canShow = false
        host.canHold = false
        coordinator.drop(host)

        assertEquals(emptyList<String>(), host.toasts)
    }

    @Test
    fun `a dialog that stopped showing takes its own entries but not the held ones`() {
        val host = FakeNoticeHost()
        val coordinator = NoticeDialogCoordinator()

        coordinator.enqueueAndShow("Seen in the first dialog", host)
        val first = host.createdWindows.single()
        host.canShow = false
        coordinator.hold("Held while stopped")
        first.isShowing = false
        host.canShow = true
        coordinator.enqueueAndShow("After the start", host)

        assertEquals(2, host.createdWindows.size)
        assertEquals("Held while stopped\n\nAfter the start", host.createdWindows[1].text)
    }

    @Test
    fun `a dialog goes only on a screen that is alive and started or starting`() {
        assertTrue(screenCanShowDialog(alive = true, started = true, starting = false))
        assertTrue(screenCanShowDialog(alive = true, started = false, starting = true), "the flush from onStart")
        assertFalse(screenCanShowDialog(alive = true, started = false, starting = false), "a stopped screen holds")
        assertFalse(screenCanShowDialog(alive = false, started = true, starting = true), "a screen that is going")
    }

    @Test
    fun `the bound pushes out only entries already shown`() {
        val host = FakeNoticeHost(canShow = false, canHold = true)
        val coordinator = NoticeDialogCoordinator(maxEntries = 2)

        coordinator.hold("One")
        coordinator.hold("Two")
        coordinator.hold("Three")
        assertEquals(3, coordinator.entryCount, "an entry never shown is not pushed out by the bound")

        host.canShow = true
        coordinator.flush(host)
        assertEquals("One\n\nTwo\n\nThree", host.createdWindows.single().text)

        coordinator.enqueueAndShow("Four", host)
        assertEquals(2, coordinator.entryCount, "entries already shown are bounded as before")
        assertEquals("Three\n\nFour", host.createdWindows.single().text)
    }

    @Test
    fun `the unshown ceiling pushes out the oldest and the dialog and the toast start with the count`() {
        val host = FakeNoticeHost(canShow = false, canHold = true)
        val coordinator = NoticeDialogCoordinator(maxUnshown = 2)
        coordinator.hold("One")
        coordinator.hold("Two")
        coordinator.hold("Three")
        assertEquals(2, coordinator.entryCount, "the oldest beyond the ceiling goes")
        host.canShow = true
        coordinator.flush(host)
        assertEquals("(+1) Two\n\nThree", host.createdWindows.single().text)

        val waiting = FakeNoticeHost(canShow = true, blocking = true, fitsInOneToast = true)
        val behind = NoticeDialogCoordinator(maxUnshown = 2)
        behind.enqueueAndShow("One", waiting)
        behind.enqueueAndShow("Two", waiting)
        behind.enqueueAndShow("Three", waiting)
        waiting.canShow = false
        waiting.canHold = false
        behind.drop(waiting)
        assertEquals(listOf("(+1) Two\n\nThree"), waiting.toasts)
    }
    @Test
    fun `notices nobody was told of go out as one toast, the newest last, with the count of what it does not show`() {
        assertEquals("(+2) A\n\nB\n\nC", noticesToast(listOf("A", "B", "C"), 0) { false })
        assertEquals("A\n\nB\n\nC", noticesToast(listOf("A", "B", "C"), 0) { true })
        assertEquals("A", noticesToast(listOf("A"), 0) { false })
        assertEquals("(+3) A\n\nB", noticesToast(listOf("A", "B"), 3) { true })
        assertEquals("(+4) A\n\nB", noticesToast(listOf("A", "B"), 3) { false })

        val host = FakeNoticeHost(canShow = true, blocking = true)
        val coordinator = NoticeDialogCoordinator()
        coordinator.enqueueAndShow("Waiting one", host)
        coordinator.enqueueAndShow("Waiting two", host)
        coordinator.drop(host)
        assertEquals(listOf("(+1) Waiting one\n\nWaiting two"), host.toasts)
    }
    @Test
    fun `showNoticeWithPolicy sends fitting notices to toast without disturbing dialog`() {
        val host = FakeNoticeHost()
        val coordinator = NoticeDialogCoordinator()
        val toasts = mutableListOf<String>()

        showNoticeWithPolicy(
            message = "Long ".repeat(20),
            fontScale = 1.0f,
            sdkInt = 34,
            showToast = { toasts += "unexpected" },
            coordinator = coordinator,
            host = host,
        )
        assertEquals(1, host.createdWindows.size)
        assertEquals(emptyList<String>(), toasts)

        // Short notice fits in toast, so it stays a toast and does not append to the open dialog.
        showNoticeWithPolicy(
            message = "Saved to Downloads",
            fontScale = 1.0f,
            sdkInt = 34,
            showToast = { toasts += "Saved to Downloads" },
            coordinator = coordinator,
            host = host,
        )
        assertEquals(listOf("Saved to Downloads"), toasts)
        assertEquals(1, host.createdWindows.size)
        assertEquals(1, coordinator.entryCount)
    }

    @Test
    fun `an oversized notice with no screen to hold a dialog falls back to the toast`() {
        val toasts = mutableListOf<String>()

        showNoticeWithPolicy(
            message = "Long ".repeat(20),
            fontScale = 1.0f,
            sdkInt = 34,
            showToast = { toasts += "no activity" },
            coordinator = null,
            host = null,
        )
        val goingHost = FakeNoticeHost(canShow = false)
        showNoticeWithPolicy(
            message = "Long ".repeat(20),
            fontScale = 1.0f,
            sdkInt = 34,
            showToast = { toasts += "activity going" },
            coordinator = NoticeDialogCoordinator(),
            host = goingHost,
        )

        assertEquals(listOf("no activity", "activity going"), toasts)
        assertEquals(0, goingHost.createdWindows.size)
    }

    @Test
    fun `an oversized notice with a once key and no screen is neither shown nor spent`() {
        val toasts = mutableListOf<String>()
        val processFlags = mutableSetOf<String>()

        showNoticeWithPolicy(
            message = "Long ".repeat(20),
            fontScale = 1.0f,
            sdkInt = 34,
            showToast = { toasts += "unexpected" },
            coordinator = null,
            host = null,
            processOnceKey = AppNotices.ONCE_KEY_STORAGE_LOW,
            shownOnceKeys = processFlags,
        )

        assertEquals(emptyList<String>(), toasts)
        assertTrue(processFlags.isEmpty())
    }

    @Test
    fun `storage_low_warning processOnceKey shows at most once across recreated activities in the same process`() {
        val processFlags = mutableSetOf<String>()
        val firstHost = FakeNoticeHost()
        val firstCoordinator = NoticeDialogCoordinator()
        val secondHost = FakeNoticeHost()
        val secondCoordinator = NoticeDialogCoordinator()

        val message = "Storage low (50 MB available). Run \"VSCodroid: Manage Device Folder Storage\" from the Command Palette."
        showNoticeWithPolicy(
            message = message,
            fontScale = 1.0f,
            sdkInt = 34,
            showToast = {},
            coordinator = firstCoordinator,
            host = firstHost,
            processOnceKey = AppNotices.ONCE_KEY_STORAGE_LOW,
            shownOnceKeys = processFlags,
        )
        assertEquals(1, firstHost.createdWindows.size)

        // Activity recreated in the same process gets a fresh coordinator and host, yet must not repeat the dialog.
        showNoticeWithPolicy(
            message = message,
            fontScale = 1.0f,
            sdkInt = 34,
            showToast = {},
            coordinator = secondCoordinator,
            host = secondHost,
            processOnceKey = AppNotices.ONCE_KEY_STORAGE_LOW,
            shownOnceKeys = processFlags,
        )
        assertEquals(0, secondHost.createdWindows.size)
        assertEquals(0, secondCoordinator.entryCount)

        // A new process (fresh processFlags) shows it once again.
        val nextProcessHost = FakeNoticeHost()
        showNoticeWithPolicy(
            message = message,
            fontScale = 1.0f,
            sdkInt = 34,
            showToast = {},
            coordinator = NoticeDialogCoordinator(),
            host = nextProcessHost,
            processOnceKey = AppNotices.ONCE_KEY_STORAGE_LOW,
            shownOnceKeys = mutableSetOf(),
        )
        assertEquals(1, nextProcessHost.createdWindows.size)
    }

    @Test
    fun `saf_workspace_multiple_folders processOnceKey shows at most once per process across workspace reloads`() {
        val processFlags = mutableSetOf<String>()
        val host = FakeNoticeHost()
        val coordinator = NoticeDialogCoordinator()
        val message = "This workspace spans multiple device folders. Only one device folder syncs at a time; edits in the others stay inside VSCodroid until those folders are opened directly."

        showNoticeWithPolicy(
            message = message,
            fontScale = 1.0f,
            sdkInt = 34,
            showToast = {},
            coordinator = coordinator,
            host = host,
            processOnceKey = AppNotices.ONCE_KEY_MULTI_DEVICE_FOLDERS,
            shownOnceKeys = processFlags,
        )
        assertEquals(1, host.createdWindows.size)

        // Reloading the workspace (or recreating MainActivity) in the same process must not repeat it.
        showNoticeWithPolicy(
            message = message,
            fontScale = 1.0f,
            sdkInt = 34,
            showToast = {},
            coordinator = NoticeDialogCoordinator(),
            host = FakeNoticeHost().also { secondHost ->
                showNoticeWithPolicy(
                    message = message,
                    fontScale = 1.0f,
                    sdkInt = 34,
                    showToast = {},
                    coordinator = coordinator,
                    host = secondHost,
                    processOnceKey = AppNotices.ONCE_KEY_MULTI_DEVICE_FOLDERS,
                    shownOnceKeys = processFlags,
                )
                assertEquals(0, secondHost.createdWindows.size)
            },
            processOnceKey = AppNotices.ONCE_KEY_MULTI_DEVICE_FOLDERS,
            shownOnceKeys = processFlags,
        )
        assertEquals(1, host.createdWindows.size)
    }

    @Test
    fun `processOnceKey is not consumed if host activity cannot show the dialog`() {
        val processFlags = mutableSetOf<String>()
        val deadHost = FakeNoticeHost(canShow = false)
        showNoticeWithPolicy(
            message = "Long ".repeat(20),
            fontScale = 1.0f,
            sdkInt = 34,
            showToast = { fail("a notice with a once key must wait for a screen, not become a toast") },
            coordinator = NoticeDialogCoordinator(),
            host = deadHost,
            processOnceKey = AppNotices.ONCE_KEY_STORAGE_LOW,
            shownOnceKeys = processFlags,
        )
        assertTrue(processFlags.isEmpty())

        val liveHost = FakeNoticeHost(canShow = true)
        showNoticeWithPolicy(
            message = "Long ".repeat(20),
            fontScale = 1.0f,
            sdkInt = 34,
            showToast = {},
            coordinator = NoticeDialogCoordinator(),
            host = liveHost,
            processOnceKey = AppNotices.ONCE_KEY_STORAGE_LOW,
            shownOnceKeys = processFlags,
        )
        assertEquals(1, liveHost.createdWindows.size)
        assertTrue(AppNotices.ONCE_KEY_STORAGE_LOW in processFlags)
    }
}
