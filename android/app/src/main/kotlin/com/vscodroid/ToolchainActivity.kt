package com.vscodroid

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.play.core.assetpacks.model.AssetPackStatus
import com.vscodroid.setup.ToolchainManager
import com.vscodroid.setup.ToolchainPickerAdapter
import com.vscodroid.setup.ToolchainRegistry
import com.vscodroid.util.Logger

/**
 * Settings screen for managing on-demand toolchains.
 * Launched from AndroidBridge.openToolchainSettings() via VS Code.
 */
class ToolchainActivity : AppCompatActivity() {
    private val tag = "ToolchainActivity"

    private lateinit var toolchainManager: ToolchainManager
    private lateinit var adapter: ToolchainPickerAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_toolchain)

        toolchainManager = ToolchainManager(this)

        // Toolbar back button
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        // Set up grid with MANAGER mode adapter
        adapter = ToolchainPickerAdapter(ToolchainPickerAdapter.Mode.MANAGER)
        adapter.setInstalled(toolchainManager.getInstalledToolchains())

        adapter.onAction = { packName, action ->
            when (action) {
                ToolchainPickerAdapter.Action.INSTALL,
                ToolchainPickerAdapter.Action.RETRY -> {
                    toolchainManager.install(packName)
                }
                ToolchainPickerAdapter.Action.REMOVE -> {
                    showRemoveConfirmation(packName)
                }
                ToolchainPickerAdapter.Action.CANCEL -> {
                    toolchainManager.cancel(packName)
                    adapter.updateState(packName, AssetPackStatus.NOT_INSTALLED, 0)
                }
            }
        }

        val grid = findViewById<RecyclerView>(R.id.toolchainGrid)
        grid.layoutManager = GridLayoutManager(this, 2)
        grid.adapter = adapter

        // Listen for download state changes
        toolchainManager.onStateChange = { packName, status, percent ->
            runOnUiThread {
                adapter.updateState(packName, status, percent)
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
