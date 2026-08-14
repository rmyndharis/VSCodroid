package com.vscodroid

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.play.core.assetpacks.model.AssetPackStatus
import com.vscodroid.setup.FirstRunSetup
import com.vscodroid.setup.ToolchainManager
import com.vscodroid.setup.ToolchainPickerAdapter
import com.vscodroid.setup.ToolchainRegistry
import com.vscodroid.storage.SafStorageManager
import com.vscodroid.util.Logger
import com.vscodroid.util.padForSystemBars
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@SuppressLint("CustomSplashScreen")
class SplashActivity : AppCompatActivity() {
    private val tag = "SplashActivity"

    private var toolchainManager: ToolchainManager? = null
    private var downloadQueue = mutableListOf<String>()
    private var currentDownloadIndex = -1
    private var cancelled = false

    // Progress UI refs (only valid after setContentView to progress layout)
    private val progressRows = mutableMapOf<String, ProgressRow>()

    private data class ProgressRow(
        val nameText: TextView,
        val progressBar: ProgressBar,
        val statusText: TextView,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        // dark(): the app is always dark; auto would follow the system theme (see MainActivity).
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)

        val setup = FirstRunSetup(this)

        // Always validate tool symlinks — Android changes nativeLibraryDir
        // path on every reinstall, which breaks absolute symlinks in usr/bin/.
        //
        // These touch the filesystem on the main thread at launch. Letting one
        // throw would crash the app before it ever draws, leaving a launch loop
        // with no explanation. What survives a failure here is degraded rather
        // than dead — tools missing from PATH, a stale terminal profile — and
        // logcat is the only trace, so keep the message specific.
        try {
            setup.setupToolSymlinks()
            setup.setupGitCore()
            setup.setupGitCaBundle()
            setup.setupRipgrepVscodeSymlink()
            setup.setupCopilotAndroidAliases()
            setup.createNpmWrappers()
            setup.ensureToolchainEnvSourcing()
            setup.ensurePromptFix()
            setup.updateSettingsNativeLibPaths()
            // Beside the other idempotent repairs, for the same reason they are
            // here: it can disappear between launches. This one because it is
            // visible in a file manager, not because the app moved it.
            setup.ensureProjectsDir()
            // A toolchain keeps the manifest it was installed with, so a
            // packaging fix never reaches an install that already exists. This
            // gives those binaries back the execute bit they should have had.
            // It returns immediately — the walk itself runs on the toolchain
            // I/O thread, because the trees involved have thousands of files
            // and this block is on the main thread.
            ToolchainManager(this).repairInstalledToolchains()
            // A folder whose permission the user withdrew in system settings never
            // comes back through the app, so its mirror is disk nothing can reach.
            // Here because it is the one point that is guaranteed to have no folder
            // open: this activity always precedes MainActivity, and nothing can tell
            // a mirror the editor is holding from one it is not. It returns
            // immediately, for the reason the line above does.
            SafStorageManager(this).reclaimRevokedMirrors()
        } catch (e: Exception) {
            Logger.e(tag, "Launch-time setup refresh failed", e)
        }

        if (!setup.isFirstRun()) {
            // The interpreter ships in the APK and every install replaces it,
            // while its runtime and stdlib are extracted only when versionName
            // changes. Reinstalling a rebuilt APK is exactly that gap, and it is
            // the one case where "not first run" still has work to do. The check
            // is two directory listings; the work it gates is 23 MB, so it runs
            // off the main thread and holds this activity open while it does —
            // lifecycleScope is cancelled the moment we finish for MainActivity.
            if (setup.pythonRuntimeNeedsWork()) {
                Logger.i(tag, "Bundled Python changed since the last extraction; reconciling")
                setContentView(R.layout.activity_splash)
                findViewById<TextView>(R.id.statusText).text = getString(R.string.status_updating_python)
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { setup.reconcilePythonRuntime() }
                    launchMain()
                }
                return
            }
            Logger.i(tag, "Not first run, launching main activity")
            launchMain()
            return
        }

        setContentView(R.layout.activity_splash)
        val statusText = findViewById<TextView>(R.id.statusText)
        val progressBar = findViewById<ProgressBar>(R.id.progressBar)

        if (setup.getPreviousVersionCode() > 0) {
            statusText.text = "Updating VSCodroid..."
        }

        setup.onProgress = { message, percent ->
            runOnUiThread {
                statusText.text = message
                progressBar.progress = percent
            }
        }

        runSetupWithRetry(setup, statusText, progressBar)
    }

    private fun runSetupWithRetry(
        setup: FirstRunSetup,
        statusText: TextView,
        progressBar: ProgressBar
    ) {
        lifecycleScope.launch {
            val result = setup.runSetup()
            when (result) {
                FirstRunSetup.SetupResult.SUCCESS -> {
                    if (shouldShowPicker()) showToolchainPicker() else launchMain()
                }
                FirstRunSetup.SetupResult.LOW_STORAGE -> {
                    showSetupError(statusText, progressBar, getString(R.string.error_storage_full), setup)
                }
                FirstRunSetup.SetupResult.ERROR -> {
                    showSetupError(statusText, progressBar, getString(R.string.error_setup_failed), setup)
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
            // Show retry button dynamically
            val parent = statusText.parent as? android.view.ViewGroup ?: return@runOnUiThread
            val retryButton = Button(this).apply {
                text = getString(R.string.progress_retry)
                setOnClickListener {
                    // Reset UI and retry
                    statusText.text = getString(R.string.extracting_message)
                    progressBar.visibility = View.VISIBLE
                    progressBar.progress = 0
                    parent.removeView(this)
                    runSetupWithRetry(setup, statusText, progressBar)
                }
            }
            parent.addView(retryButton)
        }
    }

    override fun onDestroy() {
        toolchainManager?.unregisterListener()
        super.onDestroy()
    }

    // -- Picker phase --

    private fun shouldShowPicker(): Boolean {
        val prefs = getSharedPreferences("vscodroid", MODE_PRIVATE)
        return !prefs.getBoolean("toolchain_picker_shown", false)
    }

    private fun markPickerShown() {
        getSharedPreferences("vscodroid", MODE_PRIVATE)
            .edit()
            .putBoolean("toolchain_picker_shown", true)
            .apply()
    }

    private fun showToolchainPicker() {
        setContentView(R.layout.layout_toolchain_picker)
        findViewById<View>(R.id.pickerRoot)
            .padForSystemBars(basePx = (24 * resources.displayMetrics.density).toInt())

        val grid = findViewById<RecyclerView>(R.id.toolchainGrid)
        val continueBtn = findViewById<Button>(R.id.continueButton)
        val skipBtn = findViewById<TextView>(R.id.skipButton)

        val adapter = ToolchainPickerAdapter(ToolchainPickerAdapter.Mode.PICKER)
        grid.layoutManager = GridLayoutManager(this, 2)
        grid.adapter = adapter

        continueBtn.setOnClickListener {
            val selected = adapter.getSelectedPackNames()
            markPickerShown()
            if (selected.isEmpty()) {
                launchMain()
            } else {
                startDownloads(selected.toList())
            }
        }

        skipBtn.setOnClickListener {
            markPickerShown()
            launchMain()
        }
    }

    // -- Download progress phase --

    private fun startDownloads(packNames: List<String>) {
        setContentView(R.layout.layout_toolchain_progress)
        findViewById<View>(R.id.progressRoot)
            .padForSystemBars(basePx = (24 * resources.displayMetrics.density).toInt())

        val container = findViewById<LinearLayout>(R.id.progressContainer)
        val cancelBtn = findViewById<Button>(R.id.cancelButton)

        downloadQueue = packNames.toMutableList()
        currentDownloadIndex = -1
        cancelled = false
        progressRows.clear()

        // Build progress rows for each toolchain
        for (packName in packNames) {
            val info = ToolchainRegistry.find(packName) ?: continue
            val row = buildProgressRow(info)
            container.addView(row.first)
            progressRows[packName] = row.second
        }

        // Set up toolchain manager
        val manager = ToolchainManager(this)
        toolchainManager = manager
        manager.onStateChange = { packName, status, percent ->
            runOnUiThread { handleDownloadState(packName, status, percent) }
        }
        manager.registerListener()

        cancelBtn.setOnClickListener {
            cancelled = true
            val currentPack = downloadQueue.getOrNull(currentDownloadIndex)
            if (currentPack != null) {
                manager.cancel(currentPack)
            }
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
            alpha = 0.6f
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
            launchMain()
            return
        }
        val packName = downloadQueue[currentDownloadIndex]
        progressRows[packName]?.statusText?.text = getString(R.string.progress_installing)
        toolchainManager?.install(packName)
    }

    private fun handleDownloadState(packName: String, status: Int, percent: Int) {
        if (cancelled) return

        // Only the download the queue is waiting for gets to speak.
        //
        // The listener is registered for the app rather than for one fetch
        // (ToolchainManager.kt:81), and every queued pack gets a row up front, so
        // a state naming some other pack reaches here and used to be acted on. It
        // could repaint a finished pack's row red, and worse, move the index --
        // stepping over whichever pack was genuinely downloading and leaving it
        // uninstalled with its row still reading "installing".
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

        when (status) {
            AssetPackStatus.DOWNLOADING, AssetPackStatus.TRANSFERRING -> {
                row.progressBar.progress = percent
                row.statusText.text = "$percent%"
            }
            AssetPackStatus.COMPLETED -> {
                row.progressBar.progress = 100
                row.statusText.text = getString(R.string.progress_done)
                row.statusText.setTextColor(getColor(R.color.colorSuccess))
            }
            AssetPackStatus.PENDING, AssetPackStatus.WAITING_FOR_WIFI -> {
                row.statusText.text = getString(R.string.progress_waiting)
            }
            AssetPackStatus.REQUIRES_USER_CONFIRMATION -> {
                try {
                    toolchainManager?.showConfirmationDialog(this)
                } catch (e: Exception) {
                    Logger.e(tag, "Failed to show confirmation dialog", e)
                }
            }
            // Anything else ends this pack without installing it. FAILED is the
            // expected one; CANCELED arrives when the download is cancelled from
            // the Play notification rather than from this screen, and UNKNOWN and
            // NOT_INSTALLED arrive when Play has nothing to report for it.
            else -> {
                if (status != AssetPackStatus.FAILED) {
                    Logger.w(tag, "Pack $packName ended at status $status")
                }
                row.statusText.text = getString(R.string.progress_failed)
                row.statusText.setTextColor(getColor(R.color.colorError))
            }
        }

        // Deliberately outside the when, and that is the whole fix.
        //
        // Advancing used to be a call inside two of the branches, so a status
        // matching no branch advanced nothing: downloadNext() was never reached
        // and the screen sat on its progress list for the rest of the session,
        // with every toolchain behind the stalled one left uninstalled. Deciding
        // it here, from the status alone, means a branch cannot forget to do it
        // -- which is the shape the bug had, rather than the particular states
        // it happened to miss.
        //
        // The stall is not a dead end, and an earlier version of this comment
        // said it was: cancelButton is always visible and goes straight to
        // launchMain(), and a relaunch skips setup entirely because
        // markSetupComplete() has already run by the time this screen appears.
        if (isTerminalPackStatus(status)) {
            downloadNext()
        }
    }



    // -- Navigation --

    private fun launchMain() {
        startActivity(Intent(this, MainActivity::class.java).apply {
            data = intent?.data
            intent?.extras?.let { putExtras(it) }
        })
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
        finish()
    }

    /**
     * Publishes the launcher shortcut that opens [ToolchainActivity].
     *
     * The screen had no way in. It is not exported, it has no launcher entry,
     * and its only caller is the `openToolchainSettings` command on the
     * BroadcastChannel relay -- which no bundled extension sends. So the
     * picker's one appearance decided the toolchains permanently.
     *
     * That last sentence used to explain itself by saying every bundled
     * extension of ours ran on the Node host, where `BroadcastChannel` is the
     * one from `node:worker_threads` and shares nothing but a name with the DOM
     * channel the relay opens. That stopped being true: `saf-bridge` now
     * declares `browser` and reaches the relay, and contributes six commands
     * through it. `openToolchainSettings` is still not one of them, so the
     * conclusion holds and the reason for it does not -- which is why the reason
     * is written as history rather than as a standing fact.
     *
     * A launcher shortcut is deliberately the entry point that does not depend on
     * the WebView, because reaching this screen matters most when the editor
     * layer is the thing that is broken.
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
     *    before the setup it needs.
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
 * permanent -- the screen's only caller was a BroadcastChannel command nothing
 * can send (AndroidBridge.kt:229, MainActivity.kt:907) -- so this paragraph
 * used to read "and costs it for good".
 *
 * A pack wrongly waited on costs that toolchain and every one queued behind it,
 * because the queue stops rather than skips, and leaves the screen sitting there
 * until the user finds the Cancel button. Losing one is better than losing the
 * remainder, which is the only reason this defaults to advancing.
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
