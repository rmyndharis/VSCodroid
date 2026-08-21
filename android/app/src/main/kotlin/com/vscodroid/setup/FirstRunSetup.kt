package com.vscodroid.setup

import android.content.Context
import android.system.Os
import com.vscodroid.BuildConfig
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
import java.nio.file.Files
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.Certificate
import java.util.Base64

/**
 * @param assetBytes how much the APK's asset tree weighs, and [largestAssetBytes]
 *   the biggest single file in it. Both are measured at build time, see
 *   `app/build.gradle.kts`, and both are parameters rather than direct reads of
 *   `BuildConfig` so that the storage pre-flight can be exercised against a tree
 *   of known size. A unit test compiles against whatever `src/main/assets` holds
 *   on the machine running it: the whole 810 MiB on a developer's checkout, and
 *   empty directories on the CI runner, which stubs them
 *   (`.github/workflows/build.yml`, "Create minimal asset stubs"). With an empty
 *   tree every branch of the pre-flight computes the same number, so a test that
 *   did not supply its own figures would pass there while distinguishing nothing.
 */
class FirstRunSetup(
    private val context: Context,
    private val assetBytes: Long = BuildConfig.EXTRACTED_ASSET_BYTES,
    private val largestAssetBytes: Long = BuildConfig.LARGEST_ASSET_BYTES,
) {
    private val tag = "FirstRunSetup"
    private val prefs = context.getSharedPreferences("vscodroid_setup", Context.MODE_PRIVATE)

    var onProgress: ((message: String, percent: Int) -> Unit)? = null

    enum class SetupResult { SUCCESS, LOW_STORAGE, ERROR }

    /** What the run was doing when it failed, and what the failure was. */
    data class Failure(val step: String, val detail: String)

    /**
     * The cause of the last [SetupResult.ERROR], or null if setup has not failed.
     *
     * Read by whoever shows the failure. Until this existed the exception went to
     * `Logger.e` and nowhere else, so a release build told the user "Setup failed"
     * and kept the only useful sentence to itself. Retrying blind is the whole of
     * what was left to them, and a device out of space retries into the same wall
     * for ever.
     *
     * Set immediately before the ERROR is returned and never cleared: a stale
     * value cannot be shown, because the only screen that reads it reads it in the
     * same branch that just set it.
     */
    @Volatile
    var lastFailure: Failure? = null
        private set

    /** The most recent [reportProgress] label, which names the step in flight. */
    @Volatile
    private var currentStep: String? = null

    fun isFirstRun(): Boolean = setupIsStale(
        prefs.getString(KEY_VERSION, null),
        prefs.getInt(KEY_VERSION_CODE, 0),
        getCurrentVersion(),
        getCurrentVersionCode(),
    )

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

    /**
     * How much of `usr/` belongs to installed toolchains rather than to the APK.
     *
     * Read from `ToolchainRegistry` rather than by measuring the directories a
     * toolchain occupies, and the imprecision is the point. The registry's
     * figures are the ones shown to a user before a download and run a little
     * over what lands on disk (Go is listed at 179 MB against about 163 MB
     * unpacked), so this over-states what is foreign, which under-states the
     * credit. That is the direction [sharedTreeCredit] needs to be wrong in.
     *
     * Null when the record cannot be read, which [sharedTreeCredit] turns into
     * no credit at all. Answering zero there would have been the opposite of
     * safe: zero means "nothing in `usr/` is foreign", so an unreadable record
     * would have credited the directory in full, which is exactly the direction
     * that lets the gate pass a device it should refuse. A record that is simply
     * absent is different and does answer zero, because the file is written by
     * the first install and its absence means none has run.
     *
     * Reads `toolchains.json` directly rather than through `ToolchainManager`,
     * whose constructor builds an `AssetPackManagerFactory` and therefore cannot
     * be instantiated in a JVM unit test. Routing the gate through it took four
     * existing storage tests down with `NoClassDefFoundError` before this was a
     * plain file read.
     */
    private fun installedToolchainBytes(): Long? = try {
        val record = File(context.filesDir, "home/.vscodroid/toolchains.json")
        if (!record.exists()) {
            // Written by the first install; absent means none has run.
            0L
        } else {
            val state = JSONArray(record.readText())
            toolchainBytesFor(
                (0 until state.length()).mapNotNull {
                    state.optJSONObject(it)?.optString("name")?.ifEmpty { null }
                }
            )
        }
    } catch (e: Exception) {
        Logger.w(tag, "Could not read installed toolchains; crediting none of usr/: ${e.message}")
        null
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

        try {
            // Ahead of the pre-flight, not behind it, for the reason
            // reconcilePythonRuntimeLocked gives for its own ordering: what frees
            // disk has to run before what needs disk. This is where the previous
            // server tree and the orphaned web client go, hundreds of MB on a
            // device upgrading across the pivot, and behind the check it was
            // gated by the shortfall it is the cure for. A device short of room
            // returned LOW_STORAGE with those trees untouched, and Retry measured
            // the same shortfall for ever, on the one install where there was
            // something to reclaim.
            //
            // Safe this early because these migrations only delete, and only
            // trees this version no longer reads. Nothing below has run yet, so
            // there is nothing here for them to remove that was just written,
            // which is the ordering hazard runPreExtractionMigrations exists to
            // avoid, in the other direction.
            if (isUpgrade) {
                runPreExtractionMigrations(previousVersionCode)
            }

            // Pre-flight: enough room for the part of the tree that is not
            // already unpacked, plus what rewriting the rest of it costs.
            //
            // The asset total comes from the build rather than from a literal here,
            // see EXTRACTED_ASSET_BYTES in app/build.gradle.kts for why, and what is
            // already on disk is MEASURED rather than inferred from the tree being
            // there at all. Those two answers differ exactly where it matters: a
            // complete tree and a tree an interrupted attempt left half-written both
            // exist, and the first needs almost nothing while the second needs the
            // rest of itself. [requiredExtractionBytes] carries the arithmetic and
            // [installedExtractionBytes] the reasons for measuring only `server/`.
            //
            // Asking for the whole asset total unconditionally is what this replaced,
            // and it was survivable only by accident: while PIVOT_VERSION_CODE was
            // still ahead of every installed build, each upgrade reaching this line
            // had its old server tree deleted a few lines above and measured a device
            // with that room already given back. That accident has now expired, which
            // is what this arithmetic exists for: an upgrade from the Code - OSS tree
            // deletes nothing, and the demand would have been 874 MiB free ON TOP OF the
            // 810 MiB the install already occupies, refused on the splash screen,
            // with a Retry button that measures the same thing for ever and a
            // MainActivity that never runs, so nothing the app offers can free a byte.
            val available = context.filesDir.usableSpace
            // Kept on its own because two different questions read it. It counts
            // toward what is already on disk, and it is also the only honest
            // answer to "is the extracted tree here at all", which is what
            // decides the rewrite headroom. `usr/` cannot answer the second: the
            // per-launch repair block writes into it before this gate ever runs.
            val extractedTreeBytes = installedExtractionBytes(File(context.filesDir, "server"))
            val installed = extractedTreeBytes +
                sharedTreeCredit(
                    installedBytes = installedExtractionBytes(File(context.filesDir, "usr")),
                    bundledBytes = BuildConfig.BUNDLED_USR_BYTES,
                    foreignBytes = installedToolchainBytes(),
                ) +
                sharedTreeCredit(
                    installedBytes = installedExtractionBytes(
                        File(context.filesDir, "home/.vscodroid/extensions")
                    ),
                    bundledBytes = BuildConfig.BUNDLED_EXTENSION_BYTES,
                    foreignBytes = 0,
                )
            val required =
                requiredExtractionBytes(assetBytes, largestAssetBytes, installed, extractedTreeBytes)
            if (available < required) {
                lastRefusedBytes = required
                Logger.e(
                    tag,
                    "Insufficient storage: ${available / 1_048_576}MB available, " +
                        "${required / 1_048_576}MB required " +
                        "(${installed / 1_048_576}MB of the tree is already unpacked)",
                )
                return@withContext SetupResult.LOW_STORAGE
            }

            reportProgress("Creating directories...", 2)
            createDirectories()

            // Each of these is checked, and every one is attempted before the
            // check, so a failure names all of them rather than the first.
            //
            // A file lost from any of these three is worse than a stale
            // extension, not better: the server tree and the four bootstrap
            // scripts are what starts the server at all, and `usr/` is the tool
            // prefix everything on PATH resolves through. Losing one silently
            // left an install that reached the editor and could never serve it,
            // with markSetupComplete() certifying the result and isFirstRun()
            // keyed on versionName or versionCode, so nothing tried again until
            // the app updated. Nothing on device checks these trees are complete --
            // verify-server-tree.py checks the build, not the install.
            //
            // Aborting was held back on the argument that a single lost file
            // would send a low-storage device round a full unpack for ever, and
            // what answers that is the pre-flight above rather than anything
            // here: it asks for what is MISSING, so every byte this attempt did
            // write is counted in the device's favour on the next one. An abort
            // at the 800th MiB leaves a retry asking for the remainder plus the
            // room to rewrite one file, a figure the user can act on, and
            // not for a second 874 MiB the device has just spent on us. The two
            // are one mechanism and have to move together.
            //
            // What is NOT promised, spelled out because the missing half of it
            // was written here as a guarantee and was not one:
            //
            //  - the retry is cheap in SPACE, not in time. Nothing skips a file
            //    because it is already there, so a retry re-copies the whole
            //    tree; on a phone that is minutes behind a progress bar.
            //  - a failure that is not about disk repeats exactly. A
            //    destination the write cannot use fails the same way every
            //    time, and the user gets "Setup failed" on each attempt with
            //    nothing telling them the retry is pointless.
            //  - the partial tree is left on disk when the user gives up, and
            //    deliberately: it is what makes the retry affordable. Deleting
            //    it would hand the next attempt the full figure again.
            val incomplete = mutableListOf<String>()

            // The reh-web download carries the web client inside this same tree,
            // so this one extraction is both the server and the workbench.
            reportProgress("Extracting server files...", SERVER_PROGRESS_START)
            // The one step long enough for a still bar to read as a hang: the server
            // tree is the bulk of the assets and takes minutes on a mid-range phone.
            // A force-quit at that point produces exactly the partial tree the retry
            // below exists to clean up, so the bar moving is not decoration.
            //
            // Driven by bytes against the figure the build computed from the very tree
            // being packaged, so it cannot drift, and reported only when the whole
            // percent changes: 55 updates rather than one per file across 16,891 of
            // them. Capped, because a tree fetched after the APK was built would
            // otherwise run the bar past the step.
            var serverBytes = 0L
            var serverPercent = SERVER_PROGRESS_START
            val extracted = extractAssetDir("vscode-reh", "server/vscode-reh") { bytes ->
                serverBytes += bytes
                if (BuildConfig.BUNDLED_SERVER_BYTES > 0) {
                    val span = SERVER_PROGRESS_END - SERVER_PROGRESS_START
                    val next = SERVER_PROGRESS_START +
                        (serverBytes * span / BuildConfig.BUNDLED_SERVER_BYTES).toInt()
                            .coerceAtMost(span)
                    if (next > serverPercent) {
                        serverPercent = next
                        reportProgress("Extracting server files...", next)
                    }
                }
            }
            if (!extracted) incomplete += "vscode-reh"

            reportProgress("Extracting server bootstrap...", 60)
            for (script in listOf("server.js", "process-monitor.js", "platform-fix.js", "dns-proxy.js")) {
                if (!extractAssetFile(script, "server/$script")) incomplete += script
            }

            reportProgress("Extracting tools...", 62)
            if (!extractAssetDir("usr", "usr")) incomplete += "usr"

            if (incomplete.isNotEmpty()) {
                throw IOException("could not unpack ${incomplete.joinToString(", ")}")
            }
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
            lastFailure = describeFailure(currentStep, e)
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
        // isDirectory, not exists, for the reason ensureProjectsDir documents:
        // a plain file at the path answers exists() with yes, mkdirs() is then
        // skipped, and nothing says so -- every later write into the "directory"
        // fails on a state this method was built to prevent. mkdirs() cannot
        // repair that case (it will not replace a file), so the honest outcome
        // is a loud one. ProcessManager hardened its TMPDIR check to this same
        // form; it re-runs on every server start and is the effective backstop
        // for tmp, but only this method covers the rest.
        for (dir in dirs) {
            val file = File(context.filesDir, dir)
            if (!file.isDirectory && !file.mkdirs()) {
                Logger.w(tag, "Could not create $dir; something else occupies the path")
            }
        }
        val tmpDir = File(context.cacheDir, "tmp")
        if (!tmpDir.isDirectory && !tmpDir.mkdirs()) {
            Logger.w(tag, "Could not create the tmp directory; something else occupies the path")
        }

        ensureProjectsDir()
    }

    /**
     * Recreates the projects directory if it has gone.
     *
     * Alone among the directories above, this one lives in app-external storage
     * -- /storage/emulated/0/Android/data/<pkg>/files/projects -- which some routes
     * outside the app can still reach (MTP over USB, a few OEM managers) and
     * which Clear Data wipes outright. Android 11 closed Android/data to the
     * system Files app and to other apps, so this is narrower than the "shows up
     * in every file manager" it used to say, and still enough to lose it. The rest are under filesDir, where nothing outside the app can
     * reach them, so they only ever need creating. This one needs repairing.
     *
     * Creating it once per version was not enough. isFirstRun() gates on
     * versionName or versionCode, so a folder deleted after setup stayed missing
     * through every relaunch and force-stop: the explorer was empty, new files could not be
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
     * @param onBytes told how many bytes each extracted file wrote, so a caller can
     *   report progress across a step that would otherwise show none. Null for the
     *   short steps, where the cost of reporting outweighs what it shows.
     *
     * @return false if any file under [assetPath] was present in the APK and
     *   could not be written. An asset that is simply absent is not a failure --
     *   several are, in builds that skip a download script -- so it answers
     *   true.
     *
     * Two callers act on it, differently, and one ignores it. [runSetupLocked]
     * collects the server tree, the four bootstrap scripts and `usr/` into a list
     * and aborts the run when any of them is short, because a file missing from
     * those leaves a server that cannot start. [extractBundledExtensions] throws
     * at the point of failure instead, because it also has to remove the
     * half-unpacked directory it created. [reconcilePythonRuntimeLocked] looks
     * away and re-asks the filesystem afterwards, which answers the same question
     * for the one file it cares about.
     *
     * This paragraph said the three trees were unchecked, and that checking them
     * would send a low-storage device round a full unpack for ever. The first
     * half stopped being true when the abort was added. The second was the reason
     * the abort was held back, and what answers it is the pre-flight asking for
     * what is MISSING rather than for the whole tree, so the retry after an
     * abort measures a device that keeps the credit for everything it already
     * wrote.
     *
     * Nothing on device verifies those trees are complete;
     * `verify-server-tree.py` checks the build, not the install.
     */
    private fun extractAssetDir(
        assetPath: String,
        destPath: String,
        onBytes: ((Long) -> Unit)? = null,
    ): Boolean {
        val destDir = File(context.filesDir, destPath)
        return try {
            val assets = context.assets.list(assetPath) ?: return true
            if (assets.isEmpty()) {
                return extractAssetFile(assetPath, destPath, onBytes)
            }
            destDir.mkdirs()
            // Every child is attempted even after one fails, so the log names
            // all of them rather than only the first.
            var ok = true
            for (asset in assets) {
                if (!extractAssetDir("$assetPath/$asset", "$destPath/$asset", onBytes)) ok = false
            }
            ok
        } catch (e: IOException) {
            Logger.d(tag, "Treating $assetPath as file (not directory)")
            extractAssetFile(assetPath, destPath, onBytes)
        }
    }

    /** @return false only when the asset existed and its copy failed. */
    private fun extractAssetFile(
        assetPath: String,
        destPath: String,
        onBytes: ((Long) -> Unit)? = null,
    ): Boolean {
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
        // Reported from the destination rather than from copyTo's return, because the
        // write goes through a temporary file and a rename: a failed copy leaves the
        // destination as it was, and counting bytes that never landed would run the bar
        // past the end of a step that had not finished.
        if (written) onBytes?.invoke(destFile.length())
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
     * through first-run extraction, which [isFirstRun] gates on versionName or
     * versionCode. An install that changes the bundled Python without moving
     * either -- `adb install -r` of a rebuilt debug APK is the everyday case --
     * therefore
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
     * inside the APK. Two stores, not one: the system roots, and whatever CAs
     * the device owner installed for themselves through Settings. That second
     * half is why the bundle exists in the form it does. A developer whose
     * company re-signs TLS, or who runs an internal git host behind a CA they
     * issued, has already told the device to trust it, gone through the
     * full-screen warning and confirmed with their device credential; the
     * bundle honouring that is the app agreeing with a decision its owner
     * already made, not making one for them. Everything else here keeps system
     * trust: this writes the file git's curl reads and nothing more, so the
     * WebView and the toolchain downloader are untouched.
     *
     * The freshness test is two conditions rather than one, and the second is
     * not optional. Installing a CA through Settings writes into a store of its
     * own and never touches the mtime of the system certificate directory, so
     * the directory-newer-than-the-file check calls the bundle fresh at exactly
     * the moment it has gone stale. Without the fingerprint the right bundle
     * would be built once and then never again, and a user who installs a CA
     * would see the app ignore it for ever with nothing to indicate why. The
     * fingerprint covers the certificates' own bytes rather than their aliases
     * because a Conscrypt alias is a hash of the subject, so a CA re-issued
     * under the same name keeps the alias it had.
     */
    fun setupGitCaBundle() {
        val caDir = systemCaCertificateDirs
            .map { File(it) }
            .firstOrNull { it.isDirectory } ?: return

        val bundle = File(context.filesDir, "usr/etc/tls/cert.pem")
        val marker = File(context.filesDir, "usr/etc/tls/.user-ca-fingerprint")
        val userPems = userCertificatePems()
        val fingerprint = sha256HexOf(userPems.joinToString(""))

        if (bundle.exists() && bundle.length() > 0 &&
            bundle.lastModified() >= caDir.lastModified() &&
            runCatching { marker.readText() }.getOrNull() == fingerprint
        ) {
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
                for (pem in userPems) out.write(pem.toByteArray())
            }
            if (!written) {
                Logger.e(tag, "Could not write the CA bundle; the previous one is unchanged")
                return
            }
            // The marker goes last, after the bundle it vouches for is already
            // under its final name. Written first, a crash between the two would
            // strand a marker describing a bundle that was never built, and the
            // freshness check would then return early on every later launch --
            // the same permanent-staleness failure the atomic write above exists
            // to prevent, arriving by the other door. In this order a crash
            // anywhere leaves the marker absent or disagreeing, which is a
            // mismatch, which is a rebuild.
            if (!writeAtomically(marker) { it.write(fingerprint.toByteArray()) }) {
                Logger.w(tag, "Could not record the user-CA fingerprint; the bundle will be rebuilt next launch")
            }
            Logger.i(
                tag,
                "CA bundle: ${certs.size} certificates from ${caDir.path}, " +
                    "and ${userPems.size} user-installed CA(s)",
            )
        } catch (e: Exception) {
            Logger.e(tag, "Failed to build CA bundle", e)
        }
    }

    /**
     * The CAs the device owner installed themselves, PEM-encoded and sorted.
     *
     * Sorted because [KeyStore.aliases] promises no order, and an order that
     * varies between launches would change the fingerprint without the trust
     * having changed, rebuilding the whole bundle on the main thread every time
     * the app starts.
     *
     * Degrades to an empty list rather than throwing, at both levels. This runs
     * inside SplashActivity's per-launch repair block, where an exception costs
     * the repairs that follow it, and the whole feature is worth less than
     * [setupToolSymlinks]. So a provider that is absent or refuses to load
     * yields no user CAs and today's system-only bundle, and one certificate
     * whose encoding cannot be read is skipped rather than costing the other
     * entries the store holds.
     *
     * The cost is paid on every launch and it is not free. Measured on an API 33
     * emulator with one CA installed, five launches: 28 ms in the steady state,
     * of which `KeyStore.aliases()` over 126 entries is 25 to 27 ms and the rest
     * is under a millisecond, against 45 ms for the whole repair block around
     * it. The first launch after an install costs 160 ms once.
     *
     * A cheaper signal was looked for and deliberately not taken. Measured from
     * inside this app on the same emulator, `/data/misc/user/0/cacerts-added` is
     * readable: `exists`, `isDirectory` and `Os.stat` all answer, and the
     * listing has the one entry it should. So an mtime on that directory could
     * skip the enumeration on the launches where nothing changed. It is not used
     * because it is a hardcoded platform path with a user id baked into it, it
     * would need the current user's id on a multi-user device, and no version of
     * it has been checked on Android 14 or later, where the root store moved
     * into an APEX. A path like that stops matching in silence, and the way it
     * fails here is the expensive direction: a directory that no longer exists
     * reads as "nothing changed". Not worth 28 ms on a screen whose next act is
     * to start a Node server. If the cost ever stops being affordable, move this
     * repair off the main thread beside the toolchain passes that already run
     * there, rather than weakening the fingerprint until the store's changes go
     * unnoticed again.
     */
    private fun userCertificatePems(): List<String> =
        runCatching { userTrustedCertificates() }
            .onFailure { Logger.w(tag, "Could not read the user CA store: ${it.message}") }
            .getOrDefault(emptyList())
            .mapNotNull { cert -> runCatching { pemOf(cert) }.getOrNull() }
            .sorted()

    /**
     * Where the system trust store lives, as an injectable list so the bundle
     * builder can be exercised against a directory a test controls.
     *
     * A `var` for the same reason `SafSyncEngine.usableSpaceOf` and
     * `ProcessManager.killRecordedProcess` are: the real value is an absolute
     * path outside the app that no test can create. Not a seam to be tidied
     * away. The order was already the code's and is kept: the Conscrypt APEX
     * copy is asked for first, and the legacy path answers where the APEX one is
     * absent. Measured on an API 33 emulator, only the legacy path exists there
     * and it holds 125 certificates, so both entries are live rather than one
     * being a leftover.
     */
    internal var systemCaCertificateDirs: List<String> =
        listOf("/apex/com.android.conscrypt/cacerts", "/system/etc/security/cacerts")

    /**
     * Reads the user half of the device trust store.
     *
     * `AndroidCAStore` is the platform's own merged view of both halves, and it
     * is used here rather than the directories underneath it because it is the
     * documented API and carries no path this app has to keep up to date.
     * Conscrypt names its entries `system:<hash>.<n>` and `user:<hash>.<n>`, so
     * the prefix is what separates the owner's own CAs from the roots that
     * shipped with the device. Measured on an API 33 emulator from inside this
     * app's process, with one CA installed through Settings: 126 aliases, of
     * which exactly one began `user:`.
     *
     * A `var` for the same reason as [systemCaCertificateDirs], and more
     * sharply: the provider does not exist on a JVM at all, so every test of
     * the bundle builder replaces this.
     */
    internal var userTrustedCertificates: () -> List<Certificate> = {
        val store = KeyStore.getInstance("AndroidCAStore")
        store.load(null)
        store.aliases().toList()
            .filter { it.startsWith("user:") }
            .mapNotNull { alias -> runCatching { store.getCertificate(alias) }.getOrNull() }
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
        // script-shell: use bundled bash for npm lifecycle scripts, because
        // npm's fallback is a POSIX shell and this app's scripts assume bash
        // os[]: install optional deps for both linux and android so tools like
        // @rollup/rollup-android-arm64 get installed alongside linux fallbacks
        //
        // Reconciled rather than rewritten, and that is the whole point of the
        // shape below. This used to build the three lines and write them over
        // whatever was there, on every launch. `.npmrc` is also where npm itself
        // writes: `npm config set registry`, an auth token for a private
        // registry, `cafile`, `strict-ssl`. All of it worked until the app was
        // next opened, and then it was gone, with nothing on screen and nothing
        // in the log to say the app had done it. A user who lost a token twice
        // would have no way to reach that conclusion.
        //
        // So only the two keys this app owns are replaced. `script-shell` is
        // owned because it has to track nativeLibraryDir, which Android moves on
        // every reinstall, and because npm's fallback is a POSIX shell while this
        // app's scripts assume bash. `os[]=linux` and `os[]=android` are owned so
        // optional dependencies resolve for both, which is what gets
        // @rollup/rollup-android-arm64 installed beside the linux fallback. Every
        // other line is carried through untouched, in the order it was written.
        //
        // The owned lines go first so the file still reads exactly as it did when
        // nothing else is present, which keeps existing installs from seeing a
        // rewrite on the launch after this ships.
        val ownedLine = Regex("""^\s*(script-shell\s*=|os\[]\s*=\s*(linux|android)\s*$)""")
        // Latin-1, not UTF-8, and the charset is load-bearing for the reason
        // [ensurePromptFix] gives: it maps all 256 byte values one to one, so
        // every byte read here comes back out unchanged. `.npmrc` is where npm
        // keeps a registry auth token, a proxy password and a `cafile` path, none
        // of which is required to be valid UTF-8, and `readText` replaces every
        // byte that is not with U+FFFD. That would destroy the credential this
        // carry-through exists to preserve, on the first launch after the user
        // set it, with nothing on screen and nothing in the log to say so. The
        // three owned lines are ASCII, so encoding the result back through the
        // same mapping is lossless as well.
        val existing = if (npmrc.exists()) String(npmrc.readBytes(), Charsets.ISO_8859_1) else ""
        val carriedOver = existing
            .lines()
            .filterNot { ownedLine.containsMatchIn(it) }
            .dropLastWhile { it.isBlank() }
        val expectedContent =
            (listOf("script-shell=$bashPath", "os[]=linux", "os[]=android") + carriedOver)
                .joinToString("\n", postfix = "\n")
        // Compared against that same decoding rather than a second `readText`.
        // A file holding one byte that is not valid UTF-8 would never compare
        // equal to what was just written to it, so every launch would rewrite an
        // already-correct file for ever.
        if (existing != expectedContent) {
            // Atomically, like every other setup file this class writes. This one
            // was the exception, and it is a bad one to be: `writeText` truncates
            // before it writes, so a write that runs out of disk leaves an empty
            // `.npmrc` rather than the previous one. Empty means no
            // `script-shell`, and npm then hands every lifecycle script to
            // `/bin/sh`. That path does exist on Android, as a symlink into
            // `/system/bin` -- measured on an API 36 emulator, `ls -l /bin/sh`
            // and `/system/bin/sh` are the same 312024-byte mksh. So the failure
            // is not ENOENT but a shell that is not bash: a postinstall using
            // `[[`, arrays or `source` dies with a syntax error, for a reason
            // nothing on screen connects to storage.
            //
            // Nothing repairs it either: repairTruncatedSetupFiles covers
            // `.bashrc` and `settings.json`, and an empty `.npmrc` is
            // indistinguishable from one a user emptied on purpose. The rewrite
            // above is reached only when the content differs, and an empty file
            // does differ, but only on a launch that has room, which is the
            // launch that would not have broken it.
            if (writeAtomically(npmrc) { it.write(expectedContent.toByteArray(Charsets.ISO_8859_1)) }) {
                Logger.d(tag, "Updated .npmrc")
            } else {
                Logger.w(tag, "Could not update .npmrc; it keeps whatever it held before")
            }
        }
    }

    /**
     * Clears a setup file an older release left half-written, so the writers
     * that skip it because it exists will write it again.
     *
     * Every writer of `.bashrc` and `settings.json` decides by asking whether
     * the file is there. Before those writes were made atomic, one interrupted
     * by a full disk left a truncated file behind -- and `writeText` creates the
     * destination before writing a byte, so even a write that failed
     * immediately left an empty one. Both answer `exists()`, so nothing wrote
     * them again: the shell came up without PROJECTS_DIR or with an unclosed
     * function, the editor came up with a fraction of its defaults, and the
     * only way out was clearing app data. Atomicity stops that happening from
     * now on; it does nothing for the devices it already happened to.
     *
     * WHAT THIS WILL AND WILL NOT REPAIR, because the line matters more than
     * the repair. A file is only cleared on evidence that cannot be a choice
     * someone made:
     *
     *  - empty. Nobody means to have a zero-byte `.bashrc` or `settings.json`,
     *    and it is the shape an ENOSPC most often leaves.
     *  - a `.bashrc` that still opens with the header this app writes but has
     *    lost the `PROJECTS_DIR` export that always followed it. Someone
     *    replacing our file writes their own; someone editing it does not
     *    usually keep our first line and delete our third.
     *
     * A partial file that is neither -- one that got far enough to look
     * plausible -- is left alone, and that is deliberate. It cannot be told
     * from a file the user shortened themselves, and clearing it would destroy
     * their work to fix a state we are only guessing at. So this narrows the
     * damage rather than ending it, and says so.
     *
     * Clearing alone would make it worse, and the first draft of this did.
     * Both writers live in `runSetupLocked`, which an already-complete install
     * never re-enters, and the every-launch repairs all open with
     * `if (bashrc.exists())`. Deleting the file would have turned a truncated
     * one into no file at all, with nothing to write it again. So each clear is
     * followed immediately by the writer that owns the file, whose own
     * `!exists()` guard the clear has just satisfied.
     *
     * Runs on every launch, and before the appenders, so they extend the file
     * this has just restored rather than the one it removed.
     */
    fun repairTruncatedSetupFiles() {
        val bashrc = File(context.filesDir, "home/.bashrc")
        if (bashrc.isFile) {
            val text = runCatching { bashrc.readText() }.getOrNull()
            val emptied = text != null && text.isBlank()
            val headerWithoutBody = text != null &&
                text.startsWith(BASHRC_HEADER) &&
                !text.contains("export PROJECTS_DIR")
            if ((emptied || headerWithoutBody) && bashrc.delete()) {
                // Its writer throws on failure, which is right inside setup and
                // wrong here: this runs beside other per-launch repairs and must
                // not take them down. A failure leaves no .bashrc, which is what
                // the previous line already produced, and the next launch tries
                // again.
                runCatching { createBashrc() }
                    .onSuccess { Logger.i(tag, "Rewrote a half-written .bashrc") }
                    .onFailure { Logger.e(tag, "Could not rewrite the half-written .bashrc", it as? Exception) }
            }
        }

        val settings = File(Environment.getMachineSettingsPath(context))
        if (settings.isFile && settings.length() == 0L && settings.delete()) {
            runCatching { createDefaultSettings() }
                .onSuccess { Logger.i(tag, "Rewrote an empty settings.json") }
                .onFailure { Logger.e(tag, "Could not rewrite the empty settings.json", it as? Exception) }
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
     * Writes the file `BASH_ENV` points at, which is what gives a NON-interactive
     * shell the commands the terminal has.
     *
     * npm, npx, claude and every toolchain binary are bash functions rather than
     * files, because SELinux denies execve under filesDir. They were defined in
     * `.bashrc` alone, and bash reads `.bashrc` only when it is interactive. So
     * everything that runs a command through `bash -c` -- a VS Code task, an npm
     * lifecycle script, a build step an extension spawns -- was told
     * "command not found" for a command the terminal beside it ran fine, and a
     * toolchain that had installed correctly looked broken from the editor.
     *
     * Measured against bash 3.2.57, and it is the rule rather than the version
     * that is being relied on. `bash -c 'type -t npm'`, `bash script.sh` and
     * `bash -lc` all report the function once BASH_ENV names this file; an
     * interactive shell reports nothing, because it reads `.bashrc` and never
     * this.
     *
     * The two files DO overlap, and a login shell is where. `bash -lc` is
     * non-interactive, so it reads this file, and it is also a login shell, so it
     * reads `.bash_profile`, which [createBashProfile] writes as an unguarded
     * `. "$HOME/.bashrc"`. Measured: `bash -lc` runs both, `bash -c` runs only
     * this one. So under `-lc` the wrappers are defined twice,
     * `toolchain-env.sh` is sourced twice and every installed toolchain lands on
     * PATH twice. The closing `cd` in `.bashrc` runs twice as well, and no longer
     * moves a shell out of the directory it was started in: it fires only when
     * `PWD` is `HOME`. Redefining a function and re-prepending a PATH
     * entry are both harmless, which is why the overlap is left alone rather than
     * guarded. Anything added to this file that is NOT safe to run twice has to
     * bring its own guard.
     *
     * WHAT THIS DOES NOT FIX, because the gap is narrower than "commands work
     * now" and the rest needs a different mechanism:
     *
     *  - a direct execve of the bare name. `child_process.spawn("go", ...)` with
     *    no shell reaches no shell, so no function exists; and the file it would
     *    have to find is under filesDir, which cannot be executed at all.
     *  - `sh -c`. Android's `sh` is mksh, which has never heard of BASH_ENV, and
     *    bash itself ignores the variable when it is invoked as `sh` or with
     *    `--posix` -- both measured. Node's `child_process.exec()` and make's
     *    default recipe shell are `/bin/sh`, so neither is covered.
     *  - anything started before this file is written. It is regenerated at every
     *    launch, ahead of the server, so that only matters on the very first one.
     *
     * Rewritten whole whenever the contents differ rather than appended to: it is
     * generated state, so there is no user edit to preserve and no guard string
     * whose presence could certify a half-written file. `.bashrc` remains the
     * interactive shell's, and keeps its appenders.
     *
     * ⚠️ This file, and `toolchain-env.sh` which it sources, must print nothing.
     * Every `$(...)` in the app now runs a shell that reads them first, so a line
     * as harmless as `echo "toolchains ready"` would land inside the output of
     * every command substitution rather than on anyone's screen. `.bashrc` is not
     * the exception it looks like: `bash -lc` reaches it through `.bash_profile`,
     * and bash's rshd/sshd branch reads it INSTEAD of this file when SSH_CLIENT
     * or SSH2_CLIENT is set, or stdin is a socket, in a top-level shell. All
     * three conditions measured on bash 3.2.57, the third included: the branch is
     * skipped as soon as an inherited SHLVL says a shell is already nested. That
     * third one is no help here, because [Environment.buildProcessEnvironment]
     * exports no SHLVL and every shell the server starts is therefore top-level.
     * What keeps the branch out of reach on device is the first two: nothing in
     * this app sets either variable (there is no sshd here, only the ssh client),
     * and the two ways a shell gets started both rule the socket out, node-pty
     * giving the terminal a real pty and a task's shell a pipe. If a third way
     * ever appears, this file is the one that stops being read. `.bashrc`'s
     * prompt block is silent only because PS1 and PROMPT_COMMAND produce nothing
     * when nobody is at a prompt; a bare `echo` beside them would carry into
     * those same substitutions.
     */
    fun createBashEnvFile() {
        val envFile = File(Environment.getBashEnvPath(context))
        val content = BASH_ENV_HEADER + npmBashFunctions() + claudeBashFunction() + """

# On-demand toolchain env vars (Go, Ruby, Java, etc.)
[ -f "${'$'}HOME/.vscodroid/toolchain-env.sh" ] && . "${'$'}HOME/.vscodroid/toolchain-env.sh"
"""
        if (envFile.isFile && runCatching { envFile.readText() }.getOrNull() == content) return

        envFile.parentFile?.mkdirs()
        // Atomic because every non-interactive shell in the app sources this
        // file: a truncated copy is not a missing command but a syntax error
        // printed by every task, npm script and build the editor starts, with
        // PATH possibly half-built behind it. A failure leaves the previous
        // file, which is the last one that worked.
        if (writeAtomically(envFile) { it.write(content.toByteArray()) }) {
            Logger.i(tag, "Wrote ${envFile.name} for non-interactive shells")
        } else {
            Logger.w(tag, "Could not write ${envFile.name}; it keeps whatever it held before")
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

    /**
     * Guards the closing `cd` in `.bashrc` so it fires only for a shell that was
     * given no directory of its own, rewriting the unguarded shape every earlier
     * release wrote.
     *
     * [createBashrc] writes the guarded block now, but its own guard is whether
     * the file exists, and `isFirstRun` gates on the version rather than on the
     * contents, so an install that already has a `.bashrc` would keep the
     * unguarded `cd` for ever. This is the half that reaches those devices, and
     * it is why it runs at every launch rather than at setup.
     *
     * Safe to call every launch: it returns as soon as the current marker is
     * there, and a block the user edited matches [LEGACY_STARTUP_DIR_BLOCK]
     * nowhere, so it is left exactly as they wrote it.
     *
     * Latin-1 and atomic for the two reasons [ensurePromptFix] documents at
     * length: the mapping is lossless for any byte the user's file holds and
     * makes the offsets below byte offsets, so the halves either side of the
     * block go out as the bytes they came in as; and the marker is the first
     * thing in the block, so a rewrite cut short would otherwise leave a file
     * that certifies itself and is never repaired again.
     */
    fun ensureStartupDirGuard() {
        val bashrc = File(context.filesDir, "home/.bashrc")
        if (!bashrc.exists()) return

        val bytes = bashrc.readBytes()
        val content = String(bytes, Charsets.ISO_8859_1)
        if (content.contains(STARTUP_DIR_MARKER_CURRENT)) return

        // Newest first. A v1 block contains none of the v0 text, so the order does
        // not decide anything today, and it is fixed anyway so that adding a v3 is
        // one more entry rather than a question about which match wins.
        val previous = listOf(LEGACY_STARTUP_DIR_BLOCK_V1, LEGACY_STARTUP_DIR_BLOCK)
        val block = previous.firstOrNull { content.contains(it) } ?: return
        val start = content.indexOf(block)
        val end = start + block.length

        val written = writeAtomically(bashrc) {
            it.write(bytes, 0, start)
            it.write(STARTUP_DIR_BLOCK.toByteArray())
            it.write(bytes, end, bytes.size - end)
        }
        if (!written) {
            Logger.w(tag, "Could not guard the startup cd in .bashrc; it keeps the shape it had")
            return
        }
        Logger.i(tag, "Guarded the startup cd in .bashrc ($STARTUP_DIR_VERSION)")
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
            val initial = BASHRC_HEADER + "\n" + PROMPT_BLOCK + "\n\n" + """
                export PROJECTS_DIR='$projectsDir'
                export SAF_MIRRORS_DIR='$safMirrorsDir'
                alias ls='ls --color=auto'
                alias ll='ls -la'

                # On-demand toolchain env vars (Go, Ruby, Java, etc.)
                [ -f "${'$'}HOME/.vscodroid/toolchain-env.sh" ] && . "${'$'}HOME/.vscodroid/toolchain-env.sh"
            """.trimIndent() + "\n\n" + STARTUP_DIR_BLOCK + "\n"
            // Thrown, not logged. This runs only from runSetupLocked, whose
            // markSetupComplete() is the last statement of the same try block --
            // so swallowing the failure certifies an install that has no
            // .bashrc, and isFirstRun() is keyed on versionName or versionCode,
            // so nothing writes one until the app updates. The every-launch repairs cannot
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
            //
            // The two Python discovery keys are pinned here as well as in
            // [refreshManagedPaths], and the duplication is load-bearing:
            // SplashActivity runs that refresh BEFORE runSetup(), so on a clean
            // install it returns at its own `!exists()` guard and a first
            // session would otherwise run unpinned. See PYTHON_LOCATOR for why
            // neither value is a preference.
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
                    "python.locator": "js",
                    "python.useEnvironmentsExtension": false,
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
        // Directories only, and the filter is the point. `list()` returns files
        // and directories alike, so a plain file sitting at an extension's path
        // would answer "already installed" for a fetched one and drop it from
        // the list -- never unpacked, never retried, and nothing said. The
        // sweeps below take the unfiltered listing on purpose: for them a stray
        // file is something to remove, not something to mistake for an install.
        val installed = extensionsDir.listFiles()
            ?.filter { it.isDirectory }
            ?.map { it.name }
            ?: emptyList()
        val toExtract = bundledDirsToExtract(installed, bundled.toList())
        for (name in toExtract) {
            // Merges rather than emptying the directory first. That leaves a
            // file this build no longer ships behind, which is inert because
            // `package.json` does not name it, and it is the better of the two
            // failures: clearing first would leave no extension at all if the
            // copy were interrupted.
            //
            // A failed unpack has to do two things, and doing either alone
            // leaves the defect standing.
            //
            // THROW, because extractAssetFile logs a failed copy and carries
            // on. That is right for the server tree, where one missing file is
            // not worth abandoning a 390 MB unpack, and wrong here: a copy that
            // silently does not happen leaves the previous release's code in
            // place, which is what this loop exists to replace. Throwing puts
            // it in front of runSetupLocked's catch, upstream of
            // markSetupComplete().
            //
            // REMOVE WHAT THIS ATTEMPT CREATED, because throwing alone does not
            // make the retry retry. extractAssetDir creates the destination
            // before copying into it, so a partial copy leaves a directory --
            // and [bundledDirsToExtract] keeps a fetched extension only while
            // its directory is ABSENT. The next attempt would drop it from the
            // list, run an empty loop, throw nothing, and let setup certify a
            // half-unpacked extension that the manifest then lists from the
            // package.json that did land: installed to look at, dead on every
            // activation, permanently.
            //
            // Only what this attempt created. A directory that was already
            // there belongs to the previous release and its files were each
            // replaced atomically, so what survives is whole even if mixed, and
            // ours are re-unpacked unconditionally so a mixed one heals next
            // run. A fetched one is in this list only because it was absent,
            // which is why this branch is the whole of its retry.
            //
            // That makes the retry rest on the delete succeeding, in the
            // condition that caused the failure. Two things could defeat it:
            //
            //   a full disk does not. Measured on ext4 -- partition filled to
            //   zero, a 64 KB control write refused with ENOSPC to prove it,
            //   and the recursive removal then succeeding. Unlinking releases
            //   space rather than needing it. Limits: ext4 on an emulator,
            //   f2fs untested, and it measured the removal rather than this
            //   code.
            //
            //   `listFiles()` answering null would. deleteRecursively walks
            //   with it and returns false having removed nothing -- measured in
            //   a JVM probe. Reachable here only through hardware failure: the
            //   tree is created by mkdirs moments earlier in this same run, by
            //   this process, under filesDir, with nothing changing its mode in
            //   between. A plain file at the path is not this case; the walk
            //   yields it and removes it.
            //
            // So the error line below reports a state nobody expects rather
            // than guarding one we do. If it fires the next attempt skips the
            // extension exactly as it did before this branch existed -- the fix
            // not applying, not a new defect. There is deliberately no second
            // mechanism: producing the state for a test needs an unreadable
            // directory, and a permission test passes vacuously wherever the
            // suite runs as root, so a fallback here would be unmeasured state
            // guarding an unmeasured failure.
            val dest = File(extensionsDir, name)
            // isDirectory, not exists, and for the same reason createDirectories
            // asks it: the question is whether a previous release left something
            // worth keeping, and a plain file at this path is not that. It would
            // answer exists() with yes, be preserved rather than cleared, and
            // then defeat mkdirs on every future attempt -- unrecoverable for a
            // fetched extension, since the retry drops it from the list. Reading
            // it as "nothing usable here" clears it and lets the retry work.
            val existedBefore = dest.isDirectory
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

        // And the case neither of those reaches: an extension this project
        // FETCHED and then stopped shipping. Its publisher is not ours, so
        // retirement cannot see it, and no version of its id is bundled any
        // more, so supersession cannot either. Nothing removed it and nothing
        // ever would. Ordered before the manifest work below on purpose --
        // reconcileExtensionsManifest drops any entry whose directory is gone,
        // so removing the directory here is also what unlists it.
        // Once per id, not once per update. See [retiredIdsToSweep]: this
        // cannot distinguish a leftover from a deliberate reinstall, so running
        // it every time takes the extension away from a user who chose it.
        val sweptAlready = prefs.getStringSet(KEY_RETIRED_SWEPT, emptySet()) ?: emptySet()
        val owed = retiredIdsToSweep(sweptAlready)
        if (owed.isNotEmpty()) {
            for (name in retiredFetchedExtensionDirs(present, owed)) {
                if (File(extensionsDir, name).deleteRecursively()) {
                    Logger.i(tag, "Removed a bundled extension this build no longer ships: $name")
                }
            }
            // Recorded whether or not anything was found: the debt is discharged
            // by looking, and a device that never had the directory must not go
            // on looking for it at every update either.
            prefs.edit().putStringSet(KEY_RETIRED_SWEPT, HashSet(sweptAlready + owed)).apply()
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
            // The same type its twin throws, though nothing here needs a type to
            // escape a catch: this function sits outside any. Uniform because
            // the two are one decision written twice, and a reader who wrapped
            // this branch to match the reconcile branch beside it would
            // otherwise turn a plain IOException back into a swallowed one.
            throw ManifestWriteFailed("could not write $manifestFile")
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
     *
     * It also runs before the storage pre-flight, which is a second ordering
     * constraint with its own reason, see the call site. Everything here only
     * deletes, so nothing it does depends on the room the pre-flight is
     * measuring.
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
        // The single funnel every step already reports through, which is why the
        // failing step can be named without threading a parameter through the
        // twenty call sites below.
        currentStep = message
        onProgress?.invoke(message, percent)
    }

    companion object {
        /** How much of an exception message is worth putting on a splash screen. */
        internal const val DETAIL_LIMIT = 120

        /**
         * The user-facing description of a failure: which step, and what went wrong.
         *
         * A pure function in the companion so it can be exercised without a
         * `Context`, which the enclosing class needs and a JVM test cannot supply.
         *
         * The exception message is kept, not just its type. It is the half that
         * says something actionable, "No space left on device" or "Permission
         * denied", while the type alone says only that something threw. It is
         * truncated because a message can carry a whole stack of causes, and a
         * splash screen is not a log viewer; the untruncated one is already in
         * `Logger.e` above the call site.
         *
         * Trailing ellipsis is stripped from the step because every progress label
         * ends in one, and "failed while Extracting server files..." reads as if
         * the sentence itself were unfinished.
         */
        internal fun describeFailure(step: String?, error: Throwable): Failure {
            val type = error.javaClass.simpleName
            val message = error.message?.trim().orEmpty()
            val detail = when {
                message.isEmpty() -> type
                message.length <= DETAIL_LIMIT -> "$type: $message"
                else -> "$type: " + message.take(DETAIL_LIMIT).trimEnd() + "\u2026"
            }
            return Failure(step?.trimEnd('.', ' ').orEmpty(), detail)
        }

        /** The band the server extraction reports across; the next step opens at 60. */
        private const val SERVER_PROGRESS_START = 5
        private const val SERVER_PROGRESS_END = 60

        private const val KEY_VERSION = "setup_version"
        private const val KEY_VERSION_CODE = "setup_version_code"

        /**
         * Headroom above the bytes to be written, and the one number here that is
         * still a judgement rather than a measurement.
         *
         * Two things extraction needs that the asset byte count does not include.
         * The larger is block rounding: the tree is over 23,000 files, and each
         * one rounds up to a filesystem block on the way out, so a few KiB of
         * slack per file adds up to tens of MiB. The smaller is the files written
         * after extraction, settings.json, .bashrc, ssh defaults, git config,
         * which together are under a megabyte.
         *
         * Deliberately a fixed figure rather than a percentage: block rounding
         * scales with the file COUNT, not with the total size, and the count has
         * been stable across pins while the size has not.
         *
         * The same figure serves the upgrade path, which asks for far fewer bytes,
         * and that is not an oversight. Rounding on a rewrite is roughly neutral,
         * the file already occupies its blocks, but the credit given for it is
         * computed from logical file lengths, which under-states the space an
         * overwrite actually reclaims, and a new file in a new pin rounds like any
         * other. One over-estimate covering another is worth more here than a
         * second constant nobody can measure either.
         */
        private const val EXTRACTION_SLACK_BYTES = 64L * 1_048_576L

        /**
         * What the pre-flight asked for the last time it refused, or 0 if it has
         * not refused in this process.
         *
         * The figure depends on what is already unpacked, so it is no longer
         * something [requiredStorageMb] can compute on its own, and that
         * function cannot take a Context, because `SplashActivity` calls it
         * statically at the point it has a LOW_STORAGE result and nothing else.
         * Recording the refusal is what keeps the message naming the number the
         * user actually has to reach: telling an upgrader to free 874 MB when the
         * gate wanted 287 sends them to delete photos they did not need to lose,
         * and telling them 287 when the gate wanted 874 sends them back to the
         * same screen.
         *
         * Volatile because the gate runs on Dispatchers.IO and the message is
         * built on the main thread.
         */
        @Volatile
        private var lastRefusedBytes: Long = 0

        /**
         * How much free space the next unpack needs, given what is already there.
         *
         * Three terms, and each one answers a different question:
         *
         *  - what is missing. Extraction merges into whatever is on disk, so an
         *    install already holding the tree is not about to write it again from
         *    nothing. Clamped at zero: `installedBytes` can exceed the asset total
         *    when a pin drops files that extraction never removes.
         *  - the room to rewrite one file, charged only when the extracted tree is
         *    already there. [writeAtomically] writes `<dest>.tmp~` and renames, so
         *    while the biggest file is being replaced both copies exist, 113 MiB of
         *    Copilot runtime, currently. On an install with nothing on disk there
         *    is no second copy to hold, and charging for one would refuse fresh
         *    installs that fit.
         *
         *    [extractedTreeBytes] and not [installedBytes] decides that, and the
         *    two are not interchangeable. `installedBytes` also carries the credit
         *    for `usr/` and the extensions directory, and SplashActivity's
         *    per-launch repair block writes into `usr/` before this gate is ever
         *    reached: `setupGitCaBundle` alone leaves `usr/etc/tls/cert.pem`
         *    there. So on a genuinely fresh install `installedBytes` is already
         *    above zero, the headroom fired, and the gate asked a device with
         *    nothing unpacked for 986 MB where 873 is what the unpack needs.
         *    Only `server/` answers the question honestly, because nothing but
         *    extraction writes there, and it is also where the biggest file lands
         *    (`vscode-reh` extracts to `server/vscode-reh`).
         *  - [EXTRACTION_SLACK_BYTES], for what neither of those counts.
         *
         * Takes its figures rather than reading `BuildConfig`, so the decision can
         * be tested with a tree of known size; see the class doc for why that
         * matters on a runner whose asset directories are empty.
         */
        internal fun requiredExtractionBytes(
            assetBytes: Long,
            largestAssetBytes: Long,
            installedBytes: Long,
            extractedTreeBytes: Long,
        ): Long {
            val missing = (assetBytes - installedBytes).coerceAtLeast(0)
            val rewriteHeadroom = if (extractedTreeBytes > 0) largestAssetBytes else 0L
            return missing + rewriteHeadroom + EXTRACTION_SLACK_BYTES
        }

        /**
         * How much of a directory extraction shares with something else may be
         * credited as already unpacked.
         *
         * `server/` can be measured and believed, because nothing but extraction
         * writes there. `usr/` and the extensions directory cannot: toolchains
         * install into the first, `npm install -g` lands there too, and the
         * second fills with whatever the user takes from the gallery. Their size
         * on disk is therefore not an answer to "how much of what we are about
         * to write is already here", and charging them in full instead was
         * asking an updater for about 334 MB where roughly 180 would do.
         *
         * Two clamps, both pointing the same way:
         *
         *  - subtract [foreignBytes], what is known to belong to something else.
         *    An over-estimate here credits less, which is the safe direction.
         *  - cap at [bundledBytes]. Extraction writes exactly the bundled tree,
         *    so nothing beyond it can be a byte we are about to write over, and
         *    without this cap a directory swollen by toolchains would credit the
         *    install for space that overwriting never gives back.
         *
         * A null [foreignBytes] means the share could not be determined and
         * yields no credit. It is not the same as zero: zero asserts the
         * directory is ours alone, which is the assumption that would credit a
         * toolchain-filled `usr/` in full.
         *
         * Getting it wrong upward is the failure worth avoiding: the gate passes,
         * extraction runs out of disk partway, and the user is told "Setup
         * failed" with no figure, on every retry, because the toolchains stay
         * where they are. Getting it wrong downward costs one round of freeing
         * space that was not strictly needed.
         */
        internal fun sharedTreeCredit(
            installedBytes: Long,
            bundledBytes: Long,
            foreignBytes: Long?,
        ): Long {
            if (foreignBytes == null) return 0
            return minOf(bundledBytes, (installedBytes - foreignBytes).coerceAtLeast(0))
        }

        /**
         * What the pre-flight requires, in whole MB, for messages shown to the
         * user. Asking [FirstRunSetup] rather than repeating a literal in the UI:
         * a screen telling someone to free 500 MB when 873 is needed sends them
         * to clear space, come back, and fail again in the same place. (873 is
         * the gate on a fresh install today: 809.5 MiB of assets plus the 64 MiB
         * of [EXTRACTION_SLACK_BYTES]. It moves with the asset tree, which is
         * the reason this function exists rather than a literal in the UI.)
         *
         * The refusal that has just happened is the honest answer, and the
         * whole-tree figure is the fallback for a caller asking before any
         * refusal, there is none today, and it is the conservative direction
         * for one that appears.
         */
        fun requiredStorageMb(): Long =
            (
                lastRefusedBytes.takeIf { it > 0 }
                    ?: requiredExtractionBytes(
                        BuildConfig.EXTRACTED_ASSET_BYTES,
                        BuildConfig.LARGEST_ASSET_BYTES,
                        installedBytes = 0,
                        extractedTreeBytes = 0,
                    )
                ) / 1_048_576L

        /**
         * Bundled extension identifiers as of the last setup. Deliberately not
         * written by [markSetupComplete] with the two above: those describe the
         * install, this describes what was bundled, and the reconcile that reads
         * it runs before setup is marked complete.
         */
        private const val KEY_BUNDLED_IDS = "bundled_extension_ids"

        /**
         * Retired extension ids whose one-time cleanup has already run.
         *
         * A string set rather than a boolean, so retiring a second extension
         * later sweeps only that one and leaves the rest alone.
         */
        private const val KEY_RETIRED_SWEPT = "retired_extension_ids_swept"

        // Process-wide: each Splash instance builds its own FirstRunSetup, so an
        // instance field would serialize nothing.
        private val setupMutex = Mutex()

        /**
         * The release that replaces the pre-built VS Code Server with Code - OSS
         * built from source. Upgrades from anything earlier need the old server
         * tree removed rather than merged into.
         *
         * Frozen, and deliberately not tied to the shipping versionCode. The
         * comparison is `fromVersionCode < PIVOT`, asked of the code a device is
         * upgrading FROM, so the value has to name the boundary in history where
         * the tree changed origin and then never move again. versionCode 10 is
         * the last release carrying the pre-built server and must trigger; 12 is
         * the first carrying the Code - OSS tree and must not. 11 sits between
         * them and was burned by a failed upload, so no device holds it.
         *
         * Raising it to match a later versionCode is the tempting mistake: every
         * upgrade would then delete a 700 MB server tree it already had correct
         * and unpack it again, on every release, with no symptom but the wait.
         * [ServerTreePivotTest] fails if it moves.
         */
        private const val PIVOT_VERSION_CODE = 11
    }
}

/**
 * The first line of every `.bashrc` this app writes.
 *
 * One constant so the writer and [FirstRunSetup.repairTruncatedSetupFiles]
 * cannot drift: the repair decides a file is half-written partly by finding
 * this line with nothing that should follow it, and if the writer's wording
 * moved the repair would silently stop recognising its own output.
 */
private const val BASHRC_HEADER = "# VSCodroid bash configuration"

/**
 * What opens the file `BASH_ENV` names.
 *
 * Says out loud that the file is generated, because it is rewritten whenever its
 * contents change and an edit made here would be gone by the next launch. The
 * `.bashrc` beside it is the opposite: appended to, never regenerated, so a user
 * may edit that one freely.
 */
private const val BASH_ENV_HEADER = """# VSCodroid: sourced by NON-INTERACTIVE bash through BASH_ENV.
# Generated at every launch -- edit ~/.bashrc instead, which is yours.
#
# npm, npx, claude and the toolchain binaries are shell functions because
# SELinux will not execute a file under this app's data directory. Functions
# live in .bashrc, which only an INTERACTIVE bash reads, so without this file a
# task or an npm lifecycle script gets "command not found" for a command the
# terminal runs fine. An interactive shell never reads this file, but a login
# shell such as `bash -lc` reads both, because .bash_profile sources .bashrc, so
# everything here has to be safe to run twice.
#
# Nothing in here, or in the toolchain-env.sh it sources, may print: this file
# is read by the shell behind every $(...), so a stray echo ends up inside that
# command's output instead of on a screen.
"""

/** Bumped whenever [PROMPT_BLOCK] changes, so an older block is recognised and replaced. */
/**
 * Where an interactive shell starts when, and only when, nobody chose for it.
 *
 * bash runs `.bashrc` for every interactive shell, including the ones VS Code
 * started in a directory it picked. The server's `getCwd` takes the launch
 * config's `cwd` first, which is the folder right-clicked in the explorer or the
 * one an extension passed to `createTerminal`, then `terminal.integrated.cwd`,
 * and only then the active workspace folder. An unconditional `cd` here overrode
 * all three, so a terminal opened on a folder was moved somewhere else before
 * the user ever saw a prompt.
 *
 * There is no remembered folder here, and that is the point rather than an
 * omission. This block runs only when no workspace folder is open, so a folder
 * remembered from an earlier session is by construction not the one the user was
 * working in. The v1 shape read `~/.vscodroid_folder`, which was written only
 * when a device folder was opened through the picker and was never cleared, so a
 * device that had opened one once sent every later empty-window shell into that
 * mirror, where a write reaches nothing until the folder is opened again.
 * `~/projects` is where the welcome file already tells the user new terminals
 * start.
 *
 * `HOME` is the one directory the server never picks on purpose: it is the last
 * fallback in `getCwd`, reached only when no workspace folder is open. Landing
 * there is therefore the signal that nobody chose, and that this block still has
 * a job to do.
 *
 * `-ef` rather than `=`, because it compares device and inode instead of text.
 * `${'$'}PWD` comes from `getcwd()` in the child while `${'$'}HOME` is the string this app
 * exported, and the guard must not turn on whether the two spell one directory
 * the same way. It is a bash builtin, so it costs no process, and `.bashrc` is
 * already bash-only.
 *
 * Concatenated after `trimIndent()` rather than written inside the raw string
 * above, so its indentation is fixed here and cannot be re-flowed by a later
 * edit to the lines around it. [ensureStartupDirGuard] matches this text byte
 * for byte on devices that already have a `.bashrc`.
 */
private const val STARTUP_DIR_VERSION = "v2"
private const val STARTUP_DIR_BEGIN = "# >>> vscodroid startup dir"
private const val STARTUP_DIR_END = "# <<< vscodroid startup dir"
private const val STARTUP_DIR_MARKER_CURRENT = "$STARTUP_DIR_BEGIN $STARTUP_DIR_VERSION >>>"

private val STARTUP_DIR_BLOCK = """
    $STARTUP_DIR_MARKER_CURRENT
    # Start in the projects directory ONLY when this shell was given no directory
    # of its own. See FirstRunSetup.ensureStartupDirGuard for why the test is -ef.
    if [ "${'$'}PWD" -ef "${'$'}HOME" ]; then
        cd "${'$'}PROJECTS_DIR" 2>/dev/null || true
    fi
    $STARTUP_DIR_END $STARTUP_DIR_VERSION <<<
""".trimIndent()

/**
 * The block as every release before this one wrote it, byte for byte.
 *
 * Frozen, and never to be regenerated from [STARTUP_DIR_BLOCK]. It is what sits
 * on every device that already has the app, and matching it exactly is what
 * makes the migration safe: a `.bashrc` whose block the user edited matches this
 * nowhere and is left exactly as they wrote it. Deriving it from the new block
 * would disarm that on the first edit to the new one.
 */
/**
 * The v1 block, byte for byte, markers written out rather than composed.
 *
 * Frozen for the reason [LEGACY_STARTUP_DIR_BLOCK] gives, and the markers are
 * literals here rather than `$STARTUP_DIR_BEGIN` and the version constant, so
 * that bumping the version cannot quietly rewrite what this is supposed to
 * match. An exact match is the whole mechanism: a block the user edited matches
 * nothing here and is left as they wrote it.
 */
private val LEGACY_STARTUP_DIR_BLOCK_V1 = """
    # >>> vscodroid startup dir v1 >>>
    # Start in the active folder ONLY when this shell was given no directory of
    # its own. See FirstRunSetup.ensureStartupDirGuard for why the test is -ef.
    if [ "${'$'}PWD" -ef "${'$'}HOME" ]; then
        if [ -f "${'$'}HOME/.vscodroid_folder" ]; then
            __folder="${'$'}(cat "${'$'}HOME/.vscodroid_folder" 2>/dev/null)"
            [ -d "${'$'}__folder" ] && cd "${'$'}__folder" 2>/dev/null || cd "${'$'}PROJECTS_DIR" 2>/dev/null || true
            unset __folder
        else
            cd "${'$'}PROJECTS_DIR" 2>/dev/null || true
        fi
    fi
    # <<< vscodroid startup dir v1 <<<
""".trimIndent()

private val LEGACY_STARTUP_DIR_BLOCK = """
    # Start in the active folder (SAF or default projects dir)
    if [ -f "${'$'}HOME/.vscodroid_folder" ]; then
        __folder="${'$'}(cat "${'$'}HOME/.vscodroid_folder" 2>/dev/null)"
        [ -d "${'$'}__folder" ] && cd "${'$'}__folder" 2>/dev/null || cd "${'$'}PROJECTS_DIR" 2>/dev/null || true
        unset __folder
    else
        cd "${'$'}PROJECTS_DIR" 2>/dev/null || true
    fi
""".trimIndent()

private const val PROMPT_VERSION = "v2"
private const val PROMPT_BEGIN = "# >>> vscodroid prompt"
private const val PROMPT_END = "# <<< vscodroid prompt"
private const val PROMPT_MARKER_CURRENT = "$PROMPT_BEGIN $PROMPT_VERSION >>>"

/**
 * The prompt block written into `.bashrc`, shared by the first-run write and by
 * [FirstRunSetup.ensurePromptFix], which replaces the legacy empty-PS1 prompt.
 */
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
 * The two settings that route Python environment discovery through `pet`, the
 * Python extension's native locator.
 *
 * Pinned rather than defaulted, and not as a preference: `pet` is a Rust binary
 * the extension spawns from `<extension>/python-env-tools/bin/pet`, and it is
 * not in the artefact this app can obtain. Open VSX serves `ms-python.python`
 * only as a `universal` VSIX, packaged without a target, and that recipe never
 * compiles the locator. Measured on the bundled 2026.4.0 tree, which is also
 * the newest the registry serves: no `python-env-tools` directory, and no ELF
 * file of any kind. Nothing chosen at download time can change that.
 *
 * With `python.locator` on `native` the extension therefore spawns a path that
 * does not exist, warns "Python Locator failed to start" with a Do Not Show
 * Again button, and lists no interpreters (issue #241). Silencing that button
 * is what must not be mistaken for a fix.
 *
 * `python.useEnvironmentsExtension` is the second key because the extension
 * tests it FIRST, in `initialize`, and a true value hands discovery to
 * `ms-python.vscode-python-envs` before the locator gate is ever read. That
 * extension is installable from Open VSX (it is in this one's extension pack)
 * and walks into the same wall: its 1.36.0 universal VSIX names
 * `python-env-tools/bin/pet` in its bundle, ships no such file, and falls back
 * to looking for it inside the Python extension. Pinning only the locator would
 * leave that branch reaching the same missing binary.
 *
 * Both default to the safe value already, so what this covers is a document
 * where something else has moved them: both carry the `onExP` tag, so the value
 * in force is not only the user's to set. A hand edit back is undone at the
 * next launch, and that is the intent rather than a side effect, since there is
 * nothing on the device for either path to spawn.
 *
 * Shipping a per-platform VSIX would not lift this either. The extension tree
 * lives under `filesDir`, which SELinux refuses to `execve`, the same wall that
 * makes the Claude Code CLI run through musl's loader.
 */
private val PYTHON_LOCATOR = SettingPin("python.locator", """"[^"]*"""", "\"js\"")
private val PYTHON_ENV_EXTENSION =
    SettingPin("python.useEnvironmentsExtension", """(?:true|false)""", "false")

/**
 * A setting this app holds at [value] whatever the document says.
 *
 * [valuePattern] is the value shape that can be rewritten in place. A key
 * carrying anything else is left exactly as it stands rather than gaining a
 * second copy of itself further up, which is the distinction
 * [CLAUDE_WRAPPER_KEY] draws for the same reason.
 *
 * Held rather than merely inserted when absent, which is where this parts
 * company with [VERIFY_SIGNATURE]. Signature verification back on is a working
 * configuration and so a decision worth keeping; a locator pointed at a binary
 * that is not in the artefact has no working configuration to keep.
 */
private class SettingPin(val key: String, valuePattern: String, val value: String) {
    private val quotedKey = "\"" + key.replace(".", "\\.") + "\""
    private val managed = Regex("""($quotedKey\s*:\s*)$valuePattern""")
    private val present = Regex("""$quotedKey\s*:""")

    fun applyTo(content: String): String = when {
        managed.containsMatchIn(content) ->
            managed.replace(content) { "${it.groupValues[1]}$value" }
        present.containsMatchIn(content) -> content
        else -> insertSetting(content, key, value)
    }
}

/**
 * The first property in the document, with the indentation it sits at.
 *
 * Anchored to the opening brace so it cannot match a property nested inside some
 * other object further down, which would put the inserted line in the wrong scope.
 */
private val FIRST_PROPERTY = Regex("""(?<=\{)\s*\n([ \t]*)(?=")""")

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
 * Extension identifiers this project used to bundle and has stopped bundling.
 *
 * GitLens is here because it was bundled, it no longer is, and what earlier
 * releases unpacked is still on those devices -- roughly 22 MB that nothing
 * else will ever remove. `b73f558` stopped the BUNDLE, which is a different
 * thing from clearing what the bundle already installed.
 */
private val RETIRED_FETCHED_IDS = listOf("eamodio.gitlens")

/**
 * The third retirement case: an extension fetched from the marketplace that
 * this build no longer bundles, at whatever version it happens to be.
 *
 * Four sweeps now read the same directory and a reader has to know which one
 * claims a given name, so state it once:
 *
 *   [bundledDirsToExtract]      what to unpack -- ours always, theirs if absent
 *   [supersededExtensionDirs]   older versions of an id STILL bundled
 *   [retiredOwnExtensionDirs]   our own publisher, id no longer bundled
 *   this one                    a fetched id no longer bundled at all
 *
 * The first three all derive their answer from what IS bundled. This one
 * cannot: nothing in the current build mentions the extension, which is exactly
 * what makes it invisible to the other two, so the identifier is carried
 * explicitly.
 *
 * Matched on the identifier, not the version, so it does not matter which
 * release a device installed -- and the pin moved four times before removal, so
 * keying on a version would have cleared some devices and not others. The
 * directory is `id-version`, so the test is the id itself or the id followed by
 * a hyphen; a bare prefix would also claim a different extension whose name
 * merely begins the same way.
 */
internal fun retiredFetchedExtensionDirs(
    present: List<String>,
    retiredIds: List<String> = RETIRED_FETCHED_IDS,
): List<String> = present.filter { name ->
    retiredIds.any { id -> name == id || name.startsWith("$id-") }
}

/**
 * Which retired ids still have a sweep owed to them.
 *
 * The sweep exists to clear what an earlier bundle left behind, which is a
 * one-time debt against a device. It was running on every app update instead,
 * and it cannot tell a leftover from a deliberate reinstall: both are a
 * directory named `eamodio.gitlens-<version>`. So a user who liked the
 * extension, saw it vanish, and installed it again from the marketplace lost it
 * again on the next update, and every update after that.
 *
 * Recording the ids already swept turns it back into what it was meant to be.
 * A device that has swept before will sweep once more, because nothing recorded
 * the earlier runs and nothing can reconstruct them; after that it stops.
 */
internal fun retiredIdsToSweep(
    alreadySwept: Set<String>,
    retiredIds: List<String> = RETIRED_FETCHED_IDS,
): List<String> = retiredIds.filterNot { it in alreadySwept }

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
 * How much of `filesDir` the toolchains named in `toolchains.json` occupy.
 *
 * A withdrawn toolchain is still counted. `ToolchainRegistry.find` answers null
 * for one, and reading a size through it alone therefore returns 0 for the
 * toolchains most likely to be sitting on a device right now: the ones
 * installed before the withdrawal. The pre-flight subtracts this figure from
 * what it credits as reusable, so a 0 credits space that is occupied, passes a
 * device that should have been refused, and the extraction it admits runs out
 * of disk partway with the toolchain still where it was.
 *
 * The retired lookup strips the prefix itself rather than going through
 * [toolchainShortName], which resolves through the registry and so returns a
 * retired name unchanged by design -- the pass-through that keeps an uninstall
 * working after a row is dropped. `toolchains.json` records the short form
 * today (see the manifests written by `download-ruby.sh` and `download-java.sh`)
 * and this reads either, because the cost of guessing wrong is a toolchain that
 * silently counts as nothing.
 *
 * An unrecognised name contributes nothing, which is the same answer as before
 * for a record this build has never heard of.
 */
internal fun toolchainBytesFor(names: List<String>): Long = names.sumOf { name ->
    ToolchainRegistry.find(name)?.estimatedSize
        ?: RETIRED_TOOLCHAINS[name.removePrefix("toolchain_")]
        ?: 0L
}

/**
 * Whether the setup recorded on this device belongs to a build other than the
 * one now running, and therefore has to be redone.
 *
 * Both halves of the identity are compared, and the versionCode is the half
 * that carries the guarantee. A versionName is a label a build declares about
 * itself and nothing stops two builds declaring the same one: 1.1.0 was
 * published under versionCode 11 and then again under 12. Comparing the label
 * alone, which is what this did, answers "same build" for those two -- so a
 * device holding the first takes the second, skips setup entirely, and keeps
 * running the older server tree under the newer app. Nothing reports it,
 * because from the app's side setup completed; it completed for a different
 * build.
 *
 * Play refuses an upload whose versionCode is not greater than the last, so the
 * code cannot repeat where the name can. Comparing both means the name stays
 * free to be whatever a release wants to call itself.
 *
 * A stored code of 0 is the value getInt returns when the
 * key was never written, which is any install predating the code being
 * recorded. Those are treated as stale, which redoes an extraction that is
 * idempotent, rather than trusting a record that was never made.
 *
 * Pure, and takes the four values rather than a Context, so the decision can be
 * pinned by a unit test; the reads themselves are one call each and have no
 * branch to get wrong.
 */
internal fun setupIsStale(
    storedName: String?,
    storedCode: Int,
    currentName: String,
    currentCode: Int,
): Boolean = storedName != currentName || storedCode != currentCode

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
 * That deterministic name is also why the body is serialised. Two threads can
 * reach here at once: `runSetupLocked` runs on Dispatchers.IO while
 * SplashActivity's per-launch repairs run on the main thread under no lock, and
 * both write `.bashrc` and `settings.json`. Sharing one temp path they open the
 * same file, interleave into it, and the first to finish renames it onto the
 * destination -- after which the loser is still writing through a descriptor
 * that now points AT the destination, and its own rename fails, so it reports
 * "unchanged" having just corrupted the file it claims not to have touched.
 * Writing straight to the destination, which is what this replaced, raced too;
 * what this shape adds is the ability to report success while doing it.
 *
 * @return true if [dest] now holds what [write] produced. On false, [dest] is
 *   untouched -- it keeps its previous contents, or stays absent.
 */
internal fun writeAtomically(dest: File, write: (FileOutputStream) -> Unit): Boolean =
    synchronized(ATOMIC_WRITE_LOCK) {
        val tmp = File(dest.parentFile, "${dest.name}.tmp~")
        try {
            FileOutputStream(tmp).use(write)
        } catch (e: IOException) {
            tmp.delete()
            return@synchronized false
        }
        if (!tmp.renameTo(dest)) {
            tmp.delete()
            return@synchronized false
        }
        true
    }

/**
 * PEM-encodes one certificate, in the shape the concatenated bundle is made of.
 *
 * The files under the system trust store are already PEM, so this exists only
 * for the user-installed half, which arrives as parsed [Certificate] objects and
 * has to be turned back into text before it can be appended. MIME encoding with
 * a 64-character line length and a bare newline separator gives it the shape
 * every other entry in the file already has, since the system store's files are
 * copied through byte for byte and OpenSSL wrote them at 64 columns. Whether
 * the reader on the far side insists on the breaks is untested here and beside
 * the point: matching the rest of the file costs one encoder argument, and a
 * single unbroken run of base64 in the middle of a bundle is a difference with
 * nothing to gain from it.
 *
 * `java.util.Base64` rather than `android.util.Base64` so the encoding is the
 * same object in a unit test as it is on the device. minSdk is 33, well past
 * the 26 that added it.
 */
private fun pemOf(cert: Certificate): String =
    "-----BEGIN CERTIFICATE-----\n" +
        Base64.getMimeEncoder(64, byteArrayOf('\n'.code.toByte())).encodeToString(cert.encoded) +
        "\n-----END CERTIFICATE-----\n"

/** SHA-256 of [text] as lower-case hex, for the user-CA freshness marker. */
private fun sha256HexOf(text: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray())
        .joinToString("") { "%02x".format(it) }

/**
 * One lock for every atomic write, not one per destination.
 *
 * Concurrent writes to *different* destinations do not happen here -- the
 * contending pair is two threads racing for the same two files -- so per-path
 * locks would buy nothing and cost a map that grows by an entry for each of the
 * 3787 files an extraction writes.
 */
private val ATOMIC_WRITE_LOCK = Any()

/**
 * How many bytes of the asset tree are already unpacked under [root], for the
 * storage pre-flight to subtract from what it asks for.
 *
 * Only `server/` is passed in, though extraction also fills `usr/` and the
 * bundled extensions, and the asymmetry is the design rather than an omission.
 * `server/` is 700 of the tree's 810 MiB and nothing but extraction writes
 * there, so every byte counted is a byte the next unpack genuinely writes over.
 * The other two are shared ground: toolchains install into `usr/`, Java is
 * 146 MB unpacked, `npm install -g` lands there too, and
 * `home/.vscodroid/extensions` fills with whatever the user takes from the
 * gallery. Crediting those would subtract bytes that overwriting does not give
 * back, and that failure is the worse one: the gate passes, extraction runs out
 * of disk partway, and the user is told "Setup failed" rather than how much to
 * free, on every retry, because the toolchains stay where they are. Charging
 * their asset size in full instead over-states the requirement, which costs a
 * user on a tight device one round of freeing space they did not strictly need
 * to free.
 *
 * Symlinks are skipped rather than followed, and that is not tidiness. The
 * Copilot alias farm links every entry of `copilot-linux-arm64`, including the
 * 113 MiB `runtime.node`, the largest file in the tree, and the extension side
 * links a whole `sdk` directory holding another 96 MiB. Following those counts
 * the same bytes twice and credits the install for space that does not exist,
 * which is the direction that lets the gate pass a device it should refuse.
 *
 * Asks `Files.isSymbolicLink` rather than [isSymlink], which is the same
 * question, both are `lstat` on the final component, and `SymlinkPredicateTest`
 * already leans on that equivalence, put through the JDK instead of
 * `android.system.Os`. The difference is only that `Os.lstat` throws in a JVM
 * unit test and [isSymlink] catches it into "not a link", so the skipping this
 * walk depends on could be asserted but never measured.
 *
 * A `root` that does not exist walks to nothing and answers 0, which is the
 * fresh-install case.
 */
internal fun installedExtractionBytes(root: File): Long =
    root.walkTopDown()
        .onEnter { !Files.isSymbolicLink(it.toPath()) }
        .filter { it.isFile && !Files.isSymbolicLink(it.toPath()) }
        .sumOf { it.length() }

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

/**
 * Names the extension directories left behind by an earlier bundled version.
 *
 * Bundled extensions are extracted to `publisher.name-version` directories, so
 * bumping a version extracts a new directory beside the old one. The scanner
 * shows only what `extensions.json` (the default profile's manifest) lists,
 * not what sits on disk, so the deletion here is half of the swap: it is what
 * lets reconcileExtensionsManifest drop the old entry and list the new version
 * in its place. The other stake is disk, which never comes back on its own:
 * the Python extension alone is 29 MB, kept for as long as the app is
 * installed. (An earlier version of this comment claimed the scanner discovers
 * extensions by listing directories; the manifest is what it reads.)
 *
 * Only strictly older copies are named. A user who installed a newer build of the
 * same extension from the marketplace keeps it: that is their copy, and the
 * scanner already prefers it. A version that is not purely numeric is left alone
 * rather than guessed at.
 */
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

/**
 * Reconciles the settings.json values this app manages, returning the updated
 * document or `null` when nothing needed changing.
 *
 * Two of the jobs are about paths. `git.path` still embeds `nativeLibraryDir`,
 * which a reinstall moves, so it is re-pointed whenever it has gone stale. The
 * terminal profile is instead migrated *off* it and onto `usr/bin/bash`, which
 * `setupToolSymlinks()` already repairs on every launch, and after that move the
 * pattern no longer matches and the profile never goes stale again.
 *
 * The move carries the other two halves of the shell-integration fix with it,
 * because all three were written by the same release. Bundling them keeps the
 * migration one-shot: once the path is off `/data/app/`, nothing here fires
 * again, so a user who later turns shell integration back off keeps it off.
 *
 * The remaining values are not migrations. They are inserted when absent rather
 * than only refreshed, so they reach installs made before the setting existed:
 * `claudeCode.claudeProcessWrapper`, itself a `nativeLibraryDir` path and so
 * refreshed too, but left alone when it points somewhere the user chose;
 * `extensions.verifySignature`; and the two Python pins [PYTHON_LOCATOR] and
 * [PYTHON_ENV_EXTENSION].
 *
 * Substitutes values in place and leaves every other byte untouched.
 * settings.json is JSONC: comments and trailing commas are legal there, so
 * parsing the document to re-serialise it would strip the user's comments,
 * escape every slash, and turn `["-i",]` into `["-i", null]`.
 *
 * A pattern that does not match changes nothing, so a file the user has
 * restructured is left as they wrote it rather than mangled.
 */
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

    // Reaches installs that already have a settings.json, which is every device
    // the failure was reported from: createDefaultSettings() writes only when
    // the file is absent, so on its own it would fix nobody who already has one.
    updated = PYTHON_LOCATOR.applyTo(updated)
    updated = PYTHON_ENV_EXTENSION.applyTo(updated)

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
