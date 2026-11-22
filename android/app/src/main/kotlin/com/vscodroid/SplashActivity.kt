package com.vscodroid

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.webkit.WebView
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.view.doOnLayout
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.play.core.assetpacks.model.AssetPackStatus
import com.vscodroid.setup.FirstRunSetup
import com.vscodroid.setup.ToolchainFailure
import com.vscodroid.setup.ToolchainManager
import com.vscodroid.setup.ToolchainCardMode
import com.vscodroid.setup.ToolchainPickerAdapter
import com.vscodroid.setup.ToolchainRegistry
import com.vscodroid.storage.SafStorageManager
import com.vscodroid.util.drawBehindSystemBars
import com.vscodroid.util.isLightColor
import com.vscodroid.util.Logger
import com.vscodroid.util.padForSystemBars
import com.vscodroid.util.paintWindow
import com.vscodroid.util.PortFinder
import com.vscodroid.webview.hasKeptPageColor
import com.vscodroid.webview.keepStartColor
import com.vscodroid.webview.lastPageColor
import com.vscodroid.webview.readSavedPageColor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.widget.Toast
import com.vscodroid.service.NodeService
import com.vscodroid.util.AppNotices
import androidx.core.content.edit

/**
 * The action of the intent by which the Toolchains screen sends a tap on Install here while setup
 * has not finished: the splash screen tells the person that the install waits for them to ask again.
 *
 * An action and not an extra. This screen is exported, and reading one extra unparcels the whole
 * bundle another app sent with its intent, which up to Android 12 throws on a class this app does
 * not have.
 */
internal const val ACTION_INSTALL_WAITS_FOR_SETUP = "com.vscodroid.action.INSTALL_WAITS_FOR_SETUP"

@SuppressLint("CustomSplashScreen")
class SplashActivity : AppCompatActivity() {
    private val tag = "SplashActivity"

    private var toolchainManager: ToolchainManager? = null
    private var downloadQueue = mutableListOf<String>()
    private var currentDownloadIndex = -1
    private var cancelled = false

    private companion object {
        /**
         * A first-run download queue is running in this process, from [startDownloads] to
         * [endDownloadQueue].
         *
         * The picker counts as answered only where the queue ends, and the queue outlives its
         * screen. A splash made again meanwhile (a font size, language or display size change, or a
         * launch after Back) found the picker unanswered and offered it over downloads still in
         * flight, where a second Continue started a second queue. Process-wide and in memory only,
         * so a process that ends mid-queue still offers the picker at its next start.
         */
        @Volatile
        var downloadQueueRunning = false
    }

    /**
     * The picker's cards while the picker is the screen on show, and null in
     * every other phase of this activity.
     *
     * Held so that coming back to the picker can correct what it says is already
     * on disk. Nothing else may use it: after Continue the layout is replaced and
     * these cards are detached, which is why [startDownloads] drops it.
     */
    private var pickerAdapter: ToolchainPickerAdapter? = null

    /**
     * The progress closure this instance installed, or null if it never got as
     * far as installing one.
     *
     * Held so [onDestroy] can take it back, and compared by identity there: the
     * sink lives in the companion of [FirstRunSetup] because the extraction
     * outlives the screen that started it, so a departing instance must not
     * silence the one that replaced it.
     */
    private var progressSink: ((String, Int) -> Unit)? = null

    /** The view reading the editor's last colour while it does; see [readSavedPageColor]. */
    private var colorProbe: WebView? = null

    /**
     * Between [onStop] and the next [onStart]: the user has left this screen.
     *
     * Not the lifecycle state, which reads CREATED inside `onCreate`, where a cold
     * launch starts the editor and must keep doing so.
     */
    private var stopped = false

    // The Toolchains screen sent the person here from a tap on Install ([ACTION_INSTALL_WAITS_FOR_SETUP]),
    // and they have not been told yet that the install was not carried over.
    private var installWaitsNotice = false

    /** [launchMain] was asked while [stopped]; [onStart] runs it when the user is back. */
    private var launchPending = false

    /**
     * Play wanted its cellular-data question put for the pack in flight while this screen was
     * [stopped], so the question waits for the next [onStart]: its window takes the foreground,
     * and put from a stopped screen it lands over whatever the person moved to, or is refused as
     * a start from the background with nothing said and never asked again. Taken back by any
     * other status of that pack, which means the question was answered elsewhere or is moot, and
     * by [onDestroy], where a screen that will not start again ends the pack instead.
     */
    private var confirmationDeferred = false

    /**
     * The setup hold a first run took ahead of its launch repairs, until a setup
     * run takes it over ([runSetupWithRetry]) or the launch gives it back.
     */
    private var heldThroughRepairs = false

    // Progress UI refs (only valid after setContentView to progress layout)
    private val progressRows = mutableMapOf<String, ProgressRow>()

    private data class ProgressRow(
        val nameText: TextView,
        val progressBar: ProgressBar,
        val statusText: TextView,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        // Before super.onCreate(), as the call it replaces required.
        drawBehindSystemBars()
        super.onCreate(savedInstanceState)
        // Once, on the screen the tap made: one made again in its place (a turn, a font size) carries
        // the same intent, and a notice still up is handed on to it by AppNotices.
        installWaitsNotice = savedInstanceState == null && intent?.action == ACTION_INSTALL_WAITS_FOR_SETUP
        // The colour the editor last showed, for the window this screen and the
        // setup it hosts are drawn on; see lastPageColor.
        paintWindow(lastPageColor(this))

        val setup = FirstRunSetup(this)

        // The file repairs, one block that a first run runs off the main thread.
        fun repairFiles() {
            // Always validate tool symlinks: Android changes nativeLibraryDir
            // path on every reinstall, which breaks absolute symlinks in usr/bin/.
            //
            // These touch the filesystem at launch, on an I/O thread (below).
            // Letting one throw would crash the
            // app, leaving a launch loop with no explanation. What survives a
            // failure here is degraded rather than dead (tools missing from PATH, a
            // stale terminal profile), and logcat is the only trace, so keep the
            // message specific.
            //
            // Guarded one at a time rather than as a block, because they share
            // nothing but the launch they run in and a single catch made the first
            // failure cost all of them. The likeliest thrower is not last: writing
            // .npmrc is a certainty on the first launch after any reinstall, because
            // its contents name nativeLibraryDir and that path is new every time, and
            // a full disk turns that write into an exception. Behind it sat the
            // repair that repoints git and the Claude Code wrapper at the directory
            // the app has just moved to, and the reclaim of SAF mirrors whose
            // permission the user withdrew, the only pass here that gives disk back.
            // A full disk therefore stopped its own remedy, and did it again on every
            // launch.
            repair("tool symlinks") { setup.setupToolSymlinks() }
            repair("git core") { setup.setupGitCore() }
            repair("the git CA bundle") { setup.setupGitCaBundle() }
            repair("the OpenSSL configuration") { setup.setupOpensslConfig() }
            repair("the ripgrep symlink") { setup.setupRipgrepVscodeSymlink() }
            repair("the Copilot aliases") { setup.setupCopilotAndroidAliases() }
            // Before the three below, all of which extend .bashrc only when it
            // already exists. An install broken by an older release carries a
            // truncated one, and every writer skipped it because it was there;
            // this clears and rewrites it so the appenders have something whole
            // to append to. It is confined to evidence that cannot be a user's
            // own edit, see the method for where that line is drawn. It also
            // writes the file a dotfile link names when that file has gone.
            repair("the truncated-file repair") { setup.repairTruncatedSetupFiles() }
            repair("the npm wrappers") { setup.createNpmWrappers() }
            repair("the toolchain env sourcing") { setup.ensureToolchainEnvSourcing() }
            repair("the prompt block") { setup.ensurePromptFix() }
            repair("the startup directory guard") { setup.ensureStartupDirGuard() }
            // The same commands for shells that never read .bashrc. Outside the
            // three above rather than inside them: it writes its own file whole, so
            // it neither needs a .bashrc to exist nor leaves anything behind in one.
            repair("the non-interactive shell env") { setup.createBashEnvFile() }
            // Beside the shell writers rather than inside them: pip.conf is its own
            // file with its own format, and it has to be reconciled every launch so a
            // changed wheelhouse URL reaches a device already installed.
            repair("the pip configuration") { setup.ensurePipConfig() }
            // Ahead of the settings refresh, which writes the preload's LD_PRELOAD
            // line only when this file is there. That guard is what keeps the line
            // from ever standing alone (a preload the linker cannot find kills
            // every terminal rather than degrading one); the order is what gets
            // the file and the line in the same launch. A same-version reinstall
            // runs no extraction, so with the two reversed the first build carrying
            // the library would put the file down after the refresh had looked, and
            // the line would wait for the launch after.
            repair("the exec preload") { setup.ensureExecPreload() }
            repair("the native library paths in settings.json") { setup.updateSettingsNativeLibPaths() }
            // Beside the other idempotent repairs, for the same reason they are
            // here: it can disappear between launches. This one because it is
            // reachable from outside the app by some routes, and wiped by Clear
            // Data, not because the app moved it.
            repair("the projects directory") { setup.ensureProjectsDir() }
            // Immediately after it, because it points AT it. The link was written
            // once, at first run, behind a guard that follows it -- so a projects
            // directory deleted from outside the app left `~/projects` dangling,
            // reading as present to every check, until the next app update. The
            // terminal starts there.
            repair("the projects symlink") { setup.createStorageSymlinks() }
        }

        // The rest, as another block; its returns end the launch, as they did.
        fun continueAfterRepairs() {
            // A toolchain keeps the manifest it was installed with, so a
            // packaging fix never reaches an install that already exists. This
            // gives those binaries back the execute bit they should have had.
            // It returns immediately, the walk itself runs on the toolchain
            // I/O thread, because the trees involved have thousands of files
            // and this block is on the main thread.
            //
            // applicationContext, not this, and that is not a style choice: the
            // walk it submits runs on the toolchain I/O thread over thousands of
            // files, and this Activity finishes for MainActivity moments later, so
            // handing it our own Context keeps a finished Activity, its Window and
            // its ContextImpl reachable for the whole walk -- exactly while the
            // editor, the WebView renderer and Node are all starting. Every
            // ToolchainManager built in this file passes applicationContext for the
            // same reason; ToolchainActivity and AndroidBridge already did.
            //
            // The Context is only half of it, and this line is the half that needs
            // nothing else: it hands the manager no callback, so there is no closure
            // to name this screen. Where there is one, in [startDownloads], the
            // closure holds this screen strongly on purpose, because it is the only
            // thing that advances the queue; [endDownloadQueue] is what bounds that
            // retention.
            repair("the toolchain repair pass") { ToolchainManager(applicationContext).repairInstalledToolchains() }
            // Finish earlier committed removals and orphan metadata cleanup. Ordinary
            // copies survive revoked permissions because terminals may still write to them.
            repair("the SAF mirror reclaim") { SafStorageManager(this).reclaimRevokedMirrors() }

            if (!setup.isFirstRun()) {
                // The interpreter ships in the APK and every install replaces it,
                // while its runtime and stdlib are extracted only when [isFirstRun]
                // sees a changed versionName, versionCode, or lastUpdateTime. If the
                // runtime in filesDir ever falls out of step with the packaged
                // interpreter, "not first run" still has work to do.
                // The check is two directory listings; the work it gates is 23 MB, so
                // it runs off the main thread and holds this activity open while it
                // does: lifecycleScope is cancelled the moment we finish for
                // MainActivity.
                if (setup.pythonRuntimeNeedsWork()) {
                    Logger.i(tag, "Bundled Python changed since the last extraction; reconciling")
                    showSplashLayout()
                    findViewById<TextView>(R.id.statusText).text = getString(R.string.status_updating_python)
                    lifecycleScope.launch {
                        withContext(Dispatchers.IO) { setup.reconcilePythonRuntime() }
                        continueAfterSetup()
                    }
                    return
                }
                Logger.i(tag, "Not first run, setup is already behind this launch")
                continueAfterSetup()
                return
            }

            showSplashLayout()
            val statusText = findViewById<TextView>(R.id.statusText)
            val progressBar = findViewById<ProgressBar>(R.id.progressBar)
            progressBar.isIndeterminate = true

            if (setup.getPreviousVersionCode() > 0) {
                statusText.text = getString(R.string.status_updating_app)
            }

            // A colour is kept once the workbench first shows one, so none is kept on
            // the first start after an update from a build that kept none, and setup
            // then runs for a minute on the dark window whatever the editor's theme.
            // Only with a port remembered: a fresh install, or one after Clear storage,
            // has no workbench storage to read either.
            if (!hasKeptPageColor(this)) {
                val port = PortFinder.rememberedPort(this)
                if (port > 0) {
                    colorProbe = readSavedPageColor(this, port) { color ->
                        colorProbe?.destroy()
                        colorProbe = null
                        if (color != null) showSavedPageColor(color)
                    }
                }
            }

            // Named rather than assigned inline, so [onDestroy] can hand back exactly
            // this closure and nobody else's.
            val sink: (String, Int) -> Unit = { message, percent ->
                statusText.post {
                    applySetupProgress(statusText, progressBar, message, percent)
                }
            }
            progressSink = sink
            // After onResume on a first run, which had no sink to attach then.
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                FirstRunSetup.attachProgress(sink)
            }

            runSetupWithRetry(setup, statusText, progressBar)
        }

        // Every launch puts the splash up and repairs off the main thread. None skips the repairs:
        // what they mend can change while the process lives (a certificate authority installed in
        // Android Settings, a shell file a person edits), and the service keeps the process alive.
        if (setup.isFirstRun()) {
            showFirstRunSplash(setup)
            startFirstRunRepairs(::repairFiles, ::continueAfterRepairs)
            return
        }
        showLaunchSplash()
        startLaunchRepairs(::repairFiles, ::continueAfterRepairs)
    }

    /**
     * The launch repairs of an ordinary launch: [files] on an I/O thread under the
     * splash, then [after] on this one, as on a first run.
     */
    private fun startLaunchRepairs(files: () -> Unit, after: () -> Unit) {
        lifecycleScope.launch {
            repairFilesOffMain(Dispatchers.IO, files, after) { !isFinishing && !isDestroyed }
        }
    }

    /**
     * The launch repairs of a first run: [files] on an I/O thread, then [after]
     * on this one ([repairFilesOffMain]). The setup hold is taken here, while the
     * activity is starting, because the platform refuses a foreground service
     * start from an app the user left more than a few seconds before, and the
     * repairs take longer than that on a slow phone; [runSetupWithRetry] takes it
     * over, and a launch that ends without a setup run gives it back here.
     */
    private fun startFirstRunRepairs(files: () -> Unit, after: () -> Unit) {
        heldThroughRepairs = holdProcessForSetup()
        lifecycleScope.launch {
            try {
                repairFilesOffMain(Dispatchers.IO, files, after) { !isFinishing && !isDestroyed }
            } finally {
                if (heldThroughRepairs) {
                    heldThroughRepairs = false
                    releaseSetupHold()
                }
            }
        }
    }

    /**
     * Runs one launch-time repair, so a failure costs that repair and no other.
     *
     * [what] names the repair in the log, and that is the second half of the
     * reason this exists: the single catch it replaced logged one message for
     * fourteen calls, so the trace said something had failed without saying
     * which, and the next line of the log was whatever the app did after
     * skipping the rest.
     *
     * Swallowing is still deliberate. A throw that escapes here, on the I/O
     * thread of the file repairs or on the main thread of the rest, crashes
     * the app, which is a launch loop with no explanation. Degraded is the
     * acceptable outcome; dead is not.
     */
    private fun repair(what: String, action: () -> Unit) {
        try {
            action()
        } catch (e: Exception) {
            Logger.e(tag, "Launch-time refresh of $what failed", e)
        }
    }

    /**
     * The splash layout, padded for the system bars the window draws behind.
     *
     * Every other full-screen root in the app is padded at its own
     * `setContentView` ([showToolchainPicker], [startDownloads],
     * [ToolchainActivity]); this one was the exception, because its root carried
     * no id to look up. Nothing else keeps a child off the navigation bar once
     * `drawBehindSystemBars()` has run, and the one child that can reach it is
     * the Retry button, which activity_splash.xml anchors to the bottom.
     *
     * A method rather than two padded call sites, so a third cannot arrive
     * unpadded.
     */
    private fun showSplashLayout() {
        // Already the screen when a first run put it up ahead of the launch
        // repairs: laid out again, the bar would start over.
        if (findViewById<View>(R.id.splashRoot) != null) return
        setContentView(R.layout.activity_splash)
        findViewById<View>(R.id.splashRoot).padForSystemBars()
        tintSplashText()
    }

    /**
     * The splash as the first-run branch of [onCreate] sets it up, the text and
     * the bar included, for the launch repairs to run under. Only existing
     * strings: the update line on an upgrade, the layout's own text on a fresh
     * install.
     */
    private fun showFirstRunSplash(setup: FirstRunSetup) {
        showSplashLayout()
        findViewById<ProgressBar>(R.id.progressBar).isIndeterminate = true
        if (setup.getPreviousVersionCode() > 0) {
            findViewById<TextView>(R.id.statusText).text = getString(R.string.status_updating_app)
        }
    }

    /**
     * The splash an ordinary launch waits under while its repairs run off the
     * main thread: the icon, the name and a moving bar. No status line, since the
     * layout's own says files are being extracted and none are.
     */
    private fun showLaunchSplash() {
        showSplashLayout()
        findViewById<ProgressBar>(R.id.progressBar).isIndeterminate = true
        findViewById<TextView>(R.id.statusText).text = ""
    }

    /**
     * The layout's text is light, for the theme's dark window, and the window is
     * the editor's colour now. Dimmed as the status line is, 6.2:1 on white.
     */
    private fun tintSplashText() {
        if (isLightColor(lastPageColor(this))) {
            val text = getColor(R.color.colorOnLightBackground)
            findViewById<TextView>(R.id.appName).setTextColor(text)
            findViewById<TextView>(R.id.statusText).setTextColor(text)
        }
    }

    /**
     * Takes the colour [readSavedPageColor] found as the editor's: kept for the
     * screens after this one, and given to the setup screen if that is still up.
     * A screen that replaced it keeps the colour it is drawn for: the toolchain
     * picker, whose text is light.
     */
    private fun showSavedPageColor(color: Int) {
        if (isDestroyed) return
        keepStartColor(color)
        if (findViewById<View>(R.id.splashRoot) == null) return
        paintWindow(color)
        tintSplashText()
    }

    /**
     * Promotes [NodeService] to the foreground for the length of the unpack.
     *
     * Starts no server: the service answers this action by promoting and nothing
     * else, and leaves its own running flag alone so the real start that follows
     * is still answered with a launch. Reported rather than thrown on: a refused
     * promotion costs the protection, not the setup, and the run continues
     * exactly as it did before this existed.
     */
    private fun holdProcessForSetup(): Boolean = try {
        startForegroundService(
            Intent(this, NodeService::class.java).apply { action = NodeService.ACTION_HOLD }
        )
        SetupHolds.taken()
        true
    } catch (e: Exception) {
        Logger.w(tag, "Could not hold the process for setup: ${e.message}")
        false
    }

    /**
     * Gives that hold back, to the service only when no other holder in this
     * process still needs it ([SetupHolds]). Plain [startService]: the service
     * is already up.
     */
    private fun releaseSetupHold() {
        if (!SetupHolds.givenBack()) return
        try {
            startService(
                Intent(this, NodeService::class.java).apply {
                    action = NodeService.ACTION_RELEASE_HOLD
                }
            )
        } catch (e: Exception) {
            Logger.w(tag, "Could not release the setup hold: ${e.message}")
        }
    }

    private fun runSetupWithRetry(
        setup: FirstRunSetup,
        statusText: TextView,
        progressBar: ProgressBar
    ) {
        // The hold a first run took ahead of its repairs, kept until this run ends.
        val heldEarlier = heldThroughRepairs.also { heldThroughRepairs = false }
        lifecycleScope.launch {
            // Held across the unpack, and only across it. Until this existed the
            // process wrote 810 MiB with no started component behind it, so a
            // user who pressed Home during the first run handed the low-memory
            // killer a process it was free to take mid-write. The run resumes
            // rather than restarting now, but resuming still costs the wait
            // twice, and the hold is what stops it being paid.
            //
            // try/finally rather than a release after the `when`, because a
            // destroyed Activity cancels this scope. The unpack itself blocks
            // with no suspension point in it, so for the instance running it the
            // cancellation is only observed once it has RETURNED, which is to
            // say once the unpack is finished: the finally then gives the hold
            // back at the right moment on both roads, and never in the middle
            // of a write.
            //
            // Taken inside `runSetup` rather than before it, because that
            // argument covers only the instance that runs the unpack. A second
            // Splash created during it (a locale change mid-extraction, or
            // MainActivity handing a VIEW launch back here) parks on the setup
            // lock, and parking is a suspension point: destroying that one
            // throws it out of the lock at once, so a hold it had taken here
            // was given back by its finally while the first instance was still
            // writing. The service answers that release by leaving the
            // foreground and stopping, which is the exact state the hold exists
            // to prevent, and a Back press on the waiter left nothing to take it
            // again. `runSetup` calls this only from the instance that has the
            // lock and work to do, so `held` stays false for a waiter and its
            // finally releases nothing.
            var held = false
            val result = try {
                setup.runSetup { held = holdProcessForSetup() }
            } finally {
                if (held) releaseSetupHold()
                if (heldEarlier) releaseSetupHold()
            }
            when (result) {
                FirstRunSetup.SetupResult.SUCCESS -> {
                    continueAfterSetup()
                }
                FirstRunSetup.SetupResult.LOW_STORAGE -> {
                    val message = getString(
                        R.string.error_storage_full, FirstRunSetup.storageToFreeMb()
                    )
                    showSetupError(statusText, progressBar, message, setup)
                }
                FirstRunSetup.SetupResult.ERROR -> {
                    // The cause, when setup got far enough to know one. It reached
                    // Logger.e and nothing else before this, so a release build
                    // left the user with "Setup failed" and a Retry button that
                    // walks into the same wall.
                    val message = setupFailureMessage(this@SplashActivity, setup.lastFailure)
                    showSetupError(statusText, progressBar, message, setup)
                }
            }
        }
    }

    private fun showSetupError(
        statusText: TextView,
        progressBar: ProgressBar,
        message: String,
        setup: FirstRunSetup
    ) {
        runOnUiThread {
            statusText.text = message
            progressBar.visibility = View.GONE
            // Nothing is in flight from here, and this screen changes only when a
            // person taps, so the reason activity_splash.xml holds the display
            // awake has just expired. Left held, a phone put down or pocketed on
            // this screen lights until the battery is flat. The layout's own
            // comment makes this argument for the picker one screen later; the
            // failure state is the wait it did not cover, because the same layout
            // hosts it. Retry below turns it back on, and a Retry that succeeds
            // leaves this layout for one that never had the flag.
            val root = findViewById<View>(R.id.splashRoot)
            root.keepScreenOn = false
            // Spoken, because the screen alone cannot say it. Setup writes into one
            // label for minutes; a user not touching the screen hears nothing between
            // the window opening and MainActivity arriving, so "still extracting" and
            // "gave up" sound identical and they wait on a screen that is finished.
            //
            // announceForAccessibility rather than a live region on the label: a live
            // region would speak every progress update too, and a percentage read
            // aloud every few hundred milliseconds is a way of saying nothing while
            // making noise. What has to be spoken is the transition, and this is the
            // only place a failure passes through.
            statusText.announceForAccessibility(message)
            // The button is in activity_splash.xml, gone until now, and the scroll
            // container above it ends at its top, so a long message scrolls
            // instead of pushing the only control out of the window. Its own click
            // hides it again, so the progress screen carries no dead button.
            val retryButton = findViewById<Button>(R.id.retryButton) ?: return@runOnUiThread
            retryButton.setOnClickListener {
                // Reset UI and retry
                statusText.text = getString(R.string.extracting_message)
                progressBar.visibility = View.VISIBLE
                progressBar.isIndeterminate = true
                progressBar.progress = 0
                // Extraction is about to run again, so the reason the flag
                // exists is back.
                root.keepScreenOn = true
                retryButton.visibility = View.GONE
                runSetupWithRetry(setup, statusText, progressBar)
            }
            retryButton.visibility = View.VISIBLE
            // A message taller than the window is read from its first line, and the
            // icon and the name above it are what scrolls away. After the next
            // layout, when the message has its new height; the scroll view clamps
            // the offset, so a message that fits moves nothing. Short of the fade
            // by its own length, or the first line would sit inside the fading edge.
            val scroll = findViewById<NestedScrollView>(R.id.splashScroll)
            scroll.doOnLayout { scroll.scrollTo(0, statusText.top - scroll.verticalFadingEdgeLength) }
            // Deliberately NOT moving accessibility focus to the Retry button, and
            // that is a reversal: the first attempt did, and lint's AccessibilityFocus
            // check refused it. It is right. Stealing focus contradicts what a screen
            // reader user expects of every other app, and it drops them somewhere they
            // did not navigate to. What they need instead is to know the screen has
            // changed and what to do about it, and both failure strings already end in
            // "Tap Retry", so the announcement above carries the instruction and
            // ordinary swiping reaches the control.
        }
    }

    /**
     * Re-reads what is on disk for a picker that is still on show.
     *
     * The set behind the refusal in `ToolchainCardState.toggleSelection` was read
     * once, when the cards were built, and this screen can be left and come back:
     * the launcher shortcut to [ToolchainActivity] is already published on every
     * launch that reaches the picker with the offer unanswered, so a toolchain can
     * be installed from there while these cards sit waiting for a person. A card
     * drawn from the older reading carries no badge and still takes a tick, and
     * ticking one spends a download that has already been paid for.
     *
     * The re-read exists for that one screen; [ToolchainActivity] re-reads in its
     * own `onStart` for the same reason. Reading here costs one small JSON file and
     * only while the picker is up. It also shows the notices held while the user
     * was away, runs a [launchMain] that was asked meanwhile, and puts Play's
     * cellular-data question if it was asked meanwhile ([confirmationDeferred]).
     */
    override fun onStart() {
        super.onStart()
        stopped = false
        ToolchainManager(applicationContext).let {
            pickerAdapter?.setInstalled(it.getInstalledToolchains(), it.installedLabels())
        }
        // Before the launch held while the user was away: a notice raised meanwhile goes on a
        // dialog now, and the launch then waits for it rather than finishing the screen under it.
        AppNotices.flush(this)
        if (launchPending) {
            launchPending = false
            launchMain()
        }
        if (confirmationDeferred) {
            confirmationDeferred = false
            askForCellularConfirmation()
        }
    }

    // A paused screen can return without another onStart.
    override fun onResume() {
        super.onResume()
        progressSink?.let { FirstRunSetup.attachProgress(it) }
        // From here and not from onStart or onCreate: a screen counts as started only once onStart
        // has returned, and until then a text too long for a toast is cut to a toast now and kept
        // as a dialog for a later start, which a setup that ends first never reaches.
        if (installWaitsNotice) {
            installWaitsNotice = false
            AppNotices.showNotice(this, R.string.toolchain_install_after_setup)
        }
    }

    override fun onStop() {
        stopped = true
        FirstRunSetup.detachProgress(progressSink)
        super.onStop()
    }

    override fun onDestroy() {
        // A question kept for a start this screen will not have. The platform marks the screen
        // destroyed before it calls this, so handleDownloadState reads the question as one it
        // cannot put, ends the pack and moves the queue on; left waiting, the queue stopped on it
        // for good. First, so a queue that ends here gives back the listener released below.
        if (confirmationDeferred) {
            confirmationDeferred = false
            downloadQueue.getOrNull(currentDownloadIndex)?.let {
                handleDownloadState(it, AssetPackStatus.REQUIRES_USER_CONFIRMATION, 0)
            }
        }
        // A Play completion installs the current pack and advances the queue even
        // after this screen leaves. The queue releases the listener and callback when done.
        toolchainManager?.unregisterListenerIfIdle()
        // Without this the extraction goes on reporting into a destroyed
        // activity's closure, which keeps that Activity and its whole view
        // hierarchy reachable for the rest of a run measured in minutes.
        //
        // Identity-checked on the far side, and the case that needs it is two
        // Splash instances existing at once. Not noHistory, which the manifest
        // deliberately does not set: MainActivity.handOffToSetup starts a fresh
        // Splash from a VIEW launch while a launcher-task Splash may be
        // mid-setup, and then the departing one's onDestroy can run after the
        // replacement has installed its own sink, so a blanket clear would
        // silence the screen the user is looking at. A config-change relaunch
        // runs the other way round -- the old instance is destroyed before the
        // new one is created -- so ordering alone would have covered that one.
        FirstRunSetup.detachProgress(progressSink)
        progressSink = null
        colorProbe?.destroy()
        colorProbe = null
        AppNotices.drop(this, carry = true)
        super.onDestroy()
    }

    // -- Picker phase --

    /**
     * Where a launch goes once setup is behind it: the picker, or the editor.
     *
     * The single place that decision is made, and that is the fix rather than a
     * tidy-up. It used to live in the continuation of the coroutine that ran
     * setup, which is the one place it cannot survive: `runSetupLocked`'s body is
     * blocking with no suspension point in it, so a relaunch part-way through
     * cancels the coroutine while the extraction runs on to `markSetupComplete()`.
     * `isFirstRun()` then goes false with the continuation never resumed, and the
     * offer was only ever made inside the `isFirstRun()` branch, so the picker was
     * gone for the life of that install. The way back is a launcher long-press,
     * which nothing tells the user about.
     *
     * Not a corner case. Locale, font scale and display size are all undeclared in
     * this activity's `configChanges` on purpose, so changing any of them during
     * an extraction that runs for minutes relaunches the activity, and the
     * manifest says as much where it lists them.
     *
     * Asking on every launch rather than only after setup costs nothing on an
     * install that answered the picker, because answering it is what writes the
     * preference: Skip and a Continue with nothing new to fetch call
     * [markPickerShown] at once, and a Continue that starts downloads leaves it to
     * [endDownloadQueue], with [downloadQueueRunning] keeping the picker down in
     * this process until then.
     * An upgrade is unaffected either way, since a new versionName or versionCode
     * makes `isFirstRun()` true and that route already passed through here.
     *
     * It is also where the delivered-pack reconcile runs, so that a first run has
     * finished unpacking before anything copies a toolchain into the same tree.
     */
    private fun continueAfterSetup() {
        // A Play pack that finished downloading after its screen went away was
        // never installed: the COMPLETED callback is the only thing that installs
        // one, and both screens drop their listener at teardown. Returns
        // immediately; the copy runs on the toolchain I/O thread.
        //
        // Here rather than in onCreate, and the difference is minutes on the one
        // launch that matters. This copies up to 180 MB into the same `usr/` the
        // first run unpacks the asset tree into (BuildConfig.EXTRACTED_ASSET_BYTES,
        // which moves with every pin), and the two space pre-flights cannot see
        // each other: setup measures usableSpace before extracting, the install
        // measures it before copying, and neither reserves for the other, so both
        // can pass and one then meets ENOSPC. The extraction survives that, it
        // aborts and the retry keeps credit for every byte it wrote. The install
        // is worse off: a copy that lands and then cannot write its record leaves
        // the tree under no manifest. What used to make that permanent was that
        // nothing on disk named the tree; an install now writes a marker before
        // it copies, and the launch repair above reclaims what the marker names,
        // so the space comes back on the next start rather than never.
        //
        // Reached from every route out of onCreate, so it still runs on every
        // launch. A launch whose setup failed skips it, and that costs nothing it
        // could have given back: the reclaim half calls removePack, which posts
        // the delete and returns, so it was never going to free bytes in time for
        // a pre-flight that had already read them.
        repair("the delivered toolchain reconcile") { ToolchainManager(applicationContext).reconcileDeliveredPacks() }
        if (shouldShowPicker()) {
            Logger.i(tag, "The toolchain picker has not been answered yet; offering it")
            showToolchainPicker()
        } else {
            launchMain()
        }
    }

    private fun shouldShowPicker(): Boolean {
        if (downloadQueueRunning) return false
        val prefs = getSharedPreferences("vscodroid", MODE_PRIVATE)
        return !prefs.getBoolean("toolchain_picker_shown", false)
    }

    private fun markPickerShown() {
        getSharedPreferences("vscodroid", MODE_PRIVATE).edit() {
            putBoolean("toolchain_picker_shown", true)
        }
    }

    private fun showToolchainPicker() {
        // Its cards and text are drawn for the theme's dark window, whatever the
        // editor's colour onCreate gave this one.
        paintWindow(getColor(R.color.colorBackground))
        setContentView(R.layout.layout_toolchain_picker)
        findViewById<View>(R.id.pickerRoot)
            .padForSystemBars(basePx = (24 * resources.displayMetrics.density).toInt())

        val grid = findViewById<RecyclerView>(R.id.toolchainGrid)
        val continueBtn = findViewById<Button>(R.id.continueButton)
        val skipBtn = findViewById<Button>(R.id.skipButton)

        val adapter = ToolchainPickerAdapter(ToolchainCardMode.PICKER)
        // What is already on disk, which this screen used to be able to assume was
        // nothing. That held while the picker was reached only from the end of a
        // fresh first run; it stopped holding when the offer moved to every launch
        // that has not answered it. An install whose first run was interrupted
        // reached the editor with the preference unset and with the launcher
        // shortcut published, and ToolchainActivity installs without touching that
        // preference, so a toolchain can be installed long before this screen is
        // offered. Ticking one of those spends the download a second time.
        //
        // Read on the main thread, as ToolchainActivity already reads it twice: it
        // is one small JSON file, and the alternative is drawing the cards wrong
        // and correcting them afterwards.
        ToolchainManager(applicationContext).let {
            adapter.setInstalled(it.getInstalledToolchains(), it.installedLabels())
        }
        grid.layoutManager = GridLayoutManager(this, 2)
        grid.adapter = adapter
        // So [onStart] can correct these cards when the screen comes back. One
        // reading is a reading of the moment it was taken, and this screen waits
        // for a person.
        pickerAdapter = adapter

        continueBtn.setOnClickListener {
            // Asked of the record again rather than of the cards, because the two
            // can disagree by the time this is tapped. `setInstalled` replaces what
            // is installed and does not untick anything, so a pack ticked here and
            // then installed from the Toolchains screen keeps its tick behind its
            // own badge; and the record can change with no lifecycle callback to
            // hang a refresh on, since `reconcileDeliveredPacks` runs on the
            // toolchain I/O thread from the line that leads to this screen. This is
            // the last point before the download is spent.
            val selected = notYetInstalled(
                adapter.getSelectedPackNames(),
                ToolchainManager(applicationContext).getInstalledToolchains(),
            )
            if (selected.isEmpty()) {
                markPickerShown()
                launchMain()
            } else {
                // Not answered yet: the downloads that follow live in this process, which can end
                // before they do, and the packs behind the one in flight would then be lost with
                // nothing offering them again. The picker is marked answered where the queue ends
                // ([endDownloadQueue]), so a process that dies in between shows it at the next start,
                // while a splash made again in this process meanwhile does not ([downloadQueueRunning]).
                startDownloads(selected)
            }
        }

        skipBtn.setOnClickListener {
            markPickerShown()
            launchMain()
        }
    }

    // -- Download progress phase --

    private fun startDownloads(packNames: List<String>) {
        // The picker's cards go with the layout that held them, so nothing may
        // push into them after this line.
        pickerAdapter = null
        setContentView(R.layout.layout_toolchain_progress)
        findViewById<View>(R.id.progressRoot)
            .padForSystemBars(basePx = (24 * resources.displayMetrics.density).toInt())

        val container = findViewById<LinearLayout>(R.id.progressContainer)
        val cancelBtn = findViewById<Button>(R.id.cancelButton)

        downloadQueue = packNames.toMutableList()
        currentDownloadIndex = -1
        cancelled = false
        // Before the first pack is asked for: a refusal reports on this thread and can end the
        // queue before [downloadNext] returns.
        downloadQueueRunning = true
        progressRows.clear()

        // Build progress rows for each toolchain
        for (packName in packNames) {
            val info = ToolchainRegistry.find(packName) ?: continue
            val row = buildProgressRow(info)
            container.addView(row.first)
            progressRows[packName] = row.second
        }

        // Set up toolchain manager
        val manager = ToolchainManager(applicationContext)
        toolchainManager = manager
        val alreadySaid = mutableSetOf<ToolchainFailure>()
        // Strongly, and holding it weakly is what this replaced. The callback is
        // the only thing that advances the queue -- handleDownloadState ends in
        // downloadNext(), which installs the pack behind the one that just
        // settled -- so behind a weak reference the queue stopped whenever the
        // collector happened to run. Pressing Back on this
        // screen sets nothing (only Cancel sets `cancelled`), so a user who
        // picked three toolchains got somewhere between one and three of them,
        // decided by the timing of a collection.
        //
        // What bounds the retention is who holds the manager. `toolchainManager`
        // is a field of this screen and this callback names the screen, so the
        // two are a cycle, and a cycle nothing outside points at is collected
        // whole. Outside it there are exactly two holders and both ARE the queue:
        // an ioExecutor task running an HTTP transfer, which ends when the
        // transfer does, and Play Core's listener registry. The registry is the
        // one that needs saying, because [onDestroy] keeps it while the current
        // request is outstanding; [endDownloadQueue] is what settles it.
        // This screen is therefore reachable for as long as the
        // downloads it started are running, and not a step past them. That holds
        // only because every state either settles or can be advanced past:
        // [handleDownloadState] ends the queue on a status that will not arrive
        // on its own AND on the one that would wait for a question this screen
        // can no longer put.
        // ToolchainActivity holds its screen weakly because nothing there depends
        // on the callback arriving; here everything does.
        manager.onStateChange = { packName, status, percent, why, _ ->
            runOnUiThread {
                // The row has space for one word and the reason is a sentence, so
                // it is said here rather than squeezed in there. Without it the
                // queue moves on and the only record of WHY is in logcat.
                //
                // To this screen only. The queue outlives it, so a failure that
                // lands after the user has left put its sentence over the editor,
                // unattributed and about a screen that is no longer there. Same
                // rule as ToolchainActivity, which gates its own Toast on the
                // screen being started; the test on it is weaker here because
                // this screen never comes back, so there is nothing to defer to.
                //
                // Before handleDownloadState, never after it. The pack that settles
                // last ends the queue in there, and the end of the queue starts the
                // editor and finishes this screen, so a reason asked for afterwards
                // met a finishing screen and was dropped: one toolchain ticked on a
                // full disk flashed "Failed" and the editor opened with nothing said.
                // A reason too long for a toast is a dialog on this screen, and
                // [launchMain] waits for it to be closed.
                val reason =
                    if (isFinishing || isDestroyed) null else reasonToAnnounce(why, alreadySaid)
                if (reason != null) {
                    AppNotices.showNotice(this, getString(reason.message), Toast.LENGTH_LONG)
                }
                handleDownloadState(packName, status, percent)
            }
        }
        manager.registerListener()

        cancelBtn.setOnClickListener {
            cancelled = true
            val currentPack = downloadQueue.getOrNull(currentDownloadIndex)
            if (currentPack != null) {
                manager.cancel(currentPack)
            }
            // Nothing after this is acted on -- handleDownloadState returns on
            // `cancelled` -- so the queue is over here just as it is when it
            // drains, and what the manager holds of this screen goes back.
            endDownloadQueue()
            launchMain()
        }

        // Start first download
        downloadNext()
    }

    private fun buildProgressRow(
        info: ToolchainRegistry.ToolchainInfo,
    ): Pair<View, ProgressRow> {
        val ctx = this
        val rowLayout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            lp.bottomMargin = (16 * resources.displayMetrics.density).toInt()
            layoutParams = lp
        }

        val nameText = TextView(ctx).apply {
            text = info.shortLabel
            setTextColor(getColor(R.color.colorOnSurface))
            textSize = 16f
        }

        val progressBar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            progressTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.colorPrimary))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            lp.topMargin = (4 * resources.displayMetrics.density).toInt()
            layoutParams = lp
        }

        val statusText = TextView(ctx).apply {
            text = getString(R.string.progress_waiting)
            setTextColor(getColor(R.color.colorOnSurface))
            // 0.7 rather than 0.6: this text is 12sp, so WCAG AA asks 4.5:1, and
            // dimming #CCCCCC to 0.6 on this window measures 4.58:1, clearing that
            // line by 0.08. At 0.7 it is 5.78:1 and still reads as secondary beside
            // the pack name. The 3.62:1 this comment used to quote is the figure for
            // 0.5, which is what the picker's dimmed labels carried, not for 0.6.
            alpha = 0.7f
            textSize = 12f
        }

        rowLayout.addView(nameText)
        rowLayout.addView(progressBar)
        rowLayout.addView(statusText)

        return rowLayout to ProgressRow(nameText, progressBar, statusText)
    }

    private fun downloadNext() {
        if (cancelled) return
        currentDownloadIndex++
        if (currentDownloadIndex >= downloadQueue.size) {
            // All done
            endDownloadQueue()
            launchMain()
            return
        }
        val packName = downloadQueue[currentDownloadIndex]
        progressRows[packName]?.statusText?.text = getString(R.string.progress_installing)
        toolchainManager?.install(packName)
    }

    /**
     * Gives back everything the manager holds of this screen, once the queue has
     * nothing left to report.
     *
     * Both halves, because either one alone keeps it. `onStateChange` names this
     * Activity and the manager holds the callback: while the queue is running
     * that is exactly right, since the callback is what advances it, and the
     * moment the queue is over it is a finished window kept alive by a download
     * that has ended.
     *
     * [onDestroy] leaves the Play listener registered while a request is outstanding,
     * so the queue itself must take it out of the process-wide registry when done.
     *
     * Called where the queue ends and nowhere else: from [downloadNext] when the
     * last pack settles, and from Cancel. That it is reached at all rests on
     * [handleDownloadState] advancing on every status that will not arrive on its
     * own, and on the one status that would otherwise wait for ever, a
     * cellular-data confirmation this screen is no longer alive to put.
     * NOT from [onDestroy], which is the one
     * place it must not be: leaving this screen does not stop the transfer, and
     * clearing the callback there is what leaves the packs behind the in-flight
     * one uninstalled.
     */
    private fun endDownloadQueue() {
        markPickerShown()
        downloadQueueRunning = false
        toolchainManager?.unregisterListener()
        toolchainManager?.onStateChange = null
    }

    /**
     * Paints a terminal result on a progress row, at full opacity.
     *
     * The row's status text is dimmed while it counts percentages, which is right
     * for a number that changes every second and wrong for the one word the user
     * has to read at the end. Setting only the colour left the dimming in place:
     * "Failed" measured 3.28:1 and "Done" 3.81:1 against this window, both under
     * the 4.5:1 AA asks for 12sp text. At full opacity they are 6.79:1 and 8.18:1.
     *
     * A helper rather than two lines twice, because the pairing is the point: a
     * result colour without the opacity is the defect, and the next result added
     * here should not be able to reintroduce it by copying one half.
     */
    private fun showResult(view: android.widget.TextView, colorRes: Int, packLabel: String) {
        view.setTextColor(getColor(colorRes))
        view.alpha = 1f
        // And spoken. The queue holds the user until every pack finishes, and rows
        // moved Waiting -> percent -> Done or Failed with nothing said, so a stalled
        // download and a progressing one sounded the same and there was no way to
        // tell whether to wait or press Cancel.
        //
        // Terminal states only, deliberately. A live region on this label would
        // announce every percentage tick, which is noise rather than information;
        // the framework already emits a progress event for the bar beside it, and a
        // user can swipe to a row and hear the current value on demand. What could
        // not be reached at all was the end of the story.
        // Named, because a queue speaks several of these and "Done" on its own says
        // which of them only to someone watching the rows move.
        view.announceForAccessibility("$packLabel: ${view.text}")
    }

    private fun handleDownloadState(packName: String, status: Int, percent: Int) {
        if (cancelled) return

        // Only the download the queue is waiting for gets to speak.
        //
        // The listener is registered for the app rather than for one fetch
        // (ToolchainManager.registerListener), and every queued pack gets a row up
        // front, so a state naming some other pack reaches here and used to be
        // acted on. It could repaint a finished pack's row red, and worse, move
        // the index -- stepping over whichever pack was genuinely downloading and
        // leaving it uninstalled with its row still reading "installing".
        //
        // downloadNext() increments the index before calling install(), so the
        // pack being fetched is always the one at the index by the time any state
        // for it arrives. Past the end of the queue nothing matches, which is also
        // what stops a late arrival reaching launchMain() a second time.
        if (!isCurrentDownload(packName, downloadQueue, currentDownloadIndex)) {
            Logger.d(tag, "Ignoring $packName at status $status; not the current download")
            return
        }

        val row = progressRows[packName] ?: return

        // Play's cellular-data question needs a window to put it in, and this
        // screen is the only one that can put it. The queue outlives the screen
        // on purpose (pressing Back sets nothing, only Cancel sets `cancelled`),
        // so a pack that reaches REQUIRES_USER_CONFIRMATION after the user has
        // left is waiting on a question nobody will ever be asked:
        // showCellularDataConfirmation needs a live Activity, its failure is only
        // logged, the status never settles, and the advance below never fires.
        // The queue then stops rather than skips, so this pack and every one
        // behind it stay uninstalled, and because [endDownloadQueue] is reached
        // only where the queue ends, this destroyed screen and its whole view
        // hierarchy stay in Play Core's process-wide registry for the life of the
        // process. ToolchainActivity defers the question to its next onStart
        // instead; a screen that is gone has no next onStart, since launchMain
        // finishes this screen for good. One that is only stopped does, and the
        // question waits for it ([confirmationDeferred]).
        //
        // Losing the pack is the lesser half of that trade, and it is recoverable:
        // ToolchainActivity's poll picks up a pack still sitting in this status
        // and puts the question itself.
        val unaskable = status == AssetPackStatus.REQUIRES_USER_CONFIRMATION &&
            (isFinishing || isDestroyed)
        // A question kept for the next start is about this pack waiting on it; any other status
        // of the pack means it was answered elsewhere or no longer matters.
        if (status != AssetPackStatus.REQUIRES_USER_CONFIRMATION) confirmationDeferred = false

        when (status) {
            AssetPackStatus.DOWNLOADING, AssetPackStatus.TRANSFERRING -> {
                row.progressBar.progress = percent
                // Through the resource, not "$percent%": the sign's position and
                // the digit glyphs are both locale-dependent, and getString
                // formats with the configuration's locale.
                row.statusText.text = getString(R.string.progress_percent, percent)
            }
            AssetPackStatus.COMPLETED -> {
                row.progressBar.progress = 100
                row.statusText.text = getString(R.string.progress_done)
                showResult(row.statusText, R.color.colorSuccess, row.nameText.text.toString())
            }
            AssetPackStatus.PENDING, AssetPackStatus.WAITING_FOR_WIFI -> {
                row.statusText.text = getString(R.string.progress_waiting)
            }
            AssetPackStatus.REQUIRES_USER_CONFIRMATION -> {
                if (unaskable) {
                    row.statusText.text = getString(R.string.progress_failed)
                    showResult(row.statusText, R.color.colorError, row.nameText.text.toString())
                } else if (stopped) {
                    confirmationDeferred = true
                } else {
                    askForCellularConfirmation()
                }
            }
            // Anything else ends this pack without installing it, with one
            // exception the branch below names. FAILED is the expected one;
            // CANCELED arrives when the download is cancelled from the Play
            // notification rather than from this screen, and UNKNOWN and
            // NOT_INSTALLED arrive when Play has nothing to report for it.
            else -> {
                if (handedToAnotherInstall(status, ToolchainManager.packIsBeingInstalled(packName))) {
                    // Not a failure, and the row is already telling the truth: it
                    // reads "Installing...", set by [downloadNext], and an install
                    // holding this pack is putting it in place right now. So the
                    // correct repaint is no repaint. Painting "Failed" here told
                    // the user an install had failed while it was succeeding.
                    Logger.i(tag, "Pack $packName was handed to an install already running")
                } else {
                    if (status != AssetPackStatus.FAILED) {
                        Logger.w(tag, "Pack $packName ended at status $status")
                    }
                    row.statusText.text = getString(R.string.progress_failed)
                    showResult(row.statusText, R.color.colorError, row.nameText.text.toString())
                }
            }
        }

        // Deliberately outside the when, and that is the whole fix.
        //
        // Advancing used to be a call inside two of the branches, so a status
        // matching no branch advanced nothing: downloadNext() was never reached
        // and the screen sat on its progress list for the rest of the session,
        // with every toolchain behind the stalled one left uninstalled. Deciding
        // it here, in one place, means a branch cannot forget to do it
        // -- which is the shape the bug had, rather than the particular states
        // it happened to miss.
        //
        // The stall is not a dead end, and an earlier version of this comment
        // said it was: cancelButton is always visible and goes straight to
        // launchMain(), and a relaunch skips setup entirely because
        // markSetupComplete() has already run by the time this screen appears.
        //
        // `unaskable` beside it rather than inside [isTerminalPackStatus],
        // because the word there means what Play says about the pack and this is
        // about what this screen can still do with it. The other reader of that
        // function, ToolchainActivity's subscription release, must go on hearing
        // "still going somewhere" for a confirmation it can defer to its onStart.
        if (isTerminalPackStatus(status) || unaskable) {
            downloadNext()
        }
    }

    /**
     * Puts Play's cellular-data question, from a screen that is in front: from
     * [handleDownloadState] when the question arrives on a started screen, and from [onStart] for
     * one [confirmationDeferred] kept.
     */
    private fun askForCellularConfirmation() {
        try {
            toolchainManager?.showConfirmationDialog(this)
        } catch (e: Exception) {
            Logger.e(tag, "Failed to show confirmation dialog", e)
        }
    }



    // -- Navigation --

    private fun launchMain() {
        // The download queue outlives this screen, and nothing else stops it.
        // Only Cancel sets `cancelled`, so pressing Back on the first-run
        // download screen finishes this Activity while the transfer carries on:
        // the download still reports through `onStateChange`, so minutes later
        // `downloadNext()` drains the queue and a destroyed Activity calls
        // startActivity. Either the app pops itself into the foreground long
        // after the user left it, or Android's background-activity-start rules
        // drop the launch and nothing says so.
        //
        // The navigation alone, never the whole method: an early return here also
        // skipped publishToolchainShortcut() below, and that is the only publisher
        // of the one route to the Toolchains screen that needs no editor. Pressing
        // Back during the first-run downloads therefore cost the user that route
        // for the session, which is a worse trade than the stray launch this guard
        // exists to stop.
        val gone = isFinishing || isDestroyed
        // A stopped screen is one the user has left. Setup that ends then used to start
        // the editor from the background, which the platform either drops or shows over
        // whatever they are doing, and the finish() below closed the task with nothing
        // started. [onStart] runs this again when they come back.
        val away = !gone && stopped
        // A dialog on this screen is a notice too long for a toast, most often why a toolchain
        // the queue just ended on could not be installed, and the finish() below takes it down
        // with the screen, read or not. The editor starts when the person closes it instead.
        val held = !gone && !away && AppNotices.afterDialog(this) { launchMain() }
        if (gone) {
            Logger.i(tag, "The splash screen is gone; not launching the editor behind the user")
        } else if (away) {
            Logger.i(tag, "The splash screen is in the background; the editor starts when it returns")
            launchPending = true
        } else if (held) {
            Logger.i(tag, "A notice is on the splash screen; the editor starts when it is closed")
        } else {
            startActivity(Intent(this, MainActivity::class.java).apply {
                data = intent?.data
            })
        }
        // After the editor is on its way, never before.
        //
        // pushDynamicShortcut is a synchronous binder round trip to the system
        // server, and running it first made the editor wait for it: measured on
        // an idle API 36 emulator over 20 cold starts, the delay before
        // startActivity returned was a median of 49.6 ms with it in front and
        // 15.2 ms behind. The call itself costs the same either way -- 19.9 ms
        // against 20.1 ms -- so what changed is who waits for it.
        //
        // startActivity got faster on its own too, 26.4 ms to 15.2 ms, which is
        // the same effect from the other side: it had been issued immediately
        // after a round trip the system server was still unwinding.
        //
        // Those are floor numbers. The devices this project exists for have a
        // busier system server than an idle emulator does.
        publishToolchainShortcut()
        if (!gone && !away && !held) finish()
    }

    /**
     * Publishes the launcher shortcut that opens [ToolchainActivity].
     *
     * The screen had no way in. It is not exported, it has no launcher entry,
     * and its only caller was the `openToolchainSettings` command on the
     * BroadcastChannel relay, which nothing sent -- so the picker's one
     * appearance decided the toolchains permanently.
     *
     * There are two routes now. `saf-bridge` declares `browser`, so it reaches
     * the relay, and its `VSCodroid: Manage Toolchains` command is the sender
     * that command never had. This shortcut is the other one, and it is the one
     * that survives a workbench that has not loaded: reaching this screen
     * matters most when the editor layer is the thing that is broken, which is
     * exactly when the Command Palette is not there to be opened.
     *
     * Pushed at runtime rather than declared in res/xml/shortcuts.xml, for two
     * reasons that both bite:
     *
     *  - A static shortcut names its target package as a literal, and
     *    applicationIdSuffix makes that literal wrong for every build except
     *    release. Intent(this, ToolchainActivity::class.java) is correct in all
     *    of them.
     *  - A static shortcut exists from the moment the app is installed, so a
     *    long-press before the first launch would open the screen with
     *    filesDir/usr not yet extracted, and install a toolchain over a base
     *    that is not there. Publishing from launchMain() -- the one funnel every
     *    completed startup passes through -- means the shortcut cannot exist
     *    before the first setup. It does outlive an update, whose setup runs
     *    again, which is why ToolchainActivity hands an install asked for
     *    before that setup has finished back here.
     *
     * Re-pushed on every launch on purpose: it is how an install that predates
     * this change gains the shortcut, and how the label follows a locale change.
     *
     * Failure is swallowed for the same reason the launch-time refresh above
     * swallows its own: a missing shortcut is worth less than the editor, and
     * crashing here would leave a launch loop with no explanation.
     */
    private fun publishToolchainShortcut() {
        try {
            val published = ShortcutManagerCompat.pushDynamicShortcut(
                this,
                ShortcutInfoCompat.Builder(this, "toolchains")
                    .setShortLabel(getString(R.string.shortcut_toolchains_short))
                    .setLongLabel(getString(R.string.shortcut_toolchains_long))
                    .setIcon(IconCompat.createWithResource(this, R.drawable.ic_shortcut_toolchain))
                    .setIntent(
                        Intent(this, ToolchainActivity::class.java)
                            .setAction(Intent.ACTION_VIEW)
                    )
                    .build()
            )
            // It reports refusal by returning false, not by throwing -- rate
            // limiting is the documented case. Ignoring that would make the only
            // route to this screen disappear with nothing to show for it, which
            // is the failure mode the screen already had.
            if (!published) {
                Logger.w(tag, "Toolchain shortcut was refused; the screen has no other entry point")
            }
        } catch (e: Exception) {
            Logger.w(tag, "Could not publish the toolchain shortcut: ${e.message}")
        }
    }
}

/**
 * Whether [status] means the pack will not arrive on its own.
 *
 * Only five states are still going somewhere: the two transfer states, the two
 * waiting states, and the confirmation prompt. Everything else counts as
 * finished -- including states this build has never heard of -- because the cost
 * of being wrong is not symmetric, though neither side is free.
 *
 * A pack wrongly treated as finished costs that toolchain until the user
 * installs it again, which they now can: publishToolchainShortcut() gives
 * ToolchainActivity a launcher shortcut. Until that existed the loss was
 * permanent -- the screen's only caller was a BroadcastChannel command that at
 * the time had no sender (AndroidBridge.openToolchainSettings, dispatched by
 * the relay MainActivity injects; saf-bridge sends it now) -- so this paragraph
 * used to read "and costs it for good".
 *
 * A pack wrongly waited on costs that toolchain and every one queued behind it,
 * because the queue stops rather than skips, and leaves the screen sitting there
 * until the user finds the Cancel button. Losing one is better than losing the
 * remainder, which is the only reason this defaults to advancing.
 *
 * A second caller reads it for a different decision: [shouldReleaseSubscription]
 * asks the same question of one pack to decide whether the Toolchains screen's
 * Play Core subscription still has anything to hear. The two agree on what the
 * word means, which is why they share this rather than each spelling out a list.
 *
 * File scope so it can be tested without an Activity; this project's unit tests
 * have no Robolectric.
 */
internal fun isTerminalPackStatus(status: Int): Boolean = when (status) {
    AssetPackStatus.DOWNLOADING,
    AssetPackStatus.TRANSFERRING,
    AssetPackStatus.PENDING,
    AssetPackStatus.WAITING_FOR_WIFI,
    AssetPackStatus.REQUIRES_USER_CONFIRMATION -> false
    else -> true
}

/**
 * Whether [status] means another install in this process is already putting this
 * toolchain in place, rather than that the pack failed.
 *
 * `UNKNOWN` reaches this screen through the same callback a Play state does, and
 * it is what `ToolchainManager` reports when it declines a duplicate: the pack is
 * held by an install that is running right now, which is the opposite of a
 * failure. The screen reaches that case without anyone trying, because two
 * managers are live on every launch: the delivered-pack reconcile runs on one
 * while Continue queues installs on another, so Play answers COMPLETED for a pack
 * the first one is already copying and the second declines it.
 *
 * The status is tested as well as [heldElsewhere], and that is the load-bearing
 * half. Without it, a genuine FAILED arriving while some other pack happened to
 * be installing would be swallowed and the row would sit on "Installing..."
 * saying nothing, which is worse than the wrong word this fixes.
 *
 * `ToolchainActivity` tells the same two apart by what it asked for; this screen
 * has no equivalent, because here every pack was asked for.
 *
 * File scope and a plain boolean argument for the reason [isTerminalPackStatus]
 * has them: this project's unit tests have no Robolectric, so a predicate inside
 * an Activity cannot be run. The flag is read on the main thread, after the other
 * install may already have released the pack; that race is lost only in the
 * microseconds between the loser's decline and the winner's release, against a
 * copy that runs for tens of seconds, and losing it means today's "Failed", so
 * the direction a mistake takes is unchanged.
 */
internal fun handedToAnotherInstall(status: Int, heldElsewhere: Boolean): Boolean =
    status == AssetPackStatus.UNKNOWN && heldElsewhere

/**
 * The ticked packs still worth downloading, given what the install record says.
 *
 * The picker refuses to tick a toolchain it knows is installed, and that refusal
 * reads a set the screen was handed earlier. Two things get past it. A pack
 * ticked while it was genuinely absent stays ticked when a later reading finds it
 * installed, because replacing that set unticks nothing; and the record can be
 * written with no lifecycle callback to hang a re-read on, by the delivered-pack
 * reconcile that runs on its own thread from the launch this screen is part of.
 * Neither is caught downstream: `downloadNext` installs what it is given, and
 * `ToolchainManager.install` has no already-installed branch, so what gets past
 * here spends the whole download and the whole copy a second time.
 *
 * Both sides go through the registry rather than through string surgery, because
 * the two halves spell a toolchain differently: the install record names it the
 * short way ("java") and the cards name it the pack way ("toolchain_java"). A
 * name neither half knows, a toolchain withdrawn from the registry, matches
 * nothing and is left alone.
 *
 * File scope so it can be tested without an Activity; this project's unit tests
 * have no Robolectric.
 */
internal fun notYetInstalled(
    selected: Collection<String>,
    installed: Collection<String>,
): List<String> {
    val onDisk = installed.mapNotNull { ToolchainRegistry.find(it)?.packName }.toSet()
    return selected.filterNot { ToolchainRegistry.find(it)?.packName in onDisk }
}

/**
 * Whether [packName] is the download the queue is currently waiting for.
 *
 * The one question the old code never asked. It deduplicated by pack name, which
 * stops the same pack advancing twice but does nothing about a different pack
 * advancing once -- and a different pack is exactly what a listener registered
 * for the whole app can deliver.
 *
 * Returns false once the queue has passed its end, so a late arrival cannot
 * reach launchMain() a second time.
 */
internal fun isCurrentDownload(packName: String, queue: List<String>, index: Int): Boolean =
    packName == queue.getOrNull(index)

/**
 * The reason worth putting on screen, or null when it has been said already.
 *
 * Toasts stack rather than replace: each one holds the screen for about three
 * and a half seconds, and the queue starts the next download the moment one
 * fails, so failures that share a cause arrive back to back. Three packs picked
 * at first run and one full disk meant three identical messages in a row, most
 * of them over the editor, because the splash screen is gone by then.
 *
 * Per reason and not per pack: a second pack failing for the same cause tells
 * the user nothing they cannot already act on, while a different cause does and
 * still gets through. [alreadySaid] belongs to one run of the queue, so a later
 * attempt says it again.
 *
 * File scope so it can be tested without an Activity; this project's unit tests
 * have no Robolectric.
 */
internal fun reasonToAnnounce(
    why: ToolchainFailure?,
    alreadySaid: MutableSet<ToolchainFailure>,
): ToolchainFailure? = if (why != null && alreadySaid.add(why)) why else null

internal fun setupFailureMessage(context: Context, failure: FirstRunSetup.Failure?): String = when {
    failure == null -> context.getString(R.string.error_setup_failed)
    failure.step.isBlank() -> context.getString(R.string.error_setup_failed) + "\n" + failure.detail
    else -> context.getString(R.string.error_setup_failed_at, failure.step, failure.detail)
}

internal fun applySetupProgress(
    statusText: TextView,
    progressBar: ProgressBar,
    message: String,
    percent: Int,
) {
    statusText.text = message
    progressBar.isIndeterminate = percent <= 0
    progressBar.progress = percent
}

/**
 * The launch repairs of every launch: [files] on [io], so that the main thread
 * stays free to draw the splash and answer a touch however long they take, then
 * [after] back on the caller's thread if [alive] still says so. [files] is plain
 * file work and does not stop for a cancellation; one that comes meanwhile is
 * seen when it returns, and nothing after it runs. The next launch repairs, and
 * on a first run sets up, from the start.
 */
internal suspend fun repairFilesOffMain(
    io: CoroutineDispatcher,
    files: () -> Unit,
    after: () -> Unit,
    alive: () -> Boolean,
) {
    withContext(io) { oneLaunchRepairAtATime(files) }
    if (alive()) after()
}

private val launchRepairs = Any()

/**
 * Runs [repairs] while no other launch in this process runs its own. On the
 * main thread two launches' repairs could never overlap; they run off it now,
 * and a second Splash meanwhile (a re-creation, or MainActivity handing
 * a launch back) would run the same `.bashrc` appenders at once, where one's
 * read and the other's later write drop a block.
 */
internal fun oneLaunchRepairAtATime(repairs: () -> Unit) = synchronized(launchRepairs) { repairs() }

/**
 * The holds this process's Splash instances have on NodeService for setup.
 * The service keeps a single hold that any release ends, so a release is passed
 * on only by the last holder. Main thread only, as every caller is.
 */
internal object SetupHolds {
    private var holders = 0

    fun taken() {
        holders++
    }

    /** Whether that was the last hold, so that the service's should go. */
    fun givenBack(): Boolean {
        holders = (holders - 1).coerceAtLeast(0)
        return holders == 0
    }
}
