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
import android.os.IBinder
import android.os.SystemClock
import android.provider.DocumentsContract
import android.text.util.Linkify
import android.view.View
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
import com.vscodroid.storage.SafStorageManager
import com.vscodroid.util.Logger
import com.vscodroid.util.Notices
import com.vscodroid.webview.DownloadCoordinator
import com.vscodroid.webview.DownloadHost
import com.vscodroid.webview.DownloadOutcome
import com.vscodroid.webview.VSCodroidWebChromeClient
import com.vscodroid.webview.VSCodroidWebView
import com.vscodroid.webview.VSCodroidWebViewClient
import com.vscodroid.webview.RETRY_URL
import com.vscodroid.webview.publishedResourceRoots
import com.vscodroid.webview.redactToken
import com.vscodroid.webview.sensitiveLocations
import org.json.JSONException
import org.json.JSONObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    private val tag = "MainActivity"

    private var webView: WebView? = null
    private var extraKeyRow: ExtraKeyRow? = null
    private var nodeService: NodeService? = null
    private var serviceBindingInitiated = false
    private var serverPort = 0
    private var backgroundedAt = 0L
    private var bridgeInitialized = false

    /**
     * Whether a workbench page is loaded and able to receive an auth callback.
     *
     * Reset by [recreateWebView], because the replacement is a new page: the ids
     * the workbench is waiting on live in memory and do not survive it. See
     * [receiveCallbackIntent].
     */
    private var workbenchLoaded = false

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
     * previous folder's watcher back — see [restoreWatcherAfterFailure].
     */
    private var watchedSafFolder: Pair<File, Uri>? = null

    /**
     * The folder [openSafFolder] is currently syncing, if any.
     *
     * `navigateToFolder` loads `/?folder=..&tkn=..` and the server redirects, so
     * two page-finished callbacks arrive per switch. Without this, the second one
     * sees a watcher that is not yet installed and starts the same sync again.
     */
    private var syncingFolder: Uri? = null

    /**
     * The directories this app publishes into the WebView, resolved once.
     *
     * Resolved on the main thread, deliberately. [publishedResourceRoots] stats
     * external storage and canonicalises four paths, so it is disk I/O and a
     * debug build with StrictMode on will say so. It is also the allowlist that
     * `shouldInterceptRequest` compares every resource request against, and
     * [initBridge] installs the client immediately before [navigateToFolder]
     * starts the page loading — so resolved on another thread, the first requests
     * would arrive while the list was still empty, and an empty allowlist refuses
     * every extension resource without a sound. A markdown preview that renders
     * blank is a far worse trade than a few milliseconds spent after a server
     * start that already took seconds.
     *
     * Lazy so the cost is paid once per Activity rather than once per WebView:
     * [recreateWebView] clears `bridgeInitialized`, so a plain call would re-stat
     * all four on every renderer crash — the moment the app can least afford it.
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
     * on the UI thread — `shouldInterceptRequest` performs synchronous HTTP, so
     * it cannot be.
     *
     * A folder rather than the URL it came from, and that is the point of the
     * field existing at all. [folderFromUrl] stats the path; a supplier that
     * called it would stat once per resource request, and the workbench issues
     * hundreds during a cold load. Deriving on navigation pays for the
     * `isDirectory` guard once per folder switch and keeps it.
     *
     * Only ever overwritten with a folder, never with the absence of one.
     * [folderFromUrl] answers null for every URL that is not a workbench URL —
     * the `data:` placeholder in [setupWebView], an error page — so assigning its
     * result directly would drop a perfectly good folder on any of them. What
     * that looks like from the outside is workspace resources 404ing now and
     * then, which is about as expensive as a symptom gets.
     */
    @Volatile
    private var openWorkspaceFolder: String? = null

    private lateinit var securityManager: SecurityManager
    private lateinit var safManager: SafStorageManager

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        Logger.i(tag, "Notification permission granted=$granted")
        // The service has already promoted itself by the time this answer
        // arrives, and it did so while the answer was still "no" — which on
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
            val script = "(function() {" +
                "  if (!window.__vscodroidDownload) return false;" +
                "  return window.__vscodroidDownload.send(" +
                "${JSONObject.quote(url)}, ${JSONObject.quote(requestId)});" +
                "})()"
            webView?.evaluateJavascript(script) { answer ->
                if (answer == "false") {
                    downloads.onComplete(requestId, "the page cannot read this download")
                }
            } ?: downloads.onComplete(requestId, "there is no page to read this download")
        }

        override fun report(outcome: DownloadOutcome, fileName: String, detail: String?) {
            if (detail != null) Logger.w(tag, "Download of $fileName: $detail")
            val message = when (outcome) {
                DownloadOutcome.SAVED -> "Saved $fileName"
                DownloadOutcome.CANCELLED -> "Download cancelled"
                DownloadOutcome.FAILED -> "Could not save $fileName"
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
        setContentView(R.layout.activity_main)

        safManager = SafStorageManager(this)
        // A save that never reached the device folder looks exactly like one that did.
        // The engine has no screen, so the notice is wired here; it is throttled inside
        // the manager, because a provider that starts refusing refuses everything.
        safManager.onWriteBackFailed { file ->
            runOnUiThread {
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.saf_write_back_failed, file.name),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }

        // The other direction: documents the device holds that did not reach the editor.
        // Its own wording, because "the only copy is inside VSCodroid" is the opposite of
        // true for these, and would send the user looking for a file that is safe.
        // The outbound direction of the same silence: a folder created in the editor
        // that did not arrive whole on the device. One notice per folder, and the cap
        // gets its own wording because it is a limit this app chose rather than the
        // device refusing.
        safManager.onUploadIncomplete { dir, lost, capped ->
            val message = if (capped) {
                getString(R.string.saf_upload_capped, dir.name)
            } else {
                resources.getQuantityString(R.plurals.saf_upload_incomplete, lost, lost, dir.name)
            }
            runOnUiThread {
                Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
            }
        }

        safManager.onDocumentsNotCopied { count, outOfRoom ->
            runOnUiThread {
                Toast.makeText(
                    this@MainActivity,
                    resources.getQuantityString(
                        if (outOfRoom) R.plurals.saf_documents_not_copied_no_room
                        else R.plurals.saf_documents_not_copied,
                        count,
                        count,
                    ),
                    Toast.LENGTH_LONG,
                ).show()
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
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        receiveCallbackIntent(intent)
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
     * Android is free to kill an app whose screen the browser has taken — a phone
     * under memory pressure signing into an extension is a routine way to get
     * there. The returning `vscodroid://callback` then builds a *new*
     * `MainActivity`, whose `onCreate` never looked at `intent.data`;
     * `SplashActivity.launchMain()` even forwards the data along, and it was
     * dropped on arrival.
     *
     * Reading it is where the recovery stops, though, and the reason is in the
     * workbench rather than here. `out/vs/code/browser/workbench/workbench.js`
     * keeps the ids it is waiting for in a plain in-memory Set —
     * `pendingCallbacks = new Set`, added to when a request is created, and never
     * written to storage — and `checkCallbacks()` iterates *that*, reading
     * `localStorage` only for ids already in it. So a relayed value is consumable
     * by exactly the page instance that began the sign-in. Once that page is
     * gone, which is the whole premise of arriving through `onCreate`, injecting
     * would write a key nothing ever reads and fire an event nothing is
     * listening for — and leave the value behind permanently, since the cleanup
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
            Toast.makeText(
                this,
                "Sign-in could not be completed because the editor restarted. " +
                    "Please sign in again.",
                Toast.LENGTH_LONG
            ).show()
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
                "Sign-in took too long to complete. Please sign in again.",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        handleExtensionCallback(uri)
    }

    override fun onDestroy() {
        safManager.stopFileWatcher()
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
                // Already unbound — safe to ignore
            }
            serviceBindingInitiated = false
        }
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
        // Deciding and recording live together in applyMemoryPressure. Nothing
        // here branches on the level, because nothing here can be run: this
        // method cannot be invoked without an Activity, and an Activity cannot
        // be built in a plain JVM test, so any decision left inside it is a
        // decision no test can reach. What remains is super, one call, and two
        // effects that need the Activity anyway.
        val pressure = applyMemoryPressure(File(cacheDir, "tmp"), level)
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
     * Handles a SAF folder selection result:
     * 1. Persists the URI permission
     * 2. Syncs folder contents to a local mirror (with progress dialog)
     * 3. Reloads VS Code with the mirror path
     */
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
        val folder = safManager.folderForOpenedPath(folderPath) ?: return
        if (watchedSafFolder?.first?.path == folder.mirrorPath) return
        if (syncingFolder == folder.uri) return
        Logger.i(tag, "Adopting a device folder the workbench opened on its own")
        openSafFolder(folder.uri, navigate = false)
    }

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
        Logger.i(tag, "SAF folder selected: $uri")

        // Persist permission so we can access this folder after app restart
        safManager.persistPermission(uri)

        val displayName = safManager.getDisplayName(uri)

        // Show progress dialog during sync
        val dialog = AlertDialog.Builder(this)
            .setTitle("Opening folder")
            .setMessage("Syncing \"$displayName\"...")
            .setCancelable(false)
            .create()
        dialog.show()

        val previouslyWatched = watchedSafFolder
        syncingFolder = uri

        lifecycleScope.launch {
            try {
                // Before the sync, and this call was moved rather than added:
                // there used to be one after it, which `startWatching` has since
                // made redundant by stopping the previous watcher itself. Reading
                // that redundancy as the whole problem is the trap, because the
                // stop that was missing is this one.
                //
                // Reopening a folder that is already open — which
                // openRecentSafFolder routes through here — ran the initial sync
                // under that folder's live watcher. `copyDocumentToLocal` lands
                // each file by writing a `.partial` beside it and calling
                // `renameTo`, the observer reads `MOVED_TO` as CREATE, and every
                // file just pulled down was immediately queued to be pushed back.
                //
                // What happens to it if the sync fails depends on which folder
                // failed, and restoreWatcherAfterFailure is where that is
                // decided.
                safManager.stopFileWatcher()
                watchedSafFolder = null

                val mirrorDir = withContext(Dispatchers.IO) {
                    safManager.syncToLocal(uri) { done, total ->
                        runOnUiThread {
                            dialog.setMessage(
                                "Syncing \"$displayName\"\n$done / $total files..."
                            )
                        }
                    }
                }

                safManager.startFileWatcher(mirrorDir, uri)
                watchedSafFolder = mirrorDir to uri

                // Write active folder so new terminals cd to the right place
                writeActiveFolder(mirrorDir.absolutePath)

                dialog.dismiss()

                // Reload VS Code with the mirror directory
                if (navigate && serverPort > 0) {
                    navigateToFolder(serverPort, mirrorDir.absolutePath)
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
                // Guarded because the window may already be gone: dismissing a
                // dialog whose Activity has been destroyed throws, and an
                // exception raised inside this handler would reach the scope's
                // handler rather than being the cancellation it is.
                if (!isFinishing && !isDestroyed) dialog.dismiss()
                throw e
            } catch (e: SecurityException) {
                dialog.dismiss()
                Logger.e(tag, "SAF permission revoked during sync", e)
                reportSyncFailure(
                    "Permission denied.", restoreWatcherAfterFailure(previouslyWatched, uri)
                )
            } catch (e: Exception) {
                dialog.dismiss()
                Logger.e(tag, "SAF sync failed", e)
                reportSyncFailure(
                    "Failed to open folder: ${e.message}.",
                    restoreWatcherAfterFailure(previouslyWatched, uri)
                )
            } finally {
                // Cleared however this ends, cancellation included: a folder left
                // marked as syncing would make every later page load into its
                // mirror a no-op, which is the defect this marker exists to avoid
                // rather than one to introduce.
                if (syncingFolder == uri) syncingFolder = null
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
     * part-written mirror would push that half onto the user's own documents —
     * damage to their only copy.
     *
     * A different folder's mirror was not touched at all. Leaving *its* watcher
     * dead buys nothing and costs the user the write-back for the folder still
     * on screen, which they go on editing in the belief it is being saved. That
     * is the failure this whole reorder exists to avoid, arriving from the other
     * direction.
     *
     * @return whether write-back is running for the folder the user is looking at.
     */
    private fun restoreWatcherAfterFailure(previous: Pair<File, Uri>?, failed: Uri): Boolean {
        val (mirrorDir, uri) = previous ?: return false
        if (!shouldRestorePreviousWatcher(uri.toString(), failed.toString())) return false
        safManager.startFileWatcher(mirrorDir, uri)
        watchedSafFolder = previous
        Logger.i(tag, "Restored the previous folder's watcher after a failed switch")
        return true
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
     */
    private fun reportSyncFailure(reason: String, writeBackStillRunning: Boolean) {
        val consequence = if (writeBackStillRunning) {
            " The folder already open is unaffected."
        } else {
            " Changes will not sync to this folder until you open it again."
        }
        Toast.makeText(this, reason + consequence, Toast.LENGTH_LONG).show()
    }

    /**
     * Opens a previously selected SAF folder from the recent list.
     * Called from [AndroidBridge.openRecentFolder] via JS bridge.
     */
    fun openRecentSafFolder(uri: Uri) {
        if (!safManager.hasPersistedPermission(uri)) {
            Toast.makeText(
                this,
                "Permission expired. Please select the folder again.",
                Toast.LENGTH_LONG
            ).show()
            // Open the picker as a fallback
            openFolderPicker()
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

        // Don't interfere if server is restarting — onServerReady handles reload.
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
        when (resumeAction(bgMs, signInIsPending(), fileChooserIsPending())) {
            ResumeAction.RELOAD -> {
                Logger.i(tag, "Reloading after ${bgMs / 1000}s in background")
                // reload(), not a rebuilt URL. The WebView URL is the only
                // truthful record of what is open, and rebuilding reads only
                // `folder` back out of it — so a multi-root workspace
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
     * Probes the WebView for an IndexedDB connection that did not survive being
     * frozen, and reloads the page from JS if it finds one.
     *
     * One signal, not two, and the missing one is the point. This also matched
     * the words "reconnect" and "lost" in the text of any `.monaco-dialog-box`,
     * which reads as a check on VS Code's reconnection dialog and is really a
     * check on the display language: install a language pack and the substrings
     * are translated, the match never fires, and the probe reports a healthy
     * connection for a broken one — silently, and only for the users who are not
     * reading English.
     *
     * It was not narrowed in favour of something better, because there is nothing
     * better to reach for. The shipped workbench carries no class that marks a
     * dialog as the reconnection one — `.monaco-dialog-box` and
     * `.monaco-dialog-modal-block` are the only dialog classes in
     * `workbench.web.main.internal.css`, and both belong to every modal it can
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
            wv.loadData(LOADING_PAGE, "text/html", "utf-8")
        }
    }

    /**
     * The client that survives a renderer crash before the real one is installed.
     *
     * `onRenderProcessGone` has a platform contract with teeth: returning false
     * — which the default `WebViewClient` does, and which is also what a WebView
     * with *no* client does — tells the framework the app cannot carry on, and it
     * ends the application process. [VSCodroidWebViewClient] returns true and
     * rebuilds the view, but it is installed by [initBridge], which runs from
     * [loadVSCode] only once the server reports ready. That leaves the whole cold
     * start — up to the thirty seconds `waitForReady` will wait — with the
     * placeholder on screen and nothing to catch a renderer that dies under
     * exactly the memory pressure a Node.js server starting up creates.
     *
     * Deliberately not the real client. That one needs the port, and it fires
     * `onPageLoaded` on every page — including this placeholder, whose load would
     * reach [injectBridgeToken] before `securityManager` has been constructed.
     * A renderer crash is the only thing worth handling before the workbench
     * exists; the placeholder is a `data:` URL and issues no requests to
     * intercept.
     */
    private fun bootstrapClient() = object : WebViewClient() {
        /**
         * The one URL this client acts on, and the reason the error page uses a
         * link rather than a script.
         *
         * The page it serves is a `data:` URL with no bridge on it: `initBridge`
         * runs once per WebView for the editor, and registering a second
         * JavaScript interface here to carry one button would widen the surface
         * that the session token exists to gate. A navigation the client
         * recognises costs nothing and reaches the same place.
         */
        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest,
        ): Boolean {
            if (request.url.toString() != RETRY_URL) return false
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
    }

    /**
     * Keeps `env(safe-area-inset-*)` at zero inside the page. Load-bearing,
     * and not for the reason its shape suggests.
     *
     * The padding does NOT position anything: the WebView render engine
     * ignores the view's own padding — with it applied, the page still
     * reports `window.innerHeight` equal to the full view height, and the
     * container padding from [ExtraKeyRow.setupWithRootView] is what places
     * the editor below the bars. What the padding DOES feed is Chromium's
     * safe-area computation, roughly `safeArea = cutout − viewPadding`.
     * Remove it and the page suddenly reads `env(safe-area-inset-top)` =
     * the full cutout height — even though the container already moved the
     * view out of the cutout — and the workbench squeezes its title bar to
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

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                webView?.evaluateJavascript(
                    // The token is injected into the page once the workbench has loaded.
                    // Before that it is absent, the call returns false, and the fallback
                    // below sends the app to the background — which is what pressing back
                    // on a not-yet-loaded editor should do anyway.
                    "(function() { var t = (window.__vscodroid || {}).authToken;" +
                        " return t ? (window.AndroidBridge?.onBackPressed?.(t) || false) : false; })()"
                ) { result ->
                    if (result != "true") {
                        moveTaskToBack(true)
                    }
                }
            }
        })
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
     * ordering between them — the user can answer the dialog faster than the
     * service binds — and losing the request to that race would leave exactly the
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

    private fun startAndBindService() {
        val serviceIntent = Intent(this, NodeService::class.java)
        startForegroundService(serviceIntent)
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
        serviceBindingInitiated = true
    }

    private fun setupServiceCallbacks() {
        nodeService?.onServerReady = { port ->
            serverPort = port
            runOnUiThread { loadVSCode(port) }
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
        // an editor whose backend is gone, and — because the binding it holds is
        // what keeps a started service alive — leaves the service unable to
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
        // onServerReady at it — launchServer()'s coroutine has already finished —
        // so the state has to be asked for rather than waited on.
        //
        // isServerReady(), not isServerRunning(). The latter is Process.isAlive,
        // which is true from the moment the process is spawned and stays true for
        // the seconds the editor server takes to bind its port, and for the whole
        // of a restart after a crash. Navigating on it points the WebView at a
        // port with nothing listening, and onReceivedError only logs, so what the
        // user gets is a connection-refused page that nothing clears.
        //
        // The real probe is HTTP and cannot run here — NetworkOnMainThreadException
        // — which is what made the wrong question attractive. isServerReady()
        // reports what that probe already found, at no cost. See
        // ProcessManager.isReady.
        val service = nodeService ?: return

        // Checked first, because anything the service has to say about the start
        // was said to a callback that did not exist yet — this activity was not
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
    private fun showServerGaveUp() {
        // The page about to be shown is not the workbench, so nothing arriving
        // afterwards should be told it is. recreateWebView clears this for the
        // same reason when it throws the loaded page away.
        workbenchLoaded = false
        val message = getString(R.string.error_server_gave_up)
        val retry = getString(R.string.error_server_retry)
        webView?.loadDataWithBaseURL(
            null,
            """<html><head><meta name="viewport" content="width=device-width, initial-scale=1.0, viewport-fit=cover"></head>
               <body style="background:#1e1e1e;color:#ccc;font-family:sans-serif;
               display:flex;align-items:center;justify-content:center;height:100vh;margin:0;">
               <div style="text-align:center;max-width:32em;padding:1.5em">
               <h2 style="color:#ccc;margin:0 0 .6em">VSCodroid</h2>
               <p style="color:#aaa;line-height:1.5">${escapeHtml(message)}</p>
               <p><a href="$RETRY_URL" style="display:inline-block;margin-top:.8em;padding:.6em 1.4em;
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
        webView?.loadData(LOADING_PAGE, "text/html", "utf-8")
        startForegroundService(Intent(this, NodeService::class.java))
    }

    private fun loadVSCode(port: Int, folderPath: String? = null) {
        initBridge(port)
        // onServerReady routes a restart through here without a folder. Falling back
        // to the folder already on screen keeps the user's workspace instead of
        // dropping them back into the default projects directory.
        // The default is created rather than merely named. Splash repairs it at
        // launch, but that is not enough on its own: this activity can be
        // started directly, and the folder can be deleted while the app is
        // running. The URL-derived branch below already refuses a path that is
        // not a directory; the default deserves the same care.
        navigateToFolder(
            port,
            folderPath ?: folderFromUrl(webView?.url) ?: FirstRunSetup(this).ensureProjectsDir()
        )
    }

    /**
     * The folder encoded in a workbench URL, if it still exists on disk.
     *
     * VS Code opens a folder by navigating this same WebView without going
     * through Kotlin, so the URL is the only record of the open workspace that
     * stays truthful. A folder that has since disappeared — a cleared SAF
     * mirror, unmounted storage — is dropped so the caller falls back to the
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
        url?.let { Uri.parse(it) }
            ?.takeIf { it.isHierarchical }
            ?.getQueryParameter("folder")
            ?.takeIf { File(it).isDirectory }

    /**
     * Initializes the WebView bridge, security manager, and clients.
     * Only called once per server lifecycle — not on every folder switch.
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
        )
        wv.addJavascriptInterface(bridge, "AndroidBridge")

        // One set of rules, read once, handed to both entry points. The service
        // worker is the second route a resource request takes into the
        // interceptor, so lists installed on only one side leave the other
        // answering by different rules — and neither side would say a word about
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
            port, roots, sensitive, { self.get()?.openWorkspaceFolder }
        ) { self.get()?.nodeService?.getConnectionToken() }

        wv.webViewClient = VSCodroidWebViewClient(
            allowedPort = port,
            resourceRoots = roots,
            sensitiveLocations = sensitive,
            openFolder = { openWorkspaceFolder },
            connectionToken = { nodeService?.getConnectionToken() },
            onCrash = { recreateWebView() },
            // A hand-off that no app accepted used to be indistinguishable from a
            // dead link: the WebView does not navigate either, so the tap did
            // nothing and said nothing. ActivityNotFoundException is separated
            // out because it is the one the user can act on by installing
            // something; everything else is quoted by type for a bug report.
            onHandoffFailed = { uri, error ->
                val scheme = uri.scheme ?: "external"
                val message = if (error is android.content.ActivityNotFoundException) {
                    getString(R.string.url_handoff_no_app, scheme)
                } else {
                    getString(R.string.url_handoff_failed, scheme, error.javaClass.simpleName)
                }
                runOnUiThread {
                    Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                }
            },
            onPageLoaded = { url ->
                folderFromUrl(url)?.let {
                    openWorkspaceFolder = it
                    adoptWorkbenchFolder(it)
                }
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
        )
        wv.webChromeClient = VSCodroidWebChromeClient { allowMultiple ->
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
     * Navigates the WebView to a specific folder without re-initializing the bridge.
     * Safe to call multiple times (e.g., when switching SAF folders).
     */
    private fun navigateToFolder(port: Int, folderPath: String) {
        val wv = webView ?: return
        // Seeded here and not only from the page-loaded callback. This method is
        // the one that knows the folder before the page exists, and between
        // loadUrl below and onPageFinished the workbench is already fetching
        // resources — against a supplier that would still be answering null, so
        // everything inside the workspace would 404 for the length of the load.
        openWorkspaceFolder = folderPath
        // The token rides in the query once. The server consumes it on `/`, turns
        // it into the vscode-tkn cookie and redirects with the folder intact;
        // everything after that authenticates itself — the cookie for pages, the
        // query for resource requests, an auth message for the WebSocket.
        val token = nodeService?.getConnectionToken()
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

        // Redacted rather than rebuilt without the token. This used to log a
        // second string assembled beside the real one, so what kept the token out
        // of logcat was a person keeping two expressions apart, and the obvious
        // edit, logging the URL that is actually loaded, put the credential for
        // every route but `/version` into a release build's logcat, readable by
        // anything holding READ_LOGS. There is one URL now, and the redactor is
        // the same one the webview layer uses.
        Logger.i(tag, "Loading VS Code at ${redactToken(url)}")
        wv.loadUrl(url)
    }

    /**
     * Writes the active folder path to ~/.vscodroid_folder so new terminals
     * can cd to the correct directory. See bashrc in FirstRunSetup.
     */
    private fun writeActiveFolder(folderPath: String) {
        try {
            val homeDir = File(Environment.getHomeDir(this))
            File(homeDir, ".vscodroid_folder").writeText(folderPath)
            Logger.d(tag, "Active folder: $folderPath")
        } catch (e: Exception) {
            Logger.d(tag, "Failed to write active folder: ${e.message}")
        }
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
     * Fix #2: Injects CSS to enlarge touch targets when the pointer is a fingertip.
     * Targets WCAG 2.5.5 minimum 44×44px for primary actions, 36px for list items.
     *
     * The test is `pointer: coarse`, not a viewport width, because what these rules
     * compensate for is the fingertip — and a fingertip does not change size with the
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
                    // Unprefixed, so it is wider than its name: the workbench also uses
                    // .slider for the colour picker, not only the scrollbar. Harmless
                    // there because that strip is already far wider than 12px, but
                    // narrow it and this rule starts deciding its width.
                    '  .slider { min-width: 12px !important; }',
                    '  .quick-input-list .monaco-list-row { min-height: 36px !important; }',
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
                    if (url && /^https?:/.test(url) && typeof AndroidBridge !== 'undefined') {
                        var t = (window.__vscodroid || {}).authToken;
                        // Only claim the click if the bridge actually opened it.
                        // `openExternalUrl` answers false when the launch itself fails,
                        // not when it disapproves of the destination: SecurityManager
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

                var HOLD_MS = 120000;
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
                // costs one blob for two minutes rather than for the life of
                // the page.
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
                    if (blob && blob.stream) return Promise.resolve(blob.stream().getReader());
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
     */
    private fun injectBridgeRelay() {
        webView?.evaluateJavascript(
            """
            (function() {
                if (typeof AndroidBridge === 'undefined') return;
                if (window.__vscodroidRelayActive) return;
                window.__vscodroidRelayActive = true;
                var ch = new BroadcastChannel('vscodroid-bridge');
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
                            result = AndroidBridge.getStorageBreakdown(token);
                            ch.postMessage({id: d.id, ok: true, data: result});
                        } else if (d.cmd === 'clearCaches') {
                            result = AndroidBridge.clearCaches(token);
                            ch.postMessage({id: d.id, ok: true, data: result});
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
     * Fix #9: Register a consumer for the onLowMemory JS callback.
     * MainActivity.onTrimMemory already fires window.__vscodroid?.onLowMemory?.(level),
     * but no consumer was registered. This method registers one.
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
                    // Hint GC and reduce image cache
                    try {
                        if (typeof gc === 'function') gc();
                    } catch(e) {}
                    // Clear any cached blob URLs
                    try {
                        var perf = performance.getEntries();
                        perf.forEach(function(e) {
                            if (e.name && e.name.startsWith('blob:')) {
                                try { URL.revokeObjectURL(e.name); } catch(x) {}
                            }
                        });
                    } catch(e) {}
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
        val versionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (_: Exception) {
            "unknown"
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
            .setMessage("$version\n\n$disclaimer")
            .setPositiveButton("OK", null)
            .setNeutralButton(getString(R.string.about_licenses)) { _, _ -> showLicensesDialog() }
            .setNegativeButton("Privacy Policy") { _, _ ->
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://rmyndharis.github.io/VSCodroid/privacy-policy.html")))
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
            .setPositiveButton("OK", null)
            .setNegativeButton(getString(R.string.licenses_full_texts)) { _, _ ->
                showLicenseTextsDialog()
            }
            .setNeutralButton("Source Code") { _, _ ->
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/rmyndharis/VSCodroid")))
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
                    .setPositiveButton("OK", null)
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
        // Read the open folder off the dying WebView before it goes away
        val lastUrl = wv.url
        val container = findViewById<android.widget.LinearLayout>(R.id.webViewContainer)
        container.removeView(wv)
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
        if (serverPort > 0) {
            // Always via loadVSCode so initBridge re-registers on the new WebView;
            // loading the old URL directly would leave it without the bridge. The
            // folder is carried over from the URL the destroyed WebView was showing.
            loadVSCode(serverPort, folderFromUrl(lastUrl))
        }
    }

    /**
     * Relays an extension auth callback from Chrome into the WebView's localStorage.
     *
     * VS Code's callback.html writes auth tokens to localStorage, but on Android
     * the callback opens in Chrome while the workbench runs in WebView — separate
     * localStorage domains. This method receives the token data via deep link
     * (vscodroid://callback?data=ENCODED_JSON) and injects it into the WebView's
     * localStorage so the workbench can pick it up.
     */
    private fun handleExtensionCallback(uri: Uri) {
        val dataParam = uri.getQueryParameter("data") ?: return
        Logger.i(tag, "Extension callback relay received")
        val escaped = org.json.JSONObject.quote(dataParam)
        webView?.evaluateJavascript("""
            (function() {
                try {
                    var d = JSON.parse(decodeURIComponent($escaped));
                    var key = 'vscode-web.url-callbacks[' + d.id + ']';
                    var value = JSON.stringify(d.uri);
                    localStorage.setItem(key, value);
                    // Dispatch synthetic StorageEvent — VS Code's workbench monitors
                    // localStorage via addEventListener("storage"), but that event only
                    // fires when ANOTHER browsing context writes. Since evaluateJavascript
                    // runs in the same context, we must dispatch it manually.
                    window.dispatchEvent(new StorageEvent('storage', {
                        key: key, newValue: value, oldValue: null,
                        storageArea: localStorage, url: window.location.href
                    }));
                    console.log('[VSCodroid] Callback relay: injected token for id=' + d.id);
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
            .setTitle("VSCodroid crashed")
            .setMessage("The app crashed in a previous session.\n\n$preview")
            .setPositiveButton("Dismiss") { _, _ -> CrashReporter.clearCrashLogs() }
            .setNeutralButton("Copy Report") { _, _ ->
                val report = CrashReporter.generateBugReport(this)
                val clipboard = getSystemService(android.content.ClipboardManager::class.java)
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("VSCodroid Bug Report", report))
                Toast.makeText(this, "Bug report copied to clipboard", Toast.LENGTH_SHORT).show()
                CrashReporter.clearCrashLogs()
            }
            .setCancelable(true)
            .show()
    }

    /**
     * Warns the user if available storage is critically low (<100 MB).
     *
     * The command name is quoted exactly as the palette lists it
     * (`VSCodroid: Clear Caches`, contributed by the bundled Android bridge
     * extension), because the reader's next action is to type it. This said
     * "Clear caches in Settings" until 2026-08-14, and there is no Settings
     * screen: the manifest declares Splash, Main and Toolchain activities and
     * nothing else, so the sentence sent a user who was out of space looking
     * for a place that does not exist.
     */
    private fun checkStorageHealth() {
        if (!StorageManager.isStorageLow(this)) return
        val available = StorageManager.formatSize(StorageManager.getAvailableStorage(this))
        Toast.makeText(
            this,
            "Storage low ($available available). Run \"VSCodroid: Clear Caches\" " +
                "from the Command Palette.",
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
     * rather than a clean refusal. `onReceivedError` only logs, so nothing else
     * would reach the user.
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
            "Android System WebView is version $version. VSCodroid is tested against " +
                "${WebViewVersion.MINIMUM_CHROME_MAJOR} and newer, so parts of the editor " +
                "may not work. Update it from the Play Store.",
            Toast.LENGTH_LONG
        ).show()
        Logger.w(tag, "WebView $version is below the tested minimum ${WebViewVersion.MINIMUM_CHROME_MAJOR}")
    }


    companion object {

        /**
         * The page shown while the server starts, in one place.
         *
         * Two callers load it now: the first setup, and a retry from the error
         * page. Written twice they would drift, and the second copy is the one a
         * user sees only after something has already gone wrong.
         */
        private const val LOADING_PAGE =
            """<html><head><meta name="viewport" content="width=device-width, initial-scale=1.0, viewport-fit=cover"></head>
               <body style="background:#1e1e1e;color:#888;font-family:sans-serif;
               display:flex;align-items:center;justify-content:center;height:100vh;margin:0;">
               <div style="text-align:center"><h2 style="color:#ccc;">VSCodroid</h2>
               <p>Starting server...</p></div></body></html>"""

    }
}

/** Run health check if backgrounded longer than this. */
internal const val HEALTH_CHECK_THRESHOLD_MS = 60_000L   // 1 minute

/** Force page reload if backgrounded longer than this. */
internal const val FORCE_RELOAD_THRESHOLD_MS = 300_000L  // 5 minutes

// Severities the process monitor understands. Words rather than numbers so that
// nothing downstream is tempted to compare them with >=, which is the defect
// this replaced.
internal const val PRESSURE_NONE = "none"
internal const val PRESSURE_MODERATE = "moderate"
internal const val PRESSURE_CRITICAL = "critical"

/**
 * What a trim level actually says about memory, which is not what comparing
 * it says.
 *
 * Android's constants are not ordered by severity. `TRIM_MEMORY_UI_HIDDEN`
 * is 20 and sits above `TRIM_MEMORY_RUNNING_CRITICAL` at 15, but it does not
 * describe memory at all — it means "your UI is no longer visible" and
 * arrives on every single backgrounding, on a device with gigabytes free.
 * A `>=` comparison therefore read an ordinary app switch as worse than a
 * genuine critical warning, and every language server idle for five minutes
 * — which, after five minutes in another app, is all of them — was killed.
 *
 * So this maps rather than compares, and the next constant Android adds
 * cannot clear a threshold by accident. Raising the number would have looked
 * like a fix and been wrong again at the next value.
 */
@Suppress("DEPRECATION")
internal fun memoryPressureOf(level: Int): String = when (level) {
    // Every level that already shed idle work keeps doing so. Critical while
    // running, and the cached levels, which all mean the system is reclaiming
    // and this process is a candidate — shrinking our own footprint there is
    // what keeps the app alive rather than merely responsive.
    TRIM_MEMORY_RUNNING_CRITICAL,
    TRIM_MEMORY_BACKGROUND,
    TRIM_MEMORY_MODERATE,
    TRIM_MEMORY_COMPLETE -> PRESSURE_CRITICAL

    // Reported, and deliberately not enough to kill anything, exactly as
    // before: 10 sat below the old threshold of 15.
    TRIM_MEMORY_RUNNING_LOW -> PRESSURE_MODERATE

    // TRIM_MEMORY_UI_HIDDEN (20) lands here, with TRIM_MEMORY_RUNNING_MODERATE
    // (5). The first is not about memory at all; the second is the mildest
    // hint Android has. This is the only line that changes behaviour, and it
    // changes it for exactly the value that was wrong.
    else -> PRESSURE_NONE
}

/**
 * Decides what a trim level means and records it for the process monitor.
 *
 * Deciding and recording are one unit, and splitting them is what made this
 * untestable: the decision sat in `onTrimMemory`, which cannot be invoked
 * without an Activity, while the recording sat in a private method that reached
 * `this.cacheDir` for the one thing it needed. Neither half could be reached, so
 * the wire between the pinned predicate and the file the monitor reads was
 * covered by nothing — and replacing [memoryPressureOf] here with a `>=`
 * comparison, the exact defect this whole path exists to prevent, left the
 * entire suite green.
 *
 * Takes the directory rather than reaching for one, so the caller supplies what
 * it already has and a test supplies a temporary one.
 *
 * @return the severity, so the caller can log it and notify the workbench —
 *   both of which need the Activity and neither of which decides anything.
 */
internal fun applyMemoryPressure(tmpDir: File, level: Int): String {
    val pressure = memoryPressureOf(level)
    if (pressure != PRESSURE_NONE) writeMemoryPressure(tmpDir, pressure)
    return pressure
}

/**
 * Writes the severity where `process-monitor.js` looks for it.
 *
 * A severity, not Android's trim level. The monitor on the other end has no
 * business knowing Android's numbering, and when it did, it compared values
 * that are not ordered by severity.
 *
 * Failure is swallowed: the file is a hint for a monitor, and losing it is
 * worth less than the memory callback it is reporting on.
 */
internal fun writeMemoryPressure(tmpDir: File, pressure: String) {
    try {
        File(tmpDir, "vscodroid-memory-pressure").writeText(pressure)
    } catch (e: Exception) {
        // Not the Activity's tag: the application class calls this too, and the
        // write most likely to fail is the one that happens after the Activity
        // is gone. Naming a destroyed component sends the reader to the wrong
        // lifecycle.
        Logger.d("MemoryPressure", "Failed to write memory pressure: ${e.message}")
    }
}

/**
 * Whether an incoming URI is the extension auth callback relay.
 *
 * Both halves are load-bearing, and the manifest is why. The VIEW filter that
 * delivers this is exported and BROWSABLE, so any installed app and any web page
 * the user taps can fire it, and what rides in the `data` parameter is written
 * into the workbench's `localStorage`. Relaxed to either half on its own —
 * `vscodroid://` with any host, or any scheme pointed at `callback` — the relay
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
 * The notice is read first, and that ordering is load-bearing: it may be terminal
 * or it may be a slow start still coming up, and in both cases the server is not
 * serving, so it must not be shadowed by a port that happens to look plausible.
 *
 * [ready] is the health probe's own finding, never process liveness. A process is
 * alive from the instant it is spawned and stays alive through the seconds before
 * its port is bound and through a whole post-crash restart; navigating on that
 * points the WebView at nothing, and `onReceivedError` only logs, so the
 * connection-refused page it produces is never cleared.
 */
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
    // Named so they collide with nothing else in this file. `TokenTaint`, which
    // guards this file against logging the connection token, follows taint by
    // identifier name across the whole file: binding the parsed URL to `uri` or
    // its host to `host` would mark two unrelated locals as token-bearing and
    // report four honest log statements as leaks. The conservatism is the point,
    // so the alias is what has to go.
    val parsed = runCatching { java.net.URI(url) }.getOrNull() ?: return false
    val hostName = parsed.host ?: return false
    return (hostName == "127.0.0.1" || hostName == "localhost") && parsed.port == port
}

internal fun bindDecision(notice: StartupNotice?, port: Int, ready: Boolean): BindDecision = when {
    notice != null && notice.terminal -> BindDecision.ShowGaveUp(notice.message)
    notice != null -> BindDecision.ShowNotice(notice.message)
    port > 0 && ready -> BindDecision.Load(port)
    else -> BindDecision.Wait
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
): ResumeAction = when {
    fileChooserPending -> ResumeAction.NOTHING
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
internal fun workbenchUrl(port: Int, folderPath: String, token: String?): String {
    val base = "http://127.0.0.1:$port/?folder=${Uri.encode(folderPath)}"
    return if (token.isNullOrEmpty()) base else "$base&tkn=${Uri.encode(token)}"
}

/**
 * Whether a folder switch that failed should leave the previous folder watched.
 *
 * The watcher is stopped before every sync, so something has to decide what a
 * failure leaves behind, and the two cases pull opposite ways. When the sync was
 * writing into the folder that was being watched — reopening the folder already
 * open — that mirror is now part-written, and a watcher over it would push the
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
 * The script the resume health check evaluates in the page.
 *
 * Lifted out of `MainActivity.checkConnectionHealth` so that what it probes can
 * be asserted without an Activity. That matters more than it looks: the previous
 * version of this script also searched every `.monaco-dialog-box` for the words
 * "reconnect" and "lost", which reads as a check on VS Code's reconnection
 * dialog and is really a check on the display language. Under a language pack
 * the substrings are translated, the match never fires, and a broken connection
 * is reported healthy — with no error, and only for the users not reading
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
