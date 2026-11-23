package com.vscodroid

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.Bundle
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.DocumentsContract
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import com.vscodroid.util.AppNotices
import com.vscodroid.util.BlockingNoticeHost
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import androidx.activity.OnBackPressedCallback
import com.vscodroid.util.drawBehindSystemBars
import com.vscodroid.util.paintWindow
import com.vscodroid.util.CrashReporter
import com.vscodroid.util.StorageManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.vscodroid.util.Environment
import com.vscodroid.bridge.AUTH_TAB_WINDOW_MILLIS
import com.vscodroid.bridge.AndroidBridge
import com.vscodroid.bridge.AuthTabWindow
import com.vscodroid.bridge.ClipboardBridge
import com.vscodroid.bridge.SecurityManager
import com.vscodroid.keyboard.BackObserverLayout
import com.vscodroid.keyboard.EdgeBackGesture
import com.vscodroid.keyboard.ExtraKeyRow
import com.vscodroid.keyboard.HardwareTyping
import com.vscodroid.keyboard.KeyInjector
import com.vscodroid.keyboard.KeyboardDismissal
import com.vscodroid.service.EditorGateway
import com.vscodroid.service.NodeService
import com.vscodroid.setup.FirstRunSetup
import com.vscodroid.setup.ToolchainManager
import com.vscodroid.storage.SafStorageManager
import com.vscodroid.storage.SafSyncEngine
import com.vscodroid.util.Logger
import com.vscodroid.util.MainThreadWatch
import com.vscodroid.util.ServerLog
import com.vscodroid.util.redactSecrets
import com.vscodroid.webview.DocumentMeasure
import com.vscodroid.webview.DownloadCoordinator
import com.vscodroid.webview.DownloadHost
import com.vscodroid.webview.DownloadOutcome
import com.vscodroid.webview.EditorWebView
import com.vscodroid.webview.OpenDocument
import com.vscodroid.webview.pageCanReadDownload
import com.vscodroid.webview.VSCodroidWebChromeClient
import com.vscodroid.webview.VSCodroidWebView
import com.vscodroid.webview.VSCodroidWebViewClient
import com.vscodroid.webview.addPageColorListener
import com.vscodroid.webview.addPlainTextPageScript
import com.vscodroid.webview.keepStartColor
import com.vscodroid.webview.lastPageColor
import com.vscodroid.webview.urlLogLabel
import com.vscodroid.webview.COPY_DIAGNOSTICS_URL
import com.vscodroid.webview.RETRY_URL
import com.vscodroid.webview.SecretStorageKey
import com.vscodroid.webview.TlsFailure
import com.vscodroid.webview.TlsFailureReason
import com.vscodroid.webview.HandoffFailure
import com.vscodroid.webview.SignInRefusal
import com.vscodroid.webview.SignInRefused
import com.vscodroid.webview.handoffFailureType
import com.vscodroid.webview.handoffFailureToAnnounce
import com.vscodroid.webview.redactToken
import com.vscodroid.webview.tlsFailureToAnnounce
import org.json.JSONException
import org.json.JSONObject
import kotlin.concurrent.thread
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import androidx.core.content.edit
import androidx.core.net.toUri
import android.annotation.SuppressLint

class MainActivity : AppCompatActivity(), BlockingNoticeHost {
    @JvmField internal val tag = "MainActivity"

    @JvmField internal var webView: WebView? = null
    @JvmField internal var extraKeyRow: ExtraKeyRow? = null
    private val keyboardHandler = Handler(Looper.getMainLooper())

    /**
     * What the page is told when the keyboard goes away, held back for a moment:
     * see [KeyboardDismissal] for why a turn of the phone must not reach it.
     */
    private val keyboardDismissal = KeyboardDismissal(
        schedule = { task, delayMs -> keyboardHandler.postDelayed(task, delayMs) },
        cancel = { task -> keyboardHandler.removeCallbacks(task) },
        now = { SystemClock.uptimeMillis() },
        imeVisible = { extraKeyRow?.imeVisible == true },
        onDismissed = {
            // A keyboard the person put away is not one to answer yes for: the hold of the editor answer ends with it.
            endTextEditorHold()
            webView?.evaluateJavascript(
                "if (window.__vscodroidKeyboardDismissed) window.__vscodroidKeyboardDismissed();",
                null,
            )
        },
        onReport = { state -> logKeyboardHidden(state) },
        onExplicitDismissed = { ageMs ->
            endTextEditorHold()
            val gestureAtMs = System.currentTimeMillis() - ageMs
            webView?.evaluateJavascript("window.__vscodroidKeyboardDismissed && window.__vscodroidKeyboardDismissed(true, $gestureAtMs);", null)
        },
    )

    /**
     * The bound service, or null while nothing is bound.
     *
     * Volatile for the reason [watchedSafFolder], [syncingFolder] and
     * [openWorkspaceFolder] are: it is written on the UI thread, in
     * `onServiceConnected` and `onServiceDisconnected`, and read off the WebView's
     * resource-interception thread, through the connection-token suppliers
     * [initBridge] hands to the client and to the service worker.
     * `shouldInterceptRequest` performs synchronous HTTP, so it cannot be on the
     * UI thread. A stale null there reads as a server that is not ready: the
     * request waits for a token that this read would never see and is refused,
     * a webview that stays blank with nothing anywhere saying why.
     */
    @Volatile
    @JvmField
    internal var nodeService: NodeService? = null
    private var serviceBindingInitiated = false

    // The four callbacks this screen put on the service (ready, error, gave up, stopped), kept so that
    // [onDestroy] takes off the ones that are still its own and nothing else.
    private var serviceCallbacks: Array<Any?>? = null
    @Volatile
    @JvmField
    internal var serverPort = 0
    private var backgroundedAt = 0L
    private var bridgeInitialized = false

    /**
     * The TLS refusals already put on screen, so a page failing many requests to
     * one host is one message rather than a minute of them. See
     * [tlsFailureToAnnounce], which owns the rule and is tested without an
     * Activity.
     *
     * Held here rather than in the client because that class has no mutable state
     * and is worth keeping that way, and because the presenter is what knows when
     * a message was actually shown.
     */
    private val announcedTlsFailures = mutableSetOf<TlsFailure>()

    /**
     * When the last certificate notice was shown, for the interval the record falls
     * back on once it is past its cap.
     *
     * A plain field rather than an atomic, unlike the write-back throttle this
     * borrows its shape from: that one is claimed from two threads, and this is read
     * and written only inside the runOnUiThread below, which is the same reason the
     * set beside it needs no synchronisation.
     */
    private var lastTlsNoticeAt = 0L

    /**
     * The hand-off failures already put on screen, so a page driving many
     * navigations to a scheme nothing answers is one message rather than a stream
     * of them. See [handoffFailureToAnnounce], which owns the rule, explains why
     * the key is the scheme and the exception type rather than the URL, and is
     * tested without an Activity.
     *
     * Held here for the reason [announcedTlsFailures] is: the client has no mutable
     * state and is worth keeping that way, and the presenter is what knows when a
     * message was actually shown.
     */
    private val announcedHandoffFailures = mutableSetOf<HandoffFailure>()
    private val announcedMultiMirrorGenerations = mutableSetOf<Long>()

    /**
     * Whether a workbench page is loaded and able to receive an auth callback.
     *
     * Cleared by every path that puts a page of this app's own in front of the
     * workbench, because none of them can consume a callback: [recreateWebView],
     * whose replacement is a new page and does not carry the ids the workbench is
     * waiting on, since those live in its memory; [showErrorPage]; and
     * [retryServerStart], whose loading page is a `data:` document. Set again
     * only where the workbench itself has finished loading. See
     * [receiveCallbackIntent].
     */
    @JvmField internal var workbenchLoaded = false
    private var recoveryPageEpoch = 0L

    /**
     * When each renderer crash this Activity has handled arrived, oldest first.
     *
     * [recreateWebView] answers a dead renderer by rebuilding the view and
     * loading the workbench into it, and nothing counted how often that had
     * happened. Loading the workbench is the peak of this app's memory use and a
     * renderer killed for memory dies doing it, so the answer to the crash
     * reproduced the crash, for as long as the user left the app open: a page
     * flashing, a warm device, and nothing on screen ever saying why.
     *
     * Kept on the Activity rather than on the client that reports the crash.
     * `recreateWebView` builds a new WebView and `initBridge` a new
     * [VSCodroidWebViewClient] on every cycle, so a counter held there is reset by
     * the very event it counts; and the bootstrap client calls the same method
     * directly, so both crash paths are only covered here.
     *
     * Written and read on the main thread only: both callers of [recreateWebView]
     * are WebView callbacks, and the control that clears it is a navigation.
     */
    private val webViewCrashes = ArrayDeque<Long>()

    /**
     * Whether the renderer-crash page is what the WebView is showing.
     *
     * That page tells the user the editor will not be reopened until they ask,
     * and the one navigator that is not the user asking is `onServerReady`. A
     * server the service restarts after a crash (an OOM kill under the same
     * memory pressure that took the renderer is the ordinary way) announces
     * itself to this Activity exactly as a first start does, and the handler
     * loaded the workbench over the page unasked. [webViewCrashes] is still full
     * at that point, so the next renderer death was refused at once; what the
     * user saw was the loop run one more turn than the page had promised.
     *
     * Set and cleared where pages change: [showErrorPage] answers it from the
     * control it is drawing, and [loadVSCode] and [retryServerStart] clear it,
     * because each of those is either the user asking or the page they asked
     * for going up. Main thread only, like the record above it.
     */
    private var rendererCrashLoopShown = false

    /**
     * The previous-crash dialog and the away-from-home notice, held so the notice
     * never covers a crash screen: it waits for the first, and [showErrorPage]
     * takes it down. Main thread only.
     */
    @JvmField internal var crashDialog: AlertDialog? = null
    private var awayFromHomeNotice: AlertDialog? = null
    private var awayFromHomePendingShow: (() -> Unit)? = null
    private var androidBridge: AndroidBridge? = null
    @Volatile
    @JvmField
    internal var destroyed = false
    @JvmField internal var activeDialogs: MutableSet<AlertDialog>? = null
    @Volatile
    @JvmField
    internal var pendingRemovalLatches: MutableSet<CountDownLatch>? = null

    // The home port of an away-from-home notice the person has not answered yet, or 0. An activity
    // taken down before the answer gives it back to the gateway, so the next editor load says it:
    // the one made in its place, or the next one opened after closing the app from the recent apps,
    // which leaves the service and the temporary origin running. After Stop Server the gateway has
    // already released that origin, and the next start's bind writes the note again.
    private var awayFromHomeUnanswered = 0

    // The gateway's count of listeners opened when that notice was said, so it goes back only to the
    // listener it is about: see [returnUnansweredAwayFromHome].
    private var awayFromHomeListener = 0L
    private var pendingNoticeAfterCrash: (() -> Unit)? = null

    /**
     * Whether the "the editor restarted" explanation has already been shown.
     *
     * One per Activity, which is what the case it exists for needs and what an
     * outside caller cannot repeat. See [receiveCallbackIntent] for both halves.
     */
    private var restartNoticeShown = false

    /**
     * Set when notification permission arrives before the service binding does,
     * and consumed by [setupServiceCallbacks]. See [refreshServiceNotification].
     */
    private var notificationRefreshPending = false

    /**
     * The mirror and tree URI the file watcher is currently on, or null when it
     * is not running.
     *
     * A pair because [SafStorageManager.startFileWatcher] needs both and one is
     * useless without the other. Kept so that a failed folder switch can put the
     * previous folder's watcher back; see [restoreWatcherAfterFailure].
     *
     * Volatile because the removal guard reads it, and that read arrives on the
     * WebView's "JavaBridge" thread while every write here is on the UI thread.
     * The guard has to answer synchronously, so it cannot hop; a stale read of
     * this field is a mirror the editor has open being reported as free.
     */
    @Volatile
    @JvmField
    internal var watchedSafFolder: Pair<File, Uri>? = null

    /**
     * The folder [openSafFolder] was most recently asked to open and has not
     * finished with, if any.
     *
     * `navigateToFolder` loads `/?folder=..&tkn=..` and the server redirects, so
     * two page-finished callbacks arrive per switch. Without this, the second one
     * sees a watcher that is not yet installed and starts the same sync again.
     * Set when the open is asked for, not when its turn under [deviceFolderOpens]
     * comes, because those two callbacks arrive before any turn could.
     *
     * Volatile for the reason [watchedSafFolder] is: the removal guard reads it
     * off the WebView's bridge thread and cannot hop to ask. A mirror read as not
     * being synced while a sync is half way through it is one whose removal
     * leaves the folder part written under a watcher that starts afterwards.
     */
    @Volatile
    @JvmField
    internal var syncingFolder: Uri? = null

    /**
     * The mirror [adoptWorkbenchFolder] last said has no grant, so the second of the
     * two page loads a folder switch produces does not say it again. Main thread only.
     */
    @JvmField internal var ungrantedMirrorNoticed: String? = null

    /**
     * Held for the whole of one device folder open, from stopping the previous
     * watcher to starting the next, failure handling included.
     *
     * The engine behind [safManager] keeps one document-id cache and one
     * watcher, on the stated premise that it serves one folder at a time, and
     * nothing here enforced that. Two opens in flight at once (an adoption of
     * the folder a cold start reopened, overlapped by a script switching the
     * page to another) each ran to completion, and the second start of the
     * watcher stopped the first, so the folder watched at the end was whichever
     * sync finished last. When that was the adoption, which never navigates,
     * the page was left on a folder no watcher covered, and every save into it
     * stayed in the mirror with nothing on screen saying so.
     *
     * A coroutine mutex rather than a refusal, because the second open is
     * usually the one that matters: it is the folder the page is on now. An
     * adoption that has gone stale by the time its turn comes is skipped under
     * the lock instead; see [adoptionIsStale].
     */
    @JvmField internal val deviceFolderOpens = Mutex()

    /**
     * The directories this app publishes into the WebView and the locations the
     * interceptor must refuse to serve. Marked stale by the client at every
     * main-frame navigation and resolved lazily on the interceptor's off-main
     * thread (see [com.vscodroid.webview.ResourceRules]).
     *
     * Built on [applicationContext] rather than `this` so the rule resolver does
     * not retain the Activity, and one holder per Activity rather than per WebView:
     * [recreateWebView] clears `bridgeInitialized`, and the replacement client
     * marks the same holder stale on its first navigation.
     */
    private val resourceRules by lazy { com.vscodroid.webview.ResourceRules.of(applicationContext) }

    /**
     * The folder the workbench currently has open, derived once per navigation.
     *
     * Volatile because the two ends are on different threads and neither can
     * move: it is written from `onPageFinished` and from [navigateToFolder],
     * both on the UI thread, and read by the resource interceptor, which is not
     * on the UI thread: `shouldInterceptRequest` performs synchronous HTTP, so
     * it cannot be.
     *
     * A folder rather than the URL it came from, and that is the point of the
     * field existing at all. [folderFromUrl] stats the path; a supplier that
     * called it would stat once per resource request, and the workbench issues
     * hundreds during a cold load. Deriving on navigation pays for the
     * `isDirectory` guard once per folder switch and keeps it.
     *
     * Placeholder and error pages retain the last workspace. A completed workbench
     * load with an unusable selected path clears the live fields, while retaining
     * the last valid reopen preference. An explicit empty window also records the
     * closed state for the next launch.
     */
    @Volatile
    @JvmField
    internal var openWorkspaceFolder: String? = null

    /**
     * The same place, reduced to the directory the resource interceptor publishes.
     *
     * Volatile and written beside [openWorkspaceFolder] by the one writer, for the
     * same reason: the reader is `shouldInterceptRequest`, which is not on the UI
     * thread.
     *
     * A second field rather than a reduction at the point of use, and the field
     * above says why in its own words: reducing needs a `stat`, because a
     * `.code-workspace` is only a workspace when it is a FILE, and the interceptor
     * runs once per resource request while the workbench issues hundreds during a
     * cold load. Deriving here pays for it once per navigation.
     *
     * Correctness, not only cost. A `stat` on the request path answers for the
     * filesystem at that instant, so a workspace file momentarily absent, during a
     * save-replace or a mirror re-sync, would answer "not a file", the reduction
     * would not happen, and the published root would become the workspace file
     * itself. A root that is a single file matches only itself, so every resource
     * beside it would be refused until something navigated again.
     *
     * Not folded into [openWorkspaceFolder]: `mirrorNameFor` reads that one and
     * wants the path the user opened, not its parent.
     */
    @Volatile
    private var openWorkspaceRoot: String? = null
    private val workspaceResources = com.vscodroid.webview.WorkspaceResourcePublication(AuthTabWindow::pageGeneration)

    /**
     * Where the last open workspace and the key row's hidden state are
     * remembered, resolved once.
     *
     * The same file `PortFinder` and `SplashActivity` use, under keys of its
     * own. Lazy because the first `getSharedPreferences` for a file reads it off
     * disk on the calling thread; [setupExtraKeyRow] now reads it on every
     * launch, and a launch through `SplashActivity` has already loaded the file
     * into this process by then.
     */
    internal val workspacePrefs by lazy { getSharedPreferences(WORKSPACE_PREFS, MODE_PRIVATE) }

    internal lateinit var securityManager: SecurityManager
    internal lateinit var safManager: SafStorageManager

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        Logger.i(tag, "Notification permission granted=$granted")
        // The service has already promoted itself by the time this answer
        // arrives, and it did so while the answer was still "no", which on
        // Android 13+ means its notification was dropped rather than shown. See
        // NodeService.refreshNotification for the measurement.
        if (granted) refreshServiceNotification()
    }

    /**
     * SAF folder picker launcher.
     * When a user selects a folder, we persist the permission, sync to a local mirror,
     * and reload VS Code with the mirror path.
     */
    @JvmField
    internal val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        folderPickerPending = false
        uri?.let { handleSafFolderSelected(it) }
    }

    /** The folder picker is open, so its answer is on its way to this page; see [fileChooserIsPending]. */
    @JvmField internal var folderPickerPending = false

    /**
     * The device file picker behind `<input type=file>`, which is what the
     * Explorer's `Upload...` command opens.
     *
     * Two contracts and not one, because the contract is what decides the
     * picker's selection mode: offering multi-select to an input that asked for
     * a single file lets the user choose five and lose four without being told.
     *
     * Registered as fields, like every launcher above: `registerForActivityResult`
     * throws once the Activity is past its own `onCreate`.
     */
    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> deliverFileChooserResult(listOfNotNull(uri)) }

    private val multiFileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> deliverFileChooserResult(uris) }

    /**
     * Routes the picker's answer to the client that asked for it.
     *
     * Read back off the WebView rather than held in a field of its own: the
     * chrome client is replaced together with the view on a renderer crash, and
     * a result belonging to the page that died must not be handed to the one
     * that replaced it. A result arriving after the process was recreated finds
     * no client and is dropped, which is right, because the element waiting for
     * it went with the old process.
     */
    private fun deliverFileChooserResult(uris: List<Uri>) {
        val client = webView?.webChromeClient as? VSCodroidWebChromeClient
        if (client == null) {
            // Logged because a selection that goes nowhere looks from the outside
            // exactly like a picker that never opened, and the two need different
            // answers from whoever reads the report.
            Logger.w(tag, "No client left to answer; dropping ${uris.size} selection(s)")
            return
        }
        client.onFileChooserResult(uris)
    }

    /**
     * Where a downloaded file is written, chosen by the user.
     *
     * `CreateDocument` and not `OpenDocument`: saving needs a grant to write a
     * document that does not exist yet, and the read grant the file picker
     * returns cannot create one.
     *
     * The type is the wildcard because the name already carries the extension.
     * That name comes from the anchor the editor clicked, so it is `App.kt`
     * rather than a guess, and a picker told `text/plain` would offer to save
     * it as `App.kt.txt`. Naming a concrete type buys nothing here either: this
     * is the device's own storage picker, and it is being asked to create a
     * file, not to filter a list.
     */
    private val downloadDestinationLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        val requestId = pickerRequestId
        pickerRequestId = null
        downloads.onDestinationChosen(requestId, uri)
    }

    /**
     * The download the picker on screen was opened for.
     *
     * The contract answers with a `Uri` and nothing else, so the request has to
     * be carried across the launch by hand. Without it the coordinator would
     * have to take whatever it is holding as the owner of the answer, and a
     * result belonging to a download that has since been dropped would be
     * adopted by the one that replaced it: bytes written into a document
     * created under the wrong file's name.
     *
     * Main thread only, which is what makes a plain field enough: it is set
     * inside the post below and read in the callback above, and
     * [DownloadCoordinator] launches one picker at a time.
     */
    private var pickerRequestId: String? = null

    /**
     * Saving a file the editor asked to download.
     *
     * The Activity supplies the four things the coordinator cannot do itself:
     * the picker, the document behind the picker's answer, the page that holds
     * the bytes, and the user. Everything about *when* each of those happens is
     * in [DownloadCoordinator].
     *
     * The type is spelled out because this and [downloadDestinationLauncher]
     * name each other, and inference cannot start from either end.
     */
    private val downloads: DownloadCoordinator = DownloadCoordinator(object : DownloadHost {
        /**
         * Posted rather than launched here. A download that waited its turn is
         * started from whatever thread finished the one before it, which is the
         * WebView's bridge thread, and the activity result registry is main
         * thread state.
         */
        override fun askDestination(requestId: String, fileName: String) {
            runOnUiThread {
                // Posted, so it can run after this screen is gone, when launch
                // throws. The download is ended rather than left holding the queue,
                // and no picker is opened for a screen that cannot take its answer:
                // it would create the document and leave it empty.
                if (isFinishing || isDestroyed) {
                    downloads.onDestinationUnavailable(requestId)
                    return@runOnUiThread
                }
                pickerRequestId = requestId
                try {
                    downloadDestinationLauncher.launch(fileName)
                } catch (e: ActivityNotFoundException) {
                    Logger.w(tag, "No document creator on this device", e)
                    pickerRequestId = null
                    downloads.onDestinationUnavailable(requestId)
                } catch (e: IllegalStateException) {
                    Logger.w(tag, "The document creator is no longer registered", e)
                    pickerRequestId = null
                    downloads.onDestinationUnavailable(requestId)
                }
            }
        }

        /**
         * Measured through a descriptor of its own opened read-only, never through the one the
         * download writes with: "wt" truncates on the platform's providers, so a document
         * measured after that open would always look empty.
         */
        override fun measureDestination(destination: Uri): DocumentMeasure? {
            val pfd = contentResolver.openFileDescriptor(destination, "r") ?: return null
            pfd.use {
                val fd = it.fileDescriptor
                val stat = Os.fstat(fd)
                if (!OsConstants.S_ISREG(stat.st_mode)) return DocumentMeasure(length = null)
                // A file system with no blocks at all is a pseudo one that says nothing about
                // room, and taking its zero at face value would refuse every save.
                val free = try {
                    Os.fstatvfs(fd).takeIf { vfs -> vfs.f_blocks > 0 }?.let { vfs -> vfs.f_bavail * vfs.f_frsize }
                } catch (e: ErrnoException) {
                    null
                }
                return DocumentMeasure(stat.st_size, occupied = stat.st_blocks * 512, free = free)
            }
        }

        override fun destinationName(destination: Uri): String? {
            return contentResolver.query(
                destination,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                val index = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index)?.takeIf { it.isNotBlank() } else null
            }
        }

        /**
         * Opened as a descriptor rather than a stream, so that when the provider hands back a
         * regular file its length can be read and cut: the platform's providers open mode "w"
         * without truncating, and a provider may ignore the "t" of "wt". A pipe has neither.
         */
        override fun openDestination(destination: Uri, mode: String): OpenDocument? {
            val pfd = contentResolver.openFileDescriptor(destination, mode) ?: return null
            val fd = pfd.fileDescriptor
            val isFile = try {
                OsConstants.S_ISREG(Os.fstat(fd).st_mode)
            } catch (e: ErrnoException) {
                false
            }
            return OpenDocument(
                ParcelFileDescriptor.AutoCloseOutputStream(pfd),
                length = if (isFile) ({ Os.fstat(fd).st_size }) else null,
                truncate = if (isFile) ({ size: Long -> Os.ftruncate(fd, size) }) else null,
            )
        }

        /**
         * Deletes a document a failed download created, or an empty one the user chose to overwrite:
         * the two cannot be told apart, and the coordinator asks only for a document it measured empty.
         *
         * The picker creates the file when the user confirms the name, so by
         * the time anything can go wrong there is already a file in their
         * folder wearing the name of the one they wanted. Left alone it is a
         * download that looks finished until it is opened.
         */
        override fun discardDestination(destination: Uri) {
            try {
                DocumentsContract.deleteDocument(contentResolver, destination)
            } catch (e: Exception) {
                // Reported and not raised: this only ever runs on a path that has
                // already failed, and a delete that fails leaves a file the user
                // can delete themselves, which is not worth a second message.
                // The class and not the exception: its message names the document the page chose.
                Logger.w(tag, "Could not remove the unfinished download: ${e.javaClass.simpleName}")
            }
        }

        override fun requestBytes(requestId: String, url: String) {
            // The answer is used, and only for the one case the page cannot
            // report itself: the capture script not being there at all. Its own
            // failures come back through finishDownload; a missing script has
            // nothing to send one with, and without this the download would sit
            // on a created file forever with nothing said.
            //
            // Anything that is not "true" is that case, rather than only
            // "false". The script answers with a JSON literal, so a read that
            // began is `"true"` and nothing else is; an expression that throws
            // or yields undefined reaches this callback as `"null"`, which
            // testing for "false" alone read as success. The download then sat
            // in `pending` for ever, the queue behind it never drained, and the
            // user was left with an empty file wearing the name they chose and
            // no message either way.
            val script = "(function() {" +
                "  if (!window.__vscodroidDownload) return false;" +
                "  return window.__vscodroidDownload.send(" +
                "${JSONObject.quote(url)}, ${JSONObject.quote(requestId)});" +
                "})()"
            webView?.evaluateJavascript(script) { answer ->
                if (answer != "true") {
                    downloads.onComplete(requestId, "the page cannot read this download")
                }
            } ?: downloads.onComplete(requestId, "there is no page to read this download")
        }

        /**
         * Hopped and unanswered, unlike [requestBytes]. A download can end on
         * the WebView's bridge thread and `evaluateJavascript` is the main
         * thread's alone; and there is nothing to learn from the answer, since
         * a page that has no hold for this URL is the ordinary case rather than
         * a failure.
         */
        override fun releaseBytes(url: String) {
            val script = "(function() {" +
                "  var d = window.__vscodroidDownload;" +
                "  if (d) d.release(${JSONObject.quote(url)});" +
                "})()"
            runOnUiThread { webView?.evaluateJavascript(script, null) }
        }

        /**
         * On the main thread, where the platform delivers a download and where [serverPort] is
         * written: the page the bytes would be asked of was loaded from that port.
         */
        override fun pageCanRead(url: String): Boolean = pageCanReadDownload(url, serverPort)

        override fun report(outcome: DownloadOutcome, fileName: String, detail: String?) {
            // Both halves are page supplied and this line is not gated on a
            // debuggable build, so it ships. `fileName` arrives through
            // `DownloadCoordinator.onDownloadNamed`, where the page names the file
            // itself, and `detail` reaches here from `onComplete(requestId, error)`,
            // whose error string the page also writes. Neither is followed by a
            // predicate that could catch this, because both are renamed on the way
            // in, which is how this site was missed while its siblings in
            // `AndroidBridge` were redacted.
            if (detail != null) {
                Logger.w(tag, "Download of ${redactToken(fileName)}: ${redactToken(detail)}")
            }
            // Resources rather than literals, and the reason is that these three
            // are the whole user-visible outcome of the download feature. A
            // message assembled into a local before it reaches the sink is the
            // shape `check-translatable-strings.py` names as the hole it cannot
            // see, so these stayed English in every locale while the gate
            // reported clean.
            val message = when (outcome) {
                DownloadOutcome.SAVED -> getString(R.string.download_saved, fileName)
                DownloadOutcome.CANCELLED -> getString(R.string.download_cancelled)
                DownloadOutcome.FAILED -> getString(R.string.download_not_saved, fileName)
            }
            // Hopped because the bytes arrive on the WebView's bridge thread, so
            // the end of a download is reported from a thread that cannot touch
            // a Toast.
            runOnUiThread { AppNotices.showNotice(this@MainActivity, message, Toast.LENGTH_SHORT) }
        }
    },
        // A lambda because this initializer runs before the Activity has a context to ask;
        // the coordinator asks only on its provider thread, during a download.
        stagingDir = { cacheDir },
        // The app's files and cache share a filesystem.
        stagingRoom = { StorageManager.getAvailableStorage(this@MainActivity) },
    )

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as NodeService.LocalBinder
            nodeService = binder.getService()
            Logger.i(tag, "Bound to NodeService")
            setupServiceCallbacks()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            nodeService = null
            Logger.w(tag, "Disconnected from NodeService")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Before super.onCreate(), as the call it replaces required.
        drawBehindSystemBars()
        super.onCreate(savedInstanceState)
        lastConfiguration = Configuration(resources.configuration)
        // A screen made again (a font size or language change, or a restore after
        // the process was killed) is handed the intent it last had, and Recents
        // restarts a task whose screens are gone with its first intent, marked as
        // launched from history. A callback in either was answered when it
        // arrived, so its data goes before the two readers below see it: read
        // again, it raised the restart notice on every recreation and rode the
        // hand-off through setup after an update.
        intent?.let {
            if (savedInstanceState != null ||
                (it.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0
            ) {
                it.data = null
            }
        }
        // First, because everything below it assumes an extracted tree. See
        // handOffToSetup for what reaching this activity without one costs.
        if (handOffToSetup()) return
        // One repair from SplashActivity's launch block, and only this one,
        // because it is the one whose absence is fatal rather than degrading.
        // The machine settings name usr/lib/libtermux-exec.so in LD_PRELOAD for
        // every terminal, and the linker aborts every exec whose preload it
        // cannot map, the shell included. Measured 2026-09-23 with the file
        // deleted: a direct start of this activity on API 33 and 36 emulators
        // left it absent, and every terminal the panel opened died with CANNOT
        // LINK EXECUTABLE until a cold launch through SplashActivity put it
        // back. The rest of that block stays there, for the reason
        // handOffToSetup gives: a folder may be open here. This one touches
        // nothing a session reads. The warm route, a launcher tap that brings a
        // live singleTask instance forward without creating it, never reaches
        // this method at all; onResume carries the same repair for it.
        //
        // On the main thread, as SplashActivity runs its block, and guarded the
        // way its repair() guards each one: a full disk turning the write into
        // an exception costs this repair and not the launch. When the file is
        // current this is a stat of the file, a read of the asset's length and
        // a package lookup, and it sits ahead of MainThreadWatch.install() at
        // the tail of this method, so on the first onCreate of a process the
        // write, deliberate, precedes the policy; a later onCreate in the same
        // process runs under the policy the earlier one installed, which only
        // logs.
        try {
            FirstRunSetup(this).ensureExecPreload()
        } catch (e: Exception) {
            Logger.e(tag, "Launch-time refresh of the exec preload failed", e)
        }
        setContentView(R.layout.activity_main)

        // The application context, and that is the whole of what this outlives an
        // Activity by. The manager's engine owns the `saf-writeback` daemon, which
        // onDestroy waits two seconds for and then leaves running rather than
        // discarding writes the user expects on the device; every reference the
        // engine holds is held for as long as that drain takes. Built with `this`,
        // that was a destroyed Activity and its whole inflated view tree, on a
        // device this app is trying to leave memory on.
        safManager = SafStorageManager(applicationContext)
        // Read into locals for the same reason, and used by every notice below:
        // a lambda that says `this@MainActivity`, `getString` or `runOnUiThread`
        // captures the Activity and hands it straight back to the engine. The
        // notices themselves have to survive, because the drain they report on is
        // exactly the part that outlives the screen.
        val appContext = applicationContext
        val toMainThread = Handler(Looper.getMainLooper())
        val screen = WeakReference(this)
        // The manager as a local for the same reason: reading the field inside one of
        // these lambdas is a read of `this`, and captures the Activity exactly as
        // naming it would. ActivityRetentionTest refuses the field spelling there.
        val storage = safManager
        // A save that never reached the device folder looks exactly like one that did.
        // The engine has no screen, so the notice is wired here; it is throttled inside
        // the manager, because a provider that starts refusing refuses everything.
        safManager.onWriteBackFailed { file ->
            toMainThread.post {
                AppNotices.showNotice(
                    appContext,
                    appContext.getString(R.string.saf_write_back_failed, file.name),
                    Toast.LENGTH_LONG,
                    screen.get(),
                )
            }
        }

        safManager.onDeviceCopiesPreserved { copies ->
            val message = if (copies.size == 1) {
                appContext.getString(R.string.saf_device_copy_preserved, preservedDeviceNameForNotice(copies.single().name))
            } else {
                appContext.resources.getQuantityString(
                    R.plurals.saf_device_copies_preserved, copies.size, copies.size
                )
            }
            toMainThread.post {
                AppNotices.showNotice(appContext, message, Toast.LENGTH_LONG, screen.get())
            }
        }

        // The same silence, a whole folder at a time: a folder created in the editor
        // that did not arrive whole on the device. One notice per folder, and the cap
        // gets its own wording because it is a limit this app chose rather than the
        // device refusing.
        //
        // The name is resolved rather than taken off the File, because the pass that
        // reports stranded uploads can only hand over the mirror ROOT, whose
        // directory name is a digest: that notice read "a1b2c3d4e5f6 is too large to
        // copy out whole", naming nothing the user has ever seen. See
        // mirrorDisplayName.
        safManager.onUploadIncomplete { dir, lost, capped ->
            val folder = mirrorDisplayName(storage.getPersistedFolders(), dir)
            val message = uploadIncompleteNotice(appContext.resources, folder, lost, capped)
            toMainThread.post {
                AppNotices.showNotice(appContext, message, Toast.LENGTH_LONG, screen.get())
            }
        }

        // Files only the copy holds that an open keeps inside the app because it cannot tell them
        // from documents deleted on the device. Named, because the person decides about each, and
        // in a sentence of its own: saving such a file again sends it, which is not true of what
        // the upload notice above counts. One file has its own wording, as a kept edit does.
        safManager.onFilesKeptInApp { dir, count, first ->
            val folder = mirrorDisplayName(storage.getPersistedFolders(), dir)
            val message = filesKeptInAppNotice(appContext.resources, folder, count, first)
            toMainThread.post {
                AppNotices.showNotice(appContext, message, Toast.LENGTH_LONG, screen.get())
            }
        }

        // Sending what a program changed while the folder opened failed partway. What was not sent then is a save
        // inside the app, a deletion or a rename the device did not get; one sentence says all three. After a switch
        // that failed, the folder named is the one already open, which did not open then: its sentence says so.
        safManager.onOpenChangesUnsent { dir, afterSwitch ->
            val message = appContext.getString(
                if (afterSwitch) R.string.saf_switch_changes_unsent else R.string.saf_open_changes_unsent,
                mirrorDisplayName(storage.getPersistedFolders(), dir),
            )
            toMainThread.post {
                AppNotices.showNotice(appContext, message, Toast.LENGTH_LONG, screen.get())
            }
        }

        // A save caught as an open put the device's newer copy in its place, which could not be kept whole. Its own
        // sentence: the kept-copy notice below says a copy was kept, which is not all of what happened, or not true.
        safManager.onHeldSaveIncomplete { file ->
            val message = appContext.getString(R.string.saf_held_save_incomplete, file.name)
            toMainThread.post {
                AppNotices.showNotice(appContext, message, Toast.LENGTH_LONG, screen.get())
            }
        }

        // The other direction: documents the device holds that did not reach the editor.
        // Its own wording, because "the only copy is inside VSCodroid" is the opposite of
        // true for these, and would send the user looking for a file that is safe.
        safManager.onDocumentsNotCopied { count, outOfRoom ->
            toMainThread.post {
                AppNotices.showNotice(
                    appContext,
                    appContext.resources.getQuantityString(
                        if (outOfRoom) R.plurals.saf_documents_not_copied_no_room
                        else R.plurals.saf_documents_not_copied,
                        count,
                        count,
                    ),
                    Toast.LENGTH_LONG,
                    screen.get(),
                )
            }
        }

        // Something deleted in the editor that stayed on the device, because deleting
        // it would have taken content the sync never copied in. Its own wording again:
        // the mirror copy is gone, so "the only copy is inside VSCodroid" would be
        // exactly backwards. Two sentences rather than one, because a directory was
        // kept for what is inside it and a document was kept for what it is, and the
        // engine has to say which: the entry is already unlinked by the time the event
        // arrives, so nothing here can ask the disk. Each comes twice, because a file the
        // editor had and the user deleted is kept for a change on the device, which "the
        // editor never had a copy of it" gets backwards too.
        safManager.onKeptOnDevice { kept, isDirectory, changed ->
            val message = appContext.getString(
                when {
                    changed && isDirectory -> R.string.saf_directory_changed_kept
                    changed -> R.string.saf_document_changed_kept
                    isDirectory -> R.string.saf_directory_kept
                    else -> R.string.saf_document_kept
                },
                kept.name,
            )
            toMainThread.post {
                AppNotices.showNotice(appContext, message, Toast.LENGTH_LONG, screen.get())
            }
        }

        // The other way a deletion ends with the device still holding the file,
        // and the one the app did not choose: the provider said no. Worth its own
        // sentence because the file comes back on the next open, so silence here
        // reads as the editor undoing a deletion by itself.
        safManager.onDeleteRefused { refused ->
            val message = appContext.getString(R.string.saf_delete_refused, refused.name)
            toMainThread.post {
                AppNotices.showNotice(appContext, message, Toast.LENGTH_LONG, screen.get())
            }
        }

        // The same outcome when nothing refused: another write to the same storage held the
        // delete past its wait. Its own sentence, because the refusal's sends the user to the
        // file manager for a file that deleting again after the next open removes.
        safManager.onDeleteBusy { busy ->
            val message = appContext.getString(R.string.saf_delete_busy, busy.name)
            toMainThread.post {
                AppNotices.showNotice(appContext, message, Toast.LENGTH_LONG, screen.get())
            }
        }

        safManager.onLocalCopiesPreserved { copies ->
            if (copies.isEmpty()) return@onLocalCopiesPreserved
            val message = if (copies.size == 1) {
                appContext.getString(
                    R.string.saf_local_copy_preserved,
                    preservedLocalNameForNotice(copies.single().name),
                )
            } else {
                appContext.resources.getQuantityString(
                    R.plurals.saf_local_copies_preserved,
                    copies.size,
                    copies.size,
                )
            }
            toMainThread.post {
                AppNotices.showNotice(appContext, message, Toast.LENGTH_LONG, screen.get())
            }
        }

        setupWebView()
        setupExtraKeyRow()
        setupBackNavigation()
        requestNotificationPermission()
        startAndBindService()
        checkPreviousCrash()
        // From Android 12 the warning is too long for a toast and is a dialog, which this screen cannot
        // show before it has started: with its once key it is held, unspent, and shown at onStart.
        checkStorageHealth()
        checkWebViewVersion()
        // Reading it here, and not only in onNewIntent, serves a callback that
        // starts this screen with nothing of it left to restore. A screen restored
        // after the app was killed while the browser had it gets the callback in
        // onNewIntent, which the platform delivers before onResume. See
        // receiveCallbackIntent.
        receiveCallbackIntent(intent)

        // Last, and the position is the whole of it. Everything above runs once
        // per launch and touches the filesystem on purpose; what comes after is
        // the editor session, which is where an unexpected main-thread read is
        // worth knowing about. Debug builds only, log only. See MainThreadWatch,
        // which lists what is expected to fire.
        MainThreadWatch.install()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Kept as this screen's intent, so a recreation is handed this one rather
        // than the launch's, and onCreate drops its data: it is answered here.
        setIntent(intent)
        receiveCallbackIntent(intent)
    }

    /**
     * Hides or shows the status and navigation bars. A swipe from the edge brings
     * them back for a moment and they hide again, so the page's full screen state
     * is not disturbed by looking at the clock.
     *
     * The window's insets follow on their own: both inset listeners ask for the
     * visible bars, which are zero while these are hidden, so the page grows into
     * the room and the display cutout keeps its padding.
     */
    private fun applyFullScreen(on: Boolean) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (on) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    /**
     * The system brings its bars back when another window has the focus (a
     * permission prompt, the recents screen), and nothing tells this activity
     * the choice was undone, so it is made again whenever the focus returns.
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        logWindowFocus(hasFocus)
        if (hasFocus && workspacePrefs.getBoolean(KEY_FULL_SCREEN, false)) applyFullScreen(true)
    }

    override fun onPause() {
        super.onPause()
        // A window that is leaving is not one to answer yes for.
        endTextEditorHold()
    }

    override fun onResume() {
        super.onResume()
        // The warm half of the repair in onCreate. This activity is singleTask,
        // so a launcher tap on a live task brings it forward without creating
        // it, and a callback intent lands in onNewIntent; neither passes
        // through SplashActivity, and measured on an API 33 emulator,
        // 2026-09-23, the icon tap left a deleted usr/lib/libtermux-exec.so
        // absent and every new terminal dead. Off the main thread, because
        // MainThreadWatch is installed by now and a stat on every resume is
        // not what it exists to catch. A terminal opened before this finishes
        // is opened against the old state; the next one is not.
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                FirstRunSetup(applicationContext).ensureExecPreload()
            } catch (e: Exception) {
                Logger.e(tag, "Resume-time refresh of the exec preload failed", e)
            }
        }
    }

    /**
     * Sends a launch that got here without first-run setup back through it.
     *
     * The VIEW filter on this activity is exported and BROWSABLE, so any
     * installed app and any page the user taps a link on can start the editor
     * directly, and a `singleTask` activity started that way builds a fresh
     * instance rather than going through [SplashActivity]. On an install whose
     * setup has never run there is nothing behind it: measured after clearing
     * app data, `files/` held only `home` and `profileInstalled`, and the
     * service spawned `libnode.so` five times, each dying on a missing
     * `libz.so.1`, before telling the user the server had crashed repeatedly.
     * The same entry also skips the repairs [SplashActivity] runs on every
     * launch, all but the exec preload (see `onCreate`), so a session reached
     * this way runs on dangling `usr/bin` symlinks and on `settings.json` paths
     * naming the previous install's native library directory.
     *
     * The filter stays on this activity rather than moving to the splash
     * screen, and that is a decision rather than an omission. A callback
     * arriving while the editor is running has to reach `onNewIntent` on the
     * live page. Keeping [SplashActivity]'s launch repairs away from an open
     * device folder is not a reason: a launch never guaranteed that, and
     * [SafStorageManager.reclaimRevokedMirrors] is safe for what it touches
     * rather than for when it runs.
     *
     * The intent travels with the hand-off, so the sign-in this filter exists
     * for is not lost: [SplashActivity] passes `data` on to the activity it launches
     * once setup finishes. Not the extras: both activities are exported, and copying
     * another app's extras unparcels them here, which a class this app lacks makes throw.
     *
     * It cannot loop. Every route from [SplashActivity] to this activity runs
     * after `markSetupComplete()`, which is what `isFirstRun()` reads, and the
     * one screen that does not reach it (a failed setup) offers Retry and no
     * way past. A future route that launched the editor with setup still
     * incomplete would bounce back here, so keep that property when adding one.
     */
    private fun handOffToSetup(): Boolean {
        if (!FirstRunSetup(this).isFirstRun()) return false
        Logger.w(tag, "Started before setup had run; handing the launch to the splash screen")
        startActivity(Intent(this, SplashActivity::class.java).apply {
            data = intent?.data
        })
        finish()
        return true
    }

    /**
     * Takes the OAuth callback relay out of an intent, if that is what it is.
     *
     * The only intent that carries a URI here is the callback relay, which is the
     * one VIEW filter the manifest still declares. Anything else is the launcher
     * bringing this singleTask activity to the front, and there is nothing to do
     * for it.
     *
     * Both entry points feed this, and only one of them used to exist. The
     * callback opens in the browser while the workbench runs in this WebView, and
     * Android is free to kill an app whose screen the browser has taken; a phone
     * under memory pressure signing into an extension is a routine way to get
     * there. The returning `vscodroid://callback` then builds a *new*
     * `MainActivity`, whose `onCreate` never looked at `intent.data`;
     * `SplashActivity.launchMain()` even forwards the data along, and it was
     * dropped on arrival.
     *
     * Reading it is where the recovery stops, though, and the reason is in the
     * workbench rather than here. `out/vs/code/browser/workbench/workbench.js`
     * keeps the ids it is waiting for in a plain in-memory Set
     * (`pendingCallbacks = new Set`, added to when a request is created, and never
     * written to storage), and `checkCallbacks()` iterates *that*, reading
     * `localStorage` only for ids already in it. So a relayed value is consumable
     * by exactly the page instance that began the sign-in. Once that page is
     * gone, which is the whole premise of arriving through `onCreate`, injecting
     * would write a key nothing ever reads and fire an event nothing is
     * listening for, and leave the value behind permanently, since the cleanup
     * is also keyed on the pending set.
     *
     * So this says so instead. A user who came back from the browser expecting
     * to be signed in is owed the reason, and "sign in again" is an instruction
     * they can act on; silently relaying into a page that drops it is the
     * failure that was already there, wearing a fix.
     *
     * The two refusals below are deliberately not alike. A request this app never
     * launched is refused in the log and nowhere else: the filter is exported, so
     * a message there would be one an outside caller could raise at any moment. A
     * request it did launch, arriving past its window, is worth a message for the
     * same reason the restart case is.
     *
     * That second message is bounded rather than gated, and the difference
     * matters. The id is a small integer the workbench counts from one, so what
     * keeps an outside caller out is the secret registered for the request: it is
     * checked before the window, and a caller without it reaches neither message.
     * A caller that does hold it can still only raise the message once,
     * because the record is retired as the message goes up ([AuthTabWindow.expire]
     * hides it and keeps its identity reserved until the page changes) -- one
     * explanation per launch, and every further arrival for that id lands in the
     * silent branch above.
     *
     * The restart message has a second way in, keyed the same way: a page can be
     * replaced inside one Activity (a server restart's reload, a folder switch)
     * with `workbenchLoaded` true throughout, and the launches it was waiting on
     * go with it ([AuthTabWindow.beginPage]). A return for one of those, carrying
     * its secret and inside its window, gets the restart message once
     * ([AuthTabWindow.takeReplaced]); anything else stays in the silent branch.
     *
     * Every message is fixed text; nothing from the callback reaches the screen.
     */
    private fun receiveCallbackIntent(intent: Intent?) {
        val uri = intent?.data ?: return
        if (!isExtensionCallback(uri.scheme, uri.host)) return
        // Bounded before anything parses it. Android's org.json recurses once per
        // nesting level with no depth cap of its own (platform sources, android-33
        // JSONTokener: nextValue calls readArray calls nextValue), and a
        // StackOverflowError is not a JSONException, so it leaves the readers
        // below, leaves onNewIntent, and takes the process with it. This filter is
        // exported and BROWSABLE, so anything on the device can deliver one.
        //
        // Measured on an API 33 emulator: 15,000 levels parsed and were refused
        // normally, and `am` could not deliver more, so the depth that overflows
        // was not reached from a shell. A real caller is not held to that limit,
        // which is why the bound is here rather than a note saying it looked fine.
        // A real callback is a few hundred bytes: 522 for a full GitHub round
        // trip, so this leaves more than an order of magnitude of room.
        val payload = uri.getQueryParameter("data")
        if (payload != null && payload.length > MAX_CALLBACK_PAYLOAD_CHARS) {
            Logger.w(tag, "Ignoring a sign-in callback whose payload is too large to be one")
            return
        }
        if (!workbenchLoaded) {
            // Ahead of the timing gate, and that ordering is the whole point.
            // This branch injects nothing; it explains. The case it exists for
            // is arriving through onCreate after the process was killed while
            // the browser had the foreground -- and a fresh process has no
            // record of opening a tab, so gating this would delete the
            // explanation for exactly the user who came back expecting to be
            // signed in.
            Logger.w(tag, "Extension callback arrived with no workbench page left to receive it")
            // Bounded, for the reason the expiry message below is bounded, and
            // this was the one message in this function that was neither bounded
            // nor keyed on a launch this app made. `workbenchLoaded` is false on
            // every cold start, for the whole of a server start-up, after
            // showServerGaveUp and after recreateWebView, so anything on the
            // device could fire this filter in a loop and hold the screen with a
            // long toast telling the user to sign in again, each delivery
            // bringing this app to the front on someone else's cue.
            //
            // The case it exists for still gets it: coming back from the browser
            // after the process was killed is one arrival into a fresh instance.
            if (!restartNoticeShown) {
                restartNoticeShown = true
                AppNotices.showNotice(
                    this,
                    getString(R.string.sign_in_editor_restarted),
                    Toast.LENGTH_LONG
                )
            }
            return
        }
        // The id the callback names, matched against the launch that could have
        // produced it. Null covers both a payload this cannot read and a request
        // this app never launched, and the two deserve the same answer: there is
        // no sign-in here to report on.
        val requestId = callbackRequestId(uri.getQueryParameter("data"))
        val armedAt = requestId?.let { AuthTabWindow.armedAt(it) }
        // The workbench registers this immutable nonce when it creates the
        // callback URI. Outgoing URL text can activate that identity but cannot
        // create or replace it. Check before expiry so an invalid return neither
        // retires the pending request nor raises its timeout notice.
        val offered = callbackNonce(uri.getQueryParameter("data"))
        val secretMatches = requestId != null && callbackSecretMatches(offered, AuthTabWindow.nonceFor(requestId))
        // A return the page on screen cannot take, for a launch an earlier page
        // was still waiting on when it was replaced while the browser had the
        // sign-in (a server restart's reload, a folder switch), with
        // workbenchLoaded true throughout. Said once per such launch, and only
        // for the secret it was registered with.
        if (requestId != null && (armedAt == null || !secretMatches) &&
            AuthTabWindow.takeReplaced(requestId, offered, SystemClock.elapsedRealtime())
        ) {
            Logger.w(tag, "A sign-in callback arrived after the page that started it was replaced")
            AppNotices.showNotice(
                this,
                getString(R.string.sign_in_editor_restarted),
                Toast.LENGTH_LONG
            )
            return
        }
        if (requestId == null || armedAt == null) {
            // Logged without the URI. It is the payload of a sign-in this app
            // did not start, and the log is readable by anything holding
            // READ_LOGS on a developer device. No toast either: a message here
            // would be one an outside caller could raise at will.
            Logger.w(tag, "Ignoring a sign-in callback that no sign-in was waiting for")
            return
        }
        if (!secretMatches) {
            Logger.w(tag, "Ignoring a sign-in callback that did not carry this request's secret")
            return
        }
        if (!authCallbackIsExpected(armedAt, SystemClock.elapsedRealtime(), AUTH_TAB_WINDOW_MILLIS)) {
            // Said out loud, unlike the branch above, because of what was just
            // established: this app launched this exact request itself. The user
            // has been reading a consent screen or waiting on a second factor for
            // longer than the window, and the alternative is an editor that
            // simply never signs in and never says why.
            //
            // Reserve the identity until this document is replaced, but hide it
            // from subsequent arrivals so this notice is shown once. Removing it
            // would let another address rearm the same pending request.
            AuthTabWindow.expire(requestId)

            // Fixed text, carrying nothing from the URI. What rides in the
            // callback is attacker-shaped by construction, and a message that
            // quoted any of it would be a way to put chosen words on the screen
            // of an app the user trusts.
            Logger.w(tag, "A sign-in callback arrived after its window had closed")
            AppNotices.showNotice(
                this,
                getString(R.string.sign_in_timed_out),
                Toast.LENGTH_LONG
            )
            return
        }
        // The id the gate just accepted, handed on rather than read again. See
        // handleExtensionCallback for why re-reading it was the defect.
        handleExtensionCallback(uri, requestId)
    }

    override fun onDestroy() {
        // Detached, because the wait it contains buys this teardown nothing.
        // SafSyncEngine.stopWatching stops the observers first and then joins the
        // write-back worker for up to two seconds, and the interrupt cannot
        // shorten that: the copy inside writeLocalToSaf streams through a
        // ContentResolver output stream, which is not interruptible. Nothing here
        // depends on the drain having finished, and a drain that outruns the wait
        // is left running either way, so on the main thread that join was two
        // seconds of a frozen screen while the user swiped the app away.
        //
        // The observers still come down first, on that thread, before the join.
        // What the hand-off costs is the moment between this returning and the
        // thread being scheduled, and reaching a second engine on the same mirror
        // in it would take a whole server start and page load.
        //
        // Guarded on the manager existing, because this activity can finish
        // before it builds one: handOffToSetup returns out of onCreate ahead of
        // every field below, and Android delivers onDestroy to an activity that
        // finished during onCreate. Reading a lateinit that was never assigned
        // throws, and a throw here is a crash on a path whose whole purpose is
        // to recover quietly.
        //
        // Both captured into locals, which keeps this thread from holding the
        // Activity: reading `safManager` or `tag` from inside the lambda would
        // capture `this`, and with it the view tree, until the thread returns.
        //
        // The shutdown rather than the stop, and that is what makes the detachment
        // safe. The three calls in openSafFolder and restoreWatcherAfterFailure are
        // serialised against each other by deviceFolderOpens; this one is outside it
        // on purpose, so it can land in the middle of a start already running on
        // Dispatchers.IO -- inside its own two-second drain, or inside the mirror
        // walk -- and neither the mutex nor cancelling the scope can stop that start
        // finishing afterwards. The shutdown is what the engine has to say "and
        // nothing starts this again" with: the ordinary stop would be overtaken and
        // leave observers and a write-back thread on an engine the replacement
        // Activity, which builds its own manager, cannot reach.
        //
        // Only half of what that costs was ever here, and the half that was is the
        // smaller one: this thread ends when the shutdown does, about two seconds.
        // The manager it names is what outlives the Activity, because the engine
        // leaves the write-back worker running when the drain outruns that wait,
        // which is why the manager is built on the application context and why the
        // device-folder notices in onCreate close over that and a main-thread
        // Handler rather than over this Activity.
        destroyed = true
        synchronized(this) { pendingRemovalLatches }?.let { latches ->
            latches.forEach { it.countDown() }
            latches.clear()
        }
        keyboardDismissal.release()
        val stopping = if (::safManager.isInitialized) safManager else null
        val logTag = tag
        if (stopping != null) {
            thread(name = "saf-watch-stop", isDaemon = true) {
                try {
                    stopping.shutdownFileWatcher()
                } catch (e: Exception) {
                    Logger.w(logTag, "Stopping the device folder watcher failed: ${e.message}")
                }
            }
        }
        // Before the WebView goes, because the page that owed the bytes is what
        // is about to be destroyed. Without this the stream opened on the user's
        // chosen document was dropped unclosed and the document left behind: a
        // file in their folder wearing the name of the one they wanted, holding
        // part of it, and indistinguishable from a finished save until opened.
        // Silent and idempotent by design, which is what a teardown needs.
        downloads.onPageGone()
        // Before unbinding, and this order is the point. The service is started
        // as well as bound, so it outlives this activity by design; the four
        // callbacks below are lambdas that close over `this`, so leaving them in
        // place hands a foreground service a strong reference to a destroyed
        // Activity, its WebView and its view tree. Unbinding does not clear
        // them: nothing overwrites them until some future activity binds and
        // installs its own, which may be never.
        //
        // Only the callbacks this screen installed, and only while they are still the ones on the
        // service. The service outlives the screen, and the app opened again just after Stop Server,
        // which finishes this screen and removes its task, makes a screen that binds to the same
        // service and installs its own while this one is still waiting to be destroyed. Clearing
        // whatever was there took the new screen's callbacks away: the server came up behind a
        // loading page that nothing ever replaced.
        nodeService?.let { service ->
            serviceCallbacks?.let { ours ->
                if (service.onServerReady === ours[0]) service.onServerReady = null
                if (service.onServerError === ours[1]) service.onServerError = null
                if (service.onServerGaveUp === ours[2]) service.onServerGaveUp = null
                if (service.onServerStopped === ours[3]) service.onServerStopped = null
            }
        }
        serviceCallbacks = null
        if (serviceBindingInitiated) {
            try {
                unbindService(serviceConnection)
            } catch (_: IllegalArgumentException) {
                // Already unbound: safe to ignore
            }
            serviceBindingInitiated = false
        }
        // Dropped before the view it wraps, exactly as recreateWebView does it
        // and for the same reason. The key row polls the page while a modifier
        // is latched, and a tick already in the looper's queue still runs after
        // this method returns; with the injector left in place it asks a
        // destroyed WebView, which answers by logging and never calling back.
        extraKeyRow?.keyInjector = null
        (webView?.webViewClient as? VSCodroidWebViewClient)?.disposeAuthPage()
        crashDialog?.setOnDismissListener(null)
        // Taken down with its screen, which the platform otherwise reports as a leaked window. Its
        // crash logs stay, so a screen made again for a font size or language change asks again.
        crashDialog?.takeIf { it.isShowing }?.dismiss()
        awayFromHomePendingShow = null
        awayFromHomeNotice?.setOnDismissListener(null)
        // Taken down with its screen for the same reason, and handed back: see [awayFromHomeUnanswered].
        awayFromHomeNotice?.takeIf { it.isShowing }?.dismiss()
        dismissActiveDialogs()
        returnUnansweredAwayFromHome()
        AppNotices.drop(this, carry = true)
        androidBridge?.shutdown()
        androidBridge = null
        webView?.destroy()
        webView = null
        super.onDestroy()
    }

    override fun onStop() {
        super.onStop()
        if (serverPort > 0) {
            backgroundedAt = SystemClock.elapsedRealtime()
        }
    }

    override fun onStart() {
        super.onStart()
        handleResumeFromBackground()
        refreshToolchainCommands()
        AppNotices.flush(this)
    }

    /**
     * Rebuilds the exec table each time the editor comes to the foreground.
     *
     * A command `pip install` writes cannot run until the table has a row for it, and
     * the table was rebuilt only by SplashActivity's launch pass. Leaving with Back or
     * Home and tapping the icon brings this singleTask activity back without that
     * pass, so "open VSCodroid again" left the command failing with `bad interpreter`
     * until the task was swiped from Recents. Cheap: one listing of `usr/bin` and two
     * small files written atomically, serialised with installs by the manager's lock.
     */
    private fun refreshToolchainCommands() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                ToolchainManager(applicationContext).regenerateDerivedFiles()
            } catch (e: Exception) {
                Logger.w(tag, "Could not refresh the toolchain commands: ${e.message}")
            }
        }
    }

    /**
     * The manifest's `configChanges` keeps the activity across a turn of the phone,
     * a resize or a fold, and across the other changes it lists, so this is where
     * they are seen. See [configurationChanged].
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        configurationChanged(newConfig)
    }

    /**
     * The configuration this activity was last given, so a change can be told apart
     * from a turn by what it changed ([isScreenTurn]). Null until [onCreate] records it.
     */
    private var lastConfiguration: Configuration? = null

    /**
     * [onConfigurationChanged] after the framework's part, apart so that a JVM test
     * can run it: the framework's own method cannot run there.
     *
     * A turn of the phone, a resize or a fold reports the keyboard hidden and it comes
     * back by itself, so [keyboardDismissal] must not hold the page's editor down for
     * that. Let the keyboard commit its word before finishing the composition and
     * releasing the old native input connection, the second only while the person is
     * aimed at text: the refocus it makes reaches the page's focus handler as a new
     * focus, which after a drag outside text holds the host and takes a keyboard that
     * stayed up away.
     *
     * Only a change of the screen's shape is such a turn. A change of navigation type,
     * of the night mode a schedule switches, or of the keyboard attached, also reaches
     * here and was taken for one: it finished a word the keyboard was composing, and
     * for up to 13 s ([KeyboardDismissal.TURN_CAP_MS]) credited to the turn a keyboard
     * the person put away, which then came back at the next scroll. A change whose
     * diff cannot be read (no configuration recorded yet) is taken for a turn, as
     * every change was before.
     */
    private fun configurationChanged(newConfig: Configuration) {
        val diff = lastConfiguration?.diff(newConfig)
        lastConfiguration = Configuration(newConfig)
        if (diff == null || isScreenTurn(diff)) {
            armTextEditorHold(keyboardDismissal.turned())
            edgeBackGesture.lifted()
            keyboardDismissal.edgeLifted()
            finishKeyboardCompositionOnTurn()
        }
        // The keyboard the extra key row stood aside for is gone, a Bluetooth one
        // asleep or unpaired, and the soft keyboard is what is left. A touch would
        // bring the row back, but the person may well carry on typing on the screen.
        if (newConfig.keyboard == Configuration.KEYBOARD_NOKEYS) {
            extraKeyRow?.hiddenForHardwareKeyboard = false
        }
    }

    private var keyboardCompositionTurn = 0

    private fun finishKeyboardCompositionOnTurn() {
        val targetView = webView ?: return
        val generation = AuthTabWindow.pageGeneration()
        val turn = ++keyboardCompositionTurn
        var attempts = 0
        fun attempt(start: Boolean) {
            if (webView !== targetView || keyboardCompositionTurn != turn ||
                AuthTabWindow.pageGeneration() != generation || isFinishing || isDestroyed) return
            attempts += 1
            targetView.evaluateJavascript(
                """
                (function() {
                    if (typeof window.__vscodroidFinishCompositionOnTurn !== 'function') return false;
                    return window.__vscodroidFinishCompositionOnTurn($start);
                })();
                """.trimIndent(),
            ) { editable ->
                if (webView !== targetView || keyboardCompositionTurn != turn ||
                    AuthTabWindow.pageGeneration() != generation || isFinishing || isDestroyed) return@evaluateJavascript
                if (editable == "\"wait\"") {
                    // Asked again with or without the focus: the window can lose it for a
                    // moment after a turn, and a poll dropped there leaves the page's wait standing.
                    if (Logger.debugEnabled && (!targetView.isFocused || !targetView.hasWindowFocus())) {
                        Logger.d(
                            tag,
                            "turn wait asked again without the focus: focused=${targetView.isFocused} " +
                                "windowFocus=${targetView.hasWindowFocus()} attempt=$attempts",
                        )
                    }
                    targetView.postDelayed({ attempt(false) }, 50L)
                } else if (editable == "true") {
                    // Release the old native connection only after the page's wait has ended too,
                    // and only to a view that still has the focus.
                    if (!targetView.isFocused || !targetView.hasWindowFocus()) return@evaluateJavascript
                    targetView.clearFocus()
                    targetView.requestFocus()
                } else if (editable == "\"none\"") {
                    // The focus is in a plain text box, for which no refocus is made, so the turn stops
                    // waiting for one: a keyboard put away over Quick Open or the find box is then the
                    // person's after the floor. Every other no leaves the turn waiting, as it did.
                    keyboardDismissal.noRefocus()
                }
            }
        }
        attempt(true)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Nothing here branches on the level itself: memoryPressureOf maps it,
        // and a comparison in its place is what once read every app switch as
        // critical. This method cannot be invoked without an Activity, so what
        // remains is super, the map, and two effects that need the Activity.
        // Nothing is recorded for the process monitor any more; it used to
        // read a file written here and kill idle language servers on it, which
        // measured as reclaiming nothing (see IDLE_THRESHOLD_MS in
        // process-monitor.js), so the log line and the page are the whole of it.
        val pressure = memoryPressureOf(level)
        if (pressure == PRESSURE_NONE) return
        Logger.w(tag, "Memory pressure: $pressure (trim level $level)")
        webView?.evaluateJavascript(
            "window.__vscodroid?.onLowMemory?.($level)", null
        )
    }


    /**
     * Posts one late answer into the page's relay, against the id its caller
     * sent.
     *
     * Guarded on the hook existing rather than assumed, and neither guard is
     * defensive. A folder switch navigates this same WebView, so an answer that
     * arrives after the navigation reaches a page with no relay in it and no
     * promise waiting for one; a renderer crash replaces the view entirely, and
     * `webView` is null between the two. Nothing is lost by dropping the answer
     * in either case, because the extension host that asked the question went
     * with the page, and the caller's own deadline is what ends the wait.
     *
     * Both values are quoted through [JSONObject.quote] rather than
     * interpolated. The payload is a listing built from device folder names,
     * which the user chose and this app never sanitised, so a quote or a
     * backslash in one would otherwise end the string literal early and the rest
     * of the name would be evaluated as script in our own page's realm.
     */
    private fun answerBridgeCommand(id: String, ok: Boolean, payload: String) {
        val script = "if (window.__vscodroidBridgeReply) window.__vscodroidBridgeReply(" +
            "${JSONObject.quote(id)}, $ok, ${JSONObject.quote(payload)})"
        webView?.evaluateJavascript(script, null)
    }

    // -- Internal --

    /**
     * After resuming from background, checks if the VS Code connection is still
     * healthy. Android freezes WebView JS execution in the background, which can
     * cause the WebSocket IPC channel to the server to time out. This leads to
     * "Canceled" errors on gallery requests and IndexedDB connections closing.
     *
     * Strategy:
     * - a device file picker still waiting to be answered: nothing at all,
     *   whatever the absence
     * - >5 min background with a sign-in in flight: health check instead of the
     *   forced reload
     * - >5 min background: force reload (stale state almost certain)
     * - >1 min background: run JS health check, reload only if broken
     * - <1 min: no action needed (WebSocket survives short pauses)
     *
     * Neither of the first two lines is about staleness. Five minutes in the
     * background is the ordinary shape of a sign-in that needed a second factor
     * or an organisation's consent screen, so the reload written for a stale
     * WebSocket was firing precisely when the user came back from one, and it
     * discards the page the callback has to land in. Browsing device storage for
     * a file is the same trip through another app, and it ends the same way, with
     * a result being handed to this page.
     */
    private fun handleResumeFromBackground() {
        val ts = backgroundedAt
        if (ts == 0L || serverPort == 0) return
        backgroundedAt = 0

        // Don't interfere if server is restarting; onServerReady handles reload.
        //
        // The same distinction as in setupServiceCallbacks, and the same reason:
        // a restart respawns the process long before the editor server inside it
        // is answering, so a liveness check (the removed isServerRunning(), which
        // read Process.isAlive) called a mid-restart server healthy and let the
        // branches below reload the page into a port that was not listening yet.
        // Through shouldActOnResume for the same reason as the binding decision:
        // the verdict has to be obeyed, and only a function that returns it can be
        // tested for obeying it. `backgroundedAt` is still consumed above whether
        // or not this passes, which is the behaviour that was here before.
        if (!shouldActOnResume(nodeService?.isServerReady(), ts, serverPort)) return

        val bgMs = SystemClock.elapsedRealtime() - ts
        when (resumeAction(bgMs, signInIsPending(), fileChooserIsPending(), savePickerIsPending())) {
            ResumeAction.RELOAD -> {
                Logger.i(tag, "Reloading after ${bgMs / 1000}s in background")
                // reload(), not a rebuilt URL. The WebView URL is the only
                // truthful record of what is open, and rebuilding reads only
                // `folder` back out of it, so a multi-root workspace
                // (`?workspace=<file>`) or a closed folder (`?ew=true`), both of
                // which the workbench navigates to on its own, would come back as
                // the default projects directory instead.
                //
                // Authentication is not a reason to rebuild here: the cookie the
                // connection token rides in lasts a week and this branch fires at
                // five minutes. A process idle long enough for it to expire will
                // have been killed by Android first, and the cold start that
                // follows re-sends the token in the query, which the server turns
                // into a fresh cookie.
                //
                // Nor is a server restart. Every spawn writes a new token, and
                // every spawn is followed by onServerReady, whose loadVSCode
                // rebuilds the URL with it, so the cookie this reload sends
                // already holds the live one.
                // Automatic recovery leaves an unsaved-work veto to the user.
                webView?.reload()
            }
            ResumeAction.PROBE_CONNECTION -> checkConnectionHealth(bgMs)
            ResumeAction.NOTHING -> Unit
        }
    }

    /**
     * Whether a sign-in this app started is still able to come back.
     *
     * Asked of the same record the callback relay is judged against, so the two
     * cannot drift: whatever [receiveCallbackIntent] would still accept is
     * whatever the reload has to keep out of the way of.
     *
     * The record outlives the callback's arrival on purpose, which is what closes
     * the narrower race. A callback delivered a moment ago is injected into the
     * page and collected asynchronously by the workbench; a record consumed on
     * arrival would answer "nothing pending" during exactly that gap and let the
     * reload discard the value it had just been handed.
     */
    private fun signInIsPending(): Boolean {
        val now = SystemClock.elapsedRealtime()
        return AuthTabWindow.armedReadings().any {
            authCallbackIsExpected(it, now, AUTH_TAB_WINDOW_MILLIS)
        }
    }

    /**
     * Whether an `<input type=file>` or the Open Folder picker is still waiting for the device picker.
     *
     * Asked of the chrome client on the current WebView, which is where
     * [deliverFileChooserResult] takes the answer, so the two cannot disagree
     * about which document is owed one. A client that was replaced with its page
     * reports nothing pending, which is right: there is no selection left to
     * protect. The folder picker is the same trip through another app, and a
     * reload on the way back would come ahead of its answer, raise the leave-page
     * dialog over the sync progress and load the page twice.
     */
    private fun fileChooserIsPending(): Boolean =
        (webView?.webChromeClient as? VSCodroidWebChromeClient)?.hasPendingFileChooser == true || folderPickerPending

    /**
     * Whether the create-document picker for a download is still waiting to be
     * answered.
     *
     * Read from [pickerRequestId] rather than from the coordinator, because this
     * is the record the picker's own result clears: it is set where the launch is
     * made and taken back in [downloadDestinationLauncher]'s callback, both on
     * the main thread and both in this file, so it cannot drift from the answer
     * it is waiting for. A launch that threw clears it too.
     *
     * It is still set when this is asked. Android delivers an activity result
     * after `onStart` and before `onResume`, and this decision is made from
     * `onStart`, so the picker's answer is still in flight at exactly this point.
     */
    private fun savePickerIsPending(): Boolean = pickerRequestId != null

    /**
     * Probes the WebView for an IndexedDB connection that did not survive being
     * frozen, and reloads the page from JS if it finds one.
     *
     * One signal, not two, and the missing one is the point. This also matched
     * the words "reconnect" and "lost" in the text of any `.monaco-dialog-box`,
     * which reads as a check on VS Code's reconnection dialog and is really a
     * check on the display language: install a language pack and the substrings
     * are translated, the match never fires, and the probe reports a healthy
     * connection for a broken one, silently, and only for the users who are not
     * reading English.
     *
     * It was not narrowed in favour of something better, because there is nothing
     * better to reach for. The shipped workbench carries no class that marks a
     * dialog as the reconnection one: `.monaco-dialog-box` and
     * `.monaco-dialog-modal-block` are the only dialog classes in
     * `out/vs/code/browser/workbench/workbench.css`, and both belong to every modal it can
     * raise. Matching any dialog instead would reload the page over an unanswered
     * "save your changes?", which trades a missed detection for lost work.
     * Wrapping the page's `WebSocket` would be a real signal, but injection here
     * happens at `onPageFinished`, long after the workbench has opened its own.
     *
     * What that leaves uncovered is a reconnection dialog raised between one and
     * five minutes of background. Above five minutes [handleResumeFromBackground]
     * reloads unconditionally and the question does not arise; below it, the
     * dialog carries its own control and the user can act on it. IndexedDB is
     * the failure with no visible affordance, which is why it is the one worth
     * probing for.
     */
    private fun checkConnectionHealth(bgMs: Long) {
        val wv = webView ?: return
        wv.evaluateJavascript(connectionHealthProbe()) { result ->
            Logger.i(tag, "Health check after ${bgMs / 1000}s: ${result?.trim('"')}")
        }
    }

    private fun setupWebView() {
        AuthTabWindow.beginPage()
        endTextEditorHold()
        webView = findViewById(R.id.webView)
        webView?.let { wv ->
            VSCodroidWebView.configure(wv)
            // The loading page's colour, before the first frame: the one the
            // workbench last painted, so a light theme starts light.
            paintWindowNow(lastPageColor(this))
            // Before the first load below: a document-start script runs only in
            // documents that begin loading after it was added, and the object a
            // page posts to is there only in those. The view recreateWebView
            // builds comes through here as well.
            addUiScaleScript(wv)
            addPlainTextPageScript(wv)
            addPageColorListener(wv, { serverPort }, ::showPageColor)
            dropCacheLeftByEarlierBuild(wv)
            applyWindowInsetsPadding(wv)
            // Here and not in initBridge, which does its work once per WebView
            // behind a guard: a WebView with no download listener drops every
            // download on the floor without a word, which is exactly the state
            // this fixes, and a replacement view created for a renderer crash
            // has to come back with one.
            wv.setDownloadListener { url, _, contentDisposition, _, _ ->
                downloads.onDownloadStart(url, contentDisposition)
            }
            // The refocus a turn makes (clearFocus, then requestFocus) anchors [KeyboardDismissal.AFTER_REFOCUS_MS] for later hide reports and extends
            // [TextEditorHold.refocused]. A WebView rebuilt for a renderer crash comes through here as well.
            wv.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    keyboardDismissal.refocused()
                    (wv as? EditorWebView)?.textEditorHold?.refocused()
                }
            }
            wv.webViewClient = bootstrapClient()
            // Show a loading placeholder while Node.js starts
            // viewport-fit=cover enables rendering into display cutout area
            // Through dataUrlSafe, which is not cosmetic here: see its comment for
            // what the unescaped page rendered as.
            //
            // The placeholder promises the editor once the server answers, so the
            // readiness that follows has to be allowed to keep that promise. This
            // runs for a WebView rebuilt over the crash page too, and when the
            // server is still coming up at that moment nothing else clears the
            // record: the user would sit on "Starting server..." with no control.
            rendererCrashLoopShown = false
            recoveryPageEpoch++
            wv.loadData(dataUrlSafe(loadingPage()), "text/html", "utf-8")
        }
    }

    /**
     * Gives the window the colour the workbench page paints itself with, and keeps
     * it for the screens of the next start.
     *
     * Where the window shows, see [paintWindow]: under a light theme the space the
     * keyboard gave back stayed #1E1E1E for up to 2.1 s while a reload or a folder
     * opened from the path box held the last frame, on an API 36 emulator, and the
     * status and navigation bars were that colour throughout. What the next start
     * shows before the workbench, see [lastPageColor].
     */
    private fun showPageColor(color: Int) {
        paintWindowWhenDrawn(color)
        keepStartColor(color)
    }

    /**
     * The last window colour asked for, so that a page's colour still waiting for
     * the frame that draws it does not land over a later one.
     */
    private var windowColorRequest = 0L

    /**
     * Gives the window [color] once the view draws the page that posted it, not
     * before.
     *
     * A page posts its colour as its load starts, and the view goes on drawing the
     * last page's frame until the new page paints, a second or more for the
     * workbench. Painted at once, the bars around that frame took the new colour
     * while it still showed the old: for 0.55 to 1.7 s at every change between a
     * dark and a light theme on an API 36 emulator, and white around the dark
     * loading page on the first launch after an update. A visual state callback
     * runs once a frame holding the page as it is when the callback is posted is
     * ready to draw, which for a page that has just started is its first.
     */
    private fun paintWindowWhenDrawn(color: Int) {
        val request = ++windowColorRequest
        val wv = webView
        if (wv != null && WebViewFeature.isFeatureSupported(WebViewFeature.VISUAL_STATE_CALLBACK)) {
            WebViewCompat.postVisualStateCallback(wv, request) { if (it == windowColorRequest) paintWindow(color) }
        } else {
            paintWindow(color)
        }
    }

    /**
     * Gives the window [color] now, for a page of this app's own, which posts
     * nothing, and drops a page's colour still waiting for its frame, which would
     * otherwise land over it.
     */
    private fun paintWindowNow(color: Int) {
        windowColorRequest++
        paintWindow(color)
    }

    /**
     * Drops the WebView's HTTP cache once per build, before this build's first page.
     *
     * The server sends everything under `/<quality>-<commit>/static`, and the files
     * of the extensions it serves, with `Cache-Control: public, max-age=31536000`
     * and no validator, and that path moves only with the VS Code commit. An update
     * extracts the tree again in place, so a file whose bytes changed at the same
     * commit keeps its URL and the WebView goes on serving the copy an earlier build
     * left. Measured on an API 33 emulator (WebView 151): after an update,
     * `workbench.css` came from the cache while the server held the new file, and
     * the mobile CSS the server build appends to it has changed more than once under
     * one commit. The 18 MB `workbench.js` is too big to be cached, which is why the
     * patches inside it did arrive.
     *
     * Only the HTTP cache goes: localStorage, IndexedDB and cookies are separate
     * stores, so the open workspace, extension state and the sealed secrets are
     * untouched. The removal runs asynchronously in Chromium; it is issued here, at
     * WebView setup, and the first workbench load waits for a server that an update
     * has just had to start from nothing.
     */
    private fun dropCacheLeftByEarlierBuild(wv: WebView) {
        val build = "${BuildConfig.VERSION_NAME}/${BuildConfig.VERSION_CODE}"
        val clearedFor = workspacePrefs.getString(KEY_WEBVIEW_CACHE_BUILD, null)
        if (!webViewCacheIsStale(clearedFor, build)) return
        wv.clearCache(true)
        workspacePrefs.edit { putString(KEY_WEBVIEW_CACHE_BUILD, build) }
        Logger.i(tag, "Dropped the WebView cache left by ${clearedFor ?: "no earlier build"}")
    }

    /**
     * The client that survives a renderer crash before the real one is installed.
     *
     * `onRenderProcessGone` has a platform contract with teeth: returning false
     * (which the default `WebViewClient` does, and which is also what a WebView
     * with *no* client does) tells the framework the app cannot carry on, and it
     * ends the application process. [VSCodroidWebViewClient] returns true and
     * rebuilds the view, but it is installed by [initBridge], which runs from
     * [loadVSCode] only once the server reports ready. That leaves the whole cold
     * start (up to the thirty seconds `waitForReady` will wait) with the
     * placeholder on screen and nothing to catch a renderer that dies under
     * exactly the memory pressure a Node.js server starting up creates.
     *
     * Deliberately not the real client. That one needs the port, and it fires
     * `onPageLoaded` on every page, including this placeholder, whose load would
     * reach [injectBridgeToken] before `securityManager` has been constructed.
     * A renderer crash is the only thing worth handling before the workbench
     * exists; the placeholder is a `data:` URL and issues no requests to
     * intercept.
     */
    // MissingOnRenderProcessGone: this client does override it, below, and the
    // recovery it drives is covered by RendererCrashLoopTest. The check does not
    // see the override on an anonymous Kotlin subclass.
    @SuppressLint("MissingOnRenderProcessGone")
    private fun bootstrapClient() = object : WebViewClient() {
        /**
         * The two URLs this client acts on, and the reason the pages carrying
         * them use a link rather than a script.
         *
         * Each is a `data:` URL with no bridge on it: `initBridge` runs once per
         * WebView for the editor, and registering a second JavaScript interface
         * here to carry one button would widen the surface that the session token
         * exists to gate. A navigation the client recognises costs nothing and
         * reaches the same place.
         */
        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest,
        ): Boolean {
            val url = request.url.toString()
            if (url == RELOAD_URL) {
                // Asking clears the record. The refusal is about a loop nobody
                // asked for, not about keeping the user out of their editor, so a
                // deliberate attempt gets the whole budget again.
                Logger.i(tag, "Loading the editor again after repeated renderer crashes")
                webViewCrashes.clear()
                // isServerReady(), not the port alone and never process liveness,
                // for the reason bindDecision gives: a renderer can die before the
                // server is answering, and navigating at a port nothing is
                // listening on replaces this page with a connection-refused one
                // that nothing clears.
                if (serverPort > 0 && nodeService?.isServerReady() == true) {
                    loadVSCode(serverPort)
                } else {
                    // Not the loading page on its own, because "not ready" covers
                    // two states this side cannot tell apart and the page serves
                    // only one of them. A server still coming up answers this start
                    // ALREADY_SERVING and nothing changes, while onServerReady
                    // navigates as soon as it is up; a server that has given up had
                    // isServiceRunning cleared by NodeService.enterTerminalState, so
                    // no readiness callback is ever coming and the bare page would
                    // say "starting" for ever with nothing on it to press. That is
                    // the state showServerGaveUp exists to prevent, and it was
                    // reachable here through a crash loop over a server that had
                    // already stopped.
                    retryServerStart()
                }
                return true
            }
            if (url == COPY_DIAGNOSTICS_URL) {
                Logger.i(tag, "Copying the diagnostics from the error page")
                copyDiagnostics()
                return true
            }
            if (url != RETRY_URL) return false
            Logger.i(tag, "Retrying the server from the error page")
            retryServerStart()
            return true
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            Logger.e(tag, "Render process gone before the workbench loaded: " +
                "didCrash=${detail.didCrash()}")
            CrashReporter.recordRendererDeath(view.context, detail)
            recreateWebView()
            return true
        }

        /**
         * The same refusal [VSCodroidWebViewClient] makes, for the window this
         * client owns.
         *
         * That window is not a corner: this client is in force from the first
         * frame until the server reports ready, which `waitForReady` will wait
         * thirty seconds for, and again after every renderer crash until
         * [initBridge] installs the real one. An Escape on the placeholder page
         * is unconsumed by definition, so without this it is handed back to the
         * platform and can return as a back press, and the app minimises while it
         * is still starting. See the other override for the mechanism.
         */
        override fun onUnhandledKeyEvent(view: WebView, event: KeyEvent) {
            if (event.keyCode == KeyEvent.KEYCODE_ESCAPE) return
            super.onUnhandledKeyEvent(view, event)
        }
    }

    /**
     * Keeps `env(safe-area-inset-*)` at zero inside the page. Load-bearing,
     * and not for the reason its shape suggests.
     *
     * The padding does NOT position anything: the WebView render engine
     * ignores the view's own padding: with it applied, the page still
     * reports `window.innerHeight` equal to the full view height, and the
     * container padding from [ExtraKeyRow.setupWithRootView] is what places
     * the editor below the bars. What the padding DOES feed is Chromium's
     * safe-area computation, roughly `safeArea = cutout − viewPadding`.
     * Remove it and the page suddenly reads `env(safe-area-inset-top)` =
     * the full cutout height (even though the container already moved the
     * view out of the cutout) and the workbench squeezes its title bar to
     * zero height: measured on API 36, `.titlebar-container` collapsed from
     * 35px to 0 and the command center, navigation arrows and layout
     * controls vanished. Both states were measured over CDP before this
     * comment was written; do not delete this listener as a "no-op" again.
     *
     * It lives here rather than in `onCreate` because [recreateWebView]
     * replaces the view, and a listener registered once against the original
     * dies with it. The explicit request is what makes it take effect on the
     * replacement: insets are dispatched on attach, and after a recreation
     * this runs on a view that is already attached, so without asking for a
     * fresh pass the padding would wait for the next rotation or keyboard.
     */
    private fun applyWindowInsetsPadding(view: View) {
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            // Cutout included for the same reason as the container listener:
            // in landscape the hole sits on a side edge where systemBars()
            // is zero, and only a matching pad keeps env() at zero there.
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(view)
    }

    private fun setupExtraKeyRow() {
        extraKeyRow = findViewById(R.id.extraKeyRow)
        extraKeyRow?.setupWithRootView(findViewById(R.id.webViewContainer))
        // Before the first inset dispatch, so a row the user hid never flashes up
        // with the first keyboard of the session.
        extraKeyRow?.hiddenByUser = workspacePrefs.getBoolean(KEY_EXTRA_KEY_ROW_HIDDEN, false)
        // The touch menu script refuses a menu focus only while the soft keyboard
        // is up, which is the one state where moving focus resizes the window.
        // The keyboard guard holds the keyboard down again once it has gone.
        extraKeyRow?.onImeVisibilityChanged = { visible ->
            webView?.evaluateJavascript("window.__vscodroidImeVisible = $visible;", null)
            if (visible) keyboardDismissal.shown()
            if (!visible) keyboardDismissal.hidden()
        }
        // Where the platform hands the activity a Back ahead of the soft keyboard, the keyboard a person closes with it is told apart from one the
        // system took away: see [KeyboardDismissal.backSeen].
        findViewById<BackObserverLayout>(R.id.webViewContainer).onBack = { eventTimeMs ->
            keyboardDismissal.backSeen(eventTimeMs)
            endTextEditorHold()
        }
    }

    /**
     * One debug line for each report of the keyboard hidden: what the decision that follows reads ([state], from [KeyboardDismissal.describe]) and what
     * the WebView says about itself at that moment: whether it is a text editor to the input method without the hold (the system hides a keyboard when
     * a focused view is none), the hold's state, whether the view holds the focus and whether the window does. Ages and flags only: no text, no token, no path.
     */
    private fun logKeyboardHidden(state: String) {
        val view = webView
        val editor = view as? EditorWebView
        Logger.d(
            tag,
            "keyboard hidden: $state editor=${editor?.realTextEditor()} ${editor?.textEditorHold?.describe()} focused=${view?.isFocused} windowFocus=${view?.hasWindowFocus()}",
        )
    }

    /**
     * One debug line at each change of the window's focus, written before the platform's own call in the same message ([android.view.ViewRootImpl] hands the
     * focus change to the views and then tells the input method service, with the answer of the focused view): what that answer is about to be. The window
     * flag, whether the WebView holds the view focus, the class of the view that does, the real answer and the hold's state, and the decision's ages. Class
     * names, ages and flags only: no text, no token, no path.
     */
    private fun logWindowFocus(hasFocus: Boolean) {
        if (!Logger.debugEnabled) return
        val view = webView as? EditorWebView
        Logger.d(
            tag,
            "window focus: has=$hasFocus webViewFocused=${view?.isFocused} focusOwner=${window.decorView.findFocus()?.javaClass?.simpleName} " +
                "editor=${view?.realTextEditor()} ${view?.textEditorHold?.describe()} ${keyboardDismissal.describe()}",
        )
    }

    /**
     * Called from [onConfigurationChanged]. Holds the WebView's answer "text editor" through the turn that [keyboardUp] says found the keyboard up, when the page is an
     * editor to the platform right now: the window gains the focus again after a turn, and a window that says it has no editor then loses its keyboard. See
     * [com.vscodroid.webview.TextEditorHold]. A turn that found the keyboard down, or a page that is not an editor, arms nothing and ends a hold
     * that an earlier turn left.
     */
    private fun armTextEditorHold(keyboardUp: Boolean) {
        val view = webView as? EditorWebView ?: return
        if (keyboardUp && view.realTextEditor()) view.textEditorHold.arm() else view.textEditorHold.end()
    }

    private fun endTextEditorHold() {
        (webView as? EditorWebView)?.textEditorHold?.end()
    }

    /**
     * Back sends the app to the background.
     *
     * It used to ask the page first, and the answer could only ever be no. The
     * round trip was Kotlin to JavaScript to `AndroidBridge.onBackPressed` and
     * back, and that bridge method returns whatever the `onBackPressed`
     * constructor lambda answers, which [initBridge] passes as `{ false }`.
     * Nothing else defines it: no patch, no bundled extension and no injected
     * script installs a page-side handler, so the workbench was never consulted
     * about anything and every back press paid an `evaluateJavascript` to be told
     * what was already known.
     *
     * Removing it also fixes what the round trip cost when there was no page. The
     * call was made through `webView?`, and the minimise sat inside its result
     * callback, so with the WebView gone (between [recreateWebView] tearing one
     * down and building the next) nothing ran at all and back did nothing.
     *
     * Dismissing editor UI with back would be a real improvement and is not this:
     * it needs something the workbench answers, and the extra key row's Esc key is
     * what closes a palette today.
     */
    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                moveTaskToBack(true)
            }
        })
    }

    /**
     * Escape is never reported unhandled by the view tree, so nothing can turn it
     * into a back press by that route.
     *
     * A key nothing consumed is offered to the PRODUCING KEYBOARD's character map
     * for a fallback action, not to `Generic.kcm`, and some of those maps still
     * carry `ESCAPE base: fallback BACK`. AOSP ships one:
     * `Vendor_18d1_Product_5018.kcm`, headed "Key character map for Google Pixel C
     * Keyboard", which is present on a stock API 33 image where `Generic.kcm` and
     * `Virtual.kcm` both read `base: none`. The BACK it produces is
     * indistinguishable from a real one by the time it reaches
     * [setupBackNavigation]'s callback, which minimises the app.
     *
     * ⚠️ **This override is only half of it, and not the half that fixes a
     * terminal.** There are two routes to that fallback and they do not meet.
     * This one is the route where nothing in the view tree consumed the key, which
     * is what happens when the WebView does not hold focus; reporting the key
     * handled here closes it. The route that matters with the workbench focused
     * runs through `WebViewClient.onUnhandledKeyEvent` instead, and the event it
     * re-injects is queued with `FLAG_UNHANDLED`, which `deliverInputEvent` sends
     * to `mSyntheticInputStage` INSTEAD of the stage that would deliver it here.
     * So this method never sees it, and cannot. That route is closed in
     * [VSCodroidWebViewClient.onUnhandledKeyEvent], which carries the full
     * mechanism; the bootstrap client in [bootstrapClient] closes the same route
     * for the window before the real client is installed.
     *
     * Testing `FLAG_FALLBACK` on a BACK here would have covered the second route
     * on Android 13 and 14 and missed it on 15 and 16: with predictive back
     * enabled, and it is by default at this app's `targetSdk`,
     * `NativePreImeInputStage` claims every BACK before the view tree, with no
     * exemption for a synthesised one.
     *
     * `super` runs first and its answer is kept, so the WebView receives the key
     * exactly as dispatched and the workbench keeps every binding it has for
     * Escape. Only the verdict reported back to the input pipeline widens. Every
     * other key, `KEYCODE_BACK` included, passes through untouched, so the back
     * gesture, the hardware back key and predictive back are unaffected.
     *
     * ⚠️ Not reproducible with `adb shell input keyevent 111`, and neither is the
     * fix: an injected event carries `Virtual.kcm`, whose ESCAPE has no fallback,
     * and the emulator's own keyboard resolves to `qwerty2.kcm`, which declares no
     * ESCAPE at all. It takes a real HID keyboard whose map has the line.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val handled = super.dispatchKeyEvent(event)
        noteHardwareTyping(event)
        return handled || event.keyCode == KeyEvent.KEYCODE_ESCAPE
    }

    /**
     * Takes the extra key row off the screen while the user types on a hardware
     * keyboard, from the key as it went by and after it was delivered, so nothing
     * about the key itself changes.
     *
     * Observation only, and after the delegate on purpose: `HardwareKeyboardTest`
     * pins that the page receives every key exactly as dispatched. Which keys
     * count is [HardwareTyping]'s, and the row comes back when a finger has been
     * on the screen, [dispatchTouchEvent], or when the keyboard is no longer there,
     * [onConfigurationChanged].
     */
    private fun noteHardwareTyping(event: KeyEvent) {
        if (event.action != KeyEvent.ACTION_DOWN) return
        val device = event.device ?: return
        if (HardwareTyping.isTypingOnHardwareKeyboard(
                isVirtual = device.isVirtual,
                source = event.source,
                keyboardType = device.keyboardType,
                isPrintingKey = event.isPrintingKey,
            )
        ) {
            extraKeyRow?.hiddenForHardwareKeyboard = true
        }
    }

    /**
     * Gives the extra key row back when a finger leaves the screen after a run of
     * typing on a hardware keyboard. A finger on the screen is the way back to the
     * soft keyboard; a mouse click or a touchpad on a keyboard cover is not, and
     * [HardwareTyping.isScreenTouch] leaves those out.
     *
     * At the lift, and after the event was delivered. The row takes its height out
     * of the page, so bringing it back at the first contact moved everything that
     * sits at the bottom of the page, the status bar and the terminal among it, up
     * from under the finger before the tap was over, and a tap is placed on the page
     * as it is when the finger lifts. Delivered first, the tap lands on the page the
     * finger was put on and only then does the page give way.
     *
     * The pair of touch events a back gesture makes is noted here too: [noteEdgeBackGesture].
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val handled = super.dispatchTouchEvent(ev)
        noteEdgeBackGesture(ev)
        if (ev.actionMasked == MotionEvent.ACTION_UP && HardwareTyping.isScreenTouch(ev.source)) {
            extraKeyRow?.hiddenForHardwareKeyboard = false
        }
        return handled
    }

    private val edgeBackGesture = EdgeBackGesture()

    /**
     * Observation only, after the delegate, like [noteHardwareTyping]: a finger that went down within a side's `systemGestures()` back-gesture region and was
     * cancelled by the system, or flicked inward past touch slop and lifted within [KeyboardDismissal.EDGE_PAIR_MS], is the back gesture, and where the activity
     * is not handed the Back key ahead of the keyboard (Android 13 and 14 with the keyboard up, and Android 16 for an app that targets 36) it is the one trace of
     * a Back the activity gets ([KeyboardDismissal.edgeGesture]). Three-button navigation reports zero width on both sides so nothing is armed, missing insets
     * before attach arm nothing, and touches inside the top status/cutout inset or bottom gesture strip are not armed. Read here and not in the root layout because
     * a layout is handed the cancel only while one of its views holds the touch, and a touch that goes down on the padding the insets leave beside a cutout has no
     * such view; the activity is handed every event of the window.
     */
    private fun noteEdgeBackGesture(ev: MotionEvent) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val toolType = ev.getToolType(0)
                val pointerSupported = HardwareTyping.isScreenTouch(ev.source) &&
                    (toolType == MotionEvent.TOOL_TYPE_FINGER || toolType == MotionEvent.TOOL_TYPE_STYLUS)
                val rootInsets = window.decorView.rootWindowInsets?.let {
                    WindowInsetsCompat.toWindowInsetsCompat(it, window.decorView)
                }
                val topInset = rootInsets
                    ?.getInsets(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout())
                    ?.top ?: 0
                val gestureInsets = rootInsets?.getInsets(WindowInsetsCompat.Type.systemGestures())
                val bottomInset = gestureInsets?.bottom ?: 0
                val windowHeight = window.decorView.height.toFloat()
                if (!pointerSupported || ev.y < topInset.toFloat() ||
                    (bottomInset > 0 && windowHeight > 0f && ev.y >= windowHeight - bottomInset.toFloat())
                ) {
                    edgeBackGesture.lifted()
                    keyboardDismissal.edgeLifted()
                    return
                }
                val touchSlop = ViewConfiguration.get(this).scaledTouchSlop.toFloat()
                if (edgeBackGesture.down(
                        ev.x,
                        window.decorView.width.toFloat(),
                        gestureInsets?.left,
                        gestureInsets?.right,
                        ev.y,
                        touchSlop,
                    )
                ) {
                    keyboardDismissal.edgeDown()
                } else {
                    keyboardDismissal.edgeLifted()
                }
            }
            MotionEvent.ACTION_MOVE -> edgeBackGesture.move(ev.x, ev.y)
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                edgeBackGesture.lifted()
                keyboardDismissal.edgeLifted()
            }
            MotionEvent.ACTION_UP -> {
                val spanMs = ev.eventTime - ev.downTime
                val trace = edgeBackGesture.finishUp(ev.x, ev.y, spanMs, KeyboardDismissal.EDGE_PAIR_MS)
                finishEdgeTouch("up", spanMs, trace, trace?.matched == true, ev.eventTime)
            }
            MotionEvent.ACTION_CANCEL -> {
                val spanMs = ev.eventTime - ev.downTime
                val trace = edgeBackGesture.finishCancel(ev.x, ev.y)
                finishEdgeTouch("cancel", spanMs, trace, trace != null, ev.eventTime)
            }
        }
    }

    private fun finishEdgeTouch(
        ended: String,
        spanMs: Long,
        trace: EdgeBackGesture.EndTrace?,
        recordPair: Boolean,
        eventTimeMs: Long,
    ) {
        if (trace == null) {
            keyboardDismissal.edgeLifted()
            return
        }
        if (recordPair) keyboardDismissal.edgeGesture(spanMs, eventTimeMs) else keyboardDismissal.edgeLifted()
        val counted = recordPair && spanMs in 0L until KeyboardDismissal.EDGE_PAIR_MS
        Logger.d(
            tag,
            "edge back gesture: ended=$ended counted=$counted span=${spanMs}ms " +
                "dx=${trace.horizontalTravelPx.roundToInt()}px maxInward=${trace.maxInwardPx.roundToInt()}px " +
                keyboardDismissal.describe(),
        )
    }

    private fun requestNotificationPermission() {
        // The permission exists from API 33. Below that notifications are on
        // unless the user turned them off in Settings, so there is no dialog to
        // ask and none to wait for. The service posts its foreground card with
        // `startForeground` when it starts, and nothing needs re-posting after an
        // answer: the launcher above never fires there.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /**
     * Asks the service to post its foreground notification again, now that there
     * is permission to show one.
     *
     * Deferred when the binding is not up yet rather than dropped. The permission
     * answer and `onServiceConnected` are both main-thread callbacks with no
     * ordering between them (the user can answer the dialog faster than the
     * service binds), and losing the request to that race would leave exactly the
     * missing notification this exists to fix, on the runs where the user was
     * quick.
     *
     * Deliberately not solved by delaying [startAndBindService] until the answer
     * arrives. A dialog the user is free to ignore forever would then be holding
     * up the server, trading a missing notification for an editor that never
     * loads.
     */
    private fun refreshServiceNotification() {
        val service = nodeService
        if (service == null) {
            notificationRefreshPending = true
            return
        }
        notificationRefreshPending = false
        service.refreshNotification()
    }

    /**
     * Starts the service and binds to it.
     *
     * The start is guarded and the bind is not, and that asymmetry is the point.
     * On Android 12+ `startForegroundService` throws
     * `ForegroundServiceStartNotAllowedException` at the CALL SITE when the app
     * may not start one from the background, and an uncaught throw here is a crash
     * during `onCreate`, which is a crash loop with no screen in front of it.
     * `NodeService.promoteToForeground` already catches the service side of the
     * same refusal and stands down with a log; this side had no such guard.
     *
     * `bindService` with `BIND_AUTO_CREATE` is outside the try, and it is the
     * fallback for the BINDING rather than for the server. It creates the service
     * under no such restriction, so the connection and every callback installed on
     * it survive the refusal; what it does not do is start anything.
     * `NodeService.launchServer` is reached only from `onStartCommand`, and a bind
     * never delivers one, so a swallowed refusal leaves a service that has
     * constructed its `ProcessManager` and will never spawn Node, while
     * [setupServiceCallbacks] reads a port of 0 and an unready server and settles
     * on `BindDecision.Wait`. Nothing else would ever start it, and what the user
     * is left looking at is the loading page saying the server is starting, for
     * ever, with no control on it. That is exactly the state [showServerGaveUp]
     * exists to replace, so the refusal is put on screen here the way
     * [retryServerStart] puts its own. The WebView is already built when this
     * runs: `onCreate` calls `setupWebView()` first.
     *
     * A refusal over a server that was already serving corrects itself: the bind
     * lands moments later, [setupServiceCallbacks] reads a bound port and a ready
     * server, and `BindDecision.Load` navigates over this page. Showing it in that
     * window is the price of not staying silent in the window that matters.
     */
    private fun startAndBindService() {
        val serviceIntent = Intent(this, NodeService::class.java)
        try {
            startForegroundService(serviceIntent)
        } catch (e: Exception) {
            Logger.e(tag, "Could not start the server in the foreground: ${e.message}")
            showServerGaveUp()
        }
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
        serviceBindingInitiated = true
    }

    private fun setupServiceCallbacks() {
        nodeService?.onServerReady = { port, sameServer ->
            // Recorded whatever the page says, so the reload the crash page
            // offers has a port to load when the user does ask.
            serverPort = port
            runOnUiThread {
                // "Taking longer than usual to start" is over. From Android 12 it is a dialog, which
                // stayed over the working editor until it was tapped. By its topic: a screen made again
                // for a change of language carries the text in the language it had.
                AppNotices.retractTopic(this, NodeService.TOPIC_SLOW_START)
                // A server that came back on its own is not the user asking, and
                // the crash page has told them the editor stays down until they
                // do. Loading here anyway ran the loop that page exists to stop
                // one more turn; see rendererCrashLoopShown.
                if (rendererCrashLoopShown) {
                    Logger.i(tag, "Server ready again; the renderer-crash page stays up until asked")
                } else if (sameServer && isWorkbenchUrl(webView?.url, port)) {
                    // Only the bootstrap died, and the page is still connected to
                    // the editor server that was adopted back. A reload would
                    // restart the extension host to repair at most a webview opened
                    // while readiness was withdrawn, and such a webview's request
                    // waits for readiness instead: see
                    // VSCodroidWebViewClient.interceptCdnRequest.
                    Logger.i(tag, "Adopted the server the page is connected to; not reloading")
                } else {
                    loadVSCode(port)
                }
            }
        }
        nodeService?.onServerError = { message ->
            runOnUiThread {
                Logger.e(tag, "Server error: $message")
                // The service records a notice before it raises it, and the record names the topic
                // readiness takes it back by.
                val topic = nodeService?.lastStartupNotice()?.takeIf { it.message == message }?.topic
                AppNotices.showNotice(this, message, Toast.LENGTH_LONG, topic = topic)
            }
        }
        // A toast lasts three and a half seconds; this state lasts until the app
        // is killed. What was on screen behind it read "Starting server...", and
        // went on reading that for ever, so the one thing the screen said was the
        // one thing that was no longer true.
        nodeService?.onServerGaveUp = { afterRestarts ->
            // The log is offered only when the restart budget was spent, because
            // only then did this episode try to run a server. See the callback's
            // own documentation for the other raiser.
            runOnUiThread { if (afterRestarts) showServerGaveUpWithLog() else showServerGaveUp() }
        }
        // Stopping the server from the notification leaves this activity showing
        // an editor whose backend is gone, and, because the binding it holds is
        // what keeps a started service alive, leaves the service unable to
        // finish stopping until the activity goes. Closing is both the honest
        // response and the thing that completes the stop.
        nodeService?.onServerStopped = {
            runOnUiThread {
                Logger.i(tag, "Server stopped from the notification; closing the editor")
                finishAndRemoveTask()
            }
        }

        serviceCallbacks = nodeService?.let { arrayOf(it.onServerReady, it.onServerError, it.onServerGaveUp, it.onServerStopped) }

        // The permission answer can beat the binding; if it did, this is the
        // first moment the request has anywhere to go.
        if (notificationRefreshPending) refreshServiceNotification()

        // A server that became ready before this activity bound will never fire
        // onServerReady at it (launchServer()'s coroutine has already finished),
        // so the state has to be asked for rather than waited on.
        //
        // isServerReady(), not the liveness check isServerRunning() was, which has
        // since been removed. That was Process.isAlive, which is true from the
        // moment the process is spawned and stays true for
        // the seconds the editor server takes to bind its port, and for the whole
        // of a restart after a crash. Navigating on it points the WebView at a
        // port with nothing listening, and onReceivedError only logs a refused
        // connection, so what the user gets is a connection-refused page that
        // nothing clears.
        //
        // The real probe is HTTP and cannot run here (NetworkOnMainThreadException),
        // which is what made the wrong question attractive. isServerReady()
        // reports what that probe already found, at no cost. See
        // ProcessManager.isReady.
        val service = nodeService ?: return

        // Checked first, because anything the service has to say about the start
        // was said to a callback that did not exist yet: this activity was not
        // bound when it happened, and nothing repeats it.
        //
        // Shown rather than judged. The notice may be terminal (a start that
        // could not spawn, a restart budget spent) or it may be a slow server
        // still being waited for, and the difference is in the message rather
        // than in anything readable here. Either way the right thing to do is
        // the same: say it and do not load, because the server is not serving.
        // A slow one that comes up afterwards arrives through onServerReady,
        // which is assigned above.
        // Through bindDecision so the branch is a value a test can assert on.
        // Reading the source for the *name* isServerReady cannot tell a call whose
        // answer is obeyed from one whose answer is discarded, and the second is
        // the mutation that survived the suite.
        val notice = service.lastStartupNotice()
        when (
            val decision = bindDecision(
                notice = notice,
                port = service.getPort(),
                ready = service.isServerReady(),
            )
        ) {
            is BindDecision.ShowNotice -> {
                Logger.w(tag, "Server start notice predating this binding: ${decision.message}")
                AppNotices.showNotice(this, decision.message, Toast.LENGTH_LONG, topic = notice?.topic)
            }
            is BindDecision.ShowGaveUp -> {
                // The page alone. A toast is gone in three and a half seconds and
                // leaves the loading page behind, still saying the server is
                // starting; the page says what happened and carries the only
                // control that can change it. No notice beside it: the page takes
                // a notice down as it comes up, which flashed a dialog wherever
                // the text is too long for a toast.
                Logger.w(tag, "Server had already given up when this binding arrived")
                showServerGaveUpWithLog()
            }
            is BindDecision.Load -> {
                Logger.i(tag, "Server already serving on port ${decision.port}, loading immediately")
                // A server that came up while no screen was bound told nobody, and a slow start
                // notice the screen before carried over is over.
                AppNotices.retractTopic(this, NodeService.TOPIC_SLOW_START)
                serverPort = decision.port
                loadVSCode(decision.port)
            }
            BindDecision.Wait -> Unit
        }
    }

    /**
     * Replaces the loading placeholder with what actually happened.
     *
     * Deliberately not a dialog. A dialog is dismissed and leaves the same lie
     * underneath it; this is the page, so there is nothing to fall back to and
     * nothing that can be missed by looking away.
     *
     * The retry is real rather than decorative: [NodeService.enterTerminalState]
     * calls `stopServingRecoverably` before raising this, so `isServiceRunning`
     * is false and the next `startForegroundService` reaches a body that starts
     * again. The binding is untouched, so readiness arrives through the callbacks
     * already installed.
     */
    private fun showServerGaveUp() = showErrorPage(getString(R.string.error_server_gave_up), RETRY_URL)

    /**
     * The same page, carrying what the server said before it stopped answering.
     *
     * Separate from [showServerGaveUp], and the split is which failures the log
     * describes rather than a preference. This one is for the terminal state
     * [NodeService.enterTerminalState] reaches once the restart budget is gone,
     * where six attempts to run the server happened and `server.log` holds what
     * they said. [showServerGaveUp] is for a foreground promotion refused before
     * anything was launched, where that file holds some earlier session and
     * offering it as the explanation would point at the wrong thing entirely.
     *
     * Which of the two a raiser means is carried by `onServerGaveUp`'s flag, and
     * it has to be: both states reach the same callback and the same page. This
     * comment claimed instead that both of this function's callers were the
     * terminal one, which was false: the STAND_DOWN arm of
     * `NodeService.onStartCommand` raises that callback too.
     *
     * One caller is left on this variant that the flag cannot reach:
     * [BindDecision.ShowGaveUp], which an activity binding after the fact reads
     * out of `lastStartupNotice()`, whose `terminal` is not that flag. Both
     * states set it. The asymmetry is deliberate rather than overlooked: the
     * terminal state keeps the service alive on purpose, so a later bind is the
     * case it exists for, while STAND_DOWN calls `stopSelf()` in the next line
     * and its notice is readable only by a bind that lands in the gap before the
     * service goes. Widening the notice to carry the flag would buy that race and
     * nothing else.
     *
     * It also keeps that refusal synchronous, which matters at one of its call
     * sites. `startAndBindService` paints this page and then binds on the next
     * line, and a bind that finds a server already serving navigates over it;
     * loaded a dispatch later, this page could instead land ON a working editor,
     * with a retry button offering to restart a healthy server.
     */
    private fun showServerGaveUpWithLog() {
        // Cleared here as well as inside showErrorPage, and that is not belt and
        // braces: the tail below is read off this thread, so the page is loaded a
        // dispatch later than this call, and nothing arriving in between should
        // be told a workbench is up.
        workbenchLoaded = false
        val targetView = webView ?: return
        val request = ++recoveryPageEpoch
        val generation = AuthTabWindow.pageGeneration()
        lifecycleScope.launch {
            // Off the main thread, for the reason checkPreviousCrash gives about
            // the same file: ServerLog.tail reads it whole, up to MAX_BYTES, under
            // the lock a rotation holds. Small, but it is a file read on the one
            // screen a user reaches after something has already gone wrong, and
            // MainThreadWatch is installed by this point precisely to catch it.
            val detail = withContext(Dispatchers.IO) {
                ServerLog(File(Environment.getLogsDir(this@MainActivity), "server.log"))
                    .tail(SERVER_LOG_SCAN_LINES)
                    // Redacted on the way out as well as on the way in, which is
                    // the rule `CrashReporter.generateBugReport` states for the
                    // other reader of this file and gives the reason for: the
                    // report stays clean whatever else ever writes there, and
                    // `--logsPath` points the editor server at the same directory.
                    // This reader needs it more, not less. The clipboard copy is
                    // at least marked EXTRA_IS_SENSITIVE; this one is drawn on
                    // screen, where it survives a screenshot and anyone standing
                    // nearby.
                    .map { redactSecrets(redactToken(it)) }
                    .let { collapseRuns(it, SERVER_LOG_LINES) }
            }
            // A page this app loaded since owns the screen. A page the editor started by itself (its
            // Reload Window, a main-frame load error) does not while the server stays given up: the
            // gateway answers it with an empty 503, and this page is then the only thing said.
            if (request != recoveryPageEpoch || webView !== targetView ||
                (generation != AuthTabWindow.pageGeneration() && nodeService?.isServerReady() == true)) return@launch
            showErrorPage(getString(R.string.error_server_gave_up), RETRY_URL, detail.joinToString("\n"))
        }
    }

    /**
     * Says that the editor kept dying and stops reloading it.
     *
     * Its control is [RELOAD_URL] and not [RETRY_URL], because the two states
     * need opposite things done. The server here is healthy and running, so
     * `startForegroundService` would be answered with `ALREADY_SERVING` and start
     * nothing, leaving the loading page up for ever: a control that looks like the
     * way out and is not. What has to be repeated is the page load.
     */
    private fun showRendererCrashLoop() =
        showErrorPage(getString(R.string.error_renderer_crash_loop), RELOAD_URL)

    /**
     * The page shown while the server starts, in one place.
     *
     * Two callers load it: the first setup, and a retry from the error page.
     * Written twice they would drift, and the second copy is the one a user sees
     * only after something has already gone wrong.
     *
     * A function rather than a constant because the sentence in it comes from
     * the resource table, which a constant cannot reach: as a literal it was the
     * first screen of every cold start and the one no translation could touch.
     * Escaped like the error page's message, since a translation is free to
     * carry an ampersand or a bracket.
     */
    private fun loadingPage(): String =
        loadingPageHtml(lastPageColor(this), getString(R.string.server_starting))

    /**
     * The page both terminal states put up, with the one control that changes the
     * state it describes.
     *
     * [control] is a URL rather than a script because these pages carry no bridge:
     * `initBridge` runs once per WebView for the editor, and registering a second
     * JavaScript interface to carry one button would widen the surface the session
     * token exists to gate.
     *
     * Each page is only ever shown under a client that answers its own control,
     * and only one of the two controls is answered by both clients. [RETRY_URL]
     * lives in the webview package and is handled by [bootstrapClient] and by
     * `VSCodroidWebViewClient`, because the server can give up at any point in the
     * session; [RELOAD_URL] is private here and is read by [bootstrapClient]
     * alone, which is sound only because [showRendererCrashLoop] is reached from
     * [recreateWebView] after `setupWebView` has put that client back. Shown under
     * the other one the button would be dead, which is the failure RETRY_URL's own
     * documentation records from when it had a private copy per client.
     */
    private fun showErrorPage(message: String, control: String, detail: String = "") {
        // The page about to be shown is not the workbench, so nothing arriving
        // afterwards should be told it is. recreateWebView clears this for the
        // same reason when it throws the loaded page away.
        workbenchLoaded = false
        endTextEditorHold()
        // Answered from the control rather than by each caller, so the two pages
        // cannot drift apart on it: the one whose control is the reload is the
        // one that refuses an unasked reload, and the gave-up page, whose control
        // restarts the server, needs the readiness that follows to load.
        rendererCrashLoopShown = control == RELOAD_URL
        // Not under the away-from-home notice: one still waiting for the
        // previous-crash dialog is dropped, and one on screen is taken down,
        // guarded because dismissing a dialog whose window has gone throws, and
        // on that alone because a screen that is only finishing still has it.
        crashDialog?.setOnDismissListener(null)
        awayFromHomePendingShow = null
        awayFromHomeNotice?.setOnDismissListener(null)
        if (!isDestroyed) awayFromHomeNotice?.dismiss()
        // Not answered, so the editor load that follows this page says it again.
        returnUnansweredAwayFromHome()
        AppNotices.drop(this)
        val retry = getString(R.string.error_server_retry)
        // The diagnostics half of the page, and the two parts of it travel
        // together on purpose: the log tail says what happened and the button
        // takes it somewhere a maintainer can read it, and neither is worth
        // showing alone. A caller that passes no detail gets the page exactly as
        // it was, which is what keeps [showRendererCrashLoop] unchanged: its
        // failure is in the renderer, and `server.log` describes a server that is
        // still running perfectly well.
        //
        // The tail is bounded here rather than at the source. `ServerLog.tail`
        // already takes a line count, but a line is free to be long, and the one
        // this exists for carries an absolute path; without a height and a scroll
        // the block pushes the retry button off a phone screen, which is the one
        // control this page must never lose. `pre-wrap` with `break-all` for the
        // same path, which has no spaces to break at.
        //
        // `column-reverse` on the scrolling box is what puts the END of the log on
        // screen, and it is load-bearing rather than decoration. Measured on an
        // emulator: twenty lines of wrapped absolute paths are three times the
        // height this box allows, so the block opened at the top and showed the
        // previous healthy session while the six failures that put the page up sat
        // below the fold. A reverse flex direction starts a scroll container at
        // what is visually the bottom, which needs no script on a page that
        // deliberately has none.
        val diagnostics = if (detail.isBlank()) "" else """
               <div style="max-height:38vh;overflow:auto;display:flex;flex-direction:column-reverse;
               margin:1em 0 0;background:#111;border-radius:4px">
               <pre style="text-align:left;margin:0;padding:.7em;color:#999;font-size:.75em;
               line-height:1.4;white-space:pre-wrap;word-break:break-all">${escapeHtml(detail)}</pre></div>
               <p><a href="$COPY_DIAGNOSTICS_URL" style="display:inline-block;margin-top:.6em;
               padding:.5em 1.2em;background:#333;color:#ccc;text-decoration:none;
               border-radius:4px;font-size:.9em">${escapeHtml(getString(R.string.crash_copy_report))}</a></p>"""
        // This app deciding to replace a dead editor, which is exactly the
        // distinction navigationIsOurs draws. Unmarked, the workbench's unload
        // veto puts the platform's leave-page modal in front of this load, and
        // its Cancel branch aborts the navigation, taking away the RETRY_URL
        // button that is the only control able to start the server again.
        //
        // Not "a backup the dead server cannot accept", which this said until it
        // was read against the shipped bundle: the workbench registers an
        // IndexedDB provider for `vscode-userdata` in the page, so a backup is
        // written here and needs no server at all. The veto the dead server does
        // cause is the other one, a save still in flight.
        markAppNavigation()
        // The page below is dark whatever the editor's theme, and the window
        // around it, which a light one had made light, goes with it.
        paintWindowNow(getColor(R.color.colorBackground))
        recoveryPageEpoch++
        webView?.loadDataWithBaseURL(
            null,
            """<html><head><meta name="viewport" content="width=device-width, initial-scale=1.0, viewport-fit=cover"></head>
               <body style="background:#1e1e1e;color:#ccc;font-family:sans-serif;
               display:flex;align-items:center;justify-content:center;min-height:100vh;margin:0;">
               <div style="text-align:center;max-width:32em;padding:1.5em">
               <h2 style="color:#ccc;margin:0 0 .6em">VSCodroid</h2>
               <p style="color:#aaa;line-height:1.5">${escapeHtml(message)}</p>
               <p><a href="$control" style="display:inline-block;margin-top:.8em;padding:.6em 1.4em;
               background:#0e639c;color:#fff;text-decoration:none;border-radius:4px">${escapeHtml(retry)}</a></p>$diagnostics
               </div></body></html>""",
            "text/html", "utf-8", null,
        )
    }

    /**
     * Starts the service again without rebinding.
     *
     * `bindService` is not repeated: this activity never unbound, so the
     * connection and every callback on it are still live, and binding a second
     * time with the same `ServiceConnection` would not deliver `onServiceConnected`
     * again anyway. Only the start is missing, and only the start is sent.
     */
    @JvmName("retryServerStart")
    internal fun retryServerStart() {
        // The page about to replace the workbench is a `data:` loading page, and
        // it can consume nothing: an auth callback relayed into it writes
        // `vscode-web.url-callbacks` into that document's own localStorage and
        // dispatches a StorageEvent no listener exists for, inside a try/catch
        // that only reaches the console. The sign-in then hangs with nothing said,
        // where a false flag raises the notice that names the restart.
        // [showErrorPage] and [recreateWebView] clear it for the same reason; this
        // site loads over the workbench exactly as they do and was left out.
        workbenchLoaded = false
        // The loading page promises the editor once the server answers, and the
        // readiness that follows has to be allowed to keep that promise.
        rendererCrashLoopShown = false
        // The same distinction [showErrorPage] marks for, and this site was left
        // out of it. This is the app replacing an editor whose server is gone, not
        // the page navigating itself, and unmarked the workbench's unload veto
        // puts the platform's leave-page modal in front of the load. Cancel then
        // aborts it, and the caller that reaches this after a mirror sync has just
        // spent minutes copying a folder the user granted.
        //
        // The loading page is not decoration on that route, it is how the folder
        // travels: its URL is a `data:` one, so [folderFromUrl] answers null and
        // [loadVSCode] falls through to the folder remembered a moment ago instead
        // of reading the old one back out of a workbench URL that never went away.
        // Abort the load and the old URL survives and wins.
        //
        // What confirming costs, stated rather than implied, because the obvious
        // sentence here is wrong. Two things raise this callback: a save still in
        // flight, which a server that is not serving can never finish, and a
        // modified file whose backup is not written yet. That backup goes to
        // IndexedDB in this WebView rather than to the server, so it is the one
        // thing the modal could still have saved, and it is scheduled about a
        // second after the keystroke. Confirming spends that second, and what
        // refusing costs is the page carrying the folder and the only control that
        // can start the server again.
        markAppNavigation()
        // The loading page's colour, as setupWebView gives it: this can replace an
        // error page, which painted the window its own.
        paintWindowNow(lastPageColor(this))
        recoveryPageEpoch++
        webView?.loadData(dataUrlSafe(loadingPage()), "text/html", "utf-8")
        // Guarded for the reason [startAndBindService] is, and put back rather
        // than only logged. The loading page is already on screen by the time this
        // throws, so a swallowed refusal leaves the editor saying "starting" for
        // ever with no control that can change it, which is exactly the state the
        // gave-up page exists to replace.
        try {
            startForegroundService(Intent(this, NodeService::class.java))
        } catch (e: Exception) {
            Logger.e(tag, "The server could not be started again: ${e.message}")
            showServerGaveUp()
        }
    }

    private fun loadVSCode(port: Int, folderPath: String? = null, fromUrl: String? = null) {
        // Every route here is the user asking for the editor or the editor going
        // up over a page that never refused it, so the refusal is over. The one
        // caller that is neither checks the flag before calling; see onServerReady.
        rendererCrashLoopShown = false
        // A start that found the home port busy runs this process on another
        // origin, where the workbench's stored settings, sign-ins and open editors
        // are not. Said once per such start; the service hands the port out once,
        // so an activity that is going away and cannot show it does not take it.
        if (!isFinishing && !isDestroyed) {
            nodeService?.takeAwayFromHome()?.takeIf { it > 0 }?.let { home ->
                Logger.w(tag, "The editor opens on port $port because its home port $home is busy")
                showAwayFromHomeNotice(home)
            }
        }
        initBridge(port)
        applyEditorLanguage()
        applySecretStorage()
        // Before the folder chain, because a closed folder is the one state that
        // chain cannot name and would otherwise fall through to the remembered
        // folder, reopening the workspace the user had just closed. [fromUrl] is
        // for the caller whose WebView no longer holds the URL it is asking about,
        // which is the renderer-crash path: [recreateWebView] builds a new one.
        //
        // Rebuilt with the live token rather than reloaded verbatim: each spawn
        // has a token of its own, and the cookie a verbatim reload relied on
        // named the server that set it.
        if (emptyWindowUrl(fromUrl ?: webView?.url, port) != null) {
            Logger.i(tag, "Restoring the closed-folder window rather than a folder")
            navigateToFolder(port, null)
            return
        }
        // onServerReady routes a restart through here without a folder. Falling back
        // to the folder already on screen keeps the user's workspace instead of
        // dropping them back into the default projects directory.
        // The default is created rather than merely named. Splash repairs it at
        // launch, but that is not enough on its own: this activity can be
        // started directly, and the folder can be deleted while the app is
        // running. The URL-derived branch below already refuses a path that is
        // not a directory; the default deserves the same care.
        //
        // The URL still outranks the remembered folder, and that ordering is the
        // whole of how this keeps the invariant: the workbench switches folders by
        // navigating itself, so while there is a page its URL is the truth. The
        // remembered one answers only where there is no URL to ask, which is a
        // WebView still holding the data: placeholder after this activity was
        // rebuilt over a server that never stopped.
        //
        // The first two answers cost nothing and are given here, on the thread
        // that asked, so the ordinary navigation is as immediate as it ever was.
        val known = folderPath ?: folderFromUrl(webView?.url)
        if (known != null) {
            navigateToFolder(port, known)
            return
        }
        val targetView = webView ?: return
        val request = ++recoveryPageEpoch
        val generation = AuthTabWindow.pageGeneration()
        lifecycleScope.launch {
            // Off the main thread for the reason adoptWorkbenchFolder hops, and
            // this is the same lookup: deciding whether a remembered mirror is
            // still granted asks the system server for every persisted grant and
            // prunes the recent list against the answer, and the default is a
            // directory this may have to create. Both are here on the cold-start
            // path, on the thread drawing the screen, in the moment before the
            // workbench is put up.
            //
            // The connection token is not taken here. This is the branch every
            // cold start takes: `onServerReady` calls this with no folder and the
            // WebView is still holding the `data:` placeholder, so `known` is
            // null. The hop can outlast a server restart, which replaces the
            // token, and a value read before the suspension is the previous
            // server's by the navigation: it landed over the page the new
            // server's own announcement had loaded, was answered "Forbidden.",
            // and nothing announces readiness again. [navigateToFolder] reads it
            // when it navigates, which is a field read: `ProcessManager` reads
            // the token file in its readiness probe, on Dispatchers.IO, before
            // `onServerReady` can call this, so no navigation reads the disk.
            val resolved = withContext(Dispatchers.IO) {
                // The close is asked about before the remembered folder, and both
                // are behind the URL: a folder still on screen outranks either.
                // Without the first test the record still named the folder the
                // user had closed, so a relaunch put them back into it, and the
                // fix that made a close survive an editor crash only covered the
                // paths that still had the URL in hand.
                //
                // Inside the IO hop because the remembered path is stat'd and the
                // fallback creates the projects directory, which is disk work;
                // `MainThreadWatch` is what notices when it is not. The preferences
                // file itself is already loaded by now: `setupExtraKeyRow` reads it
                // in `onCreate`.
                if (workspaceWasClosed()) null else rememberedWorkspaceFolder()
                    ?: FirstRunSetup(this@MainActivity).ensureProjectsDir()
            }
            // Asked again rather than carried across the hop. The URL outranks
            // the remembered folder before the suspension and has to go on
            // outranking it after: a renderer crash recovering into the folder it
            // was showing arrives through this same method, and a remembered
            // folder resolved before it would otherwise land last and win.
            val currentUrl = targetView.url
            if (request != recoveryPageEpoch || webView !== targetView || nodeService?.isServerReady() != true ||
                (generation != AuthTabWindow.pageGeneration() && isWorkbenchUrl(currentUrl, port))) return@launch
            val target = workspaceTargetAfterLookup(currentUrl, port, folderFromUrl(currentUrl), resolved)
            navigateToFolder(port, target)
        }
    }

    /**
     * Says that this start could not use the home port, in a dialog: Android 12
     * and later cut a text toast to two lines, which kept both the port and the
     * way back off the screen (measured on an API 37 image).
     *
     * It never covers a crash screen. A previous-crash dialog still open is
     * answered first, and [showErrorPage] takes the notice down or drops it.
     * The service hands the port out once per process. Until the person answers
     * the dialog the port is kept in [awayFromHomeUnanswered], and an activity
     * taken down before that (a font size or language change makes it again, and
     * so does a reclaimed screen; closing the app from the recent apps ends it
     * while the server goes on) gives it back, so the notice is not lost with it.
     *
     * Stop Server is in the notification, and a phone with notifications turned
     * off for the app shows none, so the dialog carries the same action under the
     * notification's own label, and the notice names both.
     */
    private fun showAwayFromHomeNotice(home: Int) {
        awayFromHomeUnanswered = home
        awayFromHomeListener = EditorGateway.instance.listenersOpened
        fun show() {
            if (isFinishing || isDestroyed) return
            awayFromHomePendingShow = null
            awayFromHomeNotice = AlertDialog.Builder(this)
                .setMessage(getString(R.string.notice_editor_away_from_home, home))
                .setPositiveButton(android.R.string.ok, null)
                .setNeutralButton(R.string.action_stop) { _, _ ->
                    startService(Intent(this, NodeService::class.java).setAction(NodeService.ACTION_STOP))
                }
                .setOnDismissListener { awayFromHomeAnswered() }
                .show()
        }
        val crash = crashDialog
        if (crash != null && crash.isShowing) {
            awayFromHomePendingShow = { show() }
            crash.setOnDismissListener { show() }
        } else {
            show()
        }
    }

    override fun isBlockingNoticeShowing(): Boolean =
        crashDialog?.isShowing == true || awayFromHomeNotice?.isShowing == true

    override fun waitForBlockingNoticeDismiss(onDismissed: () -> Unit) {
        pendingNoticeAfterCrash = onDismissed
        val crash = crashDialog
        if (crash != null && crash.isShowing) {
            crash.setOnDismissListener {
                val away = awayFromHomePendingShow
                awayFromHomePendingShow = null
                away?.invoke()
                if (awayFromHomeNotice?.isShowing != true) {
                    flushPendingNoticeAfterCrash()
                }
            }
            return
        }
        val away = awayFromHomeNotice
        if (away != null && away.isShowing) {
            away.setOnDismissListener { awayFromHomeAnswered() }
            return
        }
        flushPendingNoticeAfterCrash()
    }

    override fun clearBlockingNoticeWaiter() {
        pendingNoticeAfterCrash = null
    }

    private fun awayFromHomeAnswered() {
        awayFromHomeUnanswered = 0
        flushPendingNoticeAfterCrash()
    }

    private fun returnUnansweredAwayFromHome() {
        val home = awayFromHomeUnanswered
        if (home <= 0) return
        awayFromHomeUnanswered = 0
        // Only while the listener it was said on is still the one bound. Stop Server releases that
        // listener, and a start opened again before this screen is destroyed binds its own and writes
        // its own note: at home, the port handed back here made the next load say another app holds
        // home; away again, it made the next load say it a second time.
        val gateway = EditorGateway.instance
        if (gateway.boundPort != null && gateway.listenersOpened == awayFromHomeListener) {
            gateway.noteAwayFromHome(home)
        }
    }

    private fun flushPendingNoticeAfterCrash() {
        val waiter = pendingNoticeAfterCrash ?: return
        pendingNoticeAfterCrash = null
        waiter.invoke()
    }

    /** The encoded query, excluding opaque placeholders and URL fragments. */
    private fun workspaceQueryFromUrl(url: String?): String? =
        url?.let { it.toUri() }?.takeIf { it.isHierarchical }?.encodedQuery

    /**
     * The folder encoded in a workbench URL, if it still exists on disk.
     *
     * VS Code opens a folder by navigating this same WebView without going
     * through Kotlin, so the URL is the only record of the open workspace that
     * stays truthful. A folder that has since disappeared (a cleared SAF
     * mirror, unmounted storage) is dropped so the caller falls back to the
     * default rather than pinning the WebView to a dead path.
     *
     * The hierarchical check is load-bearing, not defensive. Before the workbench
     * is loaded the WebView still holds the `data:` placeholder from
     * [setupWebView], and querying it throws
     * `UnsupportedOperationException` on an opaque URI. This runs on the main
     * thread from `onServiceConnected`, so on every cold start the app died at the
     * moment the server came up.
     */
    private fun folderFromUrl(url: String?): String? = workbenchTarget(
        query = workspaceQueryFromUrl(url),
        isDirectory = { path -> File(path).isDirectory },
        isFile = { path -> File(path).isFile },
    )

    /**
     * Initializes the WebView bridge, security manager, and clients.
     * Called from every [loadVSCode] and [navigateToFolder], but does its work once
     * per WebView: [recreateWebView] clears the guard, and a server restart does not.
     */
    private fun initBridge(port: Int) {
        if (port > 0) serverPort = port
        val wv = webView ?: return

        // Skip re-initialization if this WebView already has the bridge. The live
        // port above is still updated so existing clients and callbacks follow
        // a server restart onto a new port.
        if (bridgeInitialized) return
        bridgeInitialized = true

        securityManager = SecurityManager()
        val clipboardBridge = ClipboardBridge(this)
        val bridge = AndroidBridge(
            context = this,
            security = securityManager,
            clipboard = clipboardBridge,
            onBackPressed = { false },
            onMinimize = { runOnUiThread { moveTaskToBack(true) } },
            // Every callback here arrives on the WebView's private "JavaBridge"
            // thread, never the UI thread, addJavascriptInterface says so in
            // as many words. So each one that touches a View, a Dialog or a Toast
            // has to hop, and the hop belongs here rather than inside the
            // handlers: these six are the whole boundary, and a reader checking
            // whether the rule holds can see all of them at once.
            //
            // onShowAbout was the only one wrapped. openRecentFolder is the one
            // that showed why the rest have to be: it builds an AlertDialog and
            // calls show() on this thread, then the progress callbacks reach the
            // same dialog from the main thread, and ViewRootImpl.checkThread
            // kills the app. It stayed invisible for as long as the command was
            // unreachable: the bundled extension declared "main", so it ran in
            // the Node extension host and its BroadcastChannel never reached the
            // relay that calls in here. Moving it to "browser" made the command
            // reachable and the missing hop reachable with it.
            onOpenFolderPicker = { runOnUiThread { openFolderPicker() } },
            onOpenRecentFolder = { uri -> runOnUiThread { openRecentSafFolder(uri) } },
            onShowAbout = { runOnUiThread { showAboutDialog() } },
            // The preference is the record and is flipped here, on the bridge
            // thread, which runs one call at a time, so two quick toggles each read
            // the other's write; apply() updates the in-memory copy before it
            // returns. Only the view is hopped, and the answer is the value just
            // written rather than the view's field, which belongs to the UI thread.
            onToggleExtraKeyRow = {
                val hidden = !workspacePrefs.getBoolean(KEY_EXTRA_KEY_ROW_HIDDEN, false)
                workspacePrefs.edit { putBoolean(KEY_EXTRA_KEY_ROW_HIDDEN, hidden) }
                runOnUiThread {
                    extraKeyRow?.hiddenByUser = hidden
                    // Asked for by name, so the row is not held back by the stand-aside
                    // a run of typing on a hardware keyboard put on it.
                    if (!hidden) extraKeyRow?.hiddenForHardwareKeyboard = false
                }
                hidden
            },
            // Recorded here, on the bridge thread, and only the window is hopped, as the
            // key row above does. The page asks and never decides: what it reads back
            // is what the app says the state is.
            onSetFullScreen = { on ->
                workspacePrefs.edit { putBoolean(KEY_FULL_SCREEN, on) }
                runOnUiThread { applyFullScreen(on) }
            },
            safManager = safManager,
            // Not hopped to the UI thread, unlike the six above. These three
            // carry a download's bytes, and the coordinator guards its own
            // state precisely so they can be answered on the thread they
            // arrive on: posting them would reorder chunks against each other
            // and turn the write into an unbounded queue on the main thread.
            onDownloadNamed = { url, fileName -> downloads.onDownloadNamed(url, fileName) },
            onDownloadChunk = { requestId, base64 -> downloads.onBytes(requestId, base64) },
            onDownloadComplete = { requestId, error -> downloads.onComplete(requestId, error) },
            // Still off the UI thread, and now off the JavaBridge thread as
            // well: the bridge runs both on a worker of its own and posts the
            // answer back through onAsyncAnswer below. Each walks every copied
            // device folder before it can answer, and a bridge call does not
            // return to JavaScript until it finishes, so answering where the
            // caller waited parked the workbench page's own thread for the
            // length of the walk. It stopped rendering and stopped taking input,
            // and nothing in the app detected it. Each still returns a String
            // rather than hopping, because the value is what gets posted and
            // runOnUiThread returns Unit.
            onListMirrors = { deviceFolderCopiesAsJson() },
            onReclaimMirror = { hash, force -> removeDeviceFolderCopy(hash, force) },
            onWorkspaceRoots = { generation, paths ->
                val resolved = paths.mapNotNull { workspaceDirectoryInForce(it) }
                val published = workspaceResources.publish(generation, resolved)
                if (published) {
                    val mirrorCount = safManager.mirrorNamesForWorkspacePaths(
                        paths + resolved.map { it.toString() },
                    ).size
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        if (shouldAnnounceMultipleDeviceFolders(mirrorCount, generation, announcedMultiMirrorGenerations)) {
                            AppNotices.showNotice(
                                applicationContext,
                                R.string.saf_workspace_multiple_folders,
                                Toast.LENGTH_LONG,
                                this@MainActivity,
                                processOnceKey = AppNotices.ONCE_KEY_MULTI_DEVICE_FOLDERS,
                            )
                        }
                    }
                }
                published
            },
            // The answer's road back into the page. evaluateJavascript is a
            // WebView call and belongs to the thread the view was made on, which
            // is why this hops while the two above must not.
            onAsyncAnswer = { id, ok, payload ->
                runOnUiThread { answerBridgeCommand(id, ok, payload) }
            },
        )
        androidBridge = bridge
        wv.addJavascriptInterface(bridge, "AndroidBridge")

        // One set of rules for both entry points. The service worker is the second
        // route a resource request takes into the interceptor, so lists installed
        // on only one side leave the other answering by different rules, and
        // neither side would say a word about the difference. The client resolves
        // them again at each main-frame navigation, and the worker reads the same
        // holder per request.

        // Register ServiceWorkerClient BEFORE loading VS Code, because service
        // worker script fetches bypass WebViewClient.shouldInterceptRequest
        // entirely.
        //
        // Weakly, and that is not a micro-optimisation. The client is stored on
        // ServiceWorkerController and is replaced only when some future Activity
        // registers its own, so whatever these lambdas close over stays reachable
        // until then, at worst for the life of the process. Reading
        // `openWorkspaceFolder` and `nodeService` directly captured `this`, which
        // is how a destroyed Activity and its view tree survived a task swipe.
        //
        // A teardown is available and was not chosen. `setServiceWorkerClient`
        // takes a @Nullable client, measured against the platform stub, so
        // clearing it in onDestroy is a real option. What it costs is an
        // answer to when an outgoing Activity runs relative to an incoming one,
        // because clearing at the wrong moment wipes the live client and
        // `bridgeInitialized` then holds off a re-registration until a renderer
        // crash reaches recreateWebView(). This Activity is `singleTask` and
        // nothing calls `recreate()`, so that window may well be unreachable
        // here; it is unmeasured either way. Holding weakly needs no answer.
        //
        // Anything added here reads through `self`, and the rule is stricter than
        // it looks: a supplier that only calls a method, or reads a member
        // inherited from Context, captures the Activity just as completely as one
        // that names a field. ServiceWorkerRetentionTest refuses any shape other
        // than `self.get()?....` for that reason.
        val self = WeakReference(this)
        VSCodroidWebViewClient.setupServiceWorkerInterception(
            { self.get()?.serverPort },
            { self.get()?.resourceRules?.roots },
            { self.get()?.resourceRules?.sensitive },
            { self.get()?.openWorkspaceRoot },
            { self.get()?.workspaceResources?.roots() },
            { self.get()?.resourceRules?.staticRoots },
        ) { self.get()?.nodeService?.getConnectionToken() }

        wv.webViewClient = VSCodroidWebViewClient(
            allowedPort = { serverPort },
            resourceRules = resourceRules,
            openFolder = { openWorkspaceRoot },
            workspaceMembers = { workspaceResources.roots() },
            connectionToken = { nodeService?.getConnectionToken() },
            onCrash = { recreateWebView() },
            onNavigationStarted = { url ->
                appNavigation.clear()
                // A workbench navigation that runs while the activity is stopped
                // replaces the page whose idle time `onStop` was counting. Move the
                // timestamp forward rather than clearing it so the new page is not
                // reloaded on return yet still ages from its own load if the app
                // stays stopped for another five minutes.
                if (isWorkbenchUrl(url, serverPort) && backgroundedAt != 0L) {
                    backgroundedAt = SystemClock.elapsedRealtime()
                }
            },
            // The page covers itself the moment it is told to leave, and a
            // navigation this client hands to another app or refuses never comes
            // to replace it: the cover has to be taken down from here.
            onNavigationDeclined = { liftLeaveCurtain() },
            // A hand-off that no app accepted used to be indistinguishable from a
            // dead link: the WebView does not navigate either, so the tap did
            // nothing and said nothing. ActivityNotFoundException is separated
            // out because it is the one the user can act on by installing
            // something; everything else is quoted by type for a bug report.
            //
            // Said once per distinct failure, not once per navigation. The notice
            // was unconditional, and a toast holds the screen for about three and
            // a half seconds without replacing the one under it, so a page
            // repeating a link the device cannot open covered the editor for as
            // long as it liked. The record is consulted inside runOnUiThread, so
            // the set has one owner thread whatever the platform guarantees about
            // which thread the callback arrives on.
            onHandoffFailed = { uri, error ->
                val handoffFailure = HandoffFailure(uri.scheme ?: "external", handoffFailureType(error))
                runOnUiThread {
                    handoffFailureToAnnounce(handoffFailure, announcedHandoffFailures)?.let {
                        val message = when {
                            error is android.content.ActivityNotFoundException ->
                                getString(R.string.url_handoff_no_app, it.scheme)
                            error is SignInRefused -> getString(
                                if (error.reason == SignInRefusal.NOT_ISSUED) R.string.sign_in_not_issued
                                else R.string.sign_in_not_ready
                            )
                            else -> getString(R.string.url_handoff_failed, it.scheme, it.failureType)
                        }
                        AppNotices.showNotice(this@MainActivity, message, Toast.LENGTH_LONG)
                    }
                }
            },
            // A certificate the device does not trust used to produce an empty
            // frame and nothing else: the platform default cancels the request
            // and tells nobody, and no load error reaches a page-level state
            // here. The record of what has been said is consulted inside
            // runOnUiThread, so the set has one owner thread whatever the
            // platform guarantees about which thread the callback arrives on.
            onTlsFailure = { failure ->
                runOnUiThread {
                    // The monotonic clock, for the reason handoffFailureToAnnounce
                    // states where it takes the same reading as its default. The
                    // wall clock steps backwards on an NTP correction or when the
                    // user sets the device time, and a backward step larger than
                    // NOTICE_INTERVAL_MS makes the subtraction negative, which
                    // silences this channel until the clock catches up; a forward
                    // step releases one notice early. The two channels share one
                    // rule and had already drifted once over what that rule reads.
                    val now = SystemClock.elapsedRealtime()
                    tlsFailureToAnnounce(failure, announcedTlsFailures, now, lastTlsNoticeAt)?.let {
                        lastTlsNoticeAt = now
                        reportTlsFailure(it)
                    }
                }
            },
            onPageLoaded = { url ->
                val opened = folderFromUrl(url)
                if (opened != null) {
                    if (rememberWorkspaceFolder(opened)) adoptWorkbenchFolder(opened)
                } else if (emptyWindowUrl(url, serverPort) != null) {
                    // Closing the folder is a navigation the workbench performs on
                    // itself, so this callback is the only place Kotlin can learn
                    // it happened. The test is [emptyWindowUrl] and not
                    // [isWorkbenchUrl]: the second is host and port only, so a bare
                    // `/` load would forget a folder that is open, and the symptom
                    // (the next launch drops the user in the projects directory)
                    // looks nothing like the cause.
                    rememberWorkspaceFolder(null)
                } else if (isWorkbenchUrl(url, serverPort) &&
                    workbenchSelection(workspaceQueryFromUrl(url))?.kind in setOf("folder", "workspace")
                ) {
                    // The selected path failed validation, but this load replaced the old page.
                    openWorkspaceFolder = null
                    openWorkspaceRoot = null
                }
                // A main-frame load is a new document, and the document that owed a
                // download's bytes went with the old one. [recreateWebView] used to
                // be the only site that said so, which covered a renderer crash and
                // nothing else: `reload()` on the way back from the background, the
                // `loadUrl` in [navigateToFolder], the server-gave-up page, and the
                // folder switches the workbench performs on its own all replace the
                // page without passing through it. After any of those the
                // coordinator was left holding a transfer nothing could finish, the
                // stream open on the user's chosen document and that document never
                // discarded, and because it runs one download at a time every later
                // Download tap queued behind it until the queue filled and the rest
                // were refused. The feature was dead for the life of the Activity.
                //
                // Unconditional, and it can be: a page cannot both have just
                // finished loading and be the one already pushing bytes. The capture
                // script is injected below, from this same callback, so no download
                // can even be named before a page has finished loading. A picker
                // still on screen is untouched by this; its result is matched by
                // request id and the document it created is removed.
                downloads.onPageGone()
                // Only for a page the server served. onPageFinished fires for
                // every main-frame load under this client, and one of them is the
                // server-gave-up page, which is a local `about:blank` document
                // rather than the workbench: injecting there writes the session
                // token into a page that has no bridge to use it, and sets the
                // flag that tells an arriving OAuth callback it has a workbench
                // to land in. It then lands in a page that cannot consume it.
                if (isWorkbenchUrl(url, serverPort)) {
                    if (backgroundedAt != 0L) {
                        backgroundedAt = SystemClock.elapsedRealtime()
                    }
                    injectBridgeToken()
                }
            },
            onRetryServer = { retryServerStart() },
            onCopyDiagnostics = { copyDiagnostics() },
            // The application's assets, not this activity's, because the client
            // outlives nothing here but the request it is answering and an
            // AssetManager tied to an activity is one more thing to get wrong on
            // recreation. Both point at the same APK.
            interfaceTranslations = applicationContext.assets,
            secretStorageKey = { SecretStorageKey.forApp(applicationContext).bytes() },
        )
        wv.webChromeClient = VSCodroidWebChromeClient(
            navigationIsOurs = ::navigationIsOurs,
        ) { allowMultiple ->
            // "*/*" rather than the input's accept types: the Upload command's
            // input declares none, and a filter derived from one could only ever
            // narrow what the user is allowed to import.
            try {
                if (allowMultiple) {
                    multiFileChooserLauncher.launch(arrayOf("*/*"))
                } else {
                    fileChooserLauncher.launch(arrayOf("*/*"))
                }
                true
            } catch (e: ActivityNotFoundException) {
                Logger.w(tag, "No document picker on this device", e)
                false
            }
        }

        val keyInjector = KeyInjector(wv)
        extraKeyRow?.keyInjector = keyInjector
    }

    /**
     * Puts one TLS refusal on screen.
     *
     * A notice and not a prompt. It offers no way to continue and there is no path
     * from here to `SslErrorHandler.proceed`, which is what the WebView javadoc
     * asks for and what Google Play's insecure-SSL-error-handler policy requires.
     * The wording differs per
     * reason because the four causes need four different next steps from the
     * reader, and a single "certificate problem" would leave a developer with an
     * expired certificate reading about trust.
     *
     * The host is named and the address never is; see [TlsFailure].
     */
    private fun reportTlsFailure(failure: TlsFailure) {
        val host = failure.host ?: getString(R.string.tls_unknown_host)
        val message = when (failure.reason) {
            TlsFailureReason.UNTRUSTED -> getString(R.string.tls_blocked_untrusted, host)
            TlsFailureReason.HOSTNAME -> getString(R.string.tls_blocked_hostname, host)
            TlsFailureReason.DATE -> getString(R.string.tls_blocked_date, host)
            TlsFailureReason.INVALID -> getString(R.string.tls_blocked_invalid, host)
            TlsFailureReason.HANDSHAKE -> getString(R.string.tls_blocked_handshake, host)
        }
        AppNotices.showNotice(this, message, Toast.LENGTH_LONG)
    }

    /**
     * Records [folderPath] as the workspace, for this Activity and for the next.
     *
     * Both records, in one place, because they answer the same question at
     * different lifetimes and a site that set one without the other would drift.
     * [openWorkspaceFolder] is what the resource interceptor and the mirror
     * removal guard read while this Activity lives; the preference is what is
     * left when it does not, and the server outlives it by design.
     *
     * It never competes with the URL. This is only ever written from the URL, by
     * the page-loaded callback, or from the navigation this activity is itself
     * performing, and it is read only where there is no URL to read: see
     * [loadVSCode], where it sits after [folderFromUrl] and before the default.
     *
     * A null [folderPath] is the user having closed the folder, and it is the one
     * absence this may be overwritten with. Every other absence must not reach
     * here: the `data:` placeholder, an error page and a load on some other port
     * all say nothing about what is open, and clearing on those would forget a
     * live folder. The caller's test is [emptyWindowUrl], which is the only URL
     * that positively says no folder is open. Before this took null, the record
     * outlived the close in both directions: the preference reopened the closed
     * folder on the next launch, and [openWorkspaceRoot] kept publishing it as a
     * served resource root for the rest of the session.
     *
     * One case is deliberately not covered. A process killed between the close
     * navigation starting and the page finishing leaves the old folder in the
     * preference, so the next launch reopens it. That is true of every folder
     * here, not only of a close: the persistent record is written from the
     * finished load (`persist = true`), while [navigateToFolder] seeds only the
     * in-memory fields (`persist = false`) so a cancelled `beforeunload` prompt
     * or an aborted navigation does not overwrite the remembered workspace.
     */
    @JvmName("rememberWorkspaceFolder")
    internal fun rememberWorkspaceFolder(folderPath: String?, persist: Boolean = true): Boolean {
        if (folderPath != null && !safManager.claimWorkspacePaths(listOf(folderPath))) {
            AppNotices.showNotice(this, R.string.saf_folder_unavailable, Toast.LENGTH_LONG)
            return false
        }
        openWorkspaceFolder = folderPath
        // The one `stat` this costs, and the only place it is paid. Above the
        // early return, because the return only means the preference is already
        // written; the fields still have to describe the folder being navigated
        // to, and after a process restart they start out null.
        //
        // Null, never the empty string: `canonicalOrNull("")` resolves to the
        // process working directory, and this field is published as a resource
        // root, so an empty string here would serve the app's own working
        // directory to the page.
        openWorkspaceRoot = folderPath?.let { workspaceDirectoryInForce(it) }
        if (!persist) return true
        // Written only on a change: this runs on every main-frame load, and the
        // server redirects, so a folder switch alone reaches it twice.
        val stored = folderPath ?: NO_FOLDER
        if (workspacePrefs.getString(KEY_LAST_FOLDER, null) == stored) return true
        workspacePrefs.edit { putString(KEY_LAST_FOLDER, stored) }
        return true
    }

    /**
     * Whether the last thing the user did was close the folder.
     *
     * Asked of the same key [rememberedWorkspaceFolder] reads, so the two cannot
     * drift, and asked before it: a deliberate close outranks a folder that was
     * remembered before it.
     */
    private fun workspaceWasClosed(): Boolean =
        workspacePrefs.getString(KEY_LAST_FOLDER, null) == NO_FOLDER

    /**
     * When this app last asked the page to go somewhere, in `elapsedRealtime`.
     *
     * Read by [VSCodroidWebChromeClient.onJsBeforeUnload] to tell a navigation
     * the user chose through this app's own UI from one the page started. The
     * first is answered for them; the second keeps the platform's dialog,
     * because a `beforeunload` only reaches that callback when the workbench has
     * vetoed leaving, and a veto means there is work it cannot yet recover.
     *
     * Consumed by the first answer it satisfies, not merely aged out. The
     * callback runs synchronously inside the load [markAppNavigation] precedes
     * and is raised once for it, so one mark answers for exactly one
     * `beforeunload`; anything after that is a second navigation and has to
     * prove itself. The expiry stays as the backstop for a mark whose load
     * never produced a callback at all, so a navigation that did not happen
     * cannot leave the answer armed indefinitely.
     *
     * Leaving it armed for the whole window was silently answering a
     * page-initiated veto: the workbench only vetoes when it holds work it
     * cannot yet recover, so a mark still standing when the user tapped
     * something the workbench navigates itself discarded whatever had not
     * reached a backup.
     */
    private val appNavigation = AppNavigationMark(APP_NAVIGATION_WINDOW_MS)

    /** Marks the navigation about to be started as this app's own. */
    private fun markAppNavigation() {
        appNavigation.mark(SystemClock.elapsedRealtime())
    }

    private fun navigationIsOurs(): Boolean =
        appNavigation.consume(SystemClock.elapsedRealtime())

    /**
     * The remembered workspace, when reopening it is still the right thing.
     *
     * Two ways it stops being so, and the second is the one that costs
     * something. A folder that is gone (deleted, storage unmounted) would pin
     * the WebView to a dead path, which is the same reason [folderFromUrl] stats
     * the folder it reads. And a device-folder mirror whose grant has lapsed is
     * not synced: a user could keep editing in the belief that their work is
     * reaching the device. Launch reclamation now leaves the mirror itself alone.
     */
    private fun rememberedWorkspaceFolder(): String? = rememberedFolderToReopen(
        remembered = workspacePrefs.getString(KEY_LAST_FOLDER, null),
        mirrorsRoot = Environment.getSafMirrorsDir(this),
        // A directory or a `.code-workspace` file, because both are things the
        // workbench can be pointed back at and both are now remembered. The test
        // is still that the path is there: a workspace deleted while the app was
        // away would otherwise be reopened onto nothing.
        exists = { File(it).exists() },
        mirrorIsGranted = { safManager.folderForOpenedPath(it) != null },
    )

    /**
     * Navigates the WebView to a specific folder.
     * Safe to call multiple times (e.g., when switching SAF folders).
     *
     * Asks for the bridge rather than assuming it, and [initBridge]'s own guard is
     * what makes that free: on a folder switch it returns at the flag and nothing
     * is re-registered, so the once-per-WebView rule still holds. The call is here
     * because this is the funnel every workbench load passes through, and one
     * route reaches it with the flag cleared. See the paragraph on it below.
     *
     * [token] is the connection token, and it is a parameter so that a caller
     * already holding it passes that value. The default reads what
     * `ProcessManager` recorded when the server became ready, a field read with
     * no disk behind it, and null while the server is not ready.
     */
    @JvmName("navigateToFolder")
    internal fun navigateToFolder(
        port: Int,
        folderPath: String?,
        token: String? = nodeService?.getConnectionToken(),
    ) {
        val wv = webView ?: return
        // The bridge can be gone by the time a folder is opened, and this is the
        // only load that does not come through [loadVSCode], which asks for it.
        // [recreateWebView] clears the flag and installs the bootstrap client, and
        // its crash-loop exit shows the crash page and returns without ever
        // reaching [loadVSCode]; [onServerReady] is refused for as long as that
        // page is up, so nothing else puts the bridge back. A picker result
        // landing afterwards would then load the real workbench under a client
        // with no JS interface and no request interception: every VSCodroid
        // command silently does nothing, the touch and keyboard injections never
        // run, and CDN requests leave the device instead of being served from
        // localhost, with nothing on screen saying so.
        //
        // The guard inside [initBridge] is the whole reason this is safe to put on
        // the folder-switch path: it returns at `bridgeInitialized` before doing
        // any work, so the ordinary case pays a field read and the once-per-WebView
        // rule is untouched.
        initBridge(port)
        // Every workbench load passes through here, and a workbench on screen is
        // a page that never refused a reload: a picker result landing while the
        // crash page is up navigates here directly, and a later self-restart's
        // readiness must not be refused over the workbench it would replace.
        rendererCrashLoopShown = false
        // Seeded in memory here, and persisted to SharedPreferences only from the
        // page-loaded callback once the load actually completes. This method is
        // the one that knows the folder before the page exists, and between
        // loadUrl below and onPageFinished the workbench is already fetching
        // resources, against a supplier that would still be answering null, so
        // everything inside the workspace would 404 for the length of the load.
        if (!rememberWorkspaceFolder(folderPath, persist = false)) return
        // The token rides in the query once. The server consumes it on `/`, turns
        // it into the vscode-tkn cookie and redirects with the folder intact;
        // everything after that authenticates itself: the cookie for pages, the
        // query for resource requests, an auth message for the WebSocket.
        val url = workbenchUrl(port, folderPath, token)

        // Say so when it is missing. Navigating without the token still happens
        // -- a page the user can retry beats no page at all -- but the server
        // answers it with a bare "Forbidden.", and the line below is identical
        // whether or not the token went with it. That combination is why this
        // failure took a screenshot to find during development instead of a log
        // line: everything read like a healthy start.
        if (token.isNullOrEmpty()) {
            Logger.e(tag, "No connection token; the workbench will be refused. " +
                "Expected at ${Environment.getConnectionTokenPath(this)}")
        }

        // The address only, for the reason [urlLogLabel] gives and the reason
        // the sibling line in `onPageFinished` gives: the query carries the
        // workspace path, and `Logger.i` has no debuggable gate, so a release
        // build wrote the folder a user opened into logcat on every cold start,
        // every folder switch and every server restart, where anything holding
        // READ_LOGS can read it. That line was changed and this one, printing
        // the same string one line earlier, was not.
        //
        // `redactToken` is not enough here and never was: it replaces the token
        // parameter and nothing else. It stays out of this statement entirely so
        // no future edit can read it as "this URL is safe to print".
        Logger.i(tag, "Loading VS Code at ${urlLogLabel(url)}")
        markAppNavigation()
        recoveryPageEpoch++
        if (backgroundedAt != 0L) {
            backgroundedAt = SystemClock.elapsedRealtime()
        }
        wv.loadUrl(url)
    }


    private fun recreateWebView() {
        Logger.w(tag, "Recreating WebView after crash")
        val wv = webView ?: return
        // Counted before anything is torn down, so a crash arriving while the
        // budget is already spent is recorded rather than lost.
        val looping = crashLoopReached(webViewCrashes, SystemClock.elapsedRealtime())
        // Read the open folder off the dying WebView before it goes away
        val lastUrl = wv.url
        val container = findViewById<android.widget.LinearLayout>(R.id.webViewContainer)
        container.removeView(wv)
        // Dropped before the view it wraps is destroyed. The only thing that
        // rebuilds this is initBridge, which is reached from loadVSCode below and
        // therefore only when a port is already bound; a renderer that dies during
        // a cold start leaves serverPort at zero, and the key row then held a
        // KeyInjector around a destroyed WebView for the rest of the session.
        // ExtraKeyRow null-guards every use, so it does nothing until a live one
        // arrives, which is the right thing for a row with no page under it.
        extraKeyRow?.keyInjector = null
        (wv.webViewClient as? VSCodroidWebViewClient)?.disposeAuthPage()
        androidBridge?.shutdown()
        androidBridge = null
        wv.destroy()

        val newWebView = EditorWebView(this)
        newWebView.id = R.id.webView
        // Weight, not the default wrap_content: the replacement has to claim the
        // height the key row leaves, the same as the one declared in the layout.
        container.addView(
            newWebView,
            0,
            android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )
        webView = newWebView

        // Reset bridge so initBridge() re-registers on the new WebView
        bridgeInitialized = false
        // The page that owed the bytes for a download in flight is gone, so
        // nothing will ever arrive for it. Dropped here rather than left to be
        // displaced by the next download, which may never come: the document
        // the picker created would sit in the user's folder as an empty file
        // with the name of the one they wanted.
        downloads.onPageGone()
        // The replacement has loaded nothing yet, so a callback arriving now has
        // to be held again rather than injected into a page that is not there.
        workbenchLoaded = false

        setupWebView()
        // The view is rebuilt either way. What a spent budget refuses is only the
        // reload, which is the half that feeds the loop: the crashed WebView is
        // documented as unusable, [handleResumeFromBackground] calls `reload()` on
        // whatever this field holds, and leaving it there would turn a loop into
        // an undefined call.
        if (looping) {
            Logger.e(
                tag,
                "The editor's renderer has died ${webViewCrashes.size} times in " +
                    "${CRASH_LOOP_WINDOW_MS / 1000}s; not loading it again unasked",
            )
            showRendererCrashLoop()
            return
        }
        // Readiness, not the port, and the two differ for the whole of a restart
        // and for ever after a server that gave up. `serverPort` is written in
        // onServerReady and in BindDecision.Load and is never cleared, so it
        // stays non-zero through a crash loop and past enterTerminalState. The
        // renderer dying under the same memory pressure that killed the server
        // then navigated the new WebView at a socket nothing is listening on,
        // replacing the gave-up page -- which carries the only Retry control --
        // with a connection-refused page that nothing clears, since
        // onReceivedError only logs. This is the same pair of branches the
        // RELOAD_URL handler already uses, and for the reason argued there.
        if (serverPort > 0 && nodeService?.isServerReady() == true) {
            // Always via loadVSCode so initBridge re-registers on the new WebView;
            // loading the old URL directly would leave it without the bridge. The
            // folder is carried over from the URL the destroyed WebView was showing.
            loadVSCode(serverPort, folderFromUrl(lastUrl), fromUrl = lastUrl)
        } else {
            // A server still coming up answers this start ALREADY_SERVING and
            // onServerReady navigates when it is up; one that has given up has no
            // callback coming and needs the page that can restart it.
            retryServerStart()
        }
    }

    /**
     * Relays an extension auth callback from Chrome into the WebView's localStorage.
     *
     * VS Code's callback.html writes auth tokens to localStorage, but on Android
     * the callback opens in Chrome while the workbench runs in WebView: separate
     * localStorage domains. This method receives the token data via deep link
     * (vscodroid://callback?data=ENCODED_JSON) and injects it into the WebView's
     * localStorage so the workbench can pick it up.
     *
     * The payload is read once, here, and what goes into the page is the result
     * rather than the text. Both halves of that matter and neither is style.
     *
     * **Once.** `callback.html` encodes exactly one time
     * (`encodeURIComponent(JSON.stringify({ id, uri }))`) and
     * `Uri.getQueryParameter` undoes it, which is why [callbackRequestId] can
     * parse what arrives as JSON at all. The script this used to inject then ran
     * `decodeURIComponent` over that already-decoded text, so every percent
     * escape in the callback was undone a second time, and `uri.query` is
     * percent-encoded by construction: `callback.html` builds it with
     * `params.toString()`. Measured end to end, a query of
     * `code=4%2F0AX4XfWjA%2BbQ%2FcD&state=a%26b%3Dc` reached the workbench as
     * `code=4/0AX4XfWjA+bQ/cD&state=a&b=c`, so the `+` inside a base64 code
     * became a space, `state` was truncated at the injected `&`, and a
     * parameter the provider never sent appeared beside them. A callback
     * carrying a double quote went further and made `JSON.parse` throw, ending
     * the sign-in with nothing on screen. GitHub's flow is hex and a uuid with
     * nothing to escape, which is how this survived.
     *
     * **The result, not the text.** The gate in [receiveCallbackIntent] and the
     * page were parsing the same attacker-supplied bytes by two different
     * grammars, so the id the gate approved was not necessarily the id the page
     * wrote under. The id now comes from the caller, which is the value the gate
     * matched against [AuthTabWindow], and the address is taken from the same
     * parse; the page is handed two finished literals and does no parsing at
     * all.
     */
    private fun handleExtensionCallback(uri: Uri, requestId: String) {
        val dataParam = uri.getQueryParameter("data") ?: return
        val callbackUri = callbackUriJson(dataParam)
        if (callbackUri == null) {
            // Logged without the payload, as the refusals in receiveCallbackIntent
            // are: it belongs to a sign-in this app cannot read, and the log is
            // readable by anything holding READ_LOGS on a developer device.
            Logger.w(tag, "A sign-in callback carried no address this relay could read")
            return
        }
        val key = JSONObject.quote("vscode-web.url-callbacks[$requestId]")
        val value = JSONObject.quote(callbackUri)
        val targetView = webView ?: return
        val page = (targetView.webViewClient as? VSCodroidWebViewClient)?.authPage(targetView) ?: return
        val generation = page.generation
        val documentId = AuthTabWindow.documentIdFor(requestId) ?: return
        val quotedDocumentId = JSONObject.quote(documentId)
        val origin = JSONObject.quote(page.origin)
        val delivery = AuthTabWindow.claimDelivery(requestId, generation, documentId, callbackNonce(dataParam)) ?: return
        Logger.i(tag, "Extension callback relay received")
        try {
            targetView.evaluateJavascript("""
            (function() {
                try {
                    if (window.__vscodroidCallbackDocumentId !== $quotedDocumentId) return false;
                    if (window !== window.top || location.origin !== $origin || location.pathname !== '/' ||
                        (window.__vscodroid || {}).pageGeneration !== $generation) return false;
                    var key = $key;
                    var value = $value;
                    localStorage.setItem(key, value);
                    // Dispatch synthetic StorageEvent: VS Code's workbench monitors
                    // localStorage via addEventListener("storage"), but that event only
                    // fires when ANOTHER browsing context writes. Since evaluateJavascript
                    // runs in the same context, we must dispatch it manually.
                    window.dispatchEvent(new StorageEvent('storage', {
                        key: key, newValue: value, oldValue: null,
                        storageArea: localStorage, url: window.location.href
                    }));
                    return true;
                } catch(e) {
                    console.error('[VSCodroid] Callback relay error:', e);
                    return false;
                }
            })();
            """.trimIndent()) { result ->
                AuthTabWindow.finishDelivery(delivery, result == "true")
            }
        } catch (error: RuntimeException) {
            AuthTabWindow.finishDelivery(delivery, false)
            throw error
        }
    }



    companion object {

        /**
         * How long a navigation this app started stays recognisable as its own.
         *
         * The `beforeunload` callback runs inside the load it belongs to, so
         * this only has to cover the moment between asking the WebView to go and
         * the page answering. It is a backstop, not the guard: the mark is
         * consumed by the answer it satisfies, so the window only ever bounds a
         * mark whose load produced no callback.
         *
         * Tight rather than generous, and the costs are not symmetric. Expiring
         * too early costs one dialog the user did not need. Staying armed too
         * long answers a veto the workbench raised because it holds unsaved
         * work, and that costs the work.
         */
        private const val APP_NAVIGATION_WINDOW_MS = 10_000L

        /** The preferences file `PortFinder` and `SplashActivity` already use. */
        private const val WORKSPACE_PREFS = "vscodroid"

        /** The workspace to reopen when there is no page left to read one from. */
        private const val KEY_LAST_FOLDER = "last_workspace_folder"

        /** Whether the user has hidden the Extra Key Row. Absent means shown. */
        private const val KEY_EXTRA_KEY_ROW_HIDDEN = "extra_key_row_hidden"

        /** Whether the user has hidden the status and navigation bars. Absent means shown. */
        internal const val KEY_FULL_SCREEN = "full_screen"

        /** The WebView version the user was last warned is below the floor. */
        internal const val KEY_WEBVIEW_WARNED = "webview_below_minimum_warned"

        /** The build that last dropped the WebView's HTTP cache, `versionName/versionCode`. */
        private const val KEY_WEBVIEW_CACHE_BUILD = "webview_cache_build"

        /**
         * What [KEY_LAST_FOLDER] holds once the user has closed the folder.
         *
         * Three states in one record rather than a second key that can disagree
         * with the first: a path is a folder to reopen, this is an empty window
         * to restore, and the key being absent is a user who has never opened
         * anything, which still means the projects directory.
         *
         * Not the empty string, which is the obvious choice and is unsafe.
         * `File("").exists()` is **true**: the empty abstract path resolves to the
         * process working directory, so an empty sentinel reaching
         * [rememberedFolderToReopen] is answered as a real folder, opened, and
         * published as a served resource root. Measured on the JDK this builds
         * with, and the same rule is what makes any relative value dangerous.
         * This one cannot be a path at all, so every reader that does not know
         * about it, an older build reading a preferences file this one wrote
         * included, filters it out through the `exists` it already applies.
         */
        private const val NO_FOLDER = "vscodroid:closed"

        /**
         * The control on the renderer-crash page, recognised by [bootstrapClient].
         *
         * Its own scheme-and-host rather than [RETRY_URL] because the two pages
         * need opposite work done; see [showRendererCrashLoop]. Handled only by
         * the bootstrap client, which is the one on the WebView whenever that page
         * is up: [recreateWebView] clears `bridgeInitialized` and the refusal path
         * never reaches `loadVSCode`, so the real client is not installed.
         */
        private const val RELOAD_URL = "vscodroid://reload-editor"

        /**
         * How much of `server.log` the server-gave-up page shows.
         *
         * Far fewer than the 200 lines [CrashReporter.generateBugReport] copies,
         * and the two numbers answer different questions. The report is read by
         * someone who wants the whole run; the page is read on a phone by someone
         * who wants to know what broke, and the failures it exists for say it in
         * their last line or two: a missing program, a missing entry point, a
         * signal. Twenty leaves room for the throw that preceded it without
         * turning the page into a log viewer.
         */
        private const val SERVER_LOG_LINES = 20

        /**
         * How much of the file the run above reads before collapsing repeats.
         *
         * Wide enough that collapsing a run of identical failures still leaves
         * the attempt before them, which is the whole point of scanning more than
         * is shown. `CrashReporter.generateBugReport` happens to take 200 as well;
         * nothing holds the two in step and neither reads the other, so treat
         * them as answering the same question rather than as one number.
         *
         * Cheap at any width: `ServerLog` caps the file at 256 KiB and this read
         * happens once, on a screen shown at most once per episode.
         */
        private const val SERVER_LOG_SCAN_LINES = 200


    }
}
