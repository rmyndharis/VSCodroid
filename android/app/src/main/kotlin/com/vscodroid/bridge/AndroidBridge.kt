package com.vscodroid.bridge

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.webkit.JavascriptInterface
import androidx.browser.customtabs.CustomTabsIntent
import com.google.android.play.core.assetpacks.model.AssetPackStatus
import com.vscodroid.setup.ToolchainManager
import com.vscodroid.setup.ToolchainRegistry
import com.vscodroid.storage.SafStorageManager
import com.vscodroid.util.CrashReporter
import com.vscodroid.util.Logger
import com.vscodroid.util.StorageManager
import com.vscodroid.webview.redactToken
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * How long generateSshKey waits for ssh-keygen before killing it.
 *
 * Generous relative to the work -- key generation is arithmetic and finishes in
 * well under a second -- because the number is not a performance budget. It is
 * the point at which "slow" becomes "never", and the caller is a WebView bridge
 * method whose JavaScript side has no timeout of its own.
 */
private const val KEYGEN_TIMEOUT_SECONDS = 60L

/**
 * How long generateSshKey waits for the stdout drain after the child has exited.
 *
 * Not a second timeout on the work -- the process is already gone by the time
 * this is used, so the only thing outstanding is whatever sits in the pipe
 * buffer. It is a bound on principle: the point of draining on a separate thread
 * is that no unbounded wait remains on the bridge thread, and an unbounded join
 * would put one straight back.
 */
private const val DRAIN_JOIN_MILLIS = 1_000L

/**
 * How long after this app opens an authentication tab a callback is still taken.
 *
 * Chosen against the sign-in it has to survive rather than against the attack.
 * A real one is a person reading a consent screen, fetching a password manager
 * and possibly a second factor, so a tight window would reject genuine returns —
 * and a rejected sign-in is a bug report, while the window being ten minutes
 * instead of one still leaves the relay shut for the rest of the day.
 */
internal const val AUTH_TAB_WINDOW_MILLIS = 10 * 60 * 1000L

/**
 * When this app last handed an https URL to a browser.
 *
 * Any of them, not only a sign-in, and that is not a slip: nothing here can tell
 * an authorisation page from a documentation link, since both arrive as an https
 * URL through the same bridge method. Opening a link therefore widens the window
 * as much as starting a sign-in does. It still bounds an entry point that had no
 * bound at all, to the ten minutes after the user last left for a browser rather
 * than to every moment the app is running.
 *
 * The `vscodroid://callback` relay had nothing to test but the shape of the URI,
 * and its VIEW filter is exported and BROWSABLE, so the value it writes into the
 * workbench's storage could be supplied at any moment by anything on the device.
 * The one fact that separates a real return from an invented one is whether this
 * app asked for it, and until now nobody was recording that.
 *
 * Deliberately process-scoped and not persisted. The ids the workbench waits on
 * live in an in-memory Set that does not survive the page, so after a restart
 * there is no pending request a relayed value could satisfy — persisting this
 * would only widen the window for callbacks that could not be delivered anyway.
 *
 * Time is read from the monotonic clock by callers, not from wall time, so that
 * changing the device clock mid-sign-in neither breaks a real return nor
 * reopens the window.
 */
object AuthTabWindow {
    // Named apart from the accessor on purpose. `private var openedAt` beside
    // `fun openedAt()` compiles and resolves to the field, but the reader has to
    // work that out, and "does this recurse?" is not a question worth leaving in
    // a security check.
    @Volatile
    private var lastOpenedAtMillis = 0L

    fun opened(nowMillis: Long) {
        lastOpenedAtMillis = nowMillis
    }

    fun openedAt(): Long = lastOpenedAtMillis
}

class AndroidBridge(
    private val context: Context,
    private val security: SecurityManager,
    private val clipboard: ClipboardBridge,
    private val onBackPressed: () -> Boolean,
    private val onMinimize: () -> Unit,
    private val onOpenFolderPicker: () -> Unit = {},
    private val onOpenRecentFolder: (Uri) -> Unit = {},
    private val onShowAbout: () -> Unit = {},
    private val safManager: SafStorageManager? = null
) {
    private val tag = "AndroidBridge"

    @JavascriptInterface
    fun copyToClipboard(authToken: String, text: String): Boolean {
        if (!security.validateToken(authToken)) return false
        return clipboard.copyToClipboard(text)
    }

    @JavascriptInterface
    fun readFromClipboard(authToken: String): String? {
        if (!security.validateToken(authToken)) return null
        return clipboard.readFromClipboard()
    }

    @JavascriptInterface
    fun hasClipboardText(authToken: String): Boolean {
        if (!security.validateToken(authToken)) return false
        return clipboard.hasClipboardText()
    }

    /**
     * Opens a URL outside the editor. Any URL. Says whether it did.
     *
     * There is no allow-list, and its absence is the product decision rather than
     * an omission. VSCodroid is a development environment: a link can point at a
     * LAN dev server on plain http, a private registry, a staging host, or a
     * scheme belonging to another tool on the device. A list of permitted shapes
     * refuses the work this app exists for, and it did -- `http://192.168.1.50:5173`
     * was refused here while the same URL followed as a link opened, because the
     * two routes had different rules and the workbench chooses the route: VS Code
     * sends "Open in Browser" through `window.open`, which `MainActivity` relays
     * to this method, while an ordinary link navigation reaches
     * `VSCodroidWebViewClient.shouldOverrideUrlLoading` and has never been
     * filtered. Same click, same URL, two outcomes, one of them silent.
     *
     * The session token above still gates the call, and that is a different
     * question: it asks whether the caller is our own page, not whether the
     * destination is one somebody approved.
     *
     * The answer is the other half. This returned Unit, so a launch that failed
     * and a launch that worked were the same event from outside: the relay in
     * `MainActivity.injectBridgeRelay` reported `ok: true` either way, and the
     * bundled extension's "Open in Browser" closed its input box with its own
     * error handler sitting unreachable behind a promise that always resolved.
     *
     * @return true when the URL was handed to a browser, false when the token
     *   was rejected or no activity took the intent -- which, with no filtering
     *   left, is the only way a well-formed call fails.
     */
    @JavascriptInterface
    fun openExternalUrl(url: String, authToken: String): Boolean {
        if (!security.validateToken(authToken)) return false
        return try {
            val uri = Uri.parse(url)
            val isLocalhost = uri.host == "127.0.0.1" || uri.host == "localhost"
            // Use system browser for localhost URLs (dev server preview needs full browser),
            // Chrome Custom Tabs for https (keeps user in-app, handles OAuth redirects).
            if (uri.scheme == "https" && !isLocalhost) {
                // Recorded before the launch, not after: launchUrl hands off to
                // another process, and a browser that answers instantly would
                // otherwise be able to return before the window it needs is open.
                AuthTabWindow.opened(SystemClock.elapsedRealtime())
                val customTabsIntent = CustomTabsIntent.Builder()
                    .setShowTitle(true)
                    .build()
                customTabsIntent.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                customTabsIntent.launchUrl(context, uri)
            } else {
                val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            }
            true
        } catch (e: Exception) {
            Logger.e(tag, "Failed to open URL: $url", e)
            false
        }
    }

    @JavascriptInterface
    fun onBackPressed(authToken: String): Boolean {
        if (!security.validateToken(authToken)) return false
        return onBackPressed.invoke()
    }

    @JavascriptInterface
    fun minimizeApp(authToken: String) {
        if (!security.validateToken(authToken)) return
        onMinimize()
    }

    @JavascriptInterface
    fun getDeviceInfo(authToken: String): String {
        if (!security.validateToken(authToken)) return "{}"
        val displayMetrics = context.resources.displayMetrics
        return JSONObject().apply {
            put("model", Build.MODEL)
            put("android", Build.VERSION.SDK_INT)
            put("api", Build.VERSION.SDK_INT)
            put("manufacturer", Build.MANUFACTURER)
            put("vscodroid_version", getVersionName())
            put("screen_width", displayMetrics.widthPixels)
            put("screen_height", displayMetrics.heightPixels)
            put("screen_density", displayMetrics.density)
            put("orientation", if (context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) "landscape" else "portrait")
        }.toString()
    }

    @JavascriptInterface
    fun getThemeMode(authToken: String): String {
        if (!security.validateToken(authToken)) return ""
        val nightMode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return if (nightMode == Configuration.UI_MODE_NIGHT_YES) "dark" else "light"
    }

    /**
     * Relays a line the page chose to print into logcat.
     *
     * Redacted, and this is the one sink where the token is the LIKELY case
     * rather than a theoretical one. Everywhere else the app redacts, the string
     * was built by its own code and carries a known `tkn=` shape; here the text is
     * whatever the workbench decided to log, and the workbench holds the
     * connection token. A page logging its own URL is ordinary behaviour.
     *
     * The session-token check above says this call came from our page, not that
     * what it carries is safe to print. logcat is readable by anything holding
     * `READ_LOGS`, and `warn` and `error` land on `Logger` methods that are not
     * gated on a debuggable build, so they ship.
     *
     * `tag` goes through it too. It is page-supplied and there is no reason it
     * could not carry a URL.
     *
     * Ceiling, stated because it is wider here than at the other call sites: the
     * redaction matches the `tkn=` parameter, so a page that prints a bare token,
     * or one re-encoded as `tkn%3D` inside another parameter, passes through. See
     * [redactToken]. Closing that would mean redacting by the token's value, which
     * this class could do and the webview call sites could not — worth knowing
     * before anyone assumes parity between them.
     */
    @JavascriptInterface
    fun logToNative(authToken: String, level: String, tag: String, message: String) {
        if (!security.validateToken(authToken)) return
        val safeTag = redactToken(tag)
        val safeMessage = redactToken(message)
        when (level) {
            "debug" -> Logger.d(safeTag, safeMessage)
            "info" -> Logger.i(safeTag, safeMessage)
            "warn" -> Logger.w(safeTag, safeMessage)
            "error" -> Logger.e(safeTag, safeMessage)
            else -> Logger.d(safeTag, safeMessage)
        }
    }

    // -- SAF (Storage Access Framework) --

    /**
     * Opens the Android SAF folder picker to select any folder on the device.
     * The result is handled by MainActivity and triggers a folder sync + reload.
     */
    @JavascriptInterface
    fun openFolderPicker(authToken: String) {
        if (!security.validateToken(authToken)) return
        Logger.i(tag, "Opening SAF folder picker")
        onOpenFolderPicker()
    }

    /**
     * Returns a JSON array of recently opened SAF folders.
     * Each entry has: uri, name, lastOpened.
     */
    @JavascriptInterface
    fun getRecentFolders(authToken: String): String {
        if (!security.validateToken(authToken)) return "[]"
        val manager = safManager ?: return "[]"
        val folders = manager.getPersistedFolders()
        return JSONArray().apply {
            folders.forEach { f ->
                put(JSONObject().apply {
                    put("uri", f.uri.toString())
                    put("name", f.displayName)
                    put("lastOpened", f.lastOpened)
                })
            }
        }.toString()
    }

    /**
     * Opens a previously selected SAF folder by its URI string.
     */
    @JavascriptInterface
    fun openRecentFolder(authToken: String, uriString: String) {
        if (!security.validateToken(authToken)) return
        val uri = Uri.parse(uriString)
        Logger.i(tag, "Opening recent SAF folder: $uri")
        onOpenRecentFolder(uri)
    }

    // -- Storage Management --

    /**
     * Returns per-component storage breakdown as JSON.
     * Keys: vscode_server, extensions, user_data, logs, tools, saf_mirrors, cache, total
     * Values in bytes.
     */
    @JavascriptInterface
    fun getStorageBreakdown(authToken: String): String {
        if (!security.validateToken(authToken)) return "{}"
        return StorageManager.getStorageBreakdown(context).toString()
    }

    /**
     * Clears caches (npm, tmp, crash logs, VS Code logs). Returns bytes freed.
     */
    @JavascriptInterface
    fun clearCaches(authToken: String): Long {
        if (!security.validateToken(authToken)) return 0
        return StorageManager.clearCaches(context)
    }

    /**
     * Returns available storage in bytes.
     */
    @JavascriptInterface
    fun getAvailableStorage(authToken: String): Long {
        if (!security.validateToken(authToken)) return 0
        return StorageManager.getAvailableStorage(context)
    }

    // -- Crash Reporting --

    /**
     * Returns the last crash log text, or null if no crashes recorded.
     */
    @JavascriptInterface
    fun getLastCrash(authToken: String): String? {
        if (!security.validateToken(authToken)) return null
        return CrashReporter.getLastCrash()
    }

    /**
     * Generates a full bug report (device info + crash logs + server logs).
     */
    @JavascriptInterface
    fun generateBugReport(authToken: String): String {
        if (!security.validateToken(authToken)) return ""
        return CrashReporter.generateBugReport(context)
    }

    /**
     * Clears stored crash logs.
     */
    @JavascriptInterface
    fun clearCrashLogs(authToken: String) {
        if (!security.validateToken(authToken)) return
        CrashReporter.clearCrashLogs()
    }

    // -- Toolchain Settings --

    /**
     * Opens the ToolchainActivity settings screen for managing toolchains.
     */
    @JavascriptInterface
    fun openToolchainSettings(authToken: String) {
        if (!security.validateToken(authToken)) return
        Logger.i(tag, "Opening toolchain settings")
        showToolchainSettings()
    }

    /** The navigation on its own, so a non-bridge caller can reach it too. */
    private fun showToolchainSettings() {
        val intent = Intent(context, com.vscodroid.ToolchainActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    // -- Toolchain Management --

    /**
     * The bridge's own manager, wired to hear back from what it starts.
     *
     * It had no `onStateChange` at all, and on a Play install that is the
     * difference between an install and a stall. Play answers a large download
     * with REQUIRES_USER_CONFIRMATION and then waits: nothing further happens
     * until a system dialog is shown and accepted. `ToolchainManager` reports
     * that status and goes no further -- it has no Activity of its own -- so
     * with no listener on this side the status was reported into a null and
     * `installToolchain` from the page did nothing, forever, with no error
     * anywhere the user could see.
     */
    private val toolchainManager: ToolchainManager by lazy {
        ToolchainManager(context).apply {
            onStateChange = { packName, status, _ -> onToolchainState(packName, status) }
        }
    }

    /**
     * Resolves what a Play install needs from a foreground component, and
     * balances the registration [ToolchainManager.install] makes on our behalf.
     *
     * The unregister is the other half of a pair that had no other half:
     * `install()` calls `registerListener()` before every Play fetch and nothing
     * here ever undid it, so this instance stayed subscribed to Play Core for
     * the life of the process. Terminal states are where the pair closes --
     * after COMPLETED, FAILED or CANCELED there is nothing further to hear, and
     * the next `install()` subscribes again. Harmless on the HTTP path, which
     * never registers: the unregister is guarded and does nothing.
     */
    private fun onToolchainState(packName: String, status: Int) {
        when (status) {
            AssetPackStatus.REQUIRES_USER_CONFIRMATION -> confirmLargeDownload(packName)
            AssetPackStatus.COMPLETED,
            AssetPackStatus.FAILED,
            AssetPackStatus.CANCELED -> toolchainManager.unregisterListener()
        }
    }

    /**
     * Shows Play's own confirmation dialog for a download it will not start
     * unattended.
     *
     * The dialog needs an Activity, and this bridge is handed one --
     * `MainActivity` constructs it with `context = this`. So the ordinary path
     * resolves the confirmation where the user already is, rather than sending
     * them somewhere to repeat themselves.
     *
     * The fallback exists because the constructor takes a `Context`, not an
     * `Activity`, and nothing enforces which one arrives. It opens the toolchain
     * screen, which builds its own manager and shows this same dialog when it
     * sees the status. Worth being plain about what that is and is not: it is a
     * visible surface for a download that is otherwise stuck silently, not a
     * guarantee the confirmation reappears -- Play Core does not promise to
     * re-emit a state to a listener that registers afterwards. Surfacing the
     * stall is the point.
     */
    private fun confirmLargeDownload(packName: String) {
        val activity = context as? Activity
        if (activity != null) {
            Logger.i(tag, "Pack $packName needs confirmation; asking the user")
            activity.runOnUiThread { toolchainManager.showConfirmationDialog(activity) }
            return
        }
        Logger.w(tag, "Pack $packName needs confirmation and this bridge has no Activity; " +
            "opening the toolchain screen so the download is not stuck out of sight")
        showToolchainSettings()
    }

    /**
     * Returns JSON array of all available toolchains (installed or not).
     * Each entry: { packName, displayName, description, estimatedSize, installed }
     */
    @JavascriptInterface
    fun getAvailableToolchains(authToken: String): String {
        if (!security.validateToken(authToken)) return "[]"
        val installed = toolchainManager.getInstalledToolchains()
        return JSONArray().apply {
            ToolchainRegistry.available.forEach { tc ->
                put(JSONObject().apply {
                    put("packName", tc.packName)
                    put("displayName", tc.displayName)
                    put("description", tc.description)
                    put("estimatedSize", tc.estimatedSize)
                    put("installed", installed.contains(tc.packName.removePrefix("toolchain_")))
                })
            }
        }.toString()
    }

    /**
     * Returns JSON array of installed toolchain names (e.g. ["go", "ruby"]).
     */
    @JavascriptInterface
    fun getInstalledToolchains(authToken: String): String {
        if (!security.validateToken(authToken)) return "[]"
        return JSONArray(toolchainManager.getInstalledToolchains()).toString()
    }

    /**
     * Starts async download + install of a toolchain by pack name or short name.
     */
    @JavascriptInterface
    fun installToolchain(name: String, authToken: String) {
        if (!security.validateToken(authToken)) return
        Logger.i(tag, "JS requested toolchain install: $name")
        toolchainManager.install(name)
    }

    /**
     * Removes a toolchain (deletes files, symlinks, env vars).
     */
    @JavascriptInterface
    fun removeToolchain(name: String, authToken: String) {
        if (!security.validateToken(authToken)) return
        Logger.i(tag, "JS requested toolchain removal: $name")
        toolchainManager.uninstall(name)
    }

    /**
     * Cancels an in-progress toolchain download.
     */
    @JavascriptInterface
    fun cancelToolchainInstall(name: String, authToken: String) {
        if (!security.validateToken(authToken)) return
        toolchainManager.cancel(name)
    }

    // -- About --

    @JavascriptInterface
    fun showAboutDialog(authToken: String) {
        if (!security.validateToken(authToken)) return
        onShowAbout()
    }

    // -- SSH Key Management --

    /**
     * Generates an ed25519 SSH key pair in ~/.ssh/.
     * Returns JSON: {success: boolean, publicKey?: string, error?: string}
     *
     * Uses the bundled ssh-keygen binary (libssh-keygen.so) via ProcessBuilder.
     * Empty passphrase for mobile UX — keys are protected by app sandbox.
     */
    @JavascriptInterface
    fun generateSshKey(authToken: String, comment: String): String {
        if (!security.validateToken(authToken)) return """{"success":false,"error":"unauthorized"}"""
        val result = JSONObject()
        try {
            val nativeLibDir = context.applicationInfo.nativeLibraryDir
            val homeDir = "${context.filesDir}/home"
            val sshDir = File(homeDir, ".ssh")
            sshDir.mkdirs()
            val keyFile = File(sshDir, "id_ed25519")

            // Don't overwrite existing key
            if (keyFile.exists()) {
                val pubKey = File("${keyFile.absolutePath}.pub").readText().trim()
                result.put("success", true)
                result.put("publicKey", pubKey)
                result.put("existed", true)
                return result.toString()
            }

            val keyComment = if (comment.isBlank()) "vscodroid@android" else comment

            val process = ProcessBuilder(
                "$nativeLibDir/libssh-keygen.so",
                "-t", "ed25519",
                "-f", keyFile.absolutePath,
                "-N", "",  // empty passphrase
                "-C", keyComment
            ).apply {
                environment()["HOME"] = homeDir
                environment()["LD_LIBRARY_PATH"] = "$nativeLibDir:${context.filesDir}/usr/lib"
                redirectErrorStream(true)
            }.start()

            // Drained on its own thread, and that ordering is the guarantee
            // rather than a detail. readText() returns at EOF, and EOF on a
            // child's stdout arrives when the child exits -- so reading before
            // the wait makes the wait unreachable in precisely the case it was
            // written for. A ssh-keygen that hangs while holding stdout open
            // parked this thread with no bound at all, and this thread is the
            // WebView's bridge thread: every other bridge call queues behind it,
            // so one stuck key generation freezes clipboard, storage and
            // toolchain calls too. The timeout below was already here, already
            // documented as protecting the caller, and simply never got to run.
            //
            // Draining concurrently is also what keeps the wait honest in the
            // other direction: with waitFor first and no reader, a child that
            // filled the ~64 KB pipe buffer would block on write and never
            // exit, turning the same hang into a guaranteed timeout instead of
            // a completed key.
            var output = ""
            val drain = Thread {
                output = try {
                    process.inputStream.bufferedReader().readText()
                } catch (e: Exception) {
                    ""
                }
            }
            drain.isDaemon = true
            drain.start()

            // Bounded, because an unbounded wait here parks the JavaScript caller
            // for as long as the binary hangs -- and the caller is a WebView
            // bridge method, so what the user sees is a dialog that never
            // returns and a UI element stuck mid-action. Key generation is
            // arithmetic and finishes in well under a second; a minute means it
            // is not going to finish at all.
            if (!process.waitFor(KEYGEN_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly()
                Logger.e(tag, "ssh-keygen did not finish within ${KEYGEN_TIMEOUT_SECONDS}s; killed")
                result.put("success", false)
                result.put("error", "ssh-keygen did not finish within ${KEYGEN_TIMEOUT_SECONDS}s")
                return result.toString()
            }
            // The child has exited, so its end of the pipe is closed and the
            // drain is at most one buffer from EOF. The join publishes what it
            // read; it is bounded so that this line cannot quietly become the
            // unbounded wait the lines above exist to remove. Overrunning it
            // costs the diagnostic text in the error message, nothing else.
            drain.join(DRAIN_JOIN_MILLIS)
            val exitCode = process.exitValue()

            if (exitCode == 0 && keyFile.exists()) {
                // Set correct permissions (600 for private key, 644 for public)
                try {
                    android.system.Os.chmod(keyFile.absolutePath, 384)  // 0600
                    android.system.Os.chmod("${keyFile.absolutePath}.pub", 420)  // 0644
                } catch (e: Exception) {
                    Logger.d(tag, "Failed to chmod SSH key: ${e.message}")
                }

                val pubKey = File("${keyFile.absolutePath}.pub").readText().trim()
                result.put("success", true)
                result.put("publicKey", pubKey)
            } else {
                result.put("success", false)
                result.put("error", output.trim().ifEmpty { "ssh-keygen exited with code $exitCode" })
            }
        } catch (e: Exception) {
            Logger.e(tag, "SSH key generation failed", e)
            result.put("success", false)
            result.put("error", e.message ?: "unknown error")
        }
        return result.toString()
    }

    /**
     * Reads the SSH public key (~/.ssh/id_ed25519.pub).
     * Returns the key contents or empty string if not found.
     */
    @JavascriptInterface
    fun getSshPublicKey(authToken: String): String {
        if (!security.validateToken(authToken)) return ""
        val pubKeyFile = File("${context.filesDir}/home/.ssh/id_ed25519.pub")
        return if (pubKeyFile.exists()) pubKeyFile.readText().trim() else ""
    }

    /**
     * Lists all SSH keys in ~/.ssh/.
     * Returns JSON array of {name, type, fingerprint} for each key pair.
     */
    @JavascriptInterface
    fun listSshKeys(authToken: String): String {
        if (!security.validateToken(authToken)) return "[]"
        val sshDir = File("${context.filesDir}/home/.ssh")
        if (!sshDir.exists()) return "[]"

        val keys = JSONArray()
        val allFiles: Array<File> = sshDir.listFiles() ?: emptyArray()
        val pubFiles = allFiles.filter { f -> f.name.endsWith(".pub") }
        for (pubFile in pubFiles) {
            try {
                val content = pubFile.readText().trim()
                val parts = content.split(" ", limit = 3)
                keys.put(JSONObject().apply {
                    put("name", pubFile.name.removeSuffix(".pub"))
                    put("type", if (parts.isNotEmpty()) parts[0] else "unknown")
                    put("comment", if (parts.size > 2) parts[2] else "")
                })
            } catch (e: Exception) {
                Logger.d(tag, "Failed to read SSH key ${pubFile.name}: ${e.message}")
            }
        }
        return keys.toString()
    }

    private fun getVersionName(): String {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }
    }
}
