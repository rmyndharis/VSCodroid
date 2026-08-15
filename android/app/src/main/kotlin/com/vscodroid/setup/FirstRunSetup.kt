package com.vscodroid.setup

import android.content.Context
import android.system.Os
import com.vscodroid.util.Environment
import com.vscodroid.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    suspend fun runSetup(): SetupResult = setupMutex.withLock {
        // Two Splash instances can exist at once (noHistory + standard
        // launchMode), each calling this from its own lifecycleScope. The body
        // is blocking I/O that never checks for cancellation, so cancelling the
        // loser does nothing — serialize instead, and let whoever waited find
        // the work already done. The winner's markSetupComplete() flips
        // isFirstRun() before the lock is released.
        if (!isFirstRun()) return@withLock SetupResult.SUCCESS
        runSetupLocked()
    }

    private suspend fun runSetupLocked(): SetupResult = withContext(Dispatchers.IO) {
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

            if (isUpgrade) {
                runPreExtractionMigrations(previousVersionCode)
            }

            // The reh-web download carries the web client inside this same tree,
            // so this one extraction is both the server and the workbench.
            reportProgress("Extracting server files...", 5)
            extractAssetDir("vscode-reh", "server/vscode-reh")

            reportProgress("Extracting server bootstrap...", 60)
            extractAssetFile("server.js", "server/server.js")
            extractAssetFile("process-monitor.js", "server/process-monitor.js")
            extractAssetFile("platform-fix.js", "server/platform-fix.js")
            extractAssetFile("dns-proxy.js", "server/dns-proxy.js")

            reportProgress("Extracting tools...", 62)
            extractAssetDir("usr", "usr")
            // Extraction merges; it never removes. An upgrade that changes the
            // bundled Python therefore writes the new stdlib beside the old one
            // and leaves both. Here the runtime is already in place, so this is
            // only the cleanup half.
            reconcilePythonRuntimeLocked()

            reportProgress("Setting up git...", 82)
            setupGitCore()

            reportProgress("Setting up tools...", 85)
            setupToolSymlinks()
            setupRipgrepVscodeSymlink()
            // Also here, not only in SplashActivity's always-run block: that
            // block runs before this extraction on a fresh install, when the
            // server tree does not exist yet, so the aliases it would build
            // no-op and Copilot would stay dead until the second cold start.
            setupCopilotAndroidAliases()
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

        ensureProjectsDir()
    }

    /**
     * Recreates the projects directory if it has gone.
     *
     * Alone among the directories above, this one lives in app-external storage
     * -- /storage/emulated/0/Android/data/<pkg>/files/projects -- which shows up
     * in every file manager and is exactly the kind of path a cleaner app
     * removes. The rest are under filesDir, where nothing outside the app can
     * reach them, so they only ever need creating. This one needs repairing.
     *
     * Creating it once per version was not enough. isFirstRun() gates on
     * versionName, so a folder deleted after setup stayed missing through every
     * relaunch and force-stop: the explorer was empty, new files could not be
     * saved, and terminals started in a directory that was not there. The only
     * ways back were clearing app data or installing a new version.
     *
     * Asks isDirectory rather than exists, because a plain file at that path
     * answers yes to the second question and is no more usable than nothing.
     */
    fun ensureProjectsDir(): String {
        val dir = File(Environment.getProjectsDir(context))
        if (!dir.isDirectory) {
            if (dir.mkdirs()) {
                Logger.i(tag, "Recreated the projects directory at $dir")
            } else {
                Logger.w(tag, "Could not recreate the projects directory at $dir")
            }
        }
        return dir.absolutePath
    }

    /**
     * @return false if any file under [assetPath] was present in the APK and
     *   could not be written. An asset that is simply absent is not a failure --
     *   several are, in builds that skip a download script -- so it answers
     *   true.
     *
     * Only [extractBundledExtensions] acts on this today, and an earlier version
     * of this line justified that by saying the other callers lose less. They do
     * not, and the sentence is worth correcting rather than deleting: the server
     * tree, the four bootstrap scripts and `usr/` all pass through here too, and
     * a file missing from any of them leaves a server that cannot start rather
     * than an extension that is merely stale. They are unchecked because
     * checking them is a larger change than this one -- a single lost file would
     * abort a 390 MB unpack that the retry then redoes from the beginning, on
     * exactly the low-storage devices the abort is meant to protect -- not
     * because the loss is smaller. Nothing on device verifies those trees are
     * complete; `verify-server-tree.py` checks the build, not the install.
     */
    private fun extractAssetDir(assetPath: String, destPath: String): Boolean {
        val destDir = File(context.filesDir, destPath)
        return try {
            val assets = context.assets.list(assetPath) ?: return true
            if (assets.isEmpty()) {
                return extractAssetFile(assetPath, destPath)
            }
            destDir.mkdirs()
            // Every child is attempted even after one fails, so the log names
            // all of them rather than only the first.
            var ok = true
            for (asset in assets) {
                if (!extractAssetDir("$assetPath/$asset", "$destPath/$asset")) ok = false
            }
            ok
        } catch (e: IOException) {
            Logger.d(tag, "Treating $assetPath as file (not directory)")
            extractAssetFile(assetPath, destPath)
        }
    }

    /** @return false only when the asset existed and its copy failed. */
    private fun extractAssetFile(assetPath: String, destPath: String): Boolean {
        val destFile = File(context.filesDir, destPath)
        destFile.parentFile?.mkdirs()

        // Opening the asset is separated from writing it so the two failures can
        // be told apart. They are not the same event: an absent asset is routine
        // -- several are absent in builds that skip a download script -- while a
        // copy that starts and then fails is not, and the single catch this
        // replaced reported both as "Asset not found".
        val input =
            try {
                context.assets.open(assetPath)
            } catch (e: IOException) {
                Logger.d(tag, "Asset not found: $assetPath (will be available after build)")
                return true
            }

        val written = input.use { stream -> writeAtomically(destFile) { output -> stream.copyTo(output) } }
        if (!written) {
            Logger.w(tag, "Failed to write $destPath; it keeps whatever it held before")
        }
        return written
    }

    /**
     * Re-extracts Python when the interpreter in the APK no longer matches the
     * runtime in filesDir.
     *
     * The interpreter ships in the APK and every install replaces it. Its
     * runtime library and stdlib travel in assets and reach filesDir only
     * through first-run extraction, which [isFirstRun] gates on versionName. An
     * install that changes the bundled Python without changing versionName --
     * `adb install -r` of a rebuilt debug APK is the everyday case -- therefore
     * leaves a new interpreter next to the previous runtime. Python then dies
     * with `CANNOT LINK EXECUTABLE ... library "libpython3.X.so" not found`,
     * naming a missing file rather than the install that removed it.
     *
     * The version is never hardcoded here. `scripts/download-python.sh` resolves
     * it from the Termux index at build time, so the only honest source is the
     * runtime's own filename, which encodes the version the interpreter was
     * linked against. Reading it costs one `assets.list` of a single directory,
     * so the common case -- nothing changed -- is a string comparison.
     *
     * Superseded copies are removed rather than left in place. A stdlib whose
     * interpreter is gone cannot be used by the one that replaced it, and the
     * tree is large enough to matter: an abandoned copy was found sitting
     * beside the current one on a device. That does discard anything pip
     * installed under the old version, which was already unreachable.
     */
    suspend fun reconcilePythonRuntime() = setupMutex.withLock { reconcilePythonRuntimeLocked() }

    /**
     * Caller must hold [setupMutex]. Two Splash instances can exist at once, and
     * both would otherwise see the same missing runtime and extract over each
     * other into the same files.
     */
    private fun reconcilePythonRuntimeLocked() {
        // An APK with no Python at all is a legitimate build shape, and it must
        // not be read as "every installed version is stale".
        val runtime = pythonRuntimeInAssets() ?: return
        val version = PYTHON_RUNTIME_NAME.find(runtime)?.groupValues?.get(1) ?: return
        val libDir = File(context.filesDir, "usr/lib")

        // Reclaim before extracting, for the reason runPreExtractionMigrations
        // gives for its own ordering: what frees disk has to run ahead of what
        // needs disk. Behind the extraction it was gated by the storage check
        // that it is the cure for -- on a device short of space the branch
        // below returned, the ~29 MB of a Python nothing can load stayed where
        // it was, and every later launch measured the same shortfall and made
        // the same decision. The one condition under which that tree is worth
        // reclaiming was the one condition under which it was kept.
        //
        // Safe this early because supersededPythonEntries names only entries
        // belonging to a version this APK does not carry, which is true whether
        // or not the current runtime has been extracted yet, and the extraction
        // below writes the current version only. An unreadable listing means no
        // candidates rather than no extraction, so it no longer returns.
        val present = libDir.listFiles()?.map { it.name } ?: emptyList()
        for (name in supersededPythonEntries(present, runtime)) {
            Logger.i(tag, "Removing superseded Python $name")
            File(libDir, name).deleteRecursively()
        }

        if (!File(libDir, runtime).exists()) {
            // extractAssetFile catches an IOException and leaves whatever it had
            // already written. A copy that runs out of disk therefore produces a
            // truncated libpython3.X.so, exists() accepts it, and this function
            // never looks again -- a corrupt runtime that no later launch
            // repairs. Refusing to start is the recoverable outcome: the trigger
            // stays true, and the next launch with room retries.
            //
            // The payload is about 29 MB; the margin is for the temporary space
            // the copies need. This addresses running out of disk, which is the
            // reason a copy realistically fails here, not every way one can.
            val required = 64L * 1_048_576L
            val available = context.filesDir.usableSpace
            if (available < required) {
                Logger.w(
                    tag,
                    "Not extracting Python: ${available / 1_048_576} MB free, " +
                        "${required / 1_048_576} MB needed. Python stays unavailable until there is room.",
                )
                return
            }
            Logger.i(tag, "Python runtime $runtime is missing; extracting it and its stdlib")
            // Stdlib first, runtime last. The runtime's absence is what brought
            // us here, so writing it last means an interrupted extraction leaves
            // exactly the state we started from and the next launch retries. The
            // alternative ordering can leave an interpreter that starts and then
            // fails on import, which is a worse thing to debug than one that
            // does not start at all.
            extractAssetDir("usr/lib/python$version", "usr/lib/python$version")
            extractAssetFile("usr/lib/$runtime", "usr/lib/$runtime")
            // extractAssetFile swallows an IOException and logs at debug, which
            // is right for an asset that is simply absent in some build shapes
            // but wrong to stay quiet about here: the check that brought us in
            // will be true again next launch, and the 23 MB will be attempted
            // again every time. Say so once, at a level someone will see.
            if (!File(libDir, runtime).exists()) {
                Logger.w(tag, "Python runtime $runtime still missing after extraction; the APK assets look incomplete")
            }
        }
    }

    /**
     * Whether [reconcilePythonRuntime] has anything to do.
     *
     * Two directory listings and some string comparison, so it is safe to ask on
     * the main thread at every launch. The work it gates is not: the stdlib is
     * 23 MB across some 1100 files, and running that in `onCreate` would trade a
     * broken Python for an ANR.
     */
    fun pythonRuntimeNeedsWork(): Boolean {
        val runtime = pythonRuntimeInAssets() ?: return false
        val libDir = File(context.filesDir, "usr/lib")
        if (!File(libDir, runtime).exists()) return true
        val present = libDir.listFiles()?.map { it.name } ?: return false
        return supersededPythonEntries(present, runtime).isNotEmpty()
    }

    /** The `libpython3.X.so` this APK carries, or null if it carries none. */
    private fun pythonRuntimeInAssets(): String? =
        try {
            context.assets.list("usr/lib")?.firstOrNull { PYTHON_RUNTIME_NAME.matches(it) }
        } catch (e: IOException) {
            Logger.w(tag, "Could not list usr/lib assets: ${e.message}")
            null
        }

    /**
     * Builds the single-file CA bundle git's curl insists on having.
     *
     * The bundled libcurl comes from Termux and carries Termux's compiled-in
     * bundle path, /data/data/com.termux/files/usr/etc/tls/cert.pem, which does
     * not exist in this sandbox. Every HTTPS request therefore died with "error
     * adding trust anchors from file", before any certificate was checked.
     *
     * Pointing GIT_SSL_CAPATH at Android's trust store does not answer it, and
     * that is the part worth recording: measured on device, a clone with CAPATH
     * set and nothing else still failed on the missing file, and clearing
     * sslCAInfo only changed the message to name an empty path. What works is a
     * bundle file -- with one, the clone succeeds whether CAPATH is set or not,
     * so curl was never reading the directory.
     *
     * The bundle is concatenated from the device's own trust store rather than
     * shipped, so it reflects what this device actually trusts and does not age
     * inside the APK. Rebuilt when the store's directory is newer than the file,
     * which is one stat rather than 143.
     */
    fun setupGitCaBundle() {
        val caDir = listOf("/apex/com.android.conscrypt/cacerts", "/system/etc/security/cacerts")
            .map { File(it) }
            .firstOrNull { it.isDirectory } ?: return

        val bundle = File(context.filesDir, "usr/etc/tls/cert.pem")
        if (bundle.exists() && bundle.length() > 0 && bundle.lastModified() >= caDir.lastModified()) {
            return
        }

        try {
            bundle.parentFile?.mkdirs()
            val certs = caDir.listFiles()?.sortedBy { it.name } ?: return
            // Atomic, and the guard above is why. Writing straight to the final
            // path leaves a half-written bundle behind on any failure -- a full
            // disk, a kill mid-copy -- carrying a fresh mtime, and the freshness
            // check then returns early on every later launch. The result is a
            // permanently truncated trust store: some certificates present, the
            // rest missing, so HTTPS clones fail against whichever hosts fell on
            // the wrong side of the cut, with nothing to suggest the file is the
            // problem. An interrupted write now leaves the previous bundle, or
            // no bundle at all, and either one gets rebuilt next time.
            val written = writeAtomically(bundle) { out ->
                for (cert in certs) {
                    if (cert.isFile) cert.inputStream().use { it.copyTo(out) }
                }
            }
            if (!written) {
                Logger.e(tag, "Could not write the CA bundle; the previous one is unchanged")
                return
            }
            Logger.i(tag, "CA bundle: ${certs.size} certificates from ${caDir.path}")
        } catch (e: Exception) {
            Logger.e(tag, "Failed to build CA bundle", e)
        }
    }

    /**
     * Points git-core's entries at binaries the app is actually allowed to run.
     *
     * Two kinds live there. Builtin subcommands are the same binary as git, so
     * they become symlinks to libgit.so. The remote helpers -- git-remote-http,
     * -https, -ftp and -ftps, one identical binary under four names -- are a
     * different program, shipped as libgit-remote-curl.so, and they are the ones
     * git genuinely has to exec: everything over HTTPS goes through one.
     *
     * Both have to resolve into nativeLibraryDir for the same reason ripgrep
     * does. SELinux refuses execve on anything under filesDir for targetSdk >=
     * 29, so the extracted copy of a helper is a file that installs, chmods and
     * then fails at the moment it is needed -- measured on an API 36 device,
     * "cannot exec 'git-remote-https': Permission denied" and a clone that dies
     * with "remote helper 'https' aborted session". A symlink works because
     * execve resolves it and checks the target.
     *
     * Links are repaired, not merely created, and that is the second half of the
     * fix. Android hands the app a new nativeLibraryDir on every reinstall, so
     * absolute links written by an earlier install point at a path that is gone
     * -- measured on the same device, where all 146 of them did, and where
     * git-clone reported "not found" rather than "permission denied" as a
     * result. File.exists() follows a link and reports false for exactly that
     * case, which is why staleness is read with Os.lstat and the recorded target
     * compared, the way setupToolSymlinks already does it.
     *
     * Safe to call on every launch, and called there so a reinstall cannot leave
     * git pointing into an install that no longer exists.
     */
    fun setupGitCore() {
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        val gitCorePath = File(context.filesDir, "usr/lib/git-core")
        val manifestFile = File(gitCorePath, "gitcore-symlinks")

        if (!manifestFile.exists()) {
            Logger.d(tag, "No gitcore-symlinks manifest found, skipping git-core setup")
            return
        }

        // git-core entry name -> the library in nativeLibraryDir it must resolve to
        val links = mutableMapOf<String, String>()
        manifestFile.readLines().filter { it.isNotBlank() }.forEach { links[it] = "libgit.so" }
        for (protocol in listOf("http", "https", "ftp", "ftps")) {
            links["git-remote-$protocol"] = "libgit-remote-curl.so"
        }

        var created = 0
        var repaired = 0

        for ((name, soName) in links) {
            val target = "$nativeLibDir/$soName"
            if (!File(target).exists()) continue
            val link = File(gitCorePath, name)

            val present = try { Os.lstat(link.absolutePath); true } catch (e: Exception) { false }
            if (present) {
                // readlink throws on a regular file, which is what the extracted
                // copy of a remote helper is -- that too has to be replaced.
                val current = try { Os.readlink(link.absolutePath) } catch (e: Exception) { null }
                if (current == target) continue
                link.delete()
                repaired++
            }

            try {
                Os.symlink(target, link.absolutePath)
                if (!present) created++
            } catch (e: Exception) {
                Logger.d(tag, "Failed to link $name -> $soName: ${e.message}")
            }
        }

        // Set execute permission on all files in git-core
        gitCorePath.listFiles()?.forEach { file ->
            if (file.isFile && !file.name.startsWith(".")) {
                file.setExecutable(true, true)
            }
        }

        Logger.i(tag, "git-core: $created links created, $repaired repaired")
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
     * Creates a symlink so VS Code's ripgrep finds rg at its expected path.
     *
     * The binary lives in nativeLibraryDir as libripgrep.so, because SELinux will
     * not exec anything under filesDir. VS Code looks for it inside the server
     * tree instead, so the two are joined by a symlink -- execve resolves it and
     * checks the target, which is where execution is allowed.
     *
     * The path it looks in moved in VS Code 1.133: @vscode/ripgrep with a single
     * bin/rg became @vscode/ripgrep-universal with one directory per platform.
     * Both are linked, so an install carrying either tree finds it; the older one
     * costs a directory and a symlink.
     *
     * Safe to call on every launch (recreates if stale, skips if current).
     */
    fun setupRipgrepVscodeSymlink() {
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        val rgBinary = File("$nativeLibDir/libripgrep.so")
        if (!rgBinary.exists()) return

        val target = rgBinary.absolutePath
        val serverDir = File(context.filesDir, "server/vscode-reh/node_modules")
        val binDirs = listOf(
            File(serverDir, "@vscode/ripgrep-universal/bin/linux-arm64"),
            File(serverDir, "@vscode/ripgrep/bin"),
        )

        for (rgBinDir in binDirs) {
            rgBinDir.mkdirs()
            val rgLink = File(rgBinDir, "rg")

            val linkExists = try { Os.lstat(rgLink.absolutePath); true } catch (e: Exception) { false }
            if (linkExists) {
                try {
                    if (Os.readlink(rgLink.absolutePath) == target) continue
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
    }

    /**
     * Aliases the Copilot platform packages under the name Android resolves.
     *
     * Node here reports process.platform === "android", and the Copilot CLI SDK
     * resolves its platform package as @github/copilot-<platform>-<arch> with no
     * fallback, so everything the server tree ships for linux-arm64 is invisible
     * on device: chat submit dies in ChatSessionsService before any request is
     * made. The server tarball cannot carry these aliases itself because AAPT
     * flattens asset symlinks into copies, so like the tool symlinks above they
     * are rebuilt on every launch. Relative targets keep them valid across
     * reinstalls. Three sites:
     *
     *  - REH node_modules: a link farm over copilot-linux-arm64 for the agent
     *    host, which runs the newer CLI line the server tree carries.
     *  - the built-in extension's node_modules: sdk -> ../copilot/sdk, whose
     *    index.js patch 0010 keeps in the build; the manifest pins the version
     *    the extension was compiled against.
     *  - ripgrep-universal: bin/android-arm64 -> linux-arm64, whose rg is
     *    already the Bionic binary via setupRipgrepVscodeSymlink().
     */
    fun setupCopilotAndroidAliases() {
        val serverRoot = File(context.filesDir, "server/vscode-reh")

        fun linkIfAbsent(link: File, target: String) {
            val exists = try { Os.lstat(link.absolutePath); true } catch (e: Exception) { false }
            if (exists) return
            try {
                Os.symlink(target, link.absolutePath)
            } catch (e: Exception) {
                Logger.d(tag, "copilot alias symlink failed for ${link.name}: ${e.message}")
            }
        }

        // REH side, for the agent host.
        val gh = File(serverRoot, "node_modules/@github")
        val linuxPkg = File(gh, "copilot-linux-arm64")
        if (linuxPkg.isDirectory) {
            val alias = File(gh, "copilot-android-arm64")
            alias.mkdirs()
            linuxPkg.listFiles()?.forEach { entry ->
                if (entry.name != "package.json") {
                    linkIfAbsent(File(alias, entry.name), "../copilot-linux-arm64/${entry.name}")
                }
            }
            try {
                val manifest = File(linuxPkg, "package.json").readText()
                    .replace("copilot-linux-arm64", "copilot-android-arm64")
                val aliasManifest = File(alias, "package.json")
                if (!aliasManifest.exists() || aliasManifest.readText() != manifest) {
                    aliasManifest.writeText(manifest)
                    Logger.i(tag, "copilot alias: REH copilot-android-arm64 -> copilot-linux-arm64")
                }
            } catch (e: Exception) {
                Logger.d(tag, "copilot REH alias manifest failed: ${e.message}")
            }
        }

        // Extension side, for the copilotcli session provider. Only meaningful
        // when the tree keeps sdk/index.js (patch 0010); without it the alias
        // would resolve to a directory with no entry point.
        val extCopilot = File(serverRoot, "extensions/copilot/node_modules/@github/copilot")
        if (File(extCopilot, "sdk/index.js").exists()) {
            try {
                val version = org.json.JSONObject(File(extCopilot, "package.json").readText())
                    .getString("version")
                val alias = File(extCopilot.parentFile, "copilot-android-arm64")
                alias.mkdirs()
                linkIfAbsent(File(alias, "sdk"), "../copilot/sdk")
                val manifest = """{"name":"@github/copilot-android-arm64","version":"$version","type":"module","exports":{"./sdk":{"import":"./sdk/index.js"}}}"""
                val aliasManifest = File(alias, "package.json")
                if (!aliasManifest.exists() || aliasManifest.readText() != manifest) {
                    aliasManifest.writeText(manifest)
                    Logger.i(tag, "copilot alias: extension copilot-android-arm64 pinned to $version")
                }
            } catch (e: Exception) {
                Logger.d(tag, "copilot extension alias failed: ${e.message}")
            }
        }

        // ripgrep-universal directory alias; its rg is the Bionic symlink.
        val rgBin = File(serverRoot, "node_modules/@vscode/ripgrep-universal/bin")
        if (File(rgBin, "linux-arm64").isDirectory) {
            linkIfAbsent(File(rgBin, "android-arm64"), "linux-arm64")
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
     * SELinux denies app_data_file:file execute_no_trans for targetSdk >= 29, so a
     * script with a shebang under filesDir fails with "bad interpreter: Permission
     * denied" no matter how it is chmod'ed. Instead, npm/npx are defined as bash
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
            // Kept as bytes and written back unchanged. Decoding to search for
            // the guards is fine -- they are ASCII -- but decoding to REWRITE
            // is not: readText replaces any byte that is not valid UTF-8 with
            // U+FFFD, so a Latin-1 accent the user typed into a comment or an
            // alias would be destroyed by appending an unrelated line. This
            // replaced appendText, which never read the file at all, and
            // carrying the bytes through is what keeps that property.
            val existing = bashrc.readBytes()
            val content = String(existing, Charsets.ISO_8859_1)
            val additions = StringBuilder()
            val added = mutableListOf<String>()
            if (!content.contains("npm()")) {
                additions.append(npmBashFunctions())
                added += "npm/npx"
            }
            // Guarded separately from the npm block rather than added to it: an
            // install that predates this already has npm(), so a shared guard
            // would skip the new function forever, and a widened one would append
            // npm() a second time.
            if (!content.contains("claude()")) {
                additions.append(claudeBashFunction())
                added += "claude"
            }
            // One rewrite through a temporary file rather than an append per
            // block. Each guard above reads a string its own block opens with,
            // so an append killed partway certified itself: `npm()` was there,
            // its body was not, and no later launch would add the rest. The two
            // decisions stay separate; only the write is shared.
            if (additions.isNotEmpty()) {
                val names = added.joinToString(" and ")
                if (writeAtomically(bashrc) { it.write(existing); it.write(additions.toString().toByteArray()) }) {
                    Logger.i(tag, "Appended $names to .bashrc")
                } else {
                    Logger.w(tag, "Could not append $names; .bashrc is unchanged")
                }
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
            // Bytes through unchanged, decoded only to search. See
            // [createNpmWrappers] for why the round trip would be lossy.
            val existing = bashrc.readBytes()
            val content = String(existing, Charsets.ISO_8859_1)
            if (!content.contains("toolchain-env.sh")) {
                // Rewritten whole through a temporary file rather than appended
                // in place. The guard above reads the filename, which appears
                // in the comment this block opens with, so an append cut short
                // by process death satisfied the check that would have finished
                // it and left a truncated `[ -f ...` line behind for good.
                val block = """

# On-demand toolchain env vars (Go, Ruby, Java, etc.)
[ -f "${'$'}HOME/.vscodroid/toolchain-env.sh" ] && . "${'$'}HOME/.vscodroid/toolchain-env.sh"
"""
                if (writeAtomically(bashrc) { it.write(existing); it.write(block.toByteArray()) }) {
                    Logger.i(tag, "Appended toolchain-env.sh sourcing to .bashrc")
                } else {
                    Logger.w(tag, "Could not append toolchain-env.sh sourcing; .bashrc is unchanged")
                }
            }
        }
    }

    /**
     * Brings the .bashrc prompt block up to [PROMPT_VERSION], rewriting whatever
     * older shape is there.
     *
     * The block is fenced by versioned markers so that any future change to it is
     * migratable. The first version had no markers at all — it printed straight
     * out of PROMPT_COMMAND with PS1 left empty, dating from when the terminal was
     * a pipe rather than the PTY node-pty now gives us — so that shape is also
     * recognised, by its function name and its `PS1=''`.
     *
     * Safe to call on every launch: it returns immediately once the current marker
     * is present, and a .bashrc whose prompt the user has rewritten matches no
     * anchor at all, so it is left as they wrote it.
     */
    fun ensurePromptFix() {
        val bashrc = File(context.filesDir, "home/.bashrc")
        if (!bashrc.exists()) return

        // Latin-1, not UTF-8, and the choice is load-bearing twice over. It maps
        // every one of the 256 byte values to exactly one character and back, so
        // the round trip is lossless for any file the user has -- readText would
        // turn a byte that is not valid UTF-8 into U+FFFD and this method
        // rewrites the whole file, so a single Latin-1 accent in a comment or an
        // alias would be destroyed on the first launch after a PROMPT_VERSION
        // bump. And because the mapping is one byte to one character, the string
        // offsets computed below are byte offsets, which is what lets the two
        // halves be copied through as bytes while the new block goes in as UTF-8.
        // Every anchor searched for here is ASCII, so the decoding cannot change
        // what matches.
        val bytes = bashrc.readBytes()
        val content = String(bytes, Charsets.ISO_8859_1)
        if (content.contains(PROMPT_MARKER_CURRENT)) return

        // Earliest anchor wins, so the old explanatory comment is swallowed too
        // rather than left behind describing a mechanism the file no longer uses.
        val start = listOf(PROMPT_BEGIN, LEGACY_PROMPT_COMMENT, PROMPT_ANCHOR_START)
            .map { content.indexOf(it) }
            .filter { it >= 0 }
            .minOrNull() ?: return

        val fenced = content.indexOf(PROMPT_END, start)
        val end = if (fenced >= 0) {
            content.indexOf('\n', fenced).takeIf { it >= 0 } ?: content.length
        } else {
            val legacy = content.indexOf(PROMPT_ANCHOR_END, start)
            if (legacy < 0) return
            legacy + PROMPT_ANCHOR_END.length
        }

        // Through a temporary file, and that is what makes the marker safe to
        // put first. `writeText` truncates and then writes, so a rewrite killed
        // partway left a .bashrc that opened with [PROMPT_MARKER_CURRENT] --
        // the very string the early return above reads -- and ended wherever
        // the write stopped: an unclosed `__vscodroid_prompt()` body, no
        // PROJECTS_DIR, no cd into the workspace. Every later launch read the
        // marker, concluded the block was current and returned, so the terminal
        // opened on a syntax error that nothing would ever repair. Nothing
        // appears under the real name now until all of it has been written, so
        // the marker cannot certify a file that was never finished.
        // The two surviving halves go out as the bytes they came in as; only the
        // block this method owns is encoded. PROMPT_BLOCK is not pure ASCII, so
        // it cannot ride the Latin-1 mapping out with them.
        if (!writeAtomically(bashrc) {
                it.write(bytes, 0, start)
                it.write(PROMPT_BLOCK.toByteArray())
                it.write(bytes, end, bytes.size - end)
            }) {
            Logger.w(tag, "Could not rewrite the .bashrc prompt block; it keeps the shape it had")
            return
        }
        Logger.i(tag, "Rewrote the .bashrc prompt block ($PROMPT_VERSION)")
    }

    private fun npmBashFunctions(): String = """

# npm/npx — shell functions (SELinux blocks exec of scripts under filesDir)
# VSCODROID_PLATFORM_FIX=1: override process.platform to "linux" for npm only
# (child processes like Rollup/esbuild see real "android" platform)
# --prefer-offline: use local cache first, saves time on slow mobile connections
npm() { VSCODROID_PLATFORM_FIX=1 node "${'$'}PREFIX/lib/node_modules/npm/bin/npm-cli.js" --prefer-offline "${'$'}@"; }
npx() { VSCODROID_PLATFORM_FIX=1 node "${'$'}PREFIX/lib/node_modules/npm/bin/npx-cli.js" "${'$'}@"; }
"""

    /**
     * `claude` in the terminal, which the extension's own login screen suggests.
     *
     * A function for the same reason npm is one: SELinux denies exec of anything
     * under filesDir, and the CLI lives there — it ships inside the extension the
     * user installed. It runs that very file rather than a second copy, so the
     * terminal and the extension are always on the same version, and the glob
     * picks up whatever version is installed without this needing to change.
     */
    private fun claudeBashFunction(): String = """

# claude — the CLI the Claude Code extension brings with it. Started through
# musl's loader: the CLI is a musl binary under filesDir, which SELinux will not
# execve but will let a loader map. libldmusl.so is found on PATH, which already
# includes nativeLibraryDir.
claude() {
    local cli="" c
    # An update can leave two versioned directories side by side, which made the
    # old echo-glob expand to two space-joined paths and fail the -f test. Take
    # the most recently installed candidate; -nt is a bash builtin, so this
    # leans on nothing external. [Aa] covers a gallery serving the publisher
    # name lowercased.
    for c in "${'$'}HOME"/.vscodroid/extensions/[Aa]nthropic.claude-code-*/resources/native-binary/claude; do
        [ -f "${'$'}c" ] || continue
        if [ -z "${'$'}cli" ] || [ "${'$'}c" -nt "${'$'}cli" ]; then cli="${'$'}c"; fi
    done
    if [ ! -f "${'$'}cli" ]; then
        echo "claude: install the Claude Code extension first" >&2
        return 127
    fi
    libldmusl.so "${'$'}cli" "${'$'}@"
}
"""

    /**
     * Updates nativeLibraryDir paths in settings.json.
     *
     * Android changes nativeLibraryDir on every reinstall (random hash in path).
     * Settings like terminal.integrated.profiles.linux.bash.path and git.path
     * reference this directory, so they must be refreshed on each launch.
     */
    fun updateSettingsNativeLibPaths() {
        migrateSettingsToMachinePath()

        val settingsFile = File(Environment.getMachineSettingsPath(context))
        if (!settingsFile.exists()) return

        val updated = refreshManagedPaths(
            settingsFile.readText(),
            Environment.getTerminalShellPath(context),
            Environment.getGitPath(context),
            Environment.getMuslLoaderPath(context),
        ) ?: return

        // Atomic because this file is the user's, not ours -- it carries their
        // editor preferences, and this runs at every launch. writeText truncates
        // first, so a failure between truncate and write leaves settings.json
        // empty or half-written, and the workbench reads that as "no settings"
        // rather than as an error. What we came to change is one path.
        if (!writeAtomically(settingsFile) { it.write(updated.toByteArray()) }) {
            Logger.w(tag, "Could not refresh managed paths; settings.json is unchanged")
            return
        }
        Logger.i(tag, "Refreshed managed paths in settings.json")
    }

    /**
     * Moves settings.json from the path this app used to write to the one the
     * workbench reads.
     *
     * Everything written to the old path was inert — the theme, the terminal
     * profile, the Python interpreter, all of it — so the move is what makes those
     * defaults take effect for the first time. It is a move rather than a fresh
     * write because the old file is reachable from the terminal and may have been
     * edited by hand.
     *
     * Runs on every launch and does nothing once the file is in place. If both
     * exist the new one wins and the old is left alone, since only the new one has
     * been in use.
     */
    private fun migrateSettingsToMachinePath() {
        val legacy = File(context.filesDir, "home/.vscodroid/User/settings.json")
        val current = File(Environment.getMachineSettingsPath(context))
        if (current.exists() || !legacy.exists()) return

        current.parentFile?.mkdirs()
        // Through a temporary file, because the guard above turns any partial
        // arrival into a permanent one. A byte copy interrupted by a full disk
        // or by process death leaves what it managed to write under the
        // destination name; `exists()` accepts that on every later launch and
        // returns, so the truncated file becomes the user's settings for good
        // while the intact original sits beside it, never read and never
        // deleted. The workbench does not report a short settings.json as an
        // error either -- it reads it as the settings -- so the loss is silent.
        // Failing outright instead leaves the original in place and lets the
        // next launch, with room, finish the move.
        val copied = legacy.inputStream().use { source ->
            writeAtomically(current) { source.copyTo(it) }
        }
        if (copied && legacy.delete()) {
            Logger.i(tag, "Moved settings.json to the path the workbench reads")
        } else {
            Logger.e(tag, "Could not move settings.json to ${current.absolutePath}")
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

                The same directory is `~/projects` in the terminal, which is where
                new terminals start.

                **Command Palette**: Ctrl+Shift+P opens it. Tap Ctrl on the key row
                above the keyboard, then Shift, then press P.

                **Terminal**: Node.js, Python, Git and Bash are bundled and ready to
                use. Run `node -v` or `python3 -V` to check.
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
            // Atomic for the reason the rewrite in ensurePromptFix() is: this
            // writer's guard is the file's own existence, so a first-run write
            // that stopped partway left a .bashrc that answered `exists()` and
            // was never written again -- the prompt half-defined and the
            // PROJECTS_DIR export missing, on a device that had never had a
            // working shell to compare against.
            val initial = "# VSCodroid bash configuration\n" + PROMPT_BLOCK + "\n\n" + """
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
            """.trimIndent() + "\n"
            // Thrown, not logged. This runs only from runSetupLocked, whose
            // markSetupComplete() is the last statement of the same try block --
            // so swallowing the failure certifies an install that has no
            // .bashrc, and isFirstRun() is keyed on versionName, so nothing
            // writes one until the app updates. The every-launch repairs cannot
            // cover it either: createNpmWrappers, ensureToolchainEnvSourcing
            // and ensurePromptFix all open with `if (bashrc.exists())`.
            //
            // The throw and the atomic write are worth exactly nothing apart.
            // The throw alone is what main did, and main's retry then skipped
            // the file, because writeText had already created a truncated one
            // that satisfied the guard above. Atomicity alone leaves the file
            // absent but tells no one, so no retry is offered. Together the
            // retry starts from a clean slate and produces a working shell.
            if (!writeAtomically(bashrc) { it.write(initial.toByteArray()) }) {
                throw IOException("could not write $bashrc")
            }
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
        // Environment.getMachineSettingsPath explains why it is this path and not
        // the `User/` one that looks like the obvious home for user settings.
        val settingsFile = File(Environment.getMachineSettingsPath(context))
        settingsFile.parentFile?.mkdirs()
        if (!settingsFile.exists()) {
            // The terminal profile is read, and the `linux` suffix is not a guess.
            // The workbench builds the key from the OS *integer* the remote sends:
            // `getPlatformKey()` maps 1 to "windows", 2 to "osx" and everything
            // else to "linux", and the server computes that integer as
            // `isMacintosh || isIOS ? 2 : isWindows ? 1 : 3`. Linux is the branch
            // nothing tests for, so Android — neither darwin nor win32 — has always
            // landed on it. isLinux is not consulted anywhere on this path, so
            // patches/0001 neither made this work nor is needed for it.
            //
            // Both fields below carry weight: the path names the symlink so the
            // basename is `bash`, which is the key the ptyHost looks up to decide
            // the injection arguments, and the args stay empty because it only
            // injects shell integration for empty or login args.
            //
            // This block was once documented as inert, on the grounds that the
            // remote "reports its platform as android". No such mechanism exists —
            // it reports an integer, never a platform string. The device
            // measurement offered as proof predates the settings-path fix, when
            // everything written here went to a file the workbench never read, so
            // no default profile was selected and terminals took the $SHELL
            // fallback for an unrelated reason.
            // Atomic like the migration and the refresh, and for the same
            // reason: the guard is the file's own existence, so a first-run
            // write that stopped partway would leave a truncated settings.json
            // that `exists()` accepts forever. The workbench reads a short file
            // as the settings rather than as an error, so the user would come
            // up with an arbitrary subset of the defaults and no way to tell.
            val defaults = """
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
                            "path": "${Environment.getTerminalShellPath(context)}",
                            "args": [],
                            "icon": "terminal-bash"
                        }
                    },
                    "git.path": "$nativeLibDir/libgit.so",
                    "terminal.integrated.shellIntegration.enabled": true,
                    "extensions.verifySignature": false,
                    "telemetry.telemetryLevel": "off",
                    "telemetry.enableTelemetry": false,
                    "update.mode": "none",
                    "update.showReleaseNotes": false,
                    "security.workspace.trust.enabled": false,
                    "python.languageServer": "Jedi",
                    "python.defaultInterpreterPath": "${context.filesDir.absolutePath}/usr/bin/python3",
                    "claudeCode.claudeProcessWrapper": "${Environment.getMuslLoaderPath(context)}",
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
            """.trimIndent()
            // Thrown for the reason createBashrc() throws: this runs only from
            // runSetupLocked, markSetupComplete() is downstream in the same try
            // block, and the every-launch repair that would otherwise catch up
            // opens with `if (!settingsFile.exists()) return`. A swallowed
            // failure here means no terminal profile, no git.path, no
            // claudeProcessWrapper and no verifySignature until the app
            // updates -- reported to the user as a successful first run.
            if (!writeAtomically(settingsFile) { it.write(defaults.toByteArray()) }) {
                throw IOException("could not write $settingsFile")
            }
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

        // Which of these need unpacking is [bundledDirsToExtract]'s decision and
        // its doc carries the reasoning. The short version: asking `exists()`
        // alone asked whether a directory carrying this version string had ever
        // been unpacked, never whether it holds the bytes this APK carries, so
        // an extension edited without a version bump reached clean installs and
        // nobody else -- and nothing noticed, because the extension tests in the
        // pull-request and release workflows read the asset rather than the
        // device.
        //
        // Read before extraction on purpose: the listing taken after it is the
        // one the superseded sweep below needs, and it must see what extraction
        // just created.
        val installed = extensionsDir.list()?.toList() ?: emptyList()
        val toExtract = bundledDirsToExtract(installed, bundled.toList())
        for (name in toExtract) {
            // Merges rather than emptying the directory first. That leaves a
            // file this build no longer ships behind, which is inert because
            // `package.json` does not name it, and it is the better of the two
            // failures: clearing first would leave no extension at all if the
            // copy were interrupted.
            //
            // The result is checked, and this is the one place on this path
            // that has to. extractAssetFile logs a failed copy and carries on,
            // which is right for the server tree -- one missing file there is
            // not worth abandoning a 390 MB unpack -- and wrong here: a copy
            // that silently does not happen leaves the previous release's code
            // in place, which is the exact defect this loop was rewritten to
            // fix, one layer further down. Throwing puts it in front of
            // runSetupLocked's catch, upstream of markSetupComplete().
            // Throwing is not enough on its own, and this is the half that was
            // missing. extractAssetDir creates the destination before copying
            // anything into it, so a copy that fails partway leaves a directory
            // holding some of the files. The retry then reads that directory as
            // proof the extension is installed -- [bundledDirsToExtract] keeps a
            // fetched one only while it is absent -- drops it from the list,
            // runs an empty loop, throws nothing, and lets markSetupComplete()
            // certify a half-unpacked extension. The manifest lists it from the
            // package.json that did land, so it appears installed and fails to
            // activate on every launch, permanently.
            //
            // Removing what this attempt created restores the absence the retry
            // needs. Only what it created: a directory that was already there
            // belongs to the previous release, and its files were each replaced
            // atomically, so what survives is whole even if it is mixed. Ours
            // are re-unpacked unconditionally, so a mixed one heals next run;
            // a fetched one is only ever in this list because it was absent, so
            // this branch is what makes its retry work at all.
            //
            // Which means the retry rests on that delete succeeding, and the
            // condition it has to succeed in is the condition that caused the
            // failure. Two things could defeat it, and they are not equally
            // likely -- the obvious one is measured safe and the other is not
            // the disk at all.
            //
            // A full filesystem does not stop it. Measured on ext4: a partial
            // extraction lookalike, the partition filled to 0 bytes free, a
            // control write of 64 KB refused with ENOSPC to prove the disk was
            // genuinely full, and `rm -rf` then returning 0 with the tree gone.
            // Unlinking releases space rather than needing it. Two limits worth
            // keeping: that was ext4 on an emulator, f2fs is untested, and it
            // measures the delete rather than this code, which has never run on
            // a device.
            //
            // What would actually defeat it is `listFiles()` answering null,
            // because `deleteRecursively` walks with it and returns false having
            // removed nothing -- measured in a JVM probe: unreadable directory,
            // null listing, false returned, every file still there. Reachable
            // here only through hardware failure. The tree is created by
            // `destDir.mkdirs()` moments earlier in this same run, by this
            // process, under `filesDir`, and nothing between the two changes its
            // mode; a plain file at the path is not this case, since the walk
            // yields it and deletes it. There is no test for it because
            // producing the state needs an unreadable directory, and a
            // permission test passes vacuously wherever the suite runs as root.
            // A guard that reports green when its own setup did not take is
            // worse than the gap it covers.
            //
            // So the error line below is for a state nobody expects rather than
            // a fallback for one we do. If it fires, the next attempt reads the
            // partial directory as an install and skips it, exactly as before
            // this branch -- the fix not applying, not a new defect. Inventing a
            // second mechanism against that would add unmeasured state to guard
            // an unmeasured failure, which is the trade this file has spent
            // several passes undoing.
            val dest = File(extensionsDir, name)
            val existedBefore = dest.exists()
            if (!extractAssetDir("extensions/$name", "home/.vscodroid/extensions/$name")) {
                if (!existedBefore && !dest.deleteRecursively()) {
                    Logger.e(
                        tag,
                        "Could not remove the partially unpacked $name. The next attempt will " +
                            "read it as installed and skip it, so this extension needs the " +
                            "directory removed by hand or an app update to recover.",
                    )
                }
                throw IOException("could not unpack bundled extension $name")
            }
        }

        val present = extensionsDir.list()?.toList() ?: emptyList()
        val superseded = supersededExtensionDirs(present, bundled.toList())
        for (name in superseded) {
            if (File(extensionsDir, name).deleteRecursively()) {
                Logger.i(tag, "Removed superseded bundled extension: $name")
            }
        }

        // Disjoint from superseded by construction: that set is versions of ids
        // still bundled, this one is our ids that stopped being bundled at all.
        for (name in retiredOwnExtensionDirs(present, bundled.toList())) {
            if (File(extensionsDir, name).deleteRecursively()) {
                Logger.i(tag, "Removed retired bundled extension: $name")
            }
        }

        // The server manages this file for marketplace installs, so it is never
        // regenerated wholesale. But it is the default profile's manifest — the
        // scanner shows only what is listed in it — and bundled extensions
        // change with app upgrades while the file survives them. Reconcile:
        // entries whose directory is gone are unloadable and dropped, freshly
        // extracted bundled versions gain an entry, everything else stays
        // exactly as the server wrote it.
        val manifestFile = File(extensionsDir, "extensions.json")
        if (!manifestFile.exists()) {
            generateExtensionsManifest(extensionsDir, bundled)
            // Recorded on this path too. A fresh install lists everything, so
            // nothing here needs the history -- but the *next* upgrade does, and
            // an install that never wrote it would read an empty set and treat
            // every bundled extension as new.
            rememberBundledIds(
                bundled.mapNotNull {
                    manifestEntryFor(extensionsDir, it)
                        ?.getJSONObject("identifier")?.getString("id")
                }
            )
        } else {
            reconcileExtensionsManifest(manifestFile, extensionsDir, bundled)
        }

        // Reports the decision. Reaching this line now also means every one of
        // those unpacks succeeded, since the loop above throws otherwise.
        Logger.i(tag, "Bundled extensions: ${toExtract.size} of ${bundled.size} needed unpacking, " +
            "${superseded.size} superseded removed")
    }

    /**
     * The bundled extension identifiers this app recorded the last time it set
     * up extensions.
     *
     * Empty on an install that predates the record, which is not the same as
     * "this app has never bundled anything" but is the only honest reading
     * available: see [bundledIdsToRelist] for what that costs, once.
     */
    private fun previouslyBundledIds(): Set<String> =
        prefs.getStringSet(KEY_BUNDLED_IDS, emptySet()) ?: emptySet()

    /**
     * A defensive copy is required: [android.content.SharedPreferences.Editor.putStringSet]
     * documents that the set passed in must not be modified afterwards, and the
     * instance handed back by `getStringSet` must not be modified at all.
     */
    private fun rememberBundledIds(ids: List<String>) {
        prefs.edit().putStringSet(KEY_BUNDLED_IDS, HashSet(ids)).apply()
    }

    private fun generateExtensionsManifest(extensionsDir: File, bundledDirs: Array<String>) {
        val entries = JSONArray()
        for (dirName in bundledDirs) {
            manifestEntryFor(extensionsDir, dirName)?.let { entries.put(it) }
        }

        // Atomic, on the same grounds reconcileExtensionsManifest states for the
        // other write of this file: a truncated manifest is read as an empty
        // extension list, so the extensions vanish rather than the write
        // visibly failing. Here it is also unrecoverable. Anything at this path
        // sends the next launch down the reconcile branch instead of this one,
        // and reconciliation cannot parse a half-written document -- it catches
        // and returns -- so the manifest is never regenerated and the list stays
        // empty for the life of the install.
        // Thrown rather than returned, and the caller is the reason. Returning
        // only ends this function: extractBundledExtensions would go on to
        // rememberBundledIds(), persisting the bundled identifier set for an
        // install that has no manifest. That record outlives the process and
        // the upgrade, and it is what a later reconcile uses to tell an
        // extension the user uninstalled from one this app has never shipped --
        // so an identifier recorded with no entry beside it reads as
        // deliberately removed and is never listed again.
        //
        // "Nothing else creates extensions.json, so the reconcile branch is
        // unreachable" was the reasoning that made that look harmless, and the
        // comment fourteen lines above the branch refutes it in this file's own
        // words: the server manages this file for marketplace installs. One
        // install from the gallery creates it, and the next upgrade then reads
        // every bundled extension as removed. Throwing also skips
        // markSetupComplete(), so the whole setup is retried instead.
        val manifestFile = File(extensionsDir, "extensions.json")
        if (!writeAtomically(manifestFile) { it.write(entries.toString(2).toByteArray()) }) {
            throw IOException("could not write $manifestFile")
        }
        Logger.i(tag, "Generated extensions.json with ${entries.length()} entries")
    }

    private fun manifestEntryFor(extensionsDir: File, dirName: String): JSONObject? {
        val extDir = File(extensionsDir, dirName)
        val pkgFile = File(extDir, "package.json")
        if (!pkgFile.exists()) {
            Logger.d(tag, "No package.json in $dirName, skipping manifest entry")
            return null
        }

        return try {
            val pkg = JSONObject(pkgFile.readText())
            val publisher = pkg.optString("publisher", "")
            val name = pkg.optString("name", "")
            val version = pkg.optString("version", "")

            if (publisher.isEmpty() || name.isEmpty()) return null

            JSONObject().apply {
                put("identifier", JSONObject().put("id", "${publisher.lowercase()}.${name.lowercase()}"))
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
        } catch (e: Exception) {
            Logger.d(tag, "Failed to parse $dirName/package.json: ${e.message}")
            null
        }
    }

    /**
     * Brings the manifest back in line with the directories after an upgrade
     * swapped bundled extension versions underneath it. Conservative on
     * purpose: an entry survives unless its directory is verifiably gone, and a
     * bundled directory is only added to replace an entry that was just
     * dropped — never for an identifier with no entry at all, which is an
     * extension the user uninstalled, nor over a surviving entry, so a user's
     * own newer install keeps winning over the bundled copy.
     */
    private fun reconcileExtensionsManifest(
        manifestFile: File,
        extensionsDir: File,
        bundledDirs: Array<String>,
    ) {
        try {
            val entries = JSONArray(manifestFile.readText())
            val kept = JSONArray()
            val keptIds = mutableSetOf<String>()
            val droppedIds = mutableSetOf<String>()
            var dropped = 0

            for (i in 0 until entries.length()) {
                val entry = entries.getJSONObject(i)
                val path = entry.optJSONObject("location")?.optString("path").orEmpty()
                val dirName = entry.optString("relativeLocation")
                    .ifEmpty { if (path.isEmpty()) "" else File(path).name }
                if (dirName.isNotEmpty() && !File(extensionsDir, dirName).exists()) {
                    dropped++
                    entry.optJSONObject("identifier")?.optString("id")?.let { droppedIds.add(it) }
                    continue
                }
                kept.put(entry)
                entry.optJSONObject("identifier")?.optString("id")?.let { keptIds.add(it) }
            }

            // Built once so the identifier of each bundled directory is read
            // from its own package.json rather than guessed from the folder name.
            val bundledEntries = bundledDirs.mapNotNull { dirName ->
                manifestEntryFor(extensionsDir, dirName)?.let {
                    it.getJSONObject("identifier").getString("id") to it
                }
            }

            val relist = bundledIdsToRelist(
                bundledIds = bundledEntries.map { it.first },
                keptIds = keptIds,
                droppedIds = droppedIds,
                previouslyBundledIds = previouslyBundledIds(),
            ).toSet()

            var added = 0
            for ((id, entry) in bundledEntries) {
                if (id !in relist) continue
                kept.put(entry)
                added++
            }

            if (dropped > 0 || added > 0) {
                // Same exposure as settings.json above: a truncated manifest
                // is read as an empty extension list, so every bundled
                // extension disappears rather than the write visibly failing.
                if (!writeAtomically(manifestFile) { it.write(kept.toString(2).toByteArray()) }) {
                    throw ManifestWriteFailed("could not rewrite $manifestFile")
                }
                Logger.i(tag, "Reconciled extensions.json: $dropped stale dropped, $added bundled added")
            }

            // Below the write, not above it. An earlier version sat above and
            // reasoned only about ordering against the DECISION -- correct as
            // far as it went, and the write six lines further down never
            // entered the frame. Recording a set the manifest does not contain
            // is the same defect [generateExtensionsManifest] throws to avoid,
            // and this is the half that runs on upgrades rather than fresh
            // installs: an identifier in the record with no entry beside it
            // reads as one the user removed, so it is never listed again, and
            // every later reconcile writes the bad set back over itself.
            //
            // Unconditional is still right. With nothing to write the manifest
            // on disk already matches this set, so the record is accurate.
            rememberBundledIds(bundledEntries.map { it.first })
        } catch (e: ManifestWriteFailed) {
            // Its own type, because IOException is too wide to mean "the write
            // failed" here. `manifestFile.readText()` above is inside this same
            // try and throws IOException too -- if the file is replaced by a
            // directory, or removed between the exists() check and the read --
            // and catching that would abort the whole setup over something the
            // catch below has always handled and survived. Only the write has
            // to reach runSetupLocked, because only the write leaves a record
            // that outlives the process.
            throw e
        } catch (e: Exception) {
            // A manifest this code cannot parse is one the server wrote in a
            // shape it understands; leave it alone rather than risk the user's
            // installed-extensions list.
            Logger.e(tag, "Could not reconcile extensions.json", e)
        }
    }

    /**
     * Migrations that have to run *before* the assets are unpacked.
     *
     * Kept separate from [runMigrations] because the ordering is not a detail:
     * extraction merges into whatever is already on disk and never deletes, so
     * anything that removes a stale tree has to happen first. Run afterwards it
     * would delete what was just unpacked, and the app would come up with no
     * server at all.
     */
    private fun runPreExtractionMigrations(fromVersionCode: Int) {
        if (fromVersionCode < PIVOT_VERSION_CODE) {
            // The server tree changed origin, not just version: what was there is a
            // pre-built VS Code Server, and what replaces it is Code - OSS built
            // from source. Their file sets differ — vsda and the bundled node are
            // gone, several paths moved — and extractAssetDir only ever writes over
            // what it recognises. Merging the two leaves orphans from the old tree
            // that nothing overwrites and nothing loads, with no visible symptom
            // beyond behaviour nobody can account for.
            val serverTree = File(context.filesDir, "server/vscode-reh")
            if (serverTree.exists()) {
                val freed = serverTree.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
                if (serverTree.deleteRecursively()) {
                    Logger.i(tag, "Removed the previous server tree (${freed / 1_048_576} MB)")
                } else {
                    // Not fatal on its own: extraction still writes the new tree over
                    // it. Say so loudly, because what survives is the orphan case
                    // above rather than a clean failure.
                    Logger.e(tag, "Could not remove the previous server tree; " +
                        "the new one will be merged into it")
                }
            }

            // Every pre-pivot release also extracted a standalone web client here
            // (the reh-web tree now carries it), and nothing writes or reads this
            // path anymore — without this, tens of MB ride along on every phone
            // forever, counted into the storage figure the app reports.
            val webTree = File(context.filesDir, "server/vscode-web")
            if (webTree.exists()) {
                val freed = webTree.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
                if (webTree.deleteRecursively()) {
                    Logger.i(tag, "Removed the orphaned web client tree (${freed / 1_048_576} MB)")
                } else {
                    Logger.e(tag, "Could not remove the orphaned web client tree at $webTree")
                }
            }
        }
    }

    private fun runMigrations(fromVersionCode: Int) {
        Logger.i(tag, "Running migrations from versionCode $fromVersionCode")

        // Post-extraction migrations go here; anything that deletes belongs in
        // runPreExtractionMigrations instead.
        //
        // Note that files owned by the user are not migrated from this method at
        // all. settings.json and .bashrc are both written only when absent, so a
        // change to their defaults reaches nobody who already has them — the
        // anchored rewrites in updateSettingsNativeLibPaths() and
        // ensurePromptFix() handle that, and they run on every launch rather than
        // only on a version change.

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

        /**
         * Bundled extension identifiers as of the last setup. Deliberately not
         * written by [markSetupComplete] with the two above: those describe the
         * install, this describes what was bundled, and the reconcile that reads
         * it runs before setup is marked complete.
         */
        private const val KEY_BUNDLED_IDS = "bundled_extension_ids"

        // Process-wide: each Splash instance builds its own FirstRunSetup, so an
        // instance field would serialize nothing.
        private val setupMutex = Mutex()

        /**
         * The release that replaces the pre-built VS Code Server with Code - OSS
         * built from source. Upgrades from anything earlier need the old server
         * tree removed rather than merged into.
         *
         * Must match versionCode in app/build.gradle.kts for the release that
         * ships the new tree; a mismatch means the migration either never runs or
         * runs for users who do not need it.
         */
        private const val PIVOT_VERSION_CODE = 11
    }
}

/**
 * The prompt block written into `.bashrc`, shared by the first-run write and by
 * [FirstRunSetup.ensurePromptFix], which replaces the legacy empty-PS1 prompt.
 */
private const val PROMPT_VERSION = "v2"
private const val PROMPT_BEGIN = "# >>> vscodroid prompt"
private const val PROMPT_END = "# <<< vscodroid prompt"
private const val PROMPT_MARKER_CURRENT = "$PROMPT_BEGIN $PROMPT_VERSION >>>"

private val PROMPT_BLOCK = """
    $PROMPT_MARKER_CURRENT
    # PROMPT_COMMAND computes the directory, PS1 renders it. The \[ \] markers tell
    # readline which bytes take no width; without them Ctrl+L and any wrapped line
    # redraw over the prompt. An earlier build printed the prompt straight out of
    # PROMPT_COMMAND with an empty PS1, dating from when the terminal was a pipe
    # rather than a PTY — readline could not measure that at all, and VS Code's
    # shell integration ended up wrapping an empty string.
    __vscodroid_prompt() {
        local dir="${'$'}PWD"
        # The tilde must be escaped. bash expands tildes in a substitution's
        # replacement text, so a bare one turns back into the home path and the
        # whole substitution collapses into a no-op. bash 3.2 does not do this,
        # so a macOS shell cannot reproduce it — only a device can.
        dir="${'$'}{dir/#${'$'}HOME/\~}"
        [[ "${'$'}dir" == /* ]] && dir="${'$'}{dir/#${'$'}PROJECTS_DIR/projects}"
        # Abbreviate SAF mirror paths: /data/.../saf-mirrors/<hash>/... → [saf]/...
        # At the mirror root there is nothing after the hash, so stripping has to be
        # conditional — stripping unconditionally leaves the hash itself standing,
        # which is the one thing this abbreviation exists to hide.
        if [[ "${'$'}dir" == *saf-mirrors/* ]]; then
            dir="${'$'}{dir#*saf-mirrors/}"
            case "${'$'}dir" in
                */*) dir="[saf]/${'$'}{dir#*/}" ;;
                *)   dir="[saf]" ;;
            esac
        fi
        __vscodroid_dir="${'$'}dir"
    }
    PROMPT_COMMAND=__vscodroid_prompt
    PS1='\[\033[32m\]${'$'}{__vscodroid_dir}\[\033[0m\] \${'$'} '
    $PROMPT_END $PROMPT_VERSION <<<
""".trimIndent()

/**
 * Anchors for a prompt block written before the versioned markers existed — the
 * shape shipped in v1.0.0, which printed from PROMPT_COMMAND with an empty PS1.
 */
private const val PROMPT_ANCHOR_START = "__vscodroid_prompt() {"
private const val PROMPT_ANCHOR_END = "PS1=''"
private const val LEGACY_PROMPT_COMMENT = "# Prompt via PROMPT_COMMAND"

/**
 * The bundled bash inside the terminal profile, and the bundled git. Both are
 * anchored on their key and match only a nativeLibraryDir value, so a path the
 * user chose themselves is left alone.
 *
 * The character class excludes braces deliberately: an earlier release used
 * `/data/app/[^"]+/lib/[^"]+`, whose tail ran straight past the directory and
 * swallowed the binary filename, leaving the terminal profile pointing at a
 * directory (issue #3). Every quantifier here is fenced by the delimiter it
 * must not cross.
 */
private val LEGACY_BASH_PROFILE_PATH = Regex(
    """("terminal\.integrated\.profiles\.linux"\s*:\s*\{\s*"bash"\s*:\s*\{[^{}]*?"path"\s*:\s*)"/data/app/[^"]*["]"""
)
private val GIT_PATH = Regex("""("git\.path"\s*:\s*)"/data/app/[^"]*["]""")

/**
 * The two settings that shipped alongside the old profile path and blocked shell
 * integration with it. Matched only in the exact shape this app wrote, so a
 * profile the user has since edited is left as they wrote it.
 */
private val LEGACY_PROFILE_ARGS = Regex(
    """("terminal\.integrated\.profiles\.linux"\s*:\s*\{\s*"bash"\s*:\s*\{[^{}]*?"args"\s*:\s*)\["-i"\]"""
)
private val SHELL_INTEGRATION_OFF = Regex(
    """("terminal\.integrated\.shellIntegration\.enabled"\s*:\s*)false"""
)

/**
 * The Claude Code wrapper path, under the same rule as the two paths above: it
 * matches only a value this app wrote, so a wrapper the user pointed somewhere
 * themselves is theirs to keep. Without an anchor this rewrote whatever it
 * found on every launch, which the doc on those two says is exactly what none
 * of them should do.
 *
 * Two managed shapes, not one. The current value lives in nativeLibraryDir and
 * moves on every reinstall. The other is `filesDir/usr/bin/...`, which is what
 * releases before the CLI stopped being bundled wrote there -- still ours to
 * re-point, and the reason an anchor on `/data/app/` alone would have stranded
 * those installs.
 *
 * [CLAUDE_WRAPPER_KEY] exists because refreshing and inserting are different
 * decisions once the anchor is there. A user-chosen value no longer matches
 * [CLAUDE_WRAPPER], and treating "did not match" as "not present" would write a
 * second copy of the key beside theirs.
 */
private val CLAUDE_WRAPPER = Regex(
    """("claudeCode\.claudeProcessWrapper"\s*:\s*)"(?:/data/app/|/data/(?:user/0|data)/[^"]*/files/usr/bin/)[^"]*""""
)
private val CLAUDE_WRAPPER_KEY = Regex(""""claudeCode\.claudeProcessWrapper"\s*:""")

/**
 * Whether the user's settings already mention extension signature verification.
 *
 * Only its presence matters, either value: someone who turned it back on meant to.
 */
private val VERIFY_SIGNATURE = Regex(""""extensions\.verifySignature"\s*:""")

/**
 * The first property in the document, with the indentation it sits at.
 *
 * Anchored to the opening brace so it cannot match a property nested inside some
 * other object further down, which would put the inserted line in the wrong scope.
 */
private val FIRST_PROPERTY = Regex("""(?<=\{)\s*\n([ \t]*)(?=")""")

/**
 * Reconciles the settings.json values this app manages, returning the updated
 * document or `null` when nothing needed changing.
 *
 * Two jobs. `git.path` still embeds `nativeLibraryDir`, which a reinstall moves,
 * so it is re-pointed whenever it has gone stale. The terminal profile is instead
 * migrated *off* `nativeLibraryDir` and onto the `usr/bin/bash` symlink, which
 * `setupToolSymlinks()` already repairs on every launch — after that move the
 * pattern no longer matches and the profile never goes stale again.
 *
 * The move carries the other two halves of the shell-integration fix with it,
 * because all three were written by the same release. Bundling them keeps the
 * migration one-shot: once the path is off `/data/app/`, nothing here fires
 * again, so a user who later turns shell integration back off keeps it off.
 *
 * Substitutes values in place and leaves every other byte untouched.
 * settings.json is JSONC: comments and trailing commas are legal there, so
 * parsing the document to re-serialise it would strip the user's comments,
 * escape every slash, and turn `["-i",]` into `["-i", null]`.
 *
 * A pattern that does not match changes nothing, so a file the user has
 * restructured is left as they wrote it rather than mangled.
 */
/**
 * Names the extension directories left behind by an earlier bundled version.
 *
 * Bundled extensions are extracted to `publisher.name-version` directories, so
 * bumping a version extracts a new directory beside the old one. The scanner
 * shows only what `extensions.json` — the default profile's manifest — lists,
 * not what sits on disk, so the deletion here is half of the swap: it is what
 * lets reconcileExtensionsManifest drop the old entry and list the new version
 * in its place. The other stake is disk, which never comes back on its own:
 * the Python extension alone is 29 MB, kept for as long as the app is
 * installed. (An earlier version of this comment claimed the scanner discovers
 * extensions by listing directories; the manifest is what it reads.)
 *
 * Only strictly older copies are named. A user who installed a newer build of the
 * same extension from the marketplace keeps it — that is their copy, and the
 * scanner already prefers it. A version that is not purely numeric is left alone
 * rather than guessed at.
 */
/**
 * Directories under our own publisher that this build no longer bundles.
 *
 * `vscodroid.*` never appears on the marketplace, so such a directory can only
 * be a leftover from a previous build of this app — the github-auth stub is the
 * case that prompted this. With the manifest reconciled it would otherwise
 * survive forever: reconciliation keeps any entry whose directory exists. A
 * directory whose base id is still bundled is not retired; its versions belong
 * to [supersededExtensionDirs].
 */
internal fun retiredOwnExtensionDirs(present: List<String>, bundled: List<String>): List<String> {
    fun base(dir: String): String? {
        val cut = dir.lastIndexOf('-')
        if (cut <= 0 || cut == dir.length - 1) return null
        return dir.substring(0, cut)
    }

    val bundledBases = bundled.mapNotNull(::base).toSet()
    return present.filter { name ->
        name.startsWith(OWN_EXTENSION_PREFIX) &&
            name !in bundled &&
            base(name).let { it != null && it !in bundledBases }
    }
}

/**
 * The publisher this project ships extensions under.
 *
 * Shared by the two decisions that turn on authorship so they cannot drift
 * apart: [retiredOwnExtensionDirs], which may delete a directory carrying it,
 * and [bundledDirsToExtract], which re-unpacks one every time. It is safe to
 * key on because this publisher never appears on the marketplace, so a
 * directory carrying it can only have come from a build of this app.
 */
internal const val OWN_EXTENSION_PREFIX = "vscodroid."

/**
 * Which bundled extension directories have to be unpacked over what is already
 * on disk.
 *
 * The split follows who wrote the extension, not how big it is, and that is the
 * whole of it: **content authored in this repository can change without its
 * version number moving; content fetched by version from Open VSX cannot.**
 * Collapsing the two branches back into one loses that distinction in whichever
 * direction it is collapsed.
 *
 * A directory is named `publisher.name-version`, so its presence answers "has a
 * directory carrying this version string been unpacked before" and nothing
 * about its contents. For an extension `download-extensions.sh` fetches at a
 * pinned version that is an exact staleness test -- the same version is the
 * same bytes. For one this repository edits in place it is no test at all,
 * which is the defect that removed the check: the process-monitor extension's
 * code was rewritten while its `package.json` stayed at 1.0.0, so the fix
 * reached clean installs and no one who upgraded.
 *
 * Both kinds share `assets/extensions`, and `.gitignore` is where the line is
 * already drawn -- this project's own are kept under source control, everything
 * else is downloaded. [OWN_EXTENSION_PREFIX] is that line expressed in a form
 * available at runtime.
 *
 * What the split buys, beyond correctness. Ours are twelve files and tens of
 * KB, so re-unpacking them every time costs nothing. The fetched ones are 57 MB
 * across 3787 files -- `ms-python.python` alone is 29 MB, a figure this file
 * also records at [supersededExtensionDirs] -- and re-copying those on every
 * versionName change would sit behind a progress bar that does not move off 88.
 * It also means an extension that regenerates files inside its own directory at
 * runtime is never copied over, so that state survives an upgrade; a blanket
 * re-copy would silently revert it.
 *
 * Pure, and takes the two listings rather than a directory, so the decision is
 * testable without a Context or a tree. `(present, bundled)` in that order, the
 * same as [supersededExtensionDirs] and [retiredOwnExtensionDirs]: all three
 * take two `List<String>` and a swap between them compiles in silence, so the
 * only protection is that there is nothing to remember.
 */
internal fun bundledDirsToExtract(present: List<String>, bundled: List<String>): List<String> =
    bundled.filter { it.startsWith(OWN_EXTENSION_PREFIX) || it !in present }

/**
 * The manifest rewrite failed, as distinct from the manifest being unreadable.
 *
 * [FirstRunSetup.reconcileExtensionsManifest] answers those two differently: an
 * unparseable manifest is left alone and setup carries on, while a failed write
 * has to abort so the identifier record is not persisted ahead of a file that
 * does not contain it. Both arrive as `IOException` from inside one try, so the
 * distinction needs a type rather than a catch clause.
 */
private class ManifestWriteFailed(message: String) : IOException(message)

/**
 * Whether [file] is itself a symbolic link.
 *
 * There were two of these in this package, asking two different questions, and
 * that is the defect rather than any symptom. This one now asks `lstat`, the way
 * `ToolchainManager` always did. The other asked whether `canonicalPath`
 * differed from `absolutePath`, which is not a question about [file]:
 * canonicalisation resolves links anywhere along the path, so it answers
 * "does this path traverse any symlink", and it answers that about the whole
 * path shape rather than about the last component.
 *
 * Be precise about what that did and did not cost, because the obvious story is
 * wrong and was written here before it was measured. On API 36 and 37
 * `/data/user/0` is a separate mount rather than a link to `/data/data` --
 * measured on both emulators, `ls -ld` shows a directory and `/proc/mounts`
 * shows its own `ext4` entry -- so canonicalisation moves an app-private path
 * nowhere at all. The old rule therefore answered false for a regular file and
 * true for a real link: the right answers, at the only call site that uses it
 * ([FirstRunSetup.createNpmWrappers]), by coincidence. No stale `npm` wrapper
 * survived because of it. An earlier version of this comment claimed one did.
 *
 * The coincidence is the problem. It is rented from a path shape the platform
 * owns and we do not, it has been otherwise on Android before, and it fails in
 * both directions when it changes: a genuinely symlinked parent makes every
 * regular file under it read as a link, so the delete this guards stops
 * happening; and `canonicalPath` throws on paths `lstat` handles, which the
 * catch below turns into "not a link" for something that is one.
 *
 * `lstat` is the one call that does not follow the final component, so it needs
 * none of that to be true. The mode test is split out so it can be pinned by a
 * test; see [isSymlinkMode].
 */
internal fun isSymlink(file: File): Boolean =
    try {
        isSymlinkMode(Os.lstat(file.absolutePath).st_mode)
    } catch (e: Exception) {
        false
    }

/**
 * Whether an `st_mode` as returned by `lstat` describes a symbolic link.
 *
 * Written against the raw POSIX file-type constants rather than
 * `OsConstants.S_ISLNK`, and that is a testability decision worth stating
 * plainly. `Os.lstat` cannot run in a JVM unit test, so the only part of this
 * predicate a test can reach is the arithmetic on a mode it supplies itself --
 * and `S_ISLNK` is a method on a stubbed platform class, so a test calling it
 * gets "not mocked" rather than an answer. The constants are no better: this
 * module sets no `isReturnDefaultValues`, and `OsConstants.S_IFMT` is not a
 * compile-time constant, so a unit test reads it as 0. A mask of zero makes
 * every mode compare equal, which is the same "true for everything" failure
 * this function exists to end.
 *
 * The values are fixed by the Linux ABI that Bionic implements: `S_IFMT` is
 * 0170000 and `S_IFLNK` is 0120000.
 */
internal fun isSymlinkMode(stMode: Int): Boolean = (stMode and 0xF000) == 0xA000

/**
 * Writes [dest] through a temporary file, so a failure leaves no partial file
 * under the name everything else checks for.
 *
 * Writing straight to the destination is what made a failed extraction
 * indistinguishable from a complete one: the bytes written before the failure
 * stay on disk, and every caller decides a file is present with `exists()`,
 * which a truncated file satisfies. The reconciliation added for #18 could
 * therefore accept a half-written interpreter runtime and never look again.
 *
 * The temporary file sits in the destination's own directory, so the rename is
 * within one filesystem and needs no space of its own. Its name is derived from
 * the destination rather than randomised, which means a copy killed outright --
 * process death, not an exception -- leaves at most one stray file per
 * destination, and the next attempt at the same asset truncates it. Sweeping
 * for strays would cost a directory walk on every extraction to reclaim a file
 * that the retry reclaims anyway, so there is no sweep.
 *
 * @return true if [dest] now holds what [write] produced. On false, [dest] is
 *   untouched -- it keeps its previous contents, or stays absent.
 */
internal fun writeAtomically(dest: File, write: (FileOutputStream) -> Unit): Boolean {
    val tmp = File(dest.parentFile, "${dest.name}.tmp~")
    try {
        FileOutputStream(tmp).use(write)
    } catch (e: IOException) {
        tmp.delete()
        return false
    }
    if (!tmp.renameTo(dest)) {
        tmp.delete()
        return false
    }
    return true
}

/**
 * Names the entries in `usr/lib` that belong to a Python the APK no longer
 * carries, given the runtime it does carry.
 *
 * Separated from the filesystem because the risk is one-directional and worth
 * testing on its own: naming one entry too few wastes disk, while naming one
 * too many deletes a stdlib that is still in use. Everything not recognisably
 * a Python runtime or stdlib is left alone, so an unfamiliar name in `usr/lib`
 * is never a candidate.
 *
 * @param present names in `usr/lib`, files and directories alike
 * @param runtime the `libpython3.X.so` this build ships
 */
internal fun supersededPythonEntries(present: List<String>, runtime: String): List<String> {
    val version = PYTHON_RUNTIME_NAME.find(runtime)?.groupValues?.get(1) ?: return emptyList()
    val currentStdlib = "python$version"
    return present.filter { name ->
        when {
            PYTHON_RUNTIME_NAME.matches(name) -> name != runtime
            PYTHON_STDLIB_NAME.matches(name) -> name != currentStdlib
            else -> false
        }
    }
}

// Anchored on purpose. The runtime is libpython3.13.so and the stdlib is
// python3.13; an unanchored match would also claim libpython3.13.so.1.0 and any
// directory that merely begins with the same letters.
internal val PYTHON_RUNTIME_NAME = Regex("""^libpython(3\.\d+)\.so$""")
internal val PYTHON_STDLIB_NAME = Regex("""^python3\.\d+$""")

/**
 * Which bundled extension identifiers need an entry written back into
 * `extensions.json`, given what the manifest already holds.
 *
 * Two cases arrive here looking exactly alike -- no manifest entry, and a
 * directory that extraction has just (re)created:
 *
 *  - the user uninstalled a bundled extension. VS Code removes the entry *and*
 *    the directory, so re-listing it would undo that choice on every upgrade.
 *  - the app began bundling an extension it has never shipped before.
 *
 * [previouslyBundledIds] is the only thing that separates them, which is why the
 * caller persists it. An identifier this app has never bundled cannot be one the
 * user removed. Without that record the first case was assumed for both, and
 * `vscodroid.vscodroid-serve-network` -- new in v1.1.0 -- reached no one
 * upgrading from v1.0.0 while working perfectly on a clean install, so no test
 * on a fresh device could have caught it.
 *
 * [keptIds] wins over every other reason to add: an identifier that still has a
 * live entry must not gain a second one, and a user's own newer install of the
 * same extension keeps its entry rather than being shadowed by the bundled copy.
 *
 * Pure, and takes the four sets rather than the files, so the decision is
 * testable without a manifest, a directory tree or a Context.
 */
internal fun bundledIdsToRelist(
    bundledIds: List<String>,
    keptIds: Set<String>,
    droppedIds: Set<String>,
    previouslyBundledIds: Set<String>,
): List<String> = bundledIds.filter { id ->
    when {
        id in keptIds -> false
        // Its entry was just dropped because the directory it named is gone --
        // an upgrade swapping 1.0.0 for 1.3.0 does exactly this.
        id in droppedIds -> true
        // Never bundled before, so there was no copy for the user to remove.
        else -> id !in previouslyBundledIds
    }
}

internal fun supersededExtensionDirs(present: List<String>, bundled: List<String>): List<String> {
    fun split(dir: String): Pair<String, String>? {
        val cut = dir.lastIndexOf('-')
        if (cut <= 0 || cut == dir.length - 1) return null
        return dir.substring(0, cut) to dir.substring(cut + 1)
    }

    fun parts(version: String): List<Int>? =
        version.split('.').map { it.toIntOrNull() ?: return null }

    fun isOlder(a: String, b: String): Boolean {
        val left = parts(a) ?: return false
        val right = parts(b) ?: return false
        for (i in 0 until maxOf(left.size, right.size)) {
            val l = left.getOrElse(i) { 0 }
            val r = right.getOrElse(i) { 0 }
            if (l != r) return l < r
        }
        return false
    }

    val current = bundled.mapNotNull(::split).toMap()
    return present.filter { name ->
        if (name in bundled) return@filter false
        val (id, version) = split(name) ?: return@filter false
        val bundledVersion = current[id] ?: return@filter false
        isOlder(version, bundledVersion)
    }
}

internal fun refreshManagedPaths(
    content: String,
    shellPath: String,
    gitPath: String,
    claudeWrapper: String,
): String? {
    var updated = GIT_PATH.replace(content) { "${it.groupValues[1]}\"$gitPath\"" }

    val movedProfile =
        LEGACY_BASH_PROFILE_PATH.replace(updated) { "${it.groupValues[1]}\"$shellPath\"" }
    if (movedProfile != updated) {
        updated = LEGACY_PROFILE_ARGS.replace(movedProfile) { "${it.groupValues[1]}[]" }
        updated = SHELL_INTEGRATION_OFF.replace(updated) { "${it.groupValues[1]}true" }
    }

    // Without this setting the Claude Code extension refuses to start at all —
    // resolveClaudeBinary() throws "Unsupported platform" rather than looking on
    // PATH — so an install that predates the setting needs it added, not just
    // refreshed. The value names musl's loader in nativeLibraryDir, so like the
    // two paths above it moves on every reinstall and has to be rewritten here.
    updated = when {
        // A managed value: refresh it, since nativeLibraryDir moves.
        CLAUDE_WRAPPER.containsMatchIn(updated) ->
            CLAUDE_WRAPPER.replace(updated) { "${it.groupValues[1]}\"$claudeWrapper\"" }
        // Present, but pointing somewhere the user chose. Leave it, and do not
        // insert beside it.
        CLAUDE_WRAPPER_KEY.containsMatchIn(updated) -> updated
        else -> insertSetting(updated, "claudeCode.claudeProcessWrapper", "\"$claudeWrapper\"")
    }

    // Signature verification cannot run here and refuses the install when it
    // cannot: Code - OSS has no node_modules/vsda, and verify-server-tree.py
    // rejects any tree that carries it, since only Microsoft's build may. Left on,
    // every marketplace install stops at "cannot verify the extension signature /
    // Signature verification was not executed" and offers to proceed unverified,
    // which teaches people to click past a security prompt for no gain.
    //
    // Added for installs that predate it rather than only written at first run,
    // and skipped when the key is already present in either state, because
    // switching it back on is a decision worth keeping.
    if (!VERIFY_SIGNATURE.containsMatchIn(updated)) {
        updated = insertSetting(updated, "extensions.verifySignature", "false")
    }

    return updated.takeIf { it != content }
}

/**
 * Adds a setting to a JSONC document, directly after its opening brace.
 *
 * The document belongs to the user and is full of comments and formatting this
 * app has no business reflowing, so exactly one line is inserted and nothing
 * else moves. It borrows the indentation of the first property when there is
 * one, which covers the document this app writes and anything formatted like it.
 *
 * [value] is written as-is, so it is raw JSON: callers quote their own strings.
 * That is what lets a boolean through -- "false" and "\"false\"" are different
 * settings, and the second one is not what any of these keys accept.
 */
private fun insertSetting(content: String, key: String, value: String): String {
    val brace = content.indexOf('{')
    if (brace < 0) return content
    val indent = FIRST_PROPERTY.find(content)?.groupValues?.get(1) ?: "    "
    return content.substring(0, brace + 1) +
        "\n$indent\"$key\": $value," +
        content.substring(brace + 1)
}
