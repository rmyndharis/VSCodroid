package com.vscodroid

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.vscodroid.setup.FirstRunSetup
import com.vscodroid.util.Logger
import kotlinx.coroutines.launch

@SuppressLint("CustomSplashScreen")
class SplashActivity : AppCompatActivity() {
    private val tag = "SplashActivity"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val setup = FirstRunSetup(this)

        // Always validate tool symlinks — Android changes nativeLibraryDir
        // path on every reinstall, which breaks absolute symlinks in usr/bin/.
        setup.setupToolSymlinks()
        setup.setupRipgrepVscodeSymlink()
        setup.createNpmWrappers()
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
                launchMain()
            } else {
                statusText.text = getString(R.string.error_storage_full)
            }
        }
    }

    private fun launchMain() {
        startActivity(Intent(this, MainActivity::class.java).apply {
            data = intent?.data
            intent?.extras?.let { putExtras(it) }
        })
        finish()
    }
}
