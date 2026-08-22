package com.vscodroid.bridge

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.webkit.JavascriptInterface
import androidx.annotation.StringRes
import androidx.browser.customtabs.CustomTabsIntent
import com.google.android.play.core.assetpacks.model.AssetPackStatus
import com.vscodroid.R
import com.vscodroid.setup.ToolchainManager
import com.vscodroid.setup.ToolchainRegistry
import com.vscodroid.storage.SafStorageManager
import com.vscodroid.util.CrashReporter
import com.vscodroid.util.Logger
import com.vscodroid.util.StorageManager
import com.vscodroid.webview.redactToken
import com.vscodroid.webview.urlLogLabel
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * How long generateSshKey waits for ssh-keygen before killing it.
 *
 * Bounded by the caller rather than by the work. Key generation is arithmetic
 * and finishes in well under a second, so any figure above that is only about
 * when "slow" becomes "never" -- and that decision is already made one layer up.
 * Every call arrives through the bridge relay, whose JavaScript side rejects at
 * five seconds (`sendBridgeCommand` in the bundled extension), so the user has
 * been told the key failed long before a longer wait here ends.
 *
 * This was sixty, justified by the claim that the JavaScript side has no timeout
 * of its own, which the shipped relay contradicts. What that bought was
 * fifty-five further seconds of a blocked bridge thread after the answer was
 * already given, and the bridge thread is shared: clipboard, storage and
 * toolchain calls all queue behind it.
 *
 * Four rather than five, so this side gives up first and the failure is reported
 * by the layer that knows why.
 */
private const val KEYGEN_TIMEOUT_SECONDS = 4L

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
 * How long after this app launches a sign-in its callback is still taken.
 *
 * Chosen against the sign-in it has to survive rather than against the attack.
 * A real one is a person reading a consent screen, fetching a password manager
 * and possibly a second factor, so a tight window would reject genuine returns,
 * and a rejected sign-in is a bug report, while the window being ten minutes
 * instead of one still leaves the relay shut for the rest of the day.
 */
internal const val AUTH_TAB_WINDOW_MILLIS = 10 * 60 * 1000L

/**
 * How many sign-in requests are remembered at once.
 *
 * A bound, not a policy. Entries are kept past their own window on purpose, so
 * that a callback arriving late can be told apart from one nobody asked for and
 * the user given a reason for the first; something therefore has to stop the
 * record growing for the life of the process. Signing in is a deliberate act a
 * person performs a handful of times in a session, so the entry older than this
 * many is the one least likely to still be owed an explanation.
 */
private const val MAX_TRACKED_SIGN_INS = 32

/**
 * The workbench request ids carried by a URL this app is about to open.
 *
 * This is what separates a sign-in launch from a documentation link, and it is a
 * fact about the URL rather than a guess about it. `callback.html` refuses to run
 * without a `vscode-reqid` (`throw new Error('Missing id')`), and the id it
 * relays on to `vscodroid://callback` is that same parameter read back out of its
 * own address. So a callback can only exist for a request whose id was carried
 * out of this app in the address it opened, as `redirect_uri`, inside `state`, or
 * wherever else the provider echoes it back, and a URL carrying none can produce
 * no callback at all.
 *
 * Matched under percent-encoding as well as plain, because the callback address
 * usually travels as a parameter of the authorisation address and is therefore
 * encoded at least once, sometimes twice. `vscode-reqid` itself survives any
 * number of rounds untouched, since every character in it is unreserved; only the
 * `=` moves, to `%3D`, then `%253D`.
 *
 * The cost of being wrong is asymmetric and worth stating. An id this misses
 * arms nothing, so the callback that comes back for it is refused in the log and
 * nowhere else: the message for a late arrival sits on the other side of that
 * branch, and `openExternalUrl` still reports the launch as made, so the sign-in
 * fails quietly. An id it invents has to have come out of a URL this app was
 * asked to open, and there are two askers rather than one. [openExternalUrl]
 * refuses a caller without the session token. `VSCodroidWebViewClient`'s
 * `shouldOverrideUrlLoading` has no token to check, so it arms only for a
 * main-frame navigation: the main frame is our own page, while the other frames
 * carry content this app does not vouch for. Widening this pattern widens both.
 */
private val AUTH_REQUEST_ID = Regex("""vscode-reqid(?:=|%(?:25)*3[Dd])(\d+)""")

internal fun authRequestIdsIn(url: String): List<String> =
    AUTH_REQUEST_ID.findAll(url).map { it.groupValues[1] }.distinct().toList()

/**
 * Why [AndroidBridge.openExternalUrl] did not open anything.
 *
 * Named constants rather than literals at each exit so a test can pin which
 * reason belongs to which failure without pinning the wording, which is not the
 * contract. The wording is user facing: the extension's "Open in Browser" puts
 * whichever of these comes back straight into a `showErrorMessage`.
 *
 * A string resource and no longer the sentence itself, and the difference is what
 * a translator can reach. These leave this app through a return value and are
 * drawn by the editor, so `check-translatable-strings.py` cannot see them: it is
 * a predicate over call shapes and finds a literal only where the literal is
 * written at the sink, which its own docstring names as the hole it has. Written
 * in Kotlin they stayed English under a translated editor for ever, and nothing
 * anywhere reported it.
 *
 * The identity is what the constant carries, exactly as before; only the text
 * moved. `res/values/strings.xml` holds the wording and the reasoning for it.
 */
@StringRes
internal val OPEN_URL_STALE_SESSION = R.string.bridge_stale_session

/** The reason that is worth acting on, and the only one the relay used to give. */
@StringRes
internal val OPEN_URL_NO_HANDLER = R.string.open_url_no_handler

/**
 * Everything else, with the exception's class name substituted into it.
 *
 * The class name and not its message: `ActivityNotFoundException` quotes the
 * whole Intent it could not match, and `FileUriExposedException` quotes the
 * file, so a message here would hand the URL to the page that supplied it and,
 * through the extension, to a notification a user may screenshot.
 *
 * A format string with the name as its one argument, rather than the prefix this
 * used to be. A sentence assembled by concatenation puts the cause in the same
 * place in every language and no translation can move it.
 */
@StringRes
internal val OPEN_URL_FAILED = R.string.open_url_failed

/**
 * Why [AndroidBridge.reclaimSafMirror] did not remove a mirror.
 *
 * The convention is [AndroidBridge.openExternalUrl]'s, and it is worth stating
 * because it reads backwards: the EMPTY STRING means it was removed, and anything
 * else is the sentence to show. A caller testing truthiness reports every refusal
 * as a success, which for this method means telling the user their disk was freed
 * while it was not.
 *
 * That convention is worth one warning for whoever writes the next test here:
 * a relaxed `Context` mock answers `getString` with the empty string, which is
 * this method's answer for "it was removed". Stub the resource rather than
 * asserting on what a relaxed mock returns.
 *
 * A refusal here is never a suggestion to retry harder. Each one names something
 * that has to change first, and the two below are the ones this file can decide
 * on its own; the rest come from the Activity, which is the only place that knows
 * what the editor currently has open.
 *
 * The same resource as [OPEN_URL_STALE_SESSION], kept under its own name: it is
 * one situation with one instruction, and the two names are what let a test say
 * which call it is pinning.
 */
@StringRes
internal val RECLAIM_STALE_SESSION = R.string.bridge_stale_session

/**
 * No Activity wired the callback, so nothing could have been removed.
 *
 * Distinct from a refusal with a reason, because a bridge built without the
 * wiring is a mistake in this app rather than a state the user can act on, and
 * reporting it as "that folder is in use" would send them looking for a folder to
 * close.
 */
@StringRes
internal val RECLAIM_UNAVAILABLE = R.string.bridge_reclaim_unavailable

/**
 * Why a command that answers by reply id refused before it started any work.
 *
 * The same resource as [OPEN_URL_STALE_SESSION] and [RECLAIM_STALE_SESSION] and
 * under its own name for the reason those two carry theirs: one situation with
 * one instruction, and a name is what lets a test say which call it pinned.
 *
 * It travels back on the CALLER's thread, as the return value, while everything
 * else these four commands answer is posted later against the reply id. That
 * split is deliberate: a caller holding no valid token must learn so without
 * anything outside the bridge being touched, which is the property
 * `BridgeTokenUniformityTest` checks by watching every collaborator. A refusal
 * routed through the reply hook would touch one.
 */
@StringRes
internal val STORAGE_STALE_SESSION = R.string.bridge_stale_session

/**
 * The sign-in requests this app launched a browser for, and when.
 *
 * Keyed by request id rather than being a single reading, and that is the whole
 * of the narrowing. It used to be one timestamp written by *every*
 * `openExternalUrl` call, so opening a documentation link widened the window in
 * which an unsolicited `vscodroid://callback` would be answered exactly as much
 * as starting a sign-in did. Now a launch arms only the ids it actually carries
 * out of the app, and a callback is judged against its own launch: an id nobody
 * launched is refused however recently a browser was opened.
 *
 * The `vscodroid://callback` relay has nothing else to test. Its VIEW filter is
 * exported and BROWSABLE, so any installed app or any page the user taps can fire
 * it, and the id it names is a counter from one rather than a secret. What an
 * outside caller cannot supply is a sign-in this app started.
 *
 * Deliberately process-scoped and not persisted. The ids the workbench waits on
 * live in an in-memory Set that does not survive the page, so after a restart
 * there is no pending request a relayed value could satisfy; persisting this
 * would only widen the window for callbacks that could not be delivered anyway.
 *
 * Entries are not removed when a callback is accepted. Two reasons, and the
 * second is not obvious: a late callback has to stay distinguishable from an
 * invented one so the user can be told why the sign-in failed, and the forced
 * reload on returning from the background asks this object whether a sign-in is
 * still in flight, so consuming the entry on arrival would let that reload fire
 * into the moment the workbench is collecting the value.
 *
 * They are removed once that explanation has been given, which is the bound on
 * it. An entry kept for the life of the process is one an outside caller can
 * name at will through the exported filter, and the id it has to guess is a
 * small integer; taking the entry back as the message goes up means each launch
 * can produce that message at most once, and every repeat lands in the branch
 * that says nothing.
 *
 * Time is supplied by callers from the monotonic clock, never from wall time, so
 * that changing the device clock mid-sign-in neither breaks a real return nor
 * reopens the window. No arithmetic happens here; the window is applied by
 * `authCallbackIsExpected`, which this object deliberately does not call.
 */
object AuthTabWindow {

    private val launches = object : LinkedHashMap<String, Long>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>): Boolean =
            size > MAX_TRACKED_SIGN_INS
    }

    /**
     * Records a browser launch against the request ids it carries.
     *
     * An id already recorded is moved forward to this launch's reading rather
     * than keeping its earlier one, because ids repeat. The workbench mints them
     * from a class static that is re-initialised with the page --
     * `static{this.REQUEST_ID=0}`, then `++REQUEST_ID` per request -- so every
     * reload, folder switch and renderer recreation starts counting at one again.
     * "Already recorded" therefore does not mean the same request seen twice; it
     * routinely means a launch from a page that no longer exists. Keeping that
     * older reading let it decide the window for the sign-in actually in flight,
     * and refused a callback that came back in seconds with a message saying it
     * had taken too long.
     *
     * @return the ids this call recorded, which is what [disarm] takes back if
     *   the launch then throws. Rolling a repeated id back removes it rather than
     *   restoring the older reading: the request that reading belonged to was
     *   minted by a page since replaced, and the workbench holds the ids it is
     *   waiting on in memory, so nothing is left that could still satisfy it.
     */
    @Synchronized
    fun arm(requestIds: Collection<String>, nowMillis: Long): List<String> {
        val armed = requestIds.distinct()
        armed.forEach {
            // Taken out before being put back, so a repeated id counts as the
            // newest entry instead of keeping the position of the launch it
            // replaces. The cap below is documented to hold the most recent
            // launches, and a plain overwrite leaves insertion order untouched.
            launches.remove(it)
            launches[it] = nowMillis
        }
        return armed
    }

    /**
     * Takes back launch records: the ids a launch armed when that launch then
     * threw, and an id whose late arrival has already been explained.
     *
     * Arming has to happen before the launch (see the caller), so a launch that
     * never happened had already opened the relay to the ids it named, and until
     * the launch could report failure nothing could tell.
     *
     * Named apart from [arm] so it does not read as a second launch site:
     * `ExtensionCallbackTest` counts arming calls against browser launches to
     * catch a launch that forgets to arm, and a rollback spelled with the same
     * name would be counted as an arming. The two call names are deliberately
     * not written out here either -- that test strips comment lines before
     * counting, but a guard that only stays correct because a neighbour
     * filters prose is one bad refactor from a false count.
     */
    @Synchronized
    fun disarm(requestIds: Collection<String>) {
        requestIds.forEach { launches.remove(it) }
    }

    /** When this app launched a browser for [requestId], or null if it never did. */
    @Synchronized
    fun armedAt(requestId: String): Long? = launches[requestId]

    /** Every launch reading held, for a caller asking whether any is still open. */
    @Synchronized
    fun armedReadings(): List<Long> = launches.values.toList()
}

/**
 * The `AndroidBridge` object the workbench sees, one `@JavascriptInterface` method
 * per call an extension may make.
 *
 * ⚠️ **Several methods have no PRODUCTION caller, and that is not dead code.**
 * A sweep for unused declarations flags `minimizeApp`, `getDeviceInfo`, `getThemeMode`,
 * `logToNative` and `cancelToolchainInstall`, because their callers are extensions
 * nobody here wrote. Read that as "nothing in `main` calls them", not as "nothing calls
 * them": some are exercised by tests, `logToNative` among them. Three things make
 * removing one a breaking change rather than a cleanup, and all three were checked
 * before this note was written:
 *
 *  1. Every one is published in `docs/05-API_SPEC.md`, which is what an extension
 *     author writes against, and `check-bridge-api-spec.py` holds the bridge and that
 *     document to each other in BOTH directions. A method deleted here fails the gate
 *     until the spec drops it too, which is the gate correctly refusing a silent API
 *     break.
 *  2. `proguard-rules.pro` keeps every `@JavascriptInterface` member, so all of them
 *     ship live and callable in the release build. "Not called here" and "not callable"
 *     are different facts.
 *  3. Two of them are load-bearing for code that is NOT obviously related.
 *     `getDeviceInfo` is the only caller of the private `getVersionName()`, so removing
 *     it strands a second declaration. `minimizeApp` is the only consumer of the
 *     `onMinimize` constructor parameter, which every test that builds a bridge has to
 *     pass; note that minimise-on-back does not depend on it, since `MainActivity`
 *     calls `moveTaskToBack(true)` natively.
 *
 * Retiring one is therefore a deliberate API decision: drop the spec row in the same
 * change, and expect the cascade above. It is not something a dead-code pass should do.
 *
 * Keeping them costs little. Every method validates the session token first, so the
 * surface is bounded by whoever holds it, which is the workbench itself.
 *
 * One caveat worth knowing before an extension trusts it: `getThemeMode` reports the
 * DEVICE's night mode, not the editor's theme. This app is always dark and ships no
 * `values-night`, so on a device in light mode it answers "light" for an editor that
 * is not.
 */
class AndroidBridge(
    private val context: Context,
    private val security: SecurityManager,
    private val clipboard: ClipboardBridge,
    private val onBackPressed: () -> Boolean,
    private val onMinimize: () -> Unit,
    private val onOpenFolderPicker: () -> Unit = {},
    private val onOpenRecentFolder: (Uri) -> Unit = {},
    private val onShowAbout: () -> Unit = {},
    private val safManager: SafStorageManager? = null,
    private val onDownloadNamed: (url: String, fileName: String) -> Unit = { _, _ -> },
    private val onDownloadChunk: (requestId: String, base64: String) -> Boolean = { _, _ -> false },
    private val onDownloadComplete: (requestId: String, error: String?) -> Unit = { _, _ -> },
    private val onListMirrors: () -> String = { "[]" },
    private val onReclaimMirror: (hash: String, force: Boolean) -> String =
        { _, _ -> context.getString(RECLAIM_UNAVAILABLE) },
    /**
     * Answers a command that could not be answered while its caller waited.
     *
     * [payload] is the value the method would have returned, or the reason it
     * failed when [ok] is false. [replyId] is the id the caller sent with the
     * command; the relay posts the answer back under it and the extension routes
     * it to the promise that is still waiting.
     */
    private val onAsyncAnswer: (replyId: String, ok: Boolean, payload: String) -> Unit =
        { _, _, _ -> },
    /**
     * The one thread every disk-walking command runs on.
     *
     * Single, deliberately. These used to run on the WebView's JavaBridge
     * thread, which serialises every bridge call, so one at a time is the
     * ordering they already had and the one their callers were written against;
     * a pool would let two removals of the same copy overlap. Daemon, so it
     * never holds the process up. Nothing shuts it down, and the reason that is
     * bounded rather than a leak is that a bridge is built once per WebView:
     * only a renderer crash produces a second one, and what the first leaves
     * behind is an idle thread with no work and no queue.
     *
     * A constructor argument rather than a private field so that a test can
     * watch it. `BridgeTokenUniformityTest` decides whether a method OBEYED the
     * token by asking whether anything outside the bridge was touched, and work
     * handed to a thread this class owned privately would be touched on that
     * thread, racing the verification. Here the hand-off itself is the signal,
     * and it is recorded on the caller's thread.
     */
    private val diskWork: Executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "bridge-disk").apply { isDaemon = true }
    },
) {
    private val tag = "AndroidBridge"

    /**
     * Runs [work] off the caller's thread and posts what it answers.
     *
     * The caller is the page's own thread. A `@JavascriptInterface` call does
     * not return to JavaScript until this method does, and the relay that makes
     * the call is injected into the workbench's main frame, so anything
     * unbounded answered inline freezes the editor: it stops rendering and stops
     * taking input for the length of the work, and nothing in the app detects
     * it. So the methods below hand back at once and the answer follows.
     *
     * The Boolean [work] returns decides whether the caller's promise resolves
     * or rejects. It is decided here rather than in the relay because this is
     * the side that knows: for [reclaimSafMirror] the answer is the
     * empty-string-means-removed convention its own documentation sets out, and
     * a relay cannot tell that apart from a listing that happens to be empty.
     */
    private fun answerLater(replyId: String, work: () -> Pair<Boolean, String>) {
        diskWork.execute {
            val (ok, payload) = try {
                work()
            } catch (e: Exception) {
                Logger.e(tag, "A bridge command failed off the caller's thread", e)
                false to (e.message ?: "unknown error")
            }
            onAsyncAnswer(replyId, ok, payload)
        }
    }

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
     * @return the empty string when the URL was handed to a browser, and
     *   otherwise a sentence naming why it was not. Which sentence matters,
     *   because the failures are not one failure: a stale session token and an
     *   intent nothing claimed both came back as `false`, and the relay turned
     *   every `false` into "no app handles that link". That is the right advice
     *   for one of them and unfollowable for the rest, `file://` included, which
     *   Android refuses outright rather than for want of an app.
     */
    @JavascriptInterface
    fun openExternalUrl(url: String, authToken: String): String {
        if (!security.validateToken(authToken)) return context.getString(OPEN_URL_STALE_SESSION)
        // Empty until the launch arms something, and then the ids to take back if
        // the launch that armed them throws. Arming has to precede the launch, so
        // without this a launch nothing accepted still left the callback relay
        // open to those ids for ten minutes with no sign-in in flight.
        var armed: List<String> = emptyList()
        return try {
            val uri = Uri.parse(url)
            val isLocalhost = uri.host == "127.0.0.1" || uri.host == "localhost"
            // Recorded before the launch, not after: both halves below hand off to
            // another process, and a browser that answers instantly would otherwise
            // be able to return before the window it needs is open. Both, and not
            // only the Custom Tabs half, because both return by the same
            // `vscodroid://callback`: a sign-in page on plain http, the shape this
            // method exists to serve on a LAN, that arms nothing hangs with the
            // callback refused and nothing said.
            //
            // What is armed is the request ids this URL carries, not the fact that
            // a browser opened. A documentation link carries none and so opens
            // nothing: it used to widen the callback window by ten minutes exactly
            // as a sign-in did.
            armed = AuthTabWindow.arm(authRequestIdsIn(url), SystemClock.elapsedRealtime())
            // Use system browser for localhost URLs (dev server preview needs full browser),
            // Chrome Custom Tabs for https (keeps user in-app, handles OAuth redirects).
            if (uri.scheme == "https" && !isLocalhost) {
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
            ""
        } catch (e: Exception) {
            AuthTabWindow.disarm(armed)
            // The URL is page supplied and this line is not gated on a debuggable
            // build, so it ships. That is the same reasoning [logToNative] gives,
            // and it carries here twice over: the workbench holds the connection
            // token and a URL it hands over can carry it, and the destination of
            // an "Open in Browser" is routinely a sign-in whose query holds an
            // OAuth code or an API key. So the address is reduced to a scheme and
            // a host by `urlLogLabel`, which is the same thing the notice for a
            // failed hand-off is allowed to say.
            //
            // The throwable is deliberately not passed. It is printed with its
            // message, and both exceptions this realistically catches put the
            // whole URI there: ActivityNotFoundException names the Intent it
            // could not match, FileUriExposedException names the file it refused.
            // Reducing the interpolation while handing the same URL to logcat
            // inside the trace would only look like a redaction. The frames behind
            // it are all framework, so the class name is what was being read for.
            Logger.e(tag, "Failed to open a URL at ${urlLogLabel(url)} (${e.javaClass.simpleName})")
            if (e is ActivityNotFoundException) context.getString(OPEN_URL_NO_HANDLER)
            else context.getString(OPEN_URL_FAILED, e.javaClass.simpleName)
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
     * this class could do and the webview call sites could not, worth knowing
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
        Logger.i(tag, "Opening recent SAF folder: ${redactToken(uri.toString())}")
        onOpenRecentFolder(uri)
    }

    // -- Saving a download to the device --

    /**
     * Reports the name the page is about to download [url] under.
     *
     * Called from the anchor click, before the platform has decided the click
     * is a download at all, and that is the point: a bridge call blocks the
     * page until it returns, so the name is on this side before the download
     * hook fires and the hook does not have to ask for it. A blob carries no
     * name, so without this every file the editor downloads would be offered
     * under a placeholder.
     */
    @JavascriptInterface
    fun noteDownloadName(authToken: String, url: String, fileName: String) {
        if (!security.validateToken(authToken)) return
        onDownloadNamed(url, fileName)
    }

    /**
     * Hands over one piece of a download the page is reading, base64 encoded
     * because the bridge carries text.
     *
     * @return true when it was written. The page stops reading on false, which
     *   is what keeps a failed write from being followed by the rest of a file
     *   and a report of success.
     */
    @JavascriptInterface
    fun writeDownloadChunk(authToken: String, requestId: String, base64: String): Boolean {
        if (!security.validateToken(authToken)) return false
        return onDownloadChunk(requestId, base64)
    }

    /**
     * Ends a download the page was reading. [error] is empty on success and
     * carries a reason otherwise.
     *
     * Empty rather than null because the value is written by JavaScript, where
     * the absent case is easy to send by accident; an empty string is the one
     * spelling both sides agree means "nothing went wrong".
     */
    @JavascriptInterface
    fun finishDownload(authToken: String, requestId: String, error: String) {
        if (!security.validateToken(authToken)) return
        onDownloadComplete(requestId, error.ifEmpty { null })
    }

    // -- Storage Management --

    /**
     * Asks for the per-component storage breakdown, which arrives by [replyId].
     * Keys: vscode_server, extensions, user_data, logs, tools, saf_mirrors, cache, total
     * Values in bytes.
     *
     * Every figure is a directory walk and `total` walks `filesDir` again on top
     * of them, so this takes as long as the disk is big. See [answerLater] for
     * why that cannot be answered where the caller waits.
     *
     * @return the empty string once the walk is under way, and otherwise the
     *   reason it was refused before starting. The JSON itself is posted later
     *   against [replyId].
     */
    @JavascriptInterface
    fun getStorageBreakdown(authToken: String, replyId: String): String {
        if (!security.validateToken(authToken)) return context.getString(STORAGE_STALE_SESSION)
        answerLater(replyId) { true to StorageManager.getStorageBreakdown(context).toString() }
        return ""
    }

    /**
     * Clears caches (npm, tmp, crash logs, VS Code logs). The bytes freed arrive
     * by [replyId], as the decimal spelling of a Long.
     *
     * It deletes trees, so it takes as long as the disk is big; see
     * [answerLater].
     *
     * @return the empty string once the deletion is under way, and otherwise the
     *   reason it was refused before starting.
     */
    @JavascriptInterface
    fun clearCaches(authToken: String, replyId: String): String {
        if (!security.validateToken(authToken)) return context.getString(STORAGE_STALE_SESSION)
        answerLater(replyId) { true to StorageManager.clearCaches(context).toString() }
        return ""
    }

    /**
     * Returns available storage in bytes.
     */
    @JavascriptInterface
    fun getAvailableStorage(authToken: String): Long {
        if (!security.validateToken(authToken)) return 0
        return StorageManager.getAvailableStorage(context)
    }

    /**
     * Returns the local copies of device folders, as a JSON array.
     *
     * [getStorageBreakdown] reports all of them as one number under `saf_mirrors`,
     * which is honest and unactionable: the row is correctly marked unclearable, and
     * choosing it is a dead end. This is the row's contents, so that the user can see
     * which folder is holding the disk and decide about that folder rather than about
     * a total.
     *
     * Every copy is walked twice before the list exists, once to size it and once
     * to ask whether the device folder holds everything in it, so this takes as
     * long as the disk is big; see [answerLater].
     *
     * The posted answer is `[]` when no SAF manager is wired up, which is
     * indistinguishable from an install that has copied no device folder, as it
     * is in [getRecentFolders]. A refusal is no longer among those cases: it
     * comes back here as a sentence instead, so a caller can tell "nothing to
     * show" from "we would not tell you".
     *
     * @return the empty string once the walk is under way, and otherwise the
     *   reason it was refused before starting. The JSON array is posted later
     *   against [replyId].
     */
    @JavascriptInterface
    fun listSafMirrors(authToken: String, replyId: String): String {
        if (!security.validateToken(authToken)) return context.getString(STORAGE_STALE_SESSION)
        answerLater(replyId) { true to onListMirrors() }
        return ""
    }

    /**
     * Removes the local copy of one device folder and says whether it went.
     *
     * ⚠️ **This deletes files, and with [force] it deletes files that exist nowhere
     * else.** A mirror the app can vouch for is a copy of the device folder and
     * removing it loses nothing. A mirror it cannot vouch for holds work the app never
     * delivered to the device: anything under `node_modules`, `.git`, `__pycache__` or
     * `.gradle`, which the sync excludes by construction, and anything written while no
     * watcher was running. [force] is the user's own decision to remove it anyway, and
     * the caller must have said what is at stake in a modal the user had to accept. It
     * is not a retry flag.
     *
     * Without [force] the copy is walked to re-ask the gate and walked again to
     * report the bytes freed, so this takes as long as the disk is big; see
     * [answerLater].
     *
     * The empty string means two different things here, one on each road back,
     * and they do not conflict. The RETURN says only that the removal was
     * accepted and its outcome will follow. The POSTED answer is the outcome:
     * empty when the mirror was removed, and otherwise the sentence naming why
     * it was not, which is decided into `ok: false` before it leaves.
     *
     * @return the empty string once the removal is under way, and otherwise the
     *   reason it was refused before starting. Success is the falsy value on
     *   both roads, so a truthiness test reads backwards on either.
     */
    @JavascriptInterface
    fun reclaimSafMirror(authToken: String, hash: String, force: Boolean, replyId: String): String {
        if (!security.validateToken(authToken)) return context.getString(RECLAIM_STALE_SESSION)
        answerLater(replyId) {
            val answer = onReclaimMirror(hash, force)
            answer.isEmpty() to answer
        }
        return ""
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
        // The APPLICATION context, deliberately, while everything else in this
        // class keeps the Activity it was built with.
        //
        // `install()` hands a listener to Play Core, which keeps it in a registry
        // that lives in that library and outlives every Activity here. The
        // listener holds this manager, so a manager built with the Activity keeps
        // the Activity, its Context and its view tree reachable for as long as
        // the registration stands. The state callback below removes the
        // registration only on COMPLETED, FAILED or CANCELED, and
        // REQUIRES_USER_CONFIRMATION waiting on a dialog the user walked away
        // from reaches none of them.
        //
        // Releasing the registration at teardown instead would be worse than the
        // leak. The COMPLETED branch of `handleStateUpdate` is what finishes an
        // install, and Play Core does not promise to re-emit a state to a listener
        // that registers afterwards, so dropping it mid-download leaves the pack
        // downloaded and never installed.
        //
        // That used to be unrecoverable, and this said so. It no longer is:
        // `ToolchainManager.reconcileDeliveredPacks` runs at launch and installs a
        // pack Play has already delivered, without needing any listener. The
        // registration is still not the thing to drop, because reconcile repairs
        // the next launch rather than this download, but the loss is now bounded.
        //
        // So the references go, not the registration. `ToolchainManager` reads
        // only `filesDir`, `cacheDir`, `assets`, `packageManager` and
        // `packageName` from this, all identical on the application context, and
        // `showConfirmationDialog` takes the Activity as a parameter from
        // [confirmLargeDownload] rather than from the manager's own field.
        //
        // Plural, and that is the correction. Changing only the constructor
        // argument left the leak intact one link along: `onStateChange` was
        // `{ ... -> onToolchainState(...) }`, an unqualified call to a member of
        // this class, which captures the bridge exactly as completely as naming a
        // field would, and the bridge holds the Activity. Confirmed in bytecode:
        // the lambda compiled to a method taking `AndroidBridge` as its first
        // parameter. It is the same shape `ServiceWorkerRetentionTest` refuses in
        // `MainActivity`, and it reached here because the fix was written as "use
        // the application context" rather than "hold nothing that holds a screen".
        //
        // So the two things this has to do are split by what they need:
        //  - closing the registration is the manager's own business, is reached
        //    through the manager, and still happens with no screen left.
        //  - the confirmation dialog needs an Activity, so it reads through a weak
        //    reference and correctly does nothing when there is none. A dialog for
        //    a destroyed Activity was never showable; what changes is that the
        //    Activity is now collectable while the download runs.
        val bridge = WeakReference(this)
        ToolchainManager(context.applicationContext).also { manager ->
            manager.onStateChange = { packName, status, _, _ ->
                when (status) {
                    // The other half of a pair that had no other half: `install()`
                    // calls `registerListener()` before every Play fetch and nothing
                    // undid it, so this instance stayed subscribed for the life of
                    // the process. After COMPLETED, FAILED or CANCELED there is
                    // nothing further to hear, and the next `install()` subscribes
                    // again. Harmless on the HTTP path, which never registers: the
                    // unregister is guarded and does nothing.
                    //
                    // Spelled out rather than delegated to `isTerminalPackStatus`,
                    // which answers a different question. That predicate defaults an
                    // unknown status to true so the first-run queue advances rather
                    // than stalls; here an unknown status is one still to come, and
                    // unregistering on it would drop the install that finishes it.
                    AssetPackStatus.COMPLETED,
                    AssetPackStatus.FAILED,
                    AssetPackStatus.CANCELED -> manager.unregisterListener()
                    AssetPackStatus.REQUIRES_USER_CONFIRMATION ->
                        bridge.get()?.confirmLargeDownload(packName)
                            // A literal, NOT the `tag` property. That property belongs
                            // to AndroidBridge, so naming it here reads a member of the
                            // very object this lambda must not hold, and the capture is
                            // complete whether or not the read looks like one.
                            // ToolchainManagerContextTest caught exactly that when this
                            // line was first written with `tag`.
                            ?: Logger.w(
                                "AndroidBridge",
                                "Pack $packName needs confirmation and the bridge that " +
                                    "started it is gone, so nothing can show the dialog. " +
                                    "The download waits until it is asked again from the " +
                                    "toolchain screen.",
                            )
                }
            }
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
                    // Resolved here rather than passed as an id. The caller is a
                    // web page that renders this straight into the DOM, so it
                    // needs the words; an id would reach it as an integer no
                    // page can turn back into text.
                    put("description", context.getString(tc.descriptionRes))
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
        Logger.i(tag, "JS requested toolchain install: ${redactToken(name)}")
        toolchainManager.install(name)
    }

    /**
     * Removes a toolchain (deletes files, symlinks, env vars).
     */
    @JavascriptInterface
    fun removeToolchain(name: String, authToken: String) {
        if (!security.validateToken(authToken)) return
        Logger.i(tag, "JS requested toolchain removal: ${redactToken(name)}")
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
     * Empty passphrase for mobile UX: keys are protected by app sandbox.
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

            // Bounded, and bounded under the relay's own five seconds so this
            // side gives up first. An unbounded wait parks the bridge thread,
            // which every other bridge call queues behind, and the JavaScript
            // caller has already rejected by then, so the extra time buys a
            // frozen clipboard and storage rather than a key.
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
     * Returns JSON array of {name, type, comment} for each key pair.
     *
     * Not a fingerprint, though this said so for a long time: computing one means
     * hashing the decoded key blob, and nothing here does that. What the third
     * field carries is the public key line's own comment.
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
