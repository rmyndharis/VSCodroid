package com.vscodroid.webview

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.SystemClock
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
import com.vscodroid.util.Environment
import com.vscodroid.util.Logger
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URISyntaxException
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
 * What the interceptor decided about one webview resource request.
 *
 * Separated from building the response because a `WebResourceResponse` cannot be
 * constructed under the stub `android.jar`, so a test has to mock its constructor
 * and can then observe nothing about it. That left refusal provable only by the
 * warning the code logs — and a log line is not a refusal. Code that logged the
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
 * callbacks are handed. Four statements printed it, and two of them —
 * `Page loaded` at `Logger.i` and the un-rewritable-URL warning at `Logger.w` —
 * are not gated on a debuggable build, so they shipped in release.
 *
 * Keyed on the parameter rather than on the token's value, because most of the
 * call sites below are in a companion object that never receives it. Two
 * ceilings follow from that and are worth naming, since a comment claiming
 * containment is worth nothing unless it also says where the containment stops:
 * a statement printing the bare token, in no `tkn=` at all, passes straight
 * through, and so does one that has been encoded again — `tkn%3D<value>` nested
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
 * Past [MAX_TLS_FAILURES_ANNOUNCED] distinct failures nothing more is said. The
 * record is what counts them, so the same test bounds the toasts and the set at
 * once. Eight refusals is already more than a reader acts on, and the ninth is the
 * point at which the page, not the user, is choosing how long the editor stays
 * covered.
 *
 * [alreadySaid] belongs to the presenter, which keeps this class free of mutable
 * state, and its lifetime falls out of that: it survives a renderer crash, because
 * the certificate has not changed, and it dies with the Activity, so a fresh
 * launch says everything again.
 */
internal fun tlsFailureToAnnounce(
    failure: TlsFailure,
    alreadySaid: MutableSet<TlsFailure>,
): TlsFailure? {
    if (alreadySaid.size >= MAX_TLS_FAILURES_ANNOUNCED) return null
    return if (alreadySaid.add(failure)) failure else null
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
 * Past [MAX_HANDOFF_FAILURES_ANNOUNCED] distinct failures nothing more is said,
 * for the reason [tlsFailureToAnnounce] gives: the record is what counts them, so
 * one test bounds the toasts and the set together.
 *
 * [alreadySaid] belongs to the presenter, which keeps this class free of mutable
 * state, and its lifetime falls out of that: it dies with the Activity, so a fresh
 * launch says everything again.
 */
internal fun handoffFailureToAnnounce(
    failure: HandoffFailure,
    alreadySaid: MutableSet<HandoffFailure>,
): HandoffFailure? {
    if (alreadySaid.size >= MAX_HANDOFF_FAILURES_ANNOUNCED) return null
    return if (alreadySaid.add(failure)) failure else null
}

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
) : WebViewClient() {

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
            Logger.i(tag, "Opened external URL: ${redactToken(url.toString())}")
        } catch (e: Exception) {
            AuthTabWindow.disarm(armed)
            // The throwable is deliberately not passed, and leaving it in was a
            // redaction that only looked like one. `Log.e(tag, msg, tr)` prints
            // the throwable with its message, and both exceptions this
            // realistically catches put the whole address there:
            // ActivityNotFoundException quotes the Intent it could not match,
            // `dat=` and all, and FileUriExposedException names the file it
            // refused. So the URL [redactToken] had just taken out of the message
            // arrived in logcat two lines below it, on a statement that is not
            // gated on a debuggable build and therefore ships. The frames behind
            // it are all framework, so the class name is what the trace was being
            // read for anyway. Same shape and same reasoning as
            // `AndroidBridge.openExternalUrl`.
            Logger.e(
                tag,
                "Failed to open external URL: ${redactToken(url.toString())} " +
                    "(${e.javaClass.simpleName})"
            )
            // The launch above is attempted for every frame, and that stays true:
            // a link in a preview or in the simple browser still leaves for a
            // browser. What is gated here is only the notice.
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
        return interceptCdnRequest(
            request, allowedPort, connectionToken(), resourceRoots, sensitiveLocations, openFolder
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
        // Redacted for the log only. [onPageLoaded] gets the URL as it arrived,
        // because `MainActivity` reads the open workspace folder out of it. It
        // has another source for that folder -- it sets it directly when it is
        // the one driving the navigation -- but not for the case this callback
        // exists to cover: the workbench switches folders by navigating itself,
        // without going through Kotlin, and then the URL is the only notice
        // there is. A redacted copy would lose the folder on exactly those loads.
        //
        // This log statement is at `Logger.i`, which is not gated on a debuggable
        // build, so it was the leak that shipped.
        Logger.i(tag, "Page loaded: ${redactToken(url)}")
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
                    resourceRootsInForce(resourceRoots, sensitiveLocations, openFolder()),
                    request.requestHeaders?.entries
                        ?.firstOrNull { it.key.equals("Origin", ignoreCase = true) }?.value,
                )
            }

            val path = uri.path ?: return null
            val localUrl = rewriteCdnUrl(path, uri.query, port, token)

            if (localUrl == null) {
                // The whole URI, so its query comes with it -- and the workbench
                // appends the token to requests of its own. At `Logger.w`, so this
                // one shipped too.
                Logger.w(TAG, "CDN URL could not be rewritten: ${redactToken(uri.toString())}")
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
            resourceRoots: List<String>, origin: String?
        ): WebResourceResponse? {
            val prefix = host.removeSuffix(".$resourceAuthority")
            val parts = prefix.split("+", limit = 2)
            val scheme = parts[0]
            val authority = if (parts.size > 1) parts[1] else ""
            val path = uri.path ?: return notFound("No path")

            if (scheme == "file" || scheme == "vscode-remote") {
                // Files served here carry `Access-Control-Allow-Origin: *`, which is what
                // makes them readable by whatever asked. The published roots decide WHICH
                // files; this decides WHO may read one, and until it existed the answer
                // was anybody. A remote page in the bundled Simple Browser could fetch a
                // workspace file and read the body, with no network involved.
                //
                // Measured on an API 37 emulator rather than assumed, because the whole
                // gate rests on the header being there: of 42 intercepted requests, 29
                // carried `Origin` and it was always `http://127.0.0.1:<port>`, the
                // workbench's own. The `.vscode-cdn.net` arm is read from the shipped
                // bundle instead, where `webviewContentExternalBaseUrlTemplate` resolves
                // webview documents to `https://{{uuid}}.vscode-cdn.net/...`.
                //
                // A missing header is served, deliberately. The other 13 were no-cors
                // subresource loads -- `<img>`, `<link>`, `<script src>` -- which send no
                // `Origin` and cannot read the body anyway, so demanding one would break
                // every extension webview to close nothing.
                //
                // ⚠️ Residual, stated rather than implied: an HTML file our own branch
                // serves gets an origin ending `.vscode-cdn.net` and passes. Closing that
                // wants `Sec-Fetch-Dest` gating, which is unverified here and risks
                // extensions that frame their own local HTML.
                if (origin != null && !isOurOrigin(origin, port)) {
                    Logger.w(TAG, "Resource refused, foreign origin: $origin")
                    return notFound("Access denied")
                }
                // Local file resource — serve directly from filesystem.
                // Both "file" and "vscode-remote" schemes use local paths in VSCodroid
                // since the server runs on the same device.
                // Through resourceOutcome so the decision is a value rather than a
                // log line. Refusing and serving both end in a WebResourceResponse,
                // which a test cannot inspect under the stub android.jar, so
                // "log the refusal and serve anyway" was indistinguishable from a
                // refusal to everything that watched this function.
                val file = when (val outcome = resourceOutcome(path, resourceRoots)) {
                    ResourceOutcome.Refused -> {
                        Logger.w(TAG, "Resource outside the published roots refused: $path")
                        return notFound("Access denied")
                    }
                    ResourceOutcome.Missing -> {
                        Logger.d(TAG, "Resource not found ($scheme): $path")
                        return notFound(path)
                    }
                    is ResourceOutcome.Serve -> outcome.file
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

        /** Whether [origin] is the workbench itself or one of its webview documents. */
        private fun isOurOrigin(origin: String, port: Int): Boolean =
            origin == "http://127.0.0.1:$port" ||
                origin == "http://localhost:$port" ||
                (origin.startsWith("https://") && origin.endsWith(".vscode-cdn.net"))

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
