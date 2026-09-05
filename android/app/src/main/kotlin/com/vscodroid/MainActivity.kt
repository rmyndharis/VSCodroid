package com.vscodroid

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
import android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE
import android.content.ComponentCallbacks2.TRIM_MEMORY_MODERATE
import android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
import android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.DocumentsContract
import android.text.util.Linkify
import android.view.KeyEvent
import android.view.View
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.OutputStream
import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import androidx.activity.OnBackPressedCallback
import com.vscodroid.util.drawBehindSystemBars
import com.vscodroid.util.CrashReporter
import com.vscodroid.util.StorageManager
import com.vscodroid.util.WebViewVersion
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.vscodroid.util.EditorLocale
import com.vscodroid.util.Environment
import com.vscodroid.bridge.AUTH_TAB_WINDOW_MILLIS
import com.vscodroid.bridge.AndroidBridge
import com.vscodroid.bridge.AuthTabWindow
import com.vscodroid.bridge.ClipboardBridge
import com.vscodroid.bridge.SecurityManager
import com.vscodroid.keyboard.ExtraKeyRow
import com.vscodroid.keyboard.KeyInjector
import com.vscodroid.service.NodeService
import com.vscodroid.service.StartupNotice
import com.vscodroid.setup.FirstRunSetup
import com.vscodroid.storage.SafFolderInfo
import com.vscodroid.storage.SafStorageManager
import com.vscodroid.util.Logger
import com.vscodroid.util.MainThreadWatch
import com.vscodroid.util.Notices
import com.vscodroid.webview.DownloadCoordinator
import com.vscodroid.webview.DownloadHost
import com.vscodroid.webview.DownloadOutcome
import com.vscodroid.webview.VSCodroidWebChromeClient
import com.vscodroid.webview.VSCodroidWebView
import com.vscodroid.webview.VSCodroidWebViewClient
import com.vscodroid.webview.urlLogLabel
import com.vscodroid.webview.RETRY_URL
import com.vscodroid.webview.TlsFailure
import com.vscodroid.webview.TlsFailureReason
import com.vscodroid.webview.HandoffFailure
import com.vscodroid.webview.handoffFailureToAnnounce
import com.vscodroid.webview.publishedResourceRoots
import com.vscodroid.webview.redactToken
import com.vscodroid.webview.sensitiveLocations
import com.vscodroid.webview.tlsFailureToAnnounce
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import kotlin.concurrent.thread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import androidx.core.content.edit
import androidx.core.net.toUri
import android.annotation.SuppressLint

class MainActivity : AppCompatActivity() {
    private val tag = "MainActivity"

    private var webView: WebView? = null
    private var extraKeyRow: ExtraKeyRow? = null

    /**
     * The bound service, or null while nothing is bound.
     *
     * Volatile for the reason [watchedSafFolder], [syncingFolder] and
     * [openWorkspaceFolder] are: it is written on the UI thread, in
     * `onServiceConnected` and `onServiceDisconnected`, and read off the WebView's
     * resource-interception thread, through the connection-token suppliers
     * [initBridge] hands to the client and to the service worker.
     * `shouldInterceptRequest` performs synchronous HTTP, so it cannot be on the
     * UI thread. A stale null there is a proxied request that goes out without the
     * connection token, which the server answers 403: a workbench asset that fails
     * to load with nothing anywhere saying why.
     */
    @Volatile
    private var nodeService: NodeService? = null
    private var serviceBindingInitiated = false
    private var serverPort = 0
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

    /**
     * Whether a workbench page is loaded and able to receive an auth callback.
     *
     * Reset by [recreateWebView], because the replacement is a new page: the ids
     * the workbench is waiting on live in memory and do not survive it. See
     * [receiveCallbackIntent].
     */
    private var workbenchLoaded = false

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
    private var watchedSafFolder: Pair<File, Uri>? = null

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
    private var syncingFolder: Uri? = null

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
    private val deviceFolderOpens = Mutex()

    /**
     * The directories this app publishes into the WebView, resolved once.
     *
     * Resolved on the main thread, deliberately. [publishedResourceRoots] stats
     * external storage and canonicalises four paths, so it is disk I/O, and
     * [MainThreadWatch] makes a debug build say so: it is the first violation
     * logged on every launch, and it is expected. It is also the allowlist that
     * `shouldInterceptRequest` compares every resource request against, and
     * [initBridge] installs the client immediately before [navigateToFolder]
     * starts the page loading, so resolved on another thread, the first requests
     * would arrive while the list was still empty, and an empty allowlist refuses
     * every extension resource without a sound. A markdown preview that renders
     * blank is a far worse trade than a few milliseconds spent after a server
     * start that already took seconds.
     *
     * Lazy so the cost is paid once per Activity rather than once per WebView:
     * [recreateWebView] clears `bridgeInitialized`, so a plain call would re-stat
     * all four on every renderer crash, the moment the app can least afford it.
     */
    private val resourceRoots: List<String> by lazy { publishedResourceRoots(this) }

    /** Directories the interceptor must refuse to serve, resolved once, as above. */
    private val sensitivePaths: List<String> by lazy { sensitiveLocations(this) }

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
     * Only ever overwritten with a folder, never with the absence of one.
     * [folderFromUrl] answers null for every URL that is not a workbench URL
     * (the `data:` placeholder in [setupWebView], an error page), so assigning its
     * result directly would drop a perfectly good folder on any of them. What
     * that looks like from the outside is workspace resources 404ing now and
     * then, which is about as expensive as a symptom gets.
     */
    @Volatile
    private var openWorkspaceFolder: String? = null

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

    /**
     * Where the last open workspace is remembered, resolved once.
     *
     * The same file `PortFinder` and `SplashActivity` use, under a key of its
     * own. Lazy because the first `getSharedPreferences` for a file reads it off
     * disk on the calling thread, and there is no reason to pay that on a launch
     * that never opens a folder.
     */
    private val workspacePrefs by lazy { getSharedPreferences(WORKSPACE_PREFS, MODE_PRIVATE) }

    private lateinit var securityManager: SecurityManager
    private lateinit var safManager: SafStorageManager

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
    private val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let { handleSafFolderSelected(it) }
    }

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
                pickerRequestId = requestId
                try {
                    downloadDestinationLauncher.launch(fileName)
                } catch (e: ActivityNotFoundException) {
                    Logger.w(tag, "No document creator on this device", e)
                    pickerRequestId = null
                    downloads.onDestinationUnavailable(requestId)
                }
            }
        }

        override fun openDestination(destination: Uri): OutputStream? =
            contentResolver.openOutputStream(destination)

        /**
         * Deletes a document a failed download created.
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
                Logger.w(tag, "Could not remove the unfinished download", e)
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
            runOnUiThread { Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show() }
        }
    })

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
        // First, because everything below it assumes an extracted tree. See
        // handOffToSetup for what reaching this activity without one costs.
        if (handOffToSetup()) return
        setContentView(R.layout.activity_main)

        // The application context, and that is the whole of what this outlives an
        // Activity by. The manager's engine owns the `saf-writeback` daemon, which
        // onDestroy waits two seconds for and then leaves running rather than
        // discarding writes the user expects on the device; every reference the
        // engine holds is held for as long as that drain takes. Built with `this`,
        // that was a destroyed Activity and its whole inflated view tree, on a
        // device this app is trying to leave memory on.
        safManager = SafStorageManager(applicationContext)
        // Read into locals for the same reason, and used by all four notices below:
        // a lambda that says `this@MainActivity`, `getString` or `runOnUiThread`
        // captures the Activity and hands it straight back to the engine. The
        // notices themselves have to survive, because the drain they report on is
        // exactly the part that outlives the screen.
        val appContext = applicationContext
        val toMainThread = Handler(Looper.getMainLooper())
        // The manager as a local for the same reason: reading the field inside one of
        // these lambdas is a read of `this`, and captures the Activity exactly as
        // naming it would. ActivityRetentionTest refuses the field spelling there.
        val storage = safManager
        // A save that never reached the device folder looks exactly like one that did.
        // The engine has no screen, so the notice is wired here; it is throttled inside
        // the manager, because a provider that starts refusing refuses everything.
        safManager.onWriteBackFailed { file ->
            toMainThread.post {
                Toast.makeText(
                    appContext,
                    appContext.getString(R.string.saf_write_back_failed, file.name),
                    Toast.LENGTH_LONG,
                ).show()
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
            val message = if (capped) {
                appContext.getString(R.string.saf_upload_capped, folder)
            } else {
                appContext.resources.getQuantityString(
                    R.plurals.saf_upload_incomplete, lost, lost, folder
                )
            }
            toMainThread.post {
                Toast.makeText(appContext, message, Toast.LENGTH_LONG).show()
            }
        }

        // The other direction: documents the device holds that did not reach the editor.
        // Its own wording, because "the only copy is inside VSCodroid" is the opposite of
        // true for these, and would send the user looking for a file that is safe.
        safManager.onDocumentsNotCopied { count, outOfRoom ->
            toMainThread.post {
                Toast.makeText(
                    appContext,
                    appContext.resources.getQuantityString(
                        if (outOfRoom) R.plurals.saf_documents_not_copied_no_room
                        else R.plurals.saf_documents_not_copied,
                        count,
                        count,
                    ),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }

        // Something deleted in the editor that stayed on the device, because deleting
        // it would have taken content the sync never copied in. Its own wording again:
        // the mirror copy is gone, so "the only copy is inside VSCodroid" would be
        // exactly backwards. Two sentences rather than one, because a directory was
        // kept for what is inside it and a document was kept for what it is, and the
        // engine has to say which: the entry is already unlinked by the time the event
        // arrives, so nothing here can ask the disk.
        safManager.onKeptOnDevice { kept, isDirectory ->
            val message = appContext.getString(
                if (isDirectory) R.string.saf_directory_kept else R.string.saf_document_kept,
                kept.name,
            )
            toMainThread.post {
                Toast.makeText(appContext, message, Toast.LENGTH_LONG).show()
            }
        }

        // The other way a deletion ends with the device still holding the file,
        // and the one the app did not choose: the provider said no. Worth its own
        // sentence because the file comes back on the next open, so silence here
        // reads as the editor undoing a deletion by itself.
        safManager.onDeleteRefused { refused ->
            val message = appContext.getString(R.string.saf_delete_refused, refused.name)
            toMainThread.post {
                Toast.makeText(appContext, message, Toast.LENGTH_LONG).show()
            }
        }

        setupWebView()
        setupExtraKeyRow()
        setupBackNavigation()
        requestNotificationPermission()
        startAndBindService()
        checkPreviousCrash()
        checkStorageHealth()
        checkWebViewVersion()
        // Reading it here, and not only in onNewIntent, is what makes a sign-in
        // survive the app being killed while the browser had the screen. See
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
        receiveCallbackIntent(intent)
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
     * launch, so a session reached this way runs on dangling `usr/bin` symlinks
     * and on `settings.json` paths naming the previous install's native library
     * directory.
     *
     * The filter stays on this activity rather than moving to the splash
     * screen, and that is a decision rather than an omission. A callback
     * arriving while the editor is running has to reach `onNewIntent` on the
     * live page; routing every one of them through [SplashActivity] would run
     * its launch repairs with a device folder open, and
     * [SafStorageManager.reclaimRevokedMirrors] is placed there precisely
     * because nothing else guarantees no folder is open.
     *
     * The intent travels with the hand-off, so the sign-in this filter exists
     * for is not lost: [SplashActivity] passes `data` and the extras on to the
     * activity it launches once setup finishes.
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
            intent?.extras?.let { putExtras(it) }
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
     * matters. It is not out of reach of an outside caller: the id is a small
     * integer the workbench counts from one, and a record is kept past its own
     * window on purpose, so anything on the device can name a request the user
     * really did start. What it cannot do is repeat, because the record is taken
     * back as the message goes up -- one explanation per launch, and every
     * further arrival for that id lands in the silent branch above.
     *
     * Both messages are fixed text; nothing from the callback reaches the screen.
     */
    private fun receiveCallbackIntent(intent: Intent?) {
        val uri = intent?.data ?: return
        if (!isExtensionCallback(uri.scheme, uri.host)) return
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
                Toast.makeText(
                    this,
                    getString(R.string.sign_in_editor_restarted),
                    Toast.LENGTH_LONG
                ).show()
            }
            return
        }
        // The id the callback names, matched against the launch that could have
        // produced it. Null covers both a payload this cannot read and a request
        // this app never launched, and the two deserve the same answer: there is
        // no sign-in here to report on.
        val requestId = callbackRequestId(uri.getQueryParameter("data"))
        val armedAt = requestId?.let { AuthTabWindow.armedAt(it) }
        if (requestId == null || armedAt == null) {
            // Logged without the URI. It is the payload of a sign-in this app
            // did not start, and the log is readable by anything holding
            // READ_LOGS on a developer device. No toast either: a message here
            // would be one an outside caller could raise at will.
            Logger.w(tag, "Ignoring a sign-in callback that no sign-in was waiting for")
            return
        }
        if (!authCallbackIsExpected(armedAt, SystemClock.elapsedRealtime(), AUTH_TAB_WINDOW_MILLIS)) {
            // Said out loud, unlike the branch above, because of what was just
            // established: this app launched this exact request itself. The user
            // has been reading a consent screen or waiting on a second factor for
            // longer than the window, and the alternative is an editor that
            // simply never signs in and never says why.
            //
            // Taken back as the message goes up, and that is what keeps the
            // message from becoming something an outside caller can operate. The
            // filter is exported and BROWSABLE and the id is an integer counted
            // from one, so anything on the device can name a launch the user
            // really made; with the record consumed here, each launch can produce
            // this message once, and every repeat falls into the silent branch
            // above. Only the arrival past the window consumes it -- an accepted
            // callback leaves the record alone, because the forced reload asks
            // whether a sign-in is still in flight and the workbench collects the
            // relayed value asynchronously.
            AuthTabWindow.disarm(listOf(requestId))

            // Fixed text, carrying nothing from the URI. What rides in the
            // callback is attacker-shaped by construction, and a message that
            // quoted any of it would be a way to put chosen words on the screen
            // of an app the user trusts.
            Logger.w(tag, "A sign-in callback arrived after its window had closed")
            Toast.makeText(
                this,
                getString(R.string.sign_in_timed_out),
                Toast.LENGTH_LONG
            ).show()
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
        // three notices in onCreate close over that and a main-thread Handler
        // rather than over this Activity.
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
        nodeService?.let {
            it.onServerReady = null
            it.onServerError = null
            it.onServerGaveUp = null
            it.onServerStopped = null
        }
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


    // -- SAF Folder Picker --

    /**
     * Opens the Android SAF folder picker UI.
     * Called from [AndroidBridge.openFolderPicker] via JS bridge.
     */
    fun openFolderPicker() {
        folderPickerLauncher.launch(null)
    }

    /**
     * Starts watching a mirror the workbench opened without going through Kotlin.
     *
     * VS Code switches folders by navigating its own WebView: Open Recent, the
     * Get Started list and Open Folder all build a `?folder=` URL and load it.
     * The picker is the only thing that ever started a watcher, so a folder
     * reached any of those ways was served read-write with nothing syncing it.
     * Every save, every `git checkout`, every terminal write stayed in the mirror
     * and never reached the device folder, and nothing on screen said so. If the
     * grant later lapsed, the launch reclaim deleted the mirror and the work with
     * it, having never existed anywhere the user could see.
     *
     * Reopening through the picker did not rescue those edits either: the sync
     * keeps a mirror copy that is newer and moves on without uploading it, and
     * writes only enter the upload journal from a write-back, so nothing retried.
     *
     * Three things are checked before acting, and each excludes a different way
     * of doing this twice: a path that is not under any mirror is an ordinary
     * project folder; a mirror already watched needs nothing; and a folder this
     * activity is itself mid-sync on will start its own watcher when it finishes.
     */
    private fun adoptWorkbenchFolder(folderPath: String) {
        lifecycleScope.launch {
            // Off the main thread, because this arrives from onPageFinished: the
            // lookup asks the system server for every persisted grant and prunes
            // the recent list against the answer, and a cold start whose remembered
            // workspace is a mirror reaches it with the workbench already drawn.
            val folder = withContext(Dispatchers.IO) {
                safManager.folderForOpenedPath(folderPath)
            } ?: return@launch
            // Back on the main thread, which is where both of these are written:
            // the coroutine resumes on Dispatchers.Main.immediate, and a second
            // page load cannot interleave between the resume and openSafFolder's
            // own assignment because that assignment is made before it suspends.
            if (watchedSafFolder?.first?.path == folder.mirrorPath) return@launch
            if (syncingFolder == folder.uri) return@launch
            Logger.i(tag, "Adopting a device folder the workbench opened on its own")
            openSafFolder(folder.uri, navigate = false)
        }
    }

    /**
     * Handles a SAF folder selection result:
     * 1. Persists the URI permission
     * 2. Syncs folder contents to a local mirror (with progress dialog)
     * 3. Reloads VS Code with the mirror path
     */
    private fun handleSafFolderSelected(uri: Uri) = openSafFolder(uri, navigate = true)

    /**
     * Syncs a device folder down, starts watching it, and points the editor at it.
     *
     * @param navigate whether to load the mirror in the WebView afterwards. False
     *   when the workbench has already navigated there itself, which is how Open
     *   Recent, the Get Started list and Open Folder reach a mirror: reloading
     *   would throw away the page the user just opened.
     */
    private fun openSafFolder(uri: Uri, navigate: Boolean) {
        // The mirror's name and never the tree URI. A SAF tree URI spells the
        // user's own directory (`.../tree/primary%3ADocuments%2F<folder>`),
        // `Logger.i` is not gated on a debuggable build, and logcat is readable
        // by anything holding READ_LOGS as well as by whoever a device bug report
        // is sent to. `persistPermission` in the hop below already names this same
        // folder by the six-byte digest of that URI, which is stable and is not
        // reversible; saying it in full here put the value back in logcat by
        // another route. The digest is a hash of the URI string and nothing else,
        // so it is answerable before any permission has been taken.
        Logger.i(tag, "Opening the device folder ${safManager.getMirrorDir(uri).name}")

        // Up before the provider is asked anything, and named from the tree URI
        // until it answers. Everything that used to run ahead of this dialog was a
        // cross-process round trip: persistPermission resolves the display name and
        // reads the persisted grants to prune the recent list, and getDisplayName
        // queries the tree a second time. All three callers are on the thread that
        // draws the screen (the picker result, the bridge through runOnUiThread, and
        // onPageFinished through adoptWorkbenchFolder), so against a network- or
        // MTP-backed provider that was seconds of frozen editor with nothing on it
        // to say why. The real name replaces the placeholder below.
        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.saf_sync_title))
            .setMessage(getString(R.string.saf_sync_message, treeUriLabel(uri.lastPathSegment)))
            .setCancelable(false)
            .create()

        // Marked before anything suspends, and that timing is the marker's whole
        // job: the two page-finished callbacks a folder switch produces arrive
        // back to back, and the second has to find this already set.
        syncingFolder = uri

        lifecycleScope.launch {
            // One open at a time, in the order they were asked for, and released
            // in the finally below however this ends. Everything that touches
            // the engine sits between the two, the failure handlers included, so
            // a watcher put back after a failed switch cannot interleave with
            // the next open stopping it. A lock() cancelled while it waits is
            // never held, so there is nothing to release on that path either.
            deviceFolderOpens.lock()
            // Read once the lock is held rather than when the open was asked for:
            // the open ahead of this one is what decides which watcher a failure
            // here has to put back.
            val previouslyWatched = watchedSafFolder
            try {
                // An adoption answers a page load, and the page may have moved on
                // while this waited its turn: the open ahead of it navigated (a
                // picked folder always does), or the workbench opened a third.
                // Syncing the folder it was queued for would then take the only
                // watcher off the folder on screen, which is the state adoption
                // exists to end. Decided inside the try so the finally releases
                // both the marker and the lock.
                if (adoptionIsStale(
                        navigate,
                        watchedSafFolder?.first?.name,
                        SafStorageManager.mirrorNameFor(
                            openWorkspaceFolder, Environment.getSafMirrorsDir(this@MainActivity)
                        ),
                        safManager.getMirrorDir(uri).name,
                    )
                ) {
                    Logger.i(tag, "Not syncing a device folder the page has moved on from")
                    return@launch
                }

                // Shown once it is this open's turn rather than when it was asked
                // for. Put up at once, a second dialog stacked over the one still
                // reporting the first sync's progress, and the one on top named a
                // folder nothing was copying yet.
                dialog.show()

                // NonCancellable, and for the permission alone. The grant used to be
                // taken on the way in, so it was taken whatever happened next; a
                // plain hop here would drop it if this activity were destroyed in
                // the moment after the picker returned, and the folder would be
                // missing from the recent list with nothing to explain it. The sync
                // below must still be skipped in that case, which the ordinary hop
                // after this one does by throwing.
                val displayName = withContext(NonCancellable + Dispatchers.IO) {
                    safManager.persistPermission(uri)
                    safManager.getDisplayName(uri)
                }
                dialog.setMessage(getString(R.string.saf_sync_message, displayName))

                // Before the sync, and this call was moved rather than added:
                // there used to be one after it, which `startWatching` has since
                // made redundant by stopping the previous watcher itself. Reading
                // that redundancy as the whole problem is the trap, because the
                // stop that was missing is this one.
                //
                // Reopening a folder that is already open (which
                // openRecentSafFolder routes through here) ran the initial sync
                // under that folder's live watcher. `copyDocumentToLocal` lands
                // each file by writing a `.partial` beside it and calling
                // `renameTo`, the observer reads `MOVED_TO` as CREATE, and every
                // file just pulled down was immediately queued to be pushed back.
                //
                // What happens to it if the sync fails depends on which folder
                // failed, and restoreWatcherAfterFailure is where that is
                // decided.
                //
                // Off this thread, and the wrapper below is what made that easy
                // to miss: only the sync was ever inside it, while this call and
                // the pair after it read as confined by sitting beside one that
                // was. Both are expensive on the main thread and the progress
                // dialog is already up. Stopping joins the previous folder's
                // write-back drain for up to two seconds and the interrupt
                // cannot shorten it, because the provider stream it is inside is
                // not interruptible; starting walks the whole mirror and issues
                // one inotify registration per directory, up to the engine's cap
                // of 2048.
                //
                // Plain Dispatchers.IO rather than NonCancellable: a scope
                // cancelled at this point must skip the sync as well, and the
                // hop throwing is what does that.
                withContext(Dispatchers.IO) { safManager.stopFileWatcher() }
                watchedSafFolder = null

                // At most one redraw per SYNC_PROGRESS_INTERVAL_MS, plus the last
                // file whatever the clock says. The engine reports once per file,
                // and a folder with tens of thousands of them posted that many
                // getQuantityString lookups and setMessage calls onto the main
                // looper, each one a measure and layout pass on the thread the
                // user is waiting on, for text changing far faster than anyone can
                // read it. The throttle is here rather than in the engine because
                // the engine has no clock policy and every other consumer of that
                // callback would inherit this one.
                //
                // A plain var needs no synchronisation: `initialSync`'s phase-2
                // loop is sequential on this one IO thread, so the callback has a
                // single caller.
                var lastProgressAt = 0L
                val mirrorDir = withContext(Dispatchers.IO) {
                    safManager.syncToLocal(uri) { done, total ->
                        val now = SystemClock.elapsedRealtime()
                        if (!syncProgressIsDue(done, total, now - lastProgressAt)) {
                            return@syncToLocal
                        }
                        lastProgressAt = now
                        runOnUiThread {
                            dialog.setMessage(
                                resources.getQuantityString(
                                    R.plurals.saf_sync_progress, total, displayName, done, total
                                )
                            )
                        }
                    }
                }

                withContext(Dispatchers.IO) { safManager.startFileWatcher(mirrorDir, uri) }
                // On the main thread, deliberately, unlike the call above it.
                // [watchedSafFolder] has one writer thread by contract and this
                // is it; the work worth moving is the tree walk, not the two
                // assignments that record it.
                beginWatching(mirrorDir, uri)


                dialog.dismiss()

                // Reload VS Code with the mirror, or with the workspace it holds.
                // The listing is off the main thread for the reason every other
                // disk read here is: `MainThreadWatch` installs a policy that
                // logs one, and `folderOpenTarget` stats each candidate.
                if (navigate && serverPort > 0) {
                    val target = withContext(Dispatchers.IO) {
                        folderOpenTarget(
                            mirrorDir.absolutePath,
                            mirrorDir.list()?.asList().orEmpty(),
                        )
                    }
                    if (target != mirrorDir.absolutePath) {
                        Logger.i(tag, "The granted folder holds a workspace; opening that")
                    }
                    // Readiness rather than the port, for the reason recreateWebView
                    // gives: a device-folder sync can run for minutes, and a server
                    // that died during it leaves `serverPort` set with nothing behind
                    // it, so navigating puts a connection-refused page in front of the
                    // folder the user just picked.
                    //
                    // But the folder is NOT dropped for it, which is the mistake the
                    // first version of this guard made. The grant is taken and the
                    // mirror is copied by the time we are here, so refusing to
                    // navigate throws away minutes of the user's copy and says
                    // nothing at all -- worse than the error page it avoids, because
                    // an error page can at least be read. It is remembered instead
                    // and the server is asked back: retryServerStart puts up the
                    // loading page, whose URL names no folder, so onServerReady's
                    // loadVSCode falls through the chain to this one.
                    if (nodeService?.isServerReady() == true) {
                        navigateToFolder(serverPort, target)
                    } else {
                        Logger.i(tag, "The server is not serving; opening the folder once it is")
                        rememberWorkspaceFolder(target)
                        retryServerStart()
                    }
                }
            } catch (e: CancellationException) {
                // Not a folder that failed. This Activity is being destroyed and
                // took its scope with it, and the handlers below would read that
                // as a failed sync, `kotlinx.coroutines.CancellationException`
                // is a `java.util.concurrent` one, which is a plain `Exception`,
                // so it lands in the catch-all and every non-suspending statement
                // in it runs.
                //
                // What that costs is not a spurious toast. `safManager` belongs
                // to this Activity and `onDestroy` has already stopped its
                // watcher and unbound everything; [restoreWatcherAfterFailure]
                // would then start a FileObserver and the `saf-writeback` thread
                // again on that same engine, and only `stopWatching()` on that
                // instance can ever stop them. The replacement Activity builds
                // its own manager, so its `stopFileWatcher()` does not reach the
                // old one. Two engines end up watching one mirror, and the
                // orphaned one reads the `.partial` renames of the next sync as
                // the user's own edits and pushes them onto the user's documents
                // which is the exact damage the stop before the sync exists to
                // prevent.
                //
                // `SafSyncEngine.shutdown()`, which is what `onDestroy` calls, now
                // refuses that restart at the engine, so this is no longer the only
                // thing standing between a cancelled scope and an unstoppable
                // watcher. It stays because the rest of the handler is still wrong
                // for a cancellation: a toast and a "sync failed" dialog for an
                // Activity that is simply going away.
                //
                // Guarded because the window may already be gone: dismissing a
                // dialog whose Activity has been destroyed throws, and an
                // exception raised inside this handler would reach the scope's
                // handler rather than being the cancellation it is.
                if (!isFinishing && !isDestroyed) dialog.dismiss()
                throw e
            } catch (e: SecurityException) {
                // Guarded like the cancellation branch above, and for the reason
                // that one gives. A real failure can land here while this screen
                // is going away (a grant revoked mid-sync, a provider giving up
                // on a network share, in the moment the user swipes the app
                // away): dismissing a dialog whose window has already been torn
                // down throws, and a throw raised inside a catch leaves the
                // coroutine by the scope's uncaught handler rather than being
                // handled here at all, which is a crash on the way out of a
                // screen the user has already left. Skipping it leaves nothing on
                // screen, because the window that carried the dialog is what has
                // gone; the notice below is a toast and outlives it.
                if (!isFinishing && !isDestroyed) dialog.dismiss()
                Logger.e(tag, "SAF permission revoked during sync", e)
                reportSyncFailure(
                    getString(R.string.saf_sync_denied),
                    restoreWatcherAfterFailure(previouslyWatched, uri)
                )
            } catch (e: Exception) {
                // Guarded for the reason the handler above it is.
                if (!isFinishing && !isDestroyed) dialog.dismiss()
                Logger.e(tag, "SAF sync failed", e)
                reportSyncFailure(
                    getString(R.string.saf_sync_failed, e.message),
                    restoreWatcherAfterFailure(previouslyWatched, uri)
                )
            } finally {
                // Cleared however this ends, cancellation included: a folder left
                // marked as syncing would make every later page load into its
                // mirror a no-op, which is the defect this marker exists to avoid
                // rather than one to introduce.
                if (syncingFolder == uri) syncingFolder = null
                deviceFolderOpens.unlock()
            }
        }
    }

    /**
     * Puts the previous folder's watcher back after a failed sync, when doing so
     * is safe.
     *
     * Safe exactly when the folder that failed is not the folder that was being
     * watched, and that distinction is the whole function. Stopping the watcher
     * before the sync is what keeps the engine from observing its own writes,
     * but leaving it stopped is only justified for the folder the sync was
     * writing into: that mirror is part-written, and a watcher over a
     * part-written mirror would push that half onto the user's own documents
     * (damage to their only copy).
     *
     * A different folder's mirror was not touched at all. Leaving *its* watcher
     * dead buys nothing and costs the user the write-back for the folder still
     * on screen, which they go on editing in the belief it is being saved. That
     * is the failure this whole reorder exists to avoid, arriving from the other
     * direction.
     *
     * Suspending for the reason [openSafFolder] hops: starting a watcher walks
     * the whole mirror and registers one kernel watch per directory, and this
     * runs from a failure handler with a dialog still on screen.
     *
     * @return whether write-back is running for the folder the user is looking at.
     */
    private suspend fun restoreWatcherAfterFailure(previous: Pair<File, Uri>?, failed: Uri): Boolean {
        val (mirrorDir, uri) = previous ?: return false
        if (!shouldRestorePreviousWatcher(uri.toString(), failed.toString())) return false
        withContext(Dispatchers.IO) { safManager.startFileWatcher(mirrorDir, uri) }
        beginWatching(mirrorDir, uri)
        Logger.i(tag, "Restored the previous folder's watcher after a failed switch")
        return true
    }

    /**
     * Records that a watcher is on [mirrorDir], both for now and for the rest of
     * the process.
     *
     * One method rather than two assignments at each site, because the two
     * records answer different questions and only one of them is ever cleared.
     * [watchedSafFolder] says what is watched now and goes null on every folder
     * switch; [mirrorsWatchedThisProcess] says what a write-back drain could
     * still be inside, and a drain outlives the switch that stopped it. A site
     * that set the first without the second would leave the removal guard
     * believing a mirror was untouched while a thread was still writing out of
     * it, and nothing about that site would look wrong.
     */
    private fun beginWatching(mirrorDir: File, uri: Uri) {
        watchedSafFolder = mirrorDir to uri
        mirrorsWatchedThisProcess.add(mirrorDir.name)
    }

    /**
     * The local copies of device folders, as the JSON the storage screen renders.
     *
     * Runs on the WebView's bridge thread and walks every mirror twice, once to
     * size it and once to ask whether the device folder holds everything in it.
     * That is slow enough to be worth naming, and the alternative is worse: a
     * screen that offers a removal without saying what it costs is the shape this
     * whole feature exists to replace.
     */
    private fun deviceFolderCopiesAsJson(): String =
        JSONArray().apply {
            safManager.listMirrors().forEach { mirror ->
                put(JSONObject().apply {
                    put("hash", mirror.hash)
                    // Absent rather than filled in with the hash: the extension
                    // decides how to name a folder the app can no longer name,
                    // and a hash rendered as a folder name reads as a real one.
                    if (mirror.displayName != null) put("name", mirror.displayName)
                    put("bytes", mirror.bytes)
                    put("lastOpened", mirror.lastOpened)
                    put("granted", mirror.granted)
                    put("reclaimable", mirror.reclaimable)
                })
            }
        }.toString()

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

    /**
     * Removes the local copy of one device folder, or says why it cannot be.
     *
     * Two refusals, in two places, because they answer different questions and
     * only one of them can be answered here. Whether anything is still using the
     * mirror is this Activity's to know, and
     * [SafStorageManager.reclaimRefusal] is the rule; whether removing it would
     * lose the user's only copy of something is the storage manager's, and
     * [SafStorageManager.reclaimMirror] re-asks it at the moment of the removal
     * rather than trusting the listing the user was shown, because a write-back
     * can strand a file while a confirmation dialog is on screen.
     *
     * The open workspace is read from [openWorkspaceFolder] and not from
     * `webView?.url`. This runs on the bridge thread, and `WebView.getUrl` is a
     * View call that belongs to the UI thread; that field exists precisely
     * because the resource interceptor has the same problem.
     *
     * @return the empty string when the copy was removed, and when the user
     *   declined a forced removal, since there is nothing to tell them about a
     *   choice they just made. Otherwise the sentence to show. Success is the
     *   falsy value; see [com.vscodroid.bridge.AndroidBridge.reclaimSafMirror].
     */
    private fun removeDeviceFolderCopy(hash: String, force: Boolean): String {
        val mirrorsRoot = Environment.getSafMirrorsDir(this)
        // The refusal is resolved here because this is the side that has a Context.
        // The predicate answers WHICH refusal applies and deliberately not what it
        // says, which is what keeps it pure and reachable from a JVM test with no
        // Activity. See SafStorageManager.RECLAIM_FOLDER_OPEN.
        val inUse = SafStorageManager.reclaimRefusal(
            hash = hash,
            watchedMirror = watchedSafFolder?.first?.name,
            syncingMirror = syncingFolder?.let { safManager.getMirrorDir(it).name },
            openWorkspaceMirror = SafStorageManager.mirrorNameFor(
                openWorkspaceFolder, mirrorsRoot
            ),
            watchedThisProcess = mirrorsWatchedThisProcess,
        )?.let { getString(it) }
        if (inUse != null) return inUse

        // Asked of the user rather than taken on the caller's word; see
        // confirmForcedRemoval. A decline is answered with nothing to say, which
        // is the only honest answer this side has: the bundled extension draws
        // EVERY non-empty answer as "Could not remove that folder's local copy:
        // <this>", so any sentence at all reports a cancel the user chose as a
        // failure of the app's. It went out first as the not-a-copy sentence,
        // which told someone who had just pressed Cancel that the app had
        // overruled them to protect their data, and then as the retry sentence
        // the filesystem-refusal branch below owns, which invited them to press
        // the button they had just declined.
        //
        // The empty string is this method's success value, so a decline is
        // reported to the caller as ok. Nothing states or implies that anything
        // was freed on that road: the byte figure is announced from the success
        // branch below and from nowhere else, and the extension answers an ok by
        // reopening the folder list, where the copy the user kept is still on it.
        if (force && !confirmForcedRemoval()) return ""

        val freed = safManager.reclaimMirror(hash, force)
        return when (freed) {
            SafStorageManager.RECLAIM_UNKNOWN -> getString(R.string.saf_mirror_unknown)
            SafStorageManager.RECLAIM_REFUSED -> getString(R.string.saf_mirror_not_a_copy)
            // The copy is still there and still whole: nothing was released and
            // nothing was deleted, so the sentence is the one that invites a retry.
            //
            // The sweep is started here as well, and that is what makes the retry
            // worth inviting. The rename fails because a `discarded-` directory an
            // earlier removal left behind is still sitting on the name this one
            // needs, so a retry fails the same way until something clears it, and
            // until now the only thing that did was the next launch. Sweeping now
            // takes the obstacle away while the user is still on the screen.
            SafStorageManager.RECLAIM_FAILED -> {
                thread(name = "saf-sweep", isDaemon = true) {
                    try {
                        safManager.sweepDiscardedMirrors()
                    } catch (e: Exception) {
                        Logger.w(tag, "Sweeping the removed copies failed: ${e.message}")
                    }
                }
                getString(R.string.saf_mirror_not_removed)
            }
            else -> {
                // The mirror is already unreachable at this point; what is left is
                // the recursive delete, which takes as long as the tree is big.
                // Detached because nothing waits for it: the next launch pass
                // finishes any leftover, so the worst a killed process costs is
                // disk that is already spent.
                thread(name = "saf-sweep", isDaemon = true) {
                    try {
                        safManager.sweepDiscardedMirrors()
                    } catch (e: Exception) {
                        Logger.w(tag, "Sweeping the removed copies failed: ${e.message}")
                    }
                }
                runOnUiThread {
                    Toast.makeText(
                        this,
                        getString(
                            R.string.saf_mirror_removed, StorageManager.formatSize(freed)
                        ),
                        Toast.LENGTH_LONG,
                    ).show()
                }
                ""
            }
        }
    }

    /**
     * Asks the user, here, before a forced removal deletes files that exist
     * nowhere else.
     *
     * `reclaimSafMirror`'s own documentation says `force` may only be set "after
     * the user has confirmed a modal that says so". That is a promise the caller
     * makes and this side cannot check, and the caller is not only the bundled
     * extension: the relay is shared by every web extension on the workbench's
     * origin (see [injectBridgeRelay]), so `{cmd:'reclaimSafMirror', force:true}`
     * from a script the user never looked at satisfied the contract exactly as
     * well as a person pressing Remove. What `force` skips is the check that every
     * file in the copy is also on the device, so what it deletes is by definition
     * work that exists in no other place: anything under `node_modules`, `.git`,
     * `__pycache__` or `.gradle`, which the sync excludes by construction, and
     * anything written while no watcher was running.
     *
     * So the question is asked by the app, of the person holding the phone, and
     * the answer is theirs. The wording is the same sentence a refused unforced
     * removal gives, which is the one that names the stake, and the affirmative
     * button names the act rather than agreeing with the app: "OK" beside a
     * warning about files that exist nowhere else leaves a reader who confirms
     * quickly nothing to read but the sentence they are dismissing.
     *
     * This runs on the bridge's own disk-work thread, never the UI thread, which
     * is what lets it wait: the four storage commands are answered there precisely
     * so the workbench's own thread is not held. The wait is bounded all the same,
     * because that thread is single and everything else queued on it waits too.
     * The bound is well inside the two minutes the bundled extension gives a
     * storage command, so a question nobody answered is reported as a refusal
     * rather than as a caller timing out.
     *
     * A question that outlives the wait is taken off the screen rather than left
     * there. Nothing was removed, which is the honest outcome, but a dialog still
     * offering Remove says the opposite: pressing it would do nothing, and a
     * second attempt would stack a second dialog on top of the first.
     */
    private fun confirmForcedRemoval(): Boolean {
        // The one thread this cannot be asked from, refused rather than obeyed.
        // Waiting for a dialog on the thread that has to draw it is a deadlock
        // until the bound expires, which the platform calls an ANR; answering no
        // is a removal that did not happen, which the caller already handles.
        // `BridgeCallbackThreadHopTest` is what keeps the real caller off this
        // thread, and this is here so a future one cannot make that mistake
        // silently.
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Logger.e(tag, "A forced removal asked for confirmation on the UI thread; refusing")
            return false
        }
        val answered = CountDownLatch(1)
        val confirmed = AtomicBoolean(false)
        // The handle the timeout below needs. Written on the UI thread and read on
        // this one, so it is published rather than a plain field.
        val shown = AtomicReference<AlertDialog?>(null)
        runOnUiThread {
            // A dialog on a window that is going away throws, and this is reached
            // from a thread that knows nothing about the screen's lifecycle. The
            // latch is released either way, so a removal is never left waiting out
            // the whole bound for a dialog that was never drawn.
            if (isFinishing || isDestroyed) {
                answered.countDown()
                return@runOnUiThread
            }
            shown.set(
                AlertDialog.Builder(this)
                    .setMessage(getString(R.string.saf_mirror_not_a_copy))
                    .setPositiveButton(R.string.saf_mirror_remove) { _, _ ->
                        confirmed.set(true)
                        answered.countDown()
                    }
                    .setNegativeButton(android.R.string.cancel) { _, _ -> answered.countDown() }
                    // Back and a tap outside both land here rather than on either
                    // button, and neither is a yes.
                    .setOnCancelListener { answered.countDown() }
                    .show()
            )
        }
        val got = answered.await(FORCED_REMOVAL_CONFIRM_MS, TimeUnit.MILLISECONDS)
        if (!got) {
            // Taken down only when nobody answered: a button already dismissed its
            // own dialog, and dismiss() is not cancel(), so this cannot release the
            // latch a second time. Guarded like every other dialog call here,
            // because the window may have gone while the wait ran.
            runOnUiThread {
                if (!isFinishing && !isDestroyed) shown.get()?.dismiss()
            }
        }
        return confirmed.get()
    }

    /**
     * Says that a folder did not open, and what that costs the user.
     *
     * The consequence is spelled out rather than left to be discovered. An
     * editor that has quietly stopped writing changes back is exactly the
     * failure that costs somebody an afternoon of work they believed was saved,
     * and a bare "failed to open" gives them no reason to suspect it.
     *
     * It is also stated only when it is true. Telling a user their work is not
     * saving when it is has its own cost, and after a failed switch away from a
     * healthy folder that folder is still being watched.
     *
     * Each case is one resource holding the whole sentence, with [reason] as its
     * argument, rather than a consequence appended to a cause in Kotlin. A
     * concatenation puts the cause first in every language and no translation can
     * move it.
     */
    private fun reportSyncFailure(reason: String, writeBackStillRunning: Boolean) {
        val message = if (writeBackStillRunning) {
            getString(R.string.saf_sync_failed_other_folder_watched, reason)
        } else {
            getString(R.string.saf_sync_failed_no_write_back, reason)
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    /**
     * Opens a previously selected SAF folder from the recent list.
     * Called from [AndroidBridge.openRecentFolder] via JS bridge.
     *
     * A URI this app holds no grant for is refused and nothing else happens. It
     * used to open the folder picker instead, and that made the system's
     * document-tree chooser something any caller could put on the user's screen
     * without ever asking for it: `openRecentFolder` is one of the relay commands,
     * the URI travels with the call, and the recent list the legitimate caller
     * chooses from is pruned of revoked grants before it is handed over
     * (`SafStorageManager.getPersistedFolders`), so a URI arriving here without a
     * grant is either a lapse in the moment between the listing and the tap or a
     * URI nobody was ever offered. Neither is a reason to open a chooser, and
     * picking a folder in one persists a grant and copies the whole tree in.
     *
     * The notice stays, because the first of those two really happens and the user
     * is owed the reason; the instruction in it already points at the command that
     * opens the picker deliberately.
     */
    fun openRecentSafFolder(uri: Uri) {
        if (!safManager.hasPersistedPermission(uri)) {
            Toast.makeText(
                this,
                getString(R.string.saf_permission_expired),
                Toast.LENGTH_LONG
            ).show()
            return
        }
        handleSafFolderSelected(uri)
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
        // is answering, so isServerRunning() calls a mid-restart server healthy
        // and lets the branches below reload the page into a port that is not
        // listening yet.
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
                // Not a fresh token -- an earlier version of this comment said
                // that and it is wrong. The server writes the token once and
                // reuses it on every later start, so what a cold start renews is
                // the cookie, never the value inside it.
                markAppNavigation()
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
     * Whether an `<input type=file>` is still waiting for the device picker.
     *
     * Asked of the chrome client on the current WebView, which is where
     * [deliverFileChooserResult] takes the answer, so the two cannot disagree
     * about which document is owed one. A client that was replaced with its page
     * reports nothing pending, which is right: there is no selection left to
     * protect.
     */
    private fun fileChooserIsPending(): Boolean =
        (webView?.webChromeClient as? VSCodroidWebChromeClient)?.hasPendingFileChooser == true

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
        webView = findViewById(R.id.webView)
        webView?.let { wv ->
            VSCodroidWebView.configure(wv)
            applyWindowInsetsPadding(wv)
            // Here and not in initBridge, which runs once per server lifecycle
            // behind a guard: a WebView with no download listener drops every
            // download on the floor without a word, which is exactly the state
            // this fixes, and a replacement view created for a renderer crash
            // has to come back with one.
            wv.setDownloadListener { url, _, contentDisposition, _, _ ->
                downloads.onDownloadStart(url, contentDisposition)
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
            wv.loadData(dataUrlSafe(loadingPage()), "text/html", "utf-8")
        }
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
            if (url != RETRY_URL) return false
            Logger.i(tag, "Retrying the server from the error page")
            retryServerStart()
            return true
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            Logger.e(tag, "Render process gone before the workbench loaded: " +
                "didCrash=${detail.didCrash()}")
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
        return handled || event.keyCode == KeyEvent.KEYCODE_ESCAPE
    }

    private fun requestNotificationPermission() {
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
        nodeService?.onServerReady = { port ->
            // Recorded whatever the page says, so the reload the crash page
            // offers has a port to load when the user does ask.
            serverPort = port
            runOnUiThread {
                // A server that came back on its own is not the user asking, and
                // the crash page has told them the editor stays down until they
                // do. Loading here anyway ran the loop that page exists to stop
                // one more turn; see rendererCrashLoopShown.
                if (rendererCrashLoopShown) {
                    Logger.i(tag, "Server ready again; the renderer-crash page stays up until asked")
                } else {
                    loadVSCode(port)
                }
            }
        }
        nodeService?.onServerError = { message ->
            runOnUiThread {
                Logger.e(tag, "Server error: $message")
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
        }
        // A toast lasts three and a half seconds; this state lasts until the app
        // is killed. What was on screen behind it read "Starting server...", and
        // went on reading that for ever, so the one thing the screen said was the
        // one thing that was no longer true.
        nodeService?.onServerGaveUp = {
            runOnUiThread { showServerGaveUp() }
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

        // The permission answer can beat the binding; if it did, this is the
        // first moment the request has anywhere to go.
        if (notificationRefreshPending) refreshServiceNotification()

        // A server that became ready before this activity bound will never fire
        // onServerReady at it (launchServer()'s coroutine has already finished),
        // so the state has to be asked for rather than waited on.
        //
        // isServerReady(), not isServerRunning(). The latter is Process.isAlive,
        // which is true from the moment the process is spawned and stays true for
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
        when (
            val decision = bindDecision(
                notice = service.lastStartupNotice(),
                port = service.getPort(),
                ready = service.isServerReady(),
            )
        ) {
            is BindDecision.ShowNotice -> {
                Logger.w(tag, "Server start notice predating this binding: ${decision.message}")
                Toast.makeText(this, decision.message, Toast.LENGTH_LONG).show()
            }
            is BindDecision.ShowGaveUp -> {
                // The page, not only the toast. The toast is gone in three and a
                // half seconds and what it leaves behind is the loading page,
                // still saying the server is starting. The page below says what
                // happened and carries the only control that can change it.
                Logger.w(tag, "Server had already given up when this binding arrived")
                Toast.makeText(this, decision.message, Toast.LENGTH_LONG).show()
                showServerGaveUp()
            }
            is BindDecision.Load -> {
                Logger.i(tag, "Server already serving on port ${decision.port}, loading immediately")
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
        """<html><head><meta name="viewport" content="width=device-width, initial-scale=1.0, viewport-fit=cover"></head>
           <body style="background:#1e1e1e;color:#888;font-family:sans-serif;
           display:flex;align-items:center;justify-content:center;height:100vh;margin:0;">
           <div style="text-align:center"><h2 style="color:#ccc;">VSCodroid</h2>
           <p>${escapeHtml(getString(R.string.server_starting))}</p></div></body></html>"""

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
    private fun showErrorPage(message: String, control: String) {
        // The page about to be shown is not the workbench, so nothing arriving
        // afterwards should be told it is. recreateWebView clears this for the
        // same reason when it throws the loaded page away.
        workbenchLoaded = false
        // Answered from the control rather than by each caller, so the two pages
        // cannot drift apart on it: the one whose control is the reload is the
        // one that refuses an unasked reload, and the gave-up page, whose control
        // restarts the server, needs the readiness that follows to load.
        rendererCrashLoopShown = control == RELOAD_URL
        val retry = getString(R.string.error_server_retry)
        // This app deciding to replace a dead editor, which is exactly the
        // distinction navigationIsOurs draws. Unmarked, the workbench's unload
        // veto over a backup the dead server cannot accept puts the platform's
        // leave-page modal in front of this load, and its Cancel branch aborts
        // the navigation -- taking away the RETRY_URL button that is the only
        // control able to start the server again.
        markAppNavigation()
        webView?.loadDataWithBaseURL(
            null,
            """<html><head><meta name="viewport" content="width=device-width, initial-scale=1.0, viewport-fit=cover"></head>
               <body style="background:#1e1e1e;color:#ccc;font-family:sans-serif;
               display:flex;align-items:center;justify-content:center;height:100vh;margin:0;">
               <div style="text-align:center;max-width:32em;padding:1.5em">
               <h2 style="color:#ccc;margin:0 0 .6em">VSCodroid</h2>
               <p style="color:#aaa;line-height:1.5">${escapeHtml(message)}</p>
               <p><a href="$control" style="display:inline-block;margin-top:.8em;padding:.6em 1.4em;
               background:#0e639c;color:#fff;text-decoration:none;border-radius:4px">${escapeHtml(retry)}</a></p>
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
    private fun retryServerStart() {
        // The loading page promises the editor once the server answers, and the
        // readiness that follows has to be allowed to keep that promise.
        rendererCrashLoopShown = false
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
        initBridge(port)
        applyEditorLanguage()
        // Before the folder chain, because a closed folder is the one state that
        // chain cannot name and would otherwise fall through to the remembered
        // folder, reopening the workspace the user had just closed. [fromUrl] is
        // for the caller whose WebView no longer holds the URL it is asking about,
        // which is the renderer-crash path: [recreateWebView] builds a new one.
        emptyWindowUrl(fromUrl ?: webView?.url, port)?.let {
            Logger.i(tag, "Restoring the closed-folder window rather than a folder")
            markAppNavigation()
            webView?.loadUrl(it)
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
        lifecycleScope.launch {
            // Off the main thread for the reason adoptWorkbenchFolder hops, and
            // this is the same lookup: deciding whether a remembered mirror is
            // still granted asks the system server for every persisted grant and
            // prunes the recent list against the answer, and the default is a
            // directory this may have to create. Both are here on the cold-start
            // path, on the thread drawing the screen, in the moment before the
            // workbench is put up.
            //
            // The connection token rides along, because this is the branch every
            // cold start takes: `onServerReady` calls this with no folder and the
            // WebView is still holding the `data:` placeholder, so `known` is
            // null. `ProcessManager.connectionToken` is `cachedToken ?: readTokenFile()`
            // and nothing has read it before the first navigation, so resolving it
            // where [navigateToFolder] used to meant a `stat` and a `readText` on
            // the main thread on every cold launch, at exactly the moment the
            // workbench URL is built. `MainThreadWatch` lists that read among the
            // sites its measured inventory did NOT see, explaining the absence as
            // needing an interaction a cold launch does not perform; a cold launch
            // always navigates. Resolving it here costs nothing extra, because the
            // hop was already being made, and makes that inventory true again.
            val resolved = withContext(Dispatchers.IO) {
                val connectionToken = nodeService?.getConnectionToken()
                // The close is asked about before the remembered folder, and both
                // are behind the URL: a folder still on screen outranks either.
                // Without the first test the record still named the folder the
                // user had closed, so a relaunch put them back into it, and the
                // fix that made a close survive an editor crash only covered the
                // paths that still had the URL in hand.
                //
                // Inside the IO hop because the first read of a preferences file
                // is disk work; `MainThreadWatch` is what notices when it is not.
                val folder = if (workspaceWasClosed()) null else rememberedWorkspaceFolder()
                    ?: FirstRunSetup(this@MainActivity).ensureProjectsDir()
                connectionToken to folder
            }
            // Asked again rather than carried across the hop. The URL outranks
            // the remembered folder before the suspension and has to go on
            // outranking it after: a renderer crash recovering into the folder it
            // was showing arrives through this same method, and a remembered
            // folder resolved before it would otherwise land last and win.
            navigateToFolder(port, folderFromUrl(webView?.url) ?: resolved.second, resolved.first)
        }
    }

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
     * [setupWebView], and `getQueryParameter` throws
     * `UnsupportedOperationException` on an opaque URI. This runs on the main
     * thread from `onServiceConnected`, so on every cold start the app died at the
     * moment the server came up.
     */
    private fun folderFromUrl(url: String?): String? =
        url?.let { it.toUri() }
            ?.takeIf { it.isHierarchical }
            ?.let {
                workbenchTarget(
                    folder = it.getQueryParameter("folder"),
                    workspace = it.getQueryParameter("workspace"),
                    isDirectory = { path -> File(path).isDirectory },
                    isFile = { path -> File(path).isFile },
                )
            }

    /**
     * Initializes the WebView bridge, security manager, and clients.
     * Only called once per server lifecycle, not on every folder switch.
     */
    private fun initBridge(port: Int) {
        val wv = webView ?: return

        // Skip re-initialization if bridge is already set up for this port
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
            // handlers: these five are the whole boundary, and a reader checking
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
            safManager = safManager,
            // Not hopped to the UI thread, unlike the five above. These three
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
            // The answer's road back into the page. evaluateJavascript is a
            // WebView call and belongs to the thread the view was made on, which
            // is why this hops while the two above must not.
            onAsyncAnswer = { id, ok, payload ->
                runOnUiThread { answerBridgeCommand(id, ok, payload) }
            },
        )
        wv.addJavascriptInterface(bridge, "AndroidBridge")

        // One set of rules, read once, handed to both entry points. The service
        // worker is the second route a resource request takes into the
        // interceptor, so lists installed on only one side leave the other
        // answering by different rules, and neither side would say a word about
        // the difference.
        val roots = resourceRoots
        val sensitive = sensitivePaths

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
        // takes a @Nullable client at minSdk 33, measured against the platform
        // stub, so clearing it in onDestroy is a real option. What it costs is an
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
            port, roots, sensitive, { self.get()?.openWorkspaceRoot }
        ) { self.get()?.nodeService?.getConnectionToken() }

        wv.webViewClient = VSCodroidWebViewClient(
            allowedPort = port,
            resourceRoots = roots,
            sensitiveLocations = sensitive,
            openFolder = { openWorkspaceRoot },
            connectionToken = { nodeService?.getConnectionToken() },
            onCrash = { recreateWebView() },
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
                val failure = HandoffFailure(
                    uri.scheme ?: "external", error.javaClass.simpleName
                )
                runOnUiThread {
                    handoffFailureToAnnounce(failure, announcedHandoffFailures)?.let {
                        val message = if (error is android.content.ActivityNotFoundException) {
                            getString(R.string.url_handoff_no_app, it.scheme)
                        } else {
                            getString(R.string.url_handoff_failed, it.scheme, it.failureType)
                        }
                        Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
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
                    rememberWorkspaceFolder(opened)
                    adoptWorkbenchFolder(opened)
                } else if (emptyWindowUrl(url, port) != null) {
                    // Closing the folder is a navigation the workbench performs on
                    // itself, so this callback is the only place Kotlin can learn
                    // it happened. The test is [emptyWindowUrl] and not
                    // [isWorkbenchUrl]: the second is host and port only, so a bare
                    // `/` load would forget a folder that is open, and the symptom
                    // (the next launch drops the user in the projects directory)
                    // looks nothing like the cause.
                    rememberWorkspaceFolder(null)
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
                if (isWorkbenchUrl(url, port)) {
                    injectBridgeToken()
                }
            },
            onRetryServer = { retryServerStart() },
            // The application's assets, not this activity's, because the client
            // outlives nothing here but the request it is answering and an
            // AssetManager tied to an activity is one more thing to get wrong on
            // recreation. Both point at the same APK.
            interfaceTranslations = applicationContext.assets,
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
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
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
     * here, not only of a close: the record is written from the finished load,
     * and moving it earlier would put a preference write on the interception
     * path.
     */
    private fun rememberWorkspaceFolder(folderPath: String?) {
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
        // Written only on a change: this runs on every main-frame load, and the
        // server redirects, so a folder switch alone reaches it twice.
        val stored = folderPath ?: NO_FOLDER
        if (workspacePrefs.getString(KEY_LAST_FOLDER, null) == stored) return
        workspacePrefs.edit { putString(KEY_LAST_FOLDER, stored) }
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
     * A timestamp rather than a flag nobody clears: the callback runs
     * synchronously inside the load it belongs to, so a few seconds is generous,
     * and an expiry means a navigation that never happened cannot leave the
     * answer armed for a page-initiated one minutes later.
     */
    @Volatile
    private var lastAppNavigation = 0L

    /** Marks the navigation about to be started as this app's own. */
    private fun markAppNavigation() {
        lastAppNavigation = SystemClock.elapsedRealtime()
    }

    private fun navigationIsOurs(): Boolean =
        SystemClock.elapsedRealtime() - lastAppNavigation < APP_NAVIGATION_WINDOW_MS

    /**
     * The remembered workspace, when reopening it is still the right thing.
     *
     * Two ways it stops being so, and the second is the one that costs
     * something. A folder that is gone (deleted, storage unmounted) would pin
     * the WebView to a dead path, which is the same reason [folderFromUrl] stats
     * the folder it reads. And a device-folder mirror whose grant has lapsed is
     * worse than useless: nothing would sync it, the launch reclaim pass is
     * entitled to delete it out from under the editor, and the user would go on
     * editing in the belief that their work is reaching the device.
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
     * Navigates the WebView to a specific folder without re-initializing the bridge.
     * Safe to call multiple times (e.g., when switching SAF folders).
     *
     * [token] is the connection token, and it is a parameter so that the caller
     * which already has a thread to spare can read it there. The default keeps
     * every other caller as it was: reading it costs a `stat` and a small
     * `readText` only until `ProcessManager` has cached it, which the first
     * navigation of the run does. See [loadVSCode] for which caller pays it.
     */
    private fun navigateToFolder(
        port: Int,
        folderPath: String?,
        token: String? = nodeService?.getConnectionToken(),
    ) {
        val wv = webView ?: return
        // Every workbench load passes through here, and a workbench on screen is
        // a page that never refused a reload: a picker result landing while the
        // crash page is up navigates here directly, and a later self-restart's
        // readiness must not be refused over the workbench it would replace.
        rendererCrashLoopShown = false
        // Seeded here and not only from the page-loaded callback. This method is
        // the one that knows the folder before the page exists, and between
        // loadUrl below and onPageFinished the workbench is already fetching
        // resources, against a supplier that would still be answering null, so
        // everything inside the workspace would 404 for the length of the load.
        rememberWorkspaceFolder(folderPath)
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
        wv.loadUrl(url)
    }

    private fun injectBridgeToken() {
        val token = securityManager.getSessionToken()
        webView?.evaluateJavascript(
            "window.__vscodroid = window.__vscodroid || {}; window.__vscodroid.authToken = '$token';",
            null
        )
        // Install JS interceptor so ExtraKeyRow Ctrl/Alt modifiers apply to soft keyboard input
        extraKeyRow?.keyInjector?.setupModifierInterceptor()
        // Inject safe area CSS for round-corner devices
        injectSafeAreaCSS()
        // Set up BroadcastChannel relay so browser extensions can reach AndroidBridge
        injectBridgeRelay()
        // Register memory pressure listener
        injectMemoryPressureHandler()
        // Fix #2: Inject touch target enlargement CSS for phone-sized screens
        injectTouchTargetCSS()
        // Keeps the soft keyboard down until the user aims at text
        injectKeyboardGuard()
        // Fix #7: Override window.open() to route through AndroidBridge
        injectWindowOpenOverride()
        // Keeps a downloaded blob readable long enough to be saved, and names it
        injectDownloadCapture()
        // Open in Browser, SSH keys and About are contributed by the bundled bridge
        // extension, which registers them through the extension API and reaches Android
        // over the relay below. They cannot be injected from here: the workbench is an
        // ES module and the AMD loader those injections needed does not exist.

        // Last, because everything above is what makes the page able to receive
        // an auth callback at all. From here a callback arriving through
        // onNewIntent goes straight into this page, which is the only page that
        // can consume one.
        workbenchLoaded = true
    }

    /**
     * Injects CSS into VS Code to handle round-corner device safe areas.
     *
     * Adds padding to the Activity Bar (left sidebar) and Status Bar (bottom)
     * so content isn't clipped by the device's rounded display corners.
     * Uses CSS `env(safe-area-inset-*)` with fallback padding.
     */
    private fun injectSafeAreaCSS() {
        webView?.evaluateJavascript(
            """
            (function() {
                if (document.getElementById('vscodroid-safe-area-css')) return;

                // Ensure viewport-fit=cover meta tag exists
                var meta = document.querySelector('meta[name="viewport"]');
                if (meta) {
                    var content = meta.getAttribute('content') || '';
                    if (content.indexOf('viewport-fit') === -1) {
                        meta.setAttribute('content', content + ', viewport-fit=cover');
                    }
                } else {
                    meta = document.createElement('meta');
                    meta.name = 'viewport';
                    meta.content = 'width=device-width, initial-scale=1.0, viewport-fit=cover';
                    document.head.appendChild(meta);
                }

                var style = document.createElement('style');
                style.id = 'vscodroid-safe-area-css';
                style.textContent = [
                    '/* VSCodroid: Safe area padding for round-corner devices */',
                    '.part.activitybar {',
                    '  padding-left: env(safe-area-inset-left, 0px);',
                    '  padding-top: env(safe-area-inset-top, 0px);',
                    '}',
                    '.part.statusbar {',
                    '  padding-left: env(safe-area-inset-left, 0px);',
                    '  padding-right: env(safe-area-inset-right, 0px);',
                    '  padding-bottom: env(safe-area-inset-bottom, 0px);',
                    '}',
                    '.part.titlebar {',
                    '  padding-top: env(safe-area-inset-top, 0px);',
                    '  padding-right: env(safe-area-inset-right, 0px);',
                    '}',
                    '.part.sidebar {',
                    '  padding-left: env(safe-area-inset-left, 0px);',
                    '}',
                    '.part.panel {',
                    '  padding-bottom: env(safe-area-inset-bottom, 0px);',
                    '}'
                ].join('\n');
                document.head.appendChild(style);
            })();
            """.trimIndent(),
            null
        )
    }

    /**
     * Tells the page which language to ask for, before it asks.
     *
     * The server decides the page's language from the request it serves the
     * workbench on: a `vscode.nls.locale` cookie if there is one, else the first
     * `Accept-Language` entry. Left alone, that means the header decides the
     * page while [EditorLocale] decides the server process, and the two can
     * disagree: `android:localeConfig` lets someone set this app to Korean on an
     * English phone, and whether the WebView's header follows the app's locale
     * or the system's is not something this app controls. The failure is not a
     * crash, it is a workbench in one language and its extension host in
     * another, with nothing on screen to explain it.
     *
     * So the cookie is written from the same answer the server process is given,
     * and the header is never consulted. `en` is written rather than nothing
     * when no bundle fits, because a cookie already on the WebView from an
     * earlier language would otherwise stand: the server reads any locale
     * starting with "en" as "serve the English that already ships".
     *
     * Called from [loadVSCode] rather than once per WebView, so a language
     * changed while the app is running is picked up by the next navigation
     * rather than only by the next process. Measured on an emulator: switching
     * the per-app language while the editor was open brought it back in the new
     * language without a restart.
     *
     * The address carries no port, and that is not an omission. A cookie is
     * scoped to a host and a path, never to a port, so this one is sent to the
     * editor server whatever port it ends up on. Naming the port would also put
     * a second expression for the workbench's own address in this file, which
     * `NavigationTokenLoggingTest` refuses: one address means the string that is
     * logged and the string that is loaded cannot drift apart.
     */
    private fun applyEditorLanguage() {
        val bundle = EditorLocale.forDevice(applicationContext.assets) ?: "en"
        try {
            CookieManager.getInstance().apply {
                setAcceptCookie(true)
                setCookie("http://127.0.0.1/", "vscode.nls.locale=$bundle; path=/")
            }
        } catch (e: Exception) {
            // The editor is still usable in English, so this is a log line and
            // not a failed start. Broad on purpose: CookieManager reaches into
            // the WebView provider, and a device whose provider is being updated
            // throws from anywhere inside it.
            Logger.w(tag, "Could not set the editor's language cookie: ${e.message}")
        }
    }

    /**
     * Keeps the soft keyboard down until the user actually aims at text.
     *
     * The editor's caret lives in a focusable element the workbench focuses
     * whenever it hands focus back to the editor: after the Explorer opens or
     * closes, after a file is opened from the tree, after a view is toggled. On
     * a desktop that is invisible. Here Chromium answers a focused editing host
     * by raising the input method, so browsing files covered half the screen
     * with a keyboard nobody asked for, which is what people report as "every
     * time I touch something the keyboard appears".
     *
     * The element is `div.native-edit-context`, not a textarea: this build of
     * the workbench uses the EditContext API. That was measured over the
     * DevTools protocol rather than assumed, after a first version of this
     * guard aimed at `textarea.inputarea`, matched nothing at all, and still
     * appeared to work because the keyboard happened not to rise in the one
     * case that was tried. Monaco also keeps a `textarea.ime-text-area` around,
     * which is not the focus target; both selectors are kept so that a version
     * bump away from EditContext does not silently disarm this.
     *
     * The mechanism is `inputmode="none"`, which tells Chromium to leave the
     * keyboard alone for an element that still takes key events, so a hardware
     * keyboard and the extra key row are unaffected. Three things have to
     * happen for it to hold:
     *
     * - it is set on whatever exists when this runs, and
     * - on any editing host that takes focus later, which is how opening a file
     *   is covered: that builds a new editor, and its element does not exist at
     *   the moment of the tap that opened it. Answering the focus is enough on
     *   its own, and it is answered by taking the focus away and giving it back
     *   with the attribute in place, because by the time the event arrives the
     *   browser has already decided to raise the keyboard. A MutationObserver
     *   over the document did the same job a moment earlier and was dropped: it
     *   ran on every line the editor rendered, which on a phone is every scroll.
     * - it is removed when a touch on text turns out to be a tap rather than a
     *   scroll, which is decided at pointerup by how far the finger travelled.
     *   Deciding it at pointerdown is the obvious shape and is wrong: dragging
     *   inside the editor is how a phone scrolls a file, so it put the keyboard
     *   up over half the screen on every scroll. The element usually already has
     *   focus by then, so no focus event follows; hence the same blur and
     *   refocus, inside the gesture, which is what raises the keyboard.
     *
     * Measured on an API 37 emulator at 411dp, `dumpsys input_method` beside a
     * screenshot each time. Tapping the Explorer icon: was `mInputShown=true`,
     * now false. Opening a file from the tree: was true, now false. Dragging to
     * scroll a file: was true, now false. Tapping a word: true, with the caret
     * on it and the keyboard up.
     *
     * What it deliberately does not do is raise the keyboard where the editor
     * itself would not take focus. A tap inside the editor but off the text
     * lands on a container Monaco ignores, and the keyboard now follows the
     * focus rather than the touch: measured, focusin never fires for that tap.
     * The version before this one raised it anyway, which is a keyboard over a
     * file with no caret in it.
     *
     * The terminal is left alone. It has an editing host of its own, and
     * opening a terminal is asking to type.
     */
    private fun injectKeyboardGuard() {
        webView?.evaluateJavascript(
            """
            (function() {
                if (window.__vscodroidKeyboardGuard) return;
                window.__vscodroidKeyboardGuard = true;
                // What counts as aiming at text: anywhere inside an editor, and
                // any real input, which covers the Command Palette, the find
                // widget and every extension form.
                //
                // The whole editor rather than its lines, because everything
                // inside one belongs to the act of typing in it: the margin
                // (tapping a line number moves the caret), the empty space under
                // the last line, and the widgets the editor renders inside
                // itself. That last one is the reason it is not narrower. The
                // suggest list is a child of `.monaco-editor`, so a narrower
                // selector reads "tap a completion" as "not text" and takes the
                // keyboard away in the middle of typing, which is worse than the
                // problem this guard exists to solve.
                //
                // The trade, stated rather than discovered later: the scrollbar,
                // sticky scroll, CodeLens, the minimap for anyone who turns it
                // on, and the find widget's buttons are all inside an editor
                // too, so touching them raises the keyboard. That is what every
                // build before this guard did for those targets and for every
                // other one, so it is where this change leaves them rather than
                // something it introduces; the alternative is a selector that
                // has to name each of them and be corrected on every VS Code
                // bump that renames one.
                var TEXT = '.monaco-editor, textarea, input, [contenteditable="true"], .native-edit-context';
                var EDITING_HOST = '.native-edit-context, .monaco-editor textarea.inputarea';
                var aimedAtText = false;
                // Set while this code is taking focus away and giving it back,
                // so the focus it causes is not treated as one to answer.
                var reapplying = false;
                // Where a touch on text went down, while it is still undecided
                // whether it is a tap or the beginning of a scroll. Null at any
                // other moment.
                var pendingTap = null;
                // How far a finger may travel and still be a tap, in CSS pixels.
                // Chromium's own touch slop is 8; this is looser because the
                // target is a line of code rather than a button.
                var TAP_SLOP = 12;
                function apply(element) {
                    if (aimedAtText) element.removeAttribute('inputmode');
                    else if (element.getAttribute('inputmode') !== 'none') element.setAttribute('inputmode', 'none');
                }
                function applyAll() {
                    document.querySelectorAll(EDITING_HOST).forEach(apply);
                }
                // Lets the keyboard up for a touch that has turned out to be a
                // tap on text.
                //
                // The element usually already has focus by then, so no focus
                // event follows to act on; hence the blur and refocus, which
                // happens inside the gesture and is what raises the keyboard.
                // Only when the guard was actually holding it down: a tap on
                // text while the keyboard is already up must not reach the focus
                // at all, because blurring an element mid-composition drops the
                // text being composed, and composition is an ordinary path now
                // that the editor ships in Japanese, Korean and both Chinese
                // scripts.
                function letTheKeyboardUp() {
                    aimedAtText = true;
                    var focused = document.activeElement;
                    var wasHeldDown = !!(focused && focused.getAttribute &&
                        focused.getAttribute('inputmode') === 'none');
                    applyAll();
                    if (wasHeldDown && focused.matches && focused.matches(EDITING_HOST)) {
                        reapplying = true;
                        focused.blur();
                        focused.focus();
                        reapplying = false;
                    }
                }
                document.addEventListener('pointerdown', function(e) {
                    var target = e.target;
                    if (target && target.closest && target.closest(TEXT)) {
                        // Undecided, and that is the point. Dragging inside the
                        // editor is how a phone scrolls a file, and it goes down
                        // on the same text a tap does, so raising the keyboard
                        // here puts it over half the screen on every scroll:
                        // the complaint this guard exists for, reached by
                        // another route. Measured on a file opened with the
                        // keyboard down, before this branch was written.
                        pendingTap = { id: e.pointerId, x: e.clientX, y: e.clientY };
                        return;
                    }
                    pendingTap = null;
                    aimedAtText = false;
                    applyAll();
                }, true);
                document.addEventListener('pointerup', function(e) {
                    // Keyed by pointer, because a second finger anywhere on the
                    // page would otherwise answer for the first: the last touch
                    // down wins the single slot, and lifting either one is read
                    // as the end of that gesture.
                    if (!pendingTap || pendingTap.id !== e.pointerId) return;
                    var travelled = Math.abs(e.clientX - pendingTap.x) +
                        Math.abs(e.clientY - pendingTap.y);
                    pendingTap = null;
                    // A scroll leaves the keyboard where it was, which is down.
                    if (travelled > TAP_SLOP) return;
                    letTheKeyboardUp();
                }, true);
                document.addEventListener('pointercancel', function(e) {
                    if (pendingTap && pendingTap.id !== e.pointerId) return;
                    // The gesture became the system's: a swipe from an edge, a
                    // pull down, a second finger. Nothing was decided, so
                    // nothing changes.
                    pendingTap = null;
                }, true);
                // Focus is answered directly rather than watched for, because an
                // editing host built for a file that is being opened is focused
                // in the same breath as it is inserted: by the time anything
                // observing the document is told, the browser has already
                // decided to raise the keyboard. Blurring and refocusing puts
                // the attribute in place before focus is granted rather than a
                // moment after.
                document.addEventListener('focusin', function(e) {
                    var target = e.target;
                    if (reapplying) return;
                    // A touch on text is still in the air. Whether the keyboard
                    // may come up is the pointerup handler's to answer, and
                    // answering it here would raise it for a scroll.
                    if (pendingTap) return;
                    if (!target || !target.matches || !target.matches(EDITING_HOST)) return;
                    if (aimedAtText) { target.removeAttribute('inputmode'); return; }
                    if (target.getAttribute('inputmode') === 'none') return;
                    target.setAttribute('inputmode', 'none');
                    reapplying = true;
                    target.blur();
                    target.focus();
                    reapplying = false;
                }, true);
                applyAll();
            })();
            """.trimIndent(),
            null
        )
    }

    /**
     * Fix #2: Injects CSS to enlarge touch targets when the pointer is a fingertip.
     * Targets WCAG 2.5.5 minimum 44×44px for primary actions, 36px for list items.
     *
     * The test is `pointer: coarse`, not a viewport width, because what these rules
     * compensate for is the fingertip, and a fingertip does not change size with the
     * screen. A phone held in landscape is wider than any width threshold that still
     * excludes tablets, so a width test left exactly the orientation people turn to
     * for code width with desktop-sized targets. Conversely a tablet driven by a
     * mouse or a DeX-style desktop reports `fine` and correctly gets none of this.
     *
     * Height would have been the wrong axis for the same reason it looks tempting:
     * `windowSoftInputMode="adjustResize"` on MainActivity in the manifest shrinks the
     * window when the soft keyboard opens, and the WebView takes what is left
     * (`layout_weight="1"`), so a height threshold would switch the sizing on and off
     * while the user types.
     *
     * It has to be a media query rather than anything sampled in Kotlin: this runs
     * once per page load, and MainActivity's `configChanges` in the manifest absorbs
     * `orientation`, `screenSize` and `screenLayout` among others, with no
     * `onConfigurationChanged`, so nothing re-invokes the injection when the window
     * changes. Line numbers are left off deliberately: both citations here named
     * the right attribute and the wrong line within a day of being written. Letting the browser hold the condition costs nothing and never goes
     * stale.
     */
    private fun injectTouchTargetCSS() {
        webView?.evaluateJavascript(
            """
            (function() {
                if (document.getElementById('vscodroid-touch-css')) return;
                var s = document.createElement('style');
                s.id = 'vscodroid-touch-css';
                s.textContent = [
                    '/* VSCodroid: Enlarged touch targets for touch input */',
                    '@media (pointer: coarse) {',
                    '  .monaco-list-row { min-height: 36px !important; padding: 2px 0 !important; }',
                    '  .tabs-container .tab { min-height: 40px !important; }',
                    '  .activitybar .action-item { min-height: 44px !important; min-width: 44px !important; }',
                    '  .activitybar .action-label { min-height: 44px !important; }',
                    '  .statusbar-item { min-height: 32px !important; padding: 0 8px !important; }',
                    '  .context-view .action-item { min-height: 40px !important; }',
                    '  .context-view .action-label { padding: 6px 12px !important; }',
                    // The three floors above are for buttons, and a menu separator
                    // is an .action-item too: it sits inside the activity bar when
                    // the compact menubar is open and inside .context-view for a
                    // right-click menu, so both floors land on a 1px divider and
                    // render it as a blank band. Measured on an API 37 emulator at
                    // 411px portrait, with the build-time menu CSS in play: the File
                    // menu's seven separators were 71px each and the menu 1427px in
                    // an 810px viewport, which is most of why it overflows at all.
                    // Both halves have to go, the label's and the item's, because
                    // either one alone still holds the row open.
                    '  .activitybar .action-label.separator,',
                    '  .context-view .action-label.separator { min-height: 0 !important; padding: 0 !important; line-height: normal !important; }',
                    // Dropped whole by a WebView without :has() (Chromium 105; this
                    // build floors at 107), which leaves the divider as it was rather
                    // than breaking the rules around it.
                    '  .activitybar .action-item:has(> .action-label.separator),',
                    '  .context-view .action-item:has(> .action-label.separator) { min-height: 0 !important; }',
                    // Unprefixed, so it is wider than its name: the workbench also uses
                    // .slider for the colour picker, not only the scrollbar. Harmless
                    // there because that strip is already far wider than 12px, but
                    // narrow it and this rule starts deciding its width.
                    '  .slider { min-width: 12px !important; }',
                    '  .quick-input-list .monaco-list-row { min-height: 36px !important; }',
                    // The chrome's own text, which no setting in this build can reach.
                    // `editor.fontSize` governs the editor and nothing else; the
                    // workbench styles itself from 622 literal `font-size` rules in
                    // workbench.css, this build registers no window zoom action and
                    // ignores `window.zoomLevel`, and `WebSettings.textZoom` is pinned
                    // at 100. So the only lever left for the parts a phone user
                    // actually reads is this stylesheet.
                    //
                    // Measured on an API 37 emulator through the DevTools protocol, at
                    // the 411 CSS px viewport a phone gives: a pane header was 11px, a
                    // status bar item 12px and a tab label 13px, against 16px of editor
                    // text beside them. Each rule below stays under the min-height
                    // already floored above (32px for a status bar item, 40px for a
                    // tab, 36px for a list row), so nothing here can push text past the
                    // row that holds it.
                    //
                    // The activity bar badge is deliberately left at 9px: it is drawn
                    // as a circle sized to its own glyph, so growing the text there
                    // distorts the shape rather than the reading.
                    '  .pane-header .title { font-size: 13px !important; }',
                    '  .part.statusbar .statusbar-item { font-size: 13px !important; }',
                    '  .tabs-container .tab .label-name { font-size: 14px !important; }',
                    '  .monaco-list-row { font-size: 13px !important; }',
                    '}'
                ].join('\n');
                document.head.appendChild(s);
            })();
            """.trimIndent(),
            null
        )
    }

    /**
     * Fix #7: Overrides window.open() to route external URLs through AndroidBridge.
     * VS Code web's link handling (Markdown preview, "Open in Browser") calls window.open(),
     * which is blocked by shouldOverrideUrlLoading. This intercepts at the JS level instead.
     */
    private fun injectWindowOpenOverride() {
        webView?.evaluateJavascript(
            """
            (function() {
                if (window.__vscodroidOpenPatched) return;
                window.__vscodroidOpenPatched = true;
                var orig = window.open;
                window.open = function(url) {
                    // The editor asking for a second window, which on a device is
                    // this one. The workbench builds that URL from its own origin
                    // and pathname and puts no connection token on it, so handing
                    // it to the system browser opened a browser showing
                    // "Forbidden." and left a popup-blocked dialog over the
                    // editor. Navigating in place is what the workbench already
                    // does for Close Workspace and Open Folder, on the branch
                    // where it has a window to reuse.
                    //
                    // A prefix test, not a search: an OAuth `redirect_uri` naming
                    // 127.0.0.1 in its query would match a substring search and
                    // blank the editor mid sign-in. A dev server on another port
                    // is a different origin and still reaches the bridge, which
                    // is the branch openExternalUrl's localhost handling exists
                    // for.
                    //
                    // Returns the window rather than null: the caller reads
                    // `!!window.open(...)` and draws its own popup-blocked
                    // message for a falsy answer.
                    if (url && url.indexOf(window.location.origin + '/') === 0) {
                        window.location.href = url;
                        return window;
                    }
                    if (url && /^https?:/.test(url) && typeof AndroidBridge !== 'undefined') {
                        var t = (window.__vscodroid || {}).authToken;
                        // Only claim the click if the bridge actually opened it.
                        // `openExternalUrl` answers with a reason when the launch itself
                        // fails, not when it disapproves of the destination: SecurityManager
                        // has no URL allow-list and says so at the point one used to
                        // stand. Reading this as a destination filter is the mistake to
                        // avoid, because it invites re-deriving the fall-through around
                        // a constraint that is gone.
                        // Swallowing the refusal here meant the click did nothing and
                        // said nothing; falling through lets the WebView navigation
                        // path open it, which is where opening anything already lives.
                        //
                        // Compared against the empty string, never used as a bare
                        // condition. The bridge answers with the reason it did not
                        // open, so success is the falsy value and every failure is
                        // truthy: a bare `if` reads backwards, claims every click it
                        // failed to open, and lets the one it did open through to the
                        // WebView as well.
                        if (t && AndroidBridge.openExternalUrl(url, t) === '') { return null; }
                    }
                    return orig.apply(window, arguments);
                };
            })();
            """.trimIndent(),
            null
        )
    }

    /**
     * Lets a file the editor downloads be saved to the device.
     *
     * Two jobs, both of which have to happen inside the page because both are
     * about objects that only exist there.
     *
     * The first is keeping the bytes reachable. The editor reads a file into
     * memory, wraps it in a blob, hands the platform a `blob:` URL for it and
     * revokes that URL on the very next task. Saving needs the user to choose a
     * destination first, which takes seconds, so by the time there is anywhere
     * to write the URL names nothing at all and the download would fail every
     * time. Revocation is therefore deferred, and only for URLs a download is
     * actually using, so nothing else in the workbench is kept alive by this.
     * The Blob itself is kept alongside, because the bytes have to be read off
     * the object: the page is served under `connect-src 'self' ws: wss: https:`,
     * so a request for a `blob:` URL is refused before it starts, and that is
     * every download of a file the editor could hold in memory.
     *
     * The second is the name. A blob has none, and the platform's download hook
     * is given the URL rather than the anchor, so `App.kt` would arrive as a
     * UUID. The anchor knows, at the moment it is clicked, and a bridge call
     * blocks the page until it returns, so reporting the name here puts it on
     * the Android side before the download hook can ask for it.
     *
     * Both hooks call through unconditionally. Nothing here decides whether a
     * click downloads, and a page that stopped downloading because our
     * bookkeeping threw would be a worse failure than the one being fixed.
     */
    private fun injectDownloadCapture() {
        webView?.evaluateJavascript(
            """
            (function() {
                if (window.__vscodroidDownload) return;

                // Long enough to outlast the queue behind it. Only one download
                // holds the create-document picker at a time, so a file clicked as
                // part of a multi-select waits behind up to MAX_QUEUED pickers
                // before anyone asks for its bytes, and each of those is a trip
                // into another app that takes the user as long as it takes. At two
                // minutes the later files were revoked while their own picker was
                // still on screen, so the user chose a folder and a name and was
                // handed a failure. The hold is released as soon as the bytes are
                // being read (see readerFor), and again the moment Android gives
                // up on a download it never read (see release), so this ceiling
                // is the backstop for the one case neither covers: a download
                // whose end nobody can report, because the page it belonged to
                // is the thing that went away.
                var HOLD_MS = 600000;
                var MAX_TRACKED = 8;

                // url -> the object behind it. The page is asked for the bytes
                // of a download by URL, and the bytes have to come off this
                // object rather than off the URL: see readerFor below.
                var made = new Map();
                var held = new Map();

                function token() { return (window.__vscodroid || {}).authToken; }

                var create = URL.createObjectURL.bind(URL);
                URL.createObjectURL = function(source) {
                    var url = create(source);
                    try {
                        made.set(url, source);
                        // Bounded, because the workbench mints object URLs for
                        // all sorts of things and remembering every one of them
                        // would pin its bytes for the life of the page. A
                        // download claims its own entry on the click, which is
                        // the task straight after this one.
                        if (made.size > MAX_TRACKED) made.delete(made.keys().next().value);
                    } catch (e) { /* the URL matters more than the record of it */ }
                    return url;
                };

                var revoke = URL.revokeObjectURL.bind(URL);
                URL.revokeObjectURL = function(url) {
                    if (held.has(url)) return;
                    revoke(url);
                };

                // Each hold releases itself, so a download nobody completes
                // costs one blob for the length of HOLD_MS rather than for the
                // life of the page.
                function hold(url) {
                    if (held.has(url)) return;
                    held.set(url, made.get(url));
                    made.delete(url);
                    setTimeout(function() { held.delete(url); revoke(url); }, HOLD_MS);
                }

                // The bytes of url, as a reader.
                //
                // A blob is read off the object and never off its URL. The
                // workbench page is served under
                // connect-src 'self' ws: wss: https:, which does not list
                // blob:, so fetching a blob: URL is refused before it leaves
                // the page: "Connecting to 'blob:...' violates the following
                // Content Security Policy directive". Reading a Blob is not a
                // request and no directive governs it. Anything else is a real
                // URL the policy already allows.
                function readerFor(url) {
                    var blob = held.get(url);
                    if (blob && blob.stream) {
                        var reader = blob.stream().getReader();
                        // The hold has done its job the moment the reader exists:
                        // the reader keeps the bytes alive by itself, so the page
                        // gets the memory back now rather than at the end of the
                        // budget above. Only on this branch, because the fetch
                        // below has not read anything yet and revoking under it
                        // would refuse the very request the hold exists for.
                        held.delete(url);
                        revoke(url);
                        return Promise.resolve(reader);
                    }
                    return fetch(url).then(function(response) {
                        if (!response.ok) throw new Error('status ' + response.status);
                        return response.body.getReader();
                    });
                }

                var click = HTMLAnchorElement.prototype.click;
                HTMLAnchorElement.prototype.click = function() {
                    try {
                        var name = this.getAttribute('download');
                        var url = this.href;
                        if (name !== null && url) {
                            if (url.lastIndexOf('blob:', 0) === 0) hold(url);
                            var t = token();
                            if (t && window.AndroidBridge) {
                                AndroidBridge.noteDownloadName(t, url, name);
                            }
                        }
                    } catch (e) { /* the click matters more than the record of it */ }
                    return click.apply(this, arguments);
                };

                function encode(bytes) {
                    var text = '';
                    for (var i = 0; i < bytes.length; i += 0x8000) {
                        text += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
                    }
                    return btoa(text);
                }

                window.__vscodroidDownload = {
                    // Lets go of a download Android has finished with without
                    // ever reading it: refused for being one of too many at
                    // once, cancelled at the picker, or failed before the
                    // bytes were asked for. Without this the blob stays
                    // pinned here for the whole of HOLD_MS after the user has
                    // been told the file is not coming, and a multi-select
                    // pins every file the queue turned away at once.
                    release: function(url) {
                        if (!held.has(url)) return;
                        held.delete(url);
                        revoke(url);
                    },
                    // Reads url and pushes it back in pieces under id. Answers
                    // whether it started, which is the one failure Android
                    // cannot be told about any other way.
                    send: function(url, id) {
                        var t = token();
                        var bridge = window.AndroidBridge;
                        if (!t || !bridge) return false;
                        // Started on a promise so that a reader this page
                        // cannot open at all is reported like any other failed
                        // read, rather than thrown back at the caller that has
                        // already been told the read began.
                        Promise.resolve().then(function() {
                            return readerFor(url);
                        }).then(function(reader) {
                            return (function pump() {
                                return reader.read().then(function(step) {
                                    if (step.done) {
                                        bridge.finishDownload(t, id, '');
                                        return;
                                    }
                                    // A refused chunk has already been explained
                                    // on the Android side. Reporting it again
                                    // here would replace that reason with this
                                    // one, which says nothing.
                                    if (!bridge.writeDownloadChunk(t, id, encode(step.value))) {
                                        reader.cancel();
                                        return;
                                    }
                                    // Yield between pieces: every bridge call
                                    // blocks this thread, so a large file would
                                    // otherwise freeze the editor for the whole
                                    // transfer.
                                    return new Promise(function(go) {
                                        setTimeout(go, 0);
                                    }).then(pump);
                                });
                            })();
                        }).catch(function(e) {
                            bridge.finishDownload(t, id, String((e && e.message) || e) || 'failed');
                        });
                        return true;
                    }
                };
            })();
            """.trimIndent(),
            null
        )
    }

    /**
     * Injects a BroadcastChannel relay into the WebView main page.
     *
     * Browser extensions run in a Web Worker, which has its own global scope and does
     * not see objects added to a page with addJavascriptInterface. This relay listens on
     * a BroadcastChannel in the page and forwards calls to AndroidBridge, which is what
     * lets an extension reach it at all.
     *
     * ⚠️ **Who can post here is every script on the workbench's origin, not the
     * bundled extension.** `product.json` carries no `webEndpointUrlTemplate`, and
     * without one the workbench starts the web extension host in a SAME-ORIGIN
     * iframe and says so (`console.warn("The web worker extension host is started
     * in a same-origin iframe!")` in `out/vs/code/browser/workbench/workbench.js`). A
     * `BroadcastChannel` is scoped by origin, so every web extension the user
     * installs from Open VSX shares this one. The token read below is no barrier
     * either: it is read out of `window.__vscodroid` by this script, on that same
     * origin, so anything that can post can also read it. Answers are posted back
     * onto the same channel, so every listener sees every reply, including replies
     * to commands somebody else asked for.
     *
     * What that means for what may be dispatched here, worked out per command
     * rather than assumed:
     *
     *  - The caller ALREADY has more than this channel gives it. A web extension
     *    can open a terminal and run anything as this app's uid, so every reading
     *    command here (`getRecentFolders`, `getStorageBreakdown`, `listSafMirrors`,
     *    `getSshPublicKey`, `listSshKeys`, `generateBugReport`) discloses what the
     *    caller could already read off the filesystem. They stay, and what changes
     *    instead is that they hand over no more than they must: `listSshKeys` no
     *    longer reports a key's comment, which is conventionally an email address.
     *  - Commands that put an Android surface on screen (`openFolderPicker`,
     *    `openToolchainSettings`, `showAboutDialog`) are visible and dismissible,
     *    and none of them changes anything on its own. They stay.
     *  - `generateSshKey` never overwrites a pair, and `clearCaches` deletes only
     *    regenerable caches. Neither loses the user's own work.
     *  - `openExternalUrl` is the one command that reaches outside the app at all,
     *    and it is the one that was narrowed: see `AndroidBridge.openExternalUrl`,
     *    which now refuses this app's own `vscodroid://callback`.
     *  - `openRecentFolder` carries the URI with the call, so the folder it opens
     *    is the caller's choice rather than the user's. What bounds it is the
     *    grant: the recent list a legitimate caller picks from is pruned of
     *    revoked grants before it is handed over
     *    (`SafStorageManager.getPersistedFolders`), and a URI this app holds no
     *    grant for is now refused with a notice instead of being answered with the
     *    system folder chooser. See [openRecentSafFolder].
     *  - `reclaimSafMirror` with `force` is the only command here that destroys
     *    user data, and its own documentation says the caller must have confirmed
     *    a modal saying so. A caller is exactly what cannot be asked to promise
     *    that, so [removeDeviceFolderCopy] now asks the user itself.
     */
    private fun injectBridgeRelay() {
        webView?.evaluateJavascript(
            """
            (function() {
                if (typeof AndroidBridge === 'undefined') return;
                if (window.__vscodroidRelayActive) return;
                window.__vscodroidRelayActive = true;
                var ch = new BroadcastChannel('vscodroid-bridge');
                // How an answer that could not be given while the caller waited
                // gets back to it. A bridge call does not return to JavaScript
                // until the Kotlin method has finished, and the thread it holds
                // is this page's own, so a method that walks the disk freezes the
                // workbench for as long as the walk. Those methods hand back at
                // once and Android posts the answer here against the same id the
                // caller sent; the extension already routes a reply by id.
                window.__vscodroidBridgeReply = function(id, ok, payload) {
                    ch.postMessage(ok
                        ? {id: id, ok: true, data: payload}
                        : {id: id, ok: false, error: payload});
                };
                ch.onmessage = function(e) {
                    var d = e.data;
                    var token = (window.__vscodroid || {}).authToken;
                    if (!token || !d || !d.cmd) return;
                    try {
                        var result;
                        if (d.cmd === 'openFolderPicker') {
                            AndroidBridge.openFolderPicker(token);
                            ch.postMessage({id: d.id, ok: true});
                        } else if (d.cmd === 'getRecentFolders') {
                            result = AndroidBridge.getRecentFolders(token);
                            ch.postMessage({id: d.id, ok: true, data: result});
                        } else if (d.cmd === 'openRecentFolder') {
                            AndroidBridge.openRecentFolder(token, d.uri);
                            ch.postMessage({id: d.id, ok: true});
                        } else if (d.cmd === 'getStorageBreakdown') {
                            // Answered later, by id, through the hook above. A
                            // non-empty answer HERE is a refusal decided before
                            // any work started, so it is posted as the error.
                            result = AndroidBridge.getStorageBreakdown(token, d.id);
                            if (result !== '') ch.postMessage({id: d.id, ok: false, error: result});
                        } else if (d.cmd === 'clearCaches') {
                            result = AndroidBridge.clearCaches(token, d.id);
                            if (result !== '') ch.postMessage({id: d.id, ok: false, error: result});
                        } else if (d.cmd === 'generateBugReport') {
                            result = AndroidBridge.generateBugReport(token);
                            ch.postMessage({id: d.id, ok: true, data: result});
                        } else if (d.cmd === 'openToolchainSettings') {
                            AndroidBridge.openToolchainSettings(token);
                            ch.postMessage({id: d.id, ok: true});
                        } else if (d.cmd === 'generateSshKey') {
                            result = AndroidBridge.generateSshKey(token, d.comment || '');
                            ch.postMessage({id: d.id, ok: true, data: result});
                        } else if (d.cmd === 'getSshPublicKey') {
                            result = AndroidBridge.getSshPublicKey(token);
                            ch.postMessage({id: d.id, ok: true, data: result});
                        } else if (d.cmd === 'listSshKeys') {
                            result = AndroidBridge.listSshKeys(token);
                            ch.postMessage({id: d.id, ok: true, data: result});
                        } else if (d.cmd === 'showAboutDialog') {
                            AndroidBridge.showAboutDialog(token);
                            ch.postMessage({id: d.id, ok: true});
                        } else if (d.cmd === 'openExternalUrl') {
                            // The only branch here whose bridge method can decline. Every
                            // other one either returns data or cannot fail in a way the
                            // caller could act on, which is why they post ok:true flatly.
                            // Posting ok:true for this one turned a blocked URL into a
                            // resolved promise, and the caller's error handler never ran.
                            //
                            // The reason comes from the bridge, which is the only
                            // side that knows it. This used to post one fixed sentence
                            // for every refusal, blaming a missing app even when the
                            // session token was stale or Android had refused the URL
                            // outright, and a user following that advice installs
                            // something that cannot help.
                            //
                            // Empty means opened. Anything else is the reason, so the
                            // comparison is against the empty string rather than a
                            // bare truthiness test, which would read backwards.
                            result = AndroidBridge.openExternalUrl(d.url, token);
                            ch.postMessage(result === ''
                                ? {id: d.id, ok: true}
                                : {id: d.id, ok: false, error: result});
                        } else if (d.cmd === 'listSafMirrors') {
                            result = AndroidBridge.listSafMirrors(token, d.id);
                            if (result !== '') ch.postMessage({id: d.id, ok: false, error: result});
                        } else if (d.cmd === 'reclaimSafMirror') {
                            // openExternalUrl's convention, now applying twice
                            // over. Empty from the call below means the removal
                            // was accepted and its outcome follows by id; the
                            // outcome itself is empty for a removal and a
                            // sentence for a refusal, and the Kotlin side decides
                            // which of the two it posted. Posting ok:true flatly
                            // for either would tell the user their disk had been
                            // freed when the removal was refused because the
                            // folder is still open.
                            result = AndroidBridge.reclaimSafMirror(
                                token, d.hash, d.force === true, d.id);
                            if (result !== '') ch.postMessage({id: d.id, ok: false, error: result});
                        } else {
                            // A command this chain does not know is answered rather
                            // than dropped. Without this the caller's promise died
                            // on its own deadline and reported "Bridge timeout: is
                            // the app running on Android?", accusing the platform
                            // of not being there, after five seconds or after two
                            // minutes for a storage command. It is also the exact
                            // failure of adding a bridge method and forgetting its
                            // relay branch, which is the moment a clear message is
                            // worth most.
                            ch.postMessage({
                                id: d.id, ok: false,
                                error: 'VSCodroid does not know the command ' + d.cmd
                            });
                        }
                    } catch(err) {
                        ch.postMessage({id: d.id, ok: false, error: String(err)});
                    }
                };
            })();
            """.trimIndent(),
            null
        )
    }

    /**
     * Registers the page-side consumer of the memory-pressure callback.
     *
     * `onTrimMemory` fires `window.__vscodroid?.onLowMemory?.(level)` and nothing
     * was listening, so this exists to give that call somewhere to land.
     *
     * It records the level and does nothing else, and the two things it used to do
     * are worth naming so neither comes back. It called `gc()`, which does not
     * exist in a WebView: the function is behind V8's `--expose-gc` and the branch
     * was dead on every device. And it walked `performance.getEntries()` and
     * revoked every entry whose name began with `blob:`, which is a list of blob
     * resources the page has FETCHED rather than ones this app created: the
     * shipped workbench keeps its own `_blobUrlCache` and hands the same URL back
     * on a later load, so revoking them broke the next load of an image, a media
     * element or a worker built from one. `TRIM_MEMORY_BACKGROUND` maps to
     * critical, so that fired on ordinary backgroundings whenever the system was
     * reclaiming, and the damage outlived the pressure. Nothing handed those URLs
     * over and nothing asked whether they were still in use.
     *
     * The one revocation this app does own is in [injectDownloadCapture], which
     * tracks the URLs it holds and releases them itself.
     */
    private fun injectMemoryPressureHandler() {
        webView?.evaluateJavascript(
            """
            (function() {
                if (window.__vscodroidMemoryHandlerActive) return;
                window.__vscodroidMemoryHandlerActive = true;
                window.__vscodroid = window.__vscodroid || {};
                window.__vscodroid.onLowMemory = function(level) {
                    console.warn('[VSCodroid] Memory pressure: level=' + level);
                };
            })();
            """.trimIndent(),
            null
        )
    }

    /**
     * Shows the About dialog. Called from AndroidBridge via JS.
     */
    fun showAboutDialog() {
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
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.about_title))
            .setMessage(getString(R.string.about_body, version, disclaimer))
            .setPositiveButton(getString(R.string.dialog_ok), null)
            .setNeutralButton(getString(R.string.about_licenses)) { _, _ -> showLicensesDialog() }
            .setNegativeButton(getString(R.string.about_privacy_policy)) { _, _ ->
                startActivity(Intent(Intent.ACTION_VIEW, "https://rmyndharis.github.io/VSCodroid/privacy-policy.html".toUri()))
            }
            .show()
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
    private fun showLicensesDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.licenses_title))
            .setView(scrollableNotice(Notices.read { assets.open(it) }))
            .setPositiveButton(getString(R.string.dialog_ok), null)
            .setNegativeButton(getString(R.string.licenses_full_texts)) { _, _ ->
                showLicenseTextsDialog()
            }
            .setNeutralButton(getString(R.string.licenses_source_code)) { _, _ ->
                startActivity(Intent(Intent.ACTION_VIEW, "https://github.com/rmyndharis/VSCodroid".toUri()))
            }
            .show()
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
    private fun showLicenseTextsDialog() {
        val names = Notices.LICENSE_TEXTS.keys.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.licenses_full_texts))
            .setItems(names) { _, which ->
                val name = names[which]
                AlertDialog.Builder(this)
                    .setTitle(name)
                    .setView(
                        scrollableNotice(
                            Notices.readOne(Notices.LICENSE_TEXTS.getValue(name)) { assets.open(it) }
                        )
                    )
                    .setPositiveButton(getString(R.string.dialog_ok), null)
                    .show()
            }
            .show()
    }

    /** A long document in a scroller, laid out the same way wherever it is shown. */
    private fun scrollableNotice(document: String): ScrollView {
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
        wv.destroy()

        val newWebView = WebView(this)
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
        Logger.i(tag, "Extension callback relay received")
        val key = JSONObject.quote("vscode-web.url-callbacks[$requestId]")
        val value = JSONObject.quote(callbackUri)
        webView?.evaluateJavascript("""
            (function() {
                try {
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
                } catch(e) {
                    console.error('[VSCodroid] Callback relay error:', e);
                }
            })();
        """.trimIndent(), null)
    }

    /**
     * Shows a dialog if the app crashed in a previous session.
     */
    private fun checkPreviousCrash() {
        if (!CrashReporter.hasPendingCrash()) return
        val lastCrash = CrashReporter.getLastCrash() ?: return
        // Truncate for display
        val preview = if (lastCrash.length > 500) lastCrash.take(500) + "\n..." else lastCrash
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.crash_title))
            .setMessage(getString(R.string.crash_message, preview))
            .setPositiveButton(getString(R.string.crash_dismiss)) { _, _ -> CrashReporter.clearCrashLogs() }
            .setNeutralButton(getString(R.string.crash_copy_report)) { _, _ ->
                lifecycleScope.launch {
                    // Off the main thread: generateBugReport reads three crash files
                    // and all of server.log under the lock a rotation holds, and a
                    // button fires on the main thread, which is the one screen shown
                    // after a crash. The clipboard write, the sensitive flag and the
                    // toast come back to Main with the result.
                    val report = withContext(Dispatchers.IO) {
                        CrashReporter.generateBugReport(this@MainActivity)
                    }
                    val clipboard = getSystemService(android.content.ClipboardManager::class.java)
                    val clip = android.content.ClipData.newPlainText("VSCodroid Bug Report", report)
                    // Marked sensitive before it goes anywhere, because of what this
                    // clip holds: [CrashReporter.generateBugReport] gathers the last
                    // 200 lines of server output and the text of the three most recent
                    // crash logs. Android 13 and later draw a preview of whatever is
                    // copied, so without this a crashing session's log is rendered
                    // over the editor for anyone looking at the screen, and the
                    // clipboard is readable by every app the user pastes into next.
                    // The preview is suppressed; the paste is unaffected.
                    //
                    // Deliberately not applied to the editor's own copy in
                    // [ClipboardBridge]. There the preview confirms what was copied,
                    // which is the whole affordance, and the text is a line the user
                    // selected rather than a log they never read.
                    clip.description.extras = android.os.PersistableBundle().apply {
                        putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true)
                    }
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(this@MainActivity, getString(R.string.crash_report_copied), Toast.LENGTH_SHORT).show()
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
            .show()
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
    private fun checkStorageHealth() {
        if (!StorageManager.isStorageLow(this)) return
        val available = StorageManager.formatSize(StorageManager.getAvailableStorage(this))
        Toast.makeText(
            this,
            getString(R.string.storage_low_warning, available),
            Toast.LENGTH_LONG
        ).show()
        Logger.w(tag, "Storage low: $available available")
    }

    /**
     * Warns when the installed WebView is older than the version this project
     * is tested against.
     *
     * `minSdk` is 33, which ships Chrome 105 or newer, so a stock device passes
     * and sees nothing. What this catches are the cases a user cannot diagnose:
     * a System WebView disabled or downgraded by hand or by an OEM image, a
     * device where WebView updates are blocked so the component ages while the
     * OS does not, and emulators or custom ROMs carrying an older WebView than
     * their API level implies. On those the workbench loads against something it
     * was never tested on, and the failure is missing CSS or a blank editor
     * rather than a clean refusal. `onReceivedError` announces a TLS handshake
     * failure and otherwise only logs, so nothing else would reach the user.
     *
     * It warns and continues rather than refusing to start. The floor is a
     * tested one, not a hard incompatibility, and an editor that degrades is
     * worth more than one that will not open. A version that cannot be read is
     * not treated as an old one; see [WebViewVersion.majorVersionOf].
     */
    private fun checkWebViewVersion() {
        val pkg = WebView.getCurrentWebViewPackage()
        val version = pkg?.versionName
        if (!WebViewVersion.isBelowMinimum(version)) {
            Logger.i(tag, "WebView: ${pkg?.packageName ?: "unknown"} ${version ?: "unknown version"}")
            return
        }
        Toast.makeText(
            this,
            getString(
                R.string.webview_below_minimum,
                version,
                WebViewVersion.MINIMUM_CHROME_MAJOR.toString(),
            ),
            Toast.LENGTH_LONG
        ).show()
        Logger.w(tag, "WebView $version is below the tested minimum ${WebViewVersion.MINIMUM_CHROME_MAJOR}")
    }


    companion object {

        /**
         * How long a navigation this app started stays recognisable as its own.
         *
         * The `beforeunload` callback runs inside the load it belongs to, so
         * this only has to cover the moment between asking the WebView to go and
         * the page answering. Generous rather than tight, because being late
         * here costs one dialog the user did not need, while being early costs
         * a dialog they did.
         */
        private const val APP_NAVIGATION_WINDOW_MS = 10_000L

        /** The preferences file `PortFinder` and `SplashActivity` already use. */
        private const val WORKSPACE_PREFS = "vscodroid"

        /** The workspace to reopen when there is no page left to read one from. */
        private const val KEY_LAST_FOLDER = "last_workspace_folder"

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
         * Every mirror this process has put a watcher on, whether or not one is
         * on it now.
         *
         * Never cleared, and that is the point rather than an omission.
         * [SafStorageManager.stopFileWatcher] stops the observers, but the
         * engine waits only two seconds for the write-back worker to drain and
         * then leaves it running rather than discarding writes the user expects
         * on the device. So a folder the app considers closed can still have a
         * thread streaming bytes out of its mirror, and every such write opens
         * the device document with `"wt"`, which truncates at open: deleting the
         * mirror underneath one empties the user's file rather than leaving it
         * alone.
         *
         * A drain only ever touches the mirror it was started for, so refusing
         * every mirror this process has watched is what puts it out of reach
         * without needing to know whether a particular drain is still alive. The
         * cost is that removing a folder opened this session needs a restart,
         * which is a sentence the user can act on.
         *
         * In the companion, and that scope is load-bearing rather than a
         * placement preference. The drain this guards belongs to the process,
         * not to the Activity that started it: a rotation, a WebView recreation,
         * or any other Activity restart hands the replacement a fresh instance
         * while the worker is still streaming bytes out of the same mirror. Held
         * as an instance field, the set would be empty in that replacement and
         * would answer "never watched" for exactly the mirror it exists to put
         * out of reach, so an Activity restart would grant the removal that
         * [SafStorageManager.reclaimRefusal] tells the user needs a restart of
         * VSCodroid. That refusal is also the one `force` cannot bypass, so
         * nothing further down would catch it.
         *
         * Concurrent because it is written on the UI thread and read on the
         * bridge thread, and a set that answers the guard cannot be one the
         * guard may see mid-write.
         */
        private val mirrorsWatchedThisProcess: MutableSet<String> =
            java.util.concurrent.ConcurrentHashMap.newKeySet()

    }
}

/** Run health check if backgrounded longer than this. */
internal const val HEALTH_CHECK_THRESHOLD_MS = 60_000L   // 1 minute

/** Force page reload if backgrounded longer than this. */
internal const val FORCE_RELOAD_THRESHOLD_MS = 300_000L  // 5 minutes

/**
 * How long a forced device-folder removal waits for the user's answer.
 *
 * Not a guess about how long a person takes to read one sentence: it is the point
 * at which "still deciding" stops being the explanation and "nobody is looking at
 * this screen" becomes one. The wait is on the bridge's single disk-work thread,
 * so everything else queued on it waits too, and the bundled extension gives a
 * storage command two minutes; this side gives up well inside that, so the
 * failure is reported as a refusal by the layer that knows why rather than as a
 * caller's deadline expiring. See `MainActivity.confirmForcedRemoval`.
 */
private const val FORCED_REMOVAL_CONFIRM_MS = 45_000L

// Severities a trim level maps to, logged and handed to the page. Words rather
// than numbers so that nothing downstream is tempted to compare them with >=,
// which is the defect this replaced.
internal const val PRESSURE_NONE = "none"
internal const val PRESSURE_MODERATE = "moderate"
internal const val PRESSURE_CRITICAL = "critical"

/**
 * What a trim level actually says about memory, which is not what comparing
 * it says.
 *
 * Android's constants are not ordered by severity. `TRIM_MEMORY_UI_HIDDEN`
 * is 20 and sits above `TRIM_MEMORY_RUNNING_CRITICAL` at 15, but it does not
 * describe memory at all: it means "your UI is no longer visible" and
 * arrives on every single backgrounding, on a device with gigabytes free.
 * A `>=` comparison therefore read an ordinary app switch as worse than a
 * genuine critical warning, and while the process monitor still killed idle
 * language servers on that word, every one of them (which, after five minutes
 * in another app, is all of them) was killed on every backgrounding.
 *
 * So this maps rather than compares, and the next constant Android adds
 * cannot clear a threshold by accident. Raising the number would have looked
 * like a fix and been wrong again at the next value.
 */
@Suppress("DEPRECATION")
internal fun memoryPressureOf(level: Int): String = when (level) {
    // Critical while running, and the cached levels, which all mean the
    // system is reclaiming and this process is a candidate; the page is told
    // so it can shrink its own footprint.
    TRIM_MEMORY_RUNNING_CRITICAL,
    TRIM_MEMORY_BACKGROUND,
    TRIM_MEMORY_MODERATE,
    TRIM_MEMORY_COMPLETE -> PRESSURE_CRITICAL

    // Reported as moderate, exactly as before: 10 sat below the old
    // threshold of 15.
    TRIM_MEMORY_RUNNING_LOW -> PRESSURE_MODERATE

    // TRIM_MEMORY_UI_HIDDEN (20) lands here, with TRIM_MEMORY_RUNNING_MODERATE
    // (5). The first is not about memory at all; the second is the mildest
    // hint Android has. This is the only line that changes behaviour, and it
    // changes it for exactly the value that was wrong.
    else -> PRESSURE_NONE
}

/**
 * Whether an incoming URI is the extension auth callback relay.
 *
 * Both halves are load-bearing, and the manifest is why. The VIEW filter that
 * delivers this is exported and BROWSABLE, so any installed app and any web page
 * the user taps can fire it, and what rides in the `data` parameter is written
 * into the workbench's `localStorage`. Relaxed to either half on its own
 * (`vscodroid://` with any host, or any scheme pointed at `callback`), the relay
 * starts accepting shapes the flow it exists for never sends.
 *
 * Taking the two parts rather than the Uri is what makes this reachable: `Uri`
 * is abstract with a private constructor, so neither building one nor mocking
 * one works in a plain JVM test, and the decision here is about two strings.
 */
internal fun isExtensionCallback(scheme: String?, host: String?): Boolean =
    scheme == "vscodroid" && host == "callback"

/**
 * The workbench request id a callback payload names, or null if it names none.
 *
 * The payload is what `callback.html` builds before it navigates to the intent:
 * `encodeURIComponent(JSON.stringify({ id, uri }))`, where `id` is the
 * `vscode-reqid` the page was loaded with. `Uri.getQueryParameter` undoes the
 * encoding, so what arrives here is the JSON object itself.
 *
 * Parsed rather than pattern-matched, and the difference is not style. A reader
 * that searched the text for a number would find one in the `uri` half as
 * readily as in the `id` half, and the value decides whether an exported entry
 * point is answered. Parsing is also what makes malformed input a null instead
 * of a guess: anything on the device can fire this intent, so the payload is
 * attacker-shaped by construction and the parse has to be total.
 *
 * Takes the already-extracted parameter rather than the `Uri`, for the reason
 * [isExtensionCallback] takes two strings: `Uri` is abstract with a private
 * constructor, so it can be neither built nor mocked in a plain JVM test, and
 * the decision here is about one string.
 */
internal fun callbackRequestId(data: String?): String? {
    if (data.isNullOrEmpty()) return null
    return try {
        JSONObject(data).optString("id").ifEmpty { null }
    } catch (e: JSONException) {
        null
    }
}

/**
 * The address a callback payload carries, as the JSON text the workbench stores.
 *
 * The workbench collects a callback by reading
 * `vscode-web.url-callbacks[<id>]` and parsing it, and what `callback.html`
 * writes there is `JSON.stringify(uri)`. So the value this returns is that same
 * text, produced from the object the payload carries rather than from the
 * payload's own bytes: the page then receives a finished literal and never
 * parses anything, which is what keeps the id the timing gate approved and the
 * id the value is written under from being decided by two different readers.
 *
 * Only an object counts. `callback.html` builds `uri` as
 * `{ scheme, authority, path?, query?, fragment? }` and can build nothing else,
 * so a payload whose `uri` is a string, a number or absent is not the message
 * this relay is for, and answering null leaves it in the branch that injects
 * nothing. That direction is deliberate: the filter is exported and BROWSABLE,
 * so the payload is attacker-shaped by construction and every reading of it has
 * to be total.
 *
 * Takes the already-extracted parameter rather than the `Uri`, for the reason
 * [callbackRequestId] does: `Uri` is abstract with a private constructor, so it
 * can be neither built nor mocked in a plain JVM test.
 */
internal fun callbackUriJson(data: String?): String? {
    if (data.isNullOrEmpty()) return null
    return try {
        JSONObject(data).optJSONObject("uri")?.toString()
    } catch (e: JSONException) {
        null
    }
}

/**
 * What binding to an already-running service should do about it.
 *
 * Extracted so the decision is a value rather than a shape in the source.
 * `ServerReadinessCallSiteTest` reads `MainActivity.kt` and checks *which* method
 * is called, which catches putting `isServerRunning` back and cannot catch
 * calling the right method and ignoring the answer -- both call sites were
 * mutated that way with the whole suite still green. A branch that returns one of
 * these can be tested for what it decides.
 */
internal sealed interface BindDecision {
    /** The server said something about its start that predates this binding. */
    data class ShowNotice(val message: String) : BindDecision

    /**
     * The server has given up, and said so before this activity bound.
     *
     * Separate from [ShowNotice] because the two need different treatment and
     * the difference is the whole point: a slow start may still come up, so a
     * toast over the loading page is honest. A server that has stopped trying
     * never will, and leaving the loading page up says "Starting server..." for
     * as long as the user waits, with no way off it.
     */
    data class ShowGaveUp(val message: String) : BindDecision

    /** Serving, on a port worth navigating to. */
    data class Load(val port: Int) : BindDecision

    /** Not serving yet; `onServerReady` will arrive if it comes up. */
    object Wait : BindDecision
}

/**
 * Whether [url] is a page the local server served, as opposed to one this app
 * drew itself.
 *
 * `onPageFinished` reports that a main-frame load finished, which is not the
 * same question and differs exactly where it costs something: the loading page
 * and the server-gave-up page are `loadData` documents with no origin, and
 * treating either as the workbench injects the session token into a page that
 * cannot use it and marks the app ready to receive an OAuth callback it cannot
 * consume.
 *
 * The test is host and port together, the same pair
 * [VSCodroidWebViewClient.isLocalhost] uses, because the port is what ties a
 * page to this server rather than to any loopback listener: binding one needs
 * no permission on Android, so the host alone says nothing about who answered.
 *
 * Parsed with `java.net.URI` rather than `Uri.parse`, so the decision can be
 * exercised by a plain JVM test. `Uri.parse` is a framework method that answers
 * null off-device, which would make every case here pass for the wrong reason.
 * The two agree on what matters: both give a null host for the `loadData`
 * documents this exists to exclude, and `URI` throws on the malformed ones,
 * which is caught below and read the same way.
 */
internal fun isWorkbenchUrl(url: String?, port: Int): Boolean {
    if (url == null || port <= 0) return false
    // Named so they collide with nothing else in this file. `LogTaint`, which
    // guards this file against logging the connection token or a device folder's
    // tree URI, follows taint by identifier name across the whole file: binding
    // the parsed URL to `uri` or its host to `host` would mark two unrelated
    // locals as sensitive and report honest log statements as leaks. A `Uri`
    // written as a type is a seed there too, so `val uri: Uri` is the same
    // mistake spelled differently. The conservatism is the point, so the alias
    // is what has to go.
    val parsed = runCatching { java.net.URI(url) }.getOrNull() ?: return false
    val hostName = parsed.host ?: return false
    return (hostName == "127.0.0.1" || hostName == "localhost") && parsed.port == port
}

/**
 * The notice is read first, and that ordering is load-bearing: it may be terminal
 * or it may be a slow start still coming up, and in both cases the server is not
 * serving, so it must not be shadowed by a port that happens to look plausible.
 *
 * Which of the two it is then decides the answer: a terminal notice gives
 * [BindDecision.ShowGaveUp] and any other gives [BindDecision.ShowNotice],
 * because a server that has stopped trying needs the loading page replaced,
 * while a slow start may still come up under a toast.
 *
 * [ready] is the health probe's own finding, never process liveness. A process is
 * alive from the instant it is spawned and stays alive through the seconds before
 * its port is bound and through a whole post-crash restart; navigating on that
 * points the WebView at nothing, and `onReceivedError` only logs a refused
 * connection, so the connection-refused page it produces is never cleared.
 */
internal fun bindDecision(notice: StartupNotice?, port: Int, ready: Boolean): BindDecision = when {
    notice != null && notice.terminal -> BindDecision.ShowGaveUp(notice.message)
    notice != null -> BindDecision.ShowNotice(notice.message)
    port > 0 && ready -> BindDecision.Load(port)
    else -> BindDecision.Wait
}

/**
 * The shortest gap between two redraws of the folder-sync dialog.
 *
 * Ten a second is already faster than anyone reads, and the count is what the
 * number is really about: the engine reports once per file, so a folder of
 * twenty thousand posted twenty thousand relayouts onto the main looper.
 */
internal const val SYNC_PROGRESS_INTERVAL_MS = 100L

/**
 * Whether the folder-sync dialog should be redrawn for this file.
 *
 * The last file always is, whatever the clock says: it is the one reading the
 * user is owed (the counts have to end matching) and the one a throttle would
 * otherwise be most likely to swallow, since it arrives right after its
 * predecessor.
 */
internal fun syncProgressIsDue(done: Int, total: Int, sinceLastMs: Long): Boolean =
    done >= total || sinceLastMs >= SYNC_PROGRESS_INTERVAL_MS

/**
 * What to call [dir] in a notice about it.
 *
 * A mirror root is named after a digest of the tree URI, twelve hex characters,
 * so a notice printing the directory name says "a1b2c3d4e5f6" where the folder's
 * own name belongs. The stranded-upload pass is what reaches that: it has the
 * whole mirror in hand rather than a directory somebody made, and it was the one
 * notice naming a hash.
 *
 * The ROOT only, which is why this is not
 * [SafStorageManager.folderForOpenedPath]. That one answers for anything inside a
 * mirror as well, by design, and here it would rename a directory created in the
 * editor after the device folder containing it: "four files in Documents did not
 * reach the device" for a shortfall that was entirely inside `src/generated`.
 * Matched on the directory NAME rather than the whole path, since the name is the
 * digest and is what the manager itself keys mirrors by.
 *
 * [folders] is the recent list, pruned of revoked grants before it is handed
 * over, so a folder whose grant the user has taken back has no entry and keeps
 * the directory name. That is the honest answer at that point: the app no longer
 * knows what the folder was called.
 *
 * Top-level and pure for the reason the notices are wired out of locals: anything
 * they call on the Activity captures it, and the write-back worker they belong to
 * outlives the screen.
 */
internal fun mirrorDisplayName(folders: List<SafFolderInfo>, dir: File): String =
    folders.firstOrNull { File(it.mirrorPath).name == dir.name }?.displayName ?: dir.name

/**
 * How long a renderer crash counts towards a loop, and how many are allowed
 * inside that time.
 *
 * A minute and three, which is a loop by any reading: a crashed renderer is
 * rebuilt and the workbench loaded into it at once, so a fourth crash inside the
 * minute says the load is what keeps killing it. A slower repeat (a page that
 * dies when one particular file is opened, an hour apart) falls out of the window
 * and goes on being recovered from, which is the right treatment for it.
 */
internal const val CRASH_LOOP_WINDOW_MS = 60_000L
internal const val CRASH_LOOP_CRASHES = 3

/**
 * Records a renderer crash and answers whether recovering from them has become a
 * loop.
 *
 * [times] is the caller's record and is MUTATED: readings older than the window
 * are dropped and [now] is added, so it stays bounded by the window rather than
 * growing for the life of the Activity.
 *
 * A count with no window would eventually refuse a recovery to a session that had
 * three unrelated crashes in a day, and a window with no count would never refuse
 * one at all. See `MainActivity.recreateWebView`, which is the only caller and
 * which cannot be driven from a JVM test.
 */
internal fun crashLoopReached(
    times: ArrayDeque<Long>,
    now: Long,
    windowMs: Long = CRASH_LOOP_WINDOW_MS,
    limit: Int = CRASH_LOOP_CRASHES,
): Boolean {
    while (times.isNotEmpty() && now - times.first() > windowMs) times.removeFirst()
    times.addLast(now)
    return times.size > limit
}

/**
 * Whether returning from the background should touch the page at all.
 *
 * [ready] is nullable because the service may not be bound, and null is not
 * evidence of anything: it must not be read as permission to reload. Only a
 * probe that actually found the server serving is.
 */
internal fun shouldActOnResume(ready: Boolean?, backgroundedAt: Long, serverPort: Int): Boolean =
    backgroundedAt != 0L && serverPort != 0 && ready == true

/** What returning from the background should do to the page. */
internal enum class ResumeAction {
    /** Short absence. The WebSocket survives those. */
    NOTHING,

    /** Long enough that the connection may be dead. Ask, reload only if it is. */
    PROBE_CONNECTION,

    /** Long enough that stale state is near certain. Reload unconditionally. */
    RELOAD,
}

/**
 * Whether the page may be reloaded on the way back from the background.
 *
 * [signInPending] outranks the reload, and that ordering is the point of this
 * function existing. The two conditions are not independent: a sign-in that takes
 * more than five minutes is a sign-in that went through a second factor or an
 * organisation's consent screen, which is the ordinary case rather than the
 * exotic one, so the threshold written for a stale WebSocket lines up almost
 * exactly with the moment a callback is being delivered. The workbench holds the
 * requests it is waiting on in memory, so the reload discards them and the
 * sign-in that had just succeeded is lost with no error anywhere.
 *
 * Downgraded to the probe rather than dropped, and that is the whole of what a
 * pending sign-in buys. The absence really was long enough for the connection to
 * have died, so answering it with nothing leaves the page in the state the
 * five-minute rule exists to repair -- and nothing would come back to it, because
 * this decision is only ever made on the way in from the background and each
 * arrival is judged on its own absence. The probe is the strongest answer that is
 * safe here: it only reloads when IndexedDB is already unusable, and a page in
 * that state has nothing left to collect a callback with.
 *
 * [fileChooserPending] outranks both, and gets the stronger answer rather than
 * the probe. The difference from a sign-in is that the answer is already
 * arriving: the picker is another app, so the browse *is* the absence, and the
 * chosen file is handed to this same document moments after this decision. The
 * probe would not be enough, on two counts. It reloads the page from JS when
 * IndexedDB is unusable, which discards the selection just as the forced reload
 * does; and it starts at one minute, where browsing storage for longer than that
 * is the ordinary case rather than the exotic one.
 *
 * The cost is a page left possibly stale, and it is bounded: the answer is being
 * delivered now, and the next trip through the background judges the page afresh
 * on its own absence.
 *
 * [savePickerPending] is the same absence reached by the other picker and gets
 * the same answer. Saving a download opens the create-document picker, which is
 * another app, and the answer comes back into this same document: the
 * coordinator asks the page for the bytes only after the destination is chosen,
 * so a reload here leaves the user having named a file that can no longer be
 * read. The default is false so that this stays one argument per signal at the
 * call site rather than a boolean nobody can see; the call site is checked by a
 * test for naming it, because a dropped argument is exactly what the default
 * would otherwise hide.
 *
 * An earlier shape of this held the reload for later by putting the caller's
 * backgrounded reading back. That reading is written afresh by `onStop`, which
 * Android always delivers before the next `onStart`, so the restored value was
 * overwritten before its only consumer could read it and the reload was dropped
 * rather than deferred.
 */
internal fun resumeAction(
    bgMs: Long,
    signInPending: Boolean,
    fileChooserPending: Boolean,
    savePickerPending: Boolean = false,
): ResumeAction = when {
    fileChooserPending || savePickerPending -> ResumeAction.NOTHING
    bgMs > FORCE_RELOAD_THRESHOLD_MS && signInPending -> ResumeAction.PROBE_CONNECTION
    bgMs > FORCE_RELOAD_THRESHOLD_MS -> ResumeAction.RELOAD
    bgMs > HEALTH_CHECK_THRESHOLD_MS -> ResumeAction.PROBE_CONNECTION
    else -> ResumeAction.NOTHING
}

/**
 * Whether the launch a callback belongs to is still inside its window.
 *
 * Shape is all `isExtensionCallback` can judge, and shape is what anything on
 * the device can produce: the VIEW filter is exported and BROWSABLE. The value
 * that rides in is written into the workbench's storage under an id the
 * workbench hands out as a counter from one, so the id an unsolicited caller
 * would have to name is not a secret and never was. What it cannot supply is a
 * sign-in this app started, which is the only thing that separates a return from
 * an invention.
 *
 * That match is made before this: the caller looks the callback's own request id
 * up in [com.vscodroid.bridge.AuthTabWindow] and gets the reading of the launch
 * that carried it, or nothing. This half answers only the remaining question,
 * which is how long ago that was.
 *
 * Timing, not identity, for the rest of it: the legitimate sender is a browser,
 * so there is no caller identity here that could be checked either.
 *
 * `openedAtMillis == 0` means no launch, which is what a caller with no reading
 * to pass would produce. The lower bound on the elapsed time is not redundant
 * with it: the caller passes a monotonic clock, and a negative reading would mean
 * the two came from different boots, which is not an elapsed time at all.
 *
 * Takes its clock readings rather than reading them, for the reason
 * `isExtensionCallback` takes two strings -- `SystemClock` is not answerable in
 * a plain JVM test, and the decision here is arithmetic on three numbers.
 */
internal fun authCallbackIsExpected(
    openedAtMillis: Long,
    nowMillis: Long,
    windowMillis: Long
): Boolean = openedAtMillis != 0L && (nowMillis - openedAtMillis) in 0..windowMillis

/**
 * The workbench URL for a folder, with the connection token when there is one.
 *
 * One expression, and that is the point of it being a function. The call site
 * used to build two, the URL it loaded and a token-free twin it logged, so the
 * token stayed out of logcat only for as long as nobody collapsed them, which is
 * what a merge does and what anyone wanting the real navigation URL in the log
 * would do deliberately. With a single URL there is nothing to keep in step:
 * the log statement redacts, and [com.vscodroid.webview.redactToken] keys on
 * `tkn=`, which is the parameter this builds.
 *
 * That coupling is the fragile part and it is why this is testable at all. A
 * later rename of the parameter would leave the redactor matching nothing and
 * the log line looking untouched.
 *
 * An empty or absent token yields the bare URL rather than `tkn=`. The server
 * answers that with "Forbidden.", the caller says so, but a page the user can
 * retry beats no page, and sending an empty token would be indistinguishable
 * from sending a wrong one.
 */
internal fun workbenchUrl(
    port: Int,
    folderPath: String?,
    token: String?,
    isFile: (String) -> Boolean = { File(it).isFile },
): String {
    // The name is not enough, and [folderOpenTarget] already says why: a
    // DIRECTORY spelled `*.code-workspace` is a workspace the workbench cannot
    // read, and it answers one of those with an empty window and no message. That
    // exclusion was applied where the picker chooses a target and not here, where
    // the URL is actually built, so such a directory still went out as
    // `?workspace=`, and it did not stay a one-off: [navigateToFolder] remembers
    // the folder before it builds the URL, so every later launch reopened the same
    // empty window. What the URL itself costs is separate and quieter:
    // [folderFromUrl] answers null for it, so a folder the workbench opens on its
    // own is never adopted.
    //
    // The stat is behind the suffix test, so an ordinary folder pays nothing; the
    // predicate is passed for the reason every other predicate in this file is,
    // that these paths do not exist on a JVM test machine.
    //
    // A null path is the third thing the workbench can be showing, and it is a
    // state rather than a missing argument: the user closed the folder. The
    // workbench spells that `?ew=true`, and it has to be spelled here so that a
    // cold start restoring it goes through the one builder, with the token. The
    // token is not optional on that path: a fresh process has no `vscode-tkn`
    // cookie, and the server answers a request without one with "Forbidden."
    // Sending it is safe because the server strips `tkn` and redirects with every
    // other query parameter intact.
    val query = when {
        folderPath == null -> "ew=true"
        folderPath.endsWith(WORKSPACE_FILE_SUFFIX) && isFile(folderPath) ->
            "workspace=${Uri.encode(folderPath)}"
        else -> "folder=${Uri.encode(folderPath)}"
    }
    val base = "http://127.0.0.1:$port/?$query"
    return if (token.isNullOrEmpty()) base else "$base&tkn=${Uri.encode(token)}"
}

/** What the workbench calls a multi-root workspace, in the only place it is spelled. */
internal const val WORKSPACE_FILE_SUFFIX = ".code-workspace"

/**
 * What a workbench URL has open, whether that is a folder or a workspace.
 *
 * The workbench navigates itself, and it names the two cases with different
 * query parameters: `folder` for a directory and `workspace` for a
 * `.code-workspace` file. Reading only `folder` is what made a workspace
 * invisible to this side, and the cost was not that the URL looked wrong. It was
 * that [MainActivity.adoptWorkbenchFolder] never ran, so a workspace opened out
 * of a device-folder mirror got no write-back watcher and every edit stayed in
 * the mirror, and that the workspace was not remembered, so the next launch
 * reopened the default projects directory instead.
 *
 * Each branch stats what it reads, and for the kind of thing it is. A `folder`
 * naming a file and a `workspace` naming a directory are both URLs nothing
 * builds; refusing them keeps a dead or wrong path from pinning the WebView,
 * which is what the directory test has always been for.
 *
 * The predicates are passed rather than called because `File` is unavailable in
 * a plain JVM test, the same reason [rememberedFolderToReopen] takes its own.
 */
internal fun workbenchTarget(
    folder: String?,
    workspace: String?,
    isDirectory: (String) -> Boolean,
    isFile: (String) -> Boolean,
): String? = folder?.takeIf(isDirectory) ?: workspace?.takeIf(isFile)

/**
 * The directory a target stands in: itself for a folder, its parent for a workspace.
 *
 * Only the resource interceptor wants this. It publishes the open workspace as a
 * resource root, and [com.vscodroid.webview.resourceRootsInForce] matches a root
 * by path prefix, so a root that is a single `.code-workspace` file matches only
 * that file and every resource beside it is refused. The statically published
 * roots cover the mirrors and projects trees, so this is the difference only for
 * a workspace held outside both, which is exactly the case nothing else covers.
 *
 * The other two readers of the field want the prefix and get it either way:
 * `folderForOpenedPath` and `mirrorNameFor` both reduce a path to the mirror
 * holding it, and a file inside a mirror reduces the same as its directory does.
 */
internal fun workspaceDirectoryInForce(
    path: String?,
    isFile: (String) -> Boolean = { File(it).isFile },
): String? =
    // Same file test as [workbenchUrl], and here it narrows a published resource
    // root rather than a URL. A DIRECTORY spelled `*.code-workspace` reduced to
    // its parent, so the root published for it covered every sibling of the folder
    // the user opened. Reducing is right for a workspace FILE, whose siblings are
    // the workspace's own content; for a directory the folder itself is the root.
    if (path != null && path.endsWith(WORKSPACE_FILE_SUFFIX) && isFile(path)) {
        File(path).parent
    } else {
        path
    }

/**
 * The URL to reload when the workbench had the folder closed, or null.
 *
 * A closed folder is the third thing the workbench can be showing, and it is the
 * one the folder chain cannot express: [workbenchTarget] answers a path or
 * nothing, and "no folder, deliberately" is not a path. So every re-navigation
 * over a closed folder fell through to the remembered folder and put the user
 * back into the workspace they had just closed. `handleResumeFromBackground`
 * already sidesteps this by calling `reload()` rather than rebuilding a URL, and
 * this is the same answer for the paths that do rebuild.
 *
 * Returned verbatim and without a token. The workbench was already running, so
 * the server has turned the token into a cookie that outlives this by a week,
 * which is the reasoning the resume path states in full. A `folder` or
 * `workspace` URL is deliberately NOT returned: those the folder chain can name,
 * and it rebuilds them with a fresh token rather than reloading a stripped one.
 *
 * What makes a URL ours is asked of [workbenchUrl] rather than spelled again
 * here. That keeps the host in exactly one expression, which is the affordance
 * `the workbench URL is assembled in exactly one place` exists to protect: a
 * second spelling is what lets the string loaded drift from the string logged,
 * and it caught this function written the obvious way.
 */
internal fun emptyWindowUrl(url: String?, port: Int): String? {
    if (url == null) return null
    val ours = workbenchUrl(port, null, null).substringBefore('?')
    if (!url.startsWith(ours)) return null
    val query = url.substringAfter('?', "")
    if (query.isEmpty()) return null
    val closed = query.split('&').any {
        it.substringBefore('=') == "ew" && it.substringAfter('=', "") == "true"
    }
    return if (closed) url else null
}

/**
 * What to open once a device folder has been granted and synced.
 *
 * The Android picker is `ACTION_OPEN_DOCUMENT_TREE` and returns a directory,
 * never a file, so a workspace on device storage can only ever be reached
 * through the folder holding it. Nothing joined the two: the folder opened as a
 * folder, and the `.code-workspace` sitting in it was reachable only by knowing
 * to find the file in the explorer and press the button on it. That is the gap
 * behind "I cannot find a way to open an existing workspace", and it is the
 * shell's to close, because desktop VS Code offers this from code the browser
 * workbench does not carry (no `contains a workspace file` string exists in the
 * bundle).
 *
 * Exactly one, at the top level, or nothing. Two is a guess, and guessing wrong
 * is worse than opening the folder the user actually chose, from which either is
 * one tap away. A directory that merely ends in `.code-workspace` is excluded
 * because the workbench answers a workspace it cannot read with an empty window
 * and no message, which is the failure this whole change exists to remove.
 */
internal fun folderOpenTarget(
    folderPath: String,
    names: List<String>,
    isFile: (String) -> Boolean = { File(it).isFile },
): String =
    names.filter { it.endsWith(WORKSPACE_FILE_SUFFIX) }
        .map { "$folderPath${File.separator}$it" }
        .filter(isFile)
        .singleOrNull()
        ?: folderPath

/**
 * Whether a folder switch that failed should leave the previous folder watched.
 *
 * The watcher is stopped before every sync, so something has to decide what a
 * failure leaves behind, and the two cases pull opposite ways. When the sync was
 * writing into the folder that was being watched (reopening the folder already
 * open), that mirror is now part-written, and a watcher over it would push the
 * half onto the user's own documents. When it was writing into a different one,
 * the watched folder was never touched, it is still the folder on screen, and
 * leaving it unwatched means the user keeps editing it with write-back silently
 * off.
 *
 * Comparing the tree URIs as text because that is what a JVM test can supply:
 * `android.net.Uri` is abstract with a private constructor, so it can be neither
 * built nor mocked outside a device.
 */
internal fun shouldRestorePreviousWatcher(previousUri: String?, failedUri: String): Boolean =
    previousUri != null && previousUri != failedUri

/**
 * Whether an adoption that waited its turn behind another open should be skipped.
 *
 * Opens are serialised, and an adoption is a reply to a page load rather than a
 * request: it exists to put the watcher on the folder the page is showing. By
 * the time its turn comes the page may be elsewhere, because the open ahead of
 * it navigated (a picked folder always does) or because the workbench moved on
 * again, and syncing the folder it was queued for would then take the only
 * watcher off the folder on screen, the state adoption exists to end. The open
 * ahead may also have watched this same folder already, and a second sync under
 * a fresh stop would only re-copy what is there.
 *
 * A requested open (`navigate` true) is never stale: it navigates to its folder
 * when it finishes, so the page is on it by construction, and reopening the
 * folder already open is how a user pulls down fresh content.
 *
 * Mirror names rather than URIs, for the reason [shouldRestorePreviousWatcher]
 * compares text, and because the name is what the removal guard and the
 * adoption lookup already compare.
 */
internal fun adoptionIsStale(
    navigate: Boolean,
    watchedMirror: String?,
    openMirror: String?,
    mirror: String,
): Boolean = !navigate && (watchedMirror == mirror || openMirror != mirror)

/**
 * The remembered workspace folder, or null when reopening it is not safe.
 *
 * A function rather than a branch inside the Activity, for the reason
 * [shouldRestorePreviousWatcher] is one: `File`, `Uri` and a `Context` cannot be
 * had in a plain JVM test, and what is decided here is a rule over one string.
 *
 * A path that is not under [mirrorsRoot] is an ordinary project folder and needs
 * no grant, so the absence of one must not refuse it; that asymmetry is the
 * whole reason the mirror test is asked first and separately.
 */
internal fun rememberedFolderToReopen(
    remembered: String?,
    mirrorsRoot: String,
    exists: (String) -> Boolean,
    mirrorIsGranted: (String) -> Boolean,
): String? {
    // Absolute, before anything is asked of the filesystem. `File("")` and every
    // relative path resolve against the process working directory, and
    // `File("").exists()` answers true, so a record holding anything but a path
    // would be reopened as whatever that directory happens to be and then
    // published as a served resource root. Every value written here is absolute;
    // anything else is a sentinel or damage, and both mean the default.
    val path = remembered?.takeIf { it.startsWith("/") }?.takeIf(exists) ?: return null
    SafStorageManager.mirrorNameFor(path, mirrorsRoot) ?: return path
    return path.takeIf { mirrorIsGranted(it) }
}

/**
 * The script the resume health check evaluates in the page.
 *
 * Lifted out of `MainActivity.checkConnectionHealth` so that what it probes can
 * be asserted without an Activity. That matters more than it looks: the previous
 * version of this script also searched every `.monaco-dialog-box` for the words
 * "reconnect" and "lost", which reads as a check on VS Code's reconnection
 * dialog and is really a check on the display language. Under a language pack
 * the substrings are translated, the match never fires, and a broken connection
 * is reported healthy, with no error, and only for the users not reading
 * English.
 *
 * What is left asks IndexedDB, which answers the same in every locale.
 */
internal fun connectionHealthProbe(): String =
    """
    (function() {
        try {
            var req = indexedDB.open('vscode-web-db');
            req.onerror = function() {
                console.warn('[VSCodroid] IndexedDB broken, reloading');
                window.location.reload();
            };
            req.onsuccess = function() { req.result.close(); };
        } catch(e) {
            console.warn('[VSCodroid] IndexedDB exception, reloading');
            window.location.reload();
            return 'reload:idb-exception';
        }
        return 'ok';
    })()
    """.trimIndent()

/**
 * The five characters that change the meaning of surrounding HTML.
 *
 * The strings this escapes come from this app's own resources, so nothing
 * hostile reaches it today. It is here because the page is built by string
 * interpolation, and the next person to interpolate something into it may be
 * carrying a filename or an exception message.
 */
internal fun escapeHtml(s: String): String = s
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
    .replace("'", "&#39;")

/**
 * Markup made safe for the `data:` URL that `WebView.loadData` splices it into.
 *
 * `loadData` does not load a document, it builds a URL out of one, and for an app
 * targeting Q or later the platform stops escaping the content: the first `#`
 * ends the URL and every byte after it becomes the fragment. The loading page
 * opens with `background:#1e1e1e`, so what the WebView actually parsed was an
 * unterminated `<body` start tag, which is dropped at end of input. The document
 * was empty and the view painted its own default white for the whole of the
 * server start, which is the first screen of every cold start and the screen the
 * Retry link puts back.
 *
 * `%` is escaped before `#`, or the `%23` written here would be escaped a second
 * time into `%2523` and reach the page as literal text.
 *
 * Not needed by [MainActivity.showServerGaveUp]: `loadDataWithBaseURL` with a
 * base URL that is not a data URL loads the content as a plain string, which is
 * why that page renders today while this one does not.
 */
internal fun dataUrlSafe(html: String): String = html
    .replace("%", "%25")
    .replace("#", "%23")

/**
 * A folder name read straight off a SAF tree URI, for the moment before the
 * provider has been asked for the real one.
 *
 * Never the final name: [SafStorageManager.getDisplayName] replaces it as soon as
 * the query answers. It exists because that query is a cross-process round trip
 * and a network- or MTP-backed provider can make it a long one, and the sync
 * dialog has to be on screen before it, not after.
 *
 * The last path segment of a tree URI arrives decoded, as `primary:Documents/Work`,
 * so the tail after the last separator is the folder the user picked. A root
 * (`primary:`) has no tail, and there the whole segment is closer to a name than
 * an empty pair of quotes is.
 */
internal fun treeUriLabel(lastPathSegment: String?): String {
    val segment = lastPathSegment.orEmpty()
    val tail = segment.substringAfterLast('/').substringAfterLast(':')
    return tail.ifBlank { segment }
}
