package com.vscodroid

import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.play.core.assetpacks.model.AssetPackStatus
import com.vscodroid.setup.ToolchainManager
import com.vscodroid.util.drawBehindSystemBars
import com.vscodroid.util.padForSystemBars
import com.vscodroid.setup.ToolchainAction
import com.vscodroid.setup.ToolchainCardMode
import com.vscodroid.setup.ToolchainPickerAdapter
import com.vscodroid.setup.ToolchainRegistry
import com.vscodroid.util.Logger
import android.widget.Toast

/**
 * Settings screen for managing on-demand toolchains.
 *
 * Reached from the launcher shortcut published by `publishToolchainShortcut` in
 * [SplashActivity], which is the only route a user has. This activity is not
 * exported and has no launcher entry of its own.
 *
 * [com.vscodroid.bridge.AndroidBridge.openToolchainSettings] can also start it,
 * and nothing calls that: it is dispatched by the `openToolchainSettings` command
 * on the BroadcastChannel relay, which no bundled extension sends. Naming it here
 * as the way in is what this comment used to do, and it sent readers looking for
 * a caller that does not exist.
 */
class ToolchainActivity : AppCompatActivity() {
    private val tag = "ToolchainActivity"

    private lateinit var toolchainManager: ToolchainManager
    private lateinit var adapter: ToolchainPickerAdapter

    /** Held as a field because the poll below posts on it and takes it back. */
    private lateinit var grid: RecyclerView

    /**
     * What [ToolchainManager.packsDownloading] said the last time this screen
     * asked, so the poll can push only when the answer has changed. Pushing an
     * unchanged snapshot costs a full rebind of every card once a second, and a
     * rebind is what takes accessibility focus off a button someone is on.
     */
    private var lastSeenDownloads: Map<String, Int> = emptyMap()

    /**
     * Re-asks the process what is downloading, because nothing tells this screen.
     *
     * Progress reaches the manager that began the transfer and nothing else, so a
     * download some other manager started is visible here only through the
     * process-wide snapshot. That snapshot was read once, at onStart, and the
     * thing it describes keeps moving: the bar sat at whichever percentage it
     * happened to read and the card went on offering Cancel after the install had
     * finished. A screen with no subscription can only ask again, and it asks only
     * while it is in front: the callback is taken back in onStop.
     */
    private val pollDownloads = object : Runnable {
        override fun run() {
            refreshDownloads()
            grid.postDelayed(this, DOWNLOAD_POLL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Before super.onCreate(), as the call it replaces required.
        drawBehindSystemBars()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_toolchain)
        findViewById<android.view.View>(R.id.toolchainRoot).padForSystemBars()

        toolchainManager = ToolchainManager(this)

        // Toolbar back button
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        // Set up grid with MANAGER mode adapter
        adapter = ToolchainPickerAdapter(ToolchainCardMode.MANAGER)
        adapter.setInstalled(toolchainManager.getInstalledToolchains())

        adapter.onAction = { packName, action ->
            when (action) {
                ToolchainAction.INSTALL,
                ToolchainAction.RETRY -> {
                    toolchainManager.install(packName)
                }
                ToolchainAction.REMOVE -> {
                    showRemoveConfirmation(packName)
                }
                ToolchainAction.CANCEL -> {
                    toolchainManager.cancel(packName)
                    // CANCELED, and specifically not the neighbouring
                    // NOT_INSTALLED, which is the same trap ToolchainManager
                    // documents where it had to pick a status for something that
                    // is not an uninstall. Cancelling stops a transfer and hands
                    // Play's delivery back; it deletes nothing from `usr/` and
                    // takes nothing out of `toolchains.json`. NOT_INSTALLED is
                    // this app's word for a completed uninstall, written at the
                    // end of uninstallLocked, and the card state reads it as
                    // exactly that and drops the pack from its installed set, so
                    // cancelling a re-download of an installed toolchain hid its
                    // Remove button until the screen was reopened. CANCELED
                    // carries no such meaning, so the card falls back to what the
                    // install record says.
                    adapter.updateState(packName, AssetPackStatus.CANCELED, 0)
                }
            }
        }

        grid = findViewById(R.id.toolchainGrid)
        grid.layoutManager = GridLayoutManager(this, 2)
        grid.adapter = adapter

        // Listen for download state changes
        toolchainManager.onStateChange = { packName, status, percent, why ->
            runOnUiThread {
                adapter.updateState(packName, status, percent)
                // Same reasoning as the first-run screen: the card carries a
                // status, not an explanation, and the explanation is the part
                // that tells the user whether to free space or move to wifi.
                if (why != null) {
                    Toast.makeText(this, getString(why.message), Toast.LENGTH_LONG).show()
                }
                // A decline reports UNKNOWN, and without this the screen answers a
                // tap with nothing at all. The card cannot say it either: the pack
                // is not installed yet and no progress belongs to this manager, so
                // it draws the same Install it drew before, and the user taps again
                // and gets the same silence. The install is genuinely happening,
                // just not by this caller, so the sentence says so rather than
                // reporting a failure.
                if (status == AssetPackStatus.UNKNOWN && why == null) {
                    Toast.makeText(
                        this, getString(R.string.toolchain_already_installing), Toast.LENGTH_SHORT
                    ).show()
                }
                if (status == AssetPackStatus.REQUIRES_USER_CONFIRMATION) {
                    try {
                        toolchainManager.showConfirmationDialog(this)
                    } catch (e: Exception) {
                        Logger.e(tag, "Failed to show confirmation dialog", e)
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        toolchainManager.registerListener()
        // Refresh installed state on resume (user may have installed from terminal)
        adapter.setInstalled(toolchainManager.getInstalledToolchains())
        // And what is downloading, which this screen is not told about at all when
        // another manager began the transfer: the cards started empty and offered
        // Install for a pack already downloading, with no progress and no Cancel.
        // Asked again on a timer from here on, because one reading goes stale while
        // the screen is still looking at it. Play's own downloads are not in this
        // map and do not need to be: registerListener above makes Play re-deliver
        // their state.
        refreshDownloads()
        grid.postDelayed(pollDownloads, DOWNLOAD_POLL_MS)
    }

    override fun onStop() {
        // Before the listener, so nothing is posted onto a screen that is going
        // away. The callback is the only thing keeping this activity referenced
        // from the view's message queue.
        grid.removeCallbacks(pollDownloads)
        toolchainManager.unregisterListener()
        super.onStop()
    }

    /**
     * Pushes a fresh view of the process's downloads into the cards, if it has
     * changed since the last one.
     */
    private fun refreshDownloads() {
        val downloading = ToolchainManager.packsDownloading()
        val refresh = downloadRefreshFor(lastSeenDownloads, downloading)
        if (!refresh.push) return
        lastSeenDownloads = downloading
        adapter.setDownloading(downloading)
        // Only when a pack has LEFT the snapshot, which is the moment its outcome
        // becomes readable and the moment this screen would otherwise draw Install
        // for something that has just finished installing. Reading the record on
        // every percentage tick instead would be a file read a second for an
        // answer that cannot have changed.
        if (refresh.rereadInstalled) adapter.setInstalled(toolchainManager.getInstalledToolchains())
    }

    private fun showRemoveConfirmation(packName: String) {
        val info = ToolchainRegistry.find(packName) ?: return
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.toolchain_remove_confirm_title, info.displayName))
            .setMessage(getString(R.string.toolchain_remove_confirm_message, info.displayName))
            .setPositiveButton(getString(R.string.toolchain_remove)) { _, _ ->
                val shortName = packName.removePrefix("toolchain_")
                toolchainManager.uninstall(shortName)
                Logger.i(tag, "User confirmed removal of $shortName")
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}

/**
 * How often the Toolchains screen re-asks the process what is downloading.
 *
 * A second is well under the pace a progress bar has to keep and well over the
 * cost of the question, which is one snapshot of a map that holds an entry per
 * transfer in flight, usually none. It runs only while this screen is in front.
 */
private const val DOWNLOAD_POLL_MS = 1_000L

/** What one reading of the download snapshot asks the screen to do. */
internal data class DownloadRefresh(val push: Boolean, val rereadInstalled: Boolean)

/**
 * The decision behind `ToolchainActivity.refreshDownloads`, at file scope so it
 * can be tested without an Activity; this project's unit tests have no
 * Robolectric, the same reason [isTerminalPackStatus] and [isCurrentDownload]
 * live beside SplashActivity.
 *
 * Two separate questions, and folding them into one would lose the cheaper half:
 * anything at all changing is worth a repaint, while only a pack leaving the map
 * is worth re-reading the install record from disk.
 */
internal fun downloadRefreshFor(
    previous: Map<String, Int>,
    current: Map<String, Int>,
): DownloadRefresh = DownloadRefresh(
    push = current != previous,
    rereadInstalled = (previous.keys - current.keys).isNotEmpty(),
)
