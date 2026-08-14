package com.vscodroid

import android.Manifest
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
import android.os.Bundle
import android.net.Uri
import android.os.IBinder
import android.os.SystemClock
import android.view.View
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import java.io.File
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import com.vscodroid.util.CrashReporter
import com.vscodroid.util.StorageManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.vscodroid.util.Environment
import com.vscodroid.bridge.AndroidBridge
import com.vscodroid.bridge.ClipboardBridge
import com.vscodroid.bridge.SecurityManager
import com.vscodroid.keyboard.ExtraKeyRow
import com.vscodroid.keyboard.KeyInjector
import com.vscodroid.service.NodeService
import com.vscodroid.setup.FirstRunSetup
import com.vscodroid.storage.SafStorageManager
import com.vscodroid.util.Logger
import com.vscodroid.webview.VSCodroidWebChromeClient
import com.vscodroid.webview.VSCodroidWebView
import com.vscodroid.webview.VSCodroidWebViewClient
import com.vscodroid.webview.publishedResourceRoots
import com.vscodroid.webview.sensitiveLocations
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    private val tag = "MainActivity"

    private var webView: WebView? = null
    private var extraKeyRow: ExtraKeyRow? = null
    private var nodeService: NodeService? = null
    private var serviceBound = false
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

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as NodeService.LocalBinder
            nodeService = binder.getService()
            serviceBound = true
            Logger.i(tag, "Bound to NodeService")
            setupServiceCallbacks()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            nodeService = null
            serviceBound = false
            Logger.w(tag, "Disconnected from NodeService")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // enableEdgeToEdge() must be called BEFORE super.onCreate().
        // Handles status bar, navigation bar, and system bar styling automatically
        // with backward compatibility across Android 13-16+.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        safManager = SafStorageManager(this)

        setupWebView()
        setupExtraKeyRow()
        setupBackNavigation()
        requestNotificationPermission()
        startAndBindService()
        checkPreviousCrash()
        checkStorageHealth()
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
     */
    private fun receiveCallbackIntent(intent: Intent?) {
        val uri = intent?.data ?: return
        if (!isExtensionCallback(uri.scheme, uri.host)) return
        if (workbenchLoaded) {
            handleExtensionCallback(uri)
            return
        }
        Logger.w(tag, "Extension callback arrived with no workbench page left to receive it")
        Toast.makeText(
            this,
            "Sign-in could not be completed because the editor restarted. " +
                "Please sign in again.",
            Toast.LENGTH_LONG
        ).show()
    }

    override fun onDestroy() {
        safManager.stopFileWatcher()
        if (serviceBindingInitiated) {
            try {
                unbindService(serviceConnection)
            } catch (_: IllegalArgumentException) {
                // Already unbound — safe to ignore
            }
            serviceBindingInitiated = false
            serviceBound = false
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
    private fun handleSafFolderSelected(uri: Uri) {
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
                if (serverPort > 0) {
                    navigateToFolder(serverPort, mirrorDir.absolutePath)
                }
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
     * - >5 min background: force reload (stale state almost certain)
     * - >1 min background: run JS health check, reload only if broken
     * - <1 min: no action needed (WebSocket survives short pauses)
     */
    private fun handleResumeFromBackground() {
        val ts = backgroundedAt
        if (ts == 0L || serverPort == 0) return
        backgroundedAt = 0

        // Don't interfere if server is restarting — onServerReady handles reload
        if (nodeService?.isServerRunning() != true) return

        val bgMs = SystemClock.elapsedRealtime() - ts
        when {
            bgMs > FORCE_RELOAD_THRESHOLD_MS -> {
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
            bgMs > HEALTH_CHECK_THRESHOLD_MS -> {
                checkConnectionHealth(bgMs)
            }
        }
    }

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
            wv.webViewClient = bootstrapClient()
            // Show a loading placeholder while Node.js starts
            // viewport-fit=cover enables rendering into display cutout area
            wv.loadData(
                """<html><head><meta name="viewport" content="width=device-width, initial-scale=1.0, viewport-fit=cover"></head>
                   <body style="background:#1e1e1e;color:#888;font-family:sans-serif;
                   display:flex;align-items:center;justify-content:center;height:100vh;margin:0;">
                   <div style="text-align:center"><h2 style="color:#ccc;">VSCodroid</h2>
                   <p>Starting server...</p></div></body></html>""",
                "text/html", "utf-8"
            )
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
        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            Logger.e(tag, "Render process gone before the workbench loaded: " +
                "didCrash=${detail.didCrash()}")
            recreateWebView()
            return true
        }
    }

    /**
     * Pads a view by the system bars, so VS Code's title bar (breadcrumbs,
     * back/forward, search) renders below the status bar.
     *
     * CSS `env(safe-area-inset-*)` may not report correct values after
     * `enableEdgeToEdge()`, so this is handled natively.
     *
     * It lives here rather than in `onCreate` because [recreateWebView] replaces
     * the view. Registered once against the original, the listener died with it,
     * and every WebView built after a renderer crash drew its title bar under the
     * status bar — the exact symptom this exists to prevent, returning at the
     * moment the user has already lost their editor once.
     *
     * The explicit request is what makes it take effect on the replacement:
     * insets are dispatched on attach, and after a recreation this runs on a view
     * that is already attached, so without asking for a fresh pass the padding
     * would wait for the next rotation or keyboard.
     */
    private fun applyWindowInsetsPadding(view: View) {
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
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

        // If the server is already running (activity recreated, rotation, etc.),
        // the launchServer() coroutine has already completed and won't fire again.
        // Check immediately and load the WebView if the server process is alive.
        // Note: isServerRunning() checks process.isAlive (no I/O), whereas
        // isServerHealthy() does HTTP — which throws NetworkOnMainThreadException.
        val service = nodeService ?: return
        val port = service.getPort()
        if (port > 0 && service.isServerRunning()) {
            Logger.i(tag, "Server already running on port $port, loading immediately")
            serverPort = port
            loadVSCode(port)
        }
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
            onMinimize = { moveTaskToBack(true) },
            onOpenFolderPicker = { openFolderPicker() },
            onOpenRecentFolder = { uri -> openRecentSafFolder(uri) },
            onShowAbout = { runOnUiThread { showAboutDialog() } },
            safManager = safManager
        )
        wv.addJavascriptInterface(bridge, "AndroidBridge")

        // One set of rules, read once, handed to both entry points. The service
        // worker is the second route a resource request takes into the
        // interceptor, so lists installed on only one side leave the other
        // answering by different rules — and neither side would say a word about
        // the difference.
        val roots = resourceRoots
        val sensitive = sensitivePaths

        // Register ServiceWorkerClient BEFORE loading VS Code — service worker
        // script fetches bypass WebViewClient.shouldInterceptRequest entirely.
        VSCodroidWebViewClient.setupServiceWorkerInterception(
            port, roots, sensitive, { openWorkspaceFolder }
        ) { nodeService?.getConnectionToken() }

        wv.webViewClient = VSCodroidWebViewClient(
            allowedPort = port,
            resourceRoots = roots,
            sensitiveLocations = sensitive,
            openFolder = { openWorkspaceFolder },
            connectionToken = { nodeService?.getConnectionToken() },
            onCrash = { recreateWebView() },
            onPageLoaded = { url ->
                folderFromUrl(url)?.let { openWorkspaceFolder = it }
                injectBridgeToken()
            },
        )
        wv.webChromeClient = VSCodroidWebChromeClient()

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
        //
        // Logged without it: this line is the one place the token would otherwise
        // reach logcat, which is readable by anything holding READ_LOGS.
        val withoutToken = "http://127.0.0.1:$port/?folder=${Uri.encode(folderPath)}"
        val token = nodeService?.getConnectionToken()
        val url = if (token.isNullOrEmpty()) withoutToken
                  else "$withoutToken&tkn=${Uri.encode(token)}"

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

        Logger.i(tag, "Loading VS Code at $withoutToken")
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
     * `windowSoftInputMode="adjustResize"` (AndroidManifest.xml:49) shrinks the
     * window when the soft keyboard opens, and the WebView takes what is left
     * (`layout_weight="1"`), so a height threshold would switch the sizing on and off
     * while the user types.
     *
     * It has to be a media query rather than anything sampled in Kotlin: this runs
     * once per page load, and MainActivity declares `configChanges` for `orientation`,
     * `screenSize` and `screenLayout` (AndroidManifest.xml:48) with no
     * `onConfigurationChanged`, so nothing re-invokes the injection when the window
     * changes. Letting the browser hold the condition costs nothing and never goes
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
                        if (t) { AndroidBridge.openExternalUrl(url, t); return null; }
                    }
                    return orig.apply(window, arguments);
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
                            AndroidBridge.openExternalUrl(d.url, token);
                            ch.postMessage({id: d.id, ok: true});
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

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.about_title))
            .setMessage("$version\n\n$disclaimer")
            .setPositiveButton("OK", null)
            .setNeutralButton("Source Code") { _, _ ->
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/rmyndharis/VSCodroid")))
            }
            .setNegativeButton("Privacy Policy") { _, _ ->
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://rmyndharis.github.io/VSCodroid/privacy-policy.html")))
            }
            .show()
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


    companion object {
        /** Run health check if backgrounded longer than this. */
        private const val HEALTH_CHECK_THRESHOLD_MS = 60_000L   // 1 minute

        /** Force page reload if backgrounded longer than this. */
        private const val FORCE_RELOAD_THRESHOLD_MS = 300_000L  // 5 minutes
    }
}

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
        Logger.d("MainActivity", "Failed to write memory pressure: ${e.message}")
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
