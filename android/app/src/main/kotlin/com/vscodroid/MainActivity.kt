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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.vscodroid.util.Environment
import com.vscodroid.bridge.AndroidBridge
import com.vscodroid.bridge.ClipboardBridge
import com.vscodroid.bridge.SecurityManager
import com.vscodroid.keyboard.ExtraKeyRow
import com.vscodroid.keyboard.KeyInjector
import com.vscodroid.service.NodeService
import com.vscodroid.util.Logger
import com.vscodroid.webview.VSCodroidWebChromeClient
import com.vscodroid.webview.VSCodroidWebView
import com.vscodroid.webview.VSCodroidWebViewClient

class MainActivity : AppCompatActivity() {
    private val tag = "MainActivity"

    private var webView: WebView? = null
    private var extraKeyRow: ExtraKeyRow? = null
    private var nodeService: NodeService? = null
    private var serviceBound = false
    private var serverPort = 0
    private var backgroundedAt = 0L

    private lateinit var securityManager: SecurityManager

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        Logger.i(tag, "Notification permission granted=$granted")
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

        setupWebView()
        setupExtraKeyRow()
        setupBackNavigation()
        requestNotificationPermission()
        startAndBindService()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onDestroy() {
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
            wv.loadData(
                """<html><body style="background:#1e1e1e;color:#888;font-family:sans-serif;
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
        val wv = webView ?: return

        securityManager = SecurityManager(port)
        val clipboardBridge = ClipboardBridge(this)
        val bridge = AndroidBridge(
            context = this,
            security = securityManager,
            clipboard = clipboardBridge,
            onBackPressed = { false },
            onMinimize = { moveTaskToBack(true) }
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

        val folder = folderPath ?: Environment.getProjectsDir(this)
        val url = "http://127.0.0.1:$port/?folder=${Uri.encode(folder)}"
        Logger.i(tag, "Loading VS Code at $url")
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

        setupWebView()
        if (serverPort > 0) {
            loadVSCode(serverPort)
        }
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
