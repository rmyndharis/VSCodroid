package com.vscodroid

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.text.util.Linkify
import android.view.View
import android.webkit.WebView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.vscodroid.MainActivity.Companion.KEY_WEBVIEW_WARNED
import com.vscodroid.util.AppNotices
import com.vscodroid.util.CrashReporter
import com.vscodroid.util.StorageManager
import com.vscodroid.util.WebViewVersion
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.LinearLayoutCompat
import androidx.lifecycle.lifecycleScope
import com.vscodroid.bridge.AndroidBridge
import com.vscodroid.bridge.ClipboardBridge
import com.vscodroid.util.Logger
import com.vscodroid.util.Notices
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.core.content.edit
import androidx.core.net.toUri

/**
 * Shows the About dialog. Called from AndroidBridge via JS.
 */
fun MainActivity.showAboutDialog() {
    // The bridge posts this, so it can run after this screen is gone, and show() on a window whose
    // token is dead throws on the main thread.
    if (isFinishing || isDestroyed) return
    // The stand-in is a resource for the reason `tls_unknown_host` is one:
    // it is rendered inside a translated sentence, so a Kotlin literal here
    // leaves one English word in the middle of an otherwise translated
    // dialog, and the gate over translatable strings cannot see a literal
    // that reaches its sink through a local.
    val versionName = try {
        packageManager.getPackageInfo(packageName, 0).versionName
            ?: getString(R.string.about_version_unknown)
    } catch (_: Exception) {
        getString(R.string.about_version_unknown)
    }
    val version = getString(R.string.about_version_format, versionName)
    val disclaimer = getString(R.string.legal_disclaimer)

    // An AlertDialog has three button slots and this dialog needs four
    // destinations, so "Source Code" moved one level in, onto the licences
    // dialog. That is where it belongs rather than a place it was pushed to:
    // what the licences dialog carries is the GPL's written offer of source,
    // and the repository link is the answer to the question that offer
    // raises.
    showWithReachableButtons(
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.about_title))
            .setMessage(getString(R.string.about_body, version, disclaimer))
            .setPositiveButton(getString(R.string.dialog_ok), null)
            .setNeutralButton(getString(R.string.about_licenses)) { _, _ -> showLicensesDialog() }
            .setNegativeButton(getString(R.string.about_privacy_policy)) { _, _ ->
                openInBrowser("https://rmyndharis.github.io/VSCodroid/privacy-policy.html")
            }
    )
}

/**
 * Shows the bundled third-party notices, read from the APK.
 *
 * Offline on purpose. The app redistributes GPL and LGPL binaries, and the
 * written offer of source that has to travel with them reaches the device
 * only through this screen; a link to a web page would discharge nothing on
 * a device with no network, which is the state this app is built to be
 * usable in.
 *
 * The offer is one of the two things that have to travel with those
 * binaries; the licence texts are the other, and they are one button away in
 * [showLicenseTextsDialog] rather than inside this body.
 *
 * [Notices.read] never throws and never returns empty: a document it could
 * not open is replaced in place by a line naming it.
 */
internal fun MainActivity.showLicensesDialog() {
    if (isFinishing || isDestroyed) return
    showWithReachableButtons(
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.licenses_title))
            .setView(scrollableNotice(Notices.read { assets.open(it) }))
            .setPositiveButton(getString(R.string.dialog_ok), null)
            .setNegativeButton(getString(R.string.licenses_full_texts)) { _, _ ->
                showLicenseTextsDialog()
            }
            .setNeutralButton(getString(R.string.licenses_source_code)) { _, _ ->
                openInBrowser("https://github.com/rmyndharis/VSCodroid")
            }
    )
}

/**
 * Tracks a modal dialog on the main thread so [dismissActiveDialogs] can tear it
 * down cleanly in [onDestroy] without leaving leaked windows behind.
 */
internal fun MainActivity.trackDialog(
    dialog: AlertDialog,
    preserveDismissListener: Boolean = false,
    onDismiss: (() -> Unit)? = null,
): AlertDialog {
    val dialogs = activeDialogs ?: mutableSetOf<AlertDialog>().also { activeDialogs = it }
    dialogs.removeAll { !it.isShowing }
    dialogs.add(dialog)
    if (!preserveDismissListener) {
        dialog.setOnDismissListener {
            activeDialogs?.remove(dialog)
            onDismiss?.invoke()
        }
    }
    return dialog
}

internal fun MainActivity.dismissActiveDialogs() {
    val dialogs = activeDialogs?.toList() ?: return
    activeDialogs?.clear()
    for (dialog in dialogs) {
        dialog.setOnDismissListener(null)
        dialog.takeIf { it.isShowing }?.dismiss()
    }
}

/**
 * Keeps all action buttons of a multi-button [AlertDialog] visible while the
 * body text scrolls above them.
 *
 * When the action buttons do not fit on one row, AppCompat's `ButtonBarLayout`
 * stacks them vertically and sets its minimum height to the first button plus
 * 16 dp. `AlertDialogLayout.tryOnMeasure` reserves only that minimum height
 * for `buttonPanel` before giving the remaining dialog height to
 * `contentPanel` or `customPanel`, so any scrolling body consumes the rest of
 * the dialog and leaves the lower buttons cut or pushed below the lower edge.
 * Giving the body panel weight 1 and letting `LinearLayoutCompat` measure the
 * vertical stack reserves the full stacked `buttonPanel` height and shrinks
 * only the scrolling body panel when the dialog reaches the window's height
 * limit. Answers the dialog it showed.
 */
internal fun MainActivity.showWithReachableButtons(builder: AlertDialog.Builder): AlertDialog {
    val dialog = trackDialog(builder.show())
    val contentPanel = dialog.findViewById<View>(androidx.appcompat.R.id.contentPanel)
    val customPanel = dialog.findViewById<View>(androidx.appcompat.R.id.customPanel)
    val buttonPanel = dialog.findViewById<View>(androidx.appcompat.R.id.buttonPanel)
    (contentPanel?.layoutParams as? LinearLayoutCompat.LayoutParams)?.weight = 1f
    (customPanel?.layoutParams as? LinearLayoutCompat.LayoutParams)?.weight = 1f
    buttonPanel?.id = View.NO_ID
    buttonPanel?.requestLayout()
    return dialog
}

/**
 * Opens a page of ours in the browser, and says so when nothing can. With no
 * browser enabled the launch throws on the main thread, and an uncaught throw
 * takes the process down with the server and the open session in it.
 */
internal fun MainActivity.openInBrowser(url: String) {
    try {
        startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
    } catch (_: ActivityNotFoundException) {
        AppNotices.showNotice(this, R.string.open_url_no_handler, Toast.LENGTH_LONG)
    }
}

/**
 * The verbatim GPL and LGPL texts, picked from a list.
 *
 * Behind a chooser rather than appended to the notices above, because the
 * three of them are 78 KiB and the notices are what someone opening that
 * screen came for. Concatenated, the attribution and the written offer would
 * be the first few percent of a scroll view that is otherwise licence
 * boilerplate, which is a worse screen for every reader of it. Two taps to
 * reach a text still puts the text on the device, and that is what the
 * licences require.
 *
 * [Notices.readOne] never throws: a text that will not open is replaced by a
 * line naming it, on screen, where it cannot be mistaken for the licence.
 */
internal fun MainActivity.showLicenseTextsDialog() {
    if (isFinishing || isDestroyed) return
    val names = Notices.LICENSE_TEXTS.keys.toTypedArray()
    trackDialog(
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.licenses_full_texts))
            .setItems(names) { _, which ->
                if (isFinishing || isDestroyed) return@setItems
                val name = names[which]
                trackDialog(
                    AlertDialog.Builder(this)
                        .setTitle(name)
                        .setView(
                            scrollableNotice(
                                Notices.readOne(Notices.LICENSE_TEXTS.getValue(name)) { assets.open(it) }
                            )
                        )
                        .setPositiveButton(getString(R.string.dialog_ok), null)
                        .show()
                )
            }
            .show()
    )
}

/** A long document in a scroller, laid out the same way wherever it is shown. */
internal fun MainActivity.scrollableNotice(document: String): ScrollView {
    val body = TextView(this).apply {
        // Before setText: autoLinkMask is applied when the text is set, and
        // linkifying is the point. The offer names repositories to fetch
        // source from, and a URL nobody can tap is a URL nobody follows.
        autoLinkMask = Linkify.WEB_URLS
        typeface = Typeface.MONOSPACE
        textSize = 11f
        val pad = (16 * resources.displayMetrics.density).toInt()
        setPadding(pad, pad, pad, pad)
        text = document
    }
    return ScrollView(this).apply { addView(body) }
}

/**
 * Puts the bug report on the clipboard and says so.
 *
 * Two screens reach it and neither can reach the other: the crash dialog,
 * which needs an uncaught Kotlin exception behind it, and the server-gave-up
 * page, which is shown when no such exception happened and no workbench ever
 * loaded. Written twice they would drift on the part that is easy to leave
 * out, which is the sensitive flag below.
 *
 * Suspending rather than launching its own coroutine, because the crash
 * dialog has work of its own to do after it and that work has to follow the
 * copy rather than race it.
 */
internal suspend fun MainActivity.copyBugReport() {
    // Off the main thread: generateBugReport reads three crash files and all
    // of server.log under the lock a rotation holds, and a button fires on
    // the main thread, which on both of these screens is the one thing the
    // user is looking at. The clipboard write, the sensitive flag and the
    // toast come back to Main with the result.
    val report = withContext(Dispatchers.IO) {
        CrashReporter.generateBugReport(this@copyBugReport)
    }
    val clipboard = getSystemService(android.content.ClipboardManager::class.java)
    val clip = android.content.ClipData.newPlainText("VSCodroid Bug Report", report)
    // Marked sensitive before it goes anywhere, because of what this clip
    // holds: [CrashReporter.generateBugReport] gathers the last 200 lines of
    // server output and the text of the three most recent crash logs. Android
    // 13 and later draw a preview of whatever is copied, so without this a
    // crashing session's log is rendered over the editor for anyone looking at
    // the screen, and the clipboard is readable by every app the user pastes
    // into next. The preview is suppressed; the paste is unaffected.
    //
    // Deliberately not applied to the editor's own copy in [ClipboardBridge].
    // There the preview confirms what was copied, which is the whole
    // affordance, and the text is a line the user selected rather than a log
    // they never read.
    //
    // The key is API 33, and so is the preview it silences: before that there
    // is nothing drawn, and an extra no release reads is not worth carrying.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        clip.description.extras = android.os.PersistableBundle().apply {
            putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true)
        }
    }
    clipboard.setPrimaryClip(clip)
    AppNotices.showNotice(this@copyBugReport, getString(R.string.crash_report_copied), Toast.LENGTH_SHORT)
}

/**
 * Answers the server-gave-up page's copy button.
 *
 * Nothing is cleared afterwards, which is the one way this differs from the
 * crash dialog's use of [copyBugReport]: the server log is not a record of an
 * event that has been dealt with, it is the current state of a server that is
 * still not running, and a second press should hand over the same thing.
 */
internal fun MainActivity.copyDiagnostics() {
    lifecycleScope.launch { copyBugReport() }
}

/**
 * Shows a dialog if the app crashed in a previous session.
 */
internal fun MainActivity.checkPreviousCrash() {
    if (!CrashReporter.hasPendingCrash()) return
    val lastCrash = CrashReporter.getLastCrash() ?: return
    // Truncate for display
    val preview = if (lastCrash.length > 500) lastCrash.take(500) + "\n..." else lastCrash
    crashDialog = showWithReachableButtons(
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.crash_title))
            .setMessage(getString(R.string.crash_message, preview))
            .setPositiveButton(getString(R.string.crash_dismiss)) { _, _ -> CrashReporter.clearCrashLogs() }
            .setNeutralButton(getString(R.string.crash_copy_report)) { _, _ ->
                lifecycleScope.launch {
                    copyBugReport()
                    CrashReporter.clearCrashLogs()
                }
            }
            // The third exit, and the one most people take. Back and a tap outside
            // cancel the dialog without running either button, and the guard above
            // is false only once the files are gone, so the same modal came back
            // over the loading editor on every later launch and there was nothing
            // on screen offering a way to stop it. Clearing here is what the user
            // believes the gesture already did.
            .setOnCancelListener { CrashReporter.clearCrashLogs() }
            .setCancelable(true)
    )
}

/**
 * Warns the user if available storage is critically low (<100 MB).
 *
 * The command name is quoted exactly as the palette lists it
 * (`VSCodroid: Manage Device Folder Storage`, contributed by the bundled
 * Android bridge extension), because the reader's next action is to type it.
 * This said "Clear caches in Settings" until 2026-08-14, and there is no
 * Settings screen: the manifest declares Splash, Main and Toolchain
 * activities and nothing else, so the sentence sent a user who was out of
 * space looking for a place that does not exist.
 *
 * It then named `VSCodroid: Clear Caches`, which exists but cannot free the
 * directory that is usually the largest. `clearCaches` empties the npm cache,
 * the temporary directory, the crash logs and the editor's logs, and none of
 * those is `saf-mirrors`, where a copy of every device folder the user has
 * opened accumulates and is never removed automatically once anything in it
 * has been built or cloned. So the one moment the advice was given was the
 * one moment it was wrong.
 */
internal fun MainActivity.checkStorageHealth() {
    if (!StorageManager.isStorageLow(this)) return
    val free = StorageManager.getAvailableStorage(this)
    val available = StorageManager.formatSize(free, resources.configuration.locales[0])
    AppNotices.showNotice(
        this,
        getString(R.string.storage_low_warning, available),
        Toast.LENGTH_LONG,
        processOnceKey = AppNotices.ONCE_KEY_STORAGE_LOW,
    )
    Logger.w(tag, "Storage low: ${StorageManager.formatSize(free)} available")
}

/**
 * Warns when the installed WebView is older than the version this project
 * is tested against.
 *
 * Every release this supports shipped with a WebView below the floor
 * (Android 13's was Chrome 105), and an image keeps the WebView it was built
 * with until the component updates itself. So what this catches is a WebView
 * that has not been updated since, the cases a user cannot diagnose: a
 * System WebView disabled or downgraded by hand or by an OEM image, a device
 * where WebView updates are blocked so the component ages while the OS does
 * not, and emulators, custom ROMs or devices without the Play Store carrying
 * the WebView their image was built with. A device that takes WebView
 * updates from the Play Store is far above the floor and sees nothing, on
 * Android 11 as on 13. Just below it the workbench loads and the failure is
 * quiet rather than a clean refusal: every webview, the Markdown preview
 * and extension panels among them, stays blank (see
 * [WebViewVersion.MINIMUM_CHROME_MAJOR]). Far below it nothing renders: the
 * factory WebView of an API 30 image, 91, cannot parse the workbench bundle
 * and shows a blank page behind the dialog (an `Uncaught SyntaxError` in
 * `workbench.js`, measured 2026-10-14).
 * `onReceivedError` announces a TLS handshake failure and otherwise only
 * logs, so nothing else would reach the user.
 *
 * It warns and continues rather than refusing to start. The floor is a
 * tested one, not a hard incompatibility, and an editor that degrades is
 * worth more than one that will not open. A version that cannot be read is
 * not treated as an old one; see [WebViewVersion.majorVersionOf].
 */
internal fun MainActivity.checkWebViewVersion() {
    val pkg = WebView.getCurrentWebViewPackage()
    val version = pkg?.versionName
    if (pkg == null || !WebViewVersion.isBelowMinimum(version)) {
        Logger.i(tag, "WebView: ${pkg?.packageName ?: "unknown"} ${version ?: "unknown version"}")
        return
    }
    Logger.w(tag, "WebView $version is below the tested minimum ${WebViewVersion.MINIMUM_CHROME_MAJOR}")
    // A dialog, where this was a toast: Android 12 and later cut a text toast
    // to two lines, which showed "Android System WebView is version
    // 113.0.5672.136. VSCodroid is tested a..." and never what to do about it
    // (measured on an API 34 image).
    if (!WebViewVersion.shouldWarn(version, workspacePrefs.getString(KEY_WEBVIEW_WARNED, null))) return
    // Remembered when the dialog is answered, not before it is shown: a screen that ends under it, or a
    // process that is killed, would otherwise leave a warning nobody saw marked as given.
    val warning = AlertDialog.Builder(this)
        .setTitle(getString(R.string.webview_below_minimum_title))
        .setMessage(
            getString(
                R.string.webview_below_minimum,
                version,
                WebViewVersion.MINIMUM_CHROME_MAJOR.toString(),
            )
        )
        .setPositiveButton(getString(R.string.webview_update)) { _, _ -> openStoreListing(pkg.packageName) }
        .setNegativeButton(getString(R.string.crash_dismiss), null)
        .setOnDismissListener { workspacePrefs.edit { putString(KEY_WEBVIEW_WARNED, version) } }
        .show()
    trackDialog(warning, preserveDismissListener = true)
}

/**
 * The store page of [packageName], the WebView provider actually in use
 * (Android System WebView on most devices, Chrome on some): the Play Store
 * app when there is one, its web page otherwise.
 */
internal fun MainActivity.openStoreListing(packageName: String) {
    for (page in listOf(
        "market://details?id=$packageName",
        "https://play.google.com/store/apps/details?id=$packageName",
    )) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, page.toUri()))
            return
        } catch (e: ActivityNotFoundException) {
            // No store app to take it: the next page is the same listing on the web.
        }
    }
    Logger.w(tag, "No app can show the store page of $packageName")
}
