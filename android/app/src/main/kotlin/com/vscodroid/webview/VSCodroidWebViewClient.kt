package com.vscodroid.webview

import android.content.Context
import android.content.Intent
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.SystemClock
import android.view.KeyEvent
import android.webkit.RenderProcessGoneDetail
import android.webkit.ServiceWorkerClient
import android.webkit.ServiceWorkerController
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.vscodroid.bridge.AuthTabWindow
import com.vscodroid.bridge.authRequestIdsIn
import com.vscodroid.isExtensionCallback
import com.vscodroid.util.EditorLocale
import com.vscodroid.util.Environment
import com.vscodroid.util.Logger
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.SequenceInputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URISyntaxException
import java.net.URL
import java.net.URLDecoder
import android.annotation.SuppressLint

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
 * What the interceptor decided about one webview resource request.
 *
 * Separated from building the response because a `WebResourceResponse` cannot be
 * constructed under the stub `android.jar`, so a test has to mock its constructor
 * and can then observe nothing about it. That left refusal provable only by the
 * warning the code logs, and a log line is not a refusal. Code that logged the
 * warning and served the file anyway satisfied every assertion.
 */
internal sealed interface ResourceOutcome {
    /** Inside a published root and present on disk. */
    data class Serve(val file: File) : ResourceOutcome

    /** Resolved outside every published root. Nothing is opened. */
    object Refused : ResourceOutcome

    /** Inside a root, but absent or not a regular file. */
    object Missing : ResourceOutcome
}

/**
 * The decision, as a value: which of the three outcomes [path] reaches against
 * [resourceRoots].
 *
 * `Refused` is the one that matters. `.ssh/id_ed25519` exists on disk and sits in
 * the app-private tree, so nothing else in the request path would stop it being
 * read and handed to the page.
 */
internal fun resourceOutcome(path: String, resourceRoots: List<String>): ResourceOutcome {
    val file = resolveWebviewResource(path, resourceRoots) ?: return ResourceOutcome.Refused
    if (!file.exists() || !file.isFile) return ResourceOutcome.Missing
    return ResourceOutcome.Serve(file)
}

/**
 * The file a webview resource request names, if it resolves inside a published
 * root, and null otherwise.
 *
 * The published roots are named individually rather than described by a prefix
 * because everything this app owns -- the SSH private key written without a
 * passphrase, the server's connection token -- shares a prefix with everything
 * it serves. A prefix test over app-private storage admits all of it, so it can
 * only ever stop a traversal out of the sandbox, never a read within it.
 *
 * Symbolic links are resolved rather than normalised away lexically. A workspace
 * is a published root and a workspace is routinely a checked-out repository, so a
 * link inside one is attacker-supplied in the ordinary case; `..` handling alone
 * would follow it out.
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
 * The workspace has to be a root: VS Code's own `localResourceRoots` includes
 * it, and without it a markdown preview cannot show an image sitting next to
 * the file being previewed. It cannot be a *static* root, because the user
 * chooses it: opening the home directory would publish the SSH key, which is
 * the whole thing being closed here.
 *
 * Overlap is tested in **both** directions. Containment alone would let the
 * user open `~/.ssh` itself as the workspace and get the directory published
 * because it contains nothing sensitive; it *is* the sensitive thing.
 *
 * [sensitive] is expected already canonical; [sensitiveLocations] is what
 * produces it, and doing that work here would put it on every request.
 */
internal fun workspaceRootOrNull(candidatePath: String?, sensitive: List<String>): String? {
    val candidate = candidatePath?.let(::canonicalOrNull) ?: return null
    return if (sensitive.any { overlaps(candidate, it) }) null else candidate
}

/**
 * The last workspace [resourceRootsInForce] refused, so the refusal is explained
 * once rather than on every request that follows it.
 *
 * The condition is sticky, which is what made the report a stream rather than an
 * event: the supplier answers the same folder for as long as it stays open and
 * the caller invokes it per webview resource request, so one markdown preview or
 * notebook render turned a single refused folder into hundreds of identical
 * lines, each repeating the absolute path, at a level that is not gated on a
 * debuggable build. Cleared whenever a candidate is accepted or absent, so a user
 * who closes the folder and opens it again is told again rather than met with
 * silence.
 *
 * A benign race, deliberately: two threads seeing the same stale value cost one
 * duplicate line, which is the failure this exists to bound rather than one it
 * reintroduces.
 */
@Volatile
private var lastRefusedWorkspace: String? = null

/**
 * The roots in force for one request: the published set, plus the open folder
 * when it is safe to publish.
 *
 * Both entry points go through here so that the two cannot drift apart. A
 * rejection is reported because the failure it causes (resources missing from
 * the user's own workspace) otherwise looks like a bug rather than a refusal.
 * Nothing is logged in the ordinary case: an accepted folder and an absent one
 * are both silent, so this only speaks when the workspace holds a location that
 * must stay unreadable, and then once per folder rather than once per request.
 * See [lastRefusedWorkspace].
 */
internal fun resourceRootsInForce(
    published: List<String>, sensitive: List<String>,
    /**
     * Already reduced to a directory by whoever supplied it.
     *
     * Reducing needs a `stat`, because a `.code-workspace` is a workspace only
     * when it is a FILE, and this function runs once per intercepted resource
     * request. `MainActivity.openWorkspaceRoot` therefore does it once per
     * navigation and both suppliers hand the answer, which keeps the stat off
     * this path and keeps a momentarily absent file from silently unpublishing
     * the root. Reducing here as well would be wrong and not merely wasteful: a
     * directory whose own name ends in `.code-workspace` would be reduced twice
     * and publish its parent.
     */
    workspaceRoot: String?,
): List<String> {
    val candidate = workspaceRoot
    val workspace = workspaceRootOrNull(candidate, sensitive)
    if (candidate != null && workspace == null) {
        if (candidate != lastRefusedWorkspace) {
            lastRefusedWorkspace = candidate
            Logger.w(
                "WebViewClient",
                "Workspace not published as a resource root, it holds a sensitive location: $candidate"
            )
        }
    } else {
        lastRefusedWorkspace = null
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

/**
 * The `tkn` query parameter, wherever it appears in a string.
 *
 * Also matches the tail of `vscode-tkn=`, the cookie the server sets from it,
 * which carries the same secret and is redacted for the same reason.
 */
private val TOKEN_PARAMETER = Regex("""tkn=[^&\s"']*""", RegexOption.IGNORE_CASE)

/**
 * [text] with the server's connection token taken out of it.
 *
 * The token authenticates every route but `/version`, `/delay-shutdown` and
 * `/callback`, so anything holding it can read what the server can read and open
 * a terminal. logcat is readable by anything holding `READ_LOGS`, which makes a
 * log line a disclosure rather than a diagnostic.
 *
 * `MainActivity.navigateToFolder` already builds a stripped copy of the
 * navigation URL to log, and says in a comment that it is the only place the
 * token could escape. It was not: the same value is appended by [withToken] to
 * every proxied request, and arrives here on the navigation URL that the page
 * callbacks are handed. Four statements printed it, and two of them
 * (`Page loaded` at `Logger.i` and the un-rewritable-URL warning at `Logger.w`)
 * are not gated on a debuggable build, so they shipped in release.
 *
 * Keyed on the parameter rather than on the token's value, because most of the
 * call sites below are in a companion object that never receives it. Two
 * ceilings follow from that and are worth naming, since a comment claiming
 * containment is worth nothing unless it also says where the containment stops:
 * a statement printing the bare token, in no `tkn=` at all, passes straight
 * through, and so does one that has been encoded again: `tkn%3D<value>` nested
 * inside another parameter is not the pattern below. Neither shape is produced
 * by any call site today, and `ConnectionTokenLoggingTest` drives the real call
 * sites rather than this function, so a statement that started producing one
 * fails there rather than here.
 *
 * Null becomes `"null"` so that redacting a nullable URL prints what string
 * interpolation already printed for it.
 */
internal fun redactToken(text: String?): String =
    text?.replace(TOKEN_PARAMETER, "tkn=<redacted>") ?: "null"

/**
 * The navigation the server-gave-up page's button makes.
 *
 * Lives here rather than in the activity because two different WebViewClients
 * are installed over one WebView's life and the page can be shown under either.
 * The bootstrap client answers this before the workbench has ever loaded; this
 * client answers it afterwards. A private copy in each was what left the button
 * dead in the second case, so there is one literal and both read it.
 */
internal const val RETRY_URL = "vscodroid://retry-server"

/**
 * Why the WebView refused a certificate, in the shape a sentence can be built from.
 *
 * [HANDSHAKE] is not one of `SslError`'s codes and never arrives as one. It
 * stands for the other half of TLS failure: the javadoc on
 * `WebViewClient.onReceivedSslError` says that callback is reached only for
 * recoverable certificate errors, and that a non-recoverable one is delivered to
 * `onReceivedError` with `ERROR_FAILED_SSL_HANDSHAKE` instead. Both halves are
 * reported as this one type so that the presenter has one thing to branch on
 * rather than two channels that can drift apart.
 */
enum class TlsFailureReason { UNTRUSTED, HOSTNAME, DATE, INVALID, HANDSHAKE }

/**
 * One refusal, carrying as much of it as is safe to repeat back to the user.
 *
 * The host, and never the address. The failing URL here is whatever the open page
 * asked for, and a dev server's address can carry an OAuth code or an API key in
 * its query, so quoting it would put a credential into a toast and into logcat.
 * `url_handoff_no_app` names a scheme rather than a whole address for the same
 * reason, and keeping to the host is also why [redactToken] needs no widening for
 * this path.
 *
 * The host is null when [tlsHostLabel] could not read one. The presenter has a
 * phrase for that case; interpolating an empty string would leave a sentence with
 * a hole in it.
 *
 * Public, unlike the helpers around it, and not by preference: it is the type the
 * public constructor's `onTlsFailure` parameter carries, and Kotlin refuses an
 * internal type in a public signature.
 */
data class TlsFailure(val host: String?, val reason: TlsFailureReason)

/**
 * The reason behind an `SslError`'s primary code.
 *
 * Only four of the six codes can reach this from a WebView.
 * `SslError.SslErrorFromChromiumErrorCode` builds every error the platform
 * produces here with `SSL_IDMISMATCH`, `SSL_DATE_INVALID`, `SSL_UNTRUSTED` or
 * `SSL_INVALID`, so `SSL_EXPIRED` and `SSL_NOTYETVALID` are legacy values on this
 * path. They are still listed, because they mean exactly what the DATE branch
 * says and letting them fall to the `else` would tell a user with an expired
 * certificate that it could not be validated, which sends them looking at the
 * wrong thing.
 *
 * An unrecognised code becomes INVALID rather than throwing. This runs inside a
 * callback whose contract is that the request gets an answer, and a slightly
 * vague word in a message costs far less than a load that never resolves.
 */
internal fun tlsReasonOf(primaryError: Int): TlsFailureReason = when (primaryError) {
    SslError.SSL_UNTRUSTED -> TlsFailureReason.UNTRUSTED
    SslError.SSL_IDMISMATCH -> TlsFailureReason.HOSTNAME
    SslError.SSL_DATE_INVALID, SslError.SSL_EXPIRED, SslError.SSL_NOTYETVALID ->
        TlsFailureReason.DATE
    else -> TlsFailureReason.INVALID
}

/**
 * The `host:port` a failing URL names, or null when it does not yield one.
 *
 * Parsed with `java.net.URI` and deliberately not `android.net.Uri`: the second is
 * a stub in the unit-test `android.jar`, so a case written against it would
 * measure the mock rather than the parse. `UrlAllowlistWiringTest` already
 * records that trade in the same words.
 *
 * The price of that choice, accepted rather than overlooked: `java.net.URI`
 * answers a null host for a name containing an underscore, where `android.net.Uri`
 * would answer one. It degrades to the presenter's generic phrase and decides
 * nothing, because this label is only ever printed and never compared.
 *
 * Everything unreadable has to reach null rather than an empty label, and it gets
 * there by two routes rather than three. `URI("nonsense")` returns a null host
 * without throwing, while `URI("not a url")` throws on the space, so the null host
 * and the exception both have to answer null. The empty string that two of
 * `SslError`'s four constructors set the url to is deliberately NOT a third route:
 * it parses to a null host like any other address with nothing in it, and a guard
 * in front of it would be a line no test could ever redden. The null check that
 * remains is not optional, because `URI` has no constructor taking null.
 *
 * The port is left off when there is none, because `URI.getPort()` answers -1 and
 * a message reading `host:-1` looks like a bug in the app rather than a fact about
 * the server.
 */
internal fun tlsHostLabel(url: String?): String? {
    if (url == null) return null
    return try {
        val parsed = URI(url)
        val host = parsed.host ?: return null
        if (parsed.port > 0) "$host:${parsed.port}" else host
    } catch (e: URISyntaxException) {
        null
    }
}

/**
 * A page-chosen address reduced to what a log statement may repeat: its scheme
 * and its host, and never its query.
 *
 * The rule [TlsFailure] states for a notice is the same rule logcat needs, and
 * the same value arrives on both channels: the address a page asks this app to
 * hand away is whatever that page chose, and a sign-in, a share link or a dev
 * server preview carries an OAuth `code`, a `state` or an API key in its query.
 * [redactToken] is keyed on `tkn=`, which is this app's own parameter and never
 * appears on an address bound for another app, so a statement built on it printed
 * the credential whole at `Logger.i` and `Logger.e`, neither of which is gated on
 * a debuggable build.
 *
 * The scheme is kept, and it is the fallback when there is no host, because it is
 * the part of a failed hand-off a reader acts on: `ssh:` with no app to answer it
 * is a different problem from a browser that refused an `https:` launch. It is
 * read lexically rather than through `java.net.URI`, which throws on addresses
 * `android.net.Uri` accepts, and a label on the way to a log line must not throw.
 *
 * Everything unreadable reaches a phrase rather than an empty string, so a
 * sentence built on this never comes out with a hole in it.
 */
internal fun urlLogLabel(url: String?): String {
    val host = tlsHostLabel(url)
    val scheme = url?.substringBefore(':', "")?.takeIf { candidate ->
        candidate.isNotEmpty() && candidate.all { it.isLetterOrDigit() || it in "+-." }
    }
    return when {
        scheme != null && host != null -> "$scheme://$host"
        host != null -> host
        scheme != null -> "$scheme:"
        else -> "an unreadable address"
    }
}

/**
 * How many refusals are put on screen, and with them how many are remembered.
 *
 * The hosts that end up in the record are chosen by whatever page is open, and one
 * of those pages is the bundled simple browser holding an arbitrary remote site.
 * So the page picks both numbers, and each of them is a cost on its own: the set is
 * an allocation, and every distinct host is another toast holding the bottom of the
 * editor for about three and a half seconds.
 *
 * Clearing the record at the cap bounded only the first. A page minting fresh
 * hostnames was announced once per name for as long as it cared to, and the clear
 * made it worse, because the hosts already said became sayable again.
 */
internal const val MAX_TLS_FAILURES_ANNOUNCED = 8

/**
 * How either notice record goes on refusing once it is past its cap.
 *
 * A hard cap bounds the messages and never lets go, which is the wrong trade for
 * a channel that is only ever advice: the page is refused before any of this
 * runs, so what a muted notice costs is the explanation, and a session here is a
 * working day rather than a page view. Eight is generous for one burst and mean
 * for a day, so past it the record lets one failure through per interval and no
 * more: a burst that does not stop is throttled to that rate rather than muted,
 * and a fault the user walks into after a quiet stretch is explained the moment
 * it happens. Same shape of interval as the write-back notice
 * (`SafStorageManager.FAILURE_NOTICE_INTERVAL_MS`) and three times its length,
 * which is the number to keep in view if either is ever tuned: long enough that a
 * wall of toasts cannot form, short enough that a fault the user has just walked
 * into is still explained.
 */
internal const val NOTICE_INTERVAL_MS = 30_000L

/**
 * The refusal worth putting on screen, or null when it has been said already.
 *
 * The same rule `reasonToAnnounce` applies to a failed toolchain download, for the
 * same reason: toasts stack rather than replace, each one holds the screen for
 * about three and a half seconds, and one page can fail many requests to one host
 * with nothing in between. A markdown preview pulling a dozen images from a host
 * with a self-signed certificate would otherwise hold the bottom of the editor for
 * the better part of a minute, over an editor the user has gone back to working in.
 *
 * Keyed on the host and the reason together. A second image from the same host is
 * the same fact and adds nothing to act on; a second host, or the same host
 * failing a different way, is a new fact and still gets through.
 *
 * Past [MAX_TLS_FAILURES_ANNOUNCED] distinct failures the rate is what is bounded
 * rather than the total: one more failure is announced per
 * [NOTICE_INTERVAL_MS], and the record stays full at the cap. Eight refusals
 * is already more than a reader acts on, and the ninth is the point at which the
 * page, not the user, is choosing how long the editor stays covered.
 *
 * [alreadySaid] belongs to the presenter, which keeps this class free of mutable
 * state, and its lifetime falls out of that: it survives a renderer crash, because
 * the certificate has not changed, and it dies with the Activity, so a fresh
 * launch says everything again.
 *
 * [now] and [lastAnnouncedAt] must be readings of the monotonic clock, for the
 * reason [handoffFailureToAnnounce] states in full: a wall clock steps backwards
 * on an NTP correction or when the user sets the device time, and a backward step
 * larger than [NOTICE_INTERVAL_MS] silences the channel until it catches up. This
 * one takes them from the caller rather than defaulting, because the reading it
 * compares against is a field on the presenter; the caller takes
 * `SystemClock.elapsedRealtime()`, and `TlsNoticeClockTest` pins that choice at
 * the call site, which is the only place the wall clock could get back in.
 */
internal fun tlsFailureToAnnounce(
    failure: TlsFailure,
    alreadySaid: MutableSet<TlsFailure>,
    now: Long,
    lastAnnouncedAt: Long,
): TlsFailure? =
    failureToAnnounce(failure, alreadySaid, now, lastAnnouncedAt, MAX_TLS_FAILURES_ANNOUNCED)

/**
 * The rule both notice channels apply, over whatever they key their record on.
 *
 * One function rather than one per channel, because the two had drifted: the
 * certificate record kept explaining a fault met after a quiet stretch while the
 * hand-off record went permanently silent at its cap, on the same shape of
 * problem and with a comment on each claiming the same reasoning. Sharing it is
 * what stops that happening again.
 *
 * Nothing is said twice: a failure already in the record is not a new fact. Past
 * [cap] distinct failures the rate is what is bounded rather than the total, one
 * more per [NOTICE_INTERVAL_MS], and the eldest entry is evicted so the record
 * stays full at the cap. Emptying it instead put the set back under the cap, so
 * a page minting fresh keys was silent for the interval and then given a whole
 * cap's worth of toasts back to back, about twenty-eight seconds of screen, for
 * as long as it cared to carry on.
 *
 * Eldest first: the callers' sets are `LinkedHashSet`, so the iterator yields
 * insertion order. A set with another order still evicts and stays bounded; only
 * which failure becomes sayable again would change.
 */
private fun <T> failureToAnnounce(
    failure: T,
    alreadySaid: MutableSet<T>,
    now: Long,
    lastAnnouncedAt: Long,
    cap: Int,
): T? {
    if (failure in alreadySaid) return null
    if (alreadySaid.size >= cap) {
        if (now - lastAnnouncedAt < NOTICE_INTERVAL_MS) return null
        with(alreadySaid.iterator()) {
            next()
            remove()
        }
    }
    alreadySaid.add(failure)
    return failure
}

/**
 * One hand-off this app could not complete, reduced to the two things a notice
 * about it can say.
 *
 * The scheme, and never the address. `url_handoff_no_app` already names only a
 * scheme, for the reason [TlsFailure] gives: the failing URL is whatever the open
 * page asked for, and a sign-in or a dev server's address can carry an OAuth code
 * or an API key in its query, so repeating it back would put a credential into a
 * toast and into logcat.
 *
 * [failureType] is the exception's simple class name, which is the only other
 * thing the two message forms print: `ActivityNotFoundException` is the one the
 * user can act on by installing something, and every other type is quoted for a
 * bug report.
 *
 * So the pair is exactly what determines the sentence, which is what makes it the
 * right key for [handoffFailureToAnnounce].
 */
internal data class HandoffFailure(val scheme: String, val failureType: String)

/**
 * How many hand-off failures are put on screen, and with them how many are
 * remembered.
 *
 * Same bound and same reasoning as [MAX_TLS_FAILURES_ANNOUNCED]. The schemes and
 * exception types that end up in the record are chosen by whatever page is open,
 * so the page picks the size of the set and the number of toasts alike, and a cap
 * that only cleared the set left the second one to it.
 */
internal const val MAX_HANDOFF_FAILURES_ANNOUNCED = 8

/**
 * When [handoffFailureToAnnounce] last let a failure through.
 *
 * Module state for the reason that function's comment gives, and benign under a
 * race for the reason [lastRefusedWorkspace] is: two threads reading the same
 * stale reading cost one extra notice or one delayed one, which is the failure
 * this exists to bound rather than one it reintroduces.
 *
 * `internal` for one reason only: the whole unit suite runs in one JVM, so this
 * reading outlives the case that set it. Every case that reaches the cap today
 * announces at least once first and therefore sets it, which makes them
 * deterministic by accident of how they were written; `ExternalUrlNoticeThrottleTest`
 * zeroes it per case so that a later one need not be.
 */
@Volatile
internal var lastHandoffNoticeAt = 0L

/**
 * The hand-off failure worth putting on screen, or null when it has been said
 * already.
 *
 * The same rule as [tlsFailureToAnnounce] and for the same measured reason: toasts
 * stack rather than replace, each one holds the screen for about three and a half
 * seconds, and one page can drive many navigations with nothing in between.
 *
 * Keyed on [HandoffFailure], which is to say on the scheme and the exception type
 * and deliberately NOT on the URL. Two reasons, and the second is the load-bearing
 * one. A second `ssh:` link that no app answers is the same fact and the same
 * sentence, so it adds nothing to act on. And a URL-keyed record would be one the
 * content can defeat at will: a page navigating to `ssh://a1`, `ssh://a2` and so on
 * mints a fresh key per navigation while producing an identical message, which is
 * a throttle that throttles nothing and a set that grows on strings a page chooses.
 *
 * Past [MAX_HANDOFF_FAILURES_ANNOUNCED] distinct failures the rate is bounded and
 * not the total, which is [failureToAnnounce]'s rule and now literally the same
 * code. It used to be a hard stop, and the two channels had drifted: a user who
 * met eight distinct scheme-and-exception pairs in the morning was told nothing
 * for the rest of the Activity's life, and the case this channel exists for -- a
 * sign-in or an `ssh:` clone link that no installed app answers -- is exactly the
 * one that arrives late in a session and looks like a dead link when it is not.
 *
 * [alreadySaid] belongs to the presenter, which keeps this class free of mutable
 * state, and its lifetime falls out of that: it dies with the Activity, so a fresh
 * launch says everything again.
 *
 * [lastHandoffNoticeAt] does not, and that asymmetry is deliberate rather than
 * overlooked. The certificate channel takes its reading from the presenter, which
 * holds one for its own record; adding a second field there would mean changing
 * every caller of this function to pass it, for a value that decides nothing until
 * eight distinct failures have accumulated in a set that is empty at every launch.
 * A reading left over from a previous Activity can only ever delay one notice by
 * up to the interval, in a session that has already reached the cap.
 *
 * [now] defaults to the monotonic clock and must not be given the wall clock,
 * which is the rule `SafStorageManager.onWriteBackFailed` states for the notice
 * this one copies its interval from. `System.currentTimeMillis()` steps
 * backwards on an NTP correction or when the user sets the device time, and a
 * backward step larger than the interval makes the subtraction negative and
 * silences the channel until the clock catches up; a forward step releases one
 * notice early. The default is what the production caller takes, so the choice
 * has to be right here rather than at the call site.
 */
internal fun handoffFailureToAnnounce(
    failure: HandoffFailure,
    alreadySaid: MutableSet<HandoffFailure>,
    now: Long = SystemClock.elapsedRealtime(),
): HandoffFailure? {
    val announced = failureToAnnounce(
        failure, alreadySaid, now, lastHandoffNoticeAt, MAX_HANDOFF_FAILURES_ANNOUNCED
    )
    if (announced != null) lastHandoffNoticeAt = now
    return announced
}

/**
 * [wrapped], stopped after [remaining] bytes.
 *
 * A 206 declares how many bytes it is sending and must send exactly that many.
 * A `FileInputStream` seeked to the start of a range runs to the end of the
 * file, so without this the body would be longer than its own `Content-Length`
 * on every range that is not a suffix.
 *
 * `java.io` has no bounded stream and the app carries no library that does, so
 * this is the whole of it. `close()` is inherited from `FilterInputStream` and
 * closes the file, which is what the WebView calls when it is done with the
 * response.
 */
internal class BoundedInputStream(wrapped: java.io.InputStream, private var remaining: Long) :
    FilterInputStream(wrapped) {

    override fun read(): Int {
        if (remaining <= 0) return -1
        val byte = super.read()
        if (byte >= 0) remaining--
        return byte
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (remaining <= 0) return -1
        val read = super.read(b, off, minOf(len.toLong(), remaining).toInt())
        if (read > 0) remaining -= read
        return read
    }

    /**
     * The third way a consumer moves through this stream, and the one that was
     * left delegating straight to the file.
     *
     * `FilterInputStream.skip` forwards to the wrapped `FileInputStream` and
     * never touches [remaining], so a consumer that skipped slid the window past
     * the end of the range while this class went on handing out [remaining] more
     * bytes: the count in `Content-Length` stayed right and the offsets stopped
     * matching the `Content-Range` beside it. Whether android_webview's stream
     * loader skips a response this arm has already positioned with
     * `channel.position` is not measured, which is the same gap the note on the
     * 206 records; bounding it costs one line either way.
     */
    override fun skip(n: Long): Long {
        val skipped = super.skip(minOf(n, remaining))
        if (skipped > 0) remaining -= skipped
        return skipped
    }

    override fun available(): Int = minOf(super.available().toLong(), remaining).toInt()
}

@SuppressLint("MissingOnRenderProcessGone")
class VSCodroidWebViewClient(
    private val allowedPort: Int,
    private val resourceRoots: List<String>,
    private val sensitiveLocations: List<String>,
    private val openFolder: () -> String?,
    private val connectionToken: () -> String?,
    private val onCrash: () -> Unit,
    private val onPageLoaded: (String?) -> Unit,
    private val onRetryServer: () -> Unit,
    /**
     * Told when a URL this app decided to hand away could not be handed away.
     *
     * A constructor parameter for the reason the three above are: this class has
     * no screen and no context of its own beyond the one the request carries.
     * Until it existed, `startActivity` throwing meant a `Logger.e` and nothing
     * else, and `return true` below stops the WebView from navigating either, so
     * the tap did nothing at all and said nothing at all. `ActivityNotFoundException`
     * for `ssh:`, `git:` or `intent:`, `FileUriExposedException` for `file:` and
     * `SecurityException` for a `content://` without a grant all land in the same
     * catch and all looked identical to a broken link.
     *
     * Deliberately NOT a pre-flight. Detecting handlers with `<queries>` plus
     * `resolveActivity` would answer null for handlers that do exist under
     * package-visibility filtering, and enumerating schemes to make it work is a
     * destination allowlist under another name. The exception is the signal.
     *
     * Not told about every failure, and the call site says which ones it keeps to
     * itself. The presenter still owes this a second filter: see
     * [handoffFailureToAnnounce], which holds the record of what has been said.
     */
    private val onHandoffFailed: (Uri, Throwable) -> Unit = { _, _ -> },
    /**
     * Told when the WebView refused a certificate, or could not negotiate TLS at all.
     *
     * A constructor parameter for the reason [onHandoffFailed] is one: this class
     * has no screen and no context of its own beyond the one the request carries,
     * so the caller decides how a refusal is said.
     *
     * Nothing was told before this existed. The platform default for
     * `onReceivedSslError` cancels the request and reports to no channel at all,
     * and the handshake half of the same failure was dropped by the main-frame
     * gate in [onReceivedError]. What the user saw was an empty tab.
     */
    private val onTlsFailure: (TlsFailure) -> Unit = { },
    /**
     * Where the translated interface bundles are read from, or null to leave
     * the interface in English.
     *
     * An `AssetManager` and not a `Context`, for the reason the callbacks above
     * are functions: this class has no context of its own and nothing here
     * needs one. The bundles are read straight out of the APK, so there is no
     * path to hand over instead.
     */
    private val interfaceTranslations: AssetManager? = null,
) : WebViewClient() {
    // MissingOnRenderProcessGone: overridden below, and what it does there is the
    // reason the class exists in one piece. The check misses the override on a
    // Kotlin class with a primary constructor.

    private val tag = "WebViewClient"

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url
        // The one URL here that is a control on our own page rather than an
        // address to hand away. Both conditions are load-bearing. The main-frame
        // gate is the same one AuthTabWindow.arm already takes below, and for the
        // same reason: this client sees subframe navigations from pages built out
        // of workspace content and from the bundled simple browser, so without it
        // any remote page could blank the editor and restart the server from a
        // hidden iframe. The comparison is the whole string, so a query appended
        // to the scheme and host does not qualify.
        if (request.isForMainFrame && url.toString() == RETRY_URL) {
            Logger.i(tag, "Retrying the server from the error page")
            onRetryServer()
            return true
        }
        if (isLocalhost(url) || isCdnRedirect(url)) {
            return false
        }
        // Everything below the main frame, and the whole of this block is about
        // what happens IF such a navigation arrives here. Whether it does is the
        // one thing in this file nobody has measured, and it is worth stating
        // rather than assuming in either direction. The platform documents this
        // callback as one that "may be called for subframes", which is what
        // `isForMainFrame` is for and what the rest of this class is written
        // against; against that, DEVICE_TEST_CHECKLIST ED-11 records the bundled
        // simple browser reaching a bad certificate at `https://10.0.2.2:8443`
        // and producing `TLS refused` from `onReceivedSslError`, which can only
        // happen if that iframe's own navigation was performed by this WebView
        // rather than handed away here.
        //
        // Both readings are answered by the same rules, so nothing rests on
        // settling it: if subframe navigations never arrive, this block never
        // runs and the behaviour is exactly what ED-11 recorded.
        if (!request.isForMainFrame) {
            // Rendered here rather than handed to another app, because that is
            // what two shipped features need. The bundled simple browser puts the
            // address the user typed into an `<iframe sandbox=...>` by assigning
            // its `src` (`extensions/simple-browser/media/index.js`), and a
            // dev-server preview does the same for a port the user is serving on.
            // Handing either away leaves the panel blank with the editor covered
            // by another app, and the port test above cannot rescue them: it
            // admits the editor's own port and no other, so 5173 is as external
            // to it as a remote host.
            //
            // Only http and https. A subframe naming a scheme this WebView cannot
            // load falls through to the gesture test below, so the user can still
            // follow a `mailto:` link inside a preview while a script driving one
            // gets nothing.
            val scheme = url.scheme?.lowercase()
            if (scheme == "http" || scheme == "https") return false
            // No user gesture behind it, and not our own page: nothing leaves.
            // The launch below carries FLAG_ACTIVITY_NEW_TASK and asks for no
            // activation, so a hidden frame reassigning `src` on a timer would
            // bring an arbitrary installed app to the front over a live editor,
            // as often as it liked, with the user having touched nothing. The
            // frames that reach here are the ones this app does not vouch for:
            // the remote site in the simple browser, previews and notebook output
            // built from workspace files. `hasGesture()` is the request's own
            // record of whether a user did this, which is the same signal the
            // notice below already depends on, so the action and the message
            // about it now turn on one question rather than two.
            if (!request.hasGesture()) {
                Logger.d(
                    tag,
                    "Refused an external navigation with no user gesture behind it at " +
                        urlLogLabel(url.toString())
                )
                return true
            }
        }
        // The one destination this method judges, on either frame, and refused
        // ahead of the arming below rather than merely left unarmed. Same test
        // and same order as `AndroidBridge.openExternalUrl`, and the two exits
        // are meant to stay in step.
        //
        // `vscodroid://callback` is this app's own sign-in relay. Its VIEW filter
        // is exported and BROWSABLE, so handing one to `startActivity` delivers
        // it straight back into `MainActivity.onNewIntent`, and a main-frame
        // launch arms the ids the address carries first: one navigation would
        // both open the window and post the callback that window judges. The
        // launch alone is the other half, and the frame test below does not
        // cover it: a subframe arms nothing, but a tapped link in the simple
        // browser still posted a payload of the remote site's choosing for an id
        // a real sign-in had in flight.
        //
        // Both routes here are ones the bridge never sees. `injectWindowOpenOverride`
        // sends only http and https to the bridge, and the workbench's own opener
        // assigns `location.href` for every other scheme, so an extension calling
        // `openExternal` on this address navigates the main frame to it. Nothing
        // legitimate is lost: a sign-in returns by the browser firing the filter,
        // never by this WebView navigating to the app's own scheme. Logged
        // without the address, for the reason [urlLogLabel] gives, and here the
        // query is a payload someone forged.
        if (isExtensionCallback(url.scheme, url.host)) {
            Logger.w(tag, "Refused to open this app's own sign-in callback")
            return true
        }
        // Empty until this launch arms something, and then the ids to take back
        // if the launch throws. Same shape as `AndroidBridge.openExternalUrl`,
        // and for the same reason: arming precedes the launch, so a launch
        // nothing accepted would otherwise leave the relay open with no sign-in
        // in flight.
        var armed: List<String> = emptyList()
        // Fix #7: Open external URLs in system browser instead of blocking silently
        try {
            val intent = Intent(Intent.ACTION_VIEW, url)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            // The app's second way out to a browser, and the one that recorded
            // nothing. The bridge is the route the workbench normally takes, but
            // `MainActivity.injectWindowOpenOverride` falls through to a plain
            // navigation whenever the page holds no session token yet or the
            // bridge reports no launch, and the workbench's own opener assigns
            // `location.href` for every scheme that is not http or https. A
            // sign-in leaving either way returns by the same
            // `vscodroid://callback` and was judged against a window nobody had
            // opened, so it was refused in the log and the sign-in hung with
            // nothing said.
            //
            // Main frame only, and that is the boundary rather than a detail.
            // Both routes above navigate the top-level frame: the injected
            // override patches the main window's `window.open`, and the opener
            // assigns the main window's `location`. Nothing else here is our
            // own page. Every other frame renders content this app does not
            // vouch for, including the remote site the bundled simple browser
            // puts in an iframe, and a frame may always navigate itself whatever
            // its sandbox says. Arming on those would let a page the user merely
            // opened choose which callback ids this app accepts, and evict the
            // sign-in actually in flight from a record that keeps the most
            // recent few. The bridge route is gated by the session token; this
            // one has no token to check, so the frame is what stands in for it.
            //
            // What is armed is the request ids the address carries, never the
            // fact that a browser opened: a documentation link carries none and
            // so opens nothing, which is what keeps this from widening the
            // window every external link is followed.
            if (request.isForMainFrame) {
                armed = AuthTabWindow.arm(
                    authRequestIdsIn(url.toString()), SystemClock.elapsedRealtime()
                )
            }
            view.context.startActivity(intent)
            // The address itself is not repeated, for the reason [urlLogLabel]
            // gives: it is the page's to choose and its query is where a sign-in
            // puts an OAuth code. This line is at `Logger.i`, which is not gated on
            // a debuggable build, so it ships.
            Logger.i(tag, "Opened an external URL at ${urlLogLabel(url.toString())}")
        } catch (e: Exception) {
            AuthTabWindow.disarm(armed)
            // Two channels, and both of them are closed here rather than one. The
            // address is reduced to a scheme and a host by [urlLogLabel], because
            // it is the page's to choose and its query is where a sign-in puts an
            // OAuth code. And the throwable is deliberately not passed: leaving it
            // in was a redaction that only looked like one, since
            // `Log.e(tag, msg, tr)` prints the throwable with its message and both
            // exceptions this realistically catches put the whole address there.
            // ActivityNotFoundException quotes the Intent it could not match,
            // `dat=` and all, and FileUriExposedException names the file it
            // refused, so whatever the message had left out arrived in logcat two
            // lines below it on a statement that is not gated on a debuggable build
            // and therefore ships. The frames behind it are all framework, so the
            // class name is what the trace was being read for anyway. Same shape
            // and same reasoning as `AndroidBridge.openExternalUrl`.
            Logger.e(
                tag,
                "Could not open an external URL at ${urlLogLabel(url.toString())} " +
                    "(${e.javaClass.simpleName})"
            )
            // A link the user taps in a preview or in the simple browser still
            // leaves for a browser: the gate above lets a subframe navigation
            // through once a gesture is behind it. What is gated here is only the
            // notice, and it is gated on the same signal for a second reason.
            //
            // It has to be, because this callback receives subframe navigations
            // and the frames are not all ours. A script can navigate an iframe to
            // a scheme nothing on the device answers, with no user gesture and as
            // often as it likes, and each failure was an unconditional
            // `Toast.LENGTH_LONG` -- about three and a half seconds of screen,
            // stacking rather than replacing. That is a rendered page holding a
            // sustained stream of notices over a live editor, and the content that
            // reaches this client includes the bundled simple browser holding an
            // arbitrary remote site plus previews and notebook output built from
            // workspace files.
            //
            // `hasGesture()` is the request's own record of whether a user gesture
            // was associated with the navigation, so it answers the question a
            // notice depends on: did the user do this. It is a disjunction with the
            // frame test rather than a conjunction, and the asymmetry is deliberate.
            // The main frame is the workbench, the one document here this app
            // serves, and it is also where both routes this channel backs up
            // navigate: `injectWindowOpenOverride` falls through to a plain
            // navigation, and the workbench's own opener assigns `location.href`
            // for every scheme that is not http or https. Whether user activation
            // survives those chains is not measured here, and losing the notice on
            // them would silence exactly the case it was added for, a sign-in or an
            // `ssh:` clone link that no app answers. So the main frame keeps its
            // notice unconditionally, and the gesture is what still earns one for a
            // link the user genuinely tapped inside a preview. Only a navigation
            // that is neither our own page nor something the user did is silent,
            // and it stays in the log line above.
            if (request.isForMainFrame || request.hasGesture()) {
                onHandoffFailed(url, e)
            }
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
        // Before the CDN arm, because this one answers an address on the app's
        // own origin and that arm refuses everything which is not
        // *.vscode-cdn.net. See [nlsBundleRequested] for the shape.
        if (isLocalhost(request.url)) {
            val requested = nlsBundleRequested(request.url) ?: return null
            return interfaceBundleResponse(requested)
        }
        return interceptCdnRequest(
            request, allowedPort, connectionToken(), resourceRoots, sensitiveLocations, openFolder
        )
    }

    /**
     * Answers the page's request for a translated interface bundle.
     *
     * A language this build does not carry is a 404 and not an error: the page
     * has already loaded the English bundle by the time it asks for this one
     * (`workbench.html` loads the fallback in the script tag above), so a
     * missing translation leaves the interface in English, which is the outcome
     * every build had before the bundles existed.
     */
    private fun interfaceBundleResponse(requested: String): WebResourceResponse {
        val assets = interfaceTranslations ?: return notFound("no interface bundles in this build")
        val bundle = EditorLocale.resolveTag(requested, EditorLocale.available(assets))
            ?: return notFound("no interface bundle for $requested")
        val messages = EditorLocale.open(assets, bundle)
            ?: return notFound("interface bundle $bundle could not be opened")

        // The asset is the JSON array on its own; the page needs it as a script
        // that assigns two globals. Wrapping here rather than shipping the
        // script form keeps one copy of 1 MiB per language in the APK.
        //
        // The second global is not decoration. `_VSCODE_NLS_MESSAGES` is what
        // the interface reads, but `_VSCODE_NLS_LANGUAGE` is what it answers
        // when asked which language it is in: the workbench reads it for
        // `vscode.env.language`, for the date and number formats it hands
        // extensions, and it copies both into every worker it starts. Upstream
        // sets them together in one bundle; the English fallback that ships in
        // the tree sets neither. Serving only the messages left every string
        // translated and every extension told the editor was in English.
        val body = SequenceInputStream(
            java.util.Collections.enumeration(
                listOf(
                    ByteArrayInputStream(NLS_ASSIGNMENT_PREFIX.toByteArray()),
                    messages,
                    ByteArrayInputStream(";globalThis._VSCODE_NLS_LANGUAGE=\"$bundle\";".toByteArray()),
                )
            )
        )
        return WebResourceResponse(
            "text/javascript", "utf-8", 200, "OK",
            // Deliberately not cached, unlike the versioned assets beside it.
            // The address carries the editor's commit and version, and the
            // bundles are rebuilt from whatever VSCODE_LOC_COMMIT pins, so two
            // builds can serve different strings at one URL and an immutable
            // year would pin a user to the older ones for as long as the WebView
            // kept them. Answering costs one read out of the APK.
            mapOf("Cache-Control" to "no-store"),
            body
        )
    }

    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
        // The URL the activity navigates to carries the connection token in its
        // query, so this is one of the two callbacks handed the very string
        // MainActivity took care not to print. See [redactToken].
        Logger.d(tag, "Page loading: ${redactToken(url)}")
    }

    /**
     * Carries the URL, because this is the only notice Kotlin gets when VS Code
     * switches folders on its own: it navigates this WebView without going
     * through us, so the URL is the only truthful record of the open workspace.
     *
     * That this notice is *sufficient* is a property of the shipped bundle, not
     * an assumption: `out/vs/code/browser/workbench/workbench.js`, the file
     * `workbench.html` loads, contains no `pushState` and no `replaceState`, so
     * the workbench cannot change its URL without a real navigation. That is why
     * there is no `doUpdateVisitedHistory` override here; re-run the grep before
     * adding one, with a live control such as `addEventListener` beside it.
     *
     * `workbench.web.main.internal.js` used to be named here as a second bundle
     * to grep. It is not one: nothing in the packaged tree loads it, which is why
     * the build's prune stage deletes it.
     */
    override fun onPageFinished(view: WebView, url: String?) {
        // Redacted for the log only. [onPageLoaded] gets the URL as it arrived,
        // because `MainActivity` reads the open workspace folder out of it. It
        // has another source for that folder -- it sets it directly when it is
        // the one driving the navigation -- but not for the case this callback
        // exists to cover: the workbench switches folders by navigating itself,
        // without going through Kotlin, and then the URL is the only notice
        // there is. A redacted copy would lose the folder on exactly those loads.
        //
        // Labelled rather than redacted, because this statement is at `Logger.i`,
        // which is not gated on a debuggable build. `redactToken` removes the
        // token and nothing else, so the folder rode out in release logcat on
        // every load and every folder switch, on a channel READ_LOGS can read.
        // That is the same reason the refusals inside `interceptResourceRequest`
        // are at `Logger.d`. The label keeps what this line is for, saying a load
        // finished, and `onPageLoaded` below still receives the URL whole.
        Logger.i(tag, "Page loaded: ${urlLogLabel(url)}")
        onPageLoaded(url)
    }

    /**
     * Refuses the certificate, and says so.
     *
     * Without this override the platform default runs, and its entire behaviour
     * is to cancel: the javadoc on `WebViewClient.onReceivedSslError` states that
     * in those words. So the request was already being refused before this
     * existed. What is added here is that the refusal is now audible, and that
     * matters because nothing downstream turns a failed load into anything on
     * screen: `MainActivity.showServerGaveUp` is driven by the server's startup
     * state and never by a load error, so what the user got was an empty simple
     * browser tab or an empty preview pane and no way to tell why.
     *
     * It reports and never proceeds. `proceed()` would trust a certificate that
     * nothing validated; the same javadoc says to always cancel and never proceed
     * past errors, and Google Play's insecure-SSL-error-handler policy refuses
     * applications that do. Cancelling and
     * reporting is not that decision and does not approach it.
     *
     * It is a notice and not a prompt, which is the platform's other instruction
     * in the same block: do not prompt the user about SSL errors, because they
     * cannot make an informed decision and the WebView shows no certificate
     * detail to base one on. There is no control here that could continue, and
     * adding one is the change this comment exists to refuse.
     *
     * The callback carries no `WebResourceRequest` and no `isForMainFrame`, so it
     * answers for the main frame, subframes and subresources alike. The first of
     * those cannot arise in this app: the workbench is loaded over plain http on
     * loopback. What is left is the realistic case, a page the user pointed the
     * editor at.
     *
     * It does not make a private CA work, and the message must not suggest it
     * might. This app trusts the Android system store only, because
     * `network_security_config.xml` declares no trust anchors and `targetSdk` is
     * 36, for which the platform default is system roots. A CA installed through
     * Android Settings is therefore not read. Changing that means declaring a user
     * certificate source, which widens every https connection the app makes and is
     * a separate decision from this one.
     */
    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
        // First, and on every path through this method, with nothing above it
        // that can throw. The contract is that exactly one of cancel() and
        // proceed() is called, so an override that returns having called neither
        // leaves that request's certificate decision outstanding for ever, which
        // is a worse silence than the one being closed here.
        handler.cancel()
        val failure = TlsFailure(tlsHostLabel(error.url), tlsReasonOf(error.primaryError))
        Logger.w(
            tag, "TLS refused for ${failure.host ?: "an unreadable address"}: ${failure.reason}"
        )
        onTlsFailure(failure)
    }

    override fun onReceivedError(
        view: WebView,
        request: WebResourceRequest,
        error: WebResourceError
    ) {
        // The other half of TLS failure, and the half that reached no channel at
        // all. onReceivedSslError is called only for recoverable certificate
        // errors; its javadoc says a non-recoverable one, such as the server
        // rejecting the client, arrives here with ERROR_FAILED_SSL_HANDSHAKE
        // instead. The main-frame gate below then swallowed it outright for a
        // subframe or a subresource, which is where every https load in this app
        // happens: the workbench itself is plain http on loopback.
        //
        // The int comparison comes first so an ordinary load error costs one
        // comparison, as this callback's javadoc asks. There is deliberately no
        // early return, so a handshake failure in the main frame still reaches
        // the line below; two log lines for that one case is the cheaper trade.
        if (error.errorCode == WebViewClient.ERROR_FAILED_SSL_HANDSHAKE) {
            onTlsFailure(
                TlsFailure(tlsHostLabel(request.url.toString()), TlsFailureReason.HANDSHAKE)
            )
        }
        if (request.isForMainFrame) {
            Logger.e(tag, "Page load error: ${error.errorCode} - ${error.description}")
        }
    }

    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        Logger.e(tag, "Render process gone! didCrash=${detail.didCrash()}")
        onCrash()
        return true
    }

    /**
     * Escape is not handed back to the platform, because handing it back is what
     * turns it into a back press.
     *
     * This callback is where an Escape the page did not consume ends up, and the
     * default implementation re-injects it: `WebViewClient.onUnhandledKeyEvent`
     * calls `onUnhandledInputEventInternal`, which calls
     * `ViewRootImpl.dispatchUnhandledInputEvent`, which queues the event with
     * `FLAG_UNHANDLED`. `deliverInputEvent` sends anything carrying that flag to
     * `mSyntheticInputStage` INSTEAD of the view tree, and that stage is the only
     * caller of `KeyCharacterMap.getFallbackAction` reached by an event carrying
     * that flag. It is not the only caller in the platform, and the other one is
     * the whole reason the Activity keeps an override of its own:
     * `PhoneWindowManager.dispatchUnhandledKey` calls it too, in the system
     * server, for a key the app reported unhandled. The two are answered
     * separately and neither substitutes for the other. Some
     * keyboards answer `ESCAPE` there with `fallback BACK` (AOSP ships one:
     * `Vendor_18d1_Product_5018.kcm`, the Pixel C keyboard, which reads
     * `base: fallback BACK` on a stock API 33 image where `Generic.kcm` reads
     * `base: none`), so the app minimises in the middle of a keystroke. Read off
     * android-33, android-35 and android-36.1 sources; the shape is the same in
     * all three.
     *
     * Returning without calling super is therefore the whole fix, and it has to
     * be here rather than in the Activity: the re-injected event never reaches
     * `Activity.dispatchKeyEvent`, because the synthetic stage is reached by
     * skipping the stage that would have delivered it. The Activity's own
     * override closes the other, disjoint route, where nothing in the view tree
     * consumed the key at all.
     *
     * Nothing is taken from the page. The platform documents this callback as
     * asynchronous and as firing only for keys the WebView did not consume, and
     * `WebViewClient` states that "Except system keys, WebView always consumes
     * the keys in the normal flow", so by the time it runs the workbench has
     * already had the key and either used it or declined it. What is given up is
     * the Alt and Ctrl fallbacks the same map defines on Escape, HOME and MENU:
     * in a terminal Alt+Esc is `ESC ESC`, a real keystroke, and answering it by
     * leaving for the home screen is the same defect wearing a modifier.
     *
     * Every other key is passed on unchanged, so no other fallback is affected.
     */
    override fun onUnhandledKeyEvent(view: WebView, event: KeyEvent) {
        if (event.keyCode == KeyEvent.KEYCODE_ESCAPE) return
        super.onUnhandledKeyEvent(view, event)
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
        /** VS Code assets are versioned by commit hash: safe to cache forever. */
        private const val CACHE_IMMUTABLE = "public, max-age=31536000, immutable"

        /**
         * The host suffix a webview resource URL carries, under which the label
         * encodes the scheme and authority of the resource being asked for.
         *
         * One constant rather than a local, because [isOurOrigin] has to refuse an
         * origin under it and the arm below has to recognise a request for it; two
         * spellings of the same authority is how the two would drift apart.
         */
        private const val RESOURCE_AUTHORITY = "vscode-resource.vscode-cdn.net"

        /**
         * The path prefix `assets/server.js` sets `nlsCoreBaseUrl` to.
         *
         * The server appends `<commit>/<version>/<locale>/nls.messages.js` to
         * whatever that key holds and puts the result in a script tag, so this
         * is one half of a contract with the other file; changing it there and
         * not here leaves the page asking for an address nothing answers, and
         * the interface silently stays English.
         *
         * A path on the app's own origin rather than a hostname, so a build
         * that loses this interception fails by 404 against the local server
         * rather than by resolving a name and opening a connection.
         */
        internal const val NLS_PATH_PREFIX = "_nls"

        private const val NLS_BUNDLE_FILE = "nls.messages.js"

        private const val NLS_ASSIGNMENT_PREFIX = "globalThis._VSCODE_NLS_MESSAGES="

        /**
         * The locale in an interface-bundle request, or null if this is not one.
         *
         * Shape: `/_nls/<commit>/<version>/<locale>/nls.messages.js`. Only the
         * ends are read. The two middle segments are the build's commit and
         * version, which the server puts there for cache separation and which
         * this app has no reason to check: they come from the same
         * `product.json` it just served the page from.
         */
        internal fun nlsBundleRequested(uri: Uri): String? {
            val segments = uri.pathSegments ?: return null
            if (segments.size < 3) return null
            if (segments.first() != NLS_PATH_PREFIX) return null
            if (segments.last() != NLS_BUNDLE_FILE) return null
            return segments[segments.size - 2].takeIf { it.isNotEmpty() }
        }

        /**
         * Register a ServiceWorkerClient to intercept service worker script fetches.
         *
         * Android WebView does not route service worker fetches through
         * `WebViewClient.shouldInterceptRequest`; they bypass it entirely, so a
         * page that registers a worker would fetch its script past every rule
         * the client applies.
         *
         * No page does, in this build. Patch 0005 sets `disableServiceWorker` to
         * a constant true in the webview bootstrap, so `workerReady` resolves
         * before it reaches `navigator.serviceWorker.register`, and nothing else
         * in the packaged tree registers one. Webview resources reach the page
         * through `shouldInterceptRequest` on this side instead: that is what
         * makes them load, and this registration is not.
         *
         * Kept anyway, and cheaply. It costs one call at bridge setup and it is
         * the thing that holds if patch 0005 is ever lost in a rebase or a future
         * page registers a worker of its own -- in which case the fetch would
         * otherwise be answered by no rules at all rather than by these.
         *
         * Must be called before the WebView loads any page, since a registration
         * that has already happened is not intercepted retroactively.
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
         * 1. main.vscode-cdn.net: Microsoft resources → empty JSON
         * 2. *.vscode-resource.vscode-cdn.net: extension webview resources → local file/proxy
         * 3. HASH.vscode-cdn.net: VS Code static assets → rewrite to localhost
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

            // The one query parameter this app has to read before it proxies
            // anything, and it is what stops a rendered page from minting an
            // origin the gate below trusts.
            //
            // The host label under `.vscode-cdn.net` is chosen by whoever names
            // the address, and every arm here answers for any label. The
            // workbench's own webview documents are loaded from
            // `https://{{uuid}}.vscode-cdn.net/{quality}/{commit}/out/vs/workbench/
            // contrib/webview/browser/pre/index.html?parentOrigin=...&origin=<salt>`
            // and that address is NOT configured: `branding/product.json` lists
            // `webviewContentExternalBaseUrlTemplate` under `remove`, and
            // `webviewExternalEndpoint` in the shipped `workbench.js` falls back to
            // that literal template when the product service carries no key, which
            // is what runs here. `_initElement` in the same bundle then sets
            // `parentOrigin` to the window's own origin. That page refuses to
            // start unless its hostname is the base-32 sha-256 of
            // `{parentOrigin, salt}` -- and the caller picks both, so a page can
            // compute a label for an origin of its own and be handed a running
            // webview host at an origin ending `.vscode-cdn.net`. From there the
            // resource arm below serves it any file under the published roots.
            //
            // Requiring `parentOrigin` to be the workbench itself closes that:
            // the page then posts its message port to `http://127.0.0.1:<port>`
            // as the target origin, which a frame at any other origin never
            // receives, so nothing can drive it. Absent is the ordinary case and
            // passes untouched -- every asset request carries no `parentOrigin`.
            //
            // `encodedQuery` and not `query`, which is the difference between a
            // gate and the appearance of one. `Uri.getQuery()` is decoded, the
            // same fact [rewriteCdnUrl] re-encodes around, so an address written
            // `?foo=a%26parentOrigin%3Dhttp%3A%2F%2F127.0.0.1%3A<port>` reaches
            // that accessor as `foo=a&parentOrigin=http://127.0.0.1:<port>` and
            // the split below reads a parameter nobody sent. A real second
            // `parentOrigin=` after it is then the one `index.html` uses, because
            // it parses the address it was actually loaded from with
            // `URLSearchParams`, and this app would have approved a value the page
            // never sent. Same escape this app already measured on the sign-in
            // callback, where `state=a%26b%3Dc` arrived as two parameters.
            val parentOrigin = queryParameter(uri.encodedQuery, "parentOrigin")
            if (parentOrigin != null && !isWorkbenchOrigin(parentOrigin, port)) {
                Logger.d(TAG, "CDN request refused, foreign parent origin: $parentOrigin")
                return notFound("Access denied")
            }

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
            if (host.endsWith(".$RESOURCE_AUTHORITY")) {
                // Composed here rather than by each caller, so that neither
                // entry point can forget to. The supplier is invoked only on
                // this branch: the other two answer without touching the
                // filesystem, and every workbench asset takes one of them.
                val headers = request.requestHeaders
                return interceptResourceRequest(
                    uri, host, port,
                    resourceRootsInForce(resourceRoots, sensitiveLocations, openFolder()),
                    headerValue(headers, "Origin"),
                    headerValue(headers, "Range"),
                    headerValue(headers, "Referer"),
                )
            }

            // Past the host test at the top of this function, null is no longer an
            // answer this arm may give. Null is the platform's instruction to load
            // the resource itself, and for a `*.vscode-cdn.net` address that means a
            // DNS lookup and a TLS connection to a host the interception exists to
            // keep this app away from: the CDN URLs are hardcoded in `workbench.js`
            // and cannot be reached through `product.json`, so this function is the
            // only thing standing between them and the network. Every exit below
            // therefore answers with a response, the way the resource arm above
            // does.
            val path = uri.path ?: return notFound("No path")
            val localUrl = rewriteCdnUrl(path, uri.query, port, token)

            if (localUrl == null) {
                // The whole URI, so its query comes with it, and the workbench
                // appends the token to requests of its own: hence [redactToken].
                //
                // At `Logger.d` and not `Logger.w`, for the reason the resource
                // arm's refusals moved: `rewriteCdnUrl` answers null for any
                // address with fewer than two path segments, so any frame the
                // editor renders can drive one of these per request by asking for
                // `https://x.vscode-cdn.net/a`, and every one of them writes a
                // string of that frame's choosing into a log `READ_LOGS` can read.
                Logger.d(TAG, "CDN URL could not be rewritten: ${redactToken(uri.toString())}")
                return notFound("CDN path too short to rewrite")
            }

            // The proxy answers null when the local server did not answer, which is
            // the ordinary case for the seconds around a server restart: the port is
            // ours and unbound, and the connection is refused. The asset is lost
            // either way, so 404 costs nothing that null did not, and null would
            // send the page out to the real CDN for it.
            return proxyToLocalhost(localUrl, request.method, "$host${uri.path}")
                ?: notFound("the local server did not answer")
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
            uri: Uri, host: String, port: Int,
            resourceRoots: List<String>, origin: String?, rangeHeader: String?,
            referer: String? = null,
        ): WebResourceResponse? {
            // The scheme half of the host label is the whole of what this reads
            // out of it. The authority half went with the two arms below that
            // used it, and the connection token went with them too: what is left
            // answers out of the filesystem and needs no credential.
            val scheme = host.removeSuffix(".$RESOURCE_AUTHORITY").substringBefore('+')
            val path = uri.path ?: return notFound("No path")

            if (scheme == "file" || scheme == "vscode-remote") {
                // The published roots decide WHICH files are served; this decides WHO
                // may read one, and until it existed the answer was anybody. A remote
                // page in the bundled Simple Browser could fetch a workspace file and
                // read the body, with no network involved. The response below now
                // names the origin it answers rather than `*`, so the decision is not
                // written twice with only one of the two enforcing it.
                //
                // Measured on an API 37 emulator rather than assumed, because the whole
                // gate rests on the header being there: of 42 intercepted requests, 29
                // carried `Origin` and it was always `http://127.0.0.1:<port>`, the
                // workbench's own. The `.vscode-cdn.net` arm is read from the shipped
                // bundle instead, where `webviewExternalEndpoint` resolves webview
                // documents to its own hardcoded
                // `https://{{uuid}}.vscode-cdn.net/...` because the key that would
                // override it is one `branding/product.json` removes.
                //
                // A missing header is served, deliberately. The other 13 were no-cors
                // subresource loads -- `<img>`, `<link>`, `<script src>` -- which send no
                // `Origin` and cannot read the body anyway, so demanding one would break
                // every extension webview to close nothing.
                //
                // What this gate does NOT close, written down because the
                // difference is easy to overstate. An `.html` file under a
                // published root, loaded as a document from this authority, runs
                // its script at an origin ending `.vscode-cdn.net`, and
                // [isOurOrigin] refusing that authority turns away the requests
                // that reach this gate at all: a CORS-mode request carries an
                // `Origin`, so such a document cannot ask a *different* one of our
                // origins for anything.
                //
                // It does not turn away the sibling it shares an origin with, and
                // the earlier wording here said it did. A same-origin `fetch`
                // sends no `Origin` header, so it never reaches this branch; it
                // falls to the `Referer` rule below, which accepts the resource
                // authority deliberately, since a stylesheet already served from
                // there is what asks for its own `url(...)` subresources. Every
                // published root answers on one authority, so "another file under
                // another root" is that same origin. A navigation carries no
                // origin either, so the same document can also frame another path
                // and read it out of `contentDocument`. That a
                // frame's own navigation reaches this callback is not a guess:
                // `pre/index.html` is exactly that, an iframe document the CDN arm
                // above answers. What it reaches is whatever [MIME_TYPES] renders
                // as a document -- html, txt, json, svg and the images -- under the
                // published roots, and only by naming the path, since nothing here
                // lists a directory.
                //
                // Closing it needs a test for what the request is FOR, and the only
                // candidate is a header nobody has measured this WebView sending
                // (`Sec-Fetch-Dest`), against a cost that is equally unmeasured: an
                // extension framing its own local `.html` is a shape this arm has
                // always served. Left open on purpose, and narrower than what it
                // replaced, where that same document could `fetch` every root.
                //
                // The path a page chooses is not repeated at a shipping level. The
                // frame asking is a page's to drive and the value is its to
                // compose, so a `Logger.w` here was an unbounded write into
                // logcat, once per request, on a channel `READ_LOGS` can read.
                // `Resource not found` below already made the same trade.
                if (origin != null && !isOurOrigin(origin, port)) {
                    Logger.d(TAG, "Resource refused, foreign origin: $origin")
                    return notFound("Access denied")
                }
                // And the requests that carry no `Origin` at all, which is the
                // half the gate above cannot see: `<img>`, `<link>` and a classic
                // `<script src>` are no-cors and send none. That half was left to
                // `Cross-Origin-Resource-Policy`, and MEASURED on an API 37
                // emulator (Chrome 149 WebView) the header does not close it: a
                // page served from `http://10.0.2.2:8877`, opened in the bundled
                // Simple Browser, loaded a workspace `.js` through `<script src>`
                // and a workspace `.png` through `<img>` while the response
                // carried `same-site`. The same WebView DOES enforce the header on
                // an ordinary network response (a no-cors fetch of one carrying
                // `same-origin` throws), so what fails is the check being applied
                // to a response synthesised by `shouldInterceptRequest` at all.
                // The header stays, because it is correct and costs nothing the
                // day that changes; it is simply not what closes this.
                //
                // `Referer` is, because it is the one signal that reaches here and
                // names the document that asked. Measured at this gate, over the
                // first run, the walkthrough, the markdown preview and the Simple
                // Browser: every legitimate no-cors load carried
                // `https://<uuid>.vscode-cdn.net/`, and the attack carried
                // `http://10.0.2.2:8877/`.
                //
                // Absent counts as foreign, and that is not caution: a page sets
                // `<meta name="referrer" content="no-referrer">` and the request
                // arrives here with no `Referer` at all, measured, still reading
                // the workspace. A rule that fell open on absent would be one
                // attribute away from useless. The cost is a legitimate no-cors
                // load that suppresses its own referrer, which nothing bundled
                // does; a CORS-mode load (module scripts, fonts) is unaffected
                // either way, since it carries an `Origin` and is answered above.
                if (origin == null && !isOurReferer(referer, port)) {
                    Logger.d(TAG, "Resource refused, foreign referer: $referer")
                    return notFound("Access denied")
                }
                // Local file resource: serve directly from filesystem.
                // Both "file" and "vscode-remote" schemes use local paths in VSCodroid
                // since the server runs on the same device.
                // Through resourceOutcome so the decision is a value rather than a
                // log line. Refusing and serving both end in a WebResourceResponse,
                // which a test cannot inspect under the stub android.jar, so
                // "log the refusal and serve anyway" was indistinguishable from a
                // refusal to everything that watched this function.
                val file = when (val outcome = resourceOutcome(path, resourceRoots)) {
                    ResourceOutcome.Refused -> {
                        Logger.d(TAG, "Resource outside the published roots refused: $path")
                        return notFound("Access denied")
                    }
                    ResourceOutcome.Missing -> {
                        Logger.d(TAG, "Resource not found ($scheme): $path")
                        return notFound(path)
                    }
                    is ResourceOutcome.Serve -> outcome.file
                }

                // Opened before the response is built, and inside a try, because
                // the check above and the open are two syscalls with a gap between
                // them and nothing upstream catches. `resourceOutcome` answers
                // Serve for a file that exists and is a regular file, which is not
                // the same question as whether it opens: a watch build or a
                // `git checkout` can unlink it in that gap, and a file with no read
                // permission fails every time without any race at all. The throw
                // travelled out of `shouldInterceptRequest` into the WebView's own
                // callback, where `CrashReporter` recorded the death rather than
                // preventing it, so a workspace being rewritten under a preview
                // took the editor down with it. IOException and not Exception:
                // FileNotFoundException is the whole of what an open reports, and
                // widening it here would swallow failures that are not this one.
                // At `Logger.d`, the same trade as the four refusals above and for
                // the same reason: both interpolated values are read out of the
                // host label and the path the page composed. Weaker than those,
                // because it needs a file that exists and still will not open --
                // one left at mode 000 in the workspace is the ordinary way to get
                // one -- but given one, a frame can ask for it in a loop and every
                // request wrote an absolute path into a log `READ_LOGS` can read,
                // on a level that is not gated on a debuggable build.
                val stream = try {
                    FileInputStream(file)
                } catch (e: IOException) {
                    Logger.d(
                        TAG,
                        "Resource could not be opened ($scheme): $path (${e.javaClass.simpleName})"
                    )
                    return notFound("Unreadable: $path")
                }

                val mimeType = guessMimeType(path)
                Logger.d(TAG, "Resource served ($scheme): $path ($mimeType)")

                // No Cache-Control on this arm, deliberately. The commit hash that
                // makes an asset safe to keep forever belongs to the URL the *other*
                // arm proxies, `/{quality}-{commit}/static/...`; what this one serves
                // is a plain filesystem path resolved against roots that include the
                // open workspace, the projects tree and the SAF mirrors, all of which
                // the user rewrites in place. `immutable` forbids revalidation, so an
                // image regenerated from the terminal or pulled with `git pull` went
                // on rendering from the old bytes in the markdown preview for the life
                // of the renderer, and nothing in the app could force a refetch: that
                // extension emits query-less resource URLs, unlike `media-preview`,
                // which appends its own `version=`. The server tree and the extensions
                // directory do not change within an install, so all they lose is a
                // warm-start saving on a local file read.
                //
                // The length is read off the descriptor that was opened rather
                // than off the path, and that is the same gap the `try` above
                // covers: `file.length()` re-stats the name, so a watch build or
                // a `git checkout` rewriting the file between the open and the
                // header declared a size the stream does not deliver, which the
                // WebView reports as a truncated subresource rather than as the
                // refetch this arm's cache decision was written to allow.
                val length = try {
                    stream.channel.size()
                } catch (e: IOException) {
                    Logger.d(TAG, "Resource length unavailable ($scheme): $path")
                    stream.close()
                    return notFound("Unreadable: $path")
                }

                // Answered to whoever asked, never `*`. The gate above decides who
                // may read one of these files; sending `*` meant the answer was
                // also written on the response itself, so any later widening of
                // the gate silently widened the read as well. A request that
                // carried no `Origin` gets no header at all: those are the no-cors
                // subresource loads -- `<img>`, `<link>`, `<script src>` -- whose
                // responses are opaque to the page whatever this says.
                //
                // ⚠️ Narrowing the echo further, to the workbench origin alone, is
                // the obvious next move and it breaks the bundled markdown
                // preview. Measured in the packaged tree:
                // `extensions/markdown-language-features/markdown-editor-out/editor.js`
                // is an ES module importing hashed chunks beside it, and the
                // `editor.css` next to it carries `@font-face` rules for the KaTeX
                // faces and a codicon `.ttf` in the same directory. A module script
                // and a font are CORS-mode requests whatever their origin, so both
                // arrive here carrying the webview's `https://<uuid>.vscode-cdn.net`
                // and both fail closed without a matching header: math stops
                // rendering and the icons become boxes.
                //
                // So the reader this arm answers is as narrow as it can be made
                // from here, and what stays open stays open: webview content can
                // read any file under the published roots, because every webview in
                // this build shares one origin shape and nothing in a resource
                // request says which webview asked. The host label encodes a scheme
                // and an authority and no more. Upstream scopes each webview to its
                // own `localResourceRoots` inside the service worker, which patch
                // 0005 disables, so restoring that scoping is a change to the
                // request shape and to the patch, not to this map.
                //
                // The header the echo cannot carry is the one for the request
                // that sends no `Origin`. A no-cors load is opaque to the page
                // that made it, and opaque is not inert: a `.js` under the open
                // workspace goes out as `application/javascript`, so a remote
                // page in the bundled Simple Browser can name it in
                // `<script src>` and run it in its own realm, where every global
                // it defines is that page's to read; and `<img onerror>` against
                // a guessed path tells the same page which files exist, a miss
                // being a 404 and a hit a 200. `Cross-Origin-Resource-Policy` is
                // the one check a no-cors response is meant to be put through, and
                // measured on this WebView it is not applied to a response
                // synthesised here at all -- see the referer gate above, which is
                // what actually refuses that page. It comes from
                // [resourceHeaders], whose comment says why `same-site` and not
                // `same-origin`, and why no `nosniff` beside it.
                val headers = resourceHeaders(origin)

                // Ranges are answered rather than refused, and media is why. The
                // Chromium media loader asks for one as soon as the user drags a
                // scrubber, and a response that always says 200 with the whole
                // body leaves seeking limited to what has already been buffered:
                // `media-preview` is bundled, so an ordinary `.mp4` in the
                // workspace reaches this. A malformed or unsatisfiable range falls
                // back to the whole file, which is what the standard permits and
                // what keeps a bad header from turning into a failed load.
                //
                // ⚠️ Whether the WebView puts `Range` into `getRequestHeaders()`
                // at all is not measured here. If it does not, [byteRangeOf] sees
                // null and this answers exactly what it answered before, plus an
                // `Accept-Ranges` a client that gets a 200 for its range simply
                // reads as a server that chose not to honour it.
                val range = byteRangeOf(rangeHeader, length)
                if (range != null) {
                    val body = try {
                        stream.channel.position(range.first)
                        BoundedInputStream(stream, range.last - range.first + 1)
                    } catch (e: IOException) {
                        stream.close()
                        Logger.d(TAG, "Resource could not be seeked ($scheme): $path")
                        return notFound("Unreadable: $path")
                    }
                    return WebResourceResponse(
                        mimeType, null, 206, "Partial Content",
                        headers + mapOf(
                            "Accept-Ranges" to "bytes",
                            "Content-Range" to "bytes ${range.first}-${range.last}/$length",
                            "Content-Length" to (range.last - range.first + 1).toString(),
                        ),
                        body
                    )
                }

                return WebResourceResponse(
                    mimeType, null, 200, "OK",
                    headers + mapOf(
                        "Accept-Ranges" to "bytes",
                        "Content-Length" to length.toString(),
                    ),
                    stream
                )
            }

            // An `http`/`https` arm sat here and forwarded to `$scheme://$authority$path`,
            // whatever host the page named. It went out correctly without the connection
            // token, so it did not leak a credential, but it did lend this app's network
            // identity to any page the editor renders: a way to reach a LAN address or a
            // loopback service the page could not reach itself.
            //
            // Removed rather than restricted, for the same reason as the branch below and
            // on the same evidence. Nothing produces the form: `pre()` in the shipped
            // workbench returns an http or https URI unchanged rather than encoding it
            // into the resource host, and no bundled extension contains one. Both were
            // grepped, each against a control proving the search was live.
            //
            // Remote content in a webview still loads normally. It does so through the
            // WebView's own navigation, which is where reaching anything already lives;
            // this arm was a second route into the same place with no restriction on it.

            // A catch-all for every other scheme with an authority used to sit here,
            // proxying to `http://127.0.0.1:$port$path` with the connection token
            // appended. It is gone rather than tightened, and restoring it needs a
            // reason better than the one it had.
            //
            // Both halves of `path` and the query were the page's to choose, and the
            // token turned that into an authenticated request. The server routes
            // `/vscode-remote-resource` after its token gate and then reads
            // `query.path` as an absolute path, so this branch answered
            // `…/vscode-remote-resource?path=<anything>` with the file: the SSH key
            // and the token file included, which is what `sensitiveLocations` exists
            // to keep out of the branch above. The response is same-origin to a
            // document the caller can frame, and the server sends no
            // X-Frame-Options, so reading it back needed no further trick.
            //
            // Nothing asked for it. The workbench encodes only `file` and
            // `vscode-remote` into the resource host, and no bundled extension
            // produces the form. The `Unknown resource scheme` line below is the
            // signal if something did: it names the scheme.
            //
            // At `Logger.d`, which is the same trade the refusals above make and
            // costs the same thing: the signal is there on a debuggable build and
            // absent on a user's. Both values in it are read out of a hostname the
            // page composed, so at a shipping level it was an unbounded write into
            // logcat, once per request, on a channel `READ_LOGS` can read.
            Logger.d(TAG, "Unknown resource scheme: $scheme (host=$host)")
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

                // `url` has been through withToken() when it names our own server.
                // One caller does now, the CDN rewrite; the unknown-scheme fallback
                // that was the second is gone.
                Logger.d(TAG, "CDN redirect: $logTag -> ${redactToken(url)} ($responseCode)")

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

                // A fallback for a static asset the local server answers without
                // a Cache-Control of its own. The route this arm proxies,
                // `/{quality}-{commit}/static/...`, does send one, so the branch
                // is expected to stay quiet; it stopped being able to fire at all
                // once the token joined the URL, which is what [assetPathOf]
                // exists to undo.
                if (responseCode in 200..299 && isStaticAsset(url) &&
                    !headers.containsKey("Cache-Control")
                ) {
                    headers["Cache-Control"] = CACHE_IMMUTABLE
                }

                // The second argument is the CHARACTER encoding, and it used to be
                // handed `conn.contentEncoding`, which is the `Content-Encoding`
                // header: a content coding, never a charset. Android's
                // HttpURLConnection strips that header when it gunzips
                // transparently, so the value was usually null and the fallback hid
                // it -- but any coding the client did not ask for transparently
                // would have arrived at the WebView as a charset name, while the
                // charset the server did send was being thrown away one line above
                // by the cut at `;`. Both halves are the same mistake, and this
                // takes the charset out of the one header that carries it.
                //
                // A blank reason phrase and a 3xx are both refused by the
                // `WebResourceResponse` constructor with IllegalArgumentException,
                // which the catch below would report as `Proxy failed` -- the same
                // line a connection failure produces, so an unusual status was
                // indistinguishable from the server not being there. The phrase is
                // defaulted, and a redirect is answered on its own terms: the
                // client already follows the ones it can, so what reaches here is
                // one it would not, and the caller turns null into a 404.
                if (responseCode in 300..399) {
                    Logger.d(TAG, "Proxy will not follow a redirect for $logTag ($responseCode)")
                    wrappedStream.close()
                    return null
                }
                WebResourceResponse(
                    contentType.substringBefore(";").trim(),
                    charsetOf(contentType),
                    responseCode,
                    conn.responseMessage?.ifBlank { null } ?: "OK",
                    headers,
                    wrappedStream
                )
            } catch (e: Exception) {
                connection?.disconnect()
                // `logTag` never carries the token: all three callers build it
                // from a host or authority and a path, and none of them includes
                // a query string, which is the only place the token ever sits.
                // `e.message` is the reason this is
                // redacted anyway: a connection failure names the socket and not
                // the URL, which is measured rather than assumed, but
                // `MalformedURLException` from `URL(url)` above reports the spec it
                // was handed, and that spec has been through withToken().
                Logger.w(TAG, "Proxy failed for $logTag: ${redactToken(e.message)}")
                null
            }
        }

        /**
         * Whether [origin] is the page this app serves, and nothing else.
         *
         * The workbench is the only document loaded from our own server, so the
         * two loopback spellings are the whole of it. Kept apart from
         * [isOurOrigin], which also has to accept a webview document: this is the
         * test for a claim that must not be satisfiable by anything the workbench
         * merely renders.
         */
        internal fun isWorkbenchOrigin(origin: String, port: Int): Boolean =
            origin == "http://127.0.0.1:$port" || origin == "http://localhost:$port"

        /**
         * Whether [origin] is the workbench itself or one of its webview documents.
         *
         * The resource authority is excluded, and that exclusion is the gate on a
         * real escalation rather than tidiness. `guessMimeType` answers `text/html`
         * for any `.html` under a published root, and a published root is the open
         * workspace, the projects tree or a SAF mirror -- a checked-out repository,
         * routinely. A navigation carries no `Origin` header, so a frame pointed at
         * `https://file+.vscode-resource.vscode-cdn.net/<path to that file>` is
         * served it as a document and runs its script at that origin. While this
         * accepted it, that script could then read every other file under every
         * published root through this same arm. Nothing legitimate ever asks from
         * there: webview documents come from the `{{uuid}}.vscode-cdn.net` template,
         * and the resource authority only ever answers subresources.
         *
         * It closes the asking that reaches this predicate, which is the asking
         * that carries an `Origin`: a CORS-mode request to a different one of our
         * origins. The caller's comment records what it does not close, and it is
         * wider than framing. A same-origin `fetch` sends no `Origin`, so it is
         * judged by [isOurReferer], which accepts this authority on purpose; and a
         * navigation carries none either, so a document served here can frame
         * another path on its own origin and read that one back.
         */
        internal fun isOurOrigin(origin: String, port: Int): Boolean =
            isWorkbenchOrigin(origin, port) ||
                (origin.startsWith("https://") && origin.endsWith(".vscode-cdn.net") &&
                    !origin.endsWith(".$RESOURCE_AUTHORITY"))

        /**
         * Whether [referer] names a document of ours, for the no-cors requests
         * that carry no `Origin` for [isOurOrigin] to judge.
         *
         * Null is false, deliberately and load-bearingly: a page suppresses its
         * own referrer with one `<meta name="referrer" content="no-referrer">`,
         * and measured on device the request then reaches the gate with no
         * `Referer` and read the workspace anyway. Falling open on absent would
         * make the rule an attribute away from useless.
         *
         * The resource authority is accepted here and refused by [isOurOrigin],
         * and the asymmetry is deliberate. There the value is an `Origin` on a
         * request a document at that authority *made*, which is the escalation
         * that comment describes. Here it is the address of a stylesheet or a
         * document already served from it, whose own `url(...)` subresources
         * carry it as their referrer. Accepting it widens nothing a foreign page
         * can reach: a page cannot set its referrer to another origin, only trim
         * or drop it, and a dropped one is refused above.
         *
         * The origin is taken by cutting at the third `/`, which is all an
         * absolute URL's origin ever is, rather than by parsing: a `Referer` is
         * always an absolute URL with no userinfo (the spec strips it), and a
         * value that is neither is not one of ours whatever it is.
         */
        internal fun isOurReferer(referer: String?, port: Int): Boolean {
            val value = referer ?: return false
            val scheme = value.substringBefore("://", "")
            if (scheme != "http" && scheme != "https") return false
            val origin = "$scheme://" + value.substringAfter("://").substringBefore('/')
            return isWorkbenchOrigin(origin, port) ||
                (origin.startsWith("https://") && origin.endsWith(".vscode-cdn.net"))
        }

        /**
         * The headers that say who may use a file served off the filesystem, for
         * a request from [origin] or from none.
         *
         * A value rather than a map built inline, because a `WebResourceResponse`
         * cannot be constructed under the stub `android.jar` and its header map
         * can be observed nowhere: this is the one thing about that response a
         * test can read, and `ResourceHeadersTest` reads it.
         *
         * `Access-Control-Allow-Origin` echoes the asker, and only when there is
         * one; the caller's comment records why `*` was wrong and why the echo
         * cannot be narrowed to the workbench alone. `Vary: Origin` goes with
         * it, because two origins may ask for one path and a cache keyed on the
         * URL would hand the second asker the first one's header.
         *
         * `Cross-Origin-Resource-Policy: same-site`, which this WebView does not
         * enforce on a response `shouldInterceptRequest` synthesises: measured, a
         * foreign page still read a workspace file through `<script src>` while
         * this header was on the response, and the same WebView refused an
         * ordinary network response carrying `same-origin`. What refuses that page
         * is the referer gate in [interceptResourceRequest]. The header stays
         * because it is correct, costs nothing, and is what the platform will
         * honour the day it applies the check here.
         *
         * The difference from `same-origin` is every extension webview. A webview document lives at
         * `https://<uuid>.vscode-cdn.net`, its content frame is `fake.html` on
         * that same origin under `allow-same-origin`, and its `<img>`, `<link>`,
         * classic `<script src>` and `<video>` all name this authority: another
         * origin on the same site, because `vscode-cdn.net` is on no public
         * suffix list (checked against the public suffix data the npm `psl`
         * package ships, which carries Microsoft's other entries such as
         * `azurewebsites.net` and `cloudapp.net` as a positive control and no
         * rule for `vscode-cdn.net`). `same-origin` would refuse all of them and
         * every webview would render without its stylesheet. Module scripts and
         * fonts are CORS-mode whatever their origin and are judged by the echo
         * instead: the policy reaches only a request that sent no `Origin`,
         * which is the one the gate serves on purpose. The workbench,
         * `http://127.0.0.1:<port>`, is cross-site to this authority, and
         * nothing read from the bundle loads a no-cors subresource from it
         * there: `vscode-resource` addresses are minted for webview content,
         * and the workbench reaches extension files through its own server.
         * That last point is read, not measured, so an extension that did it
         * would show up as a webview asset that stopped loading.
         *
         * No `X-Content-Type-Options: nosniff`, on purpose. The policy above is
         * what refuses the remote page; nosniff would add nothing against it
         * and would cost every file whose extension [MIME_TYPES] does not know:
         * such a file goes out as `application/octet-stream`, and the WebView's
         * opaque-response blocking refuses a nosniff response of that type for
         * an `<img>` or `<audio>` it cannot sniff, where without the header it
         * sniffs and renders. The bundled media preview loads `.bmp`, `.avif`
         * and `.oga` that way.
         */
        internal fun resourceHeaders(origin: String?): Map<String, String> {
            val policy = mapOf("Cross-Origin-Resource-Policy" to "same-site")
            return if (origin == null) policy else mapOf(
                "Access-Control-Allow-Origin" to origin,
                "Vary" to "Origin",
            ) + policy
        }

        /**
         * The value of the [name] request header, whatever case it arrived in.
         *
         * HTTP header names are case-insensitive and the WebView does not promise
         * a spelling, so the lookup is too. One helper rather than a lambda at
         * each call site: the origin gate and the range answer both read one, and
         * a gate that missed its header because of a capital letter fails open.
         */
        internal fun headerValue(headers: Map<String, String>?, name: String): String? =
            headers?.entries?.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

        /**
         * The single byte range [header] asks for out of a [length]-byte file, or
         * null when the whole file is the right answer.
         *
         * Null covers every case a 200 still satisfies: no header, a unit other
         * than bytes, a multi-range request (which would need a multipart body for
         * a gain nothing here asks for), a range that does not parse, and one that
         * starts past the end. RFC 9110 lets a server ignore a Range header it
         * does not want to honour, so falling back to the whole file is a legal
         * answer everywhere and keeps a malformed header from turning into a
         * failed load. A zero-length file is one of those cases: there is no
         * satisfiable range in it at all.
         *
         * Both forms are answered: `bytes=start-end` and `bytes=start-`, plus the
         * suffix form `bytes=-n`, which the media loader uses to read a trailing
         * index. The end is clamped to the last byte, because a player routinely
         * asks for more than the file holds.
         */
        internal fun byteRangeOf(header: String?, length: Long): LongRange? {
            val trimmed = header?.trim() ?: return null
            if (!trimmed.startsWith("bytes=", ignoreCase = true)) return null
            if (length <= 0) return null
            val spec = trimmed.substring("bytes=".length).trim()
            if (spec.contains(',')) return null
            // A spec with no dash at all is the same malformed header as an
            // unreadable end, and it used to read as an open range for the same
            // reason: `substringBefore('-')` answers the whole string when the
            // delimiter is missing and `substringAfter('-', "")` answers the
            // default, so `bytes=100` against a 1000-byte file produced a 206 for
            // 100..999. RFC 9110 says to ignore a Range that does not parse.
            if ('-' !in spec) return null
            val first = spec.substringBefore('-').trim()
            val last = spec.substringAfter('-', "").trim()
            val start: Long
            val end: Long
            if (first.isEmpty()) {
                val suffix = last.toLongOrNull() ?: return null
                if (suffix <= 0) return null
                start = maxOf(0L, length - suffix)
                end = length - 1
            } else {
                start = first.toLongOrNull() ?: return null
                if (start >= length) return null
                // An end that is present and unreadable is the whole header being
                // malformed, not an open range: `bytes=100-19x` used to answer
                // 100..last, which is a 206 for something nobody asked for. RFC
                // 9110 says to ignore a Range that does not parse, and null here
                // is exactly that.
                end = (if (last.isEmpty()) length - 1 else last.toLongOrNull() ?: return null)
                    .coerceAtMost(length - 1)
            }
            return if (end < start) null else start..end
        }

        /**
         * The value of [name] in a still-encoded query string, decoded, or null.
         *
         * [query] is `Uri.getEncodedQuery()`, and it has to be. The decoded
         * spelling folds a `%26` inside a value into a real `&`, which is a
         * separator here, so a page could write one parameter that this reads as
         * two. Splitting first and decoding after is what makes the boundaries the
         * ones the page actually sent.
         *
         * Read out of the query string rather than through `Uri.getQueryParameter`
         * for the reason [tlsHostLabel] gives about `android.net.Uri`: it is a stub
         * under the unit-test `android.jar`, so a case written against it would
         * measure the mock rather than the parse.
         *
         * The name is decoded before it is compared, and not only the value. This
         * has to agree with the `URLSearchParams` in
         * `contrib/webview/browser/pre/index.html`, which decodes both, so a page
         * writing `parentOri%67in=` would otherwise hide a parameter from this
         * while handing that page the value. `+` becoming a space falls out of the
         * same agreement: `URLDecoder` and `URLSearchParams` both read the query
         * as form encoding.
         *
         * A part that will not decode is answered as it arrived rather than
         * dropped. This feeds a refusal, and a malformed value has to reach the
         * comparison and fail it rather than vanish into "no parameter present".
         */
        internal fun queryParameter(query: String?, name: String): String? =
            query?.split("&")
                ?.map { it.split("=", limit = 2) }
                ?.firstOrNull { decodeQueryPart(it[0]) == name }
                ?.let { if (it.size > 1) decodeQueryPart(it[1]) else "" }

        private fun decodeQueryPart(part: String): String =
            try {
                URLDecoder.decode(part, "UTF-8")
            } catch (e: IllegalArgumentException) {
                part
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
         *
         * [path] is `Uri.getPath()`, which is DECODED, and every part of it is the
         * page's: any rendered document may name a `*.vscode-cdn.net` host, which is
         * the only test the caller applies. Built by concatenation, `%3F` arrived
         * here as a real `?` and `%2E%2E%2F` as a real `../`, so both became URL
         * syntax rather than text, and [withToken] then signed the result with the
         * server's credential. That is the hazard the two removed arms in
         * [interceptResourceRequest] were deleted for, and it reached the same
         * place: `/vscode-remote-resource?path=<absolute>` sits behind the token
         * gate and then reads any file the server can read, the passphrase-less SSH
         * key included.
         *
         * So the URL is assembled through `java.net.URI`, whose multi-argument
         * constructor quotes everything in a path component that would otherwise be
         * a delimiter: `?`, `#`, a bare `%`, CR and LF, and anything non-ASCII.
         * `java.net.URI` rather than `android.net.Uri` for the reason [tlsHostLabel]
         * gives: the second is a stub under the unit-test `android.jar`, so a case
         * written against it would measure the mock and not the encoding. It also
         * fixes a smaller thing the concatenation got wrong, an ordinary space in a
         * resource name arriving unencoded in a URL string.
         *
         * Dot segments are refused rather than quoted, because they are legal path
         * syntax and `URI` keeps them: `..` inside [path] walks out of
         * `/{quality}-{commit}/static/` onto any route of our own server. What
         * resolves them is the HTTP client, and on Android that is OkHttp behind
         * `HttpURLConnection`, which canonicalises the path as it writes the request
         * line: standard behaviour, not measured on a device here, and neither the
         * desktop JVM the unit suite runs on nor the server's own `url.parse` does
         * it. Refusing is what keeps the app from issuing a request whose route
         * depends on which end resolves it. `quality` and `commit` need no such
         * test: they are joined by a hyphen, so the segment they produce can never
         * be `.` or `..`.
         *
         * Nothing the workbench asks for carries one, so refusing costs nothing, and
         * the caller answers a null here with a 404 of its own rather than handing
         * the address back to the WebView to fetch from the real CDN.
         *
         * ⚠️ [query] is `Uri.getQuery()`, the DECODED spelling, and it does not get
         * the treatment the path gets. `&` and `=` are legal inside a query
         * component, so the constructor passes them through rather than quoting
         * them, and a page writing `?a=b%26c=d` therefore issues a request the
         * server reads as two parameters where the page wrote one. Reasoned from
         * the constructor's contract, not measured.
         *
         * What keeps that harmless is the target rather than the encoding: this
         * URL names our own `/{quality}-{commit}/static/` route, which reads
         * nothing out of its query, and the one parameter that decides anything
         * there is the credential [withToken] appends afterwards, which a page
         * cannot know. A `tkn=` a page folds in of its own can only make its own
         * fetch fail.
         *
         * So the constraint worth keeping is on the target: aim this at a route
         * that reads its query and the paragraph above stops being true. Passing
         * `getEncodedQuery()` is not the fix it looks like, because the
         * constructor would then quote the `%` in every legitimate escape and
         * double-encode the workbench's own asset queries. Building the query
         * outside the constructor is what that would take, and that hands `#`, CR
         * and LF straight through to `HttpURLConnection`.
         */
        private fun rewriteCdnUrl(path: String, query: String?, port: Int, token: String?): String? {
            val segments = path.removePrefix("/").split("/", limit = 3)
            if (segments.size < 2) return null

            val quality = segments[0]   // "stable" or "insider"
            val commit = segments[1]    // commit hash
            val rest = if (segments.size > 2) segments[2] else ""

            if (rest.split("/").any { it == "." || it == ".." }) return null

            val localPath = "/$quality-$commit/static/$rest"
            // Empty and absent are one case here, as they were for the `?` this
            // replaces: `URI` writes a bare `?` for an empty query, which [withToken]
            // would then read as a query to extend.
            val localUrl = try {
                URI(
                    "http", null, "127.0.0.1", port, localPath, query?.ifEmpty { null }, null
                ).toASCIIString()
            } catch (e: URISyntaxException) {
                return null
            }
            return withToken(localUrl, token)
        }

        /**
         * Appends the connection token to a URL that points at our own server.
         *
         * These requests need it carried explicitly. The workbench authenticates
         * itself with the `vscode-tkn` cookie, but everything here is re-fetched
         * through HttpURLConnection, which has its own cookie store and shares
         * nothing with the WebView, so without the query parameter the server
         * answers 403 and the asset silently fails to load.
         *
         * Deliberately not folded into proxyToLocalhost(), despite the name of
         * that function, and the reason is now a historical one rather than a
         * present hazard. `proxyToLocalhost` has exactly one caller today, the
         * CDN rewrite, which always names 127.0.0.1 and always comes through
         * here. It used to have three: one proxied http/https resources to
         * whatever host an extension's webview referenced, where the token would
         * have been handed to that host, and one appended the token to a
         * page-controlled path against our own server. Both are gone.
         *
         * ⚠️ Do not read "one caller, always local" as licence to fold the token
         * into `proxyToLocalhost`. The separation is what made both of those
         * callers visible as defects, and a token applied inside the proxy is
         * applied to whatever a future caller passes, silently. Keeping it an
         * explicit step at the call site is the property worth having, not the
         * current caller count.
         */
        private fun withToken(url: String, token: String?): String {
            if (token.isNullOrEmpty()) return url
            val separator = if (url.contains('?')) "&" else "?"
            return "$url$separator" + "tkn=" + Uri.encode(token)
        }

        /**
         * The part of [pathOrUrl] the two suffix tests below can answer for.
         *
         * They are asked both ways: the resource arm has a bare `uri.path`,
         * while the proxy has the whole rewritten URL. Since the editor server
         * began requiring a connection token, every one of those URLs ends in
         * `tkn=<hex>`, so a suffix test on the raw string matched no asset at
         * all and both fallbacks reading it, the immutable cache header and the
         * MIME type for a response that carries none of its own, had quietly
         * stopped being reachable.
         */
        private fun assetPathOf(pathOrUrl: String): String = pathOrUrl.substringBefore('?')

        /**
         * What each file extension this app serves is, by MIME type.
         *
         * One table rather than two lists, because the two lists disagreed: `.jpg`
         * was a static asset and had no MIME type, so a proxied JPEG was answered
         * `application/octet-stream`.
         *
         * The additions past the original eight are not cosmetic. Chromium sniffs
         * images, so those were rendering anyway, but a module script and
         * `WebAssembly.instantiateStreaming` both refuse a response whose type is
         * not right: an extension webview doing `import('./x.mjs')` or
         * `instantiateStreaming(fetch('./x.wasm'))` through a `vscode-resource` URL
         * failed on the type alone. No bundled extension does either today (the
         * `.mjs` and `.wasm` files in the server tree are all node-side), so the
         * population is what a user installs from Open VSX. Media is here for the
         * bundled `media-preview`, which is also why the arm serving these answers
         * range requests.
         */
        private val MIME_TYPES = mapOf(
            "html" to "text/html",
            "js" to "application/javascript",
            "mjs" to "text/javascript",
            "cjs" to "text/javascript",
            "css" to "text/css",
            "json" to "application/json",
            "map" to "application/json",
            "wasm" to "application/wasm",
            "txt" to "text/plain",
            "svg" to "image/svg+xml",
            "png" to "image/png",
            "jpg" to "image/jpeg",
            "jpeg" to "image/jpeg",
            "jpe" to "image/jpeg",
            "gif" to "image/gif",
            "webp" to "image/webp",
            "bmp" to "image/bmp",
            "avif" to "image/avif",
            "ico" to "image/x-icon",
            "mp4" to "video/mp4",
            "webm" to "video/webm",
            "mp3" to "audio/mpeg",
            "wav" to "audio/wav",
            "ogg" to "audio/ogg",
            "oga" to "audio/ogg",
            "woff2" to "font/woff2",
            "woff" to "font/woff",
            "ttf" to "font/ttf",
        )

        /**
         * The extensions that may carry an immutable cache header.
         *
         * Derived from [MIME_TYPES] rather than written out again, which is what
         * keeps the two from disagreeing a second time. Everything the proxied
         * route serves is versioned by the commit hash in its own URL, so the only
         * exclusions are the two shapes that name a document rather than an asset:
         * an HTML entry point and a JSON payload, either of which the server may
         * answer differently within one install.
         */
        private val CACHEABLE_ASSETS = MIME_TYPES.keys - setOf("html", "json")

        /** The lowercase extension of [pathOrUrl], with its query taken off. */
        private fun extensionOf(pathOrUrl: String): String =
            assetPathOf(pathOrUrl).substringAfterLast('.', "").lowercase()

        internal fun isStaticAsset(pathOrUrl: String): Boolean =
            extensionOf(pathOrUrl) in CACHEABLE_ASSETS

        internal fun guessMimeType(pathOrUrl: String): String =
            MIME_TYPES[extensionOf(pathOrUrl)] ?: "application/octet-stream"

        /**
         * The character set named by a `Content-Type`, or utf-8 when it names none.
         *
         * Quotes are stripped because the parameter is allowed to carry them
         * (`charset="utf-8"`), and a quoted name handed to the WebView as a charset
         * is not one it can look up.
         */
        internal fun charsetOf(contentType: String?): String =
            contentType?.split(';')
                ?.map { it.trim() }
                ?.firstOrNull { it.startsWith("charset=", ignoreCase = true) }
                ?.substringAfter('=')
                ?.trim('"', ' ')
                ?.takeIf { it.isNotEmpty() }
                ?: "utf-8"
    }
}
