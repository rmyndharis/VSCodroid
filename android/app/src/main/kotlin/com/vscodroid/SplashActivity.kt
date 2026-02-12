package com.vscodroid

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.play.core.assetpacks.model.AssetPackStatus
import com.vscodroid.setup.FirstRunSetup
import com.vscodroid.setup.ToolchainManager
import com.vscodroid.setup.ToolchainPickerAdapter
import com.vscodroid.setup.ToolchainRegistry
import com.vscodroid.util.Logger
import kotlinx.coroutines.launch

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
        super.onCreate(savedInstanceState)

        val setup = FirstRunSetup(this)

        // Always validate tool symlinks — Android changes nativeLibraryDir
        // path on every reinstall, which breaks absolute symlinks in usr/bin/.
        setup.setupToolSymlinks()
        setup.setupRipgrepVscodeSymlink()
        setup.createNpmWrappers()
        setup.ensureToolchainEnvSourcing()
        setup.updateSettingsNativeLibPaths()

        if (!setup.isFirstRun()) {
            Logger.i(tag, "Not first run, launching main activity")
            launchMain()
            return
        }

        setContentView(R.layout.activity_splash)
        val statusText = findViewById<TextView>(R.id.statusText)
        val progressBar = findViewById<ProgressBar>(R.id.progressBar)

        setup.onProgress = { message, percent ->
            runOnUiThread {
                statusText.text = message
                progressBar.progress = percent
            }
        }

        lifecycleScope.launch {
            val success = setup.runSetup()
            if (success) {
                if (shouldShowPicker()) {
                    showToolchainPicker()
                } else {
                    launchMain()
                }
            } else {
                statusText.text = getString(R.string.error_storage_full)
            }
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
                // Start next download
                downloadNext()
            }
            AssetPackStatus.FAILED -> {
                row.statusText.text = getString(R.string.progress_failed)
                row.statusText.setTextColor(getColor(R.color.colorError))
                // Skip failed and continue with next
                downloadNext()
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
        }
    }

    // -- Navigation --

    private fun launchMain() {
        startActivity(Intent(this, MainActivity::class.java).apply {
            data = intent?.data
            intent?.extras?.let { putExtras(it) }
        })
        finish()
    }
}
