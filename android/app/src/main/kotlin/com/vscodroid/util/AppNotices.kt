package com.vscodroid.util

import android.app.Activity
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil

/**
 * Width model pinned by `AppNoticesTest` (Android 12+ two-line pill capacity at
 * default font scale = 75 narrow units, half of it a line; wide CJK glyphs count 2).
 */
internal const val TOAST_MAX_DISPLAY_WIDTH = 75

/**
 * Maximum number of long notices retained in one combined notice dialog when
 * several failures arrive back to back. Keeping the newest five prevents an
 * unbounded dialog on repeated failures while keeping earlier context visible.
 */
internal const val MAX_COMBINED_NOTICE_ENTRIES = 5

/**
 * Ceiling of the long notices no dialog has shown yet, the ones a stopped screen keeps for its
 * next start or that wait behind another dialog. Twenty is four times the dialog's bound and
 * still a dialog a person reads through; beyond it the oldest goes and is counted, and the count
 * opens the dialog and the toast.
 */
internal const val MAX_UNSHOWN_NOTICE_ENTRIES = 20
private const val NOTICE_SEPARATOR = "\n\n"

internal fun isWideToastCodePoint(cp: Int): Boolean =
    cp in 0x1100..0x11FF || // Hangul Jamo
        cp in 0x2E80..0x9FFF || // CJK Radicals, Symbols, Kana, Bopomofo, Unified Ideographs
        cp in 0xAC00..0xD7AF || // Hangul Syllables
        cp in 0xF900..0xFAFF || // CJK Compatibility Ideographs
        cp in 0xFE10..0xFE1F || // Vertical forms
        cp in 0xFE30..0xFE4F || // CJK Compatibility Forms
        cp in 0xFF01..0xFF60 || // Fullwidth ASCII variants
        cp in 0xFFE0..0xFFE6 || // Fullwidth symbol variants
        cp in 0x20000..0x2FA1F // CJK Extension Ideographs

internal fun toastDisplayWidth(text: CharSequence): Int {
    var width = 0
    var i = 0
    while (i < text.length) {
        val cp = Character.codePointAt(text, i)
        width += if (isWideToastCodePoint(cp)) 2 else 1
        i += Character.charCount(cp)
    }
    return width
}

/**
 * Reports whether [text] fits inside Android's two-line text toast pill at the
 * active [fontScale] on OS level [sdkInt].
 *
 * Measured line by line, because a line break starts a new line of the pill
 * whatever room the one before it had left: two short notices joined by a blank
 * line are three lines, and a toast shows two. Counted as one narrow unit, the
 * break let such a join pass for a short line.
 *
 * Below Android 12 (API 31), text toasts wrap instead of clipping at two lines,
 * so every notice fits a toast there.
 */
internal fun fitsInToast(
    text: CharSequence,
    fontScale: Float = 1f,
    sdkInt: Int = Build.VERSION.SDK_INT,
): Boolean {
    if (sdkInt < Build.VERSION_CODES.S) return true
    val scale = if (fontScale.isFinite() && fontScale > 0f) fontScale else 1f
    val lineWidth = TOAST_MAX_DISPLAY_WIDTH / 2.0
    val lines = text.split('\n').sumOf { line ->
        maxOf(1, ceil(toastDisplayWidth(line) * scale / lineWidth).toInt())
    }
    return lines <= 2
}

internal interface NoticeWindow {
    val isShowing: Boolean
    fun setMessage(text: CharSequence)
    fun dismiss()
}

internal interface NoticeHostState {
    fun canShowDialog(): Boolean
    fun canHoldNotices(): Boolean
    fun isBlockingDialogShowing(): Boolean
    fun waitForBlockingDialogDismiss(onDismissed: () -> Unit)
    fun clearBlockingWaiter()
    fun createAndShowDialog(text: CharSequence, onDismissed: () -> Unit): NoticeWindow
    fun toastFits(text: CharSequence): Boolean
    fun showToast(text: CharSequence)
}

internal interface BlockingNoticeHost {
    fun isBlockingNoticeShowing(): Boolean
    fun waitForBlockingNoticeDismiss(onDismissed: () -> Unit)
    fun clearBlockingNoticeWaiter()
}

// What a screen still held when it was taken down: the notices that never reached a dialog and
// those on a dialog still open, with the count the ceiling pushed out. [onceKeys] is parallel to
// [texts] and names the process-once key of an entry not shown yet, which the next screen spends
// when it shows the entry. [topics] is parallel too, and names what a retraction matches.
internal class HeldNotices(
    val texts: List<String>,
    val pushedOut: Int,
    val onceKeys: List<String?> = emptyList(),
    val topics: List<String?> = emptyList(),
) {
    // What is left once the notices on [topic] are taken back, or null when no notice is: a count
    // of pushed-out notices alone has nothing to show and would only prefix an unrelated dialog.
    fun withoutTopic(topic: String): HeldNotices? {
        val kept = texts.indices.filter { topics.getOrNull(it) != topic }
        if (kept.isEmpty()) return null
        return HeldNotices(
            kept.map { texts[it] }, pushedOut, kept.map { onceKeys.getOrNull(it) }, kept.map { topics.getOrNull(it) },
        )
    }
}

internal class NoticeDialogCoordinator(
    private val maxEntries: Int = MAX_COMBINED_NOTICE_ENTRIES,
    private val maxUnshown: Int = MAX_UNSHOWN_NOTICE_ENTRIES,
    // The process-once keys already spent. A held entry that has a key spends it when it is shown,
    // and is not shown when the key is already spent.
    private val onceKeys: MutableSet<String>? = null,
) {
    init {
        require(maxEntries >= 1) { "maxEntries must be positive: $maxEntries" }
        require(maxUnshown >= 1) { "maxUnshown must be positive: $maxUnshown" }
    }

    // What a person has had of an entry: nothing yet (it waits behind another dialog), the toast
    // cut after two lines when it was raised on a stopped screen, or the whole text on a dialog
    // while the screen was started.
    private enum class Told { NOTHING, TOAST, DIALOG }

    private class Entry(val text: String, var told: Told, val onceKey: String? = null, val topic: String? = null)

    private val entries = ArrayDeque<Entry>()
    private var pushedOut = 0
    private var openWindow: NoticeWindow? = null
    private var waitingForBlocker = false
    private var afterClose: (() -> Unit)? = null

    val entryCount: Int get() = entries.size
    val currentText: String?
        get() = if (entries.isEmpty()) null else combinedMessage()

    fun enqueueAndShow(text: CharSequence, host: NoticeHostState, topic: String? = null) {
        if (!host.canShowDialog()) return
        val active = openWindow
        if (active != null && active.isShowing) {
            appendEntry(text.toString(), Told.DIALOG, topic = topic)
            active.setMessage(combinedMessage())
            return
        }
        if (active != null) {
            openWindow = null
            entries.removeAll { it.told == Told.DIALOG }
        }
        appendEntry(text.toString(), Told.NOTHING, topic = topic)
        presentOrDefer(host)
    }

    // The notice of a stopped screen, toasted when it was raised, kept for the dialog at the
    // screen's next start. A dialog the screen still holds from before takes the text too, but it
    // counts as shown only once the screen is started again.
    fun hold(text: CharSequence, topic: String? = null) {
        appendEntry(text.toString(), Told.TOAST, topic = topic)
        openWindow?.takeIf { it.isShowing }?.setMessage(combinedMessage())
    }

    // A notice with a process-once key raised on a screen that is alive but not started: it was not
    // toasted and its key is not spent, and it waits for the start of a screen that can show it
    // whole, where the key is spent. Counted as toasted so that a screen going away does not toast
    // it, which would show it cut and leave its key to be spent again by the next call. Raised
    // again while it waits, it is not added twice.
    fun holdOnce(text: CharSequence, onceKey: String) {
        if (entries.any { it.onceKey == onceKey }) return
        appendEntry(text.toString(), Told.TOAST, onceKey)
        openWindow?.takeIf { it.isShowing }?.setMessage(combinedMessage())
    }

    // Takes back the notices on [topic], which have stopped being true, as "the server is slow to
    // start" has once the server is up: off a dialog that is open, which closes when nothing else is
    // on it, and out of what waits for a screen. By topic and not by text, which a screen made again
    // for a change of language carries in the language it had.
    fun retractTopic(topic: String) {
        if (!entries.removeAll { it.topic == topic }) return
        val window = openWindow?.takeIf { it.isShowing } ?: return
        if (entries.isEmpty()) {
            openWindow = null
            pushedOut = 0
            window.dismiss()
            runAfterClose()
        } else {
            window.setMessage(combinedMessage())
        }
    }

    // Runs [action] once the dialog on screen is closed, and says whether one is open to wait for. A
    // screen that is about to finish asks, because its finish takes the dialog down unread. Closed by
    // the person or by this coordinator (a retraction, a spent once key), the action runs; a screen
    // going away ends it unrun, as it ends the dialog. One action waits at a time, the latest.
    fun afterDialog(action: () -> Unit): Boolean {
        if (openWindow?.isShowing != true) return false
        afterClose = action
        return true
    }

    fun flush(host: NoticeHostState) {
        if (entries.isEmpty()) return
        val open = openWindow
        if (open?.isShowing == true) {
            if (!host.canShowDialog()) return
            // What was held while the screen was stopped went onto this dialog unseen, and is seen
            // now: its once key is spent as on a dialog made new. Left unspent, the next call with
            // that key showed the notice a second time.
            if (spendOnceKeys()) {
                if (entries.isEmpty()) {
                    openWindow = null
                    pushedOut = 0
                    open.dismiss()
                    runAfterClose()
                    return
                }
                open.setMessage(combinedMessage())
            }
            entries.forEach { it.told = Told.DIALOG }
            return
        }
        waitingForBlocker = false
        presentOrDefer(host)
    }

    // The notices of a screen the system took down, taken over by the screen made in its place.
    // They count as toasted at most, so the next start shows them whole.
    fun take(held: HeldNotices) {
        held.texts.forEachIndexed { index, text ->
            appendEntry(text, Told.TOAST, held.onceKeys.getOrNull(index), held.topics.getOrNull(index))
        }
        pushedOut += held.pushedOut
    }

    // Returns what the screen still held, for a caller whose screen is being made again.
    fun drop(host: NoticeHostState): HeldNotices? {
        // What goes out here is what a person has had nothing of, the notices that waited behind
        // another dialog, and as one toast: the platform discards toasts over a small quota from
        // an app with no resumed activity, which is the state of an app whose screen is going.
        val untold = entries.filter { it.told == Told.NOTHING }.map { it.text }
        val onScreen = openWindow?.isShowing == true
        val heldEntries = entries.filter { it.told != Told.DIALOG || onScreen }
        val held = heldEntries.map { it.text }
        // An entry on the dialog has spent its key already, and the next screen would read the spent
        // key as another screen's and drop the only copy of a dialog still open.
        val heldKeys = heldEntries.map { if (it.told == Told.DIALOG) null else it.onceKey }
        val heldTopics = heldEntries.map { it.topic }
        val count = pushedOut
        entries.clear()
        pushedOut = 0
        afterClose = null
        if (waitingForBlocker) {
            waitingForBlocker = false
            host.clearBlockingWaiter()
        }
        val win = openWindow
        openWindow = null
        win?.dismiss()
        if (untold.isNotEmpty()) host.showToast(noticesToast(untold, count, host::toastFits))
        return if (held.isEmpty()) null else HeldNotices(held, count, heldKeys, heldTopics)
    }

    private fun runAfterClose() {
        val action = afterClose ?: return
        afterClose = null
        action()
    }

    private fun combinedMessage(): String {
        val joined = entries.joinToString(NOTICE_SEPARATOR) { it.text }
        return if (pushedOut > 0) "(+$pushedOut) $joined" else joined
    }

    private fun appendEntry(text: String, told: Told, onceKey: String? = null, topic: String? = null) {
        // A notice on a topic already here says it again: a screen made again with the slow start
        // dialog carried over is told the same thing by the service when it binds.
        if (topic != null && entries.any { it.topic == topic }) return
        // The bound keeps a dialog a person watches from growing, so it pushes out entries already
        // shown. Entries not shown yet have a ceiling of their own: beyond it the oldest goes and
        // is counted, so that nothing grows without bound or leaves without a trace.
        while (entries.size >= maxEntries) {
            val oldestShown = entries.indexOfFirst { it.told == Told.DIALOG }
            if (oldestShown < 0) break
            entries.removeAt(oldestShown)
        }
        while (told != Told.DIALOG && entries.count { it.told != Told.DIALOG } >= maxUnshown) {
            entries.removeAt(entries.indexOfFirst { it.told != Told.DIALOG })
            pushedOut += 1
        }
        entries.addLast(Entry(text, told, onceKey, topic))
    }

    // The entries a person has not been shown that carry a key already spent are not shown now, and
    // the others spend theirs: a key is spent once, by whichever call shows it first. True when an
    // entry came off.
    private fun spendOnceKeys(): Boolean {
        val keys = onceKeys ?: return false
        return entries.removeAll { it.told != Told.DIALOG && it.onceKey != null && !keys.add(it.onceKey) }
    }

    private fun presentOrDefer(host: NoticeHostState) {
        if (entries.isEmpty()) return
        if (!host.canShowDialog()) {
            // A stopped screen keeps the entries for its next start; a screen that is going sends
            // them out as a toast.
            if (!host.canHoldNotices()) drop(host)
            return
        }
        if (host.isBlockingDialogShowing()) {
            if (!waitingForBlocker) {
                waitingForBlocker = true
                host.waitForBlockingDialogDismiss {
                    waitingForBlocker = false
                    presentOrDefer(host)
                }
            }
            return
        }
        waitingForBlocker = false
        spendOnceKeys()
        if (entries.isEmpty()) return
        var created: NoticeWindow? = null
        created = host.createAndShowDialog(combinedMessage()) {
            if (openWindow === created) {
                openWindow = null
                entries.clear()
                pushedOut = 0
                runAfterClose()
            }
        }
        openWindow = created
        entries.forEach { it.told = Told.DIALOG }
    }
}

// Can show a dialog: the screen exists, is not going away and is started, so that a dialog put on
// it is on a screen a person can see. Starting counts: the flush from onStart runs before the
// lifecycle reaches STARTED.
internal fun screenCanShowDialog(alive: Boolean, started: Boolean, starting: Boolean): Boolean =
    alive && (starting || started)

// The toast of the notices a person has had nothing of, joined as the dialog joins them, the
// newest last. Its count, in a form that needs no translation, says how many it does not show:
// those the ceiling pushed out and, when the text is longer than the two lines a toast shows from
// Android 12, those after the first.
internal fun noticesToast(texts: List<String>, pushedOut: Int, fits: (CharSequence) -> Boolean): String {
    val joined = texts.joinToString(NOTICE_SEPARATOR)
    val notShown = pushedOut + if (texts.size > 1 && !fits(joined)) texts.size - 1 else 0
    return if (notShown > 0) "(+$notShown) $joined" else joined
}

internal fun showNoticeWithPolicy(
    message: CharSequence,
    fontScale: Float,
    sdkInt: Int,
    showToast: () -> Unit,
    coordinator: NoticeDialogCoordinator?,
    host: NoticeHostState?,
    processOnceKey: String? = null,
    shownOnceKeys: MutableSet<String>? = null,
    topic: String? = null,
) {
    if (processOnceKey != null && shownOnceKeys != null && processOnceKey in shownOnceKeys) {
        return
    }
    if (fitsInToast(message, fontScale, sdkInt)) {
        if (processOnceKey != null && shownOnceKeys != null && !shownOnceKeys.add(processOnceKey)) {
            return
        }
        showToast()
        return
    }
    if (coordinator != null && host != null && host.canShowDialog()) {
        if (processOnceKey != null && shownOnceKeys != null && !shownOnceKeys.add(processOnceKey)) {
            return
        }
        coordinator.enqueueAndShow(message, host, topic)
        return
    }
    // A notice with a once key is not an event: with no started screen it is neither shown nor
    // spent, and waits for a screen that can show it whole, which spends the key when it does.
    if (processOnceKey != null) {
        if (coordinator != null && host != null && host.canHoldNotices()) coordinator.holdOnce(message, processOnceKey)
        return
    }
    // Both, for a stopped screen: the toast reaches the person now in whatever app is in front and
    // outlives a process ended before the next start, and the dialog at that start shows the whole text.
    if (coordinator != null && host != null && host.canHoldNotices()) {
        showToast()
        coordinator.hold(message, topic)
        return
    }
    // No screen at all: the activity is gone or going, as when a save fails to reach a device
    // folder after the app's screen has closed. A toast cut after two lines still says that
    // something happened, which nothing would.
    showToast()
}

internal object AppNotices {
    const val ONCE_KEY_STORAGE_LOW = "storage_low_warning"
    const val ONCE_KEY_MULTI_DEVICE_FOLDERS = "saf_workspace_multiple_folders"

    private val coordinators = WeakHashMap<Activity, NoticeDialogCoordinator>()
    private val shownOnceKeys = ConcurrentHashMap.newKeySet<String>()

    // Held by a screen the system took down, for the screen made in its place, which is the same
    // screen to a person. Read and written from the lifecycle calls, on the main thread.
    private val heldForNextScreen = HashMap<Class<out Activity>, HeldNotices>()

    fun showNotice(
        context: Context,
        message: CharSequence,
        duration: Int = Toast.LENGTH_LONG,
        activity: Activity? = context as? Activity,
        processOnceKey: String? = null,
        // What [retractTopic] takes the notice back by, for one that stops being true.
        topic: String? = null,
    ) {
        val fontScale = context.resources?.configuration?.fontScale ?: 1f
        val coordinator = activity?.let {
            coordinators.getOrPut(it) { NoticeDialogCoordinator(onceKeys = shownOnceKeys) }
        }
        val host = activity?.let(::AndroidNoticeHost)
        showNoticeWithPolicy(
            message = message,
            fontScale = fontScale,
            sdkInt = Build.VERSION.SDK_INT,
            showToast = { toast(context, message, duration) },
            coordinator = coordinator,
            host = host,
            processOnceKey = processOnceKey,
            shownOnceKeys = shownOnceKeys,
            topic = topic,
        )
    }

    fun showNotice(
        context: Context,
        @StringRes messageRes: Int,
        duration: Int = Toast.LENGTH_LONG,
        activity: Activity? = context as? Activity,
        processOnceKey: String? = null,
    ) {
        showNotice(context, context.getString(messageRes), duration, activity, processOnceKey)
    }

    private fun toast(context: Context, message: CharSequence, duration: Int) {
        Toast.makeText(context, message, duration).show()
    }

    // From onStart of each screen: the notices it held while stopped are shown now that it can
    // be seen.
    fun flush(activity: Activity) {
        heldForNextScreen.remove(activity.javaClass)?.let {
            coordinators.getOrPut(activity) { NoticeDialogCoordinator(onceKeys = shownOnceKeys) }.take(it)
        }
        val coordinator = coordinators[activity] ?: return
        val host = AndroidNoticeHost(activity, starting = true)
        coordinator.flush(host)
        host.starting = false
    }

    // The notices on [topic] that [activity] was given, no longer true, and those the screen before it
    // left for it and it has not taken yet. A toast already shown fades by itself.
    fun retractTopic(activity: Activity, topic: String) {
        coordinators[activity]?.retractTopic(topic)
        val held = heldForNextScreen[activity.javaClass] ?: return
        val left = held.withoutTopic(topic)
        if (left == null) heldForNextScreen.remove(activity.javaClass) else heldForNextScreen[activity.javaClass] = left
    }

    // Runs [action] once the notice dialog on [activity] is closed, and says whether there is one to
    // wait for. A screen asks before it finishes, which would take the dialog down unread.
    fun afterDialog(activity: Activity, action: () -> Unit): Boolean =
        coordinators[activity]?.afterDialog(action) == true

    // carry: the call comes from onDestroy. What a screen that is not finishing still held is kept
    // for the one the system makes in its place (a font size change, a turn of a screen that does
    // not handle it, a reclaimed screen). The screen is asked only when there is something to keep.
    fun drop(activity: Activity, carry: Boolean = false) {
        val coordinator = coordinators.remove(activity) ?: return
        val held = coordinator.drop(AndroidNoticeHost(activity))
        if (carry && held != null && !activity.isFinishing) heldForNextScreen[activity.javaClass] = held
    }

    private class AndroidNoticeWindow(
        activity: Activity,
        private val dialog: AlertDialog,
    ) : NoticeWindow {
        private val activityRef = WeakReference(activity)

        // Up as far as the window manager knows: shown, and its window not yet removed. The window
        // manager gives the window its parent when it adds it and takes it away when it removes it,
        // both at once; isAttachedToWindow comes only with the first frame. Not the screen's
        // isDestroyed: the screen's own onDestroy asks, both whether to hand the dialog on and to take
        // it down, and the platform marks the screen destroyed before it calls onDestroy, while the
        // dialog's window is removed only after onDestroy returns.
        private fun isUp(): Boolean {
            return dialog.isShowing && dialog.window?.peekDecorView()?.parent != null
        }

        override val isShowing: Boolean
            get() {
                val owner = activityRef.get() ?: return false
                return !owner.isFinishing && isUp()
            }

        override fun setMessage(text: CharSequence) {
            dialog.setMessage(text)
        }

        override fun dismiss() {
            dialog.setOnDismissListener(null)
            if (isUp()) dialog.dismiss()
        }
    }

    private class AndroidNoticeHost(
        activity: Activity,
        // True during the flush that onStart runs: the lifecycle reaches STARTED only after
        // onStart returns.
        var starting: Boolean = false,
    ) : NoticeHostState {
        private val activityRef = WeakReference(activity)

        override fun canHoldNotices(): Boolean {
            val activity = activityRef.get() ?: return false
            return !(activity.isFinishing || activity.isDestroyed)
        }

        override fun canShowDialog(): Boolean {
            val activity = activityRef.get() ?: return false
            val started = (activity as? LifecycleOwner)?.lifecycle?.currentState
                ?.isAtLeast(Lifecycle.State.STARTED) ?: true
            return screenCanShowDialog(alive = canHoldNotices(), started = started, starting = starting)
        }

        override fun toastFits(text: CharSequence): Boolean {
            val activity = activityRef.get() ?: return true
            return fitsInToast(text, activity.resources.configuration.fontScale)
        }

        override fun isBlockingDialogShowing(): Boolean =
            (activityRef.get() as? BlockingNoticeHost)?.isBlockingNoticeShowing() == true

        override fun waitForBlockingDialogDismiss(onDismissed: () -> Unit) {
            val host = activityRef.get() as? BlockingNoticeHost
            if (host == null) {
                onDismissed()
                return
            }
            host.waitForBlockingNoticeDismiss(onDismissed)
        }

        override fun clearBlockingWaiter() {
            (activityRef.get() as? BlockingNoticeHost)?.clearBlockingNoticeWaiter()
        }

        override fun createAndShowDialog(
            text: CharSequence,
            onDismissed: () -> Unit,
        ): NoticeWindow {
            val activity = activityRef.get()
            val dialog = AlertDialog.Builder(activity!!)
                .setMessage(text)
                .setPositiveButton(android.R.string.ok, null)
                .create()
            dialog.setOnDismissListener { onDismissed() }
            dialog.show()
            return AndroidNoticeWindow(activity, dialog)
        }

        override fun showToast(text: CharSequence) {
            val context = activityRef.get()?.applicationContext ?: return
            toast(context, text, Toast.LENGTH_LONG)
        }
    }
}
