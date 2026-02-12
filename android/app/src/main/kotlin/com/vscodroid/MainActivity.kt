package com.vscodroid

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.net.Uri
import android.os.IBinder
import android.os.SystemClock
import android.webkit.WebView
import android.widget.Toast
import java.io.File
import androidx.activity.OnBackPressedCallback
import com.vscodroid.util.CrashReporter
import com.vscodroid.util.StorageManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.vscodroid.util.Environment
import com.vscodroid.bridge.AndroidBridge
import com.vscodroid.bridge.ClipboardBridge
import com.vscodroid.bridge.SecurityManager
import com.vscodroid.keyboard.ExtraKeyRow
import com.vscodroid.keyboard.KeyInjector
import com.vscodroid.service.NodeService
import com.vscodroid.storage.SafStorageManager
import com.vscodroid.util.Logger
import com.vscodroid.webview.VSCodroidWebChromeClient
import com.vscodroid.webview.VSCodroidWebView
import com.vscodroid.webview.VSCodroidWebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    private val tag = "MainActivity"

    private var webView: WebView? = null
    private var extraKeyRow: ExtraKeyRow? = null
    private var nodeService: NodeService? = null
    private var serviceBound = false
    private var serverPort = 0
    private var backgroundedAt = 0L
    private var bridgeInitialized = false

    private lateinit var securityManager: SecurityManager
    private lateinit var safManager: SafStorageManager

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        Logger.i(tag, "Notification permission granted=$granted")
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
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val uri = intent.data
        if (uri?.scheme == "vscodroid" && uri.host == "oauth") {
            handleOAuthCallback(uri)
            return
        }
        handleIntent(intent)
    }

    override fun onDestroy() {
        safManager.stopFileWatcher()
        if (serviceBound) {
            unbindService(serviceConnection)
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
        @Suppress("DEPRECATION")
        if (level >= TRIM_MEMORY_RUNNING_LOW) {
            Logger.w(tag, "Low memory signal: level=$level")
            writeMemoryPressure(level)
            webView?.evaluateJavascript(
                "window.__vscodroid?.onLowMemory?.($level)", null
            )
        }
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

        lifecycleScope.launch {
            try {
                val mirrorDir = withContext(Dispatchers.IO) {
                    safManager.syncToLocal(uri) { done, total ->
                        runOnUiThread {
                            dialog.setMessage(
                                "Syncing \"$displayName\"\n$done / $total files..."
                            )
                        }
                    }
                }

                // Stop any existing file watcher before starting a new one
                safManager.stopFileWatcher()
                safManager.startFileWatcher(mirrorDir, uri)

                // Write active folder so new terminals cd to the right place
                writeActiveFolder(mirrorDir.absolutePath)

                dialog.dismiss()

                // Reload VS Code with the mirror directory
                if (serverPort > 0) {
                    navigateToFolder(serverPort, mirrorDir.absolutePath)
                }
            } catch (e: SecurityException) {
                dialog.dismiss()
                Toast.makeText(
                    this@MainActivity,
                    "Permission denied. Please select the folder again.",
                    Toast.LENGTH_LONG
                ).show()
                Logger.e(tag, "SAF permission revoked during sync", e)
            } catch (e: Exception) {
                dialog.dismiss()
                Toast.makeText(
                    this@MainActivity,
                    "Failed to open folder: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
                Logger.e(tag, "SAF sync failed", e)
            }
        }
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

    private fun writeMemoryPressure(level: Int) {
        try {
            val tmpDir = File(cacheDir, "tmp")
            File(tmpDir, "vscodroid-memory-pressure").writeText(level.toString())
        } catch (e: Exception) {
            Logger.d(tag, "Failed to write memory pressure: ${e.message}")
        }
    }

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
                webView?.reload()
            }
            bgMs > HEALTH_CHECK_THRESHOLD_MS -> {
                checkConnectionHealth(bgMs)
            }
        }
    }

    /**
     * Evaluates a JS health check in the WebView that detects:
     * 1. VS Code reconnection dialog (WebSocket IPC channel broken)
     * 2. IndexedDB "closed" state (database connection lost during freeze)
     *
     * If either issue is detected, triggers window.location.reload() from JS
     * to re-establish all connections cleanly.
     */
    private fun checkConnectionHealth(bgMs: Long) {
        val wv = webView ?: return
        wv.evaluateJavascript(
            """
            (function() {
                var i, text;
                var dialogs = document.querySelectorAll('.monaco-dialog-box');
                for (i = 0; i < dialogs.length; i++) {
                    text = (dialogs[i].textContent || '').toLowerCase();
                    if (text.indexOf('reconnect') >= 0 || text.indexOf('lost') >= 0) {
                        console.warn('[VSCodroid] Connection lost, reloading');
                        window.location.reload();
                        return 'reload:connection-lost';
                    }
                }
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
        ) { result ->
            Logger.i(tag, "Health check after ${bgMs / 1000}s: ${result?.trim('"')}")
        }
    }

    private fun setupWebView() {
        webView = findViewById(R.id.webView)
        webView?.let { wv ->
            VSCodroidWebView.configure(wv)
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

    private fun setupExtraKeyRow() {
        extraKeyRow = findViewById(R.id.extraKeyRow)
        extraKeyRow?.setupWithRootView(window.decorView.rootView)
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                webView?.evaluateJavascript(
                    "(function() { return window.AndroidBridge?.onBackPressed?.() || false; })()"
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

    private fun startAndBindService() {
        val serviceIntent = Intent(this, NodeService::class.java)
        startForegroundService(serviceIntent)
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
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

        // If the server is already running (activity recreated, rotation, etc.),
        // the launchServer() coroutine has already completed and won't fire again.
        // Check immediately and load the WebView if the server is healthy.
        val service = nodeService ?: return
        val port = service.getPort()
        if (port > 0 && service.isServerHealthy()) {
            Logger.i(tag, "Server already running on port $port, loading immediately")
            serverPort = port
            loadVSCode(port)
        }
    }

    private fun loadVSCode(port: Int, folderPath: String? = null) {
        initBridge(port)
        navigateToFolder(port, folderPath ?: Environment.getProjectsDir(this))
    }

    /**
     * Initializes the WebView bridge, security manager, and clients.
     * Only called once per server lifecycle — not on every folder switch.
     */
    private fun initBridge(port: Int) {
        val wv = webView ?: return

        // Skip re-initialization if bridge is already set up for this port
        if (bridgeInitialized) return
        bridgeInitialized = true

        securityManager = SecurityManager(port)
        val clipboardBridge = ClipboardBridge(this)
        val bridge = AndroidBridge(
            context = this,
            security = securityManager,
            clipboard = clipboardBridge,
            onBackPressed = { false },
            onMinimize = { moveTaskToBack(true) },
            onOpenFolderPicker = { openFolderPicker() },
            onOpenRecentFolder = { uri -> openRecentSafFolder(uri) },
            safManager = safManager
        )
        wv.addJavascriptInterface(bridge, "AndroidBridge")

        // Register ServiceWorkerClient BEFORE loading VS Code — service worker
        // script fetches bypass WebViewClient.shouldInterceptRequest entirely.
        VSCodroidWebViewClient.setupServiceWorkerInterception(port)

        wv.webViewClient = VSCodroidWebViewClient(
            allowedPort = port,
            onCrash = { recreateWebView() },
            onPageLoaded = { injectBridgeToken() }
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
        val url = "http://127.0.0.1:$port/?folder=${Uri.encode(folderPath)}"
        Logger.i(tag, "Loading VS Code at $url")
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
     * Injects a BroadcastChannel relay into the WebView main page.
     *
     * Browser extensions (which run in a Web Worker) cannot access AndroidBridge
     * directly because addJavascriptInterface only injects into the main page context.
     * This relay listens on a BroadcastChannel and forwards calls to AndroidBridge,
     * bridging the gap between the Web Worker and the main page.
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
                        } else if (d.cmd === 'startGitHubAuth') {
                            AndroidBridge.startGitHubAuth(d.url, token);
                            ch.postMessage({id: d.id, ok: true});
                        } else if (d.cmd === 'openToolchainSettings') {
                            AndroidBridge.openToolchainSettings(token);
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

    private fun recreateWebView() {
        Logger.w(tag, "Recreating WebView after crash")
        val wv = webView ?: return
        val container = findViewById<android.widget.FrameLayout>(R.id.webViewContainer)
        container.removeView(wv)
        wv.destroy()

        val newWebView = WebView(this)
        newWebView.id = R.id.webView
        container.addView(newWebView, 0)
        webView = newWebView

        // Reset bridge so initBridge() re-registers on the new WebView
        bridgeInitialized = false

        setupWebView()
        if (serverPort > 0) {
            loadVSCode(serverPort)
        }
    }

    /**
     * Forwards an OAuth callback deep link to VS Code's authentication handler.
     * URL format: vscodroid://oauth/github?code=XXX&state=YYY
     */
    private fun handleOAuthCallback(uri: Uri) {
        val code = uri.getQueryParameter("code")
        val state = uri.getQueryParameter("state")
        if (code == null || state == null) {
            Logger.w(tag, "OAuth callback missing code or state: $uri")
            return
        }
        Logger.i(tag, "OAuth callback received (state=$state)")
        // Forward to VS Code's auth handler via JS
        val escapedCode = code.replace("'", "\\'")
        val escapedState = state.replace("'", "\\'")
        webView?.evaluateJavascript(
            "window.__vscodroid?.onOAuthCallback?.('$escapedCode', '$escapedState')", null
        )
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
     */
    private fun checkStorageHealth() {
        if (!StorageManager.isStorageLow(this)) return
        val available = StorageManager.formatSize(StorageManager.getAvailableStorage(this))
        Toast.makeText(
            this,
            "Storage low ($available available). Clear caches in Settings.",
            Toast.LENGTH_LONG
        ).show()
        Logger.w(tag, "Storage low: $available available")
    }

    private fun handleIntent(intent: Intent) {
        val uri = intent.data ?: return
        Logger.i(tag, "Received intent with URI: $uri")
        val escaped = uri.toString()
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
        webView?.evaluateJavascript(
            "window.__vscodroid?.onFileOpen?.('${escaped}')", null
        )
    }

    companion object {
        /** Run health check if backgrounded longer than this. */
        private const val HEALTH_CHECK_THRESHOLD_MS = 60_000L   // 1 minute

        /** Force page reload if backgrounded longer than this. */
        private const val FORCE_RELOAD_THRESHOLD_MS = 300_000L  // 5 minutes
    }
}
