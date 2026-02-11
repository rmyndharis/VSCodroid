package com.vscodroid.setup

import android.content.Context
import android.system.Os
import com.vscodroid.util.Environment
import com.vscodroid.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class FirstRunSetup(private val context: Context) {
    private val tag = "FirstRunSetup"
    private val prefs = context.getSharedPreferences("vscodroid_setup", Context.MODE_PRIVATE)

    var onProgress: ((message: String, percent: Int) -> Unit)? = null

    fun isFirstRun(): Boolean {
        val installedVersion = prefs.getString(KEY_VERSION, null)
        val currentVersion = getCurrentVersion()
        return installedVersion != currentVersion
    }

    suspend fun runSetup(): Boolean = withContext(Dispatchers.IO) {
        Logger.i(tag, "Starting first-run setup")
        val startTime = System.currentTimeMillis()

        try {
            reportProgress("Creating directories...", 5)
            createDirectories()

            reportProgress("Extracting server files...", 15)
            extractAssetDir("vscode-reh", "server/vscode-reh")

            reportProgress("Extracting web client...", 40)
            extractAssetDir("vscode-web", "server/vscode-web")

            reportProgress("Extracting server bootstrap...", 58)
            extractAssetFile("server.js", "server/server.js")

            reportProgress("Extracting tools...", 62)
            extractAssetDir("usr", "usr")

            reportProgress("Setting up git...", 66)
            setupGitCore()

            reportProgress("Setting up tools...", 68)
            setupToolSymlinks()
            setupRipgrepVscodeSymlink()
            createStorageSymlinks()
            createWelcomeProject()
            createBashrc()

            reportProgress("Setting up extensions...", 70)
            extractBundledExtensions()

            reportProgress("Configuring environment...", 90)
            createDefaultSettings()

            reportProgress("Done!", 100)
            markSetupComplete()

            val elapsed = System.currentTimeMillis() - startTime
            Logger.i(tag, "First-run setup completed in ${elapsed}ms")
            true
        } catch (e: Exception) {
            Logger.e(tag, "First-run setup failed", e)
            false
        }
    }

    private fun createDirectories() {
        val dirs = listOf(
            "home",
            "home/.vscodroid",
            "home/.vscodroid/extensions",
            "home/.vscodroid/data/logs",
            "home/.vscodroid/logs",
            "server",
            "usr/bin",
            "usr/lib",
            "usr/lib/git-core",
            "usr/lib/python3.12",
            "usr/share/terminfo",
        )
        for (dir in dirs) {
            val file = File(context.filesDir, dir)
            if (!file.exists()) {
                file.mkdirs()
            }
        }
        val tmpDir = File(context.cacheDir, "tmp")
        if (!tmpDir.exists()) tmpDir.mkdirs()

        // App-external projects directory (visible in file managers)
        val projectsDir = File(Environment.getProjectsDir(context))
        if (!projectsDir.exists()) projectsDir.mkdirs()
    }

    private fun extractAssetDir(assetPath: String, destPath: String) {
        val destDir = File(context.filesDir, destPath)
        try {
            val assets = context.assets.list(assetPath) ?: return
            if (assets.isEmpty()) {
                extractAssetFile(assetPath, destPath)
                return
            }
            destDir.mkdirs()
            for (asset in assets) {
                extractAssetDir("$assetPath/$asset", "$destPath/$asset")
            }
        } catch (e: IOException) {
            Logger.d(tag, "Treating $assetPath as file (not directory)")
            extractAssetFile(assetPath, destPath)
        }
    }

    private fun extractAssetFile(assetPath: String, destPath: String) {
        val destFile = File(context.filesDir, destPath)
        destFile.parentFile?.mkdirs()
        try {
            context.assets.open(assetPath).use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }
        } catch (e: IOException) {
            Logger.d(tag, "Asset not found: $assetPath (will be available after build)")
        }
    }

    private fun setupGitCore() {
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        val gitCorePath = File(context.filesDir, "usr/lib/git-core")
        val manifestFile = File(gitCorePath, "gitcore-symlinks")

        if (!manifestFile.exists()) {
            Logger.d(tag, "No gitcore-symlinks manifest found, skipping git-core setup")
            return
        }

        val gitBinary = "$nativeLibDir/libgit.so"
        var symlinksCreated = 0

        // Create symlinks for git subcommands that point to the main git binary
        manifestFile.readLines().filter { it.isNotBlank() }.forEach { name ->
            val linkPath = File(gitCorePath, name)
            if (!linkPath.exists()) {
                try {
                    Os.symlink(gitBinary, linkPath.absolutePath)
                    symlinksCreated++
                } catch (e: Exception) {
                    Logger.d(tag, "Failed to create symlink for $name: ${e.message}")
                }
            }
        }

        // Set execute permission on all files in git-core
        gitCorePath.listFiles()?.forEach { file ->
            if (file.isFile && !file.name.startsWith(".")) {
                file.setExecutable(true, false)
            }
        }

        Logger.i(tag, "git-core setup: $symlinksCreated symlinks created")
    }

    /**
     * Creates or updates symlinks in usr/bin/ pointing to native binaries.
     *
     * Android changes the nativeLibraryDir path on every reinstall (random hash),
     * so existing symlinks may point to a stale path. This method validates and
     * recreates them as needed — safe to call on every launch, not just first run.
     */
    fun setupToolSymlinks() {
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        val binDir = File(context.filesDir, "usr/bin")
        binDir.mkdirs()

        val tools = mapOf(
            "bash" to "libbash.so",
            "git" to "libgit.so",
            "node" to "libnode.so",
            "python3" to "libpython.so",
            "python" to "libpython.so",
            "rg" to "libripgrep.so",
            "tmux" to "libtmux.so",
            "make" to "libmake.so",
        )

        var created = 0
        var updated = 0
        for ((name, soName) in tools) {
            var linkUpdated = false
            val link = File(binDir, name)
            val target = "$nativeLibDir/$soName"
            if (!File(target).exists()) continue

            // Check if a symlink already exists (lstat doesn't follow symlinks,
            // unlike File.exists() which returns false for dangling symlinks)
            val linkExists = try { Os.lstat(link.absolutePath); true } catch (e: Exception) { false }

            if (linkExists) {
                try {
                    val currentTarget = Os.readlink(link.absolutePath)
                    if (currentTarget == target) continue
                } catch (_: Exception) { }
                // Stale or broken symlink — remove it
                link.delete()
                updated++
                linkUpdated = true
            }

            try {
                Os.symlink(target, link.absolutePath)
                if (!linkUpdated) created++
            } catch (e: Exception) {
                Logger.d(tag, "Failed to create symlink $name -> $soName: ${e.message}")
            }
        }
        Logger.i(tag, "Tool symlinks: $created created, $updated updated in usr/bin/")
    }

    /**
     * Creates a symlink so VS Code's @vscode/ripgrep finds rg at its expected path.
     * The rg binary lives in nativeLibraryDir as libripgrep.so, but VS Code looks for
     * node_modules/@vscode/ripgrep/bin/rg inside the server directory.
     * Safe to call on every launch (recreates if stale, skips if current).
     */
    fun setupRipgrepVscodeSymlink() {
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        val rgBinary = File("$nativeLibDir/libripgrep.so")
        if (!rgBinary.exists()) return

        val rgBinDir = File(context.filesDir, "server/vscode-reh/node_modules/@vscode/ripgrep/bin")
        rgBinDir.mkdirs()
        val rgLink = File(rgBinDir, "rg")
        val target = rgBinary.absolutePath

        val linkExists = try { Os.lstat(rgLink.absolutePath); true } catch (e: Exception) { false }
        if (linkExists) {
            try {
                if (Os.readlink(rgLink.absolutePath) == target) return
            } catch (_: Exception) { }
            rgLink.delete()
        }

        try {
            Os.symlink(target, rgLink.absolutePath)
            Logger.i(tag, "ripgrep symlink: ${rgLink.absolutePath} -> $target")
        } catch (e: Exception) {
            Logger.d(tag, "Failed to create ripgrep symlink: ${e.message}")
        }
    }

    private fun createWelcomeProject() {
        val projectsDir = File(Environment.getProjectsDir(context))
        val welcomeFile = File(projectsDir, "README.md")
        if (!welcomeFile.exists()) {
            welcomeFile.writeText("""
                # Welcome to VSCodroid

                This is your projects directory. Create folders here to start coding.

                Your projects are stored at:
                `Android/data/${context.packageName}/files/projects/`

                You can access this folder from any file manager on your device.
            """.trimIndent() + "\n")
        }
    }

    private fun createStorageSymlinks() {
        val homeDir = File(context.filesDir, "home")
        val projectsDir = Environment.getProjectsDir(context)

        // ~/projects -> app-external projects dir (convenience symlink)
        val link = File(homeDir, "projects")
        if (!link.exists() && File(projectsDir).exists()) {
            try {
                Os.symlink(projectsDir, link.absolutePath)
            } catch (e: Exception) {
                Logger.d(tag, "Failed to create projects symlink: ${e.message}")
            }
        }
    }

    private fun createBashrc() {
        val projectsDir = Environment.getProjectsDir(context)
        val bashrc = File(context.filesDir, "home/.bashrc")
        if (!bashrc.exists()) {
            bashrc.writeText("""
                # VSCodroid bash configuration
                # Prompt via PROMPT_COMMAND (readline can't show PS1 on pipe stdin)
                __vscodroid_prompt() {
                    local dir="${'$'}PWD"
                    dir="${'$'}{dir/#${'$'}HOME/~}"
                    [[ "${'$'}dir" == /* ]] && dir="${'$'}{dir/#${'$'}PROJECTS_DIR/projects}"
                    printf '\033[32m%s\033[0m ${'$'} ' "${'$'}dir"
                }
                PROMPT_COMMAND=__vscodroid_prompt
                PS1=''

                export PROJECTS_DIR='$projectsDir'
                alias ls='ls --color=auto'
                alias ll='ls -la'

                # Start in projects directory
                cd "${'$'}PROJECTS_DIR" 2>/dev/null || true
            """.trimIndent() + "\n")
        }
    }

    private fun createDefaultSettings() {
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        val settingsDir = File(context.filesDir, "home/.vscodroid/User")
        settingsDir.mkdirs()
        val settingsFile = File(settingsDir, "settings.json")
        if (!settingsFile.exists()) {
            settingsFile.writeText("""
                {
                    "workbench.colorTheme": "Default Dark Modern",
                    "editor.fontSize": 14,
                    "editor.wordWrap": "on",
                    "editor.minimap.enabled": false,
                    "terminal.integrated.fontSize": 13,
                    "terminal.integrated.defaultProfile.linux": "bash",
                    "terminal.integrated.profiles.linux": {
                        "bash": {
                            "path": "$nativeLibDir/libbash.so",
                            "args": ["--noediting", "-i"],
                            "icon": "terminal-bash"
                        }
                    },
                    "git.path": "$nativeLibDir/libgit.so",
                    "terminal.integrated.shellIntegration.enabled": false,
                    "telemetry.telemetryLevel": "off",
                    "security.workspace.trust.enabled": false
                }
            """.trimIndent())
        }
    }

    private fun extractBundledExtensions() {
        val extensionsDir = File(context.filesDir, "home/.vscodroid/extensions")
        extensionsDir.mkdirs()

        val bundled = try {
            context.assets.list("extensions") ?: emptyArray()
        } catch (e: IOException) {
            Logger.d(tag, "No bundled extensions in assets")
            emptyArray()
        }

        if (bundled.isEmpty()) {
            Logger.d(tag, "No bundled extensions found")
            return
        }

        var extracted = 0
        for (name in bundled) {
            val dest = File(extensionsDir, name)
            if (!dest.exists()) {
                extractAssetDir("extensions/$name", "home/.vscodroid/extensions/$name")
                extracted++
            }
        }

        // Generate extensions.json only if it doesn't exist (first run).
        // VS Code Server manages this file for marketplace-installed extensions,
        // so we only write it once for bundled extensions.
        val manifestFile = File(extensionsDir, "extensions.json")
        if (!manifestFile.exists()) {
            generateExtensionsManifest(extensionsDir, bundled)
        }

        Logger.i(tag, "Bundled extensions: $extracted extracted, ${bundled.size} total")
    }

    private fun generateExtensionsManifest(extensionsDir: File, bundledDirs: Array<String>) {
        val entries = JSONArray()

        for (dirName in bundledDirs) {
            val extDir = File(extensionsDir, dirName)
            val pkgFile = File(extDir, "package.json")
            if (!pkgFile.exists()) {
                Logger.d(tag, "No package.json in $dirName, skipping manifest entry")
                continue
            }

            try {
                val pkg = JSONObject(pkgFile.readText())
                val publisher = pkg.optString("publisher", "")
                val name = pkg.optString("name", "")
                val version = pkg.optString("version", "")

                if (publisher.isEmpty() || name.isEmpty()) continue

                val id = "${publisher.lowercase()}.${name.lowercase()}"

                val entry = JSONObject().apply {
                    put("identifier", JSONObject().put("id", id))
                    put("version", version)
                    put("location", JSONObject().apply {
                        put("\$mid", 1)
                        put("path", extDir.absolutePath)
                        put("scheme", "file")
                    })
                    put("relativeLocation", dirName)
                    put("metadata", JSONObject().apply {
                        put("installedTimestamp", System.currentTimeMillis())
                        put("source", "bundled")
                    })
                }
                entries.put(entry)
            } catch (e: Exception) {
                Logger.d(tag, "Failed to parse $dirName/package.json: ${e.message}")
            }
        }

        val manifestFile = File(extensionsDir, "extensions.json")
        manifestFile.writeText(entries.toString(2))
        Logger.i(tag, "Generated extensions.json with ${entries.length()} entries")
    }

    private fun markSetupComplete() {
        prefs.edit().putString(KEY_VERSION, getCurrentVersion()).apply()
    }

    private fun getCurrentVersion(): String {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0"
        } catch (e: Exception) {
            "0"
        }
    }

    private fun reportProgress(message: String, percent: Int) {
        Logger.d(tag, "Progress: $percent% - $message")
        onProgress?.invoke(message, percent)
    }

    companion object {
        private const val KEY_VERSION = "setup_version"
    }
}
