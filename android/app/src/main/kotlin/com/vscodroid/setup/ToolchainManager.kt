package com.vscodroid.setup

import android.app.Activity
import android.content.Context
import android.os.StatFs
import androidx.annotation.StringRes
import com.vscodroid.BuildConfig
import com.vscodroid.R
import android.system.Os
import com.google.android.play.core.assetpacks.AssetPackManagerFactory
import com.google.android.play.core.assetpacks.AssetPackState
import com.google.android.play.core.assetpacks.AssetPackStateUpdateListener
import com.google.android.play.core.assetpacks.model.AssetPackErrorCode
import com.google.android.play.core.assetpacks.model.AssetPackStatus
import com.vscodroid.util.Environment
import com.vscodroid.util.Logger
import com.vscodroid.util.StorageManager
import com.vscodroid.webview.redactToken
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import android.annotation.SuppressLint

/**
 * Manages on-demand toolchain installation via Play Asset Delivery or HTTP fallback.
 *
 * Play Store install flow:
 *   1. fetch() → AssetPackManager downloads the pack
 *   2. On COMPLETED → installFromDirectory() copies files to filesDir (off main thread)
 *   3. chmod +x on binaries, create symlinks in usr/bin/
 *   4. Write toolchain-env.sh for bash, persist state to toolchains.json
 *   5. removePack() to free the duplicate asset pack storage
 *
 * HTTP fallback (sideloaded/debug builds):
 *   1. Download ZIP from GitHub Releases via HttpURLConnection
 *   2. Extract ZIP → installFromDirectory() (shared with Play path)
 *   3. Same chmod/symlink/persist steps as above
 */
class ToolchainManager(private val context: Context) {

    private val tag = "ToolchainManager"
    private val assetPackManager = AssetPackManagerFactory.getInstance(context)
    private val stateFile = File(context.filesDir, "home/.vscodroid/toolchains.json")
    private val envFile = File(context.filesDir, "home/.vscodroid/toolchain-env.sh")

    /**
     * The two derived files that make a toolchain command reachable from
     * something that is not bash, both named from [Environment] so the writer
     * here and the reader there cannot drift apart.
     */
    private val execTable = File(Environment.getExecTablePath(context))
    private val tcBinDir = File(Environment.getTrampolineBinDir(context))

    private val filesDir = context.filesDir.absolutePath
    private val homeDir = "$filesDir/home"

    /**
     * Single-thread executor for heavy file I/O (copy, chmod, symlink).
     *
     * Built by [toolchainIoExecutor] rather than by `newSingleThreadExecutor`,
     * whose thread is a core thread and is therefore never reclaimed. Nothing
     * shuts this down and nothing can: nine call sites each build their own
     * [ToolchainManager] and none of them owns the object long enough to know
     * when the work is finished. `SplashActivity.onCreate` alone builds two and
     * submits on both, on every launch, into a process kept alive by the
     * foreground service, so the parked threads accumulated for as long as the
     * process lived.
     */
    private val ioExecutor = toolchainIoExecutor()

    /**
     * Progress and outcome for one pack.
     *
     * The first three arguments are the pack name, an `AssetPackStatus` value,
     * and how far along it is as a percentage.
     * The fourth argument is why it failed, and it is null for every status that
     * is not a failure. It exists because "Failed" on its own is the same word
     * for a full disk, a dropped connection and a release that never published
     * the file, and the user is the one person who can act on the difference:
     * free space, move to wifi, or wait for a release. The reason was in logcat
     * and nowhere else.
     *
     * Reached through [fail] and [report] rather than invoked directly, so a new
     * failure site cannot forget to say why: [fail] has no overload without one.
     */
    var onStateChange: ((String, Int, Int, ToolchainFailure?) -> Unit)? = null

    /** Progress or success. Never a failure: those go through [fail]. */
    private fun report(packName: String, status: Int, percent: Int) {
        onStateChange?.invoke(packName, status, percent, null)
    }

    /**
     * A failure, and why.
     *
     * There is no overload without a reason, which is the point: every failure
     * site has to name one, and a new one cannot be added without doing the
     * same. Counting them here would only rot; the compiler does the counting.
     */
    private fun fail(packName: String, why: ToolchainFailure) {
        // Logged before the callback, and unconditionally, because the callback is
        // optional and this used to be the whole method. A manager built without an
        // `onStateChange` reported its failures into a null and left nothing at all,
        // not even a line: `reconcileDeliveredPacks` runs on exactly such an instance
        // at launch, so a manifest it could not read or a state file it could not
        // write vanished without trace. Every screen that installs does set the
        // callback, which is why this was invisible.
        // Redacted because one caller's pack name is a string a page chose:
        // [install] reports INTERNAL for a name the registry does not know, and a
        // name the registry does not know is arbitrary text. The bridge redacts it
        // for its own line and hands the raw value on, so the asymmetry is paid
        // here. A registry pack name carries nothing for this to remove.
        Logger.e(tag, "Toolchain ${redactToken(packName)} failed: $why")
        onStateChange?.invoke(packName, AssetPackStatus.FAILED, 0, why)
    }

    // -- HTTP fallback state --

    /**
     * One download's cancellation flag.
     *
     * There was a single `@Volatile httpCancelled` here for every pack, and a
     * shared flag cannot express "cancel this one". Two ways out of it were
     * reachable from the toolchain screen. Cancelling Go while Ruby sat queued
     * behind it aborted Ruby too, at its first check, before it had transferred
     * a byte -- the executor serialises the tasks, not the flag they read.
     * Starting a third install while the first was mid-transfer ran the reset on
     * the *calling* thread and cleared a cancellation the running download had
     * not looked at yet, so the pack the user cancelled carried on downloading.
     *
     * A flag per request rather than per pack name: the token is created when
     * the download is asked for and handed to the task that performs it, so
     * whatever happens to the map afterwards, that task keeps reading the flag
     * its own caller can set.
     */
    private class HttpDownload {
        @Volatile var cancelled = false

        /**
         * How far the transfer has got, kept on the token as well as reported.
         *
         * The report goes to `onStateChange`, which belongs to the manager that
         * began the download and to nothing else. A screen rebuilt while the
         * transfer runs holds a different manager and hears none of it, so
         * without a figure the process itself can be asked for, the only card
         * such a screen could draw would be a progress bar frozen at zero.
         * Volatile because the download thread writes it and the main thread
         * reads it through [packsDownloading].
         */
        @Volatile var percent = 0
    }

    /**
     * A request the release cannot answer, now or on a third attempt.
     *
     * Retryability used to be decided by looking for `"404"` as a substring of
     * the exception message, which was fine while every message was written
     * with that in mind and stopped being fine as soon as the messages carried
     * a URL. Three of the retryable ones do -- a redirect with no `Location`,
     * a non-200 status, and running out of redirect hops -- and after GitHub's
     * redirect the URL is a signed `objects.githubusercontent.com` link full of
     * hex. A 64-character hex string contains `404` about **1.5%** of the time
     * (measured over 400k samples; analytically 62/4096), and such a URL has
     * several hex components. So a small but real slice of transient failures
     * was being classified permanent and given up on after one attempt.
     *
     * A type instead of a spelling. The exception says what it is rather than
     * hoping its prose reads a particular way, and the cost of getting it wrong
     * is one-directional either way: this only ever ends an install sooner than
     * it needed to, never installs something it should not have.
     */
    private class MissingFromRelease(message: String) : IOException(message)

    /**
     * A `Range` the origin will not serve: HTTP 416.
     *
     * A type rather than a spelling, for the reason [MissingFromRelease] is one.
     * The recovery it names is the opposite of a retry: every attempt inside
     * [retrying] re-reads the same partial file and asks from the same offset,
     * so a refusal of that offset is refused identically three times and the
     * user is told the network failed. What has to change between attempts is
     * the file, not the connection, and only [downloadFile] knows which file
     * that is.
     */
    private class RangeRefused(message: String) : IOException(message)

    companion object {
        private const val HTTP_TIMEOUT_MS = 30_000     // 30s connect + read timeout
        private const val MAX_RETRIES = 2
        private const val DOWNLOAD_BUFFER_SIZE = 8192
        private const val MAX_REDIRECTS = 5

        /**
         * Ceiling on the digest manifest read into memory.
         *
         * Three lines of `sha256sum` output today, so this is roughly five
         * hundred times what it needs. It is a ceiling rather than a budget: an
         * origin that answers this URL with something enormous should cost a
         * refusal, not the process.
         */
        private const val MANIFEST_MAX_CHARS = 64 * 1024

        /**
         * The installer names that mean Play delivered this app, and can
         * therefore deliver its asset packs.
         *
         * `com.google.android.feedback` is the Play Store's own legacy package
         * name, and it is still the installer of record for installs made by
         * older versions of it. Left out, such a device took the HTTP path: a
         * Play install downloading native binaries from a GitHub release, which
         * is the one thing the Play path exists to avoid.
         */
        private val PLAY_INSTALLERS = setOf(
            "com.android.vending",
            "com.google.android.feedback",
        )

        /**
         * Records that a toolchain's binaries have had their execute bit checked.
         * Kept in the toolchain's own state entry rather than as one global flag,
         * so a toolchain installed later is marked without being walked, and a
         * repair that fails partway is retried only for what it did not finish.
         */
        private const val KEY_EXEC_REPAIRED = "execBitsChecked"

        /**
         * Serialises every read-modify-write cycle on `toolchains.json` and the
         * env file generated from it.
         *
         * Deliberately on the companion rather than on the instance, because an
         * instance field would serialise nothing here. Nine call sites each
         * build their own [ToolchainManager] -- six in `SplashActivity`, plus
         * `ToolchainActivity`, `AndroidBridge` and `Environment` -- each with
         * its own single-thread executor, so the executors serialise their own
         * work and nothing serialises theirs against each other. Two of them
         * overlapping on one file is not hypothetical: the repair pass fires
         * from `SplashActivity` on every launch and runs `readState` ->
         * mutate -> `writeState`, while an install queued from the same screen
         * runs the same cycle on the same file. Whichever writes last wins with
         * a copy of the array it read before the other one changed it, and the
         * loser's toolchain is simply not in the file any more.
         *
         * Ceiling: one process. The file lives in `filesDir`, so any other
         * process that opened it -- the Node server, a terminal -- is outside
         * this lock entirely. Nothing does today, and the atomic rename below is
         * what keeps such a reader from ever seeing a half-written file even so.
         */
        private val stateLock = Any()

        /**
         * Pack names whose copy into the shared `usr/` tree is running right now.
         *
         * On the companion for the same reason [stateLock] is: every call site
         * builds its own [ToolchainManager], and `ioExecutor` is an instance
         * field, so the executors serialise each manager's own work and nothing
         * serialises theirs against each other. Two managers installing one pack
         * is reachable without trying: `ToolchainActivity` declares no
         * `configChanges`, so a rotation mid-install recreates it with a second
         * manager, and the card it rebuilds knows nothing of the install still
         * running, so it offers Install again.
         *
         * What that costs is space and bookkeeping rather than the bytes
         * themselves. Both copies carry identical source bytes, so the
         * overlapping writes do not corrupt anything. But neither install's
         * pre-flight reserves for the other, so a device whose free space sits
         * between one install's reservation and two installs' real peak passes
         * both checks and then hits ENOSPC partway through the second copy. That
         * leaves a truncated tree under a record the first install has already
         * written as installed, and `repairInstalledToolchains` does not
         * re-examine a pack the record calls installed, so nothing ever puts it
         * right.
         *
         * The second install declines rather than waits, which is the call
         * `SafSyncEngine.documentWritesInFlight` already makes for this shape:
         * waiting on an unbounded filesystem operation turns a race into a stall
         * behind whatever the user is looking at, and here there is nothing to
         * wait for anyway. The pack is being installed; the caller that declines
         * has nothing to add to that.
         *
         * Ceiling: one process, exactly as above. Another process copying into
         * `usr/` is outside this entirely, and nothing does today.
         */
        private val installsInFlight = ConcurrentHashMap.newKeySet<String>()

        /**
         * The token for each pack with a download outstanding, so [cancel] can
         * find it by name.
         *
         * Entries are put in on the calling thread and removed by the task
         * itself, which is what lets a cancellation arriving while the pack is
         * still queued be seen once it starts.
         *
         * On the companion for the reason [installsInFlight] is, and it is the
         * same rotation that makes it reachable. `ToolchainActivity` declares no
         * `configChanges`, so turning the phone mid-download destroys the
         * Activity and builds a second manager. As an instance field this map
         * went with the first one: the transfer carried on, and nothing left
         * alive could reach its token. Cancelling was then impossible from
         * anywhere -- not from the rebuilt screen, and not from `AndroidBridge`,
         * whose own lazily built manager is a third instance again -- so the
         * only way to stop a download on mobile data was to force-stop the app.
         *
         * It is also what lets [downloadViaHttp] recognise a pack that is
         * already being fetched. [installsInFlight] cannot do that job: it is
         * claimed only once the archive has been downloaded and expanded, so it
         * reads free for the whole of the transfer, and a tap on the Install
         * button the rebuilt card offers started the download again from the
         * first byte.
         *
         * Ceiling: keyed by pack name. Two requests for one pack can still
         * overlap in the window between the decline check and the publish below,
         * which leaves the earlier task holding a token this map no longer points
         * at; the earlier task still terminates on its own, and the two-argument
         * `remove` in the download's `finally` keeps the later one cancellable.
         */
        private val httpDownloads = ConcurrentHashMap<String, HttpDownload>()

        /**
         * Every pack with an HTTP transfer outstanding right now, and how far
         * each has got.
         *
         * Asked of the process rather than of one manager, because progress is
         * reported only to the manager that began the download. Two readers need
         * the answer and neither holds that manager. The toolchain screen rebuilds
         * its cards from nothing on every `onCreate` -- a rotation, or simply
         * opening it while the first-run queue is still working -- and a card with
         * no report to go on drew Install for a pack that was already downloading:
         * no progress, and no Cancel, which is the one button that stops a 56 MB
         * transfer on mobile data. [com.vscodroid.util.StorageManager.clearCaches]
         * needs it for the opposite reason: to leave a running download's staging
         * directory alone while it clears the abandoned ones.
         *
         * The Play path is not here because it is asked for instead of tracked:
         * [readPlayDownloads] puts the same question to Play Core, and the
         * toolchain screen asks it in `onStart` beside this map. This paragraph
         * used to justify the omission by asserting that Play Core re-delivers
         * state for a pack it is still fetching once [registerListener] runs.
         * Nothing here ever measured that, and what it costs when it is wrong is
         * the card offering Install for a pack already downloading: no progress,
         * and no Cancel.
         */
        internal fun packsDownloading(): Map<String, Int> =
            httpDownloads.mapValues { (_, download) -> download.percent }
    }

    private val listener = AssetPackStateUpdateListener { state ->
        handleStateUpdate(state)
    }

    /**
     * Whether Play installed this app, once something has asked.
     *
     * Volatile because the callers are not on one thread: the activities reach
     * [install] on the main thread, `AndroidBridge` reaches it from the
     * WebView's, and [reconcileDeliveredPacks] asks from [ioExecutor]. Two
     * threads racing here cost one extra binder call and agree on the answer,
     * so nothing stronger is needed. See [shouldUseHttpFallback].
     */
    @Volatile
    private var installSourceIsPlay: Boolean? = null

    // -- Lifecycle --

    /**
     * Whether this instance's [listener] is currently registered with Play Core.
     *
     * Two callers reach [registerListener] on one instance without knowing about
     * each other: the activities call it from `onStart`, and [install] calls it
     * again before every `fetch`. So a registration and its removal are made by
     * different code for different reasons, and whether they balance depends on
     * what registering twice means.
     *
     * Play Core's own registry is a `Set` -- `AssetPackManager.registerListener`
     * delegates to a class holding `protected final java.util.Set b`, verified
     * by decompiling asset-delivery 2.2.2 rather than assumed -- so registering
     * the same object twice is already one entry, and the duplicate deliveries
     * that would otherwise follow do not happen. This flag is therefore not
     * fixing a live double-delivery bug, and saying so matters: an earlier
     * draft of this comment claimed it was.
     *
     * It is kept because that behaviour is an implementation detail of a
     * minified third-party class, promised nowhere in the API. Making the
     * pairing explicit here means [unregisterListener] can be called from a
     * terminal state without the caller knowing whether [install] took the Play
     * branch or the HTTP one -- which never registers -- and our correctness
     * does not rest on a `Set` we had to decompile to find.
     *
     * Guarded rather than volatile because check-and-act is the whole point, and
     * the callers are not on one thread: the activities are on the main thread,
     * while the bridge reaches [install] from the WebView's own thread.
     */
    private var listenerRegistered = false

    @Synchronized
    fun registerListener() {
        if (listenerRegistered) return
        assetPackManager.registerListener(listener)
        listenerRegistered = true
    }

    @Synchronized
    fun unregisterListener() {
        if (!listenerRegistered) return
        assetPackManager.unregisterListener(listener)
        listenerRegistered = false
    }

    // -- Query state --

    /**
     * Asks Play what it is already doing with the packs this build offers, and
     * hands each answer to [onState] as a pack name, an `AssetPackStatus` and a
     * percentage.
     *
     * A screen built while a Play download is running holds no reports at all,
     * and [packsDownloading] cannot help it: that map covers the HTTP path only.
     * The gap was covered by an assertion instead, that Play Core re-delivers
     * state for a pack it is still fetching once a listener registers. Nothing
     * here has ever measured that, this file said so in one place and asserted
     * the opposite in another, and what it costs when it is wrong is the card
     * offering Install for a pack already downloading: no progress, and no
     * Cancel, which is the only control in the app that stops a 55 MB metered
     * transfer. This is the question with an answer either way.
     *
     * Every pack is reported, including settled ones; deciding which of them a
     * card should be redrawn from belongs to the screen, which is also the only
     * party that knows whether it has something better already. The listeners
     * are Play Core's, so they arrive on the main thread.
     *
     * Play-only, and silent on the HTTP path: `getPackStates` on an install Play
     * does not recognise fails rather than answering an empty map, which is not
     * an event worth telling a user about.
     */
    fun readPlayDownloads(onState: (packName: String, status: Int, percent: Int) -> Unit) {
        try {
            assetPackManager.getPackStates(ToolchainRegistry.available.map { it.packName })
                .addOnSuccessListener { states ->
                    for (state in states.packStates().values) {
                        onState(
                            state.name(),
                            state.status(),
                            packDownloadPercent(
                                state.bytesDownloaded(),
                                state.totalBytesToDownload(),
                            ),
                        )
                    }
                }
                .addOnFailureListener { e ->
                    Logger.d(tag, "Play has nothing to say about the toolchain packs: ${e.message}")
                }
        } catch (e: Exception) {
            Logger.d(tag, "Could not ask Play about the toolchain packs: ${e.message}")
        }
    }

    fun getInstalledToolchains(): List<String> {
        val state = readState()
        val result = mutableListOf<String>()
        for (i in 0 until state.length()) {
            val obj = state.optJSONObject(i) ?: continue
            result.add(obj.optString("name", ""))
        }
        return result.filter { it.isNotEmpty() }
    }

    // -- Install --

    fun install(packName: String) {
        val info = ToolchainRegistry.find(packName)
        if (info == null) {
            // The one line here that fires BECAUSE the string is not a known
            // toolchain, which is to say exactly when it is whatever the caller
            // sent. Logger.e is not gated on a debuggable build.
            Logger.e(tag, "Unknown toolchain: ${redactToken(packName)}")
            fail(packName, ToolchainFailure.INTERNAL)
            return
        }
        Logger.i(tag, "Requesting install of ${info.displayName} (${info.packName})")

        if (shouldUseHttpFallback()) {
            val url = info.downloadUrl
            if (url == null) {
                Logger.e(tag, "No downloadUrl for ${info.packName}: Play Store required")
                fail(info.packName, ToolchainFailure.PLAY_REQUIRED)
                return
            }
            // Both figures, because the two questions differ by a factor of
            // three and one number was answering both. The space gate has to
            // reserve for the unpacked tree; the progress bar divides by what
            // crosses the network, and only when the server sends no
            // Content-Length. Given the unpacked figure, a chunked response left
            // the Java 17 bar topping out near 30% for the whole transfer.
            downloadViaHttp(info.packName, url, info.estimatedSize, info.downloadSize)
        } else {
            // Ensure listener is registered before fetching
            registerListener()
            assetPackManager.fetch(listOf(info.packName))
        }
    }

    fun cancel(packName: String) {
        val info = ToolchainRegistry.find(packName) ?: return
        // Signals only this pack's download to stop (checked every 8KB in the
        // download loop). A pack with nothing outstanding has no token, and
        // cancelling it must not reach a download that is running for another.
        //
        // The map is process-wide, so this reaches the transfer whichever manager
        // began it. That is the point rather than a side effect: a rotation
        // rebuilds the screen with a second manager while the first one's
        // download carries on, and a token only the destroyed manager could see
        // made the transfer uncancellable from anywhere.
        httpDownloads[info.packName]?.cancelled = true
        assetPackManager.cancel(listOf(info.packName))
        // Play's cancel is a request to the download service and does nothing to a
        // pack it has already finished delivering, so without this a cancel could
        // leave a complete pack sitting in `filesDir/assetpacks`. That was merely
        // wasted disk until [reconcileDeliveredPacks] existed; now it is a delivered
        // pack that the next launch would find and install, which is the opposite of
        // what the user just asked for. Releasing it here is what makes "cancelled"
        // and "not delivered" the same state, which is the only reading of Play's
        // records that reconcile can make.
        //
        // Queued on [ioExecutor] rather than run here, because Play's removePack is
        // a real recursive delete of the delivered directory, and the COMPLETED
        // branch copies out of that same directory on this executor. The card goes
        // on offering CANCEL throughout that copy, since handleStateUpdate does not
        // report COMPLETED and the last status the UI holds is TRANSFERRING, so a
        // tap in those seconds deleted the source under the reader and left a part
        // written `usr/` tree behind. A single-thread executor puts the removal
        // behind the copy instead.
        //
        // Bounded to this instance, and that is the whole of it: `ioExecutor` is an
        // instance field, and every construction site makes its own manager, so the
        // ordering holds only between a cancel and a copy running on the SAME manager.
        // All three callers that can reach a live download do hold one: ToolchainActivity
        // and the first-run queue in SplashActivity each keep a manager for the download
        // and the cancel alike, and `AndroidBridge` builds exactly one, lazily, that
        // `installToolchain` and `cancelToolchainInstall` both go through.
        //
        // What stays unordered is a cancel through one manager against a copy running on
        // another's executor, an extension cancelling what the first-run queue started
        // being the reachable case. [installsInFlight] is process-wide and
        // [installFromDirectory] holds a claim on it for the length of the copy, but
        // [releasePack] does not consult it, so nothing puts the removal behind a copy
        // that a different manager began.
        ioExecutor.execute { releasePack(info.packName) }
        Logger.i(tag, "Cancelled download of ${info.packName}")
    }

    /**
     * Shows the Play Store cellular data confirmation dialog for large downloads.
     * Called when AssetPackStatus.REQUIRES_USER_CONFIRMATION is received.
     */
    @Suppress("DEPRECATION")
    fun showConfirmationDialog(activity: Activity) {
        try {
            assetPackManager.showCellularDataConfirmation(activity)
                .addOnSuccessListener {
                    Logger.i(tag, "User confirmed cellular data download")
                }
                .addOnFailureListener { e ->
                    Logger.e(tag, "Cellular data confirmation failed", e)
                }
        } catch (e: Exception) {
            Logger.e(tag, "Failed to show confirmation dialog", e)
        }
    }

    // -- Uninstall (runs on IO thread) --

    fun uninstall(name: String) {
        ioExecutor.execute {
            try {
                uninstallSync(name)
            } catch (e: Exception) {
                // The throwable is kept: nothing that throws on this path builds
                // its message from this argument, they name paths taken from the
                // install record, so the second channel does not repeat what the
                // first one hides.
                Logger.e(tag, "Failed to uninstall ${redactToken(name)}", e)
                // Said to the screen as well, so that every route out of an
                // uninstall reports exactly once. The card takes its Remove
                // button away when the user confirms and puts it back on the
                // first report about the pack; without this line an exception
                // here left it with no button at all until the screen was
                // reopened. UNKNOWN because nothing about the toolchain changed:
                // the card falls back to what the install record says, which is
                // still Remove. FAILED would be wrong twice, offering a Retry
                // that installs rather than removes.
                report("toolchain_${toolchainShortName(name)}", AssetPackStatus.UNKNOWN, 0)
            }
        }
    }

    /**
     * Accepts either name form, because the two entry points into this class
     * disagreed about which one they take and only one of them said so.
     *
     * [install] resolves through [ToolchainRegistry.find], so `go` and
     * `toolchain_ruby` both work there. This side matched the persisted short name
     * only, and the form JavaScript actually holds is the pack name --
     * `getAvailableToolchains` hands it out as `packName`. So the natural call,
     * `removeToolchain("toolchain_ruby")`, logged "not found in state" and removed
     * nothing, while the same string passed to `installToolchain` worked.
     * `ToolchainActivity` never hit it because it strips the prefix itself
     * before calling; the bridge did not.
     *
     * Takes the same [installsInFlight] claim an install does, because the two
     * are the same file work in opposite directions and nothing put them in
     * order. Each construction site has its own single-thread executor, so a
     * Remove queued on one manager runs while a copy runs on another's: the
     * reachable pair is a removal from the bridge or the toolchain screen
     * against the copy the first-run queue is performing. Deleting the files a
     * copy is writing leaves a tree the install then records as complete.
     *
     * Declines rather than waits, which is the call [installFromDirectory]
     * already makes for this shape, and reports UNKNOWN for the reason it does:
     * a screen that has taken the Remove button away needs an answer to put it
     * back, and neither COMPLETED nor NOT_INSTALLED is true here.
     *
     * Not every removal comes through here. [removeRetiredToolchainsSync] calls
     * [uninstallLocked] directly and takes no claim; its KDoc carries why that
     * one is safe.
     */
    private fun uninstallSync(nameOrPack: String) {
        val name = toolchainShortName(nameOrPack)
        val packName = "toolchain_$name"
        if (!installsInFlight.add(packName)) {
            Logger.i(tag, "An install of $packName is running; not removing it underneath")
            report(packName, AssetPackStatus.UNKNOWN, 0)
            return
        }
        try {
            synchronized(stateLock) { uninstallLocked(name) }
        } finally {
            installsInFlight.remove(packName)
        }
    }

    /**
     * Caller must hold [stateLock]. The manifest read at the top names the
     * files, symlinks and libraries deleted below it, so another instance
     * rewriting the record midway through leaves this pass deleting from one
     * version of the truth and recording against another.
     */
    private fun uninstallLocked(name: String) {
        val state = readState()
        var manifestObj: JSONObject? = null
        var idx = -1

        for (i in 0 until state.length()) {
            val obj = state.optJSONObject(i) ?: continue
            if (obj.optString("name") == name) {
                manifestObj = obj
                idx = i
                break
            }
        }

        if (manifestObj == null) {
            // The uninstall twin of the "Unknown toolchain" line in [install]:
            // [toolchainShortName] returns a name it does not recognise unchanged,
            // deliberately, so that a retired toolchain can still be removed, and
            // that is what puts arbitrary text here.
            Logger.w(tag, "Toolchain ${redactToken(name)} not found in state")
            // Reported rather than returned in silence, and NOT_INSTALLED is what
            // is true: the record does not name it. The screen that asked has
            // taken the Remove button off the card while the removal is queued,
            // and this is the only thing that ever puts it right for a record
            // that was already gone.
            report("toolchain_$name", AssetPackStatus.NOT_INSTALLED, 0)
            return
        }

        // Delete symlinks from usr/bin/ (use Os.lstat to catch dangling symlinks)
        val symlinks = manifestObj.optJSONObject("symlinks")
        if (symlinks != null) {
            val binDir = File(context.filesDir, "usr/bin")
            for (key in symlinks.keys()) {
                val link = File(binDir, key)
                val linkExists = try { Os.lstat(link.absolutePath); true } catch (e: Exception) { false }
                if (linkExists) {
                    link.delete()
                    Logger.d(tag, "Removed symlink: $key")
                }
            }
        }

        // Delete individual binary files (for toolchains like Ruby that place
        // binaries directly in usr/bin/ rather than an isolated directory)
        val binaries = manifestObj.optJSONArray("binaries")
        if (binaries != null) {
            for (i in 0 until binaries.length()) {
                val binFile = File(context.filesDir, binaries.getString(i))
                if (binFile.exists()) {
                    binFile.delete()
                    Logger.d(tag, "Removed binary: ${binaries.getString(i)}")
                }
            }
        }

        // Delete the toolchain's isolated install root (NOT shared dirs like usr/bin/)
        val installRoot = manifestObj.optString("installRoot", "")
        if (installRoot.isNotEmpty()) {
            val dir = File(context.filesDir, installRoot)
            if (dir.exists()) {
                dir.deleteRecursively()
                Logger.d(tag, "Deleted install root: $installRoot")
            }
        }

        // Delete library symlinks (versioned sonames)
        val libSymlinks = manifestObj.optJSONObject("libSymlinks")
        if (libSymlinks != null) {
            for (linkName in libSymlinks.keys()) {
                val linkFile = File(context.filesDir, "usr/lib/$linkName")
                val linkExists = try { Os.lstat(linkFile.absolutePath); true } catch (e: Exception) { false }
                if (linkExists) {
                    linkFile.delete()
                    Logger.d(tag, "Removed lib symlink: $linkName")
                }
            }
        }

        // Delete libs that were copied to usr/lib/ -- but usr/lib is shared with
        // the base install, and a manifest may name a library the base APK also
        // ships. Ruby's libffi.so is the live case: Python's _ctypes links it, so
        // deleting it here broke `import ctypes` until the next app update, with
        // nothing pointing at the uninstall that caused it. What the base ships
        // is read from the APK's own assets rather than kept as a list here; if
        // that listing cannot be read, nothing is deleted -- a leftover library
        // is reclaimed by the next install, a deleted base library is not.
        //
        // The other installed toolchains are added to that listing, because they
        // share the directory on exactly the same terms and the check was made
        // for one of the two sharers only. Removing A would take a library B also
        // ships, and B then fails at its next dlopen with an error naming the
        // library rather than the uninstall. No two shipped manifests name the
        // same library today, so this is a floor rather than a repair -- and the
        // manifests are regenerated from upstream packages, so what they name is
        // not this repository's choice to keep disjoint.
        val libs = manifestObj.optJSONArray("libs")
        if (libs != null) {
            val baseShipped = try {
                context.assets.list("usr/lib")?.toSet()?.plus(otherToolchainLibs(state, name))
            } catch (e: Exception) {
                Logger.w(tag, "Cannot list base usr/lib assets; keeping all libs: ${e.message}")
                null
            }
            val names = (0 until libs.length()).map { libs.getString(it) }
            val keep = names.toSet() - toolchainLibsSafeToRemove(names, baseShipped).toSet()
            for (name in keep) {
                Logger.i(tag, "Keeping $name: the base install ships it too")
            }
            for (name in toolchainLibsSafeToRemove(names, baseShipped)) {
                val lib = File(context.filesDir, "usr/lib/$name")
                if (lib.exists()) lib.delete()
            }
        }

        // Remove from state
        state.remove(idx)
        // Asked, exactly as [installFromDirectoryHoldingPack] asks it, and for the
        // sibling reason: the record is what everything else reads, so a removal
        // whose record write failed is not a removal that happened. The files are
        // gone either way -- they were deleted above -- but reporting
        // NOT_INSTALLED on top of a record that still names the toolchain tells
        // the user it is gone while the next launch draws it as installed and
        // `getAllToolchainEnv` goes on exporting JAVA_HOME for a directory that no
        // longer exists. Nothing reconciles that later: the repair pass only
        // checks execute bits.
        //
        // FAILED rather than silence, because it is the only channel that reaches
        // the user with a reason, and the Retry it puts on the card reinstalls the
        // toolchain, which is the repair for a record that outlived its files.
        // STORAGE for the reason the install sibling picks it: [writeAtomically]
        // writes a temporary file and renames it, and what stops that is space or
        // an I/O fault, both of which "free some space and try again" survives.
        if (!writeState(state)) {
            fail("toolchain_$name", ToolchainFailure.STORAGE)
            return
        }
        regenerateDerivedFilesLocked()

        Logger.i(tag, "Uninstalled toolchain: $name")
        report("toolchain_$name", AssetPackStatus.NOT_INSTALLED, 0)
    }

    // -- Asset pack state handling --

    private fun handleStateUpdate(state: AssetPackState) {
        val packName = state.name()
        val status = state.status()
        val totalBytes = state.totalBytesToDownload()
        val downloaded = state.bytesDownloaded()
        val percent = packDownloadPercent(downloaded, totalBytes)

        Logger.d(tag, "Pack $packName: status=$status, $downloaded/$totalBytes ($percent%)")

        // Don't fire onStateChange for COMPLETED here; the real COMPLETED fires
        // after copyFromAssetPack() finishes extraction (line in copyFromAssetPack).
        // Firing it twice would cause downloadNext() to be called twice, skipping packs.
        //
        // FAILED is held back for a different reason: report() has no way to say
        // why, and this is the only place that knows. It is emitted below through
        // fail() instead, still exactly once and still carrying FAILED, which is
        // what keeps isTerminalPackStatus moving the first-run queue past it. A
        // pack that stops emitting FAILED strands every pack queued behind it.
        if (status != AssetPackStatus.COMPLETED && status != AssetPackStatus.FAILED) {
            report(packName, status, percent)
        }

        // Not enumerated, and the else arm below is why. Play Core adds statuses,
        // and a `when` naming all of today's would keep compiling while silently
        // ignoring a new one; the else arm reports whatever arrives instead.
        @SuppressLint("SwitchIntDef")
        when (status) {
            AssetPackStatus.COMPLETED -> {
                // Heavy I/O: copy files, chmod, symlinks. Run off main thread
                ioExecutor.execute {
                    try {
                        val location = assetPackManager.getPackLocation(packName)
                        val assetsPath = location?.assetsPath()
                        if (assetsPath != null) {
                            installDeliveredPack(packName, File(assetsPath))
                        } else {
                            Logger.e(tag, "No assetsPath for completed pack $packName")
                            fail(packName, ToolchainFailure.INTERNAL)
                        }
                    } catch (e: Exception) {
                        Logger.e(tag, "Failed to process completed pack $packName", e)
                        fail(packName, ToolchainFailure.INTERNAL)
                    }
                }
            }
            AssetPackStatus.FAILED -> {
                val code = state.errorCode()
                Logger.e(tag, "Pack $packName download failed: errorCode=$code")
                fail(packName, toolchainFailureFor(code))
            }
            AssetPackStatus.REQUIRES_USER_CONFIRMATION -> {
                // Downloads exceeding 200MB or Play-determined thresholds need user
                // confirmation via a system dialog. We need an Activity reference for
                // this; for now, log and report the state so the UI can prompt the user.
                Logger.w(tag, "Pack $packName requires user confirmation")
            }
            else -> { /* DOWNLOADING, PENDING, WAITING_FOR_WIFI, etc., just report progress */ }
        }
    }

    /**
     * Copies a pack Play has delivered into `usr/`, then hands Play's copy back.
     *
     * One method rather than the same sequence at each site, because two now reach it:
     * the COMPLETED callback, and [reconcileDeliveredPacks] at launch. Both have to
     * apply the same pre-flight and neither may forget the release, and the way the
     * second site came to exist is that the first was the only one.
     *
     * The pre-flight is the one [downloadViaHttp] does, with a smaller figure.
     * `installFromDirectory` copies the whole tree, and without this the copy fails
     * partway on a full device: an IOException reported as INTERNAL, and half a
     * toolchain left in `usr/` under no manifest, which is what made every retry start
     * from less space than the last.
     */
    private fun installDeliveredPack(packName: String, assetsDir: File) {
        // [packUnpackedBytes], not `ToolchainRegistry.find(...)?.estimatedSize ?: 0L`.
        // A retired pack answers null from the registry -- that pass-through is
        // deliberate and keeps uninstalling one working -- and the elvis then made the
        // whole reservation the 50 MB buffer. A gate that asks for 50 MB before copying
        // 155 MB, which is what Java 17 unpacks to today, is worse than no gate: it
        // passes exactly the devices it exists to refuse, and reports success while
        // doing it. That figure moves with the JDK, so re-measure it rather than
        // trusting it here: `du -sk android/toolchain_java/src/main/assets/usr`.
        val unpacked = packUnpackedBytes(packName)
        if (unpacked == null) {
            // Unknown size, so there is no honest reservation to make. Said out loud
            // rather than gated on a guess, because a wrong figure here is
            // indistinguishable from a check that ran.
            Logger.w(
                tag,
                "No recorded size for $packName, so the space pre-flight is skipped; " +
                    "a full device will fail during the copy instead of before it",
            )
        } else {
            val credit = existingTreeCredit(
                deliveredInstallRoot(assetsDir, packName),
                context.filesDir,
                unpacked,
                StorageManager::dirSize,
            )
            val required = (packInstallBytes(unpacked) - credit).coerceAtLeast(SPACE_BUFFER)
            val available = StatFs(context.filesDir.absolutePath).availableBytes
            if (available < required) {
                Logger.e(
                    tag,
                    "Not enough disk space for $packName: " +
                        "${available / 1_000_000} MB available, " +
                        "${required / 1_000_000} MB required",
                )
                // Play's copy is deliberately KEPT here, and an earlier version of
                // this branch released it on the reasoning that a refused install has
                // no use for it. That reasoning was wrong twice over.
                //
                // Play delivers into `filesDir/assetpacks`, verified from
                // asset-delivery 2.2.2's bytecode: `bh` builds
                // `new File(context.getFilesDir(), "assetpacks")`. So the pack sits on
                // the very filesystem `StatFs(filesDir)` above just measured. Deleting
                // it frees `unpacked` bytes and the retry re-downloads and re-extracts
                // `unpacked` bytes into the same directory, arriving back at the free
                // space that failed the gate. The delete buys the next attempt nothing
                // and charges it a full download, 55 MB for Java 17.
                //
                // Keeping it is also what makes the repair work. The user frees space,
                // and the next launch's [reconcileDeliveredPacks] finds this same
                // delivered pack still in place and installs it with no download at
                // all. Releasing it here would remove the one thing that path needs.
                fail(packName, ToolchainFailure.STORAGE)
                return
            }
        }
        // False covers two situations, and `removePack` is wrong for both.
        //
        // Another install of this pack may be copying out of `assetsDir` at this
        // moment, and `removePack` is a real recursive delete of that directory:
        // releasing it would pull the source out from under the reader, which is
        // the hazard [cancel] documents from the other direction. The install
        // that holds the pack releases it when it is done with it.
        //
        // Or this install copied the tree and could not write the record. That
        // is the space refusal above arriving one step later, and the same
        // reasoning governs it: the user frees space, relaunches, and
        // [reconcileDeliveredPacks] finishes the install from a delivery still in
        // place. Deleting it here charged them the whole download again for a
        // failure that had already done all the work.
        if (installFromDirectory(packName, assetsDir)) {
            releasePack(packName)
        }
    }

    /**
     * Finishes any Play delivery that completed while nothing was listening for it.
     *
     * Until this existed, `installFromDirectory` was reachable only from the
     * COMPLETED callback, and that callback only arrives while a listener is
     * registered. This is the second route, and the one that needs no listener. Both screens that install
     * toolchains drop their registration at teardown -- `ToolchainActivity.onStop` and
     * `SplashActivity.onDestroy` -- so backgrounding the app mid-download left the pack
     * downloaded, paid for, and never installed. Nothing retried it: the picker reads
     * `toolchains.json`, sees the toolchain absent, and offers the same download again.
     *
     * Holding the registration open instead is the wrong repair, and the reason is
     * the retention this class was just corrected for: a listener that outlives its
     * screen is what kept an Activity alive in Play Core's registry. Reconciling at
     * launch needs no listener at all. Play is asked what it has already delivered,
     * which is a question with an answer whether or not anyone was listening when it
     * became true.
     *
     * The second job is the same repair for the other outcome. A pack Play still
     * holds for a toolchain the record already calls installed is a delivery that
     * was consumed and never handed back, because `removePack` is asynchronous and
     * a process that goes away between the copy and the delete leaves it in place.
     * Nothing here measures it: both space pre-flights read free bytes, and the
     * pack is a whole toolchain of them. So it is released rather than passed over.
     *
     * Play installs only. On the HTTP path there is no pack to ask about, and
     * `getPackLocation` on an install Play does not recognise is a question with no
     * meaning rather than a cheap no.
     */
    fun reconcileDeliveredPacks() {
        ioExecutor.execute {
            // Inside the executor, not before it. `shouldUseHttpFallback` asks the
            // package manager who installed this app, which is a synchronous binder
            // call, and the one caller is `SplashActivity.onCreate` on the main
            // thread. Every other launch repair hands its work off for the same
            // reason; this one used to do its first piece of work before it did.
            if (shouldUseHttpFallback()) return@execute
            try {
                // Read once, and nothing in the loop invalidates it: each pack is
                // visited at most once, so an install performed here can only affect
                // an entry already passed. Re-reading per pack would be equally
                // correct and would cost a file parse per toolchain.
                val installed = getInstalledToolchains()
                ToolchainRegistry.available.forEach { info ->
                    // Per pack, not around the loop. `installDeliveredPack`
                    // reaches `JSONObject(...)` on a malformed manifest and
                    // `copyDirectoryTree` on a directory it cannot list or a
                    // disk that fills up, and either throw used to unwind out
                    // of the whole `forEach`. Ruby is first in the registry, so
                    // one throw on Ruby meant Java's delivery was never examined
                    // -- neither installed if it was outstanding, nor reclaimed
                    // if it was a whole toolchain of duplicate storage. Every
                    // later launch repeated exactly that, so the reclaim that
                    // could have made room for Ruby was the thing Ruby's failure
                    // prevented. The pack is named here; the outer catch below
                    // could only name the exception.
                    try {
                        reconcileOnePack(info, installed)
                    } catch (e: Exception) {
                        Logger.w(
                            tag,
                            "Could not reconcile ${info.packName}: ${e.message}",
                        )
                    }
                }
                // The packs this build no longer offers, asked about the same
                // way. [removeRetiredToolchainsSync] reads the install record
                // and deletes the copy under `usr/`; it never asks Play, so a
                // delivery a process death left in `filesDir/assetpacks` before
                // its removal ran outlived the withdrawal: 179 MB for Go,
                // counted by no pre-flight and offered for removal by no
                // screen. Released outright rather than installed. Nothing can
                // be reading the directory, because [install] refuses a name
                // the registry does not know.
                RETIRED_TOOLCHAINS.keys.forEach { name ->
                    val packName = "toolchain_$name"
                    try {
                        if (assetPackManager.getPackLocation(packName) == null) return@forEach
                        Logger.i(tag, "Reclaiming a delivered pack for a retired toolchain: $packName")
                        releasePack(packName)
                    } catch (e: Exception) {
                        Logger.w(tag, "Could not ask Play about $packName: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Logger.w(tag, "Could not reconcile delivered packs: ${e.message}")
            }
        }
    }

    /**
     * One pack's half of [reconcileDeliveredPacks], so a failure can be contained
     * to the pack it belongs to.
     *
     * @param installed what `toolchains.json` named when the pass began
     */
    private fun reconcileOnePack(
        info: ToolchainRegistry.ToolchainInfo,
        installed: List<String>,
    ) {
        // Play is asked first, and the record second. The order is the
        // whole of the repair below: asking the record first meant an
        // installed toolchain was never asked about, so a delivered pack
        // whose removal did not happen was passed over on every launch
        // for the life of the install.
        val location = try {
            assetPackManager.getPackLocation(info.packName)
        } catch (e: Exception) {
            Logger.w(tag, "Could not ask Play about ${info.packName}: ${e.message}")
            null
        } ?: return
        if (info.packName.removePrefix("toolchain_") in installed) {
            // The toolchain is already in `usr/`, so this delivery has
            // nothing left to give and is pure duplicate storage. It
            // reaches here when the release that should have followed the
            // install did not complete: `removePack` is asynchronous, and
            // the process can go away between the copy and the delete.
            //
            // Skipped while an install holds the pack, for the reason
            // [installDeliveredPack] gives: `removePack` is a recursive
            // delete of the directory that install is reading. A record
            // written by an earlier install of the same toolchain makes
            // that pair reachable.
            if (info.packName in installsInFlight) return
            Logger.i(
                tag,
                "Reclaiming a delivered pack for an installed toolchain: " +
                    info.packName,
            )
            releasePack(info.packName)
            return
        }
        val assetsPath = location.assetsPath() ?: return
        Logger.i(
            tag,
            "Finishing a delivery nothing was listening for: ${info.packName}",
        )
        installDeliveredPack(info.packName, File(assetsPath))
    }

    /**
     * Hands Play's copy of [packName] back, whether or not the install used it.
     *
     * Called on both exits from the COMPLETED branch, which is the point. Play writes
     * the pack outside `filesDir` and keeps it until asked; leaving it there after a
     * refusal charged the user for a delivery nothing consumed, on the one path that
     * fires only when the device is already out of room.
     *
     * Guarded, because this now runs on a failure path. `removePack` is a Play Core
     * call and the HTTP delivery path never registered one; an exception here must not
     * replace the failure the caller is on its way to report.
     */
    private fun releasePack(packName: String) {
        try {
            // `removePack` posts the delete and returns; the Task is how Play says
            // whether it happened. Logging success on the return alone said "freed"
            // for a delete that failed or never ran, and the pack it left behind is
            // a whole toolchain in `filesDir/assetpacks` -- 155 MB for Java 17 --
            // that no space pre-flight here counts, because both of them measure
            // free space rather than what Play is holding. So the one line that
            // would have named the leak was the line asserting it had not happened.
            assetPackManager.removePack(packName)
                .addOnSuccessListener {
                    Logger.i(tag, "Removed asset pack $packName (freed duplicate storage)")
                }
                .addOnFailureListener { e ->
                    Logger.w(
                        tag,
                        "Play did not remove asset pack $packName; it stays on disk " +
                            "until the next launch reclaims it",
                        e,
                    )
                }
        } catch (e: Exception) {
            Logger.w(tag, "Could not remove asset pack $packName: ${e.message}")
        }
    }

    // -- File operations --

    /**
     * Copies a pack into the shared `usr/` tree, unless another install of the
     * same pack is already doing exactly that.
     *
     * The claim is taken here rather than at either entry point because this is
     * where the two of them meet: [installDeliveredPack] arrives from Play, the
     * background task in [downloadViaHttp] arrives from a release ZIP, and the
     * copy below is the part they share. One claim site is also what keeps a
     * single install from claiming twice.
     *
     * A decliner touches nothing the install that holds the claim owns: it does
     * not release the pack, does not delete a staging directory, and does not
     * write the record. The staging directory is per download already
     * ([toolchainTempDir]), so the HTTP path's own cleanup stays correct without
     * knowing about any of this; the Play pack is not, which is why the return
     * value exists.
     *
     * @return whether Play's delivered copy may now be handed back. That is the
     *   question [installDeliveredPack] is asking, and answering "did this call
     *   own the claim" instead lost the one case where the two differ. False
     *   covers a decline, because `removePack` is a real recursive delete of the
     *   very directory the other install is copying out of, and it now also
     *   covers a record that could not be written: freeing space and relaunching
     *   is the repair for that, and [reconcileDeliveredPacks] can only perform it
     *   while the delivery is still there. The HTTP path ignores the answer, and
     *   correctly: its staging directory belongs to one download.
     */
    private fun installFromDirectory(packName: String, assetsDir: File): Boolean {
        if (!installsInFlight.add(packName)) {
            Logger.i(tag, "Another install already holds $packName; leaving it to that one")
            // Neither COMPLETED nor FAILED, because both would be untrue in a way
            // something acts on. COMPLETED is the word this class uses for "the
            // record is written and the toolchain is usable", which this call did
            // not do and cannot promise the other one will. FAILED carries a
            // reason the user is asked to act on, and there is nothing here for
            // them to act on.
            //
            // Reported at all, rather than returning in silence, because the
            // first-run queue advances on reported state and `isTerminalPackStatus`
            // counts anything outside the five in-progress statuses as finished.
            // A silent return would leave that queue waiting on a pack that this
            // manager has stopped working on, with every pack behind it.
            //
            // UNKNOWN and specifically NOT the neighbouring NOT_INSTALLED, which
            // reads as terminal just as well and is wrong for a different reason.
            // That one is this class's word for a completed uninstall, written at
            // the end of `uninstallSync`, and `ToolchainCardState.updateState`
            // reads it as exactly that and drops the pack from its installed set.
            // The rotation this claim exists for is where that bites: the manager
            // still holding the claim belongs to the destroyed Activity, so its
            // COMPLETED reaches nothing, while this decline reaches the live card
            // and would leave it offering Install for a toolchain that is on its
            // way in. UNKNOWN carries no such meaning to any reader here.
            report(packName, AssetPackStatus.UNKNOWN, 0)
            return false
        }
        return try {
            installFromDirectoryHoldingPack(packName, assetsDir)
        } finally {
            // In a finally rather than at each exit: the body reports FAILED and
            // returns from four places and throws from more, and a claim dropped
            // on only the path its author had in mind is never released at all,
            // which would refuse every later install of that pack for the life
            // of the process.
            installsInFlight.remove(packName)
        }
    }

    /**
     * The delivered manifest's install root under `filesDir`, best-effort.
     *
     * Best-effort and null on anything unexpected, because the authoritative
     * parse stays where it is, in [installFromDirectoryHoldingPack]: a malformed
     * manifest must still report CORRUPT from there rather than change the space
     * gate's answer here.
     */
    private fun deliveredInstallRoot(assetsDir: File, packName: String): File? = try {
        val manifestFile = File(assetsDir, "$packName.json")
        if (!manifestFile.exists()) {
            null
        } else {
            JSONObject(manifestFile.readText()).optString("installRoot", "")
                .takeIf { it.isNotEmpty() }
                ?.let { File(context.filesDir, it) }
        }
    } catch (e: Exception) {
        Logger.w(tag, "Could not read the install root from $packName's manifest", e)
        null
    }

    /**
     * The install itself, with the pack already claimed by the caller.
     *
     * @return whether the delivery has nothing left to give. True for a finished
     *   install and true for a `CORRUPT` refusal, which a retry has to
     *   re-download anyway. False for the copy failing and for the record write
     *   failing, which are the same event a step apart: a full disk during or at
     *   the end of a copy. The user frees space, and the next launch's
     *   [reconcileDeliveredPacks] finishes the install from the delivery still in
     *   place, with no download at all. That is the same reasoning the space
     *   refusal in [installDeliveredPack] already states, applied to the two
     *   outcomes that reach the user as "free some space and try again".
     */
    private fun installFromDirectoryHoldingPack(packName: String, assetsDir: File): Boolean {
        // Both of these report FAILED before returning, and that is load-bearing
        // rather than tidy. Every other way out of this function ends in a state
        // being reported -- success at the end, and the caller's catch on a throw
        // -- but a plain return reported nothing, and the first-run queue is
        // driven entirely by those reports. A pack whose manifest was missing or
        // malformed therefore stalled setup on the progress screen instead of
        // being skipped, which is a far worse outcome than one absent toolchain.
        val manifestFile = File(assetsDir, "$packName.json")
        if (!manifestFile.exists()) {
            Logger.e(tag, "No $packName.json in asset pack $packName")
            fail(packName, ToolchainFailure.CORRUPT)
            // Released: a delivery with no manifest in it is not something a
            // later launch can finish, and a retry has to fetch it again.
            return true
        }

        val manifest = JSONObject(manifestFile.readText())
        val name = manifest.optString("name", "")
        if (name.isEmpty()) {
            Logger.e(tag, "Invalid manifest.json in $packName: missing 'name'")
            fail(packName, ToolchainFailure.CORRUPT)
            return true
        }
        Logger.i(tag, "Installing toolchain: $name (from $packName)")

        // Copy all files from usr/ to filesDir/usr/
        val usrSrc = File(assetsDir, "usr")
        if (usrSrc.exists()) {
            try {
                copyDirectoryRecursively(usrSrc, File(context.filesDir, "usr"))
            } catch (e: IOException) {
                // Caught here, where the two delivery paths meet, because
                // neither of them could name this event afterwards. Left to
                // unwind, the exception reached `catch (e: IOException)` in
                // downloadViaHttp and was reported as NETWORK -- "Download
                // failed. Check your connection and try again." for a disk that
                // filled up after the download succeeded -- and on the Play path
                // it reached `catch (e: Exception)` and was reported as INTERNAL,
                // which asks the user to read a log. STORAGE is the remedy that
                // fits both causes this can have: ENOSPC partway through the
                // write, and a directory that cannot be listed, which
                // `copyDirectoryTree` refuses rather than treating as empty.
                Logger.e(tag, "Could not copy $packName into usr/", e)
                reclaimPartialCopy(name, manifest)
                fail(packName, ToolchainFailure.STORAGE)
                // Play's copy is KEPT, exactly as for the record write below:
                // freeing space and relaunching finishes this install from the
                // delivery, with no download.
                return false
            }
        }

        // chmod +x on the binaries the manifest names. Whether every one of them
        // took the bit decides the repair marker written with the record below.
        val execBitsSet = applyManifestExecBits(manifest)

        // Create symlinks in usr/bin/
        val symlinks = manifest.optJSONObject("symlinks")
        if (symlinks != null) {
            val binDir = File(context.filesDir, "usr/bin")
            binDir.mkdirs()
            for (linkName in symlinks.keys()) {
                val target = symlinks.getString(linkName)
                val targetAbsolute = "$filesDir/$target"
                val linkFile = File(binDir, linkName)

                // Remove existing link if stale
                val linkExists = try { Os.lstat(linkFile.absolutePath); true } catch (e: Exception) { false }
                if (linkExists) {
                    linkFile.delete()
                }

                try {
                    Os.symlink(targetAbsolute, linkFile.absolutePath)
                    Logger.d(tag, "Symlink: $linkName -> $target")
                } catch (e: Exception) {
                    Logger.w(tag, "Failed to create symlink $linkName: ${e.message}")
                }
            }
        }

        // Create library symlinks (versioned sonames like libruby.so.3.4 → libruby.so)
        // Android assets can't contain symlinks, so versioned sonames are created at install time.
        val libSymlinks = manifest.optJSONObject("libSymlinks")
        if (libSymlinks != null) {
            val libDir = File(context.filesDir, "usr/lib")
            for (linkName in libSymlinks.keys()) {
                val targetName = libSymlinks.getString(linkName)
                val targetFile = File(libDir, targetName)
                val linkFile = File(libDir, linkName)
                if (targetFile.exists()) {
                    val linkExists = try { Os.lstat(linkFile.absolutePath); true } catch (e: Exception) { false }
                    if (linkExists) linkFile.delete()
                    try {
                        Os.symlink(targetFile.absolutePath, linkFile.absolutePath)
                        Logger.d(tag, "Lib symlink: $linkName -> $targetName")
                    } catch (e: Exception) {
                        Logger.w(tag, "Failed to create lib symlink $linkName: ${e.message}")
                    }
                }
            }
        }

        // Persist state. Read, mutate and write are one unit: on its own, each
        // is safe, and interleaved with another instance's cycle the write puts
        // back an array that predates whatever the other one added.
        //
        // Only this tail is held, not the copy above it. The copy moves up to
        // 160 MB and holds nothing anyone else needs; the record is four lines
        // and is what everything else reads.
        //
        // Flushed before the record, for the reason [flushWritesToMedia] gives:
        // the record is the only thing that says these binaries exist, and
        // repairInstalledToolchainsSync re-checks the tree with isDirectory,
        // never its contents. A power cut with the record on the medium and the
        // copy still in page cache leaves a toolchain listed as installed whose
        // binaries are holes, and nothing later looks. Outside the lock, not
        // inside: the copy it flushes is already finished here, and a sync waits
        // on the whole device's dirty set, which is not something to hold a lock
        // across.
        flushWritesToMedia()

        synchronized(stateLock) {
            val state = readState()
            // Remove any existing entry for this toolchain
            for (i in state.length() - 1 downTo 0) {
                if (state.optJSONObject(i)?.optString("name") == name) {
                    state.remove(i)
                }
            }
            // Marked only when the chmod above actually took for every binary the
            // manifest names. Written unconditionally, as it was, this recorded a
            // toolchain as repaired over binaries whose chmod never happened --
            // the loop skips a path that is not there and used to ignore what
            // `setExecutable` returned -- and [repairInstalledToolchainsSync]
            // skips a marked entry for ever, so nothing would look at it again.
            // Left false, the next launch walks the tree once and marks it then.
            manifest.put(KEY_EXEC_REPAIRED, execBitsSet)
            state.put(manifest)
            // The record is what the install consists of, as far as everything
            // else is concerned. Without it getInstalledToolchains() does not
            // name the toolchain, the environment below is regenerated from a
            // record that predates it, readState() re-reads the file, so it
            // reads back the old one, and no manifest survives to tell
            // uninstallLocked which files, symlinks and libraries the ~160 MB
            // just copied consists of. Reporting COMPLETED on top of that is the
            // part that makes it silent: the picker shows 100% and Done, the
            // first-run queue moves on, and the card reads "Install" again after
            // the next launch with nothing said.
            if (!writeState(state)) {
                // Same reclaim as the copy failure above, because this is the same
                // event a step later: the record naming the ~155 MB just copied does
                // not exist, so nothing else can ever find those files. Uninstall
                // works off this manifest, no card offers a Remove for a toolchain
                // getInstalledToolchains() cannot name, and the repair pass only
                // visits records that exist -- clearing app data was the way back.
                // On the failure this is about, a full disk, they are exactly the
                // bytes the retry the user is being asked to make needs.
                //
                // Safe over a reinstall: writeState false means the file still holds
                // the previous record, so [reclaimPartialCopy] re-reads it, still
                // sees the toolchain named, and keeps the working copy's files. The
                // `usr/bin` and `usr/lib` residue stays, for the reason that function
                // documents: sorting it belongs to the uninstall's library
                // bookkeeping, which needs the manifest this failure means we could
                // not write.
                reclaimPartialCopy(name, manifest)
                fail(packName, ToolchainFailure.STORAGE)
                // Play's copy is KEPT, which is the whole of the return value
                // above. The user is told to free space and try again, and the
                // next launch's [reconcileDeliveredPacks] can finish this
                // install from the delivery without downloading anything --
                // but only while the delivery is still there.
                return false
            }
            regenerateDerivedFilesLocked()
        }

        Logger.i(tag, "Toolchain $name installed successfully")
        report(packName, AssetPackStatus.COMPLETED, 100)
        return true
    }

    /**
     * Gives the execute bit to every binary [manifest] names, and says whether
     * they all have it now.
     *
     * The manifest's `binaries` array is the authoritative list, and it is the
     * only thing that names the ELF objects a toolchain keeps outside its own
     * install root: Ruby's interpreter is `usr/bin/ruby` while its install root
     * is `usr/lib/ruby`, so a pass that walks the root alone cannot reach the
     * one file the toolchain cannot run without.
     *
     * One function for the install and for [repairInstalledToolchainsSync],
     * because the two were the same loop with different mistakes in it: the
     * install ignored what `setExecutable` returned and then recorded the
     * toolchain as repaired, and the repair could not see these paths at all.
     *
     * A path that is not there is not a failure. Nothing can give a bit to a
     * file that does not exist, now or on any later launch, and a manifest may
     * legitimately name a binary a particular payload did not ship.
     *
     * @return false when a file exists, lacks the bit, and would not take it
     */
    private fun applyManifestExecBits(manifest: JSONObject): Boolean {
        val binaries = manifest.optJSONArray("binaries") ?: return true
        var allExecutable = true
        for (i in 0 until binaries.length()) {
            val binPath = binaries.getString(i)
            val binFile = File(context.filesDir, binPath)
            if (!binFile.exists() || binFile.canExecute()) continue
            if (binFile.setExecutable(true, true)) {
                Logger.d(tag, "chmod +x: $binPath")
            } else {
                Logger.w(tag, "Could not set the execute bit on $binPath")
                allExecutable = false
            }
        }
        return allExecutable
    }

    /**
     * Deletes the tree an install that did not finish had already written, when
     * nothing else lays claim to it.
     *
     * The install record is written last, so both ways an install can end after
     * the copy has started -- the copy throwing, and the record write failing --
     * leave up to the whole unpacked size, about 155 MB for the Java 17 that
     * ships today, under no manifest at all: [getInstalledToolchains] does not
     * name it, the Toolchains screen offers no Remove for it, [uninstallLocked]
     * has no record to work from, and the repair pass only visits records that
     * exist. Clearing app data was the only way back.
     *
     * The install root only. The same copy also writes into `usr/bin` and
     * `usr/lib`, which are shared with the base install and with other
     * toolchains, and deciding what may go from there is the bookkeeping
     * [uninstallLocked] does from a manifest this failure means we do not have.
     * The install root is the toolchain's own directory and is the bulk of it,
     * and deleting it is what makes room for the retry that a full disk needs.
     *
     * Left alone when the record already names this toolchain: that is a
     * reinstall over a working copy, and those files are the working copy's.
     */
    private fun reclaimPartialCopy(name: String, manifest: JSONObject) {
        val installRoot = manifest.optString("installRoot", "")
        if (installRoot.isEmpty()) return
        if (name in getInstalledToolchains()) {
            Logger.w(tag, "Keeping $installRoot: the record still names $name")
            return
        }
        val dir = File(context.filesDir, installRoot)
        if (!dir.exists()) return
        if (dir.deleteRecursively()) {
            Logger.i(tag, "Reclaimed the partial $name tree under $installRoot")
        } else {
            Logger.w(tag, "Could not fully reclaim the partial $name tree under $installRoot")
        }
    }

    // -- HTTP fallback (sideloaded installs) --

    /**
     * Returns true if the app was NOT installed via Play Store.
     * On sideloaded/debug builds, Play Asset Delivery silently fails,
     * so we download toolchain ZIPs from GitHub Releases instead.
     *
     * Asked once per manager and remembered, because who installed the app
     * cannot change while the process runs and asking is a binder round trip.
     * The refusal below is remembered too, deliberately: the query names this
     * app's own package, so it does not fail because a service was busy, it
     * fails because the platform will not answer it for this caller at all.
     * Asking again would cost a round trip per install to be refused again.
     * [reconcileDeliveredPacks] makes that call on the toolchain I/O thread,
     * deliberately and with a comment saying why; [install] makes it on
     * whichever thread tapped, which for both screens is the main one. Caching
     * removes the repeat: the first-run queue installs pack after pack through
     * one manager, and so does every later tap on the toolchain screen.
     *
     * The first call still runs where its caller stands. Moving [install] onto
     * [ioExecutor] instead would be worse than the round trip it saves: the
     * decline checks it performs are what answer a tap on a pack already being
     * fetched, and behind a transfer occupying that single thread they would be
     * answered minutes later.
     */
    private fun shouldUseHttpFallback(): Boolean {
        installSourceIsPlay?.let { return !it }
        val isPlay = try {
            val source = context.packageManager.getInstallSourceInfo(context.packageName)
            val installer = source.installingPackageName
            Logger.d(tag, "Install source: $installer")
            // `com.google.android.feedback` is the Play Store's own legacy
            // package name and is still the installer of record on installs made
            // by older versions of it. Treated as Play because it is Play: the
            // HTTP fallback exists for installs Play cannot serve asset packs
            // to, and this is not one of them.
            //
            // Everything else, INCLUDING a null, stays on the HTTP path. That is
            // deliberate and is not a fail-open oversight: `adb install` and
            // several package installers leave `installingPackageName` null, and
            // those are precisely the sideloads the ZIP path exists for, so
            // reading an unknown installer as Play would leave them fetching
            // asset packs Play will never deliver, silently.
            installer in PLAY_INSTALLERS
        } catch (e: Exception) {
            Logger.w(tag, "Could not determine install source, using HTTP fallback: ${e.message}")
            false
        }
        installSourceIsPlay = isPlay
        return !isPlay
    }

    /**
     * Downloads a toolchain ZIP from GitHub Releases, extracts it, and installs.
     * Runs entirely on ioExecutor. Fires onStateChange with AssetPackStatus constants.
     */
    private fun downloadViaHttp(
        packName: String,
        url: String,
        unpackedBytes: Long,
        downloadBytes: Long,
    ) {
        // Asked, not claimed, and the difference is the whole of why this is here.
        //
        // The claim that actually excludes a second install is taken where both
        // delivery paths converge, in [installFromDirectory], because that is the
        // one point every route into the shared tree passes through. But this path
        // reaches it last: it downloads the archive and expands it first, so two
        // managers installing the same pack each spend a whole download and a
        // whole extraction before one of them declines, on a device whose
        // pre-flight reserved for one of them. Rotating the toolchain screen
        // mid-download is enough to arrange it.
        //
        // A second claim here would be worse than the waste: it is the same key,
        // so the install that owns it would meet its own claim further down and
        // refuse itself. Reading the set instead cannot do that, and it cannot
        // make anything worse either. If two callers both read it as free they
        // proceed exactly as they do today and the real claim still decides; what
        // this catches is the ordinary case, where one is already well into the
        // copy by the time the other starts.
        if (packName in installsInFlight) {
            Logger.i(tag, "Another install already holds $packName; not downloading it again")
            report(packName, AssetPackStatus.UNKNOWN, 0)
            return
        }
        // The same question one stage earlier, and it is the one that catches the
        // case the check above cannot. [installsInFlight] is claimed only once the
        // archive has been downloaded and expanded, so for the whole of a transfer
        // -- 56 MB for Java 17, minutes on a phone connection -- it reads free.
        // Rotating the toolchain screen destroys the Activity, rebuilds it with a
        // second manager whose card knows nothing of the download still running,
        // and offers Install again; a tap then spent the whole download a second
        // time, and the claim further down made exactly one of the two copies
        // happen. The disk was right and the data allowance was not.
        //
        // Declines rather than waits, the call [installFromDirectory] already
        // makes for the same shape: the pack is being fetched, and the caller that
        // declines has nothing to add to that.
        //
        // `containsKey`, not `in`: the Kotlin compiler refuses `in` on a
        // ConcurrentHashMap, where it would resolve to `containsValue`.
        if (httpDownloads.containsKey(packName)) {
            Logger.i(tag, "A download of $packName is already outstanding; not starting another")
            report(packName, AssetPackStatus.UNKNOWN, 0)
            return
        }
        // Published before the task is queued rather than reset once it starts.
        // A cancellation can arrive while this pack is still waiting behind
        // another one, and it has to survive the wait: resetting at task start
        // would discard it just as reliably as the shared flag did.
        val download = HttpDownload()
        httpDownloads[packName] = download
        report(packName, AssetPackStatus.PENDING, 0)

        ioExecutor.execute {
            // Per download, not per class. The finally below deletes this
            // directory whole, and it used to be one constant path shared by
            // every ToolchainManager in the process: nine call sites each build
            // their own, ioExecutor is an instance field so it serialises
            // nothing across them, and rotating ToolchainActivity or cancelling
            // the first-run queue leaves an earlier download still running. Two
            // downloads then wrote one zip path and each finally deleted the
            // other's tree, which surfaces as a digest mismatch, the alarm for
            // tampering, fired by a race with ourselves.
            val tempDir = toolchainTempDir(context.cacheDir, packName)
            val zipFile = File(tempDir, "$packName.zip")
            val extractDir = File(tempDir, packName)

            try {
                // Asked before anything else, the space pre-flight included.
                // Every other check of this flag sits past the manifest fetch,
                // so a pack cancelled while queued still spent a request and,
                // on a stalled connection, up to three read timeouts of it,
                // with the first-run queue waiting behind. The pre-flight has
                // to come after it because the pre-flight fails the pack: a
                // download cancelled while it waited behind another transfer,
                // on a device short of the reservation, was reported as a
                // storage failure, a Retry badge and a "not enough space" toast
                // for a transfer the user had just stopped, and only then as
                // CANCELED by the finally below.
                if (download.cancelled) {
                    Logger.i(tag, "HTTP download cancelled for $packName")
                    return@execute
                }

                // Pre-flight disk space check
                val stat = StatFs(context.filesDir.absolutePath)
                val availableBytes = stat.availableBytes
                val requiredBytes = toolchainInstallBytes(unpackedBytes)
                if (availableBytes < requiredBytes) {
                    Logger.e(tag, "Not enough disk space: ${availableBytes / 1_000_000} MB available, " +
                            "${requiredBytes / 1_000_000} MB required")
                    fail(packName, ToolchainFailure.STORAGE)
                    return@execute
                }

                tempDir.mkdirs()

                // Asked again after the pre-flight: a cancel that lands while the
                // StatFs above is being read is otherwise not seen until after
                // the manifest fetch, which is the request this flag exists to
                // save on a stalled connection.
                if (download.cancelled) {
                    Logger.i(tag, "HTTP download cancelled for $packName")
                    return@execute
                }

                // Once, before either request, so the manifest and the payload
                // name the same release however long the transfer takes. Falls
                // back to the unpinned URL, which is what shipped before this.
                val pinnedUrl = pinLatest(url, download)

                // Resolved before the payload, not after. A release that cannot
                // vouch for this ZIP should cost a few hundred bytes and a clear
                // refusal, rather than 179 MB and then a refusal.
                val expectedDigest = publishedDigestFor(pinnedUrl, download)

                // Download
                retrying(packName, download) {
                    try {
                        downloadFile(packName, pinnedUrl, zipFile, downloadBytes, download)
                    } catch (e: RangeRefused) {
                        // Between attempts, because it is the only thing that
                        // changes what the next one asks for. [downloadFile]
                        // derives its `Range` from the bytes on disk, so a
                        // refusal of that offset is a refusal every attempt
                        // repeats verbatim: all three fail identically and the
                        // user is shown a network error for an origin that is
                        // answering. Dropping the partial file is what turns the
                        // resume back into a fresh request for the whole asset.
                        // Reachable when the release asset is re-uploaded
                        // smaller between attempts, and when an earlier attempt
                        // wrote more bytes than the origin declared.
                        Logger.w(tag, "$packName: the origin refuses to resume; starting again", e)
                        zipFile.delete()
                        throw e
                    }
                }

                if (download.cancelled) {
                    Logger.i(tag, "HTTP download cancelled for $packName")
                    return@execute
                }

                // The check this path did not have. Content-Length says the
                // transfer finished; only this says the bytes are the ones the
                // release published. Everything below it -- extraction, chmod,
                // symlinks into usr/ -- treats the archive as trusted, so this
                // is the last point at which it can be refused.
                val actualDigest = sha256Of(zipFile)
                if (!actualDigest.equals(expectedDigest, ignoreCase = true)) {
                    Logger.e(
                        tag,
                        "Digest mismatch for $packName: the release publishes $expectedDigest, " +
                            "the download hashes to $actualDigest. Not installing it.",
                    )
                    fail(packName, ToolchainFailure.DIGEST)
                    return@execute
                }
                Logger.i(tag, "$packName matches the digest the release publishes")

                // Asked again, because the digest above hashes the whole archive
                // and the card offers Cancel throughout it. Everything past this
                // line writes: the extraction fills a staging tree the size of the
                // unpacked toolchain, and the copy after it is ~155 MB into `usr/`.
                if (download.cancelled) {
                    Logger.i(tag, "HTTP download cancelled for $packName")
                    return@execute
                }

                // Extract: report as TRANSFERRING (file copy phase)
                download.percent = 90
                report(packName, AssetPackStatus.TRANSFERRING, 90)
                extractDir.deleteRecursively()
                extractDir.mkdirs()
                extractZip(zipFile, extractDir)
                // Before the copy, which is the other half of the peak. The
                // digest was checked above and nothing reads the archive again,
                // so holding it through installFromDirectory buys nothing and
                // costs a device its whole download size in headroom.
                zipFile.delete()

                // The last point at which stopping leaves nothing behind, and the
                // reason the check is here rather than inside the copy. Extraction
                // has just spent seconds on a tree the size of the toolchain, and
                // the card has been offering Cancel for all of it; but
                // [installFromDirectoryHoldingPack] copies into the shared `usr/`
                // tree and only then writes the manifest naming what it put there,
                // so a copy abandoned partway leaves files no uninstall can name,
                // which is worse than the unwanted install this check refuses. The
                // staging tree above is this download's own and the finally below
                // deletes it whole.
                if (download.cancelled) {
                    Logger.i(tag, "HTTP download cancelled for $packName")
                    return@execute
                }

                // Install from extracted directory (same path as Play Asset Delivery)
                //
                // The answer is not read here, unlike the Play path: a decline
                // leaves this task with nothing to undo. The finally below
                // deletes a staging directory this download owns alone, so it
                // cannot reach whatever the install that holds the pack is
                // reading.
                installFromDirectory(packName, extractDir)

            } catch (e: MissingFromRelease) {
                // Ahead of the IOException it extends, because they are not the
                // same event to a user: this one will not succeed on a better
                // connection, and only a release changes it.
                Logger.e(tag, "Not published in the release: $packName", e)
                fail(packName, ToolchainFailure.NOT_PUBLISHED)
            } catch (e: IOException) {
                if (download.cancelled) {
                    Logger.i(tag, "HTTP download cancelled for $packName")
                } else {
                    Logger.e(tag, "HTTP download failed for $packName", e)
                    fail(packName, ToolchainFailure.NETWORK)
                }
            } catch (e: SecurityException) {
                Logger.e(tag, "Zip security violation for $packName", e)
                fail(packName, ToolchainFailure.CORRUPT)
            } catch (e: Exception) {
                Logger.e(tag, "Unexpected error downloading $packName", e)
                fail(packName, ToolchainFailure.INTERNAL)
            } finally {
                // Two-argument remove: a later request for the same pack has
                // already replaced this entry, and dropping its token would
                // leave that download uncancellable.
                val heldThePack = httpDownloads.remove(packName, download)
                // Clean up temp files
                tempDir.deleteRecursively()
                // The cancellation, said out loud. Every `return@execute` on the
                // flag above leaves without reporting, and a report goes to the
                // manager that began this download and to no other, so what the
                // silence withheld was withheld from the only party that could
                // act on it. The toolchain screen takes the pack out of its own
                // outstanding set when Cancel is tapped, but that set belongs to
                // the screen the tap happened on, while the token is process-wide
                // precisely so a rebuilt screen can stop a transfer the destroyed
                // one began. Cancelled that way, the destroyed screen's set kept
                // the pack, and with it the Play Core registration
                // `ToolchainActivity.shouldReleaseSubscription` hands back only
                // when that set empties. The first-run queue is the other reader
                // that needs an ending: it advances on a terminal status, so the
                // row sat where it was with every pack behind it waiting.
                //
                // Here rather than at each of those exits, so a check added later
                // at a new expensive boundary cannot forget it, and after the
                // bookkeeping above so a listener asking [packsDownloading] on
                // its way through does not read this transfer as still running.
                // Only while this token still held the pack: once a later request
                // has replaced it, that download owns what is said about the pack.
                //
                // CANCELED, because nothing failed and every listener already has
                // a branch for it -- Play emits it on the other delivery path. A
                // cancel landing during the final copy is reported after that
                // copy's COMPLETED, which costs nothing: the card reads CANCELED
                // against the install record and draws Remove, and the first-run
                // queue has already moved past the pack.
                if (heldThePack && download.cancelled) {
                    report(packName, AssetPackStatus.CANCELED, 0)
                }
            }
        }
    }

    /**
     * Runs [attempt] up to MAX_RETRIES + 1 times with exponential backoff.
     *
     * Shared by both things this class fetches, and that is a correctness
     * property rather than deduplication. It was written for the ZIP alone, and
     * putting the manifest fetch in front of it without routing it through
     * meant a zero-tolerance request ran ahead of a fault-tolerant one: on
     * mobile data, a stall that the ZIP would have absorbed ended the whole
     * install after one read timeout, having transferred nothing. Hardening the
     * payload is no good if it lowers the odds of getting one.
     *
     * The one thing it does not retry is [MissingFromRelease], which stays
     * right for both: a ZIP not uploaded to the release will not appear on the
     * third attempt, and neither will a manifest the release does not carry.
     * Everything else gets all three.
     */
    @Throws(IOException::class)
    private fun <T> retrying(what: String, download: HttpDownload, attempt: () -> T): T {
        var lastException: IOException? = null
        for (n in 0..MAX_RETRIES) {
            try {
                if (n > 0) {
                    val backoffMs = (1L shl n) * 1000  // 2s, 4s
                    Logger.i(tag, "Retry $n/$MAX_RETRIES for $what after ${backoffMs}ms")
                    Thread.sleep(backoffMs)
                }
                return attempt()
            } catch (e: MissingFromRelease) {
                // Must precede the IOException catch below, which it extends.
                // The release does not carry this file; asking twice more only
                // spends six seconds arriving at the same answer.
                throw e
            } catch (e: IOException) {
                lastException = e
                if (download.cancelled) throw e
                Logger.w(tag, "Attempt $n failed for $what: ${e.message}")
            }
        }
        throw lastException ?: IOException("$what failed after ${MAX_RETRIES + 1} attempts")
    }

    /**
     * Opens [url], following redirects (GitHub → CDN) up to MAX_REDIRECTS hops,
     * and hands the connected 200 or 206 response to [body].
     *
     * Shared by the two things this class fetches, which is the point: they must
     * agree about timeouts, redirects and transfer encoding or the digest one of
     * them publishes describes a body the other one did not receive. [what]
     * names the artifact so a 404 says which of them is missing from the
     * release.
     *
     * Two statuses become types rather than a bare IOException, because a caller
     * has to act on each: 404 is [MissingFromRelease] and 416 is [RangeRefused].
     * Anything else outside {200, 206} is an IOException. A 206 reaches [body]
     * because [downloadFile] resumes, so [body] has to ask which of the two it
     * got: 206 is the remainder of a body already part-written, 200 is the whole
     * file coming again and its output stream must truncate.
     *
     * @param requestHeaders set on every hop, not only the first. The only header
     *   any caller sends is `Range`, and the hop that serves the bytes is the
     *   redirect target, so dropping it at the redirect is exactly where a resume
     *   would stop working.
     */
    @Throws(IOException::class)
    private fun <T> withRedirects(
        url: String,
        what: String,
        requestHeaders: Map<String, String> = emptyMap(),
        body: (HttpURLConnection) -> T,
    ): T {
        var currentUrl = url
        var redirects = 0

        while (redirects < MAX_REDIRECTS) {
            val conn = URL(currentUrl).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = HTTP_TIMEOUT_MS
                conn.readTimeout = HTTP_TIMEOUT_MS
                conn.instanceFollowRedirects = false
                conn.setRequestProperty("User-Agent", "VSCodroid")
                // Asked for explicitly, because the length check in downloadFile
                // is only sound if the body arrives as the header describes it.
                // HttpURLConnection otherwise advertises gzip on its own and
                // decodes it transparently, which leaves Content-Length
                // measuring the compressed stream while the loop counts the
                // decompressed one -- every download would then look truncated.
                // Costs nothing here: these payloads are ZIPs, which no origin
                // usefully compresses a second time.
                conn.setRequestProperty("Accept-Encoding", "identity")
                // Carried across every hop, deliberately. The only header any
                // caller adds is `Range`, and dropping it at the redirect to the
                // signed CDN address is precisely where it would stop working:
                // that is the hop that serves the bytes.
                requestHeaders.forEach { (name, value) -> conn.setRequestProperty(name, value) }

                val responseCode = conn.responseCode

                if (responseCode in 300..399) {
                    val location = conn.getHeaderField("Location")
                        ?: throw IOException("Redirect with no Location header from $currentUrl")
                    currentUrl = nextRedirectUrl(currentUrl, location)
                    redirects++
                    conn.disconnect()
                    continue
                }

                if (responseCode == 404) {
                    throw MissingFromRelease("404 Not Found: $currentUrl ($what not uploaded to release?)")
                }

                // Only [downloadFile] sends a `Range`, so only it can get this,
                // and only it can act on it: the offset it asked from is past
                // the end of what this origin will serve.
                if (responseCode == 416) {
                    throw RangeRefused("416 Range Not Satisfiable: $currentUrl ($what)")
                }

                // 206 is a server honouring a `Range`, which only [downloadFile]
                // asks for. Accepted here rather than in the caller because this
                // is where a status becomes a refusal; the caller still has to
                // ask which of the two it got, since 200 means the whole file is
                // coming again and its output stream must truncate.
                if (responseCode != 200 && responseCode != 206) {
                    throw IOException("HTTP $responseCode from $currentUrl")
                }

                return body(conn)
            } finally {
                conn.disconnect()
            }
        }

        throw IOException("Too many redirects ($MAX_REDIRECTS) for $url")
    }

    /**
     * Reads the release's digest manifest.
     *
     * Bounded rather than read whole. The body is a few hundred bytes of
     * `sha256sum` output, and this function exists because the payload it
     * describes is not to be taken on trust -- reading an unbounded remote body
     * into memory on the way to saying so would be the same trust by another
     * name.
     *
     * A body cut at the bound cannot produce a wrong digest, though it is worth
     * being exact about why, because there are two cases and only one of them
     * is the obvious one. A cut landing in the *digest* leaves a field that is
     * not 64 hex characters, which [digestFromManifest] skips. A cut landing in
     * the *name* leaves a perfectly valid digest beside a shortened filename --
     * nothing rejects that line, and nothing needs to: names are compared whole,
     * so it matches no ZIP anyone asked for. Either way the lookup finds
     * nothing and the install is refused. (This said "a line cut mid-way fails
     * the 64-hex test", which is true of the first case only.)
     */
    @Throws(IOException::class)
    private fun fetchManifest(url: String): String =
        withRedirects(url, "digest manifest") { conn ->
            val buffer = CharArray(MANIFEST_MAX_CHARS)
            conn.inputStream.bufferedReader().use { reader ->
                var total = 0
                while (total < buffer.size) {
                    val n = reader.read(buffer, total, buffer.size - total)
                    if (n < 0) break
                    total += n
                }
                String(buffer, 0, total)
            }
        }

    /**
     * [zipUrl] pointed at a concrete release, or [zipUrl] unchanged when none
     * can be resolved.
     *
     * The release this build belongs to is preferred, and `latest` is the
     * fallback rather than the first choice. `latest` names whichever release
     * is newest at the moment of the request, which is not necessarily one this
     * app was built alongside, and the gap is not theoretical. Measured
     * 2026-08-19, with `latest` naming v1.1.0:
     *
     * ```
     * releases/latest/download/toolchain_go.zip  -> 404
     * releases/download/v1.0.0/toolchain_go.zip  -> 200
     * ```
     *
     * An installed v1.0.0 offers Go in its picker, because its own registry
     * lists it, and v1.1.0 does not publish that ZIP because the entry was
     * retired. Through `latest` that install can no longer fetch a payload its
     * own release still carries. The reverse costs the same: a release deleted
     * after publication moves `latest` backwards, onto a release built before
     * whatever the installed app now requires.
     *
     * Note what this cannot do, because the shape recurs. It ships in an app
     * version, so it repairs the population that installs that version onward
     * and nothing already on a device. v1.0.0 keeps resolving through `latest`
     * forever.
     *
     * The manifest and the payload are two requests minutes apart, and both used
     * to go through `latest` independently, so a release published between them
     * handed back bytes to check against a digest read from the release before
     * it. Resolving once and building both URLs from the answer is what closes
     * that; [manifestUrlFor] derives the manifest beside the ZIP, so pinning the
     * ZIP pins the manifest for free.
     *
     * One HEAD, one hop, no body. `releases/latest` answers `releases/tag/<tag>`
     * and involves no asset, which is why it is asked rather than the asset URL:
     * that one redirects twice and ends at a signed CDN address carrying no tag.
     *
     * **Falls back to the unpinned URL when the resolution fails, deliberately.**
     * A wrong pin would 404 every toolchain for everyone, while the unpinned URL
     * is what shipped before this existed.
     *
     * That floor covers a failure to resolve and nothing more, which is narrower
     * than this once claimed. Once a pin IS returned, both requests use it and
     * neither falls back: a 404 on the pinned release becomes
     * [MissingFromRelease], which [retrying] rethrows on the first attempt, and
     * the install is refused. So a release published inside the one request
     * between the HEAD and the manifest fetch pins to the older one and fails
     * where the unpinned path would have taken both files from the newer.
     *
     * That window is one request wide against a transfer measured in minutes,
     * it fails closed, and retrying succeeds, so it is the price of closing the
     * wide window rather than a defect in the trade. It is written down because
     * the sentence it replaces asserted the price could not exist, and the next
     * reader would have believed it.
     */
    private fun pinLatest(zipUrl: String, download: HttpDownload): String {
        // Before the request, for the reason the check above this call records: a
        // pack cancelled while queued must not spend one, and this one carries a
        // 30 s connect and a 30 s read timeout that nothing else here would
        // interrupt.
        if (download.cancelled) return zipUrl
        ownReleaseAssetUrl(zipUrl)?.let {
            Logger.i(tag, "Pinned this install to ${it.substringBeforeLast('/')}")
            return it
        }
        if (download.cancelled) return zipUrl
        val latestUrl = latestReleaseUrlFor(zipUrl) ?: return zipUrl
        val pinned = try {
            val conn = URL(latestUrl).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = HTTP_TIMEOUT_MS
                conn.readTimeout = HTTP_TIMEOUT_MS
                conn.instanceFollowRedirects = false
                conn.requestMethod = "HEAD"
                conn.setRequestProperty("User-Agent", "VSCodroid")
                val code = conn.responseCode
                val location = if (code in 300..399) conn.getHeaderField("Location") else null
                location?.let { releaseTagFromLocation(it) }?.let { pinnedAssetUrl(zipUrl, it) }
            } finally {
                conn.disconnect()
            }
        } catch (e: IOException) {
            Logger.w(tag, "Could not resolve the latest release: ${e.message}")
            null
        }
        if (pinned == null) {
            Logger.w(
                tag,
                "Falling back to the unpinned release URL; the digest and the payload " +
                    "may come from different releases if one is published mid-download",
            )
            return zipUrl
        }
        Logger.i(tag, "Pinned this install to ${pinned.substringBeforeLast('/')}")
        return pinned
    }

    /**
     * [zipUrl] served from this build's own release, or null.
     *
     * Null covers three different things on purpose, because the caller does
     * the same thing with all of them: this build's version does not name a
     * tag, no release carries that tag, or the release carries it but not this
     * asset. Each leaves `latest` as the answer, which is what shipped before
     * this existed.
     *
     * One HEAD against the asset itself rather than against the release page,
     * because the asset is the question. Measured 2026-08-19, redirects not
     * followed: a published asset answers 302 and both a missing asset and a
     * missing tag answer 404, so one request separates every case that matters.
     * Asking the release page instead would answer 200 for a release whose
     * toolchain step failed, pin to it, and turn a fallback into a refused
     * install two requests later.
     */
    private fun ownReleaseAssetUrl(zipUrl: String): String? {
        val ownTag = appReleaseTag(BuildConfig.VERSION_NAME) ?: return null
        val url = pinnedAssetUrl(zipUrl, ownTag) ?: return null
        if (assetIsPublished(url)) return url
        Logger.i(
            tag,
            "Release $ownTag does not publish ${zipUrl.substringAfterLast('/')}; " +
                "falling back to the latest release",
        )
        return null
    }

    /**
     * Whether the server will serve [url], asked with a HEAD and no body.
     *
     * Redirects are not followed, so the 302 that a published asset answers is
     * the success and there is no transfer behind it. Any failure to ask counts
     * as no: the caller's fallback is the behaviour that shipped before this,
     * and a network that cannot answer this cannot serve the payload either.
     */
    private fun assetIsPublished(url: String): Boolean = try {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = HTTP_TIMEOUT_MS
            conn.readTimeout = HTTP_TIMEOUT_MS
            conn.instanceFollowRedirects = false
            conn.requestMethod = "HEAD"
            conn.setRequestProperty("User-Agent", "VSCodroid")
            conn.responseCode in 200..399
        } finally {
            conn.disconnect()
        }
    } catch (e: IOException) {
        Logger.w(tag, "Could not ask whether $url is published: ${e.message}")
        false
    }

    /**
     * The digest the release publishes for [zipName], or a refusal.
     *
     * Fetched before the ZIP rather than after, so a release that cannot vouch
     * for its payload costs a few hundred bytes instead of 179 MB.
     *
     * There is no fallback, and that is the whole change: silently installing
     * what nothing vouches for is the behaviour being removed, so it cannot be
     * what happens when the manifest is missing. A release without one -- or
     * with one that does not name this ZIP -- fails the install and says why.
     * Releases cannot reach that state unnoticed: the same step in `release.yml`
     * that packages the ZIPs writes this manifest and fails the release if a ZIP
     * named in [ToolchainRegistry] is missing from either.
     *
     * Retried on the same terms as the payload. Being first in the sequence and
     * less tolerant than what follows it is the one way this check could make
     * installs worse rather than safer.
     */
    @Throws(IOException::class)
    private fun publishedDigestFor(zipUrl: String, download: HttpDownload): String {
        val zipName = zipUrl.substringAfterLast('/')
        val manifest = retrying("$zipName digest manifest", download) {
            fetchManifest(manifestUrlFor(zipUrl))
        }
        return digestFromManifest(manifest, zipName)
            // [MissingFromRelease], not the plain IOException it extends. The two are
            // caught in that order and mean different things to the user, and this
            // case was landing in the wrong one: the manifest was fetched, so nothing
            // about the network is at fault, yet `catch (e: IOException)` reported
            // "Download failed. Check your connection and try again." A better
            // connection cannot produce a manifest entry that is not there.
            //
            // The release is incomplete, which is exactly what NOT_PUBLISHED already
            // says: "not in the current release, try again after the next app update."
            // `digestFromManifest` also answers null for two conflicting entries, and
            // that is the same answer for the same reason -- nothing here can say
            // which one vouches for the payload.
            ?: throw MissingFromRelease(
                "The release's digest manifest does not name $zipName; refusing to install a " +
                    "payload nothing vouches for"
            )
    }

    /**
     * Downloads the toolchain ZIP, reporting progress as DOWNLOADING.
     *
     * Refuses a body shorter or longer than the server said it would send, which
     * is worth stating as narrowly as it holds: it catches a transfer that ends
     * early -- a connection dropped mid-stream, a proxy closing a chunked body
     * without finishing it -- and it says nothing at all about whether the bytes
     * are the right ones. A payload that arrives complete and wrong matches its
     * own Content-Length and passes here.
     *
     * That second question is answered by the caller rather than here, against
     * the digest the release publishes; see [publishedDigestFor]. The two checks
     * are kept apart because they fail differently: a short read is a transfer
     * fault and worth retrying, so it is thrown from inside the retry loop,
     * while a complete body whose digest is wrong is not going to become right
     * on the third attempt.
     *
     * A retry picks up where the last attempt stopped rather than starting
     * again. [retrying] gives three attempts, and each of them used to open the
     * destination truncating and ask for the whole file: a mobile connection
     * that dropped at 50 MB of the 55.4 MB Java ZIP spent those 50 MB again, up
     * to about 166 MB of a metered allowance for one toolchain. So a second
     * attempt that finds bytes already written asks for the rest with `Range`
     * and appends. The staging directory belongs to this download alone
     * ([toolchainTempDir]), so what is on disk can only be this transfer's own
     * earlier bytes.
     *
     * A server that ignores the header answers 200 with the whole body, which is
     * why the response code decides whether the stream appends or truncates
     * rather than the request doing so. A server that honours it wrongly, from
     * an offset other than the one asked for, produces an archive whose digest
     * does not match what the release published, and the caller refuses it: this
     * cannot install anything the digest check would not have installed anyway.
     * A server that refuses the offset outright answers 416, which is
     * [RangeRefused] and is the one failure the caller has to act on rather than
     * repeat; see where [retrying] wraps this call.
     */
    @Throws(IOException::class)
    private fun downloadFile(
        packName: String,
        url: String,
        destFile: File,
        expectedTransferBytes: Long,
        download: HttpDownload,
    ) {
        // Read before the request, because it is what the request asks for. Zero
        // on a first attempt: the file does not exist yet.
        val alreadyHave = destFile.length()
        val headers =
            if (alreadyHave > 0) mapOf("Range" to "bytes=$alreadyHave-") else emptyMap()
        withRedirects(url, "toolchain ZIP", headers) { conn ->
            // 206 means the server is sending the remainder and the bytes on disk
            // stand; anything else means it is sending the file from the start and
            // they do not.
            val resumed = conn.responseCode == 206
            val startAt = if (resumed) alreadyHave else 0L
            if (alreadyHave > 0 && !resumed) {
                Logger.i(tag, "$packName: the server did not honour Range, starting again")
            }
            // Two different numbers, kept apart. The progress denominator may
            // fall back to the registry's download figure, which is a constant
            // written by hand; the completeness check may not,
            // because that constant goes stale the moment a payload is
            // rebuilt and would then fail every download of a healthy file.
            // Only a length the server actually sent is evidence of
            // anything -- and on a 206 that length is the remainder, which is
            // what the check below compares against and why the bar adds the
            // part already on disk to it.
            val declaredBytes = conn.contentLengthLong
            val totalBytes = if (declaredBytes > 0) startAt + declaredBytes else expectedTransferBytes

            fun percentOf(bytes: Long): Int =
                if (totalBytes > 0) ((bytes * 85) / totalBytes).toInt().coerceAtMost(85) else 0

            // The figure a resumed transfer starts from, not zero: reporting zero
            // here would walk the bar backwards on every retry.
            val startPercent = percentOf(startAt)
            report(packName, AssetPackStatus.DOWNLOADING, startPercent)
            download.percent = startPercent

            var bytesRead = 0L
            // What the last report said, so the loop below reports a figure rather
            // than an iteration. Starts at the figure just reported, which is also
            // the whole of what a transfer with no length to divide by can ever say.
            var lastReported = startPercent
            BufferedInputStream(conn.inputStream).use { input ->
                FileOutputStream(destFile, resumed).use { output ->
                    val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
                    var len: Int

                    while (input.read(buffer).also { len = it } != -1) {
                        if (download.cancelled) {
                            throw IOException("Download cancelled")
                        }
                        output.write(buffer, 0, len)
                        bytesRead += len
                        val percent = percentOf(startAt + bytesRead)
                        // On the token as well as in the report: the report reaches
                        // only the manager that began this download, and a screen
                        // rebuilt mid-transfer holds a different one. Written every
                        // iteration, unlike the report, because it costs a field
                        // store and it is what [packsDownloading] hands the screens
                        // that were never subscribed.
                        download.percent = percent
                        // Only when the figure has moved. The buffer is 8 KB and
                        // `percent` takes 86 values, so a 55 MB toolchain reported
                        // 6,763 times to say 86 different things, and both consumers
                        // post every one of them onto the main thread: the card
                        // rebinds and the first-run row re-formats its text. The poll
                        // channel on the toolchain screen already refuses to push an
                        // unchanged snapshot for exactly this reason.
                        if (percent != lastReported) {
                            lastReported = percent
                            report(packName, AssetPackStatus.DOWNLOADING, percent)
                        }
                    }
                }
            }

            if (!isCompleteTransfer(declaredBytes, bytesRead)) {
                // The counts belong in the message. They were held out of it and
                // logged separately, because retryability was decided by looking
                // for "404" as a substring and a byte count that happened to read
                // 404 would have turned a retryable truncation into an immediate
                // failure. That predicate is a type now, so the workaround has
                // nothing left to work around, and the retry loop logs the message
                // on every attempt.
                throw IOException(
                    "Incomplete download for $packName: received $bytesRead bytes of $declaredBytes declared"
                )
            }

            Logger.i(tag, "Downloaded $packName: ${destFile.length() / 1_000_000} MB")
        }
    }

    /**
     * Extracts a ZIP archive to the destination directory.
     * Includes zip-slip protection (rejects entries that escape destDir).
     */
    @Throws(IOException::class, SecurityException::class)
    private fun extractZip(zipFile: File, destDir: File) {
        val canonicalDest = destDir.canonicalPath

        ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val outFile = File(destDir, entry.name)
                val canonicalOut = outFile.canonicalPath

                if (!canonicalOut.startsWith(canonicalDest + File.separator) && canonicalOut != canonicalDest) {
                    throw SecurityException("Zip slip detected: ${entry.name}")
                }

                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { out ->
                        zis.copyTo(out)
                    }
                }

                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        Logger.i(tag, "Extracted ${zipFile.name} to ${destDir.absolutePath}")
    }

    // -- Shared file operations --

    private fun copyDirectoryRecursively(src: File, dest: File) =
        copyDirectoryTree(src, dest)

    // -- Environment file generation --

    /**
     * Regenerates both files derived from the installed set.
     *
     * `~/.vscodroid/toolchain-env.sh` is sourced by `.bashrc` and by the
     * `BASH_ENV` file, so a terminal and a `bash -c` pick up the toolchain's
     * environment and its loader wrappers. `~/.vscodroid/toolchain-exec.tsv`
     * and the symlinks beside it answer the same question for every caller that
     * never reaches bash.
     */
    fun regenerateDerivedFiles() = synchronized(stateLock) { regenerateDerivedFilesLocked() }

    /**
     * Everything derived from `toolchains.json`, regenerated together.
     *
     * The two generators answer the same question for two different readers:
     * `toolchain-env.sh` tells bash what a toolchain command means, and
     * `toolchain-exec.tsv` tells the execution trampoline the same thing for
     * every caller that never reaches bash. They are called as one because the
     * three moments the installed set changes are the three moments both have to
     * be rewritten, and a call site that remembered one and forgot the other
     * would leave the terminal working while a task or a `make` recipe answered
     * exit 127, which reads as the toolchain not being installed.
     *
     * Caller must hold [stateLock].
     */
    private fun regenerateDerivedFilesLocked() {
        regenerateEnvFileLocked()
        regenerateExecTableLocked()
    }

    /**
     * Path to the system dynamic linker, which is the only way a toolchain
     * binary runs at all.
     *
     * SELinux denies `execute_no_trans` on `app_data_file`, so the kernel
     * refuses to `execve` anything whose inode is under `filesDir` -- which is
     * every byte a downloaded toolchain consists of. Measured on an API 37
     * emulator, `user` build, SELinux enforcing, from inside the app's own
     * process: a valid ELF copied there with mode 0755 fails with EACCES, and
     * it fails identically when reached through a symlink, because the check is
     * on the resolved inode's label rather than on the path. `chmod` cannot
     * reach it.
     *
     * Handing the same file to a loader that lives somewhere the app *may*
     * execute from does run it: `/system/bin/linker64 <binary>` returned 0 and
     * the program's own output, arguments and quoting survived, a non-zero exit
     * code came back intact, and `$0` was the binary's path rather than the
     * loader's. It is the shape the bundled Claude Code CLI already relies on.
     */
    private val systemLoader = "/system/bin/linker64"

    /**
     * Caller must hold [stateLock]. This file is derived from `toolchains.json`,
     * so regenerating it from a state another instance is midway through
     * changing produces an environment for a set of toolchains that never
     * existed.
     */
    private fun regenerateEnvFileLocked() {
        val installed = readableState()
        if (installed == null) {
            // Damage is not absence. The state file this could not parse was written
            // by a build that truncated it in place, and the devices carrying one
            // are upgrading from exactly that build. Reading it as "no toolchains"
            // deletes the env file below, the only working record of how to run
            // what is still on disk, and every launch deletes it again. Leaving
            // the last good file is what the bashrc repair does for the same damage.
            Logger.w(tag, "toolchains.json is unreadable; keeping the env file as it stands")
            return
        }
        if (installed.length() == 0) {
            if (envFile.exists()) envFile.delete()
            return
        }

        val sb = StringBuilder()
        sb.appendLine("# Auto-generated by ToolchainManager: do not edit")
        sb.appendLine("# Sourced by .bashrc for toolchain environment variables")
        sb.appendLine()

        val extraPaths = mutableListOf<String>()

        for (i in 0 until installed.length()) {
            val tc = installed.optJSONObject(i) ?: continue
            val name = tc.optString("name", "unknown")
            val env = tc.optJSONObject("env") ?: continue

            sb.appendLine("# $name toolchain")
            for (key in env.keys()) {
                // $HOME is deliberately left alone: the shell expands it when
                // it sources this file, which is what the manifest intends. It
                // used to be "replaced" with itself, which reads as a deliberate
                // transformation and is not one.
                val value = env.getString(key)
                    .replace("\$FILESDIR", "\$PREFIX/..")
                sb.appendLine("export $key=\"$value\"")
            }
            sb.appendLine()

            // Binary wrappers. Nothing under filesDir can be execve'd, so every
            // name PATH would resolve to is shadowed by a shell function that
            // hands the file to the system loader instead. Functions rather than
            // scripts for the same reason npm and npx are functions: a shebang
            // file under filesDir cannot be executed either.
            //
            // Only ELF objects are wrapped here. A manifest's `binaries` list
            // also carries interpreter scripts -- Ruby's `gem` and `rake` are
            // Ruby source -- and those are handled by scriptWrappers below,
            // which routes them through their interpreter. That interpreter is
            // itself one of these functions, so the two layers compose.
            val scriptNames = tc.optJSONObject("scriptWrappers")
                ?.optJSONObject("scripts")?.keys()?.asSequence()?.toSet().orEmpty()
            val binaries = tc.optJSONArray("binaries")
            if (binaries != null) {
                val lines = mutableListOf<String>()
                for (i in 0 until binaries.length()) {
                    val relPath = binaries.getString(i)
                    val command = relPath.substringAfterLast('/')
                    if (command.isEmpty() || command in scriptNames) continue
                    // A name bash cannot use as a function is skipped rather than
                    // written. The manifests are regenerated from upstream packages
                    // at build time, so a future version can introduce a binary
                    // called something like `[`, coreutils' own name for `test`,
                    // without anyone here choosing it -- and this file is sourced by
                    // .bashrc, so one unusable name is a parse error that takes out
                    // *every* new terminal, not just that command. Losing one
                    // wrapper is the smaller failure, and it says so in the log.
                    //
                    // The bar is [isShellFunctionName], which refuses a name
                    // carrying any of the measured unsafe characters. It is wider
                    // than a shell identifier: `2to3` and `foo-bar` pass it, and
                    // `ShellFunctionNameTest` establishes against a real bash that
                    // they are genuinely usable. This skips fewer names than it
                    // looks like it does, which is the intent. The exec table is
                    // generated over the full list either way, since a trampoline
                    // symlink has no naming constraint at all.
                    if (!isShellFunctionName(command)) {
                        Logger.w(tag, "No wrapper for $command: not a name bash can " +
                            "use as a function, so it would break every terminal")
                        continue
                    }
                    if (!isElfFile(File(context.filesDir, relPath))) continue
                    lines.add("$command() { $systemLoader \"\$PREFIX/../$relPath\" \"\$@\"; }")
                }
                if (lines.isNotEmpty()) {
                    sb.appendLine("# $name binaries (SELinux blocks exec under filesDir)")
                    lines.forEach(sb::appendLine)
                    sb.appendLine()
                }
            }

            // Script wrappers: bash functions for scripts that can't execute directly
            // on Android: SELinux denies execute_no_trans under filesDir, so a shebang
            // never runs. Invokes scripts via their interpreter instead.
            val scriptWrappers = tc.optJSONObject("scriptWrappers")
            if (scriptWrappers != null) {
                val interpreter = scriptWrappers.optString("interpreter", "")
                val scripts = scriptWrappers.optJSONObject("scripts")
                if (interpreter.isNotEmpty() && scripts != null) {
                    val lines = mutableListOf<String>()
                    for (scriptName in scripts.keys()) {
                        // The same bar the binaries above are held to, and for
                        // the same reason: these names are not this repository's
                        // choice either. `download-ruby.sh` takes each one from
                        // the `basename` of whatever upstream ships in the
                        // package's bin directory, splitting binaries from
                        // scripts by reading the file rather than by naming it,
                        // so a future package can introduce a name nobody here
                        // chose. This file is sourced by .bashrc, so a name bash
                        // cannot use as a function costs every wrapper written
                        // after it in every new terminal, not just this command.
                        // The exec table below carries it regardless: a
                        // trampoline symlink has no naming constraint.
                        if (!isShellFunctionName(scriptName)) {
                            Logger.w(tag, "No wrapper for $scriptName: not a name bash can " +
                                "use as a function, so it would break every terminal")
                            continue
                        }
                        val scriptPath = scripts.getString(scriptName)
                        lines.add("$scriptName() { $interpreter \"\$PREFIX/../$scriptPath\" \"\$@\"; }")
                    }
                    if (lines.isNotEmpty()) {
                        sb.appendLine("# $name script wrappers (SELinux blocks exec under filesDir)")
                        lines.forEach(sb::appendLine)
                        sb.appendLine()
                    }
                }
            }

            // Collect extra PATH dirs
            val pathDirs = tc.optJSONArray("pathDirs")
            if (pathDirs != null) {
                for (j in 0 until pathDirs.length()) {
                    extraPaths.add("\$PREFIX/../${pathDirs.getString(j)}")
                }
            }
        }

        if (extraPaths.isNotEmpty()) {
            val paths = extraPaths.joinToString(":")
            sb.appendLine("# Toolchain PATH additions")
            // Appended, never prepended. These directories hold the raw payload:
            // `usr/bin/ruby` is the interpreter itself and the JDK's bin holds
            // `java`, both ELF objects under filesDir that SELinux refuses to
            // execve. The trampoline directory that CAN start them is already on
            // PATH ahead of them, put there by
            // [Environment.buildProcessEnvironment], and a shell that prepended
            // these would restore the unexecutable copies in front of it for
            // itself and for every child it spawns. Bash function lookup happens
            // before PATH either way, so this line only decides what a
            // non-bash child of a bash shell resolves.
            //
            // They are kept rather than dropped as insurance for a future
            // manifest whose `binaries` list is incomplete: behind the
            // trampoline directory they cost nothing.
            sb.appendLine("export PATH=\"\$PATH:$paths\"")
        }

        envFile.parentFile?.mkdirs()
        // Atomic for the same reason as the state file, with a different cost on
        // failure: `.bashrc` sources this unconditionally, so a truncated copy is
        // not an absent environment but a sourcing error printed into every new
        // terminal, possibly with PATH half-built.
        if (!writeAtomically(envFile) { it.write(sb.toString().toByteArray()) }) {
            Logger.e(tag, "Could not write toolchain-env.sh; it still holds the previous environment")
            return
        }
        Logger.i(tag, "Regenerated toolchain-env.sh (${installed.length()} toolchains)")
    }

    /**
     * Writes the table the execution trampoline reads, then repoints the
     * symlinks that put its names on PATH.
     *
     * The shell functions [regenerateEnvFileLocked] emits reach one shell
     * family, and only once it has been told to read that file. Everything that
     * resolves a bare command name and execve's the result went on failing with
     * EACCES: a direct `spawn("ruby", args)` from an extension or a language
     * server, a `make` recipe (whose shell `patch-default-shell.py` compiled to
     * `/system/bin/sh`, which is mksh and has never heard of `BASH_ENV`), Node's
     * own `exec()` for the same reason, bash invoked as `sh`, and a
     * `"type": "process"` task. This file is what those callers reach instead.
     *
     * Two record shapes, matching the two the env file already emits:
     *
     *   `<command>\t<absolute path of an ELF>`
     *   `<command>\t<absolute path of the interpreter>\t<absolute path of a script>`
     *
     * and a third for the environment, with an empty command field:
     *
     *   `\t<variable>\t<value>`
     *
     * The environment travels here as well as in the env file because of when
     * each reader looks. Bash re-reads `toolchain-env.sh` in every shell; the
     * server process reads [getAllToolchainEnv] once, at start, and every
     * non-bash child inherits that snapshot. A toolchain installed while the
     * server ran therefore had its commands on PATH within the second and its
     * variables only after the next restart: measured on an API 37 emulator, a
     * `"type": "process"` task ran the trampoline's `ruby` with RUBYLIB unset, a
     * load path inside Termux's prefix and `LoadError` on `require "json"`,
     * while the same probe through bash worked. The empty command field is what
     * keeps the row harmless to a trampoline built before it existed: a
     * basename is never empty, so such a reader walks past it.
     *
     * Absolute, and expanded here rather than written as `$PREFIX/../` the way
     * the env file does. The trampoline is a C program and not a shell; a
     * `$PREFIX` in the table would be a literal path component that does not
     * exist.
     *
     * Two deliberate differences from the env file, each of which would be a
     * defect if it were quietly made symmetrical:
     *
     *   * [isShellFunctionName] is NOT applied. That filter exists because one
     *     name bash cannot use as a function is a parse error that takes out
     *     every new terminal, and it refuses any name carrying a measured unsafe
     *     character: coreutils' `[` is a real file name it turns away. A symlink
     *     has no naming constraint at all, so reusing the filter here would drop
     *     exactly the commands the trampoline was built to reach, and the only
     *     sign would be one missing command.
     *   * A later toolchain wins a name collision, which is what sourcing the
     *     env file top to bottom already does to a redefined bash function.
     *
     * Caller must hold [stateLock]. This is derived from `toolchains.json`, so
     * generating it from a state another instance is midway through changing
     * describes a set of toolchains that never existed.
     */
    private fun regenerateExecTableLocked() {
        val installed = readableState()
        if (installed == null) {
            // Damage is not absence, exactly as in [regenerateEnvFileLocked].
            // Deleting the table over a state file this could not parse would
            // take every toolchain command off PATH on every launch, while the
            // payload it names is still on disk and still runnable through the
            // table that was last written correctly.
            Logger.w(tag, "toolchains.json is unreadable; keeping the exec table as it stands")
            return
        }
        if (installed.length() == 0) {
            if (execTable.exists()) execTable.delete()
            refreshTrampolineLinks(emptySet())
            return
        }

        // Insertion-ordered so the file is stable between runs: a table that
        // reordered itself on every launch would make every diff of it
        // unreadable, and this file is one of the first things to look at when
        // a command goes missing.
        val rows = LinkedHashMap<String, String>()
        // Keyed by variable, so a later toolchain wins a collision: the same
        // outcome sourcing the env file top to bottom gives a redefined export.
        val envRows = LinkedHashMap<String, String>()
        for (i in 0 until installed.length()) {
            val tc = installed.optJSONObject(i) ?: continue
            val name = tc.optString("name", "unknown")
            val scriptNames = tc.optJSONObject("scriptWrappers")
                ?.optJSONObject("scripts")?.keys()?.asSequence()?.toSet().orEmpty()

            val env = tc.optJSONObject("env")
            if (env != null) {
                for (key in env.keys()) {
                    val value = expandToolchainValue(env.getString(key))
                    // A tab or a line break in the name, or a line break in the
                    // value, is a torn record: the trampoline reads what follows
                    // as a row of its own. `=` is what setenv itself refuses.
                    if (key.isEmpty() || key.any { it == '=' || it == '\t' || it == '\n' } ||
                        '\n' in value
                    ) {
                        Logger.w(tag, "No environment row for $name's $key: not a name " +
                            "the table can carry")
                        continue
                    }
                    envRows[key] = "\t$key\t$value"
                }
            }

            // Every ELF the manifest names, keyed by its command name, so the
            // script rows below can resolve their interpreter to a real path.
            val elfPaths = mutableMapOf<String, String>()
            val binaries = tc.optJSONArray("binaries")
            if (binaries != null) {
                for (j in 0 until binaries.length()) {
                    val relPath = binaries.getString(j)
                    val command = relPath.substringAfterLast('/')
                    if (command.isEmpty() || command in scriptNames) continue
                    if (!isElfFile(File(context.filesDir, relPath))) continue
                    val absolute = "$filesDir/$relPath"
                    elfPaths[command] = absolute
                    rows[command] = "$command\t$absolute"
                }
            }

            val scriptWrappers = tc.optJSONObject("scriptWrappers")
            val interpreter = scriptWrappers?.optString("interpreter", "").orEmpty()
            val scripts = scriptWrappers?.optJSONObject("scripts")
            if (interpreter.isNotEmpty() && scripts != null) {
                // The manifest names the interpreter as a bare word because the
                // env file's wrapper is read by a shell, which resolves it
                // against PATH. Nothing resolves it for the trampoline, and it
                // must not resolve it itself: searching PATH from inside the
                // trampoline would let a poisoned PATH decide which program runs
                // a toolchain's own scripts.
                val interpreterPath = elfPaths[interpreter]
                if (interpreterPath == null) {
                    Logger.w(tag, "No exec-table rows for $name scripts: its interpreter " +
                        "$interpreter is not an ELF this manifest ships")
                } else {
                    for (scriptName in scripts.keys()) {
                        val scriptPath = scripts.getString(scriptName)
                        rows[scriptName] = "$scriptName\t$interpreterPath\t$filesDir/$scriptPath"
                    }
                }
            }
        }

        val body = (envRows.values + rows.values).joinToString("\n", postfix = "\n")
        execTable.parentFile?.mkdirs()
        // Atomic for a reason of its own, not by imitation: a torn line is a
        // truncated path, and the trampoline would refuse it naming a file the
        // user never chose. A rename means a concurrent reader sees the whole of
        // the old table or the whole of the new one.
        if (!writeAtomically(execTable) { it.write(body.toByteArray()) }) {
            Logger.e(tag, "Could not write toolchain-exec.tsv; it still holds the previous table")
            return
        }
        refreshTrampolineLinks(rows.keys)
        Logger.i(tag, "Regenerated toolchain-exec.tsv (${rows.size} commands, " +
            "${envRows.size} variables)")
    }

    /**
     * Puts one symlink per table entry in front of PATH, and sweeps the rest.
     *
     * Failure here is contained on purpose. The caller's next act may be to
     * report an install as complete, and losing the links costs what this repair
     * already costs on the next launch, while letting the exception out would
     * lose the install record that names several hundred MB of files.
     */
    private fun refreshTrampolineLinks(commands: Set<String>) {
        try {
            refreshTrampolineLinksLocked(commands)
        } catch (e: Exception) {
            Logger.w(tag, "Could not refresh the toolchain trampoline links: ${e.message}")
        }
    }

    /**
     * Every name in [commands] becomes a symlink onto the single trampoline
     * binary in `nativeLibraryDir`; anything else in the directory is removed.
     *
     * The links point from filesDir INTO nativeLibraryDir and never the other
     * way round, which is the shape `FirstRunSetup.setupToolSymlinks` has proved
     * in production for ten binaries: the app cannot write to nativeLibraryDir,
     * and execve judges the resolved inode, which there is on a partition the
     * app may execute from.
     *
     * Rebuilt on every launch rather than only at install time, for the same
     * reason those ten are: Android hands out a new `nativeLibraryDir` path on
     * every reinstall, which dangles every absolute link into the old one.
     * Staleness is decided by reading the link rather than by `File.exists()`,
     * which follows a link and answers false for a dangling one, so a link
     * pointing at a directory that no longer exists would be read as absent and
     * then fail to be created.
     *
     * A link is created under a temporary name and renamed into place. The
     * delete-then-create that `setupToolSymlinks` performs leaves a window in
     * which the name does not exist at all, and unlike that pass this one runs
     * while the server is alive and something may be resolving PATH through the
     * directory. Rename within one directory is atomic, so a lookup sees the old
     * link or the new one. The existing window is narrow and has caused no
     * reported failure; this is the cheaper shape rather than a fix for
     * something observed.
     *
     * The links are created even when the trampoline binary is missing, which is
     * what a downgrade to a build without it produces. `execvp` treats ENOENT as
     * "keep looking further along PATH", so a dangling link degrades to exactly
     * today's behaviour rather than to something worse, and skipping the write
     * would instead leave a link pointing into a `nativeLibraryDir` that a
     * reinstall has already moved.
     */
    private fun refreshTrampolineLinksLocked(commands: Set<String>) {
        val target = Environment.getTrampolinePath(context)
        if (commands.isNotEmpty() && !tcBinDir.exists() && !tcBinDir.mkdirs()) {
            Logger.w(tag, "Could not create ${tcBinDir.absolutePath}; " +
                "toolchain commands stay unreachable to anything but bash")
            return
        }

        var created = 0
        var updated = 0
        for (command in commands) {
            val link = File(tcBinDir, command)
            val current = try { Os.readlink(link.absolutePath) } catch (e: Exception) { null }
            if (current == target) continue

            val staging = File(tcBinDir, ".$command.tmp~")
            staging.delete()
            try {
                Os.symlink(target, staging.absolutePath)
                Os.rename(staging.absolutePath, link.absolutePath)
                if (current == null) created++ else updated++
            } catch (e: Exception) {
                staging.delete()
                Logger.d(tag, "Failed to link $command to the trampoline: ${e.message}")
            }
        }

        // The sweep, and it is not tidiness. A name left behind after its
        // toolchain was uninstalled still resolves, so the command answers exit
        // 127 from the trampoline saying there is no entry for it, rather than
        // the shell's own "command not found" for a command the user has just
        // removed.
        var removed = 0
        for (entry in tcBinDir.listFiles().orEmpty()) {
            if (entry.name in commands) continue
            if (entry.delete()) removed++
        }

        if (created + updated + removed > 0) {
            Logger.i(tag, "Trampoline links: $created created, $updated repointed, " +
                "$removed removed in usr/libexec/tcbin/")
        }
    }

    /**
     * A manifest value with its placeholders resolved to this install's paths.
     *
     * Shared by the server environment and the exec table, which are two
     * readers of one promise: a value that expanded differently in the two
     * would hand a task a different GEM_PATH from the one the terminal beside
     * it was started with.
     */
    private fun expandToolchainValue(value: String): String =
        value.replace("\$FILESDIR", filesDir).replace("\$HOME", homeDir)

    /**
     * Returns resolved environment variables for all installed toolchains.
     * Used by Environment.kt to include in the Node.js server process env.
     *
     * Read once per server start, and that is the limit of what it can do: a
     * toolchain installed while the server runs is not in the map the server
     * already has. [regenerateExecTableLocked] writes the same variables into
     * the trampoline's table for that case, and bash re-reads the env file on
     * its own.
     */
    fun getAllToolchainEnv(): Map<String, String> {
        val installed = readState()
        val env = mutableMapOf<String, String>()
        val extraPaths = mutableListOf<String>()

        for (i in 0 until installed.length()) {
            val tc = installed.optJSONObject(i) ?: continue
            val tcEnv = tc.optJSONObject("env") ?: continue

            for (key in tcEnv.keys()) {
                env[key] = expandToolchainValue(tcEnv.getString(key))
            }

            val pathDirs = tc.optJSONArray("pathDirs")
            if (pathDirs != null) {
                for (j in 0 until pathDirs.length()) {
                    extraPaths.add("$filesDir/${pathDirs.getString(j)}")
                }
            }
        }

        if (extraPaths.isNotEmpty()) {
            env["__TOOLCHAIN_EXTRA_PATH"] = extraPaths.joinToString(":")
        }

        return env
    }

    // -- Repair of installs from earlier app versions --

    /**
     * Gives back the execute bit to binaries an older install left without one.
     *
     * A toolchain keeps the manifest it was installed with: it is persisted into
     * `toolchains.json`, `filesDir` survives app updates, and nothing rewrites it
     * at launch. So a packaging fix reaches new installs only, and the install
     * that was already there stays exactly as wrong as it was.
     *
     * The execute bit is set on the manifest's `binaries` entries and nowhere
     * else -- the recursive copy grants none, because `copyTo` does not carry
     * modes -- and an earlier Go manifest named `go` and `gofmt` alone, leaving
     * the `pkg/tool` binaries `go` forks without one.
     *
     * **This is necessary and it is not sufficient, and an earlier version of
     * this comment claimed otherwise.** It said the bit was "the difference
     * between working and not" for Go. It is not: SELinux denies
     * `execute_no_trans` on `app_data_file`, so nothing under `filesDir` can be
     * `execve`d whatever its mode. Measured from inside the app's own process on
     * an API 37 emulator, `user` build, enforcing -- a valid ELF there with mode
     * 0755 fails with EACCES, and fails identically through a symlink, because
     * the check is on the resolved inode's label rather than the path. What
     * actually makes a toolchain runnable is the loader indirection
     * [regenerateEnvFileLocked] writes; this pass only removes a second obstacle
     * sitting behind it.
     *
     * The bit is still worth restoring, because the loader will not run a file
     * the mode denies either. The payload is already on disk, so this needs no
     * download and no reinstall: every ELF object under the install root gets
     * the bit, which is the same rule the packaging gates use to decide what is
     * a binary. Scripts are deliberately not included -- they are wrapped in
     * shell functions that route them through their interpreter instead.
     *
     * Runs once per toolchain, recorded in its own state entry. A tree of several
     * thousand files is not something to walk on every launch, and an install
     * that never had the problem is marked without being walked at all.
     */
    fun repairInstalledToolchains() {
        ioExecutor.execute {
            try {
                // First, and before the env file is rewritten below. A retired
                // toolchain's wrappers go with it rather than being written out
                // again, and the repair that follows does not spend the launch
                // walking and chmod-ing several thousand files that are about to
                // be deleted. Go's tree alone is that size.
                removeRetiredToolchainsSync()
            } catch (e: Exception) {
                Logger.w(tag, "Could not remove a retired toolchain: ${e.message}")
            }
            try {
                // Before the repair rather than after it, because what it
                // reclaims is what the repair's own space pre-flights read.
                sweepAbandonedDownloadsSync()
            } catch (e: Exception) {
                Logger.w(tag, "Could not sweep abandoned toolchain downloads: ${e.message}")
            }
            try {
                repairInstalledToolchainsSync()
            } catch (e: Exception) {
                // A failed repair leaves the marker unset, so the next launch
                // tries again. Nothing else depends on it having run.
                Logger.w(tag, "Toolchain repair pass failed: ${e.message}")
            }
            try {
                // Unconditionally, and separately from the repair above, because
                // the two answer different questions. The repair is marked done per
                // toolchain and never runs again; this has to run on every launch,
                // because the env file is otherwise written only by an install or an
                // uninstall. A toolchain installed by an earlier version keeps the
                // file that version wrote -- so the loader wrappers, which are what
                // make a toolchain command runnable at all, reached new installs
                // only. That is the same shape as the bundled-extension defect this
                // release fixed: an improvement that skips exactly the installs that
                // need it.
                //
                // Cheap enough to do every time: it reads toolchains.json, formats a
                // few dozen lines and writes them atomically. With no toolchains
                // installed it deletes the file and returns.
                //
                // It is also where the trampoline table and its symlinks are
                // rebuilt, and they need this launch pass for a second reason of
                // their own: Android hands out a new nativeLibraryDir path on
                // every reinstall, so the links an install created point into a
                // directory that no longer exists until this repoints them.
                regenerateDerivedFiles()
            } catch (e: Exception) {
                Logger.w(tag, "Could not refresh the toolchain environment: ${e.message}")
            }
        }
    }

    /**
     * Deletes staging directories left behind by a download that never reached
     * its `finally`.
     *
     * [toolchainTempDir] gives every request a directory of its own, which is
     * what stopped two downloads deleting each other's work, and it moved the
     * cost of an abandoned one from "overwritten by the next attempt" to "kept
     * for ever". The other sweep is the user's:
     * `StorageManager.clearAbandonedToolchainDownloads`, reached from the Clear
     * action on the storage screen, takes every directory whose pack has no
     * transfer outstanding, however recent. This pass runs with nobody looking,
     * so it is the cautious one and waits a day.
     *
     * The bytes are not idle. Every toolchain space pre-flight reads
     * `StatFs(filesDir).availableBytes`, and `cacheDir` is on the same
     * filesystem, so three attempts abandoned mid-transfer -- backgrounded and
     * reclaimed, force-stopped, crashed -- leave roughly 170 MB apiece standing
     * between the user and the retry, which then refuses for want of space.
     *
     * Only entries older than [ABANDONED_DOWNLOAD_AGE_MS] are touched, and a
     * directory whose timestamp cannot be read is left alone. A running download
     * must never have its staging directory pulled out from under it, and the
     * cost of leaving one an extra day is a day of disk.
     */
    private fun sweepAbandonedDownloadsSync() {
        val root = File(context.cacheDir, "toolchain-download")
        val entries = root.listFiles() ?: return
        val now = System.currentTimeMillis()
        var removed = 0
        for (entry in entries) {
            if (!isAbandonedDownload(entry.lastModified(), now)) continue
            if (entry.deleteRecursively()) removed++
        }
        if (removed > 0) {
            Logger.i(tag, "Removed $removed abandoned toolchain download directories")
        }
    }

    /**
     * Removes a toolchain the app no longer offers.
     *
     * A toolchain leaves [ToolchainRegistry.available] when it stops being worth
     * its payload, and that alone would strand every install that already has
     * it. The card list is built from the registry
     * ([ToolchainCardState.items]), so the Remove button disappears with the
     * entry, while the install record, the payload and the loader wrappers are
     * all read from `toolchains.json` and carry on. The result is a toolchain
     * the user cannot get rid of and cannot use, which is worse than either.
     *
     * So the removal is done for them, once, on the first launch that carries
     * the retirement. [uninstallLocked] reads the install record rather than the
     * registry, so it can still find what it is deleting.
     *
     * No marker is kept and none is needed: a retired pack has no registry entry
     * and no download URL, so nothing can put it back, and the pass costs one
     * state read on every later launch. That is the same read
     * [regenerateEnvFileLocked] does immediately afterwards.
     *
     * The one removal that reaches [uninstallLocked] without taking the
     * [installsInFlight] claim [uninstallSync] takes, and safe for the same
     * reason the paragraph above gives: [install] resolves through
     * [ToolchainRegistry.find] and refuses a name it does not know, so nothing
     * can be installing a pack that has left the registry.
     */
    private fun removeRetiredToolchainsSync() = synchronized(stateLock) {
        val installed = readState()
        val present = mutableListOf<String>()
        for (i in 0 until installed.length()) {
            val name = installed.optJSONObject(i)?.optString("name").orEmpty()
            if (name.isNotEmpty() && toolchainShortName(name) in RETIRED_TOOLCHAINS) {
                present.add(name)
            }
        }
        for (name in present) {
            Logger.i(tag, "Removing $name: this build no longer offers it")
            uninstallLocked(name)
        }
    }

    /**
     * Holds [stateLock] across the tree walk as well as the record update, and
     * that is the deliberate cheaper half of a trade. The walk is bounded, and
     * normally one-time -- an entry is marked afterwards and skipped from then
     * on -- so the contention it can cause is a background install's record write
     * waiting a fraction of a second, once, after a download that took minutes.
     * Splitting it into read-walk-relock would buy that back at the cost of
     * reasoning about a state that changed underneath the walk. Readers are
     * unaffected either way: [readState] does not take the lock.
     *
     * "Normally" is the honest word, and what it excludes is worth pricing. An
     * entry goes unmarked when a binary the manifest names is present and will
     * not take the execute bit, and that entry is walked again on the next
     * launch: for Java 17 a `walkTopDown` over about 155 MB reading the first
     * four bytes of every file, under this lock, for as long as the condition
     * lasts. Retrying is still the right call -- a toolchain whose interpreter
     * has no execute bit is unusable, and the walk is the only thing that can
     * put it back -- but the cost is per launch, not once.
     */
    private fun repairInstalledToolchainsSync() = synchronized(stateLock) {
        val state = readState()
        var changed = false

        for (i in 0 until state.length()) {
            val entry = state.optJSONObject(i) ?: continue
            if (entry.optBoolean(KEY_EXEC_REPAIRED, false)) continue

            val name = entry.optString("name", "?")
            val installRoot = entry.optString("installRoot", "")
            val root = if (installRoot.isEmpty()) null else File(context.filesDir, installRoot)

            if (root != null && root.isDirectory) {
                val fixed = markExecutablesUnder(root)
                if (fixed > 0) {
                    Logger.i(tag, "Restored the execute bit on $fixed binaries in $name")
                }
            }
            // What the walk above cannot reach, and for Ruby that is the whole
            // point of the pass: its install root is `usr/lib/ruby` while the
            // interpreter is `usr/bin/ruby`, so the one file the toolchain
            // cannot run without sits outside the tree being walked. The
            // manifest names it, along with every other command a payload puts
            // in a directory it shares with the base install.
            val binariesRepaired = applyManifestExecBits(entry)
            // Marked when nothing is left for a later launch to do, which
            // includes a toolchain with no install root and one whose tree is
            // gone: this pass can do nothing for either now or ever. A binary
            // that is there and would not take the bit is the one case that is
            // worth trying again, so it goes unmarked and the next launch
            // revisits it.
            if (binariesRepaired) {
                entry.put(KEY_EXEC_REPAIRED, true)
                changed = true
            }
        }

        if (changed) writeState(state)
    }

    /**
     * Marks every ELF object under [root] executable, returning how many needed it.
     *
     * Symlinks are stepped over rather than followed: the link's target may sit
     * outside this toolchain -- `usr/lib` is shared with the base install -- and
     * a repair has no business changing permissions there.
     */
    private fun markExecutablesUnder(root: File): Int = markExecutablesIn(root)

    // -- State persistence --

    private fun readState(): JSONArray {
        if (!stateFile.exists()) return JSONArray()
        return try {
            JSONArray(stateFile.readText())
        } catch (e: Exception) {
            Logger.w(tag, "Failed to read toolchains.json: ${e.message}")
            JSONArray()
        }
    }

    /**
     * [readState] for the caller that cannot afford damage to read as absence.
     *
     * Returns null when the file exists and nothing can parse it, and an empty
     * array only when there is genuinely no record: the empty branch of
     * [regenerateEnvFileLocked] deletes the env file, which is right for a
     * machine with nothing installed and wrong for one whose record is a
     * half-written relic of the version that installed everything.
     */
    private fun readableState(): JSONArray? {
        if (!stateFile.exists()) return JSONArray()
        return try {
            JSONArray(stateFile.readText())
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Replaces `toolchains.json` in one step, or leaves the previous record
     * exactly where it was.
     *
     * `writeText` truncates before it writes, so a process killed in that window
     * left a zero-length or half-written file -- and [readState] answers a
     * malformed file with an empty array, on the reasonable-looking grounds that
     * it cannot do better. The two together are silent: every installed
     * toolchain disappears from the app's view while its several hundred MB stay
     * on disk, and the manifest naming the files, symlinks and libraries an
     * uninstall would remove is gone with it. There is no way back from that
     * short of reinstalling each toolchain over itself.
     *
     * [writeAtomically] writes a sibling and renames it, and rename is the
     * operation that either happened or did not. A failure now costs the update
     * rather than the record.
     *
     * @return false when the record on disk is still the previous one. Keeping
     *   the old record is the right outcome; reporting the update as if it
     *   landed is not, and a caller whose next act is to tell the user the
     *   toolchain is installed has to ask. [installFromDirectory] is that caller.
     */
    private fun writeState(state: JSONArray): Boolean = synchronized(stateLock) {
        stateFile.parentFile?.mkdirs()
        val written = writeAtomically(stateFile) { it.write(state.toString(2).toByteArray()) }
        if (!written) {
            Logger.e(tag, "Could not write toolchains.json; it still holds the previous record")
        }
        written
    }
}

/**
 * A staging directory belonging to one download rather than to the class.
 *
 * The path used to be a constant, and the `finally` that cleans up deletes the
 * directory whole. Nine call sites each construct their own `ToolchainManager`
 * and `ioExecutor` is an instance field, so it serialises nothing between them:
 * rotating the toolchain screen, or cancelling the first-run queue and reaching
 * the picker again, leaves an earlier download still running against the same
 * path. Each one's cleanup then deleted the other's archive and tree, which
 * surfaces as a digest mismatch, the alarm that exists for tampering, raised by
 * a race with ourselves.
 *
 * A process killed mid-download leaves one directory behind, which Android
 * reclaims under storage pressure. The shared path leaked the same way and was
 * merely overwritten by the next attempt rather than reclaimed.
 */
internal fun toolchainTempDir(cacheDir: File, packName: String): File =
    File(cacheDir, "toolchain-download/$packName-${System.nanoTime()}")

/**
 * How far along a Play delivery is, as a percentage.
 *
 * Zero when Play has not said how big the pack is yet, which it does not until
 * the download is under way: dividing by that total unguarded is the one way
 * this arithmetic can fail, and it fails on the first update of every download.
 *
 * At file scope because two callers need the same answer: the state callback,
 * and [ToolchainManager.readPlayDownloads] asking after the fact.
 */
internal fun packDownloadPercent(bytesDownloaded: Long, totalBytes: Long): Int =
    if (totalBytes > 0) ((bytesDownloaded * 100) / totalBytes).toInt() else 0

/** How long an idle toolchain I/O thread is kept before it is given back. */
internal const val IO_THREAD_KEEPALIVE_MS = 30_000L

/**
 * The single-thread executor each [ToolchainManager] does its file work on.
 *
 * One thread, an unbounded queue and therefore the same serialisation
 * `Executors.newSingleThreadExecutor` gives, with one difference: the thread
 * times out when it has been idle for [IO_THREAD_KEEPALIVE_MS] and is created
 * again on the next submission. A core thread never times out, and that default
 * is what made the lifetime a problem here, because nothing in this class or in
 * any caller ever shuts one down: nine call sites each construct their own
 * manager, `SplashActivity.onCreate` constructs two of them per launch and
 * submits on both, and the process outlives the screens because the server runs
 * in a foreground service. Every launch therefore parked two more threads that
 * had already finished their work.
 *
 * Shutting the executors down instead would need an owner for each manager, and
 * there is none: `Environment` and `AndroidBridge` build one where they stand.
 * Reclaiming an idle thread costs nothing and needs no owner.
 *
 * At file scope so the configuration can be asserted without reaching into a
 * manager's private field.
 */
internal fun toolchainIoExecutor(
    keepAliveMs: Long = IO_THREAD_KEEPALIVE_MS,
): ThreadPoolExecutor = ThreadPoolExecutor(
    1,
    1,
    keepAliveMs,
    TimeUnit.MILLISECONDS,
    LinkedBlockingQueue(),
) { r -> Thread(r, "toolchain-io").apply { isDaemon = true } }
    .apply { allowCoreThreadTimeOut(true) }

/**
 * How stale a toolchain staging directory has to be before it is swept.
 *
 * A day, and generously so. The only thing this has to be longer than is a
 * download that is still running, and the directory's own timestamp is a weak
 * witness of that: creating the archive and the extraction directory touches it,
 * but writing 56 MB into a file that already exists does not. A day is far
 * beyond any transfer this app performs and costs at most one day of disk.
 */
internal const val ABANDONED_DOWNLOAD_AGE_MS = 24L * 60 * 60 * 1000

/**
 * Whether a staging directory last touched at [lastModifiedMs] belongs to a
 * download nothing is performing any more.
 *
 * Zero is `File.lastModified`'s answer for a timestamp it could not read, and it
 * is treated as "cannot tell" rather than as the epoch: deleting a directory a
 * download is writing into is the failure worth avoiding, and an extra day of
 * disk is the price.
 */
internal fun isAbandonedDownload(lastModifiedMs: Long, nowMs: Long): Boolean =
    lastModifiedMs > 0L && lastModifiedMs < nowMs - ABANDONED_DOWNLOAD_AGE_MS

/** Free space asked for beyond what the install itself needs. */
internal const val SPACE_BUFFER = 50_000_000L

/**
 * What an HTTP toolchain install actually needs free, given its unpacked size.
 *
 * Twice the tree, because the install holds two copies at its peak: the one
 * unpacked into the cache and the one being written into `filesDir/usr`. The
 * reservation used to charge for one, which is the product rather than the
 * process, and nothing frees a stage before the next allocates. For Java 17 that
 * asked 196 MB for something that needs about 342, so every device between the
 * two figures downloaded 55 MB and then failed partway through the copy, with
 * the disk error reported as a network problem and the half-copied files left in
 * `usr/` under no manifest, so each retry started from less space than the last.
 *
 * The downloaded archive is deliberately not a third term: it is deleted after
 * extraction, before the copy begins. Charging for it here as well would refuse
 * devices for room they never need at once.
 */
internal fun toolchainInstallBytes(unpackedBytes: Long): Long =
    unpackedBytes * 2 + SPACE_BUFFER

/**
 * The unpacked size recorded for [packName], or null when none is.
 *
 * Null rather than zero, and the distinction is the whole reason this exists.
 * `ToolchainRegistry.find` answers null for a retired pack, by design: that
 * pass-through is what keeps uninstalling one working after its row is dropped.
 * A caller that spells the lookup as `find(...)?.estimatedSize ?: 0L` therefore
 * turns "I do not know" into "it needs nothing", and a space gate built on that
 * reserves the bare buffer for a tree of any size. Zero is a real answer here
 * only for a pack that genuinely occupies nothing, which none do.
 *
 * [RETIRED_TOOLCHAINS] is consulted second because the packs most likely to be
 * on a device right now are the ones installed before a withdrawal, and the
 * registry is exactly where they are not.
 * The prefix is stripped here rather than through `toolchainShortName`, which
 * resolves through the registry and returns a retired name unchanged.
 */
internal fun packUnpackedBytes(packName: String): Long? =
    ToolchainRegistry.find(packName)?.estimatedSize
        ?: RETIRED_TOOLCHAINS[packName.removePrefix("toolchain_")]

/**
 * What a Play Asset Delivery install needs free, given its unpacked size.
 *
 * One tree rather than two, because by the time this is asked the first copy is
 * already written and already counted. The figure is right; the reason given for
 * it was not, and the wrong reason is worth naming because a later change rested
 * on it.
 *
 * ⚠️ Play does NOT write the pack outside `filesDir`, which this used to claim.
 * Verified from asset-delivery 2.2.2's bytecode: `bh` builds
 * `new File(context.getFilesDir(), "assetpacks")`. The delivered tree therefore
 * sits on exactly the filesystem `StatFs(context.filesDir)` measures, and it is
 * already occupying space when the caller takes that reading. So the only NEW
 * allocation the install makes is the copy into `usr/`, which is what this
 * charges for; peak usage is two trees, and `removePack` frees Play's afterwards.
 *
 * The consequence that matters is elsewhere: deleting Play's copy when an install
 * is refused for space does not improve the next attempt, because the retry
 * re-extracts the same bytes into the same place. See the refusal branch in
 * [ToolchainManager.installDeliveredPack], which keeps it for that reason.
 */
internal fun packInstallBytes(unpackedBytes: Long): Long =
    unpackedBytes + SPACE_BUFFER

/**
 * Bytes already on disk under an install root that the copy will write over.
 *
 * `copyTo(overwrite = true)` deletes the destination before opening the output
 * stream, so an overwrite of an identical tree allocates nothing net. Clamped at
 * [recordedBytes] because a directory can hold more than the pack will write
 * back, and a credit for those bytes is space the copy never returns.
 */
internal fun existingTreeCredit(
    root: File?,
    within: File,
    recordedBytes: Long,
    measure: (File) -> Long,
): Long {
    if (root == null) return 0
    // A root that resolves outside [within] credits nothing. `..` escapes
    // File(base, relative) while an absolute string is re-nested under it, so this
    // rejects the one form that reaches outside. The manifest comes from a signed
    // pack rather than an attacker, and uninstallLocked and reclaimPartialCopy
    // already build the same File and delete through it, so this is not a last line
    // of defence. It is that a credit is the first DECISION taken on that string:
    // measuring the whole app data directory would clamp to the pack's own size and
    // leave the gate asking for nothing but the buffer.
    val base = runCatching { within.canonicalFile }.getOrNull() ?: return 0
    val resolved = runCatching { root.canonicalFile }.getOrNull() ?: return 0
    if (!generateSequence(resolved) { it.parentFile }.any { it == base }) return 0
    return minOf(recordedBytes, measure(resolved)).coerceAtLeast(0)
}

/**
 * Copies a tree, refusing rather than shrugging when a directory cannot be read.
 *
 * `listFiles` answers null both for a directory that is empty of readable
 * entries and for one it could not enumerate at all, and this used to adopt the
 * benign reading with a bare `return`. The caller then wrote the install record
 * and reported the toolchain COMPLETED: a subtree that was never copied, symlinks
 * pointing at files that are not there, a green card, and a command that does not
 * run. The file already states the principle elsewhere, that damage is not
 * absence; this is the one place it was not applied.
 *
 * At file scope with the lister injected so the refusing branch can be driven
 * from a JVM test, which is the only way to reach it: producing a real
 * unreadable directory needs a filesystem fault or a concurrent deletion.
 *
 * @param list how children are enumerated, defaulting to the real filesystem
 * @throws IOException if a directory exists but cannot be listed
 */
internal fun copyDirectoryTree(
    src: File,
    dest: File,
    list: (File) -> Array<File>? = File::listFiles,
) {
    if (src.isDirectory) {
        dest.mkdirs()
        val children = list(src)
            ?: throw IOException(
                "Cannot list ${src.absolutePath}; the toolchain tree is incomplete"
            )
        for (child in children) {
            copyDirectoryTree(child, File(dest, child.name), list)
        }
    } else {
        dest.parentFile?.mkdirs()
        src.copyTo(dest, overwrite = true)
    }
}

/**
 * Gives the execute bit to every ELF object under [root], returning how many
 * needed it.
 *
 * At file scope and taking its symlink predicate as a parameter so a test can
 * reach it. [isSymlink] uses `Os.lstat`, which cannot run in a JVM unit test --
 * it throws, the catch turns that into "not a link", and every entry then looks
 * like a regular file. Injecting the predicate is what lets the skip-links
 * behaviour be asserted rather than assumed; production still passes
 * [isSymlink].
 *
 * Links are stepped over rather than followed: a toolchain's `usr/lib` is shared
 * with the base install, so a link there can point at a file this pass has no
 * business changing the permissions of.
 *
 * Only ELF objects. Scripts are deliberately excluded -- they are wrapped in
 * shell functions that route them through their interpreter, because nothing
 * under `filesDir` can be executed directly whatever its mode.
 */
internal fun markExecutablesIn(root: File, isLink: (File) -> Boolean = ::isSymlink): Int {
    var fixed = 0
    root.walkTopDown()
        .onEnter { !isLink(it) }
        .forEach { file ->
            if (!file.isFile || isLink(file)) return@forEach
            if (!isElfFile(file)) return@forEach
            if (file.canExecute()) return@forEach
            if (file.setExecutable(true, true)) fixed++
        }
    return fixed
}

/**
 * Whether [name] is safe to write into `toolchain-env.sh` as a shell function.
 *
 * `.bashrc` sources that file unconditionally, and one unusable name does not cost
 * one command -- it costs the rest of the file. Measured: sourcing a file holding
 * `good() {...}`, then `a=b() {...}`, then `after() {...}` leaves `good` defined
 * and `after` gone. So a toolchain whose manifest carried a bad name would take
 * out every wrapper written after it, in every new terminal.
 *
 * The boundary is measured against bash rather than assumed, because assuming it
 * was wrong in both directions. An earlier version of this allowed only
 * `[A-Za-z_][A-Za-z0-9_]*`, which is the rule for shell *variables*; bash is far
 * more permissive about function names and happily defines `grpc-tool`, `2to3`,
 * `foo.bar` and even `café`. Refusing those would have dropped working wrappers.
 *
 * What actually goes wrong divides in two, and both are refused:
 *
 *  - `( ) < > = [ $ \ " ' `` ` ``` and whitespace make the definition a parse
 *    error, which is what kills the rest of the file.
 *  - `; | &` are worse than an error: they split the line, so bash defines a
 *    function under a *different* name and reports nothing. The wrapper is simply
 *    absent under the name the user will type.
 *
 * Everything else measured -- `- . + : @ % ^ ! , { } ] * ? # ~ /`, a leading
 * digit, non-ASCII -- defines correctly and is allowed. All fifty command names in
 * the three shipped manifests pass, but those manifests are regenerated from
 * upstream packages at build time, so what they contain is not this repository's
 * choice to make.
 */
internal fun isShellFunctionName(name: String): Boolean {
    if (name.isEmpty()) return false
    return name.none { it in SHELL_UNSAFE || it.isWhitespace() }
}

/**
 * The characters measured to break a function definition or to silently define one
 * under another name. Not a guess at what looks dangerous: each was checked by
 * defining a function and asking `declare -F` whether that name exists.
 */
private const val SHELL_UNSAFE = "()<>=[\$\\\"'`;|&"

/** Whether [file] begins with the four bytes every ELF object starts with. */
internal fun isElfFile(file: File): Boolean = try {
    file.inputStream().use { input ->
        val header = ByteArray(4)
        input.read(header) == 4 && isElfHeader(header)
    }
} catch (e: Exception) {
    false
}

/**
 * Whether these opening bytes are an ELF object's.
 *
 * The same four bytes the packaging gates read. Separated so the repair pass can
 * be checked against real files rather than a mock that would only agree with
 * the implementation it was written from.
 */
internal fun isElfHeader(header: ByteArray): Boolean =
    header.size >= 4 &&
        header[0] == 0x7F.toByte() &&
        header[1] == 'E'.code.toByte() &&
        header[2] == 'L'.code.toByte() &&
        header[3] == 'F'.code.toByte()

/**
 * Whether a transfer that ended may be treated as the whole file.
 *
 * Separated from the socket for the same reason as the other decisions in this
 * package: the risk runs one way. Judging a complete download incomplete costs a
 * retry and then a visible failure, while judging an incomplete one complete
 * hands [ToolchainManager.extractZip] a truncated archive -- and a ZIP is read
 * entry by entry, so a cut one extracts happily up to the cut. What installs is
 * a toolchain that is simply missing whatever came after it, with the manifest
 * claiming it whole.
 *
 * @param declaredBytes the `Content-Length` the server sent, or a value <= 0
 *   when it sent none. Absence has to be accepted rather than treated as zero:
 *   the header is optional, a chunked response omits it, and refusing those
 *   would fail-closed on healthy downloads.
 * @param receivedBytes what the read loop actually counted
 */
internal fun isCompleteTransfer(declaredBytes: Long, receivedBytes: Long): Boolean =
    declaredBytes <= 0L || receivedBytes == declaredBytes

/**
 * Where a redirect from [currentUrl] points, refusing one that would drop TLS.
 *
 * Redirects are followed by hand here (`instanceFollowRedirects = false`), and
 * that is exactly what removes the platform's own refusal: `HttpURLConnection`
 * will not automatically follow `https` to `http`, and a loop that reads
 * `Location` itself never asks it to. `network_security_config.xml` permits
 * cleartext app-wide, for the loopback server the editor runs on, so nothing
 * below this refuses the hop either.
 *
 * Both artifacts that decide whether a toolchain ZIP may be installed come
 * through this function: the payload, and the `sha256` manifest it is checked
 * against, whose URL is derived beside it. A chain that drops to cleartext
 * therefore carries the evidence and the thing it vouches for over the same
 * unprotected hop, and an on-path answer supplies a hostile archive together
 * with a manifest naming its digest. What installs is unpacked into
 * `filesDir/usr` and turned into shell functions that `.bashrc` sources for
 * every terminal.
 *
 * The origin sends no such redirect today, so this is a floor rather than a
 * repair. Upgrades are left alone: an `http` start may go anywhere, which is
 * what keeps a loopback fixture usable, and only a chain that began in `https`
 * is held to it.
 *
 * @throws IOException when following the hop would leave TLS behind
 */
@Throws(IOException::class)
internal fun nextRedirectUrl(currentUrl: String, location: String): String {
    val next = if (location.startsWith("http")) location
               else URL(URL(currentUrl), location).toString()
    if (currentUrl.startsWith("https://", ignoreCase = true) &&
        !next.startsWith("https://", ignoreCase = true)
    ) {
        throw IOException("Refusing a redirect from $currentUrl to cleartext $next")
    }
    return next
}

/**
 * Why an install failed, in the terms the person looking at the screen can act in.
 *
 * The screen said "Failed" and nothing else, for every cause, while the reason
 * sat in logcat where no user reads it. These are grouped by WHAT TO DO rather
 * than by where the exception came from: a full disk and a failed state write
 * are both [STORAGE] because both are answered by freeing space, and a truncated
 * archive and a manifest missing its name are both [CORRUPT] because both are
 * answered by retrying.
 *
 * [NOT_PUBLISHED] is deliberately not [NETWORK], although it arrives as an
 * IOException on the same path: retrying on better signal cannot fix a file the
 * release does not contain, and telling someone to check their connection when
 * that is the problem sends them to look in the wrong place.
 */
// `@param:` rather than a bare `@StringRes`, which Kotlin warns will start
// applying to the backing field as well. Naming the target pins today's meaning
// instead of letting a compiler upgrade choose a different one.
enum class ToolchainFailure(@param:StringRes val message: Int) {
    NETWORK(R.string.toolchain_failed_network),
    STORAGE(R.string.toolchain_failed_storage),
    NOT_PUBLISHED(R.string.toolchain_failed_not_published),
    DIGEST(R.string.toolchain_failed_digest),
    CORRUPT(R.string.toolchain_failed_corrupt),
    // Reached from both delivery paths, so its message names what Play will not
    // do rather than which build is running: a sideloaded install with no ZIP for
    // this pack, and a Play install Play does not recognise as one of its own.
    // Naming the build type is wrong for the second, which shouldUseHttpFallback
    // has already classified as a Play install.
    PLAY_REQUIRED(R.string.toolchain_failed_play),
    INTERNAL(R.string.toolchain_failed_internal),
}

/**
 * Play's error code for a failed asset pack, in the terms [ToolchainFailure] speaks.
 *
 * The Play delivery path reported FAILED with no reason at all. The code went to
 * logcat and stopped there, so a Play Store user was left with the bare word that
 * the HTTP path had already stopped showing, for a full disk and a dropped
 * connection alike. Nothing new is said here; the existing vocabulary is simply
 * reached from the other delivery path.
 *
 * Grouped by what the user can do, like the enum itself, and taken from what each
 * code is documented to mean rather than from what its name resembles:
 *
 *  - `NETWORK_ERROR` is "unable to obtain the asset pack details", so
 *    [ToolchainFailure.NETWORK]: a better connection is the answer.
 *  - `INSUFFICIENT_STORAGE` is a download refused for space, so
 *    [ToolchainFailure.STORAGE]: freeing space is the answer.
 *  - `PACK_UNAVAILABLE` is "the asset pack wasn't included in the App Bundle that
 *    was published", which is this app's own release not carrying it, so
 *    [ToolchainFailure.NOT_PUBLISHED]: waiting for an update is the only answer.
 *  - `APP_NOT_OWNED` and `UNRECOGNIZED_INSTALLATION` both say Play does not
 *    recognise this copy of the app as one it delivered, and Play serves asset
 *    packs only to copies it does, so [ToolchainFailure.PLAY_REQUIRED].
 *
 * Everything else is [ToolchainFailure.INTERNAL] on purpose, including codes with
 * an inviting name. `APP_UNAVAILABLE` is "the requesting app is unavailable",
 * which is about the app or the user's access to it and not about the pack, so
 * sending it to `NOT_PUBLISHED` would tell someone this toolchain is missing from
 * a release that carries it. `ACCESS_DENIED`, `API_NOT_AVAILABLE`,
 * `INVALID_REQUEST`, `DOWNLOAD_NOT_FOUND` and `CONFIRMATION_NOT_REQUIRED` name
 * conditions no message in the enum can act on, and the last three are this
 * app's own mistakes rather than the user's.
 *
 * Two documented codes cannot be named here at all: `PLAY_STORE_NOT_FOUND` (-11)
 * and `NETWORK_UNRESTRICTED` (-12) appear in the library's own IntDef listing but
 * are not declared by asset-delivery 2.2.2, so they arrive as numbers this build
 * has no constant for and fall through with the rest. Every code that lands in
 * INTERNAL is still in the log line beside this call, raw.
 */
internal fun toolchainFailureFor(errorCode: Int): ToolchainFailure = when (errorCode) {
    AssetPackErrorCode.NETWORK_ERROR -> ToolchainFailure.NETWORK
    AssetPackErrorCode.INSUFFICIENT_STORAGE -> ToolchainFailure.STORAGE
    AssetPackErrorCode.PACK_UNAVAILABLE -> ToolchainFailure.NOT_PUBLISHED
    AssetPackErrorCode.APP_NOT_OWNED,
    AssetPackErrorCode.UNRECOGNIZED_INSTALLATION -> ToolchainFailure.PLAY_REQUIRED
    else -> ToolchainFailure.INTERNAL
}

/**
 * Toolchains no longer offered, against the space each still occupies on a
 * device that installed one before it was withdrawn.
 *
 * The size is here rather than left to `ToolchainRegistry.find`, which answers
 * null for anything it no longer offers. [packUnpackedBytes] is the one reader
 * of the size ([ToolchainManager.removeRetiredToolchainsSync] reads the keys),
 * and what it feeds is the Play install pre-flight: spelled
 * `find(...)?.estimatedSize ?: 0L`, that gate reserved the bare 50 MB buffer
 * before copying a tree of any size, which is the direction that admits a
 * device it should have refused and then fills it up partway through the copy.
 *
 * Keyed by short name, the form [toolchainShortName] produces, so a record
 * written as either `go` or `toolchain_go` resolves.
 *
 * An entry belongs here when it has left [ToolchainRegistry.available]: see
 * [ToolchainManager.removeRetiredToolchainsSync] for why leaving it alone is the
 * one option that helps nobody.
 *
 * `go` is here because it could not compile. Android refuses to execute a file
 * under the app's data directory, and `go build` and `go run` fork the compiler,
 * assembler and linker themselves, so those forks are refused however the `go`
 * command itself is reached. Measured in the app's own SELinux domain, with a
 * control: a plain shell script placed there and marked executable is refused
 * too. It ran, it printed a version, and it could not build a program, for
 * 179 MB.
 */
internal val RETIRED_TOOLCHAINS = mapOf("go" to 179_000_000L)

/** The digest manifest's filename, as `release.yml` writes it beside the ZIPs. */
private const val MANIFEST_NAME = "toolchains.sha256"

/**
 * Where to find the digest manifest for a toolchain ZIP: beside it.
 *
 * Derived from the ZIP's own URL rather than written down separately, which
 * removes a second constant that could drift from the first. It does **not**
 * remove the window underneath, and that window is larger than it looks.
 *
 * Both URLs go through `releases/latest/download/`, which names whichever
 * release is newest at the moment of each request -- and the two requests are
 * not adjacent. The manifest is read first, then the payload transfers, with up
 * to two backed-off retries behind it: minutes for the largest pack, Go at
 * 179 MB, on a phone connection -- not an instant. A release published anywhere
 * in that span hands back new bytes to check against a digest read from the
 * release before it.
 *
 * That fails closed -- the digests disagree, nothing is installed -- so it costs
 * a refused install rather than a bad one, and retrying succeeds. What it costs
 * beyond that is diagnostic: in the log it is indistinguishable from the
 * tampering this check exists to catch.
 *
 * That window is closed before this is called, by [pinnedAssetUrl] and the
 * resolution in front of it: the ZIP URL handed here already names a concrete
 * release, so deriving the manifest beside it inherits the same one. The
 * paragraphs above describe what happens when that resolution fails and the
 * unpinned URL is used as a fallback, which is the old behaviour and the floor
 * this can never drop below.
 */
internal fun manifestUrlFor(zipUrl: String): String =
    zipUrl.substringBeforeLast('/') + "/" + MANIFEST_NAME

/**
 * The release-level `latest` URL an asset URL resolves through, or null when
 * the URL is not of that shape.
 *
 * `https://host/o/r/releases/latest/download/x.zip` gives
 * `https://host/o/r/releases/latest`.
 *
 * Deliberately the RELEASE redirect and not the asset one. Measured 2026-08-16:
 * `releases/latest/download/<asset>` redirects to
 * `releases/download/<tag>/<asset>` and then again to a signed CDN URL that
 * carries no tag, so reading the end of that chain answers nothing and reading
 * its middle means depending on how many hops there are. `releases/latest`
 * redirects once, to `releases/tag/<tag>`, and involves no asset at all.
 */
internal fun latestReleaseUrlFor(assetUrl: String): String? {
    val i = assetUrl.indexOf(LATEST_DOWNLOAD)
    return if (i < 0) null else assetUrl.substring(0, i) + "/releases/latest"
}

/**
 * The tag named by a `releases/latest` redirect, or null.
 *
 * `https://host/o/r/releases/tag/v1.2.3` gives `v1.2.3`.
 *
 * A tag that is not plainly a tag is refused rather than pasted into a URL.
 * Everything downstream falls back to the unpinned URL on null, so refusing
 * costs the old behaviour; accepting something with a slash or a space in it
 * would build a URL that 404s for every toolchain.
 */
internal fun releaseTagFromLocation(location: String): String? {
    val i = location.indexOf(RELEASES_TAG)
    if (i < 0) return null
    val tag = location.substring(i + RELEASES_TAG.length)
        .substringBefore('?')
        .substringBefore('#')
        .trim('/')
    return if (tag.isNotEmpty() && TAG_CHARS.matches(tag)) tag else null
}

/**
 * The release tag a build of [versionName] belongs to, or null.
 *
 * `1.1.0` gives `v1.1.0`, matching the tags `release.yml` publishes.
 *
 * `-debug` is stripped because it is a `versionNameSuffix` on the debug build
 * type and not part of any tag; a debug build belongs to the same release its
 * source does. Nothing else is stripped. A suffix this does not know about
 * yields a tag no release carries, the probe answers 404, and the caller falls
 * back to `latest` -- so an unknown suffix costs the old behaviour rather than
 * a wrong guess at which release the build came from.
 */
internal fun appReleaseTag(versionName: String): String? {
    val version = versionName.removeSuffix("-debug")
    return if (version.isNotEmpty() && TAG_CHARS.matches(version)) "v$version" else null
}

/**
 * [assetUrl] with `latest` replaced by a concrete [tag], or null.
 *
 * `.../releases/latest/download/x.zip` and `v1.2.3` give
 * `.../releases/download/v1.2.3/x.zip`.
 *
 * Refuses an asset name containing a slash, because that would mean the URL was
 * not the flat `releases/latest/download/<name>` this is written for and the
 * result would name something else entirely.
 */
internal fun pinnedAssetUrl(assetUrl: String, tag: String): String? {
    val i = assetUrl.indexOf(LATEST_DOWNLOAD)
    if (i < 0) return null
    val asset = assetUrl.substring(i + LATEST_DOWNLOAD.length)
    if (asset.isEmpty() || asset.contains('/')) return null
    if (!TAG_CHARS.matches(tag)) return null
    return assetUrl.substring(0, i) + "/releases/download/" + tag + "/" + asset
}

private const val LATEST_DOWNLOAD = "/releases/latest/download/"
private const val RELEASES_TAG = "/releases/tag/"
private val TAG_CHARS = Regex("""[A-Za-z0-9._-]+""")

/**
 * The digest a `sha256sum` manifest publishes for [fileName], or null when it
 * publishes none it can be trusted to mean.
 *
 * Separated from the network for the same reason as the other decisions in this
 * package, and here the one-directional risk is the sharpest in the file:
 * returning null costs a refused install that a re-release fixes, while
 * returning a digest the manifest did not really state for this file defeats the
 * entire check -- and it defeats it silently, because the comparison downstream
 * then passes.
 *
 * So every doubt resolves to null:
 *
 *  - a line whose first field is not exactly 64 hex characters is not a digest,
 *    including a line the read bound cut in half, and is skipped;
 *  - two lines naming this file with *different* digests are ambiguous, and a
 *    manifest that cannot make up its mind is refused outright rather than
 *    resolved by taking the first or the last;
 *  - no line at all is a refusal, which is what a release published without a
 *    manifest entry for this ZIP produces.
 *
 * Names are compared as bare filenames. `sha256sum` writes whatever path it was
 * handed, and prefixes a `*` in binary mode; both are stripped so a manifest
 * generated from a build directory still matches the flattened asset name a
 * device downloads.
 */
internal fun digestFromManifest(manifest: String, fileName: String): String? {
    var found: String? = null
    for (raw in manifest.lineSequence()) {
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith("#")) continue

        val parts = line.split(WHITESPACE, limit = 2)
        if (parts.size != 2) continue

        val digest = parts[0].lowercase()
        if (!SHA256_HEX.matches(digest)) continue

        val name = parts[1].trim().removePrefix("*").substringAfterLast('/')
        if (name != fileName) continue

        if (found != null && found != digest) return null
        found = digest
    }
    return found
}

private val WHITESPACE = Regex("""\s+""")
private val SHA256_HEX = Regex("""^[0-9a-f]{64}$""")

/**
 * The SHA-256 of [file], lowercase hex.
 *
 * Streamed rather than read whole: the payloads are up to 179 MB and this runs
 * on a phone, where holding one in a byte array to hash it is how a verification
 * step becomes the reason an install dies.
 */
internal fun sha256Of(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    file.inputStream().buffered().use { input ->
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/**
 * The name a toolchain is persisted under, from either form callers use.
 *
 * `toolchain_ruby` and `ruby` name the same thing: the first is the asset pack and
 * the URL, the second is what goes in `toolchains.json` and what the shell
 * environment is built from. [ToolchainRegistry.find] already accepts both, so
 * this asks it and then takes the recorded form, rather than stripping the
 * prefix as a string operation -- a name that is not a known toolchain comes
 * back unchanged instead of being silently reshaped.
 *
 * That last part is why the fallback is the input rather than null: an entry can
 * outlive its registry row. A toolchain dropped from [ToolchainRegistry] in some
 * later release is still installed on the devices that have it, and its
 * uninstall has to keep working -- the alternative is files that nothing can
 * remove.
 */
internal fun toolchainShortName(nameOrPack: String): String =
    ToolchainRegistry.find(nameOrPack)?.packName?.removePrefix("toolchain_") ?: nameOrPack

/**
 * Names the manifest `libs` entries an uninstall may delete from the shared
 * `usr/lib`, given what the base APK ships there.
 *
 * Separated from the filesystem because the risk is one-directional, the same
 * shape as [supersededPythonEntries]: removing one entry too few leaves a file
 * the next install overwrites, while removing one too many deletes a library
 * the base app loads -- and that stays broken until the next version bump
 * re-extracts assets, with the error naming the library rather than the
 * uninstall that removed it.
 *
 * @param libs the manifest's `libs` entries
 * @param baseShipped names the base APK ships in `usr/lib`, or null when the
 *   listing could not be read -- in which case nothing is safe to remove
 */
internal fun toolchainLibsSafeToRemove(libs: List<String>, baseShipped: Set<String>?): List<String> {
    if (baseShipped == null) return emptyList()
    return libs.filter { it !in baseShipped }
}

/**
 * Every `usr/lib` entry the install record names for a toolchain other than
 * [exceptName].
 *
 * The uninstall's protected set is "what someone else in this directory owns",
 * and the base APK was the only someone else it counted. Another installed
 * toolchain owns its libraries on the same terms: the copy is one file, the
 * directory is shared, and whichever uninstall runs first would take it.
 *
 * @param state the parsed `toolchains.json`, which the caller already holds
 * @param exceptName the toolchain being removed, in the short form the record
 *   uses
 */
internal fun otherToolchainLibs(state: JSONArray, exceptName: String): Set<String> {
    val shared = mutableSetOf<String>()
    for (i in 0 until state.length()) {
        val entry = state.optJSONObject(i) ?: continue
        if (entry.optString("name") == exceptName) continue
        val libs = entry.optJSONArray("libs") ?: continue
        for (j in 0 until libs.length()) shared.add(libs.getString(j))
    }
    return shared
}
