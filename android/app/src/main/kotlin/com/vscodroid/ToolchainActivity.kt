package com.vscodroid

import android.graphics.Color
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.play.core.assetpacks.model.AssetPackStatus
import com.vscodroid.setup.ToolchainManager
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

    override fun onCreate(savedInstanceState: Bundle?) {
        // dark(): the app is always dark; auto would follow the system theme (see MainActivity).
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
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
                    adapter.updateState(packName, AssetPackStatus.NOT_INSTALLED, 0)
                }
            }
        }

        val grid = findViewById<RecyclerView>(R.id.toolchainGrid)
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
    }

    override fun onStop() {
        toolchainManager.unregisterListener()
        super.onStop()
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
