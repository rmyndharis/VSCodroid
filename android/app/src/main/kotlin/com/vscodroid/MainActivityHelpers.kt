package com.vscodroid

import android.content.Context
import android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
import android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE
import android.content.ComponentCallbacks2.TRIM_MEMORY_MODERATE
import android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
import android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.content.res.Resources
import android.net.Uri
import android.os.SystemClock
import android.webkit.WebView
import java.io.File
import java.security.MessageDigest
import com.vscodroid.util.isLightColor
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.vscodroid.bridge.AuthTabWindow
import com.vscodroid.service.StartupNotice
import com.vscodroid.storage.SafFolderInfo
import com.vscodroid.storage.SafStorageManager
import com.vscodroid.storage.SafSyncEngine
import com.vscodroid.util.Logger
import com.vscodroid.webview.VSCodroidWebChromeClient
import com.vscodroid.webview.isWorkbenchPath
import com.vscodroid.webview.redactToken
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.core.content.edit

/** Run health check if backgrounded longer than this. */
internal const val HEALTH_CHECK_THRESHOLD_MS = 60_000L   // 1 minute

/** Force page reload if backgrounded longer than this. */
internal const val FORCE_RELOAD_THRESHOLD_MS = 300_000L  // 5 minutes

// Severities a trim level maps to. Only the log line carries the word; the page
// is handed the raw trim level, and only when it maps above none. Words rather
// than numbers so that nothing downstream is tempted to compare them with >=,
// which is the defect this replaced.
internal const val PRESSURE_NONE = "none"
internal const val PRESSURE_MODERATE = "moderate"
internal const val PRESSURE_CRITICAL = "critical"

/**
 * Whether a change of configuration with this [diff] (`Configuration.diff`) changes the shape
 * of the screen: a turn of the phone, a resize in a window or a fold. Those are the changes that
 * take the keyboard down for a moment and bring it back. A change of navigation type, of night
 * mode, of the keyboard attached or of density alone does not reshape the window, and is not
 * one, though the manifest keeps the activity across all of them.
 */
internal fun isScreenTurn(diff: Int): Boolean = diff and (
    ActivityInfo.CONFIG_ORIENTATION or ActivityInfo.CONFIG_SCREEN_SIZE or
        ActivityInfo.CONFIG_SMALLEST_SCREEN_SIZE or ActivityInfo.CONFIG_SCREEN_LAYOUT
    ) != 0

/**
 * What a trim level actually says about memory, which is not what comparing
 * it says.
 *
 * Android's constants are not ordered by severity. `TRIM_MEMORY_UI_HIDDEN`
 * is 20 and sits above `TRIM_MEMORY_RUNNING_CRITICAL` at 15, but it does not
 * describe memory at all: it means "your UI is no longer visible" and
 * arrives on every single backgrounding, on a device with gigabytes free.
 * A `>=` comparison therefore read an ordinary app switch as worse than a
 * genuine critical warning, and while the process monitor still killed idle
 * language servers on that word, every one of them (which, after five minutes
 * in another app, is all of them) was killed on every backgrounding.
 *
 * So this maps rather than compares, and the next constant Android adds
 * cannot clear a threshold by accident. Raising the number would have looked
 * like a fix and been wrong again at the next value.
 */
@Suppress("DEPRECATION")
internal fun memoryPressureOf(level: Int): String = when (level) {
    // Critical while running, and the cached levels, which all mean the
    // system is reclaiming and this process is a candidate; the page is told
    // so it can shrink its own footprint.
    TRIM_MEMORY_RUNNING_CRITICAL,
    TRIM_MEMORY_BACKGROUND,
    TRIM_MEMORY_MODERATE,
    TRIM_MEMORY_COMPLETE -> PRESSURE_CRITICAL

    // Reported as moderate, exactly as before: 10 sat below the old
    // threshold of 15.
    TRIM_MEMORY_RUNNING_LOW -> PRESSURE_MODERATE

    // TRIM_MEMORY_UI_HIDDEN (20) lands here, with TRIM_MEMORY_RUNNING_MODERATE
    // (5). The first is not about memory at all; the second is the mildest
    // hint Android has. This is the only line that changes behaviour, and it
    // changes it for exactly the value that was wrong.
    else -> PRESSURE_NONE
}

/**
 * Whether an incoming URI is the extension auth callback relay.
 *
 * Both halves are load-bearing, and the manifest is why. The VIEW filter that
 * delivers this is exported and BROWSABLE, so any installed app and any web page
 * the user taps can fire it, and what rides in the `data` parameter is written
 * into the workbench's `localStorage`. Relaxed to either half on its own
 * (`vscodroid://` with any host, or any scheme pointed at `callback`), the relay
 * starts accepting shapes the flow it exists for never sends.
 *
 * Taking the two parts rather than the Uri is what makes this reachable: `Uri`
 * is abstract with a private constructor, so neither building one nor mocking
 * one works in a plain JVM test, and the decision here is about two strings.
 */
internal fun isExtensionCallback(scheme: String?, host: String?): Boolean =
    scheme.equals("vscodroid", ignoreCase = true) && host.equals("callback", ignoreCase = true)

/**
 * The workbench request id a callback payload names, or null if it names none.
 *
 * The payload is what `callback.html` builds before it navigates to the intent:
 * `encodeURIComponent(JSON.stringify({ id, uri }))`, where `id` is the
 * `vscode-reqid` the page was loaded with. `Uri.getQueryParameter` undoes the
 * encoding, so what arrives here is the JSON object itself.
 *
 * Parsed rather than pattern-matched, and the difference is not style. A reader
 * that searched the text for a number would find one in the `uri` half as
 * readily as in the `id` half, and the value decides whether an exported entry
 * point is answered. Parsing is also what makes malformed input a null instead
 * of a guess: anything on the device can fire this intent, so the payload is
 * attacker-shaped by construction and the parse has to be total.
 *
 * Takes the already-extracted parameter rather than the `Uri`, for the reason
 * [isExtensionCallback] takes two strings: `Uri` is abstract with a private
 * constructor, so it can be neither built nor mocked in a plain JVM test, and
 * the decision here is about one string.
 */
internal fun callbackRequestId(data: String?): String? {
    if (data.isNullOrEmpty()) return null
    return try {
        JSONObject(data).optString("id").ifEmpty { null }
    } catch (e: JSONException) {
        null
    }
}

/**
 * The secret a callback payload carries, if it carries one.
 *
 * It rides in the query, because that is the one part of the callback the
 * workbench minted and the provider handed back: patch 0019 puts
 * `vscodroid-nonce` in the callback URL beside `vscode-reqid`, `callback.html`
 * strips only the `vscode-` parameters, and what is left becomes `uri.query` in
 * the payload. Null covers a payload this cannot read, one whose `uri` is not an
 * object, one with no query and one whose query has no such parameter, which are
 * the shapes an outside caller produces and all deserve the same answer.
 */
internal fun callbackNonce(data: String?): String? {
    val query = try {
        (JSONObject(data ?: return null).optJSONObject("uri")?.opt("query") as? String)
    } catch (e: JSONException) {
        null
    } ?: return null
    return callbackNonceParam(query)
}

/** The name the workbench mints the per-request secret under. See patch 0019. */
internal const val CALLBACK_NONCE_PARAM = "vscodroid-nonce"

/**
 * The longest `data` parameter [MainActivity.receiveCallbackIntent] will parse.
 *
 * Generous by design: the payload a real sign-in carries is a few hundred bytes,
 * and the point of the bound is the shape no real one has, a deeply nested value
 * that makes the platform's JSON reader recurse until the stack is gone.
 */
internal const val MAX_CALLBACK_PAYLOAD_CHARS = 8192

/**
 * The secret in a callback query, read without `Uri`, which a plain JVM test
 * cannot build. Split on the two separators a query uses and compared whole, so
 * a parameter merely ending in the name (`x-vscodroid-nonce`) is not it.
 */
internal fun callbackNonceParam(query: String): String? = query.split('&')
    .firstOrNull { it.substringBefore('=') == CALLBACK_NONCE_PARAM }
    ?.substringAfter('=', "")
    ?.ifEmpty { null }

/**
 * The same query with the secret taken out, or null when it carried none.
 *
 * The relay hands the address to the workbench, which hands it to the extension
 * that started the sign-in, and the secret is this app's business with the
 * browser rather than something an extension should be handed. Returns an empty
 * string when the secret was the only parameter, which the caller drops.
 */
internal fun callbackQueryWithoutNonce(query: String): String =
    query.split('&').filterNot { it.substringBefore('=') == CALLBACK_NONCE_PARAM }
        .joinToString("&")

/** The return must carry the nonce registered by this document's callback provider. */
internal fun callbackSecretMatches(offered: String?, expected: String?): Boolean {
    if (expected.isNullOrEmpty() || offered.isNullOrEmpty()) return false
    return MessageDigest.isEqual(
        offered.toByteArray(Charsets.UTF_8),
        expected.toByteArray(Charsets.UTF_8),
    )
}

/**
 * The address a callback payload carries, as the JSON text the workbench stores.
 *
 * The workbench collects a callback by reading
 * `vscode-web.url-callbacks[<id>]` and parsing it, and what `callback.html`
 * writes there is `JSON.stringify(uri)`. So the value this returns is that same
 * text, produced from the object the payload carries rather than from the
 * payload's own bytes: the page then receives a finished literal and never
 * parses anything, which is what keeps the id the timing gate approved and the
 * id the value is written under from being decided by two different readers.
 *
 * Only an object counts. `callback.html` builds `uri` as
 * `{ scheme, authority, path?, query?, fragment? }` and can build nothing else,
 * so a payload whose `uri` is a string, a number or absent is not the message
 * this relay is for, and answering null leaves it in the branch that injects
 * nothing. That direction is deliberate: the filter is exported and BROWSABLE,
 * so the payload is attacker-shaped by construction and every reading of it has
 * to be total.
 *
 * Takes the already-extracted parameter rather than the `Uri`, for the reason
 * [callbackRequestId] does: `Uri` is abstract with a private constructor, so it
 * can be neither built nor mocked in a plain JVM test.
 */
internal fun callbackUriJson(data: String?): String? {
    if (data.isNullOrEmpty()) return null
    return try {
        val uri = JSONObject(data).optJSONObject("uri") ?: return null
        // The parameter this app added is taken back out before the address goes
        // any further: the workbench hands it to the extension that started the
        // sign-in, and what that extension asked for is its own callback, not a
        // parameter this app and the browser arranged between themselves. A
        // query left empty by the removal is dropped rather than sent as "".
        //
        // Only the parameter, not every copy of the value. A provider that echoes
        // the whole redirect back inside another parameter carries one with it:
        // the GitHub extension's url-handler flow puts the callback URL in
        // `state`, percent encoded, so the secret is in there too (that flow is
        // filtered out on this build, which carries no GitHub client secret).
        // Rewriting inside `state` is not an option, because that value is
        // compared byte for byte by the flow that sent it. Nothing is gained by
        // that copy: the extension holding it is the one whose sign-in this is.
        val query = uri.opt("query") as? String
        if (query != null && callbackNonceParam(query) != null) {
            val rest = callbackQueryWithoutNonce(query)
            if (rest.isEmpty()) uri.remove("query") else uri.put("query", rest)
        }
        uri.toString()
    } catch (e: JSONException) {
        null
    }
}

/**
 * What binding to an already-running service should do about it.
 *
 * Extracted so the decision is a value rather than a shape in the source.
 * `ServerReadinessDecisionTest` checks the pure decision logic, while
 * `ServerReadinessCallSiteTest` invokes the activity methods to verify their
 * loading, notice, port-recording, and resume effects.
 */
internal sealed interface BindDecision {
    /** The server said something about its start that predates this binding. */
    data class ShowNotice(val message: String) : BindDecision

    /**
     * The server has given up, and said so before this activity bound.
     *
     * Separate from [ShowNotice] because the two need different treatment and
     * the difference is the whole point: a slow start may still come up, so a
     * toast over the loading page is honest. A server that has stopped trying
     * never will, and leaving the loading page up says "Starting server..." for
     * as long as the user waits, with no way off it.
     */
    data class ShowGaveUp(val message: String) : BindDecision

    /** Serving, on a port worth navigating to. */
    data class Load(val port: Int) : BindDecision

    /** Not serving yet; `onServerReady` will arrive if it comes up. */
    object Wait : BindDecision
}

/**
 * Whether [url] is a page the local server served, as opposed to one this app
 * drew itself.
 *
 * `onPageFinished` reports that a main-frame load finished, which is not the
 * same question and differs exactly where it costs something: the loading page
 * and the server-gave-up page are `loadData` documents with no origin, and
 * treating either as the workbench injects the session token into a page that
 * cannot use it and marks the app ready to receive an OAuth callback it cannot
 * consume.
 *
 * The address and port match the gateway's IPv4 listener. A hostname may select
 * IPv6, whose listener is not reserved by this application.
 *
 * Parsed with `java.net.URI` rather than `Uri.parse`, so the decision can be
 * exercised by a plain JVM test. `Uri.parse` is a framework method that answers
 * null off-device, which would make every case here pass for the wrong reason.
 * The two agree on what matters: both give a null host for the `loadData`
 * documents this exists to exclude, and `URI` throws on the malformed ones,
 * which is caught below and read the same way.
 */
internal fun isWorkbenchUrl(url: String?, port: Int): Boolean {
    if (url == null || port <= 0) return false
    // Named so they collide with nothing else in this file. `LogTaint`, which
    // guards this file against logging the connection token or a device folder's
    // tree URI, follows taint by identifier name across the whole file: binding
    // the parsed URL to `uri` or its host to `host` would mark two unrelated
    // locals as sensitive and report honest log statements as leaks. A `Uri`
    // written as a type is a seed there too, so `val uri: Uri` is the same
    // mistake spelled differently. The conservatism is the point, so the alias
    // is what has to go.
    val parsed = runCatching { java.net.URI(url) }.getOrNull() ?: return false
    val hostName = parsed.host ?: return false
    return hostName == "127.0.0.1" && parsed.port == port &&
        isWorkbenchPath(parsed.path)
}

/**
 * The notice is read first, and that ordering is load-bearing: it may be terminal
 * or it may be a slow start still coming up, and in both cases the server is not
 * serving, so it must not be shadowed by a port that happens to look plausible.
 *
 * Which of the two it is then decides the answer: a terminal notice gives
 * [BindDecision.ShowGaveUp] and any other gives [BindDecision.ShowNotice],
 * because a server that has stopped trying needs the loading page replaced,
 * while a slow start may still come up under a toast.
 *
 * [ready] is the health probe's own finding, never process liveness. A process is
 * alive from the instant it is spawned and stays alive through the seconds before
 * its port is bound and through a whole post-crash restart; navigating on that
 * points the WebView at nothing, and `onReceivedError` only logs a refused
 * connection, so the connection-refused page it produces is never cleared.
 */
internal fun bindDecision(notice: StartupNotice?, port: Int, ready: Boolean): BindDecision = when {
    notice != null && notice.terminal -> BindDecision.ShowGaveUp(notice.message)
    notice != null -> BindDecision.ShowNotice(notice.message)
    port > 0 && ready -> BindDecision.Load(port)
    else -> BindDecision.Wait
}

/**
 * The shortest gap between two redraws of the folder-sync dialog.
 *
 * Ten a second is already faster than anyone reads, and the count is what the
 * number is really about: the engine reports once per file, so a folder of
 * twenty thousand posted twenty thousand relayouts onto the main looper.
 */
internal const val SYNC_PROGRESS_INTERVAL_MS = 100L

/**
 * Whether the folder-sync dialog should be redrawn for this file.
 *
 * The last file always is, whatever the clock says: it is the one reading the
 * user is owed (the counts have to end matching) and the one a throttle would
 * otherwise be most likely to swallow, since it arrives right after its
 * predecessor.
 */
internal fun syncProgressIsDue(done: Int, total: Int, sinceLastMs: Long): Boolean =
    done >= total || sinceLastMs >= SYNC_PROGRESS_INTERVAL_MS

/**
 * The notice for a folder created in the editor that did not arrive whole on the device: [lost]
 * entries short, or stopped at the cap this app sets ([capped]), which has its own wording because
 * the limit is the app's and not the device's. [folder] is the folder's own name.
 */
internal fun uploadIncompleteNotice(resources: Resources, folder: String, lost: Int, capped: Boolean): String =
    if (capped) {
        resources.getString(R.string.saf_upload_capped, folder)
    } else {
        resources.getQuantityString(R.plurals.saf_upload_incomplete, lost, lost, folder)
    }

/**
 * The notice for files an open of [folder] keeps inside the app, naming the [first] of them: one
 * file has a sentence of its own, as a kept edit does, and [count] of them take the plural.
 */
internal fun filesKeptInAppNotice(resources: Resources, folder: String, count: Int, first: String): String =
    if (count == 1) {
        resources.getString(R.string.saf_file_kept_in_app, folder, first)
    } else {
        resources.getQuantityString(R.plurals.saf_files_kept_in_app, count, count, folder, first)
    }

/** Keeps the device suffix visible in a short notice. */
internal fun preservedDeviceNameForNotice(name: String): String =
    preservedCopyNameForNotice(name, SafSyncEngine.DEVICE_COPY_SUFFIX)

/** Keeps the local suffix visible in a short notice. */
internal fun preservedLocalNameForNotice(name: String): String =
    preservedCopyNameForNotice(name, SafSyncEngine.LOCAL_COPY_SUFFIX)

private fun preservedCopyNameForNotice(name: String, suffixMarker: String): String {
    val maxCodePoints = 32
    if (name.codePointCount(0, name.length) <= maxCodePoints) return name
    val marker = name.lastIndexOf(suffixMarker)
    val suffixStart = if (marker >= 0) marker else name.offsetByCodePoints(name.length, -18)
    val suffix = name.substring(suffixStart)
    val suffixLength = suffix.codePointCount(0, suffix.length)
    val prefixLength = (maxCodePoints - 3 - suffixLength).coerceAtLeast(0)
        .coerceAtMost(name.codePointCount(0, suffixStart))
    return name.substring(0, name.offsetByCodePoints(0, prefixLength)) + "..." + suffix
}

/**
 * What to call [dir] in a notice about it.
 *
 * A mirror root is named after a digest of the tree URI, twelve hex characters,
 * so a notice printing the directory name says "a1b2c3d4e5f6" where the folder's
 * own name belongs. The stranded-upload pass is what reaches that: it has the
 * whole mirror in hand rather than a directory somebody made, and it was the one
 * notice naming a hash.
 *
 * The ROOT only, which is why this is not
 * [SafStorageManager.folderForOpenedPath]. That one answers for anything inside a
 * mirror as well, by design, and here it would rename a directory created in the
 * editor after the device folder containing it: "four files in Documents did not
 * reach the device" for a shortfall that was entirely inside `src/generated`.
 * Matched on the complete canonical path so system aliases still name the same
 * copy, while a nested directory with the same basename keeps its own name.
 *
 * [folders] is the recent list, pruned of revoked grants before it is handed
 * over, so a folder whose grant the user has taken back has no entry and keeps
 * the directory name. That is the honest answer at that point: the app no longer
 * knows what the folder was called.
 *
 * Top-level for the reason the notices are wired out of locals: anything
 * they call on the Activity captures it, and the write-back worker they belong to
 * outlives the screen.
 */
internal fun mirrorDisplayName(folders: List<SafFolderInfo>, dir: File): String {
    val directory = runCatching { dir.canonicalFile }.getOrNull() ?: return dir.name
    return folders.firstOrNull {
        runCatching { File(it.mirrorPath).canonicalFile == directory }.getOrDefault(false)
    }?.displayName ?: dir.name
}

/**
 * How long a renderer crash counts towards a loop, and how many are allowed
 * inside that time.
 *
 * A minute and three, which is a loop by any reading: a crashed renderer is
 * rebuilt and the workbench loaded into it at once, so a fourth crash inside the
 * minute says the load is what keeps killing it. A slower repeat (a page that
 * dies when one particular file is opened, an hour apart) falls out of the window
 * and goes on being recovered from, which is the right treatment for it.
 */
internal const val CRASH_LOOP_WINDOW_MS = 60_000L
internal const val CRASH_LOOP_CRASHES = 3

/**
 * Records a renderer crash and answers whether recovering from them has become a
 * loop.
 *
 * [times] is the caller's record and is MUTATED: readings older than the window
 * are dropped and [now] is added, so it stays bounded by the window rather than
 * growing for the life of the Activity.
 *
 * A count with no window would eventually refuse a recovery to a session that had
 * three unrelated crashes in a day, and a window with no count would never refuse
 * one at all. See `MainActivity.recreateWebView`, which is the only caller and
 * which cannot be driven from a JVM test.
 */
internal fun crashLoopReached(
    times: ArrayDeque<Long>,
    now: Long,
    windowMs: Long = CRASH_LOOP_WINDOW_MS,
    limit: Int = CRASH_LOOP_CRASHES,
): Boolean {
    while (times.isNotEmpty() && now - times.first() > windowMs) times.removeFirst()
    times.addLast(now)
    return times.size > limit
}

/**
 * Whether returning from the background should touch the page at all.
 *
 * [ready] is nullable because the service may not be bound, and null is not
 * evidence of anything: it must not be read as permission to reload. Only a
 * probe that actually found the server serving is.
 */
internal fun shouldActOnResume(ready: Boolean?, backgroundedAt: Long, serverPort: Int): Boolean =
    backgroundedAt != 0L && serverPort != 0 && ready == true

/** What returning from the background should do to the page. */
internal enum class ResumeAction {
    /** Short absence. The WebSocket survives those. */
    NOTHING,

    /** Long enough that the connection may be dead. Ask, reload only if it is. */
    PROBE_CONNECTION,

    /** Long enough that stale state is near certain. Reload unconditionally. */
    RELOAD,
}

/**
 * Whether the page may be reloaded on the way back from the background.
 *
 * [signInPending] outranks the reload, and that ordering is the point of this
 * function existing. The two conditions are not independent: a sign-in that takes
 * more than five minutes is a sign-in that went through a second factor or an
 * organisation's consent screen, which is the ordinary case rather than the
 * exotic one, so the threshold written for a stale WebSocket lines up almost
 * exactly with the moment a callback is being delivered. The workbench holds the
 * requests it is waiting on in memory, so the reload discards them and the
 * sign-in that had just succeeded is lost with no error anywhere.
 *
 * Downgraded to the probe rather than dropped, and that is the whole of what a
 * pending sign-in buys. The absence really was long enough for the connection to
 * have died, so answering it with nothing leaves the page in the state the
 * five-minute rule exists to repair -- and nothing would come back to it, because
 * this decision is only ever made on the way in from the background and each
 * arrival is judged on its own absence. The probe is the strongest answer that is
 * safe here: it only reloads when IndexedDB is already unusable, and a page in
 * that state has nothing left to collect a callback with.
 *
 * [fileChooserPending] outranks both, and gets the stronger answer rather than
 * the probe. The difference from a sign-in is that the answer is already
 * arriving: the picker is another app, so the browse *is* the absence, and the
 * chosen file is handed to this same document moments after this decision. The
 * probe would not be enough, on two counts. It reloads the page from JS when
 * IndexedDB is unusable, which discards the selection just as the forced reload
 * does; and it starts at one minute, where browsing storage for longer than that
 * is the ordinary case rather than the exotic one.
 *
 * The cost is a page left possibly stale, and it is bounded: the answer is being
 * delivered now, and the next trip through the background judges the page afresh
 * on its own absence.
 *
 * [savePickerPending] is the same absence reached by the other picker and gets
 * the same answer. Saving a download opens the create-document picker, which is
 * another app, and the answer comes back into this same document: the
 * coordinator asks the page for the bytes only after the destination is chosen,
 * so a reload here leaves the user having named a file that can no longer be
 * read. The default is false so that this stays one argument per signal at the
 * call site rather than a boolean nobody can see; the call site is checked by a
 * test for naming it, because a dropped argument is exactly what the default
 * would otherwise hide.
 *
 * An earlier shape of this held the reload for later by putting the caller's
 * backgrounded reading back. That reading is written afresh by `onStop`, which
 * Android always delivers before the next `onStart`, so the restored value was
 * overwritten before its only consumer could read it and the reload was dropped
 * rather than deferred.
 */
internal fun resumeAction(
    bgMs: Long,
    signInPending: Boolean,
    fileChooserPending: Boolean,
    savePickerPending: Boolean = false,
): ResumeAction = when {
    fileChooserPending || savePickerPending -> ResumeAction.NOTHING
    bgMs > FORCE_RELOAD_THRESHOLD_MS && signInPending -> ResumeAction.PROBE_CONNECTION
    bgMs > FORCE_RELOAD_THRESHOLD_MS -> ResumeAction.RELOAD
    bgMs > HEALTH_CHECK_THRESHOLD_MS -> ResumeAction.PROBE_CONNECTION
    else -> ResumeAction.NOTHING
}

/**
 * Whether the launch a callback belongs to is still inside its window.
 *
 * Shape is all `isExtensionCallback` can judge, and shape is what anything on
 * the device can produce: the VIEW filter is exported and BROWSABLE. The value
 * that rides in is written into the workbench's storage under an id the
 * workbench hands out as a counter from one, so the id an unsolicited caller
 * would have to name is not a secret and never was. What it cannot supply is a
 * sign-in this app started, which is the only thing that separates a return from
 * an invention.
 *
 * That match is made before this: the caller looks the callback's own request id
 * up in [com.vscodroid.bridge.AuthTabWindow] and gets the reading of the launch
 * that carried it, or nothing. This half answers only the remaining question,
 * which is how long ago that was.
 *
 * Timing, not identity, for the rest of it: the legitimate sender is a browser,
 * so there is no caller identity here that could be checked either.
 *
 * `openedAtMillis == 0` means no launch, which is what a caller with no reading
 * to pass would produce. The lower bound on the elapsed time is not redundant
 * with it: the caller passes a monotonic clock, and a negative reading would mean
 * the two came from different boots, which is not an elapsed time at all.
 *
 * Takes its clock readings rather than reading them, for the reason
 * `isExtensionCallback` takes two strings -- `SystemClock` is not answerable in
 * a plain JVM test, and the decision here is arithmetic on three numbers.
 */
internal fun authCallbackIsExpected(
    openedAtMillis: Long,
    nowMillis: Long,
    windowMillis: Long
): Boolean = openedAtMillis != 0L && (nowMillis - openedAtMillis) in 0..windowMillis

/**
 * The workbench URL for a folder, with the connection token when there is one.
 *
 * One expression, and that is the point of it being a function. The call site
 * used to build two, the URL it loaded and a token-free twin it logged, so the
 * token stayed out of logcat only for as long as nobody collapsed them, which is
 * what a merge does and what anyone wanting the real navigation URL in the log
 * would do deliberately. With a single URL there is nothing to keep in step:
 * the log statement redacts, and [com.vscodroid.webview.redactToken] keys on
 * `tkn=`, which is the parameter this builds.
 *
 * That coupling is the fragile part and it is why this is testable at all. A
 * later rename of the parameter would leave the redactor matching nothing and
 * the log line looking untouched.
 *
 * An empty or absent token yields the bare URL rather than `tkn=`. The server
 * answers that with "Forbidden.", the caller says so, but a page the user can
 * retry beats no page, and sending an empty token would be indistinguishable
 * from sending a wrong one.
 */
internal fun workbenchUrl(
    port: Int,
    folderPath: String?,
    token: String?,
    isFile: (String) -> Boolean = { File(it).isFile },
): String {
    // The name is not enough, and [folderOpenTarget] already says why: a
    // DIRECTORY spelled `*.code-workspace` is a workspace the workbench cannot
    // read, and it answers one of those with an empty window and no message. That
    // exclusion was applied where the picker chooses a target and not here, where
    // the URL is actually built, so such a directory still went out as
    // `?workspace=`. What the URL itself costs is separate and quieter:
    // [folderFromUrl] answers null for it, so a folder the workbench opens on its
    // own is never adopted.
    //
    // The stat is behind the suffix test, so an ordinary folder pays nothing; the
    // predicate is passed for the reason every other predicate in this file is,
    // that these paths do not exist on a JVM test machine.
    //
    // A null path is the third thing the workbench can be showing, and it is a
    // state rather than a missing argument: the user closed the folder. The
    // workbench spells that `?ew=true`, and it has to be spelled here so that a
    // cold start restoring it goes through the one builder, with the token. The
    // token is not optional on that path: a fresh process has no `vscode-tkn`
    // cookie, and the server answers a request without one with "Forbidden."
    // Sending it is safe because the server strips `tkn` and redirects with every
    // other query parameter intact.
    val query = when {
        folderPath == null -> "ew=true"
        folderPath.endsWith(WORKSPACE_FILE_SUFFIX) && isFile(folderPath) ->
            "workspace=${Uri.encode(folderPath)}"
        else -> "folder=${Uri.encode(folderPath)}"
    }
    val base = "http://127.0.0.1:$port/?$query"
    return if (token.isNullOrEmpty()) base else "$base&tkn=${Uri.encode(token)}"
}

/** What the workbench calls a multi-root workspace, in the only place it is spelled. */
internal const val WORKSPACE_FILE_SUFFIX = ".code-workspace"

/** The final decoded workspace selector, before checking whether its path exists. */
internal data class WorkbenchSelection(val kind: String, val value: String)

internal fun workbenchSelection(query: String?): WorkbenchSelection? {
    var selected: WorkbenchSelection? = null
    for (part in query?.split('&').orEmpty()) {
        val kind = decodeWorkbenchQueryPart(part.substringBefore('='))
        if (kind == "folder" || kind == "workspace" || kind == "ew") {
            selected = WorkbenchSelection(kind, decodeWorkbenchQueryPart(part.substringAfter('=', "")))
        }
    }
    return selected
}

private fun decodeWorkbenchQueryPart(part: String): String = java.net.URLDecoder.decode(
    // Form queries keep malformed percent signs literal and decode valid escapes once.
    part.replace(Regex("%(?![0-9a-fA-F]{2})"), "%25"),
    "UTF-8",
)

/** Validate only the selected target; an earlier selector is never a fallback. */
internal fun workbenchTarget(
    query: String?,
    isDirectory: (String) -> Boolean,
    isFile: (String) -> Boolean,
): String? {
    val selected = workbenchSelection(query) ?: return null
    // Relative and empty values become file URIs in the workbench, not paths under our cwd.
    if (!selected.value.startsWith('/')) return null
    return when (selected.kind) {
        "folder" -> selected.value.takeIf(isDirectory)
        "workspace" -> selected.value.takeIf(isFile)
        else -> null
    }
}

/**
 * The directory a target stands in: itself for a folder, its parent for a workspace.
 *
 * Only the resource interceptor wants this. It publishes the open workspace as a
 * resource root, and [com.vscodroid.webview.resourceRootsInForce] matches a root
 * by path prefix, so a root that is a single `.code-workspace` file matches only
 * that file and every resource beside it is refused. The statically published
 * roots cover the mirrors and projects trees, so this is the difference only for
 * a workspace held outside both, which is exactly the case nothing else covers.
 *
 * The other two readers of the field want the prefix and get it either way:
 * `folderForOpenedPath` and `mirrorNameFor` both reduce a path to the mirror
 * holding it, and a file inside a mirror reduces the same as its directory does.
 */
internal fun workspaceDirectoryInForce(
    path: String?,
    isFile: (String) -> Boolean = { File(it).isFile },
): String? =
    // Same file test as [workbenchUrl], and here it narrows a published resource
    // root rather than a URL. A DIRECTORY spelled `*.code-workspace` reduced to
    // its parent, so the root published for it covered every sibling of the folder
    // the user opened. Reducing is right for a workspace FILE, whose siblings are
    // the workspace's own content; for a directory the folder itself is the root.
    if (path != null && path.endsWith(WORKSPACE_FILE_SUFFIX) && isFile(path)) {
        File(path).parent
    } else {
        path
    }

/**
 * The URL when it shows the workbench with the folder closed, or null.
 *
 * A closed folder is the third thing the workbench can be showing, and it is the
 * one the folder chain cannot express: [workbenchTarget] answers a path or
 * nothing, and "no folder, deliberately" is not a path. So every re-navigation
 * over a closed folder fell through to the remembered folder and put the user
 * back into the workspace they had just closed. `handleResumeFromBackground`
 * already sidesteps this by calling `reload()` rather than rebuilding a URL, and
 * this is how the paths that do rebuild recognise the state.
 *
 * A predicate in practice: both callers test the answer for null and never load
 * it. The URL carries no token, and the cookie that authenticated it named a
 * server that a restart may since have replaced with one holding a new token, so
 * the closed state is rebuilt through [workbenchUrl], which spells `ew=true` with
 * the live one. The final recognized selector decides: `ew` closes the window
 * regardless of its value, while a final `folder` or `workspace` stays with the
 * folder chain.
 *
 * The native workbench predicate owns the supported hosts, port and root path.
 * Only HTTP windows are restored, matching [workbenchUrl].
 */
internal fun emptyWindowUrl(url: String?, port: Int): String? {
    if (url == null) return null
    // Keep form-query percent decoding separate from strict address parsing.
    val workbenchAddress = url.substringBefore('#').substringBefore('?')
    if (!workbenchAddress.startsWith("http://") || !isWorkbenchUrl(workbenchAddress, port)) return null
    val query = url.substringBefore('#').substringAfter('?', "")
    return url.takeIf { workbenchSelection(query)?.kind == "ew" }
}

/** A workspace changed while its reopen path was being resolved on IO. */
internal fun workspaceTargetAfterLookup(
    currentUrl: String?,
    port: Int,
    currentTarget: String?,
    resolved: String?,
): String? = if (emptyWindowUrl(currentUrl, port) != null) null else currentTarget ?: resolved

/**
 * What to open once a device folder has been granted and synced.
 *
 * The Android picker is `ACTION_OPEN_DOCUMENT_TREE` and returns a directory,
 * never a file, so a workspace on device storage can only ever be reached
 * through the folder holding it. Nothing joined the two: the folder opened as a
 * folder, and the `.code-workspace` sitting in it was reachable only by knowing
 * to find the file in the explorer and press the button on it. That is the gap
 * behind "I cannot find a way to open an existing workspace", and it is the
 * shell's to close, because desktop VS Code offers this from code the browser
 * workbench does not carry (no `contains a workspace file` string exists in the
 * bundle).
 *
 * Exactly one, at the top level, or nothing. Two is a guess, and guessing wrong
 * is worse than opening the folder the user actually chose, from which either is
 * one tap away. A directory that merely ends in `.code-workspace` is excluded
 * because the workbench answers a workspace it cannot read with an empty window
 * and no message, which is the failure this whole change exists to remove.
 */
internal fun folderOpenTarget(
    folderPath: String,
    names: List<String>,
    isFile: (String) -> Boolean = { File(it).isFile },
): String =
    names.filter { it.endsWith(WORKSPACE_FILE_SUFFIX) }
        .map { "$folderPath${File.separator}$it" }
        .filter(isFile)
        .singleOrNull()
        ?: folderPath

/**
 * Where to send the workbench for a device folder that has just been synced: [target],
 * the copy at [mirrorPath] or a workspace file in it, spelled through [namedPath] or
 * left on the copy's hash path.
 *
 * The workbench names a folder after the last segment of its path, so the named path,
 * `saf-mirrors/by-name/<hash>/<name>` (`SafStorageManager.namedPathFor`), is what shows
 * the folder's own name in place of the hash. But it keeps open editors, unsaved changes
 * and terminals per path, so moving a folder from one spelling to the other leaves all
 * of that behind under the first, where nothing would bring it back. Hence three rules,
 * in order:
 *
 * - A folder the page already has open keeps the spelling it is open under. Picking it
 *   again is how fresh content is pulled down, and it must come back with its editors.
 * - Otherwise the named path is used when [byName]: the copy was made by this open, or a
 *   page has opened it by name before, so its state is there or nowhere.
 * - Otherwise the hash path: a copy older than named paths, whose state may be under its
 *   hash. The bundled extension offers the move from there, where it can see whether
 *   anything would be left behind.
 *
 * [openNow] is the folder the page has open, as the page spells it.
 */
internal fun deviceFolderTarget(
    target: String,
    mirrorPath: String,
    namedPath: String?,
    byName: Boolean,
    openNow: String?,
): String {
    val mirror = File(mirrorPath)
    val onScreen = openNow?.let { open ->
        if (open == mirrorPath || open.startsWith(mirrorPath + File.separator)) {
            mirrorPath
        } else {
            SafStorageManager.namedRootOf(open, mirror.parent.orEmpty())
                ?.takeIf { (_, hash) -> hash == mirror.name }?.first
        }
    }
    val root = onScreen ?: namedPath?.takeIf { byName } ?: mirrorPath
    return root + target.removePrefix(mirrorPath)
}

/**
 * Whether a folder switch that failed should leave the previous folder watched.
 *
 * The watcher is stopped before every sync, so something has to decide what a
 * failure leaves behind, and the two cases pull opposite ways. When the sync was
 * writing into the folder that was being watched (reopening the folder already
 * open), that mirror is now part-written, and a watcher over it would push the
 * half onto the user's own documents. When it was writing into a different one,
 * the watched folder was never touched, it is still the folder on screen, and
 * leaving it unwatched means the user keeps editing it with write-back silently
 * off.
 *
 * [mirrorUntouched] is the third case, and it lifts the first rule: a sync refused as busy
 * stopped before it wrote anything, so even the folder being reopened is as the watcher
 * left it and the half-written mirror the rule guards against does not exist.
 *
 * Comparing the tree URIs as text because that is what a JVM test can supply:
 * `android.net.Uri` is abstract with a private constructor, so it can be neither
 * built nor mocked outside a device.
 */
internal fun shouldRestorePreviousWatcher(
    previousUri: String?,
    failedUri: String,
    mirrorUntouched: Boolean = false,
): Boolean =
    previousUri != null && (mirrorUntouched || previousUri != failedUri)

/**
 * Whether an adoption that waited its turn behind another open should be skipped.
 *
 * Opens are serialised, and an adoption is a reply to a page load rather than a
 * request: it exists to put the watcher on the folder the page is showing. By
 * the time its turn comes the page may be elsewhere, because the open ahead of
 * it navigated (a picked folder always does) or because the workbench moved on
 * again, and syncing the folder it was queued for would then take the only
 * watcher off the folder on screen, the state adoption exists to end. The open
 * ahead may also have watched this same folder already, and a second sync under
 * a fresh stop would only re-copy what is there.
 *
 * A requested open (`navigate` true) is never stale: it navigates to its folder
 * when it finishes, so the page is on it by construction, and reopening the
 * folder already open is how a user pulls down fresh content.
 *
 * The captured path already resolved to this mirror on Dispatchers.IO. Compare
 * that same spelling after waiting, so aliases retain their resolved owner and
 * no filesystem lookup is needed on the main thread.
 */
internal fun adoptionIsStale(
    navigate: Boolean,
    watchedMirror: String?,
    openPath: String?,
    adoptedPath: String?,
    mirror: String,
): Boolean = !navigate && (watchedMirror == mirror || openPath == null || openPath != adoptedPath)

/**
 * The remembered workspace folder, or null when reopening it is not safe.
 *
 * A function rather than a branch inside the Activity, for the reason
 * [shouldRestorePreviousWatcher] is one: `File`, `Uri` and a `Context` cannot be
 * had in a plain JVM test, and what is decided here is a rule over one string.
 *
 * A path that is not under [mirrorsRoot] is an ordinary project folder and needs
 * no grant, so the absence of one must not refuse it; that asymmetry is the
 * whole reason the mirror test is asked first and separately.
 */
internal fun rememberedFolderToReopen(
    remembered: String?,
    mirrorsRoot: String,
    exists: (String) -> Boolean,
    mirrorIsGranted: (String) -> Boolean,
): String? {
    // Absolute, before anything is asked of the filesystem. `File("")` and every
    // relative path resolve against the process working directory, and
    // `File("").exists()` answers true, so a record holding anything but a path
    // would be reopened as whatever that directory happens to be and then
    // published as a served resource root. Every value written here is absolute;
    // anything else is a sentinel or damage, and both mean the default.
    val path = remembered?.takeIf { it.startsWith("/") }?.takeIf(exists) ?: return null
    SafStorageManager.mirrorNameFor(path, mirrorsRoot) ?: return path
    return path.takeIf { mirrorIsGranted(it) }
}

/**
 * The script the resume health check evaluates in the page.
 *
 * Lifted out of `MainActivity.checkConnectionHealth` so that what it probes can
 * be asserted without an Activity. That matters more than it looks: the previous
 * version of this script also searched every `.monaco-dialog-box` for the words
 * "reconnect" and "lost", which reads as a check on VS Code's reconnection
 * dialog and is really a check on the display language. Under a language pack
 * the substrings are translated, the match never fires, and a broken connection
 * is reported healthy, with no error, and only for the users not reading
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

/**
 * Applies the size chosen with **VSCodroid: UI Scale** to every workbench page,
 * as the page is parsed.
 *
 * Nothing in the workbench scales its own chrome: `editor.fontSize` and the
 * terminal's font size reach those two only, `window.zoomLevel` does nothing in
 * the web workbench, and Android's font size never arrives because `textZoom` is
 * pinned. The page's viewport does reach it. With its three scale keys set to the
 * size, the WebView lays the page out narrower by that factor and draws it larger
 * to fill the view, and taps, the caret, menus and the keyboard follow, measured
 * on an API 33 emulator at 125%. CSS `zoom` on the root is no answer: the
 * workbench still sized itself to the window, its right edge went off screen and
 * a menu opened away from the finger.
 *
 * The element is rewritten as the parser inserts it, before the first layout, so
 * a page is never laid out at 100% first; from `onPageFinished` the change would
 * race the workbench's first layout. The relay's `getUiScale` and `setUiScale`
 * read and change it live through the hook the script leaves, and the size is kept
 * in the page's localStorage, which holds it across reloads and restarts. A port
 * move loses it with everything else the page keeps by origin.
 *
 * Registered for every origin because a rule cannot leave the port open (one
 * without a port names port 80 only) and the server's port is not known when the
 * WebView is set up. The script returns at once anywhere but the top frame at `/`
 * on the loopback address.
 */
internal fun addUiScaleScript(webView: WebView) {
    if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
        try {
            WebViewCompat.addDocumentStartJavaScript(webView, uiScaleScript(), setOf("*"))
        } catch (e: RuntimeException) {
            // The editor matters more than its size: the page stays at 100%, and
            // with no hook in it the command says the WebView needs updating.
            Logger.w("MainActivity", "Could not add the UI scale script: ${e.message}")
        }
    } else {
        Logger.w("MainActivity", "This WebView cannot run a script at document start, so the UI scale stays at 100%")
    }
}

/** The document-start script [addUiScaleScript] adds. */
internal fun uiScaleScript(): String =
    """
    (function() {
        // Every frame of every origin starts with this, since the rule it is
        // registered under cannot name the server's port. Only the workbench
        // page is scaled: the top frame at / on http://127.0.0.1:<port>.
        if (window.top !== window || location.pathname !== '/' ||
            location.protocol !== 'http:' || location.hostname !== '127.0.0.1' || !location.port) return;
        var KEY = 'vscodroid.uiScale';
        var SCALES = [1, 1.1, 1.25, 1.5];
        // The narrowest the page may get at a size offered, in CSS px. At 329, a
        // 411 dp phone at 125%, the editor beside an open side bar was 111 wide.
        var MIN_WIDTH = 320;
        var current = 1;

        // The sizes that keep the page at least MIN_WIDTH wide on this screen,
        // by its narrower side, since turning the phone does not reload the page.
        function offered() {
            var narrow = Math.min(screen.width, screen.height);
            return SCALES.filter(function(s) { return s === 1 || narrow / s >= MIN_WIDTH; });
        }

        // Sets the scale keys of the page's own viewport element to s and keeps
        // every other key, viewport-fit included. False while there is none.
        function apply(s) {
            var meta = document.querySelector('meta[name="viewport"]');
            if (!meta) return false;
            var keep = (meta.getAttribute('content') || '').split(',').map(function(p) {
                return p.trim();
            }).filter(function(p) {
                return p && !/^(initial|minimum|maximum)-scale\s*=/.test(p);
            });
            meta.setAttribute('content', keep.concat(
                ['initial-scale=' + s, 'minimum-scale=' + s, 'maximum-scale=' + s]).join(', '));
            return true;
        }

        // Whether the page is drawn at s and laid out no wider than what is on
        // screen. That layout is a WebView behaviour, not a standard: with wide
        // viewport mode off, as this app leaves it, a device-width page is laid
        // out at the view's width divided by its initial scale. Without that the
        // page would stay as wide as the view and be drawn larger than it, its
        // right edge off the screen.
        function tookEffect(s) {
            var vv = window.visualViewport;
            return !!vv && Math.abs(vv.scale - s) < 0.01 &&
                document.documentElement.clientWidth <= vv.width + 1;
        }

        // Judged two frames on, once the page has been laid out and drawn at s,
        // and put back to 100% if s did not take effect. A size chosen in the
        // meantime is left alone. Answers the size in force.
        function settle(s, done) {
            requestAnimationFrame(function() {
                requestAnimationFrame(function() {
                    if (s === current && s !== 1 && !tookEffect(s)) {
                        console.warn('[VSCodroid] UI scale ' + s + ' did not take effect, back to 100%');
                        localStorage.removeItem(KEY);
                        current = 1;
                        apply(1);
                    }
                    done(current);
                });
            });
        }

        // What the relay's getUiScale and setUiScale answer with.
        window.__vscodroidUiScale = {
            state: function() { return { scale: current, choices: offered() }; },
            set: function(s, done) {
                if (offered().indexOf(s) < 0) { done(current); return; }
                if (s === 1) localStorage.removeItem(KEY);
                else localStorage.setItem(KEY, String(s));
                current = s;
                apply(s);
                settle(s, done);
            }
        };

        // The size chosen, or the largest under it that this screen still allows.
        var chosen = Number(localStorage.getItem(KEY));
        current = offered().filter(function(s) { return s <= chosen; }).pop() || 1;
        if (current === 1) return;
        // As the parser inserts the element, which is before the first layout.
        var watch = new MutationObserver(function() {
            if (!apply(current)) return;
            watch.disconnect();
            settle(current, function() {});
        });
        watch.observe(document, { childList: true, subtree: true });
        // A page without the element is not watched for the rest of its life.
        document.addEventListener('DOMContentLoaded', function() {
            watch.disconnect();
            if (!document.querySelector('meta[name="viewport"]')) current = 1;
        });
    })();
    """.trimIndent()

/**
 * The newest [keep] lines of a log, with runs of the same line collapsed to one.
 *
 * Top-level and internal because the page that uses it is a private method on an
 * Activity and a JVM test cannot reach one, which is the reason [bindDecision]
 * and [escapeHtml] live out here too: the decision is the part worth pinning.
 *
 * Collapsing is what the server-gave-up page needs and the ordinary tail is not.
 * That page is reached only after six attempts to start the server, so a failure
 * that is the same every time has written its line six times by then. Measured on
 * an emulator with the Node binary removed: the block filled with six identical
 * copies of one sentence and showed nothing else.
 *
 * Consecutive runs only, never global. Two attempts that got further print lines
 * differing in the pid, so nothing merges them, and a line repeating LATER in the
 * run is a thing happening again, which is exactly what a reader needs to see.
 *
 * Collapsed first and cut second, which is the order that matters. Cutting to
 * [keep] and then collapsing leaves one line on the failure this exists for,
 * throwing away the attempt that preceded it.
 */
internal fun collapseRuns(lines: List<String>, keep: Int): List<String> =
    lines.filterIndexed { i, line -> i == 0 || line != lines[i - 1] }.takeLast(keep)

/**
 * The five characters that change the meaning of surrounding HTML.
 *
 * The strings this escapes come from this app's own resources, so nothing
 * hostile reaches it today. It is here because the page is built by string
 * interpolation, and the next person to interpolate something into it may be
 * carrying a filename or an exception message.
 */
internal fun escapeHtml(s: String): String = s
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
    .replace("'", "&#39;")

/**
 * The page [MainActivity] shows while the server starts, on [background], with
 * text that reads on it: light grey on a dark colour, dark grey on a light one.
 * The background is the editor colour the workbench last showed, so each text
 * clears 4.5:1 on that of every theme the server ships, and on any colour at most
 * as luminous as #3f3f3f or at least as luminous as #dcdcdc; a third-party
 * theme's colour between those can measure less. The #888 the line had on
 * #1e1e1e measures 4.19:1 on Monokai's #272822. Top-level for the reason
 * [escapeHtml] is.
 */
internal fun loadingPageHtml(background: Int, message: String): String {
    val light = isLightColor(background)
    return """<html><head><meta name="viewport" content="width=device-width, initial-scale=1.0, viewport-fit=cover"></head>
           <body style="background:${"#%06x".format(background and 0xFFFFFF)};color:${if (light) "#616161" else "#aaa"};font-family:sans-serif;
           display:flex;align-items:center;justify-content:center;height:100vh;margin:0;">
           <div style="text-align:center"><h2 style="color:${if (light) "#333" else "#ccc"};">VSCodroid</h2>
           <p>${escapeHtml(message)}</p></div></body></html>"""
}

/**
 * Markup made safe for the `data:` URL that `WebView.loadData` splices it into.
 *
 * `loadData` does not load a document, it builds a URL out of one, and for an app
 * targeting Q or later the platform stops escaping the content: the first `#`
 * ends the URL and every byte after it becomes the fragment. The loading page
 * opens with `background:#1e1e1e`, so what the WebView actually parsed was an
 * unterminated `<body` start tag, which is dropped at end of input. The document
 * was empty and the view painted its own default white for the whole of the
 * server start, which is the first screen of every cold start and the screen the
 * Retry link puts back.
 *
 * `%` is escaped before `#`, or the `%23` written here would be escaped a second
 * time into `%2523` and reach the page as literal text.
 *
 * Not needed by [MainActivity.showServerGaveUp]: `loadDataWithBaseURL` with a
 * base URL that is not a data URL loads the content as a plain string, which is
 * why that page renders today while this one does not.
 */
internal fun dataUrlSafe(html: String): String = html
    .replace("%", "%25")
    .replace("#", "%23")

/**
 * Whether the WebView's HTTP cache may still hold what an earlier build's server
 * tree served, given the build that last dropped it.
 *
 * Any change of build counts, a versionCode alone included, because that is what
 * makes setup extract the tree again.
 */
internal fun webViewCacheIsStale(clearedFor: String?, build: String): Boolean = clearedFor != build

/**
 * A folder name read straight off a SAF tree URI, for the moment before the
 * provider has been asked for the real one.
 *
 * Never the final name: [SafStorageManager.getDisplayName] replaces it as soon as
 * the query answers. It exists because that query is a cross-process round trip
 * and a network- or MTP-backed provider can make it a long one, and the sync
 * dialog has to be on screen before it, not after.
 *
 * The last path segment of a tree URI arrives decoded, as `primary:Documents/Work`,
 * so the tail after the last separator is the folder the user picked. A root
 * (`primary:`) has no tail, and there the whole segment is closer to a name than
 * an empty pair of quotes is.
 */
internal fun treeUriLabel(lastPathSegment: String?): String {
    val segment = lastPathSegment.orEmpty()
    val tail = segment.substringAfterLast('/').substringAfterLast(':')
    return tail.ifBlank { segment }
}

/**
 * The record of a navigation this app started, good for one answer.
 *
 * [VSCodroidWebChromeClient.onJsBeforeUnload] answers a `beforeunload` for the
 * user only when this says the navigation is the app's own. A `beforeunload`
 * reaches that callback only when the workbench vetoed leaving, and it vetoes
 * only while it holds work it has not yet written a backup of, so a wrong "yes"
 * here throws that work away with nothing on screen.
 *
 * One answer per mark, because the callback is raised once for the load
 * [mark] precedes: a mark left standing for the rest of a window answered for
 * whatever the page decided to do next, which is the case the veto exists for.
 * The window survives only as the backstop for a mark whose load never produced
 * a callback at all.
 *
 * Its own class because [MainActivity] cannot be constructed in a unit test,
 * and a rule whose failure costs unsaved work should not be the one part with
 * no check on it.
 */
internal class AppNavigationMark(private val windowMs: Long) {

    @Volatile
    private var markedAt = 0L

    /** Says the navigation about to be started is this app's own. */
    fun mark(now: Long) {
        markedAt = now
    }

    fun clear() {
        markedAt = 0L
    }

    /** True at most once per [mark], and only inside the window. */
    fun consume(now: Long): Boolean {
        val marked = markedAt
        if (marked == 0L || now - marked >= windowMs) return false
        markedAt = 0L
        return true
    }
}

/**
 * What the bootstrap script answered for the document that requested it: true when sign-in
 * registration is working, false when the page is the workbench but it is not, and null
 * when the answer is not for this document at all (no marker, another generation, a page
 * that is not the top-level workbench).
 */
internal fun authBootstrapVerdict(result: String?, generation: Long): Boolean? = try {
    val marker = JSONArray(result ?: "null")
    if (marker.length() == 2 && marker.opt(0) is Number && marker.getLong(0) == generation &&
        marker.getDouble(0) == generation.toDouble() && marker.opt(1) is Boolean
    ) {
        marker.getBoolean(1)
    } else {
        null
    }
} catch (_: JSONException) {
    null
}

/**
 * Whether a multi-root workspace spanning [mirrorCount] distinct device-folder
 * copies should be announced for [generation], recording the generation so the
 * notice appears at most once per workbench page load.
 */
internal fun shouldAnnounceMultipleDeviceFolders(
    mirrorCount: Int,
    generation: Long,
    announcedGenerations: MutableSet<Long>,
): Boolean {
    if (mirrorCount < 2 || generation <= 0L) return false
    return announcedGenerations.add(generation)
}
