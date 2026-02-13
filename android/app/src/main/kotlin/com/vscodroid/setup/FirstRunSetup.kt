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

    enum class SetupResult { SUCCESS, LOW_STORAGE, ERROR }

    fun isFirstRun(): Boolean {
        val installedVersion = prefs.getString(KEY_VERSION, null)
        val currentVersion = getCurrentVersion()
        return installedVersion != currentVersion
    }

    suspend fun runSetup(): SetupResult = withContext(Dispatchers.IO) {
        val previousVersionCode = getPreviousVersionCode()
        val currentVersionCode = getCurrentVersionCode()
        val isUpgrade = previousVersionCode > 0

        if (isUpgrade) {
            Logger.i(tag, "Upgrading from versionCode $previousVersionCode to $currentVersionCode (${getCurrentVersion()})")
        } else {
            Logger.i(tag, "Fresh install, version ${getCurrentVersion()} (versionCode $currentVersionCode)")
        }
        val startTime = System.currentTimeMillis()

        // Pre-flight: check available storage (~500MB needed for extraction)
        val available = context.filesDir.usableSpace
        val required = 500L * 1_048_576L
        if (available < required) {
            Logger.e(tag, "Insufficient storage: ${available / 1_048_576}MB available, ${required / 1_048_576}MB required")
            return@withContext SetupResult.LOW_STORAGE
        }

        try {
            reportProgress("Creating directories...", 2)
            createDirectories()

            reportProgress("Extracting server files...", 5)
            extractAssetDir("vscode-reh", "server/vscode-reh")

            reportProgress("Extracting web client...", 40)
            extractAssetDir("vscode-web", "server/vscode-web")

            reportProgress("Extracting server bootstrap...", 60)
            extractAssetFile("server.js", "server/server.js")
            extractAssetFile("process-monitor.js", "server/process-monitor.js")
            extractAssetFile("platform-fix.js", "server/platform-fix.js")

            reportProgress("Extracting tools...", 62)
            extractAssetDir("usr", "usr")

            reportProgress("Setting up git...", 82)
            setupGitCore()

            reportProgress("Setting up tools...", 85)
            setupToolSymlinks()
            setupRipgrepVscodeSymlink()
            setupSshDefaults()
            createBashrc()
            createBashProfile()
            createTmuxConf()
            createNpmWrappers()  // After createBashrc — appends npm functions to .bashrc
            createStorageSymlinks()
            createWelcomeProject()

            reportProgress("Setting up extensions...", 88)
            extractBundledExtensions()

            reportProgress("Configuring environment...", 97)
            createDefaultSettings()

            reportProgress("Done!", 100)

            if (isUpgrade) {
                runMigrations(previousVersionCode)
            }

            markSetupComplete()

            val elapsed = System.currentTimeMillis() - startTime
            Logger.i(tag, "First-run setup completed in ${elapsed}ms")
            SetupResult.SUCCESS
        } catch (e: Exception) {
            Logger.e(tag, "First-run setup failed", e)
            SetupResult.ERROR
        }
    }

    private fun createDirectories() {
        val dirs = listOf(
            "home",
            "home/.ssh",
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
                file.setExecutable(true, true)
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
            "ssh" to "libssh.so",
            "ssh-keygen" to "libssh-keygen.so",
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

    /**
     * Creates default SSH configuration for git operations.
     *
     * Sets up ~/.ssh/ directory, default ssh_config (auto-accept first connection,
     * ed25519 key, keepalive), and correct file permissions. Only runs on first setup
     * — does not overwrite existing user SSH config.
     */
    private fun setupSshDefaults() {
        val homeDir = context.filesDir.absolutePath + "/home"
        val sshDir = File(homeDir, ".ssh")
        sshDir.mkdirs()

        // Set directory permissions to 700 (owner only)
        try {
            Os.chmod(sshDir.absolutePath, 448) // 0700 octal = 448 decimal
        } catch (e: Exception) {
            Logger.d(tag, "Failed to chmod .ssh: ${e.message}")
        }

        // Create default ssh_config if it doesn't exist.
        // Uses absolute paths because Termux openssh resolves ~ to its
        // compiled-in prefix (/data/data/com.termux/...), not $HOME.
        val sshConfig = File(sshDir, "config")
        if (!sshConfig.exists()) {
            sshConfig.writeText("""
                Host *
                    StrictHostKeyChecking accept-new
                    IdentityFile $homeDir/.ssh/id_ed25519
                    ServerAliveInterval 60
                    UserKnownHostsFile $homeDir/.ssh/known_hosts
            """.trimIndent() + "\n")
            try {
                Os.chmod(sshConfig.absolutePath, 384) // 0600
            } catch (e: Exception) {
                Logger.d(tag, "Failed to chmod ssh config: ${e.message}")
            }
        }

        Logger.i(tag, "SSH defaults configured")
    }

    /**
     * Ensures npm/npx shell functions exist in .bashrc and creates .npmrc.
     *
     * Android mounts /data noexec, so shell script wrappers in usr/bin/ fail with
     * "bad interpreter: Permission denied". Instead, npm/npx are defined as bash
     * functions that invoke node with the cli entry point.
     *
     * Safe to call on every launch — only appends if functions are missing.
     */
    fun createNpmWrappers() {
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        val filesDir = context.filesDir.absolutePath
        val npmCliJs = "$filesDir/usr/lib/node_modules/npm/bin/npm-cli.js"

        // Only set up if npm was actually extracted
        if (!File(npmCliJs).exists()) {
            Logger.d(tag, "npm not extracted yet, skipping npm setup")
            return
        }

        // Remove stale script-based wrappers from previous versions
        val binDir = File(context.filesDir, "usr/bin")
        for (name in listOf("npm", "npx")) {
            val script = File(binDir, name)
            if (script.exists() && !isSymlink(script)) {
                script.delete()
                Logger.d(tag, "Removed stale $name script wrapper")
            }
        }

        // Append npm/npx functions to .bashrc if not already present
        val bashrc = File(context.filesDir, "home/.bashrc")
        if (bashrc.exists()) {
            val content = bashrc.readText()
            if (!content.contains("npm()")) {
                bashrc.appendText(npmBashFunctions())
                Logger.i(tag, "Appended npm/npx functions to .bashrc")
            }
        }

        // Update .npmrc on every launch — nativeLibDir changes on APK reinstall
        val npmrc = File(context.filesDir, "home/.npmrc")
        val bashPath = "$nativeLibDir/libbash.so"
        // script-shell: use bundled bash for npm lifecycle scripts (Android has no /bin/sh)
        // os[]: install optional deps for both linux and android so tools like
        // @rollup/rollup-android-arm64 get installed alongside linux fallbacks
        val expectedContent = "script-shell=$bashPath\nos[]=linux\nos[]=android\n"
        if (!npmrc.exists() || npmrc.readText() != expectedContent) {
            npmrc.writeText(expectedContent)
            Logger.d(tag, "Updated .npmrc")
        }
    }

    /**
     * Ensures .bashrc sources toolchain-env.sh for on-demand toolchain env vars.
     * Safe to call on every launch — only appends if the sourcing line is missing.
     */
    fun ensureToolchainEnvSourcing() {
        val bashrc = File(context.filesDir, "home/.bashrc")
        if (bashrc.exists()) {
            val content = bashrc.readText()
            if (!content.contains("toolchain-env.sh")) {
                bashrc.appendText("""

# On-demand toolchain env vars (Go, Ruby, Java, etc.)
[ -f "${'$'}HOME/.vscodroid/toolchain-env.sh" ] && . "${'$'}HOME/.vscodroid/toolchain-env.sh"
""")
                Logger.i(tag, "Appended toolchain-env.sh sourcing to .bashrc")
            }
        }
    }

    private fun isSymlink(file: File): Boolean = try {
        Os.lstat(file.absolutePath)
        file.canonicalPath != file.absolutePath
    } catch (e: Exception) { false }

    private fun npmBashFunctions(): String = """

# npm/npx — shell functions (Android noexec prevents script wrappers)
# VSCODROID_PLATFORM_FIX=1: override process.platform to "linux" for npm only
# (child processes like Rollup/esbuild see real "android" platform)
# --prefer-offline: use local cache first, saves time on slow mobile connections
npm() { VSCODROID_PLATFORM_FIX=1 node "${'$'}PREFIX/lib/node_modules/npm/bin/npm-cli.js" --prefer-offline "${'$'}@"; }
npx() { VSCODROID_PLATFORM_FIX=1 node "${'$'}PREFIX/lib/node_modules/npm/bin/npx-cli.js" "${'$'}@"; }
"""

    /**
     * Updates nativeLibraryDir paths in settings.json.
     *
     * Android changes nativeLibraryDir on every reinstall (random hash in path).
     * Settings like terminal.integrated.profiles.linux.bash.path and git.path
     * reference this directory, so they must be refreshed on each launch.
     */
    fun updateSettingsNativeLibPaths() {
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        val settingsFile = File(context.filesDir, "home/.vscodroid/User/settings.json")
        if (!settingsFile.exists()) return

        val content = settingsFile.readText()
        // Match any /data/app/<random>/<pkg-random>/lib/<arch> prefix
        val pattern = Regex("""/data/app/[^"]+/lib/[^"]+""")
        val updated = pattern.replace(content, nativeLibDir)

        if (updated != content) {
            settingsFile.writeText(updated)
            Logger.i(tag, "Updated nativeLibDir paths in settings.json")
        }
    }

    private fun createWelcomeProject() {
        val projectsDir = File(Environment.getProjectsDir(context))
        val welcomeFile = File(projectsDir, "README.md")
        if (!welcomeFile.exists()) {
            welcomeFile.writeText("""
                # Welcome to VSCodroid

                This is your default projects directory. Create folders here to start coding.

                Your default projects are stored at:
                `Android/data/${context.packageName}/files/projects/`

                **Open any folder on your device**: Use the Command Palette
                (F1) → "VSCodroid: Open Folder from Device" to browse Downloads,
                USB drives, or cloud storage folders.

                **Recent folders**: Use "VSCodroid: Open Recent Folder" to quickly
                reopen previously selected folders.
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
        val safMirrorsDir = Environment.getSafMirrorsDir(context)
        val bashrc = File(context.filesDir, "home/.bashrc")
        if (!bashrc.exists()) {
            bashrc.writeText("""
                # VSCodroid bash configuration
                # Prompt via PROMPT_COMMAND (readline can't show PS1 on pipe stdin)
                __vscodroid_prompt() {
                    local dir="${'$'}PWD"
                    dir="${'$'}{dir/#${'$'}HOME/~}"
                    [[ "${'$'}dir" == /* ]] && dir="${'$'}{dir/#${'$'}PROJECTS_DIR/projects}"
                    # Abbreviate SAF mirror paths: /data/.../saf-mirrors/<hash>/... → [folder]/...
                    if [[ "${'$'}dir" == *saf-mirrors/* ]]; then
                        dir="${'$'}{dir#*saf-mirrors/}"
                        dir="${'$'}{dir#*/}"  # strip hash dir
                        [ -z "${'$'}dir" ] && dir="[saf]"
                        dir="[saf]/${'$'}dir"
                    fi
                    printf '\033[32m%s\033[0m ${'$'} ' "${'$'}dir"
                }
                PROMPT_COMMAND=__vscodroid_prompt
                PS1=''

                export PROJECTS_DIR='$projectsDir'
                export SAF_MIRRORS_DIR='$safMirrorsDir'
                alias ls='ls --color=auto'
                alias ll='ls -la'

                # On-demand toolchain env vars (Go, Ruby, Java, etc.)
                [ -f "${'$'}HOME/.vscodroid/toolchain-env.sh" ] && . "${'$'}HOME/.vscodroid/toolchain-env.sh"

                # Start in the active folder (SAF or default projects dir)
                if [ -f "${'$'}HOME/.vscodroid_folder" ]; then
                    __folder="${'$'}(cat "${'$'}HOME/.vscodroid_folder" 2>/dev/null)"
                    [ -d "${'$'}__folder" ] && cd "${'$'}__folder" 2>/dev/null || cd "${'$'}PROJECTS_DIR" 2>/dev/null || true
                    unset __folder
                else
                    cd "${'$'}PROJECTS_DIR" 2>/dev/null || true
                fi
            """.trimIndent() + "\n")
        }
    }

    private fun createBashProfile() {
        val bashProfile = File(context.filesDir, "home/.bash_profile")
        if (!bashProfile.exists()) {
            bashProfile.writeText("""
                # Source .bashrc for login shells (e.g. tmux sessions)
                if [ -f "${'$'}HOME/.bashrc" ]; then
                    . "${'$'}HOME/.bashrc"
                fi
            """.trimIndent() + "\n")
        }
    }

    private fun createTmuxConf() {
        val tmuxConf = File(context.filesDir, "home/.tmux.conf")
        if (!tmuxConf.exists()) {
            tmuxConf.writeText("""
                # VSCodroid tmux configuration
                set -g mouse on
                set -g default-terminal "xterm-256color"
                set -g history-limit 10000
                set -g escape-time 10
                set -g status off
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
                    "workbench.startupEditor": "none",
                    "workbench.colorTheme": "Default Dark Modern",
                    "editor.fontSize": 14,
                    "editor.wordWrap": "on",
                    "editor.minimap.enabled": false,
                    "diffEditor.wordWrap": "on",
                    "terminal.integrated.fontSize": 13,
                    "terminal.integrated.defaultProfile.linux": "bash",
                    "terminal.integrated.profiles.linux": {
                        "bash": {
                            "path": "$nativeLibDir/libbash.so",
                            "args": ["-i"],
                            "icon": "terminal-bash"
                        }
                    },
                    "git.path": "$nativeLibDir/libgit.so",
                    "terminal.integrated.shellIntegration.enabled": false,
                    "telemetry.telemetryLevel": "off",
                    "telemetry.enableTelemetry": false,
                    "update.mode": "none",
                    "update.showReleaseNotes": false,
                    "security.workspace.trust.enabled": false,
                    "python.languageServer": "Jedi",
                    "python.defaultInterpreterPath": "${context.filesDir.absolutePath}/usr/bin/python3",
                    "gitlens.showWelcomeOnInstall": false,
                    "gitlens.showWhatsNewAfterUpgrades": false,
                    "gitlens.codeLens.enabled": false,
                    "gitlens.currentLine.enabled": true,
                    "gitlens.hovers.enabled": false,
                    "gitlens.statusBar.enabled": false,
                    "launch": {
                        "version": "0.2.0",
                        "configurations": [
                            {
                                "name": "Attach to Node.js",
                                "type": "node",
                                "request": "attach",
                                "port": 9229,
                                "restart": true,
                                "skipFiles": ["<node_internals>/**"]
                            },
                            {
                                "name": "NestJS: Debug",
                                "type": "node",
                                "request": "launch",
                                "runtimeArgs": ["--inspect", "-r", "ts-node/register", "-r", "tsconfig-paths/register"],
                                "args": ["${'$'}{workspaceFolder}/src/main.ts"],
                                "skipFiles": ["<node_internals>/**"],
                                "console": "integratedTerminal"
                            },
                            {
                                "name": "Node.js: Run Current File",
                                "type": "node",
                                "request": "launch",
                                "program": "${'$'}{file}",
                                "skipFiles": ["<node_internals>/**"],
                                "console": "integratedTerminal"
                            }
                        ]
                    }
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

    private fun runMigrations(fromVersionCode: Int) {
        Logger.i(tag, "Running migrations from versionCode $fromVersionCode")

        // Add new migrations at the bottom with the next versionCode.
        // Each block runs for users upgrading FROM a version before the threshold.
        // Example:
        // if (fromVersionCode < 3) {
        //     migrateToV3()  // e.g., add new .bashrc entries
        // }

        Logger.i(tag, "Migrations complete")
    }

    fun getPreviousVersionCode(): Int {
        return prefs.getInt(KEY_VERSION_CODE, 0)
    }

    private fun getCurrentVersionCode(): Int {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).let {
                if (android.os.Build.VERSION.SDK_INT >= 28) it.longVersionCode.toInt()
                else @Suppress("DEPRECATION") it.versionCode
            }
        } catch (e: Exception) {
            0
        }
    }

    private fun markSetupComplete() {
        prefs.edit()
            .putString(KEY_VERSION, getCurrentVersion())
            .putInt(KEY_VERSION_CODE, getCurrentVersionCode())
            .apply()
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
        private const val KEY_VERSION_CODE = "setup_version_code"
    }
}
