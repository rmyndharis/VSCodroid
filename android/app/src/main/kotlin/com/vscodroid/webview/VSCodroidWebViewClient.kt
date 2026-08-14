package com.vscodroid.webview

import android.content.Context
import android.content.Intent
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
import com.vscodroid.util.Environment
import com.vscodroid.util.Logger
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * The directories this app publishes to content rendered inside the WebView,
 * whatever folder is open. [resourceRootsInForce] adds the open folder to them.
 *
 * Resolved once and handed in as plain strings rather than being derived from a
 * Context inside the client: a WebViewClient lives as long as the WebView
 * holding it, and the Context available where it is constructed is the
 * Activity's.
 *
 * Canonicalised here so that [resolveWebviewResource] compares two paths in the
 * same form. That function resolves the request before testing it, so a root
 * left as written would be tested against something it cannot equal wherever a
 * link sits anywhere along either path -- and the shape of that failure is
 * every extension resource silently 404ing, which reads as anything but a path
 * problem.
 *
 * Deliberately not a claim that any particular root does contain one. The point
 * is that nothing here needs to know, which is what keeps this correct when the
 * layout underneath changes. A root that cannot be canonicalised is dropped,
 * costing that root its resources and granting nothing.
 */
internal fun publishedResourceRoots(context: Context): List<String> = listOf(
    // Built-in extensions ship inside the server tree, so this is what
    // markdown preview, the notebook renderers and Simple Browser load their
    // own assets from.
    Environment.getServerDir(context),
    // Installed and bundled extensions.
    Environment.getExtensionsDir(context),
    // The two places a workspace normally lives. Kept here as well as arriving
    // through the open-folder supplier, because that supplier answers nothing
    // until the first navigation completes, and resources requested during that
    // first load would otherwise be refused.
    Environment.getProjectsDir(context),
    Environment.getSafMirrorsDir(context),
).mapNotNull(::canonicalOrNull)

/**
 * The file a resource request names, or null if it is not a published resource.
 *
 * The published roots are named individually rather than described by a prefix
 * because everything this app owns -- the SSH private key written without a
 * passphrase, the server's connection token -- shares a prefix with everything
 * it serves. A prefix test over app-private storage admits all of it, so it can
 * only ever stop a traversal out of the sandbox, never a read within it.
 *
 * Symbolic links are resolved rather than normalised away lexically. A
 * workspace is a published root and a workspace is routinely a checked-out
 * repository, so a link inside one is attacker-supplied in the ordinary case;
 * `..` handling alone would follow it out.
 *
 * The canonical path is what comes back, not the path as asked for, so that the
 * file opened is the file that was checked.
 */
internal fun resolveWebviewResource(requestedPath: String, roots: List<String>): File? {
    val canonical = canonicalOrNull(requestedPath) ?: return null
    // Compared with the separator appended: a root's name is also a prefix of
    // its siblings' names, and creating such a sibling is within reach of
    // anything running in the extension host.
    return if (roots.any { canonical.startsWith("$it/") }) File(canonical) else null
}

/**
 * The locations that must not be readable through a resource request, whatever
 * folder happens to be open.
 *
 * Canonicalised once here rather than on every request, which is what lets
 * [workspaceRootOrNull] take them as a precondition instead of resolving them
 * itself.
 */
internal fun sensitiveLocations(context: Context): List<String> = listOf(
    Environment.getConnectionTokenPath(context),
    Environment.getSshDir(context),
).mapNotNull(::canonicalOrNull)

/**
 * The open folder as a resource root, or null if publishing it would publish
 * something that must stay unreadable.
 *
 * The workspace has to be a root — VS Code's own `localResourceRoots` includes
 * it, and without it a markdown preview cannot show an image sitting next to
 * the file being previewed. It cannot be a *static* root, because the user
 * chooses it: opening the home directory would publish the SSH key, which is
 * the whole thing being closed here.
 *
 * Overlap is tested in **both** directions. Containment alone would let the
 * user open `~/.ssh` itself as the workspace and get the directory published
 * because it contains nothing sensitive — it *is* the sensitive thing.
 *
 * [sensitive] is expected already canonical; [sensitiveLocations] is what
 * produces it, and doing that work here would put it on every request.
 */
internal fun workspaceRootOrNull(candidatePath: String?, sensitive: List<String>): String? {
    val candidate = candidatePath?.let(::canonicalOrNull) ?: return null
    return if (sensitive.any { overlaps(candidate, it) }) null else candidate
}

/**
 * The roots in force for one request: the published set, plus the open folder
 * when it is safe to publish.
 *
 * Both entry points go through here so that the two cannot drift apart. A
 * rejection is reported because the failure it causes — resources missing from
 * the user's own workspace — otherwise looks like a bug rather than a refusal.
 * Nothing is logged in the ordinary case: an accepted folder and an absent one
 * are both silent, so this only speaks when the workspace holds a location that
 * must stay unreadable.
 */
internal fun resourceRootsInForce(
    published: List<String>, sensitive: List<String>, candidate: String?
): List<String> {
    val workspace = workspaceRootOrNull(candidate, sensitive)
    if (candidate != null && workspace == null) {
        Logger.w(
            "WebViewClient",
            "Workspace not published as a resource root, it holds a sensitive location: $candidate"
        )
    }
    return published + listOfNotNull(workspace)
}

/**
 * Whether two canonical paths name the same place or one holds the other.
 *
 * Symmetric on purpose. Both arguments are directories-or-files whose
 * relationship matters in either order, and the separator is appended on each
 * side for the same reason it is in [resolveWebviewResource]: a name is also a
 * prefix of its siblings' names.
 */
private fun overlaps(a: String, b: String): Boolean =
    a == b || a.startsWith("$b/") || b.startsWith("$a/")

private fun canonicalOrNull(path: String): String? =
    try {
        File(path).canonicalPath
    } catch (e: IOException) {
        null
    }

class VSCodroidWebViewClient(
    private val allowedPort: Int,
    private val resourceRoots: List<String>,
    private val sensitiveLocations: List<String>,
    private val openFolder: () -> String?,
    private val connectionToken: () -> String?,
    private val onCrash: () -> Unit,
    private val onPageLoaded: (String?) -> Unit
) : WebViewClient() {

    private val tag = "WebViewClient"

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url
        if (isLocalhost(url) || isCdnRedirect(url)) {
            return false
        }
        // Fix #7: Open external URLs in system browser instead of blocking silently
        try {
            val intent = Intent(Intent.ACTION_VIEW, url)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            view.context.startActivity(intent)
            Logger.i(tag, "Opened external URL: $url")
        } catch (e: Exception) {
            Logger.e(tag, "Failed to open external URL: $url", e)
        }
        return true  // Don't navigate WebView to external URL
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
        return interceptCdnRequest(
            request, allowedPort, connectionToken(), resourceRoots, sensitiveLocations, openFolder
        )
    }

    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
        Logger.d(tag, "Page loading: $url")
    }

    /**
     * Carries the URL, because this is the only notice Kotlin gets when VS Code
     * switches folders on its own — it navigates this WebView without going
     * through us, so the URL is the only truthful record of the open workspace.
     *
     * That this notice is *sufficient* is a property of the shipped bundles, not
     * an assumption: neither `out/vs/code/browser/workbench/workbench.js` nor
     * `out/vs/workbench/workbench.web.main.internal.js` contains a single
     * `pushState` or `replaceState`, so the workbench cannot change its URL
     * without a real navigation. That is why there is no
     * `doUpdateVisitedHistory` override here; re-run the grep before adding one.
     */
    override fun onPageFinished(view: WebView, url: String?) {
        Logger.i(tag, "Page loaded: $url")
        onPageLoaded(url)
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
        /** VS Code assets are versioned by commit hash — safe to cache forever. */
        private const val CACHE_IMMUTABLE = "public, max-age=31536000, immutable"

        /**
         * Register a ServiceWorkerClient to intercept service worker script fetches.
         *
         * Android WebView does NOT route service worker fetches through
         * WebViewClient.shouldInterceptRequest — they bypass it entirely.
         * Without this, webview iframes at *.vscode-cdn.net origins fail to
         * register their service-worker.js (needed for vscode-resource: loading).
         *
         * Must be called before the WebView loads any page that uses service workers.
         *
         * Takes the same roots and the same open-folder supplier as the client
         * constructor, and for the same reason: this is the second way a
         * resource request reaches [interceptCdnRequest], so anything applied to
         * only one of them leaves the other answering from the old rules. Both
         * compose them through [resourceRootsInForce] rather than each doing it,
         * so there is one rule and not two copies of one.
         */
        fun setupServiceWorkerInterception(
            port: Int,
            resourceRoots: List<String>,
            sensitiveLocations: List<String>,
            openFolder: () -> String?,
            connectionToken: () -> String?
        ) {
            try {
                val swController = ServiceWorkerController.getInstance()
                swController.setServiceWorkerClient(object : ServiceWorkerClient() {
                    override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? {
                        return interceptCdnRequest(
                            request, port, connectionToken(),
                            resourceRoots, sensitiveLocations, openFolder
                        )
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
        internal fun interceptCdnRequest(
            request: WebResourceRequest,
            port: Int,
            token: String?,
            resourceRoots: List<String>,
            sensitiveLocations: List<String>,
            openFolder: () -> String?
        ): WebResourceResponse? {
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
                // Composed here rather than by each caller, so that neither
                // entry point can forget to. The supplier is invoked only on
                // this branch: the other two answer without touching the
                // filesystem, and every workbench asset takes one of them.
                return interceptResourceRequest(
                    uri, host, resourceAuthority, port, token,
                    resourceRootsInForce(resourceRoots, sensitiveLocations, openFolder())
                )
            }

            val path = uri.path ?: return null
            val localUrl = rewriteCdnUrl(path, uri.query, port, token)

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
            uri: Uri, host: String, resourceAuthority: String, port: Int, token: String?,
            resourceRoots: List<String>
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
                val file = resolveWebviewResource(path, resourceRoots) ?: run {
                    Logger.w(TAG, "Resource outside the published roots refused: $path")
                    return notFound("Access denied")
                }
                if (!file.exists() || !file.isFile) {
                    Logger.d(TAG, "Resource not found ($scheme): $path")
                    return notFound(path)
                }

                val mimeType = guessMimeType(path)
                Logger.d(TAG, "Resource served ($scheme): $path ($mimeType)")

                return WebResourceResponse(
                    mimeType, null, 200, "OK",
                    buildMap {
                        put("Access-Control-Allow-Origin", "*")
                        put("Content-Length", file.length().toString())
                        // Static resources are versioned by commit hash in the URL,
                        // so they never change — cache aggressively for warm starts.
                        if (isStaticAsset(path)) {
                            put("Cache-Control", CACHE_IMMUTABLE)
                        }
                    },
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
                val localUrl = withToken("http://127.0.0.1:$port$path$queryPart", token)
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

                val headers = conn.headerFields
                    ?.filterKeys { it != null }
                    ?.mapValues { it.value.joinToString(", ") }
                    ?.toMutableMap()
                    ?: mutableMapOf()

                // Ensure static assets from the local server get cached.
                // VS Code Server may not set Cache-Control on all responses.
                if (responseCode in 200..299 && isStaticAsset(url) &&
                    !headers.containsKey("Cache-Control")
                ) {
                    headers["Cache-Control"] = CACHE_IMMUTABLE
                }

                WebResourceResponse(
                    contentType.substringBefore(";").trim(),
                    encoding ?: "utf-8",
                    responseCode,
                    conn.responseMessage ?: "OK",
                    headers,
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
        private fun rewriteCdnUrl(path: String, query: String?, port: Int, token: String?): String? {
            val segments = path.removePrefix("/").split("/", limit = 3)
            if (segments.size < 2) return null

            val quality = segments[0]   // "stable" or "insider"
            val commit = segments[1]    // commit hash
            val rest = if (segments.size > 2) segments[2] else ""

            val localPath = "/$quality-$commit/static/$rest"
            val queryPart = if (!query.isNullOrEmpty()) "?$query" else ""
            return withToken("http://127.0.0.1:$port$localPath$queryPart", token)
        }

        /**
         * Appends the connection token to a URL that points at our own server.
         *
         * These requests need it carried explicitly. The workbench authenticates
         * itself with the `vscode-tkn` cookie, but everything here is re-fetched
         * through HttpURLConnection, which has its own cookie store and shares
         * nothing with the WebView — so without the query parameter the server
         * answers 403 and the asset silently fails to load.
         *
         * Deliberately not folded into proxyToLocalhost(), despite the name of
         * that function: one of its callers proxies http/https resources to
         * whatever host an extension's webview references, and putting the token
         * there would hand our credential to that host.
         */
        private fun withToken(url: String, token: String?): String {
            if (token.isNullOrEmpty()) return url
            val separator = if (url.contains('?')) "&" else "?"
            return "$url$separator" + "tkn=" + Uri.encode(token)
        }

        private fun isStaticAsset(path: String): Boolean {
            return path.endsWith(".js") || path.endsWith(".css") ||
                    path.endsWith(".woff2") || path.endsWith(".woff") ||
                    path.endsWith(".ttf") || path.endsWith(".svg") ||
                    path.endsWith(".png") || path.endsWith(".jpg")
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
