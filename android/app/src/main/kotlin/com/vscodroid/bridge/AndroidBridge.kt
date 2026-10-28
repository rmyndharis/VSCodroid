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
import com.vscodroid.authCallbackIsExpected
import com.vscodroid.isExtensionCallback
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
import java.io.IOException
import java.nio.channels.Channels
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.lang.ref.WeakReference
import java.net.URLEncoder
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import androidx.core.net.toUri

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

private val PRIVATE_KEY_MODE = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)

// The app's umask drops the group and other bits again, as it did for ssh-keygen writing here directly.
private val PUBLIC_KEY_MODE = PRIVATE_KEY_MODE + setOf(PosixFilePermission.GROUP_READ, PosixFilePermission.OTHERS_READ)

/**
 * How long generateSshKey waits for the stdout drain after the child has exited.
 *
 * Not a second timeout on the work -- the process is already gone by the time
 * this is used, so the only thing outstanding is whatever sits in the pipe
 * buffer. It is a bound on principle: the point of draining on a separate thread
 * is that no unbounded wait remains on the bridge thread, and an unbounded join
 * would put one straight back. The same finishing budget bounds waiting for a
 * failed child to stop before its temporary output is removed.
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
 * How long an identity that was registered and never launched keeps its slot once the
 * registry is full. The workbench opens the address right after it registers one, so an
 * hour is long past any sign-in that is still going to happen; what it frees are the
 * identities of requests that were abandoned between the two, which otherwise held a slot
 * until the page was reloaded and, thirty-two of them in, refused every later sign-in.
 */
internal const val AUTH_UNLAUNCHED_GRACE_MILLIS = 60 * 60 * 1000L

/**
 * Bound retained identities per workbench document. A full registry makes room only by
 * dropping an identity that can no longer be satisfied, see [AuthTabWindow.register].
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
 * fails quietly. An id it invents arms nothing unless the workbench registered
 * that id with the same secret ([AuthTabWindow.register]), so a URL this app was
 * asked to open cannot choose which identities the relay accepts. Of the two
 * askers, [openExternalUrl] refuses a caller without the session token, and
 * `VSCodroidWebViewClient`'s `shouldOverrideUrlLoading`, which has no token to
 * check, arms only for a main-frame navigation: the main frame is our own page,
 * while the other frames carry content this app does not vouch for.
 */
private val AUTH_REQUEST_ID = Regex("""vscode-reqid(?:=|%(?:25)*3[Dd])(\d+)""")

/**
 * The secret the workbench mints into the callback URL it hands an extension,
 * read out of the address this app is asked to open.
 *
 * Patch 0019 pushes `vscodroid-nonce=<32 hex>` beside `vscode-reqid`, so it
 * arrives here inside the provider's `redirect_uri` and comes back in the query
 * the browser is redirected to. Encoding-tolerant for the reason
 * [AUTH_REQUEST_ID] is: the callback URL is a parameter of the URL being opened,
 * so it is percent-encoded once, and twice when the extension nests it again.
 *
 * One per address, not one per request id. The workbench mints a callback URL
 * per request and an extension opens one of them at a time; an address carrying
 * two would leave this reading the first, which is refused on arrival rather
 * than accepted for the wrong launch.
 */
private val AUTH_CALLBACK_NONCE = Regex("""vscodroid-nonce(?:=|%(?:25)*3[Dd])([0-9a-f]{32})""")

/**
 * How many request ids one address may record.
 *
 * The scan stops after a small number of distinct ids so an address cannot
 * monopolize the document's bounded sign-in record in one launch.
 */
private const val MAX_AUTH_REQUEST_IDS = 8

internal fun authRequestIdsIn(url: String): List<String> =
    AUTH_REQUEST_ID.findAll(url).map { it.groupValues[1] }
        .distinct().take(MAX_AUTH_REQUEST_IDS).toList()

/** The callback secret [url] carries, or null when it carries none. */
internal fun authCallbackNonceIn(url: String): String? =
    AUTH_CALLBACK_NONCE.find(url)?.groupValues?.get(1)

/**
 * The Microsoft sign-in's `state` as it reaches this app: the callback URL with
 * only its `?` escaped, so the rest of that URL's query sits beside `state` as
 * parameters of the authorisation address.
 *
 * The extension appends `&state=` + `encodeURI(callbackUri)`, which escapes none
 * of `?`, `&` or `=`, and the workbench opener's round trip then escapes only
 * the `?`, twice over, to `%253F`. The provider echoes back only `state`, so the
 * callback arrives without its nonce, scheme and authority and `callback.html`
 * throws `Missing scheme`. The trailing parameters are the ones
 * `LocalStorageURLCallbackProvider.create` pushes, in its order; a callback with
 * any other key after them is a different shape and is left alone.
 */
private val UNENCODED_CALLBACK_STATE = Regex(
    """(?<=[?&])state=(http://127\.0\.0\.1:\d+(?:/[A-Za-z0-9._~-]+)*/callback)%253F""" +
        """(vscode-reqid=\d+&vscodroid-nonce=[0-9a-f]{32}""" +
        """&vscode-scheme=[A-Za-z0-9._~-]+&vscode-authority=[A-Za-z0-9._~-]+)""" +
        """(?=$|#|&(?!vscode-))"""
)

/**
 * [url] with the callback run [UNENCODED_CALLBACK_STATE] matches put back into
 * one `state` value, percent-encoded once, which Microsoft and vscode.dev's
 * redirect both return whole. Any other address comes back unchanged, the MCP
 * provider's correctly encoded `state` included.
 *
 * `URLEncoder` is safe here only because the pattern admits no space and no `*`,
 * the two characters it would not escape the way a query needs.
 */
internal fun encodeCallbackState(url: String): String =
    UNENCODED_CALLBACK_STATE.replace(url) {
        "state=" + URLEncoder.encode("${it.groupValues[1]}?${it.groupValues[2]}", "UTF-8")
    }

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
 *
 * One caller substitutes a scheme rather than a class name: the refusal of this
 * app's own `vscodroid://callback` in [AndroidBridge.openExternalUrl], which
 * fails before any launch is attempted and so has no exception to name. The
 * argument is the cause either way, and the scheme is the cause there.
 */
@StringRes
internal val OPEN_URL_FAILED = R.string.open_url_failed

/**
 * Why [AndroidBridge.reclaimSafMirror] did not remove a mirror.
 *
 * The convention is [AndroidBridge.openExternalUrl]'s, and it is worth stating
 * because it reads backwards: the EMPTY STRING means there is nothing to say, and
 * anything else is the sentence to show. A caller testing truthiness reports every
 * refusal as a success, which for this method means telling the user their disk
 * was freed while it was not.
 *
 * "Nothing to say" and not "it was removed", which is the narrower reading it had.
 * The Activity also answers empty when the user declines the confirmation a forced
 * removal asks for, because every non-empty answer is drawn as "Could not remove
 * that folder's local copy: <this>" and a cancel the user chose is not a failure
 * of the app's. Nothing on that road claims any disk was freed: the byte figure is
 * announced natively, from the branch that actually removed something.
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
 * Why a command that answers by reply id refused because too many are already
 * outstanding.
 *
 * The one refusal here with a resource of its own rather than the stale-session
 * sentence the three above share, because that sentence is only half right for a
 * full queue. The request was indeed not accepted and trying again is indeed
 * what the caller should do; the instruction is not. Reloading the window does
 * not drain the single disk thread, so a user who followed it waited exactly as
 * long and lost their editor state on the way. This one asks for the wait and
 * nothing else.
 */
@StringRes
internal val STORAGE_BUSY = R.string.bridge_storage_busy

/**
 * How many disk-walking commands may be outstanding at once.
 *
 * A bound on a queue that had none. All four of these commands are on the relay,
 * which means any script sharing the workbench's origin can post them, and each
 * one walks the whole of `filesDir` or every copied device folder before it
 * answers; the executor below is single and its queue was unbounded, so a caller
 * posting `listSafMirrors` in a loop pinned the disk, the battery and every
 * legitimate storage call behind it for as long as it liked, with `clearCaches`
 * re-running its deletion on each queued copy.
 *
 * Four because there are four such commands and a person drives them one at a
 * time from the command palette, so the running one plus a full set behind it is
 * already more than any real sequence produces. Past that the refusal is the
 * honest answer, and it is one the caller can retry.
 */
private const val MAX_QUEUED_DISK_COMMANDS = 4

/**
 * Launch identities belong to one workbench document, whose request counter
 * restarts on reload. An address opened later in that document cannot replace
 * either the original secret or its time window. Expired identities stay
 * reserved until the document changes; failed browser launches can roll back.
 */
object AuthTabWindow {
    internal class Launch(val nonce: String, val documentId: String) {
        /** When it was registered, if the caller had a clock; an identity without one is never aged out. */
        var registeredAt: Long? = null
        var at: Long? = null
        var expired = false
        var outstanding = 0
        var succeeded = false
    }
    class Attempt internal constructor(
        internal val generation: Long, internal val launches: List<Launch>,
    ) {
        internal var completed = false
        fun isEmpty(): Boolean = launches.isEmpty()
    }

    private val launches = LinkedHashMap<String, Launch>()
    private var generation = 0L

    @Synchronized
    fun pageGeneration(): Long = generation

    @Synchronized
    fun beginPage() {
        generation++
        launches.clear()
    }

    /**
     * Records the identity the workbench minted for a sign-in request.
     *
     * With the registry full, [nowMillis] lets it drop the oldest identity whose return can
     * no longer be accepted: one already reported late, one launched and past
     * [AUTH_TAB_WINDOW_MILLIS], or one registered and still not launched after
     * [AUTH_UNLAUNCHED_GRACE_MILLIS]. A request that is waiting for its launch within that
     * time, or whose window is open, is never dropped, so a page left open for days can go
     * on signing in without a reload, and one with 32 sign-ins in flight is still refused.
     * Without a clock nothing is dropped.
     */
    @Synchronized
    fun register(
        requestId: String, nonce: String, documentId: String, pageGeneration: Long,
        nowMillis: Long? = null,
    ): Boolean {
        val numericId = requestId.toLongOrNull() ?: return false
        if (numericId <= 0 || numericId.toString() != requestId ||
            !nonce.matches(Regex("[0-9a-f]{32}")) || !documentId.matches(Regex("[0-9a-f]{32}")) ||
            pageGeneration != generation) return false
        launches[requestId]?.let { return it.nonce == nonce && it.documentId == documentId }
        if (launches.size >= MAX_TRACKED_SIGN_INS && !(nowMillis != null && dropOldestClosed(nowMillis))) {
            return false
        }
        launches[requestId] = Launch(nonce, documentId).also { it.registeredAt = nowMillis }
        return true
    }

    /** Caller holds the lock. True when it made room. */
    private fun dropOldestClosed(nowMillis: Long): Boolean {
        val closed = launches.entries.firstOrNull { (_, launch) ->
            val openedAt = launch.at
            val registeredAt = launch.registeredAt
            launch.expired || (openedAt != null && launch.outstanding == 0 &&
                !authCallbackIsExpected(openedAt, nowMillis, AUTH_TAB_WINDOW_MILLIS)) ||
                (openedAt == null && launch.outstanding == 0 && registeredAt != null &&
                    nowMillis - registeredAt > AUTH_UNLAUNCHED_GRACE_MILLIS)
        } ?: return false
        launches.remove(closed.key)
        return true
    }

    @Synchronized
    fun arm(
        requestIds: Collection<String>, nonce: String?, nowMillis: Long,
        pageGeneration: Long = generation,
    ): Attempt {
        val records = if (pageGeneration != generation) emptyList() else requestIds.distinct()
            .mapNotNull { launches[it] }.filter { !it.expired && it.nonce == nonce }
        records.forEach {
            if (it.at == null) it.at = nowMillis
            it.outstanding++
        }
        return Attempt(pageGeneration, records)
    }

    @Synchronized
    fun launched(attempt: Attempt) = finish(attempt, true)

    @Synchronized
    fun disarm(attempt: Attempt) = finish(attempt, false)

    private fun finish(attempt: Attempt, succeeded: Boolean) {
        if (attempt.completed || attempt.generation != generation) return
        attempt.completed = true
        attempt.launches.forEach {
            it.outstanding--
            it.succeeded = it.succeeded || succeeded
            if (it.outstanding == 0 && !it.succeeded) it.at = null
        }
    }

    @Synchronized
    fun expire(requestId: String) {
        launches[requestId]?.expired = true
    }

    @Synchronized
    fun armedAt(requestId: String): Long? = launches[requestId]?.takeUnless { it.expired }?.at

    @Synchronized
    fun nonceFor(requestId: String): String? = launches[requestId]?.nonce

    @Synchronized
    fun documentIdFor(requestId: String): String? = launches[requestId]?.documentId

    @Synchronized
    fun armedReadings(): List<Long> = launches.values.filterNot { it.expired }.mapNotNull { it.at }
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
 * DEVICE's night mode, not the editor's theme. The editor starts on a dark theme
 * whatever the device is set to and follows the theme the user picks, so on a device
 * in light mode it answers "light" for an editor that is not.
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
    /** Flips and persists whether the Extra Key Row is hidden, answering the new state. */
    private val onToggleExtraKeyRow: () -> Boolean = { false },
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
    private val onWorkspaceRoots: (Long, List<String>) -> Boolean = { _, _ -> false },
) {
    private val tag = "AndroidBridge"

    /**
     * How many commands are queued on or running against [diskWork].
     *
     * Counted here rather than read off the executor because the queue is the
     * executor's own and a `ThreadPoolExecutor`'s size is not part of the
     * `Executor` contract this takes. Incremented before the hand-off and given
     * back in a `finally`, so a command that throws cannot leak a slot and shut
     * the four commands down for the life of the page.
     */
    private val queuedDiskWork = java.util.concurrent.atomic.AtomicInteger()

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
     * empty-string-means-nothing-to-say convention its own documentation sets
     * out, and a relay cannot tell that apart from a listing that happens to be
     * empty.
     *
     * @return whether the work was accepted. False when the queue is full, or when
     *   [requireIdle] was requested while other work remains. No work or delayed reply
     *   follows a refusal, so the caller answers on its own thread.
     */
    private fun answerLater(
        replyId: String,
        requireIdle: Boolean = false,
        work: () -> Pair<Boolean, String>,
    ): Boolean {
        // Do not leave destructive consent requests queued after their caller moves on.
        if (requireIdle && !queuedDiskWork.compareAndSet(0, 1)) return false
        if (!requireIdle && queuedDiskWork.incrementAndGet() > MAX_QUEUED_DISK_COMMANDS) {
            queuedDiskWork.decrementAndGet()
            Logger.w(tag, "Refusing a storage command: $MAX_QUEUED_DISK_COMMANDS are already " +
                "queued on the one disk thread")
            return false
        }
        diskWork.execute {
            try {
                val (ok, payload) = try {
                    work()
                } catch (e: Exception) {
                    Logger.e(tag, "A bridge command failed off the caller's thread", e)
                    false to (e.message ?: "unknown error")
                }
                onAsyncAnswer(replyId, ok, payload)
            } finally {
                queuedDiskWork.decrementAndGet()
            }
        }
        return true
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

    /** Refuses a queued bootstrap before it can change a replacement document. */
    @JavascriptInterface
    fun isAuthPageCurrent(authToken: String, pageGeneration: Long): Boolean {
        if (!security.validateToken(authToken)) return false
        return pageGeneration == AuthTabWindow.pageGeneration()
    }

    /**
     * Registers a sign-in request the workbench has just issued: its numeric id, the
     * secret minted for it and the document that minted it, for [pageGeneration] only.
     * False makes the workbench fail that request rather than open a browser whose
     * return the relay would refuse. Not a relay command, so an extension cannot call it.
     */
    @JavascriptInterface
    fun registerAuthCallback(
        requestId: String, nonce: String, documentId: String, pageGeneration: Long, authToken: String
    ): Boolean {
        if (!security.validateToken(authToken)) return false
        return AuthTabWindow.register(
            requestId, nonce, documentId, pageGeneration, SystemClock.elapsedRealtime(),
        )
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
     * `VSCodroidWebViewClient.shouldOverrideUrlLoading`, which filtered nothing.
     * Same click, same URL, two outcomes, one of them silent. Both routes now
     * refuse exactly one address, this app's own `vscodroid://callback`, for the
     * reason given at the refusal below; nothing else is judged on either.
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
        val address = encodeCallbackState(url)
        // Null until the launch arms something, and then the activation to take back
        // if the launch that armed it throws. Arming has to precede the launch, so
        // without this a launch nothing accepted still left the callback relay
        // open to those ids for ten minutes with no sign-in in flight.
        var armed: AuthTabWindow.Attempt? = null
        val pageGeneration = AuthTabWindow.pageGeneration()
        return try {
            val uri = address.toUri()
            // The one destination this method judges, and it is refused before
            // anything is armed rather than merely left unarmed.
            //
            // `vscodroid://callback` is this app's own sign-in relay. Its VIEW
            // filter is exported and BROWSABLE, so handing one to `startActivity`
            // delivers it straight back into `MainActivity.onNewIntent`. While this
            // test sat BELOW the arming, one call both opened the ten-minute window
            // for the ids in the URL and posted the callback that window exists to
            // judge. The order is now the other way round, and what follows is why
            // the refusal is kept rather than only the reordering.
            //
            // `AuthTabWindow`'s stated property is that an outside caller cannot
            // supply a sign-in this app started; a caller that can reach this
            // method could supply exactly that, and through the relay that is
            // anything sharing the workbench's origin. Even with the arming
            // moved, the launch alone lets such a caller deliver a payload of its
            // own for an id a REAL sign-in has in flight, which is the half not
            // arming would leave open.
            //
            // Nothing legitimate is lost. A sign-in returns by the browser firing
            // this filter, never by the editor asking Android to open its own
            // scheme, and `injectWindowOpenOverride` only routes http and https
            // here, so the workbench cannot reach this branch at all.
            if (isExtensionCallback(uri.scheme, uri.host)) {
                Logger.w(tag, "Refused to open this app's own sign-in callback")
                return context.getString(OPEN_URL_FAILED, uri.scheme)
            }
            val isLocalhost = uri.host == "127.0.0.1" || uri.host == "localhost"
            // Recorded before the launch, not after: both halves below hand off to
            // another process, and a browser that answers instantly would otherwise
            // be able to return before the window it needs is open. Both, and not
            // only the Custom Tabs half, because both return by the same
            // `vscodroid://callback`: a sign-in page on plain http, the shape this
            // method exists to serve on a LAN, that arms nothing hangs with the
            // callback refused and nothing said.
            //
            // What is armed is the registered identities whose ids and secret this URL
            // carries, not the fact that a browser opened. A documentation link carries none and so opens
            // nothing: it used to widen the callback window by ten minutes exactly
            // as a sign-in did.
            armed = AuthTabWindow.arm(
                authRequestIdsIn(address), authCallbackNonceIn(address),
                SystemClock.elapsedRealtime(), pageGeneration,
            )
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
            AuthTabWindow.launched(armed)
            ""
        } catch (e: Exception) {
            armed?.let { AuthTabWindow.disarm(it) }
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

    @JavascriptInterface
    fun publishWorkspaceRoots(authToken: String, pageGeneration: Long, pathsJson: String): Boolean {
        if (!security.validateToken(authToken)) return false
        if (pageGeneration != AuthTabWindow.pageGeneration()) return false
        if (pathsJson.length > 262144) return false
        return try {
            val array = JSONArray(pathsJson)
            val paths = (0 until array.length()).map { index ->
                val path = array.opt(index) as? String ?: return false
                if (!path.startsWith('/')) return false
                path
            }
            onWorkspaceRoots(pageGeneration, paths)
        } catch (_: Exception) {
            false
        }
    }

    @JavascriptInterface
    fun claimWorkspacePaths(authToken: String, pathsJson: String): Boolean {
        if (!security.validateToken(authToken)) return false
        if (pathsJson.length > 262144) return false
        val manager = safManager ?: return false
        return try {
            val array = JSONArray(pathsJson)
            val paths = (0 until array.length()).map { index ->
                val path = array.opt(index) as? String ?: return false
                if (!path.startsWith('/')) return false
                path
            }
            manager.claimWorkspacePaths(paths)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Opens a previously selected SAF folder by its URI string.
     *
     * Named by its mirror, and [redactToken] is not an alternative here: it takes
     * out the `tkn=` parameter and nothing else, so a tree URI passed through it
     * arrives whole. A tree URI spells out the user's own directory
     * (`.../tree/primary%3ADocuments%2F<folder>`), `Logger.i` is not gated on a
     * debuggable build, and this is the ordinary success path, so every reopen from
     * Open Recent put a client, an employer or a project name into release logcat
     * and into any bug report the device produces. The mirror name is the six-byte
     * digest of that same URI: stable, not reversible, and what every other line
     * about this folder already uses. See `SafStorageManager.persistPermission`.
     */
    @JavascriptInterface
    fun openRecentFolder(authToken: String, uriString: String) {
        if (!security.validateToken(authToken)) return
        val uri = uriString.toUri()
        safManager?.let { it.claimMirrorOpen(it.getMirrorDir(uri)) }
        // Null, and printed as such, only for a bridge built with no SAF manager,
        // which is also a bridge whose open callback defaults to doing nothing.
        // Production has no such caller: `MainActivity` passes both together.
        val mirror = safManager?.getMirrorDir(uri)?.name
        Logger.i(tag, "Opening recent device folder $mirror")
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
     * Keys: vscode_server, extensions, user_data, logs, tools, saf_mirrors, projects,
     * cache, total. `projects` is the workspace, counted in `total` only when it
     * lies outside filesDir (an install that kept it on shared storage).
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
        val started = answerLater(replyId) {
            true to StorageManager.getStorageBreakdown(context).toString()
        }
        if (!started) return context.getString(STORAGE_BUSY)
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
        val started = answerLater(replyId) { true to StorageManager.clearCaches(context).toString() }
        if (!started) return context.getString(STORAGE_BUSY)
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
        val started = answerLater(replyId) { true to onListMirrors() }
        if (!started) return context.getString(STORAGE_BUSY)
        return ""
    }

    /**
     * Requests removal of a local copy that may contain files absent on the device.
     *
     * [force] requires explicit consent and is never a retry flag. The Activity asks
     * the user itself before removal and the manager still refuses an in-use copy.
     * Destructive requests are admitted only while this bridge's disk worker is idle.
     *
     * Empty return means accepted, with the outcome posted later against [replyId].
     * A non-empty return is an immediate refusal and starts no work. Posted empty
     * payloads mean removal or user cancellation; refusals are posted with `ok: false`.
     */
    @JavascriptInterface
    fun reclaimSafMirror(authToken: String, hash: String, force: Boolean, replyId: String): String {
        if (!security.validateToken(authToken)) return context.getString(RECLAIM_STALE_SESSION)
        val started = answerLater(replyId, requireIdle = true) {
            val answer = onReclaimMirror(hash, force)
            answer.isEmpty() to answer
        }
        if (!started) return context.getString(STORAGE_BUSY)
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
     * The bug report [CrashReporter.generateBugReport] builds, which the bundled
     * saf-bridge extension opens in an editor for **VSCodroid: Copy Bug Report**.
     * Empty only when the session token is refused: a report always has its header.
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
            manager.onStateChange = { packName, status, _, _, _ ->
                when (status) {
                    // Only the manager knows whether another requested pack still needs Play.
                    AssetPackStatus.COMPLETED,
                    AssetPackStatus.FAILED,
                    AssetPackStatus.CANCELED,
                    AssetPackStatus.UNKNOWN -> manager.unregisterListenerIfIdle()
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
     * Returns JSON array of installed toolchain names (e.g. ["ruby", "java"]).
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

    // -- Extra Key Row --

    /**
     * Hides the Extra Key Row if it is shown, shows it if it is hidden, and
     * keeps the choice across restarts.
     *
     * @return true when the row is now hidden. A refused token answers false,
     *   which reads as "shown" while nothing changed; the relay takes the token
     *   from the page this object is bound to, so a refusal there is a page that
     *   is not this app's.
     */
    @JavascriptInterface
    fun toggleExtraKeyRow(authToken: String): Boolean {
        if (!security.validateToken(authToken)) return false
        return onToggleExtraKeyRow()
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
        var child: Process? = null
        var stage: File? = null
        var interrupted = false
        try {
            val nativeLibDir = context.applicationInfo.nativeLibraryDir
            val homeDir = "${context.filesDir}/home"
            val sshDir = File(homeDir, ".ssh")
            sshDir.mkdirs()
            val keyFile = File(sshDir, "id_ed25519")
            val pubFile = File("${keyFile.absolutePath}.pub")

            // Existing private keys must never be replaced, even if their public file is missing.
            if (keyFile.exists() && pubFile.isFile) {
                val pubKey = pubFile.readText().trim()
                result.put("success", true)
                result.put("publicKey", pubKey)
                result.put("existed", true)
                return result.toString()
            }
            if (keyFile.exists()) {
                result.put("success", false)
                result.put(
                    "error",
                    "An SSH private key already exists without its public file. " +
                        "Recover it in the terminal with: " +
                        "ssh-keygen -y -f ~/.ssh/id_ed25519 > ~/.ssh/id_ed25519.pub"
                )
                return result.toString()
            }
            if (Files.exists(pubFile.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                result.put("success", false)
                result.put(
                    "error",
                    "An SSH public file already exists without its private key. " +
                        "Keep it, or move ~/.ssh/id_ed25519.pub aside before generating a new pair."
                )
                return result.toString()
            }

            val keyComment = if (comment.isBlank()) "vscodroid@android" else comment
            val temporary = Files.createTempDirectory(context.filesDir.toPath(), ".ssh-keygen-").toFile()
            stage = temporary
            val stagedKey = File(temporary, "id_ed25519")
            val stagedPublic = File(temporary, "id_ed25519.pub")
            val process = ProcessBuilder(
                "$nativeLibDir/libssh-keygen.so",
                "-t", "ed25519",
                "-f", stagedKey.absolutePath,
                "-N", "",
                "-C", keyComment
            ).apply {
                environment()["HOME"] = homeDir
                environment()["LD_LIBRARY_PATH"] = "$nativeLibDir:${context.filesDir}/usr/lib"
                redirectErrorStream(true)
            }.start()
            child = process

            var output = ""
            val drain = Thread {
                output = try {
                    process.inputStream.bufferedReader().readText()
                } catch (e: Exception) {
                    ""
                }
            }.apply { isDaemon = true }
            drain.start()

            if (!process.waitFor(KEYGEN_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)) {
                throw IOException("ssh-keygen did not finish within ${KEYGEN_TIMEOUT_SECONDS}s")
            }
            drain.join(DRAIN_JOIN_MILLIS)
            val exitCode = process.exitValue()

            if (exitCode == 0 &&
                Files.isRegularFile(stagedKey.toPath(), LinkOption.NOFOLLOW_LINKS) &&
                Files.isRegularFile(stagedPublic.toPath(), LinkOption.NOFOLLOW_LINKS)
            ) {
                val pubKey = stagedPublic.readText().trim()
                // A concurrent import must not be replaced by the generated pair.
                if (Files.exists(keyFile.toPath(), LinkOption.NOFOLLOW_LINKS) ||
                    Files.exists(pubFile.toPath(), LinkOption.NOFOLLOW_LINKS)
                ) {
                    throw IOException("An SSH key file appeared during generation. Existing files were kept.")
                }
                try {
                    installNewFile(stagedKey, keyFile, PRIVATE_KEY_MODE)
                } catch (e: FileAlreadyExistsException) {
                    throw IOException("An SSH private key appeared during generation. It was kept.", e)
                }
                try {
                    installNewFile(stagedPublic, pubFile, PUBLIC_KEY_MODE)
                    result.put("success", true)
                    result.put("publicKey", pubKey)
                } catch (e: Exception) {
                    // Keep the published private key rather than rolling back a pathname.
                    result.put("success", false)
                    result.put(
                        "error",
                        "An SSH private key was created, but its public file could not be installed. " +
                            "Existing files were kept. Recover its public key with " +
                            "ssh-keygen -y -f ~/.ssh/id_ed25519, then inspect " +
                            "~/.ssh/id_ed25519.pub before replacing it."
                    )
                }
            } else {
                result.put("success", false)
                result.put("error", output.trim().ifEmpty { "ssh-keygen exited with code $exitCode" })
            }
        } catch (e: Exception) {
            interrupted = e is InterruptedException
            Logger.e(tag, "SSH key generation failed", e)
            result.put("success", false)
            result.put("error", if (interrupted) "SSH key generation was interrupted" else e.message ?: "unknown error")
        } finally {
            try {
                val stopped = child?.let { !it.isAlive || stopSshKeygen(it) } ?: true
                if (stopped) {
                    stage?.let { temporary ->
                        try {
                            Files.deleteIfExists(File(temporary, "id_ed25519").toPath())
                            Files.deleteIfExists(File(temporary, "id_ed25519.pub").toPath())
                            Files.deleteIfExists(temporary.toPath())
                        } catch (e: Exception) {
                            Logger.w(tag, "Could not remove temporary SSH files: ${e.javaClass.simpleName}")
                        }
                    }
                } else {
                    result.put("success", false)
                    result.put("error", "ssh-keygen could not be stopped within the cleanup deadline; temporary output was kept")
                }
            } finally {
                if (interrupted) Thread.currentThread().interrupt()
            }
        }
        return result.toString()
    }

    /**
     * Copies [source] to [target], which must not exist. SELinux denies an app link(2),
     * and a rename would replace a file imported since the check above; CREATE_NEW
     * refuses an existing name in the call that creates it, and the mode is set by that
     * call, not by a chmod after it.
     */
    private fun installNewFile(source: File, target: File, mode: Set<PosixFilePermission>) {
        Files.newByteChannel(
            target.toPath(),
            setOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
            PosixFilePermissions.asFileAttribute(mode),
        ).use { out ->
            var copied = false
            try {
                Files.newInputStream(source.toPath(), LinkOption.NOFOLLOW_LINKS).use {
                    it.copyTo(Channels.newOutputStream(out))
                }
                copied = true
            } finally {
                // CREATE_NEW refused every other creator, so a partial file here is ours.
                if (!copied) Files.deleteIfExists(target.toPath())
            }
        }
    }

    private fun stopSshKeygen(process: Process): Boolean {
        var interrupted = Thread.interrupted()
        try {
            process.destroyForcibly()
            val deadline = System.nanoTime() +
                java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(DRAIN_JOIN_MILLIS)
            while (process.isAlive) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0L) return false
                try {
                    if (process.waitFor(remaining, java.util.concurrent.TimeUnit.NANOSECONDS)) return true
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
            return true
        } catch (e: Exception) {
            Logger.w(tag, "Could not stop SSH key generation: ${e.javaClass.simpleName}")
            return !process.isAlive
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
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
     * Returns JSON array of {name, type} for each key pair.
     *
     * The comment field is deliberately NOT reported, and it used to be. A public
     * key line's third field is conventionally `user@host` or an email address,
     * so for any key the user imported rather than generated here it is a personal
     * identifier; the generated default, `vscodroid@android`, is the only one that
     * carries nothing. This command is on the BroadcastChannel relay, whose
     * answers are broadcast to every listener sharing the workbench's origin, and
     * nothing in the app or in the bundled extensions ever read the field.
     *
     * It is also not a fingerprint, though this said so for a long time: computing
     * one means hashing the decoded key blob, and nothing here does that. The
     * field that was dropped was the comment, whatever it was called.
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
                // The type only, so the comment that follows it is never read.
                val content = pubFile.readText().trim()
                val parts = content.split(" ", limit = 2)
                keys.put(JSONObject().apply {
                    put("name", pubFile.name.removeSuffix(".pub"))
                    put("type", if (parts.isNotEmpty()) parts[0] else "unknown")
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
