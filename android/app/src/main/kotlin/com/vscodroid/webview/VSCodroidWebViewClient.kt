package com.vscodroid.webview

import android.graphics.Bitmap
import android.net.Uri
import android.webkit.RenderProcessGoneDetail
import android.webkit.ServiceWorkerClient
import android.webkit.ServiceWorkerController
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.vscodroid.util.Logger
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL

class VSCodroidWebViewClient(
    private val allowedPort: Int,
    private val onCrash: () -> Unit,
    private val onPageLoaded: () -> Unit
) : WebViewClient() {

    private val tag = "WebViewClient"

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url
        if (isLocalhost(url) || isCdnRedirect(url)) {
            return false
        }
        Logger.w(tag, "Blocked external navigation: $url")
        return true
    }

    /**
     * Intercepts requests to VS Code CDN domains and redirects them to the local server.
     *
     * VS Code's web client has hardcoded CDN URLs (e.g. *.vscode-cdn.net) for webview
     * content, extension resources, etc. Since we run offline on localhost, we rewrite
     * these to fetch from the local VS Code Server instead.
     *
     * CDN path format:  /{quality}/{commit}/{path}
     * Local path format: /{quality}-{commit}/static/{path}
     */
    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        return interceptCdnRequest(request, allowedPort)
    }

    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
        Logger.d(tag, "Page loading: $url")
    }

    override fun onPageFinished(view: WebView, url: String?) {
        Logger.i(tag, "Page loaded: $url")
        onPageLoaded()
    }

    override fun onReceivedError(
        view: WebView,
        request: WebResourceRequest,
        error: WebResourceError
    ) {
        if (request.isForMainFrame) {
            Logger.e(tag, "Page load error: ${error.errorCode} - ${error.description}")
        }
    }

    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        Logger.e(tag, "Render process gone! didCrash=${detail.didCrash()}")
        onCrash()
        return true
    }

    private fun isLocalhost(uri: Uri): Boolean {
        val host = uri.host ?: return false
        return (host == "127.0.0.1" || host == "localhost") && uri.port == allowedPort
    }

    private fun isCdnRedirect(uri: Uri): Boolean {
        val host = uri.host ?: return false
        return host.endsWith(".vscode-cdn.net")
    }

    companion object {
        private const val TAG = "WebViewClient"

        /**
         * Register a ServiceWorkerClient to intercept service worker script fetches.
         *
         * Android WebView does NOT route service worker fetches through
         * WebViewClient.shouldInterceptRequest — they bypass it entirely.
         * Without this, webview iframes at *.vscode-cdn.net origins fail to
         * register their service-worker.js (needed for vscode-resource: loading).
         *
         * Must be called before the WebView loads any page that uses service workers.
         */
        fun setupServiceWorkerInterception(port: Int) {
            try {
                val swController = ServiceWorkerController.getInstance()
                swController.setServiceWorkerClient(object : ServiceWorkerClient() {
                    override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? {
                        return interceptCdnRequest(request, port)
                    }
                })
                Logger.i(TAG, "ServiceWorkerClient registered for CDN interception on port $port")
            } catch (e: Exception) {
                Logger.w(TAG, "Failed to set up ServiceWorkerClient: ${e.message}")
            }
        }

        /**
         * Shared CDN interception logic used by both WebViewClient and ServiceWorkerClient.
         *
         * Handles three types of *.vscode-cdn.net requests:
         * 1. main.vscode-cdn.net — Microsoft resources → empty JSON
         * 2. *.vscode-resource.vscode-cdn.net — extension webview resources → local file/proxy
         * 3. HASH.vscode-cdn.net — VS Code static assets → rewrite to localhost
         */
        internal fun interceptCdnRequest(request: WebResourceRequest, port: Int): WebResourceResponse? {
            val uri = request.url
            val host = uri.host ?: return null

            if (!host.endsWith(".vscode-cdn.net")) return null

            // main.vscode-cdn.net serves Microsoft-specific resources that don't exist locally.
            if (host == "main.vscode-cdn.net") {
                Logger.d(TAG, "CDN blocked (Microsoft resource): $host${uri.path}")
                val emptyJson = "{}".byteInputStream()
                return WebResourceResponse(
                    "application/json", "utf-8", 200, "OK",
                    mapOf("Access-Control-Allow-Origin" to "*"), emptyJson
                )
            }

            // vscode-resource URLs: extension webview resources (images, CSS, JS from extensions).
            // Format: https://SCHEME+AUTHORITY.vscode-resource.vscode-cdn.net/PATH
            // Service workers are disabled; we serve these directly from the filesystem.
            val resourceAuthority = "vscode-resource.vscode-cdn.net"
            if (host.endsWith(".$resourceAuthority")) {
                return interceptResourceRequest(uri, host, resourceAuthority, port)
            }

            val path = uri.path ?: return null
            val localUrl = rewriteCdnUrl(path, uri.query, port)

            if (localUrl == null) {
                Logger.w(TAG, "CDN URL could not be rewritten: $uri")
                return null
            }

            return proxyToLocalhost(localUrl, request.method, "$host${uri.path}")
        }

        /**
         * Serves extension webview resources directly.
         *
         * URL format: https://file+.vscode-resource.vscode-cdn.net/path/to/resource
         * The hostname encodes scheme+authority, the path is the filesystem path.
         *
         * With service workers disabled, this replaces the SW's fetch interception
         * for vscode-resource: URLs. Android WebView's shouldInterceptRequest handles
         * all sub-resource requests from webview iframes, making SWs unnecessary.
         */
        private fun interceptResourceRequest(
            uri: Uri, host: String, resourceAuthority: String, port: Int
        ): WebResourceResponse? {
            val prefix = host.removeSuffix(".$resourceAuthority")
            val parts = prefix.split("+", limit = 2)
            val scheme = parts[0]
            val authority = if (parts.size > 1) parts[1] else ""
            val path = uri.path ?: return notFound("No path")

            if (scheme == "file" || scheme == "vscode-remote") {
                // Local file resource — serve directly from filesystem.
                // Both "file" and "vscode-remote" schemes use local paths in VSCodroid
                // since the server runs on the same device.
                val file = File(path)
                if (!file.exists() || !file.isFile) {
                    Logger.d(TAG, "Resource not found ($scheme): $path")
                    return notFound(path)
                }

                val mimeType = guessMimeType(path)
                Logger.d(TAG, "Resource served ($scheme): $path ($mimeType)")

                return WebResourceResponse(
                    mimeType, null, 200, "OK",
                    mapOf(
                        "Access-Control-Allow-Origin" to "*",
                        "Content-Length" to file.length().toString()
                    ),
                    FileInputStream(file)
                )
            }

            if (scheme == "http" || scheme == "https") {
                // Remote resource — proxy through to actual URL
                val query = uri.query
                val queryPart = if (!query.isNullOrEmpty()) "?$query" else ""
                val actualUrl = "$scheme://$authority$path$queryPart"
                return proxyToLocalhost(actualUrl, "GET", "resource:$authority$path")
            }

            // For other schemes with authority, proxy through localhost server
            if (authority.isNotEmpty()) {
                val query = uri.query
                val queryPart = if (!query.isNullOrEmpty()) "?$query" else ""
                val localUrl = "http://127.0.0.1:$port$path$queryPart"
                return proxyToLocalhost(localUrl, "GET", "remote-resource:$path")
            }

            Logger.w(TAG, "Unknown resource scheme: $scheme (host=$host)")
            return notFound("Unknown scheme: $scheme")
        }

        private fun proxyToLocalhost(url: String, method: String, logTag: String): WebResourceResponse? {
            var connection: HttpURLConnection? = null
            return try {
                val conn = URL(url).openConnection() as HttpURLConnection
                connection = conn
                conn.connectTimeout = 5000
                conn.readTimeout = 10000
                conn.requestMethod = method

                val responseCode = conn.responseCode
                val contentType = conn.contentType ?: guessMimeType(url)
                val encoding = conn.contentEncoding

                Logger.d(TAG, "CDN redirect: $logTag -> $url ($responseCode)")

                val responseStream = if (responseCode < 400) {
                    conn.inputStream
                } else {
                    conn.errorStream ?: ByteArrayInputStream(ByteArray(0))
                }
                val wrappedStream = object : FilterInputStream(responseStream) {
                    override fun close() {
                        try {
                            super.close()
                        } finally {
                            conn.disconnect()
                        }
                    }
                }

                WebResourceResponse(
                    contentType.substringBefore(";").trim(),
                    encoding ?: "utf-8",
                    responseCode,
                    conn.responseMessage ?: "OK",
                    conn.headerFields
                        ?.filterKeys { it != null }
                        ?.mapValues { it.value.joinToString(", ") }
                        ?: emptyMap(),
                    wrappedStream
                )
            } catch (e: Exception) {
                connection?.disconnect()
                Logger.w(TAG, "Proxy failed for $logTag: ${e.message}")
                null
            }
        }

        private fun notFound(detail: String): WebResourceResponse {
            return WebResourceResponse(
                "text/plain", "utf-8", 404, "Not Found",
                mapOf("Access-Control-Allow-Origin" to "*"),
                "Not Found: $detail".byteInputStream()
            )
        }

        /**
         * Rewrites a CDN path to a local server URL.
         *
         * Input:  /stable/cd4ee3b1.../out/vs/workbench/contrib/webview/browser/pre/index.html
         * Output: http://127.0.0.1:{port}/stable-cd4ee3b1.../static/out/vs/workbench/contrib/webview/browser/pre/index.html
         */
        private fun rewriteCdnUrl(path: String, query: String?, port: Int): String? {
            val segments = path.removePrefix("/").split("/", limit = 3)
            if (segments.size < 2) return null

            val quality = segments[0]   // "stable" or "insider"
            val commit = segments[1]    // commit hash
            val rest = if (segments.size > 2) segments[2] else ""

            val localPath = "/$quality-$commit/static/$rest"
            val queryPart = if (!query.isNullOrEmpty()) "?$query" else ""
            return "http://127.0.0.1:$port$localPath$queryPart"
        }

        private fun guessMimeType(path: String): String {
            return when {
                path.endsWith(".html") -> "text/html"
                path.endsWith(".js") -> "application/javascript"
                path.endsWith(".css") -> "text/css"
                path.endsWith(".json") -> "application/json"
                path.endsWith(".svg") -> "image/svg+xml"
                path.endsWith(".png") -> "image/png"
                path.endsWith(".woff2") -> "font/woff2"
                path.endsWith(".woff") -> "font/woff"
                path.endsWith(".ttf") -> "font/ttf"
                else -> "application/octet-stream"
            }
        }
    }
}
